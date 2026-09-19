# 下一周期需求与痛点（NEEDS）

> **已归档（2026-09-19）**：本周期（第六期「杂务/准备周期」，260919-prep-cleanup，分支 `prep/next-phase`，T1~T9 全部完成，`docs(prep-01)`~`chore(prep-09)`）已结束，本文档随周期归档至 `archive/目标与任务/260919-prep-cleanup/`；未完成事项（R-01/R-02 本体、R-04 拍板点、留池 U-07/U-11/U-14~U-20）已结转至新 `目标与任务/NEXT_CYCLE_NEEDS.md`（第七期结转稿）。本文档为静态存档，不再更新。

> 用途：回答"下一周期为什么做这些"——本周期要处理的杂务与痛点、候选任务的优先级映射、以及开工前必须拍板的技术决策。
> 状态：**杂务/准备周期（2026-09-18 立项，分支 `prep/next-phase`）**——本周期**不做正式功能方向、不立业务主线**，只处理代码仓库的杂务与铺垫清理，为后续 feed 流改造 / 日志体系改造 / 新功能（届时各开新分支）腾出干净地基。**用户 2026-09-18 已给出方向想法**（见 4.1 N11~N14：评论/粉丝分页、常青文档瘦身、commit message 规范、测试目录本地化），连同首轮探查杂务 N1~N10 一并待评审；**评审与拆任务已于 2026-09-18 完成（R-03 拍板，T1~T9 见任务清单）**，执行进度以 `NEXT_CYCLE_TASKS.md` 为准。
> **变更纪律（2026-09-18 用户拍板）**：本档不设"六、变更记录"节（模板已同步移除）——变更以 git 提交历史为准，不再维护文档级变更记录。
> 术语约定：**周期 > 任务**。本文档只回答 Why（需求与决策），How（拆任务）在任务清单文档。

***

## 一、结转：仍有效的通用约定（来自已归档周期，随周期继续生效）

| 编号 | 约定 | 说明 |
| ---- | ---- | ---- |
| C-1 | 双文档结构 | 本文档（需求与痛点，决策唯一源）+ 任务清单（执行细节） |
| C-2 | 一任务一窗口一 commit | 默认期望，允许例外需标注；commit message 强制带任务编号 |
| C-3 | commit 语义闭环 | 代码 + 常青文档更新 + 任务清单勾选进**同一 commit** |

> 另有若干"延续性约定"不单独编号，随周期生效（原文见 `archive/目标与任务/260918-cache-ending/NEXT_CYCLE_NEEDS.md` 一节，逐条核对仍有效）：
> ① **红线措辞约定**：红线只列"明显越界"的项，作用是**防跑偏、不把执行 Agent 限制死**；若某条红线会阻碍正确做法——**先说明理由申请调整**，获批准后动手，未获批准则维持原红线；
> ② **编号引用约定**：禁裸编号引用已归档周期元素，引用一律写成 `<周期>/<编号>`（如 `260918/R-02`）；裸编号仅指本文档内部定义的元素；
> ③ **技术红线**：禁 Spring/SpringBoot/MyBatis；不擅改 `@WebServlet` URL / web.xml / IoC 扫描；不为实现便利改业务逻辑语义；
> ④ **不引入 MQ**：异步（若需）用进程内线程池（`ExecutorService`），不引入 RabbitMQ/Kafka/RocketMQ；
> ⑤ **脚本规范**：脚本一律 Python；临时一次性脚本放 `temp_script/`，长期复用/自动化放 `tools/`；
> ⑥ **DDL 备份**：出现表结构改动，执行前先备份库结构与建表语句到 `.docs/DBbackups/`；
> ⑦ **质疑协议（G11）**：执行 Agent 对 Why/How 层有质疑权、亦有报告义务——四时点触发 × L1~L4 分级动作，质疑记录回写 4.0，**裁决权永远在用户**。

***

## 二、结转：未完成的需求（260918-cache-ending 归档时未关闭）

> **编号体系（本档内部）**：`C-#` 通用约定；**`R-##` 待评审事项**（本节与三节连续编号）；`N#` 本周期新探查候选痛点/杂务（4.1）；`T#` 任务（见任务清单文档）。引用**已归档周期**的元素仍写 `<周期>/<编号>`；每行"来源"列保留原编号以便追溯。

