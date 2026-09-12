# 下一周期需求与痛点

> 用途：回答"下一周期为什么做这些"——本周期要解决的痛点、候选任务的优先级映射、以及开工前必须拍板的技术决策。
> 状态：**一版已完成（T1~T6，2026-09-12）；二期 T7 观测埋点、T8 读路径加固、T9 O-8 TTL 精调全部完成（2026-09-13）**——一版核心决策（统一 Redis + Cache-Aside 三态 + 空标记独立 key + 短 TTL + 可降级 + 写失败=失效（DEL）+ 统一单飞 + 关注双 Set + MULTI + 全部重制）已全部落地（T1 基建 + T2~T5 四域 + T6 收尾）。二期 = 观测埋点（惰性日志，T7）+ 读路径加固（H12/H13，T8）+ O-8 TTL 精调（T9）全部完成，详见 4.14；O-7 拍板维持 FULLTEXT 直查；O-5/O-9 留池未排期；分布式继续延后。本文档不归档（二期需求已排、O-5/O-9 未消化）。
> 来源：260912-package-refactor 周期（B 方向 feature package 改造，T1~T9 全部完成，pkg-01~pkg-09 已合并）归档后的新一轮规划。方向：用户提出缓存改造（此前预告的 C 方向），当前分支 `refactor/cache-architecture`。
> 术语约定：**周期 > 任务**。本文档只回答 Why（需求与决策），How（拆任务）在任务清单文档。

***

## 一、结转：仍有效的通用约定（来自已归档周期，随周期继续生效）

| 编号 | 约定 | 说明 |
| ---- | ---- | ---- |
| C-1 | 双文档结构 | 本文档（需求与痛点，决策唯一源）+ 任务清单（执行细节） |
| C-2 | 一任务一窗口一 commit | 默认期望，允许例外需标注；commit message 强制带任务编号 |
| C-3 | commit 语义闭环 | 代码 + 常青文档更新 + 任务清单勾选进**同一 commit** |

> 本周期涉及架构改动，周期约定（G1-G10）沿用，细节在任务清单文档落定时补齐。

***

## 二、结转：遗留未决项（260912-package-refactor 归档时未关闭）

| 编号 | 事项 | 状态 | 说明 |
| ---- | ---- | ---- | ---- |
| P5 | 缓存一致性弱点 | **本周期已消化（T6）** | `LikeService.likeContent` 缓存更新在事务外，靠定时刷新兜底（计数暂可能不准）→ 由本周期 C 缓存改造消化：写路径条件写+失败失效、读三态自愈、定时刷新移除（详见 4.2/4.6 与 T6 回写） |
| P6 | 优惠券抢购限流 | 默认不做 | 需用户确认纳入才拆任务；改动面 `CouponService`/`CouponController` |
| U-07 | content ↔ comment 包层循环依赖（非 Bean 环） | 待定（C 周期后观察：仍在） | content 域共享组件被 comment 域引用（CommentService 注入 ContentCache/CommentCache），content 又引用 comment 的 CommentService；仅包架构不纯净，Java 允许；T3 已移除 CommentService→ContentCacheManager 依赖，但包层环未自然解除，不随缓存重制消除 |

***

## 三、本周期痛点（Why 梳理）

### 3.1 现状确认（探索结论，2026-09-12，全部属实）

用户现状描述 + 探索验证：

| # | 用户描述 | 证据 |
|---|---------|------|
| 1 | 初始化全量加载：全部 content 进缓存 | `ContentCacheManager.init()` → `refresh()` → `contentDao.findAllContent` 全表 + 逐条媒体 + 逐条评论树 |
| 2 | 查询走缓存，miss 查库回填，无防重 | `getContentFromCache` → `backfillContent`，无锁无单飞，并发 miss 同时打库 |
| 3 | 评论与内容同生同灭，评论 miss=没有评论 | `getCommentTree` miss 返回空列表；`cacheContent`/`evictContent` 成对操作 |
| 4 | 数据更新后更新缓存 | 评论增删/点赞/编辑/下架各自同步，分散在各 Service 事务提交后 |
| 5 | 三套读取方式并存 | Redis（点赞 LikeCacheService）/ HashMap（内容/评论/索引）/ 直接查库（关注 FollowDao、UserService、搜索 FULLTEXT） |
| 6 | 无防重 | 内容回填与点赞回填 `syncContentLikers` 均无单飞 |
| 7 | 10 分钟定时全量刷新，数据多即雪崩 | `scheduleAtFixedRate`（`cache.content.refreshMinutes=10`）全量重建 |
| 8 | 启动全量加载，评论也全量 | `init()` 即 `refresh()` 全量 |

### 3.2 探索发现的问题清单（本周期内部编号 H1~H10）

