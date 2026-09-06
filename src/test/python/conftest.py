# -*- coding: utf-8 -*-
"""
conftest.py - pytest fixtures for TV community platform acceptance tests.

Provides:
- base_url fixture
- Two test users (userA, userB) with registration + login
- Content creation (video upload) fixture
- Available coupon fixture (fixed seed coupon, fail if seed missing)
- Test file fixtures for upload tests
"""

import os
import shutil
import uuid
import pytest
import requests

# 默认指向 run_tests 启动的独立测试实例（18080，连测试库 3307），避免手动跑 pytest 误连 IDEA 8080 生产实例
BASE_URL = os.environ.get("TV_BASE_URL", "http://127.0.0.1:18080")

# 基线种子内容标题前缀（与 tools/init_test_db.py 的 SEED_CONTENT_TITLE_PREFIX 保持一致，
# 两处需同步修改）。避开 pytest_/smoke_ 前缀，以免被 cleanup_data.py 自动清理删除。
SEED_COMMENT_CONTENT_PREFIX = "seed_baseline"

# T1 固定券种子标题前缀（与 tools/init_test_db.py 的 SEED_COUPON_TITLE_PREFIX 保持一致，
# 两处需同步修改）：基线种子固定一张高库存/远期券（end 2099、库存 999999），
# 确定可抢、永不耗尽，供 available_coupon_id 确定性命中，消灭 coupon 用例静默 skip。
SEED_COUPON_TITLE_PREFIX = "seed_baseline_coupon"

# E-09b 过期券测试（T5）：测试库重建自 .docs/archive/DBbackups 最新 db.sql，
# 其中 coupon id=6 为历史过期券（begin 2026-05-12 / end 2026-05-15，库存 998），
# end_time 恒小于当前时间，grab 必 409 且不产生订单/库存消耗；备份更新后需核对本常量。
EXPIRED_COUPON_ID = 6

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
STONE_DIR = os.environ.get("TV_STONE_DIR", "D:/data/projects/VideoPlatform/stone")

# Real media files for upload tests (configurable via TV_TEST_RESOURCE_DIR)
TEST_RESOURCE_DIR = os.environ.get(
    "TV_TEST_RESOURCE_DIR", r"D:\dev\WorkSpace\VideoPlatform\TestResource"
)


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


_REAL_FILES = {
    "video": ("test_video.mp4", os.path.join(TEST_RESOURCE_DIR, "test_video.mp4")),
    "cover": ("test_cover.png", os.path.join(TEST_RESOURCE_DIR, "test_cover.png")),
    "image": ("test_image.jpg", os.path.join(TEST_RESOURCE_DIR, "test_image.jpg")),
}


def _copy_real_test_files(test_dir):
    """Copy real media files from TEST_RESOURCE_DIR into test_dir.

    Returns None if any required file is missing, so callers can fall back.
    """
    result = {}
    for key, (filename, source) in _REAL_FILES.items():
        if not os.path.isfile(source):
            return None
        dest = os.path.join(test_dir, filename)
        shutil.copy2(source, dest)
        result[key] = dest
    return result


