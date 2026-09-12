# 下一周期任务清单

> 关联文档：目标与任务/NEXT_CYCLE_NEEDS.md（决策唯一源；此文件为执行细节）
> 状态：**任务拆分骨架（草稿）** —— 本周期做 C（缓存改造），一版拆 6 任务（T1 基建 + T2~T5 四域重制 + T6 收尾，默认期望 6 commit）；四要素详情**待细化**（执行方案后续逐任务填写，执行前仍可微调 G5）。
> 工作流：每个任务开独立窗口执行；"任务清单 + 需求与痛点"为窗口间唯一交接载体。
> 来源：260912-package-refactor 周期（B 方向 T1~T9 全部完成）归档后的新一轮规划。本周期=NEEDS 中 C 方向缓存改造，决策见 NEXT_CYCLE_NEEDS.md（4.1~4.12 已拍板，O-5~O-9 二期）。

***

## 一、周期约定（本次继续沿用）

| 编号  | 约定                   | 内容                                                                                                                                                                                                                                                                                 |
| --- | -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| G1  | 一任务一窗口一 commit（默认期望） | 每个任务开独立窗口，默认期望 1 个 commit；因任务内部依赖需拆多 commit 或小任务合并时，在该任务详情标注；commit message 强制带任务编号（本周期 `refactor(cache-0N)`）                                                                                                                                                                                |
| G2  | 开窗协议（输入）             | 新窗口顺序读取：① .docs/INDEX.md → ② NEXT_CYCLE_NEEDS.md（决策节必读，含 4.1~4.12、H1~H11、O-5~O-9）→ ③ 本任务清单当前任务 → ④ 常青文档（CURRENT_ARCHITECTURE.md 六.Redis 设计 / BUSINESS_FLOW.md 3.1 缓存机制等）→ ⑤ 上一个任务 commit                                                                                                                              |
| G3  | 收窗协议（输出）             | ① **跑测试与反馈**：本周期为缓存层重制（对外行为零变化、内部实现全改），以 `mvn compile` + JUnit 为每任务验收，**相关端点 pytest 回归**（测试执行与回传依实际窗口环境安排，不指定角色）；`pytest all` 全量在收尾任务统一跑一次。② 勾选任务清单状态 → ③ 涉及架构/业务改动时同步常青文档 → ④ 提交 |
| G4  | commit 语义闭环          | 代码改动 + 其对应常青文档更新 + 任务清单勾选进同一 commit；message 强制带任务编号                                                                                                                                                                                                                                 |
| G5  | 超范围暂停规则              | 执行中发现需求歧义、或任务实际远超预期 → 停在第一个决策点，回写任务清单（拆/改），不得硬扛、不得擅自扩大范围                                                                                                                                                                                                           |
| G6  | 评审与返工                | 评审以"任务验收标准 + 测试结果"为准；返工记录在任务清单；连续返工 ≥2 次 → 返回周期设计重新评估                                                                                                                                                                                             |
| G7  | 决策唯一源                | NEXT_CYCLE_NEEDS.md 决策节为唯一决策源；窗口内发现新决策 → 回写该节并标记"已定/待定"，不许自行拍板                                                                                                                                                                                            |
| G8  | 分支与合并                | **开分支 / 合并回 integration / 合入 master 均由用户手动执行**；任务窗口只负责本任务的代码、测试与 commit（G1），不自行创建/切换分支、不合并 |
| G9  | DDL 备份               | 任何任务出现表结构改动，执行前必须先备份库结构与建表语句到 .docs/DBbackups/（本周期为缓存层重制，预计无 DDL；若有例外先申请）                                                                                                                                                          |
| G10 | 脚本规范                 | 临时一次性脚本放 temp_script/，长期复用/自动化脚本放 tools/                                                                                                                                                                                                                                          |

