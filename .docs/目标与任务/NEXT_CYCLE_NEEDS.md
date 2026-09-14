# 下一周期需求与痛点

> 用途：回答"下一周期为什么做这些"——本周期要解决的痛点、候选任务的优先级映射、以及开工前必须拍板的技术决策。
> 状态：**方向已拍板（2026-09-13，R-11）**——本周期 = **缓存加固**（缓存韧性 + 启动加载治理）；落地范围见 **4.3**（含"明确不做"清单）。结转事项已**统一编号为 `R-01`~`R-13`**（二表 R-01~R-10 待消化的事实项；三表 R-11~R-13 决策项，R-11 已拍板；旧带周期前缀编号退居"来源"列供追溯）；4.1 的候选痛点 N1~N9 已随方向拍板**部分纳入，纳入项见 4.3**。
> 配套：How（拆任务）见 `目标与任务/NEXT_CYCLE_TASKS.md`（**已拆 T1~T6**；四要素为骨架，执行方案由执行窗口探索细化）。
> 注意：二/三为**结转总账**，不等于本周期范围；本周期做什么、不做什么，以 **4.3** 为准。
> 来源：260913-cache-architecture 周期（C 方向缓存改造，一版 T1~T6 + 二期 T7~T9，共 9 个 commit `refactor(cache-01)`~`refactor(cache-09)`，全部完成）归档后：代码复查发现的失效路径缺陷（4.1 的 N1~N9）+ 已登记代码债（`UNPLANNED_ISSUES.md` 的 U-08~U-10）+ 留池项 R-01/R-04。
> 术语约定：**周期 > 任务**。本文档只回答 Why（需求与决策），How（拆任务）在任务清单文档。

***

## 一、结转：仍有效的通用约定（来自已归档周期，随周期继续生效）

| 编号 | 约定 | 说明 |
| ---- | ---- | ---- |
| C-1 | 双文档结构 | 本文档（需求与痛点，决策唯一源）+ 任务清单（执行细节） |
| C-2 | 一任务一窗口一 commit | 默认期望，允许例外需标注；commit message 强制带任务编号 |
| C-3 | commit 语义闭环 | 代码 + 常青文档更新 + 任务清单勾选进**同一 commit** |

> 另有若干"延续性约定"不单独编号，随周期生效（原文见 `archive/目标与任务/260913-cache-architecture/NEXT_CYCLE_NEEDS.md` 七决策与约束）：
> ① **红线措辞约定**：红线只列"明显越界"的项，作用是**防跑偏、不把执行 Agent 限制死**（不穷举做法、不做一刀切禁止）；若某条红线会阻碍正确做法（过紧 / 过窄 / 已不适用）——不允许硬扛、也不允许自行放开，**先说明理由申请调整**，获批准后按新口径动手，未获批准则维持原红线（完整表述见 `目标与任务/NEXT_CYCLE_TASKS.md` 二节）；
> ② **编号引用约定**：禁裸编号引用已归档周期元素，引用一律写成 `<周期>/<编号>`（如 `260913/O-5`）；裸编号仅指本文档内部定义的元素；
> ③ **技术红线**：禁 Spring/SpringBoot/MyBatis；不擅改 `@WebServlet` URL / web.xml / IoC 扫描；不为缓存便利改业务逻辑语义（点赞去重/楼中楼/搜索）；
> ④ **不引入 MQ**：异步（若需）用进程内线程池（`ExecutorService`），不引入 RabbitMQ/Kafka/RocketMQ；
> ⑤ **脚本规范**：脚本一律 Python；临时一次性脚本放 `temp_script/`，长期复用/自动化放 `tools/`；
> ⑥ **DDL 备份**：出现表结构改动，执行前先备份库结构与建表语句到 `.docs/DBbackups/`。

***

## 二、结转：未完成的需求（260913-cache-architecture 归档时未关闭）

> **编号体系（本档内部）**：`C-#` 通用约定；**`R-##` 待评审事项**（本节与三节连续编号，评审时按 R 编号点单）；`N#` 本周期新探查候选痛点（4.1）；`T#` 任务（见任务清单文档）。引用**已归档周期**的元素仍写 `<周期>/<编号>`（见一⑤）；每行"来源"列保留原编号以便追溯，正文不再使用带周期前缀的旧编号。

