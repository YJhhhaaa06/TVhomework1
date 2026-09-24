# 日志规范（LOG_CONVENTION）

> 何时读：**新增 / 修改任何日志调用点**时（决定"用哪个级别、该不该记"）；判断"测试日志为什么进了生产目录 / 找不到日志"时。
> 定位：**日志级别与粒度的唯一活文档**（2026-09-22 建立——在此之前，级别判据只活在早期 commit `6446b7d` 的 message 里，常青 / 说明书均无承载）。实现与装配见 `常青/CURRENT_ARCHITECTURE.md` 3.12~3.15；测试链路与断言写法见 `说明书/TEST_AUTOMATION.md` §4.5。
> 纪律：本档**只定判据，不改实现**。与本档不符的既有代码 = **待治理项**（清单见 `目标与任务/NEXT_CYCLE_NEEDS.md` R-01），不因本档建立而自动合规。

***

## 一、主流做法（2026-09-22 调研，本档判据的来源）

### 1.1 级别的通行语义（SLF4J / Logback / Log4j2 生态的事实标准）

| 级别 | 通行判据（主流原文表述） | 生产默认 |
| ---- | ---- | ---- |
| ERROR | **出事了，需要人介入 / 告警**：`something broke — needs attention now` | 开，且作为**告警源** |
| WARN | **异常但已被处理 / 可恢复**：`unexpected but handled — investigate soon`（重试成功、走了降级、废弃用法） | 开；**聚合看，不单条告警** |
| INFO | **业务事件与生命周期**：`what happened`（服务启停、用户动作完成、状态迁移）；**应当稀疏** | 开（根级别默认） |
| DEBUG | 开发诊断：`how it happened`（SQL 参数、缓存命中/miss、分支走向） | 关（排障时**按包**临时开） |
| TRACE | 极细粒度（循环体内、逐条请求体） | 关（生产几乎不开） |

> 本项目用 **JUL**（零依赖），级别名映射：ERROR → `SEVERE`、WARN → `WARNING`、DEBUG → `FINE`。
> 版本：以上条目为社区通行表述的整理，来源 = SLF4J / Logback / Log4j2 生态惯用判据（"needs attention now" / "unexpected but handled" 等）、12-Factor App 的 Logs 一节、Logback 的配置发现顺序（`logback-test.xml` > `logback.xml`）。

**被反复强调的四条纪律**：

1. **ERROR 必须可行动（actionable）**："可预期的业务拒绝"（参数校验失败、未登录 401、资源不存在 404、重复提交）**不得**记 ERROR —— 它们是**正常业务流**，主流会显式标注为反模式（`Flag: ERROR for expected exceptions`）。
2. **一次失败只记一条**：`catch` 里记完**又 `throw`** 属反模式（同一失败产生两条日志）——"log **or** rethrow, not both"；正确姿势 = **在最终处理点记**（或"上抛则不记，由最外层统一记"）。
3. **绝不记敏感数据**（密码 / token / PII / 完整请求体），结构化（key=value 或 JSON）+ **关联 ID**（traceId / MDC）+ 异常对象作为**最后一个参数**（保住堆栈）。
4. **INFO 要稀疏**：最常见的日志事故是"什么都记 INFO"→ 日志被淹没；"每请求一行"这类应由**基础设施层**（访问日志）承担，而非业务代码逐条打点。

### 1.2 测试日志与生产日志：主流的四种区分手段

| # | 手段 | 做法 | 评价 |
| -- | ---- | ---- | ---- |
| ① | **落点物理隔离**（首选） | 测试写独立目录（CI 产物目录），生产写部署目录 | 最彻底、零歧义；前提是"配置可注入" |
| ② | **配置覆盖**（测试专属配置） | `logback-test.xml` 放 `src/test/resources`——优先级**高于**生产配置，且不打进产物 | 测试与生产配置完全分离；依赖框架的 classpath 优先机制 |
| ③ | **级别差异** | 测试更细（本包 DEBUG）、框架噪音压到 WARN、**根级别保持 INFO**（"切勿全局 DEBUG，否则海量框架日志反淹没有用信息"） | 降噪 + 便于复现失败 |
| ④ | **环境标识** | 文件名/目录带 env，或结构化字段 `env=test` | 汇聚到同一平台（ELK/Datadog）时便于过滤；**单机场景冗余** |

