# -*- coding: utf-8 -*-
"""test_milestone_log.py — T9（log2-09）"成功路径与关键状态变更 INFO 补点"的端到端断言。

覆盖（对应 T9 验收 ①~⑤、⑦）：
① 7 个补点在**真实请求的成功路径**各产生**恰好一条** INFO 记录，字段完整、文案与既有失败文案成对；
② 与 T8 的 7 个审计操作点**零重叠**（文件级证明：里程碑行只在 system.log、且只在 `com.itheima.*`
   业务 logger 上；audit.log / access.log / error.log 里 0 条）；
③ 失败路径**不**产生里程碑行（登录密码错误为对照）；
④ 无敏感值（全文件不变式：四个应用输出端**任何一行**都不得出现 11 位手机号明文）；
⑤ 对外行为零变化（只按既有契约断言响应 `code`）。
⑦ `LOG_CONVENTION.md` 的必记 / 绝不记与实际一致（口径落在 §3.2-必记③ 与 §3.6）。

## 补点清单（逐条判定见 TASKS T9 执行回写；本文件只覆盖**已补的 7 点**）
- 登录成功 / 用户注册成功（`UserService`）
- 添加视频成功 / 添加动态成功 / 删除内容成功（`ContentService`）
- 关注成功 / 取关成功（`FollowService`）

**明确不补**（同属"逐条判定"的结论，见回写）：点赞 / 取消点赞（高频、低追溯价值）；
评论发表 / 删除（高频；失败已有 SEVERE 承载）；作品编辑类（`update`/`mediaDelete`/`commentEnabled`/
`replaceMedia`，常规写操作）；管理端与用户敏感变更（属 T8 审计，另落 audit.log）。

## 读法（先例 `src/test/python/test_audit_log.py`，本文件沿用同一套）
- 输出端文件是轮转形态 `system.log.<N>`；选文件 = "前缀匹配 + mtime 最新"，**排除 JUL 的 `.lck`**。
  ⚠️ 目录里可能同时存在历史遗留的 `system.log`（无 `.N` 后缀、早期不轮转配置的产物）——靠 mtime 最新
  选中当前写入文件即可，不要写死文件名。
- **不用行号增量定位本次记录**（落盘期间可能轮转 → 假绿/假红）：改用**指纹 + 全文件计数 delta**——
  每次断言取"执行前后该指纹行数必须恰好 +1"，并把返回的最后一行的字段与**本 run 新建对象**对齐
  （userId / contentId）。**前提**：pytest 单进程串行（未启用 xdist）且本 run 日志量远小于
  `log.maxBytes`（出厂 10MB）→ 不发生轮转；若将来并行化或量级增长，需改为按 `req=` 定位。
- **手机号不变式必须先剥掉 `req=<16hex>`**：请求 id 是 16 位十六进制，天然会命中
  `1[3-9]\\d{9}`（实测 26 行假阳性）——剥离后剩下的命中才是真嫌疑行。

## 前置
`run_tests.py` 启动的独立实例（18080 + 测试库 3307）+ 注入
`LOG_PATH=.stage8-target/tomcat-test-18080/logs/system.log` → 四个输出端（system / error /
access / audit）同目录（相对路径只取文件名，锚 `log.file` 的目录）。
"""

import glob
import os
import re
import time
import uuid

import pytest
import requests

from conftest import ACCESS_LOG_DIR, BASE_URL

# 结构化单行形态（LogFormatter 前缀 + 业务 logger 名）
TS = r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}"
REQ = r"[0-9a-f]{16}"

# 里程碑行：level=INFO + **业务类 logger**（audit / access 是专属 logger，不匹配本式）
MILESTONE_LINE_RE = re.compile(
    rf"^ts={TS} level=INFO logger=com\.itheima\.\S+ req={REQ} msg="
)

REQ_TOKEN_RE = re.compile(rf"req={REQ}")
PHONE_RE = re.compile(r"1[3-9]\d{9}")

# 7 个补点的定位指纹（**取消息前缀**，具体对象 id 在用例里再对齐）
NEEDLE_LOGIN = "msg=登录成功, userId="
NEEDLE_REGISTER = "msg=用户注册成功, userId="
NEEDLE_ADD_VIDEO = "msg=添加视频成功, contentId="
NEEDLE_ADD_POST = "msg=添加动态成功, contentId="
NEEDLE_DELETE_CONTENT = "msg=删除内容成功, contentId="
NEEDLE_FOLLOW = "msg=关注成功, userId="
NEEDLE_UNFOLLOW = "msg=取关成功, userId="

