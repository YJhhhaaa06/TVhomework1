# -*- coding: utf-8 -*-
"""
test_bigv_hot_reload.py - 大V名单 / 阈值**外置文件热更**端到端留证（feed3-T27-B）

背景（feed3-T27-A 落地）：大V「名单 + 阈值」的取值收敛到单点 `config.FeedBigVConfig`——
未配 `feed.bigv.configFile` 时每次现读 `AppConfig` 静态键（与改造前逐字一致）；
配了则由该单点读**外置 Properties 文件**，并在取值时按 `feed.bigv.refreshMillis`
（默认 5000ms）节流**惰性重读** ⇒ **改文件不必重启进程**。

本文件证的就是后半句（端到端）：**不重启应用**，只改配置文件，观察大V判定**翻转**。

判定翻转的观测口径（复用 feed T23 的既有事实，不引入新语义）：
  - 作者**非大V** ⇒ 发布走 fanout ⇒ 其内容出现在粉丝 `feed_inbox`；
  - 作者是**大V** ⇒ fanout 早退（不落表）⇒ `feed_inbox` **无**该行，但粉丝 `/feed`
    **仍能看到**（来自「大V发件箱腿」，读时拉、不落表）。
  两者合起来是强证据：内容确实发布成功，只是**路由不同**。

三段式（同一作者 A + 同一粉丝 F，全程不重启）：
  ① 文件 = 默认参数（threshold 10000 + 空名单）⇒ A 普通：发 c1 ⇒ `feed_inbox` **有** (F, c1)；
  ② 文件 = 名单含 A ⇒ A 大V：发 c2 ⇒ `feed_inbox` **无** (F, c2) 且 `/feed` **有** c2；
  ③ 文件 = 名单清空 ⇒ A 又普通：发 c3 ⇒ `feed_inbox` **有** (F, c3)。
②③ 即"**不重启**改文件 ⇒ 判定翻转"。
阶段 ② 另带**消费进度哨兵**（第三位作者，全程非大V）：先用它走同一条 fanout 管道并确认落库，
证明"**此刻**消费链是通的" ⇒ 排除"c2 未落库只是 MQ 消费慢"这一竞争解释（负向断言的经典假绿）。

跑法（须显式给出配置文件路径；未给 ⇒ 本文件整体 skip）：
  FEED_BIGV_CONFIGFILE=<可写路径> python tools\\tv.py test
  可选 FEED_BIGV_REFRESHMILIS=<毫秒> 缩小节流窗口以加速（不设则按配置等满窗口）。
例（Windows Git Bash）：
  FEED_BIGV_CONFIGFILE=D:/javaproject/VideoPlatform/TVhomework1/temp_script/bigv-hotreload.properties \\
    python tools/tv.py test

跳过口径：
  - 未设 `FEED_BIGV_CONFIGFILE` ⇒ skip（**默认跑法行为与 T27-A 之前完全一致**，本用例是留证手段，
    不是默认能力）；
  - MySQL 通道不可用 ⇒ skip（直查库是手段，不是被测能力）；
  - docker / Redis 不可用**不影响本文件**（本文件不读 Redis）。

数据自建自清：注册全新 `testA_fbigv_*` 临时用户（命中 tools/cleanup_data.py 白名单），
内容在 finally 里由作者走真实删除路径软删；配置文件恢复为空（等价"未启用"）。
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
REFRESH_ENV = "FEED_BIGV_REFRESHMILIS"

# app.properties 默认值兜底（与 AppConfig 的带默认值读取同源）
_DEFAULT_BIGV_THRESHOLD = 10000
_DEFAULT_REFRESH_MILLIS = 5000

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
            f"未设置 {CONFIG_FILE_ENV} ⇒ 外部热更未启用（默认跑法行为与 T27-A 之前一致）；"
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


def _write_config(path, threshold, listed_ids):
    """**原子写**配置（临时文件 + os.replace）——与应用侧"读全文件 + 整批换入"的口径配套。"""
    lines = [
        "# feed3-T27-B 端到端留证用清单；键名与 app.properties 的 feed.bigv.* 一致\n",
        f"feed.bigv.threshold={threshold}\n",
        "feed.bigv.userIds=" + ",".join(str(i) for i in listed_ids) + "\n",
    ]
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text("".join(lines), encoding="utf-8")
    os.replace(tmp, path)


# ---------------------------------------------------------------------------
# HTTP 辅助（各测试文件自带 helper，沿 test_feed_read.py 既有风格）
# ---------------------------------------------------------------------------

def _register_fresh_user(tag):
    unique = uuid.uuid4().hex[:8]
    suffix = str(int(unique, 16))[-8:].zfill(8)
    username = f"testA_fbigv_{tag}_{unique}"
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


def _post_content(base_url, token, title, test_files):
    with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
        resp = requests.post(
            f"{base_url}/api/upload/post",
            headers={"token": token},
            data={"title": title, "description": "feed3-T27-B 热更 e2e", "categoryId": "0"},
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


def _is_synced(fan_id):
    """读态闸门：feed_inbox_sync 存在即已同步（窗口重建完成）。"""
    rows = _run_sql(
        f"SELECT COUNT(*) FROM feed_inbox_sync WHERE user_id = {int(fan_id)}"
    )
    return int(rows or "0") == 1


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------

def test_hot_reload_flips_bigv_decision_without_restart(base_url, test_files):
    """不重启应用，仅改外置配置文件 ⇒ 大V判定翻转（普通 → 大V → 普通）。

    观测：非大V时内容进 `feed_inbox`；大V时 **不进** `feed_inbox` 但 `/feed` 仍可见（发件箱腿）。
    """
    config_file = _config_file()
    _require_mysql()

    author = _register_fresh_user("author")     # 判定会翻转的作者
    fan = _register_fresh_user("fan")           # 关注 author 与 probe
    probe = _register_fresh_user("probe")       # 哨兵作者：**全程都不是大V**
    created = []                                # [(token, content_id)]

    try:
        # ---- 阶段 0：文件 = 默认参数（与 app.properties 等价，故全程不影响其它用例）----
        _write_config(config_file, _DEFAULT_BIGV_THRESHOLD, [])

        # 粉丝关注作者 ⇒ 触发窗口重建（作者此时**非**大V ⇒ 内容应进粉丝收件箱）
        assert _follow(base_url, fan["token"], author["id"]).get("code") == 200, "关注失败"
        assert _poll(lambda: _is_synced(fan["id"])), "窗口重建未在超时内完成（feed_inbox_sync 无行）"

        # ---- 阶段 ①：作者非大V ⇒ 发布内容应写入粉丝收件箱 ----
        c1 = _post_content(base_url, author["token"], "pytest_bigv_hotreload_1", test_files)
        created.append((author["token"], c1))
        assert _poll(lambda: _inbox_has(fan["id"], c1) > 0), (
            "配置文件=默认参数时作者应判为非大V、内容应进粉丝收件箱（fanout 未落库）"
        )

        # ---- 阶段 ②：把作者写进名单（**不重启**）⇒ 判定应翻转为大V ----
        _write_config(config_file, _DEFAULT_BIGV_THRESHOLD, [author["id"]])
        _wait_reload_window()

        # **消费进度哨兵**：先用一位"确定非大V"的作者走同一条 fanout 管道，证明**此刻**消费链是通的
        # —— 否则下面"c2 未落库"可能只是 MQ 消费慢，而不是判定翻转（负向断言的经典假绿）。
        # 哨兵作者先关注、等窗口重建完成，再发布 ⇒ 该内容只可能由 fanout 写入（重建时它还不存在）。
        assert _follow(base_url, fan["token"], probe["id"]).get("code") == 200, "关注哨兵作者失败"
        assert _poll(lambda: _is_synced(fan["id"])), "哨兵触发的窗口重建未在超时内完成"
        c_probe = _post_content(base_url, probe["token"], "pytest_bigv_hotreload_probe", test_files)
        created.append((probe["token"], c_probe))
        assert _poll(lambda: _inbox_has(fan["id"], c_probe) > 0), (
            "哨兵失败：同刻普通作者的内容都未落库 ⇒ fanout 消费链异常，"
            "本阶段的'无行'不能作为热更结论"
        )

        c2 = _post_content(base_url, author["token"], "pytest_bigv_hotreload_2", test_files)
        created.append((author["token"], c2))
        time.sleep(ABSENCE_CONFIRM_SECONDS)          # 负向断言：给它足够时间"本该落库却没落"
        assert _inbox_has(fan["id"], c2) == 0, (
            "改文件（不重启）后作者应判为大V ⇒ 其内容不得写入粉丝收件箱"
            "（同刻哨兵已证明消费链正常，故排除'消费慢'）"
        )
        assert c2 in _feed_ids(base_url, fan["token"]), (
            "大V内容应仍对粉丝可见（来自大V发件箱腿）——否则说明是发布失败而非判定翻转"
        )

        # ---- 阶段 ③：清空名单（**不重启**）⇒ 判定应再次翻转为普通 ----
        _write_config(config_file, _DEFAULT_BIGV_THRESHOLD, [])
        _wait_reload_window()

        c3 = _post_content(base_url, author["token"], "pytest_bigv_hotreload_3", test_files)
        created.append((author["token"], c3))
        assert _poll(lambda: _inbox_has(fan["id"], c3) > 0), (
            "清空名单（不重启）后作者应重判为普通 ⇒ 内容应重新写入粉丝收件箱"
        )

    finally:
        for token, content_id in created:
            try:
                _delete_content(base_url, token, content_id)
            except Exception:      # noqa: BLE001 - 清理失败不掩盖用例结论
                pass
        # 恢复为"未启用外部文件"（等价默认跑法），避免影响同批次其它用例
        try:
            _write_config(config_file, _DEFAULT_BIGV_THRESHOLD, [])
        except Exception:          # noqa: BLE001
            pass
