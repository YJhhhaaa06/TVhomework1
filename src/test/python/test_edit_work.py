# -*- coding: utf-8 -*-
"""
test_edit_work.py - 阶段三「编辑作品」验收测试（换源 / 单图删除 / 文案编辑）。

覆盖：
1. 换源：作者替换视频封面/视频文件/图文单张图片；详情展示新源；新文件落盘、旧文件删除
2. 换源边界：非作者 403、未登录 401、内容不存在 404、type 非法 400、媒体行不存在 404、文件类型不符 400
3. 文案编辑：作者改标题/简介后详情更新，测后复原；非作者 403、未登录 401、内容不存在 404、
   空标题 400、标题超长 400、简介超长 400
4. 单图删除：作者删除多图中的一张（sort 重排，其余图不受影响）；非作者 403、未登录 401、
   媒体行不存在 404、删视频封面（type!=2）400（后端守卫）

说明：
- 修改类（换源/删图）用例一律「用例内自建内容 + finally 清理」，绝不写共享 fixture
  （sample_content_id/sample_post_content_id），用例运行不依赖任何文件顺序（T4，2026-09-05）。
- 文案编辑用例对共享 fixture 改文案后 finally 复原，净零残留；边界用例只读共享 fixture
  （403/401/404/400 路径不改写数据）。
- 兼容非根上下文部署：详情/上传 URL 带 contextPath 前缀时，resolve_upload_path 剥离前缀后再定位本地文件。
"""

import os
import uuid
from contextlib import ExitStack

import pytest
import requests

_unique = uuid.uuid4().hex[:8]

STONE_DIR = os.environ.get("TV_STONE_DIR", "D:/data/projects/VideoPlatform/stone")

_MIME = {
    "mp4": "video/mp4",
    "png": "image/png",
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
}


# ---------------------------------------------------------------------------
# 辅助
# ---------------------------------------------------------------------------

def get_detail(base_url, content_id, token=None):
    headers = {"token": token} if token else {}
    resp = requests.get(
        f"{base_url}/search/IdSearch",
        params={"contentId": content_id},
        headers=headers,
        timeout=10,
    )
    result = resp.json()
    assert result.get("code") == 200, f"详情查询失败: {result}"
    return result["data"]


def replace_media(base_url, token, content_id, mtype, sort, file_path):
    headers = {"token": token} if token else {}
    ext = os.path.splitext(file_path)[1].lower().lstrip(".")
    with open(file_path, "rb") as f:
        resp = requests.post(
            f"{base_url}/api/upload/replace",
            params={"contentId": content_id, "type": mtype, "sort": sort},
            headers=headers,
            files={
                "file": (
                    os.path.basename(file_path),
                    f,
                    _MIME.get(ext, "application/octet-stream"),
                )
            },
            timeout=30,
        )
    return resp.json()


def delete_media(base_url, token, content_id, mtype, sort):
    headers = {"token": token} if token else {}
    resp = requests.post(
        f"{base_url}/content/mediaDelete",
        params={"contentId": content_id, "type": mtype, "sort": sort},
        headers=headers,
        timeout=10,
    )
    return resp.json()


def update_content_info(base_url, token, content_id, title, desc):
    headers = {"token": token} if token else {}
    resp = requests.post(
        f"{base_url}/content/update",
        data={"contentId": content_id, "title": title, "description": desc},
        headers=headers,
        timeout=10,
    )
    return resp.json()


def upload_post(base_url, token, title, image_paths):
    """用例内新建图文（可多图），返回 contentId。"""
    headers = {"token": token}
    with ExitStack() as stack:
        files = []
        for p in image_paths:
            fh = stack.enter_context(open(p, "rb"))
            files.append(("image", (os.path.basename(p), fh, "image/jpeg")))
        resp = requests.post(
            f"{base_url}/api/upload/post",
            headers=headers,
            data={"title": title, "description": "edit_work_test", "categoryId": "0"},
            files=files,
            timeout=30,
        )
    return resp.json()


