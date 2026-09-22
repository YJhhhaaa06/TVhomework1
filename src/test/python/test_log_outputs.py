# -*- coding: utf-8 -*-
"""T4（log-04）收尾：日志**输出端分流**与**跨输出端串联**的端到端断言。

补的是 T1 留下的那条口子（T1 覆盖边界①原话："error 只收 SEVERE 的自动化断言属 pytest 侧（T3/T4）"）：
T1~T3 的 JUnit 只到"装配层"（输出端规格表、handler 的 level、useParentHandlers、轮转参数），
**真实落盘的文件级分流**只能由本文件断言——直接读测试实例日志目录里的真实文件。

前置：run_tests.py 启动的独立实例（18080 + 测试库 3307）+ 注入
`LOG_PATH=.stage8-target/tomcat-test-18080/logs/system.log` → 三个输出端（system / error /
access）都落该目录（相对路径只取文件名、锚 `log.file` 目录，见 `LogUtil` 类注释）。

读法：轮转后文件名为 `<名>.<N>`（N=0 为当前写入文件，目录里另有 JUL 的 `<名>.N.lck` 锁文件
→ 选文件时必须排除），且 FileHandler append=true → 既有 run 的行仍留在文件里。故断言分两类：
① **全文件不变式**——分流口径必须对历史上每一行都成立；
② **本 run 记录**——**不依赖行号增量**（轮转/并发写入下易脆），而是给触发请求一个**唯一指纹**：
`contentId` 用随机 marker，异常栈里的 `For input string: "<marker>"` 即本次请求的标记，
向前回退到最近的 `ts=` 记录行即为该请求在 error/system 输出端的记录（`record_containing`）。

SEVERE 触发点：`GET /comment/show?contentId=<非数字>`。`CommentController.showComment`
直接 `Long.parseLong(contentIdStr)`（该分支无 try/catch）→ `NumberFormatException` 未被业务
包成 `BusinessException` → `ExceptionFilter` 的 `catch (Exception)` 记 SEVERE「未处理异常」
并回 500。选它是因为它是当前**唯一可稳定复现的未处理异常路径**（其余失败路径都是业务异常/
参数异常，走 WARNING）。本文件断言的是**日志分流与串联**、不是响应码设计——若将来该参数
解析被改为 `ParamException`(400)，对应用例需换触发点（届时见本注释）。
"""

import glob
import os
import re
import time
import uuid

import requests

from conftest import ACCESS_LOG_DIR, BASE_URL

# 结构化单行形态：ts=<ISO，3 位毫秒 + 带冒号时区> level=… logger=… [req=<16 位 hex> ]msg=…
TS = r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}"
REQ = r"[0-9a-f]{16}"
REQ_RE = re.compile(rf"req=({REQ})")

# 访问行（LogFormatter 前缀 + AccessLogFilter 的 msg 字段，字段顺序即实现顺序）
ACCESS_LINE_RE = re.compile(
    rf"^ts={TS} level=INFO logger=access req={REQ} "
    rf"msg=method=\S+ path=\S+ userId=(?:-|\d+) code=\d+ cost=\d+ms slow=[01]$"
)
# 应用日志行（system / error 输出端）：级别不限；请求线程内带 req=、非请求线程整段不出现
APP_LINE_RE = re.compile(rf"^ts={TS} level=(\w+) logger=\S+ (?:req={REQ} )?msg=")


# ---------------------------------------------------------------------------
# 读取助手（轮转文件 + 本 run 记录定位）
# ---------------------------------------------------------------------------

def log_path(prefix):
    """目录里按前缀命名的轮转日志文件中最新一个（mtime 降序）；排除 JUL 的 `.lck` 锁文件。"""
    candidates = [p for p in glob.glob(os.path.join(ACCESS_LOG_DIR, prefix))
                  if not p.endswith(".lck")]
    if not candidates:
        return None
    return max(candidates, key=os.path.getmtime)


def read_lines(path):
    """严格 UTF-8 解码后按行读（坏字节/编码错即失败——日志可读性是本周期目标之一）。"""
    with open(path, encoding="utf-8") as fh:
        return fh.read().splitlines()


def snapshot(prefix):
    """当前最新文件 + 行数，仅用于断言"本 run 确实新写了行"（不作为精确定位的依据）。"""
    path = log_path(prefix)
    return (path, len(read_lines(path)) if path else 0)


def new_lines(prefix, snap):
    """快照之后新增的行；若期间发生轮转（快照路径不再是当前文件）→ 返回整份当前文件。

    **仅用于"有新写入"这类粗判**：精确定位本次请求的落盘记录一律走
    `record_containing()`（唯一指纹），不依赖行号增量。
    """
    path = log_path(prefix)
    assert path, f"日志文件未生成（前缀 {prefix}，目录 {ACCESS_LOG_DIR}）"
    lines = read_lines(path)
    if snap[0] == path and len(lines) >= snap[1]:
        return lines[snap[1]:]
    return lines