| 编号 | 事项 | 类别 | 来源（归档周期） | 状态 | 说明 |
| ---- | ---- | ---- | ---- | ---- | ---- |
| R-01 | 索引全量读 + 拷贝 shuffle 本体 | 需求（性能） | `260918/R-02` | 明确保留 | `LRANGE 0 -1` 全量读保留（推荐 shuffle 对外语义不变），`getRecommendByFilter` 读面每次全量去重 + shuffle 只取 12 条，数据量大时 O(n)；待 feed 改造或真流量触发再评估 |
| R-02 | content ↔ comment 包层循环依赖 | 观察（包架构） | `260918/R-04`（= `UNPLANNED_ISSUES.md` U-07） | 待定 | content 域共享组件被 comment 域引用、content 又引用 comment 的 `CommentService`；**非 IoC/Bean 环**仅包架构不纯净；多周期复查仍在，未随任何任务落地 |

> **留池不编号项（`UNPLANNED_ISSUES.md`，本周期不承诺排期，评审时一并过）**：
> U-11 Redis 停机 `/start` 返回空推荐（对外行为变更未拍板；DB 兜底与否 = 行为变更）— 维持留池；
> **U-12 follow 大集全量 `SMEMBERS` 装载无分页 / U-13 评论树全量装载重排——用户 2026-09-18 明确倾向在本次杂务周期解决（"评论、粉丝没有分页加载而是全量加载"），已转入本档 4.1 N11**（范围与分页形态拆任务时细化）；
> **U-14 同型 N2 未治点 2 处**（`ContentService.search` / `FollowService.getFollowingList/getFollowerList` 事务回调内调缓存读）——与 260918/T3 同款改造形态已明确，**改动面小、属既有代码债**，是否在本杂务周期顺手收官由用户评审（见 4.1 备注）。

> **已完成、不结转**（供对照）：260918 周期 T1~T3 全部完成（authorName 冗余同步 / 批量装载合并 / Feed+Profile 缓存读移出事务，`refactor(cache-01)`~`(03)`）——详见 `archive/目标与任务/260918-cache-ending/`。

***

## 三、结转：未拍板的决策（进任务清单前须拍板）

> 编号接续二（`R-##` 连续）；本表是"必须拍板才能开工"的决策项，二表是"待消化的事实项"。

| 编号 | 待拍板事项 | 来源（归档周期） | 当前状态 | 说明 |
| ---- | ---- | ---- | ---- | ---- |
| R-03 | **本周期方向（杂务周期做什么）** | 本档新增 | **已拍板（2026-09-18）**：杂务周期范围 = T1~T9（见 4.3）——第一批 T1~T6 基座/卫生 + 第二批 T7~T9 用户点名主菜；U-14 不随本周期，N10 转留池（U-15） | 已拆任务见 `NEXT_CYCLE_TASKS.md`；分页形态（T7 缓存载体 / T8 主楼分页）与 CountRepairTool 去留（R-05）属开工前拍板点 |
| R-04 | content ↔ comment 包层环（= R-02）怎么处置 | `260918/R-04` | 待定 | 多周期未动；包架构纯净化是否本周期做（若不做继续留池） |
| R-05 | `CountRepairTool` 去留（详情见 4.1 N8） | 本档新增 4.1 N8 | **已拍板（2026-09-19）**：**A 案删除**（否决 B 迁测试源码树 / C 保留+改造，决策段见 4.0） | 三类候选：保留（现状，javadoc 已有说明）/ 移出主代码 / 删除；删除理由：零引用 + 能力被 `tools/check_integrity.py` 严格超集覆盖 + 随 war 出货 + 拉低覆盖率，且**反转 `260902/T4` 原保留决议**（已在 4.0 显式记录） |

***

## 四、本周期痛点与目标方案

> 结构：**4.1 杂务清单**（本周期探查发现，**待评审纳入**）→ **4.2 候选方向与拍板结论** → **4.3 本周期范围与反面清单**；**4.0 已回写技术决策与质疑记录**（执行中拍板/质疑追加）。

### 4.0 已回写技术决策与质疑记录（执行中拍板/质疑，按任务追加）

> T1~T5 窗口无 Why 层新决策、无疑似被覆盖的需求（实现细节与 L1 记录见 `NEXT_CYCLE_TASKS.md` 各任务"执行回写"）。

**决策 R-05（2026-09-19，T6 窗口，用户拍板）**：`CountRepairTool` 处置 = **A 案删除**。

