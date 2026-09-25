# -*- coding: utf-8 -*-
"""T3（log-03）访问日志端到端断言：access.out 落一行且字段完整 / 结果码与响应体一致 /
reqId 串联异常路径 / 敏感值（密码·手机号·token）绝不落盘。

前置：run_tests.py 启动的独立实例（18080 + 测试库 3307 + media-test），LOG_PATH 注入
tomcat-test-18080/logs → access 输出端落同目录 access.log（轮转后 <名>.N，N=0 当前）。

约定：与 conftest 相同的"最新轮转文件 + 轮询等待"读法（请求已返回但 access 行可能
仍在落盘窗口内 → 轮询至多 3s），避免偶发误报。
"""

import glob
import os
import re
import time
import uuid

import requests

from conftest import ACCESS_LOG_DIR, BASE_URL, USER_A
from conftest import login_user, register_user

REQ_RE = re.compile(r"req=([0-9a-f]{16})")
COST_RE = re.compile(r"cost=(\d+)ms")
# 慢阈值默认 1000ms：本地 db/redis 请求 ms 级，正常请求必 slow=0（slow=1 的触发走 JUnit 纯函数边界）
SLOW_DEFAULT_MS = 1000


def newest_log(prefix):
    """目录中按前缀命名的轮转日志文件里最新一个（mtime 降序），不存在返回 None。"""
    files = sorted(glob.glob(os.path.join(ACCESS_LOG_DIR, prefix)), key=os.path.getmtime, reverse=True)
    return files[0] if files else None


def read_access_lines():
    path = newest_log("access.log*")
    assert path, f"access.log 未生成（目录: {ACCESS_LOG_DIR}）"
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read().splitlines()


def read_system_lines():
    path = newest_log("system.log*")
    assert path, f"system.log 未生成（目录: {ACCESS_LOG_DIR}）"
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read().splitlines()


def wait_for_access_line(marker, timeout=3.0):
    """轮询 access.log，返回第一个含 marker 的行；超时 fail（覆盖"响应先于落盘返回"的窗口）。"""
    line = wait_for_access_line_where(lambda ln: marker in ln, timeout)
    if line is None:
        raise AssertionError(f"access.log 未在 {timeout}s 内出现含 {marker!r} 的行（目录 {ACCESS_LOG_DIR}）")
    return line


