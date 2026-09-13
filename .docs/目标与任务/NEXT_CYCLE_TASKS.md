# 下一周期任务清单

> 关联文档：目标与任务/NEXT_CYCLE_NEEDS.md（决策唯一源；此文件为执行细节）  
> 状态：**已拆任务（2026-09-13）**——R-11 拍板为「**缓存加固**（缓存韧性 + 启动加载治理）」，本周期 **6 个任务 T1~T6**（草稿态）。四要素为骨架，**执行方案由执行窗口的 Agent 探索细化**（G5 允许回写）。  
> 工作流：每个任务开独立窗口执行；"任务清单 + 需求与痛点"为窗口间唯一交接载体。  
> 来源：260913-cache-architecture 周期（C 方向缓存改造 T1~T9 全部完成）归档后，代码复查发现的失效路径缺陷（NEEDS 4.1 的 N1~N9 + 已登记的 U-08/U-10）+ 留池项 R-01/R-04。  
> **本周期明确不做**：D 方向 feed 流改造（**前置条件 = 缓存加固完成并合并 PR**，用户 2026-09-13 定）、U-09 全量收口、N6/N8/N9、R-02/R-03/R-05~R-10 —— 完整清单见 NEEDS 4.3。

---

## 一、周期约定（R-11 拍板后已校准）

| 编号  | 约定                   | 内容                                                                                                                                                                                       |
| --- | -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| G1  | 一任务一窗口一 commit（默认期望） | 每个任务开独立窗口，默认期望 1 个 commit；因任务内部依赖需拆多 commit 或小任务合并时，**在该任务详情标注**；commit message 强制带任务编号，本周期前缀 = **`fix(cache-0N)`**（T1→`cache-01` … T6→`cache-06`；引用任务一律用 `T1`~`T6`，不用 commit scope）              |
| G2  | 开窗协议（输入）             | 新窗口顺序读取：① `.docs/INDEX.md` → ② `目标与任务/NEXT_CYCLE_NEEDS.md`（**决策节必读**：C-1~C-3、R-01~R-13、4.3 本周期范围、4.1 的 N1~N9）→ ③ 本任务清单当前任务 → ④ 常青文档（`常青/CURRENT_ARCHITECTURE.md` 六.Redis 设计 + `常青/BUSINESS_FLOW.md` 3.1 缓存机制）→ ⑤ 上一个任务 commit |
| G3  | 收窗协议（输出）             | ① **跑测试与反馈**：每任务 = `mvn compile` + 相关 JUnit + **相关端点 pytest 回归**；**`pytest all` 全量在 T5（启动路径影响面大）与 T6（收尾）各跑一次**；测试执行与回传依实际窗口环境安排，不指定角色 ② 勾选任务清单状态 → ③ 涉及架构/业务改动时同步常青文档 → ④ 提交                                |
| G4  | commit 语义闭环          | 代码改动 + 其对应常青文档更新 + 任务清单勾选进同一 commit；message 强制带任务编号                                                                                                                                        |
| G5  | 超范围暂停规则              | 执行中发现需求歧义、或任务实际远超预期 → 停在第一个决策点，回写任务清单（拆/改），不得硬扛、不得擅自扩大范围                                                                                                                                 |
| G6  | 评审与返工                | 评审以"任务验收标准 + 测试结果"为准；返工记录在任务清单；连续返工 ≥2 次 → 返回周期设计重新评估                                                                                                                                    |
| G7  | 决策唯一源                | `NEXT_CYCLE_NEEDS.md` 决策节为唯一决策源；窗口内发现新决策 → 回写该节并标记"已定/待定"，不许自行拍板                                                                                                                        |
| G8  | 分支与合并                | **开分支 / 合并回 integration / 合入 master 均由用户手动执行**；任务窗口只负责本任务的代码、测试与 commit（G1），不自行创建/切换分支、不合并                                                                                              |
| G9  | DDL 备份               | **本周期无 DDL**（缓存层改造，不涉及表结构；已在任务总览显式标注）。若出现例外 → 执行前先备份库结构与建表语句到 `.docs/DBbackups/`，并向用户报备                                                                                |
| G10 | 脚本规范                 | 脚本一律 Python；临时一次性脚本放 `temp_script/`，长期复用/自动化脚本放 `tools/`（工具统一入口 `tools/tv.py`）                                                                                                            |

