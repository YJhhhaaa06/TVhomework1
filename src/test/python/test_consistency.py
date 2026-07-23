# -*- coding: utf-8 -*-
"""
test_consistency.py - Level 2 数据一致性测试 (Data Consistency Tests)

Verify that operations update database/cache counts correctly.

Test IDs follow ACCEPTANCE_CRITERIA.md:
  C-01 ~ C-10
"""

import pytest
import requests


# ---------------------------------------------------------------------------
# Helper: query like count / status
# ---------------------------------------------------------------------------

def get_content_like_count(base_url, content_id, token):
    """GET /like/content/count?contentId=X -> int."""
    resp = requests.get(
        f"{base_url}/like/content/count",
        params={"contentId": content_id},
        headers={"token": token},
        timeout=10,
    )
    body = resp.json()
    assert body.get("code") == 200, f"Like count query failed: {body}"
    return body["data"]


def get_content_like_status(base_url, content_id, token):
    """GET /like/content/status?contentId=X -> bool."""
    resp = requests.get(
        f"{base_url}/like/content/status",
        params={"contentId": content_id},
        headers={"token": token},
        timeout=10,
    )
    body = resp.json()
    assert body.get("code") == 200, f"Like status query failed: {body}"
    return body["data"]


def get_comment_like_count(base_url, comment_id, token):
    """GET /like/comment/count?commentId=X -> int."""
    resp = requests.get(
        f"{base_url}/like/comment/count",
        params={"commentId": comment_id},
        headers={"token": token},
        timeout=10,
    )
    body = resp.json()
    assert body.get("code") == 200, f"Comment like count query failed: {body}"
    return body["data"]


def get_profile(base_url, user_id, token):
    """GET /profile?userId=X -> profile dict."""
    resp = requests.get(
        f"{base_url}/profile",
        params={"userId": user_id},
        headers={"token": token},
        timeout=10,
    )
    body = resp.json()
    assert body.get("code") == 200, f"Profile query failed: {body}"
    return body["data"]


def get_content_detail(base_url, content_id, token):
    """GET /search/IdSearch?contentId=X -> content detail dict."""
    resp = requests.get(
        f"{base_url}/search/IdSearch",
        params={"contentId": content_id},
        headers={"token": token},
        timeout=10,
    )
    body = resp.json()
    assert body.get("code") == 200, f"Content detail query failed: {body}"
    return body["data"]


# ---------------------------------------------------------------------------
# C-01 ~ C-03: Content Like Count Consistency
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestContentLikeConsistency:

    def test_C01_like_count_increments(self, base_url, token_b, sample_content_id):
        """C-01: After liking, likeCount should increase by 1."""
        before = get_content_like_count(base_url, sample_content_id, token_b)

        # Like
        resp = requests.post(
            f"{base_url}/like/content/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"contentId": str(sample_content_id)},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Like failed: {body}"

        after = get_content_like_count(base_url, sample_content_id, token_b)
        assert after == before + 1, \
            f"likeCount should be {before + 1} after like, got {after}"

    def test_C02_unlike_count_decrements(self, base_url, token_b, sample_content_id):
        """C-02: After unliking, likeCount should decrease by 1."""
        # We're in the state where userB has already liked (from C-01).
        count_before = get_content_like_count(base_url, sample_content_id, token_b)

        # Cancel the like.
        resp = requests.post(
            f"{base_url}/like/content/remove",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"contentId": str(sample_content_id)},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Unlike failed: {body}"

        count_after = get_content_like_count(base_url, sample_content_id, token_b)
        assert count_after == count_before - 1, \
            f"likeCount should be {count_before - 1} after unlike, got {count_after}"

        # Also verify like status is false
        status = get_content_like_status(base_url, sample_content_id, token_b)
        assert status is False, f"Like status should be False after unlike, got {status}"

    def test_C03_like_status_consistency(self, base_url, token_b, sample_content_id):
        """C-03: Like status is true after liking, false after unliking."""
        # Like
        requests.post(
            f"{base_url}/like/content/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"contentId": str(sample_content_id)},
            timeout=10,
        )
        status_after_like = get_content_like_status(base_url, sample_content_id, token_b)
        assert status_after_like is True, \
            f"Like status should be True after like, got {status_after_like}"

        # Unlike
        requests.post(
            f"{base_url}/like/content/remove",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"contentId": str(sample_content_id)},
            timeout=10,
        )
        status_after_unlike = get_content_like_status(base_url, sample_content_id, token_b)
        assert status_after_unlike is False, \
            f"Like status should be False after unlike, got {status_after_unlike}"


