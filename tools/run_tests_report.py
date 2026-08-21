# -*- coding: utf-8 -*-
"""主会话测试收口包装：完整输出落盘，stdout 只回显紧凑摘要。

用法（与 tools/run_tests.py 参数一一对应，默认 all）:
    python tools/run_tests_report.py all
    python tools/run_tests_report.py build|start|test|stop
    python tools/run_tests_report.py junit   # mvn -o test（JUnit/Mockito 单测）

设计约定:
    * 纯标准库；不改动 tools/run_tests.py 的任何安全逻辑。
    * 完整输出写入 D:\\data\\projects\\VideoPlatform\\stone\\temp\\test-reports\\run-<时间戳>.log。
    * 机器可读结果写入同目录 latest.json（exit_code/pytest 计数/构建状态/端口状态/log 路径）。
    * stdout 只打印几行摘要，避免测试输出灌入主会话上下文。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
from datetime import datetime
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
RUN_TESTS_SCRIPT = PROJECT_ROOT / "tools" / "run_tests.py"
REPORT_DIR = Path(r"D:\data\projects\VideoPlatform\stone\temp\test-reports")
STAGE8_TARGET = Path(r"D:\data\projects\VideoPlatform\stone\temp\stage8-target")
WAR_FILE = STAGE8_TARGET / "untitled-1.0-SNAPSHOT.war"
HTTP_PORT = 18080
JAVA_HOME = Path(r"D:\dev\DevTools\jdk\openjdk-25.0.2")
MAVEN_CMD = Path(r"D:\IDE\IDEA\IntelliJ IDEA 2025.3.2\plugins\maven\lib\maven3\bin\mvn.cmd")
M2_REPO = Path(r"D:\dev\WorkSpace\VideoPlatform\maven")
VALID_PHASES = ("build", "start", "test", "stop", "all", "junit")

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def port_open(port: int) -> bool:
    """127.0.0.1:port 是否可连接（与 run_tests.py 口径一致）。"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(2)
        try:
            sock.connect(("127.0.0.1", port))
            return True
        except OSError:
            return False


def parse_pytest_summary(output: str) -> dict:
    """从 pytest 汇总行提取计数；找不到汇总行时返回 None 计数。"""
    summary_line = None
    for line in output.splitlines():
        if (
            "====" in line
            and " in " in line
            and (" passed" in line or " failed" in line)
        ):
            summary_line = line
    if summary_line is None:
        return {
            "passed": None,
            "failed": None,
            "errors": None,
            "skipped": None,
            "summary_line": None,
        }

    def count(label: str):
        match = re.search(rf"(\d+)\s+{label}", summary_line)
        return int(match.group(1)) if match else 0

    return {
        "passed": count("passed"),
        "failed": count("failed"),
        "errors": count("error"),
        "skipped": count("skipped"),
        "summary_line": summary_line.strip(),
    }


def build_ok(output: str, phase: str):
    """build/all 阶段判断构建是否成功；其余阶段返回 None。"""
    if phase not in ("build", "all"):
        return None
    return "Maven 打包失败" not in output and "BUILD FAILURE" not in output


def parse_junit_summary(output: str) -> dict:
    """汇总 surefire 的 Tests run 行；找不到时返回 None 计数。"""
    lines = [
        line.strip()
        for line in output.splitlines()
        if "Tests run:" in line and "Failures:" in line and " -- in " in line
    ]
    if not lines:
        return {
            "tests": None,
            "failures": None,
            "errors": None,
            "skipped": None,
            "summary_line": None,
        }

    def total(label: str) -> int:
        total = 0
        for line in lines:
            match = re.search(rf"{label}:\s*(\d+)", line)
            if match:
                total += int(match.group(1))
        return total

    return {
        "tests": total("Tests run"),
        "failures": total("Failures"),
        "errors": total("Errors"),
        "skipped": total("Skipped"),
        "summary_line": lines[-1],
    }


def iso_mtime(path: Path):
    try:
        return datetime.fromtimestamp(path.stat().st_mtime).isoformat(timespec="seconds")
    except OSError:
        return None