---

## 二、任务清单理念与任务模板

> **任务清单理念（2026-09-13 用户明确，本周期及后续遵循）**  
> 本清单只回答"**要做什么、不做什么**"，**不提前过度详细设计**——四要素是骨架，具体执行方案由执行窗口的 Agent 探索细化。  
> **红线边界的目的是"防跑偏"，不是把 Agent 限制死**：因此只列"明显越界"的项，不穷举做法；执行中若发现红线本身阻碍了正确做法，按下方"红线措辞约定"先申请再动手。  
> 反面清单同样是清单的一部分：**"明确不做"要写出来**，避免 Agent 顺手扩张。

> **任务模板（每任务必含四要素）**：入口线索（从哪找）+ 红线边界（别碰什么）+ 强制探索步骤（动手前先确认什么）+ 验收（怎么算完成）。执行 Agent 允许动态调整，但**调整前先回写任务清单/需求文档（G5/G7），再动手**。

> **红线措辞约定（延续）**：红线只列"明显越界"的项，作用是**防跑偏，不是把执行 Agent 限制死**——不穷举做法、不做"一刀切禁止"。  
> 若执行中发现**某条红线会阻碍正确做法**（过紧、过窄、或已不适用）：不允许硬扛，也不允许自行放开，**先向用户说明理由并申请调整**；获批准后按新口径动手，未获批准则维持原红线。

> **编号引用约定（延续）**：禁裸编号引用已归档周期元素（引用一律写 `<周期>/<编号>`，如 `260913/O-5`）；裸编号仅指本档内部定义的元素（`T1`~`T6`）与 `NEXT_CYCLE_NEEDS.md` 内部元素（C-1~C-3 / R-01~R-13 / N1~N9）。执行中发现引用歧义 → 回写清单用文字澄清，不得自行猜义。

```markdown
### T1 任务标题

* **入口线索**：…
* **红线边界**：（只列"明显越界"的项，作用是防跑偏；若某条红线会阻碍正确做法 → 先说明理由申请调整，获批准后按新口径动手）
* **强制探索步骤**：动刀前先 (1) 检索… (2) 确认… (3) 若清单未覆盖 → 回写本文档再动手
* **验收**：…
```

---

## 三、任务总览（本周期 6 任务）

> **DDL 标注（G9 要求）**：本周期**无 DDL**——6 个任务均只动 Java 代码与配置，不涉及表结构变更。

| 编号 | 标题 | 对应候选 | 依赖 | 验收关键（动态） | 期望 commit 主题 | 状态 |
| -- | -- | ---- | -- | -------- | ------------ | -- |
| T1 | 韧性底座：Redis 超时 + 快速失败 | U-10、N1 | — | Redis 停机时缓存路径**快速失败**（不再逐请求等连接超时）；恢复后自动回到正常缓存路径；JUnit + 相关 pytest 绿 | `fix(cache-01)` | 已完成（2026-09-13） |
| T2 | 降级不放量：降级路径接入单飞 | N1 | T1（软） | Redis 停机时**同一 key 的并发读仍只打一次 DB**；`SingleFlight` 失败清理语义不被破坏 | `fix(cache-02)` | 已完成（2026-09-13） |
| T3 | 负缓存治理：区分"确认无数据"与"加载失败" | N2 | T2（软） | DB 瞬时失败**不再写 60s 空标记、不 DEL 既有数据 key**；新增单测覆盖；三态语义与对外错误约定不变 | `fix(cache-03)` | 草稿 |
| T4 | 写路径的失败与竞态治理 | N3、N4、N7 | —（独立，可并行窗口） | 空标记写入守卫生效（并发不回填假空）；索引写失败可自愈或至少可检测；条件写竞态有明确结论（修掉 or 记录为已接受）；**含 3 项，允许拆多 commit（G1 例外标注）** | `fix(cache-04)` | 草稿 |
| T5 | 启动加载治理 | N5、R-01、R-04 | T1~T4（软） | Redis 写入**移出 DB 事务**；批量操作 pipeline 化、启动往返次数显著下降（前后对比）；**开工前须用户拍板 R-01 取向**；`pytest all` 全绿 | `fix(cache-05)` | 草稿 |
| T6 | 收尾 | U-08 + 全周期 | T1~T5 | U-08 消除（索引 key 生成与解析同源）；T1~T5 无残留；常青文档同步；覆盖率地图 rerun 无回归；`pytest all` 全绿 | `fix(cache-06)` | 草稿 |