> 本周期为**缓存层重制、对外行为零变化**（用户拍板），周期约定在既往周期基础上延续；G3 相比 B 周期补充"相关端点 pytest 回归"（重制风险高于纯搬移）。

***

## 二、任务模板（每任务必含四要素）

> 背景：任务详情采用"入口线索 + 红线边界 + 强制探索 + 验收"四要素结构（沿用既往周期）；执行 Agent 允许动态调整，但**调整前先回写任务清单/需求文档（G5/G7），再动手**。
>
> **红线措辞约定（延续 2026-09-06 用户修订）**：红线不做"一刀切禁止"。若执行中**发现不改本来要守的"红线"就会阻碍后续工作**，不允许硬扛、也不允许擅自开禁——**必须先向用户申请并说明理由，获得批准后才能动手**；未获批准则维持红线。
>
> **编号引用约定（延续 2026-09-06）**：禁裸编号引用已归档周期元素（旧 T1-T9 等）；裸编号仅指本文档内部定义的元素（本表 T1-T6 与 NEEDS 的 H1~H11 / O-5~O-9 / 4.1~4.12）。执行中发现引用歧义 → 回写清单用文字澄清，不得自行猜义。

```markdown
### T1-N 任务标题

* **入口线索**：…
* **红线边界**：（列明哪些不该动；若发现不动会阻碍后续工作 → 先向用户申请并说明理由，批准后方可动手）
* **强制探索步骤**：动刀前先 (1) 检索… (2) 确认… (3) 若清单未覆盖 → 回写本文档再动手
* **验收**：…
```

***

## 三、任务总览（C=缓存改造一版，T1 基建 + T2~T5 四域重制 + T6 收尾 = 6 commit）

| 编号 | 标题 | 对应域/模块 | 依赖 | 验收关键（动态） | 期望 commit 主题 | 状态 |
| -- | -- | -- | -- | -- | -- | --- |
| T1 | 缓存基建骨架：`com.itheima.cache` 基建包（统一 Redis 访问 + 序列化 + key 规范 + 单飞组件 + 三态/空标记/写失败 DEL 封装） | content/基建（新包） | — | 基建类落位 `com.itheima.cache`，`mvn compile` + 新增组件单测绿；业务读路径尚未切换（只增不改） | `refactor(cache-01)` | 已完成（2026-09-12：cache 基建 7 类纯新增，JUnit 新增 36 例，tv.py test junit 240 例全绿） |
| T2 | 内容缓存重制：ContentCacheManager 内容部分拆分为内容缓存类，三态 Cache-Aside + 空标记 + 写失败 DEL | content | T1 | 内容读路径（Start/Search/Detail/Feed/Profile）全部走新缓存；H2 相关 TTL 策略按 4.12 一版（固定 TTL+简单抖动）；`mvn compile` + JUnit + 相关 pytest 绿 | `refactor(cache-02)` | 已完成（2026-09-12：ContentCache 7 方法纯新增 + 调用点全切 + 索引迁 Redis LIST + 计数失效自愈 + H3 修复；tv.py test junit 252 例全绿 + pytest all 124 passed） |
| T3 | 评论缓存重制：评论树独立 TTL + 空标记 + 业务显式失效（内容删除级联删评论 key） | content/comment | T1/T2 | 评论读/写路径走新缓存；评论 miss ≠ 没有评论（三态）；`mvn compile` + JUnit + 相关 pytest 绿 | `refactor(cache-03)` | 已完成（2026-09-12：CommentCache 9 方法新增管理 + 读写路径全切 + 失效式 + 删除/下架级联失效 + H1 评论竞态消除 + H7 启动少一轮 N+1；tv.py test junit 261 例全绿 + pytest all 124 passed） |
| T4 | 点赞缓存重制：LikeCacheService 重写为计数/成员分离 + 单飞 + 写失败 DEL | like | T1 | 点赞读/写路径走新缓存；清除 `__placeholder__` 占位符（H11）；`mvn compile` + JUnit + 相关 pytest 绿 | `refactor(cache-04)` | 已完成（2026-09-12：LikeCacheService 重写为计数/成员分离+三态读+统一单飞+降级，占位符清零，LikeService 读路径委托；tv.py test junit 264 例全绿 + pytest all 124 passed） |
| T5 | 关注关系入缓存：user:following / user:follower 双 Set + MULTI 双写 + 失败双 DEL | follow | T1 | 关注读（isFollowing/列表）与写（关注/取关）路径走新缓存；`mvn compile` + JUnit + 相关 pytest 绿 | `refactor(cache-05)` | 已完成（2026-09-12：FollowCache 双 Set 三态读+条件 MULTI 双写+失败双 DEL+降级新增，读写路径全切，配置 cache.follow.ttlMinutes；tv.py test junit 293 例全绿 + pytest all 124 passed） |
| T6 | 收尾：旧缓存代码残留清理 + pytest all 全量回归 + 常青文档同步 + 覆盖率地图 | — | T1~T5 | 无旧缓存实现残留（ContentCacheManager/LikeCacheService 旧实现移除）；`pytest all` 全绿；CURRENT_ARCHITECTURE.md（六.Redis 设计）/ BUSINESS_FLOW.md（3.1 缓存机制）同步；覆盖率地图 rerun 无回归 | `refactor(cache-06)` | 待执行 |

