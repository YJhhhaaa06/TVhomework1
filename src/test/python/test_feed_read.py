# -*- coding: utf-8 -*-
"""
test_feed_read.py - feed 读侧「两路读 + 归并截断」端到端测试（feed2-23 T23 建）

背景：T23 起 `/feed` 不再纯拉——先过**读态闸门**（`feed_inbox_sync` 有行才走窗口），
再读**两路**：① 收件箱窗口（`feed_inbox` 真相表 → `feed:inbox:{id}` 可降级读缓存）；
② 大V发件箱窗口（**不落表**，读时按作者取最近 N 条 + `feed:outbox:{authorId}` 缓存）；
两路**归并去重 → contentId 降序 → 截断读侧总窗口 M** → 按 `(page-1)*pageSize` 切页；
**深翻（越过窗口）→ 空 list 且 `total` 保持窗口实际条数**；未同步 → 回退既有纯拉。

覆盖：
  1. 两路合并后与**独立 oracle 逐条相等**（首屏）+ `total` / `totalPages` 口径。
  2. 逐页切片（`pageSize=2`）：每页 == oracle 切片；末页可短。
  3. 深翻：`page = totalPages + 2` → 空 list，`total` 不变。
  4. **"短页 ≠ 到底"**：窗口内一条内容被作者软删 ⇒ 页内跳过 ⇒ 返回少于 pageSize，
     但 `total` 仍是窗口条数（前端据此才不会提前停住，见 `chunkedList.js` 的 `shortPageMeansEnd`）。
  5. 未同步（全新用户：无关注、无 `feed_inbox_sync` 行）→ `/feed` 正常返回空信封（走纯拉的判定分支）。
  6. 收件箱腿回填：读过 `/feed` 后 `feed:inbox:{fan}` 缓存存在（miss → DB → 回填）。
  7. 大V发件箱腿：被关注作者按应用参数为大V时，其内容**不在** `feed_inbox`，但 `/feed` 仍能取到
     （来自 outbox 腿），且 `feed:outbox:{author}` 缓存存在。

读取手段与跳过口径：
  - **MySQL（独立 oracle 与真相断言）**：`mysql.exe` 子进程（沿 `test_feed_rebuild.py` 先例），
    连接参数取 `run_tests.py` 注入的 `DB_*`。**只发 SELECT**（本文件不写库）。
  - **Redis（只读 `PING` / `EXISTS`）**：`docker exec <容器> redis-cli`（零新依赖）。
  - **MQ 可达性**：按 AppConfig 同一覆盖链解析 `rabbitmq.port`（`RABBITMQ_PORT` > app.properties）。
  - 用例 1~4 / 6 / 7 需重建收敛或缓存，故带环境 skip；**用例 5 不 skip**（降级跑下同样成立）。

两组互斥（同一跑只覆盖一组）：
  - **默认参数**（`feed.bigv.threshold=10000` + 空名单）⇒ 被关注作者不是大V ⇒ 覆盖用例 1~6；
  - **`FEED_BIGV_THRESHOLD=1 python tools\\tv.py test`** ⇒ 作者成为大V ⇒ 覆盖用例 7（1~4 自动 skip）。

阈值 / 名单 / N / M **一律按 app.properties + `FEED_*` 环境变量同源读取**，oracle 与应用同参。

数据自建自清：每个用例各自注册全新临时用户（`testA_` 前缀，命中 tools/cleanup_data.py 白名单，
命名 `testA_fread_*`），内容在 teardown 由作者走真实删除路径软删。
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

# 项目根（按 AppConfig 覆盖链解析应用实际使用的 MQ 端口 / 参数用）
_PROJECT_ROOT = Path(__file__).resolve().parents[3]

# 应用所连的 Redis 容器（app.properties 固定 localhost:6379 → 该容器，db 0、无密码）
REDIS_CONTAINER = "redis"

# 缓存 key 前缀（须与 cache.CacheKeys 同步）
INBOX_PREFIX = "feed:inbox:"
OUTBOX_PREFIX = "feed:outbox:"

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
_DEFAULT_READ_WINDOW_MAX = 300
_DEFAULT_OUTBOX_WINDOW_SIZE = 20
_DEFAULT_BIGV_THRESHOLD = 10000


# ---------------------------------------------------------------------------
# HTTP 辅助
# ---------------------------------------------------------------------------

def _register_fresh_user(tag):
    """注册一个全新的 testA_ 前缀用户（无任何关注关系），返回 id/token/username。"""
    unique = uuid.uuid4().hex[:8]
    suffix = str(int(unique, 16))[-8:].zfill(8)
    username = f"testA_fread_{tag}_{unique}"
    body = conftest.register_user(username, f"139{suffix}", "abc123")
    assert body.get("code") == 200, f"注册临时用户失败: {body}"
    data = body.get("data") or {}
    return {"id": data.get("id"), "token": data.get("token"), "username": username}


def _follow(base_url, token, followed_user_id, action):
    """follow/add | follow/remove。"""
    return requests.post(
        f"{base_url}/follow/{action}",
        headers={"token": token, "Content-Type": "application/x-www-form-urlencoded"},
        data={"followedUserId": str(followed_user_id)},
        timeout=10,
    ).json()


def _post_content(base_url, token, title, test_files):
    """POST /api/upload/post 建一条图文内容（真实写路径），返回 contentId。"""
    with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
        resp = requests.post(
            f"{base_url}/api/upload/post",
            headers={"token": token},
            data={"title": title, "description": "feed2-23 读侧两路 e2e", "categoryId": "0"},
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


def _feed_ids(body):
    """/feed 响应 → 内容 id 列表（按响应顺序）。"""
    data = body.get("data") or {}
    return [it.get("id") for it in (data.get("list") or [])]


# ---------------------------------------------------------------------------
# Redis 只读辅助（docker exec redis-cli）
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
        pytest.skip("mysql 客户端不可用，无法复算读侧期望窗口")
    try:
        _run_sql("SELECT 1")
    except Exception as exc:      # noqa: BLE001 - 通道不可用一律 skip，避免误报失败
        pytest.skip(f"测试库不可达，无法复算读侧期望窗口: {exc}")


def _app_prop(key, default):
    """按 app.properties 读取配置（缺键 → 默认值，与 AppConfig 的带默认值读取同源）。"""
    props = _PROJECT_ROOT / "src" / "main" / "resources" / "app.properties"
    for line in props.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith(key + "="):
            value = stripped.split("=", 1)[1].strip()
            return value if value else default
    return default


def _read_params():
    """读侧参数：M（总窗口）/ N（大V每作者条数）——环境变量优先，与应用同参。"""
    env_m = os.environ.get("FEED_READWINDOWMAX")
    read_max = int(env_m) if env_m else int(_app_prop("feed.readWindowMax", _DEFAULT_READ_WINDOW_MAX))
    env_n = os.environ.get("FEED_OUTBOX_WINDOWSIZE")
    outbox_n = int(env_n) if env_n else int(_app_prop("feed.outbox.windowSize",
                                                      _DEFAULT_OUTBOX_WINDOW_SIZE))
    return read_max, outbox_n


def _bigv_params():
    """大V判定参数（阈值 + 名单）——环境变量优先，与应用同参（同 `_read_params` 口径）。"""
    env_t = os.environ.get("FEED_BIGV_THRESHOLD")
    threshold = int(env_t) if env_t else int(_app_prop("feed.bigv.threshold", _DEFAULT_BIGV_THRESHOLD))
    raw = os.environ.get("FEED_BIGV_USERIDS")
    if raw is None:
        raw = _app_prop("feed.bigv.userIds", "")
    listed = [int(t) for t in raw.split(",") if t.strip().isdigit()]
    return threshold, listed


def _bigv_subquery(fan_id, threshold, listed):
    """该用户关注的**大V作者**子集（口径与应用读侧批量判定同源：DB 真值 `users.follower_count`）。"""
    cond = f"u.follower_count >= {threshold}"
    if listed:
        cond += " OR u.id IN (" + ",".join(str(i) for i in listed) + ")"
    return (
        "SELECT f.followed_user_id FROM follow f"
        f" WHERE f.user_id = {int(fan_id)}"
        f"   AND f.followed_user_id IN (SELECT u.id FROM users u WHERE {cond})"
    )


def _oracle_read_window(fan_id):
    """独立 oracle：`/feed` **应有**的可见窗口（两路读 → 归并去重 → 降序 → 截断 M）。

    口径（NEEDS 4.0 / TASKS T23 对齐补录）：
      ① 收件箱腿 = `feed_inbox` 该用户全部行；
      ② 大V腿 = 关注的**大V作者**各自最近 N 条（`content.id` 降序，`is_deleted = 0`）；
      ③ 归并去重 → contentId 降序 → 截断到 M。
    大V判定：阈值 / 名单与应用**同参**，粉丝数取 DB 真值（应用读侧批量判定同样走 DB ⇒ 同源）。
    """
    read_max, outbox_n = _read_params()
    threshold, listed = _bigv_params()
    sql = (
        "SELECT DISTINCT x.content_id FROM ("
        f"  SELECT content_id FROM feed_inbox WHERE user_id = {int(fan_id)}"
        "   UNION ALL"
        "   SELECT t.content_id FROM ("
        "     SELECT c.id AS content_id,"
        "            ROW_NUMBER() OVER (PARTITION BY c.user_id ORDER BY c.id DESC) AS rn"
        "     FROM content c"
        f"     WHERE c.is_deleted = 0 AND c.user_id IN ({_bigv_subquery(fan_id, threshold, listed)})"
        f"   ) t WHERE t.rn <= {outbox_n}"
        ") x ORDER BY x.content_id DESC"
        f" LIMIT {read_max}"
    )
    return [int(line.strip()) for line in _run_sql(sql).splitlines() if line.strip()]


def _db_inbox_ids(fan_id):
    """DB 真相：该用户 `feed_inbox` 窗口（content_id 降序）。"""
    out = _run_sql(
        "SELECT content_id FROM feed_inbox "
        f"WHERE user_id = {int(fan_id)} ORDER BY content_id DESC"
    )
    return [int(line.strip()) for line in out.splitlines() if line.strip()]


def _db_synced(fan_id):
    """窗口同步状态：`feed_inbox_sync` 是否存在该用户行（**存在即已同步**）。"""
    out = _run_sql(f"SELECT COUNT(*) FROM feed_inbox_sync WHERE user_id = {int(fan_id)}")
    return out.strip() == "1"


# ---------------------------------------------------------------------------
# 前置：环境 skip 口径
# ---------------------------------------------------------------------------

def _app_mq_port():
    """应用实际使用的 MQ 端口：AppConfig 覆盖链 = 环境变量 `RABBITMQ_PORT` > `app.properties`。"""
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
    """MQ 不可达 → skip：窗口靠"关注触发重建"产出，降级跑下该链路本就不产出窗口。"""
    port = _app_mq_port()
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return
    except OSError:
        pytest.skip(f"应用配置的 RabbitMQ(127.0.0.1:{port}) 不可达（降级跑），跳过读侧窗口 e2e 用例")


def _require_inbox_leg_env():
    """收件箱腿用例的共同前置：MQ 可达（重建产窗口）+ MySQL 可用 + 被关注作者**不是大V**。

    若 `FEED_BIGV_*` 把作者判成大V，其内容不进 `feed_inbox`（改走 outbox 腿）⇒ 这批用例的
    前提不成立，直接 skip（改跑默认参数即可；发件箱腿由用例 7 在 `FEED_BIGV_THRESHOLD=1` 下覆盖）。
    """
    _require_mq_reachable()
    _require_mysql()
    threshold, listed = _bigv_params()
    if threshold <= 1 or listed:
        pytest.skip("FEED_BIGV_* 使被关注作者可能是大V，收件箱腿用例不适用（用默认参数跑）")


def _require_outbox_leg_env():
    """发件箱腿用例前置：需"被关注作者按应用参数为大V"——默认参数下构造不出，故要求显式配置。"""
    _require_mq_reachable()
    _require_mysql()
    threshold, listed = _bigv_params()
    if not listed and threshold > 1:
        pytest.skip("未配置大V（FEED_BIGV_USERIDS / FEED_BIGV_THRESHOLD），跳过发件箱腿 e2e")


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------

def _scenario(base_url, test_files, content_count):
    """自建自清：全新作者 + 全新粉丝；**作者先发 `content_count` 条、粉丝后关注**（触发重建）。

    刻意"先发后关"：这些内容发布时粉丝还不是粉丝 ⇒ 无 fanout 消息 ⇒ 窗口只能由**重建**写入，
    这样断言才真正证明"窗口来自重建 + 读侧两路"。默认只发 1 条（上传是 e2e 里最贵的动作，
    逐页 / 短页用例才需要更多，见 `scenario_three`）。**本 fixture 不做环境 skip**。
    """
    author = _register_fresh_user("author")
    fan = _register_fresh_user("fan")
    contents = []
    try:
        for i in range(content_count):
            contents.append(_post_content(base_url, author["token"], f"feed2-23 读侧 e2e #{i}", test_files))
        add = _follow(base_url, fan["token"], author["id"], "add")
        assert add.get("code") == 200, f"follow/add failed: {add}"
        yield {"author": author, "fan": fan, "contents": contents}
    finally:
        _follow(base_url, fan["token"], author["id"], "remove")
        for content_id in contents:
            _delete_content(base_url, author["token"], content_id)


@pytest.fixture
def scenario(base_url, test_files):
    """1 条内容的场景（首屏 oracle / 深翻 / 缓存回填 / 发件箱腿）。"""
    yield from _scenario(base_url, test_files, 1)


@pytest.fixture
def scenario_three(base_url, test_files):
    """**3 条**内容的场景（逐页切片 / 软删短页 —— 需要多页与"删中间一条"）。"""
    yield from _scenario(base_url, test_files, 3)


def _await_window_converged(fan_id):
    """等重建收敛：同步状态已写 **且** DB 窗口 == 独立 oracle。"""
    return _poll(lambda: _db_synced(fan_id) and _db_inbox_ids(fan_id) == _window_oracle_ids(fan_id))


def _window_oracle_ids(fan_id):
    """收件箱腿 oracle = 该用户的 DB 窗口（重建产物）；与两路口径一致（非大V场景）。"""
    return _oracle_read_window(fan_id)


@pytest.mark.boundary
class TestFeedReadWindow:
    """T23：两路读 + 归并截断 M + 深翻禁止 + 短页不判到底。"""

    def test_synced_feed_matches_two_leg_oracle(self, scenario, base_url):
        """首屏：`/feed` 的 id 序 == 独立 oracle 的前 pageSize 条；`total` / `totalPages` 口径正确。"""
        _require_inbox_leg_env()
        fan_id = scenario["fan"]["id"]

        assert _await_window_converged(fan_id), (
            f"窗口未收敛: synced={_db_synced(fan_id)} inbox={_db_inbox_ids(fan_id)}"
        )
        oracle = _oracle_read_window(fan_id)
        assert set(scenario["contents"]) <= set(oracle), "前置：oracle 应含刚发布的 3 条（否则断言空转）"

        body = _feed(base_url, scenario["fan"]["token"], page=1, page_size=100)

        assert body.get("code") == 200, f"/feed failed: {body}"
        data = body.get("data") or {}
        assert set(data.keys()) == _ENVELOPE_KEYS, f"/feed 信封字段集不符: {data}"
        assert _feed_ids(body) == oracle[:100], f"/feed 应与 oracle 前 100 条逐条相等: {data}"
        assert data.get("total") == len(oracle), "total = 归并窗口实际条数（≤ M）"
        assert data.get("totalPages") == (len(oracle) + 99) // 100, "totalPages 由 total / pageSize 推导"

    def test_paging_slices_window_into_pages(self, scenario_three, base_url):
        """`pageSize=2` 逐页切片：每页 == oracle 对应切片；末页可为短页。"""
        _require_inbox_leg_env()
        scenario = scenario_three
        fan_id = scenario["fan"]["id"]
        assert _await_window_converged(fan_id), "前置：窗口应先收敛"
        oracle = _oracle_read_window(fan_id)

        page1 = _feed(base_url, scenario["fan"]["token"], page=1, page_size=2)
        page2 = _feed(base_url, scenario["fan"]["token"], page=2, page_size=2)

        assert _feed_ids(page1) == oracle[0:2], f"第 1 页应为 oracle[0:2]: {page1}"
        assert _feed_ids(page2) == oracle[2:4], f"第 2 页应为 oracle[2:4]、且可能为短页: {page2}"
        assert page1["data"]["total"] == page2["data"]["total"] == len(oracle), "各页 total 一致"

    def test_deep_page_beyond_window_is_empty_keeps_total(self, scenario, base_url):
        """深翻：越过窗口 → **空 list**，`total` 保持不变（前端据此自然停止翻页）。"""
        _require_inbox_leg_env()
        fan_id = scenario["fan"]["id"]
        assert _await_window_converged(fan_id), "前置：窗口应先收敛"
        oracle = _oracle_read_window(fan_id)
        total_pages = (len(oracle) + 1) // 2          # pageSize=2

        body = _feed(base_url, scenario["fan"]["token"], page=total_pages + 2, page_size=2)

        assert body.get("code") == 200, f"/feed failed: {body}"
        assert _feed_ids(body) == [], "越过窗口应返回空页（深翻禁止）"
        assert body["data"]["total"] == len(oracle), "空页的 total 仍为窗口实际条数"

    def test_deleted_content_shrinks_page_but_keeps_total(self, scenario_three, base_url):
        """**"短页 ≠ 到底"**：窗口内一条被软删 ⇒ 页内跳过 ⇒ 返回条数 < pageSize，但 `total` 不变。

        （前端 `chunkedList.shortPageMeansEnd=false` 的前提就是这条：短页不得判"已到底"。）
        """
        _require_inbox_leg_env()
        scenario = scenario_three
        fan_id = scenario["fan"]["id"]
        assert _await_window_converged(fan_id), "前置：窗口应先收敛"
        oracle = _oracle_read_window(fan_id)
        assert len(oracle) == 3, f"前置：本例期望窗口恰 3 条，实际 {oracle}"

        # 删掉窗口里"最旧"的那条（oracle 降序的最后一位）——仍在 feed_inbox 行内，但内容已软删
        removed = oracle[-1]
        assert _delete_content(base_url, scenario["author"]["token"], removed).get("code") == 200

        body = _feed(base_url, scenario["fan"]["token"], page=1, page_size=3)

        ids = _feed_ids(body)
        assert removed not in ids, "已软删内容不应出现在页内（页内跳过 null）"
        assert len(ids) == 2, f"页内跳过一条 ⇒ 返回 2 条（短于 pageSize=3）: {ids}"
        assert body["data"]["total"] == 3, "total 仍为窗口条数（含被跳过者）⇒ 短页不是到底"

    def test_unsynced_user_gets_empty_envelope_without_error(self, base_url):
        """未同步（全新用户：无关注、无 `feed_inbox_sync` 行）→ `/feed` 走纯拉分支并正常返回空信封。

        **不做环境 skip**：降级跑（MQ / oracle 不可用）下同样成立。
        """
        fresh = _register_fresh_user("unsynced")

        body = _feed(base_url, fresh["token"], page=1, page_size=10)

        assert body.get("code") == 200, f"/feed failed: {body}"
        data = body.get("data") or {}
        assert set(data.keys()) == _ENVELOPE_KEYS, f"/feed 信封字段集不符: {data}"
        assert data.get("list") == [] and data.get("total") == 0, "无关注 ⇒ 空窗口"

    def test_inbox_cache_is_backfilled_after_read(self, scenario, base_url):
        """收件箱腿回填：读过 `/feed` 后 `feed:inbox:{fan}` 缓存应存在（miss → DB → 回填）。"""
        _require_inbox_leg_env()
        _require_redis_container()
        fan_id = scenario["fan"]["id"]
        assert _await_window_converged(fan_id), "前置：窗口应先收敛"

        body = _feed(base_url, scenario["fan"]["token"], page=1, page_size=100)
        assert body.get("code") == 200, f"/feed failed: {body}"

        assert _poll(lambda: _exists(INBOX_PREFIX + str(fan_id)) == 1), (
            f"读过 /feed 后收件箱读缓存应被回填（EXISTS {INBOX_PREFIX}{fan_id} == 0）"
        )

    def test_bigv_leg_reads_outbox_not_inbox(self, scenario, base_url):
        """大V发件箱腿：作者内容**不进** `feed_inbox`，但 `/feed` 仍取得到（来自 outbox 腿）。

        需 `FEED_BIGV_THRESHOLD=1`（或名单命中）运行整套；默认参数下自动 skip。
        """
        _require_outbox_leg_env()
        _require_redis_container()
        author = scenario["author"]
        fan_id = scenario["fan"]["id"]

        # 大V ⇒ 重建排除该作者 ⇒ 窗口为空（但同步状态写出，"空但已同步"）
        assert _poll(lambda: _db_synced(fan_id) and _db_inbox_ids(fan_id) == []), (
            f"大V内容不应进收件箱窗口: synced={_db_synced(fan_id)} inbox={_db_inbox_ids(fan_id)}"
        )

        body = _feed(base_url, scenario["fan"]["token"], page=1, page_size=100)
        ids = _feed_ids(body)

        assert body.get("code") == 200, f"/feed failed: {body}"
        assert set(scenario["contents"]) <= set(ids), (
            f"大V内容应由发件箱腿取到: expected⊆{ids}"
        )
        assert _poll(lambda: _exists(OUTBOX_PREFIX + str(author["id"])) == 1), (
            f"发件箱读缓存应被回填（EXISTS {OUTBOX_PREFIX}{author['id']} == 0）"
        )
