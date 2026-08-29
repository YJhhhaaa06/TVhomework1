# -*- coding: utf-8 -*-
"""
test_hide_content.py - 阶段五 A2 管理员下架/恢复内容 验收测试。

覆盖：
1. 一致性：管理员下架视频/图文后详情 404、作者主页不再列出、管理端 list hidden=true；
   恢复后详情 200、作者主页重新列出、媒体 URL 与评论/点赞计数与下架前一致（隐藏≠删除，数据完好）
2. 边界：未登录 401、非管理员 403、不存在 404、已下架重复下架 409、未下架恢复 409、缺 contentId 400、
   管理端 list 结构

说明：
- 复用 conftest fixtures：token_a/token_b/user_a_id/test_files。
- 管理员身份用模块级 admin_user fixture：注册临时用户 → tools/admin.py --promote → 测后 --demote。
- 下架/恢复是状态变更操作，一律「用例内新建内容（唯一标题含 uuid）」再操作；finally 兜底
  （已下架先管理员恢复、再由作者删除），绝不触碰共享 fixture（sample_content_id 等）。
- 首页/搜索不直接断言（/start 随机排序、全文索引分词不稳定，由 DAO is_deleted=0 过滤 + removeContent
  缓存剔除代码层保证），改为断言详情 404 + 作者主页不列出等确定性项。
"""

import os
import subprocess
import sys
import uuid
from pathlib import Path

import pytest
import requests

_unique = uuid.uuid4().hex[:8]

PROJECT_ROOT = Path(__file__).resolve().parents[3]


# ---------------------------------------------------------------------------
# 管理员身份 fixture（复制 test_admin.py 范式，promote/demote 成对）
# ---------------------------------------------------------------------------

