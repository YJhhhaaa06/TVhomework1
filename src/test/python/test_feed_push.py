# -*- coding: utf-8 -*-
"""
test_feed_push.py - feed 写扩散（push 管线）端到端测试（feed1-18 T18）

背景：T18 落地「发布 → MQ → 消费者 → 粉丝收件箱 ZADD」的写扩散链路（**影子期：只写不读**，
`/feed` 读路径零改动）。本文件是该链路的**端到端证据**——收件箱在 Redis 里、应用侧无 HTTP 读接口，
故直接读 Redis 断言（读通道见下）。

覆盖：
  1. 粉丝视角：b 关注 a → a 发布图文 → **轮询**（最终一致窗口内收敛）收件箱
     `feed:inbox:{b_id}` 含新 contentId，且 `score == contentId`；
  2. 该收件箱 key 带 TTL（到期整条回收，不是单条内容过期）；
  3. 非粉丝不受影响：全新用户（无关注关系）的收件箱既无该条、也不存在 inbox key；
  4. 红线哨兵：`/feed` 拉模式响应信封口径不变，且能取到刚发布的内容（读路径零改动）。

读取手段与跳过口径：
  - **Redis**：`docker exec <容器> redis-cli <命令>` 子进程——Python 侧无 redis 依赖
    （requirements 只有 pytest/requests），redis-cli 就在应用所连的同一容器内（db 0、无密码），
    与既有 `mysql.exe` 子进程范式同构，**零新依赖**；官方口径见 `说明书/TEST_AUTOMATION.md` §4.7。
  - **MQ 可达性**：按 `AppConfig` 同一覆盖链解析应用实际用的 `rabbitmq.port`
    （`RABBITMQ_PORT` 环境变量 > `app.properties`）；不可达即 skip。
  - 两层 skip 只作用于**收件箱类断言**（用例 1~3）——**用例 4 不跳过**，故降级跑
    （`RABBITMQ_PORT=5699 python tools/tv.py test`）下仍能证明"发布与 `/feed` 业务链路不受影响"。

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

# 应用所连的 Redis 容器（app.properties 固定 localhost:6379 → 该容器，db 0、无密码）
REDIS_CONTAINER = "redis"

# 收件箱 key 前缀（须与 cache.CacheKeys.FEED_INBOX_PREFIX 同步）
INBOX_PREFIX = "feed:inbox:"

# 内容域信封字段集（唯一信封 common.model.dto.PageResult）
_ENVELOPE_KEYS = {"list", "total", "page", "pageSize", "totalPages"}

# 收敛窗口：MQ 投递 + 消费 + Redis 写在本地毫秒级完成；10s 上限只用于吸收抖动
POLL_TIMEOUT_SECONDS = 10.0
POLL_INTERVAL_SECONDS = 0.2


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
    """POST /api/upload/post 建一条图文内容（真实写路径 = T18 的投递点），返回 contentId。"""
    with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
        resp = requests.post(
            f"{base_url}/api/upload/post",
            headers={"token": token},
            data={"title": title, "description": "feed1-18 写扩散用例", "categoryId": "0"},
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
        pytest.skip("docker 不可用，无法读 Redis 收件箱")
    probe = subprocess.run(
        ["docker", "exec", REDIS_CONTAINER, "redis-cli", "PING"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30,
    )
    if probe.returncode != 0 or probe.stdout.strip() != "PONG":
        pytest.skip(f"容器 {REDIS_CONTAINER} 不可用，无法读 Redis 收件箱")


def _redis_cli(*args):
    """只读执行一条 redis-cli 命令并返回 stdout（已 strip）。"""
    proc = subprocess.run(
        ["docker", "exec", REDIS_CONTAINER, "redis-cli", *[str(a) for a in args]],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30,
    )
    if proc.returncode != 0:
        raise RuntimeError("redis-cli 执行失败: " + (proc.stderr.strip() or proc.stdout.strip()))
    return proc.stdout.strip()


def _zscore(key, member):
    """ZSCORE；成员不存在返回 None（redis-cli 对 nil 输出空串，兼容加括号形态）。"""
    out = _redis_cli("ZSCORE", key, member)
    return None if out in ("", "(nil)") else float(out)


def _zcard(key):
    return int(_redis_cli("ZCARD", key) or "0")


def _ttl(key):
    return int(_redis_cli("TTL", key) or "-2")


def _poll(predicate, timeout=POLL_TIMEOUT_SECONDS, interval=POLL_INTERVAL_SECONDS):
    """轮询直到 predicate 为真（最终一致窗口内收敛），超时返回最后一次结果（调用方断言）。"""
    deadline = time.monotonic() + timeout
    while True:
        value = predicate()
        if value or time.monotonic() >= deadline:
            return value
        time.sleep(interval)


# ---------------------------------------------------------------------------
# 前置：MQ 可达性 / 收件箱类断言的 skip 口径
# ---------------------------------------------------------------------------

def _app_mq_port():
    """应用实际使用的 MQ 端口：AppConfig 覆盖链 = 环境变量 `RABBITMQ_PORT` > `app.properties` 的 rabbitmq.port。

    降级跑（`RABBITMQ_PORT=5699 python tools/tv.py test`）把应用指向不可达端口，故必须按**同一**覆盖链
    解析，才能判断"本次跑，写扩散链路是否可能产出收件箱"。
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
    """MQ 不可达 → skip：写扩散靠 MQ 投递，降级跑下该链路本就不产出收件箱，超时失败不代表回归。"""
    port = _app_mq_port()
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return
    except OSError:
        pytest.skip(f"应用配置的 RabbitMQ(127.0.0.1:{port}) 不可达（降级跑），跳过写扩散 e2e 用例")