- **依据**：① 全仓**零生产引用、零测试引用**（仅 `tools/check_integrity.py` L110/L137 注释提及）；② 能力被 `check_integrity.py` 的 `COUNT_CHECKS`/`FIX_STATEMENTS` **严格超集**覆盖——5 条修复 SQL 逐条等价，且该入口默认 dry-run、`--fix` 才写库、单事务、修后复查、prod 二次确认、报告落盘；③ 实测该类**随 war 出货**（`stage8-target/untitled-1.0-SNAPSHOT.war` 内 `WEB-INF/classes/com/itheima/util/CountRepairTool.class`），jacoco 记 97 指令 / 28 行 / 3 方法 0 覆盖；④ 其唯一独有能力（不依赖 mysql CLI 的 JDBC 直连）恰是"运维入口分裂"本身，价值低于统一入口原则。
- **否决 B（迁 `src/test/java/com/itheima/tools/`）**：虽同样退出 war 与覆盖率分母，但会把"运维工具放测试树"确认为模式（既有 `CouponAdmin` 已硬编码本机库）。
- **否决 C（保留+改造）**：为零引用且已被统一入口覆盖的工具新增 dry-run/环境门禁逻辑与配套测试，与"杂务/准备周期、改动面小"不符，且仍是第二套运维入口。
- **历史关系（显式记录，G11 去向不悬空）**：本决策**反转** `260902/T4` 的"能力整合 + 类头标注（保留）"决议；该周期"唯一允许的 main 运维工具"口径随之作废——能力已由统一入口承接（属**迁移完成**，非废弃）。
- **附带拍板**：LogUtil 兜底口径取**硬口径**——`LogUtil` 内不出现任何 `System.out/err`。
- **已声明的语义变化**（T6 对外可感知变化，均为本次改造的预期结果）：初始化信息改走 `java.util.logging` 后 ① **全部初始化信息受 `log.level` 过滤**——默认 INFO 可见；级别调至 WARNING 及以上时 "successfully load logs" 等 INFO 行不再出现，级别调至 SEVERE 时"log dir 创建失败""非法 log.level 回退 INFO"等 WARNING 行亦不再出现（改造前 `System.out/err` 一律不受级别影响）；② 控制台通道变为 `ConsoleHandler`（**stderr** + `SimpleFormatter` 格式，改造前为 stdout 裸行）；③ 初始化信息**同时落入 `system.log`**（改造前只进 stdout，从不进文件——N7 痛点本身）；④ 初始化时清空 root 既有 handler 的副作用被显式记录（同一 JVM 内容器/第三方 JUL 日志也会路由到本工具的 Console+File；该行为 HEAD 已存在，非本次新增，仅补文档说明）。


**决策 T7-A / T7-B（2026-09-19，T7 窗口，用户拍板）**：关注/粉丝列表分页的两项核心技术决策。

- **T7-A 分页载体 = A1「缓存有序结构」（Set→ZSet 同构替换，用户拍板）**：`user:following:{userId}` / `user:follower:{userId}` 由 Set 升级为 ZSet（score = 成员自身 id），分页读走 `ZRANGE start stop`、总数走 `ZCARD`（一趟 pipeline 取"该页 + 总数"）。
  - **否决 A2**（缓存保持全量 + Service 层内存切片）：只解决契约、不解决成本——每次请求仍 `SMEMBERS` 全量 + String→Long 装箱，恰好抵消"后端大分页 + 前端预加载"要省的延迟，且没有游标锚点。
  - **否决 A3**（DB `LIMIT/OFFSET` 或 keyset 直查）：能力与 A1 同级且实现更省，但关注/粉丝列表读路径会离开"缓存优先"惯例（hit/miss 两套行为、排序需与 `sortIds` 严格对齐）。
  - **与"后端大分页 + 前端小分页（预加载）"目标的适配**：A1 同时满足 ① 任意连续区间 ② 单次成本与列表总量弱相关（`O(log n + N)`） ③ 游标锚点（后续可加 `ZRANGEBYSCORE`）——对比见 `.docs/temp/T7_PLAN.md` 第 9 节。
  - **类型冲突风险已消除**（用户 2026-09-19 指出 + 本窗口实测）：`AppShutDownListener.contextDestroyed` 调 `MyRedisPool.flushDb()`，停机即清库，部署重启后不存在旧 Set 数据；即便异常残留，`RedisAccess` 把 `JedisDataException` 包装为 `CacheException` → 读路径降级 DB 作答，业务不失败。
- **T7-B 响应结构 = B2**：带 `page`/`pageSize` 任一参数时 `data = {list,total,page,pageSize,totalPages}`（复用 `content.model.dto.PageResult`，与 `/profile` 的 `contentPage` 同风格）；**缺省（两个参数都不传）时 `data` 仍为全量数组**，与改造前逐字节一致（既有 pytest 用例零破坏）。
- **附带口径**：`total` 取同一 ZSet 的 `ZCARD`（与页内容同源，避免与独立计数 key `user:followCount` 的瞬时不一致）；缺省路径**不做切片**——兼容**不能**用"默认 `page=1&pageSize=50`"实现（上限 50 会截断全量），必须在 Controller 显式区分"是否传了分页参数"。
- **留池**：`SMEMBERS` 时代的"热路径全量回传"已随 A1 消除；**miss / 降级路径仍为全量装载**（缓存装载的既有形态，与 R-01 索引全量读同型）→ 登记 `UNPLANNED_ISSUES.md`。

