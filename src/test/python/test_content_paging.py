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
import os
import shutil
import subprocess
from pathlib import Path

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


# ---------------------------------------------------------------------------
# /search/keywordSearch 排序 tie-breaker（日志第三张清单 T15，池项 U-25）
# ---------------------------------------------------------------------------
# 缺陷：两处 ORDER BY 缺 `, c.id DESC`（`:272` 单字符 LIKE 分支 / `:278` 多字符 MATCH 分支）
# → 同一秒创建的内容在 offset 分页下顺序不确定，页间可能重复或漏项；前端 chunkedList 的
# seen 去重会**掩盖**该缺陷（helper 注释已写明"去重只兜漂移、不替代后端『页间不重不漏』"）。
# 本用例直接取后端响应（不经前端去重），并刻意**不断言 createTime**（上传时已写 Redis 缓存，
# 事后直连改库会让响应里的 createTime 陈旧；顺序来自 DAO SQL，字段来自缓存）。

# 稀有 token：实测测试库 `MATCH … AGAINST('qz')` = 0 行、`title LIKE '%q%'` = 0 行
TIE_TOKEN = "qz"          # 2 字符 → 多字符分支（MATCH … AGAINST）
TIE_CHAR = "q"            # 1 字符 → 单字符分支（title LIKE '%q%'）
TIE_TITLE_PREFIX = "pytest_srch_qz_tb"   # 命中 cleanup_data.py 的 `pytest\_%` 前缀
TIE_N = 6                 # 同秒行数（≥5：把"不稳定排序恰好等于 id 降序"的假绿概率压到 ~1/6!）
PINNED_SECOND = "2001-02-03 04:05:06"    # 唯一过去秒：不抢"最新"位，避免扰动 /profile、/feed

_MYSQL_CANDIDATES = (
    r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
)


def _mysql_path():
    """定位 mysql 客户端（沿 test_comment_delete.py 既有先例）。"""
    found = shutil.which("mysql")
    if found:
        return Path(found)
    for candidate in _MYSQL_CANDIDATES:
        if Path(candidate).exists():
            return Path(candidate)
    return None


def _run_sql(sql):
    """直连测试库执行 SQL（连接参数由 run_tests.py 注入的 DB_* 给出）。"""
    mysql = _mysql_path()
    assert mysql is not None, "mysql 客户端不可用"
    cmd = [
        str(mysql),
        "--user=" + os.environ.get("DB_USER", "root"),
        "--password=" + os.environ.get("DB_PASSWORD", "MySQL"),
        "--host=" + os.environ.get("DB_HOST", "127.0.0.1"),
        "--port=" + os.environ.get("DB_PORT", "3306"),
        "--database=" + os.environ.get("DB_NAME", "tvdatabase"),
        "--batch",
        "--skip-column-names",
        "--default-character-set=utf8mb4",
        "--execute",
        sql,
    ]
    proc = subprocess.run(
        cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120
    )
    if proc.returncode != 0:
        raise RuntimeError("mysql 执行失败: " + (proc.stderr.strip() or proc.stdout.strip()))
    return proc.stdout.strip()


def _post_content(base_url, token, title, test_files):
    """POST /api/upload/post 建一条图文内容（真实写路径），返回 contentId。"""
    with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
        resp = requests.post(
            f"{base_url}/api/upload/post",
            headers={"token": token},
            data={"title": title, "description": "tie-breaker 同秒内容", "categoryId": "0"},
            files={
                "cover": ("test_cover.png", cf, "image/png"),
                "image": ("test_image.jpg", imf, "image/jpeg"),
            },
            timeout=30,
        )
    result = resp.json()
    assert result.get("code") == 200, f"图文上传失败: {result}"
    return result["data"]["contentId"]


def _delete_content(base_url, token, content_id):
    """作者软删（真实路径，顺带失效缓存）；teardown 兜底用。"""
    return requests.post(
        f"{base_url}/content/delete",
        params={"contentId": content_id},
        headers={"token": token},
        timeout=10,
    ).json()


