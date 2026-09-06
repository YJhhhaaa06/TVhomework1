# -*- coding: utf-8 -*-
"""媒体目录统一解析（T2：测试/生产媒体目录隔离 + 工具按库判定）。

体系：
    * 测试库（TVDatabase_test）→ 测试媒体目录 media-test（env TV_TEST_MEDIA_ROOT 可覆盖；
      默认值需与 tools/run_tests.py 的 TEST_MEDIA_ROOT、src/test/python/conftest.py 的
      STONE_DIR 三处同步修改）
    * 其它库（生产）→ app.properties upload.path > 环境变量 TV_STONE_DIR > 默认生产 stone

按库判定保证：无论走 tools/tv.py 还是裸跑本类工具，只要连接的是测试库，
媒体扫描只触及 media-test，绝不会误扫/误清生产目录。

用法：
    import db_config
    import media_paths
    db_config.use()                       # 或 use("prod") / force_test
    root = media_paths.resolve_upload_root(db_config.DB_NAME)
"""

from __future__ import annotations

import os
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
APP_PROPERTIES = PROJECT_ROOT / "src" / "main" / "resources" / "app.properties"

# 与 run_tests.TEST_DB_NAME / tools/env/test.conf 一致（按库判定的测试库锚点）
TEST_DB_NAME = "TVDatabase_test"

# 测试媒体根默认值：与 tools/run_tests.py TEST_MEDIA_ROOT、src/test/python/conftest.py
# STONE_DIR 默认值三处同步（T2）；env TV_TEST_MEDIA_ROOT 可覆盖
TEST_MEDIA_ROOT_DEFAULT = Path(r"D:\data\projects\VideoPlatform\media-test")

# 生产媒体根（app.properties upload.path / TV_STONE_DIR 缺失时的兜底）
PROD_UPLOAD_ROOT_DEFAULT = Path("D:/data/projects/VideoPlatform/stone")

# 与 FileUploadService / MediaAuditService 一致的媒体子目录
MEDIA_DIRS = ("video", "image", "cover")


def read_app_properties(path: Path) -> dict[str, str]:
    """解析 app.properties（key=value，# 注释），文件缺失或解析失败返回空。"""
    props: dict[str, str] = {}
    if not path.is_file():
        return props
    try:
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    except OSError:
        return props
    return props


def test_media_root() -> Path:
    """测试媒体根目录（run_tests 的注入目标）：env TV_TEST_MEDIA_ROOT > 默认值。"""
    env = os.environ.get("TV_TEST_MEDIA_ROOT")
    return Path(env).resolve() if env else TEST_MEDIA_ROOT_DEFAULT.resolve()


def resolve_upload_root(db_name: str) -> Path:
    """按连接的库判定媒体根；测试库 → 测试目录，其它（生产）→ app.properties 优先。

    生产分支顺序（与既有工具一致）：app.properties upload.path > TV_STONE_DIR > 默认。
    """
    if db_name == TEST_DB_NAME:
        return test_media_root()
    props = read_app_properties(APP_PROPERTIES)
    if props.get("upload.path"):
        return Path(props["upload.path"]).resolve()
    env = os.environ.get("TV_STONE_DIR")
    if env:
        return Path(env).resolve()
    return PROD_UPLOAD_ROOT_DEFAULT.resolve()