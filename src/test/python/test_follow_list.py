# -*- coding: utf-8 -*-
"""
test_follow_list.py - 关注/粉丝列表端到端测试（T1，方向 A 补测试缺口）

覆盖两个此前零 pytest 覆盖的读接口：
  GET /follow/following?userId=<id>   关注列表（userId 的关注对象）
  GET /follow/followers?userId=<id>   粉丝列表（userId 的粉丝）

每接口三态：正常返回列表 / 空列表（空数组）/ 未登录 401；
另含健壮性用例：缺 userId 参数 -> 400（parseUserId 抛 ParamException）。
数据流复用 conftest 的 user_a/user_b（b 关注 a），自建自清（try/finally 恢复
"未关注"初态，保证 E-08「首次 follow 必 200」不被动摇），不改任何被测业务代码。

守卫说明：AuthFilter 对 /follow 前缀匹配需登录（与 BUSINESS_FLOW 7.2 旧表不一致，
已按代码为准修正文档），故两读接口无 token 必 401。

T7（第六期，A1 有序缓存 + B2 分页信封）追加：分页用例类见文件末尾
`TestFollowListPagination`——带 page/pageSize 返回信封、页间不重不漏、顺序稳定（升序）、
参数归一（page<1→1、pageSize 上限 50）、越界页空数组保留 total、**缺省不传参数仍返回数组**。
"""

import uuid

import pytest
import requests

import conftest
from conftest import USER_A, USER_B


def _follow_add_remove(base_url, token, followed_user_id, action):
    """执行一次关注/取关：action='add'/'remove'。返回响应 JSON。"""
    resp = requests.post(
        f"{base_url}/follow/{action}",
        headers={"token": token, "Content-Type": "application/x-www-form-urlencoded"},
        data={"followedUserId": str(followed_user_id)},
        timeout=10,
    )
    return resp.json()


