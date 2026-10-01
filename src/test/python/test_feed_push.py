# -*- coding: utf-8 -*-
"""
test_feed_push.py - feed 写扩散（push 管线）端到端测试（feed1-18 T18 建；feed2-21 T21 改写为落库语义）

背景：T21 起写扩散 = 「发布 → MQ → 消费者 → **落库 DB 真相表 `feed_inbox`** + 写后失效 DEL」
（NEEDS 4.0 写侧拍板：单写 DB 真相 + 写后失效 + 读 miss 回源回填；`/feed` 读路径自 **feed2-23 T23** 起
已切**两路读**——收件箱腿 + 大V发件箱腿——本文件仍以 **DB 真相**为准做断言）。
本文件是该链路的**端到端证据**——断言直连测试库只读复算。

覆盖：
  1. 粉丝视角：b 关注 a → a 发布图文 → **轮询**（最终一致窗口内收敛）`feed_inbox` 出现 (b_id, contentId)；
  2. 非粉丝不受影响：全新用户（无关注关系）在 `feed_inbox` 中零行；
  3. 红线哨兵：`/feed` 响应信封口径不变，且能取到刚发布的内容（信封与"取得到"是 T23 的不变量）；
  4. （feed3-T30）fanout 游标直读**不再物化 `user:follower` zset**：发布并确认 fanout 已落库后，
     `EXISTS user:follower:{author}` 仍为 0；**正向对照** = 调 `/follow/followers`（粉丝列表分页，
     语义未变）后该 key 变 1 ⇒ 证明"该观测通道能测出物化"（不同观测结果 = 真差异，非通道失效）；
  5. （feed3-T30）多页游标**不漏**：`FEED_FANOUT_BATCH` 小批量专跑下建 `2*batch` 个粉丝
     （恰整数倍 ⇒ 满页 ×2 + 空尾页终止），发布后**每个**粉丝的 `feed_inbox` 都含该行
     （"不重"由 JUnit 的游标严格递增断言承担——DB 侧 `INSERT IGNORE` 无法观测重复）。

读取手段与跳过口径：
  - **MySQL（独立 oracle）**：`mysql.exe` 子进程（沿 `test_feed_rebuild.py` / `test_content_paging.py`
    既有先例），连接参数取 `run_tests.py` 注入的 `DB_*`。**只发 SELECT**，不写库。
  - **MQ 可达性**：按 `AppConfig` 同一覆盖链解析应用实际用的 `rabbitmq.port`
    （`RABBITMQ_PORT` 环境变量 > `app.properties`）；不可达即 skip。
  - **Redis（只读 `EXISTS`）**：`docker exec <容器> redis-cli`（零新依赖；沿 `test_feed_read.py`
    先例，docker / 容器不可用 ⇒ 该用例 skip）。
  - 两层 skip 只作用于**落库类断言**（用例 1~2、4~5）——**用例 3 不跳过**，故降级跑
    （`RABBITMQ_PORT=5699 python tools/tv.py test`）下仍能证明"发布与 `/feed` 业务链路不受影响"。
  - 用例 5 另需 `FEED_FANOUT_BATCH` 设为小值（默认 200 跑法下 skip；专跑 =
    `FEED_FANOUT_BATCH=2 python tools/tv.py test all`）。
  - **收件箱 DEL 的 Redis 断言不在本文件**：归 `test_feed_rebuild.py` 用例 2（"收敛后再发布"序列，
    规避重建晚于 DEL 的过渡期竞态）。

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

# 应用所连的 Redis 容器（app.properties 固定 localhost:6379 → 该容器，db 0、无密码；沿 test_feed_read.py）
REDIS_CONTAINER = "redis"

# 粉丝列表缓存 key 前缀（feed3-T30 观测"fanout 是否物化粉丝 zset"）
FOLLOWER_PREFIX = "user:follower:"

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


def _followers(base_url, token, user_id):
    """GET /follow/followers?userId=..（粉丝列表分页；feed3-T30 用作"物化观测通道"的正向对照）。"""
    resp = requests.get(
        f"{base_url}/follow/followers",
        params={"userId": user_id},
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
# Redis 只读辅助（feed3-T30：docker exec redis-cli；零新依赖，沿 test_feed_read.py 先例）
# ---------------------------------------------------------------------------

def _require_redis_container():
    """docker / 容器不可用 → skip（读 Redis 是本文件的手段，不是被测能力）。"""
    if shutil.which("docker") is None:
        pytest.skip("docker 不可用，无法读 Redis 缓存")
    probe = subprocess.run(
        ["docker", "exec", REDIS_CONTAINER, "redis-cli", "PING"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30,
    )
    if probe.returncode != 0 or probe.stdout.strip() != "PONG":
        pytest.skip(f"容器 {REDIS_CONTAINER} 不可用，无法读 Redis 缓存")


def _redis_cli(*args):
    """只读执行一条 redis-cli 命令并返回 stdout（已 strip）。"""
    proc = subprocess.run(
        ["docker", "exec", REDIS_CONTAINER, "redis-cli", *[str(a) for a in args]],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30,
    )
    if proc.returncode != 0:
        raise RuntimeError("redis-cli 执行失败: " + (proc.stderr.strip() or proc.stdout.strip()))
    return proc.stdout.strip()


def _exists(key):
    return int(_redis_cli("EXISTS", key) or "0")


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


def _app_fanout_batch():
    """应用实际使用的 fanout 批量：覆盖链 = `FEED_FANOUT_BATCH` > `app.properties` 的 feed.fanout.batch。

    与 `_app_mq_port` 同型：只有按**同一覆盖链**解析，才能判断"本次跑"是否处于小批量（多页游标）状态。
    """
    env_value = os.environ.get("FEED_FANOUT_BATCH")
    if env_value:
        return int(env_value)
    props = _PROJECT_ROOT / "src" / "main" / "resources" / "app.properties"
    for line in props.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("feed.fanout.batch="):
            return int(line.split("=", 1)[1].strip())
    return 200


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
    """T21：写扩散落库（DB 真相）——粉丝收件箱增量落表、非粉丝不受影响、读路径信封口径不变。"""

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
        """红线哨兵：/feed 响应信封口径不变，且能取到刚发布的内容（切换前后一致）。

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