| 编号 | 事项 | 类别 | 来源（归档周期） | 状态 | 说明 |
| ---- | ---- | ---- | ---- | ---- | ---- |
| R-01 | 初始化选择性加载 | 需求（方案待拍板） | `260913/O-5` | 留池未排期 | 启动全量加载（现状 `ContentCache.init()`：全表 content + 逐条媒体）改为按需回填 or 分级加载？评论是否仍全量。与 R-04 同源 |
| R-02 | 关注/粉丝计数入缓存 | 需求（方案待拍板） | `260913/O-9` | 留池未排期 | Profile 的 followCount/followerCount 是否一并入缓存：关系 Set 是成员、计数是独立 key；注意 SCARD 冷 set 返 0 的坑 |
| R-03 | 单飞进程内锁的多实例化 | 需求（架构） | `260913/分布式` | 继续延后 | 260913 周期 4.9 约定"进程内锁只对单实例有效；当前单 Tomcat 够用，不过度设计"；多实例需分布式锁 |
| R-04 | 初始化 N+1 未根治 | 需求（性能） | `260913/H7` | 只缓解 | T3 让启动少一轮评论 N+1（评论不再全量加载）；内容仍全表 + 逐条媒体查询，且串在一个长事务里 |
| R-05 | 缓存对象对外共享可变引用 | 需求（待确认闭环） | `260913/H8` | 疑似已解，文档未闭环 | 新实现读路径走 JSON（`codec.fromJson` 每次产生新对象），共享引用实质消失；但归档文档六只声明"H1~H6 修复"，本项未明确闭环 → 需确认后关闭 |
| R-06 | `authorName` 冗余不同步 | 需求（一致性） | `260913/H9` | 未处理 | 用户改名后内容缓存仍带旧名，需等 TTL/失效；当前无改名接口，属潜在项 |
| R-07 | 索引全量读 + 拷贝 shuffle | 需求（性能） | `260913/H10` | 明确保留 | T8 明示"`LRANGE 0 -1` 全量读保留（推荐 shuffle 对外语义不变）"；`getRecommendByFilter` 仍每次去重 + shuffle 全量候选、只取 12 条，数据量大时 O(n) |
| R-08 | follow/like 分域 TTL 真流量复调 | 待数据 | `260913/TTL` | 待真流量 | T9 取参来自本地轻量压测（`temp_script/pressure_cache.py` 双轮 total=21000）；follow 未覆盖真实关系数据，30min 属保守延长并标注"待真流量复调" |
| R-09 | 批量续期测试覆盖缺口 | 测试 | `260913/T9-Review①②` | 记录不修 | ① `getBatch` 续期未覆盖 miss key 场景；② Like/Follow 批量 pipeline 续期未直接断言（单 key hit-data 已覆盖） |
| R-10 | 是否需定期重建索引防长尾漂移 | 待评估 | `260913/O-6-尾巴` | 未登记 | 260913 周期 O-6 行明示"如需定期重建索引防长尾漂移，另行登记评估"——尚未评估 |

> **已完成、不结转**（供对照）：一版 T1~T6 全部；二期 T7 观测埋点 / T8 读路径加固 / T9 TTL 精调；H1~H6、H11（占位符 bug）、H12/H13/H14 已治理；O-1~O-4、O-6、O-7、O-8 已拍板或已完成；P5 已消化（T6）。

***

## 三、结转：未拍板的决策（进任务清单前须拍板）

> 编号接续二（`R-##` 连续）；本表是"必须拍板才能开工"的决策项，二表是"待消化的事实项"。

| 编号 | 待拍板事项 | 来源（归档周期） | 当前状态 | 说明 |
| ---- | ---- | ---- | ---- | ---- |
| R-11 | **第三期方向**（本周期主线做什么） | 本档新增 | **已拍板（2026-09-13）**：缓存加固（缓存韧性 + 启动加载治理） | 落地范围与"明确不做"清单见 **4.3**；已拆任务见 `NEXT_CYCLE_TASKS.md` 的 T1~T6；D 方向 feed 的前置条件由用户同期定下（见 4.3） |
| R-12 | 优惠券抢购限流 | `260913/P6` | 默认不做 | 上周期列为"范围外（默认不做）"；需用户确认才纳入、才拆任务；改动面 `CouponService`/`CouponController` |
| R-13 | content ↔ comment 包层循环依赖 | `260913/U-07` | 待定（C 周期复查：**仍在**，未自然解除） | content 域共享组件（ContentCacheDTO 等）被 comment 域引用，content 又引用 comment 的 `CommentService`；**非 IoC/Bean 环**，仅包架构不纯净、Java 允许；T3 已移除 `CommentService→ContentCacheManager` 依赖，但包层环未解除 |

> R-01 / R-02 同时是"未完成需求"（见二）和"未拍板决策"——要不要做、怎么做都没定：R-01 需在 全量（现状） / 按需回填 / 分级加载 三取向中选；R-02 需先决定是否补计数缓存。
> 已拍板、仅留"未来再评估"口子的（不计入未拍板）：`260913/O-7` 搜索维持 FULLTEXT 直查——"若未来要动，另行登记评估"。

***

## 四、本周期痛点与目标方案