| 编号 | 严重度 | 问题 |
| ---- | ---- | ---- |
| H1 | 高 | HashMap 并发不安全：`contentCache`/`commentCache`/`typeCategoryIndex`/`recommendList` 均裸集合；回填写与并发读同 map、refresh 引用替换与写线程竞态会丢写；`updateCacheAfterAdd` 的 `recommendList.addFirst` 无锁，与 `removeIf`/`set` 并发可抛 CME |
| H2 | 高 | TTL 语义：命中不续期，热点内容固定 10 分钟过期 → 下次访问回填，等于周期性全量重载，放大 DB 压力 |
| H3 | 中 | 缓存更新在事务内（`addVideo`/`addPost` 调 `updateCacheAfterAdd`），回滚时缓存已写入 → 脏缓存 |
| H4 | 中 | 内存计数非原子（`updateContentLikeCount` 直接改可变 DTO），并发点赞丢计数；与 refresh 引用替换竞态可改到旧对象 |
| H5 | 中 | Redis 写失败即接口 500：`LikeService` 事务提交后 `cache.likeContent` 无 try-catch，DB 已成功但用户收到失败，重试撞 `ConflictException` |
| H6 | 中 | Redis 点赞 key 永不过期（`content:like:{id}`/`comment:like:{id}` 无 TTL 无清理），累积僵尸 key |
| H7 | 中 | N+1 遍布初始化：全表 content → 每 content 查媒体 → 每 content 查评论树，三条 N+1 串在一个长事务里 |
| H8 | 低 | 缓存对象对外共享可变引用：`getContentFromCache` 返回内部 DTO，`imageUrls` 浅拷贝共享 |
| H9 | 低 | `authorName` 冗余不同步：用户改名后缓存旧名字需等刷新（当前无改名接口，潜在） |
| H10 | 低 | `getRecommendByFilter` 每次拷贝并 shuffle 全量索引，只取 12 条，数据量大时 O(n) |