> 状态取值：草稿 / 待执行 / 执行中 / 已完成 / 搁置。
>
> **重制顺序理由（依赖方向）**：T1 基建先行（统一 Redis 访问/序列化/单飞/三态/降级是全部重制的地基，纯新增零业务改动最安全）；T2 内容 → T3 评论 → T4 点赞 → T5 关注按"读路径切换"递进（评论树构建原在 ContentCacheManager，随 T3 迁出；点赞/关注相互独立可并行窗口）；T6 收尾清残留 + 全量回归。
>
> **二期已延期**：O-5~O-9（初始化选择性加载 / 定时刷新去留 / 搜索入缓存 / TTL 滑动续期 / 关注粉丝计数）均为二期迭代，本清单不拆任务。

***

## 四、任务详情

> **共通注（所有重制任务通用）**：
> - 遵循 NEEDS 4.1~4.13：统一 Redis；三态 Cache-Aside（miss/hit-empty/hit-data）；空标记独立 key + 短 TTL；**写失败=失效（DEL）+ 读自愈**；统一单飞组件（ConcurrentHashMap+FutureTask，失败必须 remove）；**基建归属 4.13**——技术无关组件（统一 Redis 访问/序列化/key 规范/单飞/三态/空标记/写失败 DEL 封装）新建于 `com.itheima.cache` 基建包，业务缓存类放各自业务域（内容/评论→content、点赞→like、关注→follow）；
> - **不引入 MQ**；Redis 写失败不阻塞主流程（4.2/4.7）；
> - key 命名遵循统一规范（`content:{id}`、`content:comments:{id}`、`empty:...`、`user:following:{id}`、`content:likeCount:{id}` 等，T1 在 `com.itheima.cache` 定稿）；
> - 序列化统一（JSON 字符串存 value，T1 定稿）；
> - 单飞只对单实例有效（当前单 Tomcat 够用，不过度设计，4.9）；
> - **本清单四要素为骨架，执行方案由执行 Agent 探索细化**（G5 允许回写）。

### T1 缓存基建骨架（方向 C）