def _require_fanout_env():
    """收件箱类断言的共同前置：MQ 可达（否则收件箱不会出现）+ Redis 可读（否则读不出断言）。"""
    _require_mq_reachable()
    _require_redis_container()


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def push_context(base_url, test_files):
    """module 级：临时作者 + 临时粉丝（fan 关注 author）+ 一条新发布内容。

    teardown：作者软删该内容 + 取关复原（本模块自建自清，不依赖外部会话用户状态）。
    **本 fixture 不做环境 skip**——发布与 `/feed` 在 MQ 降级时同样应正常，故前置检查放在
    各"收件箱类断言"用例内（见 `_require_fanout_env`），以保证用例 4（读路径哨兵）在降级跑下仍执行。
    """
    author = _register_fresh_user("author")
    fan = _register_fresh_user("fan")
    stranger = _register_fresh_user("stranger")

    add = _follow(base_url, fan["token"], author["id"], "add")
    assert add.get("code") == 200, f"follow/add failed: {add}"

    content_id = None
    try:
        content_id = _post_content(base_url, author["token"], "feed1-18 写扩散用例", test_files)
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
    """T18：写扩散（影子期只写不读）——收件箱增量正确、非粉丝不受影响、读路径零改动。"""

    def test_fanout_writes_inbox_for_follower(self, push_context):
        """粉丝收件箱在收敛窗口内出现新 contentId（最终一致）。"""
        _require_fanout_env()
        key = INBOX_PREFIX + str(push_context["fan"]["id"])
        content_id = push_context["content_id"]

        score = _poll(lambda: _zscore(key, content_id))

        assert score is not None, f"粉丝收件箱未含新内容: key={key}, contentId={content_id}"
        # score = contentId（自增单调 ⇒ 降序即内容倒序，与拉模式 ORDER BY create_time DESC, id DESC 同口径）
        assert score == float(content_id), f"收件箱 score 应等于 contentId: {score}"

    def test_inbox_key_carries_ttl(self, push_context):
        """收件箱带 TTL（到期整条回收，不是单条内容过期）。"""
        _require_fanout_env()
        key = INBOX_PREFIX + str(push_context["fan"]["id"])
        content_id = push_context["content_id"]
        assert _poll(lambda: _zscore(key, content_id)) is not None, "前置：收件箱应先收敛"

        assert _ttl(key) > 0, f"收件箱应带正 TTL: TTL={_ttl(key)}"

    def test_non_follower_inbox_not_affected(self, push_context):
        """非粉丝（无关注关系）的收件箱既无该条、也不存在 inbox key。"""
        _require_fanout_env()
        fan_key = INBOX_PREFIX + str(push_context["fan"]["id"])
        content_id = push_context["content_id"]
        # 等 fanout 处理完（粉丝侧收敛 = 该消息已消费），再断言非粉丝侧
        assert _poll(lambda: _zscore(fan_key, content_id)) is not None, "前置：收件箱应先收敛"

        stranger_key = INBOX_PREFIX + str(push_context["stranger"]["id"])
        assert _zscore(stranger_key, content_id) is None, \
            f"非粉丝收件箱不应含该内容: key={stranger_key}"
        assert _zcard(stranger_key) == 0, f"非粉丝不应存在收件箱 key: key={stranger_key}"

    def test_feed_read_path_unchanged(self, base_url, push_context):
        """红线哨兵：/feed 拉模式信封口径不变，且能取到刚发布的内容（读路径零改动）。

        **不做环境 skip**：MQ / Redis 降级时本用例仍应通过——这正是"业务链路不依赖推"的证据。
        """
        body = _feed(base_url, push_context["fan"]["token"])

        assert body.get("code") == 200, f"/feed failed: {body}"
        data = body.get("data") or {}
        assert set(data.keys()) == _ENVELOPE_KEYS, f"/feed 信封字段集不符: {data}"
        ids = [it.get("id") for it in (data.get("list") or [])
               if it.get("authorId") == push_context["author"]["id"]]
        assert push_context["content_id"] in ids, \
            f"/feed 应取到刚发布内容 {push_context['content_id']}: {ids}"