> 结构：**4.1 痛点清单**（代码复查发现，R-11 拍板后部分纳入）→ **4.2 候选方向与拍板结论** → **4.3 本周期范围与反面清单**。
> 待补：归档文档风格的"目标形态"与"逐项已拍板技术决策"两段，待 T1~T6 执行中按实际方案回写（G7 允许）。

### 4.0 已回写技术决策（执行中拍板，按任务追加）

**T1（fix(cache-01)，2026-09-13 拍板并落地——超时配置化 + 全局熔断）**：

* 显式超时：connect/so 各 1000ms + 池借用 maxWait 1000ms（app.properties `redis.connectTimeoutMs/soTimeoutMs/pool.maxWaitMs`，此前走 Jedis 默认 2000ms 未显式化 + maxWait -1 无限阻塞）；Jedis 5.1.0 无 `(poolConfig, host, port, connTimeout, soTimeout)` 短构造器，用 8 参 `(…, password, database, clientName)` 等价替代（password/clientName=null 保持原语义）。
* 全局熔断：粒度=**全局单熔断**（单 Redis 实例，按域只增探针流量）；失败口径=**从 `RedisAccess.execute` 冒出的 CacheException 计一次失败**（包装异常均为 Redis 起源；回调自抛极罕见，偏差无害——最坏提前降级，不违反"缓存失败不导致业务失败"）；恢复探测=**半开单探针**（连续失败 ≥5 开断、冷却 10s，期满 CAS 放行唯一探针，成功闭合/失败重开重置冷却），参数 `redis.breaker.failureThreshold/cooldownMillis` 可配。
* 落点：`cache/RedisCircuitBreaker`（新）+ `RedisAccess` 接线（唯一出入口，熔断异常由既有 catch 降级自然接住，CacheAside 零改动）+ `MyRedisPool`（public 签名零变化）。详见 `常青/CURRENT_ARCHITECTURE.md` 6.6；运行时验证记录见 `NEXT_CYCLE_TASKS.md` T1 执行回写。

**T2（fix(cache-02)，2026-09-13 拍板并落地——降级不放量：降级路径接入单飞）**：

* 统一规则：**降级读 = 与 miss 回填同款"单飞 + 全量 loader"取数，但仅装载、不写回**（对齐 D4"降级路径不写回"）；降级与 miss 共用同一 `SingleFlight` key 空间（`SingleFlight` 类零改动）。
* 治理面：读路径降级分支共 10 处——`CacheAside` 3 处（getInternal / getBatch 整批 / getBatch 脏 JSON 单 key）、`FollowCache` 3 处（isFollowing / getSetMembers / batchIsFollowing 降级态）、`LikeCacheService` 4 处（isContentLiked / isCommentLiked / 两批量降级态）。单 key 降级由"单行查询"改为单飞全量装载作答（删 3 个 `*FromDb` 助手，DAO 单行方法保留——写路径仍用）；批量降级由"targeted 批量查询 + 必失败的回填写入尝试"改为"单飞全量装载作答"（DB 总负载不升反降）。`read`（无生产调用方）、写路径 catch、`ensureIndex`（已单飞）、`readIndex`（降级空、无 DB 装载，U-11）不在范围。
* 失败语义（执行定稿）：loader 失败 → FutureTask 异常完成 → leader/joiner 均以异常收场（**失败不以数据形式共享给等待者**）→ 条目 remove → 下一请求全新重试；等待无超时=与现状等价（分布式锁/超时=R-03）。
* 与 T1 熔断关系（执行定稿）：正交互补——熔断管"Redis 访问快速失败"，单飞管"降级后 DB 去重"；熔断 OPEN 后每请求仍进降级分支，单飞仍然必需。
* 统计口径微调：降级路径 `LOAD` 从"每请求记一次"变为"实际去重后装载记一次（leader 记）"，与 miss 单飞口径一致；`DEGRADE` 不变。
* 落点：`CacheAside`（370 行）+ `FollowCache`（524 行）+ `LikeCacheService`（628 行）；防漂移公共入口 `FollowCache.loadViaSingleFlight` / `LikeCacheService.loadLikersViaSingleFlight`。详见 `常青/CURRENT_ARCHITECTURE.md` 6.7；验证（JUnit 337 + pytest 124 + 黑洞运行时 20 并发同 key Com_select 差值 8）见 `NEXT_CYCLE_TASKS.md` T2 执行回写。

**T3（fix(cache-03)，2026-09-14 拍板并落地——负缓存治理：区分"确认无数据"与"加载失败"，治 N2）**：