> 状态取值：草稿 / 待执行 / 执行中 / 已完成 / 搁置。

> **顺序理由**：T1 先行——没有"快速失败"时，降级/负缓存的行为都被"每请求等连接超时"掩盖，前后对比也测不准；T2、T3 依次收紧降级路径的"不放量"与"不撒谎"，三者同属"Redis 不可用时系统怎么表现"；T4 是写入侧独立主题（守卫/自愈/原子性），不依赖 T1~T3，可并行开窗；T5 动启动路径，风险最高、影响面最大，放在韧性修好之后（否则启动出问题会与韧性缺陷互相掩盖）；T6 收尾统一回归与文档。

> **回填要求**：任务执行后在本节与"四、任务详情"同步状态与"执行回写"；拆/改任务必须在"对应候选"列保留 NEEDS 编号，保持 Why→How 可追溯。

---

## 四、任务详情

> **共通注（本周期通用）**：缓存仅作加速器，**任何缓存失败不得导致业务失败**（260913 周期 4.2 确立，本周期不破）；技术红线沿用——禁 Spring/SpringBoot/MyBatis、不擅改 `@WebServlet` URL / web.xml / IoC 扫描、不为实现便利改业务逻辑语义；不引入 MQ、**不引入新的第三方依赖**。  
> **红线口径（与二节"红线措辞约定"一致）**：下面各任务的"红线边界"只列"明显越界"的项，作用是**防跑偏**、不穷举做法；若某条红线会阻碍正确做法（过紧 / 过窄 / 已不适用）→ 说明理由**申请调整**，不许硬扛、也不许自行放开。

### T1 韧性底座：Redis 超时 + 快速失败

