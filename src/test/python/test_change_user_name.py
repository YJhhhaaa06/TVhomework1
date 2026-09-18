# -*- coding: utf-8 -*-
"""
test_change_user_name.py - 第五期 T1 authorName 冗余同步：/user/changeUserName 端点端到端。

覆盖（方案 A：补改名接口 + 级联失效内容缓存 authorName）：
  POST /user/changeUserName  修改当前登录用户名（AuthFilter PROTECTED_EXACT 精确匹配）

分支覆盖（代码为准）：
  未登录/无 token                          -> 401（AuthFilter 精确匹配 /user/changeUserName）
  空用户名 / 超长（>=50 字符）             -> 400（ParamException 用户名不合法）
  与其他用户同名（重复名）                 -> 409（ConflictException 用户名已被占用）
  正常改名 -> 200 / data null；随后
     内容详情（/search/IdSearch）authorName 变更为新名（改名后级联失效该作者内容 key，
       读自愈重新 JOIN users 回填新名——缓存旧名已被清除的运行时断言）
     个人主页（/profile）该内容 authorName 变更为新名
     新名可登录（/user/login by phone 返回新 username）

数据流自包含（不改 conftest user_a/user_b 身份，避免跨用例破坏）：
每个用例注册一次性用户（username 前缀 testB_/testA_，命中 cleanup_data.py T5 白名单，
级联清理其 content/comment 等关联），无跨用例状态依赖、顺序无关。
"""

import uuid

import pytest
import requests


# ---------------------------------------------------------------------------
# 辅助
# ---------------------------------------------------------------------------

def _register_disposable(base_url, prefix="testB_"):
    """注册一次性测试用户并返回 {id, token, username, phone}。"""
    _unique = uuid.uuid4().hex[:8]
    _phone_suffix = str(int(_unique, 16))[-8:].zfill(8)
    payload = {
        "username": f"{prefix}{_unique}",
        "phone": f"137{_phone_suffix}",
        "password": "abc123",
    }
    resp = requests.post(f"{base_url}/user/register", json=payload, timeout=10)
    body = resp.json()
    assert body.get("code") == 200, f"注册一次性用户失败: {body}"
    data = body.get("data", {})
    return {
        "id": data.get("id"),
        "token": data.get("token"),
        "username": data.get("username"),
        "phone": payload["phone"],
    }


def _login(base_url, account, password):
    """POST /user/login（account 为手机号），返回响应 JSON。"""
    return requests.post(
        f"{base_url}/user/login",
        json={"account": account, "password": password},
        timeout=10,
    ).json()


def _change_user_name(base_url, token, payload):
    """POST /user/changeUserName（JSON），返回响应 JSON。"""
    return requests.post(
        f"{base_url}/user/changeUserName",
        headers={"token": token, "Content-Type": "application/json"},
        json=payload,
        timeout=10,
    ).json()


def _upload_post(base_url, token, title, test_files):
    """用例内新建独立图文（封面对勾 + 图），返回响应 JSON。"""
    with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
        resp = requests.post(
            f"{base_url}/api/upload/post",
            headers={"token": token},
            data={"title": title, "description": "change_user_name_test", "categoryId": "0"},
            files={
                "cover": ("test_cover.png", cf, "image/png"),
                "image": ("test_image.jpg", imf, "image/jpeg"),
            },
            timeout=30,
        )
    return resp.json()


def _get_detail(base_url, content_id, token=None):
    """GET /search/IdSearch，返回 {code, data}。"""
    headers = {"token": token} if token else {}
    resp = requests.get(
        f"{base_url}/search/IdSearch",
        params={"contentId": content_id},
        headers=headers,
        timeout=10,
    )
    return resp.json()


def _profile_items(base_url, token, user_id):
    """GET /profile 第 1 页返回的内容条目列表（含 authorName）。"""
    resp = requests.get(
        f"{base_url}/profile",
        params={"userId": user_id, "page": 1, "pageSize": 20},
        headers={"token": token},
        timeout=10,
    )
    body = resp.json()
    assert body.get("code") == 200, f"主页查询失败: {body}"
    content_page = body.get("data", {}).get("contentPage") or {}
    return content_page.get("list") or []