* 拍板取向（用户 2026-09-14）：**对外行为保持**——loader 失败仍表现为"本次读无数据"（内容 404 / 评论空 / 批量逐 key 跳过），只治理"loader 失败时怎么写 / 不写缓存"这一层；**不统一各域对外错误约定**（like/follow 的 500 语义不动）。
* 区分载体：loader 失败抛 **`DatabaseException`**（现成，事务模板已把 SQLException 包成它）；`return null` 仅保留"确认无数据"（DB 无行 / 媒体损坏 / 未知类型）；意外异常统一包成 `DatabaseException`（防 NPE 等静默污染空标记）。
* CacheAside 契约（新增）：所有装载点（getInternal miss、getInternal 降级、getBatch miss 循环、getBatch 整批降级、getBatch 脏 JSON 单 key 降级）捕获 `DatabaseException` → 记日志转 null——**不写空标记、不 DEL 既有数据 key**（读路径不把瞬时故障固化成假空）；miss 路径失败直接 return null **跳过 markEmpty**；降级路径共用 `loadDegraded` helper。契约仅对 `DatabaseException` 生效，like/follow 的 `ServerException` 不受影响。
* 写路径守卫：`ContentCache.addContent`/`refreshContent`（DB 提交后缓存同步）遇 `DatabaseException` 静默跳过（refresh 保留旧缓存，读自愈），防提交后 500。
* 落点：`CacheAside` + `ContentCache` + `CommentCache`（详见 `常青/CURRENT_ARCHITECTURE.md` 6.8；验证 JUnit 348 + pytest 124 + subagent 评审无🔴见 `NEXT_CYCLE_TASKS.md` T3 执行回写）。

**T4-①（fix(cache-04a)，2026-09-14 拍板并落地——空标记写入存在守卫，治 N3）**：

* 拍板取向（用户 2026-09-14）：**exists 守卫**（对齐 `FollowCache.writeSet` 260913 先例）而非 Lua 原子化——Lua 需 RedisAccess 新增 eval 路径且 mock 复杂化，守卫已把竞态窗口从"loader 全程"缩到毫秒级。T4 拆 3 commit（G1 校准）：`fix(cache-04a/04b/04c)`，本项=a。
* 实现：`CacheAside.markEmpty` 加 `if (!j.exists(dataKey))` 守卫——数据 key 已存在（并发回填/业务写刚写入真数据）时跳过，**不写空标记、不 DEL**；**原 `del(dataKey)` 随守卫移除**（守卫内为死代码，且现状竞态下是 N3 危害的组成部分——删掉并发刚写入的真数据）；`FollowCache.writeSet` 空分支定向复用 `markEmpty`（U-09 允许的 T4 定向复用，先例守卫内的 del 一并消除）。
* 残余竞态（已接受）：exists 检查→setex 的毫秒间隙内并发写入时空标记可能覆盖其上——数据 key 未被删，空标记 60s 过期或下次业务写 `writeOrInvalidate` 清空标记即自愈，无真数据丢失。
* 落点：`CacheAside.markEmpty` + `FollowCache.writeSet` 空分支（详见 `常青/CURRENT_ARCHITECTURE.md` 6.9；验证 JUnit 351 全绿含并发不假空时序测试 + pytest 124 见 `NEXT_CYCLE_TASKS.md` T4 执行回写 04a）。

**T4-②（fix(cache-04b)，2026-09-14 拍板并落地——索引写失败自愈，治 N4）**：

* 拍板取向（plan 定稿）：**自愈 = `addToIndex` 写失败时 catch 内 best-effort DEL 本内容所属 4 个索引 key** → 下次推荐读 `ensureIndex` 发现缺失即触发既有单飞懒重建（`loadAllWithoutMedia` + `rebuildIndexes`）全量重建——**复用既有懒重建基建、零新增 key**（否决脏标记 key：触碰 key 命名边界且 T6 U-08 要管；否决完整性校验：无便宜一致性信号、成本高）。`indexKeysOf` 与 `lremAndLpush` 同源提取；双层 best-effort（DEL 也失败不抛，读路径同样降级）。
* 失败场景三分收敛：抖动已过 → DEL 成功自愈；Redis 持续挂 → DEL 也失败与现状一致（无新增伤害）；DEL 部分成功 → 已 DEL 的 key 缺失照样触发全量重建 → 收敛。
* 残余窗口（已接受）：Redis 持续挂恢复后索引仍可能不完整（与现状一致，读路径降级兜底）；懒重建触发面仍限 `getRecommendByFilter`（与现状一致，不做 R-10）。
* 落点：`ContentCache.addToIndex` + `indexKeysOf` + `deleteIndexKeysQuietly`（详见 `常青/CURRENT_ARCHITECTURE.md` 6.10；验证 JUnit 353 全绿 +2 + pytest 124 见 `NEXT_CYCLE_TASKS.md` T4 执行回写 04b）。

