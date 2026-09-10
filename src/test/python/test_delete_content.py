# -*- coding: utf-8 -*-
"""
test_delete_content.py - 阶段四 A1 作者删除作品 验收测试。

覆盖：
1. 一致性：作者删视频/图文后详情 404、作者主页不再列出、物理文件删除
2. 边界：非作者 403、未登录 401、内容不存在 404、已删除再删 404、缺 contentId 400

说明：
- 复用 conftest fixtures：token_a/token_b/test_files/user_a_id。
- 删除是破坏性操作，一律「用例内新建内容（唯一标题含 uuid）」再删，绝不触碰共享
  fixture（sample_content_id/sample_post_content_id）——本文件（字母序排在
  test_consistency 之后、test_edit_work 之前）若删了共享 fixture 会破坏后续文件。
- boundary 中新建但未删的内容在 finally 兜底删除，避免污染后续测试。
- 物理文件断言沿用 test_edit_work 的 resolve_upload_path 约定（STONE_DIR + 剥离 contextPath 前缀）。
- 首页/搜索/关注流不可见由 DAO is_deleted=0 过滤 + removeContent 缓存剔除保证（代码层），
  首页随机排序/全文索引分词不稳定，不在本文件断言；级联（评论/点赞/媒体记录）由 JUnit verify 覆盖。
"""

import os
import uuid

import pytest
import requests

from conftest import STONE_DIR  # 与 conftest 唯一持有默认值（N6/T2），严禁各自硬编码

_unique = uuid.uuid4().hex[:8]


# ---------------------------------------------------------------------------
# 辅助
# ---------------------------------------------------------------------------

def delete_content(base_url, token, content_id):
    headers = {"token": token} if token else {}
    resp = requests.post(
        f"{base_url}/content/delete",
        params={"contentId": content_id},
        headers=headers,
        timeout=10,
    )
    return resp.json()


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
            data={"title": title, "description": "delete_content_test", "categoryId": "1"},
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
            data={"title": title, "description": "delete_content_test", "categoryId": "0"},
            files={
                "cover": ("test_cover.png", cf, "image/png"),
                "image": ("test_image.jpg", imf, "image/jpeg"),
            },
            timeout=30,
        )
    return resp.json()


def resolve_upload_path(url):
    """/{contextPath}/upload/{dir}/{file} -> STONE_DIR/{dir}/{file}（剥离 contextPath 前缀，兼容非根部署）"""
    if not url or "/upload/" not in url:
        return None
    rel = url.split("/upload/", 1)[1]
    parts = rel.split("/")
    if len(parts) != 2 or not parts[0] or not parts[1]:
        return None
    return os.path.join(STONE_DIR, parts[0], parts[1])


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


# ---------------------------------------------------------------------------
# 一致性：作者删除自己作品（用例内新建内容）
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestDeleteContent:

    def test_author_deletes_video_content(self, base_url, token_a, user_a_id, test_files):
        title = f"delcnt_video_{_unique}"
        up = upload_video(base_url, token_a, title, test_files)
        assert up.get("code") == 200, f"新建视频失败: {up}"
        cid = up["data"]["contentId"]

        detail = get_detail(base_url, cid, token_a)
        media_urls = [detail["videoUrl"], detail["coverUrl"]]
        try:
            result = delete_content(base_url, token_a, cid)
            assert result.get("code") == 200, f"删除失败: {result}"

            assert get_detail_result(base_url, cid, token_a).get("code") == 404, "删除后详情应 404"
            ids = profile_content_ids(base_url, token_a, user_a_id)
            assert ids, "作者主页第 1 页不应为空"
            assert cid not in ids, "作者主页不应再列出已删内容"
            for url in media_urls:
                path = resolve_upload_path(url)
                if path:
                    assert not os.path.exists(path), f"物理文件应已删除: {path}"
        finally:
            # 兜底：删除动作若失败则补删，已删则 404 忽略
            delete_content(base_url, token_a, cid)

    def test_author_deletes_post_content(self, base_url, token_a, user_a_id, test_files):
        title = f"delcnt_post_{_unique}"
        up = upload_post(base_url, token_a, title, test_files)
        assert up.get("code") == 200, f"新建图文失败: {up}"
        cid = up["data"]["contentId"]

        detail = get_detail(base_url, cid, token_a)
        media_urls = [detail["coverUrl"]] + (detail.get("imageUrls") or [])
        try:
            result = delete_content(base_url, token_a, cid)
            assert result.get("code") == 200, f"删除失败: {result}"

            assert get_detail_result(base_url, cid, token_a).get("code") == 404, "删除后详情应 404"
            ids = profile_content_ids(base_url, token_a, user_a_id)
            assert ids, "作者主页第 1 页不应为空"
            assert cid not in ids, "作者主页不应再列出已删内容"
            for url in media_urls:
                path = resolve_upload_path(url)
                if path:
                    assert not os.path.exists(path), f"物理文件应已删除: {path}"
        finally:
            # 兜底：删除动作若失败则补删，已删则 404 忽略
            delete_content(base_url, token_a, cid)


# ---------------------------------------------------------------------------
# 边界（只读 / 自建自清）
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestDeleteBoundary:

    def test_non_author_cannot_delete(self, base_url, token_a, token_b, test_files):
        title = f"delcnt_na_{_unique}"
        up = upload_video(base_url, token_a, title, test_files)
        assert up.get("code") == 200, f"新建视频失败: {up}"
        cid = up["data"]["contentId"]
        try:
            result = delete_content(base_url, token_b, cid)
            assert result.get("code") == 403, f"非作者删除应 403: {result}"
            assert get_detail_result(base_url, cid, token_a).get("code") == 200, "内容应仍存在"
        finally:
            delete_content(base_url, token_a, cid)

    def test_delete_requires_login(self, base_url, token_a, test_files):
        title = f"delcnt_401_{_unique}"
        up = upload_video(base_url, token_a, title, test_files)
        assert up.get("code") == 200, f"新建视频失败: {up}"
        cid = up["data"]["contentId"]
        try:
            result = delete_content(base_url, None, cid)
            assert result.get("code") == 401, f"未登录删除应 401: {result}"
        finally:
            delete_content(base_url, token_a, cid)

    def test_delete_missing_content_404(self, base_url, token_a):
        result = delete_content(base_url, token_a, 999999999)
        assert result.get("code") == 404, f"不存在内容删除应 404: {result}"

    def test_delete_already_deleted_404(self, base_url, token_a, test_files):
        title = f"delcnt_again_{_unique}"
        up = upload_video(base_url, token_a, title, test_files)
        assert up.get("code") == 200, f"新建视频失败: {up}"
        cid = up["data"]["contentId"]

        first = delete_content(base_url, token_a, cid)
        assert first.get("code") == 200, f"首次删除应成功: {first}"
        second = delete_content(base_url, token_a, cid)
        assert second.get("code") == 404, f"已删除再删应 404: {second}"

    def test_delete_missing_contentId_400(self, base_url, token_a):
        resp = requests.post(
            f"{base_url}/content/delete",
            headers={"token": token_a},
            timeout=10,
        )
        assert resp.json().get("code") == 400, "缺少 contentId 应 400"