# ---------------------------------------------------------------------------
# POST /user/changeUserName
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestChangeUserNameBoundary:

    def test_change_user_name_requires_login(self, base_url):
        """无 token 访问 /user/changeUserName -> code=401（AuthFilter 精确匹配）。"""
        body = _change_user_name(base_url, "", {"userName": "new_name"})
        assert body.get("code") == 401, \
            f"changeUserName without token should return 401, got {body.get('code')}: {body}"

    def test_change_user_name_blank_or_too_long(self, base_url):
        """空用户名 / 超长用户名 -> code=400（ParamException 用户名不合法）。"""
        user = _register_disposable(base_url)

        body_blank = _change_user_name(base_url, user["token"], {"userName": ""})
        assert body_blank.get("code") == 400, \
            f"Blank userName should return 400, got {body_blank.get('code')}: {body_blank}"

        body_long = _change_user_name(base_url, user["token"], {"userName": "a" * 50})
        assert body_long.get("code") == 400, \
            f"50-char userName should return 400, got {body_long.get('code')}: {body_long}"

    def test_change_user_name_duplicate_rejected(self, base_url):
        """改名为另一已存在用户的名字 -> code=409（ConflictException 用户名已被占用）。"""
        user_a = _register_disposable(base_url, prefix="testB_")
        user_b = _register_disposable(base_url, prefix="testB_")

        body = _change_user_name(base_url, user_a["token"], {"userName": user_b["username"]})
        assert body.get("code") == 409, \
            f"Duplicate userName should return 409, got {body.get('code')}: {body}"


@pytest.mark.consistency
class TestChangeUserNameConsistency:

    def test_rename_updates_content_author_name_everywhere(self, base_url, test_files):
        """改名后：内容详情 + 个人主页 authorName 变更为新名（内容缓存级联失效生效），新名可登录。

        流程即"旧名入缓存（上传时）→ 详情读到旧名（缓存命中/DB 装载均带旧名）→ 改名 →
        详情/主页读到新名"——若级联失效缺失，详情会继续返回缓存旧名，此用例即红。
        """
        user = _register_disposable(base_url)
        new_name = f"renamed_{uuid.uuid4().hex[:8]}"

        # 1) 上传内容：authorName 入缓存 = 注册用户名
        title = f"renmv_{uuid.uuid4().hex[:8]}"
        up = _upload_post(base_url, user["token"], title, test_files)
        assert up.get("code") == 200, f"新建图文失败: {up}"
        cid = up["data"]["contentId"]

        # 2) 改名前置位：详情 authorName = 旧名
        before = _get_detail(base_url, cid, user["token"])
        assert before.get("code") == 200, f"改名前置位详情失败: {before}"
        assert before["data"].get("authorName") == user["username"], \
            f"改名前置位 authorName 应为旧名, got: {before['data'].get('authorName')}"

        # 3) 改名
        body = _change_user_name(base_url, user["token"], {"userName": new_name})
        assert body.get("code") == 200, f"改名失败: {body}"
        assert body.get("data") is None, f"Expected data=null on success, got: {body}"

        # 4) 详情 authorName = 新名（级联失效后读自愈回填）
        after = _get_detail(base_url, cid, user["token"])
        assert after.get("code") == 200, f"改名后详情失败: {after}"
        assert after["data"].get("authorName") == new_name, \
            f"改名后详情 authorName 应为新名, got: {after['data'].get('authorName')}"

        # 5) 个人主页该内容 authorName = 新名
        profile_list = _profile_items(base_url, user["token"], user["id"])
        item = next((it for it in profile_list if it.get("id") == cid), None)
        assert item is not None, f"改名后主页应仍含该内容: {profile_list}"
        assert item.get("authorName") == new_name, \
            f"改名后主页 authorName 应为新名, got: {item.get('authorName')}"

        # 6) 新名可登录（by phone 返回新 username）
        login = _login(base_url, user["phone"], "abc123")
        assert login.get("code") == 200, f"新名登录失败: {login}"
        assert login.get("data", {}).get("username") == new_name, \
            f"登录返回的 username 应为新名, got: {login}"