### 4.1 候选痛点（2026-09-13 代码复查，**待评审纳入，尚未拍板**）

> 编号 `N1`~`N9` 为**本档内部编号**（与已归档周期的 `H*` / `O-*` / `P*` 编号体系无关）。每条给出"如果不改，什么时候会出什么问题"的具体场景。
> 评审时逐条决定：纳入本周期 / 转 `UNPLANNED_ISSUES.md` 留痕 / 废弃。
> 与已登记代码债的关系：`N1` 是 `UNPLANNED_ISSUES.md` 的 `U-10` 的另一面（同根因）；`N3` 是 `U-09`（重复实现）的具体后果之一。本节任一项经评审纳入本周期后，升格为正式编号（并入 `R-##` 或任务编号 `T#`）。

| 编号 | 问题 | 证据 | 不改会怎样（具体场景） |
| ---- | ---- | ---- | ---- |
| N1 | **降级路径绕开单飞**：Redis 异常后直接调 loader，不经 `SingleFlight`、无熔断、无本地兜底 | `CacheAside.getInternal` catch → `invokeLoader` 直调；`getBatch` catch → `loadBatch`；`FollowCache.isFollowing` / `getSetMembers` / `batchIsFollowing` 降级直查 DAO；`LikeCacheService.isContentLiked` 降级 `isContentLikedFromDb` | Redis 宕机时，所有读请求在 catch 之后**既没有缓存、也没有单飞**，全部并发的打到 DB；叠加 `U-10`（没配 timeout）每个请求还要先等约 2 秒连接超时。热门接口一并发 → DB 连接池被占满 → **数据库跟着不可用 → 全站 500**。即"缓存不可用"升级成"数据库不可用" |
| N2 | **loader 把"查库失败"当成"确实没有数据"**：`null` 同时表示"不存在"和"查失败"，被 CacheAside 判定为 hit-empty 写入空标记 | `ContentCache.loadContentFromDb` catch(SQLException) 返回 null；`CommentCache.loadCommentTree` 同样；`CacheAside.get` 见 null → `markEmpty`（写 `empty:` 60s + DEL 数据 key） | 热门内容的 content key 会因点赞/评论数变化被频繁失效（读自愈），miss 很频繁。某次 miss 恰好撞上 DB 的一次瞬时错误（连接抖动/SQL 超时）→ 缓存判定"该内容不存在"并写 60 秒空标记 → **这条热门内容对全部用户 404 长达一分钟**，而 DB 其实几秒后就恢复了。对比：点赞/关注域 loader 遇 SQLException 是抛 `ServerException`（接口 500、不污染缓存）——同一套缓存层里 DB 错误有**三种不同结局**，只有内容/评论会把瞬时错误固化成假数据 |
| N3 | **`CacheAside.markEmpty` 无条件 DEL 数据 key**：写空标记前不判断数据 key 是否已被并发写入（缺 `FollowCache.writeSet` 已有的"存在守卫"） | `CacheAside.markEmpty` → `setex(empty:…) + del(dataKey)` 无 exists 前置判断；对照 `FollowCache.writeSet` 空分支有 `if (!j.exists(setKey))`（T5 review 必修②，防的正是这个坑） | 用户刚发完评论，另一请求正在 miss 回填同一棵评论树，其 loader 读到"无评论"（提交前）返回 null，随后评论提交+缓存失效，该请求才执行 `markEmpty` → 写 60 秒空标记 → **刚发的评论 60 秒内对所有人不可见**。同一个坑，关注（原生 Set 路径）已修，内容/评论/like 空集分支没修 |
| N4 | **索引写失败不自愈**：`addToIndex` 失败只记日志；`ensureIndex` 只判索引 key 是否存在，不做完整性校验/修复 | `ContentCache.addToIndex` catch → 仅 WARNING；`ensureIndex` → `if (exists(indexKey)) return;` | 发布视频时 Redis 一次抖动导致 `addToIndex` 失败（此时 content key 已写成功）→ 该内容不在任何 `content:index:*` 里，而索引 key 存在所以懒重建永不触发 → **首页推荐/Feed 永远刷不到这条视频**，只能等应用重启时的 `init()` 全量重建或索引 key 被清掉 |
| N5 | **启动初始化在 DB 事务内写 Redis，且逐条无 pipeline** | `ContentCache.init()` 的 `transactionTemplate.execute(conn -> { findAllContent + 逐条 findMedia + rebuildRedis(...) })`——Redis 写入位于事务内；`rebuildRedis` 逐条 `writeContent`（SETEX+DEL），`rebuildIndexes` 逐条 8 次 LREM/LPUSH，全程无 pipeline | 内容到 5 万条时重启应用 → 一个长事务里跑"全表 + 5 万次媒体查询 + 约 10 万次 Redis 往返 + 约 40 万次索引往返" → 启动从秒级涨到分钟级；该事务全程占着 DB 连接，**启动窗口期接口因拿不到连接而大面积超时**。且这与 H3 已确立的原则（Redis 写入必须移出 DB 事务）自相矛盾——`addContent` 修了，`init()` 没修 |
| N6 | **点赞成员回填按"该内容的全部点赞者"装载**，内存/延迟随点赞量放大 | `loadContentLikers` = `contentLikeDao.findLikerIdsByContentId`（全量）；`isContentLiked` miss 即触发；`backfillBatchContentLikers` 对 missed 里**每个 id 各来一次** | 判断"某用户有没有给这条视频点过赞"，代价是把该视频**全部**点赞者 id 读出来在 JVM 建 Set。10 万赞的热门视频 = 一次判断加载 10 万行；`/start` 推荐位 12 条的 likeSet 若恰好都是冷的，一次请求就是 12 倍量级。Redis 挂时最糟（降级路径逐条全量查） |
| N7 | **条件写"先探存在、再 INCR"非原子** | `LikeCacheService.likeContent` 第一趟 pipeline 读 `exists(countKey)`，`p.sync()` 之后第二趟才 `j.incr(countKey)` | 点赞请求探到 count key 存在 → 就在这中间，并发失效（内容下架走 `deleteContentLike`，或另一请求写失败触发 `cacheAside.invalidate`）把 count key DEL 掉 → 本请求的 `INCR` 把 key **以 1 重建** → 该内容点赞数在 TTL（15 分钟）内对所有人显示为 1，真实值可能是 1000+ |
| N8 | **缓存 JSON 无格式版本**：DTO 字段删/改名后旧条目反序列化失败，且降级路径**不写回** | `JacksonCodec` 未关 `FAIL_ON_UNKNOWN_PROPERTIES`（Jackson 默认 true）；`CacheAside` 反序列化失败 → catch → DEGRADE + 直接 loader（不写回） | 第三期若因楼中楼/展示改造删掉或重命名 `ContentCacheDTO` 的字段 → 部署后 Redis 旧 JSON 全部反序列化失败 → 这批 key 在各自 TTL 内（content 最长 30 分钟）**每次读都降级走 DB 且不回写缓存** → 缓存对它们完全失效，DB 独自承担全量流量 |
| N9 | **观测仍看不到"某段时间的命中率"**（进程内计数、重启即丢、每 1000 次一条 INFO、无重置、无端点） | `CacheStats`：`AtomicLong[5][6]` + `DEFAULT_LOG_INTERVAL=1000` 惰性 INFO | 属于 T7 已拍板（惰性日志、不引入定时器、不新增端点）的**已知取舍**，列出仅供评审时确认是否仍接受；低频环境（本地/演示）流量不足 1000 次则永远不出日志，调优时仍无数据可依 |