MILESTONE_NEEDLES = (
    NEEDLE_LOGIN, NEEDLE_REGISTER, NEEDLE_ADD_VIDEO, NEEDLE_ADD_POST,
    NEEDLE_DELETE_CONTENT, NEEDLE_FOLLOW, NEEDLE_UNFOLLOW,
)

SYSTEM = "system.log*"
AUDIT = "audit.log*"
ACCESS = "access.log*"
ERROR = "error.log*"


# ---------------------------------------------------------------------------
# 读法助手（前缀匹配 + mtime 最新 + 排除 .lck）
# ---------------------------------------------------------------------------

def log_path(prefix):
    """目录里按前缀命名的轮转日志文件中最新一个（mtime 降序）；排除 JUL 的 `.lck` 锁文件。"""
    candidates = [p for p in glob.glob(os.path.join(ACCESS_LOG_DIR, prefix))
                  if not p.endswith(".lck")]
    if not candidates:
        return None
    return max(candidates, key=os.path.getmtime)


def read_lines(prefix):
    """最新文件的全部行；文件不存在返回空列表（供"该输出端可能没有本类记录"的判空）。"""
    path = log_path(prefix)
    if path is None:
        return []
    with open(path, encoding="utf-8") as fh:
        return fh.read().splitlines()


def count(prefix, needle):
    """当前该输出端文件中含指纹的行数（**全文件计数**，不做行号增量）。"""
    return sum(1 for line in read_lines(prefix) if needle in line)


def milestone(needle, request_fn, prefix=SYSTEM, timeout=5.0):
    """执行 `request_fn()`（返回响应 JSON），断言：

    ① 响应 `code == 200`（对外行为零变化，走既有契约）；
    ② 该里程碑指纹**本次恰好新增一条**（轮询覆盖"响应先于落盘"的窗口，末段再等一小段确认不是两条）；
    ③ 行形态合规（单行结构化 + level=INFO + 业务 logger）。

    返回 `(响应 JSON, 新增的那一行)`。
    """
    before = count(prefix, needle)

    body = request_fn()
    assert body.get("code") == 200, f"成功路径应 code=200（否则不会留痕）: {body}"

    deadline = time.time() + timeout
    while count(prefix, needle) < before + 1:
        if time.time() >= deadline:
            tail = [ln[:160] for ln in read_lines(prefix)[-3:]]
            raise AssertionError(
                f"{prefix} 未在 {timeout}s 内出现里程碑记录 {needle!r}"
                f"（当前 {count(prefix, needle)} 条，目录 {ACCESS_LOG_DIR}）；文件尾部 3 行 = {tail}"
            )
        time.sleep(0.05)
    time.sleep(0.2)  # 稳定窗口：确认只新增这一条

    hits = [ln for ln in read_lines(prefix) if needle in ln]
    assert len(hits) == before + 1, (
        f"本次应只新增 1 条里程碑记录，实得 {len(hits) - before} 条：\n"
        + "\n".join(ln[:200] for ln in hits[-3:])
    )

    line = hits[-1]  # 追加写 → 最后一条即本次记录
    assert MILESTONE_LINE_RE.match(line), (
        f"里程碑行不符合单行结构化形态（须 level=INFO + 业务 logger + req=）: {line[:240]}"
    )
    return body, line


def assert_no_milestone(needle, request_fn, expect_code, prefix=SYSTEM):
    """执行 `request_fn()`，断言响应码为 `expect_code` 且该里程碑行数**一条都没增加**。

    ⚠️ 断言前**先证目标文件存在且非空**——否则 `count()` 两侧恒为 0、本断言会**静默空过**
    （评审 🟡-3：负向断言自身不自证"读到了正确文件"）。
    """
    assert log_path(prefix) and read_lines(prefix), (
        f"{prefix} 缺失或为空（目录 {ACCESS_LOG_DIR}）→ 本负向断言无从证伪"
    )
    before = count(prefix, needle)
    body = request_fn()
    assert body.get("code") == expect_code, f"预期 code={expect_code}: {body}"
    time.sleep(0.2)
    after = count(prefix, needle)
    assert after == before, f"失败路径不得产生里程碑记录（{needle!r}：{before} → {after}）"
    return body


# ---------------------------------------------------------------------------
# 业务请求助手（用户名前缀命中 cleanup_data 白名单：testA_ / testB_）
# ---------------------------------------------------------------------------