* **入口线索**：`util/MyRedisPool`（静态池，当前只配 `maxTotal/maxIdle/minIdle/testOnBorrow`，**没有任何 timeout**）、`app.properties` 的 `redis.*` 段、`cache/RedisAccess`（所有域缓存的唯一 Redis 出入口）。目标：Redis 不可用时"快速失败"，而不是每个请求先等一次连接超时。
* **红线边界**：不引入新的第三方依赖；不改 `MyRedisPool` 现有对外方法签名（他处仍在用）；不改变"缓存失败一律降级、不抛业务异常"的既有语义；不动 TTL 取值与 key 命名。
* **强制探索步骤**：动刀前先 (1) 确认所有 Redis 访问是否都收敛在 `RedisAccess`（`rg 'MyRedisPool|redis.clients.jedis' src/main`，若有绕过点先记录）(2) 确认 Jedis 连接/读写超时的默认值与可配项 (3) 熔断的粒度（全局 or 按域）、失败判定口径与恢复探测方式——若清单未覆盖 → 回写本文档再动手。
* **验收**：Redis 停机时缓存路径的失败耗时从"每次等连接超时"降到"立即降级"（有可复现的验证方式，如本地停 Redis 后测单次请求耗时）；Redis 恢复后自动回到正常缓存路径；`mvn compile` + 既有 JUnit 全绿；相关端点 pytest 无回归。
* **执行回写（2026-09-13，fix(cache-01) 已落地）**：
  * **强制探索结论**：① Redis 访问已全部收敛在 `RedisAccess`（`MyRedisPool` 主代码仅 RedisAccess 与 AppShutDownListener 关停路径两处引用，后者非热路径，无需熔断）；② Jedis 5.1.0 默认超时 2000ms 未显式化、`maxWait` 默认 -1（池耗尽无限阻塞）——javap 实证 Jedis 5.1.0 **无** `(poolConfig, host, port, connTimeout, soTimeout)` 短构造器，采用 8 参 `(…, connTimeout, soTimeout, password, database, clientName)`，password/clientName=null、database=默认库，与原三参语义一致；③ 熔断设计（清单未覆盖，按本回写执行）——**粒度=全局单熔断**（单 Redis 实例宕机影响所有域，按域只增探针流量）；**失败口径=从 `RedisAccess.execute` 冒出的 CacheException 计一次失败**（包装异常均为 Redis 起源；回调自抛 CacheException 极罕见，计数偏差无害——最坏提前降级）；**恢复探测=半开单探针**（冷却期满 CAS 放行唯一探针，成功闭合/失败重开重置冷却）。
  * **实现**：新建 `cache/RedisCircuitBreaker`（141 行 @Component，CLOSED→OPEN→HALF_OPEN 状态机，AtomicInteger CAS 无锁，迁移打日志；半开重开分支"先写冷却起点后 CAS"写序经独立评审修正）；`RedisAccess` 接线（tryAcquire 拒绝即抛 CacheException 快速失败不取连接 + finally 按成败回填；双构造器 `@InjectConstructor`/无参兼容既有 6 处测试直调）；`MyRedisPool` 显式超时 + maxWait（public 签名零变化）；`AppConfig` +5 getter、app.properties +5 键（connectTimeoutMs=1000 / soTimeoutMs=1000 / pool.maxWaitMs=1000 / breaker.failureThreshold=5 / breaker.cooldownMillis=10000，均可配）。熔断异常由 CacheAside/业务既有 catch 降级自然接住，**CacheAside 零改动**。
  * **验证**：`mvn -o compile` 过；JUnit **332 例全绿**（tv.py test junit，沙箱拦截→沙箱外执行；cache 包 58→71：新增 RedisCircuitBreakerTest 8 例含并发唯一探针、RedisAccessTest 4→9 含探针恢复全链路与 CacheException 计失败口径）；pytest **124 passed**（tv.py test all，含全部端点回归）；运行时可复现验证（temp_script/verify_cache_failfast.py + 实例日志）：① 黑洞地址注入（REDIS_HOST=203.0.113.1）启动即熔断开启、后续全部"快速失败（未访问 Redis）"；② 真实 docker stop redis → 8 次 /start 全 200（13~78ms，无超时等待）→ 冷却期满探针失败重开（日志实证探针失败路径）→ docker start redis 后探针成功"熔断恢复，回到正常缓存路径"，响应 38B→4KB；**独立 subagent 评审通过**（无🔴必须修复；🟡 两条已落实：半开重开写序修正 + 恢复路径/口径钉死补测 + 测试冷却 50ms→500ms 防抖）。
  * **验收对照**：快速失败 ✓（运行时计时）；自动恢复 ✓（日志+数据恢复）；mvn compile + JUnit 全绿 ✓；pytest 无回归 ✓。发现既有问题 `/start` Redis 停机降级为空列表（非 T1 引入，登记 UNPLANNED_ISSUES **U-11**）；U-10 在 UNPLANNED_ISSUES 标注已修复。

### T2 降级不放量：降级路径接入单飞

