# 下一周期需求与痛点

> 用途：回答"下一周期为什么做这些"——本周期要解决的痛点、候选任务的优先级映射、以及开工前必须拍板的技术决策。
> 状态：**方向已拍板（2026-09-15，R-10）**——本周期 = **缓存体系综合改造**（结构收敛 + 装载反转 + 读路径优化 + 计数入缓存 + 小项打包）；落地范围见 **4.3**（含"明确不做"清单）。结转事项统一编号为 `R-01`~`R-12`（R-10 已拍板）；4.1 的候选痛点 N1~N3（2026-09-15 代码复查）已随方向拍板**全部纳入，纳入项见 4.3**。
> 配套：How（拆任务）见 `目标与任务/NEXT_CYCLE_TASKS.md`（**已拆 T1~T8**；四要素为骨架，执行方案由执行窗口探索细化）。
> 注意：二/三为**结转总账**，不等于本周期范围；本周期做什么、不做什么，以 **4.3** 为准。
> 来源：260914-cache-hardening 周期（第三期「缓存加固」，T1~T6 全部完成，`fix(cache-01)`~`fix(cache-06)`，T4 拆 04a/04b/04c）归档后：结转的未完成需求与未拍板决策（二/三表）+ 三期候选痛点评审不纳入项（N6/N8 升格为 R-08/R-09）+ `UNPLANNED_ISSUES.md` 留池项（U-07 同步为 R-12；U-09/U-11 留池不编号）+ **2026-09-15 代码复查新发现（4.1 的 N1~N3）**。
> 术语约定：**周期 > 任务**。本文档只回答 Why（需求与决策），How（拆任务）在任务清单文档。

***

## 一、结转：仍有效的通用约定（来自已归档周期，随周期继续生效）

| 编号 | 约定 | 说明 |
| ---- | ---- | ---- |
| C-1 | 双文档结构 | 本文档（需求与痛点，决策唯一源）+ 任务清单（执行细节） |
| C-2 | 一任务一窗口一 commit | 默认期望，允许例外需标注；commit message 强制带任务编号 |
| C-3 | commit 语义闭环 | 代码 + 常青文档更新 + 任务清单勾选进**同一 commit** |

> 另有若干"延续性约定"不单独编号，随周期生效（原文见 `archive/目标与任务/260914-cache-hardening/NEXT_CYCLE_NEEDS.md` 一节）：
> ① **红线措辞约定**：红线只列"明显越界"的项，作用是**防跑偏、不把执行 Agent 限制死**（不穷举做法、不做一刀切禁止）；若某条红线会阻碍正确做法（过紧 / 过窄 / 已不适用）——不允许硬扛、也不允许自行放开，**先说明理由申请调整**，获批准后按新口径动手，未获批准则维持原红线（完整表述见 `NEXT_CYCLE_TASKS.md` 二节）；
> ② **编号引用约定**：禁裸编号引用已归档周期元素，引用一律写成 `<周期>/<编号>`（如 `260914/R-02`）；裸编号仅指本文档内部定义的元素；
> ③ **技术红线**：禁 Spring/SpringBoot/MyBatis；不擅改 `@WebServlet` URL / web.xml / IoC 扫描；不为实现便利改业务逻辑语义；
> ④ **不引入 MQ**：异步（若需）用进程内线程池（`ExecutorService`），不引入 RabbitMQ/Kafka/RocketMQ；
> ⑤ **脚本规范**：脚本一律 Python；临时一次性脚本放 `temp_script/`，长期复用/自动化放 `tools/`；
> ⑥ **DDL 备份**：出现表结构改动，执行前先备份库结构与建表语句到 `.docs/DBbackups/`；
> ⑦ **质疑协议（G11）**：执行 Agent 对 Why 层（需求真实性/必要性）与 How 层（任务/验收可操作性）有质疑权、亦有报告义务——四时点触发（开窗阅读 / 动手前复核 / 探索中 / 验收时）× L1~L4 分级动作，质疑记录回写 4.0，**裁决权永远在用户**（完整协议与记录格式见 `NEXT_CYCLE_TASKS.md` 二节；2026-09-15 随模板修订新增）。

***

## 二、结转：未完成的需求（260914-cache-hardening 归档时未关闭）

> **编号体系（本档内部）**：`C-#` 通用约定；**`R-##` 待评审事项**（本节与三节连续编号，评审时按 R 编号点单）；`N#` 本周期新探查候选痛点（4.1）；`T#` 任务（见任务清单文档）。引用**已归档周期**的元素仍写 `<周期>/<编号>`（见一②）；每行"来源"列保留原编号以便追溯，正文不使用带周期前缀的旧编号。