def upload_video(base_url, token, title, test_files):
    """用例内新建独立视频（封面+视频），返回响应 JSON。"""
    headers = {"token": token}
    with open(test_files["video"], "rb") as vf, open(test_files["cover"], "rb") as cf:
        resp = requests.post(
            f"{base_url}/api/upload/video",
            headers=headers,
            data={"title": title, "description": "edit_work_test", "categoryId": "1"},
            files={
                "video": ("test_video.mp4", vf, "video/mp4"),
                "cover": ("test_cover.png", cf, "image/png"),
            },
            timeout=30,
        )
    return resp.json()


def delete_content(base_url, token, content_id):
    """删除内容（finally 清理用）。已删/不存在返回 404，不抛错。"""
    headers = {"token": token} if token else {}
    resp = requests.post(
        f"{base_url}/content/delete",
        params={"contentId": content_id},
        headers=headers,
        timeout=10,
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


# ---------------------------------------------------------------------------
# 换源（用例内自建内容 + finally 清理，不写共享 fixture）
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestReplaceMedia:

    def test_replace_cover_updates_detail_and_old_file_removed(
        self, base_url, token_a, test_files
    ):
        # 用例内自建视频，不写共享 fixture；finally 删内容自清
        title = f"editrep_cover_{_unique}"
        up = upload_video(base_url, token_a, title, test_files)
        assert up.get("code") == 200, f"新建视频失败: {up}"
        cid = up["data"]["contentId"]
        try:
            before = get_detail(base_url, cid, token_a)
            old_cover = before["coverUrl"]
            old_path = resolve_upload_path(old_cover)
            assert old_path and os.path.isfile(old_path), f"旧封面文件应存在: {old_path}"

            result = replace_media(base_url, token_a, cid, 3, 1, test_files["cover"])
            assert result.get("code") == 200, f"替换封面失败: {result}"
            new_url = result["data"]["url"]
            assert new_url != old_cover, "封面 URL 应变化"

            detail = get_detail(base_url, cid, token_a)
            # 详情 URL 带 contextPath 前缀，用 endswith 兼容非根部署
            assert detail["coverUrl"].endswith(new_url), f"详情封面应为新 URL: {detail['coverUrl']}"
            new_path = resolve_upload_path(new_url)
            assert new_path and os.path.isfile(new_path), f"新封面文件应落盘: {new_path}"
            assert not os.path.exists(old_path), f"旧封面文件应已删除: {old_path}"
        finally:
            delete_content(base_url, token_a, cid)

    def test_replace_video_updates_detail(
        self, base_url, token_a, test_files
    ):
        title = f"editrep_video_{_unique}"
        up = upload_video(base_url, token_a, title, test_files)
        assert up.get("code") == 200, f"新建视频失败: {up}"
        cid = up["data"]["contentId"]
        try:
            old_video = get_detail(base_url, cid, token_a)["videoUrl"]

            result = replace_media(base_url, token_a, cid, 1, 1, test_files["video"])
            assert result.get("code") == 200, f"替换视频失败: {result}"

            detail = get_detail(base_url, cid, token_a)
            assert detail["videoUrl"] != old_video, "视频 URL 应变化"
            new_path = resolve_upload_path(detail["videoUrl"])
            assert new_path and os.path.isfile(new_path), "新视频文件应落盘"
        finally:
            delete_content(base_url, token_a, cid)

    def test_replace_single_image_in_post(
        self, base_url, token_a, test_files
    ):
        # 用例内自建图文（1 张图即可），finally 删内容自清
        title = f"editrep_post_{_unique}"
        up = upload_post(base_url, token_a, title, [test_files["image"]])
        assert up.get("code") == 200, f"新建图文失败: {up}"
        cid = up["data"]["contentId"]
        try:
            old_images = get_detail(base_url, cid, token_a).get("imageUrls") or []
            assert old_images, "图文应至少有一张图"
            old_first = old_images[0]

            result = replace_media(base_url, token_a, cid, 2, 1, test_files["image"])
            assert result.get("code") == 200, f"替换单图失败: {result}"

            detail = get_detail(base_url, cid, token_a)
            images = detail.get("imageUrls") or []
            assert len(images) == len(old_images), "替换不应改变图片数量"
            assert images[0] != old_first, "第 1 张图 URL 应变化"
        finally:
            delete_content(base_url, token_a, cid)


# ---------------------------------------------------------------------------
# 换源边界（只读不污染）
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestReplaceBoundary:

    def test_replace_non_author_403(
        self, base_url, token_a, token_b, sample_content_id, test_files
    ):
        cid = sample_content_id
        before = get_detail(base_url, cid, token_a)["coverUrl"]

        result = replace_media(base_url, token_b, cid, 3, 1, test_files["cover"])
        assert result.get("code") == 403, f"非作者替换应 403: {result}"
        assert get_detail(base_url, cid, token_a)["coverUrl"] == before, "封面不应被改动"

    def test_replace_requires_login_401(self, base_url, sample_content_id, test_files):
        result = replace_media(base_url, None, sample_content_id, 3, 1, test_files["cover"])
        assert result.get("code") == 401, f"未登录替换应 401: {result}"

    def test_replace_missing_content_404(self, base_url, token_a, test_files):
        result = replace_media(base_url, token_a, 999999999, 3, 1, test_files["cover"])
        assert result.get("code") == 404, f"不存在内容替换应 404: {result}"

    def test_replace_invalid_type_400(self, base_url, token_a, sample_content_id, test_files):
        result = replace_media(base_url, token_a, sample_content_id, 0, 1, test_files["cover"])
        assert result.get("code") == 400, f"type=0 应 400: {result}"

    def test_replace_missing_media_row_404(
        self, base_url, token_a, sample_content_id, test_files
    ):
        # 视频内容上没有 type=2 的媒体行
        result = replace_media(base_url, token_a, sample_content_id, 2, 99, test_files["image"])
        assert result.get("code") == 404, f"媒体行不存在应 404: {result}"

    def test_replace_wrong_file_type_400(
        self, base_url, token_a, sample_content_id, test_files
    ):
        # 向视频位传 .jpg 图片 -> 后缀校验失败
        result = replace_media(base_url, token_a, sample_content_id, 1, 1, test_files["image"])
        assert result.get("code") == 400, f"文件类型不符应 400: {result}"


# ---------------------------------------------------------------------------
# 文案编辑（consistency 用例测后复原；boundary 只读）
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestEditText:

    def test_author_edits_title_and_desc_then_restores(
        self, base_url, token_a, sample_content_id
    ):
        cid = sample_content_id
        original = get_detail(base_url, cid, token_a)
        orig_title = original["title"]
        orig_desc = original["description"]
        new_title = f"编辑标题_{_unique}"
        new_desc = f"编辑简介_{_unique}"
        try:
            result = update_content_info(base_url, token_a, cid, new_title, new_desc)
            assert result.get("code") == 200, f"改文案失败: {result}"
            detail = get_detail(base_url, cid, token_a)
            assert detail["title"] == new_title, "标题应更新"
            assert detail["description"] == new_desc, "简介应更新"
        finally:
            restore = update_content_info(base_url, token_a, cid, orig_title, orig_desc)
            assert restore.get("code") == 200, f"复原文案失败: {restore}"
            restored = get_detail(base_url, cid, token_a)
            assert restored["title"] == orig_title, "标题应复原"
            assert restored["description"] == orig_desc, "简介应复原"


@pytest.mark.boundary
class TestEditTextBoundary:

    def test_edit_text_non_author_403(
        self, base_url, token_a, token_b, sample_content_id
    ):
        original = get_detail(base_url, sample_content_id, token_a)
        result = update_content_info(
            base_url, token_b, sample_content_id, "越权标题", "越权简介"
        )
        assert result.get("code") == 403, f"非作者改文案应 403: {result}"
        detail = get_detail(base_url, sample_content_id, token_a)
        assert detail["title"] == original["title"], "标题不应被改动"

    def test_edit_text_requires_login_401(self, base_url, sample_content_id):
        result = update_content_info(base_url, None, sample_content_id, "t", "d")
        assert result.get("code") == 401, f"未登录改文案应 401: {result}"

    def test_edit_text_missing_content_404(self, base_url, token_a):
        result = update_content_info(base_url, token_a, 999999999, "t", "d")
        assert result.get("code") == 404, f"不存在内容应 404: {result}"

    def test_edit_text_blank_title_400(self, base_url, token_a, sample_content_id):
        result = update_content_info(base_url, token_a, sample_content_id, "   ", "d")
        assert result.get("code") == 400, f"空标题应 400: {result}"

    def test_edit_text_too_long_title_400(self, base_url, token_a, sample_content_id):
        result = update_content_info(base_url, token_a, sample_content_id, "字" * 51, "d")
        assert result.get("code") == 400, f"标题超长应 400: {result}"

    def test_edit_text_too_long_desc_400(self, base_url, token_a, sample_content_id):
        result = update_content_info(base_url, token_a, sample_content_id, "t", "字" * 5001)
        assert result.get("code") == 400, f"简介超长应 400: {result}"


# ---------------------------------------------------------------------------
# 单图删除（consistency 用用例内新建图文；boundary 只读/不破坏）
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestDeleteImage:

    def test_author_deletes_images(self, base_url, token_a, test_files):
        # 用例内新建图文（2 张图），避免写共享 fixture；finally 删内容自清
        title = f"editdel_{_unique}"
        up = upload_post(base_url, token_a, title, [test_files["image"], test_files["image"]])
        assert up.get("code") == 200, f"新建图文失败: {up}"
        cid = up["data"]["contentId"]

        try:
            detail = get_detail(base_url, cid, token_a)
            images = detail.get("imageUrls") or []
            assert len(images) == 2, "新图文应有 2 张图"
            second = images[1]

            # 删第 1 张 -> 剩原第 2 张（sort 重排后仍为第 1 位）
            result = delete_media(base_url, token_a, cid, 2, 1)
            assert result.get("code") == 200, f"删除图片失败: {result}"
            detail = get_detail(base_url, cid, token_a)
            images = detail.get("imageUrls") or []
            assert len(images) == 1, "删 1 张后应剩 1 张"
            assert images[0] == second, "剩余应为原第 2 张"

            # 删剩余
            result = delete_media(base_url, token_a, cid, 2, 1)
            assert result.get("code") == 200, f"删除最后一张失败: {result}"
            detail = get_detail(base_url, cid, token_a)
            assert not (detail.get("imageUrls") or []), "删除全部图片后 imageUrls 应为空"
        finally:
            delete_content(base_url, token_a, cid)


@pytest.mark.boundary
class TestDeleteImageBoundary:

    def test_delete_non_author_403(self, base_url, token_b, sample_post_content_id):
        result = delete_media(base_url, token_b, sample_post_content_id, 2, 1)
        assert result.get("code") == 403, f"非作者删图应 403: {result}"

    def test_delete_requires_login_401(self, base_url, sample_post_content_id):
        result = delete_media(base_url, None, sample_post_content_id, 2, 1)
        assert result.get("code") == 401, f"未登录删图应 401: {result}"

    def test_delete_missing_media_row_404(self, base_url, token_a, sample_post_content_id):
        result = delete_media(base_url, token_a, sample_post_content_id, 2, 99)
        assert result.get("code") == 404, f"媒体行不存在应 404: {result}"

    def test_delete_video_cover_400(self, base_url, token_a, sample_content_id):
        # 后端守卫：仅支持删除图片（type==2），删视频封面应 400
        result = delete_media(base_url, token_a, sample_content_id, 3, 1)
        assert result.get("code") == 400, f"删视频封面应 400: {result}"
