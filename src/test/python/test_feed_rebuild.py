# -*- coding: utf-8 -*-
"""
test_feed_rebuild.py - feed 收件箱重建（窗口化）端到端测试
（feed1-19 T19 建；feed2-21 T21 改写用例 2；**feed2-22 T22 全面改写为窗口 + 落库语义**）

背景：T22 起重建 = 「关注 / 取关 → rebuild 消息 → 消费者 → **单事务** `DELETE feed_inbox` +
**每关注作者最近 K 条** → 归并去重降序 → 裁剪 C → `INSERT IGNORE` feed_inbox + upsert
feed_inbox_sync」→ **提交后 DEL 收件箱读缓存三件套**（`/feed` 读路径仍为拉模式，零改动）。
**重建不再写 Redis**（单写 DB 真相 + 写后失效；一期"完整态标记 `feed:inbox:full:`"已退役）。

覆盖：
  1. 关注触发重建：`feed_inbox_sync` 出现 且 `feed_inbox` 窗口（content_id 降序）与该用户的
     **独立 oracle**（直连 MySQL 按「每关注者最近 K → 归并去重 → 裁剪 C」复算，`is_deleted=0`、
     排除大V）**逐条相等**；同时 **Redis 三件套不存在**——窗口非空却不写缓存 ⇒ 证明"重建只失效不写"。
  2. 重建后 fanout（写扩散，T21 起 = 写 DB 真相 + 写后失效）：作者再发一条（此时已是粉丝 → 走 fanout）
     ⇒ `feed_inbox` 出现该条，且收件箱读缓存仍不存在（"收敛后再发布"序列，规避过渡期竞态）。
  3. 取关触发重建：该博主内容从窗口消失（窗口 == 新 oracle = 空集），`feed_inbox_sync` 仍在
     （"空但已同步"）。
  4. 红线哨兵：`/feed` 拉模式响应信封口径不变且能取到内容（读路径零改动）——**不做环境 skip**，
     故降级跑（`RABBITMQ_PORT=5699 python tools\tv.py test`）下仍能证明业务链路不依赖推。

读取手段与跳过口径：
  - **MySQL（独立 oracle 与真相断言）**：`mysql.exe` 子进程（沿 `test_content_paging.py` /
    `test_comment_delete.py` 既有先例），连接参数取 `run_tests.py` 注入的 `DB_*`。**只发 SELECT**。
  - **Redis（只读命令 `PING` / `EXISTS`）**：`docker exec <容器> redis-cli` 子进程——Python 侧无
    redis 依赖，redis-cli 就在应用所连的同一容器内（db 0、无密码），**零新依赖**；
    官方口径见 `说明书/TEST_AUTOMATION.md` §4.7。
  - **MQ 可达性**：按 `AppConfig` 同一覆盖链解析应用实际用的 `rabbitmq.port`
    （`RABBITMQ_PORT` 环境变量 > `app.properties`）；不可达即 skip。
  - 三层 skip 只作用于**重建类断言**（用例 1~3）——**用例 4 不跳过**。

分层说明：**并发投递"仅一次实际重建"由 JUnit 承担**（`FeedRebuildServiceTest` 的 SET NX 去重 +
并发用例，确定性断言）；e2e 侧无法可靠构造重复投递（重复关注返回 409），故不复刻。

窗口参数（K / C）与"大V判定"阈值 / 名单**一律按 app.properties 同源读取**，oracle 与应用同参。

数据自建自清：每个用例各自注册全新临时用户（`testA_` 前缀，命中 tools/cleanup_data.py 白名单，
命名 `testA_frebuild_*`），不依赖 conftest 的会话用户；内容在 teardown 里由作者走真实删除路径软删。
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

# 项目根（按 AppConfig 覆盖链解析应用实际使用的 MQ 端口 / 窗口参数用）
_PROJECT_ROOT = Path(__file__).resolve().parents[3]

# 应用所连的 Redis 容器（app.properties 固定 localhost:6379 → 该容器，db 0、无密码）
REDIS_CONTAINER = "redis"

# 收件箱读缓存 key 前缀（须与 cache.CacheKeys 同步）
INBOX_PREFIX = "feed:inbox:"

# 内容域信封字段集（唯一信封 common.model.dto.PageResult）
_ENVELOPE_KEYS = {"list", "total", "page", "pageSize", "totalPages"}

# 收敛窗口：MQ 投递 + 消费 + DB 窗口重算在本地毫秒级完成；10s 上限只用于吸收抖动
POLL_TIMEOUT_SECONDS = 10.0
POLL_INTERVAL_SECONDS = 0.2

_MYSQL_CANDIDATES = (
    r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
)

# app.properties 默认值兜底（与 AppConfig 的带默认值读取同源）
_DEFAULT_WINDOW_PER_AUTHOR = 20
_DEFAULT_WINDOW_MAX = 200
_DEFAULT_BIGV_THRESHOLD = 10000


# ---------------------------------------------------------------------------
# HTTP 辅助
# ---------------------------------------------------------------------------

def _register_fresh_user(tag):
    """注册一个全新的 testA_ 前缀用户（无任何关注关系），返回 id/token/username。"""
    unique = uuid.uuid4().hex[:8]
    suffix = str(int(unique, 16))[-8:].zfill(8)
    username = f"testA_frebuild_{tag}_{unique}"
    body = conftest.register_user(username, f"138{suffix}", "abc123")
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
    """POST /api/upload/post 建一条图文内容（真实写路径），返回 contentId。"""
    with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
        resp = requests.post(
            f"{base_url}/api/upload/post",
            headers={"token": token},
            data={"title": title, "description": "feed2-22 重建窗口用例", "categoryId": "0"},
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
# Redis 只读辅助（docker exec redis-cli）
# ---------------------------------------------------------------------------

def _require_redis_container():
    """docker / 容器不可用 → skip（读 Redis 是本文件的手段，不是被测能力）。"""
    if shutil.which("docker") is None:
        pytest.skip("docker 不可用，无法读 Redis 收件箱缓存")
    probe = subprocess.run(
        ["docker", "exec", REDIS_CONTAINER, "redis-cli", "PING"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30,
    )
    if probe.returncode != 0 or probe.stdout.strip() != "PONG":
        pytest.skip(f"容器 {REDIS_CONTAINER} 不可用，无法读 Redis 收件箱缓存")


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


def _cache_absent(fan_id):
    """重建 / fanout 之后收件箱读缓存三件套都应不存在（T22 起重建**不写**缓存，只失效）。"""
    inbox = INBOX_PREFIX + str(fan_id)
    return _exists(inbox) == 0 and _exists("empty:" + inbox) == 0 \
        and _exists("partial:" + inbox) == 0


def _poll(predicate, timeout=POLL_TIMEOUT_SECONDS, interval=POLL_INTERVAL_SECONDS):
    """轮询直到 predicate 为真（最终一致窗口内收敛），超时返回最后一次结果（调用方断言）。"""
    deadline = time.monotonic() + timeout
    while True:
        value = predicate()
        if value or time.monotonic() >= deadline:
            return value
        time.sleep(interval)


# ---------------------------------------------------------------------------
# MySQL 只读辅助（独立 oracle：不经过应用代码路径复算期望窗口）
# ---------------------------------------------------------------------------

def _mysql_path():
    """定位 mysql 客户端（沿 test_content_paging.py 既有先例）。"""
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
        pytest.skip("mysql 客户端不可用，无法复算收件箱期望窗口")
    try:
        _run_sql("SELECT 1")
    except Exception as exc:      # noqa: BLE001 - 通道不可用一律 skip，避免误报失败
        pytest.skip(f"测试库不可达，无法复算收件箱期望窗口: {exc}")


def _app_prop(key, default):
    """按 app.properties 读取配置（缺键 → 默认值，与 AppConfig 的带默认值读取同源）。"""
    props = _PROJECT_ROOT / "src" / "main" / "resources" / "app.properties"
    for line in props.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith(key + "="):
            value = stripped.split("=", 1)[1].strip()
            return value if value else default
    return default


def _window_params():
    """窗口参数 K（每作者条数）/ C（总上限）——与 AppConfig 同源读取（环境变量优先）。"""
    env_k = os.environ.get("FEED_INBOX_WINDOWPERAUTHOR")
    env_c = os.environ.get("FEED_INBOX_WINDOWMAX")
    per_author = int(env_k) if env_k else int(_app_prop("feed.inbox.windowPerAuthor",
                                                        _DEFAULT_WINDOW_PER_AUTHOR))
    window_max = int(env_c) if env_c else int(_app_prop("feed.inbox.windowMax", _DEFAULT_WINDOW_MAX))
    return per_author, window_max


def _bigv_params():
    """大V判定参数（阈值 + 名单）——oracle 需与应用同参排除大V作者（环境变量优先，同 K/C 口径）。"""
    env_t = os.environ.get("FEED_BIGV_THRESHOLD")
    threshold = int(env_t) if env_t else int(_app_prop("feed.bigv.threshold", _DEFAULT_BIGV_THRESHOLD))
    raw = os.environ.get("FEED_BIGV_USERIDS")
    if raw is None:
        raw = _app_prop("feed.bigv.userIds", "")
    listed = [int(t) for t in raw.split(",") if t.strip().isdigit()]
    return threshold, listed


def _oracle_window_ids(fan_id):
    """独立 oracle：该用户收件箱窗口**应有**的内容 id 序列。

    口径与应用同参（NEEDS 4.0 机制拍板）：关注者集合取自 `follow` 表（DB 真相，非缓存）→
    **每关注作者最近 K 条**（`id` 降序，id 自增即时间序）→ 归并去重 → contentId 降序 → 裁剪到 C；
    `is_deleted = 0`；并**排除大V作者**（`users.follower_count >= 阈值` 或命中名单）——
    阈值 / 名单与应用同参，**粉丝数来源不同**（oracle = DB 真值 `users.follower_count`；
    应用 = `FollowCache.getFollowerCount` 缓存读）。测试用的全新临时用户粉丝数远低于阈值，
    两种来源下判定结果一致，故不产生分歧；该差异不影响本用例断言力。
    """
    per_author, window_max = _window_params()
    threshold, listed = _bigv_params()
    excluded = [f"AND c.user_id NOT IN (SELECT u.id FROM users u WHERE u.follower_count >= {threshold})"]
    if listed:
        excluded.append("AND c.user_id NOT IN (" + ",".join(str(i) for i in listed) + ")")
    sql = (
        "SELECT content_id FROM ("
        "  SELECT c.id AS content_id,"
        "         ROW_NUMBER() OVER (PARTITION BY c.user_id ORDER BY c.id DESC) AS rn"
        "  FROM content c"
        f"  WHERE c.user_id IN (SELECT followed_user_id FROM follow WHERE user_id = {int(fan_id)})"
        "    AND c.is_deleted = 0"
        "    " + " ".join(excluded) +
        ") w "
        f"WHERE w.rn <= {per_author} "
        "ORDER BY w.content_id DESC "
        f"LIMIT {window_max}"
    )
    return [int(line.strip()) for line in _run_sql(sql).splitlines() if line.strip()]


def _db_window_ids(fan_id):
    """DB 真相：该用户 `feed_inbox` 窗口（content_id 降序）——重建的产物。"""
    out = _run_sql(
        "SELECT content_id FROM feed_inbox "
        f"WHERE user_id = {int(fan_id)} ORDER BY content_id DESC"
    )
    return [int(line.strip()) for line in out.splitlines() if line.strip()]


def _db_inbox_has(fan_id, content_id):
    """fanout 落库收敛信号（feed2-21 T21）：DB 真相表 `feed_inbox` 是否已含该条（行数 == 1）。"""
    out = _run_sql(
        "SELECT COUNT(*) FROM feed_inbox "
        f"WHERE user_id = {int(fan_id)} AND content_id = {int(content_id)}"
    )
    return out.strip() == "1"


def _db_synced(fan_id):
    """窗口同步状态（T22）：`feed_inbox_sync` 是否存在该用户行（**存在即已同步**）。"""
    out = _run_sql(f"SELECT COUNT(*) FROM feed_inbox_sync WHERE user_id = {int(fan_id)}")
    return out.strip() == "1"


# ---------------------------------------------------------------------------
# 前置：MQ 可达性 / 重建类断言的 skip 口径
# ---------------------------------------------------------------------------

def _app_mq_port():
    """应用实际使用的 MQ 端口：AppConfig 覆盖链 = 环境变量 `RABBITMQ_PORT` > `app.properties`。

    降级跑（`RABBITMQ_PORT=5699 python tools/tv.py test`）把应用指向不可达端口，故必须按**同一**
    覆盖链解析，才能判断"本次跑，重建链路是否可能产出窗口"。
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
    """MQ 不可达 → skip：重建靠 MQ 投递，降级跑下该链路本就不产出窗口，超时失败不代表回归。"""
    port = _app_mq_port()
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return
    except OSError:
        pytest.skip(f"应用配置的 RabbitMQ(127.0.0.1:{port}) 不可达（降级跑），跳过重建 e2e 用例")


