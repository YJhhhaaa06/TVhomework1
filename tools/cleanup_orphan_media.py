# -*- coding: utf-8 -*-
"""孤儿媒体清理脚本（Python，符合 AGENTS.md 脚本约定）。

用途：用户在 DataGrip 手动删除数据库测试数据后，磁盘媒体目录里会残留大量
「已无任何数据库引用」的孤儿文件。本脚本扫描 stone/{video,image,cover}，
列出孤儿文件，确认后移入回收站 stone/_trash/<时间戳>/（不直接删除，可恢复）。

安全设计（对应需求里的双保险，批量化为 O(1) 次查询）：
    1. 启动门禁：先验证数据库连接正常（查询 content_media 计数 + 取一条
       正常媒体 URL 示例）。数据库不可达 → 退出码 3，未触碰任何文件。
    2. 批量快照：一次查出 content_media 全部被引用 URL 构建集合。
    3. 交叉校验（H1）：--quarantine 前校验「磁盘实际 URL ∩ 数据库被引用 URL」
       的重叠数；数据库非空且重叠为 0 时判定疑似配置错误，中止（退出码 5），
       除非加 --force。
    4. 移动前批量复核：把候选孤儿 URL 再查一次库，期间被新引用的跳过（TOCTOU 第一道）。
    5. 移动后复核（H2）：文件移入回收站后再查一次库，期间被新引用的文件
       自动从回收站移回原目录（TOCTOU 第二道，竞态窗口内的补救）。

目录边界：只扫描 stone/video|image|cover 三个子目录第一层；
    排除 stone/temp/（构建产物）、stone/_trash/（回收站本身）、
    隐藏文件、符号链接/junction、文件名不匹配安全正则 ^[A-Za-z0-9._-]+$ 的文件
    （以上只报告不处理）。
    说明：回收站 stone/_trash/ 位于 Web 可服务目录内（context.xml 挂载 /upload），
    本工具按用户决定不迁移回收站位置，仅作提示。

用法：
    python tools/cleanup_orphan_media.py                       # dry-run：列出孤儿（默认）
    python tools/cleanup_orphan_media.py --preview             # 同上，显式 dry-run
    python tools/cleanup_orphan_media.py --report-only         # dry-run 并落盘报告
    python tools/cleanup_orphan_media.py --quarantine          # 交互确认后移入回收站
    python tools/cleanup_orphan_media.py --quarantine --yes    # 跳过交互确认
    python tools/cleanup_orphan_media.py --quarantine --yes --force   # 跳过交叉校验一致性中止
    python tools/cleanup_orphan_media.py --quarantine --only <子串>  # 只回收相对URL含子串的孤儿
    python tools/cleanup_orphan_media.py --quarantine --yes-empty-db  # content_media 为空时显式确认

连接参数（统一来源 tools/env/，T6）：
    DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME
    （默认当前激活环境 active.conf；环境变量优先级最高）

安全提示（重要）：本脚本会【移动磁盘媒体文件】（移入 stone/_trash/ 回收站）。
    测试与生产共享 stone 媒体目录，若连接测试库运行，生产库引用的媒体文件
    在测试库中查不到引用会被误判为孤儿 → 误伤生产内容。因此仅允许在
    prod 环境执行（tv.py 已拦截 test 环境）；执行时确认当前 stone 属于所选环境。

退出码：
    0  成功 / dry-run 完成
    1  其它错误
    2  mysql 客户端不可用
    3  数据库连接/健康门禁或复核失败（未触碰任何文件）
    4  回收目录创建等环境问题（已移动文件会落盘报告）
    5  疑似配置错误：磁盘与 DB 被引用 URL 无重叠（未加 --force 时中止）
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

import db_config

PROJECT_ROOT = Path(__file__).resolve().parent.parent
APP_PROPERTIES = PROJECT_ROOT / "src" / "main" / "resources" / "app.properties"
REPORT_DIR = PROJECT_ROOT / ".docs" / "temp"

DEFAULT_UPLOAD_ROOT = Path("D:/data/projects/VideoPlatform/stone")

# 连接参数统一来源 tools/env/（T6：默认 active.conf 当前环境，环境变量 DB_* 优先；
# 本脚本会移动磁盘媒体文件，tv.py 已禁止在 test 环境执行）
db_config.use()
MYSQL_HOST = db_config.DB_HOST
MYSQL_PORT = db_config.DB_PORT
MYSQL_USER = db_config.DB_USER
MYSQL_PASSWORD = db_config.DB_PASSWORD
DB_NAME = db_config.DB_NAME

COMMON_MYSQL_PATHS = [
    Path(r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"),
    Path(r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"),
]

# 与 FileUploadService / MediaAuditService 一致的安全文件名规则
MEDIA_DIRS = ("video", "image", "cover")
NAME_PATTERN = re.compile(r"^[A-Za-z0-9._-]+$")

SQL_BATCH_SIZE = 400  # WHERE url IN (...) 分批大小，避免超长 SQL


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
        "--default-character-set=utf8mb4",
        "--batch",
        "--skip-column-names",
        "--execute",
        sql,
    ]
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=120,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("mysql 执行超时(120s): " + sql[:80]) from exc
    except OSError as exc:
        raise RuntimeError("mysql 客户端启动失败: " + str(exc)) from exc
    if proc.returncode != 0:
        raise RuntimeError("mysql 执行失败: " + (proc.stderr.strip() or proc.stdout.strip()))
    return proc.stdout


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


def resolve_upload_root() -> Path:
    """上传根目录：app.properties 的 upload.path > 环境变量 TV_STONE_DIR > 默认值。"""
    props = read_app_properties(APP_PROPERTIES)
    if props.get("upload.path"):
        return Path(props["upload.path"])
    env = os.environ.get("TV_STONE_DIR")
    if env:
        return Path(env)
    return DEFAULT_UPLOAD_ROOT


def human_size(num: int) -> str:
    value = float(num)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.1f} {unit}" if unit != "B" else f"{int(value)} B"
        value /= 1024
    return f"{num} B"


def is_symlink_like(entry: Path) -> bool:
    """是否符号链接或 Windows junction（junction 需 Python 3.12+ 才可检测，尽力而为）。"""
    if entry.is_symlink():
        return True
    isjunction = getattr(os.path, "isjunction", None)
    return bool(isjunction and isjunction(entry))


def scan_files(root: Path, referenced: set[str]) -> tuple[list[dict], list[dict], list[dict], list[dict]]:
    """扫描 stone/{video,image,cover}，返回 (referenced, orphan, unknown, symlink) 四个列表。"""
    referenced_files: list[dict] = []
    orphan_files: list[dict] = []
    unknown_files: list[dict] = []
    symlink_files: list[dict] = []

    for media_dir in MEDIA_DIRS:
        dir_path = root / media_dir
        if not dir_path.is_dir():
            continue
        for entry in sorted(dir_path.iterdir(), key=lambda p: p.name):
            if not entry.is_file() or entry.name.startswith("."):
                continue
            name = entry.name
            rel_url = f"/upload/{media_dir}/{name}"
            if is_symlink_like(entry):
                symlink_files.append({"media_dir": media_dir, "name": name,
                                      "rel_url": rel_url, "size": 0})
                continue
            try:
                size = entry.stat().st_size
            except OSError:
                size = 0
            info = {"media_dir": media_dir, "name": name, "rel_url": rel_url, "path": str(entry)}
            if NAME_PATTERN.fullmatch(name) and ".." not in name:
                if rel_url in referenced:
                    referenced_files.append(info)
                else:
                    info["size"] = size
                    orphan_files.append(info)
            else:
                info["size"] = size
                unknown_files.append(info)

    return referenced_files, orphan_files, unknown_files, symlink_files


def verify_still_referenced(mysql: Path, rel_urls: list[str]) -> set[str]:
    """批量复核：候选 URL 中仍被数据库引用的集合（分批查询，防超长 SQL）。"""
    still: set[str] = set()
    for i in range(0, len(rel_urls), SQL_BATCH_SIZE):
        chunk = rel_urls[i:i + SQL_BATCH_SIZE]
        in_clause = ",".join("'" + u.replace("'", "''") + "'" for u in chunk)
        out = run_sql(mysql, f"SELECT url FROM content_media WHERE url IN ({in_clause})")
        for line in out.splitlines():
            still.add(line.strip())
    return still


def _section_lines(title: str, items: list[dict], cols: tuple[str, ...]) -> list[str]:
    """生成一个表格小节；无数据时输出占位。"""
    lines = ["", f"## {title}", ""]
    if not items:
        lines.append("_（无）_")
        return lines
    lines.append("| " + " | ".join(cols) + " |")
    lines.append("|" + "|".join(["------"] * len(cols)) + "|")
    for it in items:
        row = []
        for col in cols:
            if col == "size":
                row.append(human_size(it.get("size", 0)))
            elif col == "path":
                row.append(f"`{it.get('path', '-')}`")
            else:
                row.append(f"`{it.get(col, '-')}`")
        lines.append("| " + " | ".join(row) + " |")
    return lines


def write_report(scan_time: datetime, candidates: list[dict], skipped: list[dict],
                 referenced: list[dict], unknown: list[dict], symlink: list[dict],
                 moved: list[dict], failed: list[dict], rolled_back: list[dict],
                 total_bytes: int, execute: bool, trash_root: Path | None) -> Path:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    report = REPORT_DIR / f"CLEANUP_MEDIA_REPORT_{scan_time.strftime('%Y-%m-%d_%H%M%S')}.md"

    lines = [
        "# 孤儿媒体清理报告",
        "",
        f"> 时间：{scan_time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"> 上传根目录：{resolve_upload_root()}",
        f"> 模式：{'--quarantine（已移入回收站）' if execute else 'dry-run（未移动）'}",
        f"> 回收站：`{trash_root}`" if trash_root else "> 回收站：-",
        "",
        "## 汇总",
        "",
        f"- 候选孤儿文件：**{len(candidates)}** 个，共 **{human_size(total_bytes)}**",
        f"- 本次移入回收站：**{len(moved)}** 个",
        f"- 移动失败：**{len(failed)}** 个",
        f"- 移动前复核被引用跳过：**{len(skipped)}** 个",
        f"- 移动后复核回滚（已移回原目录）：**{len(rolled_back)}** 个",
        f"- 符号链接/junction 跳过：**{len(symlink)}** 个",
        f"- 正常被引用文件：**{len(referenced)}** 个（未处理）",
        f"- 非安全命名文件：**{len(unknown)}** 个（仅报告，未处理）",
        "",
    ]
    lines += _section_lines("候选孤儿文件清单", candidates, ("media_dir", "name", "size", "rel_url"))
    lines += _section_lines("移入回收站（成功）", moved, ("media_dir", "name", "rel_url", "path"))
    lines += _section_lines("移动失败", failed, ("media_dir", "name", "rel_url"))
    lines += _section_lines("移动前复核被引用跳过", skipped, ("media_dir", "name", "rel_url"))
    lines += _section_lines("移动后复核回滚（已移回原目录）", rolled_back, ("media_dir", "name", "rel_url"))
    lines += _section_lines("符号链接/junction 跳过", symlink, ("media_dir", "name", "rel_url"))

    report.write_text("\n".join(lines), encoding="utf-8")
    return report


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description="孤儿媒体清理（默认只读 dry-run）")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--preview", action="store_true", help="只列出孤儿文件（默认行为）")
    mode.add_argument("--quarantine", action="store_true", help="确认后把孤儿文件移入回收站")
    mode.add_argument("--report-only", action="store_true", help="dry-run 并落盘报告，不移动")
    parser.add_argument("--only", metavar="SUBSTR", default=None,
                        help="只处理相对 URL 包含该子串的孤儿文件（精准处理）")
    parser.add_argument("--yes", action="store_true", help="跳过 --quarantine 的交互确认")
    parser.add_argument("--force", action="store_true",
                        help="跳过「磁盘与 DB 被引用 URL 无重叠」时的一致性中止")
    parser.add_argument("--yes-empty-db", action="store_true",
                        help="content_media 为空时显式确认仍继续（危险场景）")
    args = parser.parse_args()

    execute = args.quarantine
    write_report_flag = execute or args.report_only
    only = args.only
    scan_time = datetime.now()

    # 0. mysql 客户端
    mysql = find_mysql()
    if mysql is None:
        print("错误: 未找到 mysql 客户端，请确认 MySQL 已安装或将 mysql 加入 PATH")
        return 2

    # 1. 上传根目录
    root = resolve_upload_root()
    if not root.is_dir():
        print(f"错误: 上传根目录不存在或不是目录: {root}")
        return 1
    if not any((root / d).is_dir() for d in MEDIA_DIRS):
        print(f"错误: 上传根目录下未找到 {MEDIA_DIRS} 任一子目录: {root}")
        return 1
    print(f"上传根目录: {root}")

    # 2. 健康门禁（对应「查一条正常媒体 URL」）
    try:
        total_out = run_sql(mysql, "SELECT COUNT(*) FROM content_media")
        total = int(total_out.strip() or "0")
        sample_out = run_sql(mysql, "SELECT url FROM content_media WHERE file_exists=1 ORDER BY id LIMIT 1")
        sample = sample_out.strip()
    except (RuntimeError, ValueError) as exc:
        print("错误: 数据库连接/健康门禁失败，未触碰任何文件")
        print(str(exc))
        return 3

    print(f"数据库正常: content_media 共 {total} 条记录"
          + (f"，示例正常媒体 URL: {sample}" if sample else "，无 file_exists=1 的示例媒体"))
    if total == 0:
        print("警告: content_media 为空！所有磁盘文件都会被判定为孤儿，这是危险场景。")
        if execute and not args.yes_empty_db:
            print("已中止。确需继续请加 --yes-empty-db。")
            return 0

    # 3. 批量快照（对应「确认目标是孤儿」的批量版）
    try:
        url_out = run_sql(mysql, "SELECT url FROM content_media")
    except RuntimeError as exc:
        print("错误: 加载被引用 URL 失败，未触碰任何文件")
        print(str(exc))
        return 3
    referenced_set = {line.strip() for line in url_out.splitlines() if line.strip()}
    print(f"已加载被引用 URL 集合: {len(referenced_set)} 个")

    # 4/5. 磁盘扫描 + 分类
    referenced_files, orphan_files, unknown_files, symlink_files = scan_files(root, referenced_set)

    if only:
        orphan_files = [o for o in orphan_files if only in o["rel_url"]]
        print(f"--only '{only}' 过滤后候选孤儿: {len(orphan_files)} 个")

    total_bytes = sum(o["size"] for o in orphan_files)

    print("===== 孤儿媒体预览 =====")
    print(f"孤儿文件: {len(orphan_files)} 个，共 {human_size(total_bytes)}")
    for o in orphan_files:
        print(f"  [{o['media_dir']}] {o['name']}  ({human_size(o['size'])})  {o['rel_url']}")
    print(f"正常被引用文件: {len(referenced_files)} 个（未处理）")
    print(f"非安全命名文件: {len(unknown_files)} 个（仅报告，未处理）")
    for u in unknown_files:
        print(f"  [?] {u['rel_url']}")
    if symlink_files:
        print(f"符号链接/junction 跳过: {len(symlink_files)} 个")
        for s in symlink_files:
            print(f"  [L] {s['rel_url']}")

    # H1: 交叉校验（磁盘实际 URL ∩ DB 被引用 URL），防止配置错误导致全量误移
    disk_urls = {f["rel_url"] for f in referenced_files} | {o["rel_url"] for o in orphan_files}
    overlap = len(disk_urls & referenced_set)
    if total > 0 and overlap == 0:
        if execute:
            if not args.force:
                print("错误: 磁盘文件与数据库被引用 URL 无任何重叠，疑似上传目录/数据库配置错误。")
                print("      为避免全量误移已中止（未移动任何文件）。确认无误请加 --force 继续。")
                return 5
            print("警告: 磁盘与 DB 被引用 URL 无重叠，已按 --force 继续。")
        else:
            print("注意: 磁盘与 DB 被引用 URL 无重叠，疑似配置错误；本次为 dry-run 仅展示，执行前请核查。")

    skipped: list[dict] = []
    moved: list[dict] = []
    failed: list[dict] = []
    rolled_back: list[dict] = []
    trash_root: Path | None = None
    cancelled = False

    if execute and orphan_files:
        # L2: 交互确认
        if not args.yes:
            print(f"将把 {len(orphan_files)} 个孤儿文件移入回收站（不删除，可恢复）。")
            try:
                answer = input("请输入 yes 确认（其它输入将中止，不移动任何文件）: ").strip().lower()
            except EOFError:
                answer = ""
            if answer != "yes":
                print("已取消。未移动任何文件。")
                cancelled = True
        if not cancelled:
            # 6. 移动前批量复核（TOCTOU 第一道）
            try:
                still = verify_still_referenced(mysql, [o["rel_url"] for o in orphan_files])
            except RuntimeError as exc:
                print("错误: 复核失败，未触碰任何文件")
                print(str(exc))
                return 3
            final_orphans = []
            for o in orphan_files:
                if o["rel_url"] in still:
                    skipped.append(o)
                else:
                    final_orphans.append(o)
            orphan_files = final_orphans
            for s in skipped:
                print(f"跳过（复核时已被引用）: {s['rel_url']}")

            # 7. 移入回收站
            if orphan_files:
                trash_root = root / "_trash" / f"{scan_time.strftime('%Y%m%d-%H%M%S')}-{scan_time.microsecond:06d}"
                for o in orphan_files:
                    try:
                        dest_dir = trash_root / o["media_dir"]
                        dest_dir.mkdir(parents=True, exist_ok=True)
                        src = Path(o["path"])
                        if not src.is_file():
                            print(f"跳过（源文件已不存在）: {o['rel_url']}")
                            continue
                        dest = dest_dir / o["name"]
                        if dest.exists():  # L1: 同名冲突加微秒后缀
                            dest = dest_dir / (scan_time.strftime("%H%M%S%f_") + o["name"])
                        shutil.move(str(src), str(dest))
                        o["dest"] = str(dest)
                        moved.append(o)
                        print(f"已回收: {o['rel_url']} -> {dest}")
                    except (OSError, shutil.Error) as exc:  # M2: 捕获 shutil.Error
                        failed.append(o)
                        print(f"失败: {o['rel_url']}: {exc}")
                print(f"回收站: {trash_root}")

                # H2: 移动后复核（TOCTOU 第二道），命中即回滚
                if moved:
                    try:
                        still_after = verify_still_referenced(mysql, [o["rel_url"] for o in moved])
                    except RuntimeError as exc:
                        print("警告: 移动后复核失败，跳过回滚（文件仍在回收站）: " + str(exc))
                        still_after = set()
                    for o in moved:
                        if o["rel_url"] in still_after:
                            try:
                                shutil.move(Path(o["dest"]), Path(o["path"]))
                                rolled_back.append(o)
                                print(f"回滚（移动期间被新引用）: {o['rel_url']} 已移回原目录")
                            except (OSError, shutil.Error) as exc:
                                print(f"警告: 回滚失败，文件仍在回收站: {o['rel_url']}: {exc}")
            else:
                print("没有需要回收的孤儿文件（全部被复核跳过）。")
    elif not execute:
        print("dry-run：未移动任何文件。确认后请运行: python tools/cleanup_orphan_media.py --quarantine")

    # 8. 报告（无论成功失败均落盘；M2）
    if write_report_flag:
        mode_execute = execute and not cancelled
        report = write_report(scan_time, orphan_files, skipped, referenced_files,
                              unknown_files, symlink_files, moved, failed, rolled_back,
                              total_bytes, mode_execute, trash_root)
        print(f"报告: {report}")

    if failed:
        return 4
    return 0


if __name__ == "__main__":
    sys.exit(main())
