# -*- coding: utf-8 -*-
"""在 Codex 沙盒内一键完成：Maven 打包 -> 启动独立 Tomcat -> 跑 pytest -> 关停。

用法:
    python tools/run_tests.py build    # 仅 Maven 离线打包
    python tools/run_tests.py start    # 仅启动独立 Tomcat（端口 18080）
    python tools/run_tests.py test     # 仅跑 pytest（要求 18080 已就绪）
    python tools/run_tests.py stop     # 仅关停独立 Tomcat
    python tools/run_tests.py all      # build -> start -> test -> stop

安全约定:
    * 只使用独立 Tomcat 运行目录与端口 18080，绝不触碰 IDE 在 8080 的实例。
    * 只终止本脚本自己启动的进程（PID 文件 + 命令行双重确认），否则拒绝强杀。
    * 不删除任何用户数据；只覆盖自己的 ROOT.war 和日志。
"""

from __future__ import annotations

import argparse
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

def _env(name: str, default: str) -> str:
    """读取 TV_* 环境变量覆盖沙盒路径；未设置时用默认值（与 conftest.py 的 TV_* 模式一致）。"""
    return os.environ.get(name, default)


PROJECT_ROOT = Path(__file__).resolve().parent.parent
TEST_DIR = PROJECT_ROOT / "src" / "test" / "python"
STAGE8_TARGET = Path(_env("TV_STAGE8_TARGET", r"D:\data\projects\VideoPlatform\stone\temp\stage8-target"))
WAR_FILE = STAGE8_TARGET / "untitled-1.0-SNAPSHOT.war"

MAVEN_CMD = Path(_env("TV_MAVEN_CMD", r"D:\IDE\IDEA\IntelliJ IDEA 2025.3.2\plugins\maven\lib\maven3\bin\mvn.cmd"))
JAVA_HOME = Path(_env("TV_JAVA_HOME", r"D:\dev\DevTools\jdk\openjdk-25.0.2"))
CATALINA_HOME = Path(_env("TV_CATALINA_HOME", r"D:\dev\DevTools\tomcat\apache-tomcat-10.1.54"))
CATALINA_BASE = Path(_env("TV_CATALINA_BASE", r"D:\data\projects\VideoPlatform\stone\temp\tomcat-test-18080"))
M2_REPO = Path(_env("TV_M2_REPO", r"D:\dev\WorkSpace\VideoPlatform\maven"))
PYTEST_DEPS = Path(_env("TV_PYTEST_DEPS", r"D:\dev\WorkSpace\VideoPlatform\temp\pytest-deps"))

HTTP_PORT = 18080
SHUTDOWN_PORT = 18005
# 用 127.0.0.1 而不是 localhost：Tomcat 只绑定 IPv4，Python 连 localhost
# 会先尝试 IPv6 ::1 并等待超时（约 2 秒/次），导致整套测试变慢。
BASE_URL = f"http://127.0.0.1:{HTTP_PORT}"
START_TIMEOUT_SECONDS = 90
STOP_TIMEOUT_SECONDS = 25