@pytest.mark.boundary
class TestFeedFanoutCursor:
    """feed3-T30：fanout 粉丝遍历改**游标（keyset）直读 DB**——不再物化 `user:follower` zset、
    多页游标不漏（批量 = `feed.fanout.batch`，默认 200；多页用例需小批量专跑）。"""

    def test_fanout_does_not_materialize_follower_zset(self, base_url, test_files):
        """发布触发 fanout 落库后，`user:follower:{author}` 仍不存在；调粉丝列表后才出现（正向对照）。

        观测口径：全新作者 + 全新粉丝 ⇒ 前置 key 必为 0；发布并**确认 fanout 落库**（否则断言无意义）
        后仍为 0 = fanout 未物化；随后调 `/follow/followers`（分页路径语义未变）令 key 出现
        ⇒ 证明"该观测通道能测出物化"，排除"读了错误 key"式的假绿。
        """
        _require_fanout_env()
        _require_redis_container()

        author = _register_fresh_user("curator")
        fan = _register_fresh_user("curfan")
        content_id = None
        follower_key = FOLLOWER_PREFIX + str(author["id"])
        try:
            add = _follow(base_url, fan["token"], author["id"], "add")
            assert add.get("code") == 200, f"follow/add failed: {add}"
            # 前置：全新作者的粉丝 zset 不存在（关注的双写探针只会 DEL、不创建）
            assert _exists(follower_key) == 0, f"前置：粉丝 zset 不应存在: {follower_key}"

            content_id = _post_content(base_url, author["token"], "feed3-T30 游标直读用例", test_files)
            assert _poll(lambda: _db_inbox_has(fan["id"], content_id)), (
                f"前置：fanout 应先落库（否则本轮断言无意义）: user_id={fan['id']}, contentId={content_id}"
            )
            # T30 核心：fanout 已执行（落库为证）而粉丝 zset 仍未被物化
            assert _exists(follower_key) == 0, (
                f"fanout 不得物化 {follower_key}（游标直读不回填缓存）"
            )

            # 正向对照：粉丝列表分页仍走原窗口 + 回填路径（语义未变）⇒ 该 key 应出现
            body = _followers(base_url, fan["token"], author["id"])
            assert body.get("code") == 200, f"/follow/followers failed: {body}"
            assert _poll(lambda: _exists(follower_key) == 1), (
                f"粉丝列表读后应物化 {follower_key}（证明观测通道能测出物化）"
            )
        finally:
            if content_id is not None:
                _delete_content(base_url, author["token"], content_id)
            _follow(base_url, fan["token"], author["id"], "remove")

    def test_fanout_keyset_multi_page_covers_all_followers(self, base_url, test_files):
        """多页游标不漏：`2*batch` 个粉丝（恰整数倍 ⇒ 满页×2 + 空尾页终止）全部收到该内容。

        默认批量（200）下 skip——需 `FEED_FANOUT_BATCH` 小批量专跑
        （`FEED_FANOUT_BATCH=2 python tools/tv.py test all`）。"不重"由 JUnit 的游标严格递增断言承担
        （DB 侧 `INSERT IGNORE` 无法观测重复）。
        """
        _require_fanout_env()
        batch = _app_fanout_batch()
        if batch > 5:
            pytest.skip(
                f"默认批量 {batch} 过大，多页游标需小批量专跑："
                f"FEED_FANOUT_BATCH=2 python tools/tv.py test all"
            )

        author = _register_fresh_user("curmulti")
        fans = [_register_fresh_user(f"curfan{i}") for i in range(2 * batch)]
        content_id = None
        try:
            for fan in fans:
                add = _follow(base_url, fan["token"], author["id"], "add")
                assert add.get("code") == 200, f"follow/add failed: {add}"
            content_id = _post_content(base_url, author["token"], "feed3-T30 多页游标用例", test_files)
            missing = [fan["id"] for fan in fans
                       if not _poll(lambda f=fan: _db_inbox_has(f["id"], content_id), timeout=20.0)]
            assert not missing, f"多页游标不得漏粉丝: missing={missing}, batch={batch}"
        finally:
            if content_id is not None:
                _delete_content(base_url, author["token"], content_id)
            for fan in fans:
                _follow(base_url, fan["token"], author["id"], "remove")