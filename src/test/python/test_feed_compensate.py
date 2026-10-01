# -*- coding: utf-8 -*-
"""
test_feed_compensate.py - 投递缓冲补偿端到端留证（feed3-T33-B）

背景（feed3-T33-B 落地 `mq.MqDeliveryBuffer`）：MQ 不可用时**确定未投出**的消息
（`publish()` 走到 `ensureConnected() == false` 分支）暂存到有界内存缓冲；连接恢复后**重放**
（rebuild / fanout / backfill 三类消息统一经过；消费侧幂等 ⇒ 重放零副作用）。

⚠️ 本文件**默认 skip**（它会停 / 起 rabbitmq 容器，不能进默认全量跑法）：

    TV_MQ_COMPENSATE_E2E=1 python tools\\tv.py test                                 # 跑全部
    TV_MQ_COMPENSATE_E2E=1 PYTEST_ADDOPTS='-k compensate' python tools\\tv.py test   # 只跑本文件

场景（同一作者 A + 粉丝 F，均为临时用户）：
  ① 前置哨兵：F 关注 A、A 发 c1 ⇒ 正常 fanout ⇒ **`feed_inbox` 出现 (F, c1) 行**（证明投递 / 消费链通）
  ② `docker stop rabbitmq` ⇒ MQ 不可用（**发布接口仍成功**——投递失败只降级，这本身也是留证点）
  ③ A 发 c2 ⇒ 投递被**暂存**（未投出）⇒ 等确认后断言 `feed_inbox` **无 (F, c2) 行**
  ④ `docker start rabbitmq` ⇒ 客户端 **automatic recovery**（不经过 `establish()`、**无 listener 回调**）
     ⇒ 补偿靠 **30s 定时惰性探测**（T33-B 主通道）触发重放
  ⑤ 轮询（上界 120s）断言 (F, c2) 行出现 ⇒ **恢复后可补偿**成立

**判据为什么用 DB 直查而不是 `/feed` 可见性**：全新粉丝的 `feed_inbox_sync` 可能尚无行 ⇒ 读侧走
"未同步 ⇒ 回退纯拉"，纯拉**立刻**就能看到 c2 ⇒ 用可见性做负向断言是假绿 / 假红（本文件首版即栽在
此：`assert c2 not in /feed` 在纯拉路径下必然失败）。直查 `feed_inbox` 行则只反映 push 平面的真实状态。
且该粉丝**此后无任何关注 / 取关动作** ⇒ c2 行的唯一来源只能是**补偿重放**（不会被重建顺带写入）。

数据自建自清：`testA_fcompensate_*` 用户（命中 tools/cleanup_data.py 白名单），内容在 finally 里软删；
无论成败都会尽力把 rabbitmq 容器恢复为运行态。

前置守卫（不满足即 skip）：未设 `TV_MQ_COMPENSATE_E2E=1` / docker 不可用 / rabbitmq 容器不存在 /
mysql 客户端不可用或测试库不可达（DB 是本文件的判据通道）/ `FEED_BIGV_THRESHOLD <= 1` 或设置了
`FEED_BIGV_CONFIGFILE`（被关注作者会被判大V ⇒ 内容不进收件箱，前提不成立，避免 U-35 同族假失败）。
"""

import os
import shutil
import subprocess
import time
import uuid
from pathlib import Path

import pytest
import requests

import conftest

_PROJECT_ROOT = Path(__file__).resolve().parents[3]

RABBITMQ_CONTAINER = "rabbitmq"

# 收敛窗口：恢复后补偿上界 = 探测周期（30s）+ 投递 + 消费 ⇒ 120s 只吸收抖动
POLL_TIMEOUT_SECONDS = 120.0
POLL_INTERVAL_SECONDS = 0.5
# MQ 停止后的确认等待：让"投递失败 ⇒ 暂存"与"消费不发生"都可见
ABSENCE_CONFIRM_SECONDS = 3.0

_MYSQL_CANDIDATES = (
    r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
)


# ---------------------------------------------------------------------------
# 前置守卫
# ---------------------------------------------------------------------------

def _require_e2e_enabled():
    if os.environ.get("TV_MQ_COMPENSATE_E2E") != "1":
        pytest.skip("未设 TV_MQ_COMPENSATE_E2E=1（本用例会停/起 rabbitmq 容器，不进默认跑法）")