# ---------------------------------------------------------------------------
# C-04 ~ C-05: Comment Like Count Consistency
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestCommentLikeConsistency:

    def _get_first_comment_id(self, base_url, content_id, token_a):
        """Helper: get the first commentId from a content's comment list.

        Note: Cache is lazy-loaded. Must call content detail first to trigger
        cache initialization before querying comments.
        """
        # Step 1: Trigger cache load by querying content detail
        resp = requests.get(
            f"{base_url}/search/IdSearch",
            params={"contentId": content_id},
            headers={"token": token_a},
            timeout=10,
        )
        # Step 2: Now query comments (cache should be loaded)
        resp = requests.get(
            f"{base_url}/comment/show",
            params={"contentId": content_id},
            headers={"token": token_a},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Comment list failed: {body}"
        comments = body.get("data", [])
        if len(comments) > 0:
            return comments[0]["commentId"]

        # If still no comments, try known content IDs with comments
        for known_id in [65, 74, 110, 80, 68, 70]:
            # Trigger cache load
            requests.get(
                f"{base_url}/search/IdSearch",
                params={"contentId": known_id},
                headers={"token": token_a},
                timeout=10,
            )
            resp = requests.get(
                f"{base_url}/comment/show",
                params={"contentId": known_id},
                headers={"token": token_a},
                timeout=10,
            )
            body = resp.json()
            comments = body.get("data", [])
            if len(comments) > 0:
                return comments[0]["commentId"]

        pytest.skip("No content with comments found in database")

    def test_C04_comment_like_count_increments(
        self, base_url, token_b, token_a, sample_content_id
    ):
        """C-04: After liking a comment, likeCount should increase by 1."""
        comment_id = self._get_first_comment_id(base_url, sample_content_id, token_a)

        before = get_comment_like_count(base_url, comment_id, token_b)

        # Like the comment
        resp = requests.post(
            f"{base_url}/like/comment/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"commentId": str(comment_id)},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Like comment failed: {body}"

        after = get_comment_like_count(base_url, comment_id, token_b)
        assert after == before + 1, \
            f"Comment likeCount should be {before + 1}, got {after}"

    def test_C05_comment_unlike_count_restores(
        self, base_url, token_b, token_a, sample_content_id
    ):
        """C-05: After unliking a comment, likeCount should decrease by 1."""
        comment_id = self._get_first_comment_id(base_url, sample_content_id, token_a)

        # Record count before unlike (userB liked this comment in C-04)
        count_before = get_comment_like_count(base_url, comment_id, token_b)

        # Unlike the comment (from state where userB liked it in C-04)
        resp = requests.post(
            f"{base_url}/like/comment/remove",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"commentId": str(comment_id)},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Unlike comment failed: {body}"

        count_after = get_comment_like_count(base_url, comment_id, token_b)
        assert count_after == count_before - 1, \
            f"Comment likeCount should be {count_before - 1} after unlike, got {count_after}"


# ---------------------------------------------------------------------------
# C-06 ~ C-08: Follow Count Consistency
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestFollowCountConsistency:

    def test_C06_C07_follow_count_increments(self, base_url, token_b, token_a, user_a_id, user_b_id):
        """C-06: After userB follows userA, userB's followCount +1.
        C-07: After userB follows userA, userA's followerCount +1.
        Merged to verify both counts using proper before/after delta.
        """
        # Record before state for both users
        profile_b_before = get_profile(base_url, user_b_id, token_b)
        profile_a_before = get_profile(base_url, user_a_id, token_a)
        follow_count_before = profile_b_before.get("followCount", 0)
        follower_count_before = profile_a_before.get("followerCount", 0)

        # Follow
        resp = requests.post(
            f"{base_url}/follow/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"followedUserId": str(user_a_id)},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Follow failed: {body}"

        # C-06: Verify userB's followCount +1
        profile_b_after = get_profile(base_url, user_b_id, token_b)
        follow_count_after = profile_b_after.get("followCount", 0)
        assert follow_count_after == follow_count_before + 1, \
            f"userB followCount should be {follow_count_before + 1}, got {follow_count_after}"

        # C-07: Verify userA's followerCount +1
        profile_a_after = get_profile(base_url, user_a_id, token_a)
        follower_count_after = profile_a_after.get("followerCount", 0)
        assert follower_count_after == follower_count_before + 1, \
            f"userA followerCount should be {follower_count_before + 1}, got {follower_count_after}"

    def test_C08_unfollow_count_restores(
        self, base_url, token_b, token_a, user_a_id, user_b_id
    ):
        """C-08: After unfollowing, both followCount and followerCount restore."""
        # Record before unfollow
        profile_b_before = get_profile(base_url, user_b_id, token_b)
        profile_a_before = get_profile(base_url, user_a_id, token_a)
        follow_count_before = profile_b_before.get("followCount", 0)
        follower_count_before = profile_a_before.get("followerCount", 0)

        # Unfollow
        resp = requests.post(
            f"{base_url}/follow/remove",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"followedUserId": str(user_a_id)},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Unfollow failed: {body}"

        # Verify both counts restored
        profile_b_after = get_profile(base_url, user_b_id, token_b)
        profile_a_after = get_profile(base_url, user_a_id, token_a)

        assert profile_b_after.get("followCount", 0) == follow_count_before - 1, \
            f"userB followCount should decrease by 1: {follow_count_before} -> {profile_b_after.get('followCount', 0)}"
        assert profile_a_after.get("followerCount", 0) == follower_count_before - 1, \
            f"userA followerCount should decrease by 1: {follower_count_before} -> {profile_a_after.get('followerCount', 0)}"


# ---------------------------------------------------------------------------
# C-09: Comment Count Consistency
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestCommentCountConsistency:

    def test_C09_comment_count_increments(
        self, base_url, token_a, sample_content_id
    ):
        """C-09: After adding a comment, content's commentCount +1."""
        detail_before = get_content_detail(base_url, sample_content_id, token_a)
        count_before = detail_before.get("commentCount", 0)

        # Add comment
        resp = requests.post(
            f"{base_url}/comment/add",
            headers={"token": token_a, "Content-Type": "application/json"},
            json={
                "contentId": sample_content_id,
                "message": "consistency count test",
            },
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Add comment failed: {body}"

        detail_after = get_content_detail(base_url, sample_content_id, token_a)
        count_after = detail_after.get("commentCount", 0)
        assert count_after == count_before + 1, \
            f"commentCount should be {count_before + 1}, got {count_after}"


# ---------------------------------------------------------------------------
# C-10: Coupon Stock Consistency
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestCouponStockConsistency:

    def test_C10_coupon_stock_decrements(
        self, base_url, token_a, available_coupon_id
    ):
        """C-10: After grabbing a coupon, stock should decrease by 1."""
        if available_coupon_id is None:
            pytest.skip("No available coupons in the system")

        # Record current stock from list
        resp_list = requests.get(f"{base_url}/coupon/list", timeout=10)
        list_body = resp_list.json()
        assert list_body.get("code") == 200, f"Coupon list failed: {list_body}"

        stock_before = None
        for coupon in list_body.get("data", []):
            coupon_id = coupon.get("id") or coupon.get("couponId")
            if coupon_id == available_coupon_id:
                stock_before = coupon.get("stock") or coupon.get("remainCount")
                break

        if stock_before is None:
            pytest.skip(f"Coupon {available_coupon_id} not found in list response")

        # Grab the coupon (may already be grabbed by user_a in smoke test)
        resp = requests.post(
            f"{base_url}/coupon/grab",
            headers={"token": token_a, "Content-Type": "application/json"},
            json={"couponId": available_coupon_id},
            timeout=10,
        )
        body = resp.json()

        if body.get("code") == 409:
            # Already grabbed - still verify the endpoint works
            pytest.skip("Coupon already grabbed by this user - cannot verify stock decrease")

        assert body.get("code") == 200, f"Grab coupon failed: {body}"

        # Check stock decreased
        resp_list2 = requests.get(f"{base_url}/coupon/list", timeout=10)
        list_body2 = resp_list2.json()
        assert list_body2.get("code") == 200, f"Coupon list query failed: {list_body2}"

        stock_after = None
        for coupon in list_body2.get("data", []):
            coupon_id = coupon.get("id") or coupon.get("couponId")
            if coupon_id == available_coupon_id:
                stock_after = coupon.get("stock") or coupon.get("remainCount")
                break

        if stock_after is not None and stock_before is not None:
            assert stock_after == stock_before - 1, \
                f"Stock should be {stock_before - 1} after grab, got {stock_after}"
