# -*- coding: utf-8 -*-
"""用生产库备份覆盖重建独立测试库 TVDatabase_test（Python，符合 AGENTS.md 脚本约定）。

用途：
    表结构/需求变更后，从 .docs/archive/DBbackups 下最新的生产库备份 db.sql
    （或用 --dump 显式指定）覆盖重建测试库，保证测试数据底座跟随生产结构；
    导入完成后自动追加【基线种子】（1 个管理员 + 1 条含评论链的基线内容），
    种子内容与维护约定见 .docs/说明书/TEST_SEED.md。

用法：
    python tools/init_test_db.py                          # 默认：.docs/archive/DBbackups 最新 db.sql -> 3307 测试库
    python tools/init_test_db.py --dump path/to/db.sql    # 显式指定备份文件

连接（统一来源 tools/env/，T6）：
    固定读 test.conf（本脚本语义=重建测试库，不读 active.conf、不开 prod 后门）；
    环境变量 DB_* 优先级最高。
    DB_HOST=127.0.0.1 / DB_PORT=3307 / DB_USER=root / DB_PASSWORD=ROOT123
    DB_NAME=TVDatabase_test

安全约定：
    * 默认只允许操作非 3306 端口（防止误 DROP 生产库）；DB_PORT=3306 时直接拒绝。
    * 无备份文件可用时不 DROP 任何库，直接报错退出。

退出码：
    0  成功（含基线种子应用与校验通过）
    1  参数/环境错误
    2  mysql 客户端不可用
    3  导入、种子或校验失败
"""

from __future__ import annotations

import argparse
import db_config
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DBBACKUP_DIR = PROJECT_ROOT / ".docs" / "archive" / "DBbackups"

# ---- 基线种子（T3，详见 .docs/说明书/TEST_SEED.md）----
SEED_ADMIN_ID = 1  # 提升现有用户（一号员工）为管理员，不新建账号
SEED_CONTENT_TITLE_PREFIX = "seed_baseline"
SEED_CONTENT_TITLE = "seed_baseline_test_content"
# 基线内容标题 LIKE 模式（\_ 转义下划线）。刻意避开 pytest_/smoke_ 前缀：
# 否则会被 cleanup_data.py 按测试内容自动清理删除。
_SEED_LIKE = f"{SEED_CONTENT_TITLE_PREFIX}\\_%"

# 连接参数统一来源 tools/env/（T6）：本脚本固定重建测试库 → 强制 test.conf，
# 绝对不读 active.conf / prod（与下方 3306 安全门禁互为双保险）。
db_config.use(force_test=True)
DB_HOST = db_config.DB_HOST
DB_PORT = db_config.DB_PORT
DB_USER = db_config.DB_USER
DB_PASSWORD = db_config.DB_PASSWORD
DB_NAME = db_config.DB_NAME

