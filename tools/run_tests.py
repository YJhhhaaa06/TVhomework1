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
    * CATALINA_BASE 与 CATALINA_HOME 相等或互为祖先时拒绝启动（防误配覆盖 Tomcat 本体）。
    * 只终止本脚本自己启动的进程（PID 文件 + 命令行双重确认），否则拒绝强杀。
    * 不删除任何用户数据；只覆盖自己的 ROOT.war 和日志。
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import zipfile
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
# T2 媒体目录隔离：测试实例上传落盘与 /upload 挂载的独立目录（与生产 stone 隔离）。
# 默认值需与 tools/media_paths.py 的 TEST_MEDIA_ROOT_DEFAULT、
# src/test/python/conftest.py 的 STONE_DIR 默认值三处同步修改。
#
# ⚠️ 清空白名单（安全关键，勿乱改）：本脚本的破坏性流程【只允许移动本常量指向的目录】
# （整目录移入回收站 test_trash，只移不删；不会删除任何文件）。
#  - TV_TEST_MEDIA_ROOT 覆盖值如与本常量不一致 → start 直接 exit 12 拒绝，绝不动其它目录；
#  - 想改换被移动的目录，唯一途径是把本常量改成那个目录（必须经用户明示后人工修改，勿作随手调整）；
#  - 即使改了本常量，生产媒体根（见 _media_dir_is_production）仍会被第二道门禁拦截。
DEFAULT_TEST_MEDIA_ROOT = Path(r"D:\data\projects\VideoPlatform\media-test")
TEST_MEDIA_ROOT = Path(_env("TV_TEST_MEDIA_ROOT", str(DEFAULT_TEST_MEDIA_ROOT)))
# 测试媒体回收站（T2 只移不删落点）：每次 fresh-start 把旧 media-test 整体移入此处
# （<media-test>-<时间戳>/），由用户手动删除以释放空间。默认与 media-test 同级；
# env TV_TEST_TRASH_ROOT 可覆盖，但落点会校验（不得命中生产根/与 src 重叠，否则 exit 12）。
TEST_TRASH_ROOT = Path(_env("TV_TEST_TRASH_ROOT", r"D:\data\projects\VideoPlatform\test_trash"))
MEDIA_DIRS = ("video", "image", "cover")  # 与 FileUploadService / MediaAuditService 一致
# 生产媒体根兜底（清空黑名单第一道门禁）：与 tools/media_paths.py 的 PROD_UPLOAD_ROOT_DEFAULT 一致
PROD_UPLOAD_ROOT_DEFAULT = Path("D:/data/projects/VideoPlatform/stone")

HTTP_PORT = 18080
SHUTDOWN_PORT = 18005
# 用 127.0.0.1 而不是 localhost：Tomcat 只绑定 IPv4，Python 连 localhost
# 会先尝试 IPv6 ::1 并等待超时（约 2 秒/次），导致整套测试变慢。
BASE_URL = f"http://127.0.0.1:{HTTP_PORT}"
START_TIMEOUT_SECONDS = 90
STOP_TIMEOUT_SECONDS = 25

# ---- 测试库连接（独立测试库 TVDatabase_test，docker mysql8.4:3307）----
TEST_DB_HOST = os.environ.get("TV_DB_HOST", "127.0.0.1")
TEST_DB_PORT = os.environ.get("TV_DB_PORT", "3307")
TEST_DB_USER = os.environ.get("TV_DB_USER", "root")
TEST_DB_PASSWORD = os.environ.get("TV_DB_PASSWORD", "ROOT123")
TEST_DB_NAME = os.environ.get("TV_DB_NAME", "TVDatabase_test")
TEST_DB_URL = os.environ.get(
    "TV_DB_URL",
    f"jdbc:mysql://{TEST_DB_HOST}:{TEST_DB_PORT}/{TEST_DB_NAME}"
    "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
)

