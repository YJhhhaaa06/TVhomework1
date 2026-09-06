# -*- coding: utf-8 -*-
"""T6 测试/运维统一入口（包装层；不修改 run_tests.py 任何安全逻辑）。

用法：
    python tools/tv.py                                     # 打印两级帮助（首行含当前环境）
    python tools/tv.py env test|prod                       # 复制模板覆盖 active.conf（持久切换）
    python tools/tv.py --env test|prod <子命令> [参数...]  # 本次命令临时用指定环境（不写盘）
    python tools/tv.py <子命令> [参数...]                  # 按 active.conf 当前环境执行

子命令（均包装既有脚本，参数与退出码原样透传）：
    admin                 tools/admin.py（--promote/--demote 写操作，prod 需二次确认）
    cleanup               tools/cleanup_data.py（--execute 写操作，prod 需二次确认）
    integrity             tools/check_integrity.py（--fix 写操作，prod 需二次确认）
    backup                tools/backup.py（prod 需二次确认）
    init-test-db          tools/init_test_db.py（固定测试库；仅在 test 语境可执行）
    test                  tools/run_tests_report.py（默认 all；固定测试库；仅在 test 语境可执行）
    cleanup-orphan-media  tools/cleanup_orphan_media.py（移动磁盘媒体文件；
                          仅允许 prod 语境，test 一律拒绝）

环境语义（T6）：
    * 持久环境 = tools/env/active.conf（由 `env` 子命令复制生成；缺失回退 test）
    * 经本入口执行时以声明环境（active / --env）的配置为准：tv.py 会把对应 conf
      的 DB_* 注入子进程环境变量（覆盖父进程继承的 DB_* 残留），保证
      "声明环境 == 实际连接库"；直接运行脚本（绕过 tv.py）时进程环境变量 DB_*
      仍是最高优先级
    * --env 临时覆盖：不写盘、不改 active.conf

退出码：透传被包装脚本的退出码。
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import db_config

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ACTIVE_ENV_KEY = "ACTIVE_ENV"

# 子命令 -> (脚本名, 一行描述)
SUBCOMMANDS = [
    ("admin", "tools/admin.py", "管理员维护（--list/--promote/--demote）"),
    ("cleanup", "tools/cleanup_data.py", "数据清理（--execute 才写库）"),
    ("integrity", "tools/check_integrity.py", "完整性校验（--fix 才修复）"),
    ("backup", "tools/backup.py", "数据库备份"),
    ("init-test-db", "tools/init_test_db.py", "重建测试库（固定 test 环境）"),
    ("test", "tools/run_tests_report.py", "跑测试（默认 all；固定 test 环境）"),
    ("cleanup-orphan-media", "tools/cleanup_orphan_media.py", "孤儿媒体回收（仅 prod 环境）"),
]
CMD_TO_SCRIPT = {name: rel for name, rel, _ in SUBCOMMANDS}
CMD_TO_DESC = {name: desc for name, _, desc in SUBCOMMANDS}

FORCE_TEST_CMDS = ("init-test-db", "test")      # 固定测试库语义；prod 语境拒绝
PROD_ONLY_CMDS = ("cleanup-orphan-media",)      # 会移动磁盘媒体文件；test 语境拒绝

# backup.py 不解析参数（无 argparse），-h/--help 会按无参数直接触发备份；
# tv.py 在此拦截并打印帮助（codex 验收反馈 3）。
BACKUP_HELP = """backup - 数据库备份（tools/backup.py）

用法:
    python tools\\tv.py backup                # 备份当前声明环境（默认 test）的数据库
    python tools\\tv.py --env prod backup     # 备份生产库（需二次确认）

说明:
    * 输出到 D:\\dev\\WorkSpace\\VideoPlatform\\auto_backup\\<时间戳>\\（db.sql + manifest.txt）
    * 仅备份数据库；媒体资源请自行打包备份（手动）。
    * prod 语境执行需二次确认。
    * 原生直调: python tools\\backup.py（不经 tv.py，环境变量 DB_* 直接生效）