def _require_docker():
    if shutil.which("docker") is None:
        pytest.skip("docker 不可用，无法停/起 rabbitmq 制造 MQ 故障")


def _require_rabbitmq_container():
    probe = _docker("inspect", "-f", "{{.State.Status}}", RABBITMQ_CONTAINER, timeout=30)
    if probe.returncode != 0:
        pytest.skip(f"容器 {RABBITMQ_CONTAINER} 不存在，无法制造 MQ 故障")


def _require_mysql():
    if _mysql_path() is None:
        pytest.skip("mysql 客户端不可用，无法直查 feed_inbox 真相行")
    try:
        _run_sql("SELECT 1")
    except Exception as exc:      # noqa: BLE001 - 判据通道不可用一律 skip
        pytest.skip(f"测试库不可达，无法直查 feed_inbox 真相行: {exc}")


def _require_inbox_leg_available():
    threshold = (os.environ.get("FEED_BIGV_THRESHOLD") or "").strip()
    if threshold.isdigit() and int(threshold) <= 1:
        pytest.skip("FEED_BIGV_THRESHOLD<=1：作者会被判为大V、内容不进收件箱（非默认跑法）")
    if (os.environ.get("FEED_BIGV_CONFIGFILE") or "").strip():
        pytest.skip("FEED_BIGV_CONFIGFILE 已设置：阈值由外置文件决定、不可预判")


# ---------------------------------------------------------------------------
# docker 辅助
# ---------------------------------------------------------------------------

def _docker(*args, timeout=60):
    return subprocess.run(
        ["docker", *[str(a) for a in args]],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout,
    )


# ---------------------------------------------------------------------------
# MySQL 只读辅助（独立 oracle：直查 push 平面真相行，绕开读侧"未同步 ⇒ 回退纯拉"的歧义）
# ---------------------------------------------------------------------------

def _mysql_path():
    found = shutil.which("mysql")
    if found:
        return Path(found)
    for candidate in _MYSQL_CANDIDATES:
        if Path(candidate).exists():
            return Path(candidate)
    return None


def _run_sql(sql):
    """直连测试库执行**只读** SQL（连接参数由 run_tests.py 注入的 DB_* 给出）。"""
    cmd = [
        str(_mysql_path()),
        "--user=" + os.environ.get("DB_USER", "root"),
        "--password=" + os.environ.get("DB_PASSWORD", "ROOT123"),
        "--host=" + os.environ.get("DB_HOST", "127.0.0.1"),
        "--port=" + os.environ.get("DB_PORT", "3307"),
        "--database=" + os.environ.get("DB_NAME", "TVDatabase_test"),
        "--batch",
        "--skip-column-names",
        "--default-character-set=utf8mb4",
        "--execute",
        sql,
    ]
    proc = subprocess.run(
        cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60
    )
    if proc.returncode != 0:
        raise RuntimeError("mysql 执行失败: " + (proc.stderr.strip() or proc.stdout.strip()))
    return proc.stdout.strip()


def _inbox_rows(fan_id, content_id):
    """`feed_inbox` 中 (fan, content) 的真实行数（唯一键 `uk_user_content` ⇒ 0 或 1）。"""
    out = _run_sql(f"SELECT COUNT(*) FROM feed_inbox WHERE user_id = {int(fan_id)} "
                   f"AND content_id = {int(content_id)}")
    return int(out)


def _await_inbox_expectation(fan_id, content_id, timeout_seconds, expect_present):
    """轮询直到 (fan, content) 行达到期望状态（有界）。"""
    deadline = time.time() + timeout_seconds
    target = 1 if expect_present else 0
    while True:
        if _inbox_rows(fan_id, content_id) == target:
            return True
        if time.time() >= deadline:
            return False
        time.sleep(POLL_INTERVAL_SECONDS)


# ---------------------------------------------------------------------------
# HTTP 辅助
# ---------------------------------------------------------------------------

