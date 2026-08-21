# -*- coding: utf-8 -*-
"""TASK-007 数据清理脚本（Python，符合 AGENTS.md 脚本约定）。

默认只做只读预览（dry-run）；必须显式加 --execute 才会写数据库。

清理范围：
    1. 测试内容污染：title 为 pytest_/smoke_ 前缀的 content，
       以及它们关联的 content_media / comment / comment_like / content_like。
       （2026-08-09 用户决策：中文标题的测试内容保留，不纳入默认范围。）
    2. 孤儿 content_media（content_id 指向不存在的 content）。
    3. 孤儿 comment_like（comment_id 指向不存在的 comment）。
    4. 可选：遗留表 video / videoinfo（需 --drop-legacy）。

用法：
    python tools/cleanup_data.py                      # 只读预览
    python tools/cleanup_data.py --exclude 100        # 预览时排除 content 100
    python tools/cleanup_data.py --execute            # 执行 DML 清理（自动先备份）
    python tools/cleanup_data.py --execute --drop-legacy   # 再删除遗留表

退出码：
    0  成功 / 预览完成
    1  其它错误
    2  mysql 客户端不可用
    3  执行前备份失败
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
REPORT_DIR = PROJECT_ROOT / ".docs"

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


def parse_ids(output: str) -> list[int]:
    return [int(line.strip()) for line in output.splitlines() if line.strip().isdigit()]


def test_content_sql(exclude_ids: set[int]) -> tuple[str, str]:
    cond = "(title LIKE 'pytest\\_%' OR title LIKE 'smoke\\_%')"
    if exclude_ids:
        ids = ",".join(str(i) for i in sorted(exclude_ids))
        cond += f" AND id NOT IN ({ids})"
    return (
        f"SELECT id FROM content WHERE {cond} ORDER BY id",
        f"DELETE FROM content WHERE {cond}",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="TASK-007 数据清理（默认只读预览）")
    parser.add_argument("--execute", action="store_true", help="真正执行 DML 清理")
    parser.add_argument("--drop-legacy", action="store_true", help="同时 DROP 遗留表 video/videoinfo（需配合 --execute）")
    parser.add_argument("--exclude", type=int, nargs="*", default=[], help="排除的 content id（如 --exclude 100）")
    args = parser.parse_args()

    if args.drop_legacy and not args.execute:
        print("错误: --drop-legacy 必须与 --execute 一起使用")
        return 1

    mysql = find_mysql()
    if mysql is None:
        print("错误: 未找到 mysql 客户端")
        return 2

    exclude_ids = set(args.exclude)
    select_test_sql, delete_test_sql = test_content_sql(exclude_ids)

    # ---- 只读预览 ----
    test_ids = parse_ids(run_sql(mysql, select_test_sql))
    orphan_media = parse_ids(run_sql(
        mysql,
        "SELECT m.id FROM content_media m LEFT JOIN content c ON m.content_id=c.id "
        "WHERE c.id IS NULL ORDER BY m.id",
    ))
    orphan_comment_like = parse_ids(run_sql(
        mysql,
        "SELECT cl.id FROM comment_like cl LEFT JOIN comment c ON cl.comment_id=c.comment_id "
        "WHERE c.comment_id IS NULL ORDER BY cl.id",
    ))

    def count_related(table: str, column: str) -> int:
        if not test_ids:
            return 0
        ids = ",".join(str(i) for i in test_ids)
        out = run_sql(mysql, f"SELECT COUNT(*) FROM {table} WHERE {column} IN ({ids})")
        return int(out.strip() or "0")

    related = {
        "content_media": count_related("content_media", "content_id"),
        "content_like": count_related("content_like", "content_id"),
        "comment": count_related("comment", "content_id"),
    }
    comment_ids: list[int] = []
    if test_ids:
        ids = ",".join(str(i) for i in test_ids)
        comment_ids = parse_ids(run_sql(mysql, f"SELECT comment_id FROM comment WHERE content_id IN ({ids})"))
    related["comment_like"] = 0
    if comment_ids:
        cids = ",".join(str(i) for i in comment_ids)
        out = run_sql(mysql, f"SELECT COUNT(*) FROM comment_like WHERE comment_id IN ({cids})")
        related["comment_like"] = int(out.strip() or "0")

    legacy_info: dict[str, int] = {}
    for table in ("video", "videoinfo"):
        out = run_sql(mysql, f"SELECT COUNT(*) FROM {table}")
        legacy_info[table] = int(out.strip() or "0")

    print("===== 清理预览（只读）=====")
    print(f"测试内容 content: {len(test_ids)} 条  id={test_ids}")
    print(f"  关联 content_media: {related['content_media']} 条")
    print(f"  关联 content_like: {related['content_like']} 条")
    print(f"  关联 comment: {related['comment']} 条")
    print(f"  关联 comment_like: {related['comment_like']} 条")
    print(f"孤儿 content_media: {len(orphan_media)} 条  id={orphan_media}")
    print(f"孤儿 comment_like: {len(orphan_comment_like)} 条  id={orphan_comment_like}")
    print(f"遗留表: video={legacy_info.get('video', 0)} 行, videoinfo={legacy_info.get('videoinfo', 0)} 行"
          + ("（本次将 DROP）" if args.drop_legacy else "（未选择 DROP）"))

    if not args.execute:
        print("未执行任何写操作。确认后请运行: python tools/cleanup_data.py --execute [--drop-legacy]")
        return 0

    # ---- 执行前自动备份 ----
    backup = subprocess.run(
        [sys.executable, str(PROJECT_ROOT / "tools" / "backup.py")],
        capture_output=True,
        text=True,
        timeout=600,
    )
    if backup.returncode != 0:
        print("错误: 执行前备份失败，已中止清理")
        print(backup.stdout)
        print(backup.stderr)
        return 3
    print("执行前备份完成，开始清理 ...")

    # ---- DML（单事务，失败回滚）----
    ids = ",".join(str(i) for i in test_ids) if test_ids else "0"
    cids = ",".join(str(i) for i in comment_ids) if comment_ids else "0"
    dml = f"""
