# -*- coding: utf-8 -*-
"""test_audit_log.py — T8（log2-08）审计日志端到端断言。

覆盖（对应 T8 验收 ①~⑤）：
① 操作点在**成功路径**各产生**恰好一条**审计记录，字段完整（操作者非空 / 操作 / 对象可读 /
   结果正确 / 时间带毫秒与时区）——`audited()` 统一断言；
② 文件级证明 `audit.log` 与 `system.log` / `access.log` **互不污染**；
③ 覆盖边界：**`changePhone` 不在本文件**——它没有 HTTP 入口（`LoginController` 只暴露
   `/changePassword`、`/changeUserName`；`UserService.changePhone` 仅被 JUnit 调用），该点由
   `AuditLogTest` / `UserServiceTest` 覆盖。本文件覆盖**可 HTTP 触达的 6 点**（admin 4 + 用户侧 2）；
④ 无敏感值落盘（新密码 / 手机号明文不出现在 audit.log，且审计行不含 query 串）；
⑤ 对外行为零变化（本文件只按既有契约断言响应 `code`——行为细节由既有用例负责）。

## 读法（先例 `src/test/python/test_log_outputs.py`，本文件沿用同一套）
- 输出端文件是轮转形态 `audit.log.<N>`；选文件 = "前缀匹配 + mtime 最新"，**排除 JUL 的 `.lck`**。
- **不用行号增量定位本次记录**（落盘期间可能轮转 → 假绿/假红）：改用**唯一指纹** =
  审计行的 `action=… operatorId=… target=… result=success` 三元组，其中 target / operatorId 全部
  来自**本 run 新建的对象**（自建内容、自建评论、自建管理员、自建专用户）。定位口径 =
  **全文件计数 delta**（`before` → `before+1`）：即便内容 id 因测试库重建而复用、历史行仍在，
  本 run 的成功操作也只会让计数**恰好 +1**；"恰好一条"= 增量恰为 1（而不是"全文件只有 1 条"）。
  **前提**：本 run 的审计量级远小于 `log.maxBytes`（出厂 10MB）、不发生轮转；若将来审计量级增长到会
  轮转，需改为按 `req=` 定位（轮转会切走历史行，计数口径失效）。

## 前置
`run_tests.py` 启动的独立实例（18080 + 测试库 3307）+ 注入
`LOG_PATH=.stage8-target/tomcat-test-18080/logs/system.log` → 四个输出端（system / error /
access / audit）同目录（相对路径只取文件名，锚 `log.file` 的目录）。
"""

import glob
import os
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path

import pytest
import requests

from conftest import ACCESS_LOG_DIR, BASE_URL

PROJECT_ROOT = Path(__file__).resolve().parents[3]

# 结构化单行形态（LogFormatter 前缀 + AuditLog 的 msg 字段；字段顺序即实现顺序）
TS = r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}"
REQ = r"[0-9a-f]{16}"

AUDIT_LINE_RE = re.compile(
    rf"^ts={TS} level=INFO logger=audit req={REQ} "
    rf"msg=action=\S+ operatorId=(?:\d+|-) target=\S+ result=success$"
)


# ---------------------------------------------------------------------------
# 读法助手（前缀匹配 + mtime 最新 + 排除 .lck）
# ---------------------------------------------------------------------------

def log_path(prefix):
    """目录里按前缀命名的轮转日志文件中最新一个（mtime 降序）；排除 JUL 的 `.lck` 锁文件。"""
    candidates = [p for p in glob.glob(os.path.join(ACCESS_LOG_DIR, prefix))
                  if not p.endswith(".lck")]
    if not candidates:
        return None
    return max(candidates, key=os.path.getmtime)


def read_lines(prefix):
    """最新文件的全部行；文件不存在返回空列表（供"该输出端可能没有本类记录"的判空）。"""
    path = log_path(prefix)
    if path is None:
        return []
    with open(path, encoding="utf-8") as fh:
        return fh.read().splitlines()


def audit_needle(action, operator_id, target):
    """该操作点本 run 审计记录的唯一指纹（字段顺序与 AuditLog.buildLine 一致）。"""
    return f"action={action} operatorId={operator_id} target={target} result=success"


def audit_count(needle):
    """当前 audit.log 中含该指纹的行数（**全文件计数**，不做行号增量）。"""
    return sum(1 for line in read_lines("audit.log*") if needle in line)