# ---- 环境预检 / pytest 超时 ----
REDIS_PORT = 6379  # 业务 app.properties 固定 localhost:6379，无环境变量覆盖
EXIT_ENV_NOT_READY = 10   # 测试环境未就绪（3307/6379 探不通），秒级退出
EXIT_PYTEST_TIMEOUT = 11  # pytest 执行超时被杀
# 用户决策（2026-09-05）：pytest 阶段正常仅 ~3s，大头在编译/部署/关停（不在刹车范围），
# 60s 已是 ~20 倍裕量，足以切断卡死且几乎不误杀；TV_PYTEST_TIMEOUT 可覆盖（验证用短超时模拟挂起）
PYTEST_TIMEOUT_SECONDS = int(os.environ.get("TV_PYTEST_TIMEOUT", "60"))

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
    # 测试库连接覆盖（AppConfig 支持 db.* -> DB_* 环境变量）
    env["DB_URL"] = TEST_DB_URL
    env["DB_USERNAME"] = TEST_DB_USER
    env["DB_PASSWORD"] = TEST_DB_PASSWORD
    # T2 媒体目录隔离：upload.path -> UPLOAD_PATH 环境变量覆盖（AppConfig 统一机制），
    # 使测试实例上传落盘指向独立 media-test；与 WAR context.xml 的 /upload 挂载保持一致。
    env["UPLOAD_PATH"] = _norm_media_path(TEST_MEDIA_ROOT)
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


def precheck_env() -> None:
    """start 前纯 socket 探测测试库(3307)/Redis(6379)；不通即秒级快速失败（exit 10）。"""
    checks = [
        (TEST_DB_PORT, "MySQL 测试库(3307, TVDatabase_test)"),
        (str(REDIS_PORT), "Redis(6379)"),
    ]
    missing = [(port, name) for port, name in checks if not port_open(int(port))]
    if missing:
        for port, name in missing:
            log(f"测试环境未就绪: {name} 端口 {port} 不可达")
        log("提示: 请先启动 docker mysql-test / redis 后重试（预检不阻塞、不影响未知进程）")
        sys.exit(EXIT_ENV_NOT_READY)
    log("测试环境预检通过: MySQL(3307) / Redis(6379) 可达")


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

def _norm_media_path(path) -> str:
    """归一化为 AppShutDownListener.normalizePath 的比对口径：\\ -> /，去尾部 /。

    用于 UPLOAD_PATH 注入值、context.xml base 改写与回读校验三处保持一致。
    """
    text = str(path).replace("\\", "/")
    while text.endswith("/"):
        text = text[:-1]
    return text


def _production_media_roots() -> list:
    """生产媒体根集合（清空门禁用）：app.properties 的 upload.path + 默认生产 stone。"""
    roots = [PROD_UPLOAD_ROOT_DEFAULT.resolve()]
    props_path = PROJECT_ROOT / "src" / "main" / "resources" / "app.properties"
    try:
        for raw in props_path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if line.startswith("upload.path="):
                roots.append(Path(line.split("=", 1)[1].strip()).resolve())
    except OSError:
        pass
    return roots


def _media_dir_is_production(test_root: Path) -> bool:
    """TEST_MEDIA_ROOT 是否命中生产媒体根（相等或为其子目录）——门禁判定（纯函数）。"""
    test_root = test_root.resolve()
    for prod in _production_media_roots():
        if test_root == prod or prod in test_root.parents:
            return True
    return False


def _is_allowlisted_media_root(test_root: Path) -> bool:
    """清空白名单判定（纯函数）：路径（归一化后）必须等于硬编码白名单目录
    DEFAULT_TEST_MEDIA_ROOT；对不上即拒绝，没有任何确认/覆盖通道可以绕过
    （删除权仅存在于硬编码白名单）。

    os.path.normcase + resolve 消除盘符大小写/分隔符差异，防止同一目录因写法不同被放行。
    """
    return os.path.normcase(str(test_root.resolve())) == os.path.normcase(
        str(DEFAULT_TEST_MEDIA_ROOT.resolve())
    )


def _is_safe_trash_target(src: Path, dst: Path) -> bool:
    """回收站落点安全判定（纯函数）：dst 不得命中生产媒体根，不得与 src 相等/互为祖先
    后代（防把回收站移进生产目录 / 与测试目录嵌套递归：如回收站建在 media-test 内部，
    下次移动会把回收站一起移走并产生嵌套）。"""
    src, dst = src.resolve(), dst.resolve()
    for prod in _production_media_roots():
        if dst == prod or prod in dst.parents:
            return False
    if src == dst or src in dst.parents or dst in src.parents:
        return False
    return True