START TRANSACTION;
DELETE FROM comment_like WHERE comment_id IN ({cids});
DELETE FROM content_like WHERE content_id IN ({ids});
DELETE FROM comment WHERE content_id IN ({ids});
DELETE FROM content_media WHERE content_id IN ({ids});
{delete_test_sql};
DELETE m FROM content_media m LEFT JOIN content c ON m.content_id=c.id WHERE c.id IS NULL;
DELETE cl FROM comment_like cl LEFT JOIN comment c ON cl.comment_id=c.comment_id WHERE c.comment_id IS NULL;
COMMIT;
"""
    try:
        run_sql(mysql, dml)
    except RuntimeError as exc:
        try:
            run_sql(mysql, "ROLLBACK;")
        except RuntimeError:
            pass
        print("错误: DML 清理失败，已尝试回滚")
        print(str(exc))
        return 1

    dropped = []
    if args.drop_legacy:
        run_sql(mysql, "DROP TABLE IF EXISTS video, videoinfo;")
        dropped = ["video", "videoinfo"]

    # ---- 清理报告 ----
    now = datetime.now()
    report = REPORT_DIR / f"CLEANUP_REPORT_{now.strftime('%Y-%m-%d_%H%M%S')}.md"
    lines = [
        "# 数据清理报告",
        "",
        f"> 日期：{now.strftime('%Y-%m-%d %H:%M:%S')}",
        f"> 执行：tools/cleanup_data.py --execute" + (" --drop-legacy" if dropped else ""),
        "",
        f"排除的 content id：{sorted(exclude_ids) or '无'}",
        "",
        "| 清理项 | 数量 | 明细 |",
        "|--------|------|------|",
        f"| 测试内容 content | {len(test_ids)} | id={test_ids} |",
        f"| 关联 content_media | {related['content_media']} | - |",
        f"| 关联 content_like | {related['content_like']} | - |",
        f"| 关联 comment | {related['comment']} | - |",
        f"| 关联 comment_like | {related['comment_like']} | - |",
        f"| 孤儿 content_media | {len(orphan_media)} | id={orphan_media} |",
        f"| 孤儿 comment_like | {len(orphan_comment_like)} | id={orphan_comment_like} |",
        f"| DROP 遗留表 | {len(dropped)} | {dropped} |",
        "",
    ]
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    report.write_text("\n".join(lines), encoding="utf-8")

    print(f"清理完成。报告: {report}")
    if dropped:
        print("已 DROP 遗留表: " + ", ".join(dropped))
    else:
        print("遗留表 video/videoinfo 未删除；如需删除请运行 --execute --drop-legacy")
    return 0


if __name__ == "__main__":
    sys.exit(main())