def _fresh_payload(username_prefix, phone_prefix):
    """本 run 唯一的注册载荷（手机号 = 前缀 + 8 位数字，用户名命中清理白名单）。"""
    unique = uuid.uuid4().hex[:8]
    suffix = str(int(unique, 16))[-8:].zfill(8)
    return {
        "username": f"{username_prefix}{unique}",
        "phone": f"{phone_prefix}{suffix}",
        "password": "abc123",
    }


def _register(base_url, username_prefix, phone_prefix):
    """注册并返回 {id, token, phone, password}（自动登录失败时回退登录一次）。"""
    payload = _fresh_payload(username_prefix, phone_prefix)
    body = requests.post(f"{base_url}/user/register", json=payload, timeout=10).json()
    assert body.get("code") == 200, f"注册失败: {body}"
    data = body.get("data") or {}
    token = data.get("token")
    if not token:
        login = requests.post(f"{base_url}/user/login",
                              json={"account": payload["phone"], "password": payload["password"]},
                              timeout=10).json()
        assert login.get("code") == 200, f"注册后回退登录失败: {login}"
        token = login["data"]["token"]
    return {"id": data.get("id"), "token": token, "phone": payload["phone"],
            "password": payload["password"]}


@pytest.fixture(scope="module")
def follower_pair(base_url):
    """关注/取关用例的两个本 run 新用户（自建自清：用例末尾显式取关，不留关系行）。"""
    follower = _register(base_url, "testA_mile_", "136")
    target = _register(base_url, "testB_mile_", "137")
    return follower, target


# ---------------------------------------------------------------------------
# ① 认证里程碑：注册 / 登录
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestAuthMilestones:

    def test_register_and_login_emit_one_info_each(self, base_url):
        """`/user/register`、`/user/login` 成功路径各恰好一条 INFO（对象 id 与本 run 新建用户对齐）。"""
        payload = _fresh_payload("testA_mile_", "133")

        body, line = milestone(
            NEEDLE_REGISTER,
            lambda: requests.post(f"{base_url}/user/register", json=payload, timeout=10).json(),
        )
        user_id = body["data"]["id"]
        assert f"userId={user_id}" in line, f"注册里程碑应指向本 run 新建用户: {line[:200]}"
        assert payload["phone"] not in line, "注册里程碑不得含手机号明文"

        # 注册内层会触发一次自动登录（池 U-16）——本用例显式再登录一次，测的是**登录**这一点
        _, line = milestone(
            NEEDLE_LOGIN,
            lambda: requests.post(f"{base_url}/user/login",
                                  json={"account": payload["phone"],
                                        "password": payload["password"]}, timeout=10).json(),
        )
        assert f"userId={user_id}" in line, f"登录里程碑应指向本 run 用户: {line[:200]}"
        assert payload["phone"] not in line, "登录里程碑只记 userId——账号（手机号）不落盘"

    def test_login_failure_emits_no_milestone(self, base_url, follower_pair):
        """密码错误（可预期业务拒绝）不得留下成功里程碑（对照：失败由 WARNING 结论行承载）。"""
        follower, _ = follower_pair
        assert_no_milestone(
            NEEDLE_LOGIN,
            lambda: requests.post(f"{base_url}/user/login",
                                  json={"account": follower["phone"], "password": "wrong-pwd"},
                                  timeout=10).json(),
            expect_code=401,
        )

    def test_duplicate_register_emits_no_milestone(self, base_url, follower_pair):
        """重复手机号注册失败不得留下成功里程碑。"""
        follower, _ = follower_pair
        assert_no_milestone(
            NEEDLE_REGISTER,
            lambda: requests.post(f"{base_url}/user/register",
                                  json={"username": f"testA_mile_dup_{uuid.uuid4().hex[:8]}",
                                        "phone": follower["phone"],
                                        "password": "abc123"}, timeout=10).json(),
            expect_code=409,
        )


