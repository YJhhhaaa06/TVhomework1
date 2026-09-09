# -*- coding: utf-8 -*-
"""
test_change_password.py - 修改密码端点端到端测试（T3，方向 A 补测试缺口）

覆盖此前零 pytest 覆盖的写接口：
  POST /user/changePassword  修改当前登录用户密码（需 phone + 旧密码 + 新密码）

分支覆盖（代码为准，异常经 ExceptionFilter 写 e.getCode()）：
  未登录（AuthFilter PROTECTED_EXACT /user/changePassword）        -> 401
  字段为空（CommandConverter，任一 blank）                           -> 400
  新密码格式非法（PasswordUtil）                                     -> 400
  手机号不匹配（格式合法但非本人手机号）                               -> 400
  旧密码错误（PasswordIncorrectException extends AuthException）      -> 401
  正常改密（{code:200, data:null}）

关键行为（现状，非本期修复对象）：
- 新密码合法范围实测为 ^[a-zA-Z0-9]{1,16}$，与文案"6~16位"不符——故
  "新密码非法"用例用特殊字符触发 400（不能用 5 位字母数字，会通过校验）。
- JWT 无状态（无吊销），改密后旧 token 不失效；此处只固化"新密可登录、
  旧密码登录失败 401"（改密真实生效），不断言旧 token 失效。

数据流自包含（不改 conftest user_a/user_b 密码，避免跨用例破坏）：
每个分支用例各自注册一次性用户（username 前缀 testA_，命中 cleanup_data.py
T5 白名单，级联清理），无跨用例状态依赖、顺序无关。
"""

import uuid

import pytest
import requests


# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------

def _register_disposable(base_url):
    """注册一次性测试用户并返回 {id, token, phone}。"""
    _unique = uuid.uuid4().hex[:8]
    _phone_suffix = str(int(_unique, 16))[-8:].zfill(8)
    payload = {
        "username": f"testA_{_unique}",
        "phone": f"138{_phone_suffix}",
        "password": "abc123",
    }
    resp = requests.post(f"{base_url}/user/register", json=payload, timeout=10)
    body = resp.json()
    assert body.get("code") == 200, f"注册一次性用户失败: {body}"
    data = body.get("data", {})
    return {"id": data.get("id"), "token": data.get("token"), "phone": payload["phone"]}


def _change_password(base_url, token, payload):
    """POST /user/changePassword（JSON），返回响应 JSON。"""
    return requests.post(
        f"{base_url}/user/changePassword",
        headers={"token": token, "Content-Type": "application/json"},
        json=payload,
        timeout=10,
    ).json()


def _login(base_url, account, password):
    """POST /user/login（JSON，account 为手机号），返回响应 JSON。"""
    return requests.post(
        f"{base_url}/user/login",
        json={"account": account, "password": password},
        timeout=10,
    ).json()


# ---------------------------------------------------------------------------
# POST /user/changePassword
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestChangePassword:

    def test_change_password_requires_login(self, base_url):
        """无 token 访问 /user/changePassword -> code=401（AuthFilter 精确匹配）。"""
        resp = requests.post(
            f"{base_url}/user/changePassword",
            json={"phone": "13800000000", "oldPassword": "abc123", "newPassword": "xyz789"},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 401, \
            f"changePassword without token should return 401, got {body.get('code')}: {body}"

    def test_change_password_blank_fields(self, base_url):
        """带 token 但缺 oldPassword（字段 blank）-> code=400（CommandConverter 输入不能为空）。"""
        user = _register_disposable(base_url)
        body = _change_password(base_url, user["token"], {
            "phone": user["phone"],
            # 缺 oldPassword
            "newPassword": "xyz789",
        })
        assert body.get("code") == 400, \
            f"Missing oldPassword should return 400, got {body.get('code')}: {body}"

    def test_change_password_success_and_new_login(self, base_url):
        """正常改密：200 / data null；新密码可登录（200 + token 非空）；旧密码登录失败（401）。"""
        user = _register_disposable(base_url)
        old_password = "abc123"
        new_password = "xyz789"

        body = _change_password(base_url, user["token"], {
            "phone": user["phone"],
            "oldPassword": old_password,
            "newPassword": new_password,
        })
        assert body.get("code") == 200, f"Change password failed: {body}"
        assert body.get("data") is None, f"Expected data=null on success, got: {body}"

        login_new = _login(base_url, user["phone"], new_password)
        assert login_new.get("code") == 200, f"Login with new password failed: {login_new}"
        assert login_new.get("data", {}).get("token"), f"New token should be non-empty: {login_new}"

        login_old = _login(base_url, user["phone"], old_password)
        assert login_old.get("code") == 401, \
            f"Login with old password should fail with 401, got {login_old.get('code')}: {login_old}"

    def test_change_password_phone_mismatch(self, base_url):
        """手机号格式合法但不匹配（非当前用户 phone）-> code=400（ParamException 手机号不匹配）。"""
        user = _register_disposable(base_url)
        # 生成一个格式合法、但必然不是当前用户的另一手机号
        other_phone = "139" + str(int(uuid.uuid4().hex[:8], 16))[-8:].zfill(8)
        assert other_phone != user["phone"], "generated phone must differ from user phone"

        body = _change_password(base_url, user["token"], {
            "phone": other_phone,
            "oldPassword": "abc123",
            "newPassword": "xyz789",
        })
        assert body.get("code") == 400, \
            f"Phone mismatch should return 400, got {body.get('code')}: {body}"

    def test_change_password_old_password_wrong(self, base_url):
        """手机号正确但旧密码错误 -> code=401（PasswordIncorrectException extends AuthException）。"""
        user = _register_disposable(base_url)
        body = _change_password(base_url, user["token"], {
            "phone": user["phone"],
            "oldPassword": "wrong001",
            "newPassword": "xyz789",
        })
        assert body.get("code") == 401, \
            f"Wrong old password should return 401, got {body.get('code')}: {body}"

    def test_change_password_new_password_invalid(self, base_url):
        """新密码格式非法（含特殊字符 @）-> code=400（PasswordUtil 正则 ^[a-zA-Z0-9]{1,16}$）。"""
        user = _register_disposable(base_url)
        body = _change_password(base_url, user["token"], {
            "phone": user["phone"],
            "oldPassword": "abc123",
            "newPassword": "abc@12345",  # 含特殊字符，触发格式校验
        })
        assert body.get("code") == 400, \
            f"Invalid new password should return 400, got {body.get('code')}: {body}"