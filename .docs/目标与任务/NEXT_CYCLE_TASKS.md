# 下一周期任务清单

> 关联文档：目标与任务/NEXT_CYCLE_NEEDS.md（决策唯一源；此文件为执行细节）
> 状态：**任务拆分已定稿** —— 本周期仅做 B（feature package），拆 9 任务（8 域迁移 + 1 收尾，默认期望 9 commit）；每任务四要素详情已填写，执行前仍可微调（G5）。
> 工作流：每个任务开独立窗口执行；"任务清单 + 需求与痛点"为窗口间唯一交接载体。
> 来源：260910-test-gap-fill 周期（A 方向 T1~T8 全部完成）归档后的新一轮规划，见 archive/目标与任务/260910-test-gap-fill/（勿读）。B/C/D 顺序已定：B 先于 C/D（缓存拆类避免二次搬家），本周期仅做 B。

***

## 一、周期约定（本次继续沿用）

| 编号  | 约定                   | 内容                                                                                                                                                                                                                                                                                 |
| --- | -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| G1  | 一任务一窗口一 commit（默认期望） | 每个任务开独立窗口，默认期望 1 个 commit；因任务内部依赖需拆多 commit 或小任务合并时，在该任务详情标注；commit message 强制带任务编号                                                                                                                                                                                |
| G2  | 开窗协议（输入）             | 新窗口顺序读取：① .docs/INDEX.md → ② 需求与痛点.md 相关章节（决策节必读，含结转 C-1\~C-4）→ ③ 任务清单.md 当前任务 → ④ 常青文档（涉及架构/业务时）→ ⑤ 上一个任务 commit                                                                                                                              |
| G3  | 收窗协议（输出）             | ① **跑测试与反馈**：本周期为纯机械搬移，以 `mvn compile` + JUnit 为每任务验收（测试执行与回传依实际窗口环境安排，不指定角色）；pytest（`all`）只在收尾任务统一跑一次。② 勾选任务清单状态 → ③ 涉及架构/业务改动时同步常青文档 → ④ 提交 |
| G4  | commit 语义闭环          | 代码改动 + 其对应常青文档更新 + 任务清单勾选进同一 commit；message 强制带任务编号                                                                                                                                                                                                                                 |
| G5  | 超范围暂停规则              | 执行中发现需求歧义、或任务实际远超预期 → 停在第一个决策点，回写任务清单（拆/改），不得硬扛、不得擅自扩大范围                                                                                                                                                                                                           |
| G6  | 评审与返工                | 评审以"任务验收标准 + 测试结果"为准；返工记录在任务清单；连续返工 ≥2 次 → 返回周期设计重新评估                                                                                                                                                                                             |
| G7  | 决策唯一源                | 需求与痛点.md 决策节为唯一决策源；窗口内发现新决策 → 回写该节并标记"已定/待定"，不许自行拍板                                                                                                                                                                                            |
| G8  | 分支与合并                | **开分支 / 合并回 integration / 合入 master 均由用户手动执行**；任务窗口只负责本任务的代码、测试与 commit（G1），不自行创建/切换分支、不合并 |
| G9  | DDL 备份               | 任何任务出现表结构改动，执行前必须先备份库结构与建表语句到 .docs/DBbackups/（本周期纯搬移，预计无 DDL；若有例外先申请）                                                                                                                                                          |
| G10 | 脚本规范                 | 临时一次性脚本放 temp_script/，长期复用/自动化脚本放 tools/                                                                                                                                                                                                                                          |

> 本周期为**纯代码搬移、行为零变化**（用户拍板），周期约定在既往周期基础上延续，删去了原 G3 中"指定某外部角色执行测试"的表述（该角色本周期不参与）。

***

## 二、任务模板（每任务必含四要素）

> 背景：任务详情采用"入口线索 + 红线边界 + 强制探索 + 验收"四要素结构（沿用上周期）；执行 Agent 允许动态调整，但**调整前先回写任务清单/需求文档（G5/G7），再动手**。
>
> **红线措辞约定（延续 2026-09-06 用户修订）**：红线不做"一刀切禁止"。若执行中**发现不改本来要守的"红线"就会阻碍后续工作**，不允许硬扛、也不允许擅自开禁——**必须先向用户申请并说明理由，获得批准后才能动手**；未获批准则维持红线。
>
> **编号引用约定（延续 2026-09-06）**：禁裸编号引用已归档周期元素（旧 T1-T8/N1-N6 等）；裸编号仅指本文档内部定义的元素（本表 T1-T9 与需求文档的 A~D/C-1~C-4/P5/P6/B/C/D）。执行中发现引用歧义 → 回写清单用文字澄清，不得自行猜义。

