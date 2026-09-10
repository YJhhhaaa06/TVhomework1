# -*- coding: utf-8 -*-
"""
test_smoke.py - Level 1 冒烟测试 (Smoke Tests)

Core flow validation: register -> login -> upload -> interact -> query.
Target: Confirm the system is basically usable.

Test IDs follow ACCEPTANCE_CRITERIA.md:
  S-01 ~ S-16
"""

import os
import pytest
import requests

from conftest import STONE_DIR


# ---------------------------------------------------------------------------
# S-01 ~ S-03: User Module
# ---------------------------------------------------------------------------

@pytest.mark.smoke
class TestUserModule:

    def test_S01_register_returns_token(self, base_url):
        """S-01: POST /user/register -> code=200, returns id + token."""
        import time
        suffix = str(int(time.time() * 1000))[-8:]  # 8 digits for phone suffix
        resp = requests.post(
            f"{base_url}/user/register",
            json={
                "username": f"smoke_user_{suffix}",
                "phone": f"137{suffix}",
                "password": "abc123",
            },
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Register failed: {body}"
        data = body.get("data", {})
        assert "id" in data, f"Response missing 'id': {data}"
        assert "token" in data, f"Response missing 'token': {data}"
        assert isinstance(data["token"], str) and len(data["token"]) > 10, \
            f"Token looks invalid: {data['token']}"

    def test_S02_login_by_phone(self, user_a, base_url):
        """S-02: POST /user/login (by phone) -> code=200, returns id + token."""
        resp = requests.post(
            f"{base_url}/user/login",
            json={"account": user_a["phone"], "password": "abc123"},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Login by phone failed: {body}"
        data = body.get("data", {})
        assert "id" in data and "token" in data, \
            f"Login response missing fields: {data}"

    def test_S03_login_by_id(self, user_a, base_url):
        """S-03: POST /user/login (by user id) -> code=200, returns id + token."""
        resp = requests.post(
            f"{base_url}/user/login",
            json={"account": str(user_a["id"]), "password": "abc123"},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Login by ID failed: {body}"
        data = body.get("data", {})
        assert "id" in data and "token" in data, \
            f"Login response missing fields: {data}"


# ---------------------------------------------------------------------------
# S-04 ~ S-06: Content Module
# ---------------------------------------------------------------------------

@pytest.mark.smoke
class TestContentModule:

    def test_S05_search(self, base_url):
        """S-05: GET /search/keywordSearch?keyword=... -> code=200, paginated structure."""
        resp = requests.get(
            f"{base_url}/search/keywordSearch",
            params={"keyword": "test", "page": 1, "pageSize": 10},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Search failed: {body}"
        data = body.get("data", {})
        assert "list" in data, f"Search response missing 'list': {data}"
        assert "total" in data, f"Search response missing 'total': {data}"
        assert "page" in data, f"Search response missing 'page': {data}"
        assert "pageSize" in data, f"Search response missing 'pageSize': {data}"

    def test_S06_content_detail(self, sample_content_id, token_a, base_url):
        """S-06: GET /search/IdSearch?contentId=... -> code=200, has title + media."""
        resp = requests.get(
            f"{base_url}/search/IdSearch",
            params={"contentId": sample_content_id},
            headers={"token": token_a},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Content detail failed: {body}"
        data = body.get("data", {})
        assert "title" in data, f"Detail missing 'title': {data}"
        # Should have either videoUrl (type=1) or imageUrls (type=2)
        has_media = "videoUrl" in data or "imageUrls" in data
        assert has_media, f"Detail missing media fields (videoUrl/imageUrls): {data.keys()}"

    # S-04 moved to end of class: homepage recommend depends on upload (sample_content_id)
    # being created first, so it must run after upload tests.
    def test_S04_homepage_recommend(self, base_url):
        """S-04: GET /start -> code=200, data is a non-empty list."""
        resp = requests.get(f"{base_url}/start?limit=10", timeout=10)
        body = resp.json()
        assert body.get("code") == 200, f"Homepage failed: {body}"
        data = body.get("data")
        assert isinstance(data, list), f"Expected list, got {type(data)}: {body}"
        assert len(data) > 0, "Homepage returned empty list - no content in system"


# ---------------------------------------------------------------------------
# S-07 ~ S-09: Upload Module
# ---------------------------------------------------------------------------

@pytest.mark.smoke
class TestUploadModule:

    def test_S07_upload_video(self, token_a, test_files, base_url):
        """S-07: POST /api/upload/video -> code=200, returns contentId."""
        headers = {"token": token_a}
        with open(test_files["video"], "rb") as vf, open(test_files["cover"], "rb") as cf:
            resp = requests.post(
                f"{base_url}/api/upload/video",
                headers=headers,
                data={
                    "title": "smoke_test_video",
                    "description": "Smoke test video upload",
                    "categoryId": "1",
                },
                files={
                    "video": ("test_video.mp4", vf, "video/mp4"),
                    "cover": ("test_cover.png", cf, "image/png"),
                },
                timeout=30,
            )
        body = resp.json()
        assert body.get("code") == 200, f"Upload video failed: {body}"
        data = body.get("data", {})
        assert "contentId" in data, f"Upload response missing 'contentId': {data}"
        assert isinstance(data["contentId"], int), \
            f"contentId should be int, got {type(data['contentId'])}"

    def test_S08_upload_verify_detail(self, sample_content_id, token_a, base_url):
        """S-08: After upload, query detail -> videoUrl is present."""
        resp = requests.get(
            f"{base_url}/search/IdSearch",
            params={"contentId": sample_content_id},
            headers={"token": token_a},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Detail query failed: {body}"
        data = body.get("data", {})
        assert "videoUrl" in data, f"Uploaded video missing videoUrl: {data.keys()}"
        video_url = data["videoUrl"]
        assert video_url and len(video_url) > 0, "videoUrl is empty"

    def test_S09_file_on_disk(self, sample_content_id, token_a, base_url):
        """S-09: Uploaded file exists in the storage dir and has size > 0."""
        # Get detail to find the filename
        resp = requests.get(
            f"{base_url}/search/IdSearch",
            params={"contentId": sample_content_id},
            headers={"token": token_a},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Detail query failed: {body}"
        data = body.get("data", {})
        video_url = data.get("videoUrl", "")
        assert video_url, "No videoUrl in detail"

        # URL format: /upload/video/abc-123.mp4
        # Local path: <STONE_DIR>/video/abc-123.mp4
        # Extract relative path after /upload/
        if "/upload/" in video_url:
            relative_path = video_url.split("/upload/")[1]
        else:
            relative_path = video_url.split("/")[-1]
        filepath = f"{STONE_DIR}/{relative_path}"

        assert os.path.exists(filepath), \
            f"File not found on disk: {filepath} (videoUrl={video_url})"
        assert os.path.getsize(filepath) > 0, \
            f"File exists but is empty: {filepath}"


# ---------------------------------------------------------------------------
# S-10 ~ S-14: Social Module
# ---------------------------------------------------------------------------

@pytest.mark.smoke
class TestSocialModule:

    # S-10 and S-11 merged: unlike depends on like having been executed first.
    def test_S10_S11_like_and_unlike(self, token_b, sample_content_id, base_url):
        """S-10: POST /like/content/add -> code=200.
        S-11: POST /like/content/remove -> code=200.
        Merged because unlike implicitly depends on like being done first.
        """
        # S-10: Like
        resp = requests.post(
            f"{base_url}/like/content/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"contentId": str(sample_content_id)},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Like content failed: {body}"

        # S-11: Unlike
        resp = requests.post(
            f"{base_url}/like/content/remove",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"contentId": str(sample_content_id)},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Unlike content failed: {body}"

    def test_S12_add_comment(self, token_a, sample_content_id, base_url):
        """S-12: POST /comment/add -> code=200."""
        resp = requests.post(
            f"{base_url}/comment/add",
            headers={"token": token_a, "Content-Type": "application/json"},
            json={"contentId": sample_content_id, "message": "smoke test comment"},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Add comment failed: {body}"

    # S-13 and S-14 merged: unfollow depends on follow having been executed first.
    def test_S13_S14_follow_and_unfollow(self, token_b, user_a_id, base_url):
        """S-13: POST /follow/add -> code=200.
        S-14: POST /follow/remove -> code=200.
        Merged because unfollow implicitly depends on follow being done first.
        """
        # S-13: Follow
        resp = requests.post(
            f"{base_url}/follow/add",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"followedUserId": str(user_a_id)},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Follow failed: {body}"

        # S-14: Unfollow
        resp = requests.post(
            f"{base_url}/follow/remove",
            headers={"token": token_b, "Content-Type": "application/x-www-form-urlencoded"},
            data={"followedUserId": str(user_a_id)},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Unfollow failed: {body}"


# ---------------------------------------------------------------------------
# S-15 ~ S-16: Coupon Module
# ---------------------------------------------------------------------------

@pytest.mark.smoke
class TestCouponModule:

    def test_S15_coupon_list(self, base_url):
        """S-15: GET /coupon/list -> code=200, returns list."""
        resp = requests.get(f"{base_url}/coupon/list", timeout=10)
        body = resp.json()
        assert body.get("code") == 200, f"Coupon list failed: {body}"
        data = body.get("data")
        assert isinstance(data, list), f"Expected list, got {type(data)}: {body}"

    def test_S16_grab_coupon(self, token_a, available_coupon_id, base_url):
        """S-16: POST /coupon/grab -> code=200, returns exchange code.

        available_coupon_id 命中 T1 固定券种子（缺失由 fixture fail，不再静默 skip）。
        同一会话内 E-09 已用 userA 抢过该券，故这里 200（首抢）或 409（同用户重复抢）
        均为合法；200 时校验兑换码非空。
        """
        resp = requests.post(
            f"{base_url}/coupon/grab",
            headers={"token": token_a, "Content-Type": "application/json"},
            json={"couponId": available_coupon_id},
            timeout=10,
        )
        body = resp.json()
        # Could be 200 (success) or 409 (already grabbed / out of stock)
        # For smoke test, we just check the endpoint is reachable and returns a valid response
        assert body.get("code") in (200, 409), \
            f"Grab coupon returned unexpected code: {body}"
        if body.get("code") == 200:
            data = body.get("data")
            assert data and isinstance(data, str) and len(data) > 0, \
                f"Exchange code should be non-empty string: {data}"
