# -*- coding: utf-8 -*-
"""
test_feed.py - 关注动态流端到端测试（第五期 T3，refactor(cache-03)）

背景：T3 把 FeedService.getFeed 的缓存批量读（ContentCache.getContentsBatch +
LikeService.batchIsContentLiked，含 miss 装载）从 DB 事务回调内**上提到事务外**。
该端点此前只有 401 边界覆盖（test_boundary.py），成功路径零 pytest 覆盖——
本文件即 T3"相关端点端到端行为零变化"的证据。

覆盖：
  1. 未关注任何人 -> /feed 200 + list 空 + total=0（早退分支，不触碰缓存）
  2. b 关注 a 后 -> b 的 /feed 含 a 的内容（含 T2 上传的图文），条目字段完整
     （id/authorId/authorName/isLiked 布尔）；取关后被关注者内容不再出现（自建自清）

数据自建自清：临时用户用 testA_ 前缀（命中 cleanup_data.py 白名单），
关注关系在 finally 中取关复原，断言不假设"b 完全无关注"（不依赖其它用例状态/执行顺序）。
"""

import uuid

import pytest
import requests

from conftest import USER_A, register_user


def _register_fresh_user():
    """注册一个全新的 testA_ 前缀用户（无任何关注关系），返回 id/token/username。"""
    unique = uuid.uuid4().hex[:8]
    suffix = str(int(unique, 16))[-8:].zfill(8)
    username = f"testA_feed_{unique}"
    body = register_user(username, f"137{suffix}", "abc123")
    assert body.get("code") == 200, f"注册临时用户失败: {body}"
    data = body.get("data") or {}
    return {"id": data.get("id"), "token": data.get("token"), "username": username}


def _get_feed(base_url, token, page=1, page_size=10):
    """GET /feed，返回响应 JSON。"""
    resp = requests.get(
        f"{base_url}/feed",
        params={"page": page, "pageSize": page_size},
        headers={"token": token},
        timeout=10,
    )
    return resp.json()


def _follow(base_url, token, followed_user_id, action):
    """执行一次关注/取关：action='add'/'remove'。返回响应 JSON。"""
    resp = requests.post(
        f"{base_url}/follow/{action}",
        headers={"token": token, "Content-Type": "application/x-www-form-urlencoded"},
        data={"followedUserId": str(followed_user_id)},
        timeout=10,
    )
    return resp.json()


@pytest.mark.boundary
class TestFeed:

    def test_feed_empty_when_not_following_anyone(self, base_url):
        """全新用户未关注任何人 -> /feed 200 + list == [] + total == 0（不触碰内容缓存）。"""
        fresh = _register_fresh_user()

        body = _get_feed(base_url, fresh["token"])

        assert body.get("code") == 200, f"/feed failed: {body}"
        data = body.get("data") or {}
        assert data.get("list") == [], f"未关注任何人应为空列表: {data}"
        assert data.get("total") == 0, f"未关注任何人 total 应为 0: {data}"
        assert data.get("page") == 1 and data.get("pageSize") == 10

    def test_feed_returns_followed_author_content(self, base_url, token_b, user_a_id,
                                                  sample_post_content_id):
        """b 关注 a 后 /feed 含 a 的内容（字段完整）；取关后被关注者内容不再出现（自建自清）。"""
        add_body = _follow(base_url, token_b, user_a_id, "add")
        try:
            assert add_body.get("code") == 200, f"follow/add failed: {add_body}"

            body = _get_feed(base_url, token_b, page=1, page_size=50)
            assert body.get("code") == 200, f"/feed failed: {body}"
            data = body.get("data") or {}
            items = data.get("list") or []
            assert items, f"关注后 /feed 应有内容: {data}"
            assert data.get("total", 0) >= 1, f"total 应 >= 1: {data}"

            mine = [it for it in items if it.get("authorId") == user_a_id]
            assert mine, f"/feed 应含被关注者 userA 的内容: {items}"
            ids = [it.get("id") for it in mine]
            assert sample_post_content_id in ids, \
                f"/feed 应含 userA 的图文内容 {sample_post_content_id}: {ids}"
            for item in mine:
                assert item.get("authorName") == USER_A["username"], \
                    f"authorName 应为被关注者用户名: {item}"
                assert isinstance(item.get("isLiked"), bool), \
                    f"isLiked 应为布尔（点赞状态填充）: {item}"
        finally:
            remove_body = _follow(base_url, token_b, user_a_id, "remove")
            assert remove_body.get("code") == 200, f"follow/remove failed: {remove_body}"

        # 取关复原后：被关注者的内容不再出现（不假设 b 完全无关注，避免依赖其它用例状态）
        after = _get_feed(base_url, token_b, page=1, page_size=50)
        assert after.get("code") == 200, f"/feed failed: {after}"
        after_items = (after.get("data") or {}).get("list") or []
        leftovers = [it.get("id") for it in after_items if it.get("authorId") == user_a_id]
        assert leftovers == [], f"取关后 /feed 不应再含 userA 内容: {leftovers}"
