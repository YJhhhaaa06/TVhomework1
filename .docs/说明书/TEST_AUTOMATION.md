# 测试自动运行脚本说明

> 文件：`tools/run_tests.py`
> 用途：在 Codex 沙盒内一键完成「Maven 打包 → 启动独立 Tomcat → 运行 pytest → 关停」
> 最后验证：2026-08-08，35/35 测试通过，无残留进程

本文档供后续会话或其他 agent 直接复用，包含设计说明、使用方式、前置条件、安全边界与已知坑。

> **跑测试的唯一权威入口**：T1 文档收口后，旧版 TEST\_GUIDE / ACCEPTANCE\_CRITERIA 已归档至 `.docs/archive/说明书/`（勿读），所有"怎么跑测试"的问题以本文档为准。

***

## 概念速览 / 快速上手（2026-09-05，T1 文档收口）

### 概念速览

| 项    | 内容                                                                                                                  |
| ---- | ------------------------------------------------------------------------------------------------------------------- |
| 两层测试 | JUnit（`src/test/java`）= 服务层单元基准（mock，不碰 DB/HTTP）；pytest（`src/test/python`）= 端到端验收（真实 MySQL/Redis/Tomcat）。归属判据见 §〇.2 |
| 测试环境 | 独立 Tomcat **18080**（与 IDEA 8080 隔离，端口不可覆盖）+ 独立测试库 Docker MySQL `TVDatabase_test`（127.0.0.1:3307）+ Redis（6379）+ RabbitMQ（5672，T16 起纳入预检；容器名 `rabbitmq`）       |
| 数据隔离 | pytest 用例全部落在测试库 3307，生产库 TVDatabase 不再产生测试残留（见 §六）；媒体落独立测试目录 media-test（T2，与生产 stone 隔离），旧测试媒体只移不删地回收至 test_trash       |

### 快速上手

```powershell
python tools\run_tests_report.py all       # 全量端到端：build -> start -> test -> stop（输出自动落盘）
python tools\run_tests_report.py junit     # 全量单元：mvn -o test
```

> junit 链（`mvn -o test`）产出变化（T4，2026-09-06）：pom 已挂 jacoco-maven-plugin 0.8.15（prepare-agent + report），
> 每次 junit 运行会额外生成 `jacoco.exec` 与 `site/jacoco/`（覆盖率报告，供 `tools\gen_coverage_map.py` 引用，
> 落 stage8-target 或 ./target 取较新）；
> 离线副本 `D:\dev\WorkSpace\VideoPlatform\maven` 已含 jacoco 及其 ASM/插件依赖，离线解析不失败；不影响通过与否。
> 覆盖率地图（`.docs/报告/覆盖率地图.md`）为生成物、不入库，需要时 rerun `python tools\gen_coverage_map.py`。

单步操作（分步调试用）：

```powershell
python tools\run_tests.py build    # 只做 Maven 离线打包
python tools\run_tests.py start    # 只启动独立 Tomcat（18080，后台保持）
python tools\run_tests.py test     # 只跑 pytest（要求 18080 已就绪）
python tools\run_tests.py stop     # 只关停独立 Tomcat
```

运维脚本统一入口（T6，2026-09-05，最推荐）：

```powershell
python tools\tv.py                               # 帮助 + 当前环境（tools/env/active.conf）
python tools\tv.py env test|prod                 # 持久切换环境（复制覆盖 active.conf）
python tools\tv.py --env test|prod <子命令>      # 本次命令临时用指定环境（不写盘）
python tools\tv.py admin|cleanup|integrity|backup|init-test-db|test|cleanup-orphan-media|log-report [参数...]
```

手动运维脚本（admin / cleanup / integrity / backup）默认连接**当前激活环境**（默认 test 测试库 3307），不再默认生产库；生产操作需先 `tv.py env prod`（持久）或 `--env prod`（临时）并在写操作时二次确认。测试库口令在 `tools/env/test.conf`（追踪）；生产口令放 `tools/env/prod.conf`（.gitignore 排除，模板 `prod.conf.example`）。`init-test-db` 与 `test` 固定测试库、拒绝 prod 语境；`cleanup-orphan-media` 仅允许 prod 语境（T2 起测试/生产媒体目录已隔离，本工具为保护真实内容的回收工具）。

前置检查与失败排查见 §七；主会话收口执行方式（日志落盘、latest.json 复核）见 §九。

> **手动跑 pytest 的注意**（T1 收尾，2026-09-05）：conftest 默认 `BASE_URL` 已指向 `http://127.0.0.1:18080`（测试实例），不再默认 8080 生产实例；不经 run\_tests 直接手动 `pytest` 前，需先 `python tools\run_tests.py start` 拉起 18080 实例，否则连接会被拒绝（快速失败，不会误连生产）。T2 起 `STONE_DIR` 默认已指向独立测试媒体目录 `media-test`（不经 run_tests 手动跑时文件断言亦不会指向生产）。

### 章节目录（导航）

| 章节   | 内容                                    |
| ---- | ------------------------------------- |
| 〇    | 测试分层与定位（JUnit vs pytest、去重判据 C1-C3）   |
| 一\~五 | 脚本解决的问题、前置条件与路径常量、使用方式与退出码、设计、沙盒限制与踩坑 |
| 六    | 数据副作用与隔离（测试库 3307、自动清理、完整性校验）         |
| 七    | 给其他 agent 的快速操作指南                     |
| 八    | 维护约定                                  |
| 九    | 上下文收口执行方式（run\_tests\_report.py）      |

***

## 〇、测试分层与定位（T1，2026-09-01）

> 本节为 **D-5（已定）** 落地方案：JUnit=单元基准、pytest=端到端；.http 已于 2026-09-01 移除（不纳入测试体系，调试价值有限）。所有新增/改动测试前先按 0.2 判据选层，避免重复覆盖（P1）。

### 0.1 两层定位