**决策 T8-D / Q1~Q4（2026-09-19，T8 窗口，用户拍板）**：评论列表分页的技术决策。

- **T8-D1 分页形态 = A「主楼分页 + 楼中楼整树」**：切片单位 = **主楼**，每条主楼携带其完整 `children`；切片点在展示层（`ContentService.sliceRoots`），**评论缓存整树结构不变**（`loadCommentTree`/`buildCommentTree` 零改动）。
  - **否决 B**（主楼 + 楼中楼都分页）：违反 T8 红线"楼中楼必须随主评论整体返回"，且会让**请求次数变差**（楼中楼需再发请求）——与用户"减少请求次数"的后续目标正好相反。
  - **否决 C**（缓存窗口读：主楼序列独立键 / DB `LIMIT` + `parent_id IN`）：唯一能治"单次成本与总量正相关"的路，但**违反 T8 红线"不动缓存装载结构"**，属缓存/读路径结构改造，应另立任务（形态备选见 `.docs/temp/T8_PLAN.md` §8.5）。
  - **否决 D**（前端伪分片）：响应体仍全量，不治 N11b/U-13 痛点本身。
- **后续目标对齐（用户 2026-09-19 追加目标："后端大分页 + 前端小分页加载，减少请求次数与用户侧延迟"）**：拆成三条能力 —— ① 大 chunk 拉取、② 锚点续拉、③ 单次成本与总量弱相关。**A 满足 ①②**（`page/pageSize` ≡ 偏移制，后续加 `cursor` 属纯加法），**仅 ③ 未治**（命中路径仍反序列化整树）；而"减少请求次数"由**前端 chunk 预加载**决定、与后端缓存结构无关，"减少延迟"由响应体大小与本地数据即时可用决定——A 均已达成。③ 在本项目量级不显著（1 条评论 ≈200B JSON → 1000 条约 200KB、5000 条约 1MB，ms 级反序列化 vs 一次网络往返），**单内容评论量上万**才值得为 ③ 立项。→ 结论：**A 是通向该目标的最短路径，且是加法路线**（接口契约在将来的窗口读任务里可原样保留）。
- **T8-D2 响应信封 = 复用 content 域 `PageResult<CommentVO>`**：T8 落点就在 content 域（comment 域只提供 VO 转换），**同域复用、零新类、无包层环**；T7 之所以自建 `FollowPageResult` 是因 content↔follow 跨域（记 U-19）。U-19"信封上移公共包"**不在 T8 处理**。
- **T8-D3 缺省兼容 = T7-B2 同款**：Controller 显式判"是否传分页参数"（`hasPagingParams`）——都不传 → `data` 仍为**全量数组**（与改造前逐字节一致）；传任一 → 分页信封。**不可**用"默认 page=1&pageSize=50"实现（上限 50 会截断全量）。
- **Q1 主楼顺序**：保持 `comment_id` **升序**（= 现状，"最早在前"）；改"最新在前"属对外行为变化，本周期不做。
- **Q2 `total` 语义**：= **主楼条数**（`roots.size()`），与"每页 N 条主楼"同源；与详情接口 `commentCount`（含楼中楼总数）口径不同，故 Q3 前端仍显示 `commentCount`。
- **Q3 详情页头部计数**：前端 `detail.js` 的 `评论 (N)` 改用详情接口 `state.content.commentCount`（含楼中楼的总数），不再按"已加载条数"计算（分页后后者会随加载页数增长、与详情统计不一致）。
- **Q4 留池**：命中路径**每次读并反序列化整树**（单个 JSON 值无法按窗口读）→ 登记 **U-20**（与 U-18「follow 装载全量」同型；彻底治本需缓存结构改造，须先放开 T8 红线"不动缓存装载结构"）。
- **附带（后续任务的接口演进约定）**：T8 的 `page/pageSize` 契约在将来做窗口读时**原样保留**；若"大分页"需要单次 >50 主楼，加**评论域自己的上限常量**（不动公共 `parsePageSize` 语义）——与 T7 记录的同一处置思路。

### 4.1 候选杂务（周期探查的代码卫生/铺垫清理，**待评审纳入，尚未拍板**）

> 编号 `N1`~`N#` 为**本档内部编号**。每条给出"如果不改，什么时候会出什么问题"的具体场景。
> **证据须可复核定位**（G11 配套）：文件:行号，不接受纯文字断言——执行窗口动手前复核证据，不成立 → L3 暂停并登记质疑。
> 评审时逐条决定：纳入本周期 / 转 `UNPLANNED_ISSUES.md` 留痕 / 废弃。任一项经评审纳入后升格为正式编号（并入 `R-##` 或任务 `T#`）。

