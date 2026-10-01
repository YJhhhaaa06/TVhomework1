# -*- coding: utf-8 -*-
"""
test_feed_debounce.py - 关注·取关去抖端到端留证（feed3-T33-A）

背景（feed3-T33-A 落地**投递侧 per-user 尾沿（trailing）去抖**，窗口 =
`feed.rebuild.debounceMillis`，默认 1000ms）：同一用户在一个窗口内的多次关注 / 取关只投**一条**
重建消息。为什么需要：`FeedRebuildService` 的 `SET NX EX` 锁注释自证"只做并发去重优化、
不聚合请求"，而 T31 后投递是单 worker 串行、消费是单 channel/prefetch=1 串行 ⇒ N 条重建消息基本
不重叠 ⇒ 那把锁几乎永远拿得到 ⇒ **N 次连续关注 ≈ N 次真实整窗重算**。

本文件证两点（对应 T33-A 验收）：
  ① **去抖生效**：1s 窗口内连续关注 3 个作者 ⇒ 应用日志出现
     `收件箱重建请求已去抖合并 ... userId=<actor>, merged=3` 结论行，且 `merged >= 2`；
  ② **窗口最终正确**：3 个作者的内容都经 `/feed` 可见（重建确已执行、且反映最终关注关系）。

对照（负向）：两次关注间隔 > 窗口 ⇒ **不出现**合并行（各自独立触发）；负向断言配"确认等待"
（`ABSENCE_CONFIRM_SECONDS`）消除"日志尚未落盘"的假通过。

前置守卫（非默认跑法一律 skip，避免 U-35 同族假失败）：
  - `FEED_BIGV_THRESHOLD` 显式 <= 1 ⇒ 被关注作者会被判大V ⇒ 内容不进收件箱 ⇒ 前提不成立 ⇒ skip；
  - 设置了 `FEED_BIGV_CONFIGFILE` ⇒ 阈值由外置文件决定、不可预判 ⇒ skip。

数据自建自清：注册全新 `testA_fdebounce_*` 用户（命中 tools/cleanup_data.py 的 `testA_` 白名单），
内容在 finally 里由作者走真实删除路径软删。

跑法：随 `python tools\\tv.py test` 默认执行（无额外环境变量要求）。
"""

import glob
import os
import re
import time
import uuid
from pathlib import Path

import pytest
import requests

import conftest

_PROJECT_ROOT = Path(__file__).resolve().parents[3]

# 收敛窗口：窗口到期 + 投递 + 重建 + 消费（本地毫秒级），10s 上限只吸收抖动
POLL_TIMEOUT_SECONDS = 10.0
POLL_INTERVAL_SECONDS = 0.2
# 负向断言的"确认等待"：真 fanout / 重建远快于此
ABSENCE_CONFIRM_SECONDS = 2.0

_MERGED_RE = re.compile(r"merged=(\d+)")


# ---------------------------------------------------------------------------
# 前置守卫 / 窗口同源解析
# ---------------------------------------------------------------------------

def _require_inbox_leg_available():
    """被关注作者不得被判为大V（否则其内容不进收件箱、用例前提不成立）——非默认跑法直接 skip。"""
    threshold = (os.environ.get("FEED_BIGV_THRESHOLD") or "").strip()
    if threshold.isdigit() and int(threshold) <= 1:
        pytest.skip("FEED_BIGV_THRESHOLD<=1：作者会被判为大V、内容不进收件箱（非默认跑法）")
    if (os.environ.get("FEED_BIGV_CONFIGFILE") or "").strip():
        pytest.skip("FEED_BIGV_CONFIGFILE 已设置：阈值由外置文件决定、不可预判")


def _debounce_window_ms():
    """与应用同源解析去抖窗口（env 覆盖链 -> app.properties -> 默认 1000）。"""
    env = (os.environ.get("FEED_REBUILD_DEBOUNCEMILLIS") or "").strip()
    if env.isdigit():
        return int(env)
    props = _PROJECT_ROOT / "src" / "main" / "resources" / "app.properties"
    for line in props.read_text(encoding="utf-8").splitlines():
        if line.startswith("feed.rebuild.debounceMillis="):
            raw = line.split("=", 1)[1].strip()
            if raw.isdigit():
                return int(raw)
    return 1000