> 另有**实锤 bug**（H11，独立登记）：[LikeCacheService.syncContentLikers](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/like/service/LikeCacheService.java#L222-L234) 的 `__placeholder__` 占位写法已失效——`sadd` 后立即 `srem` 移掉最后成员，Redis 自动回收空集合 key，等于没标记，`EXISTS` 仍 false，继续穿透。

> **二期探索补充（2026-09-12，一版落地后复查发现）**：

| 编号 | 严重度 | 问题 |
| ---- | ---- | ---- |
| H12 | 中 | 读路径 RTT 放大：[CacheAside.getInternal](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/cache/CacheAside.java#L94-L118) 每次读 = EXISTS 空标记 + GET 两趟往返；/start 推荐 12 条内容逐条 `getContent` ≈ 24+ 趟往返（[ContentCache.getRecommendByFilter](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ContentCache.java#L90-L113)）；索引 `LRANGE 0 -1` 每请求拉全量 id |
| H13 | 低 | KEYS 命令阻塞：[removeContent](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ContentCache.java#L139-L153) 与 rebuildIndexes 用 `KEYS "content:index:*"`，Redis 主线程 O(N)，数据量大后隐患（应换 SCAN） |
| H14 | 低 | 无观测能力：全链路只有 WARNING 日志，无命中率/穿透/降级/写失败计数——O-8 TTL 精调与后续调优没有数据依据 |

### 3.3 目标形态（Why 的答案）

缓存体系重制为**统一 Redis 缓存层**：一个缓存源、统一序列化、统一 TTL/失效策略；读走 Cache-Aside 三态自愈，写可降级；评论与内容解耦为业务显式失效；点赞计数与成员分离。缓存仅作加速器，**任何缓存失败不得导致业务失败**。

***

## 四、目标方案（已拍板决策，2026-09-12）

### 4.1 统一 Redis 缓存

- 内容 / 评论 / 点赞 / 关注（若入缓存）全部收敛到 Redis；**废弃 ContentCacheManager 的成员变量 HashMap** 与 `recommendList`/`typeCategoryIndex` 内存结构。
- **类型分区索引方案（原"方案待定"，T2 已拍板 2026-09-12）**：`typeCategoryIndex` 迁为 **Redis LIST `content:index:{type}:{category}`**（每个内容写 4 个 key：`(t,c)/(t,-1)/(-1,c)/(-1,-1)`，`LREM+LPUSH` 保持新前序）；启动 `ContentCache.init()` 全量重建 + 索引 key 缺失时单飞懒重建（防 Redis 重启/被清后 /start 空推荐）；`recommendList` 为全仓库无读取的死代码，**废弃不迁移**（随旧类 T6 清理）。
- 统一序列化规范（JSON 字符串存 value），统一 key 命名（`content:{id}`、`content:comments:{id}`、`content:index:{t}:{c}`、`content:likeCount:{id}` 等）。
- 判断依据：单实例下本地缓存更快，但"统一"优先于"放哪"；Redis 原生 TTL、重启自愈（miss 回填）、日后多实例兼容。
- **计数/门禁变更策略（T2 已拍板）**：内容点赞数/评论数/评论区开关（DB 列 like_count/comment_count/comment_enabled 为源真理）变更后只**失效 content key**，由 Cache-Aside 读自愈回填最新值；不做 Redis JSON 读改写（避免并发竞态丢更新）。

### 4.2 缓存必须可降级

- **读路径**：缓存 miss / Redis 挂了 → 查 DB 返回（降级，不报错）。
- **写路径**：DB 提交成功后缓存同步失败 → **失效缓存（DEL 相关 key）** + 记日志，由 Cache-Aside 自愈（下次读 miss → DB 回填，必带最新值）。
  - **语义是"删掉旧缓存让它自愈"，不是忽略写失败留着旧缓存**——否则热门内容的新评论/新点赞可能长期不可见（2026-09-12 用户质疑后修正，原文案"当没缓存"有歧义）。
  - DEL 也失败（Redis 挂）→ 读路径整体降级走 DB，仍然一致，不产生永久不可见窗口。
- **主链路任何一步不得因缓存失败而失败**。

### 4.3 Cache-Aside 三态区分

| 状态 | 含义 | 处理 |
| ---- | ---- | ---- |
| miss（key 不存在） | 未知/未加载/已过期 | 查 DB 回填 |
| hit-empty（key 存在，空标记） | 已加载，确实无数据 | 直接返回空，不查 DB |
| hit-data（key 存在，有数据） | 有数据 | 直接返回 |

- 评论缓存 miss **不得**当作"评论不存在"（修正现设计）。
- 空标记独立 key + 短 TTL（见 4.4）。

### 4.4 空标记：独立 key + 短 TTL（已拍板）

- 不碰业务容器，另起 String key 标记"已确认无数据"：

```
content:comments:{id}        → list/zset 或 JSON（有评论）
empty:content:comments:{id}  → "1"，TTL 短（约 30s~5min）
```

- 读路径：`GET empty:...` 命中 → 返回空；miss → 查数据 key → 还没有 → DB 回填（有数据写数据 key，无数据写空标记）。
- 优点：业务容器纯数据零脏值；空标记短 TTL 自动过期；不依赖 Redis 空容器回收行为。
- 配套：废弃 `__placeholder__` hack（H11）。

### 4.5 同生同灭 → 业务显式失效

- "内容删了评论不可见"改为业务操作显式失效：删除内容 → 显式删 `content:{id}` + `content:comments:{id}` + 空标记。
- 读评论前先确认 content 存在（读 content 缓存，miss 则查 DB）。
- 内容与评论解耦：各自独立 TTL、独立回填，约束从"缓存结构"变为"业务操作"。

### 4.6 点赞：计数与成员分离

```
content:likeCount:{id}  → int（回填/加减即可，高频读）
content:likeSet:{id}    → set（仅在需要"谁点过"时查，低频，允许 miss 穿透）
```

- 计数独立 key 天然避开"空集合被回收"问题；点赞不再需要占位符。
- 评论点赞同理。

### 4.7 不引入 MQ；异步化可选

- 缓存更新"失败不阻塞主流程"由 4.2 的同步容错 + 降级解决，**不需要异步**。
- 如需藏延迟（Redis 慢但不死），用**进程内线程池**（`ExecutorService`，单实例内部足够），不引入 MQ（RabbitMQ/Kafka/RocketMQ 成本高，其持久化/投递保证对缓存场景无用）。
- Redis 彻底挂时异步队列反而堆积注定失败的任务，正确行为是"失败快、别排队、直接降级"。

### 4.8 修复 placeholder 实锤 bug

- `syncContentLikers`/`syncCommentLikers` 的 `__placeholder__` 写法失效（H11），随 4.4/4.6 重制一并清除。

### 4.9 统一单飞组件（O-1 已拍板）

- **全部回填点都做单飞**，但通过**一个统一组件**实现，四处复用（内容 / 评论 / 点赞 / 空标记），不各自写一遍。
- 实现：`ConcurrentHashMap.computeIfAbsent` + `FutureTask`（进程内锁 + 双检）：

```java
public <T> T get(String key, Callable<T> loader) {
    FutureTask<T> task = new FutureTask<>(loader);
    Future<T> existing = inFlight.putIfAbsent(key, task);
    if (existing == null) { task.run(); } else { task = (FutureTask<T>) existing; }
    try {
        return task.get();
    } catch (Exception e) {
        inFlight.remove(key, task); // 失败必须清掉，否则后续请求永远拿到失败
        throw new RuntimeException(e);
    }
}
```

- 三个配套约束（比写单飞更值得注意）：
  1. **loader 失败必须 remove**，防缓存失败结果；
  2. **成功后清理** inFlight 项或设上限，防 key 无限增长内存泄漏；
  3. 进程内锁**只对单实例有效**，多实例需分布式锁（当前单 Tomcat 够用，不过度设计）。
- 单飞不改变缓存语义，只解决"同一瞬间重复打 DB"；与 TTL、空标记、失效策略正交。

### 4.10 关注关系入缓存（O-3 已拍板）

- 以**用户为中心**维护两个 Set（用户提出，采纳）：

```
user:following:{userId}  → Set<followedUserId>   （我关注了谁）
user:follower:{userId}   → Set<userId>           （谁关注了我）
```

- 一份关系同时服务：单条/批量 `isFollowing`（SISMEMBER）+ feed 拉关注列表（SMEMBERS）。
- **写路径双写原子**：关注/取关一次写两个 key，用 **Redis MULTI 事务**包住；失败则**双 DEL** 一起失效（宁可读回填，不要半新半旧）。
- **边界（重要）**：本项只缓存"关系"本身；"我关注的人的最新内容列表"（feed 聚合）属 D 方向 feed 改造，**不在本周期**。

### 4.11 重制方式（O-4 已拍板：全部重写）

- 改动面过大、简单修补不够 → **全部重写缓存层实现**，但三点明确：
  1. **重写 ≠ 推倒业务**：重写 ContentCacheManager + LikeCacheService + 新增关注缓存 + 各 Service 调用点；业务逻辑（点赞去重/楼中楼/搜索）一字不动；对外语义（Controller/URL/pytest 行为）**零变化**；
  2. **顺势拆 god class**：ContentCacheManager 现 666 行混内容/评论树/索引/推荐/计数/定时刷新，重写时拆成职责清晰的小类（内容缓存、评论缓存、关注缓存、单飞组件、序列化工具），**不要重写后再堆一个大类**；
  3. **分阶段切换**：先建新缓存层骨架（统一 Redis + 单飞 + 序列化），再逐域切换读路径（内容→评论→点赞→关注），每步 JUnit/pytest 全绿，符合"一任务一 commit"。

### 4.12 一版 / 二期切分（已拍板，防过度设计）

- **一版基础方案（必须做，否则不可用）**：
  1. 统一 Redis + Cache-Aside 三态 + 空标记（内容/评论）——正确性地基
  2. 写失败 → DEL 失效 + 读自愈——容错地基
  3. 统一单飞组件——防击穿
  4. 点赞计数/成员分离 + 关注关系（双 Set + MULTI）
- **二期迭代（一版跑通后再加）**：
  5. TTL 策略精调（滑动续期/抖动/取值）——一版先用固定 TTL + 简单抖动
  6. 初始化选择性加载、定时刷新去留
  7. 性能调优、分布式
- 迭代前提：一版结构不堵死二期（4.1~4.11 一次做对，5~7 留口子）。
- **二期范围已拍板（2026-09-12，见 4.14）**：观测埋点（治 H14）+ 读路径加固（治 H12/H13）+ O-8 TTL 精调；O-7 维持 FULLTEXT 直查；O-5/O-9 留池；分布式延后。

### 4.13 基建归属（已拍板：cache 单独成包）

- 新建 **`com.itheima.cache` 基建包**（与 `ioc/filter/util/exception/config` 平级），装技术无关的缓存基建：
  - 统一 Redis 访问封装、JSON 序列化、统一 key 生成规范、单飞组件、三态/空标记/写失败 DEL 封装。
- **业务缓存类放各自业务域**（不塞进 cache 包）：内容缓存→content、评论缓存→content、点赞缓存→like、关注缓存→follow（各自域内定义 key 命名、TTL、失效逻辑，import `com.itheima.cache` 基建）。
- 理由：① 基建会被 content/comment/like/follow/admin 全域引用，放业务域会造成其它域反向依赖业务域，破坏 B 周期域边界（硬伤）；② util 是"通用小工具"（连接池/JWT/密码/日志），缓存抽象是本次核心地基，塞 util 会变杂货铺。
- 分工边界：`com.itheima.cache` = 技术无关；业务域 = 技术 + 业务（key/TTL/失效策略）。

### 4.14 二期范围与设计要点（已拍板，2026-09-12）

- **二期主线 = "测量 → 优化 → 再测量"闭环**，拆 3 任务（T7→T8→T9，见任务清单）：
  1. **T7 观测埋点（治 H14）**：新增 `CacheStats` 统计组件——六类事件计数（hitData / hitEmpty / miss / loadCount / degradeCount / writeFailCount），AtomicLong 无锁；**按 key 前缀分域分桶**（域解析收敛到 `CacheKeys.domainOf` 单一源，key 生成与解析同源不漂移；分域是刻意设计：将来 O-8 分域调 TTL 需要各域自己的命中率曲线）；埋点位置 = CacheAside 自动挂（JSON 路径全覆盖）+ LikeCacheService/FollowCache 原生 Set 三态路径手动打点（不埋则点赞成员/关注关系穿透率是盲区）。
  2. **T8 读路径加固（治 H12/H13）**：CacheAside 单 key 读 pipeline 化（EXISTS 空标记 + GET 合一趟往返）+ 批量读接口（pipeline/MGET，批量三态判断须与单 key 语义一致）；`getRecommendByFilter` 逐条 getContent 改批量；`KEYS "content:index:*"` → SCAN。**LRANGE 全量读保留**（推荐 shuffle 对外语义不变）。
  3. **T9 O-8 TTL 精调**：读命中顺带续期（挂 T8 pipeline 读路径）+ 分域 TTL 取值（只动既有 `cache.*.ttlMinutes` 配置）；**调参依据 = T7 观测数据**（本地流量不足时用测试/压测流量造数，执行时评估）。
- **统计输出方式（用户拍板）：惰性日志**——每 N 次缓存访问顺带输出一次各域摘要；**不引入定时器**（与 O-6 移除定时刷新的决策不冲突：统计只读不重建）、**不新增 admin 端点**。
- **O-7 搜索（用户拍板）：维持 FULLTEXT 直查，不做缓存**——关键词基数大命中率低、结果新鲜度敏感，缓存性价比差。
- **待 T9 执行时拍板**：空标记 60s 是否参与续期（倾向**不续期**，防"假空"窗口延长，执行时定）。
- **T7 执行定稿（2026-09-12，G7 回写）**：① 惰性日志阈值 **N=1000**（`DEFAULT_LOG_INTERVAL`，类内常量；不引入定时器/端点），摘要 INFO 单行格式 `CacheStats 摘要: total=.. content{hitData=.. …}`；② 域解析 `CacheKeys.domainOf` 长前缀优先（content:index/content:like/content:comments 先于通用 content:），`empty:` 解包到底层数据 key 再归域，**content:index 归 CONTENT 域**；③ 批量记录粒度=**每 (数据 key, 决策) 记一次**（like 批量 key 各异按 id、follow 批量单 key 按一趟）；④ **Like/Follow 原生 Set 写方法 catch 也计 WRITE_FAIL**（补全各域写失败口径，仅记数不改语义）；⑤ 挂点清单：CacheAside 自动打点（read/getInternal 三态 + 降级 + invokeLoader 入口 LOAD + writeOrInvalidate/markEmpty/deleteQuietly 写失败）+ LikeCacheService/FollowCache 三态读分支与批量 pipeline 手动打点。
- **T8 执行定稿（2026-09-12，G7 回写）**：① 单 key 读（`read`/`getInternal`）pipeline 化——EXISTS 空标记 + GET 数据 key **一趟往返**（内部 `probe(dataKey)` 复用，三态/空标记/单飞/降级/统计逐条不变）；② 批量读接口 **`CacheAside.getBatch(List<String>, Class<T>, Function<String,T>, long)` → `Map<String,T>`**：一趟 pipeline 批量 EXISTS+GET，三态判断与单 key 完全一致（先空标记后数据 key），miss 项逐个单飞回填，统计按 (key, 决策) 打点（T7 口径延续）；**单个 key 脏 JSON 或整批 Redis 异常 → 该 key/全部 key DEGRADE + 直接 loader 不写回**（对齐单 key 降级语义，防拖垮整批）；调用方保证 key 无重复；③ 内容批量接入：`ContentCache.getContentsBatch(List<Long>)`（id→DTO，null 值=hit-empty/DB 无数据透传），**推荐（/start）、Feed 页、Profile 分页三处高频多 key 循环全部切批量读**（任务强制探索 ① 评估结论；结果集/顺序/空跳语义不变）；④ 索引 `KEYS "content:index:*"` → **SCAN**（`ContentCache.forEachIndexKey`，`scan(cursor, ScanParams.match("content:index:*").count(100))` 游标收敛于 "0"；removeContent LREM 与 rebuildIndexes DEL 两处；LREM/DEL 幂等、SCAN 重复 key 无害）；⑤ 批次策略=**整批一趟 pipeline**（不 chunk；LRANGE 已全量拉回 id、内存基准一致）——为 T9 续期挂点保留同一 pipeline 读路径结构；⑥ 测试：JUnit surefire 308 + pool 4 = 312 例全绿（CacheAsideTest +7 批量读/往返断言、ContentCacheTest +4 批量与 SCAN 多游标），pytest all 124 passed。
- **T9 执行定稿（2026-09-13，G7 回写）**：① **续期触发粒度=每次命中都续**（pipeline 内直接追加 EXPIRE、零额外往返；否决"剩余 TTL 低于阈值才续"——需多一趟 TTL 查询，违背 T8 一趟结构，T8 定稿⑤保留的同一 pipeline 读路径结构即为本点挂载）；② **空标记不续期（执行定稿）**——续期只对 data key 发 EXPIRE，`empty:` key 从不被 EXPIRE（防"假空"窗口延长）；实现=对数据 key **无条件入列 EXPIRE**（hit-data 生效；hit-empty/miss 时数据 key 不存在 EXPIRE 返回 0 无效果；EXPIRE 失败即 pipeline Redis 异常→既有降级 loader，读不受影响）；③ **续期值=原 TTL ±10% 抖动**（CacheAside 路径复用 applyJitter 保底 1s；原生 Set 路径=域 TTL 精确值，与写路径 expire 口径一致）；④ **挂点清单**：CacheAside `getInternal`（新增私有 `probeRenew(dataKey, ttl)`：exists+get+expire 一趟）/`getBatch`（命令收集循环逐 key expire）；LikeCacheService `scanLikeSet` + 内容/评论两个批量 pipeline；FollowCache `scanSet`/`getSetMembers`/`batchIsFollowing`；`read()`（纯三态读、无 TTL 上下文、无生产调用方）与 FollowCache `probePair`（写路径只读探测）不续期；⑤ **分域 TTL 取值（用户拍板=轻量造数+不足保守回退）**：本地无累积观测数据 → 新增 temp_script/pressure_cache.py（Python，注册临时用户+遍历域读端点 /start /search/IdSearch /comment/show /like/content|comment 的 status|count /follow/following|followers /feed /profile，预热填缓存+测量段 ≥3000 次记录触发 ≥3 条 CacheStats 摘要，只读不落盘）——**改动前基线**压测 vs **续期后**对比（total=21000 摘要）：content 恒 100% hitData（启动全量重建+热读）、comment 命中占比 38.4%→42.5%（134/349→148/348）、like 59.0%→64.8%（847/1436→931/1436）、follow 以空关系为主 hitEmpty≈100%（miss 仅 2 一次性装载）；⑥ **取值**（只动 app.properties，不改代码接线）：content 10→**30min**（热读常驻、miss≈0 无穿透风险）、comment 保持 **10min**（新鲜度敏感：评论增删/点赞失效驱动，命中 38~42% 保持短 TTL 最安全）、like 10→**15min**（命中率高且显式失效清晰）、follow 10→**30min**（关系低频变 + MULTI 双写失效清晰；压测未覆盖真实关系数据，依据写路径特性保守延长，标注"待真流量复调"）；⑦ **配置读取单测**：新增 config/AppConfigCacheTtlTest 5 例（四 getter 与 app.properties 绑定生效、非 0 互不串读）；⑧ **测试**：JUnit 323 例全绿（surefire 319 + pool 4 = T8 末 312 + 新增 11：CacheAsideTest +4 续期[命中续期抖动/空标记不续/批量续期/续期失败降级]、LikeCacheServiceTest/FollowCacheTest 各 +1 hit-data 续期、AppConfigCacheTtlTest +5），pytest all 124 passed；⑨ 常青文档同步（CURRENT_ARCHITECTURE 6.5 TTL 精调小节/6.2 key 表/4.2/9.2/12 更新日志 2.11 + BUSINESS_FLOW 3.1 注记）。
- **O-5 / O-9 继续留池未排期**；分布式（单飞进程内锁的多实例化）继续延后，不破一版留的口子。

***

## 五、未定项（待续聊，进任务清单前须拍板）

> O-1~O-4 已拍板（见 4.9~4.12），O-7/O-8 已拍板或排期（见 4.14），下表仅剩留池未定项。

| # | 未定项 | 归期 | 说明 |
| ---- | ---- | ---- | ---- |
| O-5 | 初始化选择性加载 | 留池（二期未排） | 启动全量加载（现状）改为按需回填 or 分级加载？评论是否仍全量 |
| O-6 | 定时全量刷新去留 | **已拍板（T6，一版移除）** | 旧 `ContentCacheManager.startScheduler` 10min 全量刷新已随旧类整体移除：内容/索引一致性由启动全量重建 + 索引 key 缺失单飞懒重建 + 业务显式失效（增删改/计数/门禁/隐藏恢复）+ Cache-Aside 按 key TTL 读自愈承担，不再需要周期性全库重载（原 H2/H7）。二期不再评估"保留/改造"；如需定期重建索引防长尾漂移，另行登记评估 |
| O-7 | 搜索是否入缓存 | **已拍板（2026-09-12，维持直查）** | 维持 MySQL FULLTEXT 直查、不做缓存（关键词基数大命中率低、新鲜度敏感；详见 4.14）。若未来要动，另行登记评估 |
| O-8 | TTL 取值与滑动续期 | **已完成（T9，2026-09-13）** | 读命中顺带续期（每次命中都续、空标记不续）+ 分域取值（content 30/comment 10/like 15/follow 30min），取参依据=双轮轻量压测 CacheStats 摘要（temp_script/pressure_cache.py），见 4.14 T9 执行定稿 |
| O-9 | followerCount/followCount 计数 | 留池（二期未排） | 关注关系入缓存后，Profile 展示的关注/粉丝计数是否一并入缓存（关系 Set 是成员，计数是独立 key；注意 SCARD 冷 set 返 0 的坑） |

***

## 六、本周期范围与边界（初步，待任务清单定稿后细化）

- **一版范围内（已完成，T1~T6）**：缓存层统一 Redis（重写 ContentCacheManager/LikeCacheService + 新增关注缓存，拆 god class）；三态 Cache-Aside + 空标记独立 key + 短 TTL + 简单抖动；写失败=失效（DEL）+ 读自愈；统一单飞组件；点赞计数/成员分离；关注关系双 Set + MULTI；占位符清除（H11）；H1~H6 修复；常青文档同步（CURRENT_ARCHITECTURE.md 六.Redis 设计、BUSINESS_FLOW.md 3.1 缓存机制等）；测试维护。
- **二期范围内（已排期，T7~T9）**：观测埋点 CacheStats（六类事件/分域/惰性日志，治 H14）；读路径加固（单 key pipeline 化/批量读/getRecommendByFilter 批量化/KEYS→SCAN，治 H12/H13）；O-8 TTL 精调（滑动续期+分域取值，依据 T7 数据）。
- **留池未排（O-5/O-9）**：初始化选择性加载；关注/粉丝计数入缓存——是否纳入后续迭代，跑完二期再评估。
- **范围外（默认不做）**：P6 优惠券限流；D feed 流改造（含 feed 聚合缓存）；前端；新增缓存之外的业务功能。
- **禁止**：Spring/SpringBoot/MyBatis（沿用）；擅自改动 `@WebServlet` URL、web.xml、IoC 扫描；业务逻辑改动（点赞去重/楼中楼/搜索，纯缓存层重制）。

***

## 七、决策与约束（2026-09-12 用户拍板记录）

- **缓存载体**：统一 Redis（用户拍板；"统一"优先于"本地 vs Redis"之争）。
- **三态方案**：Cache-Aside 三态区分（miss / hit-empty / hit-data），评论 miss 不再是"没有评论"。
- **空标记实现**：独立 key + 短 TTL（用户拍板，弃用容器占位/值编码方案）。
- **一致性策略**：Cache-Aside + 显式失效（内容删除级联删评论 key），不依赖"同生同灭"内存捆绑。
- **写失败语义（用户质疑后修正）**：写失败 = **失效（DEL）**让读自愈，不是忽略写失败留旧缓存——否则热门内容新评论/新点赞长期不可见。
- **容错原则**：缓存必须可降级，读写主链路不得因缓存失败而失败；不引入 MQ，异步化（若需）用进程内线程池。
- **单飞（O-1）**：统一组件（ConcurrentHashMap + FutureTask），内容/评论/点赞/空标记四处复用；失败必须 remove。
- **关注关系（O-3）**：以用户为中心 `user:following` / `user:follower` 双 Set，MULTI 双写，失败双 DEL；只做关系不做 feed 聚合。
- **重制范围（O-4）**：全部重写缓存层 = 拆 god class + 业务零变化 + 分阶段切换。
- **基建归属（用户拍板）**：新建 `com.itheima.cache` 基建包装技术无关组件；业务缓存类放各自业务域（内容/评论→content、点赞→like、关注→follow）。
- **MVP 切分（已拍板）**：一版基础方案（统一 Redis + 三态空标记 + 写失败失效 + 单飞 + 点赞/关注）→ 二期迭代（TTL 精调/加载策略/搜索/计数）。
- **红线措辞约定延续**：发现不改"红线"就阻碍后续工作 → 先向用户申请并说明理由，批准后才能动手。
- **编号引用约定延续**：禁裸编号引用已归档周期元素；裸编号仅指本文档内部定义元素（H1~H11、O-5~O-9；O-1~O-4 已关闭）。
- **定时刷新去留（O-6，T6 拍板）**：一版**移除**定时全量刷新；一致性由启动全量重建 + 索引懒重建 + 业务显式失效 + Cache-Aside 按 key TTL 读自愈承担（不再周期性全库重载）。
- **删除/下架内容的点赞缓存清理（T6 落地）**：旧 ContentCacheManager.evictContent 的"失效点赞缓存"副作用迁入 `LikeService.deleteContentLike`（ContentService 删除/下架内容 DB 提交后显式调用，失效 count+set+empty 三 key），保持删除链路对外行为零变化。

***

## 八、变更记录

| 日期 | 版本 | 内容 |
| ---- | --- | ---- |
| 2026-09-12 | 0.1 | 新建本文档：归档 260912-package-refactor（B 方向 T1~T9 完成）后开启 C 缓存改造周期；探索确认用户现状描述 8 条全部属实；列出探索问题 H1~H10 + 占位符 bug H11；拍板核心决策（统一 Redis / 三态 Cache-Aside / 空标记独立 key+短 TTL / 可降级 / 点赞计数分离 / 不引入 MQ / 同生同灭改业务失效）；登记未定项 O-1~O-7 待续聊 |
| 2026-09-12 | 0.2 | 拍板 O-1~O-4 并回写：新增 4.9 统一单飞组件（ConcurrentHashMap+FutureTask，四处复用，失败必须 remove）、4.10 关注关系入缓存（user:following/follower 双 Set + MULTI 双写 + 失败双 DEL，边界=只做关系不做 feed 聚合）、4.11 全部重写（拆 god class + 业务零变化 + 分阶段切换）、4.12 一版/二期切分；**修正 4.2 写失败语义**：写失败=失效（DEL）让读自愈，而非忽略留旧缓存（用户质疑"热门内容新评论长期不可见"后修正）；未定项重编号 O-5~O-9（TTL 滑动续期 O-8、关注/粉丝计数 O-9） |
| 2026-09-12 | 0.3 | 新增 4.13 基建归属：新建 `com.itheima.cache` 基建包装技术无关组件（统一 Redis 访问/序列化/key 规范/单飞/三态/空标记/写失败 DEL 封装）；业务缓存类放各自业务域（内容/评论→content、点赞→like、关注→follow），避免其它域反向依赖业务域破坏域边界 |
| 2026-09-12 | 0.4 | T2 内容缓存重制拍板回写（G7）：① 4.1 索引"方案待定"→已定：类型分区索引 = Redis LIST `content:index:{t}:{c}`（4 key/内容，LREM+LPUSH 新前序，启动全量重建+懒重建），`recommendList` 死代码废弃；② 4.1 新增计数/门禁变更策略：点赞/评论数/评论区开关变更 = 失效 content key 读自愈（DB 列为源真理，不读改写）；③ H3 修复落地：addVideo/addPost 的 Redis 缓存写入（`contentCache.addContent`）移出 DB 事务；④ 说明：旧 `updateCacheAfterAdd`（评论树空列表种子）因签名需 Connection 仍在事务内调用，仅作用于旧内存/评论树且对读路径无影响（H3 修复点=新 Redis 写入已移出），T3 迁出评论时删除；旧 `removeContent`/`refreshContent`（清内存评论树/旧点赞 key、恢复评论树）保留至 T3/T4 |
| 2026-09-12 | 0.5 | **T6 收尾拍板回写**：① O-6 定时刷新去留=**一版移除**（随旧 ContentCacheManager 整体删除，理由见 O-6 行）；② 删除路径点赞缓存清理迁入 `LikeService.deleteContentLike`（七决策记录）；③ P5 标记已消化；④ U-07 观察结论=C 周期后包层环仍在（未自然解除）；⑤ 0.4 遗留的 `removeContent`/`refreshContent` 遗产副作用已随旧类删除 |
| 2026-09-12 | 0.6 | **二期规划拍板回写**（一版 T1~T6 完成后的续期讨论）：① 状态行更新为一版已完成+二期范围已拍板，本文档不归档；② 新增二期探索补充问题 H12（读路径 RTT 放大）/H13（KEYS 命令阻塞）/H14（无观测能力）；③ 新增 4.14 二期范围与设计要点：主线=观测埋点（CacheStats 六类事件/分域/惰性日志输出）→ 读路径加固（pipeline 化/批量读/SCAN）→ O-8 TTL 精调（滑动续期+分域取值，依据 T7 数据），拆 3 任务 T7~T9；④ O-7 拍板维持 FULLTEXT 直查；⑤ O-5/O-9 留池未排期、分布式继续延后；⑥ 更新六.范围与边界 |
| 2026-09-12 | 0.7 | **T7 观测埋点完成回写**：① 4.14 补"T7 执行定稿"注记（N=1000、CacheDomain 5 域/domainOf 长前缀优先/content:index 归 CONTENT、批量记录粒度规则、Like/Follow 写失败也计 WRITE_FAIL、挂点清单）；② 状态行更新（T7 已完成、T8/T9 待执行）；③ 治 H14 落地，为 T8 前后对比与 T9 分域 TTL 调参提供观测数据依据 |
| 2026-09-12 | 0.8 | **T8 读路径加固完成回写**：① 4.14 补"T8 执行定稿"注记（单 key 读 pipeline 化一趟往返 probe、getBatch 批量读接口语义与 DEGRADE 口径、/start+Feed+Profile 三处批量接入、索引 KEYS→SCAN、整批不 chunk 策略与 T9 续期挂点结构保留）；② 状态行更新（T8 已完成、T9 待执行）；③ 治 H12（read 路径往返合并：单 key 2→1 趟、推荐 12 条 24+→1 趟 + 少量 miss 回填）与 H13（KEYS→SCAN）落地 |
| 2026-09-13 | 0.10 | **T9 O-8 TTL 精调完成回写**：① 4.14 补"T9 执行定稿"注记（续期粒度=每次命中都续、空标记不续期执行定稿、续期值=原 TTL 抖动、挂点清单、分域取值=轻量造数+不足保守回退、双轮压测数据、四域取值与依据、AppConfigCacheTtlTest、JUnit 323 + pytest 124）；② 状态行更新（T9 已完成，二期全收口）；③ O-8 行置已完成；④ 治 H2 残余形态"命中不续期"（滑动续期落地：热点 key 常驻由续期自然达成）；⑤ review 后修正 like 命中占比口径为 59.0%→64.8%（931/1436≈64.8%，原 66.3% 为手误） |