| 编号 | 问题 | 证据（可复核定位） | 不改会怎样（具体场景） |
| ---- | ---- | ---- | ---- |
| N1 | **pom.xml 留存整套 Kotlin 构建残留**：`kotlin.version` 属性、`kotlin-stdlib-jdk8` / `kotlin-test` 依赖、`kotlin-maven-plugin`（含对 `src/main/java` 的 compile/test-compile 配置），但全仓**无任何 .kt 文件**（git 全历史无、磁盘无）；`maven-compiler-plugin` 默认 execution 反而被置 `phase=none` 显式重建 | `pom.xml` L19（`kotlin.version`）、L60-70（kotin 两依赖，kotlin-test scope=test）、L132-160（kotlin-maven-plugin 配置 + compiler 默认执行禁用）；佐证 `.idea/kotlinc.xml` 被追踪 | 构建配置与实际代码严重不符：新增开发者/换环境时误以为项目含 Kotlin 源；无用插件占用构建流程与传递依赖体积；**若未来重排构建链，残留 kotlin 配置会掩盖真实编译路径**（当前 Java 编译实际挂在哪个插件上需回归验证再动） |
| N2 | **`.idea/` IDE 私有目录被 git 追踪**（11 个文件）：`.name` / `misc.xml` / `encodings.xml` / `vcs.xml` / `webContexts.xml` / `dataSources.xml`（含本机 DB 连接信息）/ `db-forest-config.xml` / `kotlinc.xml` / `copilot.data.migration.ask2agent.xml` / `runConfigurations/Tomcat_10_1_54.xml` / `.gitignore`；顶层 `.gitignore` 只逐文件忽略了 modules/jarRepositories/compiler/libraries/* | `git ls-files .idea/`（11 文件）；`.gitignore` L7-14 | IDE 状态文件频繁变动、污染 commit 可视性；`dataSources.xml` 含本机连接串进仓库，若仓库将来公开/多机拉取即泄露本机拓扑；`.idea` 配置因人而异，入库反而造成多机/多 IDE 冲突（**注：若有意共享 Tomcat runConfig，需保留该单独文件并按需取舍**） |
| N3 | **一次性迁移脚本误入 git 追踪**：`temp_script/migrate_comments_to_two_level.py`（2026-08-28 评论楼中楼一次性迁移）被 `git` 追踪，而顶层 `.gitignore` L66 已声明 `/temp_script` 整体忽略（此文件在 ignore 规则生效前入库，历史遗留） | `git ls-files temp_script/` 仅此 1 文件；`.gitignore` L66 | 违反"临时脚本不入库"约定（协议⑤），一次性脚本保留在 git 历史里让后来者误以为是长期工具；其余 verify_*/pressure_* 一次性脚本均在 ignore 下免除追踪，仅此一个例外 |
| N4 | **`RequestParser` 三段落灰旧 `getBody` 实现**（保留 `BufferedReader.readLine` 循环版、`ContentLength` 定长版、`try-with-resources` 版，均为注释代码），现役实现是 L13-17 流式 `getBody` | `src/main/java/com/itheima/controller/RequestParser.java` L19-56 | 注释残留误导后来者（不知哪个是现役实现）；`ContentLength` 版甚至隐藏着定长读取的边界写法，易被误当可选方案抄袭 |
| N5 | **`web.xml` 顶部整段注释掉的旧 `<web-app>` 骨架**（`xmlns=javaee` web-app_4_0 空的声明块） | `src/main/webapp/WEB-INF/web.xml` L1-6 | 死注释；后续编辑 web.xml 时易误解除注释导致 XML 重复声明冲突 |
| N6 | **分页参数解析重复实现**：`FeedController` 与 `ProfileController` 各有一份逐字符相同的 `parsePage`（默认 1）+ `parsePageSize`（默认 10，cap 50），均为私有方法；`BaseServlet`)（基类）与 `BaseServletUtil` 无此公共能力 | `FeedController.java` L32-56；`ProfileController.java` L45-70 | 新增分页接口（feed 改造/新功能）时重复第三份拷贝；两处已出现局部漂移风险（若一处改上限另一处漏改）——趁新功能加接口前收敛到公共处 |
| N7 | **日志工具类自身用 `System.out/err` 直接打印**：`LogUtil` 初始化（建目录失败、加载成功、非法级别 fallback）与全局失败信息不经过 java.util.logging 自身通道 | `src/main/java/com/itheima/util/LogUtil.java` L25/L36/L38/L46 | 与"日志体系改造"预演矛盾：系统里唯一例外把初始化日志打到控制台而非落文件，改造成本最小的一处；且 `successfully load logs` 用 info 口吻但进 stdout、错误反而不进日志文件，调试排查看不到（**2026-09-19 T6 已落地**：初始化改走 logger，先挂 ConsoleHandler 再打日志，硬口径零 System.out/err） |
| N8 | **运维 main 工具类残留主代码且不脱轨**：`CountRepairTool` 是带 `main` 的计数修复 CLI（计数 SQL 与 `tools/check_integrity.py` 语义一致），用 `System.out/err` + `e.printStackTrace()`，**javadoc 已声明"由 check_integrity.py 提供统一入口、本类保留供直连调试/交叉验证"** | `src/main/java/com/itheima/util/CountRepairTool.java` 全文（L18-60 main/repair）；javadoc L10-14 | 主代码里藏着与本项目运维规范重复的裸 CLI，且未过统一工具入口；每年维护读到会疑惑"为什么不直接用 check_integrity"；保留理由已被 javadoc 自述但无评审记录（去留见 R-05）→ **2026-09-19 R-05 拍板删除（已落地）**，能力由 `tools/check_integrity.py --fix` 承接 |
| N9 | **`CURRENT_ARCHITECTURE.md` 头部版本元数据滞后**：头部写"版本：2.23 / 最后更新：2026-09-16"，但更新日志节已记录到 **2.26（2026-09-18）**，2.24~2.26 三条（T1~T3）未体现在头部——**历史复现**：260917 归档记录已留 L1"header 版本号 T1 未按惯例 bump"（`260918/R` 更新日志 2.17 条目） | `.docs/常青/CURRENT_ARCHITECTURE.md` L3-4（头部）vs L847-849（更新日志 2.24~2.26） | 读者按头部判断文档新鲜度会误判滞后三期；同一问题第二次出现说明"更新日志 bump ≠ 头部 bump"是系统性疏漏，可在杂务周期一次性对齐（顺带检查 BUSINESS_FLOW 版本头） |
| N10 | **无统一请求/响应日志**：仅 `ExceptionFilter` 记录异常（WARNING/SEVERE + 堆栈）；`BaseServlet` 基类只做 `init` 注入与 `writeSuccess/writeError`，各 Controller `doGet/doPost` 无入参、无耗时记录——全仓 controller 层仅 `AppShutDownListener` 有 logger | `BaseServlet.java` 全文（无 logger）；`ExceptionFilter.java` L24-35（仅异常日志）；controller 包 grep `logger.info/warning/severe` 仅命中 `AppShutDownListener` | 现网/测试排查全靠异常日志与外部 access log，**无"谁在什么时刻调了哪个接口、耗时多少"的链路痕迹**；属日志体系改造的主干缺失——**2026-09-18 评审：转留池（U-15），摸底放日志体系改造分支立项前，不在本杂务周期排期** |
| N11 | **评论、粉丝列表无分页、全量加载（用户点名，2026-09-18）**：关注/粉丝列表 `getFollowingList`/`getFollowerList` 无分页参数、`SetCache.getMembers` 走 `SMEMBERS` 一键全量回传 + miss 全量 DB 装载（`UNPLANNED_ISSUES.md` U-12）；评论树 `loadCommentTree` 整树从 DB 全量捞出 + 内存 `buildCommentTree` 重排、`getCommentsForContent` 无分页整树渲染（U-13）——两处此前均以"懒加载/分页范畴（接口契约 + DAO 分页 + 展示决策）"转留池，**用户 2026-09-18 明确要在本周期解决** | `FollowController.java` L34-38（无分页参数、全量返回）；`SetCache.getMembers` L112-139；`CommentCache.loadCommentTree` L99-114 + `buildCommentTree` L117-145；`CommentController.java` L82（整树渲染） | 用户量大后关注列表/粉丝列表/评论列表响应体无限增长：关注百万 BOX、评论数千条时单接口拉全量（既有 U-12/U-13 原文：SMEMBERS 全量装箱、整树重排 O(n)）；且不解决则"加载更多"等常见交互无法落地，是 feed 改造之外体验卡点。**2026-09-19：关注/粉丝侧已随 T7 落地**（A1 有序化 + B2 信封；命中路径不再全量回传，miss/降级仍为装载形态 → 留池）；**评论侧（N11b）已随 T8 落地**（D1=A 主楼分页 + 楼中楼整树、缓存整树结构不变；命中路径仍反序列化整树 → 留池 U-20） |
| N12 | **常青文档臃肿**：`CURRENT_ARCHITECTURE.md` 已达 887+ 行，其中"十二、更新日志"横跨 2026-07-23~2026-09-18、单条 commit 记录动辄 400+ 字（把问题/选型/验证/文档细节全部写进常青正文），与 commit message、TASKS 执行回写内容高度重复；`BUSINESS_FLOW.md` 同步有逐 commit 注记叠加 | `.docs/常青/CURRENT_ARCHITECTURE.md` L844-883（更新日志 40+ 条）；`BUSINESS_FLOW.md`（版本 1.0，逐任务注记直接写正文） | 常青文档本意是"地图/快速导航"，被逐 commit 细节掩盖主干结构——新任务读文档成本上升，且每条 commit 都要改多处、文档维护成本翻倍；需要定粒度规范：**常青只留"结构与决策摘要"，不设独立变更记录（用户 2026-09-18 拍板：变更以 git 提交历史为准，N13 规范落地后常青更新日志也按同口径收敛）** |
| N13 | **commit message 无规范、内容臃肿**：当前历史 commit 结构松散——header 长句 + 数百字正文（问题/选型/验证/文档一股脑无分节，如 `refactor(cache-02)`、`refactor(cache-03)` 单条正文 400+ 字），"看似说很多又像什么都没说"；`C-3 commit 语义闭环`只约定"代码+文档+勾选同 commit、message 带任务编号"，未定 message 本身格式 | `git log` 下 `refactor(cache-01)`~`(03)` 等条目（如 `002cb75`、`5610a9d`、`49d9367` 正文无分节）；`archive/目标与任务/260918-cache-ending/NEXT_CYCLE_TASKS.md` 二节（只有"message 强制带任务编号"） | 检索历史困难（scope/主题不统一、要点淹没在长文中）；后续要"按 commit 追溯某期决策/验证结论"成本高；**需定统一规范并回写周期模板**（type(scope) header ≤ 字数 + 正文固定分节如「问题/方案/验证/文档」，可选关联 ISSUES 编号） |
| N14 | **测试必须在沙箱外跑——编译产物/日志/媒体目录都落在沙箱可写区之外**：砂箱可写区 = 仓库根 `D:\javaproject\VideoPlatform\TVhomework1`（及少量用户目录），但 a) 编译产物：`pom` 注释明言"沙箱内 javac 无法枚举 worktree 的 target/ 作 classpath"，`-Dstage8.buildDir` 需指外部可写目录（run_tests 用 stage8-target）；b) 运行/测试日志：落 `tomcat-test-18080\logs\tomcat_stderr.log` 等仓库外路径；c) 媒体上传目录：`context.xml` 硬编码 `D:/data/projects/VideoPlatform/stone`（绝对外部路径），run_tests 的媒体写入被沙箱拦（记于项目记忆）；d) `run_tests.py` 硬编码 `SHUTDOWN_PORT=18005`/`HTTP_PORT`/`BASE_URL=127.0.0.1` | `pom.xml` L20-22（stage8.buildDir 注释）；`src/main/webapp/META-INF/context.xml` L2-8（`D:/data/projects/VideoPlatform/stone`）；`tools/run_tests.py` L68-70；项目记忆"trae 沙箱拦截 run_tests*.py 的 process/port/network" | 每次测试/回归必须交 codex 在沙箱外验证，迭代闭环慢（trae 内改完不能直接自证）；目录本地化（buildDir/日志/媒体收进仓库内 ignore 目录 + 端口参数化）后需实测沙箱内能否带起 Tomcat——**若沙箱仍拦进程/端口则只能缓解而非根除，该边界拆任务时先探明** |