* **入口线索**：`cache/CacheAside` 的 `getInternal` / `getBatch` catch 分支（当前直接 `invokeLoader`，**不经单飞**）、`FollowCache` 的 `isFollowing` / `getSetMembers` / `batchIsFollowing` 降级分支、`LikeCacheService` 的 `isContentLiked` 降级分支。目标：Redis 不可用时，同一 key 的并发读仍然只打一次 DB。
* **红线边界**：不改变降级语义（仍是"直接走 DB、失败**不写回**"）；不引入分布式锁（多实例是 R-03，不在本周期）。
* **强制探索步骤**：动刀前先 (1) 列出全部降级分支（`rg 'catch \(CacheException' src/main/java`）逐一确认是否已有单飞保护 (2) 确认"降级 + 单飞"组合下 loader 失败/超时的行为——**不能把失败结果共享给其他等待者** (3) 确认与 T1 熔断的关系（熔断命中后是否还需要单飞）——回写本文档再动手。
* **验收**：Redis 停机时，同一 key 的并发读只触发一次 DB 装载（有可复现验证）；`SingleFlight` 的失败清理语义未被破坏（既有 `SingleFlightTest` 语义保持）；`mvn compile` + JUnit 全绿 + 相关 pytest 无回归。
* **执行回写（2026-09-13，fix(cache-02) 已落地）**：
  * **强制探索结论**：① 全部 30 处 `catch (CacheException)` 逐一确认——需治理的**降级读分支 10 处**（`CacheAside` getInternal/getBatch 整批/getBatch 脏 JSON 3 处；`FollowCache` isFollowing/getSetMembers/batchIsFollowing 3 处；`LikeCacheService` isContentLiked/isCommentLiked/两批量 4 处，含任务点名之外的对称孪生与批量）；不在范围：`read`（无生产调用方）、写路径 catch、`ensureIndex`（已单飞）、`readIndex`（降级空、无 DB 装载，U-11 既有语义）；② **失败语义**：loader 失败 → FutureTask 异常完成 → leader/joiner 均以异常收场（**失败不以数据形式共享**，无人拿到伪结果）→ 条目 remove → 下一请求全新重试；等待无超时=与现状逐请求阻塞等价（R-03 不在本周期）；③ **与 T1 熔断关系**：正交互补——熔断管"Redis 快速失败"，单飞管"降级后 DB 去重"，熔断 OPEN 后单飞仍然必需。
  * **实现（统一规则=降级读与 miss 回填共用同一单飞 key 空间、全量 loader 作答、仅装载不写回/D4）**：`CacheAside` 3 处降级 catch 包 `singleFlight.get(key, loader)`；`FollowCache` isFollowing/getSetMembers catch 单飞全量装载作答（替代原单行/targeted 查询，删 `isFollowingFromDb`）、batchIsFollowing 增 `degraded` 标志——降级态单飞全量作答、**不再走 targeted 批量查询与必失败的回填写入尝试**（正常 miss 路径不变）；`LikeCacheService` isContentLiked/isCommentLiked 同构改造（删 `isContentLikedFromDb`/`isCommentLikedFromDb`）、两批量降级态逐 cid 单飞装载作答（正常 miss 的 backfill 不变）；防漂移：降级装载唯一入口 `FollowCache.loadViaSingleFlight` / `LikeCacheService.loadLikersViaSingleFlight`（🟡 评审建议落实）。**红线对照**：降级语义未变（仍直接走 DB、不写回；批量降级移除的是"Redis 挂时必失败的写入尝试"，对外观察行为不变）；DAO 单行方法（isLiked/isFollowing）保留——FollowService/LikeService 写路径仍用；`SingleFlight` 类零改动。
  * **统计口径微调**：`LOAD` 从"每降级请求记一次"变为"实际去重后装载记一次（leader 记）"，与 miss 单飞口径一致；`DEGRADE` 仍按请求/key 记。
  * **验证**：`tv.py test junit` **337 例全绿**（T1 末 332 +5：CacheAsideTest +3——降级并发同 key loader 只执行一次且无写回/降级 loader 失败异常传播不缓存且下次重试/批量降级逐 key 去重；LikeCacheServiceTest +1、FollowCacheTest +1 并发去重；降级断言改造为"全量装载作答+无写尝试"。并发用例用 mock RedisAccess 恒抛 CacheException——**MockedStatic 线程局部不可跨线程**，改法经实测确认）+ `tv.py test all` **pytest 124 passed** + **运行时黑洞验证**（REDIS_HOST=203.0.113.1 启动即熔断开启（tv.py 预检查真实 6379 不受影响），20 线程并发同 key `/search/IdSearch`：全部 200 且数据一致，MySQL `Com_select` 差值仅 **8** 次（无单飞应 ≈60~120，未随并发线性放大），tomcat_stderr.log 实证"熔断开启中，快速失败（未访问 Redis）"；脚本 `temp_script/verify_cache02_degrade_singleflight.py`）+ **独立 subagent 评审通过**（无🔴；🟡 抽公共降级装载方法已落实、🟡 并发窗口观测维持 sleep 惯例与 SingleFlightTest 一致记录不修）。