| 编号 | 事项 | 类别 | 来源（归档周期） | 状态 | 说明 |
| ---- | ---- | ---- | ---- | ---- | ---- |
| R-01 | 关注/粉丝计数入缓存 | 需求（方案待拍板） | `260914/R-02`（溯源 `260913/O-9`） | **已纳入本周期（T6）** | Profile 的 followCount/followerCount 是否一并入缓存：关系 Set 是成员、计数是独立 key；注意 SCARD 冷 set 返 0 的坑（证据复核 2026-09-15：`ProfileService.getProfile` 每次从 DB user 表读计数） |
| R-02 | 单飞进程内锁的多实例化 | 需求（架构） | `260914/R-03`（溯源 `260913/分布式`） | 继续延后 | 进程内锁只对单实例有效；当前单 Tomcat 够用，不过度设计；多实例需分布式锁 |
| R-03 | `authorName` 冗余不同步 | 需求（一致性） | `260914/R-06`（溯源 `260913/H9`） | 未处理 | 用户改名后内容缓存仍带旧名，需等 TTL/失效；当前无改名接口，属潜在项 |
| R-04 | 索引全量读 + 拷贝 shuffle | 需求（性能） | `260914/R-07`（溯源 `260913/H10`） | 明确保留 | `LRANGE 0 -1` 全量读保留（推荐 shuffle 对外语义不变）；`getRecommendByFilter` 仍每次去重 + shuffle 全量候选、只取 12 条，数据量大时 O(n) |
| R-05 | follow/like 分域 TTL 真流量复调 | 待数据 | `260914/R-08`（溯源 `260913/TTL`） | 待真流量 | 取参来自本地轻量压测（`temp_script/pressure_cache.py` 双轮 total=21000）；follow 未覆盖真实关系数据，30min 属保守延长并标注"待真流量复调" |
| R-06 | 批量续期测试覆盖缺口 | 测试 | `260914/R-09`（溯源 `260913/T9-Review①②`） | **已纳入本周期（T7）** | ① `getBatch` 续期未覆盖 miss key 场景；② Like/Follow 批量 pipeline 续期未直接断言（单 key hit-data 已覆盖） |
| R-07 | 是否需定期重建索引防长尾漂移 | 待评估 | `260914/R-10`（溯源 `260913/O-6-尾巴`） | **已评估（2026-09-16，T5）：无需定期重建** | 260913 周期 O-6 明示"如需定期重建索引防长尾漂移，另行登记评估"——**2026-09-16 T5 评估结论**：`lrem(k,0,id)`（删全部出现）+ `lpush` 写即去重 → id 每 key 至多 1 条、索引大小 ≤ 活跃内容数，`removeContent` 对全部 4 索引 key 幂等 LREM → 正常操作**无系统性长尾漂移**；仅删除 LREM 失败（停机窗口）残留有界脏 id，T5 惰性探测后每条至多消耗 1 个探测位，索引 key 缺失/启动全量重建即收敛 |
| R-08 | 点赞成员回填按"该内容的全部点赞者"装载 | 需求（设计反转待拍板） | `260914/N6` | **已纳入本周期（T4，2026-09-16 已完成）** | 内存/延迟随点赞量放大（10 万赞视频一次判断加载 10 万行；`/start` 推荐位 12 条冷 key 时一次 12 倍量级；Redis 挂时降级逐条全量查最糟）；**已按用户 2026-09-15 拍板落地**：内容+评论成员 key 全反转 `content:likeSet`/`comment:likeSet` → `user:likeSet`/`user:commentLikeSet`（与 `user:following` 同构），装载量由"内容点赞者数"变"该用户点赞数"；证据复核 2026-09-15：批量 miss 对每个 cid 各来一次全量 likers 装载（`backfillBatchContentLikers`），answer 查询与回填查询为两套——反转后收敛为单 set 一次全量 |
| R-09 | 缓存 JSON 无格式版本 | 需求（条件触发） | `260914/N8` | **已纳入本周期（T7 一行修）** | DTO 字段删/改名后旧条目反序列化失败且降级路径不写回（Jackson 未关 `FAIL_ON_UNKNOWN_PROPERTIES`，`CacheAside` 脏 JSON → DEGRADE 直接 loader）→ 这批 key 在各自 TTL 内每次读都走 DB；证据复核 2026-09-15：`JacksonCodec` MAPPER 未 disable 该 feature |