| 层                         | 定位          | 运行方式                                                  | 断言形态与边界                                                                               |
| ------------------------- | ----------- | ----------------------------------------------------- | ------------------------------------------------------------------------------------- |
| JUnit（`src/test/java`）    | **服务层单元基准** | `python tools\run_tests_report.py junit`（mvn -o test） | Mock DAO/缓存，**不碰数据库/HTTP**；验证逻辑契约（计算/异常类型/DAO 调用契约/缓存联动）                              |
| pytest（`src/test/python`） | **端到端验收**   | `python tools\run_tests_report.py all`                | 真实 MySQL/Redis/Tomcat；验证 HTTP 入口行为（401/400/403/404/409…）与跨层副作用（落库、缓存失效、物理文件删除、跨接口可见性） |

### 0.2 去重判据（C1-C3，按行为形态单选）

| 判据 | 问句                                                        | 命中 → 载体                              |
| -- | --------------------------------------------------------- | ------------------------------------ |
| C1 | 校验点藏在 service 方法内部（计算/异常类型/DAO 调用契约），mock 依赖即可断言，无需真实 DB？ | → JUnit                              |
| C2 | 需要经 HTTP 入口（鉴权/过滤器/参数解析），或依赖真实堆栈副作用（落库/缓存/文件/跨接口可见性）才能断言？ | → pytest                             |
| C3 | C1、C2 都命中？                                                | → **按行为拆分**，每条子断言重走 C1/C2 单选，分别进两份文件 |

规则：**同一条断言的意图永远只有唯一归属**；多选仅发生在"需求跨层"（同一需求既有纯逻辑契约又有跨层副作用时拆两份文件），绝不允许同一断言重复覆盖。示例：JUnit 不写 401/400（HTTP 入口归 pytest）；pytest 不重复归一化计算（逻辑契约归 JUnit）。日志类断言同理（T4 日志体系）：格式串 / 装配 / 轮转参数 → JUnit；"真实请求是否落盘、输出端之间是否串通、跨文件能否按 `req=` 串联" → pytest（读落盘文件，写法见 §4.5）。

### 0.3 实测用例基数（2026-09-01）

| 体系     | 数量                 |
| ------ | ------------------ |
| JUnit  | 113 例（surefire 实跑） |
| pytest | 103 例（`def test_`） |

> 后续以 `latest.json` 实跑计数为最权威来源，本节仅作追溯基数。

***

## 一、解决什么问题

1. 沙盒不能外网下载依赖，因此 Maven 必须离线、pytest 依赖必须预先放在本地目录。
2. 沙盒不允许 `Start-Process` 启动后台服务（会触发授权），但允许普通 Python 子进程方式拉起 Tomcat。
3. 用户的 IDEA Tomcat 占用 8080，脚本必须使用完全隔离的独立实例，不能干扰开发环境。
4. 用户此前出过事故，脚本的停止/清理逻辑必须保守：只清理自己启动的进程，绝不误杀无关进程。

***

## 二、前置条件与路径常量

| 名称             | 路径（默认值）                                                                                   | 覆盖环境变量                | 说明                                                         |
| -------------- | ----------------------------------------------------------------------------------------- | --------------------- | ---------------------------------------------------------- |
| JDK            | `D:\dev\DevTools\jdk\openjdk-25.0.2`                                                      | TV\_JAVA\_HOME        | 必须位于沙盒可写根目录，否则 Java 的 `toRealPath` 会被沙盒拒绝                  |
| Tomcat（安装）     | `D:\dev\DevTools\tomcat\apache-tomcat-10.1.54`                                            | TV\_CATALINA\_HOME    | 只读使用安装目录，运行目录另建                                            |
| Maven          | `D:\IDE\IDEA\IntelliJ IDEA 2025.3.2\plugins\maven\lib\maven3\bin\mvn.cmd`                 | TV\_MAVEN\_CMD        | IDEA 自带发行版                                                 |
| 离线本地仓库         | `D:\dev\WorkSpace\VideoPlatform\maven`                                                    | TV\_M2\_REPO          | 由 `C:\Users\ASUS\.m2\repository` 复制而来；javac 需要可写目录读取依赖 jar |
| pytest 依赖      | `D:\dev\WorkSpace\VideoPlatform\temp\pytest-deps`                                         | TV\_PYTEST\_DEPS      | 由用户在自己的终端用 `pip install --target` 生成，沙盒只读使用                |
| 独立 Tomcat 运行目录 | 项目内 `.stage8-target\tomcat-test-18080`（T9 起，相对项目根；含 conf/webapps/logs）                | TV\_CATALINA\_BASE    | CATALINA\_BASE，含 conf/webapps/logs                         |
| 构建输出目录（war）    | 项目内 `.stage8-target`（其下 `untitled-1.0-SNAPSHOT.war`，T9 起）                      | TV\_STAGE8\_TARGET    | 由 `build` 生成                                               |
| 测试报告目录         | 项目内 `.stage8-target\test-reports`（T9 起）                                              | TV\_TEST\_REPORT\_DIR | run\_tests\_report.py 完整日志与 latest.json 落盘位置               |
| 测试媒体目录         | 项目内 `.stage8-target\media-test`（video/image/cover，T9 起）                        | TV\_TEST\_MEDIA\_ROOT | T2 起 18080 测试实例上传落盘 + `/upload` 挂载指向此处，与生产 stone 隔离；**白名单硬编码于 run_tests.py**：仅该目录会被移动式回收（整目录移入 test_trash，只移不删），env 覆盖为其它路径/命中生产根均拒绝（exit 12）  |
| 测试媒体回收站         | 项目内 `.stage8-target\test_trash`（含 `<media-test>-<时间戳>/` 历史快照，T9 起）                  | TV\_TEST\_TRASH\_ROOT | 旧 media-test 移动式回收落点（不删除任何文件，用户手动清理）；落点命中生产根或与 media-test 重叠/嵌套时拒绝（exit 12）  |
| 测试代码           | 项目 `src\test\python`                                                                      | 无                     | pytest 用例 + conftest.py + pytest.ini                       |