```markdown
### T1-N 任务标题

* **入口线索**：…
* **红线边界**：（列明哪些不该动；若发现不动会阻碍后续工作 → 先向用户申请并说明理由，批准后方可动手）
* **强制探索步骤**：动刀前先 (1) 检索… (2) 确认… (3) 若清单未覆盖 → 回写本文档再动手
* **验收**：…
```

***

## 三、任务总览（B=feature package，8 域迁移 + 1 收尾 = 9 commit）

| 编号 | 标题 | 对应域 | 依赖 | 验收关键（动态） | 期望 commit 主题 | 状态 |
| -- | -- | -- | -- | -- | -- | --- |
| T1 | 迁移 coupon 域（/coupon/\*） | coupon | — | 全套类落位 `com.itheima.coupon`，旧包无残留，`mvn compile` + JUnit 绿 | `refactor(pkg-01)` | 已完成 |
| T2 | 迁移 upload 域（/api/upload/\*） | upload | — | 全套类落位 `com.itheima.upload`，`mvn compile` + JUnit 绿 | `refactor(pkg-02)` | 已完成 |
| T3 | 迁移 user 域（/user/\*） | user | — | 全套类落位 `com.itheima.user`，测试随迁，`mvn compile` + JUnit 绿 | `refactor(pkg-03)` | 待执行 |
| T4 | 迁移 comment 域（/comment/\*） | comment | — | 全套类落位 `com.itheima.comment`，测试随迁，`mvn compile` + JUnit 绿 | `refactor(pkg-04)` | 待执行 |
| T5 | 迁移 follow 域（/follow/\*） | follow | — | 全套类落位 `com.itheima.follow`，测试随迁，`mvn compile` + JUnit 绿 | `refactor(pkg-05)` | 待执行 |
| T6 | 迁移 like 域（/like/\*） | like | — | 全套类落位 `com.itheima.like`，测试随迁，`mvn compile` + JUnit 绿 | `refactor(pkg-06)` | 待执行 |
| T7 | 迁移 content 域（/content、/start、/search、/feed、/profile + 共享缓存组件） | content | T5/T6 | 全套类落位 `com.itheima.content`，**收口全部指向旧 model/service 的引用**，测试随迁，`mvn compile` + JUnit 绿；体量过大可拆 2 commit（见详情） | `refactor(pkg-07)` | 待执行 |
| T8 | 迁移 admin 域（/api/admin/\*） | admin | T7 | 全套类落位 `com.itheima.admin`，测试随迁，`mvn compile` + JUnit 绿 | `refactor(pkg-08)` | 待执行 |
| T9 | 收尾：旧引用巡检 + pytest all + 常青文档 + 覆盖率地图 | — | T1~T8 | 全仓库无 `com.itheima.(service\|dao\|controller\|model)` 旧引用残留；pytest（`all`）全绿；常青文档已同步；覆盖率地图 rerun 无回归 | `refactor(pkg-09)` | 待执行 |

> 状态取值：草稿 / 待执行 / 执行中 / 已完成 / 搁置。
>
> **迁移顺序理由（依赖方向）**：coupon/upload 自包含零反向依赖，先动最安全；comment 的 CommentCacheDTO 依赖仍指向旧 model 路径（content 未搬前有效）；follow/like 的 DAO 被 content 域服务依赖 → 先于 content；content 最大且承载读接口与共享组件、并收口所有旧 `com.itheima.model.*` 归属它的类 → 倒数第二；admin 依赖 content/comment 的 DAO → 最后。inter（content）为最大单域，允许拆 2 个 commit（G1 例外，须在详情标注）。
>
> **C/D 已延期**：本周期仅做 B（2026-09-10 用户评审决定）；C 缓存改造 / D feed 流为后续周期方向，本清单暂不拆任务。

***

## 四、任务详情

> **共通注（所有迁移任务通用）**：
> - 每个搬移类：改 `package` 声明 + 同步更新**所有引用该类的文件**（主代码 + `src/test/java` + `tools/`）的 import；
> - **不改**类名、`@WebServlet` URL、方法签名与行为、任何业务逻辑；基建包（ioc/filter/util/exception/config）零改动；
> - `src/main/webapp/WEB-INF/web.xml` 零改动（只注册 filter，无 Servlet 类名引用）；
> - JUnit 与被测类**同包跟随**：对应 `src/test/java/com/itheima/service/XxxTest.java` 同步迁移 package 声明；
> - 不共用代码 → 直接改文件，不做新增 shim/兼容类；
> - 完成标识 = 该域类全部落位新包 + 旧包不留该域类 + `mvn compile` 通过。