def create_test_files():
    """Return paths to upload test files.

    Prefers real media files from TEST_RESOURCE_DIR (copied to a temp dir so
    the originals are never modified). Falls back to synthetic 1KB files if
    the resource directory is unavailable.
    """
    import tempfile

    test_dir = os.path.join(tempfile.gettempdir(), "tvtest_upload")
    os.makedirs(test_dir, exist_ok=True)

    real_files = _copy_real_test_files(test_dir)
    if real_files:
        return real_files

    MIN_SIZE = 1024  # 1 KB minimum

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
def sample_comment_id(base_url, token_a):
    """Return a stable commentId from the baseline seed content.

    Baseline content (title prefix SEED_COMMENT_CONTENT_PREFIX) is created by
    tools/init_test_db.py on every test-DB rebuild, so its comments exist in the
    cache loaded at app startup. Locate it deterministically via /search/keywordSearch
    (MATCH on title/description, is_deleted=0, seed created last at init); fall back
    to scanning the homepage for any content with comments if the search misses.
    """
    def _comment_id_of_content(content_id, strict=False):
        """取内容首条评论 id。

        strict=True（兜底分支，保持旧语义）：detail/comment 接口非 200 即抛断言；
        strict=False（基线分支）：仅探测，任何失败返回 None 以便回退。
        """
        try:
            resp = requests.get(
                f"{base_url}/search/IdSearch",
                params={"contentId": content_id},
                headers={"token": token_a},
                timeout=10,
            )
            body = resp.json()
            if strict:
                assert body.get("code") == 200, f"Content detail failed: {body}"
            elif body.get("code") != 200:
                return None

            resp = requests.get(
                f"{base_url}/comment/show",
                params={"contentId": content_id},
                headers={"token": token_a},
                timeout=10,
            )
            body = resp.json()
            if strict:
                assert body.get("code") == 200, f"Comment list failed: {body}"
            elif body.get("code") != 200:
                return None
            comments = body.get("data", [])
            return comments[0]["commentId"] if comments else None
        except (requests.RequestException, KeyError, TypeError, ValueError):
            return None

    # 1) 基线种子内容：关键字搜索确定性定位（种子标题唯一，创建时间最新 -> 排在首屏）
    resp = requests.get(
        f"{base_url}/search/keywordSearch",
        params={"keyword": SEED_COMMENT_CONTENT_PREFIX, "page": 1, "pageSize": 50},
        timeout=10,
    )
    body = resp.json()
    if body.get("code") == 200:
        items = (body.get("data") or {}).get("list") or []
        for item in items:
            if str(item.get("title", "")).startswith(SEED_COMMENT_CONTENT_PREFIX):
                comment_id = _comment_id_of_content(item.get("id"))
                if comment_id is not None:
                    return comment_id

    # 2) 兜底：扫描首页任意带评论的内容（兼容未种子化/旧数据），保持原严格断言
    resp = requests.get(f"{base_url}/start", params={"limit": 50}, timeout=10)
    body = resp.json()
    assert body.get("code") == 200, f"Homepage failed: {body}"
    for item in body.get("data", []):
        if item.get("commentCount", 0) > 0:
            comment_id = _comment_id_of_content(item["id"], strict=True)
            if comment_id is not None:
                return comment_id

    pytest.skip("No content with comments found in database")


@pytest.fixture(scope="session")
def available_coupon_id(base_url):
    """返回固定券种子（标题前缀 SEED_COUPON_TITLE_PREFIX）的 couponId。

    T1 起固定券种子由 init_test_db.py 幂等追加（高库存 999999、end 2099-12-31），
    /coupon/list 中按标题前缀确定性命中该券；缺失说明测试库未重建/种子失效，
    直接 fail（快速失败优于静默 skip，N2 收紧方向）。
    """
    resp = requests.get(f"{BASE_URL}/coupon/list", timeout=10)
    result = resp.json()
    assert result.get("code") == 200, f"Coupon list failed: {result}"
    coupons = result.get("data") or []
    for coupon in coupons:
        if str(coupon.get("title", "")).startswith(SEED_COUPON_TITLE_PREFIX):
            return coupon.get("id") or coupon.get("couponId")
    pytest.fail(
        f"测试库缺少固定券种子（标题前缀 {SEED_COUPON_TITLE_PREFIX}），"
        f"请先重建测试库: python tools/init_test_db.py"
    )


@pytest.fixture(scope="session")
def expired_coupon_id():
    """返回一张确定已过期的优惠券 id（conftest.EXPIRED_COUPON_ID，来自 db.sql 历史行）。

    已过期券 grab 必 409（deductStock 的 end_time >= NOW() 不满足），不落订单、不耗库存。
    """
    return EXPIRED_COUPON_ID