### T3 负缓存治理：区分"确认无数据"与"加载失败"

* **入口线索**：`ContentCache.loadContentFromDb`、`CommentCache.loadCommentTree`（两者 catch `SQLException` → `return null`，被 `CacheAside` 当成"确认无数据"写入 60s 空标记）；对照 `LikeCacheService.loadXxx` 与 `FollowCache.loadXxx`（遇 `SQLException` 抛 `ServerException`，不污染缓存）。目标：loader 的"没有数据"与"查失败了"必须可区分，读路径不把瞬时故障固化成假数据。
* **红线边界**：三态语义（miss / hit-empty / hit-data）与空标记机制不动；"缓存失败不得导致业务失败"不动；只调整"loader 失败时怎么写 / 不写缓存"这一层；**不统一各域的对外错误约定**（404 还是 500 由各业务既有约定决定）。
* **强制探索步骤**：动刀前先 (1) 列出全部 loader 及其失败返回值口径（`rg 'return null' src/main/java/com/itheima` 逐个确认语义）(2) 确认各域"确实无数据"与"查失败"当前分别产生什么对外结果（404 / 空列表 / 500）(3) 选定区分机制（如给 `CacheAside` 增加"loader 失败不得上报为空"的契约，或引入显式的"未找到"载体）——**若涉及对外行为变化，先回写 NEEDS 报备再动手（G7）**。
* **验收**：模拟 DB 瞬时失败时，热门内容不会被写成 60s 空标记（可复现：让 loader 抛错 → 断言未写 `empty:` 且既有数据 key 未被 DEL）；新增单测覆盖"加载失败不写空标记"；`mvn compile` + JUnit 全绿 + 相关 pytest 无回归。

### T4 写路径的失败与竞态治理

* **入口线索**（同一主题三项，可拆多 commit，按 G1 标注）：
  ① `cache/CacheAside.markEmpty` —— 无条件 `setex(empty:…) + del(dataKey)`，**缺"数据 key 不存在才写"的守卫**；对照 `FollowCache.writeSet` 空分支已有 `if (!j.exists(setKey))`（260913 周期 T5 review 必修②，同一坑只修了一半）。
  ② `ContentCache.addToIndex` 失败仅记 WARNING + `ensureIndex` 只判索引 key 是否存在（**索引缺失不自愈**，内容可能长期不进推荐）。
  ③ `LikeCacheService.likeContent` / `likeComment` 的"探 exists → 再 INCR/SADD"两步非原子（并发失效时计数可能被以 1 重建）。
