# -*- coding: utf-8 -*-
"""一次性数据迁移：评论递归树 -> 楼中楼两级结构（阶段性任务，勿复用）。

背景：
    评论展示改为 B 站楼中楼（见 .docs/目标与任务/下一阶段功能梳理.md 3.5）：
    parent_id 语义收窄为"一律指向主楼 id"（主楼 parent_id=NULL，楼内回复 parent_id=主楼 id）。
    存量数据中 parent_id 可能是任意祖先（递归树），需一次性上溯重挂为所在主楼 id。

用法：
    python temp_script/migrate_comments_to_two_level.py --check   # 校验 + 统计（不写库）
    python temp_script/migrate_comments_to_two_level.py --apply   # 执行迁移（幂等，重复执行无副作用）

约定：
    * 依据 AGENTS.md，临时一次性脚本放 temp_script/。
    * 通过 mysql CLI 直连（模式同 tools/admin.py），不改表结构，无需 DDL 备份。
    * 迁移覆盖 is_deleted=1 的评论（语义保持一致，避免以后查询过滤出错）。
    * parent_id=0 的历史写法等价于 NULL（主楼），一并归一为 NULL。
    * 父链跨内容/父缺失的评论自动降级为独立主楼并醒目提示（保证两级不变量）。

退出码：
    0   成功 / 无需迁移
    1   其它错误
    2   mysql 客户端不可用
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from datetime import datetime
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


def load_comments(mysql: Path) -> list[tuple[int, int, int | None]]:
    """返回 [(comment_id, content_id, parent_id)]，parent_id 为 None 表示主楼。"""
    output = run_sql(
        mysql,
        "SELECT comment_id, content_id, parent_id FROM comment ORDER BY comment_id",
    )
    rows = []
    for line in output.strip().splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        comment_id = int(parts[0])
        content_id = int(parts[1])
        parent_id = None if parts[2] in ("", "NULL") else int(parts[2])
        rows.append((comment_id, content_id, parent_id))
    return rows


def analyze(rows: list[tuple[int, int, int | None]]):
    """返回 (to_change, roots, max_depth, warnings)。

    to_change: [(comment_id, target_main_id)] —— target 为 None 表示置为 NULL（主楼）
    warnings:   [(comment_id, content_id, parent_id)] —— 跨内容父评论，已自动降级为主楼
    """
    by_id = {comment_id: (content_id, parent_id) for comment_id, content_id, parent_id in rows}

    def depth_of(comment_id: int) -> int:
        seen: set[int] = set()
        d = 0
        while comment_id in by_id and comment_id not in seen:
            seen.add(comment_id)
            d += 1
            parent_id = by_id[comment_id][1]
            if parent_id is None or parent_id == 0:
                break
            comment_id = parent_id
        return d

    def main_of(comment_id: int):
        """返回 (主楼id, cross)。cross=True 表示父链跨内容/父缺失，无法合法挂任何主楼。"""
        seen: set[int] = set()
        content_id = by_id[comment_id][0]
        while comment_id in by_id and comment_id not in seen:
            seen.add(comment_id)
            parent_id = by_id[comment_id][1]
            if parent_id is None or parent_id == 0:
                return comment_id, False
            parent_info = by_id.get(parent_id)
            if parent_info is None or parent_info[0] != content_id:
                return None, True  # 父缺失或跨内容 => 无合法主楼
            comment_id = parent_id
        return None, True

    max_depth = max((depth_of(cid) for cid, _, _ in rows), default=0)
    roots = sum(1 for _, _, p in rows if p is None or p == 0)
    to_change: list[tuple[int, int | None]] = []
    warnings: list[tuple[int, int, int]] = []

    for comment_id, content_id, parent_id in rows:
        if parent_id is None:
            continue
        if parent_id == 0:
            # 存量历史写法：0 等价于 NULL（主楼），统一归一为 NULL
            to_change.append((comment_id, None))
            continue
        target, cross = main_of(comment_id)
        if cross:
            warnings.append((comment_id, content_id, parent_id))
            to_change.append((comment_id, None))  # 降级为独立主楼，保证两级不变量
            continue
        if target != parent_id:
            to_change.append((comment_id, target))

    to_change.sort(key=lambda row: row[0])
    return to_change, roots, max_depth, warnings


def print_stats(rows, to_change, roots, max_depth, warnings):
    total = len(rows)
    print(f"评论总数: {total}")
    print(f"主楼数(parent_id IS NULL / 0): {roots}")
    print(f"当前最大深度: {max_depth}")
    norm_counts = sum(1 for _, t in to_change if t is None)
    rehang_counts = sum(1 for _, t in to_change if t is not None)
    print(f"待变更评论数: {len(to_change)}（0->NULL 归一 {norm_counts} 条；重挂主楼 {rehang_counts} 条）")
    if warnings:
        print("\n[警告] 以下评论父链跨内容或父缺失，将自动降级为独立主楼（原先不可见/显示悬挂）：")
        for comment_id, content_id, parent_id in warnings:
            print(f"  comment_id={comment_id} content_id={content_id} parent_id={parent_id}")
    if to_change:
        print(f"\n结论: 存在待迁移数据。使用 --apply 执行（幂等）。")
    else:
        print("\n结论: 已是楼中楼两级结构（含 0 归一为 NULL），无需迁移。")


def backup_comments_table(mysql: Path, dest: Path) -> int:
    """执行前把 comment 表整表备份为 TSV（含表头）到项目 DBbackups 目录。"""
    output = run_sql(mysql, "SELECT comment_id, content_id, user_id, content, parent_id,"
                            " create_time, update_time, like_count, is_deleted FROM comment")
    lines = output.strip().splitlines() if output.strip() else []
    with dest.open("w", encoding="utf-8", newline="") as f:
        f.write("comment_id\tcontent_id\tuser_id\tcontent\tparent_id\tcreate_time\tupdate_time\tlike_count\tis_deleted\n")
        for line in lines:
            f.write(line + "\n")
    return len(lines)


def apply_migration(mysql: Path, to_change: list[tuple[int, int | None]]) -> int:
    # 单事务包裹全部 UPDATE：任一失败整体回滚（mysql CLI 断连自动回滚未提交事务）
    stmts = ["START TRANSACTION"]
    for comment_id, target in to_change:
        if target is None:
            stmts.append(f"UPDATE comment SET parent_id = NULL WHERE comment_id = {comment_id}")
        else:
            stmts.append(f"UPDATE comment SET parent_id = {target} WHERE comment_id = {comment_id}")
    stmts.append("COMMIT")
    run_sql(mysql, "; ".join(stmts))
    return len(to_change)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description="评论递归树 -> 楼中楼两级结构迁移（一次性）")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--check", action="store_true", help="校验 + 统计（只读，不写库）")
    group.add_argument("--apply", action="store_true", help="执行迁移（幂等）")
    args = parser.parse_args()

    mysql = find_mysql()
    if mysql is None:
        print("错误: 未找到 mysql 客户端，请确认 MySQL 已安装或将 mysql 加入 PATH")
        return 2

    try:
        rows = load_comments(mysql)
    except RuntimeError as e:
        print("错误:", e)
        return 1

    to_change, roots, max_depth, warnings = analyze(rows)
    print_stats(rows, to_change, roots, max_depth, warnings)

    if args.check:
        return 0

    # --apply
    if not to_change:
        print("无需迁移（已经是两级结构）。")
        return 0

    # 执行前整表备份（数据迁移安全性）
    backup_dir = Path(__file__).resolve().parent.parent / ".docs" / "DBbackups"
    backup_dir.mkdir(parents=True, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_file = backup_dir / f"comment_data_pre_two_level_{ts}.tsv"
    try:
        row_count = backup_comments_table(mysql, backup_file)
    except RuntimeError as e:
        print("错误: 备份 comment 表失败，放弃迁移:", e)
        return 1
    print(f"已备份 comment 表（{row_count} 行）到: {backup_file}")

    print("\n开始迁移...")
    try:
        changed = apply_migration(mysql, to_change)
    except RuntimeError as e:
        print("错误:", e)
        return 1

    print(f"迁移完成: 共变更 {changed} 条评论。")
    print("\n迁移后复检：")
    rows2 = load_comments(mysql)
    to_change2, roots2, max_depth2, warnings2 = analyze(rows2)
    print_stats(rows2, to_change2, roots2, max_depth2, warnings2)
    if to_change2:
        print("[错误] 迁移后仍有待变更评论，请立即检查！")
        return 1
    if max_depth2 > 2:
        print("[错误] 迁移后仍非两级结构，请立即检查！")
        return 1
    print("迁移校验通过：两级结构成立，无悬挂评论。")
    return 0


if __name__ == "__main__":
    sys.exit(main())