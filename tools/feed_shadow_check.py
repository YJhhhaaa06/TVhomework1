# -*- coding: utf-8 -*-
"""feed 窗口核对（feed2-25 T25 改写；原 feed1-20 T20 影子工具）——**只读**。

定位 = **二期切读后的窗口观测基线**。T21 起收件箱已落表、T22 起重建"只失效不写"、T23 起
`/feed` 走有界窗口两路读 ⇒ 一期口径（以 Redis 完整态标记 `feed:inbox:full:` 分组、以 Redis
ZSet 为比对对象、oracle = 拉模式全量）已完全失真。本工具按二期事实改写：

**真相源 = DB**（`feed_inbox` 窗口 + `feed_inbox_sync` 同步状态）；Redis `feed:inbox:{userId}`
= **可降级读缓存**（写后失效、读 miss 回源回填），**无正确性含义**，故只作"回填覆盖率"观测。

核对对象与口径：
  * **DB 窗口**（真相）= 直连 MySQL：`SELECT content_id FROM feed_inbox WHERE user_id = {uid}
    ORDER BY content_id DESC`（重建产物 + fanout 增量）。
  * **独立 oracle**（重建口径，与 `src/test/python/test_feed_rebuild.py` 的 oracle **同源**：
    每关注作者最近 K 条 → 归并去重 → contentId 降序 → 裁剪 C、排除大V）：

        SELECT content_id FROM (
          SELECT c.id AS content_id,
                 ROW_NUMBER() OVER (PARTITION BY c.user_id ORDER BY c.id DESC) AS rn
          FROM content c
          WHERE c.user_id IN (SELECT followed_user_id FROM follow WHERE user_id = {uid})
            AND c.is_deleted = 0
            AND c.user_id NOT IN (SELECT u.id FROM users u WHERE u.follower_count >= {阈值})
        ) w WHERE w.rn <= {K} ORDER BY w.content_id DESC LIMIT {C}

    关注者集合取 `follow` 表（DB 真相）、**不拿应用自己的读路径当基准**（那会"同样错就看不出来"）。

    ⚠️ **大V判定来源差异（登记）**：oracle 的粉丝数取 DB 真值 `users.follower_count`，而重建侧
    `FeedBigVRouter.isBigV` 走 `FollowCache.getFollowerCount` 计数**缓存**——阈值 / 名单**同参、来源不同**
    （同 `NEXT_CYCLE_TASKS.md` T22 残余⑥口径）。计数漂移（缓存未回填 / 陈旧）时两侧对大V的判定可能
    不一致（一侧排除该作者内容、另一侧未排除）⇒ 表现为 `missing` 的**假阳性**；宜在计数收敛后判读。

**分组语义（核心）**：
  * **[A] 已同步**（`feed_inbox_sync` 有该用户行 = 曾成功重建过）⇒ **判定组**：
    `missing = oracle \\ DB窗口` ⇒ **有缺失即不一致（判失败）**；
    `extra = DB窗口 \\ oracle` ⇒ **只报告、不判失败**（合法来源：重建后 fanout 追加的新内容 /
    表侧非严格有界 / 大V判定 fail-open 的历史残留；见 NEEDS 4.0 T22 残余②④）。
  * **[B] 未同步**（只有 `feed_inbox` 散行、无同步状态）⇒ **完整性未知、不是缺陷**：该用户从没
    成功重建过（fanout 只追增、从不写同步状态）⇒ 其窗口不可作为读源（读侧回退纯拉），只计数。

**Redis（best-effort）**：主判定完全不依赖 Redis。docker / 容器 / PING 不可用 ⇒ 缓存覆盖率记为
`null` + 一条提示，**不影响退出码**。可用时扫描 `feed:inbox:*` 统计**已同步用户中数据 key 的
回填覆盖率**；对存在的 key 比对成员集合 vs DB 窗口，差异只作 `cache_diff` 观测（不判失败）。

**扫描与前缀排除**：一期完整态标记 `feed:inbox:full:` **以** `feed:inbox:` 开头（前缀重叠）⇒
遍历时必须显式排除（残留 key 由 TTL 回收，仍须防御）；本工具按"先判长前缀再判短前缀 + 尾段强制
数字"双保险。`feed:rebuild:lock:` 不匹配该 glob，分类函数仍防御性排除。universe 取 **DB**
（`feed_inbox` ∪ `feed_inbox_sync` 的 user_id），不再取 Redis key。

只读红线：**全部** Redis 命令经 `redis_cmd` 白名单（`PING/EXISTS/ZREVRANGE`）；扫描走
`redis_scan`（`--scan --pattern` 与 pattern **均硬编码**，无外部输入）。MySQL 只发**单条**
`SELECT`（禁 `;` 多语句；另拒 `INTO OUTFILE` / `INTO DUMPFILE` / `FOR UPDATE` /
`LOCK IN SHARE MODE`）；响应解析经 `_as_int` 兜底（类型不符 / 服务端错误 → 友好文案而非
Traceback）。**不写 / 不删 / 不改** Redis、DB、broker；输出只落 stdout（`--json` 留给机器
消费），**不生成任何落盘文件**。

数据源：
  * Redis：子进程 `docker exec <容器> redis-cli …`（容器名默认 `redis`；应用经 app.properties
    连 localhost:6379 = 该容器，db 0、无密码）。
  * MySQL：`mysql.exe` 子进程；连接参数取 `db_config`（经 `tv.py` 调用时被注入 `DB_*`，保证
    "声明环境 == 实际连接库"）；密码经 `MYSQL_PWD` 环境变量传递（不进 argv）。

退出码：0 = 核对完成且**所有"已同步"窗口均无缺失成员**（含存在未同步组、含**空 universe 零数据
报告**）；1 = 存在"已同步但缺成员（missing）"；2 = 参数 / **DB 通道**错误（缺 mysql 客户端、
连库失败、只读白名单拒绝——友好文案，**无 Traceback**）。Redis 通道不可用**不置 2**（只把缓存
覆盖率记 null）。

判读提示：核对是**快照**测量——若恰逢 fanout 在写或重建在替换窗口，"missing"可能是瞬时；本工具
**不加轮询重试**（轮询是 pytest 的手段），宜在低写入窗口执行。
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

import db_config  # tools/ 同目录；模块导入即加载 active.conf（缺失回退 test）

PROJECT_ROOT = Path(__file__).resolve().parent.parent

# 前缀常量（须与 src/main/java/com/itheima/cache/CacheKeys.java 同步）
INBOX_PREFIX = "feed:inbox:"
INBOX_FULL_PREFIX = "feed:inbox:full:"        # 一期遗留标记（已退役）；前缀重叠 ⇒ 遍历时必须先判
REBUILD_LOCK_PREFIX = "feed:rebuild:lock:"

# Redis 只读命令白名单：这是该通道能到达 redis-cli 的**全部** token（写防线）
REDIS_READ_COMMANDS = frozenset({"PING", "EXISTS", "ZREVRANGE"})
SCAN_PATTERN_INBOX = INBOX_PREFIX + "*"
DEFAULT_REDIS_CONTAINER = "redis"

COMMON_MYSQL_PATHS = (
    Path(r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"),
    Path(r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"),
)

# 窗口参数默认值（须与 AppConfig 缺省一致）
DEFAULT_WINDOW_PER_AUTHOR = 20
DEFAULT_WINDOW_MAX = 200
DEFAULT_BIGV_THRESHOLD = 10000

DEFAULT_TIMEOUT = 30
DEFAULT_MAX_DETAIL = 100
DETAIL_SAMPLE = 20                     # 文本输出里最多展示的缺失 / 多余样本数


class ChannelError(RuntimeError):
    """外部通道不可用或只读白名单拒绝（docker / 容器 / mysql 客户端 / 连库失败）→ 退出码 2。"""


class DbChannelError(ChannelError):
    """DB 通道错误（缺 mysql / 连库失败 / 查询被守卫拒）→ 退出码 2（Redis 错误不属此类）。"""


def die(message: str, code: int = 2) -> None:
    print(f"feed-shadow 错误: {message}", file=sys.stderr)
    sys.exit(code)


def _as_int(raw: str, what: str) -> int:
    """把 redis-cli / mysql 的文本响应转 int；服务端错误或类型不符 → ChannelError。

    守住"参数 / 通道错误一律友好文案、**无 Traceback**"的契约不被裸 `ValueError` 打破。
    """
    try:
        return int(raw.strip())
    except ValueError as exc:
        raise ChannelError(f"无法解析 {what} 的响应（可能类型不符 / 服务端错误）: {raw!r}") from exc


# ---------------------------------------------------------------------------
# 参数 / 配置
# ---------------------------------------------------------------------------

def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="feed_shadow_check.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=(
            "feed 窗口核对（只读）：DB 真相 feed_inbox 与重建 oracle 比对。\n"
            "按\"窗口同步状态 feed_inbox_sync 是否存在\"分组——已同步 = 判定组（缺成员即失败）；"
            "未同步 = 完整性未知（设计预期，不判失败）。"
        ),
        epilog=(
            "示例:\n"
            "  python tools\\feed_shadow_check.py                    # 全量扫描所有已同步 / 有散行的用户\n"
            "  python tools\\feed_shadow_check.py --user-id 42       # 精查单个用户\n"
            "  python tools\\feed_shadow_check.py --json             # 机器可读（直调本脚本，经 tv.py 会有横幅）\n"
            "  python tools\\tv.py feed-shadow                       # 经统一入口（注入声明环境的 DB_*）\n"
        ),
    )
    parser.add_argument("--user-id", type=int, default=None,
                        help="只核对该用户（精查）；缺省 = 全量扫描（DB 侧 universe）")
    parser.add_argument("--json", action="store_true", help="只输出一个 JSON 对象（机器可读）")
    parser.add_argument("--redis-container", default=DEFAULT_REDIS_CONTAINER,
                        help=f"Redis 容器名（默认 {DEFAULT_REDIS_CONTAINER}；不可用时缓存覆盖率记 null）")
    parser.add_argument("--limit", type=int, default=None,
                        help="全量模式最多核对 K 个用户（opt-in；默认不限，超限截断并提示）")
    parser.add_argument("--max-detail", type=int, default=DEFAULT_MAX_DETAIL,
                        help=f"JSON 中 missing / extra 明细上限（默认 {DEFAULT_MAX_DETAIL}）")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT,
                        help=f"单条子进程超时秒数（默认 {DEFAULT_TIMEOUT}）")
    args = parser.parse_args(argv)
    if args.user_id is not None and args.user_id <= 0:
        parser.error("--user-id 必须为正整数")
    if args.limit is not None and args.limit < 1:
        parser.error("--limit 必须 >= 1")
    if args.max_detail < 1:
        parser.error("--max-detail 必须 >= 1")
    if args.timeout < 1:
        parser.error("--timeout 必须 >= 1")
    return args


def _app_prop(key: str, default: str) -> str:
    """按 app.properties 读取配置（缺键 → 默认值，与 AppConfig 的带默认值读取同源）。"""
    props = PROJECT_ROOT / "src" / "main" / "resources" / "app.properties"
    try:
        lines = props.read_text(encoding="utf-8").splitlines()
    except OSError:
        return default
    for line in lines:
        stripped = line.strip()
        if stripped.startswith(key + "="):
            value = stripped.split("=", 1)[1].strip()
            return value if value else default
    return default


def _oracle_params() -> tuple[int, int, int, list[int]]:
    """oracle 参数：K（每作者条数）/ C（总窗口上限）/ 大V阈值 / 大V名单（env 覆盖链，与 pytest 同源）。"""
    env_k = os.environ.get("FEED_INBOX_WINDOWPERAUTHOR")
    per_author = int(env_k) if env_k else int(_app_prop("feed.inbox.windowPerAuthor",
                                                        str(DEFAULT_WINDOW_PER_AUTHOR)))
    env_c = os.environ.get("FEED_INBOX_WINDOWMAX")
    window_max = int(env_c) if env_c else int(_app_prop("feed.inbox.windowMax",
                                                        str(DEFAULT_WINDOW_MAX)))
    env_t = os.environ.get("FEED_BIGV_THRESHOLD")
    threshold = int(env_t) if env_t else int(_app_prop("feed.bigv.threshold",
                                                       str(DEFAULT_BIGV_THRESHOLD)))
    raw = os.environ.get("FEED_BIGV_USERIDS")
    if raw is None:
        raw = _app_prop("feed.bigv.userIds", "")
    listed = [int(t) for t in raw.split(",") if t.strip().isdigit()]
    return per_author, window_max, threshold, listed


# ---------------------------------------------------------------------------
# Redis 只读通道（best-effort：不可用不置 2）
# ---------------------------------------------------------------------------

def _run_redis(container: str, timeout: int, *args: object) -> str:
    """Redis 通道的唯一出口：子进程 `docker exec <容器> redis-cli …`。"""
    if shutil.which("docker") is None:
        raise ChannelError("docker 不可用（缓存覆盖率是附加观测，不是被测能力）")
    try:
        proc = subprocess.run(
            ["docker", "exec", container, "redis-cli", *[str(a) for a in args]],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        raise ChannelError(f"redis-cli 执行超时({timeout}s): " + " ".join(str(a) for a in args)) from exc
    except OSError as exc:
        raise ChannelError("docker 启动失败: " + str(exc)) from exc
    if proc.returncode != 0:
        raise ChannelError("redis-cli 执行失败: " + (proc.stderr.strip() or proc.stdout.strip()))
    return proc.stdout.strip()


def redis_cmd(container: str, timeout: int, *args: object) -> str:
    """只读执行一条 redis-cli 命令；**命令白名单是本通道唯一的写防线**。"""
    op = str(args[0]).upper() if args else ""
    if op not in REDIS_READ_COMMANDS:
        raise ChannelError(
            f"拒绝非白名单 Redis 命令: {op!r}"
            f"（只读工具，白名单 = {sorted(REDIS_READ_COMMANDS)}）"
        )
    return _run_redis(container, timeout, *args)


def redis_scan(container: str, timeout: int) -> list[str]:
    """`redis-cli --scan --pattern <收件箱前缀>`：每行一个 key、无游标行、空即无输出。

    命令与 pattern **均硬编码**（不接受外部传入）——扫描只服务于收件箱前缀遍历。
    勿改裸 `SCAN cursor MATCH`——raw 输出首行是游标，会污染 key 列表。
    """
    out = _run_redis(container, timeout, "--scan", "--pattern", SCAN_PATTERN_INBOX)
    return [line.strip() for line in out.splitlines() if line.strip()]


# ---------------------------------------------------------------------------
# MySQL 只读通道（独立 oracle）
# ---------------------------------------------------------------------------

def find_mysql() -> Path | None:
    found = shutil.which("mysql")
    if found:
        return Path(found)
    for candidate in COMMON_MYSQL_PATHS:
        if candidate.exists():
            return candidate
    return None


def run_sql(sql: str, timeout: int = DEFAULT_TIMEOUT) -> str:
    """只读执行**单条** SELECT（禁多语句）；密码走 MYSQL_PWD，不进 argv。失败 → DbChannelError。"""
    text = sql.strip()
    upper = text.upper()
    if not upper.startswith("SELECT"):
        raise DbChannelError("拒绝非 SELECT 语句（只读工具）: " + text[:60])
    if ";" in text:
        raise DbChannelError("拒绝多语句（只读工具，禁 ';'）: " + text[:60])
    for verb in ("INTO OUTFILE", "INTO DUMPFILE", "FOR UPDATE", "LOCK IN SHARE MODE"):
        if verb in upper:
            raise DbChannelError(f"拒绝带写 / 锁语义的 SELECT（只读工具）: {verb} —— " + text[:60])
    mysql = find_mysql()
    if mysql is None:
        raise DbChannelError("mysql 客户端不可用（无法复算窗口真相）")
    cmd = [
        str(mysql),
        f"--user={db_config.DB_USER}",
        f"--host={db_config.DB_HOST}",
        f"--port={db_config.DB_PORT}",
        f"--database={db_config.DB_NAME}",
        "--default-character-set=utf8mb4",
        "--batch",
        "--skip-column-names",
        "--execute",
        text,
    ]
    env = os.environ.copy()
    env["MYSQL_PWD"] = db_config.DB_PASSWORD
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                              errors="replace", timeout=timeout, env=env)
    except subprocess.TimeoutExpired as exc:
        raise DbChannelError(f"mysql 执行超时({timeout}s): " + text[:80]) from exc
    except OSError as exc:
        raise DbChannelError("mysql 客户端启动失败: " + str(exc)) from exc
    if proc.returncode != 0:
        raise DbChannelError("mysql 执行失败: " + (proc.stderr.strip() or proc.stdout.strip()))
    return proc.stdout


def db_universe(timeout: int) -> list[int]:
    """DB universe = 有 `feed_inbox` 散行的用户 ∪ 有同步状态行的用户（一条 SELECT）。"""
    out = run_sql(
        "SELECT DISTINCT user_id FROM feed_inbox "
        "UNION SELECT user_id FROM feed_inbox_sync", timeout)
    return sorted({_as_int(line, "universe user_id") for line in out.splitlines() if line.strip()})


def db_is_synced(user_id: int, timeout: int) -> bool:
    """窗口同步状态：`feed_inbox_sync` 是否存在该用户行（**存在即已同步**）。"""
    out = run_sql(f"SELECT COUNT(*) FROM feed_inbox_sync WHERE user_id = {int(user_id)}", timeout)
    return _as_int(out or "0", "sync COUNT") > 0


def db_window_ids(user_id: int, timeout: int) -> list[int]:
    """DB 真相：该用户 `feed_inbox` 窗口（content_id **降序**）。"""
    out = run_sql(
        f"SELECT content_id FROM feed_inbox WHERE user_id = {int(user_id)} "
        "ORDER BY content_id DESC", timeout)
    return [_as_int(line, "feed_inbox content_id") for line in out.splitlines() if line.strip()]


def oracle_window_ids(user_id: int, params: tuple[int, int, int, list[int]], timeout: int) -> list[int]:
    """独立 oracle：该用户窗口**应有**的 id 序列（每作者最近 K → 去重 → 降序 → 裁剪 C、排除大V）。"""
    per_author, window_max, threshold, listed = params
    excluded = [f"AND c.user_id NOT IN (SELECT u.id FROM users u WHERE u.follower_count >= {threshold})"]
    if listed:
        excluded.append("AND c.user_id NOT IN (" + ",".join(str(i) for i in listed) + ")")
    sql = (
        "SELECT content_id FROM ("
        "  SELECT c.id AS content_id,"
        "         ROW_NUMBER() OVER (PARTITION BY c.user_id ORDER BY c.id DESC) AS rn"
        "  FROM content c"
        f"  WHERE c.user_id IN (SELECT followed_user_id FROM follow WHERE user_id = {int(user_id)})"
        "    AND c.is_deleted = 0"
        "    " + " ".join(excluded) +
        ") w "
        f"WHERE w.rn <= {per_author} "
        "ORDER BY w.content_id DESC "
        f"LIMIT {window_max}"
    )
    out = run_sql(sql, timeout)
    return [_as_int(line, "oracle id") for line in out.splitlines() if line.strip()]


# ---------------------------------------------------------------------------
# Redis 分类 / 缓存覆盖观测
# ---------------------------------------------------------------------------

def classify_key(key: str) -> tuple[str, int | None]:
    """→ (kind, user_id | None)；kind ∈ {member, legacy_marker, lock, other}。

    **先判长前缀 `feed:inbox:full:` 再判 `feed:inbox:`** —— 否则 `feed:inbox:full:123`
    会落进 member 分支（把遗留标记当成一个收件箱）。尾段再强制数字，双保险。
    """
    for prefix, kind in ((INBOX_FULL_PREFIX, "legacy_marker"),
                         (REBUILD_LOCK_PREFIX, "lock"),
                         (INBOX_PREFIX, "member")):
        if key.startswith(prefix):
            tail = key[len(prefix):]
            return (kind, int(tail)) if tail.isdigit() else (kind, None)
    return ("other", None)


def scan_cache_keys(container: str, timeout: int) -> dict:
    """扫描 `feed:inbox:*`，拆出成员用户集与遗留标记（并统计被排除的标记 key）。"""
    matched = redis_scan(container, timeout)
    member_users: set[int] = set()
    legacy_marker_keys: list[str] = []
    other_keys: list[str] = []
    for key in matched:
        kind, uid = classify_key(key)
        if kind == "member" and uid is not None:
            member_users.add(uid)
        elif kind == "legacy_marker":
            legacy_marker_keys.append(key)
        else:
            other_keys.append(key)
    return {
        "matched_count": len(matched),
        "member_users": member_users,
        "legacy_marker_keys": legacy_marker_keys,
        "other_keys": other_keys,
    }


def parse_ids(raw: str) -> list[int]:
    """ZREVRANGE 输出逐行解析；空集无输出，防御 (nil) / (empty array) 等形态。"""
    ids: list[int] = []
    for line in raw.splitlines():
        text = line.strip()
        if text.lstrip("-").isdigit():
            ids.append(int(text))
    return ids


# ---------------------------------------------------------------------------
# 单用户核对
# ---------------------------------------------------------------------------

def check_user(user_id: int, params: tuple[int, int, int, list[int]], timeout: int,
               max_detail: int) -> dict:
    """核对单用户：DB 窗口 vs oracle（missing 判失败 / extra 报告）；同步状态决定分组。"""
    synced = db_is_synced(user_id, timeout)
    db_ids = db_window_ids(user_id, timeout)
    record: dict = {"user_id": user_id, "synced": synced, "db_ids": db_ids, "db_count": len(db_ids)}
    if not synced:
        return record

    expect = oracle_window_ids(user_id, params, timeout)
    db_set, expect_set = set(db_ids), set(expect)
    missing = [cid for cid in expect if cid not in db_set]        # oracle \ DB → 判失败
    extra = [cid for cid in db_ids if cid not in expect_set]      # DB \ oracle → 只报告
    record.update({
        "oracle_ids": expect,
        "oracle_len": len(expect),
        "missing": missing[:max_detail],
        "missing_count": len(missing),
        "extra": extra[:max_detail],
        "extra_count": len(extra),
    })
    record["consistent"] = not missing
    return record


# ---------------------------------------------------------------------------
# 渲染
# ---------------------------------------------------------------------------

def _seq_text(ids: list[int]) -> str:
    if not ids:
        return "[]"
    shown = ids if len(ids) <= DETAIL_SAMPLE else ids[:DETAIL_SAMPLE] + [f"…(+{len(ids) - DETAIL_SAMPLE})"]
    return "[" + ", ".join(str(v) for v in shown) + "]"


def render_text(report: dict) -> str:
    meta, summary = report["meta"], report["summary"]
    out: list[str] = []
    out.append("feed 窗口核对（只读 · 二期切读观测基线）")
    out.append(f"  数据源: MySQL {meta['mysql']['host']}:{meta['mysql']['port']}/{meta['mysql']['database']}"
               f" | Redis 容器={meta['redis']['container']} (db 0)")
    out.append(f"  oracle 参数: K={meta['params']['per_author']} C={meta['params']['window_max']}"
               f" 大V阈值={meta['params']['bigv_threshold']} 名单={meta['params']['bigv_listed']}")
    out.append("  口径: 真相=DB feed_inbox；缺成员(missing)判失败；多出(extra)仅报告；未同步不判失败")
    out.append("")

    if meta["mode"] == "scan":
        out.append(f"[扫描] DB universe = feed_inbox ∪ feed_inbox_sync → {summary['scanned_users']} 用户")
        if summary["truncated_users"]:
            out.append(f"       ⚠ --limit 生效：省略 {summary['truncated_users']} 个用户未核对")
    else:
        out.append(f"[核对] user={meta['user_id']}（精查）")
    out.append(f"[分组] 已同步 {summary['synced']}"
               f"（一致 {summary['consistent']} / 不一致 {summary['inconsistent']}）；"
               f"未同步 {summary['unsynced']}（完整性未知，设计预期）")
    cov = summary.get("cache_coverage")
    if cov is None:
        out.append(f"[缓存] 覆盖率不可用（Redis 通道不可用）：{summary.get('cache_note') or '—'}")
    else:
        out.append(f"[缓存] 已同步用户回填覆盖率 {cov['covered']}/{cov['synced_total']}"
                   f"（{cov['ratio_pct']}%）；成员与 DB 不一致 {cov['mismatch']}（观测，不判失败）")
    out.append("")

    if summary["empty_universe"] and meta["mode"] == "scan":
        out.append("[结论] 空 universe：无可核对对象。可能因——尚无重建 / fanout 写入 "
                   "（feed_inbox 与 feed_inbox_sync 均空）/ 连错实例。退出码 = 0")
        return "\n".join(out)

    out.append("[A] 已同步 → DB 窗口 vs 重建 oracle（缺成员即不一致）")
    if not (report["consistent"] or report["inconsistent"]):
        out.append("    （无）")
    for rec in report["consistent"]:
        out.append(f"    [OK]   user={rec['user_id']}  DB={rec['db_count']}  oracle={rec['oracle_len']}"
                   f"  多出={rec['extra_count']}")
    for rec in report["inconsistent"]:
        out.append(f"    [DIFF] user={rec['user_id']}  DB={rec['db_count']}  oracle={rec['oracle_len']}"
                   f"  缺{rec['missing_count']}  多{rec['extra_count']}")
        out.append(f"           缺成员样本: {_seq_text(rec['missing'])}")
        out.append(f"           DB 窗口: {_seq_text(rec['db_ids'])}")
        out.append(f"           oracle:  {_seq_text(rec['oracle_ids'])}")
    out.append("")

    out.append("[B] 未同步（无 feed_inbox_sync 行 → 读侧回退纯拉；本组不判失败）")
    if not report["unknown_completeness"]:
        out.append("    （无）")
    for rec in report["unknown_completeness"]:
        out.append(f"    user={rec['user_id']}  DB={rec['db_count']}")
    out.append("")

    out.append(f"[结论] 已同步 {summary['synced']}：一致 {summary['consistent']} / "
               f"不一致 {summary['inconsistent']}；未同步 {summary['unsynced']}；"
               f"退出码 = {report['exit_code']}")
    return "\n".join(out)


def render_json(report: dict) -> str:
    payload = {
        "meta": report["meta"],
        "summary": report["summary"],
        "consistent": [
            {"user_id": r["user_id"], "db_count": r["db_count"],
             "oracle_len": r["oracle_len"], "extra_count": r["extra_count"]}
            for r in report["consistent"]
        ],
        "inconsistent": [
            {"user_id": r["user_id"], "db_count": r["db_count"], "oracle_len": r["oracle_len"],
             "missing": r["missing"], "missing_count": r["missing_count"],
             "extra": r["extra"], "extra_count": r["extra_count"]}
            for r in report["inconsistent"]
        ],
        "unknown_completeness": [
            {"user_id": r["user_id"], "db_count": r["db_count"]}
            for r in report["unknown_completeness"]
        ],
        "exit_code": report["exit_code"],
        "errors": report["errors"],
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


# ---------------------------------------------------------------------------
# 缓存覆盖观测（best-effort）
# ---------------------------------------------------------------------------

def observe_cache(container: str, timeout: int, synced_records: list[dict],
                  db_ids_by_user: dict[int, list[int]]) -> tuple[dict | None, str | None]:
    """返回 (cache_coverage | None, note | None)。Redis 不可用 / 中途失败 → (None, 友好文案)。

    缓存覆盖率是**附加观测**：任何 Redis 通道异常（含 PING 之后的单条命令失败 / 超时）
    都只降级为"覆盖率不可用"，**绝不抛出**（不得让工具主判定 DB 的退出码被附加观测带偏）。
    """
    try:
        pong = redis_cmd(container, timeout, "PING")
        if pong != "PONG":
            return None, f"Redis 容器 {container!r} 不可用（PING → {pong!r}）"
        scan = scan_cache_keys(container, timeout)
        member_users = scan["member_users"]
        covered = mismatch = 0
        for rec in synced_records:
            uid = rec["user_id"]
            exists = _as_int(redis_cmd(container, timeout, "EXISTS", INBOX_PREFIX + str(uid)) or "0",
                             "EXISTS " + INBOX_PREFIX + str(uid))
            if exists <= 0:
                continue
            covered += 1
            cache_ids = set(parse_ids(redis_cmd(container, timeout, "ZREVRANGE",
                                                INBOX_PREFIX + str(uid), 0, -1)))
            if cache_ids != set(db_ids_by_user.get(uid, [])):
                mismatch += 1
    except ChannelError as exc:
        return None, str(exc)

    synced_total = len(synced_records)
    ratio = round(covered * 100.0 / synced_total, 1) if synced_total else 0.0
    return {
        "synced_total": synced_total,
        "covered": covered,
        "ratio_pct": ratio,
        "mismatch": mismatch,
        "scanned_inbox_prefix_keys": scan["matched_count"],
        "legacy_marker_keys": len(scan["legacy_marker_keys"]),
        "other_keys": scan["other_keys"],
        "member_key_users": len(member_users),
    }, None


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main(argv: list[str]) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    args = parse_args(argv)
    params = _oracle_params()
    per_author, window_max, threshold, listed = params

    try:
        if args.user_id is not None:
            universe, scan = [args.user_id], None
            truncated = 0
        else:
            universe = db_universe(args.timeout)
            truncated = 0
            if args.limit is not None and len(universe) > args.limit:
                truncated = len(universe) - args.limit
                universe = universe[:args.limit]

        consistent, inconsistent, unknown = [], [], []
        db_ids_by_user: dict[int, list[int]] = {}
        for uid in universe:
            rec = check_user(uid, params, args.timeout, args.max_detail)
            db_ids_by_user[uid] = rec["db_ids"]
            if not rec["synced"]:
                unknown.append(rec)
            elif rec["consistent"]:
                consistent.append(rec)
            else:
                inconsistent.append(rec)
    except DbChannelError as exc:
        die(str(exc))
    except ChannelError as exc:
        die(str(exc))

    cache_coverage, cache_note = observe_cache(args.redis_container, args.timeout,
                                               consistent + inconsistent, db_ids_by_user)

    summary = {
        "scanned_users": len(universe),
        "synced": len(consistent) + len(inconsistent),
        "consistent": len(consistent),
        "inconsistent": len(inconsistent),
        "unsynced": len(unknown),
        "missing_total": sum(r["missing_count"] for r in inconsistent),
        "extra_total": sum(r["extra_count"] for r in consistent + inconsistent),
        "truncated_users": truncated,
        "empty_universe": len(universe) == 0,
        "cache_coverage": cache_coverage,
        "cache_note": cache_note,
    }
    report = {
        "meta": {
            "mode": "user" if args.user_id is not None else "scan",
            "user_id": args.user_id,
            "mysql": {"host": db_config.DB_HOST, "port": db_config.DB_PORT,
                      "database": db_config.DB_NAME},
            "redis": {"container": args.redis_container, "db": 0},
            "params": {"per_author": per_author, "window_max": window_max,
                       "bigv_threshold": threshold, "bigv_listed": listed},
            "prefixes": {"inbox": INBOX_PREFIX, "legacy_full": INBOX_FULL_PREFIX,
                         "lock": REBUILD_LOCK_PREFIX},
            "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        },
        "summary": summary,
        "consistent": consistent,
        "inconsistent": inconsistent,
        "unknown_completeness": unknown,
        "exit_code": 1 if inconsistent else 0,
        "errors": [],
    }
    print(render_json(report) if args.json else render_text(report))
    return report["exit_code"]


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