> 覆盖机制：上述路径均可在 `tools/run_tests*.py` 中通过同名 `TV_*` 环境变量覆盖（T1 落地，与 conftest.py 的 TV\_BASE\_URL 等先例一致）。HTTP 端口 18080/shutdown 18005 属安全隔离设计，**不可覆盖**。
> 两个超时旋钮同样可覆盖：`TV_START_TIMEOUT`（就绪等待上限，默认 **180s**，T10）/ `TV_PYTEST_TIMEOUT`（pytest 整体刹车，默认 60s，T2）——见 §三 退出码 6 / 11。

外部服务依赖：生产库 MySQL80（TVDatabase:3306）+ **独立测试库 Docker MySQL8.4（TVDatabase\_test:3307）** + Redis + RabbitMQ（`rabbitmq` 容器，5672；T16 起），均需运行中。

***

## 三、使用方式

在项目根目录执行：

```powershell
python tools\run_tests.py all      # 推荐：打包 -> 启动 -> 测试 -> 关停
python tools\run_tests.py build    # 只做 Maven 离线打包
python tools\run_tests.py start    # 只启动独立 Tomcat（18080，后台保持）
python tools\run_tests.py test     # 只跑 pytest（要求 18080 已就绪）
python tools\run_tests.py stop     # 只关停独立 Tomcat
```

`all` 的典型耗时约 **2 分钟**（2026-09-23 T10 实测：连续 3 次 `test all` 的分段耗时 = 打包 12\~26s / Tomcat 就绪（多数走重展开路径）40\~46s / pytest 34\~42s / 关停 8\~10s）。⚠️ 旧文记的「约 30\~35 秒：打包 2 秒、启动 16 秒、pytest 3 秒」是更早窗口（构建/部署目录在仓库外）的口径，与本机现状不符，已作废。

### 退出码约定

| 退出码   | 含义                  |
| ----- | ------------------- |
| 0     | 成功                  |
| 2     | 必要路径缺失              |
| 3     | Maven 成功但未生成 war    |
| 4     | 18080 被其他程序占用，拒绝启动  |
| 5 / 6 | Tomcat 提前退出 / 启动超时  |
| 7     | 停止失败且无法确认进程归属，需人工检查 |
| 8     | 测试前置不满足（18080 未就绪）  |
| 9     | 配置/挂载回读校验失败（server.xml 端口或 ROOT.war 媒体挂载改写后校验不符，拒绝启动） |
| 10    | 测试环境未就绪（MySQL 3307 / Redis 6379 / RabbitMQ 5672 探测失败，秒级退出） |
| 11    | pytest 执行超时（默认 60s，`TV_PYTEST_TIMEOUT` 可覆盖）被强制终止 |
| 12    | 媒体目录门禁拒绝（破坏权只信任硬编码白名单，任一失败 exit 12）：① 移动源（归一化后）不等于 run_tests.py 硬编码的 `DEFAULT_TEST_MEDIA_ROOT`（白名单）——env 覆盖到任何其它目录均无法移动、无人工确认通道；② 移动源命中生产媒体根（app.properties upload.path / 默认生产 stone 或其子目录）——白名单被人工改动指向生产时的第二道保险；③ 回收站落点（`TEST_TRASH_ROOT`）命中生产根或与移动源重叠/嵌套 |

> 补充（哪类失败本就快，T2 于 2026-09-05 确认）：Maven 编译失败与 Tomcat 提前退出本就秒级失败（exit = mvn 返回码 / 5，不空等）；10 号专门解决「DB/Redis 未就绪导致应用不就绪的空等」；就绪超时（6）保留为兜底，上限**默认 180s、`TV_START_TIMEOUT` 可覆盖**（T10，2026-09-23：原 90s 仅最慢通过样本的 1.4 倍、落在实测抖动带内，会造成 `test all` 偶发"等待超时"假失败）。

***

## 四、脚本设计

### 4.1 主流程（`all`）

```text
build ──> start ──> test ──> finally: stop
```

`all` 持有自己启动的 `Popen` 对象，`finally` 保证即使 pytest 失败也会执行 `stop`。

pytest 阶段有整体超时刹车（T2，2026-09-05）：`subprocess.run(timeout=…)`，默认 60s、`TV_PYTEST_TIMEOUT` 可覆盖；超时即强制终止 pytest 进程并报错（exit 11），`all` 的 `finally` 仍保证 `stop`，不会留下 18080 实例。

### 4.2 启动

- 用 `subprocess.Popen(["cmd", "/c", "call", "catalina.bat", "run"])` 拉起，`CREATE_NO_WINDOW` 隐藏窗口。

- 把包装进程 PID 写入 `CATALINA_BASE\logs\tomcat.pid`。

- 轮询 `http://127.0.0.1:18080/start`，就绪后返回。

- **就绪等待上限**（T10，2026-09-23）：**每 2s 探测一次**，上限**默认 180s**（`TV_START_TIMEOUT` 可覆盖）；到点仍未就绪 → `stop` + `exit 6`（退出码口径不变，日志仍为 `等待超时（<上限>s）`）。默认值依据 = **全量 `run.log` 实测（45 个就绪样本，三带分布）**：**40.1\~45.3s ×23**（主流，推断为 Tomcat 重展开 `webapps/ROOT` —— war 每次重建、约 8.2MB / 数百文件）/ **61.0\~65.4s ×4**（慢时段）/ **6.0\~6.6s ×18**（Tomcat 命中复用、未重展开）/ 1 次 **>90s** 触顶（即 T10 要消除的那次假失败）；**180s ≈ 最慢通过样本（65.4s）的 2.8 倍**，已落在抖动带之外（旧值 90s 仅 1.4 倍、正卡带内）。⚠️ "是否重展开"由 Tomcat 自己的 mtime 比较决定（两带并存），本窗口未追根因——不影响取值：上限要覆盖的是**慢带**。

