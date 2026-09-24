# -*- coding: utf-8 -*-
"""T13（日志第三张清单，commit 令牌 log3-13）日志消费报告——**只读**。

三条视角（让前两张清单落地的结构化日志第一次被消费）：
  (a) `req=` 全链路追溯：同一请求跨四输出端（access / system / error / audit）按时间序展示；
  (b) 耗时分布：按 `path` 聚合 access 行 `cost=`（次数 / 平均 / p50 / p95 / 最大）+ `slow=1` Top N；
  (c) 错误率：结果码分布，**4xx 预期拒绝与 5xx 失败分列**（与 T12 同源口径）。

只读红线：**不写 / 不删 / 不改 / 不移动 / 不压缩**任何日志文件、不触发轮转（不调用任何生产端代码）；
输出只落 stdout（`--json` 即可留给机器消费），本工具**不生成任何落盘文件**。

数据源与窗口口径（判读细节见 `.docs/说明书/TEST_AUTOMATION.md` §4.6）：
  * 默认目录 = e2e 测试实例日志 `.stage8-target/tomcat-test-18080/logs/`；
    JUnit 链路 `--dir .stage8-target/test-logs`；生产 / 本地直跑链路 `--dir logs`（相对路径按 cwd 解析）。
  * 每端合并全部轮转文件（`<名>.log.<N>`，N=0 为当前写入文件、N 越大越旧；裸名文件视为最旧），
    排除 JUL 的 `.lck` 锁文件；按 `ts=` 升序稳定排序后取**末尾 N 行**（`--all` 取消上限）。
  * 视角 (a) **不受窗口约束**——在四端全部行里定向过滤 `req=`，保证追溯不因窗口截断而缺端。

容错：半行 / 缺字段 / 非 UTF-8 坏字节 / 混入其它端记录 / T1 前的旧格式残留
一律**跳过并计数**（`未识别行`），不中断；空行单列（`空行`）。

退出码：0 = 出报告（含"目录存在但无日志文件"的零数据报告）；2 = 参数 / 目录错误（不打印堆栈）。
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent

# 默认日志目录 = e2e 测试实例（与 tools/run_tests.py 的 LOG_PATH 注入、conftest.ACCESS_LOG_DIR 同指）
DEFAULT_LOG_DIR = str(PROJECT_ROOT / ".stage8-target" / "tomcat-test-18080" / "logs")
DEFAULT_CONFIG = str(PROJECT_ROOT / "src" / "main" / "resources" / "app.properties")
DEFAULT_LIMIT = 2000          # 每端窗口行数（--all 取消）
DEFAULT_TOP = 10              # 表格行数
DEFAULT_SLOW_MS = 1000        # log.slowRequestMs 的回退默认值

# 端口顺序：仅用于展示与时序并列时的稳定次序（不是优先级）
PORT_ORDER = ("access", "system", "error", "audit")
# 端口 → app.properties 配置键（消费侧适配既有配置；键缺失回退内置默认名）
PORT_KEYS = {
    "system": "log.file",
    "error": "log.error.file",
    "access": "log.access.file",
    "audit": "log.audit.file",
}
DEFAULT_PORT_FILES = {
    "system": "system.log",
    "error": "error.log",
    "access": "access.log",
    "audit": "audit.log",
}

# 结构化行形态（与 src/test/python/test_log_outputs.py 的正则同口径，此处额外做字段捕获）
TS = r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}"
REQ = r"[0-9a-f]{16}"
ACCESS_LINE_RE = re.compile(
    rf"^ts=(?P<ts>{TS}) level=INFO logger=access req=(?P<req>{REQ}) msg=(?P<msg>.*)$"
)
ACCESS_MSG_RE = re.compile(
    r"^method=(?P<method>\S+) path=(?P<path>\S+) userId=(?P<uid>-|\d+) "
    r"code=(?P<code>\d+) cost=(?P<cost>\d+)ms slow=(?P<slow>[01])$"
)
APP_LINE_RE = re.compile(
    rf"^ts=(?P<ts>{TS}) level=(?P<level>\w+) logger=(?P<logger>\S+)"
    rf"(?: req=(?P<req>{REQ}))? msg=(?P<msg>.*)$"
)
AUDIT_MSG_RE = re.compile(r"^action=\S+ operatorId=\S+ target=\S+ result=success$")
AUDIT_ACTION_RE = re.compile(r"^action=(\S+)")
# 输出端标识（专属 logger）：混入其它端的记录一律判"未识别"（不下发到本端本就该发生）
PORT_LOGGERS = {"access": "access", "audit": "audit"}


def die(message: str, code: int = 2) -> None:
    print(f"log-report 错误: {message}", file=sys.stderr)
    sys.exit(code)


# ---------------------------------------------------------------------------
# 参数与配置
# ---------------------------------------------------------------------------

def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="log_report.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=(
            "日志消费报告（只读）：req= 全链路追溯 / 耗时分布与慢请求 / 错误率（4xx 与 5xx 分列）\n"
            "窗口 = 每端合并全部轮转文件后按 ts= 升序取末尾 N 行（--all 取消上限）"
        ),
        epilog=(
            "示例:\n"
            "  python tools\\log_report.py                          # e2e 产物，默认窗口\n"
            "  python tools\\log_report.py --all                     # 全量口径\n"
            "  python tools\\log_report.py --req 0a1b2c3d4e5f0001    # 指定请求跨端追溯\n"
            "  python tools\\log_report.py --dir .stage8-target/test-logs   # JUnit 链路\n"
            "  python tools\\log_report.py --dir logs                # 生产/本地直跑链路\n"
            "  python tools\\log_report.py --json                    # 机器可读（直调本脚本，经 tv.py 会有横幅）\n"
        ),
    )
    parser.add_argument("--dir", default=DEFAULT_LOG_DIR,
                        help=f"日志目录（默认 e2e 产物: {DEFAULT_LOG_DIR}）")
    window = parser.add_mutually_exclusive_group()
    window.add_argument("--all", action="store_true", help="取消窗口上限（全量）")
    window.add_argument("--limit", type=int, default=None,
                        help=f"每端窗口行数（默认 {DEFAULT_LIMIT}）")
    parser.add_argument("--req", default=None,
                        help="视角 (a) 的目标 req（缺省=窗口内最新一条 code>=500，无则最新一条）")
    parser.add_argument("--top", type=int, default=DEFAULT_TOP, help=f"表格行数（默认 {DEFAULT_TOP}）")
    parser.add_argument("--slow-ms", type=int, default=None,
                        help=f"慢请求阈值（仅用于 slow 标记一致性核对，默认取 log.slowRequestMs）")
    parser.add_argument("--config", default=DEFAULT_CONFIG,
                        help="配置文件（解析四端文件名与 log.slowRequestMs）")
    parser.add_argument("--json", action="store_true", help="只输出一个 JSON 对象（机器可读）")
    args = parser.parse_args(argv)
    if args.limit is not None and args.limit < 1:
        parser.error("--limit 必须 >= 1")
    if args.top < 1:
        parser.error("--top 必须 >= 1")
    if args.slow_ms is not None and args.slow_ms < 0:
        parser.error("--slow-ms 必须 >= 0")
    return args


def resolve_config(path: str) -> tuple[dict, int, str | None]:
    """读 app.properties 取四端文件名（basename）与 log.slowRequestMs；缺失/键缺回退内置默认。"""
    files = dict(DEFAULT_PORT_FILES)
    slow_ms = DEFAULT_SLOW_MS
    props: dict[str, str] = {}
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for raw in fh:
                line = raw.strip()
                if not line or line.startswith("#") or line.startswith("!"):
                    continue
                if "=" not in line:
                    continue
                key, value = line.split("=", 1)
                props[key.strip()] = value.strip()
    except OSError as exc:
        return files, slow_ms, f"配置不可读（{exc}）→ 回退内置默认端口名与 {DEFAULT_SLOW_MS}ms"

    note = None
    for port, key in PORT_KEYS.items():
        value = props.get(key)
        if value:
            files[port] = os.path.basename(value.replace("\\", "/"))
        else:
            note = f"配置缺少 {key} → 该端回退内置默认名 {DEFAULT_PORT_FILES[port]}"
    raw_slow = props.get("log.slowRequestMs")
    if raw_slow and raw_slow.isdigit():
        slow_ms = int(raw_slow)
    elif raw_slow:
        note = f"配置 log.slowRequestMs 非法（{raw_slow}）→ 回退 {DEFAULT_SLOW_MS}ms"
    return files, slow_ms, note


# ---------------------------------------------------------------------------
# 选文件 + 读取 + 解析（容错：未识别行跳过并计数）
# ---------------------------------------------------------------------------

def list_port_files(log_dir: str, base: str) -> tuple[list[tuple[str, str]], list[str]]:
    """端口文件列表（**旧 → 新**）：裸名最旧、其后按轮转序号 N 降序；排除 `.lck`。

    返回 (files, ignored)：files = [(文件名, 全路径)]；ignored = 形似但后缀非数字的文件名。
    """
    files: list[tuple[int, int, str, str]] = []
    ignored: list[str] = []
    rot_re = re.compile(re.escape(base) + r"\.(\d+)$")
    try:
        names = sorted(os.listdir(log_dir))
    except OSError as exc:
        die(f"无法读取日志目录 {log_dir}（{exc}）")
    for name in names:
        full = os.path.join(log_dir, name)
        if not os.path.isfile(full) or name.endswith(".lck"):
            continue
        if name == base:                      # 裸名文件（如 T1 前的旧格式 system.log）→ 最旧
            files.append((0, 0, name, full))
            continue
        match = rot_re.match(name)
        if match:                             # N 越大越旧 → 取负值使大 N 排在前面
            files.append((1, -int(match.group(1)), name, full))
            continue
        if name.startswith(base + "."):
            ignored.append(name)
    files.sort(key=lambda item: (item[0], item[1], item[2]))
    return [(item[2], item[3]) for item in files], ignored


def iter_lines(path: str) -> list[str]:
    """读一个文件并按行返回；**容错解码**（坏字节替换，不中断）。"""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except OSError as exc:
        die(f"无法读取日志文件 {path}（{exc}）")
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()                            # 末行完整以 LF 结尾时不产生空元素
    return [ln[:-1] if ln.endswith("\r") else ln for ln in lines]


def parse_access(line: str) -> dict | None:
    match = ACCESS_LINE_RE.match(line)
    if not match:
        return None
    msg = ACCESS_MSG_RE.match(match.group("msg"))
    if not msg:
        return None
    return {
        "ts": match.group("ts"), "port": "access", "level": "INFO", "logger": "access",
        "req": match.group("req"), "msg": match.group("msg"),
        "method": msg.group("method"), "path": msg.group("path"),
        "code": int(msg.group("code")), "cost": int(msg.group("cost")), "slow": int(msg.group("slow")),
        "cont_count": 0, "cont_first": None,
    }


def parse_app(port: str):
    """返回应用 / 审计端的行解析函数（闭包携带端口，用于拒绝混入的其它端记录）。"""
    def parse(line: str) -> dict | None:
        match = APP_LINE_RE.match(line)
        if not match:
            return None
        logger = match.group("logger")
        if port == "audit":
            if logger != PORT_LOGGERS["audit"] or not AUDIT_MSG_RE.match(match.group("msg")):
                return None
        elif logger in PORT_LOGGERS.values():  # system / error 端不得出现专属 logger 的记录
            return None
        return {
            "ts": match.group("ts"), "port": port, "level": match.group("level"),
            "logger": logger, "req": match.group("req"), "msg": match.group("msg"),
            "cont_count": 0, "cont_first": None,
        }
    return parse


def read_port(log_dir: str, base: str, parse, allow_cont: bool) -> tuple[list[dict], list[dict], list[str]]:
    """读一个端口的全部文件：返回 (records, files_info, ignored_files)。

    records = 全部结构化记录（含 `file` / `lineno`，供排序与定位）；
    files_info = 每文件 (records / unrecognized / blank) —— **整份文件**的计数（不受窗口影响）。
    堆栈等**续行**归属其前一条记录（计入 cont_count），不单列、不计"未识别"。
    """
    files, ignored = list_port_files(log_dir, base)
    records: list[dict] = []
    files_info: list[dict] = []
    for name, full in files:
        counts = {"records": 0, "unrecognized": 0, "blank": 0}
        pending: dict | None = None
        for lineno, line in enumerate(iter_lines(full), start=1):
            if not line.strip():
                counts["blank"] += 1
                continue
            record = parse(line)
            if record is None:
                if allow_cont and pending is not None and not line.startswith("ts="):
                    pending["cont_count"] += 1
                    if pending["cont_first"] is None:
                        pending["cont_first"] = line.strip()[:200]
                    continue
                counts["unrecognized"] += 1
                continue
            record["file"] = name
            record["lineno"] = lineno
            records.append(record)
            counts["records"] += 1
            pending = record
        files_info.append({"port": base, "file": name, **counts})
    return records, files_info, ignored


# ---------------------------------------------------------------------------
# 三条视角
# ---------------------------------------------------------------------------

def percentile(sorted_values: list[int], ratio: float) -> int:
    """最近秩法（nearest-rank）百分位。"""
    index = math.ceil(ratio * len(sorted_values)) - 1
    return sorted_values[max(index, 0)]


def pick_trace_req(access_rows: list[dict]) -> tuple[str | None, str]:
    """缺省样例：窗口内最新一条 code>=500 的 access 行；无则最新一条 access 行。"""
    for row in reversed(access_rows):
        if row["code"] >= 500:
            return row["req"], "窗口内最新一条 code>=500"
    if access_rows:
        return access_rows[-1]["req"], "窗口内最新一条 access 行"
    return None, "窗口内无 access 行"


def build_trace(all_rows: dict[str, list[dict]], req_id: str | None) -> dict:
    """视角 (a)：在四端**全部行**中定向过滤 req（不受窗口约束）。"""
    if req_id is None:
        return {"req": None, "picked_by": "窗口内无 access 行", "hit_ports": [], "records": []}
    records = [row for port in PORT_ORDER for row in all_rows[port] if row.get("req") == req_id]
    records.sort(key=lambda row: (row["ts"], PORT_ORDER.index(row["port"]), row["file"], row["lineno"]))
    hit_ports = sorted({row["port"] for row in records}, key=PORT_ORDER.index)
    return {"req": req_id, "hit_ports": hit_ports, "records": records}


def build_latency(access_rows: list[dict], top: int, slow_ms: int) -> dict:
    """视角 (b)：按 path 聚合 cost 分布 + slow=1 Top N + 标记一致性核对。"""
    by_path: dict[str, list[dict]] = defaultdict(list)
    for row in access_rows:
        by_path[row["path"]].append(row)

    paths = []
    for path, rows in by_path.items():
        costs = sorted(row["cost"] for row in rows)
        paths.append({
            "path": path, "count": len(rows), "avg": round(sum(costs) / len(costs), 1),
            "p50": percentile(costs, 0.50), "p95": percentile(costs, 0.95), "max": costs[-1],
            "slow": sum(1 for row in rows if row["slow"] == 1),
        })
    paths.sort(key=lambda item: (-item["p95"], -item["max"], -item["count"], item["path"]))

    slow_rows = [row for row in access_rows if row["slow"] == 1]
    slow_lines = [
        {"ts": row["ts"], "path": row["path"], "cost": row["cost"], "file": row["file"], "lineno": row["lineno"]}
        for row in sorted(slow_rows, key=lambda row: (-row["cost"], row["ts"]))[:top]
    ]
    consistency = {
        "slow_ms": slow_ms,
        "slow_marked": len(slow_rows),
        # 记录值即权威（写入端阈值可配）：这里只做"标记与其阈值是否自洽"的计数，不做不变式断言
        "cost_over_threshold": sum(1 for row in access_rows if row["cost"] >= slow_ms),
        "mismatch": sum(1 for row in access_rows if (row["slow"] == 1) != (row["cost"] >= slow_ms)),
    }
    return {
        "paths": paths[:top], "paths_total": len(paths),
        "slow_lines": slow_lines, "slow_total": len(slow_rows), "consistency": consistency,
    }


def build_errors(access_rows: list[dict], top: int) -> dict:
    """视角 (c)：结果码分布（4xx 预期拒绝 / 5xx 失败分列；code=0 = 非业务/未收口）。"""
    by_code = Counter(row["code"] for row in access_rows)
    codes = {
        "200": by_code.get(200, 0),
        "4xx": sum(count for code, count in by_code.items() if 400 <= code <= 499),
        "5xx": sum(count for code, count in by_code.items() if 500 <= code <= 599),
        "0": by_code.get(0, 0),
    }
    codes["other"] = len(access_rows) - sum(codes.values())
    denominator = len(access_rows) - codes["0"]     # 分母 = 走过业务统一出口的行（code != 0）
    top_5xx = [
        {"path": path, "count": count}
        for path, count in Counter(row["path"] for row in access_rows if 500 <= row["code"] <= 599).most_common(top)
    ]
    top_4xx = [
        {"path": path, "code": code, "count": count}
        for (path, code), count in Counter(
            (row["path"], row["code"]) for row in access_rows if 400 <= row["code"] <= 499
        ).most_common(top)
    ]
    return {
        "total": len(access_rows),
        "by_code": {str(code): count for code, count in sorted(by_code.items())},
        "codes": codes,
        "rates": {
            "denominator": denominator,
            "reject_4xx": round(codes["4xx"] / denominator, 4) if denominator else 0.0,
            "fail_5xx": round(codes["5xx"] / denominator, 4) if denominator else 0.0,
        },
        "top_5xx": top_5xx,
        "top_4xx": top_4xx,
    }


# ---------------------------------------------------------------------------
# 输出（人读 / JSON）
# ---------------------------------------------------------------------------

def clip(text: str, width: int = 90) -> str:
    text = text.replace("\t", " ")
    return text if len(text) <= width else text[: width - 3] + "..."


def render_text(report: dict) -> str:
    meta, overview = report["meta"], report["overview"]
    trace, latency, errors = report["trace"], report["latency"], report["errors"]
    out: list[str] = []
    add = out.append

    add(f"日志消费报告（只读）  数据源={meta['dir']}")
    add(f"  窗口={meta['window']}  慢请求阈值={meta['slow_ms']}ms  配置={meta['config']}")
    if meta.get("config_note"):
        add(f"  配置提示: {meta['config_note']}")
    if meta["files"]:
        parts = [f"{item['file']}(记录 {item['records']} / 未识别 {item['unrecognized']} / 空行 {item['blank']})"
                 for item in meta["files"]]
        add("  文件: " + " | ".join(parts))
    else:
        add("  文件: （未找到任何项目日志文件——检查 --dir 指向的链路）")
    if meta["ignored_files"]:
        add("  忽略文件（后缀非轮转序号）: " + ", ".join(meta["ignored_files"]))
    add("")
    if meta["files"]:
        window_line = " / ".join(f"{port} {count}" for port, count in meta["window_counts"].items())
        add(f"[概览] 窗口内记录行: {window_line} ；未识别行（全文件）{meta['unrecognized_total']} "
            f"；空行 {meta['blank_total']}")
    else:
        add("[概览] 无记录行（未找到项目日志文件）")
    if overview.get("audit_actions"):
        add("    audit 动作分布（窗口内，只列出现的动作）: "
            + " ; ".join(f"{action} x{count}" for action, count in overview["audit_actions"].items()))
    add("")

    hit_desc = f"命中 {len(trace['hit_ports'])} 端" + (f": {', '.join(trace['hit_ports'])}" if trace["hit_ports"] else "")
    if trace["req"] is None:
        add(f"[a] req 追溯: 无目标 req（{trace.get('picked_by', '')}；可用 --req 指定）")
    else:
        add(f"[a] req 追溯: req={trace['req']}（{trace.get('picked_by', '--req 指定')}；{hit_desc}）")
    if not trace["records"]:
        add("    （无记录）" if trace["req"] is None else "    （无记录：该 req 不在四端任何文件中）")
    for row in trace["records"]:
        cont = f" [续行 {row['cont_count']}]" if row["cont_count"] else ""
        add(f"    {row['ts']}  {row['port']:<6} {row['level']:<7} {clip(row['logger'], 42):<42} {clip(row['msg'])}{cont}")
        if row.get("cont_first"):
            add(f"        ↳ {clip(row['cont_first'], 96)}")
    add("")

    add(f"[b] 耗时分布（按 path，p95 降序 Top {len(latency['paths'])}/{latency['paths_total']}）")
    add(f"    {'path':<34} {'次数':>6} {'平均':>8} {'p50':>6} {'p95':>6} {'最大':>6} {'slow=1':>7}")
    for item in latency["paths"]:
        add(f"    {clip(item['path'], 34):<34} {item['count']:>6} {item['avg']:>8} "
            f"{item['p50']:>6} {item['p95']:>6} {item['max']:>6} {item['slow']:>7}")
    if not latency["paths"]:
        add("    （窗口内无 access 行）")
    add(f"    slow=1 明细（Top {len(latency['slow_lines'])}/{latency['slow_total']}，按 cost 降序）")
    for item in latency["slow_lines"]:
        add(f"      {item['ts']}  {item['path']}  cost={item['cost']}ms")
    consistency = latency["consistency"]
    add(f"    一致性核对: slow=1 {consistency['slow_marked']} 条 / cost>={consistency['slow_ms']}ms "
        f"{consistency['cost_over_threshold']} 条 / 不一致 {consistency['mismatch']} 条"
        "（不一致多为写入端用了不同阈值，如 JUnit 直测阈值 0 的 /probe 行）")
    add("")

    add(f"[c] 错误率（窗口内 access {errors['total']} 行；分母=有结果码的业务行 code!=0）")
    if errors["by_code"]:
        add("    结果码分布: " + " / ".join(f"{code} x{count}" for code, count in errors["by_code"].items())
            + f" ；code=0（非业务/未收口）x{errors['codes']['0']}")
    else:
        add("    结果码分布: （窗口内无 access 行）")
    add(f"    4xx 预期拒绝率 {errors['rates']['reject_4xx']:.2%}（{errors['codes']['4xx']}/{errors['rates']['denominator']}）"
        f" ；5xx 失败率 {errors['rates']['fail_5xx']:.2%}（{errors['codes']['5xx']}/{errors['rates']['denominator']}）")
    if errors["top_5xx"]:
        add("    5xx Top: " + " ; ".join(f"{item['path']} x{item['count']}" for item in errors["top_5xx"]))
    if errors["top_4xx"]:
        add("    4xx Top: " + " ; ".join(f"{item['path']} code={item['code']} x{item['count']}"
                                          for item in errors["top_4xx"]))
    return "\n".join(out)


def trace_record_json(row: dict) -> dict:
    return {
        "ts": row["ts"], "port": row["port"], "level": row["level"], "logger": row["logger"],
        "msg": row["msg"], "file": row["file"], "lineno": row["lineno"],
        "cont_count": row["cont_count"], "cont_first": row["cont_first"],
    }


def render_json(report: dict) -> str:
    meta, trace = report["meta"], report["trace"]
    payload = {
        "meta": {
            "dir": meta["dir"], "config": meta["config"], "config_note": meta.get("config_note"),
            "window": meta["window"], "limit": meta["limit"], "slow_ms": meta["slow_ms"],
            "files": meta["files"], "ignored_files": meta["ignored_files"],
            "unrecognized_total": meta["unrecognized_total"], "blank_total": meta["blank_total"],
            "window_counts": meta["window_counts"],
        },
        "overview": report["overview"],
        "trace": {
            "req": trace["req"], "picked_by": trace.get("picked_by"), "hit_ports": trace["hit_ports"],
            "records": [trace_record_json(row) for row in trace["records"]],
        },
        "latency": report["latency"],
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
    log_dir = os.path.abspath(args.dir)
    if not os.path.isdir(log_dir):
        die(f"日志目录不存在: {log_dir}（--dir 指定；e2e 默认 {DEFAULT_LOG_DIR}）")

    port_files, slow_ms, config_note = resolve_config(args.config)
    if args.slow_ms is not None:
        slow_ms = args.slow_ms
    limit = None if args.all else (args.limit if args.limit is not None else DEFAULT_LIMIT)
    window = "全量（--all）" if limit is None else f"每端最近 {limit} 行（ts 升序尾部）"

    all_rows: dict[str, list[dict]] = {}
    files_info: list[dict] = []
    ignored_files: list[str] = []
    for port in PORT_ORDER:
        parse = parse_access if port == "access" else parse_app(port)
        rows, info, ignored = read_port(log_dir, port_files[port], parse, allow_cont=(port != "access"))
        # 按 ts 升序稳定排序（同 ts 保持"旧文件在前"的顺序，不依赖 mtime）
        rows.sort(key=lambda row: row["ts"])
        all_rows[port] = rows
        files_info.extend(info)
        ignored_files.extend(ignored)

    windowed = {port: (rows if limit is None else rows[-limit:]) for port, rows in all_rows.items()}
    access_window = windowed["access"]
    # 审计动作分布（窗口内；只列出现的动作——未出现即 0 条，如 user.changePhone 无 HTTP 入口）
    audit_actions = Counter(
        match.group(1) for row in windowed["audit"]
        if (match := AUDIT_ACTION_RE.match(row["msg"]))
    )
    audit_actions = {action: count for action, count in sorted(audit_actions.items(), key=lambda kv: (-kv[1], kv[0]))}

    req_id, picked_by = (args.req, "--req 指定") if args.req else pick_trace_req(access_window)
    trace = build_trace(all_rows, req_id)
    trace["picked_by"] = picked_by if args.req is None else "--req 指定"

    report = {
        "meta": {
            "dir": log_dir, "config": os.path.abspath(args.config), "config_note": config_note,
            "window": window, "limit": limit, "slow_ms": slow_ms,
            "files": files_info, "ignored_files": ignored_files,
            "unrecognized_total": sum(item["unrecognized"] for item in files_info),
            "blank_total": sum(item["blank"] for item in files_info),
            "window_counts": {port: len(rows) for port, rows in windowed.items()},
        },
        "overview": {
            "records_by_port": {port: len(rows) for port, rows in all_rows.items()},
            "records_in_window": {port: len(rows) for port, rows in windowed.items()},
            "audit_actions": audit_actions,
        },
        "trace": trace,
        "latency": build_latency(access_window, args.top, slow_ms),
        "errors": build_errors(access_window, args.top),
    }

    print(render_json(report) if args.json else render_text(report))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))