def _is_safe_catalina_base(base: Path, home: Path) -> bool:
    """CATALINA_BASE 与 CATALINA_HOME 重叠判定（纯函数）：相等或互为祖先 → 拒绝。

    prepare_base 会对 base 执行 mkdir / 改写 server.xml / copy2 覆盖 webapps/ROOT.war，
    若 base 与 home 重叠（例如 TV_CATALINA_BASE 被误配成 Tomcat 本体或 IDE 8080
    实例目录），上述写操作会直接覆盖 Tomcat 自身/生产部署产物，不可恢复
    （无回收站）。normcase + resolve 消除盘符大小写/分隔符差异。

    注意：home 本身也可被 TV_CATALINA_HOME 覆盖，"被期望的 Tomcat 本体"由用户
    配置决定；本门禁只防 base 与 home 重叠这一确定性误配，不防指向其它未知
    生产 Tomcat（无已知名单可查）。默认场景 base 与 home 完全分离，判定放行。
    """
    base_res, home_res = base.resolve(), home.resolve()
    return not (
        base_res == home_res
        or base_res in home_res.parents
        or home_res in base_res.parents
    )


def _evict_media_dir(src: Path, dst_root: Path) -> Path:
    """把整目录移动到回收站（只移不删，同盘即原子 rename；不删除任何文件）。

    返回回收目标路径；调用方负责先通过 _is_safe_trash_target 校验。
    健壮性：同名目标已存在时追加 -dupN 后缀（避免嵌套）；移动失败（权限/占用）时
    统一 exit 12 且源目录仍在原位；跨盘移动退化为拷贝+删除（较慢），执行前日志提示。
    """
    dst_root.mkdir(parents=True, exist_ok=True)
    if os.path.splitdrive(str(src))[0].lower() != os.path.splitdrive(str(dst_root))[0].lower():
        log(f"注意: 回收站与测试媒体目录不在同一磁盘，移动将退化为拷贝+删除（较慢）: {src} -> {dst_root}")
    stamp = time.strftime("%Y%m%d-%H%M%S-") + f"{time.time_ns() % 1_000_000:06d}"
    target = dst_root / f"{src.name}-{stamp}"
    dup = 0
    while target.exists():
        dup += 1
        target = dst_root / f"{src.name}-{stamp}-dup{dup}"
    try:
        shutil.move(str(src), str(target))
    except OSError as exc:
        log(f"测试媒体目录移动失败（源目录仍在原位，未删除任何文件）: {src} -> {target}: {exc}")
        sys.exit(12)
    return target


def init_test_media_dir() -> None:
    """fresh-start 语义下「移动式回收 + 重建」测试媒体目录（T2，像测试库 init）。

    仅在 prepare_base（已确认无运行中的本脚本实例，复用/外来进程路径不会走到）调用。
    行为：把旧 media-test 整目录移入回收站 test_trash（只移不删，用户可手动删除），
    再重建空目录 video/image/cover。
    门禁（防误移动设计，破坏权只信任硬编码白名单）：
      1) 白名单：源目录（归一化后）不等于 DEFAULT_TEST_MEDIA_ROOT（硬编码白名单）→
         拒绝并 exit 12（env 覆盖到任何其它目录都无法移动，也无人工确认通道）；
      2) 生产黑名单（保险）：源命中生产媒体根 → 拒绝并 exit 12；
      3) 回收站落点校验：test_trash 命中生产根或与源目录重叠/嵌套 → 拒绝并 exit 12。

    注意：要移动别的目录 = 人工把 DEFAULT_TEST_MEDIA_ROOT 改成目标目录；除此以外
    没有任何方法能让本流程作用于其它路径。本流程【永不删除】任何文件。
    """
    if not _is_allowlisted_media_root(TEST_MEDIA_ROOT):
        log(f"拒绝: 媒体目录 {TEST_MEDIA_ROOT} 不在白名单内"
            f"（唯一允许 {DEFAULT_TEST_MEDIA_ROOT}），为避免误移动其它目录已中止；"
            "如确需改换，必须人工修改 run_tests.py 的 DEFAULT_TEST_MEDIA_ROOT")
        sys.exit(12)
    if _media_dir_is_production(TEST_MEDIA_ROOT):
        log(f"拒绝启动: 媒体目录 {TEST_MEDIA_ROOT} 命中生产媒体根，为避免移动生产内容已中止")
        sys.exit(12)
    if TEST_MEDIA_ROOT.is_dir():
        if not _is_safe_trash_target(TEST_MEDIA_ROOT, TEST_TRASH_ROOT):
            log(f"拒绝启动: 测试媒体回收站落点不合法（命中生产根或与 media-test 重叠/嵌套）: {TEST_TRASH_ROOT}")
            sys.exit(12)
        evicted = _evict_media_dir(TEST_MEDIA_ROOT, TEST_TRASH_ROOT)
        log(f"测试媒体目录已回收（只移不删）: {evicted}；如需释放空间请手动删除该目录")
    for name in MEDIA_DIRS:
        (TEST_MEDIA_ROOT / name).mkdir(parents=True, exist_ok=True)