- **启动前环境预检**（T2，2026-09-05；T16 起扩展第三项 RabbitMQ(5672)）：纯 socket 探测测试库 MySQL(3307)、Redis(6379) 与 RabbitMQ(5672)，不通即报「测试环境未就绪」并秒级退出（exit 10），不再空等至启动超时（6）。应用自身的 MQ 降级能力不由预检负责（T17 单测覆盖），预检只约束测试环境。

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

- 复用固定 CATALINA\_BASE；`conf/server.xml` 缺失时从 CATALINA\_HOME 复制，然后回读校验端口配置，防止误用 8080。

- war 部署为 `webapps\ROOT.war`（根上下文 `/`）。

### 4.5 日志

脚本与容器日志全部位于 `CATALINA_BASE\logs\`（T9 起即项目内 `.stage8-target\tomcat-test-18080\logs\`）：

- `run.log`：脚本自己的操作日志

- `tomcat_stdout.log` / `tomcat_stderr.log`：Tomcat 控制台输出

- `catalina.*.log`、`localhost.*.log`：Tomcat 运行日志

**应用日志（T1~T4 日志体系，JUL 自建）**：与上面的容器日志同目录，但由应用自己写；落点规则 = "**`log.file` 所在目录 = 日志目录，其余输出端相对路径只取文件名、落同一目录**"（口径见 `src/main/java/com/itheima/util/LogUtil.java` 类注释）：

| 输出端 | 文件名 | 收什么                                              |
| ---- | ---- | ------------------------------------------------ |
| system | `system.log.<N>` | 全域应用日志（阈值 `log.level`，默认 INFO）：失败（SEVERE）/ 降级（WARNING）/ **业务里程碑 INFO（T9：登录、注册、内容发布、作者删除作品、关注·取关，共 7 点）** |
| error | `error.log.<N>` | 只收 `>= log.error.level`（默认 SEVERE）                |
| access | `access.log.<N>` | 访问日志（专属 logger `access`，`useParentHandlers=false` → **只落本文件**，不进 system.log） |
| audit | `audit.log.<N>` | 审计留痕（专属 logger `audit`，T8：管理端 4 个写操作 + 用户侧 3 个敏感变更的**成功路径**；同样 `useParentHandlers=false` → 不进 system.log；阈值固定 INFO，**不受 `log.level` 影响**） |

- **落盘目录随链路不同**：`all`/`start`/`test`（18080 实例）注入 `LOG_PATH=<CATALINA_BASE>/logs/system.log` → 四个文件都落 `.stage8-target\tomcat-test-18080\logs\`；`junit` 由 surefire 另注入 `LOG_PATH=${stage8.buildDir}/test-logs/system.log` → 落 `.stage8-target\test-logs\`（由此"测试日志不再写进运行日志目录"，N5）。
- **文件名是轮转形态**：`<名>.<N>`，**N=0 为当前写入文件、N 越大越旧**（`log.maxBytes` / `log.fileCount` 控制）；读日志要取"前缀匹配 + mtime 最新"的那个文件，不要写死 `<名>.log`。
- **pytest 怎么断言日志**（先例：`src/test/python/test_access_log.py`、`test_log_outputs.py`、`test_audit_log.py`、`test_milestone_log.py`）：① 选文件 = "`<名>.log*` 前缀匹配 + mtime 最新"，**必须排除 JUL 的 `<名>.<N>.lck` 锁文件**；② 断言分两类——**全文件不变式**（分流口径须对文件里每一行成立，如 error 只收 SEVERE、audit 只收合法审计行）与**本 run 记录**（"响应先于落盘返回"是常态 → 轮询至多 3s）；③ 精确定位"本次请求"的落盘记录**不要依赖行号增量**（落盘期间可能发生轮转），改用**唯一指纹**：把随机 marker 塞进请求参数、由异常栈回显，再向前回退到最近的 `ts=` 行即为该记录（`test_log_outputs.py` 的 `record_containing`），记录行上的 `req=` 可继续用来跨输出端回查；**审计行没有异常栈可回显** → 改用"**动作三元组指纹 + 全文件计数 delta**"（`test_audit_log.py` 的 `audited()`：`action=`/`operatorId=`/`target=` 的行数在执行操作前后必须恰好 +1；target id 全部取自本 run 新建对象，故测试库重建导致 id 复用、历史行仍在也不会误判）。归属判据见 §〇.2：**日志是否真的落盘、输出端之间是否串通**属"依赖真实堆栈副作用" → pytest；只到装配层（handler / level / 格式串 / 轮转参数）的断言归 JUnit。
- **业务里程碑 INFO 的断言口径（T9 立的第三类写法，先例 `src/test/python/test_milestone_log.py`）**：① **"恰一条"仍用指纹 + 全文件计数 delta**（`milestone()`：执行前后必须恰好 +1），但指纹取**消息前缀**（`msg=关注成功, userId=`）而非对象 id——对象 id 只能从响应里拿到、无法用于"执行前"计数；随后把**新增那一行**与本 run 新建对象对齐（`userId=` / `contentId=` 出现在该行即证归属）。② **失败路径断言 = delta 恒 0**（`assert_no_milestone()`：401 / 409 等拒绝后指纹行数不得变化）。③ **手机号不变式必须先剥掉 `req=<16hex>`**：请求 id 是 16 位十六进制、天然命中 `1[3-9]\\d{9}`（实测 26 行假阳性），不剥会误报"日志出现明文手机号"。④ 单测侧的共享探针 = `src/test/java/com/itheima/util/LogProbe`（命名不匹配 surefire includes，不会被当用例跑）。
- **事务基础设施 / 包装点定栈的断言口径（T11 立的第四类写法）**：① **"恰一条带堆栈"用 JUnit 探针 + 真实模板**（`src/test/java/com/itheima/util/TransactionTemplateTest`：真实 `TransactionTemplate` + 真实连接池，回调分别抛 `SQLException` / `BusinessException` / `IllegalStateException` → 断言"恰一条 SEVERE 带栈（且消息不含 SQL 文本）" / "0 条" / "0 条"）；② **真实基础设施异常**（连接池耗尽）在 `pool-test` 执行 fork 内做（`MyConnectionPoolTest#transactionTemplateLogsSevereWhenConnectionExhausted`——该 fork 已是 `DB_POOL_MAXSIZE=1` / `DB_POOL_TIMEOUTMS=500`，占满池即得 `SQLException("获取数据库连接超时")`）；③ **跨层联合断言**（内容装载链、评论树链）用真实模板 + mock DAO/Redis，断言堆栈恰落在包装点（`TransactionTemplate` / `CommentCache`）、装载层与吸收点两行均 `getThrown() == null`；④ **e2e 注不进基础设施异常** → 该类验收只由单测承担，pytest 侧只做回归（本任务对外行为零变化，故无新增 pytest 断言）。
- **可预期业务拒绝的级别断言（T12 立的第五类写法，先例 `src/test/python/test_log_outputs.py` 的 `test_expected_rejections_are_warning_and_never_reach_error_output`）**：① 触发面用**本 run 新建的一次性用户**（401 旧密码错误 / 400 手机号不匹配 / 409 撞名），按其 `userId` + 端点 + 结果码在 `access.log` 上定位本请求的 `req=`（**不用行号增量**）；② 正向断言 = `system.log` 上同 `req` 的**源头结论行**存在且 `level=WARNING`，并检查其**紧随一行不是堆栈续行**（"不带栈"的落盘判据）；③ 反向断言 = `error.log` 里该 `req` **0 条**（**按 req 定向**，不做全文件计数——append=true 下历史 SEVERE 行仍在，全文件断言会误红）；④ 该口径属"**真失败才允许进 `error.log`**"（该端阈值 `SEVERE`）的负向验证，正面（500 类仍落）由 JUnit `assertExactlyOneStacked` + T4 的 `test_error_output_keeps_only_severe` 承担。

