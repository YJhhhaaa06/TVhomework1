# -*- coding: utf-8 -*-
"""test_admin.py - 管理员权限与媒体运维集成测试（阶段八 TASK-047）。

覆盖：
* /api/admin/media/me 与 /api/admin/media/list 的 401 / 403 / 200 三角色断言
* 管理员 /api/admin/media/scan 扫描结构与 restore 错误路径
* 正向 restore 仅针对"孤儿且缺失"的媒体项，避免污染真实内容数据
"""

import os
import subprocess
import sys
import uuid
from pathlib import Path

import pytest
import requests

PROJECT_ROOT = Path(__file__).resolve().parents[3]


def _run_admin(*args):
    """调用 tools/admin.py，失败即抛出。"""
    proc = subprocess.run(
        [sys.executable, str(PROJECT_ROOT / "tools" / "admin.py"), *args],
        cwd=str(PROJECT_ROOT),
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert proc.returncode == 0, (
        f"admin.py {args} failed: stdout={proc.stdout!r} stderr={proc.stderr!r}"
    )
    return proc.stdout


@pytest.fixture(scope="module")
def admin_user(base_url):
    """注册临时用户并提升为管理员，用例结束降级恢复。"""
    unique = uuid.uuid4().hex[:8]
    suffix = str(int(unique, 16))[-8:].zfill(8)
    payload = {
        "username": f"admin_{unique}",
        "phone": f"137{suffix}",
        "password": "abc123",
    }
    reg = requests.post(
        f"{base_url}/user/register", json=payload, timeout=10
    ).json()
    assert reg.get("code") == 200, f"管理员注册失败: {reg}"
    data = reg.get("data", {})
    user_id = data.get("id")
    assert user_id is not None, f"注册响应缺少 id: {reg}"
    _run_admin("--promote", str(user_id))
    yield {"id": user_id, "token": data.get("token"), "phone": payload["phone"]}
    _run_admin("--demote", str(user_id))


@pytest.mark.admin
class TestAdminPermission:

    def test_media_me_without_token_401(self, base_url):
        body = requests.get(f"{base_url}/api/admin/media/me", timeout=10).json()
        assert body.get("code") == 401, body

    def test_media_list_without_token_401(self, base_url):
        body = requests.get(f"{base_url}/api/admin/media/list", timeout=10).json()
        assert body.get("code") == 401, body

    def test_media_me_normal_user_403(self, base_url, token_a):
        body = requests.get(
            f"{base_url}/api/admin/media/me",
            headers={"token": token_a},
            timeout=10,
        ).json()
        assert body.get("code") == 403, body

    def test_media_list_normal_user_403(self, base_url, token_a):
        body = requests.get(
            f"{base_url}/api/admin/media/list",
            headers={"token": token_a},
            timeout=10,
        ).json()
        assert body.get("code") == 403, body

    def test_media_me_admin_200(self, base_url, admin_user):
        body = requests.get(
            f"{base_url}/api/admin/media/me",
            headers={"token": admin_user["token"]},
            timeout=10,
        ).json()
        assert body.get("code") == 200, body
        assert body.get("data") is True, body

    def test_media_list_admin_200(self, base_url, admin_user):
        body = requests.get(
            f"{base_url}/api/admin/media/list",
            headers={"token": admin_user["token"]},
            timeout=60,
        ).json()
        assert body.get("code") == 200, body
        data = body.get("data")
        assert isinstance(data, dict), body
        for key in ("scanTime", "total", "existing", "missing", "invalid"):
            assert key in data, body


@pytest.mark.admin
class TestMediaOps:

    def test_scan_admin_200_structure(self, base_url, admin_user):
        body = requests.post(
            f"{base_url}/api/admin/media/scan",
            headers={"token": admin_user["token"]},
            timeout=60,
        ).json()
        assert body.get("code") == 200, body
        data = body.get("data")
        assert isinstance(data, dict), body
        for key in ("scanTime", "total", "existing", "missing", "invalid",
                    "notScanned", "orphanMediaIds", "contentsWithoutMedia"):
            assert key in data, body

    def test_restore_without_media_id_400(self, base_url, admin_user):
        body = requests.post(
            f"{base_url}/api/admin/media/restore",
            headers={"token": admin_user["token"]},
            timeout=30,
        ).json()
        assert body.get("code") == 400, body

    def test_restore_without_file_400(self, base_url, admin_user):
        body = requests.post(
            f"{base_url}/api/admin/media/restore",
            params={"mediaId": 1},
            headers={"token": admin_user["token"]},
            files={"dummy": (None, "x")},
            timeout=30,
        ).json()
        assert body.get("code") == 400, body

    def test_restore_nonexistent_media_404(self, base_url, admin_user):
        body = requests.post(
            f"{base_url}/api/admin/media/restore",
            params={"mediaId": 999999999},
            headers={"token": admin_user["token"]},
            files={"file": ("restore.mp4", b"x" * 1024, "application/octet-stream")},
            timeout=30,
        ).json()
        assert body.get("code") == 404, body

    def test_restore_orphan_missing_positive(self, base_url, admin_user):
        """仅对孤儿且缺失的媒体执行正向恢复，测后删除文件并回扫复位。"""
        scan = requests.post(
            f"{base_url}/api/admin/media/scan",
            headers={"token": admin_user["token"]},
            timeout=60,
        ).json()
        assert scan.get("code") == 200, scan
        data = scan.get("data", {})
        orphans = set(data.get("orphanMediaIds") or [])
        items = data.get("items") or []
        target = next(
            (it for it in items
             if it.get("mediaId") in orphans and it.get("status") == "MISSING"),
            None,
        )
        if target is None:
            pytest.skip("无孤儿且缺失的媒体项，跳过正向恢复")

        url = target["url"]
        ext = url.rsplit(".", 1)[-1] if "." in url else "bin"
        resp = requests.post(
            f"{base_url}/api/admin/media/restore",
            params={"mediaId": target["mediaId"]},
            headers={"token": admin_user["token"]},
            files={"file": (f"restore.{ext}", b"x" * 1024, "application/octet-stream")},
            timeout=60,
        )
        body = resp.json()
        assert body.get("code") == 200, body
        assert body.get("data", {}).get("fileExists") is True, body

        # 清理：删除恢复出的临时文件并回扫复位 file_exists 标记
        expected = target.get("expectedPath")
        if expected and os.path.isfile(expected):
            os.remove(expected)
        requests.post(
            f"{base_url}/api/admin/media/scan",
            headers={"token": admin_user["token"]},
            timeout=60,
        )