> **已完成、不结转**（供对照）：三期 T1~T6 全部——超时显式化 + 全局熔断（U-10、N1）/ 降级接入单飞（N1）/ 负缓存 DatabaseException 契约（N2）/ 空标记 exists 守卫（N3）/ 索引写失败自愈（N4）/ 条件写 Lua 原子化（N7）/ 启动加载治理（N5 + R-01 全量+工程化 + R-04 N+1 根治，T5）/ U-08 key 同源归一（T6）；旧 R-05（缓存对象共享可变引用）**2026-09-15 归档复核确认关闭**——`CacheAside` 三条读路径（`read` / `get`→`getInternal` / `getBatch`）均走 `codec.fromJson` 每次产生新对象，Redis 值语义不持有 Java 引用；N9（观测只能看惰性日志）为已接受取舍（`260913/T7` 拍板），不结转。

***

## 三、结转：未拍板的决策（进任务清单前须拍板）

> 编号接续二（`R-##` 连续）；本表是"必须拍板才能开工"的决策项，二表是"待消化的事实项"。

| 编号 | 待拍板事项 | 来源（归档周期） | 当前状态 | 说明 |
| ---- | ---- | ---- | ---- | ---- |
| R-10 | **第四期方向**（本周期主线做什么） | 本档新增 | **已拍板（2026-09-15）**：缓存体系综合改造——U-09 结构收敛 + R-08 点赞装载反转 + 读路径/降级优化（N1/N2）+ R-01 计数入缓存 + 小项打包（用户定调：第四期仍只做缓存体系，能拆的任务均排进本周期，一任务一窗口单独探索/修改/review） | 落地范围见 **4.3**；已拆任务见 `NEXT_CYCLE_TASKS.md` 的 T1~T8；D 方向 feed 延后至第五期（前置条件已满足——三期 T1~T6 全关闭，PR 合并状态待用户在第五期立项前确认） |
| R-11 | 优惠券抢购限流 | `260914/R-12`（溯源 `260913/P6`） | 默认不做 | 历次周期均列"范围外（默认不做）"；需用户确认才纳入、才拆任务；改动面 `CouponService`/`CouponController` |
| R-12 | content ↔ comment 包层循环依赖 | `260914/R-13`（即 `UNPLANNED_ISSUES.md` 的 U-07） | 待定（三期复查：**仍在**） | content 域共享组件（ContentCacheDTO 等）被 comment 域引用，content 又引用 comment 的 `CommentService`；**非 IoC/Bean 环**，仅包架构不纯净、Java 允许；三期 T3 已移除 `CommentService→ContentCacheManager` 依赖，包层环未解除 |

> R-01 同时是"未完成需求"（见二）和"未拍板决策"——需先决定是否补计数缓存，再定方案。
> 已拍板、仅留"未来再评估"口子的（不计入未拍板）：`260913/O-7` 搜索维持 FULLTEXT 直查——"若未来要动，另行登记评估"。
> `UNPLANNED_ISSUES.md` 留池不编号项：U-09（缓存行为 5 份重复实现的全量收口，三期明确不做、只在 T2/T4 定向复用）、U-11（Redis 停机 `/start` 空推荐，既有语义）——是否纳入本周期，评审时与本表一并过。

***

## 四、本周期痛点与目标方案

> 结构：**4.1 痛点清单**（代码复查发现）→ **4.2 候选方向与拍板结论** → **4.3 本周期范围与反面清单**；**4.0 已回写技术决策与质疑记录**（执行中拍板/质疑追加）。**待方向拍板（R-10）后填写。**

### 4.0 已回写技术决策与质疑记录（执行中拍板/质疑，按任务追加）

> 每落地一个任务的技术决策在此追加一段（G7：窗口内新决策先回写本节的"已定/待定"状态，不许自行拍板）：任务编号 + commit 前缀 + 拍板日期 + 拍板取向与理由 + 关键实现点 + 落点（类/模块）+ 验证摘要。
> **质疑记录（G11）**：Why 层质疑（前提证伪 / 已满足 / 必要性存疑 / 内部矛盾）在此登记——L2 带疑继续、L3 暂停必须留痕（L1 仅记录落任务清单"执行回写"）；**裁决权在用户**，裁决结果即一条新决策，同节留痕。

