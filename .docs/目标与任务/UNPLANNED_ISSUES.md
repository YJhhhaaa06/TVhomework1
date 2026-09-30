# 未排期事项（待决策池）

> 用途：登记**发现需要改、但暂时没有排进任务清单**的内容（文档滞后 / 文案与实现不一致 / 代码债 / 观察项等），避免遗忘或散落在各任务探索结论里。
> 原则：本档只**登记与留存**，不承诺排期；是否消化、何时排期由用户评审决定。
> 分流：排进当/下周期任务清单 → 移出本档并注明去向；确认不改（won't fix）→ 标记"废弃"并留一句理由。

***

## 一、登记规则

| 项 | 规则 |
|----|------|
| 谁登记 | 任何任务执行中发现（G5 超范围暂停的落点之一）；用户/评审时也可直接登记 |
| 记什么 | 一句话问题 + 证据/位置 + 来源（周期/任务/日期）+ 建议方向（可选） |
| 何时消化 | 用户评审本档时决定：排期（进任务清单）/ 随手修 / 废弃 |
| 与结转关系 | 跨周期的"方向级"事项（如缓存改造、feed 改造）仍走需求与痛点文档的结转/方向预告；**本档只收"颗粒度小的具体问题"** |
| 保持同步 | 归档周期时本档同步归档至 `archive/目标与任务/<周期名>/`，新周期延续新本 |

***

## 二、已登记候选事项