### T1 迁移 coupon 域（方向 B）

* **入口线索**：[CouponController.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/controller/CouponController.java)（/coupon/\*）、[CouponService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/service/CouponService.java)、[CouponDao.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/dao/CouponDao.java)、[GrabCouponRequest.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/model/dto/GrabCouponRequest.java) → 目标 `com.itheima.coupon`（controller/service/dao/model/dto）。
* **红线边界**：不改抢券/列表业务逻辑与 URL；不引入对基建包的反向依赖；CouponServiceTest 同包随迁。**若发现不动上述红线项就会阻碍迁移（如不动业务逻辑无法让编译/测试通过）→ 不得硬扛、也不得擅自开禁：先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) `rg 'CouponController|CouponService|CouponDao|GrabCouponRequest' src/` 全量找出引用方；(2) 确认 test/CouponServiceTest 与 tools/CouponAdmin 的 import 同步；(3) 若无其它域引用（本域自包含）→ 直接搬。
* **验收**：4 类全部落位 `com.itheima.coupon`；旧包无残留；全仓库 import 指向新包；`mvn compile` 通过与 JUnit 绿。

### T2 迁移 upload 域（方向 B）

* **入口线索**：[UploadController.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/controller/UploadController.java)（/api/upload/\*）、[UploadType.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/controller/UploadType.java)、[FileUploadService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/service/FileUploadService.java)、[UploadCommand.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/model/command/UploadCommand.java)、[UploadResult.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/model/vo/UploadResult.java) → 目标 `com.itheima.upload`。
* **红线边界**：不改上传校验/落盘/换源逻辑与 URL；ContentService import UploadCommand 需同步改（content 域未搬前 import 指向新 upload 包即可）；FileUploadServiceTest 同包随迁。**若发现不动上述红线项就会阻碍迁移（如不动上传逻辑无法让编译/测试通过）→ 不得硬扛、也不得擅自开禁：先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) `rg 'UploadController|UploadType|FileUploadService|UploadCommand|UploadResult' src/` 列出引用方（重点 ContentService）；(2) 确认 test/FileUploadServiceTest 随迁；(3) 确认无隐藏硬编码类路径字符串（`Class.forName` 等）。
* **验收**：5 类落位 `com.itheima.upload`；ContentService 等跨域 import 同步；`mvn compile` 通过与 JUnit 绿。

### T3 迁移 user 域（方向 B）

* **入口线索**：[LoginController.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/controller/LoginController.java)（/user/\*）、[UserService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/service/UserService.java)、[UserDao.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/dao/UserDao.java)、model/entity/User、model/dto/LoginDTO、RegisterDTO、ChangePasswordDTO、model/command/LoginCommand、RegisterCommand、ChangePasswordCommand、LoginType、model/vo/LoginVO → 目标 `com.itheima.user`。
* **红线边界**：不改认证/改密逻辑与 URL；**User 实体被多模块 import（FollowDao/ProfileService 等）→ 全量同步**；UserServiceTest 同包随迁；UserService 的 `import com.itheima.util.*;` 通配 import 不动（util 基建未动）。**若发现不动上述红线项就会阻碍迁移（如不动认证流程无法让编译/测试通过）→ 不得硬扛、也不得擅自开禁：先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) `rg 'com\.itheima\.model\.entity\.User|LoginController|UserService|UserDao|LoginDTO|LoginVO|LoginCommand|LoginType' src/` 列出全部引用方（预计 userId 上下文贯穿多域）；(2) 确认 tools/CouponAdmin 若引用 User 则同步。
* **验收**：9 类落位 `com.itheima.user`；全仓库 import 同步；`mvn compile` 通过与 JUnit 绿。

### T4 迁移 comment 域（方向 B）

* **入口线索**：[CommentController.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/controller/CommentController.java)（/comment/\*）、[CommentService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/service/CommentService.java)、[CommentDao.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/dao/CommentDao.java)、model/dto/CommentDTO、model/command/CommentCommand → 目标 `com.itheima.comment`。
* **红线边界**：不改楼中楼/软删逻辑与 URL；CommentService 依赖 content 的 CommentCacheDTO/ContentCacheDTO → **content 域未搬，暂走旧 `com.itheima.model.cache` 路径，T7 再收口**；CommentVO 归 content 域（共享组件）→ 不随本域搬；CommentServiceTest 同包随迁。**若发现不动上述红线项就会阻碍迁移（如不改评论逻辑无法让编译/测试通过）→ 不得硬扛、也不得擅自开禁：先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) `rg 'CommentController|CommentService|CommentDao|CommentDTO|CommentCommand' src/` 列出引用方（ContentService/AdminCommentController 等）；(2) 确认 CommentVO 保持不动只会影响 import 方向，无遗漏。
* **验收**：5 类落位 `com.itheima.comment`；跨域 import 同步；`mvn compile` 通过与 JUnit 绿。

