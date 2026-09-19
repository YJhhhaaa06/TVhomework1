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
| U-12 | 观察（懒加载/分页） | follow 域大集全量装载（自第五期 NEEDS 4.1 的 N3 转入）：`SetCache.getMembers` hit-data 走 `SMEMBERS` 一键全量回传 + String→Long 装箱全量，miss 装载同样全量 DB 粉丝/关注列表——**懒加载但无分页/无上限**，与四期 R-08 点赞反转同动机（like 侧压了装载量、follow 侧天然粉丝维度未做） | `SetCache.getMembers` L112-139（L122 `j.smembers(setKey)` 全量）；`FollowCache.getFollowerIds` L152-155 / `getFollowingIds` L144-147；`FollowController` L34-37（`getFollowerList`/`getFollowingList` **无分页参数、全量返回**） | 第五期 NEEDS 4.1 N3（2026-09-17 代码复查） | **已落地（2026-09-19 第六期 T7）**：关注/粉丝侧按 A1（成员 key Set→ZSet 有序化 + `ZSetCache` 窗口读）+ B2（传参返回分页信封、缺省仍全量数组）落地，**命中路径不再 `SMEMBERS` 全量回传 + 装箱**；miss/降级仍为全量装载形态 → 残留转 **U-18**。本项移出待决策池（保留本行便于追溯）。 |
| U-13 | 观察（懒加载/分页） | 评论树全量装载重排（自第五期 NEEDS 4.1 的 N4 转入）：miss 装载把整棵评论树（含楼中楼）从 DB 全量捞出 + `buildCommentTree` 内存重排；分页会撕裂楼中楼树结构，属展示/接口契约决策层；另 `notifyCommentLikeChanged` 每次评论点赞先 `findContentIdByCommentId` 轻查 DB 定位所属内容（写路径多一次 DB，低频） | `CommentCache.loadCommentTree` L99-114 + `buildCommentTree` L117-145；`CommentCache.notifyCommentLikeChanged` L84-89 + `findContentIdByCommentId` L148-162；`CommentController` L82（`getCommentsForContent` 无分页、整树渲染） | 第五期 NEEDS 4.1 N4（2026-09-17 代码复查） | **已落地（2026-09-19 第六期 T8）**：按 D1=A（主楼分页 + 楼中楼整树）落地——缓存整树**结构不变**，分页为展示层按主楼切片（每页 N 条主楼、楼中楼整树随行），传 `page`/`pageSize` 任一返回分页信封、缺省仍全量数组。**未改动**：`loadCommentTree`/`buildCommentTree` 整树装载与 `notifyCommentLikeChanged` 的轻查 DB（属装载侧形态，非本次范围）→ 分页成本残留登记 **U-20**。本项移出待决策池（保留本行便于追溯）。 |
| U-14 | 代码债（事务边界） | **同型 N2 未治点 2 处（事务回调内调缓存读）**：① `ContentService.search` 事务回调内逐 key `contentCache.getContent` + `contentStatusFiller.fillLikeAndFollowBatch`（点赞/关注缓存批量读，miss 会走 DB 装载）；② `FollowService.getFollowingList`/`getFollowerList` 经私有 `buildUserList` 在事务回调内调 `followCache.batchIsFollowing`。与第五期 T3 已治的 N2（Feed/Profile）同根因——自研 `TransactionTemplate` 无传播语义，外层事务持连接期间缓存装载再取新连接，`db.pool.maxSize=20`（timeoutMs=5000）下高峰互相等连接、事务持有期被 Redis 往返与 DB 装载拉长 | `ContentService.search` L71-91（L77 逐 key `getContent`、L83 `fillLikeAndFollowBatch`）；`FollowService.buildUserList` L94-105（L99 `batchIsFollowing`，调用点 L71/L85 在事务回调内） | 第五期 T3（cache-03）探索发现（2026-09-18，L1 仅记录） | **留池→评审候选（2026-09-18 杂务周期）**：T3 范围由任务清单明确限定 Feed/Profile（入口线索 + 红线"不动推荐路径"），两处不在 T3 范围、不擅自扩范围；改动形态与 T3 同款（DB 查询与缓存读分离，search 侧还需先把逐 key `getContent` 换成批量读）；NEEDS 4.1 备注已标注"若纳入需与 N11/U-12 协调同一批 FollowService 方法"，第六期 T7/T8 落地后再评。**2026-09-19 更新：T7、T8 均已落地**（FollowService 新增分页读、私有 `buildUserList`/事务边界**未动**）→ **前置条件解除**：本项已作为第七期评审候选结转至 `目标与任务/NEXT_CYCLE_NEEDS.md` 二节留池项（T7/T8 落地后不再有同文件改动踩线） |
| U-15 | 观察（日志体系） | 无统一请求/响应日志：仅 `ExceptionFilter` 记录异常（WARNING/SEVERE + 堆栈）；`BaseServlet` 基类只做 `init` 注入与写响应辅助，各 Controller `doGet/doPost` 无入参、无耗时记录——全仓 controller 层仅 `AppShutDownListener` 有 logger，排查依赖外部 access log | `BaseServlet.java`（无 logger）；`ExceptionFilter.java` L24-35（仅异常日志）；`grep logger` 于 controller 包仅命中 `AppShutDownListener` | 第六期 NEEDS 4.1 N10（2026-09-18 探查） | **留池（2026-09-18 评审转出）**：属日志体系改造主干项（统一 Filter/基类埋点 + 格式约定），不做单独摸底、摸底放日志体系改造分支立项前更有时效；本杂务周期明确不排（NEEDS 4.3 反面清单） |
| U-16 | 观察（异常处理） | 注册后自动登录缺少异常处理：`LoginController.register` 注册成功但自动登录失败（理论上不应发生）时无返回 token 兜底——建议登录失败时返回注册成功但提示手动登录 | `LoginController.register()`（`userService.login(id, rc.getPassword())` 失败路径无兜底） | `BUSINESS_FLOW` 八节问题2（2026-07-23 登记；2026-09-18 T2 常青瘦身删除八节时转出） | 待定 |
| U-17 | 观察（并发/限流） | 优惠券抢购无用户级限流：高并发下大量请求同时通过库存检查，存在超卖风险；当前缓解 = DB 行锁（`UPDATE ... WHERE stock>0`）+ `coupon_order` 唯一索引（防重复抢） | `CouponService.grabCoupon()`（`deductStock` 乐观锁） | `BUSINESS_FLOW` 八节问题5（2026-07-23 登记；2026-09-18 T2 常青瘦身删除八节时转出） | 待定 |
| U-18 | 观察（装载量） | 关注/粉丝列表**装载路径仍为全量**：`ZSetCache` miss 单飞回填与 Redis 降级作答都走"DB 全量 loader + 全量 ZADD 回填"（`FollowDao.getAllFollowedUserIds`/`getFollowerUserIds` 无 LIMIT）；T7 只消除了**命中路径**的 `SMEMBERS` 全量回传与 String→Long 装箱（hit 为 `ZRANGE[start,stop]`+`ZCARD`，O(log n + N)）。与 R-01（索引全量读保留）同型：真解决需装载侧分页（DAO 分页 SQL / keyset）或分段回填，属独立改造 | `ZSetCache.getWindow`/`getMembers`（miss 与降级分支的 loader 全量）；`FollowCache.loadFollowingIds/loadFollowerIds`；`FollowDao`（无分页 SQL） | 第六期 T7 执行发现（2026-09-19，随 A1 落地显式登记） | 待定（留池） |
| U-19 | 观察（包架构） | 分页信封**同形二分**：`content.model.dto.PageResult` 与 T7 新增的 `follow.model.dto.FollowPageResult` 字段与 JSON 形状完全一致（list/total/page/pageSize/totalPages）。T7 之所以不直接复用：content 域已 import `follow.service.FollowCache`，follow 反向 import content DTO 会形成**新的 follow↔content 包层环**（与 U-07 的 content↔comment 同型）。建议把分页信封上移到公共包（如 `com.itheima.common.dto.PageResult`）供多域共用（涉 content 域 6 个 main 文件 + 4 个测试文件的 import 调整，属跨域重构） | `content/model/dto/PageResult.java`（FeedService/ProfileService/ContentService/FeedController/SearchController/ProfileVO + FeedServiceTest/ProfileServiceTest/ContentServiceTest）；`follow/model/dto/FollowPageResult.java` | 第六期 T7 执行发现（2026-09-19） | 待定（留池） |
| U-20 | 观察（读放大） | 评论列表分页后**命中路径仍每次读并反序列化整树**：`content:comments:{id}` 是单个 JSON 值，无法按窗口读，故 `/comment/show` 传分页参数时仍是"取整树 → 按主楼切片"；T8 只压了**响应体大小 / VO 转换量 / 点赞批量查询量**（DB 只查该页 id），单次成本仍随评论总量**正相关**。与 U-18（follow 装载全量）同型；彻底治本需缓存结构改造（`content:commentRoots:{id}` 主楼序列独立键 + 楼中楼按主楼分键，或 DB `LIMIT/OFFSET`/keyset 窗口读 + `parent_id IN` 批量取楼中楼），两者都需先放开 T8 红线"不动缓存装载结构"。**不紧急依据**：1 条评论 ≈ 200B JSON → 1000 条约 200KB（反序列化 ms 级）、5000 条约 1MB，远小于一次网络往返；单内容评论量上万时才值得立项 | `CommentCache.getCommentTree`/`loadCommentTree`（整树单键）；`ContentService.sliceRoots`（切片源=整树主楼列表）；`CommentDao.getComments`（无 LIMIT） | 第六期 T8 执行发现（2026-09-19，随 D1=A 落地显式登记） | 待定（留池） |

> 已消化/已修复项（U-05、U-08、U-09、U-10）已随各周期落地，2026-09-17 清理移出本档；**U-12（follow 域大集全量装载）已随第六期 T7 落地**（转 NEEDS N11 → T7；残留装载形态转 **U-18**）。U-13（评论树全量）已随第六期 **T8** 落地（2026-09-19：`/comment/show` 主楼分页 + 楼中楼整树、缓存结构不变；装载侧残留与命中路径读放大转 **U-20**）。

***

## 三、登记模板

```markdown
| U-0N | 类别 | 一句话问题 | 位置/证据链接 | 来源（周期/任务/日期） | 待定 |
```

类别取值示例：文档滞后 / 文案与实现不一致 / 代码债 / 观察 / 其它。