| 编号 | 类别 | 问题 | 位置/证据 | 来源 | 状态 |
|----|------|------|-----------|------|------|
| U-11 | 观察（降级质量） | Redis 停机时 `/start` 推荐返回**空列表**（HTTP 200 `data:[]`），**无 DB 兜底**——`getRecommendByFilter` 依赖 Redis 索引（`ensureIndex` 懒重建需写 Redis、失败"本次推荐降级为空"；`readIndex` 失败"降级为空推荐"），索引不可读即无候选可批量装载。属**既有语义**（实现注释明写"Redis 异常降级为空 / 不 crash"），业务未 500，但"缓存失败不导致业务失败"在此处体现为"返回空推荐"而非"DB 兜底推荐"，用户可感知 | `content/service/ContentCache.java` 的 `ensureIndex`（懒重建失败 catch"降级为空"）/ `readIndex`（catch"降级为空推荐"）；运行时实证：`docker stop redis` → `/start` 38B 空响应（2026-09-13） | `260921-prep-cleanup`/U-11（原三期 cache-01 运行时验证发现，2026-09-13） | **2026-09-21 裁决 = 维持现状**（立项评审 R-09）：不做"停机返回 DB 兜底推荐"（属对外行为变更）；"N1 加重面"（停机期间逐请求触发索引全量重建）已随第四期 T5 落地（重建失败进程内冷却退避）。**2026-09-28（feed 三期对齐）复核：本期不取，维持留池**（原 `NEXT_CYCLE_NEEDS.md` 二节 `R-01`，已自 NEEDS 移出、仅存本档） |
| U-31 | 观察（可观测性缺口） | **MQ 无积压监控 / DLQ 巡检 / 压测**：无队列深度观测、无 DLQ 巡检、无 MQ 健康指标；`tools/` 下唯一 feed 工具 `feed_shadow_check.py` 比对的是"DB 真相 vs 重建 oracle"（Redis 已降 best-effort），**不覆盖 MQ 侧** | `tools/` 清单（无 MQ / 积压类工具）；`tools/feed_shadow_check.py`；`mq/MqTopology.java:40-41`（DLQ 无 TTL） | feed 三期探索（2026-09-28，原 `NEXT_CYCLE_NEEDS.md` 4.1 `N10`）→ 立项对齐判定"不取" | 留池（2026-09-28 对齐：可靠性加固**不取**"积压监控 / 巡检 / 压测"，见 NEEDS `R-10`） |
| U-32 | 运行期脆弱性（消费拉起） | **消费者拉起无定时重试**：`basicConsume` 失败（或注册时连接不可用）只记 WARNING 并关 channel、`startedQueues` 不记 ⇒ **无定时重试**，须等 `init()` / 再次 `register` / **下次连接建立（`onConnected`）** 才重新拉起；运行期"连接仍可用但 `basicConsume` 偶发失败"时消费停摆且无感知 | `mq/MqConsumerContainer.java:128-134`（`onConnected` 重拉）、`:136-166`（`startLocked` 失败只记 WARNING）、`:96-104`（`init` 单次） | feed 三期探索（2026-09-28，原 4.1 `N12`）→ 判定"不取" | 留池（2026-09-28 对齐：`R-10` **不取**⑥"消费者拉起的定时重试"） |
| U-33 | 配置债（参数未收敛） | **MQ 侧参数未入配置**：confirm 超时 / 重连冷却 / 消费线程数 / prefetch 均为**包内常量**，仅 feed 业务键入配置 ⇒ 做规模 / 可靠性治理时无法在不改代码、不重新部署的前提下调参（本期**只**参数化 feed 域批量尺寸） | `mq/MqPublisher.java:32`（confirm 超时 5s）、`mq/MqConnectionManager.java:58`（重连冷却 30s）、`:64`（消费线程数 4）、`mq/MqConsumerContainer.java:41`（prefetch 1）；对照 `config/AppConfig.java` 的 `feed.*` 已配置化 | feed 三期探索（2026-09-28，原 4.1 `N7`）→ 判定"不取" | 留池（2026-09-28 对齐：本期只取 feed 域批量尺寸参数化） |
| U-34 | 规模 / 吞吐（缺数据支撑的优化） | **发布 publisher confirm 串行**：单 confirm channel + `synchronized`「`basicPublish` + `waitForConfirms`」⇒ **多线程发布被串行化、吞吐受限**；一期注释把"每线程 channel / 异步批量确认"挂在三期，但本期**异步投递只解 RT 解耦、不改串行**，且无吞吐实测数据 | `mq/MqPublisher.java:16-20`（线程安全注释）、`:72-94`（`synchronized (confirmLock)`） | feed 三期探索（2026-09-28，原 4.1 `N8` 之吞吐面）→ 判定"留池" | 留池（2026-09-28 对齐：本期不做"多 channel / 每线程 channel 提吞吐"） |
| U-35 | 测试脆弱性（前置守卫缺失） | **`test_feed_push.py` / `test_feed_rebuild.py` 的"收件箱腿"用例在 `FEED_BIGV_THRESHOLD=1` 下必然失败**：该模式下被关注作者被强制判为大V ⇒ fanout 必然跳过写扩散、重建 oracle 也按 `follower_count >= 阈值` 排除该作者 ⇒ 断言"前置：粉丝收件箱应先落库"无法成立。**两个文件均无 skip 守卫**（`test_feed_read.py` 已有：`_require_non_bigv_author`，并注明"改跑默认参数即可"）⇒ 全量跑 `FEED_BIGV_THRESHOLD=1` 会稳定产生 5 例假失败，掩盖真实回归 | `src/test/python/test_feed_push.py::TestFeedPushFanout`（2 例）、`src/test/python/test_feed_rebuild.py::TestFeedRebuildWindow`（3 例）；对照 `test_feed_read.py:353-362` 的守卫 | feed3 **T26** 窗口（2026-09-29，留证跑发现；已用 `git stash` 取 HEAD 基线证为**既有前提问题、非 T26 回归**——改动前后同 5 例、原因逐条一致） | 留池（建议方向：照 `test_feed_read.py` 先例补"作者是否大V"前置守卫并 skip；**非 T26 范围**，不扩范围） |
| U-36 | 观察（表侧膨胀 → 读放大） | **`feed_inbox` 表侧无主动清理（`N5`），且读侧装载是"全量、无 LIMIT"** ⇒ 表侧膨胀会**线性放大读路径成本**：`FeedInboxReader.readInbox` 把该用户收件箱**全部**行塞进 `feed:inbox:{id}` 的 ZSet（**每次 fanout 都 DEL 该 key ⇒ 下次读要重装全部行**），再由 `mergeDedupSortTrim` 在内存里截到 M=300。真正**无上界**的用户 = **关注集稳定者**（不再关注 / 取关）：初始关注触发过一次重建（≤ C=200），此后 fanout 只追增、直到下次关注 / 取关。**用户可见结果不变**（读侧 M=300 兜底）；成本在 ① DB 扫描行数 ② Redis ZSet 成员数 ③ 读侧内存 | `feed/dao/FeedInboxDao.java:163-175`（`findInboxContentIds` = `SELECT content_id … ORDER BY content_id`，**无 LIMIT**）、`feed/service/FeedInboxReader.java:88-96`（整窗入 ZSet 缓存）、`feed/service/FeedInboxWriter.java`（fanout 只 `INSERT IGNORE`、**无 DELETE**）、`feed/service/FeedRebuildService.java:92-93`（残余②"表侧非严格有界"）；`FeedInboxReader` 类注释"窗口量级 ≈ C=200、成本可忽略"在稳定关注集用户上**不成立** | feed3 **T29** 窗口（2026-09-29，表侧保留核算发现）→ 判定"**不在 T29 做**"（T29 拍板 = 复用重建顺带裁剪 + 零新载体；红线禁止 fanout 删 / 定期任务） | **留池**（建议方向 = **读侧加界**：`findInboxContentIds` 改 `ORDER BY content_id DESC LIMIT M(+缓冲)`，切开"膨胀 → 读成本"这条链；零新载体、**非** fanout 删除，但会把"缓存 = DB 全量集合"不变式改为"缓存 = top-M"，须同步登记。**表侧本体是否主动清理另需拍板**——`R-08` 已定"复用重建顺带裁剪 + 接受残余"，重开须先说明理由） |
| U-37 | 测试脆弱性（种子定位临界） | **`conftest.sample_comment_id` 的搜索定位依赖"种子创建时间最新"这一随时间衰减的前提**：定位 = `/search/keywordSearch?keyword=seed_baseline`（背后 `MATCH(title, description) AGAINST('seed_baseline' IN NATURAL LANGUAGE MODE)` + `ORDER BY create_time DESC LIMIT 0,50`）→ 在**首屏 50 条**里找 title 以 `seed_baseline` 前缀者。但**命中集** = title/description 含 `seed`/`baseline` 分词的**全部内容**（含大量**改名测试残留** `renmv_*`——其 description 遗留 seed 类字样），且排序按创建时间 ⇒ 测试库长期不重建时，残留（比种子新）把种子挤向首屏边缘，**再新增几条内容即出窗**（实测：种子排 49/50 位；一轮 pytest 后下一轮即 3 例 fixture error，`init_test_db` 重建后复跑全绿） | 实测 2026-10-01：`/search/keywordSearch?keyword=seed_baseline` 返回 50 条（total=50）且 seed 条排第 49；`src/test/python/conftest.py:359-376`（定位 + fail 文案）；`content/dao/ContentDao.java:411-425`（FULLTEXT + create_time DESC LIMIT）；残留例 `content.title='renmv_*'` / `description='change_user_name_test'`（改名测试产物，cleanup 白名单未覆盖） | feed3 **T32** 窗口（2026-10-01，降级跑复现；已证**非 T32 引入**——库内数据完好、纯定位前提老化） | 留池（建议方向：定位改"不依赖排序"（如精确 title 匹配 / 直查 SQL）；或 cleanup 白名单覆盖 `renmv_` 改名残留；或把"定期重建测试库"写成显式流程） |

