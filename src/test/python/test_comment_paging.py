# -*- coding: utf-8 -*-
"""
test_comment_paging.py - 评论列表主楼分页（T8 / N11b）验收测试。

背景：
    `/comment/show` 原为整树全量返回（主楼 + 每条的楼中楼），评论多时响应体与成本随总量放大。
    T8 落地：**主楼分页 + 楼中楼整树**（每页 N 条主楼，每条主楼携带其完整楼中楼），
    缓存装载结构不变（仍是整树 JSON，切片发生在展示层）。

契约（缺省兼容，硬要求）：
- **不传** page/pageSize → data 仍为全量数组，与改造前逐字节一致（既有用例零破坏）；
- **传任一** → data = {list,total,page,pageSize,totalPages}，`total` = **主楼条数**；
- 分页解析复用公共 `BaseServletUtil.parsePage/parsePageSize`（默认 1/10、上限 50）。

覆盖：
1. 带分页参数 → 信封字段齐全，total 为主楼数
2. 不传参数 → 仍是全量数组（缺省兼容回归）
3. 页间主楼不重不漏、顺序与全量一致（comment_id 升序）
4. 半参数（只传 page 或只传 pageSize）同样视为分页请求
5. 越界页 → 空列表但保留真实 total
6. pageSize 超上限 → 复用公共 cap 50
7. 楼中楼随主楼整体返回、不被切

说明：
- 本文件自建一条专用内容（module 级 fixture）并只在其上发评论，使 total 完全可控；
  测试库在 pytest 结束会清理，无需逐条删除。
- conftest.py 的既有 fixture 未做任何改动。
"""

import uuid

import pytest
import requests

_unique = str(uuid.uuid4().hex[:8])

MAIN_COUNT = 7        # 专用内容上的主楼条数（total 的期望值）
ROOT_REPLY_COUNT = 2  # 第 1 条主楼下的楼中楼条数


# ---------------------------------------------------------------------------
# 评论操作辅助
# ---------------------------------------------------------------------------

def show(base_url, content_id, token=None, **params):
    """调 /comment/show；返回原始 data（可能是数组或分页信封），由调用方断言类型。"""
    headers = {"token": token} if token else {}
    query = {"contentId": content_id}
    query.update(params)
    resp = requests.get(f"{base_url}/comment/show", params=query, headers=headers, timeout=10)
    result = resp.json()
    assert result.get("code") == 200, f"查询评论失败: {result}"
    return result.get("data")


def add_comment(base_url, token, content_id, message, parent_id=None):
    body = {"contentId": content_id, "message": message}
    if parent_id is not None:
        body["parentId"] = parent_id
    resp = requests.post(
        f"{base_url}/comment/add",
        headers={"token": token, "Content-Type": "application/json"},
        json=body,
        timeout=10,
    )
    result = resp.json()
    assert result.get("code") == 200, f"添加评论失败: {result}"
    return result


def root_ids(comments):
    return [c["commentId"] for c in comments]


# ---------------------------------------------------------------------------
# 夹具：专用内容 + 可控评论
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def paging_content_id(base_url, token_a, test_files):
    """上传一条专用视频（本文件独占），使评论 total 完全可控。"""
    with open(test_files["video"], "rb") as vf, open(test_files["cover"], "rb") as cf:
        resp = requests.post(
            f"{base_url}/api/upload/video",
            headers={"token": token_a},
            data={
                "title": f"pytest_comment_paging_{_unique}",
                "description": "Auto-generated content for comment paging tests",
                "categoryId": "1",
            },
            files={
                "video": ("test_video.mp4", vf, "video/mp4"),
                "cover": ("test_cover.png", cf, "image/png"),
            },
            timeout=30,
        )
    result = resp.json()
    assert result.get("code") == 200, f"上传失败: {result}"
    return result["data"]["contentId"]