def patch_war_context(war: Path) -> None:
    """改写部署用 ROOT.war 内 META-INF/context.xml 的 PostResources base 为媒体测试目录。

    使 /upload 挂载与 UPLOAD_PATH 注入值同指 media-test（AppShutDownListener
    启动时会强校验两者一致，故必须同时改写；不改源码 context.xml，避免影响生产 8080）。
    只改写 <PostResources .../> 标签内的 base 属性（支持单引号/双引号），
    不触碰其它 XML 元素。
    """
    target = _norm_media_path(TEST_MEDIA_ROOT)
    tmp = war.with_name(war.name + ".patched")
    try:
        with zipfile.ZipFile(war, "r") as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == "META-INF/context.xml":
                    text = data.decode("utf-8")
                    text = re.sub(
                        r'(<PostResources\b[^>]*?\bbase=)(["\'])([^"\']*)\2',
                        lambda m: m.group(1) + m.group(2) + target + m.group(2),
                        text,
                        flags=re.S,
                    )
                    data = text.encode("utf-8")
                zout.writestr(item, data)
        os.replace(tmp, war)
    except (OSError, zipfile.BadZipFile, KeyError, UnicodeDecodeError) as exc:
        log(f"改写 ROOT.war 媒体挂载失败，拒绝启动: {exc}")
        sys.exit(9)
    finally:
        try:
            if tmp.exists():
                tmp.unlink()
        except OSError:
            pass


def verify_war_context(war: Path) -> bool:
    """回读校验（仿 server.xml 模式）：ROOT.war 内 context.xml 的 PostResources base
    必须全部等于媒体测试目录（不得残留生产 stone base），违规则拒绝启动。
    支持单引号/双引号属性写法。"""
    target = _norm_media_path(TEST_MEDIA_ROOT)
    try:
        with zipfile.ZipFile(war, "r") as zf:
            text = zf.read("META-INF/context.xml").decode("utf-8")
    except (KeyError, OSError, zipfile.BadZipFile):
        return False
    found = re.findall(r'<PostResources\b[^>]*?\bbase=(["\'])([^"\']*)\1', text, flags=re.S)
    bases = [value for _quote, value in found]
    return bool(bases) and all(b == target for b in bases)