* **入口线索**：新建 `com.itheima.cache` 基建包（4.13 已拍板，不再"执行时定"）：① 统一 Redis 访问封装（基于 [MyRedisPool.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/util/MyRedisPool.java)）；② JSON 序列化工具；③ 统一 key 命名/生成规范；④ 单飞组件（4.9 ConcurrentHashMap+FutureTask，失败 remove）；⑤ 三态读取 + 空标记写入封装（4.3/4.4，空标记独立 key + 短 TTL）；⑥ 写失败=DEL 降级封装（4.2）。
* **红线边界**：不改任何业务读路径（本任务**只增不改**）；不改 MyRedisPool 现有对外方法签名（他处仍在用）；不引入 Spring/MyBatis。**若发现不动红线就阻碍基建（如需要改 MyRedisPool 才能封装）→ 先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) 读 [ContentCacheManager.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ContentCacheManager.java) 与 [LikeCacheService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/like/service/LikeCacheService.java) 摸清现有 Redis/序列化用法；(2) 确认 MyRedisPool 线程安全用法（getJedis try-with-resources）；(3) 若 key/TTL 规范需新增决策 → 回写 NEEDS 再动手。
* **验收**：基建类全部落位；`mvn compile` 通过；新增基建组件有 JUnit 单测覆盖（单飞成功/失败 remove、三态读、空标记 TTL、DEL 降级）；现有测试全绿（本任务无业务切换）。

### T2 内容缓存重制（方向 C）

* **入口线索**：[ContentCacheManager.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ContentCacheManager.java)（666 行 god class，本任务拆出**内容缓存**职责：contentCache/contentTimestamps/类型分区索引/recommendList → 迁入新内容缓存类，用 T1 基建实现三态 + 空标记 + 写失败 DEL）；切换内容读路径：[StartController.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/controller/StartController.java)、[ContentService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ContentService.java)（search/getContentDetailVO）、[FeedService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/FeedService.java)、[ProfileService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ProfileService.java)。**执行方案已细化（见下"T2 执行回写"，2026-09-12 完成）**。
* **红线边界**：不改内容业务逻辑与 `@WebServlet` URL；评论缓存（commentCache/评论树）本任务**不动**（T3 处理）；不定时刷新（O-6 二期）；不引入滑动续期（O-8 二期，一版固定 TTL+简单抖动）。**若发现不动上述红线就阻碍重制 → 先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) `rg 'getContentFromCache|backfillContent|evictContent|getRecommendByFilter|updateCacheAfterAdd|refreshContent|removeContent' src/` 全量列出调用方；(2) 确认定时刷新 `startScheduler` 的去留对一版的影响（先保留 or 先停，执行时评估，倾向保留至 T6 决定）；(3) 若 T2 范围需碰评论 → 停决策点回写。
* **验收**：内容读路径全部走新缓存实现；旧 ContentCacheManager 中内容相关方法调用清零（评论部分残留可暂留）；`mvn compile` + JUnit 全绿 + 内容相关 pytest（/start /search /content/detail /feed /profile）回归通过。

> **T2 执行回写（2026-09-12，G5/G7）**：① 新增 `com.itheima.content.service.ContentCache`（400 行），内容详情走 T1 CacheAside 三态/空标记/单飞/写失败 DEL，类型分区索引迁为 Redis LIST `content:index:{t}:{c}`（NEEDS 4.1 方案拍板，见 NEEDS 0.4），recommendList 死代码废弃不迁移；② 计数（点赞/评论数/评论区开关）变更 = 失效 content key 读自愈（DB 列源真理），内容新增写入移出 DB 事务（H3）；③ **验收"调用清零"实现口径**：内容读方法（getContentFromCache/getRecommendByFilter/toContentVO/toDetailVO 等）全清零；写方法按"遗产副作用"保留并标注 T3/T4 清理——`updateCacheAfterAdd` 保留为评论树空列表种子（因需 Connection 仍在事务内，仅内存副作用、对读路径无影响）、`removeContent`/`refreshContent` 保留用于删/下架清内存评论树与 unhide 恢复评论树；④ 测试：新增 ContentCacheTest 12 例，ContentService/Feed/Profile/Like/Comment 五测试改造随新调用点（JUnit 252 全绿、pytest all 124 passed）。

### T3 评论缓存重制（方向 C）