def wait_new_lines(prefix, snap, predicate, timeout=3.0):
    """轮询"快照增量行"直到 predicate 成立（覆盖"响应先于落盘"的窗口）；超时 fail。"""
    deadline = time.time() + timeout
    while True:
        lines = new_lines(prefix, snap)
        if predicate(lines):
            return lines
        if time.time() >= deadline:
            raise AssertionError(
                f"{prefix} 未在 {timeout}s 内出现满足条件的增量行（目录 {ACCESS_LOG_DIR}）；"
                f"增量前 3 行 = {[ln[:120] for ln in lines[:3]]}"
            )
        time.sleep(0.05)


def wait_for_line(prefix, predicate, timeout=3.0):
    """轮询整份最新文件，返回第一个满足 predicate 的行；超时 fail（供 access 侧按 req 回查）。"""
    deadline = time.time() + timeout
    while True:
        path = log_path(prefix)
        assert path, f"日志文件未生成（前缀 {prefix}，目录 {ACCESS_LOG_DIR}）"
        for line in read_lines(path):
            if predicate(line):
                return line
        if time.time() >= deadline:
            raise AssertionError(
                f"{prefix} 未在 {timeout}s 内出现满足条件的行（目录 {ACCESS_LOG_DIR}）"
            )
        time.sleep(0.05)


def unique_marker():
    """本次请求的唯一指纹（放进 contentId，经异常栈回显）。"""
    return "t4-" + uuid.uuid4().hex[:12]


def trigger_unhandled_exception(marker):
    """触发一次未处理异常（SEVERE + 500），返回响应体。触发点说明见模块 docstring。

    `marker` 会出现在 `NumberFormatException` 的 `For input string: "<marker>"` 里。
    """
    resp = requests.get(f"{BASE_URL}/comment/show",
                        params={"contentId": marker}, timeout=10)
    assert resp.status_code == 200, (
        f"本项目响应恒为 HTTP 200 + body code，未走统一出口才会变 status: {resp.status_code}"
    )
    return resp.json()


def record_containing(prefix, marker, timeout=3.0):
    """定位"含 `marker` 的那条记录"，返回 `(记录行, 该记录的续行列表)`。

    记录行 = marker 所在行**向前**最近的、以 `ts=` 开头的行（异常堆栈跟随首行，不另起记录）；
    续行 = 记录行之后到"下一个 `ts=` 行"之前的行。轮转 / 陈旧行均不影响定位——marker 唯一。
    """
    deadline = time.time() + timeout
    while True:
        path = log_path(prefix)
        assert path, f"日志文件未生成（前缀 {prefix}，目录 {ACCESS_LOG_DIR}）"
        lines = read_lines(path)
        for i, line in enumerate(lines):
            if marker not in line:
                continue
            start = i
            while start > 0 and not lines[start].startswith("ts="):
                start -= 1
            end = i
            while end + 1 < len(lines) and not lines[end + 1].startswith("ts="):
                end += 1
            assert lines[start].startswith("ts="), (
                f"{prefix} 中 marker {marker!r} 之前找不到结构化记录行（行 {start + 1}）"
            )
            return lines[start], lines[start + 1:end + 1]
        if time.time() >= deadline:
            raise AssertionError(
                f"{prefix} 未在 {timeout}s 内出现含 marker {marker!r} 的记录（目录 {ACCESS_LOG_DIR}）"
            )
        time.sleep(0.05)


def one_request():
    """产生一次普通成功请求（公共首页，与 run_tests 就绪探测同一端点，无需登录）。"""
    resp = requests.get(f"{BASE_URL}/start", timeout=10)
    body = resp.json()
    assert body.get("code") == 200, f"/start 应成功: {body}"
    return body


# ---------------------------------------------------------------------------
# ① 访问行形态：严格单行结构化 + LF 行尾
# ---------------------------------------------------------------------------

def test_access_lines_are_single_line_structured():
    """access 输出端每一行都必须是完整单行结构化访问日志（字段齐、req 16 位、行尾 LF）。"""
    snap = snapshot("access.log*")
    one_request()
    wait_new_lines("access.log*", snap, lambda lines: len(lines) > 0)

    path = log_path("access.log*")
    with open(path, "rb") as fh:
        raw = fh.read()
    text = raw.decode("utf-8")  # 严格解码：存在坏字节即失败
    assert "\r" not in text, "access.log 行尾必须固定 LF（不得出现 CR）"
    assert text.endswith("\n"), "access.log 末行应完整以 LF 结尾（不得截断在写一半）"

    lines = text[:-1].split("\n")
    assert lines, "access.log 为空"
    bad = [ln for ln in lines if not ACCESS_LINE_RE.match(ln)]
    assert not bad, (
        f"存在不符合单行结构化格式的 access 行（共 {len(bad)}/{len(lines)} 行，前 2 条）: "
        f"{[ln[:160] for ln in bad[:2]]}"
    )


