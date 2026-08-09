# -*- coding: utf-8 -*-
"""数据库一键备份脚本（Python，符合 AGENTS.md 脚本约定）。

用途：
    仅备份 MySQL 数据库 tvdatabase 到 D:\\dev\\WorkSpace\\VideoPlatform\\auto_backup\\<时间戳>\\。

约定（2026-08-09 用户决策）：
    * 数据库备份由脚本自动完成；备份完成后由用户手动迁移到工作区外。
    * 媒体资源（stone/video、image、cover）由用户手动打包备份，脚本不做。

用法：
    python tools/backup.py

退出码：
    0  成功
    1  其它错误（异常）
    2  mysqldump 不可用
    3  备份文件缺失/为空/内容校验失败

环境变量（可选）：
    DB_HOST / DB_PORT / DB_USER / DB_PASSWORD
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

AUTO_BACKUP_ROOT = Path(r"D:\dev\WorkSpace\VideoPlatform\auto_backup")

DB_NAME = "tvdatabase"
DB_HOST = os.environ.get("DB_HOST", "127.0.0.1")
DB_PORT = os.environ.get("DB_PORT", "3306")
DB_USER = os.environ.get("DB_USER", "root")
DB_PASSWORD = os.environ.get("DB_PASSWORD", "MySQL")

COMMON_MYSQLDUMP_PATHS = [
    Path(r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe"),
    Path(r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe"),
    Path(r"C:\Program Files\MySQL\MySQL Workbench 8.0\mysqldump.exe"),
]


def find_mysqldump() -> Path | None:
    found = shutil.which("mysqldump")
    if found:
        return Path(found)
    for candidate in COMMON_MYSQLDUMP_PATHS:
        if candidate.exists():
            return candidate
    return None


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def write_manifest(backup_dir: Path, dump_path: Path, started_at: str) -> None:
    size = dump_path.stat().st_size
    digest = sha256_of(dump_path)
    lines = [
        f"started_at={started_at}",
        f"finished_at={datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"host={DB_HOST}",
        f"port={DB_PORT}",
        f"database={DB_NAME}",
        f"dump_file={dump_path.name}",
        f"size_bytes={size}",
        f"sha256={digest}",
        "",
        "说明：数据库备份请由用户手动迁移到工作区外；媒体资源由用户手动打包备份。",
    ]
    (backup_dir / "manifest.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    mysqldump = find_mysqldump()
    if mysqldump is None:
        print("错误: 未找到 mysqldump，请确认 MySQL 已安装或将 mysqldump 加入 PATH")
        return 2

    AUTO_BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
    started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_dir = AUTO_BACKUP_ROOT / stamp
    backup_dir.mkdir(parents=True, exist_ok=False)
    dump_path = backup_dir / "db.sql"

    cmd = [
        str(mysqldump),
        f"--user={DB_USER}",
        f"--password={DB_PASSWORD}",
        f"--host={DB_HOST}",
        f"--port={DB_PORT}",
        "--single-transaction",
        "--routines",
        "--triggers",
        f"--result-file={dump_path}",
        DB_NAME,
    ]
    print(f"[{stamp}] 开始备份数据库 {DB_NAME} -> {dump_path}")
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    except subprocess.TimeoutExpired:
        print("错误: mysqldump 执行超时（600s）")
        return 1

    if proc.returncode != 0:
        print("错误: mysqldump 失败，退出码 " + str(proc.returncode))
        print(proc.stderr.strip() or proc.stdout.strip())
        return 1

    if not dump_path.exists() or dump_path.stat().st_size == 0:
        print("错误: 备份文件缺失或为空")
        return 3

    content = dump_path.read_text(encoding="utf-8", errors="replace")
    if "Dump completed" not in content:
        print("错误: 备份内容校验失败（未找到 Dump completed 标记）")
        return 3

    write_manifest(backup_dir, dump_path, started_at)
    print(f"备份成功: {dump_path} ({dump_path.stat().st_size} bytes)")
    print(f"清单: {backup_dir / 'manifest.txt'}")
    print("提醒: 请手动把该备份迁移到工作区外；媒体资源请自行打包备份。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
