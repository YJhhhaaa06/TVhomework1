# -*- coding: utf-8 -*-
"""
test_content_paging.py - feed / search / profile 三域后端大分页（T19）

背景：T11-B 把三处内容列表的**前端**迁到公共 chunkedList（chunk=50，受当时各域后端口径限制），
但三域**后端**分页口径当时未动（T11 窗口拍板推后 → 立 T19）：
  - `/feed`、`/profile` 走公共 `parsePageSize(req)`：上限 50、缺省 10；
  - `/search/keywordSearch` 自理解析：无上限、缺省 12。
T19 把三域统一为「域级上限 100 + 域级信封 100」，前端只传 `page`（后端不再让前端决定要多少条）。

本文件是该口径的**端到端证据**：
  1. 缺省（不传分页参数）→ 信封 page=1 / pageSize=100（域级信封，不再是 10 / 12）；
  2. pageSize 超上限 → 100；**51 原样回显**（T19 前公共 cap 50 会截到 50）；
  3. **pageSize=1 仍原样回显** —— 小信封跨页验证能力必须保留（T19 拍板"不采纳后端硬忽略
     pageSize"：否则三域"页间不重不漏"将失去唯一低成本验证手段，见计划 3.5/S2）；
  4. `/feed`、`/search` 缺省 == 显式 `page=1&pageSize=100` **逐字节一致**（非空列表比对，
     避免空信封下该断言退化为"解析路径相同"的近乎恒真）；
  5. 非法 `pageSize`（非数字）回落域级缺省而非 500 —— T19 顺带修掉的旧 500 坑
     （`SearchController` GET 分支原手写 `Integer.parseInt`）。

数据：复用 conftest 的 user_a/user_b；feed 的非空场景用「b 关注 a」构造并在 finally 取关复原
（409 = 已关注亦可继续，仅对本次新建的做复原）。不依赖其它用例执行顺序。
"""

import json

import pytest
import requests

import conftest

# 三域域级信封/上限（T19 拍板值，须与各 Controller 的 XXX_PAGE_SIZE_MAX/DEFAULT 同步）
DOMAIN_PAGE_SIZE = 100

# 内容相关域共用的信封字段集（唯一信封 common.model.dto.PageResult）
_ENVELOPE_KEYS = {"list", "total", "page", "pageSize", "totalPages"}


def _get(base_url, path, params, token=None):
    """GET 请求并返回响应 JSON；`token=None` 时不带鉴权头（search/profile 免登录）。"""
    headers = {"token": token} if token else {}
    resp = requests.get(f"{base_url}{path}", params=params, headers=headers, timeout=10)
    return resp.json()


def _feed(base_url, token, **params):
    """GET /feed（未显式给出的分页参数不带在 URL 上）。"""
    return _get(base_url, "/feed", params, token)


def _profile(base_url, user_id, **params):
    """GET /profile?userId=..（信封在 data.contentPage）。"""
    return _get(base_url, "/profile", dict({"userId": user_id}, **params))


def _search(base_url, **params):
    """GET /search/keywordSearch（免登录）。"""
    return _get(base_url, "/search/keywordSearch", params)


def _follow(base_url, token, followed_user_id, action):
    """follow/add | follow/remove。"""
    resp = requests.post(
        f"{base_url}/follow/{action}",
        headers={"token": token, "Content-Type": "application/x-www-form-urlencoded"},
        data={"followedUserId": str(followed_user_id)},
        timeout=10,
    )
    return resp.json()


def _assert_envelope(data, *, page, page_size, label=""):
    """断言分页信封形状与 page/pageSize 归一结果。"""
    assert isinstance(data, dict), f"{label}应为分页信封对象: {data}"
    assert set(data.keys()) == _ENVELOPE_KEYS, f"{label}信封字段集不符: {data}"
    assert data["page"] == page and data["pageSize"] == page_size, \
        f"{label}信封应为 page={page} / pageSize={page_size}: {data}"