# ---------------------------------------------------------------------------
# ② error 输出端：只收 SEVERE
# ---------------------------------------------------------------------------

def test_error_output_keeps_only_severe():
    """error.log 只收 SEVERE：本 run 的未处理异常必落 error 输出端，且文件内无低级别行。"""
    marker = unique_marker()
    body = trigger_unhandled_exception(marker)
    assert body.get("code") == 500, f"未处理异常应回 500: {body}"

    record, cont = record_containing("error.log*", marker)
    assert " level=SEVERE " in record, f"该异常在 error 输出端应为 SEVERE 行: {record[:200]}"
    assert "未处理异常" in record, f"该异常在 error 输出端应记为未处理异常: {record[:200]}"
    assert cont, "SEVERE 记录应带异常堆栈续行（堆栈跟在首行之后）"
    assert all(not ln.startswith("ts=") for ln in cont), (
        f"异常堆栈不得自成结构化记录（续行里出现了 ts= 开头）: {[ln[:120] for ln in cont[:2]]}"
    )

    # 全文件不变式：文件里所有结构化行都必须是 SEVERE
    offenders = [ln for ln in read_lines(log_path("error.log*"))
                 if APP_LINE_RE.match(ln) and " level=SEVERE " not in ln]
    assert not offenders, (
        f"error.log 存在非 SEVERE 的历史行（{len(offenders)} 条，前 2 条）: "
        f"{[ln[:160] for ln in offenders[:2]]}"
    )


# ---------------------------------------------------------------------------
# ③ 输出端互不污染：access 行不进 system.log，应用日志不进 access.log
# ---------------------------------------------------------------------------

def test_system_and_access_outputs_are_not_mixed():
    """access 输出端挂专属 logger + useParentHandlers=false 的文件级证明（D12 ①）。"""
    snap = snapshot("access.log*")
    one_request()
    wait_new_lines("access.log*", snap, lambda lines: len(lines) > 0)

    access_lines = [ln for ln in read_lines(log_path("access.log*")) if ln.startswith("ts=")]
    system_lines = [ln for ln in read_lines(log_path("system.log*")) if ln.startswith("ts=")]
    assert access_lines, "access.log 无结构化访问行"
    assert system_lines, "system.log 无结构化应用日志行"

    assert all(" logger=access " in ln for ln in access_lines), (
        "access.log 混入了非访问日志记录（应用日志不得下发到 access）: "
        f"{[ln[:160] for ln in access_lines if ' logger=access ' not in ln][:2]}"
    )
    leaked = [ln for ln in system_lines if " logger=access " in ln]
    assert not leaked, (
        f"access 行泄进了 system.log（useParentHandlers 未生效？）共 {len(leaked)} 条，"
        f"前 2 条: {[ln[:160] for ln in leaked[:2]]}"
    )


# ---------------------------------------------------------------------------
# ④ 跨输出端串联：同一 reqId 贯穿 access / system / error 三个文件
# ---------------------------------------------------------------------------

def test_severe_request_correlates_across_three_outputs():
    """一次未处理异常请求：access 记 code=500，system 与 error 记同 req 的 SEVERE（D6 收益）。"""
    marker = unique_marker()
    body = trigger_unhandled_exception(marker)
    assert body.get("code") == 500, f"未处理异常应回 500: {body}"

    # 以 error 输出端的该请求记录为锚：取出 reqId（本次请求在各输出端的唯一串联键）
    record, _ = record_containing("error.log*", marker)
    match = REQ_RE.search(record)
    assert match, f"该请求的 SEVERE 记录应带 req=: {record[:200]}"
    req_id = match.group(1)

    # system 输出端：同 req 的 SEVERE 行（同一请求可跨文件 grep 串联）
    home = f"req={req_id} "
    system_line = wait_for_line("system.log*", lambda ln: home in ln and " level=SEVERE " in ln)
    assert "未处理异常" in system_line, f"system.log 同 req 记录应为未处理异常: {system_line[:200]}"

    # access 输出端：同 req 的行，且结果码收口为 500（未处理异常路径）
    access_line = wait_for_line("access.log*", lambda ln: home in ln)
    assert " path=/comment/show " in access_line, f"access 行 path 应为本次请求: {access_line[:200]}"
    assert " code=500 " in access_line, f"access 行结果码应为 500: {access_line[:200]}"
