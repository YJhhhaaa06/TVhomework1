# -*- coding: utf-8 -*-
"""
test_feed_push.py - feed 写扩散（push 管线）端到端测试（feed1-18 T18 建；feed2-21 T21 改写为落库语义）

背景：T21 起写扩散 = 「发布 → MQ → 消费者 → **落库 DB 真相表 `feed_inbox`** + 写后失效 DEL」
（NEEDS 4.0 写侧拍板：单写 DB 真相 + 写后失效 + 读 miss 回源回填；**`/feed` 读路径仍为拉模式**，
本期零改动）。本文件是该链路的**端到端证据**——断言直连测试库只读复算（收件箱尚无可读 HTTP 接口）。

覆盖：
  1. 粉丝视角：b 关注 a → a 发布图文 → **轮询**（最终一致窗口内收敛）`feed_inbox` 出现 (b_id, contentId)；
  2. 非粉丝不受影响：全新用户（无关注关系）在 `feed_inbox` 中零行；
  3. 红线哨兵：`/feed` 拉模式响应信封口径不变，且能取到刚发布的内容（读路径零改动）。

读取手段与跳过口径：
  - **MySQL（独立 oracle）**：`mysql.exe` 子进程（沿 `test_feed_rebuild.py` / `test_content_paging.py`
    既有先例），连接参数取 `run_tests.py` 注入的 `DB_*`。**只发 SELECT**，不写库。
  - **MQ 可达性**：按 `AppConfig` 同一覆盖链解析应用实际用的 `rabbitmq.port`
    （`RABBITMQ_PORT` 环境变量 > `app.properties`）；不可达即 skip。
  - 两层 skip 只作用于**落库类断言**（用例 1~2）——**用例 3 不跳过**，故降级跑
    （`RABBITMQ_PORT=5699 python tools/tv.py test`）下仍能证明"发布与 `/feed` 业务链路不受影响"。
  - **Redis 不再由本文件断言**：T21 起 fanout 只 DEL 缓存（不写），DEL 的 e2e 断言归
    `test_feed_rebuild.py` 用例 2（"收敛后再发布"序列，规避重建晚于 DEL 的过渡期竞态）。

数据自建自清：三个用户全部用 `testA_` 前缀临时注册（命中 tools/cleanup_data.py 的
TEST_USERNAME_PREFIXES 白名单，命名 `testA_fpush_*`），不依赖 conftest 的 user_a/user_b
（避免与 test_feed.py 等用例互相影响关注状态）；内容在 teardown 里由作者走真实删除路径软删。
"""

import os
import shutil
import socket
import subprocess
import time
import uuid
from pathlib import Path

import pytest
import requests

import conftest

# 项目根（按 AppConfig 覆盖链解析应用实际使用的 MQ 端口用）
_PROJECT_ROOT = Path(__file__).resolve().parents[3]

# 收件箱真相表（须与 main 侧 SQL / 迁移脚本同步）
FEED_INBOX_TABLE = "feed_inbox"

# 内容域信封字段集（唯一信封 common.model.dto.PageResult）
_ENVELOPE_KEYS = {"list", "total", "page", "pageSize", "totalPages"}

# 收敛窗口：MQ 投递 + 消费 + DB 落库在本地毫秒级完成；10s 上限只用于吸收抖动
POLL_TIMEOUT_SECONDS = 10.0
POLL_INTERVAL_SECONDS = 0.2

_MYSQL_CANDIDATES = (
    r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
)


# ---------------------------------------------------------------------------
# HTTP 辅助
# ---------------------------------------------------------------------------

def _register_fresh_user(tag):
    """注册一个全新的 testA_ 前缀用户（无任何关注关系），返回 id/token/username。"""
    unique = uuid.uuid4().hex[:8]
    suffix = str(int(unique, 16))[-8:].zfill(8)
    username = f"testA_fpush_{tag}_{unique}"
    body = conftest.register_user(username, f"137{suffix}", "abc123")
    assert body.get("code") == 200, f"注册临时用户失败: {body}"
    data = body.get("data") or {}
    return {"id": data.get("id"), "token": data.get("token"), "username": username}


