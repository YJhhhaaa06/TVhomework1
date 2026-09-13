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
| U-05 | 代码债 | `LikeService.likeContent` 缓存更新在事务外，靠定时刷新兜底（计数暂可能不准）——已结转至后续方向 C，此处留痕 | LikeService.java（缓存写入点） | 结转向 P5（需求文档二） | 已消化（260913 周期 T6，P5 已关闭） |
| U-07 | 观察（包架构） | content ↔ comment 包层循环依赖：content 域共享组件（ContentCacheDTO / ContentCache / CommentCache 等）被 comment 域引用，而 content 域又引用 comment 域的 CommentService。**非 IoC/Bean 环**（依赖链有向无环，容器可正常构建、测试全绿），仅包架构不纯净，Java 允许 | content.ContentService 注入 comment.CommentService（含 comment.dao.CommentDao）；comment.CommentService 注入 content.service 的 ContentCache / CommentCache | 260912 周期（B）探索记录（2026-09-10）；260913 周期复查（2026-09-13） | 待定（C 周期复查：**仍在**，未自然解除；消化点顺延至后续周期） |
| U-08 | 代码债 | 缓存 key 规范"唯一源"已出现漂移：内容索引 key `content:index:{type}:{category}` 由业务包内的 `ContentCache.indexKey()` 自行拼接（`CacheKeys` 无对应生成方法），却由基建 `CacheKeys.domainOf()` 按 `content:index:` 前缀解析——生成与解析不同源 | `ContentCache.java:339` `indexKey()`；`CacheKeys.java:91` `domainOf` 分支 | 260913 周期归档后代码探查（2026-09-13） | **已排期 → 本周期 T6**（R-11 拍板，2026-09-13） |
| U-09 | 代码债 | 缓存行为（三态/空标记/降级/单飞/续期）存在 **5 份重复实现**：`CacheAside` 1 份 + `ContentCache`/`CommentCache`/`LikeCacheService`/`FollowCache` 各 1 份（原生 Set 路径走不了 CacheAside，只能各域手搓）。域缓存类合计 ≈1774 行 > cache 基建包 ≈881 行 | `CacheAside.java`；`FollowCache.java`（scanSet/writeSet/单飞/降级各一段）；`LikeCacheService.java`（scanLikeSet 等） | 260913 周期归档后代码探查（2026-09-13） | 待定（**本周期明确不做**：不与"改行为"的修复混在同一周期，理由见 NEEDS 4.3；只在 T2/T4 必要处做定向复用） |
| U-10 | 代码债 | Redis 宕机缺"快速失败"：`app.properties` 只有 host/port/maxTotal/maxIdle/minIdle，**无 timeout 配置**（走 Jedis 默认 ≈2s），**无熔断**。降级语义本身正确（不 500），但 Redis 宕机期间每个请求都要先等一次连接超时，延迟被放大 | `app.properties:10-15`；`MyRedisPool.java`（静态池，未设 timeout） | 260913 周期归档后代码探查（2026-09-13） | **已修复（本周期 T1，fix(cache-01)，2026-09-13）**：显式超时 + 全局熔断（见 CURRENT_ARCHITECTURE 6.6） |
| U-11 | 观察（降级质量） | Redis 停机时 `/start` 推荐返回**空列表**（HTTP 200 `data:[]`），无 DB 兜底——`getRecommendByFilter` 依赖 Redis 索引（`ensureIndex` 懒重建需写 Redis、失败"本次推荐降级为空"；`readIndex` 失败"降级为空推荐"），索引不可读即无候选可批量装载。属**既有语义**（C 周期实现注释明写"Redis 异常降级为空/不 crash"），非 T1 引入；T1 熔断只是让"降级为空"来得更快。业务未 500，但"缓存失败不导致业务失败"在此处体现为"返回空推荐"而非"DB 兜底推荐"，用户可感知 | `ContentCache.ensureIndex`（懒重建失败 catch"降级为空"）；`ContentCache.readIndex`（catch"降级为空推荐"）；运行时实证：docker stop redis → /start 38B 空响应（2026-09-13） | 三期 T1（cache-01）运行时验证发现（2026-09-13） | 待定（修复方向若做：索引不可读时 DB 直查兜底；涉及 ContentCache 降级语义调整，建议随 T2/T3 或单独评估） |

> 注：U-05 与需求文档"二、结转遗留未决项 P5"重复，但按"颗粒度"归本档留痕；P5 已随 260913 周期 T6 消化，本行仅留痕。

***

## 三、登记模板

```markdown
| U-0N | 类别 | 一句话问题 | 位置/证据链接 | 来源（周期/任务/日期） | 待定 |
```

类别取值示例：文档滞后 / 文案与实现不一致 / 代码债 / 观察 / 其它。

***

## 四、变更记录

为了减缓文档膨胀，本文档不写变更记录