> **共识**："区分"的重点**不是给日志打 `test` 标记**，而是**测试日志别写进生产落点、别触发生产告警**。日志被视为**事件流**（12-Factor）：应用只管往"出口"写，归档 / 路由 / 保留由运行环境负责——这与本项目"输出端只取自配置、目录由 `log.file` 锚定"的设计同源。

***

## 二、本项目的现行机制（已落地，勿重复造）

| 机制 | 现状 |
| ---- | ---- |
| **配置覆盖链** | `AppConfig`：**环境变量**（`log.level` → `LOG_LEVEL`，点转下划线大写；`log.file` 另有 **`LOG_PATH` 别名**）→ **`-Dkey=value`** 系统属性 → `app.properties`。**覆盖无需改代码** |
| **输出端** | 四路：`system.log`（阈值 `log.level`，默认 INFO）/ `error.log`（`log.error.level`，默认 SEVERE）/ `access.log`（**每请求一行**，阈值固定 INFO，专属 logger `access`）/ `audit.log`（**审计留痕**，阈值固定 INFO，专属 logger `audit`，T8） |
| **落点口径** | `log.file` 的**父目录 = 日志目录**，其余输出端相对路径只取文件名落同目录 → **注入 `LOG_PATH` 一处即全部换目录** |
| **测试隔离**（三种运行互不污染） | ① JUnit（`python tools\tv.py test junit`）→ `.stage8-target/test-logs/`（`pom.xml` surefire 注入 `LOG_PATH`）；② e2e（`test all`）→ `.stage8-target/tomcat-test-18080/logs/`（`tools/run_tests.py` 注入）；③ 生产 / 本地直跑 → `logs/`（`app.properties` 相对路径，按 cwd 解析） |
| **关联** | 日志行 `req=`（`LogContext`；**非请求线程整段不输出**）+ 访问日志行 `method= path= userId= code= cost= slow=` |
| **轮转** | JUL 原生 `FileHandler(pattern, limit, count, append)`：`<名>.<N>`，N=0 为当前写入文件、N 越大越旧；`log.maxBytes<=0` = 不轮转 |
| **存量分布**（2026-09-23 **T11 两批落地后**实测） | **159 个调用点 / 24 个持 `LOGGER` 的类**：SEVERE **63**（**T11 新增 19 处**，两批——第 1 次提交 11 处：事务模板包装点 1 + 改名级联包装点 1 + 评论链包装点 7 + 登录链包装点 2；第 2 次提交 8 处同族业务 wrap 缺口：`CommentService` 2 / `UserService.isAdmin` 1 / `FollowService.loadUserList` 1 / `CouponService` 4；全部在 catch 内且承担"唯一带堆栈"）+ WARNING **84**（79 处在 catch 内 = 78 处降级·自愈 + **T11 新增 1 处**（`CommentCache.ensureRootsWindow` 外层按异常类型拆 catch 后的 Redis 侧持栈行）+ 5 处非 catch 状态/统计）+ INFO **12**（基础设施 5 + T9 业务里程碑 7；T11 未改）+ FINE **0**。T6 复批结论见 TASKS T6 执行回写；T7 的双栈收口与 T11 的定栈口径见 3.1 附加纪律 2 与 3.4-③。**T8 增补**：审计记录器 `util/AuditLog` 的 INFO 写向 **audit 专属 logger（独立输出端）**、不进 `system.log` → 上面的分布不含它（见 3.5）。**T12 增补（2026-09-24）**：`UserService` 三个敏感变更方法**按异常类型拆 catch** → 调用点 159 → **162**、SEVERE 63 → **60**、WARNING 84 → **87**（400/401/409 由"SEVERE + 栈"改记"WARNING 无栈"，另 3 处真失败兜底分支保持 SEVERE + 栈；级别语义修正、无新增能力） |