### 4.6 日志消费报告（`tools/log_report.py`，日志第三张清单 T13）

让结构化日志"第一次被真正消费"的**只读**工具（NEEDS R-01 消费层最小可用收窄版），挂 `tv.py` 子命令 `log-report`。

- **只读红线**：不写 / 不删 / 不改 / 不移动 / 不压缩任何日志文件、不触发轮转；报告只落 stdout（**不生成落盘文件**，需要留档自行重定向）。可证方式 = 日志目录 `(文件名, 字节数, LastWriteTime)` 前后快照 diff + `git status` 无业务代码改动。
- **数据源（三条链路）**：默认 `.stage8-target\tomcat-test-18080\logs\`（e2e `test all` 产物）；`--dir .stage8-target/test-logs` = JUnit 链路；`--dir logs` = 生产 / 本地直跑链路（相对路径按 cwd 解析）。**四端文件名与慢请求阈值取自 `src/main/resources/app.properties`**（`--config` 可换；文件 / 键缺失回退内置默认名与 1000ms，并在报告头部提示）。
- **窗口与轮转合并**：每端取目录内匹配 `<名>.log` 或 `<名>.log.<N>` 的全部文件（**排除 JUL 的 `.lck` 锁文件**；后缀非轮转序号的同名文件列入报告的 `忽略文件`），**裸名文件视为最旧、其后按 N 降序（N 越大越旧）**；逐文件读入后按 `ts=` 升序**稳定排序**，取**末尾 N 行**（默认 `--limit 2000`，`--all` 取消上限）。视角 (a) **不受窗口约束**（在四端全部行里定向过滤 `req=`）。
- **容错判读**：半行 / 缺字段 / 非 UTF-8 坏字节 / 混入其它端记录 / 旧格式残留一律**跳过并计数**（报告的 `未识别行`，按文件分列；空行单列）。现成例子：e2e 目录里的裸名 `system.log`（T1 前的 JUL 两行格式）**贡献 0 条记录 + 5755 行未识别**——属预期而非故障。`未识别行` / `空行` 只统计**整份文件**，与窗口无关。
- **三视角口径**：
  - **(a) `req=` 全链路追溯**：按 `ts` 排序展示同一请求在四端的记录；`system` / `error` 的异常堆栈计入该记录的 `续行 N`（首行续行另起一行显示）。缺省自动选"窗口内最新一条 `code>=500`"（无则最新一条 access 行），`--req <16hex>` 可指定。**注意**：同一条记录可能在 `tomcat_stderr.log` 出现容器 stderr 副本——本工具**只消费四端**；JUnit 侧审计行（非请求线程写入）**没有 `req=`**，不参与串联。
  - **(b) 耗时分布**：按 `path` 聚合（次数 / 平均 / p50 / p95 / 最大 / `slow=1` 数；p95 = 最近秩法），按 p95 降序取 `--top`（默认 10）+ `slow=1` 明细（按 `cost` 降序）+ **一致性核对**。
  - **(c) 错误率**：结果码分布；**分母 = `code != 0` 的业务行**（`code=0` = 未走业务统一出口：静态资源 / OPTIONS 预检 / 未映射 404——单列、不计入错误率）；**4xx = 预期拒绝、5xx = 失败，必须分列**（与 `LOG_CONVENTION` §3.1 附加纪律 1 / T12 同源口径）；附 Top 5xx path 与 Top 4xx (path, code)。
- **`slow` 一致性不是全局不变式**：`slow=1 ⇔ cost >= log.slowRequestMs` 只在**同一阈值**下成立，**记录值即权威**（工具不重算、不据此返回非 0）。反例：JUnit 链路 `access.log.0` 的 15 条 `/probe` 行 `cost=0ms slow=1`（用例直测阈值 0）→ 报告如实显示"不一致 15 条"。
- **审计动作分布**：概览里按 `action=` 聚合（窗口内、**只列出现的动作**）——未出现即 0 条，如 e2e 中 `user.changePhone` 恒为 0（该点当前无 HTTP 入口，见 §4.5 与 `LOG_CONVENTION` 3.5）。
- **机器可读**：`--json` 在 stdout 输出单个 JSON 对象（`meta` / `overview` / `trace` / `latency` / `errors`）；**经 `tv.py` 调用会多出横幅行 → 机器消费请直调 `python tools\log_report.py --json`**。
- **退出码**：0 = 出报告（含"目录存在但无日志文件"的零数据报告）；2 = 参数 / 目录错误（argparse 或友好错误，**无 Traceback**）。工具**不因报告内容异常返回非 0**（只读报告，不做门禁）。
- **典型用法**：

  ```powershell
  python tools\tv.py log-report                                 # e2e 产物 + 默认窗口
  python tools\log_report.py --all                              # 全量口径
  python tools\log_report.py --req <16hex>                      # 指定请求跨端追溯
  python tools\log_report.py --dir .stage8-target/test-logs     # JUnit 链路
  python tools\log_report.py --dir logs                         # 生产 / 本地直跑链路
  ```

- **验证边界（T13）**：本任务**不改业务代码**，故**未新增 pytest 用例**（项目规则只约束业务改动；归属判据同 §〇.2——该工具是消费侧、不产生落盘副作用）；验收数字由**三方对账**承担：报告 ↔ **独立重算**（`ts=` 行数 / 结果码分布 / `slow` 计数）↔ **合成夹具**（`temp_script/t13_fixture_check.py`，23 项：轮转合并顺序 / 窗口 tail / 未识别与空行计数 / 4xx·5xx 分列 / slow 一致性 / 四端串联 / 只读性 / 非法参数）。**生产 / 本地直跑链路仍无实测数据**（`logs/` 现为空，属 R-11"已知缺口"）。

***

## 五、沙盒限制与踩坑记录（重要）

1. **不要用** **`Start-Process`** **启动 Tomcat**：沙盒会要求授权。必须用 Python 子进程，这也是项目 AGENTS.md 对脚本语言的要求。
2. **JDK 必须在可写根目录**：沙盒会拒绝 Java 对工作区外文件执行 `toRealPath`，这是此前 Maven 构建失败的根因。
3. **Maven 需要可写本地仓库**：javac 读取依赖 jar 时同样受路径限制，所以必须用离线副本（`D:\dev\WorkSpace\VideoPlatform\maven`）并通过 `-Dmaven.repo.local` 指定。
4. **沙盒可能禁止进程枚举**（CIM/tasklist 返回空）：脚本已改为以端口探测为主；停止失败时宁可报错让用户人工检查，也不扩大强杀范围。
5. **必须用** **`127.0.0.1`** **而不是** **`localhost`**：Python 连 `localhost` 会先试 IPv6 `::1`，而 Tomcat 只绑 IPv4，每次新连接白白等待约 2 秒，整套测试会从 3 秒退化到 150 秒。
6. **pytest/requests 无法在沙盒内 pip 安装**：需要在用户普通终端执行：

   ```powershell
   & "C:\Users\ASUS\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" -m pip install --target "D:\dev\WorkSpace\VideoPlatform\temp\pytest-deps" pytest requests
   ```

   沙盒运行测试时通过 `PYTHONPATH` 指向该目录。
7. **javac 无法读取 worktree 的** **`target/`** **作为 classpath**：沙箱内目录枚举被拒，表现为测试编译时"程序包 com.itheima.\* 不存在"；因此 Maven 构建通过 `-Dstage8.buildDir` 指向项目内 `.stage8-target`（T9 起；早期为 `D:\data\projects\VideoPlatform\stone\temp\stage8-target`，pom 默认 `./target`），war 也位于该目录。
8. **离线仓库来源记录**：新加入的测试依赖（junit/mockito/bytebuddy/surefire）`_remote.repositories` 原本只有 `>central=`，默认 aliyun 镜像下离线解析会拒认；已逐项追加 `>aliyun=` 行（只追加不删除，模式与既有 mysql 依赖一致）。

***

## 六、数据副作用与隔离

pytest 端到端用例运行在**独立测试库** `TVDatabase_test`（Docker MySQL 127.0.0.1:3307，连接由 `run_tests.py` 通过 `DB_URL/DB_USERNAME/DB_PASSWORD` 环境变量注入，`AppConfig` 自动读取，不改 app.properties；默认 JDBC 串含 `allowPublicKeyRetrieval=true`，适配测试库 MySQL8 caching\_sha2 认证，T2 返工 2026-09-05）：

- 注册用户（`testA_*`、`testB_*`、`smoke_user_*`、`timing_*` 等）

- 上传测试视频/图片（T2 起落独立测试媒体目录 `D:\data\projects\VideoPlatform\media-test`，不再写入生产 stone）

- 评论、点赞、关注记录

- 抢优惠券（消耗库存）

**自动清理**：`run_tests.py test/all` 结束后自动清理**测试库**数据库数据（等价 `DB_* 指向测试库 + cleanup_data.py --execute --no-backup`）：
① pytest_/smoke_ 前缀 content 及关联记录；② **测试用户（T5，2026-09-08）**：username 命中 `cleanup_data.TEST_USERNAME_PREFIXES` 白名单（testA_/testB_/smoke_user_/admin_/hid_admin_/timing_）的 users 及关联（content/comment/comment_like/content_like/follow/coupon_order，关联表无 users 外键须显式级联；顺序子表在前）。

seed 用户（一号员工/内部人员等中文名）不命中白名单，天然不受影响；删除测试用户后非测试行的冗余计数（like_count/follower_count/follow_count）可能漂移，用 `check_integrity.py --fix` 修复（既有职责边界）。**生产库 TVDatabase 不再产生测试残留**。

**媒体文件**（T2 起）：pytest 上传的媒体文件落 `media-test`（`run_tests.py start` fresh-start 时把旧 media-test 整目录**移动式回收**至 `test_trash`——只移不删、由用户手动清理，再重建空目录；生命周期由 run_tests 管理，不产生孤儿残留）；生产 stone 不再接收测试文件。`cleanup_orphan_media.py` 为保护真实内容的回收工具，仍仅限 prod 语境执行（媒体根按库判定，见其 docstring）。

**管理员链路**：`test_admin.py` / `test_hide_content.py` / `test_comment_delete.py` 的管理员操作通过 `DB_*` 环境变量连测试库（`run_tests.py cmd_test` 注入），`tools/admin.py` 与测试内直接 SQL 均遵守该约定；人工命令行调用 admin.py（或经 `tv.py admin`）默认连接 tools/env 当前激活环境（默认测试库，T6 起不再默认生产库），生产操作需显式 `tv.py env prod` 并二次确认。测试库重建后由基线种子提供 `users.id=1` 管理员（role=1，见 TEST_SEED.md）；三个 admin 用例仍各自动态"注册/挑选 + 提升 + 降级"自建自清，不依赖该种子账号。

### 6.1 测试库初始化与重建

- 一次初始化/表结构更新后重建：`python tools\init_test_db.py`（默认取 `.docs/archive/DBbackups` 下最新 `db.sql`，或 `--dump` 指定；DROP+CREATE 后导入，并**自动追加基线种子**：提升 `users.id=1` 为管理员 + 一条 `seed_baseline_*` 内容及评论链，内容与维护约定见 TEST_SEED.md）。

- 测试库连接参数默认取自 `tools/env/test.conf`（T6：init\_test\_db 固定读 test.conf，不读 active.conf、不开 prod 后门）；仍可用 `DB_HOST/DB_PORT/DB_USER/DB_PASSWORD/DB_NAME` 环境变量覆盖。run\_tests 体系另持独立覆盖机制：`TV_DB_HOST/TV_DB_PORT/TV_DB_USER/TV_DB_PASSWORD/TV_DB_NAME/TV_DB_URL`（默认 127.0.0.1:3307 / root / ROOT123 / TVDatabase\_test）。

- 安全门禁：`init_test_db.py` 拒绝操作 `DB_PORT=3306`，防止误 DROP 生产库。

- 清理报告：`cleanup_data.py --execute` 生成的 `CLEANUP_REPORT_*.md` 写入 `.docs/temp/`（已在 .gitignore，不入库）。

### 6.2 完整性校验统一工具（tools/check\_integrity.py）

脏数据排查单一入口（默认 dry-run 只读，绝不移动/删除文件；计数漂移修复需显式 `--fix`）：一次列出**孤儿记录**（content\_media/content\_like/comment/comment\_like/comment\_media/楼中楼孤儿回复）、**孤儿媒体文件**（磁盘无库引用）、**库引用缺失文件**（库 URL 指向的磁盘文件不存在）、**重复引用**（content\_media/comment\_media 无唯一键）、**计数漂移**（content.like\_count / content.comment\_count / comment.like\_count / users.follow\_count / users.follower\_count 与关联表实际记录数不一致）。

```powershell
python tools\tv.py integrity                             # 完整性检查（默认当前激活环境，默认测试库），报告落 .docs/temp/INTEGRITY_REPORT_*.md
python tools\tv.py integrity --no-media                  # 跳过磁盘媒体检查（只查库内）
python tools\tv.py integrity --fix                       # 显式修复计数漂移（prod 下需二次确认）
python tools\tv.py --env prod integrity                  # 本次检查生产库（临时，不改 active.conf）
```

> 测试库场景（T2 起）：媒体根按库判定已指向 `media-test`（与连接库对应），可安全扫描，不会误扫生产；仍可用 `--no-media` 只查库内检查项。

退出码：0 完成 / 1 参数或其它错误（含 `--fix` 失败已回滚）/ 2 mysql 客户端不可用 / 3 数据库连接或健康门禁失败（未触碰任何数据）/ 5 疑似配置错误（库内存在媒体记录但磁盘与被引用 URL 无重叠或媒体目录缺失）。

**职责边界**：`check_integrity.py` 默认只做只读检查并输出报告；计数漂移可经显式 `--fix` 单事务重算修复（不删行、不动文件；SQL 语义以本文件 `FIX_STATEMENTS` 为唯一基准）；其余清理按报告另行执行 `cleanup_data.py`（测试污染/孤儿 content\_media/comment\_like）或 `cleanup_orphan_media.py`（孤儿媒体移回收站）；其余检查项（孤儿 comment/content\_like/comment\_media/楼中楼、重复引用、库引用缺失文件）cleanup 暂不支持，需人工或后续工具处理。

***

## 七、给其他 agent 的快速操作指南

1. 先做只读检查（全部应为 True）：

   ```powershell
   Test-Path 'D:\dev\DevTools\jdk\openjdk-25.0.2\bin\java.exe'
   Test-Path 'D:\dev\WorkSpace\VideoPlatform\maven'
   Test-Path 'D:\dev\WorkSpace\VideoPlatform\temp\pytest-deps'
   Test-Path 'D:\dev\DevTools\tomcat\apache-tomcat-10.1.54\bin\catalina.bat'
   ```

2. 确认测试环境外部依赖就绪（3307 测试库 / 6379 Redis / 5672 RabbitMQ，T2 起 `start/all` 会自动预检并秒级报错；第三项 T16 起）：

   ```powershell
   Test-NetConnection 127.0.0.1 -Port 3307 | Select-Object TcpTestSucceeded
   Test-NetConnection 127.0.0.1 -Port 6379 | Select-Object TcpTestSucceeded
   Test-NetConnection 127.0.0.1 -Port 5672 | Select-Object TcpTestSucceeded
   ```

3. 确认 18080 空闲：

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

6. 不要修改脚本的安全边界：8080 隔离、进程归属校验、不删除用户数据。

***

## 八、维护约定

- 修改路径或端口时，同步更新脚本常量与本文档。

- 脚本改动后至少执行 `python -m py_compile tools\run_tests.py` 并完整跑一次 `all`。

- 涉及安全逻辑（停止/强杀/删除）的大改动，建议按用户要求先派 subagent 审查，通过后再运行。

- pytest 用例自包含（T4，2026-09-05）：用例应自建自清（独立建立数据 + finally 清理），不写共享 fixture（sample_content_id/sample_post_content_id）；同文件/跨文件不得存在运行顺序依赖（如 C-01→C-02 点赞状态、edit_work 换源永久替换共享媒体）。例外：对共享 fixture 的临时改动（如改文案）必须 finally 复原，净零残留方可。

- 断言不锁定中文文案（T5，2026-09-05）：端到端用例断言 code + 稳定字段，不写 `"xxx" in msg` 类中文子串匹配；被断言响应稳定字段需在响应中恒定存在；后端文案措辞调整不应翻转测试结果。

- 统一 db 配置（T6，2026-09-05）：库操作脚本（admin / cleanup_data / check\_integrity / backup / cleanup\_orphan\_media / init\_test\_db）的连接参数统一经 `tools/db_config.py` 读取 `tools/env/`（`rg "environ.get\(\"DB_" tools` 应仅 db_config.py 一处）。`test.conf` 追踪；`prod.conf`（含生产口令）与 `active.conf`（当前生效）被 .gitignore 排除，模板见 `prod.conf.example`。修改连接参数：改对应 `*.conf` 后运行 `python tools\tv.py env <name>` 重新激活（active.conf 由切换命令生成，手工编辑无效）。run\_tests 体系的 TEST\_DB\_\* 属安全边界，与本配置独立双份维护，改动时注意同步 test.conf。经 `tv.py` 执行时以声明环境（active / \-\-env）配置为准：tv.py 会把对应 conf 注入子进程环境变量、覆盖继承的 DB\_\* 残留（保证"声明环境 == 实际连接库"，防环境变量残留绕过 prod 确认/禁 test 护栏）；因此不要在 tv.py 前残留 DB\_\* 期望被脚本读取。直接运行脚本（不经 tv.py）时环境变量优先级不变。

## 九、上下文收口执行方式（主会话推荐入口）

> 文件：`tools/run_tests_report.py`
> 用途：主会话跑测试时避免输出灌入上下文；完整日志落盘 + `latest.json` 留痕。
> 最后验证：2026-08-10，35/35 通过，stdout 仅 3 行摘要。

### 9.1 为什么测试不派 subagent 执行

当前环境实测（2026-08-10）：

| 派发方式                | 结果                          |
| ------------------- | --------------------------- |
| `fork_turns="none"` | 任务消息不送达，subagent 回复“没有收到任务” |
| `fork_turns="1"`    | 最小 ACK 任务也卡死无响应，需人工中断       |
| `fork_turns="all"`  | 消息可达，但继承全部项目上下文，跑完测试会自行找活干  |

因此测试统一由主会话 + 收口脚本执行，不派 subagent；除非用户明确要求。

### 9.2 用法

```powershell
python tools\run_tests_report.py all       # 默认：build -> start -> test -> stop
python tools\run_tests_report.py junit     # mvn -o test（JUnit 用例数以 latest.json/0.3 节为准，2026-09-01 实测 surefire 113 例，输出同样落盘）
python tools\run_tests_report.py build     # 仅离线打包
python tools\run_tests_report.py start     # 仅启动
python tools\run_tests_report.py test      # 仅 pytest
python tools\run_tests_report.py stop      # 仅关停
```

`all/build/start/test/stop` 参数原样透传给 `tools/run_tests.py`，退出码约定相同；`junit` 等价执行 `mvn -o test`（含连接池独立 fork 执行）。

### 9.3 产物

- 完整日志：项目内 `.stage8-target\test-reports\run-<yyyyMMdd_HHmmss>.log`（UTF-8，只追加不删除；T9 起）

- 机器可读结果：同目录 `latest.json`

`latest.json` 字段：

| 字段                                    | 说明                        |
| ------------------------------------- | ------------------------- |
| `phase` / `script`                    | 本次执行的子命令                  |
| `exit_code`                           | run\_tests.py 退出码         |
| `build_ok`                            | build/all 时是否构建成功         |
| `pytest.passed/failed/errors/skipped` | pytest 汇总计数               |
| `pytest_summary`                      | pytest 最后一行汇总             |
| `junit.tests/failures/errors/skipped` | junit 时 surefire 每测试类统计之和 |
| `junit_summary`                       | junit 时最后一条 Tests run 行   |
| `war.exists/mtime`                    | war 产物状态                  |
| `port_18080_open_after`               | 执行后 18080 是否仍被占用          |
| `log_file`                            | 完整日志路径                    |

### 9.4 主会话复核步骤

1. 看 stdout 摘要（exit、pytest 汇总、18080 状态）。

2. 读 `latest.json` 核对退出码与用例数；不采信任何口头汇报。

3. 失败时只读日志尾部（约 60 行）或按 `FAILED` 关键词定位，禁止整份日志灌入上下文：

   ```powershell
   Get-Content -Encoding UTF8 -Tail 60 "d:\javaproject\VideoPlatform\TVhomework1\.stage8-target\test-reports\run-<时间戳>.log"
   ```

4. 确认 `port_18080_open_after=false`；若为 true，先 `python tools\run_tests.py stop`，无法停止时把 PID/端口信息交给用户人工处理。

### 9.5 维护约定

- 修改 `run_tests_report.py` 后至少执行 `python -m py_compile tools\run_tests_report.py` 并跑一次 `all`。

- 修改 `run_tests.py` 的输出或退出码行为时，同步检查本脚本的解析字段。