> **备注（与已登记代码债的关系）**：U-14（`ContentService.search` + `FollowService` 关注/粉丝列表的事务回调内缓存读，260918/T3 同型未治点）——改造形态已明确（DB 查询与缓存读分离回传），**改动面小、风险可控，与本周期"清偿代码债"气质一致**，建议评审时决定是否纳入；若纳入将占用独立任务而不属纯杂务。
> 留池仍不动项：U-11（停机 `/start` 空推荐）维持留池，见二节；U-12/U-13 已随用户拍板转本档 N11（见二节更新），不再留池。**U-12（关注/粉丝列表加分页）与 U-14（②号点）都落在 `FollowService.getFollowingList/getFollowerList` 同一批方法上，若 N11 与 U-14 同时纳入，需在任务拆分时协调同一文件的两项改动，避免任务相互踩线。**

### 4.2 候选方向（拍板后记录结论）

| 候选 | 内容 | 依据 | 结论 |
| ---- | ---- | ---- | ---- |
| **杂务清理（准备分支）** | T1~T6 基座/卫生（commit 规范 N13 / 常青瘦身 N12+N9 / pom Kotlin N1 / git 卫生 N2+N3 / 死注释+分页收敛 N4+N5+N6 / 日志卫生 N7+N8）+ T7~T9 主菜（关注-粉丝分页 N11a、评论分页 N11b、测试目录本地化 N14） | 2026-09-18 周期探查 + 用户方向反馈（点名单后拆任务） | ✅ **本周期采纳（2026-09-18，R-03 拍板）**：范围见 4.3，任务见 `NEXT_CYCLE_TASKS.md` T1~T9 |
| N10 请求日志摸底 | 仅摸底不落地，属日志体系改造主干项 | 无统一请求/响应日志（N10） | ⏸ **转留池（U-15）**：日志体系改造分支立项前做摸底更有时效 |
| **正式功能方向**（feed 流改造 / 日志体系改造 / 新功能） | 用户已预告各开新分支 | — | ⏸ 不在本周期做，另开分支 |

