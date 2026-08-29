# -*- coding: utf-8 -*-
"""
test_comment_reply_floor.py - 楼中楼「回复楼内回复 + @ 引用」验收测试。

背景：
    详情页楼中楼（主楼 + 平铺楼内回复）原来每条回复没有「回复」按钮，无法回复楼内回复。
    本次修复：楼内回复也可被回复，parentId 传被回复的楼内回复 id，
    后端上溯挂主楼并记录 reply_to_user_id（被回复评论作者），响应带 replyToUserId/replyToUsername。

覆盖：
1. 回复某楼内回复 -> 新评论 parentId 归一化为主楼 id，replyToUserId = 被回复人 id，replyToUsername 非空
2. 直接回复主楼 -> replyToUserId 为空（无 @ 前缀）
3. 被 @ 的楼内回复被软删后，指向它的「回复 @xxx」展示仍正常（username 冗余于后端 JOIN）

说明：
- 每个用例自建评论（消息带 uuid 保证唯一），结束后删除主楼（整楼软删）净零残留。
- 楼中楼两级结构由 stage-1 迁移保证；本测试只涉及现有两级数据。
"""

import uuid

import pytest
import requests

_unique = str(uuid.uuid4().hex[:8])


# ---------------------------------------------------------------------------
# 评论操作辅助（与 test_comment_delete.py 同构）
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


def delete_comment(base_url, comment_id, token):
    resp = requests.post(
        f"{base_url}/comment/delete",
        params={"commentId": comment_id},
        headers={"token": token},
        timeout=10,
    )
    return resp.json()


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestReplyToFloorReply:

    def test_reply_to_floor_reply_normalizes_and_mentions(
        self, base_url, token_a, token_b, user_b_id, sample_content_id
    ):
        cid = sample_content_id
        main_msg = f"rf_main_{_unique}"
        reply_msg = f"rf_reply_{_unique}"
        new_msg = f"rf_reply_to_reply_{_unique}"

        # userA 主楼，userB 回复主楼（楼内回复），userA 回复该楼内回复
        add_comment(base_url, token_a, cid, main_msg)
        comments = list_comments(base_url, cid, token_a)
        main_id = find_comment(comments, main_msg)["commentId"]

        add_comment(base_url, token_b, cid, reply_msg, parent_id=main_id)
        comments = list_comments(base_url, cid, token_a)
        reply = find_comment(comments, reply_msg)
        assert reply is not None and reply.get("parentId") == main_id

        add_comment(base_url, token_a, cid, new_msg, parent_id=reply["commentId"])

        comments = list_comments(base_url, cid, token_a)
        new = find_comment(comments, new_msg)
        assert new is not None
        # 上溯挂主楼 + @ 目标为被回复的楼内回复作者（userB）
        assert new.get("parentId") == main_id, "回复楼内回复应归一化挂到主楼"
        assert new.get("replyToUserId") == user_b_id, "replyToUserId 应为被回复作者 id"
        assert new.get("replyToUsername"), "replyToUsername 应非空用于展示"

        # 清理：删主楼整楼软删（含 userB 的楼内回复），净零残留
        result = delete_comment(base_url, main_id, token_a)
        assert result.get("code") == 200, f"清理失败: {result}"

    def test_reply_to_main_floor_has_no_mention(
        self, base_url, token_a, token_b, sample_content_id
    ):
        cid = sample_content_id
        main_msg = f"rfm_{_unique}"
        direct_msg = f"rf_direct_{_unique}"

        add_comment(base_url, token_a, cid, main_msg)
        comments = list_comments(base_url, cid, token_a)
        main_id = find_comment(comments, main_msg)["commentId"]

        # userB 直接回复主楼 => 无 @ 引用
        add_comment(base_url, token_b, cid, direct_msg, parent_id=main_id)
        comments = list_comments(base_url, cid, token_a)
        reply = find_comment(comments, direct_msg)
        assert reply is not None, "直接回复主楼应存在于楼中楼"
        assert reply.get("replyToUserId") is None, "直接回复主楼不应记录 @ 引用"

        # 清理
        result = delete_comment(base_url, main_id, token_a)
        assert result.get("code") == 200, f"清理失败: {result}"

    def test_mention_survives_replied_reply_deletion(
        self, base_url, token_a, token_b, sample_content_id
    ):
        cid = sample_content_id
        main_msg = f"rfm2_{_unique}"
        replied_msg = f"rf_replied_{_unique}"
        new_msg = f"rf_after_delete_{_unique}"

        add_comment(base_url, token_a, cid, main_msg)
        comments = list_comments(base_url, cid, token_a)
        main_id = find_comment(comments, main_msg)["commentId"]

        add_comment(base_url, token_b, cid, replied_msg, parent_id=main_id)
        comments = list_comments(base_url, cid, token_a)
        replied = find_comment(comments, replied_msg)

        add_comment(base_url, token_a, cid, new_msg, parent_id=replied["commentId"])

        # userB 删除自己的被 @ 回复
        result = delete_comment(base_url, replied["commentId"], token_b)
        assert result.get("code") == 200, f"删除被 @ 回复失败: {result}"

        # 新回复仍在楼中楼中，且「回复 @xxx」用户名仍可展示
        comments = list_comments(base_url, cid, token_a)
        new = find_comment(comments, new_msg)
        assert new is not None, "被 @ 回复删除后，指向它的新回复应保留"
        assert new.get("replyToUsername"), "username 冗余存储，删除后仍应可展示"

        # 清理：删主楼整楼软删
        result = delete_comment(base_url, main_id, token_a)
        assert result.get("code") == 200, f"清理失败: {result}"