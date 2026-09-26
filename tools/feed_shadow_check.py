# -*- coding: utf-8 -*-
"""T20（feed 推拉结合第一期，commit 令牌 feed1-20）feed 影子核对——**只读**。

定位 = **二期切读准入测量仪**。一期影子期无人读收件箱（`/feed` 仍走拉模式），故本工具
在一期**即时价值低**；它的用途是在**真实 / 准真实数据**上给出"完整态覆盖率 + 完整态下与
拉模式逐条一致率 + 偏差样本"，作为二期"是否把读路径切到收件箱"的数据依据。

核对对象与口径（与 T19 e2e 的独立 oracle **同源**）：
  * 收件箱 = Redis ZSet `feed:inbox:{userId}`（member = contentId、score = contentId）。
  * 完整态标记 = `feed:inbox:full:{userId}`（值恒 "1"，**只由重建写**，fanout 从不触碰）。
  * 拉模式基准（oracle）= 直连 MySQL 复算：

        SELECT c.id FROM content c WHERE c.user_id IN
          (SELECT followed_user_id FROM follow WHERE user_id = {uid})
          AND c.is_deleted = 0 ORDER BY c.create_time DESC, c.id DESC

    **不拿应用自己的读路径当基准**（那会"同样错就看不出来"），且关注者集合取 `follow`
    表（DB 真相），不取缓存。
  * 判定维度：`ZREVRANGE feed:inbox:{uid} 0 -1` 与 oracle **逐条相等** + `ZCARD == len(oracle)`
    + 标记 `EXISTS == 1` + 标记 `TTL > 0`。

**分组语义（关键）**：
  * **[A] 有完整态标记** ⇒ 判定组：收件箱应与 oracle 逐条相等，不符即"不一致"。
  * **[B] 无完整态标记** ⇒ **完整性未知，不是缺陷**：重建只在关注 / 取关时触发（一期红线 =
    不引入读触发），且 fanout 从不写标记 ⇒ 未发生过关注变动的用户没有标记、其收件箱**必然缺
    历史内容**。二期切读的闸门 = "标记存在"，故本组**只计数、不判失败**。

**扫描与前缀排除（防把标记当收件箱）**：
  `feed:inbox:full:` **以** `feed:inbox:` 开头（`CacheKeys` 已登记该重叠）⇒ 遍历
  `feed:inbox:*` 时**必须显式排除**。本工具按"先判长前缀再判短前缀 + 尾段强制数字"双保险，
  并在报告里**显式展示"已排除标记 key 数"**，不静默吞掉。universe = 成员 key 的 userId
  **∪** 标记 key 的 userId —— 覆盖"空但完整"收件箱（Redis 会删除空 ZSet ⇒"取关到空"后只剩
  标记、没有成员 key，仅扫成员前缀会漏）。`feed:rebuild:lock:` 不匹配 `feed:inbox:*`
  （glob 里 `feed:inbox:` 是字面前缀），分类函数仍防御性排除。

只读红线：**全部** Redis 命令经 `redis_cmd` 白名单（`PING/EXISTS/ZCARD/ZREVRANGE/TTL`）；扫描走
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

退出码：0 = 核对完成且**所有"有标记"收件箱均一致**（含存在无标记组、含**空 universe 零数据
  报告**）；1 = 存在"有标记但收件箱 ≠ oracle"；2 = 参数 / 通道错误（缺 docker、容器不可用、
  缺 mysql 客户端、连库失败、只读白名单拒绝——友好文案，**无 Traceback**）。标记 TTL 异常只
  报告（`anomalies`），不置 1（一期无人读、由残余窗口登记承接）。

判读提示：核对是**快照**测量——若恰逢 fanout 在写或标记 TTL 到期，"不一致"可能是瞬时；
  本工具**不加轮询重试**（轮询是 pytest 的手段），宜在低写入窗口执行。
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

# 前缀常量（须与 src/main/java/com/itheima/cache/CacheKeys.java 同步：L27 / L36 / L48）
INBOX_PREFIX = "feed:inbox:"
INBOX_FULL_PREFIX = "feed:inbox:full:"        # ⚠ 以 INBOX_PREFIX 开头，遍历时必须先判长前缀
REBUILD_LOCK_PREFIX = "feed:rebuild:lock:"

# Redis 只读命令白名单：这是该通道能到达 redis-cli 的**全部** token（写防线）
REDIS_READ_COMMANDS = frozenset({"PING", "EXISTS", "ZCARD", "ZREVRANGE", "TTL"})
SCAN_PATTERN_INBOX = INBOX_PREFIX + "*"
DEFAULT_REDIS_CONTAINER = "redis"

COMMON_MYSQL_PATHS = (
    Path(r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"),
    Path(r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"),
)

DEFAULT_TIMEOUT = 30
DEFAULT_MAX_DETAIL = 100
DETAIL_SAMPLE = 20                     # 文本输出里最多展示的缺失 / 多余样本数

# oracle 口径（与 src/test/python/test_feed_rebuild.py:_oracle_inbox_ids 一字不差）
ORACLE_SQL_IDS = (
    "SELECT c.id FROM content c WHERE c.user_id IN "
    "(SELECT followed_user_id FROM follow WHERE user_id = {uid}) "
    "AND c.is_deleted = 0 ORDER BY c.create_time DESC, c.id DESC"
)
ORACLE_SQL_COUNT = (
    "SELECT COUNT(*) FROM content c WHERE c.user_id IN "
    "(SELECT followed_user_id FROM follow WHERE user_id = {uid}) "
    "AND c.is_deleted = 0"
)


class ChannelError(RuntimeError):
    """外部通道不可用或只读白名单拒绝（docker / 容器 / mysql 客户端 / 连库失败）→ 退出码 2。"""


def die(message: str, code: int = 2) -> None:
    print(f"feed-shadow 错误: {message}", file=sys.stderr)
    sys.exit(code)


def _as_int(raw: str, what: str) -> int:
    """把 redis-cli / mysql 的文本响应转 int；服务端错误或类型不符 → ChannelError。

    守住"参数 / 通道错误一律友好文案、**无 Traceback**"的契约不被裸 `ValueError` 打破
    （例如外部把 `feed:inbox:{id}` 误写成 String 类型 → redis-cli 返回 `(error) WRONGTYPE …`）。
    """
    try:
        return int(raw.strip())
    except ValueError as exc:
        raise ChannelError(f"无法解析 {what} 的响应（可能 key 类型不符 / 服务端错误）: {raw!r}") from exc


# ---------------------------------------------------------------------------
# 参数
# ---------------------------------------------------------------------------

def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="feed_shadow_check.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=(
            "feed 影子核对（只读）：收件箱 feed:inbox:{userId} 与拉模式 oracle 逐条比对。\n"
            "按\"完整态标记是否存在\"分组——有标记 = 判定组；无标记 = 完整性未知（设计预期，不判失败）。"
        ),
        epilog=(
            "示例:\n"
            "  python tools\\feed_shadow_check.py                    # 全量扫描所有收件箱\n"
            "  python tools\\feed_shadow_check.py --user-id 42       # 精查单个用户\n"
            "  python tools\\feed_shadow_check.py --json             # 机器可读（直调本脚本，经 tv.py 会有横幅）\n"
            "  python tools\\tv.py feed-shadow                       # 经统一入口（注入声明环境的 DB_*）\n"
        ),
    )
    parser.add_argument("--user-id", type=int, default=None,
                        help="只核对该用户（精查）；缺省 = 全量扫描所有收件箱")
    parser.add_argument("--json", action="store_true", help="只输出一个 JSON 对象（机器可读）")
    parser.add_argument("--redis-container", default=DEFAULT_REDIS_CONTAINER,
                        help=f"Redis 容器名（默认 {DEFAULT_REDIS_CONTAINER}）")
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


# ---------------------------------------------------------------------------
# Redis 只读通道
# ---------------------------------------------------------------------------

def _run_redis(container: str, timeout: int, *args: object) -> str:
    """Redis 通道的唯一出口：子进程 `docker exec <容器> redis-cli …`。"""
    if shutil.which("docker") is None:
        raise ChannelError("docker 不可用（读 Redis 收件箱是手段，不是被测能力）")
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
    """只读执行**单条** SELECT（禁多语句）；密码走 MYSQL_PWD，不进 argv。"""
    text = sql.strip()
    upper = text.upper()
    if not upper.startswith("SELECT"):
        raise ChannelError("拒绝非 SELECT 语句（只读工具）: " + text[:60])
    if ";" in text:
        raise ChannelError("拒绝多语句（只读工具，禁 ';'）: " + text[:60])
    for verb in ("INTO OUTFILE", "INTO DUMPFILE", "FOR UPDATE", "LOCK IN SHARE MODE"):
        if verb in upper:
            raise ChannelError(f"拒绝带写 / 锁语义的 SELECT（只读工具）: {verb} —— " + text[:60])
    mysql = find_mysql()
    if mysql is None:
        raise ChannelError("mysql 客户端不可用（无法复算收件箱期望序列）")
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
        raise ChannelError(f"mysql 执行超时({timeout}s): " + text[:80]) from exc
    except OSError as exc:
        raise ChannelError("mysql 客户端启动失败: " + str(exc)) from exc
    if proc.returncode != 0:
        raise ChannelError("mysql 执行失败: " + (proc.stderr.strip() or proc.stdout.strip()))
    return proc.stdout


def oracle_ids(user_id: int, timeout: int) -> list[int]:
    """独立 oracle：该用户收件箱**应有**的内容 id 序列（全量）。"""
    out = run_sql(ORACLE_SQL_IDS.format(uid=int(user_id)), timeout)
    return [_as_int(line, "oracle id") for line in out.splitlines() if line.strip()]


def oracle_count(user_id: int, timeout: int) -> int:
    """无标记组只要计数 → 用便宜版 COUNT（不必拉全序列）。"""
    out = run_sql(ORACLE_SQL_COUNT.format(uid=int(user_id)), timeout)
    return _as_int(out or "0", "oracle COUNT")


# ---------------------------------------------------------------------------
# 分类 / 解析
# ---------------------------------------------------------------------------

def classify_key(key: str) -> tuple[str, int | None]:
    """→ (kind, user_id | None)；kind ∈ {member, full_marker, lock, other}。

    **先判长前缀 `feed:inbox:full:` 再判 `feed:inbox:`** —— 否则 `feed:inbox:full:123`
    会落进 member 分支（把"完整态标记"当成一个收件箱）。尾段再强制数字，双保险。
    """
    for prefix, kind in ((INBOX_FULL_PREFIX, "full_marker"),
                         (REBUILD_LOCK_PREFIX, "lock"),
                         (INBOX_PREFIX, "member")):
        if key.startswith(prefix):
            tail = key[len(prefix):]
            return (kind, int(tail)) if tail.isdigit() else (kind, None)
    return ("other", None)


def parse_ids(raw: str) -> list[int]:
    """ZREVRANGE 输出逐行解析；空集无输出，防御 (nil) / (empty array) 等形态。"""
    ids: list[int] = []
    for line in raw.splitlines():
        text = line.strip()
        if text.lstrip("-").isdigit():
            ids.append(int(text))
    return ids


def scan_universe(container: str, timeout: int) -> dict:
    """全量扫描 `feed:inbox:*`，按分类拆分出成员 / 标记，并统计被排除的标记 key。

    注：`feed:inbox:*` 的 glob 也会命中 `feed:inbox:full:*`（前缀重叠）——这正是必须显式
    排除的原因；`feed:rebuild:lock:*` 不匹配该 glob，分类函数仍防御性排除。
    """
    matched = redis_scan(container, timeout)
    member_users: set[int] = set()
    marker_users: set[int] = set()
    excluded_marker_keys: list[str] = []
    other_keys: list[str] = []
    for key in matched:
        kind, uid = classify_key(key)
        if kind == "member" and uid is not None:
            member_users.add(uid)
        elif kind == "full_marker" and uid is not None:
            marker_users.add(uid)
            excluded_marker_keys.append(key)
        else:
            other_keys.append(key)
    return {
        "matched_count": len(matched),
        "member_users": member_users,
        "marker_users": marker_users,
        "excluded_marker_keys": excluded_marker_keys,
        "other_keys": other_keys,
    }


# ---------------------------------------------------------------------------
# 单用户核对
# ---------------------------------------------------------------------------

def check_user(user_id: int, container: str, timeout: int, max_detail: int) -> dict:
    inbox_key = INBOX_PREFIX + str(user_id)
    marker_key = INBOX_FULL_PREFIX + str(user_id)
    marker_exists = _as_int(redis_cmd(container, timeout, "EXISTS", marker_key) or "0",
                            "EXISTS " + marker_key)
    zcard = _as_int(redis_cmd(container, timeout, "ZCARD", inbox_key) or "0", "ZCARD " + inbox_key)
    record: dict = {"user_id": user_id, "zcard": zcard, "marker_exists": marker_exists,
                    "marker_ttl": None, "inbox_ids": []}

    if marker_exists:
        record["marker_ttl"] = _as_int(redis_cmd(container, timeout, "TTL", marker_key) or "-2",
                                      "TTL " + marker_key)
        inbox_ids = parse_ids(redis_cmd(container, timeout, "ZREVRANGE", inbox_key, 0, -1))
        expect = oracle_ids(user_id, timeout)
        inbox_set, expect_set = set(inbox_ids), set(expect)
        missing = [cid for cid in expect if cid not in inbox_set]
        extra = [cid for cid in inbox_ids if cid not in expect_set]
        record.update({
            "inbox_ids": inbox_ids,
            "oracle_ids": expect,
            "oracle_len": len(expect),
            "missing": missing[:max_detail],
            "missing_count": len(missing),
            "extra": extra[:max_detail],
            "extra_count": len(extra),
            "seq_mismatch": inbox_ids != expect,
            "zcard_mismatch": zcard != len(expect),
        })
        record["consistent"] = not record["seq_mismatch"] and not record["zcard_mismatch"]
    else:
        record["inbox_ids"] = parse_ids(redis_cmd(container, timeout, "ZREVRANGE", inbox_key, 0, -1))
        record["oracle_count"] = oracle_count(user_id, timeout)
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
    out.append("feed 影子核对（只读 · 二期切读准入测量仪）")
    out.append(f"  数据源: Redis 容器={meta['redis']['container']} (db 0) | "
               f"MySQL {meta['mysql']['host']}:{meta['mysql']['port']}/{meta['mysql']['database']}")
    out.append(f"  前缀: 收件箱={INBOX_PREFIX}  完整态标记={INBOX_FULL_PREFIX}（遍历时显式排除）")
    out.append("")

    if meta["mode"] == "scan":
        out.append(f"[扫描] {SCAN_PATTERN_INBOX} → 匹配 key {summary['matched_inbox_prefix_keys']}；"
                   f"成员收件箱 {summary['member_inbox_count']}、"
                   f"已排除标记 key {summary['excluded_marker_keys']}；universe {summary['scanned_inboxes']}")
        if summary["truncated_users"]:
            out.append(f"       ⚠ --limit 生效：省略 {summary['truncated_users']} 个用户未核对")
        if summary["other_keys"]:
            out.append(f"       ⚠ 未识别 key {len(summary['other_keys'])} 个：{summary['other_keys'][:5]}")
    else:
        out.append(f"[核对] user={meta['user_id']}（精查）")
    out.append(f"[分组] 有完整态标记 {summary['with_marker']}"
               f"（一致 {summary['consistent']} / 不一致 {summary['inconsistent']}）；"
               f"无完整态标记 {summary['without_marker']}（完整性未知，设计预期）")
    out.append("")

    if summary["empty_universe"] and meta["mode"] == "scan":
        out.append("[结论] 空 universe：无可核对对象。可能因——尚无关注 / 取关触发重建"
                   "（标记只由重建写）/ 收件箱已 TTL 回收 / 连错实例。退出码 = 0")
        return "\n".join(out)

    out.append("[A] 有完整态标记 → 与拉模式 oracle 逐条比对")
    if not (report["consistent"] or report["inconsistent"]):
        out.append("    （无）")
    for rec in report["consistent"]:
        out.append(f"    [OK]   user={rec['user_id']}  ZCARD={rec['zcard']}  oracle={rec['oracle_len']}"
                   f"  markerTTL={rec['marker_ttl']}s")
    for rec in report["inconsistent"]:
        out.append(f"    [DIFF] user={rec['user_id']}  ZCARD={rec['zcard']}  oracle={rec['oracle_len']}"
                   f"  缺{rec['missing_count']} 多{rec['extra_count']}"
                   f"  seqDiff={'是' if rec['seq_mismatch'] else '否'}"
                   f"  markerTTL={rec['marker_ttl']}s")
        out.append(f"           收件箱: {_seq_text(rec['inbox_ids'])}")
        out.append(f"           oracle: {_seq_text(rec['oracle_ids'])}")
    for rec in report["anomalies"]:
        out.append(f"    [WARN] user={rec['user_id']}  markerTTL={rec['ttl']}"
                   f"（非正；标记应随重建续期）")
    out.append("")

    out.append("[B] 无完整态标记（完整性未知 → 二期回退拉模式；本组不判失败）")
    if not report["unknown_completeness"]:
        out.append("    （无）")
    for rec in report["unknown_completeness"]:
        out.append(f"    user={rec['user_id']}  ZCARD={rec['zcard']}  oracle={rec['oracle_count']}")
    out.append("")

    out.append(f"[结论] 有标记 {summary['with_marker']}：一致 {summary['consistent']} / "
               f"不一致 {summary['inconsistent']}；无标记 {summary['without_marker']}；"
               f"退出码 = {report['exit_code']}")
    return "\n".join(out)


def render_json(report: dict) -> str:
    payload = {
        "meta": report["meta"],
        "summary": report["summary"],
        "consistent": [
            {"user_id": r["user_id"], "zcard": r["zcard"], "oracle_len": r["oracle_len"],
             "marker_ttl": r["marker_ttl"]}
            for r in report["consistent"]
        ],
        "inconsistent": [
            {"user_id": r["user_id"], "zcard": r["zcard"], "oracle_len": r["oracle_len"],
             "missing": r["missing"], "missing_count": r["missing_count"],
             "extra": r["extra"], "extra_count": r["extra_count"],
             "seq_mismatch": r["seq_mismatch"], "zcard_mismatch": r["zcard_mismatch"],
             "marker_ttl": r["marker_ttl"]}
            for r in report["inconsistent"]
        ],
        "unknown_completeness": [
            {"user_id": r["user_id"], "zcard": r["zcard"], "oracle_count": r["oracle_count"]}
            for r in report["unknown_completeness"]
        ],
        "anomalies": report["anomalies"],
        "exit_code": report["exit_code"],
        "errors": report["errors"],
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main(argv: list[str]) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    args = parse_args(argv)
    try:
        pong = redis_cmd(args.redis_container, args.timeout, "PING")
        if pong != "PONG":
            raise ChannelError(f"Redis 容器 {args.redis_container!r} 不可用（PING → {pong!r}）")

        if args.user_id is not None:
            universe, scan = [args.user_id], None
            truncated = 0
        else:
            scan = scan_universe(args.redis_container, args.timeout)
            universe = sorted(scan["member_users"] | scan["marker_users"])
            truncated = 0
            if args.limit is not None and len(universe) > args.limit:
                truncated = len(universe) - args.limit
                universe = universe[:args.limit]

        consistent, inconsistent, unknown, anomalies = [], [], [], []
        for uid in universe:
            rec = check_user(uid, args.redis_container, args.timeout, args.max_detail)
            if not rec["marker_exists"]:
                unknown.append(rec)
                continue
            if rec["marker_ttl"] is not None and rec["marker_ttl"] <= 0:
                anomalies.append({"user_id": uid, "kind": "marker_ttl_non_positive", "ttl": rec["marker_ttl"]})
            (consistent if rec["consistent"] else inconsistent).append(rec)
    except ChannelError as exc:
        die(str(exc))

    summary = {
        "scanned_inboxes": len(universe),
        "matched_inbox_prefix_keys": scan["matched_count"] if scan else None,
        "member_inbox_count": len(scan["member_users"]) if scan else None,
        "excluded_marker_keys": len(scan["excluded_marker_keys"]) if scan else None,
        "other_keys": scan["other_keys"] if scan else [],
        "with_marker": len(consistent) + len(inconsistent),
        "consistent": len(consistent),
        "inconsistent": len(inconsistent),
        "without_marker": len(unknown),
        "truncated_users": truncated,
        "empty_universe": len(universe) == 0,
    }
    report = {
        "meta": {
            "mode": "user" if args.user_id is not None else "scan",
            "user_id": args.user_id,
            "redis": {"container": args.redis_container, "db": 0},
            "mysql": {"host": db_config.DB_HOST, "port": db_config.DB_PORT,
                      "database": db_config.DB_NAME},
            "prefixes": {"inbox": INBOX_PREFIX, "full": INBOX_FULL_PREFIX,
                         "lock": REBUILD_LOCK_PREFIX},
            "excluded_prefixes": [INBOX_FULL_PREFIX, REBUILD_LOCK_PREFIX],
            "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        },
        "summary": summary,
        "consistent": consistent,
        "inconsistent": inconsistent,
        "unknown_completeness": unknown,
        "anomalies": anomalies,
        "exit_code": 1 if inconsistent else 0,
        "errors": [],
    }
    print(render_json(report) if args.json else render_text(report))
    return report["exit_code"]


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