### 4.2 候选方向（R-11 已拍板，2026-09-13）

| 候选 | 内容 | 依据 | 结论 |
| ---- | ---- | ---- | ---- |
| **缓存韧性 + 启动加载治理**（合称"缓存加固"） | 面向"Redis 不可用 / DB 抖动"的加固：超时/熔断/降级不放量（U-10、N1）、负缓存治理（N2）、写路径失败与竞态（N3/N4/N7）、启动与回填放大（N5、R-01、R-04），外加 U-08 key 归一 | 4.1 候选痛点 N1~N5/N7 + 已登记 U-08/U-10 + 留池 R-01/R-04 | ✅ **本周期采纳** |
| D 方向 feed 流改造 | feed（关注流）聚合改造，含 feed 聚合缓存 | 260913 周期"范围外（默认不做）"明示；其 4.10 边界"本项只缓存关系本身，feed 聚合属 D 方向" | ⏸ **延迟**——前置条件 = 缓存加固完成并合并 PR（用户 2026-09-13 定） |
| 缓存行为统一收口 | 把三态/空标记/降级/单飞/续期从"5 份重复实现"收敛到基建（`UNPLANNED_ISSUES.md` 的 `U-09`） | 260913 周期归档后代码探查（2026-09-13） | ⏸ 延后；本周期只在 T2/T4 必要处做**定向复用**，不做全面重构 |
| 消费留池项 | 初始化选择性加载（R-01）+ 关注/粉丝计数入缓存（R-02） | 上周期留池未排期，跑完二期再评估 | ⚠️ 部分纳入：**R-01 随 T5 一起做**（与 N5 同源）；R-02 仍留池 |

### 4.3 本周期范围（R-11 拍板结果，2026-09-13）

**主题**：缓存加固——"Redis 不可用 / DB 抖动时，缓存既不撒谎、也不放量"。

