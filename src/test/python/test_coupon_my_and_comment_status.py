# -*- coding: utf-8 -*-
"""
test_coupon_my_and_comment_status.py - 我的优惠券 + 评论点赞状态端到端测试（T2，方向 A 补测试缺口）

覆盖两个此前零 pytest 覆盖的读接口：
  GET /coupon/my                 当前登录用户的优惠券（含兑换码）
  GET /like/comment/status?commentId=<id>   当前登录用户对某评论的点赞状态

/coupon/my 三态：未登录 401 / 未抢券空数组 / 抢后含记录（couponId+couponCode）。
/like/comment/status 四态：未登录 401 / 未点赞 false / 已点赞 true /
评论不存在→false / 缺 commentId→400。

守卫说明（AuthFilter）：/coupon/my 以 PROTECTED_EXACT 精确匹配需登录；
/like/* 前缀匹配需登录（覆盖 /like/comment/status）。二者无 token 均 401。

数据流自包含（不新增固定种子，沿用 T1"自建自清"哲学）：
- /coupon/my 空列表用例使用自建一次性用户（username 前缀 testA_，落在
  cleanup_data.py T5 测试用户白名单，删除时级联清理 coupon_order），全量 all
  运行下 userA/userB 均已抢过固定券（test_boundary E-09 / test_smoke S-16 /
  test_consistency C-10），不能依赖它们的空态。
- 抢后断言同一新用户抢固定券种子（conftest.available_coupon_id，高库存/远期，
  新用户必 200），data 恰一条记录。
- 评论点赞状态用例先用 status 查询清态（若已点先取消），再测 未点→已点，
  finally 内复原（净零残留），不假设初态、不依赖 C-04/C-05 先执行完。
"""

import pytest
import requests
import uuid

from conftest import SEED_COUPON_TITLE_PREFIX


# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------

def _register_disposable(base_url):
    """注册一次性测试用户并返回 {id, token}。

    username 用 testA_ 前缀 + UUID 后缀：既保证跨会话唯一，又命中
    cleanup_data.py T5 测试用户白名单（级联清理 coupon_order），不留残留。
    """
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
    return {"id": data.get("id"), "token": data.get("token")}


def _grab_coupon(base_url, token, coupon_id):
    """抢券 POST /coupon/grab，返回响应 JSON。"""
    return requests.post(
        f"{base_url}/coupon/grab",
        headers={"token": token, "Content-Type": "application/json"},
        json={"couponId": coupon_id},
        timeout=10,
    ).json()


def _like_comment(base_url, token, comment_id):
    """点赞评论 POST /like/comment/add，返回响应 JSON。"""
    return requests.post(
        f"{base_url}/like/comment/add",
        headers={"token": token, "Content-Type": "application/x-www-form-urlencoded"},
        data={"commentId": str(comment_id)},
        timeout=10,
    ).json()


def _unlike_comment(base_url, token, comment_id):
    """取消评论点赞 POST /like/comment/remove，返回响应 JSON。"""
    return requests.post(
        f"{base_url}/like/comment/remove",
        headers={"token": token, "Content-Type": "application/x-www-form-urlencoded"},
        data={"commentId": str(comment_id)},
        timeout=10,
    ).json()


def _comment_status(base_url, token, comment_id):
    """GET /like/comment/status?commentId=X -> bool（code==200 否则断言失败）。"""
    resp = requests.get(
        f"{base_url}/like/comment/status",
        params={"commentId": comment_id},
        headers={"token": token},
        timeout=10,
    )
    body = resp.json()
    assert body.get("code") == 200, f"Comment like status query failed: {body}"
    return body.get("data")