COMMON_MYSQL_PATHS = [
    Path(r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"),
    Path(r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"),
]


def find_mysql() -> Path | None:
    found = shutil.which("mysql")
    if found:
        return Path(found)
    for candidate in COMMON_MYSQL_PATHS:
        if candidate.exists():
            return candidate
    return None


def mysql_base_cmd(mysql: Path) -> list:
    return [
        str(mysql),
        f"--user={DB_USER}",
        f"--password={DB_PASSWORD}",
        f"--host={DB_HOST}",
        f"--port={DB_PORT}",
    ]


def latest_backup_sql() -> Path | None:
    """定位 .docs/archive/DBbackups/<时间戳>/db.sql 中修改时间最新的那份。"""
    if not DEFAULT_DBBACKUP_DIR.exists():
        return None
    matches = sorted(
        DEFAULT_DBBACKUP_DIR.glob("*/db.sql"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    return matches[0] if matches else None


def apply_baseline_seed(base: list[str]) -> tuple[bool, str]:
    """导入备份后追加固定基线种子（幂等，可重复 init；见 .docs/说明书/TEST_SEED.md）。

    基线内容：
        * 管理员：users.id=SEED_ADMIN_ID 提升为 role=1（现有用户，不新建账号）。
        * 基线内容：一条**type=2 图文**（无 content_media 行）+ 3 条评论链（主楼 /
          回复 / 楼中楼@）。必须用图文：缓存构建 buildContentMedia 对 type=1 视频
          强制要求 content_media 视频行，缺失会抛「资源已丢失」导致应用启动失败；
          图文允许无媒体行（纯文字内容，媒体扫描视为完整）。标题避开
          pytest_/smoke_ 前缀，避免被 cleanup_data.py 自动清理。
    返回 (是否成功, 摘要或错误文本)。
    """
    seed_sql = (
        # 1) 管理员基线（幂等 UPDATE）
        f"UPDATE `users` SET `role` = 1 WHERE `id` = {SEED_ADMIN_ID}; "
        # 2) 幂等清理旧种子（输入 dump 若已含种子则先删除，防止重复插入）
        f"DELETE FROM `comment` WHERE `content_id` IN "
        f"(SELECT `id` FROM `content` WHERE `title` LIKE '{_SEED_LIKE}'); "
        f"DELETE FROM `content` WHERE `title` LIKE '{_SEED_LIKE}'; "
        # 3) 基线内容：type=2 图文（无 content_media 行：缓存构建对图文不要求媒体行，
        #    纯文字内容由媒体扫描视为完整，file_exists 回写 1）；
        #    comment_count 显式设值（=3），避免计数漂移
        "INSERT INTO `content` "
        "(`user_id`,`type`,`title`,`description`,`category_id`,`comment_count`,"
        "`like_count`,`is_deleted`,`create_time`,`update_time`,`file_exists`,"
        "`last_verify_time`,`comment_enabled`) "
        f"VALUES ({SEED_ADMIN_ID}, 2, '{SEED_CONTENT_TITLE}', "
        "'T3 baseline seed content with comments', 0, 3, 0, 0, NOW(), NOW(), 1, NULL, 1); "
        "SET @cid := LAST_INSERT_ID(); "
        # 4) 主楼评论（作者=基线管理员）
        "INSERT INTO `comment` (`content_id`,`user_id`,`content`,`parent_id`,`reply_to_user_id`) "
        f"VALUES (@cid, {SEED_ADMIN_ID}, 'seed baseline comment main', NULL, NULL); "
        "SET @c1 := LAST_INSERT_ID(); "
        # 5) 回复（作者=库内既有用户 id=2）
        "INSERT INTO `comment` (`content_id`,`user_id`,`content`,`parent_id`,`reply_to_user_id`) "
        "VALUES (@cid, 2, 'seed baseline reply', @c1, NULL); "
        "SET @c2 := LAST_INSERT_ID(); "
        # 6) 楼中楼（parent=回复，@回复作者=2）
        "INSERT INTO `comment` (`content_id`,`user_id`,`content`,`parent_id`,`reply_to_user_id`) "
        f"VALUES (@cid, {SEED_ADMIN_ID}, 'seed baseline reply-to-reply', @c2, 2);"
    )
    proc = subprocess.run(
        base + ["--default-character-set=utf8mb4", "--database", DB_NAME,
                "--execute", seed_sql],
        capture_output=True,
        text=True,
        timeout=120,
    )
    if proc.returncode != 0:
        tail = (proc.stderr or proc.stdout or "").strip()
        return False, tail[:500] if tail else "mysql 执行失败（无输出）"

    verify_sql = (
        f"SELECT `role` FROM `users` WHERE `id` = {SEED_ADMIN_ID}; "
        f"SELECT COUNT(*) FROM `comment` WHERE `content_id` IN "
        f"(SELECT `id` FROM `content` WHERE `title` LIKE '{_SEED_LIKE}'); "
        f"SELECT `title` FROM `content` WHERE `title` LIKE '{_SEED_LIKE}' LIMIT 1;"
    )
    proc = subprocess.run(
        base + ["--batch", "--skip-column-names", "--database", DB_NAME,
                "--execute", verify_sql],
        capture_output=True,
        text=True,
        timeout=60,
    )
    if proc.returncode != 0:
        return False, (proc.stderr or proc.stdout or "").strip()[:500]
    lines = [ln.strip() for ln in (proc.stdout or "").splitlines() if ln.strip()]
    admin_role = lines[0] if len(lines) > 0 else "?"
    comment_cnt = lines[1] if len(lines) > 1 else "?"
    seed_title = lines[2] if len(lines) > 2 else "?"
    report = (f"管理员 id={SEED_ADMIN_ID} role={admin_role}；"
              f"基线内容 '{seed_title}' 评论 {comment_cnt} 条")
    if admin_role != "1" or comment_cnt != "3" or seed_title != SEED_CONTENT_TITLE:
        return False, "校验不通过: " + report
    return True, report


def main() -> int:
    parser = argparse.ArgumentParser(
        description="用生产库备份覆盖重建独立测试库（默认 .docs/archive/DBbackups 最新 db.sql，导入后追加基线种子）"
    )
    parser.add_argument(
        "--dump", type=str, default=None,
        help="生产库备份 sql 文件路径（默认自动选取 .docs/archive/DBbackups 下最新 db.sql）",
    )
    args = parser.parse_args()

    # 安全门禁：只允许重建非 3306 端口，防止误 DROP 生产库
    if DB_PORT == "3306":
        print(f"错误: DB_PORT=3306（生产库）被安全门禁拒绝，测试库需用独立端口（默认 3307）")
        return 1

    mysql = find_mysql()
    if mysql is None:
        print("错误: 未找到 mysql 客户端，请确认 MySQL 已安装或将 mysql 加入 PATH")
        return 2

    if args.dump:
        dump_path = Path(args.dump)
        if not dump_path.exists():
            print(f"错误: 指定备份不存在: {dump_path}")
            return 1
    else:
        dump_path = latest_backup_sql()
        if dump_path is None:
            print("错误: .docs/archive/DBbackups 下没有备份 sql，请用 --dump 指定，或先运行 tools/backup.py 并迁移备份到该目录")
            return 1
    print(f"使用备份: {dump_path}")

    base = mysql_base_cmd(mysql)

    # 1) DROP + CREATE 测试库
    drop_create = (
        f"DROP DATABASE IF EXISTS `{DB_NAME}`; "
        f"CREATE DATABASE `{DB_NAME}` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
    )
    proc = subprocess.run(
        base + ["--execute", drop_create],
        capture_output=True,
        text=True,
        timeout=60,
    )
    if proc.returncode != 0:
        print("错误: 重建数据库失败")
        print(proc.stderr.strip() or proc.stdout.strip())
        return 3

    # 2) 导入备份 sql（stdin 喂入，避免 shell 重定向差异）
    with open(dump_path, "rb") as fh:
        proc = subprocess.run(
            base + ["--default-character-set=utf8mb4", "--database", DB_NAME],
            stdin=fh,
            capture_output=True,
            timeout=600,
        )
    if proc.returncode != 0:
        print("错误: 备份导入失败")
        tail = proc.stderr or b""
        print(tail[-2000:].decode("utf-8", errors="replace"))
        return 3

    # 3) 校验
    check = subprocess.run(
        base + ["--batch", "--skip-column-names", "--database", DB_NAME,
                "--execute", "SELECT COUNT(*) FROM content;"],
        capture_output=True,
        text=True,
        timeout=60,
    )
    if check.returncode != 0:
        print("错误: 校验失败（content 表不存在或连接异常）")
        print(check.stderr.strip() or check.stdout.strip())
        return 3
    count = (check.stdout or "").strip()

    # 4) 追加基线种子（1 个管理员 + 1 条含评论链的基线内容，幂等）
    ok, detail = apply_baseline_seed(base)
    if not ok:
        print("错误: 基线种子失败")
        print(detail)
        return 3

    print(f"初始化完成: {DB_HOST}:{DB_PORT}/{DB_NAME} content 行数 = {count}")
    print(f"基线种子: {detail}")
    return 0


if __name__ == "__main__":
    sys.exit(main())