PID_FILE = CATALINA_BASE / "logs" / "tomcat.pid"
RUN_LOG = CATALINA_BASE / "logs" / "run.log"
TOMCAT_STDOUT = CATALINA_BASE / "logs" / "tomcat_stdout.log"
TOMCAT_STDERR = CATALINA_BASE / "logs" / "tomcat_stderr.log"

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def log(message: str) -> None:
    line = f"[{time.strftime('%H:%M:%S')}] {message}"
    print(line, flush=True)
    try:
        RUN_LOG.parent.mkdir(parents=True, exist_ok=True)
        with open(RUN_LOG, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except OSError:
        pass


def base_env() -> dict:
    env = os.environ.copy()
    env["JAVA_HOME"] = str(JAVA_HOME)
    env["CATALINA_HOME"] = str(CATALINA_HOME)
    env["CATALINA_BASE"] = str(CATALINA_BASE)
    return env


def require(*paths: Path) -> None:
    missing = [str(p) for p in paths if not p.exists()]
    if missing:
        log("缺少必要路径: " + ", ".join(missing))
        sys.exit(2)


def port_open(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(2)
        try:
            sock.connect(("127.0.0.1", port))
            return True
        except OSError:
            return False


def app_ready() -> bool:
    try:
        with urllib.request.urlopen(BASE_URL + "/start", timeout=3) as resp:
            return resp.status == 200
    except Exception:
        return False


def run_hidden(cmd: list, **kwargs) -> subprocess.CompletedProcess:
    kwargs.setdefault("creationflags", NO_WINDOW)
    return subprocess.run(cmd, **kwargs)


def our_java_pids() -> list:
    """返回命令行中包含 CATALINA_BASE 的 java.exe 进程 PID 列表。"""
    try:
        result = subprocess.run(
            [
                "powershell", "-NoProfile", "-Command",
                "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" "
                "| ForEach-Object { $_.ProcessId.ToString() + '|' + $_.CommandLine }",
            ],
            capture_output=True,
            text=True,
            timeout=20,
            creationflags=NO_WINDOW,
        )
    except Exception:
        return []
    pids = []
    for line in result.stdout.splitlines():
        if "|" in line and str(CATALINA_BASE) in line:
            pid_text = line.split("|", 1)[0].strip()
            if pid_text.isdigit():
                pids.append(int(pid_text))
    return pids


def wrapper_pid_is_ours(pid: int) -> bool:
    """PID 文件里的包装进程是否仍然是对应的 catalina 命令。"""
    try:
        result = subprocess.run(
            [
                "powershell", "-NoProfile", "-Command",
                f"(Get-CimInstance Win32_Process -Filter 'ProcessId={pid}').CommandLine",
            ],
            capture_output=True,
            text=True,
            timeout=20,
            creationflags=NO_WINDOW,
        )
    except Exception:
        cmdline = ""
    else:
        cmdline = result.stdout or ""
    if "catalina.bat" in cmdline:
        return True
    # CIM 在沙盒内可能被拒，回退：进程是 cmd.exe，且 PID 与本脚本 PID 文件一致。
    try:
        result = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
            capture_output=True,
            text=True,
            timeout=15,
            creationflags=NO_WINDOW,
        )
        image = (result.stdout.split('"')[1] if '"' in result.stdout else "").lower()
    except Exception:
        image = ""
    if image == "cmd.exe" and PID_FILE.exists():
        try:
            return PID_FILE.read_text(encoding="ascii").strip() == str(pid)
        except OSError:
            return False
    return False


def read_pid_file():
    if not PID_FILE.exists():
        return None
    try:
        return int(PID_FILE.read_text(encoding="ascii").strip())
    except (OSError, ValueError):
        return None


def remove_pid_file() -> None:
    if PID_FILE.exists():
        try:
            PID_FILE.unlink()
        except OSError:
            pass


# ---------------------------------------------------------------------------
# build
# ---------------------------------------------------------------------------

def cmd_build(_args) -> int:
    require(JAVA_HOME / "bin" / "java.exe", MAVEN_CMD, M2_REPO)
    cmd = [
        str(MAVEN_CMD), "-o", "-q", "package", "-DskipTests",
        "-Duser.home=C:\\Users\\ASUS",
        f"-Dmaven.repo.local={M2_REPO}",
        f"-Dstage8.buildDir={STAGE8_TARGET}",
    ]
    log("开始 Maven 离线打包 ...")
    result = run_hidden(
        cmd,
        cwd=str(PROJECT_ROOT),
        env=base_env(),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        log("Maven 打包失败，详见下方输出")
        if result.stdout:
            print(result.stdout[-4000:])
        if result.stderr:
            print(result.stderr[-4000:], file=sys.stderr)
        return result.returncode
    if not WAR_FILE.exists():
        log(f"Maven 成功但未找到 war: {WAR_FILE}")
        return 3
    log(f"打包完成: {WAR_FILE} ({WAR_FILE.stat().st_size} bytes)")
    return 0


# ---------------------------------------------------------------------------
# start / stop
# ---------------------------------------------------------------------------

def prepare_base() -> None:
    CATALINA_BASE.mkdir(parents=True, exist_ok=True)
    for name in ("conf", "logs", "temp", "webapps", "work"):
        (CATALINA_BASE / name).mkdir(exist_ok=True)

    server_xml = CATALINA_BASE / "conf" / "server.xml"
    if not server_xml.exists():
        shutil.copytree(CATALINA_HOME / "conf", CATALINA_BASE / "conf", dirs_exist_ok=True)
        log("已从 CATALINA_HOME 初始化 conf 目录")

    text = server_xml.read_text(encoding="utf-8")
    updated = (
        text.replace('port="8005"', f'port="{SHUTDOWN_PORT}"')
        .replace(
            '<Connector port="8080"',
            f'<Connector port="{HTTP_PORT}" address="127.0.0.1"',
        )
    )
    if updated != text:
        server_xml.write_text(updated, encoding="utf-8")
        log(f"已更新端口配置: HTTP={HTTP_PORT}, shutdown={SHUTDOWN_PORT}")

    current = server_xml.read_text(encoding="utf-8")
    if (
        f'<Connector port="{HTTP_PORT}" address="127.0.0.1"' not in current
        or f'port="{SHUTDOWN_PORT}"' not in current
    ):
        log(f"server.xml 端口校验失败，拒绝启动（防止误用 8080）: {server_xml}")
        sys.exit(9)

    shutil.copy2(WAR_FILE, CATALINA_BASE / "webapps" / "ROOT.war")
    log("已部署 ROOT.war")


def _start_server():
    require(JAVA_HOME / "bin" / "java.exe", CATALINA_HOME / "bin" / "catalina.bat", WAR_FILE)

    if app_ready():
        log(f"{BASE_URL} 上已有本应用在运行，直接复用")
        return None
    if port_open(HTTP_PORT):
        log(f"端口 {HTTP_PORT} 被其他程序占用，拒绝启动（不碰未知进程）")
        sys.exit(4)

    prepare_base()
    log(f"启动独立 Tomcat: {CATALINA_BASE} (端口 {HTTP_PORT})")

    stdout_fh = open(TOMCAT_STDOUT, "wb", buffering=0)
    stderr_fh = open(TOMCAT_STDERR, "wb", buffering=0)
    bat = CATALINA_HOME / "bin" / "catalina.bat"
    proc = subprocess.Popen(
        ["cmd", "/c", "call", str(bat), "run"],
        cwd=str(CATALINA_HOME),
        env=base_env(),
        stdout=stdout_fh,
        stderr=stderr_fh,
        creationflags=NO_WINDOW,
    )
    PID_FILE.parent.mkdir(parents=True, exist_ok=True)
    PID_FILE.write_text(str(proc.pid), encoding="ascii")
    log(f"Tomcat 包装进程 PID={proc.pid}，等待就绪 ...")

    start = time.time()
    while time.time() - start < START_TIMEOUT_SECONDS:
        if proc.poll() is not None:
            log(f"Tomcat 提前退出，code={proc.returncode}，日志见 {TOMCAT_STDOUT} / {TOMCAT_STDERR}")
            _stop_server(proc)
            sys.exit(5)
        if app_ready():
            log(f"Tomcat 就绪（{time.time() - start:.1f}s）: {BASE_URL}")
            return proc
        time.sleep(2)

    log(f"等待超时（{START_TIMEOUT_SECONDS}s），日志见 {TOMCAT_STDOUT} / {TOMCAT_STDERR}")
    _stop_server(proc)
    sys.exit(6)


def cmd_start(_args) -> int:
    _start_server()
    return 0


def _stop_server(proc) -> int:
    if not port_open(HTTP_PORT) and (proc is None or proc.poll() is not None):
        log("18080 未监听，无需停止")
        remove_pid_file()
        return 0

    log("优雅停止独立 Tomcat ...")
    bat = CATALINA_HOME / "bin" / "catalina.bat"
    run_hidden(
        ["cmd", "/c", "call", str(bat), "stop"],
        env=base_env(),
        capture_output=True,
        timeout=30,
    )

    deadline = time.time() + STOP_TIMEOUT_SECONDS
    while time.time() < deadline and port_open(HTTP_PORT):
        time.sleep(1)

    if not port_open(HTTP_PORT):
        log("Tomcat 已停止")
        remove_pid_file()
        return 0

    # 强制清理：只允许终止确认属于本脚本的进程
    if proc is not None and proc.poll() is None:
        log(f"优雅停止未完成，终止本脚本启动的进程树 PID={proc.pid}")
        run_hidden(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True, timeout=15)
        time.sleep(2)
        if not port_open(HTTP_PORT):
            log("Tomcat 已停止（强制清理）")
            remove_pid_file()
            return 0
        log("强制清理后 18080 仍被占用，请人工检查")
        return 7

    pid_from_file = read_pid_file()
    if pid_from_file is not None and wrapper_pid_is_ours(pid_from_file):
        log(f"优雅停止未完成，终止 PID 文件对应进程树 PID={pid_from_file}")
        run_hidden(["taskkill", "/PID", str(pid_from_file), "/T", "/F"], capture_output=True, timeout=15)
        time.sleep(2)
        if not port_open(HTTP_PORT):
            log("Tomcat 已停止（强制清理）")
            remove_pid_file()
            return 0
        log("强制清理后 18080 仍被占用，请人工检查")
        return 7

    log("存在残留进程但无法确认属于本脚本，拒绝强制终止，请人工检查 18080")
    return 7


def cmd_stop(_args) -> int:
    return _stop_server(None)


# ---------------------------------------------------------------------------
# test
# ---------------------------------------------------------------------------

def cmd_test(_args) -> int:
    require(PYTEST_DEPS)
    if not app_ready():
        log(f"{BASE_URL} 应用未就绪，请先运行 start")
        return 8

    env = os.environ.copy()
    existing_pythonpath = env.get("PYTHONPATH")
    env["PYTHONPATH"] = str(PYTEST_DEPS) + (
        os.pathsep + existing_pythonpath if existing_pythonpath else ""
    )
    env["TV_BASE_URL"] = BASE_URL

    cmd = [sys.executable, "-m", "pytest", str(TEST_DIR), "-v", "--tb=short"]
    log("开始 pytest ...")
    result = subprocess.run(cmd, cwd=str(PROJECT_ROOT), env=env)
    log(f"pytest 结束，exit={result.returncode}")
    return result.returncode


# ---------------------------------------------------------------------------
# all
# ---------------------------------------------------------------------------

def cmd_all(_args) -> int:
    return_code = cmd_build(_args)
    if return_code != 0:
        return return_code
    proc = _start_server()
    try:
        return cmd_test(_args)
    finally:
        _stop_server(proc)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Codex 沙盒内一键构建/启动/测试/关停（独立 Tomcat 18080）"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    for name in ("build", "start", "test", "stop", "all"):
        subparsers.add_parser(name, help=f"执行 {name}")
    args = parser.parse_args()

    handlers = {
        "build": cmd_build,
        "start": cmd_start,
        "test": cmd_test,
        "stop": cmd_stop,
        "all": cmd_all,
    }
    sys.exit(handlers[args.command](args))


if __name__ == "__main__":
    main()