* **红线边界**：不改三态语义与 key 命名；空标记 TTL（60s）不变；不引入分布式锁；**不顺手做 U-09 的全面收口或无关重构**（独立议题，不在本周期）。
* **强制探索步骤**：动刀前先 (1) 确认 `markEmpty` 的全部调用方（`CacheAside` 内部 + `writeContentLikers`/`writeCommentLikers` 空分支）与并发路径 (2) 确认索引"缺失"与"不完整"当前如何被判定，选一个最小改动即可自愈的方案 (3) 确认 Redis 侧是否有原子手段替代"探存在→写"（Lua / `SET NX` 等）；若无则**明确定下"可接受的残余竞态"并记录**——回写本文档。
* **验收**：新增单测覆盖"并发写数据 + 回填写空标记"不产生假空（守卫生效）；索引写入失败后可自愈（或至少可被检测）；条件写竞态有明确结论（修掉 or 记录为已接受）；`mvn compile` + JUnit 全绿 + 相关 pytest 无回归。

### T5 启动加载治理

* **入口线索**：`ContentCache.init()` —— `transactionTemplate.execute` 内做全表 + 逐条媒体查询 + `rebuildRedis`（**Redis 写入在 DB 事务内**）；`rebuildRedis` / `rebuildIndexes` 逐条无 pipeline。连带 R-01（初始化选择性加载：全量 / 按需回填 / 分级加载 三取向待选）与 R-04（初始化 N+1）。目标：启动不再随内容量线性放大，且 Redis 写入不再占着 DB 事务。
* **红线边界**：**R-01 的取向必须先由用户拍板**（G7：不许自行选）；对外行为零变化（启动后的读路径结果、推荐/Feed/Profile 语义不变）；不改 `@WebServlet` URL / web.xml / IoC 扫描；**不做 R-10（定期重建索引）**——仍是待评估项。
* **强制探索步骤**：动刀前先 (1) 确认 `init()` 在启动流程中的位置与失败影响（`Initializable` 调用方）(2) 量化现状：内容行数 × Redis 往返次数、单次启动耗时（本地可测）(3) R-01 三取向的代价对比（含"按需回填"对首屏延迟的影响）→ **停决策点向用户报备并拍板后再动手（G7）**。
* **验收**：Redis 写入不在 DB 事务内（可观察/可断言）；批量操作 pipeline 化，启动往返次数显著下降（有前后对比数据）；R-01 拍板结果落地且与 NEEDS 一致；`mvn compile` + JUnit 全绿 + **`pytest all` 全绿**。

### T6 收尾

* **入口线索**：全仓库巡检 + 回归 + 文档同步 + 覆盖率。基于 T1~T5 完成态。含 **U-08**（key 规范漂移：`ContentCache.indexKey` 自行拼接 `content:index:`，`CacheKeys` 无对应生成方法却由 `domainOf` 解析）。
* **红线边界**：不引入新功能改动；只做残留清理、U-08 归一、验证与文档；发现"要改但未排期"的内容 → **登记 `UNPLANNED_ISSUES.md`**，不在本周期硬做。
* **强制探索步骤**：动刀前先 (1) `rg` 确认 T1~T5 无残留（旧的直调模式、临时开关、临时日志等）(2) 校验 `@WebServlet` URL / web.xml / IoC 扫描原样 (3) 确认 `CacheStats` 观测口径是否仍准确（T1 引入熔断后 `DEGRADE` 的计数含义可能变化）。
* **验收**：U-08 消除（索引 key 生成与解析同源，`CacheKeys` 为唯一源）；`pytest all` 全量回归全绿；常青文档同步（`CURRENT_ARCHITECTURE.md` 六.Redis 设计 + `BUSINESS_FLOW.md` 3.1 缓存机制）；NEEDS 中已拍板决策与实现一致；覆盖率地图 rerun 无回归。

---

## 五、变更记录