def _follow(base_url, token, followed_user_id, action):
    """follow/add | follow/remove。"""
    resp = requests.post(
        f"{base_url}/follow/{action}",
        headers={"token": token, "Content-Type": "application/x-www-form-urlencoded"},
        data={"followedUserId": str(followed_user_id)},
        timeout=10,
    )
    return resp.json()


def _post_content(base_url, token, title, test_files):
    """POST /api/upload/post 建一条图文内容（真实写路径 = 投递点），返回 contentId。"""
    with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
        resp = requests.post(
            f"{base_url}/api/upload/post",
            headers={"token": token},
            data={"title": title, "description": "feed2-21 写扩散落库用例", "categoryId": "0"},
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
    """作者软删（真实路径），teardown 兜底用。"""
    return requests.post(
        f"{base_url}/content/delete",
        params={"contentId": content_id},
        headers={"token": token},
        timeout=10,
    ).json()


def _feed(base_url, token, page=1, page_size=100):
    resp = requests.get(
        f"{base_url}/feed",
        params={"page": page, "pageSize": page_size},
        headers={"token": token},
        timeout=10,
    )
    return resp.json()


# ---------------------------------------------------------------------------
# MySQL 只读辅助（DB 真相断言：独立 oracle，不经过应用代码路径）
# ---------------------------------------------------------------------------

def _mysql_path():
    """定位 mysql 客户端（沿 test_content_paging.py / test_feed_rebuild.py 既有先例）。"""
    found = shutil.which("mysql")
    if found:
        return Path(found)
    for candidate in _MYSQL_CANDIDATES:
        if Path(candidate).exists():
            return Path(candidate)
    return None


def _run_sql(sql):
    """直连测试库执行**只读** SQL（连接参数由 run_tests.py 注入的 DB_* 给出）。"""
    mysql = _mysql_path()
    cmd = [
        str(mysql),
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
    """oracle 通道不可用 → skip（直查 MySQL 是手段，不是被测能力）。"""
    if _mysql_path() is None:
        pytest.skip("mysql 客户端不可用，无法读收件箱真相表")
    try:
        _run_sql("SELECT 1")
    except Exception as exc:      # noqa: BLE001 - 通道不可用一律 skip，避免误报失败
        pytest.skip(f"测试库不可达，无法读收件箱真相表: {exc}")


def _db_inbox_has(fan_id, content_id):
    """该粉丝收件箱（DB 真相）是否已含该内容（行数 == 1）。"""
    out = _run_sql(
        f"SELECT COUNT(*) FROM {FEED_INBOX_TABLE} "
        f"WHERE user_id = {int(fan_id)} AND content_id = {int(content_id)}"
    )
    return out.strip() == "1"


def _db_inbox_count(user_id):
    out = _run_sql(f"SELECT COUNT(*) FROM {FEED_INBOX_TABLE} WHERE user_id = {int(user_id)}")
    return int(out.strip() or "0")


def _poll(predicate, timeout=POLL_TIMEOUT_SECONDS, interval=POLL_INTERVAL_SECONDS):
    """轮询直到 predicate 为真（最终一致窗口内收敛），超时返回最后一次结果（调用方断言）。"""
    deadline = time.monotonic() + timeout
    while True:
        value = predicate()
        if value or time.monotonic() >= deadline:
            return value
        time.sleep(interval)


# ---------------------------------------------------------------------------
# 前置：MQ 可达性 / 落库类断言的 skip 口径
# ---------------------------------------------------------------------------

def _app_mq_port():
    """应用实际使用的 MQ 端口：AppConfig 覆盖链 = 环境变量 `RABBITMQ_PORT` > `app.properties` 的 rabbitmq.port。

    降级跑（`RABBITMQ_PORT=5699 python tools/tv.py test`）把应用指向不可达端口，故必须按**同一**覆盖链
    解析，才能判断"本次跑，写扩散链路是否可能产出落库"。
    """
    env_port = os.environ.get("RABBITMQ_PORT")
    if env_port:
        return int(env_port)
    props = _PROJECT_ROOT / "src" / "main" / "resources" / "app.properties"
    for line in props.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("rabbitmq.port="):
            return int(line.split("=", 1)[1].strip())
    return 5672


def _require_mq_reachable():
    """MQ 不可达 → skip：写扩散靠 MQ 投递，降级跑下该链路本就不产出落库，超时失败不代表回归。"""
    port = _app_mq_port()
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return
    except OSError:
        pytest.skip(f"应用配置的 RabbitMQ(127.0.0.1:{port}) 不可达（降级跑），跳过写扩散 e2e 用例")


def _require_fanout_env():
    """落库类断言的共同前置：MQ 可达（否则 fanout 不会执行）+ MySQL 可读（否则读不出断言）。"""
    _require_mq_reachable()
    _require_mysql()


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def push_context(base_url, test_files):
    """module 级：临时作者 + 临时粉丝（fan 关注 author）+ 一条新发布内容。

    teardown：作者软删该内容 + 取关复原（本模块自建自清，不依赖外部会话用户状态）。
    **本 fixture 不做环境 skip**——发布与 `/feed` 在 MQ 降级时同样应正常，故前置检查放在
    各"落库类断言"用例内（见 `_require_fanout_env`），以保证用例 3（读路径哨兵）在降级跑下仍执行。
    """
    author = _register_fresh_user("author")
    fan = _register_fresh_user("fan")
    stranger = _register_fresh_user("stranger")

    add = _follow(base_url, fan["token"], author["id"], "add")
    assert add.get("code") == 200, f"follow/add failed: {add}"

    content_id = None
    try:
        content_id = _post_content(base_url, author["token"], "feed2-21 写扩散落库用例", test_files)
        yield {
            "author": author,
            "fan": fan,
            "stranger": stranger,
            "content_id": content_id,
        }
    finally:
        if content_id is not None:
            _delete_content(base_url, author["token"], content_id)
        _follow(base_url, fan["token"], author["id"], "remove")


@pytest.mark.boundary
class TestFeedPushFanout:
    """T21：写扩散落库（DB 真相）——粉丝收件箱增量落表、非粉丝不受影响、读路径零改动。"""

    def test_fanout_writes_db_inbox_for_follower(self, push_context):
        """粉丝收件箱（DB 真相 `feed_inbox`）在收敛窗口内出现新 contentId（最终一致）。"""
        _require_fanout_env()
        fan_id = push_context["fan"]["id"]
        content_id = push_context["content_id"]

        assert _poll(lambda: _db_inbox_has(fan_id, content_id)), (
            f"粉丝收件箱未落库: user_id={fan_id}, contentId={content_id}, "
            f"rows={_db_inbox_count(fan_id)}"
        )

    def test_non_follower_db_inbox_not_affected(self, push_context):
        """非粉丝（无关注关系）的收件箱零行（fanout 不写无关用户）。"""
        _require_fanout_env()
        fan_id = push_context["fan"]["id"]
        content_id = push_context["content_id"]
        # 等 fanout 处理完（粉丝侧收敛 = 该消息已消费），再断言非粉丝侧
        assert _poll(lambda: _db_inbox_has(fan_id, content_id)), "前置：粉丝收件箱应先落库"

        stranger_id = push_context["stranger"]["id"]
        assert _db_inbox_has(stranger_id, content_id) is False, \
            f"非粉丝收件箱不应含该内容: user_id={stranger_id}"
        assert _db_inbox_count(stranger_id) == 0, f"非粉丝不应有收件箱行: user_id={stranger_id}"

    def test_feed_read_path_unchanged(self, base_url, push_context):
        """红线哨兵：/feed 拉模式信封口径不变，且能取到刚发布的内容（读路径零改动）。

        **不做环境 skip**：MQ / MySQL 降级时本用例仍应通过——这正是"业务链路不依赖推"的证据。
        """
        body = _feed(base_url, push_context["fan"]["token"])

        assert body.get("code") == 200, f"/feed failed: {body}"
        data = body.get("data") or {}
        assert set(data.keys()) == _ENVELOPE_KEYS, f"/feed 信封字段集不符: {data}"
        ids = [it.get("id") for it in (data.get("list") or [])
               if it.get("authorId") == push_context["author"]["id"]]
        assert push_context["content_id"] in ids, \
            f"/feed 应取到刚发布内容 {push_context['content_id']}: {ids}"