***

## 三、标准（本项目口径，2026-09-22 立）

### 3.1 级别判据（四条，按序判定）

1. **这次操作失败了吗？** 没失败 → 走 ③④。
2. 失败了 → **需要人介入吗 / 对外可用性受损了吗**？（**判据不是"有没有上抛"，而是"要不要人管"**）
   - **需要人介入 → `SEVERE`（ERROR）**，含两类：① **本次请求 / 任务失败**（未被吸收，最终上抛 500 / 任务中止）；② **基础设施与进程级故障**（MySQL 驱动加载、连接池初始化、启动期装配、Bean 生命周期、后台任务连续失败）——**这类即使被 `catch` 吞掉、不再上抛、进程还活着，也保持 `SEVERE`**：它对**之后每个请求**都成立，必须有人管。
   - **不需要人介入（异常已被自动吸收，对外可用性未受损）→ `WARNING`**：缓存降级、重试成功、切备用路径、丢弃非关键数据。
3. 没失败，但属**业务里程碑 / 状态迁移 / 敏感变更**（服务启停、熔断状态迁移、登录与注册成功、内容发布、管理端与账号敏感操作）→ **`INFO`**。
4. 其余开发诊断信息 → **`FINE`（DEBUG）**，默认不输出。

**附加纪律（采纳主流共识）**：