def _require_rebuild_env():
    """重建类断言的共同前置：MQ 可达（否则重建消息不会到达）+ MySQL / oracle 可用。"""
    _require_mq_reachable()
    _require_mysql()


def _require_cache_env():
    """缓存失效断言的前置：Redis 容器可读（失效断言是 T22"只失效不写"的直接证据）。"""
    _require_redis_container()


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------

@pytest.fixture
def scenario(base_url, test_files):
    """每个用例自建自清：全新作者 + 全新粉丝；**作者先发内容、粉丝后关注**（触发重建）。

    刻意"先发后关"：该内容发布时粉丝还不是粉丝 ⇒ 没有 fanout 消息，只能由**重建**写进窗口，
    这样断言才真正证明重建生效（而不是在验证 T21 的增量路径）。

    **本 fixture 不做环境 skip**——发布与 `/feed` 在 MQ 降级时同样应正常，故前置检查放各
    "重建类断言"用例内（见 `_require_rebuild_env`），以保证读路径哨兵在降级跑下仍执行。
    """
    author = _register_fresh_user("author")
    fan = _register_fresh_user("fan")
    content_id = None
    try:
        content_id = _post_content(base_url, author["token"], "feed2-22 重建窗口 e2e", test_files)
        add = _follow(base_url, fan["token"], author["id"], "add")
        assert add.get("code") == 200, f"follow/add failed: {add}"
        yield {"author": author, "fan": fan, "content_id": content_id}
    finally:
        _follow(base_url, fan["token"], author["id"], "remove")
        if content_id is not None:
            _delete_content(base_url, author["token"], content_id)