# ---------------------------------------------------------------------------
# ① 内容里程碑：发布（视频 / 动态）、作者删除
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestContentMilestones:

    def test_publish_and_delete_emit_one_info_each(self, base_url, token_a, test_files):
        """视频发布 / 动态发布 / 作者删除三个点各恰好一条 INFO；用例末尾自清。"""
        video_id = post_id = None
        try:
            with open(test_files["video"], "rb") as vf, open(test_files["cover"], "rb") as cf:
                body, line = milestone(
                    NEEDLE_ADD_VIDEO,
                    lambda: requests.post(
                        f"{base_url}/api/upload/video",
                        headers={"token": token_a},
                        data={"title": f"pytest_mile_{uuid.uuid4().hex[:8]}",
                              "description": "milestone log test", "categoryId": "1"},
                        files={"video": ("test_video.mp4", vf, "video/mp4"),
                               "cover": ("test_cover.png", cf, "image/png")},
                        timeout=30,
                    ).json(),
                )
            video_id = body["data"]["contentId"]
            assert f"contentId={video_id}" in line, f"发布里程碑应指向本 run 新建内容: {line[:200]}"

            with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
                body, line = milestone(
                    NEEDLE_ADD_POST,
                    lambda: requests.post(
                        f"{base_url}/api/upload/post",
                        headers={"token": token_a},
                        data={"title": f"pytest_mile_{uuid.uuid4().hex[:8]}",
                              "description": "milestone log test", "categoryId": "0"},
                        files={"cover": ("test_cover.png", cf, "image/png"),
                               "image": ("test_image.jpg", imf, "image/jpeg")},
                        timeout=30,
                    ).json(),
                )
            post_id = body["data"]["contentId"]
            assert f"contentId={post_id}" in line, f"动态发布里程碑应指向本 run 新建内容: {line[:200]}"

            _, line = milestone(
                NEEDLE_DELETE_CONTENT,
                lambda: requests.post(f"{base_url}/content/delete", params={"contentId": video_id},
                                      headers={"token": token_a}, timeout=10).json(),
            )
            assert f"contentId={video_id}" in line, f"删除里程碑应指向被删内容: {line[:200]}"
        finally:
            # 自清（不改 conftest 既有 fixture、不触碰共享数据）；失败路径不产生里程碑，断言不到这里
            for cid in (video_id, post_id):
                if cid is not None:
                    requests.post(f"{base_url}/content/delete", params={"contentId": cid},
                                  headers={"token": token_a}, timeout=10)

    def test_publish_requires_login_and_emits_no_milestone(self, base_url, test_files):
        """未登录上传被 401 拦下 → 不产生发布里程碑（里程碑只在真实成功路径上）。"""
        with open(test_files["video"], "rb") as vf, open(test_files["cover"], "rb") as cf:
            assert_no_milestone(
                NEEDLE_ADD_VIDEO,
                lambda: requests.post(
                    f"{base_url}/api/upload/video",
                    data={"title": f"pytest_mile_{uuid.uuid4().hex[:8]}",
                          "description": "milestone log test", "categoryId": "1"},
                    files={"video": ("test_video.mp4", vf, "video/mp4"),
                           "cover": ("test_cover.png", cf, "image/png")},
                    timeout=30,
                ).json(),
                expect_code=401,
            )


# ---------------------------------------------------------------------------
# ① 关系里程碑：关注 / 取关
# ---------------------------------------------------------------------------

@pytest.mark.consistency
class TestRelationMilestones:

    def test_follow_and_unfollow_emit_one_info_each(self, base_url, follower_pair):
        """`/follow/add`、`/follow/remove` 成功路径各恰好一条 INFO；用例末尾即为取关本身。"""
        follower, target = follower_pair
        headers = {"token": follower["token"]}
        pair = f"userId={follower['id']}, followedUserId={target['id']}"

        _, line = milestone(
            NEEDLE_FOLLOW,
            lambda: requests.post(f"{base_url}/follow/add",
                                  params={"followedUserId": target["id"]},
                                  headers=headers, timeout=10).json(),
        )
        assert pair in line, f"关注里程碑应记双方 id（与既有失败文案同字段口径）: {line[:200]}"

        _, line = milestone(
            NEEDLE_UNFOLLOW,
            lambda: requests.post(f"{base_url}/follow/remove",
                                  params={"followedUserId": target["id"]},
                                  headers=headers, timeout=10).json(),
        )
        assert pair in line, f"取关里程碑应记双方 id: {line[:200]}"

    def test_duplicate_follow_emits_no_milestone(self, base_url, follower_pair):
        """重复关注（409）不产生里程碑；随后取关还原状态。"""
        follower, target = follower_pair
        headers = {"token": follower["token"]}
        requests.post(f"{base_url}/follow/add", params={"followedUserId": target["id"]},
                      headers=headers, timeout=10)
        try:
            assert_no_milestone(
                NEEDLE_FOLLOW,
                lambda: requests.post(f"{base_url}/follow/add",
                                      params={"followedUserId": target["id"]},
                                      headers=headers, timeout=10).json(),
                expect_code=409,
            )
        finally:
            requests.post(f"{base_url}/follow/remove", params={"followedUserId": target["id"]},
                          headers=headers, timeout=10)