**T2（`refactor(cache-02)`，2026-09-15 执行定稿）**：LikeCacheService 收口 SetCache + content/comment 孪生合并。**取向**：行为零变化重构——Set 成员读路径（单成员三态 / 批量多 set 单成员）全部改走 T1 组件 `SetCache`；孪生方法按 id 维度参数化收敛为单实现。**关键实现点**：`isXxxLiked`/`batchIsXxxLiked` → `SetCache.isMember`/`batchKeysIsMember`（调用点 `idToKey`/`keyToId` 双 Map 桥接 key↔id，不改 CacheKeys）；计数合并为 `getLikeCount(key, loader)`（CacheAside 语义含兜底 LOAD 打点不变）；loader 与批量 answer 统一为类内 `DaoQuery<T>`/`BatchQuery` 参数化 helper（用户可见异常文案逐字不变）；删除与 SetCache 重复的 scan/write/backfill/degrade 样板（628→420 行）。**L2 差异（T1 已登记，确认收敛）**：批量 miss 回填全量成员 loader 失败由"上抛 500"变 best-effort（DB 答案照常返回、仅记日志，4.2 缓存失败不得导致业务失败）；批量 dbAnswer 失败仍上抛；单成员 miss/降级 loader 失败仍上抛。**红线遵守**：Lua 条件写 4 方法 / `deleteXxxLike` / `LikeService` 及业务调用方签名零改动；key 命名/三态顺序/打点口径/TTL/降级语义不变。**验证**：JUnit **397 例全绿**（395+2）无回归；pytest all **124 passed**；无 DDL、无新依赖。

**T3（`refactor(cache-03)`，2026-09-15 执行定稿）**：FollowCache 读路径收口 SetCache。**取向**：行为零变化重构——读路径（单成员三态 / 单 set 批量判定 / 全量列表）全部改走 T1 组件 `SetCache`；写路径（MULTI 双写 + 失败双 DEL）为 follow 特有双 key 原子语义，**不在收口面**，`cacheFollow`/`cacheUnfollow`/`probePair`/`invalidateKeysQuietly` 逐字保持。**关键实现点**：`isFollowing` → `SetCache.isMember`；`batchIsFollowing` → `SetCache.batchIsMember`（单 set 多成员·Follow 形态，"answer 查询 + 回填全量两趟"由组件内保持）；`getFollowingIds/getFollowerIds` → `SetCache.getMembers` + `sortIds` 唯一包装点统一升序（hit-data/miss/降级三路径一致，防热/冷读顺序波动，落实 6.13 getMembers 排序交接提示）；构造注入 `SingleFlight`→`SetCache`；删除与 SetCache 重复的 `scanSet`/`writeSet`/`loadViaSingleFlight`/`getSetMembers`/`toSortedLongs`（524→326 行）；关注/粉丝列表孪生 loader 收敛为 `DaoQuery<T>` 参数化 helper（日志与用户可见异常文案逐字不变），批量 answer 单处使用保留独立方法。**行为对照**：批量 miss 回填 FollowCache **现状已是 best-effort**（与 SetCache 契约一致，无 T2 那种 L2 差异）；批量 dbAnswer 失败仍上抛；单成员 miss/降级 loader 失败仍上抛；空集回填统一 `cacheAside.markEmpty`（exists 守卫同源）；降级经 SetCache 单飞装载作答不写回（D4）。**红线遵守**：key/三态顺序/空标记 TTL/打点口径/排序确定性不变；`FollowService`/`ProfileService`/`FeedService`/`ContentStatusFiller` 调用方签名零感知；无 DDL、无新依赖。**验证**：JUnit **397 例全绿**（FollowCacheTest 29 例平移适配）无回归；**pytest all 124 passed**（本周期收口后第一个全量回归点）。