@pytest.mark.boundary
class TestFeedRebuildWindow:
    """T22：重建 = 窗口重算落库 + 同步状态；Redis 只失效不写。"""

    def test_follow_rebuilds_window_equal_to_oracle(self, scenario):
        """关注 → 重建收敛：`feed_inbox_sync` 出现，且 DB 窗口与独立 oracle **逐条相等**；
        同时 Redis 三件套不存在（窗口非空却不写缓存 = "重建只失效不写"的直接证据）。"""
        _require_rebuild_env()
        _require_cache_env()
        fan_id = scenario["fan"]["id"]

        def converged():
            if not _db_synced(fan_id):
                return False
            return _db_window_ids(fan_id) == _oracle_window_ids(fan_id)

        assert _poll(converged), (
            f"收件箱窗口未收敛: window={_db_window_ids(fan_id)} oracle={_oracle_window_ids(fan_id)} "
            f"synced={_db_synced(fan_id)}"
        )
        expected = _oracle_window_ids(fan_id)
        assert scenario["content_id"] in expected, "前置：oracle 应含刚发布内容（否则断言空转）"
        assert _db_window_ids(fan_id) == expected, "窗口应与 oracle 逐条相等（不重不漏）"
        # 窗口非空却没有任何缓存 key ⇒ 重建没有写 Redis（旧口径会写 ZSet + 完整态标记）
        assert _cache_absent(fan_id), (
            f"重建不应写收件箱读缓存: inboxExists={_exists(INBOX_PREFIX + str(fan_id))}"
        )

    def test_fanout_after_rebuild_appends_db_and_cache_stays_absent(self, scenario, base_url, test_files):
        """重建后再发布：fanout **写 DB 真相**（窗口出现该条）且缓存仍不存在。

        "收敛后再发布"序列规避"重建晚于 DEL 而重新写入缓存"的过渡期竞态（收敛 = 同步状态已写 =
        重建最后一步已完成）。T22 起重建与 fanout 都只失效不写缓存，故 Redis 侧恒为空。
        """
        _require_rebuild_env()
        _require_cache_env()
        fan_id = scenario["fan"]["id"]
        author = scenario["author"]

        assert _poll(lambda: _db_synced(fan_id) and _db_window_ids(fan_id) == _oracle_window_ids(fan_id)), \
            "前置：初次重建应先收敛"

        second = _post_content(base_url, author["token"], "feed2-22 重建后追加", test_files)
        try:
            # fanout 落库（DB 真相出现该条）= "该消息已消费完"的收敛信号（轮询 DB，不经 Redis）
            assert _poll(lambda: _db_inbox_has(fan_id, second)), (
                f"fanout 未落库: feed_inbox 缺 (user_id={fan_id}, content_id={second})"
            )
            assert _cache_absent(fan_id), "fanout 写后失效后缓存仍不应存在"
        finally:
            _delete_content(base_url, author["token"], second)

    def test_unfollow_rebuilds_window_without_author(self, scenario, base_url):
        """取关 → 重建收敛：该博主内容移出窗口（窗口 == 新 oracle = 空集），同步状态仍在
        （"空但已同步"）。"""
        _require_rebuild_env()
        _require_cache_env()
        fan_id = scenario["fan"]["id"]
        author_id = scenario["author"]["id"]
        content_id = scenario["content_id"]

        assert _poll(lambda: _db_synced(fan_id) and content_id in _db_window_ids(fan_id)), \
            "前置：初次重建应先收敛"

        remove = _follow(base_url, scenario["fan"]["token"], author_id, "remove")
        assert remove.get("code") == 200, f"follow/remove failed: {remove}"

        assert _poll(lambda: _db_window_ids(fan_id) == _oracle_window_ids(fan_id) and _db_synced(fan_id)), (
            f"取关后重建未收敛: window={_db_window_ids(fan_id)} oracle={_oracle_window_ids(fan_id)} "
            f"synced={_db_synced(fan_id)}"
        )
        assert content_id not in _db_window_ids(fan_id), "取关后不得残留该博主内容"
        # 未关注任何人 ≠ 未同步：重建仍写同步状态（T19"空集也写完整态"口径的落表版）
        assert _db_synced(fan_id), "空窗口也应标为已同步（否则读侧会误判为未同步而回退拉模式）"

    def test_feed_read_path_unchanged(self, base_url, scenario):
        """红线哨兵：/feed 拉模式信封口径不变，且能取到刚发布的内容（读路径零改动）。

        **不做环境 skip**：MQ / Redis / DB oracle 降级时本用例仍应通过——这正是"业务链路不依赖推"的证据。
        """
        body = _feed(base_url, scenario["fan"]["token"])

        assert body.get("code") == 200, f"/feed failed: {body}"
        data = body.get("data") or {}
        assert set(data.keys()) == _ENVELOPE_KEYS, f"/feed 信封字段集不符: {data}"
        ids = [it.get("id") for it in (data.get("list") or [])
               if it.get("authorId") == scenario["author"]["id"]]
        assert scenario["content_id"] in ids, \
            f"/feed 应取到刚发布内容 {scenario['content_id']}: {ids}"