# ---------------------------------------------------------------------------
# ② ③ ④ 文件级不变式：分流 / 零重叠 / 无敏感值
# ---------------------------------------------------------------------------

@pytest.mark.consistency
def test_milestones_are_info_lines_in_system_log_only(base_url, follower_pair):
    """文件级证明：7 个补点只写 `system.log`、且只走业务 logger（与 T8 的 audit.log 零重叠）。

    先证明"读到了正确且非空的文件"，否则下面的"未泄漏"会**静默空过**（文件缺失时 leaked 恒为 []）。
    """
    for prefix, label in ((SYSTEM, "system.log"), (AUDIT, "audit.log"), (ACCESS, "access.log")):
        assert log_path(prefix), f"{label} 未生成（目录 {ACCESS_LOG_DIR}），本断言无从证伪"
        assert read_lines(prefix), f"{label} 无任何行，本断言无从证伪"
    # error.log 只要求存在：它阈值 SEVERE、单独定向跑本文件时可能为空（"里程碑 INFO 不进 error"
    # 与文件内容多少无关，故不强制非空）；其余三端必须非空，否则下面的"未泄漏"会静默空过
    assert log_path(ERROR), f"error.log 未生成（目录 {ACCESS_LOG_DIR}）"

    # 本 run 必须已产生里程碑行（用例顺序保证：本文件前面的用例已写过；单独跑本用例时靠 follower_pair 兜底）
    follower, target = follower_pair
    milestone(
        NEEDLE_FOLLOW,
        lambda: requests.post(f"{base_url}/follow/add", params={"followedUserId": target["id"]},
                              headers={"token": follower["token"]}, timeout=10).json(),
    )
    requests.post(f"{base_url}/follow/remove", params={"followedUserId": target["id"]},
                  headers={"token": follower["token"]}, timeout=10)

    hits = [ln for ln in read_lines(SYSTEM) if any(n in ln for n in MILESTONE_NEEDLES)]
    assert hits, "system.log 里应有本 run 的里程碑行（否则以下断言恒真）"
    for line in hits:
        assert MILESTONE_LINE_RE.match(line), (
            f"里程碑行形态不合规（须 level=INFO + logger=com.itheima.* + req=）: {line[:240]}"
        )
        assert " logger=audit " not in line and " logger=access " not in line, (
            f"里程碑不得写向专属 logger: {line[:240]}"
        )

    for prefix, label in ((AUDIT, "audit.log"), (ACCESS, "access.log"), (ERROR, "error.log")):
        leaked = [ln for ln in read_lines(prefix) if any(n in ln for n in MILESTONE_NEEDLES)]
        assert not leaked, (
            f"里程碑行泄进了 {label}（与 T8 审计的零重叠被破坏 / 输出端串通）共 {len(leaked)} 条，"
            f"前 2 条: {[ln[:160] for ln in leaked[:2]]}"
        )


@pytest.mark.consistency
def test_no_plaintext_phone_in_app_logs(base_url, follower_pair):
    """全文件不变式（T9 验收④，脱敏出口的自动化门禁）：

    四个应用输出端里**任何一行**都不出现 11 位手机号明文。扫描前先剥掉 `req=<16hex>`
    （请求 id 是 16 位十六进制，实测会命中 `1[3-9]\\d{9}` 造成假阳性）；剥离后剩下的命中即真嫌疑行。
    """
    follower, _ = follower_pair
    assert follower["phone"], "本 run 用户手机号非空（否则本用例无从证伪）"

    for prefix in (SYSTEM, AUDIT, ACCESS):
        assert log_path(prefix) and read_lines(prefix), f"{prefix} 无内容，本断言无从证伪"
    assert log_path(ERROR), f"{ERROR} 未生成，本断言无从证伪（空文件可接受：阈值 SEVERE）"

    offenders = []
    for prefix, label in ((SYSTEM, "system.log"), (AUDIT, "audit.log"),
                          (ACCESS, "access.log"), (ERROR, "error.log")):
        for line in read_lines(prefix):
            if PHONE_RE.search(REQ_TOKEN_RE.sub("req=<id>", line)):
                offenders.append(f"[{label}] {line[:200]}")

    assert not offenders, (
        f"应用日志出现 11 位手机号明文（须经 StringUtil.maskForLog 出口脱敏）共 {len(offenders)} 行：\n"
        + "\n".join(offenders[:5])
    )