WINDOW_MS = _debounce_window_ms()


# ---------------------------------------------------------------------------
# HTTP 辅助
# ---------------------------------------------------------------------------

def _register_fresh_user(tag):
    """注册一个全新的 testA_ 前缀用户（无任何关注关系），返回 id/token/username。"""
    unique = uuid.uuid4().hex[:8]
    suffix = str(int(unique, 16))[-8:].zfill(8)
    username = f"testA_fdebounce_{tag}_{unique}"
    body = conftest.register_user(username, f"137{suffix}", "abc123")
    assert body.get("code") == 200, f"注册临时用户失败: {body}"
    data = body.get("data") or {}
    return {"id": data.get("id"), "token": data.get("token"), "username": username}


def _follow(base_url, token, followed_user_id, action):
    """follow/add | follow/remove（action 取值见 test_feed_rebuild 同族写法）。"""
    resp = requests.post(
        f"{base_url}/follow/{action}",
        headers={"token": token, "Content-Type": "application/x-www-form-urlencoded"},
        data={"followedUserId": str(followed_user_id)},
        timeout=10,
    )
    return resp.json()


def _post_content(base_url, token, title, test_files):
    """POST /api/upload/post 建一条图文内容（真实写路径），返回 contentId。"""
    with open(test_files["cover"], "rb") as cf, open(test_files["image"], "rb") as imf:
        resp = requests.post(
            f"{base_url}/api/upload/post",
            headers={"token": token},
            data={"title": title, "description": "feed3-T33-A 去抖留证", "categoryId": "0"},
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
    """作者软删（真实路径），finally 兜底用。"""
    return requests.post(
        f"{base_url}/content/delete",
        params={"contentId": content_id},
        headers={"token": token},
        timeout=10,
    ).json()


def _feed_ids(base_url, token, page_size=100):
    """`/feed` 当前可见窗口的 contentId 列表（信封 = common.model.dto.PageResult，条目字段 = id）。"""
    resp = requests.get(
        f"{base_url}/feed",
        params={"page": 1, "pageSize": page_size},
        headers={"token": token},
        timeout=10,
    )
    body = resp.json()
    data = body.get("data") or {}
    return {item.get("id") for item in (data.get("list") or [])}


def _await_feed_contains(base_url, token, expected_ids, timeout_seconds):
    """轮询 `/feed` 直到 `expected_ids` 全部可见（有界等待，不做固定 sleep 兜底）。"""
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        if expected_ids.issubset(_feed_ids(base_url, token)):
            return True
        time.sleep(POLL_INTERVAL_SECONDS)
    return expected_ids.issubset(_feed_ids(base_url, token))


# ---------------------------------------------------------------------------
# 应用日志读取（口径同 test_log_outputs：轮转文件取最新一个，严格 UTF-8）
# ---------------------------------------------------------------------------

def _system_log_path():
    candidates = [p for p in glob.glob(os.path.join(conftest.ACCESS_LOG_DIR, "system.log*"))
                  if not p.endswith(".lck")]
    return max(candidates, key=os.path.getmtime) if candidates else None


def _log_lines(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read().splitlines()


def _log_snapshot():
    path = _system_log_path()
    return (path, len(_log_lines(path)) if path else 0)


def _lines_after(snapshot):
    """快照之后新增的行；期间发生轮转则返回整份当前文件（仅用于"是否出现某行"的粗判）。"""
    path, count = snapshot
    current = _system_log_path()
    if current is None:
        return []
    lines = _log_lines(current)
    if path != current or len(lines) < count:
        return lines
    return lines[count:]


def _merged_line_for(lines, user_id):
    """找该用户的去抖合并结论行（恰一条即"窗口内多次被合并"的直接证据）。"""
    for line in lines:
        if "已去抖合并" in line and f"userId={user_id}," in line:
            return line
    return None


def _await_line(matcher, snapshot, timeout_seconds):
    """轮询"快照之后的新增行"直到 matcher 命中（有界）；超时返回 None。

    为什么要轮询而不是"等 /feed 可见后再读"：全新用户的收件箱尚未同步时，读侧会**回退纯拉**
    （`feed_inbox_sync` 无行 ⇒ synced=false），纯拉**立刻**就能看到内容 ⇒ 用 `/feed` 可见当"重建完成"
    的信号会早于去抖窗口到期（本文件首版即栽在这里）。故此处以**日志行本身**为收敛信号。
    """
    deadline = time.time() + timeout_seconds
    while True:
        hit = matcher(_lines_after(snapshot))
        if hit is not None:
            return hit
        if time.time() >= deadline:
            return None
        time.sleep(POLL_INTERVAL_SECONDS)


# ---------------------------------------------------------------------------
# 用例
# ---------------------------------------------------------------------------

def test_burst_follows_merge_into_single_rebuild(base_url, test_files):
    """① 1s 窗口内连续关注 3 个作者 ⇒ 合并为一次重建投递（日志结论行 + 窗口最终正确）。"""
    _require_inbox_leg_available()
    actor = _register_fresh_user("burst")
    authors = [_register_fresh_user(f"burst_a{i}") for i in range(3)]
    content_ids = []
    try:
        for index, author in enumerate(authors):
            content_ids.append(_post_content(
                base_url, author["token"], f"debounce_burst_{index}_{uuid.uuid4().hex[:6]}", test_files))

        snapshot = _log_snapshot()
        for author in authors:
            body = _follow(base_url, actor["token"], author["id"], "add")
            assert body.get("code") == 200, f"关注失败: {body}"

        # 等"窗口到期 → 投递 → 重建"：以**合并结论行**为收敛信号（窗口 1000ms + 投递 + 消费）
        merged_line = _await_line(
            lambda lines: _merged_line_for(lines, actor["id"]), snapshot, POLL_TIMEOUT_SECONDS)
        assert merged_line is not None, (
            f"窗口到期后应出现去抖合并结论行（1s 内连续 3 次关注 ⇒ 合并），窗口 {WINDOW_MS}ms")
        merged = int(_MERGED_RE.search(merged_line).group(1))
        assert merged >= 2, f"合并计数应 >= 2（连续关注被合并），实际: {merged_line}"

        assert _await_feed_contains(base_url, actor["token"], set(content_ids), POLL_TIMEOUT_SECONDS), \
            "合并后的那一次重建应使 3 个作者的内容全部可见（窗口反映最终关注关系）"
    finally:
        for content_id, author in zip(content_ids, authors):
            _delete_content(base_url, author["token"], content_id)


def test_spaced_follows_are_not_merged(base_url, test_files):
    """② 对照：两次关注间隔 > 窗口 ⇒ 不合并（各自独立触发；负向断言配确认等待）。"""
    _require_inbox_leg_available()
    actor = _register_fresh_user("spaced")
    authors = [_register_fresh_user(f"spaced_a{i}") for i in range(2)]
    content_ids = []
    try:
        for index, author in enumerate(authors):
            content_ids.append(_post_content(
                base_url, author["token"], f"debounce_spaced_{index}_{uuid.uuid4().hex[:6]}", test_files))

        snapshot = _log_snapshot()
        assert _follow(base_url, actor["token"], authors[0]["id"], "add").get("code") == 200
        time.sleep(WINDOW_MS / 1000.0 * 2.5)   # 明确跨过一个窗口
        assert _follow(base_url, actor["token"], authors[1]["id"], "add").get("code") == 200

        # 哨兵：先证明"日志读取路径有效"（能读到关注成功行）——否则下面的负向断言是**假绿**
        sentinel = _await_line(
            lambda lines: next((ln for ln in lines
                                if "关注成功" in ln and f"userId={actor['id']}," in ln), None),
            snapshot, POLL_TIMEOUT_SECONDS)
        assert sentinel is not None, "哨兵失败：读不到关注成功日志行（日志读取路径异常）"

        assert _await_feed_contains(base_url, actor["token"], set(content_ids), POLL_TIMEOUT_SECONDS), \
            "两次独立重建后两条内容都应可见"

        time.sleep(ABSENCE_CONFIRM_SECONDS)   # 确认等待：排除"日志尚未落盘"的假通过
        assert _merged_line_for(_lines_after(snapshot), actor["id"]) is None, \
            "两次关注间隔 > 一个窗口 ⇒ 不应出现合并结论行"
    finally:
        for content_id, author in zip(content_ids, authors):
            _delete_content(base_url, author["token"], content_id)