def prepare_base() -> None:
    # 门禁：base 与 home 重叠（相等/互为祖先）→ 拒绝，防误配 TV_CATALINA_BASE
    # 覆盖 Tomcat 本体或生产部署产物（对比媒体目录的三重门禁；这里无回收站兜底）
    if not _is_safe_catalina_base(CATALINA_BASE, CATALINA_HOME):
        log(f"拒绝启动: CATALINA_BASE {CATALINA_BASE} 与 CATALINA_HOME {CATALINA_HOME} "
            "相等或互为祖先（疑似误配为 Tomcat 本体/实例目录）。为避免覆盖 Tomcat "
            "自身部署产物（server.xml / webapps/ROOT.war，无回收站、不可恢复）已中止；"
            "请修正 TV_CATALINA_BASE 后重试")
        sys.exit(12)
    CATALINA_BASE.mkdir(parents=True, exist_ok=True)
    for name in ("conf", "logs", "temp", "webapps", "work"):
        (CATALINA_BASE / name).mkdir(exist_ok=True)

    # T2 媒体隔离：fresh-start 移动式回收旧测试媒体 + 重建（只移不删，类似测试库 init 语义）
    init_test_media_dir()
    log(f"已就绪测试媒体目录: {TEST_MEDIA_ROOT}（fresh-start 移动式回收重建，只移不删）")

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

    root_war = CATALINA_BASE / "webapps" / "ROOT.war"
    shutil.copy2(WAR_FILE, root_war)
    # T2 媒体隔离：改写 ROOT.war 内 context.xml 的 /upload 挂载 base 并回读校验，
    # 使其与 UPLOAD_PATH 注入一致（AppShutDownListener 启动校验兜底）。
    patch_war_context(root_war)
    if not verify_war_context(root_war):
        log(f"ROOT.war 媒体挂载校验失败（context.xml base != {_norm_media_path(TEST_MEDIA_ROOT)}），"
            "拒绝启动（防止传 A 目录取 B 目录）")
        sys.exit(9)
    log("已部署 ROOT.war（/upload 挂载指向测试媒体目录）")


def _start_server():
    require(JAVA_HOME / "bin" / "java.exe", CATALINA_HOME / "bin" / "catalina.bat", WAR_FILE)

    precheck_env()

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

def _cleanup_test_db() -> None:
    """跑完测试后自动清理测试库数据库数据（media 文件由 cleanup_orphan_media.py 另行处理）。"""
    cleanup_script = PROJECT_ROOT / "tools" / "cleanup_data.py"
    if not cleanup_script.exists():
        log("警告: 缺少 tools/cleanup_data.py，跳过测试库清理")
        return
    env = os.environ.copy()
    env["DB_HOST"] = TEST_DB_HOST
    env["DB_PORT"] = TEST_DB_PORT
    env["DB_USER"] = TEST_DB_USER
    env["DB_PASSWORD"] = TEST_DB_PASSWORD
    env["DB_NAME"] = TEST_DB_NAME
    proc = subprocess.run(
        [sys.executable, str(cleanup_script), "--execute", "--no-backup"],
        cwd=str(PROJECT_ROOT),
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=300,
    )
    if proc.returncode != 0:
        log(f"警告: 测试库清理失败 exit={proc.returncode} (输出尾部见下)")
        print((proc.stderr or proc.stdout or "").splitlines()[-5:], file=sys.stderr)


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
    # T2 媒体隔离：测试侧文件落盘断言（STONE_DIR）指向独立媒体测试目录，兜底 env 缺失场景
    env["TV_STONE_DIR"] = _norm_media_path(TEST_MEDIA_ROOT)
    # 测试库连接参数透传给 pytest（admin.py / test_comment_delete.py 通过 DB_* 读取）
    env["DB_HOST"] = TEST_DB_HOST
    env["DB_PORT"] = TEST_DB_PORT
    env["DB_USER"] = TEST_DB_USER
    env["DB_PASSWORD"] = TEST_DB_PASSWORD
    env["DB_NAME"] = TEST_DB_NAME

    cmd = [sys.executable, "-m", "pytest", str(TEST_DIR), "-v", "--tb=short"]
    log(f"开始 pytest（超时上限 {PYTEST_TIMEOUT_SECONDS}s）...")
    try:
        result = subprocess.run(
            cmd, cwd=str(PROJECT_ROOT), env=env,
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=PYTEST_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as exc:
        log(f"pytest 超时（>{PYTEST_TIMEOUT_SECONDS}s）已强制终止")
        tail = (exc.stdout or "")[-4000:]
        if tail:
            log("pytest 输出尾部摘要（便于定位卡点）:")
            print(tail)
        _cleanup_test_db()
        return EXIT_PYTEST_TIMEOUT

    log(f"pytest 结束，exit={result.returncode}")
    if result.stdout:
        print(result.stdout)  # 保持输出可见性（run_tests_report 仍会整体捕获落盘）
    if result.stderr:
        print(result.stderr, file=sys.stderr)
    _cleanup_test_db()
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