### T5 迁移 follow 域（方向 B）

* **入口线索**：[FollowController.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/controller/FollowController.java)（/follow/\*）、[FollowService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/service/FollowService.java)、[FollowDao.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/dao/FollowDao.java)、model/entity/User（关注列表条目含用户名）→ 目标 `com.itheima.follow`。
* **红线边界**：不改关注/取关/列表逻辑与 URL；FollowServiceTest 同包随迁；FeedService/ProfileService/ContentStatusFiller 依赖 FollowDao → 跨域 import 指向新包（它们自身未搬时照常 import）。**若发现不动上述红线项就会阻碍迁移（如不改关注逻辑无法让编译/测试通过）→ 不得硬扛、也不得擅自开禁：先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) `rg 'FollowController|FollowService|FollowDao' src/` 列出引用方（content 域服务居多）；(2) 确认 FollowDao 移入 follow 域后，content 域服务 import 同步为 `com.itheima.follow.FollowDao`。
* **验收**：3 类落位 `com.itheima.follow`；依赖方 import 同步；`mvn compile` 通过与 JUnit 绿。

### T6 迁移 like 域（方向 B）

* **入口线索**：[LikeController.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/controller/LikeController.java)（/like/\*）、[LikeService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/service/LikeService.java)、[LikeCacheService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/service/LikeCacheService.java)、[ContentLikeDao.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/dao/ContentLikeDao.java)、[CommentLikeDao.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/dao/CommentLikeDao.java) → 目标 `com.itheima.like`。
* **红线边界**：不改点赞/缓存逻辑与 URL；ContentService 删除作品级联清赞 import ContentLikeDao → 跨域同步；LikeServiceTest/LikeCacheServiceTest 同包随迁。**若发现不动上述红线项就会阻碍迁移（如不改点赞/缓存逻辑无法让编译/测试通过）→ 不得硬扛、也不得擅自开禁：先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) `rg 'LikeController|LikeService|LikeCacheService|ContentLikeDao|CommentLikeDao' src/` 列出引用方；(2) 确认 LikeCacheService 无注入构造、被 ContentCacheManager 依赖 → import 同步。
* **验收**：5 类落位 `com.itheima.like`；`mvn compile` 通过与 JUnit 绿。

### T7 迁移 content 域（方向 B，最大域）

* **入口线索**：[ContentController.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/controller/ContentController.java)（/content/\*）、StartController（/start）、SearchController（/search）、FeedController（/feed）、ProfileController（/profile）；ContentService、ContentCacheManager、ContentStatusFiller、FeedService、ProfileService；ContentDao、ContentMediaDao、ContentLikeDao；entity/ContentMedia、cache/ContentCacheDTO、CommentCacheDTO、vo/ContentVO、ContentDetailVO、CommentVO、dto/PageResult、SearchDTO、command/CommandConverter、ContentType → 目标 `com.itheima.content`。
* **红线边界**：不改内容/搜索/feed/主页逻辑与 URL；本域**收口所有指向旧 `com.itheima.model.*`（归属本域的类）与旧 service 路径的引用**——搬移 ContentCacheDTO/CommentCacheDTO/PageResult/CommandConverter 等共享类时，全部引用方（含已搬走的 user/comment/like/upload/admin 域）import 一并改为 `com.itheima.content.*`；ContentServiceTest/CommentService 相关测试与 ContentCacheManagerLifecycleTest/FeedServiceTest/ProfileServiceTest 同包随迁。**若发现不动上述红线项就会阻碍迁移（如不改内容逻辑无法让编译/测试通过）→ 不得硬扛、也不得擅自开禁：先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) 用 `rg 'com\.itheima\.model\.(cache|vo|dto|command|entity)'` 全量列出待收口引用；(2) 逐类核对"归属 content"的模型类（需求文档 §4.2 归属表）与"归属其它域"的（如 LoginVO→user、GrabCouponRequest→coupon）无错放；(3) 若单任务体量过大（预计改动文件最多）→ **停决策点，向用户申请拆 2 个 commit**（如 a. 模型与共享组件收口 b. controller/service/dao 搬移），获准后在详情标注（G1 例外）。
* **验收**：content 域全部类落位；全仓库 `com.itheima.model.*` 中归属本域的引用清零、归属其它域的未错搬；`mvn compile` 通过与 JUnit 绿。

