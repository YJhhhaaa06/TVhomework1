# -*- coding: utf-8 -*-
"""
test_boundary.py - Level 3 边界与异常测试 (Boundary & Exception Tests)

Verify error handling, parameter validation, auth enforcement, and idempotency.

Test IDs follow ACCEPTANCE_CRITERIA.md:
  E-01 ~ E-12
"""

import pytest
import requests


# ---------------------------------------------------------------------------
# E-01 ~ E-04: Parameter Validation
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestParameterValidation:

    def test_E01_login_missing_fields(self, base_url):
        """E-01: POST /user/login with empty body -> code=400."""
        resp = requests.post(
            f"{base_url}/user/login",
            json={},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 400, \
            f"Login with empty body should return 400, got code={body.get('code')}: {body}"

    def test_E02_register_duplicate_phone(self, base_url, user_a):
        """E-02: Register with already-used phone -> code=409."""
        resp = requests.post(
            f"{base_url}/user/register",
            json={
                "username": "duplicate_phone_user",
                "phone": user_a["phone"],
                "password": "abc123",
            },
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 409, \
            f"Duplicate phone should return 409, got {body.get('code')}: {body}"

    def test_E03_comment_missing_message(self, base_url, token_a, sample_content_id):
        """E-03: POST /comment/add without message -> code=400."""
        resp = requests.post(
            f"{base_url}/comment/add",
            headers={"token": token_a, "Content-Type": "application/json"},
            json={"contentId": sample_content_id},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 400, \
            f"Comment without message should return 400, got code={body.get('code')}: {body}"

    def test_E04_search_missing_keyword(self, base_url):
        """E-04: GET /search/keywordSearch without keyword -> error or empty."""
        resp = requests.get(
            f"{base_url}/search/keywordSearch",
            params={"page": 1, "pageSize": 10},
            timeout=10,
        )
        body = resp.json()
        # Could be 400 (parameter error) or 200 with empty result
        assert body.get("code") in (200, 400), \
            f"Search without keyword returned unexpected code: {body}"


# ---------------------------------------------------------------------------
# E-05 ~ E-06: Authentication Tests
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestAuthEnforcement:

    def test_E05_no_token_on_protected_endpoint(self, base_url):
        """E-05: Access /feed without token -> code=401."""
        resp = requests.get(
            f"{base_url}/feed",
            params={"page": 1, "pageSize": 10},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 401, \
            f"Access /feed without token should return code=401, got code={body.get('code')}: {body}"

    def test_E06_fake_token_on_protected_endpoint(self, base_url):
        """E-06: Access /feed with fake token -> code=401."""
        resp = requests.get(
            f"{base_url}/feed",
            params={"page": 1, "pageSize": 10},
            headers={"token": "fake-token-12345"},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 401, \
            f"Access /feed with fake token should return code=401, got code={body.get('code')}: {body}"


# ---------------------------------------------------------------------------
# E-07 ~ E-10: Idempotency (Duplicate Operation)
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestIdempotency:

    def test_E07_duplicate_like_returns_409(self, base_url, token_b, sample_content_id):
        """E-07: Like the same content twice -> second time returns 409."""
        # First like
        resp1 = requests.post(
            f"{base_url}/like/content/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"contentId": str(sample_content_id)},
            timeout=10,
        )
        body1 = resp1.json()
        assert body1.get("code") == 200, f"First like failed: {body1}"

        # Second like (duplicate)
        resp2 = requests.post(
            f"{base_url}/like/content/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"contentId": str(sample_content_id)},
            timeout=10,
        )
        body2 = resp2.json()
        assert body2.get("code") == 409, \
            f"Duplicate like should return 409, got {body2.get('code')}: {body2}"

        # Cleanup: unlike
        requests.post(
            f"{base_url}/like/content/remove",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"contentId": str(sample_content_id)},
            timeout=10,
        )

    def test_E08_duplicate_follow_returns_409(self, base_url, token_b, user_a_id):
        """E-08: Follow the same user twice -> second time returns 409."""
        # First follow
        resp1 = requests.post(
            f"{base_url}/follow/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"followedUserId": str(user_a_id)},
            timeout=10,
        )
        body1 = resp1.json()
        assert body1.get("code") == 200, f"First follow failed: {body1}"

        # Second follow (duplicate)
        resp2 = requests.post(
            f"{base_url}/follow/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"followedUserId": str(user_a_id)},
            timeout=10,
        )
        body2 = resp2.json()
        assert body2.get("code") == 409, \
            f"Duplicate follow should return 409, got {body2.get('code')}: {body2}"

        # Cleanup: unfollow
        requests.post(
            f"{base_url}/follow/remove",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"followedUserId": str(user_a_id)},
            timeout=10,
        )

    def test_E09_duplicate_coupon_grab_returns_409(
        self, base_url, token_a, available_coupon_id
    ):
        """E-09: Grab the same coupon twice -> second time returns 409.

        首抢（正常抢）必 200：available_coupon_id 命中 T1 固定券种子（高库存 999999、
        end 2099、begin 过去），且 token_a 每次会话新建用户、从未领取过；再抢同券撞
        coupon_order 唯一键 -> 409。缺失种子券由 fixture 直接 fail（不再静默 skip）。
        """
        # First grab
        resp1 = requests.post(
            f"{base_url}/coupon/grab",
            headers={"token": token_a, "Content-Type": "application/json"},
            json={"couponId": available_coupon_id},
            timeout=10,
        )
        body1 = resp1.json()
        assert body1.get("code") == 200, f"First grab failed: {body1}"

        # Second grab (duplicate)
        resp2 = requests.post(
            f"{base_url}/coupon/grab",
            headers={"token": token_a, "Content-Type": "application/json"},
            json={"couponId": available_coupon_id},
            timeout=10,
        )
        body2 = resp2.json()
        assert body2.get("code") == 409, \
            f"Duplicate coupon grab should return 409, got {body2.get('code')}: {body2}"

    def test_E09b_grab_expired_coupon_returns_409(
        self, base_url, token_a, expired_coupon_id
    ):
        """E-09b: Grab an expired coupon -> code=409.

        已过期券（conftest.EXPIRED_COUPON_ID=6，db.sql 历史行，end 早于当前时间）
        必然不可抢：deductStock 的 end_time >= NOW() 不满足 -> 409；
        不落 coupon_order、不耗库存。
        """
        resp = requests.post(
            f"{base_url}/coupon/grab",
            headers={"token": token_a, "Content-Type": "application/json"},
            json={"couponId": expired_coupon_id},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 409, \
            f"Grab expired coupon should return 409, got {body.get('code')}: {body}"

    def test_E10_follow_self_returns_409(self, base_url, token_a, user_a_id):
        """E-10: Follow yourself -> returns 409."""
        resp = requests.post(
            f"{base_url}/follow/add",
            headers={"token": token_a, "Content-Type": "application/x-www-form-urlencoded"},
            data={"followedUserId": str(user_a_id)},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 409, \
            f"Follow self should return 409, got {body.get('code')}: {body}"


# ---------------------------------------------------------------------------
# E-11 ~ E-12: Resource Not Found
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestResourceNotFound:

    def test_E11_like_nonexistent_content(self, base_url, token_b):
        """E-11: Like a content with contentId=99999 -> code=404."""
        resp = requests.post(
            f"{base_url}/like/content/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"contentId": "99999"},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 404, \
            f"Like nonexistent content should return 404, got {body.get('code')}: {body}"

    def test_E12_view_nonexistent_detail(self, base_url, token_a):
        """E-12: View detail of contentId=99999 -> code=404 or data=null."""
        resp = requests.get(
            f"{base_url}/search/IdSearch",
            params={"contentId": 99999},
            headers={"token": token_a},
            timeout=10,
        )
        body = resp.json()
        code = body.get("code")
        data = body.get("data")
        # Accept either 404 or 200 with null data
        assert code == 404 or data is None or data == {}, \
            f"Nonexistent content should return 404 or null data, got code={code}, data={data}"