def wait_for_access_line_where(predicate, timeout=3.0):
    """轮询 access.log，返回第一个满足 predicate 的行；无命中且超时返回 None。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for line in read_access_lines():
            if predicate(line):
                return line
        time.sleep(0.05)
    return None


def unique_phone():
    """生成 11 位唯一手机号（138 + 8 位纯数字），与 conftest 的 USER_A/B 风格一致。"""
    return f"138{str(int(uuid.uuid4().hex[:8], 16))[-8:].zfill(8)}"


# ---------------------------------------------------------------------------
# ① 成功请求：一行 + 字段完整 + 耗时>0
# ---------------------------------------------------------------------------

def test_success_request_has_full_access_line(user_a):
    resp = login_user(USER_A["phone"], USER_A["password"])
    assert resp.get("code") == 200, f"login 应成功: {resp}"
    token = (resp.get("data") or {}).get("token")
    assert token, f"login 应返回 token: {resp}"

    # 最新 access 行：path=/user/login + 全字段（加 code=200 条件，避免与跨 run 残留的同 path 旧行竞争）
    line = wait_for_access_line_where(lambda ln: "path=/user/login " in ln and " code=200 " in ln)
    assert line, "access.log 未出现 code=200 的 /user/login 行"
    assert "method=POST " in line, line
    assert "path=/user/login " in line, line
    assert "userId=" in line, line
    # 注：login 为公共端点（请求不带 token → LoginFilter 不注入 userId），行的 userId=- 属预期；
    # 登录用户落在 userId=<id> 的断言由下方带 token 的受保护请求覆盖
    assert "code=200 " in line, f"成功请求结果码应为 200: {line}"
    assert " slow=0" in line, f"本地请求不应带慢标记: {line}"

    cost = COST_RE.search(line)
    assert cost, f"缺少 cost 字段: {line}"
    assert int(cost.group(1)) > 0, f"真实请求耗时应 > 0ms: {line}"

    assert REQ_RE.search(line), f"缺少 req= (16 位 hex): {line}"

    # 带 token 的受保护请求：userId 应落到具体 id（LoginFilter 注入 Long attribute）。
    # 注意不能只按 path 匹配首行——同路径可能有"无 token 401"的杂乱请求行（如 coupon 域测试），
    # 必须 path + code=200 + userId 三条件齐备才算命中我们这次请求。
    authed = requests.get(f"{BASE_URL}/coupon/my", headers={"token": token}, timeout=10)
    assert authed.json().get("code") == 200, f"受保护接口应 200: {authed.text}"
    authed_line = wait_for_access_line_where(
        lambda ln: "path=/coupon/my " in ln and " code=200 " in ln and "userId=- " not in ln
    )
    assert authed_line, f"应找到带 token 的 /coupon/my 200 行且 userId=<id>"
    # T4：原 `return line` 让 pytest 报 PytestReturnNotNoneWarning（测试函数应返回 None）；
    # 需要的断言都已在上方完成，删除返回值不影响覆盖。


# ---------------------------------------------------------------------------
# ② 未登录访问受保护接口 → 结果码 401（userId=-）
# ---------------------------------------------------------------------------

def test_unauthorized_request_gets_code_401():
    resp = requests.get(f"{BASE_URL}/user/changePassword", timeout=10)
    assert resp.json().get("code") == 401, f"未登录访问受保护接口应 401: {resp.text}"

    line = wait_for_access_line("path=/user/changePassword ")
    assert "code=401 " in line, f"未登录结果码应为 401: {line}"
    assert "userId=- " in line, f"未登录应记 userId=-: {line}"


# ---------------------------------------------------------------------------
# ③ 静态资源（未走 BaseServletUtil）→ code=0 缺省语义
# ---------------------------------------------------------------------------

def test_static_resource_gets_code_zero():
    resp = requests.get(f"{BASE_URL}/index.html", timeout=10)
    assert resp.status_code == 200

    line = wait_for_access_line("path=/index.html ")
    assert "code=0 " in line, f"静态资源未走业务出口，结果码缺省应为 0: {line}"


# ---------------------------------------------------------------------------
# ④ 异常路径端到端串联：同一 req 贯穿 access 行与 system 应用日志
# ---------------------------------------------------------------------------

def test_error_path_request_id_correlates_with_app_logs():
    phone = unique_phone()
    first = register_user("dup_" + phone, phone, USER_A["password"])
    assert first.get("code") == 200, f"首次注册应成功: {first}"

    second = register_user("dup_" + phone, phone, USER_A["password"])
    assert second.get("code") == 409, f"同号二次注册应 409: {second}"

    # access 行：/user/register code=409（首个 register 是 200，须按 code 精确定位 409 行），附带该请求的 req
    deadline = time.time() + 3.0
    line = None
    while time.time() < deadline:
        for ln in read_access_lines():
            if "path=/user/register " in ln and "code=409 " in ln:
                line = ln
                break
        if line:
            break
        time.sleep(0.05)
    assert line, "access.log 未在 3s 内出现 code=409 的 /user/register 行"
    assert "code=409 " in line, f"重复注册结果码应为 409: {line}"
    match = REQ_RE.search(line)
    assert match, f"access 行应有 req=: {line}"
    req_id = match.group(1)

    # system 应用日志：ExceptionFilter 在链内、reqId 仍有效时打的 WARNING 必须带同一 req
    system_lines = read_system_lines()
    correlated = [
        ln for ln in system_lines
        if f"req={req_id} " in ln and "业务异常" in ln
    ]
    assert correlated, (
        f"system.log 应存在带 req={req_id} 的业务异常日志（D6 收益端到端落地）; "
        f"access req={req_id}, system 中同 req 行数={len([ln for ln in system_lines if f'req={req_id} ' in ln])}"
    )


# ---------------------------------------------------------------------------
# ⑤ 隐私守卫：密码 / 手机号 / token 明文一律不落盘
# ---------------------------------------------------------------------------

def test_secrets_never_land_in_access_log(user_a):
    login_user(USER_A["phone"], USER_A["password"])
    token = user_a["token"]

    content = "\n".join(read_access_lines())
    for secret in (USER_A["password"], USER_A["phone"], token):
        assert secret not in content, (
            f"访问日志不得出现敏感值（password/phone/token 明文）: {secret!r} 长度={len(secret)}"
        )