### 4.3 本周期范围（R-03 拍板结果，2026-09-18）

**主题**：杂务/准备周期——分批交付：第一批**基座/卫生**（T1~T6，低风险相互独立）+ 第二批**用户点名主菜**（T7~T9）。

| 纳入 | 对应编号 | 落到任务 | 一句话 |
| ---- | ---- | ---- | ---- |
| commit message 规范 | N13 | T1 | 定 type/SCOPE/header/正文分节规范并回写周期模板，本周期起 commit 全按此写 |
| 常青文档瘦身（含头部对齐） | N12 + N9 | T2 | 更新日志压成一行摘要或移除（改引 git 历史）、头部版本与内容一致、BUSINESS_FLOW 注记收敛 |
| pom Kotlin 残留清理 | N1 | T3 | 移除 kotlin 三件套，`mvn -o` 编译/JUnit 回归全绿 |
| git 卫生 | N2 + N3 | T4 | `.idea/` 整目录出库 + ignore；`temp_script` 例外出库 |
| 随手清理（死注释 + 分页收敛） | N4 + N5 + N6 | T5 | 删除 RequestParser/web.xml 死注释；parsePage/parsePageSize 收敛公共（T7/T8 前置） |
| 日志卫生 | N7 + N8 | T6 | LogUtil 自我合规；CountRepairTool 已按 R-05 拍板**删除**（统一入口 = `tools/check_integrity.py --fix`） |
| 关注/粉丝列表分页 | N11a（U-12） | T7 | 新增可选 page/pageSize 缺省兼容；缓存分页载体拍板（**A1 = Set→ZSet 有序化 + ZSetCache 窗口读**）；前端 **user.js**（非 follow.js——那是 `#/follow` 关注流视图）分页。**2026-09-19 已落地**：JUnit 472 / pytest 135 全绿 |
| 评论列表分页 | N11b（U-13） | T8 | 主楼分页 + 楼中楼整树，缺省兼容；缓存整树不动只做展示层切片；前端 detail.js 加载更多。**2026-09-19 已落地**：D1=A（`PageResult<CommentVO>` 信封 + `hasPagingParams` 判参），JUnit 482 / pytest 142 全绿 |
| 测试目录本地化 | N14 | T9 | 沙箱边界探明 → buildDir/日志/媒体本地化实现（端口参数化判定非阻塞跳过）→ **2026-09-19 已落地：沙箱内 `tv.py test all` 全链可跑，JUnit 482 / pytest 142 全绿**（详见 TASKS T9 执行回写） |