- **`SEVERE` 必须可行动**：可预期的业务拒绝（校验失败 / 401 / 404 / 409 重复）**不得**记 `SEVERE` —— 不记，或按 ③ 记 `INFO`。**本项目落点（T12 落地，2026-09-24）**：可预期拒绝由**结论行**承载——出口 `ExceptionFilter` 的 `BusinessException` 分支记 `WARNING`（带 `code=` / `msg=`）；**源头**若要补上层拿不到的业务标识（如 `userId`）→ 同样 `WARNING` 且**不带栈**（它是结论行、不是"包装点"，沿用附加纪律 2 的"结论行不带栈"）；**真失败**由同方法兜底的 `catch (BusinessException e)` 记 `SEVERE` + 堆栈兜住——"同一失败只有一条带堆栈记录"约束的是**真失败链**，可预期拒绝全链无栈属预期（正常业务流，堆栈无行动价值）。实现形态 = **按异常类型拆 catch**（`catch (ParamException | PasswordIncorrectException | ConflictException e)` → `WARNING` 无栈 ＋ 原 `catch (BusinessException e)` → `SEVERE` 带栈兜底），**不得**用 `instanceof` 分流（已否决）；典范 = `UserService` 的 `changePassword` / `changeUserName` / `changePhone`。
- **一次失败只允许一条"带堆栈"的记录**：禁止"记完又抛"让上层把**同一堆栈**再记一遍。默认由**最终处理点**记（Web 线程的最外层 = `ExceptionFilter`）；**例外允许原地记**——当该处能提供**最终处理点拿不到的信息**（脱敏后的业务标识、外部返回码、重试次数、任务/线程上下文）时，可在原地记，但**上层只记转译后的结论（不带堆栈）**。**"哪一层持栈"按链定，判据 = 安全门禁**：去任一侧堆栈时，另一侧必须**必有一条带堆栈记录**覆盖该链的**全部路径**（改动前逐点核实来源，不得只看"正常路径"）。**实测口径（2026-09-23 T11 定案，取代 T7 的"按链定栈"临时口径）**：**"包装点即源头"**——把异常**包装**成 `DatabaseException` / `ServerException` 的那一处记 `SEVERE` + **堆栈**；下游的**吸收点 / 结论行 / 上下文行 一律只记结论与业务标识、不带堆栈**。推论三条：① **业务链** = 业务层原地记 `SEVERE`（带业务标识 + 堆栈），`ExceptionFilter` 的 `BusinessException` 分支只记结论行（`catch (Exception)` 的"未处理异常"才带堆栈、只被非业务异常命中）；② **事务基础设施异常**（获取连接 / `setAutoCommit` / `commit` 失败）= `TransactionTemplate` 的 `catch (SQLException)` 记 `SEVERE` + 堆栈（**T11 新增**；`catch (BusinessException)` / `catch (RuntimeException)` 分支**不记**——前者已由业务层持栈，后者最终由 `ExceptionFilter` 的"未处理异常"或缓存吸收点带栈）；③ **内容装载链 / 评论树链 / 关注点赞回填链** = 各自的**包装点持栈**（`ContentCache` 的 `catch (Exception)`、`CommentCache` 各 `catch (SQLException)`、`FollowCache`·`LikeCacheService` 的 loader `catch (SQLException)`），吸收点（`CacheAside` 4 处 / `ContentCache` 写路径与启动装载 / `CommentCache` 各 `catch (DatabaseException)` / `SetCache`·`ZSetCache` 回填 catch）与之配套**去栈**——**T7 登记的关注/点赞回填链"双栈残余"由此收口**。**禁止两处都带堆栈**。**两类例外**：① **包装点自身可以是 `WARNING`**——"级别"与"是否持栈"是两件事
（如 `ContentCache` 的装载异常包装点按 §3.1-② 判为降级型 `WARNING`，但堆栈仍记在那里）；② **"吸收点即该链唯一捕获点"必须持栈**
（吞掉型：`CommentCache` 的"楼中楼懒载异常"与"评论所属内容/主楼查询失败"、`ensureRootsWindow` 外层非数据库分支）——不适用"吸收点不带栈"。
**存量未合规（2026-09-23 已全部收口）**：8 处"包装成 `DatabaseException`/`ServerException` 但不记日志"的业务 wrap 点（`CommentService` 2 / `UserService.isAdmin` 1 /
`FollowService.loadUserList` 1 / `CouponService` 4）已随 **T11 第 2 次提交**补 `SEVERE` + 堆栈 → 本档判据下**无存量未合规点**；
同族"不可达分支"（`catch (Exception)` 紧跟 `catch (RuntimeException) { throw e; }`，全仓 9 处）按"实际不可达"**不补**，明细见 `目标与任务/NEXT_CYCLE_TASKS.md` T11 执行回写第 2 次提交块。
- **非 Web 线程必须自设"根捕获"**：`ExceptionFilter` 只覆盖 Web 线程；将来引入进程内线程池 / 定时任务后，**任务体最外层必须有 `try/catch` 记 `SEVERE`**（否则异常静默消失、无人知晓），并在提交前 `LogContext.wrap(task)`（见一⑦与 3.3）。
- **`SEVERE` 与"是否吞掉"无关**（判据见 ② 第 ② 类）：存量 **9 处"吞掉型 SEVERE"**（MySQL 驱动加载 / 连接池初始化 / Bean `destroy`·`shutdown` / `ExceptionFilter` 自身 / 索引重建查询）**按"是否需要人介入"逐点判**——如驱动加载、连接池初始化失败 = 进程残废 → **保持 `SEVERE`**；关机期 `destroy`·`shutdown` 失败影响面小 → 可降 `WARNING`；索引重建失败有 DB 兜底 → 按可用性定。
- **调试日志的写法（JUL 特性，与 SLF4J 不同）**：JUL **没有** `{}` 占位符——`LOGGER.fine("x=" + x)` 的**拼接总会发生**，即使该级别没开。故：① 拼接只是简单字段（现状 2 处 `fine` 即此类）→ 直接写即可；② 拼接昂贵（调 `toString()`、组装集合、序列化）→ 用 **`LOGGER.log(Level.FINE, () -> "…" + x)`**（JDK 8+ 的 `Supplier` 重载，本项目 JDK 25 可用）或 `if (LOGGER.isLoggable(Level.FINE))` 守卫。当前全仓 `isLoggable` / `Supplier` 用法 = **0**；本条是对**新写调用点**的要求。
- **级别不得"跨语义借用"**：`log.level` 是**运维开关**、不是判据；"因为现在 INFO 看不到"不能成为升级别的理由（需要看 → 临时调 `log.level`）。
- **绝不记**：密码 / token 完整值 / 手机号明文（要记时必须经**唯一出口** `StringUtil.maskForLog`，见 3.7）/ 请求体（D7 口径）。
- **异常堆栈**：跟消息输出（本项目 `LogFormatter` 已实现），不另起一段、不吞。