**T4（`refactor(cache-04)`，2026-09-16 执行定稿；方案 2026-09-15 用户拍板：内容+评论全反转）**：点赞成员装载反转（R-08）。**取向**：成员缓存 key 由内容/评论维度（`content:likeSet`/`comment:likeSet`，Set<userId>，miss/降级装载量=该内容/评论点赞者数，随热度放大——10 万赞爆款一次判断装载 10 万行）反转为**用户维度**（`user:likeSet:{userId}` Set<contentId> / `user:commentLikeSet:{userId}` Set<commentId>，装载量=该用户点赞数，与 user:following 同构），「我是否点过赞」与热门内容解耦。**关键实现点**：`CacheKeys` 新增 `userLikeSet`/`userCommentLikeSet`、删除旧两工厂（不双写，like TTL 15min 自然回收）；`domainOf` 在 `user:`→FOLLOW 之前插入 `user:commentLike`/`user:like`→LIKE（统计归位 LIKE 桶，T8 预期项前置）；读路径 `isXxxLiked` → `SetCache.isMember`（loader=用户全量点赞 `findLikedContentIdsByUser`/`findLikedCommentIdsByUser`），批量 → `SetCache.batchIsMember` **单 set 多成员**（替代多 set 单成员 `batchKeysIsMember`，命令/趟 12×4 → 1 趟(2+N+1)，装载 12 次内容全量 → 1 次用户全量）；写路径 Lua 原子条件写**脚本常量零改动**、仅 KEYS/ARGV 换维度（likeContent KEYS=[user:likeSet:{userId}, content:likeCount:{contentId}, empty:user:likeSet:{userId}]、ARGV=[contentId]；unlike 同减空标记；评论对称）；`deleteContentLike`/`deleteCommentLike` 改**仅失效计数 key**（成员残留不清理亦无害：物理删除 DB 点赞行一并删除+id 不复用+UI 无查询路径永不外显；软删隐藏保留点赞记录、恢复读自愈对齐）；DAO 新增 2 个全量查询、删除 `findLikerIdsBy*`（死代码）。**红线遵守**：计数 key（content/comment:likeCount）不动、`LikeService`/Controllers 签名零感知、三态/空标记 TTL/单飞/降级/批量回填 best-effort 语义由 SetCache 承接不变、无 DDL、无新依赖。**已知取舍（拍板接受）**：单用户点赞量极大的极端账号 miss 装载反向放大；删除后残留成员不清理。**验证**：JUnit **403 例全绿**（LikeCacheServiceTest 28→32、CacheKeysTest 11→13）+ pytest all **124 passed** + subagent review 通过（无🔴，🟡4 全落实）。

**T5（`refactor(cache-05)`，2026-09-16 执行定稿；无方案拍板点）**：推荐读路径惰性探测（N2）+ 索引懒重建失败冷却退避（N1）+ R-07 附带评估。**取向**：零对外行为变化的读路径优化——推荐从"全量候选批量探测"收敛为"按 shuffle 序逐个惰性探测、凑满 limit 即止"；重建失败后进程内冷却退避，停机期间不再逐请求 DB 全表查询。**关键实现点**：`ContentCache.getRecommendByFilter` 内 `getContentsBatch(distinctIds)` → 逐个 `getContent(contentId)`（复用既有 public API，`getContentsBatch`/`getBatch`/Feed/Profile 零改动）；`ensureIndex` 重建失败记进程内 volatile 单时间戳 `lastFailedRebuildAtMillis`、成功清 0，冷却窗口（`cache.content.indexRebuildCooldownMillis=10000`，对齐熔断冷却先例 `redis.breaker.cooldownMillis`）内跳过 exists+重建（零 Redis 零 DB），冷却过期自然重试；`rebuildIndexes` 返回 boolean（Redis 写失败=false，失败信号从不可观测变可观测；init 路径忽略返回值）；失败口径 = `rebuildIndexes` false（exists 检查失败不单独记）；ContentCache 双构造（@InjectConstructor + 包级注入冷却值）+ AppConfig 新 getter。**均匀性论证**：shuffle 仍在全量去重 id 列表上一次性执行，返回集 = "shuffle 序前 limit 个非 null" 与批量读后按同序收集逐位一致——惰性探测只改"探测多少"不改"取哪些"，null 跳过语义等价（最坏全探测 = 现状退化场景）。**R-07 评估结论（回写二表）**：LREM(count=0) 删全部出现 + LPUSH 写即去重、removeContent 对全部 4 索引 key 幂等 LREM → 正常操作无系统性长尾漂移、**无需定期重建**；仅删除 LREM 失败（停机窗口）残留有界脏 id，惰性探测后每条至多 1 个探测位。**红线遵守**：`readIndex` 全量 LRANGE 保留（R-04 本体不做）、"停机空推荐"语义不变（U-11 本体不做）、@WebServlet/web.xml/IoC 零改动、无 DDL、无新依赖。**验证**：JUnit **407 例全绿**（surefire 403 + pool 4）无回归；**pytest all 124 passed**；运行时黑洞验证（`temp_script/verify_cache05_rebuild_backoff.py`）：20 次 /start 全 200 + 空推荐、**Δ Com_select = 0**（无退避应 ≈20）。

