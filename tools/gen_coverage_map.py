# -*- coding: utf-8 -*-
"""
T4 覆盖率地图生成脚本（长期工具，静态对照 + jacoco 数据合并）

产出: .docs/报告/覆盖率地图.md（生成物，已 .gitignore 不入库）
   1) controller 端点清单（@WebServlet + doXxx 分派字面量，启发式解析）
   2) AuthFilter 守卫分类（public / login / admin）
   3) pytest 用例 URL 静态对照（端点有无 pytest 用例）
   4) jacoco JUnit 覆盖（service/controller 类级 LINE%）
   5) "改 X 需补哪些测试" 服务-端点 反向映射
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = r"d:\javaproject\VideoPlatform\TVhomework1"
CTRL_DIR = os.path.join(ROOT, "src", "main", "java", "com", "itheima", "controller")
FILTER_FILE = os.path.join(ROOT, "src", "main", "java", "com", "itheima", "filter", "AuthFilter.java")
TEST_DIR = os.path.join(ROOT, "src", "test", "python")
JACOCO_XML = os.path.join(ROOT, "target", "site", "jacoco", "jacoco.xml")
# 自动化链路（run_tests / codex）打包时 jacoco 落 stage8-target（TV_STAGE8_TARGET），
# IDEA mvn test 落 ./target —— 取两者中较新的一份，保证 JUnit 数字跟随最近一次构建
STAGE8_JACOCO_XML = os.path.join(
    os.environ.get("TV_STAGE8_TARGET", r"D:\data\projects\VideoPlatform\stone\temp\stage8-target"),
    "site", "jacoco", "jacoco.xml",
)
OUT_MD = os.path.join(ROOT, ".docs", "报告", "覆盖率地图.md")

SKIP_CTRL = {"BaseServlet", "BaseServletUtil", "RequestParser", "UploadType", "AppShutDownListener"}


def scan_controllers():
    """返回 [(ctrl, verb, full_path, raw_action)]"""
    endpoints = []
    for f in glob.glob(os.path.join(CTRL_DIR, "*.java")):
        ctrl = os.path.basename(f)[:-5]
        if ctrl in SKIP_CTRL:
            continue
        text = open(f, encoding="utf-8").read()
        m = re.search(r'@WebServlet\("([^"]+)"\)', text)
        if not m:
            continue
        base = m.group(1)
        cur_verb = None
        handlers = []
        for line in text.splitlines():
            mm = re.match(r'\s*protected void do(Get|Post|Put|Delete)\(', line)
            if mm:
                cur_verb = mm.group(1).upper()
                handlers.append(cur_verb)
                continue
            if cur_verb is None:
                continue
            for am in re.finditer(r'"(/[^"]*)"\s*\.equals\(|case[^"\n]*"(/[^"]*)"', line):
                action = (am.group(1) or am.group(2)).rstrip("/")
                b = base[:-2] if base.endswith("/*") else base
                b = b.rstrip("/")
                full = action if action == b else b + action
                endpoints.append((ctrl, cur_verb, full, action))
        # 直挂 servlet（无分派字面量的 handler）：端点为映射本身
        for verb in handlers:
            if not any(e[0] == ctrl and e[1] == verb and e[2] for e in endpoints):
                if not base.endswith("/*"):
                    b = base.rstrip("/")
                    endpoints.append((ctrl, verb, b, b))
    return endpoints


def guards():
    f = open(FILTER_FILE, encoding="utf-8").read()
    p_block = re.search(r'PROTECTED_PREFIXES\s*=\s*Set\.of\((.*?)\);', f, re.S)
    e_block = re.search(r'PROTECTED_EXACT\s*=\s*Set\.of\((.*?)\);', f, re.S)
    prefixes = re.findall(r'"([^"]+)"', p_block.group(1))
    exacts = re.findall(r'"([^"]+)"', e_block.group(1))

    def classify(path):
        if path.startswith("/api/admin"):
            return "admin"
        # 与 AuthFilter 语义对齐：前缀型守卫是 startsWith（无边界），精确型是 equals
        for p in prefixes:
            if path.startswith(p):
                return "login"
        if path in exacts:
            return "login"
        return "public"

    return prefixes, exacts, classify


def extract_path(s):
    """从 pytest url 字符串里掰出路径骨架（去掉 f-string 前缀/查询串）。"""
    if s.startswith("{"):  # 前缀是 f-string 变量（如 {base_url}），只切第一个 }，避免 query 内联变量被误切
        s = s[s.find("}") + 1:]
    elif "http://" in s.lower() or ":18080" in s:
        s = s.split(":18080", 1)[-1]
    s = s.split("?", 1)[0].strip()
    if not s.startswith("/"):
        return None
    if "{" in s:  # 动态段（本项目少见，query 传参为主）
        s = re.sub(r"\{[^}]*\}", "*", s)
    return s


REQ_RE = re.compile(r"requests\.(get|post|put|delete|patch|request)\s*\(")


def scan_pytest():
    """只统计真实 HTTP 调用（requests.xxx 调用窗口内的首个路径字面量），排除注释/docstring/判断串。

    返回 [(file, call_line, path)]
    """
    used = []
    for f in glob.glob(os.path.join(TEST_DIR, "*.py")):
        name = os.path.basename(f)
        lines = open(f, encoding="utf-8").read().splitlines()
        n = len(lines)
        i = 0
        while i < n:
            if not REQ_RE.search(lines[i]):
                i += 1
                continue
            for j in range(i, min(i + 4, n)):  # url 通常紧跟调用行（多为下一行）
                for m in re.finditer(r'f?["\']([^"\']+)["\']', lines[j]):
                    p = extract_path(m.group(1))
                    if p:
                        used.append((name, i + 1, p))
                        break
                else:
                    continue
                break
            i += 1
    return used


def load_jacoco_xml(path):
    """解析 jacoco.xml，失败（构建中断/写一半）返回 None"""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return None
    if not root.findall("package"):
        return None
    return root


def pick_jacoco_xml():
    """从 stage8-target 与 ./target 各取可解析的 jacoco.xml，选较新者；都不可用返回 None"""
    cands = []
    for p in (STAGE8_JACOCO_XML, JACOCO_XML):
        root = load_jacoco_xml(p) if os.path.exists(p) else None
        if root is None:
            if os.path.exists(p):
                print(f"[warn] jacoco.xml 无效或未完成，跳过：{p}", file=sys.stderr)
            continue
        cands.append((os.path.getmtime(p), root))
    if not cands:
        return None, None
    cands.sort()
    return cands[-1][1]


def jacoco_classes():
    root = pick_jacoco_xml()
    if root is None:
        return {}
    res = {}
    for pkg in root.findall("package"):
        for cls in pkg.findall("class"):
            cnt = {}
            for c in cls.findall("counter"):
                cnt[c.get("type")] = (int(c.get("missed")), int(c.get("covered")))
            lm, lc = cnt.get("LINE", (0, 0))
            res[cls.get("name")] = round(100 * lc / (lc + lm), 1) if lc + lm else None
    # 报告根部整体 LINE 覆盖
    root_cnt = {}
    for c in root.findall("counter"):
        root_cnt[c.get("type")] = (int(c.get("missed")), int(c.get("covered")))
    lm, lc = root_cnt.get("LINE", (0, 0))
    res["__TOTAL_LINE__"] = round(100 * lc / (lc + lm), 1) if lc + lm else None
    return res


def junit_test_map():
    """service 类名 -> 引用了它的 JUnit 测试文件名列表"""
    inv = defaultdict(list)
    for f in glob.glob(os.path.join(ROOT, "src", "test", "java", "**", "*.java"), recursive=True):
        name = os.path.basename(f)
        if not name.endswith("Test.java"):
            continue
        text = open(f, encoding="utf-8").read()
        # 去掉注释，避免 "提到但未测" 的误匹配
        text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
        for svc in glob.glob(os.path.join(ROOT, "src", "main", "java", "com", "itheima", "service", "*.java")):
            sname = os.path.basename(svc)[:-5]
            if re.search(r"\b" + re.escape(sname) + r"\b", text):
                inv[sname].append(name[:-5])
    return {k: sorted(v) for k, v in inv.items()}


def controller_service_map():
    """service 类名 -> 引用它的 controller 名列表（按 @Inject Service 字段）"""
    inv = defaultdict(set)
    for f in glob.glob(os.path.join(CTRL_DIR, "*.java")):
        ctrl = os.path.basename(f)[:-5]
        if ctrl in SKIP_CTRL:
            continue
        for fm in re.finditer(r'@Inject\s+private\s+(\w+Service|\w+Manager\w*)\s+\w+;',
                              open(f, encoding="utf-8").read()):
            inv[fm.group(1)].add(ctrl)
    return inv


def main():
    endpoints = scan_controllers()
    prefixes, exacts, classify = guards()
    used = scan_pytest()
    jcov = jacoco_classes()
    cs_map = controller_service_map()
    jt_map = junit_test_map()

    # 端点 → pytest 命中
    hit = defaultdict(list)
    for ctrl, verb, path, _ in endpoints:
        for fname, ln, p in used:
            if p == path or p.startswith(path + "/") or p.startswith(path + "?"):
                hit[(ctrl, verb, path)].append((fname, ln))

    rows = []
    for ctrl, verb, path, _ in endpoints:
        g = classify(path)
        hs = hit.get((ctrl, verb, path), [])
        rows.append({
            "ctrl": ctrl, "verb": verb, "path": path, "guard": g,
            "pytest": hs, "covered": bool(hs),
        })

    total = len(rows)
    covered = sum(1 for r in rows if r["covered"])
    uncovered = [r for r in rows if not r["covered"]]
    # 预期公开的写端点（登录/注册必然公开，不算缺口）
    EXPECTED_PUBLIC_POST = {"/user/login", "/user/register"}
    public_post = [r for r in rows
                   if r["guard"] == "public" and r["verb"] == "POST"
                   and r["path"] not in EXPECTED_PUBLIC_POST]

    # filter 死规则：守卫精确路径无对应端点
    all_paths = {r["path"] for r in rows}
    dead_exact = [e for e in exacts if e not in all_paths]

    # 组装 md
    lines = []
    A = lines.append
    A("# 覆盖率地图（T4，本机生成）")
    A("")
    A("> 生成方式：静态分析（controller 路由 × pytest URL 对照）+ jacoco JUnit 数据。")
    A("> 生成脚本：`tools/gen_coverage_map.py`（报告为生成物，不入库；rerun 即刷新）。")
    A("")
    A("## 一、总体视图")
    A("")
    A(f"- 端点总数：**{total}**；有 pytest 用例：**{covered}**；无用例：**{len(uncovered)}**")
    A(f"- JUnit 总体 LINE 覆盖（jacoco，`mvn test` 产物）：**{jcov.get('__TOTAL_LINE__')}%**")
    A(f"- 守卫分布：public {sum(1 for r in rows if r['guard']=='public')} / login {sum(1 for r in rows if r['guard']=='login')} / admin {sum(1 for r in rows if r['guard']=='admin')}")
    A(f"- 疑似缺守卫：见四（目前无）"+("" if not public_post else "（" + str(len(public_post)) + " 个）"))
    A(f"- AuthFilter 死规则（守卫精确路径无对应端点）：{'存在，见四' if dead_exact else '无'}")
    A("")
    A("> 说明：jacoco 数据仅来自 JUnit（service 层单测基准），取 `run_tests` 的 stage8-target 与 IDEA `./target` ")
    A("> 两份 jacoco.xml 中较新者（JUnit 数字时效=最近一次 mvn test 构建）；servlet（controller）需 Web 容器加载，JUnit 不触达，")
    A("> 故 controller 类 LINE 0% 属预期——端到端（HTTP 入口）覆盖由 pytest 承担，本报告对 pytest 侧采用")
    A("> 路由静态对照（哪些端点有/无用例），不体现分支深度。")
    A("")
    A("## 二、端点 × 守卫 × pytest 对照")
    A("")
    A("| Controller | 方法 | 端点 | 守卫 | pytest 用例 |")
    A("|---|---|---|---|---|")
    for r in sorted(rows, key=lambda x: (x["ctrl"], x["path"])):
        marks = ", ".join(f"{f}:{ln}" for f, ln in r["pytest"]) if r["pytest"] else "**无**"
        A(f"| {r['ctrl']} | {r['verb']} | {r['path']} | {r['guard']} | {marks} |")
    A("")
    A("## 三、无 pytest 用例的端点（需补测试）")
    A("")
    A("| Controller | 方法 | 端点 | 守卫 |")
    A("|---|---|---|---|")
    for r in sorted(uncovered, key=lambda x: (x["ctrl"], x["path"])):
        A(f"| {r['ctrl']} | {r['verb']} | {r['path']} | {r['guard']} |")
    if not uncovered:
        A("（无）")
    A("")
    A("## 四、守卫缺口与守卫死规则")
    A("")
    if public_post:
        A("**公开写操作端点（POST、无登录守卫、非预期公开）**：")
        A("")
        for r in public_post:
            A(f"- `{r['verb']} {r['path']}`（{r['ctrl']}）")
        A("")
    else:
        A("- 公开写操作端点：无（`/user/login`、`/user/register` 为预期公开，不计缺口）")
        A("")
    if dead_exact:
        A("**AuthFilter 守卫规则中含、但路由清单中不存在的端点（死规则）**：")
        A("")
        for e in dead_exact:
            A(f"- `{e}`")
    else:
        A("- 守卫精确路径死规则：无")
    A("")
    A("## 五、JUnit 类级覆盖（jacoco LINE%）")
    A("")
    A("| 类 | LINE% |")
    A("|---|---|")
    for cls in sorted(jcov, key=lambda c: (jcov[c] is None, jcov[c] if jcov[c] is not None else 0)):
        if cls.startswith("com/itheima/service") or cls.startswith("com/itheima/controller"):
            short = cls.replace("com/itheima/", "")
            A(f"| {short} | {jcov[cls] if jcov[cls] is not None else '-'} |")
    A("")
    A("## 六、改 X 需补哪些测试（服务 → 相关端点）")
    A("")
    A("> 用法：改某个服务时，改完它读这一行——本服务 JUnit 单测在哪个文件、多少行覆盖；")
    A("> 端到端要看哪些端点已有 pytest、哪些还是缺口（缺 pytest 列）。")
    A("")
    A("| 服务类 | JUnit LINE% | 对应 JUnit 测试 | 相关端点（controller 引用该服务） | 缺 pytest 的相关端点 |")
    A("|---|---|---|---|---|")
    for svc in sorted(cs_map):
        endpoints_of = [r for r in rows if r["ctrl"] in cs_map[svc]]
        ep_str = "; ".join(sorted({f"{r['verb']} {r['path']}" for r in endpoints_of})) or "-"
        missing = "; ".join(sorted({r["path"] for r in endpoints_of if not r["covered"]})) or "-"
        tstr = ", ".join(jt_map.get(svc, [])) or "-"
        A(f"| {svc} | {jcov.get('com/itheima/service/' + svc, '-')} | {tstr} | {ep_str} | {missing} |")

    os.makedirs(os.path.dirname(OUT_MD), exist_ok=True)
    open(OUT_MD, "w", encoding="utf-8").write("\n".join(lines))

    # stdout 摘要
    print(f"端点总数 {total}，有 pytest {covered}，无用例 {len(uncovered)}")
    print(f"public {public_post} 条写公开端点 | 守卫死规则 {dead_exact}")
    print(f"报告 => {OUT_MD}")


if __name__ == "__main__":
    main()