| 日期         | 版本  | 内容                                                                                                                                                                          |
| ---------- | --- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-09-13 | 0.1 | 新建本文档（模板就位）：结转周期约定 G1-G10（G1/G3/G9 三处标注"待 R-11 拍板后校准"）+ 任务模板四要素 + 红线措辞/编号引用约定；任务总览与任务详情**留空待填**，并预置"候选任务来源映射"（按 R-11 四个候选方向列出各自可拆解的 R/N/U 条目）                    |
| 2026-09-13 | 0.2 | **R-11 拍板后拆任务**：方向 = 缓存加固（缓存韧性 + 启动加载治理）；G1/G3/G9 三处校准（commit 前缀 `fix(cache-0N)`、验收层级明确、本周期无 DDL 并在任务总览标注）；新增"任务清单理念"（只写做什么/不做什么、不提前过度详细设计、红线只防跑偏）；任务总览填入 **T1~T6**（含 NEEDS 编号映射与顺序理由）；"四、任务详情"填入 6 个任务的四要素骨架；移除已被本周期消费的"候选任务来源映射"（范围见 NEEDS 4.3） |
| 2026-09-13 | 0.3 | **修订红线措辞约定**（用户要求）：① 明确红线"只列明显越界的项、作用是防跑偏、不穷举做法、不做一刀切禁止"；② 机制表述由"不改本来要守的红线就会阻碍后续工作 → 申请开禁"改为"**某条红线会阻碍正确做法**（过紧 / 过窄 / 已不适用）→ 说明理由**申请调整**"，两个禁止（硬扛 / 自行放开）保留；③ 同步更新任务模板中"红线边界"一行的括号说明 |
| 2026-09-13 | 0.4 | **T1~T6 逐条红线对齐新口径**（用户要求）：① 四节共通注补"**红线口径**"一段（只列明显越界项、防跑偏不穷举做法、阻碍正确做法即申请调整），各任务不再各自解释机制；② 逐任务去重与去掉做法级指定——T1 删"（熔断自己写）"（已由共通注"不引入新的第三方依赖"覆盖）、"TTL / key"改为"TTL 取值与 key 命名"；T2 合并"不改变降级语义"与"不改成写回缓存"两条重复项；T3 精简"一刀切"表述与标点；T4 合并"不做 U-09 全面收口"与"不顺手重构无关代码"两条；T5 删去与 G7 重复的"开工前停决策点报备"括号说明；T6 保持。**实质边界全部保留，只做去重、去做法级与措辞对齐** |
| 2026-09-13 | 0.5 | **T1 执行完成回写**（fix(cache-01)）：总览 T1 状态 草稿→已完成（2026-09-13）；T1 详情追加"执行回写"——探索结论（访问已收敛 RedisAccess/Jedis 5.1.0 无短超时构造器用 8 参替代/熔断粒度=全局、失败口径=execute 冒出的 CacheException、恢复=半开单探针）、实现摘要（RedisCircuitBreaker 141 行 + RedisAccess 接线 + MyRedisPool 显式超时/maxWait + AppConfig·app.properties 5 键，CacheAside 零改动；半开重开写序经独立评审修正）、验证结果（mvn compile 过 + JUnit 332 全绿 + pytest 124 passed + 运行时黑洞注入/真实停 Redis 双验证含探针失败重开与恢复实证 + subagent 评审通过且🟡建议全部落实）；登记 U-11（/start 停机空降级，既有语义）、U-10 标注已修复 |
| 2026-09-13 | 0.6 | **T2 执行完成回写**（fix(cache-02)）：总览 T2 状态 草稿→已完成（2026-09-13）；T2 详情追加"执行回写"——探索结论（30 处 catch 逐一确认、降级读分支 10 处为治理面/失败不以数据共享且条目移除可重试/与 T1 熔断正交）、实现摘要（统一规则=降级读与 miss 共用单飞 key 空间、全量 loader 作答、仅装载不写回/D4；CacheAside 3 处 + FollowCache 3 处 + LikeCacheService 4 处；批量降级移除必失败的回填写入尝试；删 3 个 *FromDb 助手而 DAO 方法保留；防漂移公共入口 loadViaSingleFlight/loadLikersViaSingleFlight；LOAD 口径改 leader 记一次）、验证结果（JUnit 337 全绿 +5、pytest all 124 passed、运行时黑洞验证 20 并发同 key Com_select 差值仅 8 且熔断日志实证、subagent 评审通过无🔴🟡一落实一记录） |