"""


def die(message: str, code: int = 1) -> None:
    print(f"错误: {message}", file=sys.stderr)
    sys.exit(code)


def print_help() -> None:
    print("tv.py - 测试/运维统一入口（T6，包装层）")
    print(f"当前环境: {db_config.env_summary()}" + ("（未初始化回退 test，建议 `env test`）"
                                                   if db_config.ENV_SOURCE.startswith("test.conf")
                                                   else ""))
    print("    切换:  python tools/tv.py env test|prod（复制覆盖 active.conf，持久）")
    print()
    print("用法: python tools/tv.py [--env test|prod] <子命令> [参数...]")
    print()
    print("子命令:")
    for name, _rel, desc in SUBCOMMANDS:
        print(f"  {name:<22} {desc}")
    print()
    print("详细参数: python tools/tv.py <子命令> -h")
    print("临时覆盖: python tools/tv.py --env prod cleanup --execute   # 本次用 prod，不改 active.conf")


def _parse_argv(argv: list[str]) -> tuple[str | None, list[str]]:
    """提取全局 --env，其余原样收集为 (子命令, 透传参数)。"""
    env_override: str | None = None
    rest: list[str] = []
    i = 0
    while i < len(argv):
        tok = argv[i]
        if tok == "--env":
            if i + 1 >= len(argv):
                die("--env 缺少环境名（可选: test / prod）")
            if env_override is not None:
                die("--env 重复指定（一次命令只允许一个 --env）")
            env_override = argv[i + 1]
            i += 2
        elif tok.startswith("--env="):
            if env_override is not None:
                die("--env 重复指定（一次命令只允许一个 --env）")
            env_override = tok.split("=", 1)[1]
            i += 1
        else:
            rest.append(tok)
            i += 1
    if env_override is not None and env_override not in ("test", "prod"):
        die(f"未知环境 '{env_override}'（可选: test / prod）")
    return env_override, rest


def cmd_env(name: str) -> int:
    """复制对应环境模板覆盖 active.conf（持久切换，不写盘到 git 追踪文件）。"""
    src = db_config.CONF_FILES[name]
    if not src.is_file():
        if name == "prod":
            print(f"缺少 {src}：请复制 tools/env/prod.conf.example 为 prod.conf")
            print("并填入真实生产连接口令后重试（prod.conf 已被 .gitignore 排除，不进 git）。")
        else:
            print(f"缺少 {src}")
        return 1
    header = (f"# 由 `python tools/tv.py env {name}` 生成（T6）。"
              f"手工编辑无效；修改请编辑 {name}.conf 后重新切换。\n")
    db_config.ACTIVE_FILE.write_text(
        header + f"{ACTIVE_ENV_KEY}={name}\n" + src.read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    db_config.use()  # 重新加载当前生效配置
    print(f"已切换当前环境: {db_config.env_summary()}")
    return 0


def needs_prod_confirm(cmd: str, args: list[str]) -> bool:
    """该命令在当前 prod 语境下是否属于可能产生副作用的操作，需要二次确认。"""
    if cmd == "admin":
        return any(a in args for a in ("--promote", "--demote"))
    if cmd == "cleanup":
        return "--execute" in args
    if cmd == "integrity":
        return "--fix" in args
    if cmd == "backup":
        return True
    if cmd == "cleanup-orphan-media":
        return "--quarantine" in args
    return False


def confirm_prod(cmd: str) -> bool:
    print(f"警告: 当前为生产环境，即将执行 {cmd} 的写/危险操作。请逐字确认。")
    try:
        answer = input("输入 yes 继续，其它输入中止: ").strip().lower()
    except EOFError:
        answer = ""
    return answer == "yes"


def run_subcommand(cmd: str, args: list[str], env_override: str | None) -> int:
    effective_env = env_override or db_config.ENV_NAME

    # ---- 环境语义护栏 ----
    if cmd in FORCE_TEST_CMDS and effective_env == "prod":
        print(f"错误: {cmd} 固定操作测试库，不允许在 prod 语境执行；"
              f"请先 `python tools/tv.py env test` 或加 `--env test`", file=sys.stderr)
        return 1
    if cmd in PROD_ONLY_CMDS and effective_env != "prod":
        print(f"错误: {cmd} 会移动磁盘媒体文件；测试/生产媒体目录已隔离"
              f"（T2：18080 测试实例落 media-test，生命周期由 run_tests 自动管理），"
              f"本工具为保护真实内容的回收工具，仅允许 prod 语境执行", file=sys.stderr)
        return 1

    # ---- 配置可用性检查（横幅前，失败即友好退出）----
    # test 子命令无需 DB_*（run_tests_report 自持测试库注入），跳过 conf 读取与
    # 环境变量注入——避免把测试口令注入 Maven(junit) 使 AppConfig 覆盖用户名/密码
    # 而 JDBC URL 仍指向 3306，造成 Access denied（codex 验收反馈 2）。
    skip_env = cmd == "test"
    if not skip_env:
        conf_path = db_config.CONF_FILES[effective_env]
        if not conf_path.is_file():
            print(f"错误: 缺少配置 {conf_path}；"
                  + (f"请复制 {conf_path.name}.example 为 {conf_path.name} 并填写真实口令"
                     if effective_env == "prod"
                     else "请检查 tools/env/ 目录"),
                  file=sys.stderr)
            return 1
        props = db_config.read_props(conf_path)
        missing = [k for k in db_config.KEYS if k not in props]
        if missing:
            print(f"错误: 配置 {conf_path} 缺少必要键: {', '.join(missing)}", file=sys.stderr)
            return 1

    # ---- 横幅 ----
    if env_override:
        banner = f"本次环境: {env_override}（--env 临时覆盖，未修改 active.conf）"
    else:
        banner = (f"当前环境: {db_config.ENV_NAME}（tools/env/active.conf；"
                  f"切换: python tools/tv.py env test|prod）")
    print("=" * 60)
    print(banner)
    print("=" * 60)

    # ---- prod 写操作二次确认 ----
    if effective_env == "prod" and needs_prod_confirm(cmd, args):
        if not confirm_prod(cmd):
            print("已中止。")
            return 1

    # ---- 组装子进程 ----
    # 非 test 子命令：强制以"声明环境"的配置为准：把 active/--env 对应 conf 的
    # DB_* 注入子进程环境变量（覆盖父进程继承的 DB_* 残留）。保证横幅/门禁判定
    # 的环境 == 子进程真实连接库，防止环境变量残留绕过 prod 确认与
    # cleanup-orphan-media 禁 test 护栏。
    env = os.environ.copy()
    if not skip_env:
        env["DB_HOST"] = props["DB_HOST"]
        env["DB_PORT"] = props["DB_PORT"]
        env["DB_USER"] = props["DB_USER"]
        env["DB_PASSWORD"] = props["DB_PASSWORD"]
        env["DB_NAME"] = props["DB_NAME"]

    script = PROJECT_ROOT / CMD_TO_SCRIPT[cmd]
    if not script.is_file():
        die(f"缺少脚本 {script}")
    if cmd == "test":
        child_args = args or ["all"]  # 等价 run_tests_report.py all
    else:
        child_args = args
    print(f"执行: python {script.name} {' '.join(child_args) if child_args else ''}".rstrip())

    result = subprocess.run(
        [sys.executable, str(script)] + child_args,
        cwd=str(PROJECT_ROOT),
        env=env,
    )
    return result.returncode


def main(argv: list[str]) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    env_override, rest = _parse_argv(argv)

    if not rest:  # 裸跑 -> 帮助
        if env_override is not None:
            die("--env 需配合子命令使用；裸跑请直接 `python tools/tv.py`")
        print_help()
        return 0

    cmd = rest[0]
    args = rest[1:]

    if cmd == "env":
        if env_override:
            die("env 子命令不接受 --env（环境名直接作为参数）")
        if len(args) != 1 or args[0] not in ("test", "prod"):
            print("用法: python tools/tv.py env test|prod（仅接受一个环境名参数）")
            return 1
        return cmd_env(args[0])

    if cmd == "-h" or cmd == "--help":
        print_help()
        return 0

    if cmd == "backup" and ("-h" in args or "--help" in args):
        print(BACKUP_HELP)
        return 0

    if cmd not in CMD_TO_SCRIPT:
        print(f"错误: 未知子命令 '{cmd}'（可选: {', '.join(CMD_TO_SCRIPT)}）", file=sys.stderr)
        print("提示: 裸跑 `python tools/tv.py` 查看帮助")
        return 2

    return run_subcommand(cmd, args, env_override)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))