### 3.2 粒度标准（记什么 / 不记什么）

- **必记**：① 每次"最终失败"（`SEVERE`）；② 每次"降级 / 自愈 / 熔断切换"（`WARNING`——**保留现状**，这 48 处是本项目排障含金量最高的日志）；③ 业务里程碑与状态迁移（`INFO`——**T9 已补 7 处**：登录成功、用户注册成功、内容发布（视频 / 动态）、作者删除作品、关注、取关；**逐条判定的同批结论 = 判不记**：点赞·取消点赞（高频、低追溯价值）、评论发表·删除（高频，失败已由 `SEVERE` 承载）、作品编辑类 `update`/`mediaDelete`/`commentEnabled`/`replaceMedia`（常规写操作）、管理端与用户敏感变更（属 ⑤ 审计）；判定记录见 `目标与任务/NEXT_CYCLE_TASKS.md` T9 执行回写）；④ **每请求一行**访问日志（已落地，属基础设施层）；⑤ **审计留痕**（管理端写操作 + 用户敏感变更的**成功路径**，T8 已落地，走独立输出端 `audit.log`——口径见 **3.5**，不占 `system.log` 的 INFO 配额）。
- **不记**：常规成功读操作的逐条日志；循环体内逐条 `INFO`；框架 / 第三方噪音（根级别保持 INFO）；任何敏感值。
- **暂不引入**：WARNING 采样 / 限流（78 处中 48 处是降级路径、含金量高）——**先治级别语义，再谈限流**；也不新增"严重等级"命名（沿用 JUL 五级，避免造第二套词汇）。

### 3.3 测试日志与生产日志的区分（本项目口径）

- **主手段 = 落点物理隔离（手段①，已落地，不改）**：三种运行各自目录（见二节表），互不污染；配套断言 = "生产 `logs/` 与 CI 产物不互写"（`src/test/python/test_log_outputs.py` / `test_access_log.py` 已有）。
- **次手段 = 级别差异（手段③，机制已在、按需用）**：需要更细输出时用 `LOG_LEVEL=FINE`（或 `-Dlog.level=FINE`）**只作用于本次运行**，零代码改动；**不把根级别长期调到 FINE**（主流同款提醒：全局 DEBUG 会海量淹没）。
  - ⚠️ **取值必须是 JUL 原生名**：`ALL` / `CONFIG` / `FINE` / `FINER` / `FINEST` / `INFO` / `SEVERE` / `WARNING`（`OFF`）。**不要传 SLF4J 名**（`DEBUG` / `ERROR` / `WARN` / `TRACE`）——实测 `LogUtil.parseLevel`（`LogUtil.java:168`）遇非法名**不会崩，但会静默回退到该输出端默认级别**（`system` → INFO、`error` → SEVERE），且只在启动期记一条 `WARNING` → 极易被误判为"调了没生效"。
