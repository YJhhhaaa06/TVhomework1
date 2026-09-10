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
        """C-01: After liking, likeCount should increase by 1. 测后 unlike 复原（自建自清）。"""
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

        # 清理：unlike 复原，保证用例自包含、不依赖/不污染其他用例
        cleanup = requests.post(
            f"{base_url}/like/content/remove",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"contentId": str(sample_content_id)},
            timeout=10,
        )
        assert cleanup.json().get("code") == 200, f"Unlike cleanup failed: {cleanup.json()}"

    def test_C02_unlike_count_decrements(self, base_url, token_b, sample_content_id):
        """C-02: After unliking, likeCount should decrease by 1.

        自包含：用例内先 like 建立点赞状态，再 unlike 验证 -1（不再依赖 C-01 先跑）。
        """
        count_before = get_content_like_count(base_url, sample_content_id, token_b)

        # 自建点赞状态
        like_resp = requests.post(
            f"{base_url}/like/content/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"contentId": str(sample_content_id)},
            timeout=10,
        )
        like_body = like_resp.json()
        assert like_body.get("code") == 200, f"Like setup failed: {like_body}"
        count_liked = get_content_like_count(base_url, sample_content_id, token_b)
        assert count_liked == count_before + 1, \
            f"Like setup should make count {count_before + 1}, got {count_liked}"

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
        assert count_after == count_liked - 1, \
            f"likeCount should be {count_liked - 1} after unlike, got {count_after}"
        assert count_after == count_before, "unlike 应完全复原（净零残留）"

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

    def test_C04_comment_like_count_increments(
        self, base_url, token_b, sample_comment_id
    ):
        """C-04: After liking a comment, likeCount should increase by 1. 测后 unlike 复原（自建自清）。"""
        comment_id = sample_comment_id

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

        # 清理：unlike 复原，保证用例自包含
        cleanup = requests.post(
            f"{base_url}/like/comment/remove",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"commentId": str(comment_id)},
            timeout=10,
        )
        assert cleanup.json().get("code") == 200, f"Unlike comment cleanup failed: {cleanup.json()}"

    def test_C05_comment_unlike_count_restores(
        self, base_url, token_b, sample_comment_id
    ):
        """C-05: After unliking a comment, likeCount should decrease by 1.

        自包含：用例内先 like 建立点赞状态，再 unlike 验证 -1（不再依赖 C-04 先跑）。
        """
        comment_id = sample_comment_id

        count_before = get_comment_like_count(base_url, comment_id, token_b)

        # 自建点赞状态
        like_resp = requests.post(
            f"{base_url}/like/comment/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"commentId": str(comment_id)},
            timeout=10,
        )
        like_body = like_resp.json()
        assert like_body.get("code") == 200, f"Like comment setup failed: {like_body}"
        count_liked = get_comment_like_count(base_url, comment_id, token_b)
        assert count_liked == count_before + 1, \
            f"Comment likeCount should be {count_before + 1} after like, got {count_liked}"

        # Unlike the comment
        resp = requests.post(
            f"{base_url}/like/comment/remove",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"commentId": str(comment_id)},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Unlike comment failed: {body}"

        count_after = get_comment_like_count(base_url, comment_id, token_b)
        assert count_after == count_liked - 1, \
            f"Comment likeCount should be {count_liked - 1} after unlike, got {count_after}"
        assert count_after == count_before, "unlike 应完全复原（净零残留）"


# ---------------------------------------------------------------------------
# C-06 ~ C-08: Follow Count Consistency
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestFollowCountConsistency:

    def test_C06_C07_follow_count_increments(self, base_url, token_b, token_a, user_a_id, user_b_id):
        """C-06: After userB follows userA, userB's followCount +1.
        C-07: After userA is followed by userB, userA's followerCount +1.
        Merged to verify both counts using proper before/after delta.
        测后 unfollow 复原（自建自清）。
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

        # 清理：unfollow 复原，保证用例自包含
        cleanup = requests.post(
            f"{base_url}/follow/remove",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"followedUserId": str(user_a_id)},
            timeout=10,
        )
        assert cleanup.json().get("code") == 200, f"Unfollow cleanup failed: {cleanup.json()}"

    def test_C08_unfollow_count_restores(
        self, base_url, token_b, token_a, user_a_id, user_b_id
    ):
        """C-08: After unfollowing, both followCount and followerCount restore.

        自包含：用例内先 follow 建立关注状态，再 unfollow 验证复原（不再依赖 C-06/C-07 先跑）。
        """
        # Record before unfollow
        profile_b_before = get_profile(base_url, user_b_id, token_b)
        profile_a_before = get_profile(base_url, user_a_id, token_a)
        follow_count_before = profile_b_before.get("followCount", 0)
        follower_count_before = profile_a_before.get("followerCount", 0)

        # 自建关注状态
        follow_resp = requests.post(
            f"{base_url}/follow/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"followedUserId": str(user_a_id)},
            timeout=10,
        )
        follow_body = follow_resp.json()
        assert follow_body.get("code") == 200, f"Follow setup failed: {follow_body}"

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

        assert profile_b_after.get("followCount", 0) == follow_count_before, \
            f"userB followCount should restore to {follow_count_before}, got {profile_b_after.get('followCount', 0)}"
        assert profile_a_after.get("followerCount", 0) == follower_count_before, \
            f"userA followerCount should restore to {follower_count_before}, got {profile_a_after.get('followerCount', 0)}"


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
        self, base_url, token_b, available_coupon_id
    ):
        """C-10: After grabbing a coupon, stock should decrease by 1.

        T1 起 available_coupon_id 命中固定券种子（高库存/远期），stock_before 必命中列表；
        userB 每次会话新建、从未抢过该券，grab 必 200（不再有"已抢过/找不到"skip 分支）。
        断言方向只锁相对差值（before-1），不锁绝对库存。
        """
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

        assert stock_before is not None, \
            f"Coupon {available_coupon_id} not found in list response"

        # Use userB so this test does not collide with E-09 (userA) in a
        # combined run.
        resp = requests.post(
            f"{base_url}/coupon/grab",
            headers={"token": token_b, "Content-Type": "application/json"},
            json={"couponId": available_coupon_id},
            timeout=10,
        )
        body = resp.json()
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

        assert stock_after is not None, \
            f"Coupon {available_coupon_id} not found in list after grab"
        assert stock_after == stock_before - 1, \
            f"Stock should be {stock_before - 1} after grab, got {stock_after}"