* **入口线索**：将 [ContentCacheManager.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ContentCacheManager.java) 评论部分（commentCache/评论树构建/collectCommentIds/addCommentToCache/removeCommentFromCache/updateCommentLikeCount 等）迁入评论缓存类；用 T1 基建实现**独立 TTL + 空标记 + 业务显式失效**（4.5：内容删除级联删评论 key）；切换评论读/写路径：[CommentService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/comment/service/CommentService.java)（addComment/删除）、[ContentService.getCommentsForContent](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ContentService.java#L111-L130)。
* **红线边界**：不改评论业务逻辑（楼中楼归一化/软删规则/开关门禁）；评论 miss 语义按三态实现（hit-empty 返回空，miss 查 DB 回填，**不得把 miss 当"没有评论"**）；不碰点赞缓存（T4）。**若发现不动上述红线就阻碍重制 → 先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) `rg 'getCommentTree|addCommentToCache|removeCommentFromCache|updateCommentLikeCount|loadCommentTree|buildCommentTree' src/` 列出全部调用方；(2) 确认"读评论前先验证 content 存在"的落点（4.5）；(3) 确认删除内容时评论 key 级联失效的调用点（ContentService.deleteContent / removeContent）。
* **验收**：评论读/写路径走新缓存；三态正确（无评论=hit-empty 空标记，有评论=hit-data，未加载=miss 回填）；删除内容级联失效验证；`mvn compile` + JUnit 全绿 + 评论相关 pytest（/comment/show /content/detail 评论区）回归通过。

