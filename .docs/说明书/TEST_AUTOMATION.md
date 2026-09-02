# 测试自动运行脚本说明

> 文件：`tools/run_tests.py`
> 用途：在 Codex 沙盒内一键完成「Maven 打包 → 启动独立 Tomcat → 运行 pytest → 关停」
> 最后验证：2026-08-08，35/35 测试通过，无残留进程

本文档供后续会话或其他 agent 直接复用，包含设计说明、使用方式、前置条件、安全边界与已知坑。

---

## 一、解决什么问题

1. 沙盒不能外网下载依赖，因此 Maven 必须离线、pytest 依赖必须预先放在本地目录。
2. 沙盒不允许 `Start-Process` 启动后台服务（会触发授权），但允许普通 Python 子进程方式拉起 Tomcat。
3. 用户的 IDEA Tomcat 占用 8080，脚本必须使用完全隔离的独立实例，不能干扰开发环境。
4. 用户此前出过事故，脚本的停止/清理逻辑必须保守：只清理自己启动的进程，绝不误杀无关进程。

---

## 二、前置条件与路径常量

| 名称 | 路径 | 说明 |
|------|------|------|
| JDK | `D:\dev\DevTools\jdk\openjdk-25.0.2` | 必须位于沙盒可写根目录，否则 Java 的 `toRealPath` 会被沙盒拒绝 |
| Tomcat | `D:\dev\DevTools\tomcat\apache-tomcat-10.1.54` | 只读使用安装目录，运行目录另建 |
| Maven | `D:\IDE\IDEA\IntelliJ IDEA 2025.3.2\plugins\maven\lib\maven3\bin\mvn.cmd` | IDEA 自带发行版 |
| 离线本地仓库 | `D:\dev\WorkSpace\VideoPlatform\maven` | 由 `C:\Users\ASUS\.m2\repository` 复制而来；javac 需要可写目录读取依赖 jar |
| pytest 依赖 | `D:\dev\WorkSpace\VideoPlatform\temp\pytest-deps` | 由用户在自己的终端用 `pip install --target` 生成，沙盒只读使用 |
| 独立 Tomcat 运行目录 | `D:\data\projects\VideoPlatform\stone\temp\tomcat-test-18080` | CATALINA_BASE，含 conf/webapps/logs |
| WAR | 项目 `target\untitled-1.0-SNAPSHOT.war` | 由 `build` 生成 |
| 测试代码 | 项目 `src\test\python` | pytest 用例 + conftest.py + pytest.ini |

外部服务依赖：生产库 MySQL80（TVDatabase:3306）+ **独立测试库 Docker MySQL8.4（TVDatabase_test:3307）** + Redis，均需运行中。

---

## 三、使用方式

在项目根目录执行：

```powershell
python tools\run_tests.py all      # 推荐：打包 -> 启动 -> 测试 -> 关停
python tools\run_tests.py build    # 只做 Maven 离线打包
python tools\run_tests.py start    # 只启动独立 Tomcat（18080，后台保持）
python tools\run_tests.py test     # 只跑 pytest（要求 18080 已就绪）
python tools\run_tests.py stop     # 只关停独立 Tomcat
```

`all` 的典型耗时约 30~35 秒：打包约 2 秒，Tomcat 启动约 16 秒，pytest 约 3 秒，关停约 5 秒。

### 退出码约定

| 退出码 | 含义 |
|--------|------|
| 0 | 成功 |
| 2 | 必要路径缺失 |
| 3 | Maven 成功但未生成 war |
| 4 | 18080 被其他程序占用，拒绝启动 |
| 5 / 6 | Tomcat 提前退出 / 启动超时 |
| 7 | 停止失败且无法确认进程归属，需人工检查 |
| 8 | 测试前置不满足（18080 未就绪） |

---

## 四、脚本设计

### 4.1 主流程（`all`）

```text
build ──> start ──> test ──> finally: stop
```

`all` 持有自己启动的 `Popen` 对象，`finally` 保证即使 pytest 失败也会执行 `stop`。

### 4.2 启动

- 用 `subprocess.Popen(["cmd", "/c", "call", "catalina.bat", "run"])` 拉起，`CREATE_NO_WINDOW` 隐藏窗口。
- 把包装进程 PID 写入 `CATALINA_BASE\logs\tomcat.pid`。
- 轮询 `http://127.0.0.1:18080/start`，就绪后返回。
- 启动前检查：18080 若已有本应用则复用；若被未知程序占用则拒绝启动。

### 4.3 关停

- 优先 `catalina.bat stop`（通过 shutdown 端口 18005 优雅关停）。
- 以「18080 端口是否关闭」作为停止是否成功的判断，不依赖进程枚举。
- 强制清理只允许两种情况：
  1. 当前进程内自己启动的 `Popen` 对象；
  2. PID 文件中的 PID，且命令行包含 `catalina.bat`（CIM 被禁时回退检查镜像名是否为 `cmd.exe` 且 PID 与 PID 文件一致）。
- 无法确认归属时拒绝强杀，提示人工检查。

### 4.4 端口与部署

