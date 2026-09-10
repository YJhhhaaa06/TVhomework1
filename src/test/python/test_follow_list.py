# -*- coding: utf-8 -*-
"""
test_follow_list.py - 关注/粉丝列表端到端测试（T1，方向 A 补测试缺口）

覆盖两个此前零 pytest 覆盖的读接口：
  GET /follow/following?userId=<id>   关注列表（userId 的关注对象）
  GET /follow/followers?userId=<id>   粉丝列表（userId 的粉丝）

每接口三态：正常返回列表 / 空列表（空数组）/ 未登录 401；
另含健壮性用例：缺 userId 参数 -> 400（parseUserId 抛 ParamException）。
数据流复用 conftest 的 user_a/user_b（b 关注 a），自建自清（try/finally 恢复
"未关注"初态，保证 E-08「首次 follow 必 200」不被动摇），不改任何被测业务代码。

守卫说明：AuthFilter 对 /follow 前缀匹配需登录（与 BUSINESS_FLOW 7.2 旧表不一致，
已按代码为准修正文档），故两读接口无 token 必 401。
"""

import pytest
import requests

from conftest import USER_A, USER_B


def _follow_add_remove(base_url, token, followed_user_id, action):
    """执行一次关注/取关：action='add'/'remove'。返回响应 JSON。"""
    resp = requests.post(
        f"{base_url}/follow/{action}",
        headers={"token": token, "Content-Type": "application/x-www-form-urlencoded"},
        data={"followedUserId": str(followed_user_id)},
        timeout=10,
    )
    return resp.json()


@pytest.mark.boundary
class TestFollowFollowing:

    def test_following_requires_login(self, base_url, user_a):
        """无 token 访问 /follow/following -> code=401。"""
        resp = requests.get(
            f"{base_url}/follow/following",
            params={"userId": user_a["id"]},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 401, \
            f"Access /follow/following without token should return 401, got {body.get('code')}: {body}"

    def test_following_empty_list(self, base_url, token_a, user_a_id):
        """user_a 未关注任何人 -> data == []（空数组，FollowService 返回 emptyList）。"""
        resp = requests.get(
            f"{base_url}/follow/following",
            params={"userId": user_a_id},
            headers={"token": token_a},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"follow/following failed: {body}"
        assert body.get("data") == [], f"Expected empty list, got: {body.get('data')}"

    def test_following_returns_list(self, base_url, token_b, user_a_id, user_b_id):
        """b 关注 a 后查 b 的关注列表：含 a 条目，username 匹配、isFollowed=true（b 已关注 a）、isSelf=false。"""
        add_body = _follow_add_remove(base_url, token_b, user_a_id, "add")
        try:
            assert add_body.get("code") == 200, f"follow/add failed: {add_body}"

            resp = requests.get(
                f"{base_url}/follow/following",
                params={"userId": user_b_id},
                headers={"token": token_b},
                timeout=10,
            )
            body = resp.json()
            assert body.get("code") == 200, f"follow/following failed: {body}"
            data = body.get("data") or []
            matches = [u for u in data if u.get("userId") == user_a_id]
            assert len(matches) == 1, \
                f"Following list should contain userA exactly once: {data}"
            item = matches[0]
            assert item.get("username") == USER_A["username"], f"Username mismatch: {item}"
            assert item.get("isFollowed") is True, f"isFollowed should be True: {item}"
            assert item.get("isSelf") is False, f"isSelf should be False: {item}"
        finally:
            _follow_add_remove(base_url, token_b, user_a_id, "remove")

    def test_following_missing_user_id(self, base_url, token_a):
        """缺 userId 参数（带 token）-> code=400。"""
        resp = requests.get(
            f"{base_url}/follow/following",
            headers={"token": token_a},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 400, \
            f"Missing userId should return 400, got {body.get('code')}: {body}"


@pytest.mark.boundary
class TestFollowFollowers:

    def test_followers_requires_login(self, base_url, user_a):
        """无 token 访问 /follow/followers -> code=401。"""
        resp = requests.get(
            f"{base_url}/follow/followers",
            params={"userId": user_a["id"]},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 401, \
            f"Access /follow/followers without token should return 401, got {body.get('code')}: {body}"

    def test_followers_empty_list(self, base_url, token_a, user_a_id):
        """user_a 无粉丝 -> data == []（空数组）。"""
        resp = requests.get(
            f"{base_url}/follow/followers",
            params={"userId": user_a_id},
            headers={"token": token_a},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"follow/followers failed: {body}"
        assert body.get("data") == [], f"Expected empty list, got: {body.get('data')}"

    def test_followers_returns_list(self, base_url, token_b, user_a_id, user_b_id):
        """b 关注 a 后查 a 的粉丝列表：含 b 条目，username 匹配、isFollowed=false（b 不关注自己）、isSelf=true（当前用户即条目本人）。"""
        add_body = _follow_add_remove(base_url, token_b, user_a_id, "add")
        try:
            assert add_body.get("code") == 200, f"follow/add failed: {add_body}"

            resp = requests.get(
                f"{base_url}/follow/followers",
                params={"userId": user_a_id},
                headers={"token": token_b},
                timeout=10,
            )
            body = resp.json()
            assert body.get("code") == 200, f"follow/followers failed: {body}"
            data = body.get("data") or []
            matches = [u for u in data if u.get("userId") == user_b_id]
            assert len(matches) == 1, \
                f"Followers list should contain userB exactly once: {data}"
            item = matches[0]
            assert item.get("username") == USER_B["username"], f"Username mismatch: {item}"
            assert item.get("isFollowed") is False, f"isFollowed should be False: {item}"
            assert item.get("isSelf") is True, f"isSelf should be True: {item}"
        finally:
            _follow_add_remove(base_url, token_b, user_a_id, "remove")