- **明确不做（手段④，本项目不采纳）**：不在日志行加 `env=test|prod` 字段——落点已可区分，加字段是冗余；若将来接入集中式日志平台，再作为独立决策评估。
- **禁止形态**：① 测试伪造的异常 / 降级堆栈出现在生产 `logs/`（历史痛点 N5）；② `System.out/err` 替代 logger（当前唯一例外 = `LogFormatter` 内部写流，属实现细节，非日志调用点）。
- **保留期不必分环境**：本项目单机、按大小轮转（`log.maxBytes` / `log.fileCount`）已够；测试目录随 `.stage8-target` 清理，无需独立归档策略（与生产"归档隔离"的差异在此被"目录即环境"吸收）。

### 3.4 与既有代码的关系

本档自 2026-09-22 起对**新增与修改**的调用点生效；存量调用点**不自动合规**。真偏差面（2026-09-22 量化 + 外部评审后收敛为三条，**均已有任务落地**）：**① 9 处"吞掉型 SEVERE"**（按 3.1-② 的"是否需要人介入"逐点判，**不是一律降级**；**T6 已复批，调用点 134 → 132**）、**② 成功路径与关键状态变更的 `INFO` 补点**（对齐 3.2-必记③，与审计日志 R-02 同源；**T9 已落地 7 处**，见 3.2-必记③与 3.6）、**③ 同一失败的"双堆栈"**（**T11 已整体收口，2026-09-23**）：① **业务链** 本即"源头带堆栈 + `ExceptionFilter` 结论行"；② **事务基础设施异常** 改由 `TransactionTemplate` 包装点持栈（**新增源头**，同时补齐"非缓存业务路径的基础设施异常全链无栈"这一既存缺口）；③ **内容装载链** = DAO 级 `SQLException` / 基础设施失败由 `TransactionTemplate` 持栈，逃出模板的非业务异常由 `ContentCache` 的 `catch (Exception)`（`WARNING`）持栈；吸收点（`CacheAside` 4 处 + 写路径 2 处 + 启动装载 1 处）全部去栈；④ **评论树链** 七个 `catch (SQLException)` 包装点补 `SEVERE` + 堆栈、七个吸收点去栈（含 `ensureRootsWindow` 外层按异常类型拆分——数据库侧去栈、Redis 侧持栈）；⑤ **关注/点赞 best-effort 回填链**（T7 残余）= loader 源头持栈 + 回填吸收点去栈 → **残余关闭**。逐点清单与"不因补点新增双栈"的逐点核实表见 `目标与任务/NEXT_CYCLE_TASKS.md` T11 执行回写（含未覆盖边界）。执行记录见 `目标与任务/NEXT_CYCLE_TASKS.md` T6 / T7 / T11 执行回写。

***

### 3.5 审计留痕口径（T8 落地）

- **记什么**：**管理端写操作** 4 个点（`/api/admin/content/hide`、`/content/unhide`、`/media/restore`、`/comment/delete`）+ **用户侧敏感变更** 3 个点（`UserService.changePassword` / `changeUserName` / `changePhone`）的**成功路径**。
- **只记成功**：失败由既有 `SEVERE` 记录承载（源头记录 + `ExceptionFilter` 结论行），审计**不重复记**——避免"同一失败两条记录"（3.1 附加纪律 2）。
- **行形态**（复用 `LogFormatter` 前缀；时间与请求关联不在 msg 内重复）：
  `ts=… level=INFO logger=audit req=… msg=action=<操作名> operatorId=<id> target=<对象:值> result=success`
  - 字段 = 操作者（`operatorId`）· 操作（`action`）· 对象（`target`）· 结果（`result`）· 时间（前缀 `ts=`，3 位毫秒 + 带冒号时区）· 请求关联（前缀 `req=`，同线程自动带，可与 access/system 行串联）。
  - **操作名用稳定字面量**（管理端 `admin.<域>.<动作>`、用户侧 `user.<方法语义>`）——**不拿 `getPathInfo()` 拼**：裸 action（`/hide`）会丢域信息，且名字随 URL 漂移；URL 本身受"不擅改 `@WebServlet`"约束，二者不会分叉。
  - 对象只放**标识**（`contentId:42` / `mediaId:7` / `commentId:9` / `userId:13`），**不放变更后的值**（手机号属 PII、用户名无记录必要）；请求体 / query 串 / 密码 / token 一律不落盘。