def main() -> int:
    parser = argparse.ArgumentParser(
        description="主会话测试收口：完整输出落盘，stdout 只回显摘要"
    )
    parser.add_argument(
        "phase",
        nargs="?",
        default="all",
        help="build|start|test|stop|all|junit，默认 all",
    )
    args = parser.parse_args()
    phase = args.phase

    started = datetime.now()
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    log_file = REPORT_DIR / f"run-{started.strftime('%Y%m%d_%H%M%S')}.log"

    if phase not in VALID_PHASES:
        output = (
            f"[run_tests_report] 未知子命令: {phase}"
            f"（可选: build|start|test|stop|all|junit）\n"
        )
        exit_code = 2
    elif not RUN_TESTS_SCRIPT.exists():
        output = f"[run_tests_report] 缺少脚本: {RUN_TESTS_SCRIPT}\n"
        exit_code = 2
    else:
        env = os.environ.copy()
        env["PYTHONIOENCODING"] = "utf-8"
        if phase == "junit":
            env["JAVA_HOME"] = str(JAVA_HOME)
            cmd = [
                "cmd", "/c", "call", str(MAVEN_CMD), "-o", "test",
                "-Duser.home=C:\\Users\\ASUS",
                f"-Dmaven.repo.local={M2_REPO}",
                f"-Dstage8.buildDir={STAGE8_TARGET}",
            ]
        else:
            cmd = [sys.executable, str(RUN_TESTS_SCRIPT), phase]
        try:
            result = subprocess.run(
                cmd,
                cwd=str(PROJECT_ROOT),
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                encoding="utf-8",
                errors="replace",
                creationflags=NO_WINDOW,
            )
            output = result.stdout or ""
            exit_code = result.returncode
        except OSError as exc:
            output = f"[run_tests_report] 启动子进程失败: {exc}\n"
            exit_code = 127
        except Exception as exc:  # 兜底，保证留痕
            output = f"[run_tests_report] 未预期错误: {exc}\n"
            exit_code = 126

    finished = datetime.now()
    pytest = parse_pytest_summary(output)
    junit = parse_junit_summary(output)
    report = {
        "script": "mvn -o test" if phase == "junit" else f"run_tests.py {phase}",
        "phase": phase,
        "started_at": started.isoformat(timespec="seconds"),
        "finished_at": finished.isoformat(timespec="seconds"),
        "exit_code": exit_code,
        "build_ok": (exit_code == 0) if phase == "junit" else build_ok(output, phase),
        "pytest": {
            "passed": pytest["passed"],
            "failed": pytest["failed"],
            "errors": pytest["errors"],
            "skipped": pytest["skipped"],
        },
        "pytest_summary": pytest["summary_line"],
        "junit": {
            "tests": junit["tests"],
            "failures": junit["failures"],
            "errors": junit["errors"],
            "skipped": junit["skipped"],
        },
        "junit_summary": junit["summary_line"],
        "war": {
            "exists": WAR_FILE.exists(),
            "path": str(WAR_FILE),
            "mtime": iso_mtime(WAR_FILE),
        },
        "port_18080_open_after": port_open(HTTP_PORT),
        "log_file": str(log_file),
    }

    try:
        log_file.write_text(output, encoding="utf-8")
    except OSError as exc:
        print(f"[run_tests_report] 写完整日志失败: {exc}")

    report_path = REPORT_DIR / "latest.json"
    try:
        report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    except OSError as exc:
        print(f"[run_tests_report] 写 latest.json 失败: {exc}")

    if phase == "junit":
        result_line = junit["summary_line"] or "surefire 无 Tests run 行"
    else:
        result_line = pytest["summary_line"] or "pytest 无汇总行"
    port_state = "open" if report["port_18080_open_after"] else "closed"
    print(
        f"[run_tests_report] phase={phase} exit={exit_code} | {result_line} "
        f"| build_ok={report['build_ok']} | 18080_after={port_state}"
    )
    print(f"log: {log_file}")
    print(f"report: {report_path}")
    if exit_code != 0:
        print(f'hint: Get-Content -Encoding UTF8 -Tail 60 "{log_file}"')
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
