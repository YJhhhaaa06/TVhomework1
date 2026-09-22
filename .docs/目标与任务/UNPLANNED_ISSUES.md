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
| U-11 | 观察（降级质量） | Redis 停机时 `/start` 推荐返回**空列表**（HTTP 200 `data:[]`），**无 DB 兜底**——`getRecommendByFilter` 依赖 Redis 索引（`ensureIndex` 懒重建需写 Redis、失败"本次推荐降级为空"；`readIndex` 失败"降级为空推荐"），索引不可读即无候选可批量装载。属**既有语义**（实现注释明写"Redis 异常降级为空 / 不 crash"），业务未 500，但"缓存失败不导致业务失败"在此处体现为"返回空推荐"而非"DB 兜底推荐"，用户可感知 | `content/service/ContentCache.java` 的 `ensureIndex`（懒重建失败 catch"降级为空"）/ `readIndex`（catch"降级为空推荐"）；运行时实证：`docker stop redis` → `/start` 38B 空响应（2026-09-13） | `260921-prep-cleanup`/U-11（原三期 cache-01 运行时验证发现，2026-09-13） | **2026-09-21 裁决 = 维持现状**（立项评审 R-09）：不做"停机返回 DB 兜底推荐"（属对外行为变更）；"N1 加重面"（停机期间逐请求触发索引全量重建）已随第四期 T5 落地（重建失败进程内冷却退避） |
| U-22 | 代码债 | **评论新增时缓存失效写在 DB 事务回调内**：`addComment` 的 `transactionTemplate.execute` 回调体内直接调 `contentCache.notifyCommentCountChanged(contentId)`——是缓存 **DEL（失效写）**、无嵌套装载，与 U-14 的"缓存读"**判据不同**。现状影响：失效发生在提交前（"先失效后提交"，并发读者可能在窗口内回填刚更新的计数，但 DB 为源真理 + Cache-Aside 读自愈，表现为计数短暂陈旧、非缺陷）；治本需把失效移到**提交后**，属**时序语义变更**，须先拍板 | `comment/service/CommentService.java` 的 `addComment`（execute 回调）→ 回调内 `notifyCommentCountChanged` | `260921-prep-cleanup`/U-22（第七期 T12 窗口全仓扫描发现，2026-09-20） | 留池（**2026-09-22 立项裁决：本周期不入**）：形态 = 将失效移到事务提交后（`addComment` 已在提交后做 `commentCache` 失效，可与之一并收口）；**不引入 MQ / 新依赖** |
| U-24 | 观察（装载量） | `/profile`（含创作中心「我的投稿」）的装载侧是**全量 id 读 + 内存切片**：`ProfileService.getProfile` 事务内调 `ContentDao.findContentIdsByUser`（SQL **无 `LIMIT`**）取该作者**全部**内容 id，再在内存 `subList(offset, end)` 切出该页 → 单次成本 ∝ **该作者内容总量**（而非页大小）。与已治本的 `U-18`（T11-C）**完全同型**，只是换到 content 域 | `content/service/ProfileService.java:72-78`（`findContentIdsByUser` + 内存 `subList`）；`content/dao/ContentDao.java:185-197`（无 `LIMIT`/`OFFSET`） | `260921-prep-cleanup`/U-24（第七期 T19 窗口探索发现，2026-09-21） | 待定（留池）：形态同 `U-18`/T11-C —— DAO 补窗口 SQL（`ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?`）+ 独立 count（`countContentByUser` 已存在）；需先 `EXPLAIN` 评估 `user_id + create_time` 索引成本。**不动** `/feed` 的关注 ids 全量读（R-01 明确保留） |
| U-25 | 观察（分页正确性） | `/search/keywordSearch` 的 SQL 排序为 `ORDER BY c.create_time DESC`（**无 id tie-breaker**），而 `/feed`、`/profile` 均为 `create_time DESC, id DESC` → 同一秒创建的多条内容在页间顺序**不确定**，offset 分页下跨页可能**重复或漏项**（不稳定排序漂移）。放大 chunk 只减少跨页次数、不消除风险；且前端 `chunkedList` 的 `seen` 去重会**掩盖**该后端缺陷（helper 注释已明写"去重只兜漂移，不替代后端『页间不重不漏』的正确性"） | `content/dao/ContentDao.java:272`（单字符 `LIKE` 分支）与 `:278`（`MATCH … AGAINST` 分支）——两处 `ORDER BY c.create_time DESC` 后均无 `, c.id DESC` | `260921-prep-cleanup`/U-25（第七期 T19 窗口探索发现，2026-09-21） | 留池（**2026-09-22 立项裁决：本周期不入**）：修法 = 两分支补 `, c.id DESC`（与 feed/profile 同口径；改变同秒内容的确定顺序，须先拍板）；**验证手段依赖小信封**（`pageSize=1/3` 逐页比对） |

> **本池的恢复经过（2026-09-21）**：池文件曾被 `6bff5d8`（"docs:ISSUES文档清理"）整体清空为 0 条，同时 `.docs/INDEX.md` 仍记载 6 条有效留池项 → 构成**文档内部矛盾**（第七期归档后的编号悬空）。经立项评审 **R-12 裁决 = 进入 ISSUE**：其中 `U-11` / `U-22` / `U-24` / `U-25` 恢复至本表；`U-23`（`说明书/DATABASE.md` 与 3306 实际 DDL 不符）**改排任务**（日志周期 **T5**：用 `SHOW CREATE TABLE` 重生成）；`U-17`（限流能力缺失）**移出**——用户将后续开**限流专项分支**处理，本周期不管、不进池。以上去向均已明确，**编号不悬空**。
>
> **2026-09-22 立项处置（日志体系改造第二张清单）**：`U-26`（`tools/run_tests.py` 就绪等待 90s 余量偏紧致 `test all` 偶发假失败）**已排任务 → 本周期 T10**，故自本表移出（去向明确、编号不悬空）；`U-22` / `U-25` **裁决暂时留池**（本周期不入）；`U-24` 留池；`U-11` 维持"维持现状"。


***

## 三、登记模板

```markdown
| U-0N | 类别 | 一句话问题 | 位置/证据链接 | 来源（周期/任务/日期） | 待定 |
```

类别取值示例：文档滞后 / 文案与实现不一致 / 代码债 / 观察 / 其它。