- **输出端**：专属 logger `audit`（`useParentHandlers=false`）→ 只落 `audit.log`、**不进 `system.log`**；阈值**固定 INFO**（不取 `log.level`）——审计是合规留痕，不得被运维开关静默；轮转复用 `log.maxBytes` / `log.fileCount`（审计记录稀疏，同容量远超需求）。
- **写失败不得影响业务**：记录器 `util/AuditLog` 吞掉写入异常并降级（同"缓存失败不导致业务失败"）。注意 JUL 的 `Logger.log` **不对 handler 异常兜底**（JDK 25 源码实测）→ 该 try/catch 是承重的，不是摆设。
- **写在哪一层**：管理端在 **controller**（操作者 = `request.getAttribute("userId")`——`LoginFilter` 已放入、`AuthFilter` 对 `/api/admin` 已保证非空，**零签名改动**）；用户侧在 **service**（操作者 = 方法入参 `userId`，该层无 request 可取）。**注**：`changePhone` 当前**无 HTTP 入口**（`LoginController` 未暴露），其审计点仅由 JUnit 覆盖。
- **测试口径**：装配层与写失败守卫归 JUnit（`AuditLogTest` / `LogUtilTest`）；文件级留痕与"`audit.log` 与 `system.log`/`access.log` 互不污染"归 pytest（`src/test/python/test_audit_log.py`，定位指纹 = 动作三元组 + 全文件计数 delta）。

***

### 3.6 业务里程碑 INFO 补点口径（T9 落地）

- **记什么**（`system.log`，业务类 logger，请求线程内自动带 `req=`；**不新建输出端**）：

| 里程碑 | 记录点（layer） | 行 msg 形态 |
| ---- | ---- | ---- |
| 登录成功 | `UserService#doLogin`（三条 `login` 重载的唯一收口） | `登录成功, userId=<id>` |
| 用户注册成功 | `UserService#registerAsUser`（事务提交后） | `用户注册成功, userId=<id>` |
| 内容发布（视频 / 动态） | `ContentService#addVideo` / `#addPost`（事务提交后、缓存同步前） | `添加视频成功, contentId=<id>, userId=<id>` / `添加动态成功, …` |
| 作者删除作品 | `ContentService#deleteContent`（事务提交后、缓存同步前） | `删除内容成功, contentId=<id>, userId=<id>` |
| 关注 / 取关 | `FollowService#follow` / `#unfollow`（事务提交后、缓存双写前） | `关注成功, userId=<id>, followedUserId=<id>` / `取关成功, …` |

- **判定口径（三条，逐条都要能对上 3.1-③）**：① 是**业务里程碑或状态迁移**（账号创建 / 会话建立 / 内容产生或消亡 / 关系变更）；② **不在 3.5 的审计范围**（审计 = 管理端写操作 + 账号敏感变更 → `audit.log`，**零重叠**）；③ **失败路径不记**——成功里程碑只在"DB 已提交"之后写，异常抛出时该行不可达（失败由既有 `SEVERE` + `ExceptionFilter` 结论行承载）。
- **位置口径**：一律"事务提交后、缓存同步前"——"落库即成功"，缓存写失败另记 `WARNING` 且读自愈，不改本行语义（与 3.5 审计行同款理由）。
- **消息形态**：与既有**失败**文案成对（`添加视频失败, userId=…` ↔ `添加视频成功, contentId=…, userId=…`），便于按前缀检索；只含**标识**（userId / contentId / followedUserId），**不含**账号、token、用户名、手机号。
- **明确不记**（同批评判结论）：点赞·取消点赞（高频、低追溯价值）；评论发表·删除（高频，失败已有 `SEVERE`）；作品编辑类（`content/update`、`mediaDelete`、`commentEnabled`、`upload/replace`——常规写操作，非里程碑）；管理端与账号敏感变更（归 3.5 审计）。
- **断言口径（T9）**：**单测**（`UserServiceTest` / `ContentServiceTest` / `FollowServiceTest`，探针＝`src/test/java/com/itheima/util/LogProbe`）断言"成功路径**恰一条** INFO + 文案 + 失败路径**零** INFO"；**落盘与分流**归 pytest `src/test/python/test_milestone_log.py`（7 点各恰好一条 + 只在 `system.log`、`audit/access/error` 零泄漏 + 无明文手机号不变式）。

