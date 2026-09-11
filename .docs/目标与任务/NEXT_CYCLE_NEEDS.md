# 下一周期需求与痛点

> 用途：回答"下一周期为什么做这些"——本周期要解决的痛点、候选任务的优先级映射、以及开工前必须拍板的技术决策。
> 状态：**核心决策已拍板（2026-09-12）** —— 统一 Redis 缓存 + Cache-Aside 三态（miss/hit-empty/hit-data）+ 空标记独立 key + 短 TTL + 缓存必须可降级 + 写失败=失效（DEL）+ 统一单飞组件 + 关注关系入缓存（双 Set + MULTI）+ 全部重制（拆 god class、行为零变化、分阶段切换）。一版基础方案与二期迭代已切分；O-5~O-9 待续聊后再进任务清单。
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
| P5 | 缓存一致性弱点 | **本周期消化** | `LikeService.likeContent` 缓存更新在事务外，靠定时刷新兜底（计数暂可能不准）；与 ContentCacheManager 拆分改造同源 → 即本周期（C 缓存改造）主战场 |
| P6 | 优惠券抢购限流 | 默认不做 | 需用户确认纳入才拆任务；改动面 `CouponService`/`CouponController` |
| U-07 | content ↔ comment 包层循环依赖（非 Bean 环） | 待定 | content 域共享组件被 comment 域引用，content 又引用 comment 的 CommentService；仅包架构不纯净，Java 允许；本周期 C 是消化点（随缓存重制观察是否自然解除） |

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

### 3.3 目标形态（Why 的答案）

缓存体系重制为**统一 Redis 缓存层**：一个缓存源、统一序列化、统一 TTL/失效策略；读走 Cache-Aside 三态自愈，写可降级；评论与内容解耦为业务显式失效；点赞计数与成员分离。缓存仅作加速器，**任何缓存失败不得导致业务失败**。

***

## 四、目标方案（已拍板决策，2026-09-12）

### 4.1 统一 Redis 缓存

- 内容 / 评论 / 点赞 / 关注（若入缓存）全部收敛到 Redis；**废弃 ContentCacheManager 的成员变量 HashMap** 与 `recommendList`/`typeCategoryIndex` 内存结构（或迁为 Redis 索引，方案待定）。
- 统一序列化规范（JSON 字符串存 value），统一 key 命名（`content:{id}`、`content:comments:{id}` 等）。
- 判断依据：单实例下本地缓存更快，但"统一"优先于"放哪"；Redis 原生 TTL、重启自愈（miss 回填）、日后多实例兼容。

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

### 4.13 基建归属（已拍板：cache 单独成包）

- 新建 **`com.itheima.cache` 基建包**（与 `ioc/filter/util/exception/config` 平级），装技术无关的缓存基建：
  - 统一 Redis 访问封装、JSON 序列化、统一 key 生成规范、单飞组件、三态/空标记/写失败 DEL 封装。
- **业务缓存类放各自业务域**（不塞进 cache 包）：内容缓存→content、评论缓存→content、点赞缓存→like、关注缓存→follow（各自域内定义 key 命名、TTL、失效逻辑，import `com.itheima.cache` 基建）。
- 理由：① 基建会被 content/comment/like/follow/admin 全域引用，放业务域会造成其它域反向依赖业务域，破坏 B 周期域边界（硬伤）；② util 是"通用小工具"（连接池/JWT/密码/日志），缓存抽象是本次核心地基，塞 util 会变杂货铺。
- 分工边界：`com.itheima.cache` = 技术无关；业务域 = 技术 + 业务（key/TTL/失效策略）。

***

## 五、未定项（待续聊，进任务清单前须拍板）

> O-1~O-4 已拍板（见 4.9~4.12），下表仅剩未定项，按"一版/二期"归类。

| # | 未定项 | 归期 | 说明 |
| ---- | ---- | ---- | ---- |
| O-5 | 初始化选择性加载 | 二期 | 启动全量加载（现状）改为按需回填 or 分级加载？评论是否仍全量 |
| O-6 | 定时全量刷新去留 | 二期 | `scheduleAtFixedRate` 10 分钟全量刷新是否保留/改造（与 TTL 自愈的关系） |
| O-7 | 搜索是否入缓存 | 二期 | 现走 MySQL FULLTEXT（合理），是否维持现状只缓存详情 |
| O-8 | TTL 取值与滑动续期 | 二期 | 一版固定 TTL + 简单抖动；滑动续期（治 H2"热点固定过期反复回填"）是否做、各 key TTL 数值 |
| O-9 | followerCount/followCount 计数 | 二期 | 关注关系入缓存后，Profile 展示的关注/粉丝计数是否一并入缓存（关系 Set 是成员，计数是独立 key） |

***

## 六、本周期范围与边界（初步，待任务清单定稿后细化）

- **一版范围内**：缓存层统一 Redis（重写 ContentCacheManager/LikeCacheService + 新增关注缓存，拆 god class）；三态 Cache-Aside + 空标记独立 key + 短 TTL + 简单抖动；写失败=失效（DEL）+ 读自愈；统一单飞组件；点赞计数/成员分离；关注关系双 Set + MULTI；占位符清除（H11）；H1~H6 修复；常青文档同步（CURRENT_ARCHITECTURE.md 六.Redis 设计、BUSINESS_FLOW.md 3.1 缓存机制等）；测试维护。
- **二期（本周期不做，跑通后再排）**：O-5 初始化选择性加载；O-6 定时刷新去留；O-7 搜索入缓存；O-8 TTL 滑动续期与取值；O-9 关注/粉丝计数入缓存。
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

***

## 八、变更记录

| 日期 | 版本 | 内容 |
| ---- | --- | ---- |
| 2026-09-12 | 0.1 | 新建本文档：归档 260912-package-refactor（B 方向 T1~T9 完成）后开启 C 缓存改造周期；探索确认用户现状描述 8 条全部属实；列出探索问题 H1~H10 + 占位符 bug H11；拍板核心决策（统一 Redis / 三态 Cache-Aside / 空标记独立 key+短 TTL / 可降级 / 点赞计数分离 / 不引入 MQ / 同生同灭改业务失效）；登记未定项 O-1~O-7 待续聊 |
| 2026-09-12 | 0.2 | 拍板 O-1~O-4 并回写：新增 4.9 统一单飞组件（ConcurrentHashMap+FutureTask，四处复用，失败必须 remove）、4.10 关注关系入缓存（user:following/follower 双 Set + MULTI 双写 + 失败双 DEL，边界=只做关系不做 feed 聚合）、4.11 全部重写（拆 god class + 业务零变化 + 分阶段切换）、4.12 一版/二期切分；**修正 4.2 写失败语义**：写失败=失效（DEL）让读自愈，而非忽略留旧缓存（用户质疑"热门内容新评论长期不可见"后修正）；未定项重编号 O-5~O-9（TTL 滑动续期 O-8、关注/粉丝计数 O-9） |
| 2026-09-12 | 0.3 | 新增 4.13 基建归属：新建 `com.itheima.cache` 基建包装技术无关组件（统一 Redis 访问/序列化/key 规范/单飞/三态/空标记/写失败 DEL 封装）；业务缓存类放各自业务域（内容/评论→content、点赞→like、关注→follow），避免其它域反向依赖业务域破坏域边界 |