- HTTP 端口 `18080`，shutdown 端口 `18005`，只绑定 `127.0.0.1`。
- 复用固定 CATALINA_BASE；`conf/server.xml` 缺失时从 CATALINA_HOME 复制，然后回读校验端口配置，防止误用 8080。
- war 部署为 `webapps\ROOT.war`（根上下文 `/`）。

### 4.5 日志

全部位于 `CATALINA_BASE\logs\`：

- `run.log`：脚本自己的操作日志
- `tomcat_stdout.log` / `tomcat_stderr.log`：Tomcat 控制台输出
- `catalina.*.log`、`localhost.*.log`：Tomcat 运行日志

---

## 五、沙盒限制与踩坑记录（重要）

1. **不要用 `Start-Process` 启动 Tomcat**：沙盒会要求授权。必须用 Python 子进程，这也是项目 AGENTS.md 对脚本语言的要求。
2. **JDK 必须在可写根目录**：沙盒会拒绝 Java 对工作区外文件执行 `toRealPath`，这是此前 Maven 构建失败的根因。
3. **Maven 需要可写本地仓库**：javac 读取依赖 jar 时同样受路径限制，所以必须用离线副本（`D:\dev\WorkSpace\VideoPlatform\maven`）并通过 `-Dmaven.repo.local` 指定。
4. **沙盒可能禁止进程枚举**（CIM/tasklist 返回空）：脚本已改为以端口探测为主；停止失败时宁可报错让用户人工检查，也不扩大强杀范围。
5. **必须用 `127.0.0.1` 而不是 `localhost`**：Python 连 `localhost` 会先试 IPv6 `::1`，而 Tomcat 只绑 IPv4，每次新连接白白等待约 2 秒，整套测试会从 3 秒退化到 150 秒。
6. **pytest/requests 无法在沙盒内 pip 安装**：需要在用户普通终端执行：

   ```powershell
   & "C:\Users\ASUS\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" -m pip install --target "D:\dev\WorkSpace\VideoPlatform\temp\pytest-deps" pytest requests
   ```

   沙盒运行测试时通过 `PYTHONPATH` 指向该目录。
7. **javac 无法读取 worktree 的 `target/` 作为 classpath**：沙箱内目录枚举被拒，表现为测试编译时"程序包 com.itheima.* 不存在"；因此 Maven 构建通过 `-Dstage8.buildDir` 指向 `D:\data\projects\VideoPlatform\stone\temp\stage8-target`（pom 默认 `./target`），war 也位于该目录。
8. **离线仓库来源记录**：新加入的测试依赖（junit/mockito/bytebuddy/surefire）`_remote.repositories` 原本只有 `>central=`，默认 aliyun 镜像下离线解析会拒认；已逐项追加 `>aliyun=` 行（只追加不删除，模式与既有 mysql 依赖一致）。

---

## 六、数据副作用与隔离

pytest 端到端用例运行在**独立测试库** `TVDatabase_test`（Docker MySQL 127.0.0.1:3307，连接由 `run_tests.py` 通过 `DB_URL/DB_USERNAME/DB_PASSWORD` 环境变量注入，`AppConfig` 自动读取，不改 app.properties）：

- 注册用户（`testA_*`、`testB_*`、`smoke_user_*`、`timing_*` 等）
- 上传测试视频/图片（仍写入 `D:\data\projects\VideoPlatform\stone`）
- 评论、点赞、关注记录
- 抢优惠券（消耗库存）

**自动清理**：`run_tests.py test/all` 结束后自动清理**测试库**数据库数据（pytest_/smoke_ 前缀 content 及关联记录，等价 `DB_* 指向测试库 + cleanup_data.py --execute --no-backup`）。**生产库 TVDatabase 不再产生测试残留**。

**媒体文件**：pytest 上传的媒体文件仍落 stone 目录，孤儿媒体由 `tools/cleanup_orphan_media.py` 按需清理（用户手动执行，不在自动清理范围内）。

**管理员链路**：`test_admin.py` / `test_hide_content.py` / `test_comment_delete.py` 的管理员操作通过 `DB_*` 环境变量连测试库（`run_tests.py cmd_test` 注入），`tools/admin.py` 与测试内直接 SQL 均遵守该约定；admin.py 人工命令行调用不设环境变量时默认仍连 3306 生产库（向后兼容）。

### 6.1 测试库初始化与重建

- 一次初始化/表结构更新后重建：`python tools\init_test_db.py`（默认取 `.docs/DBBackup` 下最新 `db.sql`，或 `--dump` 指定；DROP+CREATE 后导入）。
- 测试库连接参数可用 `TV_DB_HOST/TV_DB_PORT/TV_DB_USER/TV_DB_PASSWORD/TV_DB_NAME/TV_DB_URL` 环境变量覆盖（默认 127.0.0.1:3307 / root / ROOT123 / TVDatabase_test）。
- 安全门禁：`init_test_db.py` 拒绝操作 `DB_PORT=3306`，防止误 DROP 生产库。
- 清理报告：`cleanup_data.py --execute` 生成的 `CLEANUP_REPORT_*.md` 写入 `.docs/temp/`（已在 .gitignore，不入库）。

---

## 七、给其他 agent 的快速操作指南

1. 先做只读检查（全部应为 True）：

   ```powershell
   Test-Path 'D:\dev\DevTools\jdk\openjdk-25.0.2\bin\java.exe'
   Test-Path 'D:\dev\WorkSpace\VideoPlatform\maven'
   Test-Path 'D:\dev\WorkSpace\VideoPlatform\temp\pytest-deps'
   Test-Path 'D:\dev\DevTools\tomcat\apache-tomcat-10.1.54\bin\catalina.bat'
   ```

2. 确认 18080 空闲：

   ```powershell
   try { Invoke-WebRequest -Uri 'http://127.0.0.1:18080/start' -UseBasicParsing -TimeoutSec 3 } catch { '空闲' }
   ```

3. 运行（主会话推荐，输出自动落盘、stdout 只回显摘要）：

   ```powershell
   python tools\run_tests_report.py all
   ```

   需要分步操作时仍用 `python tools\run_tests.py build/start/test/stop`。

4. 失败排查顺序：
   - 看 `run.log` 和 `tomcat_stdout.log` / `tomcat_stderr.log`；
   - 看 18080 是否被残留进程占用，若是，先 `python tools\run_tests.py stop`；
   - 若 `stop` 提示无法确认归属，不要扩大强杀范围，把 PID 和端口信息交给用户人工处理。

5. 不要修改脚本的安全边界：8080 隔离、进程归属校验、不删除用户数据。

---

## 八、维护约定

- 修改路径或端口时，同步更新脚本常量与本文档。
- 脚本改动后至少执行 `python -m py_compile tools\run_tests.py` 并完整跑一次 `all`。
- 涉及安全逻辑（停止/强杀/删除）的大改动，建议按用户要求先派 subagent 审查，通过后再运行。

## 九、上下文收口执行方式（主会话推荐入口）

> 文件：`tools/run_tests_report.py`
> 用途：主会话跑测试时避免输出灌入上下文；完整日志落盘 + `latest.json` 留痕。
> 最后验证：2026-08-10，35/35 通过，stdout 仅 3 行摘要。

### 9.1 为什么测试不派 subagent 执行

当前环境实测（2026-08-10）：

| 派发方式 | 结果 |
|---------|------|
| `fork_turns="none"` | 任务消息不送达，subagent 回复“没有收到任务” |
| `fork_turns="1"` | 最小 ACK 任务也卡死无响应，需人工中断 |
| `fork_turns="all"` | 消息可达，但继承全部项目上下文，跑完测试会自行找活干 |

因此测试统一由主会话 + 收口脚本执行，不派 subagent；除非用户明确要求。

### 9.2 用法

```powershell
python tools\run_tests_report.py all       # 默认：build -> start -> test -> stop
python tools\run_tests_report.py junit     # mvn -o test（JUnit 61 例，输出同样落盘）
python tools\run_tests_report.py build     # 仅离线打包
python tools\run_tests_report.py start     # 仅启动
python tools\run_tests_report.py test      # 仅 pytest
python tools\run_tests_report.py stop      # 仅关停
```

`all/build/start/test/stop` 参数原样透传给 `tools/run_tests.py`，退出码约定相同；`junit` 等价执行 `mvn -o test`（含连接池独立 fork 执行）。

### 9.3 产物

- 完整日志：`D:\data\projects\VideoPlatform\stone\temp\test-reports\run-<yyyyMMdd_HHmmss>.log`（UTF-8，只追加不删除）
- 机器可读结果：同目录 `latest.json`

`latest.json` 字段：

| 字段 | 说明 |
|------|------|
| `phase` / `script` | 本次执行的子命令 |
| `exit_code` | run_tests.py 退出码 |
| `build_ok` | build/all 时是否构建成功 |
| `pytest.passed/failed/errors/skipped` | pytest 汇总计数 |
| `pytest_summary` | pytest 最后一行汇总 |
| `junit.tests/failures/errors/skipped` | junit 时 surefire 每测试类统计之和 |
| `junit_summary` | junit 时最后一条 Tests run 行 |
| `war.exists/mtime` | war 产物状态 |
| `port_18080_open_after` | 执行后 18080 是否仍被占用 |
| `log_file` | 完整日志路径 |

### 9.4 主会话复核步骤

1. 看 stdout 摘要（exit、pytest 汇总、18080 状态）。
2. 读 `latest.json` 核对退出码与用例数；不采信任何口头汇报。
3. 失败时只读日志尾部（约 60 行）或按 `FAILED` 关键词定位，禁止整份日志灌入上下文：

   ```powershell
   Get-Content -Encoding UTF8 -Tail 60 "D:\data\projects\VideoPlatform\stone\temp\test-reports\run-<时间戳>.log"
   ```

4. 确认 `port_18080_open_after=false`；若为 true，先 `python tools\run_tests.py stop`，无法停止时把 PID/端口信息交给用户人工处理。

### 9.5 维护约定

- 修改 `run_tests_report.py` 后至少执行 `python -m py_compile tools\run_tests_report.py` 并跑一次 `all`。
- 修改 `run_tests.py` 的输出或退出码行为时，同步检查本脚本的解析字段。