***

### 3.7 敏感值脱敏出口（T9 落地）

- **唯一出口 = `util/StringUtil.maskForLog(String field, String value)`**：任何"想写进日志的敏感字段"都必须经它取值，**调用点不得自行截串 / 拼接**（先拼后脱敏等于已落盘）。
- **形态（fail-closed）**：按**字段类型名 + 值形态**分派；**未登记的字段名、null、空值、形态不符的输入一律返回掩码 `******`** —— 字段名写错或值形态异常只会"多打码"、不会"漏打码"。当前登记：`phone` —— **仅当通过 `phoneCheck`（11 位全数字且首位为 1）**时按 `maskPhone` 口径保留首 3 + 末 4（`138****1234`），其余一律整串掩码（含"长度恰为 11 但非号码"这一边界，JUnit 有断言锁住）。
- **不做什么**（本轮选型结论，否决理由）：① **不新建类**（既有 `StringUtil` 已是该字段实现所在，新建门面会形成"两个手机号脱敏入口"）；② **不做按字段类型分派之外的通用策略表 / 注解式脱敏**（当前全仓需脱敏字段只有 1 种、调用点 1 处，策略表只有一行属过度设计）；③ **不加 `env=` 类装饰字段**（3.3 已明确不做）。
- **扩展方式**：出现第二种敏感字段（邮箱 / 外部 openid / 身份证等）时，在 `StringUtil` 内加一条分派（必要时同族 `maskXxx`）+ 在本节登记，**不新开口子**。
- **自动化门禁**：pytest 全文件不变式——四个应用输出端（`system` / `error` / `access` / `audit`）**任何一行**都不得出现 11 位手机号明文（扫描前先剥掉 `req=<16hex>`：请求 id 天然含 11 位数字串 → 实测 26 行假阳性，不剥会误报）；新增日志点若漏脱敏，本断言会红。

***

## 四、改日志调用点的自检清单（逐条过）

- [ ] 级别按 **3.1** 四条判据判定（不是"照抄邻居"、不是"看心情"）
- [ ] 没有让**同一堆栈**被记两次（原地记了上下文，上层就不再重复堆栈）
- [ ] 若是**非 Web 线程**（线程池 / 定时任务）：任务体有自设 `try/catch` 根捕获，且提交前 `LogContext.wrap(task)`
- [ ] **不手工 `set/clear` `LogContext`**（清理点唯一 = `AccessLogFilter` 的 `finally`，已由单测断言）；**禁用 `InheritableThreadLocal`**
- [ ] 调试日志若拼接昂贵，用 `Supplier` 重载或 `isLoggable` 守卫（**JUL 无 `{}` 占位符**）
- [ ] `SEVERE` 可行动；可预期的业务拒绝没记成 `SEVERE`
- [ ] 无敏感值（密码 / token / 手机号明文 / 请求体）；**要记敏感字段时经唯一出口 `StringUtil.maskForLog`**（3.7，不得自行截串）
- [ ] 若记的是**业务里程碑**（`INFO`）：对得上 3.6 三条判定（是里程碑 / 不与 3.5 审计重叠 / **失败路径不记**），且位置在"事务提交后、缓存同步前"
- [ ] 需要串联时确认在请求线程内（`req=` 自动带上；异步任务先 `LogContext.wrap`）
- [ ] 新增日志**不改变对外行为**（URL / 参数 / 响应体 / 业务语义一律不动）