def _run_admin(*args):
    """调用 tools/admin.py，失败即抛出。"""
    proc = subprocess.run(
        [sys.executable, str(PROJECT_ROOT / "tools" / "admin.py"), *args],
        cwd=str(PROJECT_ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=60,
    )
    assert proc.returncode == 0, (
        f"admin.py {args} failed: stdout={proc.stdout!r} stderr={proc.stderr!r}"
    )
    return proc.stdout


@pytest.fixture(scope="module")
def admin_user(base_url):
    """注册临时用户并提升为管理员，用例结束降级恢复。"""
    unique = uuid.uuid4().hex[:8]
    suffix = str(int(unique, 16))[-8:].zfill(8)
    payload = {
        "username": f"hid_admin_{unique}",
        "phone": f"136{suffix}",
        "password": "abc123",
    }
    reg = requests.post(
        f"{base_url}/user/register", json=payload, timeout=10
    ).json()
    assert reg.get("code") == 200, f"管理员注册失败: {reg}"
    data = reg.get("data", {})
    user_id = data.get("id")
    assert user_id is not None, f"注册响应缺少 id: {reg}"
    _run_admin("--promote", str(user_id))
    yield {"id": user_id, "token": data.get("token"), "phone": payload["phone"]}
    _run_admin("--demote", str(user_id))


# ---------------------------------------------------------------------------
# 辅助
# ---------------------------------------------------------------------------

def hide_content(base_url, token, content_id):
    headers = {"token": token} if token else {}
    resp = requests.post(
        f"{base_url}/api/admin/content/hide",
        params={"contentId": content_id},
        headers=headers,
        timeout=10,
    )
    return resp.json()


def unhide_content(base_url, token, content_id):
    headers = {"token": token} if token else {}
    resp = requests.post(
        f"{base_url}/api/admin/content/unhide",
        params={"contentId": content_id},
        headers=headers,
        timeout=10,
    )
    return resp.json()


def admin_content_list(base_url, token):
    headers = {"token": token} if token else {}
    resp = requests.get(f"{base_url}/api/admin/content/list", headers=headers, timeout=10)
    return resp.json()


def admin_list_item(base_url, token, content_id):
    """管理端清单中该 contentId 的条目（不存在返回 None）。"""
    body = admin_content_list(base_url, token)
    assert body.get("code") == 200, f"管理端清单查询失败: {body}"
    for item in body.get("data") or []:
        if item.get("id") == content_id:
            return item
    return None


def get_detail_result(base_url, content_id, token=None):
    """返回详情查询的原始 JSON（用于 404 断言）。"""
    headers = {"token": token} if token else {}
    resp = requests.get(
        f"{base_url}/search/IdSearch",
        params={"contentId": content_id},
        headers=headers,
        timeout=10,
    )
    return resp.json()


def get_detail(base_url, content_id, token=None):
    result = get_detail_result(base_url, content_id, token)
    assert result.get("code") == 200, f"详情查询失败: {result}"
    return result["data"]


def upload_video(base_url, token, title, test_files):
    """用例内新建独立视频，返回响应 JSON。"""
    headers = {"token": token}
    with open(test_files["video"], "rb") as vf, open(test_files["cover"], "rb") as cf:
        resp = requests.post(
            f"{base_url}/api/upload/video",
            headers=headers,
            data={"title": title, "description": "hide_content_test", "categoryId": "1"},
            files={
                "video": ("test_video.mp4", vf, "video/mp4"),
                "cover": ("test_cover.png", cf, "image/png"),
            },
            timeout=30,
        )
    return resp.json()


def upload_post(base_url, token, title, test_files):
    """用例内新建独立图文（封面+图），返回响应 JSON。"""
    headers = {"token": token}
    with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
        resp = requests.post(
            f"{base_url}/api/upload/post",
            headers=headers,
            data={"title": title, "description": "hide_content_test", "categoryId": "0"},
            files={
                "cover": ("test_cover.png", cf, "image/png"),
                "image": ("test_image.jpg", imf, "image/jpeg"),
            },
            timeout=30,
        )
    return resp.json()


def profile_content_ids(base_url, token, user_id):
    """GET /profile 第 1 页返回的内容 id 列表（新发布内容按 create_time DESC 必在第 1 页）。"""
    headers = {"token": token} if token else {}
    resp = requests.get(
        f"{base_url}/profile",
        params={"userId": user_id, "page": 1, "pageSize": 20},
        headers=headers,
        timeout=10,
    )
    result = resp.json()
    assert result.get("code") == 200, f"主页查询失败: {result}"
    content_page = result.get("data", {}).get("contentPage") or {}
    return [item["id"] for item in (content_page.get("list") or [])]


def cleanup_content(base_url, token_a, admin_token, cid):
    """兜底清理：已下架先管理员恢复，再由作者删除（已删则忽略）。"""
    if get_detail_result(base_url, cid, token_a).get("code") != 200:
        unhide_content(base_url, admin_token, cid)
    # 作者删除（已删/不存在则 404，忽略）
    requests.post(
        f"{base_url}/content/delete",
        params={"contentId": cid},
        headers={"token": token_a},
        timeout=10,
    )


# ---------------------------------------------------------------------------
# 一致性：管理员下架 → 前台不可见 → 恢复 → 数据完好
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestHideContent:

    def test_admin_hides_video_then_restores(self, base_url, token_a, user_a_id, admin_user, test_files):
        admin_token = admin_user["token"]
        title = f"hidcnt_video_{_unique}"
        up = upload_video(base_url, token_a, title, test_files)
        assert up.get("code") == 200, f"新建视频失败: {up}"
        cid = up["data"]["contentId"]

        before = get_detail(base_url, cid, token_a)
        media_before = {"videoUrl": before["videoUrl"], "coverUrl": before["coverUrl"]}
        counts_before = {"commentCount": before["commentCount"], "likeCount": before["likeCount"]}
        try:
            # 下架 → 前台不可见
            assert hide_content(base_url, admin_token, cid).get("code") == 200, "下架应成功"
            assert get_detail_result(base_url, cid, token_a).get("code") == 404, "下架后详情应 404"
            ids = profile_content_ids(base_url, token_a, user_a_id)
            assert cid not in ids, "下架后作者主页不应再列出"
            item = admin_list_item(base_url, admin_token, cid)
            assert item is not None, "管理端清单应包含该内容"
            assert item.get("hidden") is True, "管理端清单 hidden 应为 true"

            # 恢复 → 重新可见且数据完好
            assert unhide_content(base_url, admin_token, cid).get("code") == 200, "恢复应成功"
            after = get_detail(base_url, cid, token_a)
            assert after["videoUrl"] == media_before["videoUrl"], "视频 URL 应保持不变"
            assert after["coverUrl"] == media_before["coverUrl"], "封面 URL 应保持不变"
            assert after["commentCount"] == counts_before["commentCount"], "评论数应保持不变"
            assert after["likeCount"] == counts_before["likeCount"], "点赞数应保持不变"
            ids = profile_content_ids(base_url, token_a, user_a_id)
            assert cid in ids, "恢复后作者主页应重新列出"
            item = admin_list_item(base_url, admin_token, cid)
            assert item is not None and item.get("hidden") is False, "管理端清单 hidden 应为 false"
        finally:
            cleanup_content(base_url, token_a, admin_token, cid)

    def test_admin_hides_post_then_restores(self, base_url, token_a, user_a_id, admin_user, test_files):
        admin_token = admin_user["token"]
        title = f"hidcnt_post_{_unique}"
        up = upload_post(base_url, token_a, title, test_files)
        assert up.get("code") == 200, f"新建图文失败: {up}"
        cid = up["data"]["contentId"]

        before = get_detail(base_url, cid, token_a)
        media_before = {"coverUrl": before["coverUrl"], "imageUrls": list(before.get("imageUrls") or [])}
        counts_before = {"commentCount": before["commentCount"], "likeCount": before["likeCount"]}
        try:
            assert hide_content(base_url, admin_token, cid).get("code") == 200, "下架应成功"
            assert get_detail_result(base_url, cid, token_a).get("code") == 404, "下架后详情应 404"
            ids = profile_content_ids(base_url, token_a, user_a_id)
            assert cid not in ids, "下架后作者主页不应再列出"

            assert unhide_content(base_url, admin_token, cid).get("code") == 200, "恢复应成功"
            after = get_detail(base_url, cid, token_a)
            assert after["coverUrl"] == media_before["coverUrl"], "封面 URL 应保持不变"
            assert (after.get("imageUrls") or []) == media_before["imageUrls"], "图片 URL 列表应保持不变"
            assert after["commentCount"] == counts_before["commentCount"], "评论数应保持不变"
            assert after["likeCount"] == counts_before["likeCount"], "点赞数应保持不变"
            ids = profile_content_ids(base_url, token_a, user_a_id)
            assert cid in ids, "恢复后作者主页应重新列出"
        finally:
            cleanup_content(base_url, token_a, admin_token, cid)


# ---------------------------------------------------------------------------
# 边界（自建自清）
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestHideBoundary:

    def test_hide_without_login_401(self, base_url):
        result = hide_content(base_url, None, 1)
        assert result.get("code") == 401, f"未登录下架应 401: {result}"

    def test_hide_normal_user_403(self, base_url, token_a):
        result = hide_content(base_url, token_a, 1)
        assert result.get("code") == 403, f"非管理员下架应 403: {result}"

    def test_unhide_without_login_401(self, base_url):
        result = unhide_content(base_url, None, 1)
        assert result.get("code") == 401, f"未登录恢复应 401: {result}"

    def test_unhide_normal_user_403(self, base_url, token_a):
        result = unhide_content(base_url, token_a, 1)
        assert result.get("code") == 403, f"非管理员恢复应 403: {result}"

    def test_admin_list_without_login_401(self, base_url):
        result = admin_content_list(base_url, None)
        assert result.get("code") == 401, f"未登录查清单应 401: {result}"

    def test_admin_list_normal_user_403(self, base_url, token_a):
        result = admin_content_list(base_url, token_a)
        assert result.get("code") == 403, f"非管理员查清单应 403: {result}"

    def test_hide_missing_content_404(self, base_url, admin_user):
        result = hide_content(base_url, admin_user["token"], 999999999)
        assert result.get("code") == 404, f"不存在内容下架应 404: {result}"

    def test_unhide_missing_content_404(self, base_url, admin_user):
        result = unhide_content(base_url, admin_user["token"], 999999999)
        assert result.get("code") == 404, f"不存在内容恢复应 404: {result}"

    def test_hide_already_hidden_409(self, base_url, token_a, admin_user, test_files):
        admin_token = admin_user["token"]
        title = f"hidcnt_again_{_unique}"
        up = upload_video(base_url, token_a, title, test_files)
        assert up.get("code") == 200, f"新建视频失败: {up}"
        cid = up["data"]["contentId"]
        try:
            first = hide_content(base_url, admin_token, cid)
            assert first.get("code") == 200, f"首次下架应成功: {first}"
            second = hide_content(base_url, admin_token, cid)
            assert second.get("code") == 409, f"已下架重复下架应 409: {second}"
        finally:
            cleanup_content(base_url, token_a, admin_token, cid)

    def test_unhide_not_hidden_409(self, base_url, token_a, admin_user, test_files):
        admin_token = admin_user["token"]
        title = f"hidcnt_unh_{_unique}"
        up = upload_video(base_url, token_a, title, test_files)
        assert up.get("code") == 200, f"新建视频失败: {up}"
        cid = up["data"]["contentId"]
        try:
            result = unhide_content(base_url, admin_token, cid)
            assert result.get("code") == 409, f"未下架内容恢复应 409: {result}"
        finally:
            cleanup_content(base_url, token_a, admin_token, cid)

    def test_hide_missing_contentId_400(self, base_url, admin_user):
        resp = requests.post(
            f"{base_url}/api/admin/content/hide",
            headers={"token": admin_user["token"]},
            timeout=10,
        )
        assert resp.json().get("code") == 400, "缺少 contentId 应 400"

    def test_unhide_missing_contentId_400(self, base_url, admin_user):
        resp = requests.post(
            f"{base_url}/api/admin/content/unhide",
            headers={"token": admin_user["token"]},
            timeout=10,
        )
        assert resp.json().get("code") == 400, "缺少 contentId 应 400"

    def test_admin_list_200_shape(self, base_url, admin_user):
        body = admin_content_list(base_url, admin_user["token"])
        assert body.get("code") == 200, body
        data = body.get("data")
        assert isinstance(data, list), body
        for item in data:
            for key in ("id", "title", "type", "authorName", "hidden"):
                assert key in item, f"清单条目缺少字段 {key}: {item}"