| 纳入 | 对应编号 | 落到任务 | 一句话 |
| ---- | ---- | ---- | ---- |
| Redis 快速失败（超时 + 熔断） | U-10、N1 | T1 | Redis 挂了不该每个请求还去等连接超时 |
| 降级不放量 | N1 | T2 | 降级路径也接单飞，同 key 并发只打一次 DB |
| 负缓存治理 | N2 | T3 | 区分"确认无数据"与"加载失败"，不把瞬时故障固化成 60s 假空 |
| 写路径失败与竞态 | N3、N4、N7 | T4 | 空标记写入守卫、索引写失败自愈、条件写原子性 |
| 启动加载治理 | N5、R-01、R-04 | T5 | Redis 写入移出 DB 事务 + pipeline 化 + 选择性加载取向拍板 |
| key 规范归一 | U-08 | T6 | `content:index` 生成与解析同源 |

**本周期明确不做（反面清单，与"纳入"同等重要）**：

| 不做 | 原因 / 去向 |
| ---- | ---- |
| D 方向 feed 流改造 | **前置条件 = 缓存加固完成并合并 PR**（用户 2026-09-13 定）；缓存层失效路径未验完，此时建 feed 等于在没验完的地基上盖第二层 |
| U-09 缓存行为全量收口 | 避免把"行为零变化的重构"与"改行为的修复"混在同一周期（测试难判绿）；只在 T2/T4 必须碰的地方做定向复用 |
| N6 点赞成员全量装载 | 需要一次设计反转（内容维度 `content:likeSet` → 用户维度 `user:likeSet`，与 4.10 `user:following` 同构）；留到 feed 周期前后单独评估 |
| N8 缓存 JSON 无格式版本 | 无对外影响路径，待真正要改 DTO 时再处理 |
| N9 观测只能看惰性日志 | T7 已拍板的取舍；本周期只确认是否仍接受，不改 |
| R-02 关注/粉丝计数入缓存 | 留池，未排期 |
| R-03 单飞多实例化（分布式） | 继续延后（单实例够用，不过度设计） |
| R-05~R-10 | 留池：R-05 待确认闭环；R-06/R-07 与 feed 周期合流更合适；R-08 待真流量；R-09 测试缺口；R-10 待评估 |

***

## 五、本周期范围与边界（初步，待方向拍板后细化）

- **留池未排**：见二——是否纳入本周期，评审时决定。
- **范围外（默认不做）**：P6 优惠券限流；前端；新增缓存之外的业务功能。
- **禁止（沿用技术红线）**：Spring/SpringBoot/MyBatis；擅改 `@WebServlet` URL、web.xml、IoC 扫描；为缓存便利改业务逻辑语义。

***

## 六、变更记录