**质疑记录**：T2 窗口 L1 仅记录 2 条（已载入任务清单 T2 执行回写，不打断）：① 日志标签 `contentId=`/`commentId=` 统一为 `id=`（保留 内容/评论 词，非对外契约）；② 常青文档 header 版本号存在 T1 未 bump 的滞后（2.16 表行后 header 仍 2.14，T1 的 6.13 无对应 header/12 行）——已随 T2 bump 至 2.17 并登记观察，留 T8 收尾统一对齐。**T3 窗口 L1 仅记录 1 条**（已载入任务清单 T3 执行回写）：域类内部日志措辞（"关注状态缓存读失败"等）统一为 SetCache 措辞，非对外契约（T2 同先例，无 L2/L3）。**T4 窗口 L1 仅记录 1 条**：`SetCache` 6.13 节注释残留旧 key 名「逐 contentLikeSet 判定」（组件零改动红线内），已在方法 javadoc 及节注释标注 T4 后该 API 停用预留，非对外契约——已随 review 🟡 落实。**T5 窗口 L1 仅记录 1 条（登记不修，超范围）**：`loadAllWithoutMedia` DB 装载失败返回空列表 → `rebuildIndexes(空)` 在 Redis 正常时会 SCAN+DEL 全量索引（既有行为——DB 瞬断清索引；零行为变化红线不随 T5 改动，留观察）；**反向边同登记**：空库（DB 无内容）时重建"成功"不记冷却 → 冷却对空库场景不生效，每请求仍全表查询（空表开销可忽略、非停机语义，留观察）。

### 4.1 候选痛点（2026-09-15 代码复查，R-10 拍板后已纳入）

> 编号 `N1`~`N3` 为**本档内部编号**（与已归档周期的 `N*` 编号体系无关，历史引用写 `<周期>/N#`）。证据均为 2026-09-15 复核定位（文件:行）；执行窗口动手前按 G11 第 (0) 项复核证据，不成立 → L3 暂停并登记质疑。
> 其中 N1/N2 为本次复查**新发现**；N3 为 U-09（`UNPLANNED_ISSUES.md`）的证据具体化（`N3 ↔ U-09`）。

| 编号 | 问题 | 证据（可复核定位） | 不改会怎样（具体场景） |
| ---- | ---- | ---- | ---- |
| N1 | **`ensureIndex` 降级态隐藏放量**（U-11 的加重面）：Redis 停机期间每次推荐读都会重试索引全量重建，无失败退避——单飞只防并发重叠，不防串行重复 | `ContentCache.ensureIndex`（L406-424）：`exists` 熔断快速失败 → catch"视为无索引" → `singleFlight` 重建（`loadAllWithoutMedia` **DB 全表查询** + `rebuildIndexes` 写 Redis 必失败）→ 条目移除 → 下一请求从头重来；三期 T2 治理 10 处降级分支时排除了 `ensureIndex`（理由"已单飞"），但单飞 ≠ 退避 | Redis 停机 10 分钟，期间每个 `/start` 请求 = 1 次 DB 全表查询 + 空推荐——**DB 压力随停机时长线性涨**，且查询结果全部作废（推荐仍为空）。与 U-11"空推荐"叠加成完整画像 |
| N2 | **推荐读对全量候选批量探测**（R-04 的加重证据）：shuffle 只需"全量 id 的随机序"，不需"全量内容探测"，现状却对全部候选发 pipeline 探测 | `ContentCache.getRecommendByFilter`（L135-149）：全量 `LRANGE` → 去重拷贝 → 全量 shuffle → `getContentsBatch(distinctIds)` 对**全部候选**一趟 pipeline EXISTS+GET+EXPIRE → 才按序收集到 limit=12 | 候选 1 万条 = 每次推荐约 3 万命令的 pipeline（EXISTS+GET+EXPIRE 各 1 万），只为取 12 条；按 shuffle 序**惰性探测**（凑满 limit 即止）可把探测量降到 ~12+跳过量，零行为变化 |
| N3 | **域缓存孪生复制**（U-09 证据具体化）：原生 Set 缓存行为 5 份重复实现，且 LikeCacheService 内部 content/comment 成对复制 | `LikeCacheService`（628 行）：load×4 / write×2 / backfill×2 / degrade×2 / batch×2 成对；`FollowCache`（524 行）与 LikeCacheService 跨类逐字重复：`scanSet`/`scanLikeSet`、`writeSet`/`writeXxxLikers`、`loadViaSingleFlight`/`loadLikersViaSingleFlight`、批量三态扫描结构、loader 12 行样板 ×8 | 每治一个缓存行为缺陷要改多处——历史实证：三期 T4-① markEmpty 竞态改 5 处调用方、T2 降级治理改 10 处分支，同一个行为修复每次花 5 遍钱；下一个缺陷仍会这样 |

### 4.2 候选方向（R-10 已拍板，2026-09-15）