# ---------------------------------------------------------------------------
# /feed
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestFeedPaging:
    """T19：/feed 域级上限 100 + 域级信封 100（原公共 cap 50 / 缺省 10）。"""

    def test_default_envelope_is_domain_size(self, base_url, token_b):
        """缺省（不传分页参数）→ page=1 / pageSize=100。"""
        body = _feed(base_url, token_b)
        assert body.get("code") == 200, f"/feed failed: {body}"
        _assert_envelope(body["data"], page=1, page_size=DOMAIN_PAGE_SIZE, label="/feed 缺省")

    def test_page_size_capped_at_domain_max(self, base_url, token_b):
        """pageSize=999 → 域级上限 100。"""
        body = _feed(base_url, token_b, pageSize=999)
        assert body.get("code") == 200, f"/feed failed: {body}"
        _assert_envelope(body["data"], page=1, page_size=DOMAIN_PAGE_SIZE, label="/feed 超上限")

    def test_page_size_above_old_cap_echoed(self, base_url, token_b):
        """pageSize=51 原样回显（T19 前公共 cap 50 会截到 50）。"""
        body = _feed(base_url, token_b, pageSize=51)
        assert body.get("code") == 200, f"/feed failed: {body}"
        _assert_envelope(body["data"], page=1, page_size=51, label="/feed 51")

    def test_small_envelope_still_supported(self, base_url, token_b):
        """pageSize=1 仍生效——小信封跨页验证能力保留（T19 不采纳"后端硬忽略 pageSize"）。"""
        body = _feed(base_url, token_b, pageSize=1)
        assert body.get("code") == 200, f"/feed failed: {body}"
        _assert_envelope(body["data"], page=1, page_size=1, label="/feed 小信封")

    def test_default_equals_explicit_first_page(self, base_url, token_b, user_a_id):
        """缺省 == 显式 `page=1&pageSize=100` 逐字节一致（**非空列表**才有判别力）。"""
        add = _follow(base_url, token_b, user_a_id, "add")
        assert add.get("code") in (200, 409), f"follow/add failed: {add}"
        added = add.get("code") == 200
        try:
            default_body = _feed(base_url, token_b)
            explicit_body = _feed(base_url, token_b, page=1, pageSize=DOMAIN_PAGE_SIZE)
            assert default_body.get("code") == 200, f"/feed 缺省 failed: {default_body}"
            assert explicit_body.get("code") == 200, f"/feed 显式 failed: {explicit_body}"

            default_data, explicit_data = default_body["data"], explicit_body["data"]
            assert default_data["list"], \
                f"本用例需非空列表才能让逐字节比对具备判别力: {default_data}"
            assert json.dumps(default_data, sort_keys=True, ensure_ascii=False) == \
                json.dumps(explicit_data, sort_keys=True, ensure_ascii=False), \
                f"缺省与显式第一页应逐字节一致: {default_data} vs {explicit_data}"
        finally:
            if added:
                _follow(base_url, token_b, user_a_id, "remove")


# ---------------------------------------------------------------------------
# /profile（信封嵌在 ProfileVO.contentPage）
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestProfilePaging:
    """T19：/profile 域级上限 100 + 域级信封 100（原公共 cap 50 / 缺省 10）。"""

    def test_default_envelope_is_domain_size(self, base_url, user_a_id):
        body = _profile(base_url, user_a_id)
        assert body.get("code") == 200, f"/profile failed: {body}"
        _assert_envelope(body["data"]["contentPage"], page=1, page_size=DOMAIN_PAGE_SIZE,
                         label="/profile 缺省")

    def test_page_size_capped_at_domain_max(self, base_url, user_a_id):
        body = _profile(base_url, user_a_id, pageSize=999)
        assert body.get("code") == 200, f"/profile failed: {body}"
        _assert_envelope(body["data"]["contentPage"], page=1, page_size=DOMAIN_PAGE_SIZE,
                         label="/profile 超上限")

    def test_page_size_above_old_cap_echoed(self, base_url, user_a_id):
        body = _profile(base_url, user_a_id, pageSize=51)
        assert body.get("code") == 200, f"/profile failed: {body}"
        _assert_envelope(body["data"]["contentPage"], page=1, page_size=51, label="/profile 51")

    def test_small_envelope_still_supported(self, base_url, user_a_id):
        body = _profile(base_url, user_a_id, pageSize=1)
        assert body.get("code") == 200, f"/profile failed: {body}"
        _assert_envelope(body["data"]["contentPage"], page=1, page_size=1, label="/profile 小信封")

    def test_default_equals_explicit_first_page(self, base_url, user_a_id):
        """缺省 == 显式 `page=1&pageSize=100` 逐字节一致（contentPage 非空才有判别力）。"""
        default_body = _profile(base_url, user_a_id)
        explicit_body = _profile(base_url, user_a_id, page=1, pageSize=DOMAIN_PAGE_SIZE)
        assert default_body.get("code") == 200, f"/profile 缺省 failed: {default_body}"
        assert explicit_body.get("code") == 200, f"/profile 显式 failed: {explicit_body}"

        default_page = default_body["data"]["contentPage"]
        assert default_page["list"], \
            f"本用例需非空列表才能让逐字节比对具备判别力: {default_page}"
        assert json.dumps(default_page, sort_keys=True, ensure_ascii=False) == \
            json.dumps(explicit_body["data"]["contentPage"], sort_keys=True, ensure_ascii=False), \
            f"缺省与显式第一页应逐字节一致: {default_page}"