### T8 迁移 admin 域（方向 B）

* **入口线索**：[MediaAdminController.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/controller/MediaAdminController.java)（/api/admin/media/\*）、[AdminContentController.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/controller/AdminContentController.java)（/api/admin/content/\*）、[AdminCommentController.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/controller/AdminCommentController.java)（/api/admin/comment/\*）、[MediaAuditService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/service/MediaAuditService.java)、model/vo/AdminContentVO、model/audit/MediaAuditItem、MediaAuditResult、RestoreResult → 目标 `com.itheima.admin`。
* **红线边界**：不改媒体扫描/恢复/审核逻辑与 URL；依赖 content.ContentDao / comment.CommentDao → 跨域 import（content/comment 已搬，import 直接指向新包）；MediaAuditServiceTest 同包随迁。**若发现不动上述红线项就会阻碍迁移（如不改审核/恢复逻辑无法让编译/测试通过）→ 不得硬扛、也不得擅自开禁：先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）。**
* **强制探索步骤**：(1) `rg 'MediaAdminController|AdminContentController|AdminCommentController|MediaAuditService|AdminContentVO|MediaAuditItem|MediaAuditResult|RestoreResult' src/` 列出引用方；(2) 确认 auth/filter 角色校验不涉及包路径。
* **验收**：8 类落位 `com.itheima.admin`；`mvn compile` 通过与 JUnit 绿。

### T9 收尾（方向 B）

* **入口线索**：全仓库巡检 + 回归验证 + 文档同步。基于 T1~T8 完成态。
* **红线边界**：不引入任何新代码改动；只做巡检、验证与文档。**若发现必须改动缺陷才能满足验收（如旧引用残留必须补代码才能清零）→ 不得硬扛、也不得擅自开禁：先向用户申请并说明理由，批准后方可动手（G5/红线措辞约定）**；发现"要改但未排期"的内容 → **登记入 `目标与任务/UNPLANNED_ISSUES.md`（G5 超范围暂停落点）**，不在本周期硬做。
* **强制探索步骤**：(1) `rg -l 'com\.itheima\.(service|dao|controller|model)' src/` 确认无旧引用残留；(2) 校验 web.xml、IoC 扫描、`@WebServlet` 注解原样；(3) 确认 `src/test/java` 全部测试落位与 package 声明一致、`tools/CouponAdmin` import 已同步。
* **验收**：不再有旧技术包引用；pytest（`all`）全绿（收尾阶段统一回归一次）；更新 `CURRENT_ARCHITECTURE.md`（四.包结构、十.代码统计、更新日志）+ 重建覆盖率地图确认无回归；`BUSINESS_FLOW.md` 确认无需改动（纯搬移不改业务流）。

***

## 五、变更记录

| 日期         | 版本  | 内容                                                                                                   |
| ---------- | --- | ---------------------------------------------------------------------------------------------------- |
| 2026-09-10 | 0.1 | 新建本文档（草稿）：结转周期约定 G1-G10 与任务模板四要素/红线措辞/编号引用约定（删去原周期约定中指定特定外部角色执行测试的表述）；任务总览按需求文档 §6 拆分 **9 任务 = 8 域迁移 + 1 收尾**（coupon→upload→user→comment→follow→like→content→admin→收尾，T7 允许拆 2 commit 例外）；四要素详情按需求文档 §4.2 类归属表填写，待评审定稿 |
| 2026-09-10 | 0.2 | 配合需求文档精简：G8 改为"开分支/合并回 integration/合入 master 均由用户手动执行，任务窗口只负责代码/测试/commit"（删除 agent 操作的 integration 工作树流程描述） |
| 2026-09-10 | 0.3 | 落实红线措辞约定到全部 9 任务：每个任务"红线边界"不再只列"不改什么"，改为"列明不改项 + **若发现不动红线即阻碍迁移 → 先向用户申请并说明理由、获准后才动手**"的完整句式；顺带清除 T9 遗留的特定外部角色表述 |
| 2026-09-10 | 0.4 | 定稿：状态由"草稿待评审"改"任务拆分已定稿"；任务总览 9 任务状态由"草稿"改"待执行"；文档改英文名 NEXT_CYCLE_TASKS.md（关联文档指向 NEXT_CYCLE_NEEDS.md、未排期指向 UNPLANNED_ISSUES.md） |