| 候选 | 内容 | 依据 | 结论 |
| ---- | ---- | ---- | ---- |
| **缓存体系综合改造** | 结构收敛（U-09/N3，治"改一处缺陷花五遍钱"）→ 点赞装载反转（R-08，装载量从"内容点赞数"变"用户点赞数"）→ 读路径/降级优化（N1/N2 + R-07 评估）→ 计数入缓存（R-01）→ 小项打包（R-09 一行修 + R-06 补测） | 2026-09-15 代码复查（N1~N3）+ 留池项证据复核；用户定调"第四期仍只做缓存体系，能拆的任务均排进本周期，一任务一窗口单独探索/修改/review" | ✅ **本周期采纳** |
| D 方向 feed 流改造 | 关注流聚合改造，含 feed 聚合缓存 | 前置条件已满足（三期完成；PR 合并状态待确认） | ⏸ **延后至第五期**（用户 2026-09-15 定：第四期仍只做缓存体系） |
| 优惠券抢购限流（R-11）/ content↔comment 包层环（R-12） | 非缓存主题 | — | ⏸ 维持留池/待定，不随本周期 |

### 4.3 本周期范围（R-10 拍板结果，2026-09-15）

**主题**：缓存体系综合改造——"结构收敛 + 装载反转 + 读路径优化 + 补缺口"。

| 纳入 | 对应编号 | 落到任务 | 一句话 |
| ---- | ---- | ---- | ---- |
| Set 缓存行为收敛进基建（组件新建） | U-09、N3 | T1 | 原生 Set 三态读/回填/批量/降级装载收敛为 cache 基建一处 |
| LikeCacheService 收口 + content/comment 孪生合并 | U-09、N3 | T2 | 628 行域缓存接入基建，成对方法合并 |
| FollowCache 收口 | U-09、N3 | T3 | 524 行域缓存接入基建（MULTI 双写等 follow 特有逻辑保持） |
| 点赞成员装载反转 | R-08（原 `260914/N6`） | T4 | `content:likeSet` → `user:likeSet`（与 `user:following` 同构），方案开工前拍板 |
| 推荐读路径优化 | N2、R-04 | T5 | shuffle 序惰性探测（全量候选探测 → ~limit+跳过量） |
| 索引重建失败退避 | N1、U-11 加重面 | T5 | 重建失败后进程内冷却，停机期间不再逐请求全表查询 |
| R-07 索引长尾漂移评估 | R-07 | T5（附带） | LREM+LPUSH 语义下确认是否漂移，结论回写本文档 |
| 关注/粉丝计数入缓存 | R-01 | T6 | followCount/followerCount 独立计数 key，Profile 读路径接入；SCARD 冷 set 返 0 的坑 |
| JSON 格式版本兼容 + 批量续期补测 | R-09（原 `260914/N8`）、R-06 | T7 | Jackson 关 `FAIL_ON_UNKNOWN_PROPERTIES`；getBatch miss key 续期与 Like/Follow 批量 pipeline 续期断言 |
| 收尾 | 全周期 | T8 | 巡检 + 全量回归 + 常青文档同步 + 覆盖率地图 |

**本周期明确不做（反面清单，与"纳入"同等重要）**：

| 不做 | 原因 / 去向 |
| ---- | ---- |
| D 方向 feed 流改造 | 用户定调第四期只做缓存体系 → **第五期候选**（前置条件已满足，PR 合并状态立项前确认） |
| U-11 "/start 停机 DB 兜底推荐" | **对外行为变更**（空推荐 → DB 兜底推荐），未拍板；本期只修零行为的 N1 退避，U-11 语义留池 `UNPLANNED_ISSUES.md` |
| R-02 单飞分布式化 | 继续延后（单实例够用，不过度设计） |
| R-03 `authorName` 冗余同步 | 无改名接口、无对外影响路径；待有改名需求时随需求做 |
| R-04 索引全量读 + 拷贝 shuffle 本体 | `LRANGE 0 -1` + 全量 shuffle 保留（推荐随机语义依赖）；本期只做 N2 的"探测面"收缩，读面不收 |
| R-05 follow/like 分域 TTL 复调 | 待真流量数据，本期无数据源 |
| R-11 优惠券限流 / R-12 包层环 | 非缓存主题，维持留池/待定 |
| N9 观测增强（重置/端点/持久化） | 已接受取舍（`260913/T7` 拍板） |

***

## 五、本周期范围与边界（指针节，范围一律以 4.3 为准）

> 本节**不单独维护范围清单**，避免与 4.3 口径分叉：本周期做什么、不做什么（含反面清单），一律以 **4.3 为唯一落点**。本节只保留 4.3 不覆盖的两类边界：

