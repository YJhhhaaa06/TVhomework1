# -*- coding: utf-8 -*-
"""
test_bigv_downgrade_backfill.py - 降级补推端到端留证（feed3-T28-B）

背景（feed3-T28-A 落地滞回判定 + feed3-T28-B 落地降级补推）：
  - 大V期间其内容**不进粉丝收件箱**（fanout 早退，由"大V发件箱腿"读时拉）；
  - 作者由大V降为普通（滞回 edge = `auto_bigv` 状态行被删、affected==1）⇒ 提交后投
    `feed.push.backfill` 消息 ⇒ 消费者把该作者最近 K 条内容补写进**现任粉丝**收件箱
    （`INSERT IGNORE`，零删除、幂等）。

本文件证的就是：**降级后，存量内容在粉丝窗口可见，且不依赖该粉丝的下一次关注 / 取关**。

场景编排（全程同一作者 A + 粉丝 F1 / F2，阈值经外置文件临时调小为 2、系数 0.8）：
  ① F1 关注 A（A 计数 1 < 2，非大V）⇒ F1 收件箱就绪；
  ② A 发 c1 ⇒ 非大V ⇒ fanout 落 (F1, c1)（正向控制：普通作者走收件箱）；
  ③ F2 关注 A（计数 2 ≥ 2）⇒ 滞回**升级**（auto_bigv 入表）；
  ④ 哨兵 S 先发一条并确认落库 ⇒ 证明"此刻"消费链通（负向断言的假绿防护）；
  ⑤ A 发 c2 ⇒ 大V ⇒ **不落** (F1, c2)，但 `/feed` 对 F1 **仍可见**（发件箱腿）——判定确实翻转；
  ⑥ F2 取关 A（计数 1 < 1.6）⇒ 滞回**降级**（auto_bigv 删行）⇒ 触发降级补推；
  ⑦ 断言（F1 全程**零动作**）：(F1, c2) 由补推落库、恰一行、且 F1 原有行**零删除**；
     `/feed` 对 F1 亦可见 c2（此时 A 已非大V、发件箱腿不再覆盖 ⇒ 只可能来自收件箱腿）。

跑法（须显式给出配置文件路径；未给 ⇒ 本文件整体 skip）：
  FEED_BIGV_CONFIGFILE=<可写路径> python tools\\tv.py test
  可选 FEED_BIGV_REFRESHMILLIS=<毫秒> 缩小节流窗口以加速（不设则按配置等满窗口；
  名与 AppConfig 的 key->env 推导一致——T27-B 原拼写 `…MILIS` 少一个 L、应用侧不生效，已随 T28-B 修正）。

跳过口径：
  - 未设 `FEED_BIGV_CONFIGFILE` ⇒ skip（**默认跑法行为与 T28-B 之前完全一致**，本用例是留证手段，
    不是默认能力）；
  - MySQL 通道不可用 ⇒ skip（直查库是手段，不是被测能力）；
  - docker / Redis 不可用**不影响本文件**（本文件不读 Redis）。

数据自建自清：注册全新 `testA_fbigv_backfill_*` 临时用户（命中 tools/cleanup_data.py 白名单），
内容在 finally 里由作者走真实删除路径软删；配置文件恢复为默认（等价"未启用外置覆盖"）。
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

# 项目根（读 app.properties 取同源默认值）
_PROJECT_ROOT = Path(__file__).resolve().parents[3]

CONFIG_FILE_ENV = "FEED_BIGV_CONFIGFILE"
REFRESH_ENV = "FEED_BIGV_REFRESHMILLIS"   # 与 AppConfig 的 key->env 推导一致（旧拼写少一个 L、应用侧不生效；T28-B 修正）

# app.properties 默认值兜底（与 AppConfig 的带默认值读取同源）
_DEFAULT_BIGV_THRESHOLD = 10000
_DEFAULT_DOWNGRADE_RATIO = 0.8
_DEFAULT_REFRESH_MILLIS = 5000

# 本用例的小阈值编排：升级线 = 2、降级线 = 0.8 × 2 = 1.6
# （A 计数 2 ⇒ 入表；F2 取关后计数 1 ⇒ 跌破降级线、删行 = edge）
_SCENARIO_THRESHOLD = 2

# 收敛窗口：本地 MQ 投递 + 消费 + 落库在毫秒级完成
POLL_TIMEOUT_SECONDS = 10.0
POLL_INTERVAL_SECONDS = 0.2
# 大V分支的"无行"是负向断言 ⇒ 需要一段"确认等待"（正常 fanout 远快于此）
ABSENCE_CONFIRM_SECONDS = 2.0

_MYSQL_CANDIDATES = (
    r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
)


# ---------------------------------------------------------------------------
# 环境：配置文件路径与节流窗口
# ---------------------------------------------------------------------------

def _config_file():
    """本文件端到端留证所依赖的外置配置文件路径（未配 ⇒ skip）。"""
    raw = os.environ.get(CONFIG_FILE_ENV)
    if not raw:
        pytest.skip(
            f"未设置 {CONFIG_FILE_ENV} ⇒ 外置热更未启用（默认跑法行为与 T28-B 之前一致）；"
            f"要跑本留证请显式指定：{CONFIG_FILE_ENV}=<可写路径> python tools/tv.py test"
        )
    return Path(raw)


def _app_prop(key, default):
    """按 app.properties 读取配置（缺键 → 默认值，与 AppConfig 的带默认值读取同源）。"""
    props = _PROJECT_ROOT / "src" / "main" / "resources" / "app.properties"
    for line in props.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith(key + "="):
            value = stripped.split("=", 1)[1].strip()
            return value if value else default
    return default


def _refresh_millis():
    """节流窗口（毫秒）：env 优先，否则 app.properties，与应用同源。"""
    env = os.environ.get(REFRESH_ENV)
    if env:
        return int(env)
    return int(_app_prop("feed.bigv.refreshMillis", _DEFAULT_REFRESH_MILLIS))


def _wait_reload_window():
    """等待"改文件 → 单点重读"的节流窗口过去（窗口即生效延迟上界）。"""
    time.sleep(_refresh_millis() / 1000.0 + 1.0)


def _write_config(path, threshold, listed_ids, downgrade_ratio):
    """**原子写**配置（临时文件 + os.replace）——与应用侧"读全文件 + 整批换入"的口径配套。

    显式写全三个判定输入（阈值 / 名单 / 系数）：本用例的升降级数学（2 / 1.6）自包含，
    不依赖 app.properties 现值或环境变量。
    """
    lines = [
        "# feed3-T28-B 降级补推端到端留证用清单；键名与 app.properties 的 feed.bigv.* 一致\n",
        f"feed.bigv.threshold={threshold}\n",
        "feed.bigv.userIds=" + ",".join(str(i) for i in listed_ids) + "\n",
        f"feed.bigv.downgradeRatio={downgrade_ratio}\n",
    ]
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text("".join(lines), encoding="utf-8")
    os.replace(tmp, path)


# ---------------------------------------------------------------------------
# HTTP 辅助（各测试文件自带 helper，沿 test_bigv_hot_reload.py 既有风格）
# ---------------------------------------------------------------------------

def _register_fresh_user(tag):
    unique = uuid.uuid4().hex[:8]
    suffix = str(int(unique, 16))[-8:].zfill(8)
    username = f"testA_fbigv_backfill_{tag}_{unique}"
    body = conftest.register_user(username, f"138{suffix}", "abc123")
    assert body.get("code") == 200, f"注册临时用户失败: {body}"
    data = body.get("data") or {}
    return {"id": data.get("id"), "token": data.get("token"), "username": username}


def _follow(base_url, token, followed_user_id):
    return requests.post(
        f"{base_url}/follow/add",
        headers={"token": token, "Content-Type": "application/x-www-form-urlencoded"},
        data={"followedUserId": str(followed_user_id)},
        timeout=10,
    ).json()


def _unfollow(base_url, token, followed_user_id):
    return requests.post(
        f"{base_url}/follow/remove",
        headers={"token": token, "Content-Type": "application/x-www-form-urlencoded"},
        data={"followedUserId": str(followed_user_id)},
        timeout=10,
    ).json()


def _post_content(base_url, token, title, test_files):
    with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
        resp = requests.post(
            f"{base_url}/api/upload/post",
            headers={"token": token},
            data={"title": title, "description": "feed3-T28-B 降级补推 e2e", "categoryId": "0"},
            files={
                "cover": ("test_cover.png", cf, "image/png"),
                "image": ("test_image.jpg", imf, "image/jpeg"),
            },
            timeout=30,
        )
    result = resp.json()
    assert result.get("code") == 200, f"图文上传失败: {result}"
    return result["data"]["contentId"]


def _delete_content(base_url, token, content_id):
    return requests.post(
        f"{base_url}/content/delete",
        params={"contentId": content_id},
        headers={"token": token},
        timeout=10,
    ).json()


def _feed_ids(base_url, token):
    resp = requests.get(
        f"{base_url}/feed",
        params={"page": 1, "pageSize": 100},
        headers={"token": token},
        timeout=10,
    )
    data = (resp.json().get("data") or {})
    return [it.get("id") for it in (data.get("list") or [])]


def _poll(predicate, timeout=POLL_TIMEOUT_SECONDS, interval=POLL_INTERVAL_SECONDS):
    deadline = time.monotonic() + timeout
    while True:
        value = predicate()
        if value or time.monotonic() >= deadline:
            return value
        time.sleep(interval)


# ---------------------------------------------------------------------------
# MySQL 只读辅助（真相断言：只发 SELECT）
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
        cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120
    )
    if proc.returncode != 0:
        raise RuntimeError("mysql 执行失败: " + (proc.stderr.strip() or proc.stdout.strip()))
    return proc.stdout.strip()


def _require_mysql():
    if _mysql_path() is None:
        pytest.skip("mysql 客户端不可用，无法断言 feed_inbox 真相")
    try:
        _run_sql("SELECT 1")
    except Exception as exc:      # noqa: BLE001 - 通道不可用一律 skip
        pytest.skip(f"测试库不可达，无法断言 feed_inbox 真相: {exc}")


def _inbox_has(fan_id, content_id):
    """粉丝收件箱真相表里是否有该行（独立 oracle：直连 MySQL，不经过应用读路径）。"""
    rows = _run_sql(
        f"SELECT COUNT(*) FROM feed_inbox WHERE user_id = {int(fan_id)} "
        f"AND content_id = {int(content_id)}"
    )
    return int(rows or "0")


def _inbox_count(fan_id):
    """该粉丝收件箱的**总行数**（断言"补推恰新增一行、零删除"）。"""
    rows = _run_sql(f"SELECT COUNT(*) FROM feed_inbox WHERE user_id = {int(fan_id)}")
    return int(rows or "0")


def _is_synced(fan_id):
    """读态闸门：feed_inbox_sync 存在即已同步（窗口重建完成）。"""
    rows = _run_sql(
        f"SELECT COUNT(*) FROM feed_inbox_sync WHERE user_id = {int(fan_id)}"
    )
    return int(rows or "0") == 1


def _auto_bigv_has(author_id):
    """自动大V状态表（滞回判定产物）是否含该作者行——升 / 降级 edge 的直接观测。"""
    rows = _run_sql(f"SELECT COUNT(*) FROM auto_bigv WHERE user_id = {int(author_id)}")
    return int(rows or "0") > 0


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------

def test_downgrade_backfill_makes_backlog_visible_without_fan_action(base_url, test_files):
    """大V→普通降级后，存量内容经补推落进仍关注者的收件箱（该粉丝全程零动作）。"""
    config_file = _config_file()
    _require_mysql()

    author = _register_fresh_user("author")     # 判定会先升后降的作者
    fan1 = _register_fresh_user("fan1")         # 全程跟随的粉丝（断言对象，零动作）
    fan2 = _register_fresh_user("fan2")         # 触发升级与降级的第二位粉丝
    probe = _register_fresh_user("probe")       # 哨兵作者：全程非大V
    created = []                                # [(token, content_id)]

    try:
        # ---- 阶段 0：临时把阈值调到 2（系数 0.8 ⇒ 降级线 1.6），并等带宽过去 ----
        _write_config(config_file, _SCENARIO_THRESHOLD, [], _DEFAULT_DOWNGRADE_RATIO)
        _wait_reload_window()

        # ---- 阶段 ①：F1 关注 A（计数 1 < 2，非大V）⇒ F1 收件箱就绪 ----
        assert _follow(base_url, fan1["token"], author["id"]).get("code") == 200, "F1 关注失败"
        assert _poll(lambda: _is_synced(fan1["id"])), "F1 窗口重建未在超时内完成（feed_inbox_sync 无行）"
        # F1 同时关注哨兵作者（供阶段 ④ 走同一条 fanout 管道）
        assert _follow(base_url, fan1["token"], probe["id"]).get("code") == 200, "F1 关注哨兵失败"
        assert _poll(lambda: _is_synced(fan1["id"])), "哨兵触发的窗口重建未在超时内完成"

        # ---- 阶段 ②：A 发 c1 ⇒ 非大V ⇒ fanout 落 (F1, c1) ----
        c1 = _post_content(base_url, author["token"], "pytest_bigv_backfill_1", test_files)
        created.append((author["token"], c1))
        assert _poll(lambda: _inbox_has(fan1["id"], c1) > 0), (
            "阈值 2 下 A 计数 1 ⇒ 非大V ⇒ c1 应进 F1 收件箱（fanout 未落库）"
        )

        # ---- 阶段 ③：F2 关注 A（计数 2 ≥ 2）⇒ 滞回**升级**（auto_bigv 入表） ----
        assert _follow(base_url, fan2["token"], author["id"]).get("code") == 200, "F2 关注失败"
        assert _poll(lambda: _auto_bigv_has(author["id"])), (
            "计数 2 ≥ 阈值 2 ⇒ 应发生升级 edge（auto_bigv 未见作者行）"
        )

        # ---- 阶段 ④：哨兵发一条并确认落库 ⇒ 证明"此刻"消费链通（防负向断言假绿） ----
        c_sentinel = _post_content(base_url, probe["token"], "pytest_bigv_backfill_sentinel", test_files)
        created.append((probe["token"], c_sentinel))
        assert _poll(lambda: _inbox_has(fan1["id"], c_sentinel) > 0), (
            "哨兵失败：同刻普通作者的内容都未落库 ⇒ fanout 消费链异常，"
            "下阶段 A 的'无行'不能作为大V判定结论"
        )

        # ---- 阶段 ⑤：A 发 c2 ⇒ 大V ⇒ 不落 (F1, c2)，但 /feed 对 F1 仍可见（发件箱腿） ----
        c2 = _post_content(base_url, author["token"], "pytest_bigv_backfill_2", test_files)
        created.append((author["token"], c2))
        time.sleep(ABSENCE_CONFIRM_SECONDS)          # 负向断言：给它足够时间"本该落库却没落"
        assert _inbox_has(fan1["id"], c2) == 0, (
            "A 已是大V（auto_bigv 命中）⇒ c2 不得写入 F1 收件箱"
            "（同刻哨兵已证明消费链正常，故排除'消费慢'）"
        )
        assert c2 in _feed_ids(base_url, fan1["token"]), (
            "大V内容应仍对粉丝可见（来自大V发件箱腿）——否则说明是发布失败而非判定生效"
        )
        # 记录降级前的 F1 收件箱行数（断言"补推恰新增一行、零删除"的基线）
        fan1_rows_before = _inbox_count(fan1["id"])

        # ---- 阶段 ⑥：F2 取关 A（计数 1 < 1.6）⇒ 滞回**降级** ⇒ 触发降级补推 ----
        assert _unfollow(base_url, fan2["token"], author["id"]).get("code") == 200, "F2 取关失败"
        assert _poll(lambda: not _auto_bigv_has(author["id"])), (
            "计数 1 < 1.6 ⇒ 应发生降级 edge（auto_bigv 仍残留作者行）"
        )

        # ---- 阶段 ⑦：F1 零动作 ⇒ 补推把 c2 落进 F1 收件箱（零删除、恰一行、无重复） ----
        assert _poll(lambda: _inbox_has(fan1["id"], c2) > 0), (
            "降级后补推未在超时内把 c2 落进 F1 收件箱（不依赖 F1 的下一次关注 / 取关）"
        )
        assert _inbox_has(fan1["id"], c2) == 1, "同一 (user, content) 恰一行（INSERT IGNORE 幂等去重）"
        after = _inbox_count(fan1["id"])
        assert after == fan1_rows_before + 1, (
            f"补推只追增：降级前行数 {fan1_rows_before} ⇒ 降级后 {after}（应恰 +1，不得删除既有行）"
        )
        for kept in (c1, c_sentinel):
            assert _inbox_has(fan1["id"], kept) > 0, f"补推不得删除既有行: contentId={kept}"
        # A 已非大V ⇒ 发件箱腿不再覆盖它 ⇒ /feed 中的 c2 只可能来自收件箱腿（补推产物）
        assert _poll(lambda: c2 in _feed_ids(base_url, fan1["token"])), (
            "降级后 c2 应对 F1 可见（经收件箱腿，即补推落库的窗口）"
        )

    finally:
        for token, content_id in created:
            try:
                _delete_content(base_url, token, content_id)
            except Exception:      # noqa: BLE001 - 清理失败不掩盖用例结论
                pass
        # 恢复为默认（等价"未启用外置覆盖"），并等带宽过去，避免影响同批次后续用例
        try:
            _write_config(config_file, _DEFAULT_BIGV_THRESHOLD, [], _DEFAULT_DOWNGRADE_RATIO)
            _wait_reload_window()
        except Exception:          # noqa: BLE001
            pass