> **T3 执行回写（2026-09-12，G5/G7）**：① 新增 `com.itheima.content.service.CommentCache`（173 行），评论树走 T1 CacheAside 三态/空标记 60s/**独立 TTL**（新增配置 `cache.comment.ttlMinutes=10`）/单飞/写失败 DEL，key 用既有 `CacheKeys.contentComments`；② **评论增/删/点赞 = 失效评论树 key 读自愈**（不做旧内存树原地增删，消除 H1 并发竞态；buildCommentTree 归一化逻辑原样迁入，loader 无评论返回 null → markEmpty 写空标记 → hit-empty）；评论点赞经 `CommentDao.getContentIdByCommentId`（新增轻查询）定位所属内容后失效（4.1.2）；③ 切读写路径：ContentService.getCommentsForContent → commentCache（并新增 **dto==null 短路**=读评论前先确认 content 存在，防隐藏内容评论回填泄漏，4.5）；CommentService addComment/deleteComment、LikeService 评论点赞 → 失效式；④ 级联失效：deleteContent/hideContent 追加 `commentCache.invalidateComments`；unhide 无需额外动作；⑤ ContentCacheManager 删除评论字段与全部评论方法（getCommentTree/collectCommentIds/loadCommentTree/buildCommentTree/addCommentToCache/removeCommentFromCache/updateCommentLikeCount/updateCacheAfterAdd 等），init 不再全量加载评论（缓解 H7 启动 N+1）；content 内存残留（contentCache/索引/推荐列表/定时刷新）与 removeContent/refreshContent（旧点赞 key 清理）按计划保留至 T4/T6；CommentService/LikeService 构造移除 ContentCacheManager 依赖；⑥ 测试：新增 CommentCacheTest 11 例 + ContentServiceTest 补 dto==null 短路用例，ContentCacheManagerLifecycleTest 评论相关 3 例迁出（9→6），tv.py test junit 261 例全绿 + pytest all 124 passed。

### T4 点赞缓存重制（方向 C）

* **入口线索**：重写 [LikeCacheService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/like/service/LikeCacheService.java) 为**计数/成员分离**（4.6：`content:likeCount:{id}` int 高频读 + `content:likeSet:{id}` set 低频成员查询）+ 单飞（4.9）+ 写失败 DEL（4.2）；**清除 `__placeholder__` hack（H11）**；切换读写路径：[LikeService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/like/service/LikeService.java)、[ContentStatusFiller.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ContentStatusFiller.java)（批量 isLiked）、ContentCacheManager.updateContentLikeCount（若 T2 已迁，改指向新点赞缓存）。
* **红线边界**：不改点赞/取消/重复校验业务逻辑；Redis 写失败不得让点赞接口 500（4.2，H5 修复）；不改评论点赞的计数归属（评论点赞计数与内容计数分开）。**若发现不动上述红线就阻碍重制 → 先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) `rg 'LikeCacheService|likeContent|isContentLiked|getContentLikeCount|batchIsContentLiked|syncContentLikers|updateContentLikeCount' src/` 列出全部调用方；(2) 确认点赞数在 ContentCacheDTO/ContentVO 中的读取点（缓存里存的是计数还是集合）；(3) 确认 deleteContentLike 的级联删除调用点（内容删除时清点赞 key）。
* **验收**：点赞读/写路径走新缓存（计数/成员分离）；`__placeholder__` 清除且无残留；Redis 挂时点赞接口不 500（降级验证）；`mvn compile` + JUnit 全绿 + 点赞相关 pytest（/like/* 内容/评论点赞、内容详情 isLiked）回归通过。

> **T4 执行回写（2026-09-12，G5/G7）**：① 重写 `com.itheima.like.service.LikeCacheService`（558 行）为**计数/成员分离**：`content:likeCount:{id}`（String int，走 T1 CacheAside，loader=新增 DAO `countByContentId/countByCommentId` COUNT 查询，避免为计数加载全量成员）+ `content:likeSet:{id}`（原生 Set，RedisAccess+SingleFlight 三态读：空标记→false / set 存在→SISMEMBER / miss→单飞回填 SADD-union+EXPIRE，空集→`markEmpty` 空标记替代失效的 `__placeholder__`（H11））；评论同理（comment:likeCount/comment:likeSet）；② **单飞拉满（H6）**：回填点（单条+批量）均经统一 SingleFlight 包住 DB 装载，key=set 数据 key；③ **写路径=条件写 + 失败失效（4.2/4.6）**：like=清 `empty:` 标记 + 仅当 key 存在才 INCR/SADD（冷 key 不创建残缺缓存，防"半套成员/计数"被命中，交给读回填 DB 真理）、unlike=仅当存在才 DECR/SREM；Redis 异常→`cacheAside.invalidate(count,set)` 双失效 + 记日志，**点赞接口不 500（H5）**；④ 批量 = pipeline（exists empty + exists set + sismember 一趟）→ DB `findLikedContentIds` 兜底 + 逐个单飞回填；⑤ **LikeService 读方法（isContentLiked/getContentLikeCount/isCommentLiked/getCommentLikeCount/batch×2）改纯委托**，回填/降级逻辑内聚缓存类（与 T2 ContentCache/T3 CommentCache 同构），写方法与事务边界零改动；`syncContentLikers/syncCommentLikers` 删除（H11 载体清零）；⑥ ContentCacheManager 删死代码 `updateContentLikeCount`（无生产调用者），`evictContent→deleteContentLike` 保留（新实现=失效 count+set+empty 三 key）；新增配置 `cache.like.ttlMinutes=10`（AppConfig.getLikeTtlSeconds）；⑦ 测试：LikeCacheServiceTest 重写 20 例（三态/空标记/单飞回填空集与非空/写条件/写失败失效/降级/批量/delete），LikeServiceTest 读路径改委托断言（14→16），ContentCacheManagerLifecycleTest 删 1 例（6→5）；tv.py test junit 264 例全绿 + pytest all 124 passed；常青文档同步（CURRENT_ARCHITECTURE 6.2/9.2/10.1 + BUSINESS_FLOW 4.1.1）+ 任务清单 T4 勾选；subagent review 无必修项（建议项：防御性分支仅兜底 NPE 已加注释说明）。

### T5 关注关系入缓存（方向 C）

* **入口线索**：新增关注缓存类（NEEDS 4.10：`user:following:{userId}` / `user:follower:{userId}` 双 Set，MULTI 双写，失败双 DEL）；切换读写路径：[FollowDao.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/follow/dao/FollowDao.java) 的读取方（[FollowService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/follow/service/FollowService.java) 关注/取关、[ContentStatusFiller.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ContentStatusFiller.java) isFollowing 批量、[FeedService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/FeedService.java) 关注列表、[ProfileService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ProfileService.java) 粉丝/关注数暂不动 O-9）。
* **红线边界**：不改关注/取关业务逻辑；只缓存"关系"本身，**不做 feed 聚合缓存**（D 方向边界）；关注数/粉丝数计数暂不入缓存（O-9 二期）；失败降级按"写失败双 DEL"（4.10）。**若发现不动上述红线就阻碍重制 → 先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) `rg 'FollowDao|getFollowedIds|getAllFollowedUserIds|isFollowing|addFollow|deleteFollow|getFollowerUserIds' src/` 列出全部调用方；(2) 确认关注/取关的事务边界（缓存双写放在 DB 提交后）；(3) 确认 MULTI 事务在单连接上如何与现有事务模板配合（不跨连接）。
* **验收**：关注关系读/写路径走新缓存（双 Set + MULTI + 失败双 DEL）；关注/取关后关系即时生效；`mvn compile` + JUnit 全绿 + 关注相关 pytest（/follow/*、内容卡片 isFollowed）回归通过。

> **T5 执行回写（2026-09-12，G5/G7）**：① 新增 `com.itheima.follow.service.FollowCache`（484 行）：`user:following`/`user:follower` 双 Set；读路径四方法（isFollowing 单条三态 / batchIsFollowing 同一 set 一趟 pipeline + DB getFollowedIds 兜底 + best-effort 单飞回填 / getFollowingIds / getFollowerIds SMEMBERS·空标记·miss 回填），列表读统一升序（miss/降级与 SMEMBERS 命中路径一致）；写路径 cacheFollow/cacheUnfollow = **条件 MULTI 双写**（两 data key 均"已加载"（set 存在或空标记存在）→ MULTI 原子 SADD/SREM 双写 + EXPIRE + 解除空标记；任一侧冷 key 或空标记命中 → 整对双 DEL（含空标记）失效让读自愈，**不创建冷 key 残缺集**，与 T4 条件写先例同构）；Redis 异常 → 双 DEL，全程不抛出（4.10/4.2）；② 空标记回填带 **set 存在守卫**（`writeSet` 空分支先 `exists` 再 setex，防并发下覆盖刚 SADD 的新关注 → 新关系最长 60s 不可见，subagent review 必修②）；③ 新增配置 `cache.follow.ttlMinutes=10` + `AppConfig.getFollowTtlSeconds`；④ 切读写路径：FollowService 关注/取关 DB 事务提交后调 cacheFollow/cacheUnfollow，getFollowingList/getFollowerList 委托 getFollowingIds/getFollowerIds，列表 isFollowed 走 batchIsFollowing（**事务内 isFollowing 前置校验保留 DB 直读**，业务校验不动）；ContentStatusFiller fillFollowStatus（批量/单条）走 batchIsFollowing/isFollowing；FeedService.getFeed 关注列表走 getFollowingIds（事务外）；ProfileService isFollowed 走 isFollowing（事务外，followCount/followerCount 不动 O-9）；⑤ FollowDao 仅剩 FollowService 业务校验 + FollowCache loader 使用（rg 核验无残留）；⑥ 测试：新增 FollowCacheTest 26 例（三态/空标记守卫/批量/列表排序/条件 MULTI/失败双 DEL/降级/回填 best-effort），FollowServiceTest 缓存联动断言改造（15 例），FeedServiceTest/ProfileServiceTest 构造与读断言随调用点（ProfileServiceTest 12 例，删 follow SQL 残留 1 例）；tv.py test junit 293 例全绿 + pytest all 124 passed；⑦ 常青文档同步：CURRENT_ARCHITECTURE（follow 域表 / 六.6.2 启用列表 / 九.9.2 / 十.10.1/10.2）+ BUSINESS_FLOW（3.1 关注缓存段 + 4.3.1 步骤 7 缓存双写）；subagent review 无必修残留（必修①②已修，建议项 ③ 同对并发写序竞态=边缘定义记录不修）。

### T6 收尾（方向 C）

* **入口线索**：全仓库巡检 + 回归验证 + 文档同步。基于 T1~T5 完成态。
* **红线边界**：不引入任何新功能改动；只做残留清理、验证与文档。**若发现必须改缺陷才能满足验收（如旧缓存实现残留必须补代码才能清零）→ 先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）**；发现"要改但未排期"的内容 → **登记入 `目标与任务/UNPLANNED_ISSUES.md`**，不在本周期硬做。
* **强制探索步骤**：(1) `rg 'ContentCacheManager|LikeCacheService|contentCache|commentCache|recommendList|typeCategoryIndex|__placeholder__' src/` 确认无旧缓存实现残留；(2) 校验 @WebServlet URL / web.xml / IoC 扫描原样；(3) 确认 `startScheduler` 定时刷新去留最终决定（O-6 二期 or 一版保留，记录到 NEEDS）。
* **验收**：无旧缓存实现残留；`pytest all` 全量回归全绿；CURRENT_ARCHITECTURE.md（六.Redis 设计）与 BUSINESS_FLOW.md（3.1 缓存机制）同步；覆盖率地图 rerun 无回归；NEEDS 中已拍板决策与实现一致。

***

## 五、变更记录

| 日期         | 版本  | 内容                                                                                                   |
| ---------- | --- | ---------------------------------------------------------------------------------------------------- |
| 2026-09-12 | 0.1 | 新建本文档（骨架草稿）：结转周期约定 G1-G10 与任务模板四要素/红线措辞/编号引用约定（G3 补充"相关端点 pytest 回归"）；按 NEEDS 4.12 一版方案拆 **6 任务 = T1 基建 + T2 内容 + T3 评论 + T4 点赞 + T5 关注 + T6 收尾**；总览表含依赖与验收关键；任务详情为**四要素骨架**，执行方案由执行 Agent 探索细化；O-5~O-9 明确归二期不拆任务 |
| 2026-09-12 | 0.2 | 落实 NEEDS 4.13 基建归属：T1 落点明确为新建 `com.itheima.cache` 基建包（原"归属执行时定"改为已拍板）；共通注补充 4.13 分工边界（基建=cache 包、业务缓存类=各自业务域） |
| 2026-09-12 | 0.3 | **T3 评论缓存重制完成**：T3 状态置"已完成"，详情追加 T3 执行回写（失效式代替原地增删、独立 TTL 配置、dto==null 短路、级联失效、ContentCacheManager 清理口径） |
| 2026-09-12 | 0.4 | **T4 点赞缓存重制完成**：T4 状态置"已完成"，详情追加 T4 执行回写（计数/成员分离、统一单飞拉满 H6、条件写+失败失效 H5、占位符清零 H11、LikeService 读路径委托、删 updateContentLikeCount 死代码、like TTL 配置） |
| 2026-09-12 | 0.5 | **T5 关注关系入缓存完成**：T5 状态置"已完成"，详情追加 T5 执行回写（FollowCache 双 Set 三态读+条件 MULTI 双写+失败双 DEL、空标记 set 存在守卫、follow TTL 配置、读写路径全切、FollowDao 仅剩业务校验与 loader、JUnit 293 + pytest 124、常青同步、subagent review 必修已修） |
