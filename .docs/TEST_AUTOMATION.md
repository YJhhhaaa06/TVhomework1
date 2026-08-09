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

外部服务依赖：MySQL80（TVDatabase）和 Redis 必须运行中。

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

---

## 六、数据副作用与清理

每次运行都会真实写入数据：

- 注册用户（`testA_*`、`testB_*`、`smoke_user_*`、`timing_*` 等）
- 上传测试视频/图片（写入 `D:\data\projects\VideoPlatform\stone`）
- 评论、点赞、关注记录
- 抢优惠券（消耗库存）

清理测试数据的 SQL 见 `TEST_GUIDE.md` 6.2 节。若重构期间频繁跑测试，建议后续建立独立测试库，避免污染开发数据。

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

3. 运行：

   ```powershell
   python tools\run_tests.py all
   ```

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