@pytest.mark.boundary
class TestFollowFollowing:

    def test_following_requires_login(self, base_url, user_a):
        """无 token 访问 /follow/following -> code=401。"""
        resp = requests.get(
            f"{base_url}/follow/following",
            params={"userId": user_a["id"]},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 401, \
            f"Access /follow/following without token should return 401, got {body.get('code')}: {body}"

    def test_following_empty_list(self, base_url, token_a, user_a_id):
        """user_a 未关注任何人 -> data == []（空数组，FollowService 返回 emptyList）。"""
        resp = requests.get(
            f"{base_url}/follow/following",
            params={"userId": user_a_id},
            headers={"token": token_a},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"follow/following failed: {body}"
        assert body.get("data") == [], f"Expected empty list, got: {body.get('data')}"

    def test_following_returns_list(self, base_url, token_b, user_a_id, user_b_id):
        """b 关注 a 后查 b 的关注列表：含 a 条目，username 匹配、isFollowed=true（b 已关注 a）、isSelf=false。"""
        add_body = _follow_add_remove(base_url, token_b, user_a_id, "add")
        try:
            assert add_body.get("code") == 200, f"follow/add failed: {add_body}"

            resp = requests.get(
                f"{base_url}/follow/following",
                params={"userId": user_b_id},
                headers={"token": token_b},
                timeout=10,
            )
            body = resp.json()
            assert body.get("code") == 200, f"follow/following failed: {body}"
            data = body.get("data") or []
            matches = [u for u in data if u.get("userId") == user_a_id]
            assert len(matches) == 1, \
                f"Following list should contain userA exactly once: {data}"
            item = matches[0]
            assert item.get("username") == USER_A["username"], f"Username mismatch: {item}"
            assert item.get("isFollowed") is True, f"isFollowed should be True: {item}"
            assert item.get("isSelf") is False, f"isSelf should be False: {item}"
        finally:
            _follow_add_remove(base_url, token_b, user_a_id, "remove")

    def test_following_missing_user_id(self, base_url, token_a):
        """缺 userId 参数（带 token）-> code=400。"""
        resp = requests.get(
            f"{base_url}/follow/following",
            headers={"token": token_a},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 400, \
            f"Missing userId should return 400, got {body.get('code')}: {body}"


@pytest.mark.boundary
class TestFollowFollowers:

    def test_followers_requires_login(self, base_url, user_a):
        """无 token 访问 /follow/followers -> code=401。"""
        resp = requests.get(
            f"{base_url}/follow/followers",
            params={"userId": user_a["id"]},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 401, \
            f"Access /follow/followers without token should return 401, got {body.get('code')}: {body}"

    def test_followers_empty_list(self, base_url, token_a, user_a_id):
        """user_a 无粉丝 -> data == []（空数组）。"""
        resp = requests.get(
            f"{base_url}/follow/followers",
            params={"userId": user_a_id},
            headers={"token": token_a},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"follow/followers failed: {body}"
        assert body.get("data") == [], f"Expected empty list, got: {body.get('data')}"

    def test_followers_returns_list(self, base_url, token_b, user_a_id, user_b_id):
        """b 关注 a 后查 a 的粉丝列表：含 b 条目，username 匹配、isFollowed=false（b 不关注自己）、isSelf=true（当前用户即条目本人）。"""
        add_body = _follow_add_remove(base_url, token_b, user_a_id, "add")
        try:
            assert add_body.get("code") == 200, f"follow/add failed: {add_body}"

            resp = requests.get(
                f"{base_url}/follow/followers",
                params={"userId": user_a_id},
                headers={"token": token_b},
                timeout=10,
            )
            body = resp.json()
            assert body.get("code") == 200, f"follow/followers failed: {body}"
            data = body.get("data") or []
            matches = [u for u in data if u.get("userId") == user_b_id]
            assert len(matches) == 1, \
                f"Followers list should contain userB exactly once: {data}"
            item = matches[0]
            assert item.get("username") == USER_B["username"], f"Username mismatch: {item}"
            assert item.get("isFollowed") is False, f"isFollowed should be False: {item}"
            assert item.get("isSelf") is True, f"isSelf should be True: {item}"
        finally:
            _follow_add_remove(base_url, token_b, user_a_id, "remove")


# ============================================================================
# T7：关注/粉丝列表分页（A1 有序缓存窗口 + B2 分页信封）
# ============================================================================

_EXTRA_USER = None


def _get_list(base_url, token, kind, user_id, page=None, page_size=None):
    """GET /follow/{kind}?userId=..[&page=..&pageSize=..]，返回响应 JSON（未传的参数不带在 URL 上）。"""
    params = {"userId": user_id}
    if page is not None:
        params["page"] = page
    if page_size is not None:
        params["pageSize"] = page_size
    resp = requests.get(
        f"{base_url}/follow/{kind}",
        params=params,
        headers={"token": token},
        timeout=10,
    )
    return resp.json()


def _register_extra_user(base_url):
    """注册（或登录）一个额外用户并在模块内缓存：用于构造"某用户有 ≥2 个粉丝"的跨页场景。

    手机号前缀 137 与 conftest 的 138/139 错开，避免与 user_a/user_b 冲突。
    """
    global _EXTRA_USER
    if _EXTRA_USER is not None:
        return _EXTRA_USER
    uniq = uuid.uuid4().hex[:8]
    suffix = str(int(uniq, 16))[-8:].zfill(8)
    username = f"testC_{uniq}"
    phone = f"137{suffix}"
    reg = conftest.register_user(username, phone, "abc123")
    data = reg.get("data") or {}
    if reg.get("code") != 200:
        login = conftest.login_user(phone, "abc123")
        data = login.get("data") or {}
        assert login.get("code") == 200, f"无法创建额外用户: reg={reg}, login={login}"
    _EXTRA_USER = {"id": data.get("id"), "token": data.get("token")}
    return _EXTRA_USER


@pytest.mark.boundary
class TestFollowListPagination:
    """T7：/follow/{following,followers} 分页——缺省兼容 + 信封 + 页间不重不漏 + 参数归一。"""

    def test_default_path_still_returns_array(self, base_url, token_a, user_a_id):
        """缺省不传分页参数 -> data 仍是数组（改造前契约，零破坏）。"""
        body = _get_list(base_url, token_a, "following", user_a_id)
        assert body.get("code") == 200, f"default following failed: {body}"
        assert isinstance(body.get("data"), list), \
            f"缺省路径 data 必须是数组: {body.get('data')}"

    def test_paged_without_token_returns_401(self, base_url, user_a_id):
        """带分页参数但无 token -> 401（守卫语义不受分页改造影响）。"""
        resp = requests.get(
            f"{base_url}/follow/followers",
            params={"userId": user_a_id, "page": 1, "pageSize": 10},
            timeout=10,
        )
        assert resp.json().get("code") == 401, f"paged without token should be 401: {resp.json()}"

    def test_paging_triggered_by_either_param_alone(self, base_url, token_a, user_a_id):
        """半参数：只传 page 或只传 pageSize 亦进入分页分支（另一个取默认值）。"""
        only_page = _get_list(base_url, token_a, "following", user_a_id, page=2)["data"]
        assert isinstance(only_page, dict), f"只传 page 也应返回信封: {only_page}"
        assert only_page["page"] == 2 and only_page["pageSize"] == 10

        only_size = _get_list(base_url, token_a, "following", user_a_id, page_size=5)["data"]
        assert isinstance(only_size, dict), f"只传 pageSize 也应返回信封: {only_size}"
        assert only_size["page"] == 1 and only_size["pageSize"] == 5

    def test_params_normalized_and_out_of_range_page_keeps_total(self, base_url, token_a, user_a_id):
        """参数归一：page<1 -> 1、pageSize 超上限 -> 50；越界页返回空数组但保留 total。"""
        norm = _get_list(base_url, token_a, "following", user_a_id, page=-1, page_size=999)["data"]
        assert norm["page"] == 1, f"page<1 应归一为 1: {norm}"
        assert norm["pageSize"] == 50, f"pageSize 超上限应归一为 50: {norm}"

        beyond = _get_list(base_url, token_a, "following", user_a_id, page=9999, page_size=10)["data"]
        assert beyond["list"] == [], f"越界页应为空数组: {beyond}"
        assert beyond["total"] == norm["total"], "越界页仍应返回 total（前端据此判末页）"

    def test_paged_pages_disjoint_ordered_and_prefix_consistent(self, base_url, token_a, token_b,
                                                                user_a_id, user_b_id):
        """粉丝列表分页：信封字段正确 + 页间不重不漏 + 与缺省全量前缀一致（顺序稳定升序）。

        自建 ≥2 个粉丝（user_b + 额外用户）以真正跨两页；finally 自清恢复"未关注"初态。
        """
        extra = _register_extra_user(base_url)
        added = []
        for token in (token_b, extra["token"]):
            body = _follow_add_remove(base_url, token, user_a_id, "add")
            # 200 = 新建成功；409 = 已关注（前序用例残留）——两者都可继续
            assert body.get("code") in (200, 409), f"follow/add failed: {body}"
            if body.get("code") == 200:
                added.append(token)
        try:
            full = _get_list(base_url, token_a, "followers", user_a_id)
            assert full.get("code") == 200, f"followers failed: {full}"
            full_ids = [u["userId"] for u in full["data"]]
            assert len(full_ids) >= 2, f"需要 ≥2 个粉丝才能验证跨页: {full_ids}"
            assert full_ids == sorted(full_ids), f"缺省全量应为升序（score=id 口径）: {full_ids}"

            page1 = _get_list(base_url, token_a, "followers", user_a_id, page=1, page_size=1)
            assert page1.get("code") == 200, f"paged followers failed: {page1}"
            data = page1["data"]
            assert isinstance(data, dict), f"分页请求 data 应为信封对象: {data}"
            assert set(data.keys()) == {"list", "total", "page", "pageSize", "totalPages"}, data
            assert data["page"] == 1 and data["pageSize"] == 1
            assert data["total"] == len(full_ids), f"total 应与成员集同源: {data} vs {full_ids}"
            assert data["totalPages"] == len(full_ids)
            assert [u["userId"] for u in data["list"]] == full_ids[:1]

            page2 = _get_list(base_url, token_a, "followers", user_a_id, page=2, page_size=1)["data"]
            page2_ids = [u["userId"] for u in page2["list"]]
            assert page2_ids == full_ids[1:2], f"第 2 页应为全量第 2 条: {page2_ids}"
            assert page2_ids[0] not in [u["userId"] for u in data["list"]], "页间不得重复"

            # 分页条目字段与缺省路径一致（username/isFollowed/isSelf 齐备）
            item = data["list"][0]
            assert item.get("username"), f"分页条目缺 username: {item}"
            assert "isFollowed" in item and "isSelf" in item, f"分页条目缺状态字段: {item}"
        finally:
            for token in added:
                _follow_add_remove(base_url, token, user_a_id, "remove")