def _register_fresh_user(tag):
    unique = uuid.uuid4().hex[:8]
    suffix = str(int(unique, 16))[-8:].zfill(8)
    username = f"testA_fcompensate_{tag}_{unique}"
    body = conftest.register_user(username, f"136{suffix}", "abc123")
    assert body.get("code") == 200, f"注册临时用户失败: {body}"
    data = body.get("data") or {}
    return {"id": data.get("id"), "token": data.get("token"), "username": username}


def _follow(base_url, token, followed_user_id):
    resp = requests.post(
        f"{base_url}/follow/add",
        headers={"token": token, "Content-Type": "application/x-www-form-urlencoded"},
        data={"followedUserId": str(followed_user_id)},
        timeout=10,
    )
    return resp.json()


def _post_content(base_url, token, title, test_files):
    with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
        resp = requests.post(
            f"{base_url}/api/upload/post",
            headers={"token": token},
            data={"title": title, "description": "feed3-T33-B 补偿留证", "categoryId": "0"},
            files={
                "cover": ("test_cover.png", cf, "image/png"),
                "image": ("test_image.jpg", imf, "image/jpeg"),
            },
            timeout=30,
        )
    result = resp.json()
    assert result.get("code") == 200, f"图文上传失败（MQ 故障期发布接口应仍成功）: {result}"
    return result["data"]["contentId"]


def _delete_content(base_url, token, content_id):
    return requests.post(
        f"{base_url}/content/delete",
        params={"contentId": content_id},
        headers={"token": token},
        timeout=10,
    ).json()


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------

def test_mq_outage_gap_is_compensated_after_recovery(base_url, test_files):
    _require_e2e_enabled()
    _require_docker()
    _require_rabbitmq_container()
    _require_mysql()
    _require_inbox_leg_available()

    author = _register_fresh_user("author")
    fan = _register_fresh_user("fan")
    content_ids = []
    stopped = False
    try:
        assert _follow(base_url, fan["token"], author["id"]).get("code") == 200, "关注失败"

        # ① 前置哨兵：正常 fanout 落库（证明投递 / 消费链通，且该粉丝的收件箱腿在工作）
        c1 = _post_content(base_url, author["token"], f"compensate_ok_{uuid.uuid4().hex[:6]}", test_files)
        content_ids.append(c1)
        assert _await_inbox_expectation(fan["id"], c1, 30.0, expect_present=True), \
            "哨兵失败：MQ 正常时 (fan, c1) 应落进 feed_inbox（否则本用例前提不成立）"

        # ② 制造 MQ 故障
        stop = _docker("stop", RABBITMQ_CONTAINER)
        assert stop.returncode == 0, f"docker stop {RABBITMQ_CONTAINER} 失败: {stop.stderr}"
        stopped = True
        time.sleep(1.0)   # 等客户端把连接标记为不可用

        # ③ 故障期发布：接口仍成功（投递只降级），消息被暂存 ⇒ push 平面无行
        c2 = _post_content(base_url, author["token"], f"compensate_gap_{uuid.uuid4().hex[:6]}", test_files)
        content_ids.append(c2)

        time.sleep(ABSENCE_CONFIRM_SECONDS)
        assert _inbox_rows(fan["id"], c2) == 0, \
            "MQ 故障期发布的内容不应落进收件箱（缺口 = 待补偿；确认等待后仍应为 0）"

        # ④ 恢复（客户端自动恢复 + 30s 定时探测 ⇒ 触发重放）
        start = _docker("start", RABBITMQ_CONTAINER)
        assert start.returncode == 0, f"docker start {RABBITMQ_CONTAINER} 失败: {start.stderr}"
        stopped = False

        # ⑤ 补偿成立：该粉丝此后无任何关注 / 取关动作 ⇒ (fan, c2) 行的唯一来源是补偿重放
        assert _await_inbox_expectation(fan["id"], c2, POLL_TIMEOUT_SECONDS, expect_present=True), \
            "恢复后未补偿：缺口内容应经缓冲重放落进粉丝收件箱（上界 = 探测周期 30s + 投递 + 消费）"
    finally:
        if stopped:
            _docker("start", RABBITMQ_CONTAINER)   # 尽力恢复容器运行态
        for content_id in content_ids:
            try:
                _delete_content(base_url, author["token"], content_id)
            except Exception:   # noqa: BLE001 —— 清理兜底，不掩盖主断言
                pass