def audited(request_fn, action, operator_id, target, timeout=5.0, expect_code=200):
    """执行 `request_fn()`（返回响应 JSON），断言：
    ① 响应 `code == expect_code`（对外行为零变化，走既有契约）；
    ② 该操作点**本次恰好新增一条**审计记录，且行形态合规、字段完整。

    返回 `(响应 JSON, 审计行)`。轮询覆盖"响应先于落盘"的窗口；末段再等一小段确认没有第二条
    （"恰好一条"而不是"至少一条"）。
    """
    needle = audit_needle(action, operator_id, target)
    before = audit_count(needle)

    body = request_fn()
    assert body.get("code") == expect_code, f"{action} 应 code={expect_code}（否则不会留痕）: {body}"

    deadline = time.time() + timeout
    while audit_count(needle) < before + 1:
        if time.time() >= deadline:
            tail = [ln[:160] for ln in read_lines("audit.log*")[-3:]]
            raise AssertionError(
                f"audit.log 未在 {timeout}s 内出现 {action} 的审计记录"
                f"（指纹 {needle!r}，当前 {audit_count(needle)} 条，目录 {ACCESS_LOG_DIR}）；"
                f"文件尾部 3 行 = {tail}"
            )
        time.sleep(0.05)
    time.sleep(0.2)  # 稳定窗口：确认只新增这一条

    hits = [ln for ln in read_lines("audit.log*") if needle in ln]
    assert len(hits) == before + 1, (
        f"{action} 本次应只新增 1 条审计记录，实得 {len(hits) - before} 条：\n"
        + "\n".join(ln[:200] for ln in hits[-3:])
    )

    line = hits[-1]  # 追加写 → 最后一条即本次记录
    assert AUDIT_LINE_RE.match(line), (
        f"审计行不符合单行结构化形态（时间须带 3 位毫秒与带冒号时区、字段须齐）: {line[:240]}"
    )
    assert "?" not in line, f"审计行不得含 query 串（本项目以不记为脱敏）: {line[:240]}"
    return body, line


# ---------------------------------------------------------------------------
# 业务请求助手
# ---------------------------------------------------------------------------

def _run_admin(*args):
    """调用 tools/admin.py（promote/demote），失败即抛出（范式同 test_admin.py）。"""
    proc = subprocess.run(
        [sys.executable, str(PROJECT_ROOT / "tools" / "admin.py"), *args],
        cwd=str(PROJECT_ROOT), capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=60,
    )
    assert proc.returncode == 0, (
        f"admin.py {args} failed: stdout={proc.stdout!r} stderr={proc.stderr!r}"
    )
    return proc.stdout


def _register(base_url, prefix, phone_prefix):
    """注册一次性用户（username 命中 cleanup_data 白名单前缀 → 可被级联清理）。

    注册响应的 `token` 可能为 null（池 U-16 兜底路径）→ 回退登录一次拿 token。
    """
    unique = uuid.uuid4().hex[:8]
    suffix = str(int(unique, 16))[-8:].zfill(8)
    payload = {"username": f"{prefix}{unique}", "phone": f"{phone_prefix}{suffix}", "password": "abc123"}
    body = requests.post(f"{base_url}/user/register", json=payload, timeout=10).json()
    assert body.get("code") == 200, f"注册失败: {body}"
    data = body.get("data", {})
    user_id = data.get("id")
    assert user_id is not None, f"注册响应缺少 id: {body}"
    token = data.get("token")
    if not token:
        login = requests.post(f"{base_url}/user/login",
                              json={"account": payload["phone"], "password": payload["password"]},
                              timeout=10).json()
        assert login.get("code") == 200, f"注册后回退登录失败: {login}"
        token = login["data"]["token"]
    return {"id": user_id, "token": token, "phone": payload["phone"], "password": payload["password"]}


def _fresh_password():
    """本文件用的新密码：**必须**匹配项目的 `^[a-zA-Z0-9]{1,16}$`（实测口径）且本 run 唯一。"""
    return "Np" + uuid.uuid4().hex[:8]


# ---------------------------------------------------------------------------
# fixtures（自建自清；不改 conftest 既有 fixture）
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def admin_user(base_url):
    """注册临时用户 → 提升为管理员（promote）→ 用例结束降级（demote）。

    与 test_admin.py / test_hide_content.py 同范式；用户名前缀 `admin_` 命中清理白名单。
    """
    user = _register(base_url, "admin_audit_", "135")
    _run_admin("--promote", str(user["id"]))
    yield user
    _run_admin("--demote", str(user["id"]))


@pytest.fixture(scope="module")
def audit_user(base_url):
    """本文件专用普通用户：用户侧审计两点的操作者（其 id / 手机号 / 密码均为本 run 唯一 → 指纹）。"""
    return _register(base_url, "testA_audit_", "134")