@pytest.fixture(scope="module")
def paging_seed(base_url, token_a, paging_content_id):
    """造 MAIN_COUNT 条主楼（第 1 条带 ROOT_REPLY_COUNT 条楼中楼）。

    返回 (contentId, 主楼 id 列表)。主楼 id 按 comment_id 升序 = 创建顺序。
    """
    cid = paging_content_id
    for i in range(MAIN_COUNT):
        add_comment(base_url, token_a, cid, f"pg_main_{i}_{_unique}")

    # 缺省路径（不传分页参数）= 全量数组：既是夹具取 id 的手段，也顺带锚定缺省契约
    mains = show(base_url, cid, token_a)
    assert isinstance(mains, list), f"缺省应返回全量数组，实际: {type(mains)}"
    assert len(mains) == MAIN_COUNT, f"专用内容主楼数应为 {MAIN_COUNT}，实际 {len(mains)}"

    ids = root_ids(mains)
    for i in range(ROOT_REPLY_COUNT):
        add_comment(base_url, token_a, cid, f"pg_reply_{i}_{_unique}", parent_id=ids[0])
    return cid, ids


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestCommentPaging:

    def test_paged_returns_envelope_with_root_total(self, base_url, paging_seed):
        cid, main_ids = paging_seed
        data = show(base_url, cid, page=1, pageSize=3)

        assert isinstance(data, dict), "带分页参数应返回信封对象"
        assert data["total"] == MAIN_COUNT, "total 应为主楼条数"
        assert data["page"] == 1
        assert data["pageSize"] == 3
        assert data["totalPages"] == 3
        assert root_ids(data["list"]) == main_ids[:3]

    def test_default_without_params_still_returns_full_array(self, base_url, paging_seed):
        cid, main_ids = paging_seed
        data = show(base_url, cid)

        assert isinstance(data, list), "不传分页参数应保持全量数组（缺省兼容）"
        assert root_ids(data) == main_ids

    def test_pages_cover_all_roots_without_overlap(self, base_url, paging_seed):
        cid, main_ids = paging_seed
        collected = []
        for page in (1, 2, 3):
            collected += root_ids(show(base_url, cid, page=page, pageSize=3)["list"])

        assert collected == main_ids, "页间主楼不重不漏、顺序与全量一致"

    def test_half_param_counts_as_paging_request(self, base_url, paging_seed):
        cid, main_ids = paging_seed

        by_size = show(base_url, cid, pageSize=3)           # 只传 pageSize
        assert isinstance(by_size, dict), "只传 pageSize 也算分页请求"
        assert by_size["page"] == 1
        assert root_ids(by_size["list"]) == main_ids[:3]

        by_page = show(base_url, cid, page=1)               # 只传 page（pageSize 缺省 10）
        assert isinstance(by_page, dict), "只传 page 也算分页请求"
        assert by_page["pageSize"] == 10
        assert root_ids(by_page["list"]) == main_ids

        beyond = show(base_url, cid, page=2)                # 只传 page 且越界
        assert beyond["list"] == []
        assert beyond["total"] == MAIN_COUNT

    def test_out_of_range_page_keeps_total(self, base_url, paging_seed):
        cid, _ = paging_seed
        data = show(base_url, cid, page=99, pageSize=3)

        assert data["list"] == [], "越界页返回空列表"
        assert data["total"] == MAIN_COUNT, "越界页仍保留真实 total"
        assert data["totalPages"] == 3

    def test_page_size_capped_at_50(self, base_url, paging_seed):
        cid, main_ids = paging_seed
        data = show(base_url, cid, page=1, pageSize=999)

        assert data["pageSize"] == 50, "pageSize 上限复用公共解析（cap 50）"
        assert root_ids(data["list"]) == main_ids

    def test_replies_stay_whole_with_root(self, base_url, paging_seed):
        cid, main_ids = paging_seed
        data = show(base_url, cid, page=1, pageSize=1)

        assert len(data["list"]) == 1
        root = data["list"][0]
        assert root["commentId"] == main_ids[0]
        assert len(root.get("children") or []) == ROOT_REPLY_COUNT, \
            "楼中楼随主楼整体返回、不被切"
