# -*- coding: utf-8 -*-
"""
test_comment_delete.py - 阶段一 C1 评论软删除（楼中楼规则）验收测试。

覆盖：
1. 用户删自己主楼 -> 主楼与楼内回复全部消失，其它评论不受影响；commentCount 减少 1+回复数
2. 用户删自己楼内回复 -> 主楼仍在，仅该回复消失；commentCount-1
3. 用户删他人评论 -> 403
4. 删不存在的评论 -> 404
5. 未登录删评论 -> 401
6. 管理员删任意评论 -> 200 且 /comment/show 消失（临时提升 testB 为管理员，结束恢复）

说明：
- 每个用例自建评论（消息带 uuid 保证唯一），删除后净零残留。
- 管理员用例依赖本机 mysql 客户端（模式同 tools/admin.py），不可用时自动 skip。
"""

import shutil
import subprocess
import uuid
from pathlib import Path

import pytest
import requests

MYSQL_HOST = "127.0.0.1"
MYSQL_PORT = "3306"
MYSQL_USER = "root"
MYSQL_PASSWORD = "MySQL"
DB_NAME = "tvdatabase"

_COMMON_MYSQL_PATHS = [
    Path(r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"),
    Path(r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"),
]


def _mysql_path():
    found = shutil.which("mysql")
    if found:
        return Path(found)
    for candidate in _COMMON_MYSQL_PATHS:
        if candidate.exists():
            return candidate
    return None


def _run_sql(mysql_path, sql):
    cmd = [
        str(mysql_path),
        f"--user={MYSQL_USER}",
        f"--password={MYSQL_PASSWORD}",
        f"--host={MYSQL_HOST}",
        f"--port={MYSQL_PORT}",
        f"--database={DB_NAME}",
        "--batch",
        "--skip-column-names",
        "--execute",
        sql,
    ]
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=120,
    )
    if proc.returncode != 0:
        raise RuntimeError("mysql 执行失败: " + (proc.stderr.strip() or proc.stdout.strip()))


@pytest.fixture(scope="module")
def admin_token_b(base_url, user_b, token_b):
    """把 testB 临时提升为管理员供删除用例使用，模块结束恢复为普通用户。"""
    mysql = _mysql_path()
    if mysql is None:
        pytest.skip("mysql 客户端不可用，跳过管理员删除用例")
    user_id = user_b["id"]
    try:
        _run_sql(mysql, f"UPDATE users SET role = 1 WHERE id = {user_id}")
    except RuntimeError:
        pytest.skip("无法提升管理员，跳过管理员删除用例")
    yield token_b
    try:
        _run_sql(mysql, f"UPDATE users SET role = 0 WHERE id = {user_id}")
    except RuntimeError:
        pass


# ---------------------------------------------------------------------------
# 评论操作辅助
# ---------------------------------------------------------------------------

def add_comment(base_url, token, content_id, message, parent_id=None):
    body = {"contentId": content_id, "message": message}
    if parent_id is not None:
        body["parentId"] = parent_id
    resp = requests.post(
        f"{base_url}/comment/add",
        headers={"token": token, "Content-Type": "application/json"},
        json=body,
        timeout=10,
    )
    result = resp.json()
    assert result.get("code") == 200, f"添加评论失败: {result}"
    return result


def list_comments(base_url, content_id, token=None):
    headers = {"token": token} if token else {}
    resp = requests.get(
        f"{base_url}/comment/show",
        params={"contentId": content_id},
        headers=headers,
        timeout=10,
    )
    result = resp.json()
    assert result.get("code") == 200, f"查询评论失败: {result}"
    return result.get("data") or []


def find_comment(comments, message):
    """在主楼 + 楼内回复中按内容查找评论，返回评论 dict 或 None。"""
    for c in comments:
        if c.get("content") == message:
            return c
        for child in c.get("children") or []:
            if child.get("content") == message:
                return child
    return None


def detail_comment_count(base_url, content_id, token):
    resp = requests.get(
        f"{base_url}/search/IdSearch",
        params={"contentId": content_id},
        headers={"token": token},
        timeout=10,
    )
    result = resp.json()
    assert result.get("code") == 200, f"查询详情失败: {result}"
    return result["data"].get("commentCount", 0)


def delete_comment(base_url, comment_id, token=None):
    headers = {"token": token} if token else {}
    resp = requests.post(
        f"{base_url}/comment/delete",
        params={"commentId": comment_id},
        headers=headers,
        timeout=10,
    )
    return resp.json()


_unique = str(uuid.uuid4().hex[:8])


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestUserDeleteMainFloor:

    def test_delete_main_floor_removes_whole_floor_and_keeps_others(
        self, base_url, token_a, token_b, sample_content_id
    ):
        cid = sample_content_id
        main_msg = f"del_main_{_unique}"
        reply1_msg = f"del_reply1_{_unique}"
        reply2_msg = f"del_reply2_{_unique}"

        before = detail_comment_count(base_url, cid, token_a)
        add_comment(base_url, token_a, cid, main_msg)
        comments = list_comments(base_url, cid, token_a)
        main_id = find_comment(comments, main_msg)["commentId"]
        add_comment(base_url, token_b, cid, reply1_msg, parent_id=main_id)
        add_comment(base_url, token_b, cid, reply2_msg, parent_id=main_id)

        # 删除主楼
        result = delete_comment(base_url, main_id, token_a)
        assert result.get("code") == 200, f"删除主楼失败: {result}"

        comments = list_comments(base_url, cid, token_a)
        assert find_comment(comments, main_msg) is None, "主楼应消失"
        assert find_comment(comments, reply1_msg) is None, "楼内回复1应随主楼消失"
        assert find_comment(comments, reply2_msg) is None, "楼内回复2应随主楼消失"
        # 净零：加了 3 条（主楼+2回复），删除整楼 -3
        assert detail_comment_count(base_url, cid, token_a) == before, "commentCount 应恢复原值"


@pytest.mark.consistency
class TestUserDeleteReply:

    def test_delete_reply_only_removes_itself(self, base_url, token_a, token_b, sample_content_id):
        cid = sample_content_id
        main_msg = f"del_main_{_unique}"
        keep_msg = f"del_keep_{_unique}"
        hit_msg = f"del_hit_{_unique}"

        before = detail_comment_count(base_url, cid, token_a)
        add_comment(base_url, token_a, cid, main_msg)
        comments = list_comments(base_url, cid, token_a)
        main_id = find_comment(comments, main_msg)["commentId"]
        add_comment(base_url, token_b, cid, keep_msg, parent_id=main_id)
        add_comment(base_url, token_b, cid, hit_msg, parent_id=main_id)

        # 找到要删的回复
        comments = list_comments(base_url, cid, token_a)
        hit = find_comment(comments, hit_msg)
        assert hit is not None and hit.get("parentId") == main_id, "回复应挂在主楼下"
        result = delete_comment(base_url, hit["commentId"], token_b)
        assert result.get("code") == 200, f"删除回复失败: {result}"

        comments = list_comments(base_url, cid, token_a)
        assert find_comment(comments, main_msg) is not None, "主楼应保留"
        assert find_comment(comments, keep_msg) is not None, "其它回复应保留"
        assert find_comment(comments, hit_msg) is None, "被删回复应消失"
        # 加了 3 条，仅删 1 条回复 => 净 +2
        assert detail_comment_count(base_url, cid, token_a) == before + 2, "commentCount 应 -1"

        # 清理：主楼归 userA、保留回复归 userB，各自删除保持净零残留
        keep = find_comment(comments, keep_msg)
        assert delete_comment(base_url, keep["commentId"], token_b).get("code") == 200
        assert delete_comment(base_url, main_id, token_a).get("code") == 200
        assert detail_comment_count(base_url, cid, token_a) == before, "清理后 commentCount 应复原"


@pytest.mark.boundary
class TestDeleteBoundary:

    def test_user_cannot_delete_others_comment(self, base_url, token_a, token_b, sample_content_id):
        cid = sample_content_id
        msg = f"del_other_{_unique}"
        add_comment(base_url, token_b, cid, msg)
        comments = list_comments(base_url, cid, token_a)
        target = find_comment(comments, msg)
        assert target is not None

        result = delete_comment(base_url, target["commentId"], token_a)
        assert result.get("code") == 403, f"删他人评论应 403，实际: {result}"

        # 评论仍在
        comments = list_comments(base_url, cid, token_a)
        assert find_comment(comments, msg) is not None, "他人评论不应被删除"

        # 清理：这是 userB 自己的评论，可由 userB 删除（保证回归可重复、净零残留）
        clean = delete_comment(base_url, target["commentId"], token_b)
        assert clean.get("code") == 200, f"清理评论失败: {clean}"

    def test_delete_missing_comment_returns_404(self, base_url, token_a):
        result = delete_comment(base_url, 999999999, token_a)
        assert result.get("code") == 404, f"删不存在评论应 404，实际: {result}"

    def test_delete_requires_login(self, base_url):
        result = delete_comment(base_url, 1)
        assert result.get("code") == 401, f"未登录删评论应 401，实际: {result}"


@pytest.mark.admin
class TestAdminDelete:

    def test_admin_can_delete_any_comment(self, base_url, token_a, admin_token_b, sample_content_id):
        cid = sample_content_id
        msg = f"del_admin_{_unique}"
        add_comment(base_url, token_a, cid, msg)
        comments = list_comments(base_url, cid, token_a)
        target = find_comment(comments, msg)
        assert target is not None

        result = requests.post(
            f"{base_url}/api/admin/comment/delete",
            params={"commentId": target["commentId"]},
            headers={"token": admin_token_b},
            timeout=10,
        ).json()
        assert result.get("code") == 200, f"管理员删除应成功，实际: {result}"

        comments = list_comments(base_url, cid, token_a)
        assert find_comment(comments, msg) is None, "管理员删除后评论应消失"
