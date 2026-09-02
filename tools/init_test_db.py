# -*- coding: utf-8 -*-
"""用生产库备份覆盖重建独立测试库 TVDatabase_test（Python，符合 AGENTS.md 脚本约定）。

用途：
    表结构/需求变更后，从 .docs/DBBackup 下最新的生产库备份 db.sql
    （或用 --dump 显式指定）覆盖重建测试库，保证测试数据底座跟随生产结构。

用法：
    python tools/init_test_db.py                          # 默认：.docs/DBBackup 下最新 db.sql -> 3307 测试库
    python tools/init_test_db.py --dump path/to/db.sql    # 显式指定备份文件

连接（可用环境变量覆盖，默认指向测试库）：
    DB_HOST=127.0.0.1 / DB_PORT=3307 / DB_USER=root / DB_PASSWORD=ROOT123
    DB_NAME=TVDatabase_test

安全约定：
    * 默认只允许操作非 3306 端口（防止误 DROP 生产库）；DB_PORT=3306 时直接拒绝。
    * 无备份文件可用时不 DROP 任何库，直接报错退出。

退出码：
    0  成功
    1  参数/环境错误
    2  mysql 客户端不可用
    3  导入或校验失败
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DBBACKUP_DIR = PROJECT_ROOT / ".docs" / "DBBackup"

# 默认指向测试库（docker mysql8.4:3307）
DB_HOST = os.environ.get("DB_HOST", "127.0.0.1")
DB_PORT = os.environ.get("DB_PORT", "3307")
DB_USER = os.environ.get("DB_USER", "root")
DB_PASSWORD = os.environ.get("DB_PASSWORD", "ROOT123")
DB_NAME = os.environ.get("DB_NAME", "TVDatabase_test")

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
    """定位 .docs/DBBackup/<时间戳>/db.sql 中修改时间最新的那份。"""
    if not DEFAULT_DBBACKUP_DIR.exists():
        return None
    matches = sorted(
        DEFAULT_DBBACKUP_DIR.glob("*/db.sql"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    return matches[0] if matches else None


def main() -> int:
    parser = argparse.ArgumentParser(
        description="用生产库备份覆盖重建独立测试库（默认 .docs/DBBackup 最新 db.sql）"
    )
    parser.add_argument(
        "--dump", type=str, default=None,
        help="生产库备份 sql 文件路径（默认自动选取 .docs/DBBackup 下最新 db.sql）",
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
            print("错误: .docs/DBBackup 下没有备份 sql，请用 --dump 指定，或先运行 tools/backup.py")
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
    print(f"初始化完成: {DB_HOST}:{DB_PORT}/{DB_NAME} content 行数 = {count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())