# ---------------------------------------------------------------------------
# /search/keywordSearch（T19 前唯一未走公共归一的入口）
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestSearchPaging:
    """T19：/search/keywordSearch 域级上限 100 + 域级信封 100（原"无上限 / 缺省 12"）。"""

    KEYWORD = conftest.SEED_COMMENT_CONTENT_PREFIX

    def test_default_envelope_is_domain_size(self, base_url):
        body = _search(base_url, keyword=self.KEYWORD)
        assert body.get("code") == 200, f"search failed: {body}"
        _assert_envelope(body["data"], page=1, page_size=DOMAIN_PAGE_SIZE, label="search 缺省")

    def test_page_size_capped_at_domain_max(self, base_url):
        """T19 前该域 pageSize 无上限（999 会原样进 SQL LIMIT）。"""
        body = _search(base_url, keyword=self.KEYWORD, pageSize=999)
        assert body.get("code") == 200, f"search failed: {body}"
        _assert_envelope(body["data"], page=1, page_size=DOMAIN_PAGE_SIZE, label="search 超上限")

    def test_page_size_above_old_cap_echoed(self, base_url):
        body = _search(base_url, keyword=self.KEYWORD, pageSize=51)
        assert body.get("code") == 200, f"search failed: {body}"
        _assert_envelope(body["data"], page=1, page_size=51, label="search 51")

    def test_small_envelope_still_supported(self, base_url):
        body = _search(base_url, keyword=self.KEYWORD, pageSize=1)
        assert body.get("code") == 200, f"search failed: {body}"
        _assert_envelope(body["data"], page=1, page_size=1, label="search 小信封")

    def test_illegal_page_size_falls_back_to_domain_default(self, base_url):
        """非法 pageSize（非数字）回落域级缺省 100，而不是 500（T19 顺带修掉的旧 500 坑）。"""
        body = _search(base_url, keyword=self.KEYWORD, pageSize="abc")
        assert body.get("code") == 200, f"非法 pageSize 不应 500: {body}"
        _assert_envelope(body["data"], page=1, page_size=DOMAIN_PAGE_SIZE, label="search 非法值")

    def test_default_equals_explicit_first_page(self, base_url):
        """缺省 == 显式 `page=1&pageSize=100` 逐字节一致（非空列表才有判别力）。"""
        default_body = _search(base_url, keyword=self.KEYWORD)
        explicit_body = _search(base_url, keyword=self.KEYWORD, page=1, pageSize=DOMAIN_PAGE_SIZE)
        assert default_body.get("code") == 200, f"search 缺省 failed: {default_body}"
        assert explicit_body.get("code") == 200, f"search 显式 failed: {explicit_body}"

        default_data = default_body["data"]
        assert default_data["list"], \
            f"本用例需非空列表才能让逐字节比对具备判别力: {default_data}"
        assert json.dumps(default_data, sort_keys=True, ensure_ascii=False) == \
            json.dumps(explicit_body["data"], sort_keys=True, ensure_ascii=False), \
            f"缺省与显式第一页应逐字节一致: {default_data}"