**本周期明确不做（反面清单，与"纳入"同等重要）**：

| 不做 | 原因 / 去向 |
| ---- | ---- |
| U-14 事务边界同型未治点 2 处 | 与 T7/T8 撞同一批 `FollowService.getFollowingList/getFollowerList` 方法，T7/T8 落地后再评（NEEDS 4.1 备注已协调） |
| N10 请求日志摸底 | 转留池（`UNPLANNED_ISSUES.md` **U-15**）：属日志体系改造主干项，摸底放该分支立项前更有时效 |
| R-02 索引全量读本体 | 明确保留（`260918/R-02`），不随本周期 |
| R-04 content↔comment 包层环 | 继续待定（`260918/R-04` = U-07），不随本周期 |
| U-11 停机 `/start` DB 兜底推荐 | 对外行为变更未拍板，维持留池 |
| 正式功能方向（feed 流 / 日志体系 / 新功能） | 另开分支；本周期只做铺垫与卫生 |

***

## 五、本周期范围与边界（指针节，范围一律以 4.3 为准）

> 本节**不单独维护范围清单**，避免与 4.3 口径分叉：本周期做什么、不做什么（含反面清单），一律以 **4.3 为唯一落点**。本节只保留 4.3 不覆盖的两类边界：

- **留池未排**：见二/三——U-11 / U-12 / U-13 维持留池；U-14 与 R-04 是否纳入本周期，评审时决定。
- **禁止（沿用技术红线）**：Spring/SpringBoot/MyBatis；擅改 `@WebServlet` URL、web.xml、IoC 扫描；为实现便利改业务逻辑语义；本周期不引入任何新依赖（N1 Kotlin 清理属"删"，不属"加"）。