@pytest.fixture(scope="module")
def same_second_rows(base_url, token_a, test_files):
    """自建 TIE_N 条 `create_time` 同秒的内容（T15）。

    建行走真实写路径（/api/upload/post），随后直连测试库把 `create_time` 钉到同一固定秒
    （任务原文允许"可显式写同值"）。module 作用域 + 显式 teardown 自清，避免污染后续
    用例的 /profile、/feed；`pytest_` 前缀另受 cleanup_data.py 兜底。
    """
    if _mysql_path() is None:
        pytest.skip("mysql 客户端不可用，跳过 T15 同秒页间顺序用例")
    ids = []

    def _id_list():
        return ",".join(str(i) for i in ids)

    try:
        for i in range(TIE_N):
            ids.append(_post_content(base_url, token_a, f"{TIE_TITLE_PREFIX}{i}", test_files))
        _run_sql(
            f"UPDATE content SET create_time='{PINNED_SECOND}' WHERE id IN ({_id_list()})"
        )
        distinct_ts = _run_sql(
            f"SELECT COUNT(DISTINCT create_time) FROM content WHERE id IN ({_id_list()})"
        )
        assert distinct_ts == "1", f"同秒钉值未生效（distinct create_time={distinct_ts}）"
        yield ids
    finally:
        if ids:
            for content_id in ids:
                _delete_content(base_url, token_a, content_id)
            _run_sql(f"DELETE FROM content WHERE id IN ({_id_list()})")


def _assert_tiebreaker_order(base_url, keyword, ids):
    """同秒内容在 /search 下的页间顺序断言（T15）。

    判别力来源：本批 `create_time` 全相同 ⇒ 正确顺序唯一 = **id 降序**（与 /feed、/profile
    同一 `create_time DESC, id DESC` 口径）。断言只比"本批 id 的相对顺序"，对 ngram 串扰
    （其它行命中同一关键词，实测真实存在）免疫；前置断言用于把"串扰/未装载"与"顺序错"
    两类失败区分开，避免假红。
    """
    ours = set(ids)
    body = _search(base_url, keyword=keyword, page=1, pageSize=DOMAIN_PAGE_SIZE)
    assert body.get("code") == 200, f"search 失败: {body}"
    data = body["data"]
    full_ids = [it["id"] for it in data["list"]]

    assert ours <= set(full_ids), f"自建同秒内容未全部命中（keyword={keyword}）：{full_ids}"
    assert data["total"] == len(full_ids), (
        f"本用例需单页装下全域结果集（keyword={keyword}）："
        f"total={data['total']} 单页={len(full_ids)}"
    )

    # 红证据载体：同秒 ⇒ 期望 id 降序
    got = [i for i in full_ids if i in ours]
    assert got == sorted(ours, reverse=True), (
        f"同秒内容应按 id 降序（与 /feed、/profile 同口径，keyword={keyword}）："
        f"实测={got} 期望={sorted(ours, reverse=True)}"
    )

    # 验收③：pageSize=1 逐页 ⇒ 页间不重不漏 + 两次遍历顺序一致
    paged = []
    for page in range(1, data["total"] + 1):
        page_body = _search(base_url, keyword=keyword, page=page, pageSize=1)
        assert page_body.get("code") == 200, f"search 第 {page} 页失败: {page_body}"
        paged += [it["id"] for it in page_body["data"]["list"]]
    assert len(paged) == data["total"] and len(set(paged)) == len(paged), (
        f"页间重/漏（keyword={keyword}）：{paged}"
    )
    assert paged == full_ids, f"两次遍历顺序不一致（keyword={keyword}）：{paged} vs {full_ids}"


@pytest.mark.boundary
class TestSearchTiebreaker:
    """T15（池项 U-25）：/search 两处 ORDER BY 补 `, c.id DESC` 后同秒内容页间顺序确定。"""

    def test_match_branch_same_second_order_is_id_desc(self, base_url, same_second_rows):
        """多字符关键词 → `MATCH … AGAINST` 分支（该分支走 filesort，**改动前应红**）。"""
        _assert_tiebreaker_order(base_url, TIE_TOKEN, same_second_rows)

    def test_like_branch_same_second_order_is_id_desc(self, base_url, same_second_rows):
        """单字符关键词 → `title LIKE` 分支（**改动前同样红**）。

        该分支的顺序**取决于执行计划、且随 LIMIT 变化**（实测，keyword=`%q%`）：
        `LIMIT 0,1` → `idx_del_time` 反向索引扫描（`EXPLAIN`: Backward index scan、无 filesort，
        此时恰好等价 `create_time DESC, id DESC`）；但 **域级信封 `LIMIT 0,100`**（前端实际用的
        那一档）→ 计划翻成全表扫描 + `Using filesort`，同秒行次序未定义 → 改动前实测即 id 升序。
        即"改动前恰好正确"只是小 LIMIT 下的巧合，不能依赖。
        """
        _assert_tiebreaker_order(base_url, TIE_CHAR, same_second_rows)
