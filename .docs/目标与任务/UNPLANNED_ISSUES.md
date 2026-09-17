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
| U-07 | 观察（包架构） | content ↔ comment 包层循环依赖：content 域共享组件（ContentCacheDTO / ContentCache / CommentCache 等）被 comment 域引用，而 content 域又引用 comment 域的 CommentService。**非 IoC/Bean 环**（依赖链有向无环，容器可正常构建、测试全绿），仅包架构不纯净，Java 允许 | content.ContentService 注入 comment.CommentService（含 comment.dao.CommentDao）；comment.CommentService 注入 content.service 的 ContentCache / CommentCache | 260912 周期（B）探索记录（2026-09-10）；260913 周期复查（2026-09-13）；260914 周期复查（2026-09-14：T3 移除 CommentService→ContentCacheManager 依赖，包层环未解除）；第四期复查（T1~T8 未涉包层结构） | 待定（第四期**未消化**；已结转第五期 `目标与任务/NEXT_CYCLE_NEEDS.md` 三节 R-04，随第五期立项评审决定消化点；第四期已归档 2026-09-17，其 NEEDS 三节 R-12 见 `archive/目标与任务/260917-cache-overhaul/`） |
| U-11 | 观察（降级质量） | Redis 停机时 `/start` 推荐返回**空列表**（HTTP 200 `data:[]`），无 DB 兜底——`getRecommendByFilter` 依赖 Redis 索引（`ensureIndex` 懒重建需写 Redis、失败"本次推荐降级为空"；`readIndex` 失败"降级为空推荐"），索引不可读即无候选可批量装载。属**既有语义**（C 周期实现注释明写"Redis 异常降级为空/不 crash"），非 T1 引入；T1 熔断只是让"降级为空"来得更快。业务未 500，但"缓存失败不导致业务失败"在此处体现为"返回空推荐"而非"DB 兜底推荐"，用户可感知 | `ContentCache.ensureIndex`（懒重建失败 catch"降级为空"）；`ContentCache.readIndex`（catch"降级为空推荐"）；运行时实证：docker stop redis → /start 38B 空响应（2026-09-13） | 三期 T1（cache-01）运行时验证发现（2026-09-13）；2026-09-15 复查发现**加重面**（四期 NEEDS 4.1 的 N1：停机期间每次 /start 重试索引全量重建，逐请求 DB 全表查询，无失败退避） | 空推荐语义维持留池；**N1 加重面已随第四期 T5 落地**（`refactor(cache-05)`，2026-09-16——重建失败进程内冷却退避，停机期间零 DB 查询）；"停机返回 DB 兜底推荐"属对外行为变更，第四期 4.3 明确不做、留池待另行拍板（第四期已归档 2026-09-17） |
| U-12 | 观察（懒加载/分页） | follow 域大集全量装载（自第五期 NEEDS 4.1 的 N3 转入）：`SetCache.getMembers` hit-data 走 `SMEMBERS` 一键全量回传 + String→Long 装箱全量，miss 装载同样全量 DB 粉丝/关注列表——**懒加载但无分页/无上限**，与四期 R-08 点赞反转同动机（like 侧压了装载量、follow 侧天然粉丝维度未做） | `SetCache.getMembers` L112-139（L122 `j.smembers(setKey)` 全量）；`FollowCache.getFollowerIds` L152-155 / `getFollowingIds` L144-147；`FollowController` L34-37（`getFollowerList`/`getFollowingList` **无分页参数、全量返回**） | 第五期 NEEDS 4.1 N3（2026-09-17 代码复查） | **留池**：改动面 = 业务接口契约加分页参数 + DAO 分页查询 + `SetCache` 分页支持（`prange`/sorted-set），远超缓存体系、属懒加载/分页范畴；待数据量级触发或专项周期（用户 2026-09-17 拍板转留池） |
| U-13 | 观察（懒加载/分页） | 评论树全量装载重排（自第五期 NEEDS 4.1 的 N4 转入）：miss 装载把整棵评论树（含楼中楼）从 DB 全量捞出 + `buildCommentTree` 内存重排；分页会撕裂楼中楼树结构，属展示/接口契约决策层；另 `notifyCommentLikeChanged` 每次评论点赞先 `findContentIdByCommentId` 轻查 DB 定位所属内容（写路径多一次 DB，低频） | `CommentCache.loadCommentTree` L99-114 + `buildCommentTree` L117-145；`CommentCache.notifyCommentLikeChanged` L84-89 + `findContentIdByCommentId` L148-162；`CommentController` L82（`getCommentsForContent` 无分页、整树渲染） | 第五期 NEEDS 4.1 N4（2026-09-17 代码复查） | **留池**：评论树结构决定难以缓存侧分页（整树语义），分页属产品/接口契约决策，不动缓存体系；待评论量级触发或产品拉开评论分页时再评估（用户 2026-09-17 拍板转留池） |

> 已消化/已修复项（U-05、U-08、U-09、U-10）已随各周期落地，2026-09-17 清理移出本档。

***

## 三、登记模板

```markdown
| U-0N | 类别 | 一句话问题 | 位置/证据链接 | 来源（周期/任务/日期） | 待定 |
```

类别取值示例：文档滞后 / 文案与实现不一致 / 代码债 / 观察 / 其它。

***

## 四、变更记录

为了减缓文档膨胀，本文档不写变更记录