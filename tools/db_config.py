# -*- coding: utf-8 -*-
"""T6 统一 db 配置（tools/env/ 体系）共享模块。

体系（tools/env/）：
    * test.conf         测试库连接（被 git 追踪，仅测试口令）
    * prod.conf         生产库连接（.gitignore 排除；首次由 prod.conf.example 复制填写）
    * active.conf       当前生效 = 复制自 test/prod，首行带 ACTIVE_ENV=<name> 元数据
                        （.gitignore 排除；由 `tv.py env test|prod` 生成）

读取规则（优先级从高到低）：
    1. 进程环境变量：DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME（原机制，兼容 run_tests 注入）
    2. 配置文件：use() 指定环境（test/prod）或 active.conf（缺省 ACTIVE_ENV 键时回退 test）

经 tools/tv.py 执行时，tv.py 会把声明环境（active / --env）的配置值注入子进程
环境变量，覆盖父进程继承的 DB_* 残留，保证"声明环境 == 实际连接库"（B1）。

用法：
    import db_config
    db_config.use()                    # 默认：active.conf（缺失/未初始化回退 test）
    db_config.use("prod")              # 指定环境（临时，不写 active.conf）
    db_config.use(force_test=True)     # 强制测试库（test/init-test-db 场景，不碰 active.conf）
    db_config.DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME / BASE_URL
    db_config.ENV_NAME / db_config.env_summary()   # 当前环境名与来源描述（tv.py 横幅用）
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

ENV_DIR = Path(__file__).resolve().parent / "env"
ACTIVE_FILE = ENV_DIR / "active.conf"
CONF_FILES = {
    "test": ENV_DIR / "test.conf",
    "prod": ENV_DIR / "prod.conf",
}
KEYS = ("DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD", "DB_NAME")
ACTIVE_ENV_KEY = "ACTIVE_ENV"

# 模块级当前生效值（use() 后写入）
DB_HOST = ""
DB_PORT = ""
DB_USER = ""
DB_PASSWORD = ""
DB_NAME = ""
BASE_URL = ""
ENV_NAME = ""
ENV_SOURCE = ""  # 描述：active.conf / test.conf / 环境变量等

_warned_fallback = False


def read_props(path: Path) -> dict:
    """解析 key=value 属性文件（跳过 # 注释与空行）；文件缺失返回空 dict。"""
    props: dict[str, str] = {}
    if not path.is_file():
        return props
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        print(f"错误: 读取配置失败 {path}: {exc}", file=sys.stderr)
        sys.exit(1)
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def _apply(props: dict, source: str, env_name: str) -> None:
    """将配置 dict 应用到模块级常量；进程环境变量（DB_*）优先级最高。"""
    global DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, DB_NAME, BASE_URL
    global ENV_NAME, ENV_SOURCE
    missing = [k for k in KEYS if k not in props]
    if missing:
        print(f"错误: 配置文件 {source} 缺少必要键: {', '.join(missing)}", file=sys.stderr)
        sys.exit(1)
    DB_HOST = os.environ.get("DB_HOST", props["DB_HOST"])
    DB_PORT = os.environ.get("DB_PORT", props["DB_PORT"])
    DB_USER = os.environ.get("DB_USER", props["DB_USER"])
    DB_PASSWORD = os.environ.get("DB_PASSWORD", props["DB_PASSWORD"])
    DB_NAME = os.environ.get("DB_NAME", props["DB_NAME"])
    BASE_URL = props.get("BASE_URL", "")
    ENV_NAME = env_name
    ENV_SOURCE = source
    if os.environ.get("DB_HOST") or os.environ.get("DB_PORT") or os.environ.get(
        "DB_USER"
    ) or os.environ.get("DB_PASSWORD") or os.environ.get("DB_NAME"):
        ENV_SOURCE += " + 环境变量覆盖"


def use(env_name: str | None = None, *, force_test: bool = False) -> None:
    """选择并加载连接配置。

    env_name: 'test' | 'prod' | None(=active.conf；缺失回退 test)
    force_test: True 时强制测试库（init_test_db/test 场景，不碰 active.conf）。
    """
    if force_test:
        env_name = "test"
    if env_name is not None:
        if env_name not in CONF_FILES:
            print(f"错误: 未知环境 '{env_name}'（可选: test / prod）", file=sys.stderr)
            sys.exit(1)
        path = CONF_FILES[env_name]
        source = f"{env_name}.conf（--env 指定）"
        if not path.is_file():
            print(
                f"错误: 缺少配置 {path}；请复制 {path.name}.example 为 {path.name} 并填写",
                file=sys.stderr,
            )
            sys.exit(1)
        props = read_props(path)
        _apply(props, source, env_name)
        return

    # 默认：active.conf（含 ACTIVE_ENV 元数据）
    global _warned_fallback
    props = read_props(ACTIVE_FILE)
    if props:
        active_env = props.get(ACTIVE_ENV_KEY, "")
        if active_env not in CONF_FILES:
            print(
                f"错误: active.conf 中 ACTIVE_ENV='{active_env}' 无效；"
                f"请重新运行 `python tools/tv.py env test|prod`",
                file=sys.stderr,
            )
            sys.exit(1)
        path = CONF_FILES[active_env]
        if not path.is_file():
            hint = (
                "请复制 prod.conf.example 为 prod.conf 后重试"
                "（或运行 `python tools/tv.py env test` 切回测试环境）"
                if active_env == "prod"
                else "请检查 tools/env/ 目录中是否存在对应配置文件"
            )
            print(f"错误: active.conf 指向 {active_env}，但缺少 {path}；{hint}", file=sys.stderr)
            sys.exit(1)
        _apply(read_props(path), f"active.conf（{active_env}）", active_env)
        return

    # active.conf 缺失（首启未初始化）：回退 test 并提示，不写盘
    if not _warned_fallback:
        _warned_fallback = True
        print(
            "提示: tools/env/active.conf 未初始化，按 test 环境处理；"
            "建议运行 `python tools/tv.py env test` 完成初始化",
            file=sys.stderr,
        )
    path = CONF_FILES["test"]
    _apply(read_props(path), "test.conf（active 缺失回退）", "test")


def env_summary() -> str:
    """当前环境简述（tv.py 横幅/帮助首行用）。"""
    return f"{ENV_NAME}（{ENV_SOURCE}）"


# 模块导入即按默认规则加载（active.conf 或回退 test）
use()