@pytest.fixture(scope="module")
def audit_content(base_url, token_a, test_files, admin_user):
    """本 run 专用内容（uuid 标题）——承载 hide / unhide、评论删除、媒体恢复三个 admin 操作点。

    自建自清：用例结束先兜底恢复（若仍下架）再由作者删除，绝不触碰共享 fixture。
    """
    title = f"pytest_audit_{uuid.uuid4().hex[:8]}"
    with open(test_files["video"], "rb") as vf, open(test_files["cover"], "rb") as cf:
        resp = requests.post(
            f"{BASE_URL}/api/upload/video",
            headers={"token": token_a},
            data={"title": title, "description": "audit log test", "categoryId": "1"},
            files={"video": ("test_video.mp4", vf, "video/mp4"),
                   "cover": ("test_cover.png", cf, "image/png")},
            timeout=30,
        )
    body = resp.json()
    assert body.get("code") == 200, f"审计用内容新建失败: {body}"
    content_id = body["data"]["contentId"]

    yield content_id

    requests.post(f"{BASE_URL}/api/admin/content/unhide", params={"contentId": content_id},
                  headers={"token": admin_user["token"]}, timeout=10)
    requests.post(f"{BASE_URL}/content/delete", params={"contentId": content_id},
                  headers={"token": token_a}, timeout=10)


@pytest.fixture(scope="module")
def missing_media_of_audit_content(base_url, admin_user, audit_content):
    """扫描 → 定位本 run 新建内容的媒体项 → 删磁盘文件使其 MISSING（restore 的正向前置）。

    `restoreMedia` 只要求 media 行存在 + URL 合法 + 扩展名匹配（**不要求孤儿**，孤儿限制只是
    test_admin.py 为不污染真实数据自设的策略）→ 对本 run 自建内容可确定性命中。
    """
    scan = requests.post(f"{BASE_URL}/api/admin/media/scan",
                         headers={"token": admin_user["token"]}, timeout=60).json()
    assert scan.get("code") == 200, f"媒体扫描失败: {scan}"
    items = [it for it in (scan.get("data") or {}).get("items") or []
             if it.get("contentId") == audit_content]
    assert items, f"扫描结果中找不到本 run 新建内容 {audit_content} 的媒体项"
    item = items[0]
    assert item.get("expectedPath") and os.path.isfile(item["expectedPath"]), f"媒体文件应存在: {item}"
    os.remove(item["expectedPath"])
    return item


# ---------------------------------------------------------------------------
# ① 管理端 4 个写操作：成功路径留痕
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestAdminAudit:

    def test_hide_and_unhide_content_are_audited(self, base_url, admin_user, audit_content):
        """`/api/admin/content/hide`、`/unhide` 各恰好一条审计记录（操作者 = request attribute）。"""
        admin_id = admin_user["id"]
        headers = {"token": admin_user["token"]}

        _, line = audited(
            lambda: requests.post(f"{base_url}/api/admin/content/hide",
                                  params={"contentId": audit_content},
                                  headers=headers, timeout=10).json(),
            "admin.content.hide", admin_id, f"contentId:{audit_content}",
        )
        assert f" operatorId={admin_id} " in line, f"操作者应为发起请求的管理员: {line[:200]}"

        audited(
            lambda: requests.post(f"{base_url}/api/admin/content/unhide",
                                  params={"contentId": audit_content},
                                  headers=headers, timeout=10).json(),
            "admin.content.unhide", admin_id, f"contentId:{audit_content}",
        )

    def test_comment_delete_is_audited(self, base_url, admin_user, audit_content, token_a):
        """`/api/admin/comment/delete` 恰好一条审计记录（对象 = 被删评论 id）。"""
        add = requests.post(f"{base_url}/comment/add",
                            headers={"token": token_a, "Content-Type": "application/json"},
                            json={"contentId": audit_content,
                                  "message": f"audit_{uuid.uuid4().hex[:8]}"},
                            timeout=10).json()
        assert add.get("code") == 200, f"发表评论失败: {add}"

        shown = requests.get(f"{base_url}/comment/show",
                             params={"contentId": audit_content},
                             headers={"token": token_a}, timeout=10).json()
        assert shown.get("code") == 200, f"评论列表查询失败: {shown}"
        comments = shown.get("data") or []
        assert comments, f"新评论应出现在列表里: {shown}"
        comment_id = max(c["commentId"] for c in comments)

        audited(
            lambda: requests.post(f"{base_url}/api/admin/comment/delete",
                                  params={"commentId": comment_id},
                                  headers={"token": admin_user["token"]}, timeout=10).json(),
            "admin.comment.delete", admin_user["id"], f"commentId:{comment_id}",
        )

    def test_media_restore_is_audited(self, base_url, admin_user, missing_media_of_audit_content):
        """`/api/admin/media/restore` 恰好一条审计记录（对象 = mediaId）。"""
        item = missing_media_of_audit_content
        media_id = item["mediaId"]
        ext = item["url"].rsplit(".", 1)[-1] if "." in item["url"] else "bin"

        body, _ = audited(
            lambda: requests.post(
                f"{base_url}/api/admin/media/restore",
                params={"mediaId": media_id},
                headers={"token": admin_user["token"]},
                files={"file": (f"restore.{ext}", b"x" * 1024, "application/octet-stream")},
                timeout=60,
            ).json(),
            "admin.media.restore", admin_user["id"], f"mediaId:{media_id}",
        )
        assert body.get("data", {}).get("fileExists") is True, f"恢复后文件应存在: {body}"
        assert os.path.isfile(item["expectedPath"]), "恢复应把文件写回原路径"