| 日期 | 版本 | 内容 |
| ---- | ---- | ---- |
| 2026-09-13 | 0.1 | 新建本文档（结转稿）：接 260913-cache-architecture 归档周期，结转 ① 通用约定（C-1~C-3 + 6 条延续约定）、② 未完成需求 10 项（O-5/O-9/分布式/H7~H10/TTL 复调/T9 review 测试缺口/索引定期重建）、③ 未拍板决策 4 项（第三期方向/P6/U-07/O-5·O-9 方案）；四（本周期痛点与目标方案）留空待方向拍板；同步登记 U-08~U-10 代码债入 UNPLANNED_ISSUES |
| 2026-09-13 | 0.2 | 补 4.1 候选痛点（代码复查，待评审纳入）：N1 降级路径绕开单飞（Redis 挂→DB 连带崩）、N2 loader 把查库失败当"确实没有数据"（瞬时 DB 错→60s 假 404）、N3 `CacheAside.markEmpty` 无条件 DEL 数据 key（缺 FollowCache 已有的存在守卫→假空窗口）、N4 索引写失败不自愈（内容永不出现在推荐）、N5 `init()` 在 DB 事务内写 Redis 且无 pipeline（启动放大并占住连接）、N6 点赞成员全量装载（内存/延迟随点赞量放大）、N7 条件写"探存在→INCR"非原子（计数被以 1 重建）、N8 缓存 JSON 无格式版本（DTO 改动后降级且不写回）、N9 观测只能看惰性日志（T7 已知取舍，列出待确认）；4.2 候选方向由 3 个扩为 4 个（补"缓存韧性专项"） |
| 2026-09-13 | 0.3 | **结转项统一重编号**：二/三两表由"带周期前缀编号"（`260913/O-5` 等）统一为**本档内部连续编号 `R-01`~`R-13`**（R-01~R-10 = 未完成需求，R-11~R-13 = 未拍板决策；R-11 = 第三期方向这一"元决策"），原编号退入"来源"列供追溯；新增"编号体系"说明（C-# / R-## / N# / T# 四类）；同步修正 4.1/4.2 内对新编号的交叉引用；补充配套 TASKS 文档指引 |
| 2026-09-13 | 0.4 | **R-11 拍板回写**：方向 = **缓存加固**（缓存韧性 + 启动加载治理）。① 状态行改为"方向已拍板"，R-11 行状态置已拍板；② 4.2 补"结论"列——韧性方案采纳、D 方向 feed 延迟（**前置条件 = 缓存加固完成并合并 PR**，用户同期定）、U-09 延后、R-01 随 T5 纳入 / R-02 仍留池；③ **新增 4.3 本周期范围**（纳入 6 项 → T1~T6 映射表 + **"明确不做"反面清单 8 项**，含每项的延后原因与去向）；④ 四节标题去掉"待补写"并说明结构（目标形态/技术决策两段待执行中回写）；⑤ 配套 `NEXT_CYCLE_TASKS.md` 已按 4.3 拆出 T1~T6 |
| 2026-09-13 | 0.5 | **修订红线措辞约定**（用户要求，与 `NEXT_CYCLE_TASKS.md` 0.3 同步）：① 一节的 ① 改为"红线只列明显越界的项、作用是防跑偏、不把执行 Agent 限制死"；② 机制表述由"申请开禁"改为"**申请调整**"，触发条件明确为"某条红线会阻碍正确做法（过紧 / 过窄 / 已不适用）"，两个禁止（硬扛 / 自行放开）保留；③ 完整表述统一指向 `NEXT_CYCLE_TASKS.md` 二节，避免两处措辞漂移 |
| 2026-09-13 | 0.6 | **T1 执行定稿回写**（fix(cache-01)）：新增 4.0"已回写技术决策"节——T1 超时配置化（connect/so/maxWait 各 1000ms，Jedis 5.1.0 用 8 参构造器等价替代）+ 全局熔断（粒度=全局、失败口径=execute 冒出的 CacheException、恢复=半开单探针，阈值/冷却可配）；U-10 随 T1 修复（UNPLANNED_ISSUES 已标注）；执行中新发现 U-11（/start 停机空降级，既有语义）登记 UNPLANNED_ISSUES 留池 |
| 2026-09-13 | 0.7 | **T2 执行定稿回写**（fix(cache-02)）：4.0 追加 T2 技术决策——统一规则（降级读=miss 同款单飞+全量 loader、仅装载不写回/D4，与 miss 共用单飞 key 空间，SingleFlight 零改动）、治理面 10 处降级读分支（单 key 单行查询→全量装载作答删 3 个 *FromDb、批量 targeted+必失败回填→单飞全量作答）、失败语义（异常传播不缓存可重试）、与 T1 熔断正交、LOAD 口径 leader 记一次；验证 JUnit 337 + pytest 124 + 黑洞运行时（20 并发同 key Com_select 差值 8） |
| 2026-09-14 | 0.8 | **T3 执行定稿回写**（fix(cache-03)）：4.0 追加 T3 技术决策——拍板=对外行为保持（用户 2026-09-14），loader 失败抛 DatabaseException（事务模板已包 SQLException），CacheAside 5 装载点捕获转 null（不写空标记不 DEL，miss 跳过 markEmpty，降级共用 loadDegraded），addContent/refreshContent 写路径守卫；N2 治理闭环（4.1 行 N2 对应 T3）；验证 JUnit 348 + pytest 124 + subagent 评审无🔴 |
| 2026-09-14 | 0.9 | **T4-① 执行定稿回写**（fix(cache-04a)）：4.0 追加 T4-① 技术决策——拍板=exists 守卫（对齐 writeSet 260913 先例，非 Lua），markEmpty 守卫"数据 key 不存在才写空标记"+ del(dataKey) 随守卫移除（死代码+竞态危害源），writeSet 空分支定向复用（U-09 T4 定向复用）；残余竞态=exists→setex 毫秒间隙可自愈（已接受）；N3 治理闭环（4.1 行 N3 对应 T4）；验证 JUnit 351（+3 含并发不假空时序测试）+ pytest 124；T4 拆 3 commit 校准 fix(cache-04a/04b/04c) |
| 2026-09-14 | 1.0 | **T4-② 执行定稿回写**（fix(cache-04b)）：4.0 追加 T4-② 技术决策——自愈=addToIndex 写失败 catch 内 best-effort DEL 所属 4 个索引 key 复用既有懒重建（零新增 key，否决脏标记/完整性校验），indexKeysOf 与 lremAndLpush 同源，双层 best-effort；失败三分收敛；残余窗口=持续挂恢复后仍可能不完整（与现状一致，不做 R-10）；N4 治理闭环（4.1 行 N4 对应 T4）；验证 JUnit 353（+2）+ pytest 124 |