# ---------------------------------------------------------------------------
# GET /coupon/my
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestCouponMy:

    def test_coupon_my_requires_login(self, base_url):
        """无 token 访问 /coupon/my -> code=401（AuthFilter 精确匹配 /coupon/my）。"""
        resp = requests.get(f"{base_url}/coupon/my", timeout=10)
        body = resp.json()
        assert body.get("code") == 401, \
            f"GET /coupon/my without token should return 401, got {body.get('code')}: {body}"

    def test_coupon_my_empty_list(self, base_url):
        """新注册用户未抢任何券 -> data == []（findOrdersByUserId 空集，非 null）。"""
        user = _register_disposable(base_url)
        resp = requests.get(f"{base_url}/coupon/my", headers={"token": user["token"]}, timeout=10)
        body = resp.json()
        assert body.get("code") == 200, f"GET /coupon/my failed: {body}"
        assert body.get("data") == [], f"Expected empty list, got: {body.get('data')}"

    def test_coupon_my_after_grab(self, base_url, available_coupon_id):
        """新用户抢固定券种子后 -> data 恰含 1 条该券记录（couponId/couponCode/title 断言点）。"""
        user = _register_disposable(base_url)
        grab_body = _grab_coupon(base_url, user["token"], available_coupon_id)
        assert grab_body.get("code") == 200, f"Grab coupon failed: {grab_body}"

        resp = requests.get(f"{base_url}/coupon/my", headers={"token": user["token"]}, timeout=10)
        body = resp.json()
        assert body.get("code") == 200, f"GET /coupon/my failed: {body}"
        data = body.get("data") or []
        assert len(data) == 1, f"Expected exactly 1 coupon order, got: {data}"
        item = data[0]
        assert item.get("couponId") == available_coupon_id, f"couponId mismatch: {item}"
        assert isinstance(item.get("couponCode"), str) and item["couponCode"], \
            f"couponCode should be non-empty string: {item}"
        assert str(item.get("title", "")).startswith(SEED_COUPON_TITLE_PREFIX), \
            f"Coupon title should start with seed prefix: {item}"


# ---------------------------------------------------------------------------
# GET /like/comment/status
# ---------------------------------------------------------------------------

@pytest.mark.boundary
class TestLikeCommentStatus:

    def test_comment_status_requires_login(self, base_url):
        """无 token 访问 /like/comment/status -> code=401（AuthFilter /like 前缀匹配）。"""
        resp = requests.get(
            f"{base_url}/like/comment/status",
            params={"commentId": 1},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 401, \
            f"GET /like/comment/status without token should return 401, got {body.get('code')}: {body}"

    def test_comment_status_two_states(self, base_url, token_b, sample_comment_id):
        """评论点赞状态两态：未点赞 false -> 点赞 true -> 取消 false（自建自清）。

        不假设初态：若 sample_comment_id 已被 userB 点赞（如 C-04/C-05 失败残留），
        先取消清态，保证下面断言确定性；finally 内复原，净零残留。
        """
        comment_id = sample_comment_id

        # 清态：保证从"未点赞"初态出发
        if _comment_status(base_url, token_b, comment_id):
            _unlike_comment(base_url, token_b, comment_id)

        try:
            assert _comment_status(base_url, token_b, comment_id) is False, \
                "After cleanup the comment should not be liked"

            like_body = _like_comment(base_url, token_b, comment_id)
            assert like_body.get("code") == 200, f"Like comment failed: {like_body}"
            assert _comment_status(base_url, token_b, comment_id) is True, \
                "Status should be True after liking"
        finally:
            if _comment_status(base_url, token_b, comment_id):
                unlike_body = _unlike_comment(base_url, token_b, comment_id)
                assert unlike_body.get("code") == 200, f"Unlike cleanup failed: {unlike_body}"
                assert _comment_status(base_url, token_b, comment_id) is False, \
                    "Status should be False after unlike (net-zero residue)"

    def test_comment_status_non_existent_comment(self, base_url, token_b):
        """评论不存在 -> code==200 且 data==false（LikeService 不校验评论存在性，liker 集为空即 false）。"""
        resp = requests.get(
            f"{base_url}/like/comment/status",
            params={"commentId": 99999999},
            headers={"token": token_b},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 200, f"Comment status query failed: {body}"
        assert body.get("data") is False, \
            f"Non-existent comment should return false, got: {body.get('data')}"

    def test_comment_status_missing_comment_id(self, base_url, token_b):
        """带 token 但缺 commentId 参数 -> code=400（parseCommentId 抛 ParamException）。"""
        resp = requests.get(
            f"{base_url}/like/comment/status",
            headers={"token": token_b},
            timeout=10,
        )
        body = resp.json()
        assert body.get("code") == 400, \
            f"Missing commentId should return 400, got {body.get('code')}: {body}"