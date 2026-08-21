# -*- coding: utf-8 -*-
"""TASK-037 管理员角色维护脚本（Python，符合 AGENTS.md 脚本约定）。

用法：
    python tools/admin.py --list
    python tools/admin.py --promote <userId|手机号>
    python tools/admin.py --demote <userId|手机号>

说明：
    * 数字参数按 userId 匹配，否则按手机号匹配。
    * 只提升/降级已有用户；新建管理员 = 先用注册接口建号再 --promote
      （脚本不做 Python BCrypt 哈希）。
    * --promote/--demote 是普通 UPDATE，不修改表结构，执行前无需整库备份。

退出码：
    0  成功
    1  其它错误
    2  mysql 客户端不可用
    3  用户不存在
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

MYSQL_HOST = "127.0.0.1"
MYSQL_PORT = "3306"
MYSQL_USER = "root"
MYSQL_PASSWORD = "MySQL"
DB_NAME = "tvdatabase"

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


def run_sql(mysql: Path, sql: str) -> str:
    cmd = [
        str(mysql),
        f"--user={MYSQL_USER}",
        f"--password={MYSQL_PASSWORD}",
        f"--host={MYSQL_HOST}",
        f"--port={MYSQL_PORT}",
        f"--database={DB_NAME}",
        "--batch",
        "--skip-column-names",
        "--execute",
        sql,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if proc.returncode != 0:
        raise RuntimeError("mysql 执行失败: " + (proc.stderr.strip() or proc.stdout.strip()))
    return proc.stdout


def list_users(mysql: Path) -> list[dict[str, str]]:
    output = run_sql(mysql, "SELECT id, username, phone, role FROM users ORDER BY id")
    rows: list[dict[str, str]] = []
    for line in output.strip().splitlines():
        parts = line.split("\t")
        if len(parts) >= 4:
            rows.append({"id": parts[0], "username": parts[1], "phone": parts[2], "role": parts[3]})
    return rows


def find_user(rows: list[dict[str, str]], key: str) -> dict[str, str] | None:
    if key.isdigit():
        return next((r for r in rows if r["id"] == key), None)
    return next((r for r in rows if r["phone"] == key), None)


def update_role(mysql: Path, user_id: str, role: int) -> None:
    run_sql(mysql, f"UPDATE users SET role = {role} WHERE id = {user_id}")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description="管理员角色维护工具")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--list", action="store_true", help="列出全部用户及其角色")
    group.add_argument("--promote", metavar="USER", help="提升为管理员（userId 或手机号）")
    group.add_argument("--demote", metavar="USER", help="取消管理员（userId 或手机号）")
    args = parser.parse_args()

    mysql = find_mysql()
    if mysql is None:
        print("错误: 未找到 mysql 客户端，请确认 MySQL 已安装或将 mysql 加入 PATH")
        return 2

    try:
        rows = list_users(mysql)
    except RuntimeError as e:
        print("错误:", e)
        return 1

    if args.list:
        print(f"{'id':<6} {'username':<20} {'phone':<15} role")
        for r in rows:
            print(f"{r['id']:<6} {r['username']:<20} {r['phone']:<15} {r['role']}")
        return 0

    key = args.promote if args.promote else args.demote
    target = find_user(rows, key)
    if target is None:
        match_type = "userId" if key.isdigit() else "手机号"
        print(f"错误: 未找到用户（按 {match_type} 匹配）: {key}")
        return 3

    new_role = 1 if args.promote else 0
    try:
        update_role(mysql, target["id"], new_role)
    except RuntimeError as e:
        print("错误:", e)
        return 1

    action = "提升为管理员" if args.promote else "取消管理员"
    print(
        f"完成: 用户 {target['username']}（id={target['id']}, phone={target['phone']}）"
        f"{action}，role: {target['role']} -> {new_role}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
