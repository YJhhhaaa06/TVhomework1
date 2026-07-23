# -*- coding: utf-8 -*-
"""
conftest.py - pytest fixtures for TV community platform acceptance tests.

Provides:
- base_url fixture
- Two test users (userA, userB) with registration + login
- Content creation (video upload) fixture
- Available coupon fixture (skip if none)
- Test file fixtures for upload tests
"""

import os
import uuid
import pytest
import requests

BASE_URL = "http://localhost:8080/MyAPP"

# Test user credentials - use UUID for guaranteed uniqueness across runs
# Phone must be 11 digits starting with 1, so use digits only
_unique = uuid.uuid4().hex[:8]
_phone_suffix = str(int(_unique, 16))[-8:].zfill(8)  # Convert hex to digits, take last 8
USER_A = {
    "username": f"testA_{_unique}",
    "phone": f"138{_phone_suffix}",
    "password": "abc123",
}
USER_B = {
    "username": f"testB_{_unique}",
    "phone": f"139{_phone_suffix}",
    "password": "abc123",
}

# Storage directory on server (used to verify file uploads exist)
STONE_DIR = "D:/stone"


# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------

def register_user(username, phone, password):
    """Register a new user and return the response JSON."""
    resp = requests.post(
        f"{BASE_URL}/user/register",
        json={"username": username, "phone": phone, "password": password},
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json()


def login_user(account, password):
    """Login with account (phone or id) and password, return the response JSON."""
    resp = requests.post(
        f"{BASE_URL}/user/login",
        json={"account": account, "password": password},
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json()


def create_test_files():
    """Create minimal test files for upload tests. Returns paths dict.

    Files are at least 1KB to avoid server-side minimum size rejection.
    """
    import tempfile

    MIN_SIZE = 1024  # 1 KB minimum

    test_dir = os.path.join(tempfile.gettempdir(), "tvtest_upload")
    os.makedirs(test_dir, exist_ok=True)

    # MP4 file: ftyp box + padding to reach >= 1KB
    video_path = os.path.join(test_dir, "test_video.mp4")
    if not os.path.exists(video_path) or os.path.getsize(video_path) < MIN_SIZE:
        ftyp_box = (
            b"\x00\x00\x00\x1c\x66\x74\x79\x70\x69\x73\x6f\x6d"
            b"\x00\x00\x02\x00\x69\x73\x6f\x6d\x69\x73\x6f\x32"
            b"\x6d\x70\x34\x31"
        )
        with open(video_path, "wb") as f:
            f.write(ftyp_box)
            # Pad with zero bytes to reach MIN_SIZE
            remaining = MIN_SIZE - len(ftyp_box)
            if remaining > 0:
                f.write(b"\x00" * remaining)

    # PNG file: valid header + padding to reach >= 1KB
    cover_path = os.path.join(test_dir, "test_cover.png")
    if not os.path.exists(cover_path) or os.path.getsize(cover_path) < MIN_SIZE:
        png_header = (
            b"\x89\x50\x4e\x47\x0d\x0a\x1a\x0a"  # PNG signature
            b"\x00\x00\x00\x0d\x49\x48\x44\x52"  # IHDR chunk
            b"\x00\x00\x00\x01\x00\x00\x00\x01"
            b"\x08\x02\x00\x00\x00\x90\x77\x53"
            b"\xde\x00\x00\x00\x0c\x49\x44\x41"
            b"\x54\x08\xd7\x63\xf8\xcf\xc0\x00"
            b"\x00\x00\x02\x00\x01\xe2\x21\xbc"
            b"\x33\x00\x00\x00\x00\x49\x45\x4e"
            b"\x44\xae\x42\x60\x82"
        )
        with open(cover_path, "wb") as f:
            f.write(png_header)
            remaining = MIN_SIZE - len(png_header)
            if remaining > 0:
                f.write(b"\x00" * remaining)

    # JPEG file: valid header + padding to reach >= 1KB
    image_path = os.path.join(test_dir, "test_image.jpg")
    if not os.path.exists(image_path) or os.path.getsize(image_path) < MIN_SIZE:
        jpg_header = (
            b"\xff\xd8\xff\xe0"  # SOI + APP0 marker
            b"\x00\x10\x4a\x46\x49\x46\x00\x01"
            b"\x01\x00\x00\x01\x00\x01\x00\x00"
            b"\xff\xd9"  # EOI
        )
        with open(image_path, "wb") as f:
            f.write(jpg_header)
            remaining = MIN_SIZE - len(jpg_header)
            if remaining > 0:
                f.write(b"\x00" * remaining)

    return {"video": video_path, "cover": cover_path, "image": image_path}


# ---------------------------------------------------------------------------
# Pytest fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def base_url():
    """Base URL for all API requests."""
    return BASE_URL


@pytest.fixture(scope="session")
def user_a(base_url):
    """Register (or login if already exists) userA. Returns dict with id, token, phone."""
    reg = register_user(USER_A["username"], USER_A["phone"], USER_A["password"])
    if reg.get("code") == 200:
        data = reg.get("data", {})
        return {"id": data.get("id"), "token": data.get("token"), "phone": USER_A["phone"]}
    # Registration failed (e.g. phone already used) - try login
    login_resp = login_user(USER_A["phone"], USER_A["password"])
    if login_resp.get("code") == 200:
        data = login_resp.get("data", {})
        return {"id": data.get("id"), "token": data.get("token"), "phone": USER_A["phone"]}
    pytest.fail(f"Cannot create userA: register={reg}, login={login_resp}")


@pytest.fixture(scope="session")
def user_b(base_url):
    """Register (or login if already exists) userB. Returns dict with id, token, phone."""
    reg = register_user(USER_B["username"], USER_B["phone"], USER_B["password"])
    if reg.get("code") == 200:
        data = reg.get("data", {})
        return {"id": data.get("id"), "token": data.get("token"), "phone": USER_B["phone"]}
    login_resp = login_user(USER_B["phone"], USER_B["password"])
    if login_resp.get("code") == 200:
        data = login_resp.get("data", {})
        return {"id": data.get("id"), "token": data.get("token"), "phone": USER_B["phone"]}
    pytest.fail(f"Cannot create userB: register={reg}, login={login_resp}")


@pytest.fixture(scope="session")
def token_a(user_a):
    """Shortcut: userA's token string."""
    return user_a["token"]


@pytest.fixture(scope="session")
def token_b(user_b):
    """Shortcut: userB's token string."""
    return user_b["token"]


@pytest.fixture(scope="session")
def user_a_id(user_a):
    """Shortcut: userA's numeric id."""
    return user_a["id"]


@pytest.fixture(scope="session")
def user_b_id(user_b):
    """Shortcut: userB's numeric id."""
    return user_b["id"]


@pytest.fixture(scope="session")
def test_files():
    """Create minimal test files for upload. Returns dict of paths."""
    return create_test_files()


@pytest.fixture(scope="session")
def sample_content_id(token_a, test_files):
    """Upload a video by userA and return the contentId.

    This is session-scoped so the upload only happens once.
    """
    headers = {"token": token_a}
    with open(test_files["video"], "rb") as vf, open(test_files["cover"], "rb") as cf:
        resp = requests.post(
            f"{BASE_URL}/api/upload/video",
            headers=headers,
            data={
                "title": "pytest_test_video",
                "description": "Auto-generated test video for acceptance tests",
                "categoryId": "1",
            },
            files={
                "video": ("test_video.mp4", vf, "video/mp4"),
                "cover": ("test_cover.png", cf, "image/png"),
            },
            timeout=30,
        )
    result = resp.json()
    assert result.get("code") == 200, f"Upload failed: {result}"
    content_id = result["data"]["contentId"]
    return content_id


@pytest.fixture(scope="session")
def sample_post_content_id(token_a, test_files):
    """Upload a post (image text) by userA and return the contentId."""
    headers = {"token": token_a}
    with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
        resp = requests.post(
            f"{BASE_URL}/api/upload/post",
            headers=headers,
            data={
                "title": "pytest_test_post",
                "description": "Auto-generated test post for acceptance tests",
                "categoryId": "0",
            },
            files={
                "cover": ("test_cover.png", cf, "image/png"),
                "image": ("test_image.jpg", imf, "image/jpeg"),
            },
            timeout=30,
        )
    result = resp.json()
    assert result.get("code") == 200, f"Post upload failed: {result}"
    return result["data"]["contentId"]


@pytest.fixture(scope="session")
def available_coupon_id(base_url):
    """Fetch available coupons. Returns the first couponId, or None if none available.

    Tests that require a coupon should use:
        @pytest.mark.skipif(available_coupon_id is None, reason="No coupons available")
    But since fixtures can't be used in marks, tests should handle None inside.
    """
    resp = requests.get(f"{BASE_URL}/coupon/list", timeout=10)
    result = resp.json()
    if result.get("code") == 200 and result.get("data"):
        coupons = result["data"]
        if isinstance(coupons, list) and len(coupons) > 0:
            # Return the first coupon's id
            coupon = coupons[0]
            return coupon.get("id") or coupon.get("couponId")
    return None
