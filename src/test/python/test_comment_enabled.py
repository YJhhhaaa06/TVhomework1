# -*- coding: utf-8 -*-
"""
test_comment_enabled.py - 阶段二 C2 作者开关评论区 验收测试。

覆盖：
1. 作者关闭 -> /comment/show 空、/comment/add 409、详情 commentEnabled=false；再开启 -> 原评论恢复、可正常发评论
2. 非作者（userB）关闭他人内容 -> 403
3. 未登录关闭 -> 401
4. 不存在的 contentId -> 404
5. 参数缺失/非法 -> 400

说明：
- 所有用例使用 userA 的会话级内容 sample_content_id。
- 每个用例结束后自动复原 comment_enabled=1（autouse fixture 兜底 + 用例自身复原），
  避免影响同会话后续测试（test_consistency / test_comment_delete 依赖「能发评论」）。
"""

import uuid

import pytest
import requests

_unique = uuid.uuid4().hex[:8]


# ---------------------------------------------------------------------------
# 辅助
# ---------------------------------------------------------------------------

def set_comment_enabled(base_url, token, content_id, enabled):
    resp = requests.post(
        f"{base_url}/content/commentEnabled",
        params={"contentId": content_id, "enabled": enabled},
        headers={"token": token},
        timeout=10,
    )
    return resp.json()


def get_detail(base_url, content_id, token=None):
    headers = {"token": token} if token else {}
    resp = requests.get(
        f"{base_url}/search/IdSearch",
        params={"contentId": content_id},
        headers=headers,
        timeout=10,
    )
    result = resp.json()
    assert result.get("code") == 200, f"详情查询失败: {result}"
    return result["data"]


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


def add_comment(base_url, token, content_id, message):
    resp = requests.post(
        f"{base_url}/comment/add",
        headers={"token": token, "Content-Type": "application/json"},
        json={"contentId": content_id, "message": message},
        timeout=10,
    )
    return resp.json()


def delete_comment(base_url, token, comment_id):
    resp = requests.post(
        f"{base_url}/comment/delete",
        params={"commentId": comment_id},
        headers={"token": token},
        timeout=10,
    )
    return resp.json()


@pytest.fixture(autouse=True)
def restore_comment_enabled(base_url, token_a, sample_content_id):
    """每个用例结束都把评论区复原为开启，防止状态污染后续测试（兜底）。"""
    yield
    set_comment_enabled(base_url, token_a, sample_content_id, 1)


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestToggleOffAndOn:

    def test_off_hides_blocks_then_on_recovers(
        self, base_url, token_a, sample_content_id
    ):
        cid = sample_content_id
        assert get_detail(base_url, cid, token_a)["commentEnabled"] is True
        before = list_comments(base_url, cid, token_a)

        # 关闭
        result = set_comment_enabled(base_url, token_a, cid, 0)
        assert result.get("code") == 200, f"关闭失败: {result}"
        assert get_detail(base_url, cid, token_a)["commentEnabled"] is False
        assert list_comments(base_url, cid, token_a) == [], "关闭后评论应整体不可见"
        added = add_comment(base_url, token_a, cid, f"disabled_{_unique}")
        assert added.get("code") == 409, f"关闭后发评论应 409，实际: {added}"

        # 开启 -> 原评论恢复
        result = set_comment_enabled(base_url, token_a, cid, 1)
        assert result.get("code") == 200, f"开启失败: {result}"
        assert get_detail(base_url, cid, token_a)["commentEnabled"] is True
        restored = list_comments(base_url, cid, token_a)
        assert restored == before, "开启后原评论应恢复"

        # 开启后可正常发评论，并自删保持净零残留
        msg = f"reopen_{_unique}"
        add_result = add_comment(base_url, token_a, cid, msg)
        assert add_result.get("code") == 200, f"开启后发评论失败: {add_result}"
        comments = list_comments(base_url, cid, token_a)
        target = next(
            (c for c in comments if c.get("content") == msg), None
        )
        assert target is not None, "新评论应可见"
        clean = delete_comment(base_url, token_a, target["commentId"])
        assert clean.get("code") == 200, f"清理评论失败: {clean}"


@pytest.mark.boundary
class TestToggleBoundary:

    def test_non_author_cannot_toggle(self, base_url, token_a, token_b, sample_content_id):
        cid = sample_content_id
        result = set_comment_enabled(base_url, token_b, cid, 0)
        assert result.get("code") == 403, f"非作者关闭应 403，实际: {result}"
        # 未被改动
        assert get_detail(base_url, cid, token_a)["commentEnabled"] is True

    def test_toggle_requires_login(self, base_url, sample_content_id):
        resp = requests.post(
            f"{base_url}/content/commentEnabled",
            params={"contentId": sample_content_id, "enabled": 0},
            timeout=10,
        )
        result = resp.json()
        assert result.get("code") == 401, f"未登录应 401，实际: {result}"

    def test_toggle_missing_content_returns_404(self, base_url, token_a):
        result = set_comment_enabled(base_url, token_a, 999999999, 0)
        assert result.get("code") == 404, f"不存在内容应 404，实际: {result}"

    def test_toggle_invalid_params_return_400(self, base_url, token_a, sample_content_id):
        # enabled 非法值
        resp = requests.post(
            f"{base_url}/content/commentEnabled",
            params={"contentId": sample_content_id, "enabled": 2},
            headers={"token": token_a},
            timeout=10,
        )
        assert resp.json().get("code") == 400, "enabled=2 应 400"

        # enabled 缺失
        resp = requests.post(
            f"{base_url}/content/commentEnabled",
            params={"contentId": sample_content_id},
            headers={"token": token_a},
            timeout=10,
        )
        assert resp.json().get("code") == 400, "缺少 enabled 应 400"

        # contentId 缺失
        resp = requests.post(
            f"{base_url}/content/commentEnabled",
            params={"enabled": 0},
            headers={"token": token_a},
            timeout=10,
        )
        assert resp.json().get("code") == 400, "缺少 contentId 应 400"