# ---------------------------------------------------------------------------
# ① 用户侧敏感变更（可 HTTP 触达的 2 点）：成功路径留痕
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestUserAudit:

    def test_change_user_name_is_audited(self, base_url, audit_user):
        """`/user/changeUserName` 恰好一条审计记录（操作者 = 方法入参 userId）。"""
        new_name = f"testA_audit_un_{uuid.uuid4().hex[:8]}"
        audited(
            lambda: requests.post(f"{base_url}/user/changeUserName",
                                  headers={"token": audit_user["token"],
                                           "Content-Type": "application/json"},
                                  json={"userName": new_name}, timeout=10).json(),
            "user.changeUserName", audit_user["id"], f"userId:{audit_user['id']}",
        )

    def test_change_password_is_audited_and_has_no_sensitive_values(self, base_url, audit_user):
        """`/user/changePassword` 恰好一条审计记录；且**新密码 / 手机号明文不落盘**（验收④）。"""
        new_password = _fresh_password()
        audited(
            lambda: requests.post(f"{base_url}/user/changePassword",
                                  headers={"token": audit_user["token"],
                                           "Content-Type": "application/json"},
                                  json={"phone": audit_user["phone"],
                                        "oldPassword": audit_user["password"],
                                        "newPassword": new_password}, timeout=10).json(),
            "user.changePassword", audit_user["id"], f"userId:{audit_user['id']}",
        )

        text = "\n".join(read_lines("audit.log*"))
        for name, secret in (("新密码", new_password), ("手机号", audit_user["phone"])):
            assert secret not in text, f"{name}明文不得落进 audit.log（请求体不记）"


# ---------------------------------------------------------------------------
# ② 文件级形态与分流：audit.log 与 system.log / access.log 互不污染
# ---------------------------------------------------------------------------

@pytest.mark.consistency
def test_audit_log_is_wellformed_and_isolated(base_url, audit_user):
    """全文件不变式 + 分流证明。

    自建一条记录（改用户名）保证本用例可独立运行；随后断言：
    ① audit.log 里**每一条**结构化行都是合法审计行（含 logger=audit）；
    ② 审计行不出现在 system.log / access.log（`useParentHandlers=false` 的文件级证明）；
    ③ audit.log 里不出现访问行或其它应用日志（只收 audit logger）。
    """
    audited(
        lambda: requests.post(f"{base_url}/user/changeUserName",
                              headers={"token": audit_user["token"],
                                       "Content-Type": "application/json"},
                              json={"userName": f"testA_audit_iso_{uuid.uuid4().hex[:8]}"},
                              timeout=10).json(),
        "user.changeUserName", audit_user["id"], f"userId:{audit_user['id']}",
    )

    audit_lines = [ln for ln in read_lines("audit.log*") if ln.startswith("ts=")]
    assert audit_lines, f"audit.log 应已有结构化记录（目录 {ACCESS_LOG_DIR}）"
    bad = [ln for ln in audit_lines if not AUDIT_LINE_RE.match(ln)]
    assert not bad, (
        f"audit.log 存在不符合审计行形态的记录（{len(bad)}/{len(audit_lines)} 条，前 2 条）: "
        f"{[ln[:160] for ln in bad[:2]]}"
    )

    for prefix, label in (("system.log*", "system.log"), ("access.log*", "access.log")):
        # 先证明"读到了正确且非空的文件"：否则文件缺失时 leaked 恒为 []，本断言会**静默空过**
        # （这不是学术担忧——`log.file` 注入失效时两个文件都不会生成，而其它断言仍可能全绿）
        assert log_path(prefix), f"{label} 未生成（目录 {ACCESS_LOG_DIR}），本断言无从证伪"
        assert read_lines(prefix), f"{label} 无任何行，本断言无从证伪"
        leaked = [ln for ln in read_lines(prefix) if " logger=audit " in ln]
        assert not leaked, (
            f"审计行泄进了 {label}（useParentHandlers 未生效？）共 {len(leaked)} 条，"
            f"前 2 条: {[ln[:160] for ln in leaked[:2]]}"
        )

    foreign = [ln for ln in audit_lines if " logger=audit " not in ln]
    assert not foreign, (
        f"audit.log 混入了非审计记录（审计端只收 audit logger）共 {len(foreign)} 条，"
        f"前 2 条: {[ln[:160] for ln in foreign[:2]]}"
    )