- **留池未排**：见二——是否纳入本周期，评审时决定。
- **禁止（沿用技术红线）**：Spring/SpringBoot/MyBatis；擅改 `@WebServlet` URL、web.xml、IoC 扫描；为实现便利改业务逻辑语义。

***

## 六、变更记录

| 日期 | 版本 | 内容 |
| ---- | ---- | ---- |
| 2026-09-16 | 0.6 | **T5 执行定稿回写**：4.0 追加 T5 技术决策（N2 惰性探测 + N1 冷却退避 + R-07 评估结论——LREM+LPUSH 无系统性漂移无需定期重建）+ T5 窗口 L1 质疑记录 1 条（loadAllWithoutMedia DB 失败空列表清索引既有行为，登记不修）+ 二表 R-07 状态列 →「已评估（2026-09-16，T5）：无需定期重建」并补结论 |
| 2026-09-16 | 0.5 | **T4 执行定稿回写**：4.0 追加 T4 技术决策（R-08 点赞成员装载反转——内容+评论全反转：user:likeSet/user:commentLikeSet 落点、批量收敛单 set 多成员、Lua 仅换 KEYS·ARGV、失效仅计数 key、DAO 增删、已知取舍；JUnit 403 / pytest 124）+ T4 窗口 L1 质疑记录 1 条（SetCache 6.13 节注释旧 key 名残留，标注停用预留非契约）+ 二表 R-08 状态列标注「T4 已完成」 |
| 2026-09-15 | 0.4 | **T3 执行定稿回写**：4.0 追加 T3 技术决策（读路径收口 SetCache 落点、写路径 MULTI 双写零改动、批量回填本已 best-effort 无 L2 差异、L1 质疑记录 1 条——日志措辞统一）+ T3 窗口 L1 质疑追加（域类日志措辞统一为 SetCache 措辞，非对外契约） |
| 2026-09-15 | 0.3 | **T2 执行定稿回写**：4.0 追加 T2 技术决策（SetCache 收口 + 孪生合并落点、L2 差异确认收敛、红线遵守、JUnit 397/pytest 124）+ T2 窗口 L1 质疑记录 2 条（日志标签统一 / 常青 header 版本滞后留 T8） |
| 2026-09-15 | 0.2 | **R-10 拍板回写**（方向 = 缓存体系综合改造）：① 状态行改为"方向已拍板"；② 4.1 填入 2026-09-15 代码复查痛点 N1~N3（N1 ensureIndex 降级态隐藏放量·U-11 加重面 / N2 推荐读对全量候选批量探测·R-04 加重证据 / N3 域缓存孪生复制·U-09 证据具体化，均带文件:行证据）；③ 4.2 填入拍板结论（缓存体系综合改造采纳；D 方向 feed 延后至第五期；R-11/R-12 非缓存主题不随行）；④ 4.3 填入范围（纳入 9 项 → T1~T8 映射 + "明确不做"反面清单 8 项）；⑤ 二表 R-01/R-06/R-07/R-08/R-09 状态列标注纳入去向（含 2026-09-15 证据复核：ProfileService 计数 DB 读、backfillBatchContentLikers 逐 cid 全量、JacksonCodec 未关 FAIL_ON_UNKNOWN_PROPERTIES）；⑥ 配套 `NEXT_CYCLE_TASKS.md` 已拆 T1~T8 |
| 2026-09-15 | 0.1 | 新建本文档（结转稿）：接 260914-cache-hardening 归档周期（第三期「缓存加固」T1~T6 全部完成，`fix(cache-01)`~`fix(cache-06)`，T4 拆 04a/04b/04c），结转 ① 通用约定（C-1~C-3 + ⑦ 条延续约定，⑦ 质疑协议 G11 为 2026-09-15 模板新增）、② 未完成需求 9 项（R-01 计数入缓存 / R-02 分布式 / R-03 authorName 冗余 / R-04 索引 shuffle / R-05 TTL 真流量复调 / R-06 批量续期测试缺口 / R-07 索引重建评估 / R-08 点赞成员全量装载（三期 N6 升格）/ R-09 JSON 格式版本（三期 N8 升格））、③ 未拍板决策 3 项（R-10 第四期方向 / R-11 优惠券限流 / R-12 U-07 包层环）；四节留空待方向拍板；旧 R-05（共享可变引用）经 2026-09-15 复核确认关闭（CacheAside 三读路径全走 fromJson）不结转；U-09/U-11 留 `UNPLANNED_ISSUES.md` 池不编号 |