> **已移出本档（2026-09-28：feed 三期对齐 + feed 二期收尾）**：`U-22` / `U-24` / `U-27` / `U-28` / `U-29` / `U-30`（**编号不悬空，去向如下**）——`U-22` / `U-27` / `U-28` / `U-29` → `NEXT_CYCLE_NEEDS.md` 二节 `R-02` / `R-03` + 任务 **`T34`**（**已纳入本期，待落地**）；`U-24` / `U-30` → 已随 feed 二期 **T25** 落地（✅ 2026-09-27）；`U-25` → 已随日志第三张清单 **T15** 落地（✅ 2026-09-25）。**本档当前有效留池 = `U-11` / `U-31` / `U-32` / `U-33` / `U-34` / `U-35` / `U-36` / `U-37`**（后五项 = 2026-09-28 feed 三期对齐判定"不取"的**新登记**，来源见各行"来源"列；`U-35` = 2026-09-29 feed3 T26 窗口新登记；`U-36` = 2026-09-29 feed3 T29 窗口新登记；`U-37` = 2026-10-01 feed3 T32 窗口降级跑复现发现）。

> **本池的恢复经过（2026-09-21）**：池文件曾被 `6bff5d8`（"docs:ISSUES文档清理"）整体清空为 0 条，同时 `.docs/INDEX.md` 仍记载 6 条有效留池项 → 构成**文档内部矛盾**（第七期归档后的编号悬空）。经立项评审 **R-12 裁决 = 进入 ISSUE**：其中 `U-11` / `U-22` / `U-24` / `U-25` 恢复至本表（**`U-22` / `U-24` 已于 2026-09-28 移出、`U-25` 已于 2026-09-25 随日志 T15 落地 —— 见上"已移出本档"注**）；`U-23`（`说明书/DATABASE.md` 与 3306 实际 DDL 不符）**改排任务**（日志周期 **T5**：用 `SHOW CREATE TABLE` 重生成）；`U-17`（限流能力缺失）**移出**——用户将后续开**限流专项分支**处理，本周期不管、不进池。以上去向均已明确，**编号不悬空**。

***

## 三、登记模板

```markdown
| U-0N | 类别 | 一句话问题 | 位置/证据链接 | 来源（周期/任务/日期） | 待定 |
```

类别取值示例：文档滞后 / 文案与实现不一致 / 代码债 / 观察 / 其它。