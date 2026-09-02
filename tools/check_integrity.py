# -*- coding: utf-8 -*-
"""完整性校验统一工具（只读，Python，符合 AGENTS.md 脚本约定）。

用途：一次 dry-run 列出三类脏数据清单，作为后续清理的输入依据：
    1. 孤儿记录（引用缺失）：content_media / content_like / comment / comment_like /
       comment_media 指向不存在的父记录，以及楼中楼回复 parent_id 无父评论。
    2. 孤儿/缺失媒体：stone/{video,image,cover} 中未被 content_media/comment_media.url
       引用的磁盘文件（孤儿媒体文件）；content_media/comment_media.url 指向不存在的
       磁盘文件（库引用缺失）。
    3. 重复引用：content_media (content_id,url) 重复行；comment_media (comment_id,url) 重复行。

职责边界：
    * 本工具只做【只读检查】并输出报告，绝不写数据库、不移动/删除文件。
    * 需要清理时另行执行 tools/cleanup_data.py（测试污染/孤儿记录）或
      tools/cleanup_orphan_media.py（孤儿媒体隔离回收）。
    * 数据库连接与媒体根目录解析与既有工具一致（DB_* 环境变量、app.properties upload.path）。

用法：
    python tools/check_integrity.py                        # 默认 dry-run，报告写入 .docs/temp/
    python tools/check_integrity.py --no-media             # 跳过磁盘媒体检查，只查库内检查项 1-8

连接参数（可用环境变量覆盖，默认指向生产库）：
    DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME
    （默认 127.0.0.1:3306 / root / MySQL / tvdatabase）

退出码：
    0  检查完成（是否发现脏数据以报告为准）
    1  参数/其它错误
    2  mysql 客户端不可用
    3  数据库连接/健康门禁失败（未触碰任何数据）
    5  疑似配置错误：content_media/comment_media 非空但磁盘扫描与被引用 URL 无重叠（媒体结果不可信）
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

PROJECT_ROOT = Path(__file__).resolve().parent.parent
REPORT_DIR = PROJECT_ROOT / ".docs" / "temp"
APP_PROPERTIES = PROJECT_ROOT / "src" / "main" / "resources" / "app.properties"

DEFAULT_UPLOAD_ROOT = Path("D:/data/projects/VideoPlatform/stone")

MYSQL_HOST = os.environ.get("DB_HOST", "127.0.0.1")
MYSQL_PORT = os.environ.get("DB_PORT", "3306")
MYSQL_USER = os.environ.get("DB_USER", "root")
MYSQL_PASSWORD = os.environ.get("DB_PASSWORD", "MySQL")
DB_NAME = os.environ.get("DB_NAME", "tvdatabase")

COMMON_MYSQL_PATHS = [
    Path(r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"),
    Path(r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"),
]

# 与 FileUploadService / MediaAuditService / cleanup_orphan_media.py 一致的媒体目录与安全命名规则
MEDIA_DIRS = ("video", "image", "cover")
NAME_PATTERN = re.compile(r"^[A-Za-z0-9._-]+$")

MAX_DETAIL = 100  # 报告每节明细上限；汇总始终是全量计数

# 孤儿记录检查（引用缺失）
ORPHAN_CHECKS = [
    ("孤儿 content_media",
     "SELECT m.id FROM content_media m LEFT JOIN content c ON m.content_id=c.id "
     "WHERE c.id IS NULL ORDER BY m.id"),
    ("孤儿 content_like",
     "SELECT cl.id FROM content_like cl LEFT JOIN content c ON cl.content_id=c.id "
     "WHERE c.id IS NULL ORDER BY cl.id"),
    ("孤儿 comment（content_id 无主）",
     "SELECT cm.comment_id FROM comment cm LEFT JOIN content c ON cm.content_id=c.id "
     "WHERE c.id IS NULL ORDER BY cm.comment_id"),
    ("孤儿 comment_like",
     "SELECT cl.id FROM comment_like cl LEFT JOIN comment c ON cl.comment_id=c.comment_id "
     "WHERE c.comment_id IS NULL ORDER BY cl.id"),
    ("楼中楼孤儿回复（parent_id 无父评论）",
     "SELECT cm.comment_id FROM comment cm LEFT JOIN comment p ON cm.parent_id=p.comment_id "
     "WHERE cm.parent_id IS NOT NULL AND p.comment_id IS NULL ORDER BY cm.comment_id"),
    ("孤儿 comment_media（comment_id 无主）",
     "SELECT cm.id FROM comment_media cm LEFT JOIN comment c ON cm.comment_id=c.comment_id "
     "WHERE c.comment_id IS NULL ORDER BY cm.id"),
]

# 重复引用检查（仅针对无唯一键的表）
DUP_CHECKS = [
    ("content_media (content_id,url) 重复",
     "SELECT content_id, url, COUNT(*) AS n FROM content_media "
     "GROUP BY content_id, url HAVING COUNT(*) > 1 ORDER BY n DESC, content_id"),
    ("comment_media (comment_id,url) 重复",
     "SELECT comment_id, url, COUNT(*) AS n FROM comment_media "
     "GROUP BY comment_id, url HAVING COUNT(*) > 1 ORDER BY n DESC, comment_id"),
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
        f"--host={MYSQL_HOST}",
        f"--port={MYSQL_PORT}",
        f"--database={DB_NAME}",
        "--default-character-set=utf8mb4",
        "--batch",
        "--skip-column-names",
        "--execute",
        sql,
    ]
    env = os.environ.copy()
    # 用环境变量传密码，避免明文出现在进程参数列表（mysql 客户端支持 MYSQL_PWD）
    env["MYSQL_PWD"] = MYSQL_PASSWORD
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=120,
            env=env,
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
    """上传根目录：app.properties 的 upload.path > 环境变量 TV_STONE_DIR > 默认值（均解析为绝对路径）。"""
    props = read_app_properties(APP_PROPERTIES)
    if props.get("upload.path"):
        return Path(props["upload.path"]).resolve()
    env = os.environ.get("TV_STONE_DIR")
    if env:
        return Path(env).resolve()
    return DEFAULT_UPLOAD_ROOT


def is_symlink_like(entry: Path) -> bool:
    """是否符号链接或 Windows junction（junction 需 Python 3.12+ 才可检测，尽力而为）。"""
    if entry.is_symlink():
        return True
    isjunction = getattr(os.path, "isjunction", None)
    return bool(isjunction and isjunction(entry))


def scan_disk_media(root: Path) -> tuple[list[dict], list[dict], list[dict]]:
    """扫描 stone/{video,image,cover} 第一层，返回 (安全命名文件, 未知命名文件, 符号链接文件)。

    规则与 cleanup_orphan_media.py 一致：只扫三个媒体子目录第一层，
    跳过隐藏文件、符号链接/junction、文件名不匹配安全正则的条目（仅报告）。
    rel_url = /upload/{dir}/{name}。
    """
    files: list[dict] = []
    unknown: list[dict] = []
    symlinks: list[dict] = []

    for media_dir in MEDIA_DIRS:
        dir_path = root / media_dir
        if not dir_path.is_dir():
            continue
        for entry in sorted(dir_path.iterdir(), key=lambda p: p.name):
            if not entry.is_file() or entry.name.startswith("."):
                continue
            rel_url = f"/upload/{media_dir}/{entry.name}"
            try:
                size = entry.stat().st_size
            except OSError:
                size = 0
            info = {
                "media_dir": media_dir,
                "name": entry.name,
                "rel_url": rel_url,
                "path": str(entry),
                "size": size,
            }
            if is_symlink_like(entry):
                symlinks.append(info)
            elif NAME_PATTERN.fullmatch(entry.name) and ".." not in entry.name:
                files.append(info)
            else:
                unknown.append(info)

    return files, unknown, symlinks


def url_to_disk_path(root: Path, url: str) -> Path | None:
    """将 /upload/{dir}/{name} 形式的 URL 映射到磁盘路径；非法/外部 URL 返回 None。"""
    if not url.startswith("/upload/"):
        return None
    parts = url[len("/upload/"):].split("/")
    if len(parts) != 2 or parts[0] not in MEDIA_DIRS:
        return None
    if not NAME_PATTERN.fullmatch(parts[1]) or ".." in parts[1]:
        return None
    return root / parts[0] / parts[1]


def check_missing_files(root: Path, urls: set[str]) -> tuple[list[dict], int]:
    """库引用缺失文件检查：content_media/comment_media.url 指向的磁盘文件不存在。

    返回 (缺失明细, 不可解析 URL 数)。
    """
    missing: list[dict] = []
    unparseable = 0
    for url in sorted(urls):
        disk = url_to_disk_path(root, url)
        if disk is None:
            unparseable += 1
            continue
        if not disk.exists():
            missing.append({"url": url, "path": str(disk)})
    return missing, unparseable


def fmt_ids(ids: list[int]) -> str:
    if len(ids) <= MAX_DETAIL:
        return str(ids)
    return str(ids[:MAX_DETAIL]) + f" ...（共 {len(ids)} 条，明细已截断）"


def fmt_dup(rows: list[list[str]]) -> str:
    if not rows:
        return "无"
    lines = []
    for row in rows[:MAX_DETAIL]:
        lines.append(f"(key={row[0]}, url={row[1]}, n={row[2]})")
    if len(rows) > MAX_DETAIL:
        lines.append(f"...（共 {len(rows)} 组，明细已截断）")
    return "；".join(lines)


class CheckIntegrityParser(argparse.ArgumentParser):
    """参数错误改抛 ValueError（argparse 默认会以退出码 2 退出，与 docstring 的退出码 2=mysql 不可用冲突）。"""

    def error(self, message: str) -> None:
        raise ValueError(message)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    parser = CheckIntegrityParser(
        description="完整性校验统一工具（只读 dry-run，报告默认写入 .docs/temp/）"
    )
    parser.add_argument("--no-media", action="store_true",
                        help="跳过磁盘媒体检查，只查库内检查项（孤儿记录/重复引用）")
    try:
        args = parser.parse_args()
    except ValueError as exc:
        print("参数错误: " + str(exc))
        return 1

    mysql = find_mysql()
    if mysql is None:
        print("错误: 未找到 mysql 客户端，请确认 MySQL 已安装或将 mysql 加入 PATH")
        return 2

    print(f"连接目标: {MYSQL_HOST}:{MYSQL_PORT}/{DB_NAME}（只读检查）")

    # ---- 健康门禁：先验证数据库可读，失败则未触碰任何数据 ----
    try:
        total_out = run_sql(mysql, "SELECT COUNT(*) FROM content_media")
        media_total = int(total_out.strip() or "0")
        content_out = run_sql(mysql, "SELECT COUNT(*) FROM content")
        content_total = int(content_out.strip() or "0")
        comment_media_out = run_sql(mysql, "SELECT COUNT(*) FROM comment_media")
        comment_media_total = int(comment_media_out.strip() or "0")
    except (RuntimeError, ValueError) as exc:
        print("错误: 数据库连接/健康门禁失败，未触碰任何数据")
        print(str(exc))
        return 3
    all_media_total = media_total + comment_media_total
    print(f"数据库正常: content 共 {content_total} 条, content_media 共 {media_total} 条, "
          f"comment_media 共 {comment_media_total} 条")

    # ---- 库内检查（孤儿记录/重复引用；任一 SQL 失败视为意外错误，中止且不落报告）----
    try:
        orphan_results: list[tuple[str, list[int]]] = []
        for name, sql in ORPHAN_CHECKS:
            out = run_sql(mysql, sql)
            ids = [int(line.strip()) for line in out.splitlines() if line.strip().isdigit()]
            orphan_results.append((name, ids))

        dup_results: list[tuple[str, list[list[str]]]] = []
        for name, sql in DUP_CHECKS:
            out = run_sql(mysql, sql)
            rows = [line.split("\t") for line in out.splitlines() if line.strip()]
            dup_results.append((name, rows))
    except RuntimeError as exc:
        print("错误: 检查执行失败（未触碰任何数据）:")
        print(str(exc))
        return 1

    # ---- 媒体检查（--no-media 跳过）----
    media_note: str = ""
    orphan_files: list[dict] = []
    missing_files: list[dict] = []
    unknown_files: list[dict] = []
    symlink_files: list[dict] = []
    unparseable = 0
    media_scanned = False
    suspect = False

    if not args.no_media:
        root = resolve_upload_root()
        if root.is_dir() and all((root / d).is_dir() for d in MEDIA_DIRS):
            media_scanned = True
            print(f"上传根目录: {root}")
            try:
                url_out = run_sql(
                    mysql,
                    "SELECT DISTINCT url FROM content_media "
                    "UNION SELECT DISTINCT url FROM comment_media",
                )
            except RuntimeError as exc:
                print("错误: 加载被引用 URL 失败（未触碰任何数据）")
                print(str(exc))
                return 3
            referenced = {line.strip() for line in url_out.splitlines() if line.strip()}
            print(f"已加载被引用 URL 集合: {len(referenced)} 个（content_media ∪ comment_media）")

            safe_files, unknown_files, symlink_files = scan_disk_media(root)
            orphan_files = [f for f in safe_files if f["rel_url"] not in referenced]
            orphan_files.sort(key=lambda f: f["rel_url"])

            # H1 重叠校验：库非空但磁盘与库 URL 无重叠 → 疑似配置错误
            disk_urls = {f["rel_url"] for f in safe_files}
            overlap = len(disk_urls & referenced)
            if all_media_total > 0 and overlap == 0:
                suspect = True
                print("警告: 磁盘文件与数据库被引用 URL 无任何重叠，疑似上传目录/数据库配置错误（媒体结果不可信）")

            missing_files, unparseable = check_missing_files(root, referenced)
            missing_files.sort(key=lambda f: f["url"])

            if unknown_files:
                print(f"非安全命名文件跳过: {len(unknown_files)} 个（仅报告）")
            if symlink_files:
                print(f"符号链接/junction 跳过: {len(symlink_files)} 个（仅报告）")
        else:
            # 媒体目录缺失：与 cleanup_orphan_media.py 的目录门禁一致——库内存在媒体记录却扫不到目录 → 疑似配置错误
            media_note = f"媒体目录缺失：上传根目录不存在或缺少 {MEDIA_DIRS} 任一子目录（{root}）"
            if all_media_total > 0:
                suspect = True
                media_note += (f"，但库中存在媒体记录（content_media+comment_media={all_media_total} 条），"
                               "疑似上传目录/数据库配置错误")
            print(media_note)
    else:
        media_note = "已指定 --no-media，跳过磁盘媒体检查"

    # ---- 汇总 ----
    orphan_total = sum(len(ids) for _, ids in orphan_results)
    dup_total = sum(len(rows) for _, rows in dup_results)
    missing_total = len(missing_files)
    orphan_mb = sum(f["size"] for f in orphan_files)

    print("===== 完整性检查汇总（只读）=====")
    for name, ids in orphan_results:
        print(f"  {name}: {len(ids)} 条")
    for name, rows in dup_results:
        print(f"  {name}: {len(rows)} 组")
    if media_scanned:
        print(f"  孤儿媒体文件: {len(orphan_files)} 个（共 {orphan_mb} 字节）")
        print(f"  库引用缺失文件: {missing_total} 个（不可解析 URL: {unparseable} 个）")
    if orphan_total == dup_total == len(orphan_files) == missing_total == 0:
        print("结论: 未发现脏数据（若跳过媒体检查则仅代表库内检查项）")
    else:
        print("结论: 发现脏数据，请按报告逐项处理——"
              "孤儿 content_media/comment_like 可走 cleanup_data.py --execute，"
              "孤儿媒体可走 cleanup_orphan_media.py --quarantine；"
              "其余（孤儿 comment/content_like/comment_media/楼中楼、重复引用、库引用缺失文件）需人工或后续工具处理")

    # ---- 报告落盘 ----
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    now = datetime.now()
    report = REPORT_DIR / f"INTEGRITY_REPORT_{now.strftime('%Y-%m-%d_%H%M%S')}.md"

    lines = [
        "# 完整性校验报告",
        "",
        f"> 时间：{now.strftime('%Y-%m-%d %H:%M:%S')}",
        f"> 连接：{MYSQL_HOST}:{MYSQL_PORT}/{DB_NAME}（只读 dry-run）",
        f"> 上传根目录：{resolve_upload_root()}" if media_scanned else "> 上传根目录：-（未扫描媒体）",
        f"> 模式：dry-run（未写库、未移动文件）",
        "",
        "## 汇总",
        "",
        f"- 孤儿记录：**{orphan_total}** 条",
        f"- 重复引用：**{dup_total}** 组",
        f"- 孤儿媒体文件：**{len(orphan_files)}** 个（共 **{orphan_mb}** 字节）" if media_scanned else "- 孤儿媒体文件：未检查（--no-media 或媒体目录缺失）",
        f"- 库引用缺失文件：**{missing_total}** 个（不可解析 URL：{unparseable} 个）" if media_scanned else "- 库引用缺失文件：未检查（--no-media 或媒体目录缺失）",
        "",
        "> 清理指引（按项区分）：孤儿 content_media / 孤儿 comment_like 可经 `tools/cleanup_data.py --execute` 自动清理；",
        "> 孤儿媒体文件可经 `tools/cleanup_orphan_media.py --quarantine` 隔离回收；",
        "> 其余（孤儿 comment / content_like / comment_media / 楼中楼孤儿回复、重复引用、库引用缺失文件）`cleanup_data.py` 暂不支持，需人工或后续工具处理。",
        "",
        "## 1. 孤儿记录",
        "",
    ]
    for name, ids in orphan_results:
        lines.append(f"- **{name}**：{len(ids)} 条  {fmt_ids(ids)}")
    lines += ["", "## 2. 重复引用", ""]
    for name, rows in dup_results:
        lines.append(f"- **{name}**：{len(rows)} 组  {fmt_dup(rows)}")
    lines += ["", "## 3. 孤儿媒体文件", ""]
    if media_note:
        lines.append(f"- {media_note}")
    elif not orphan_files:
        lines.append("- （无）")
    for f in orphan_files[:MAX_DETAIL]:
        lines.append(f"- `{f['rel_url']}`（{f['size']} 字节）")
    if len(orphan_files) > MAX_DETAIL:
        lines.append(f"- ...（共 {len(orphan_files)} 个，明细已截断）")
    lines += ["", "## 4. 库引用缺失文件", ""]
    if media_note:
        lines.append(f"- {media_note}")
    elif not missing_files:
        lines.append("- （无）")
    for f in missing_files[:MAX_DETAIL]:
        lines.append(f"- `{f['url']}` → `{f['path']}` 不存在")
    if len(missing_files) > MAX_DETAIL:
        lines.append(f"- ...（共 {len(missing_files)} 个，明细已截断）")
    if unparseable:
        lines.append(f"- 另有 {unparseable} 个 URL 无法映射到磁盘路径（外部/非 /upload 前缀），未检查存在性")
    lines += ["", "## 5. 跳过/异常说明", ""]
    if media_scanned:
        for f in unknown_files[:MAX_DETAIL]:
            lines.append(f"- 非安全命名文件跳过（未纳入媒体判定，仅报告）：`{f['rel_url']}`")
        if len(unknown_files) > MAX_DETAIL:
            lines.append(f"- ...非安全命名文件共 {len(unknown_files)} 个，明细已截断")
        for f in symlink_files[:MAX_DETAIL]:
            lines.append(f"- 符号链接/junction 跳过：`{f['rel_url']}`")
        if len(symlink_files) > MAX_DETAIL:
            lines.append(f"- ...符号链接/junction 共 {len(symlink_files)} 个，明细已截断")
    if media_note:
        lines.append(f"- {media_note}")
    if suspect:
        lines.append("- 疑似配置错误：库内存在媒体记录但磁盘扫描与被引用 URL 无重叠（或媒体目录缺失），诊断退出码 5，媒体检查结果不可信")
    if not any([media_note, suspect, unknown_files, symlink_files]):
        lines.append("- （无）")

    report.write_text("\n".join(lines), encoding="utf-8")
    print(f"报告: {report}")

    return 5 if suspect else 0


if __name__ == "__main__":
    sys.exit(main())
