# 下一周期需求与痛点

> **已归档（2026-09-12）**：本周期为 feature package 改造（B 方向，按 8 业务域重组 controller/service/dao/model，仅搬移不改名、行为零变化，T1~T9 全部完成）。新周期文档见 `.docs/目标与任务/`（勿改此归档文件，仅追溯用）。

> 用途：回答"下一周期为什么做这些"——本周期要解决的痛点、候选任务的优先级映射、以及开工前必须拍板的技术决策。
> 状态：**方案已定（2026-09-10 用户逐项拍板）** —— 目标包结构=8 域、共享组件归 content、业务层全拆基建不动、分域多任务、仅搬移不改名；任务拆分待评审后进入任务清单文档。
> 来源：260910-test-gap-fill 周期（补测试缺口 A 方向 T1~T8 全部完成，`target/all` 全绿）归档后的新一轮规划。方向来源：用户提出（feature package / 缓存改造 / feed 流改造），本周期**只做 B（feature package）**。
> 术语约定：**周期 > 任务**。本文档只回答 Why（需求与决策），How（拆任务）在任务清单文档。

***

## 一、结转：仍有效的通用约定（来自已归档周期，随周期继续生效）

| 编号 | 约定                  | 说明                                                    |
| ---- | --------------------- | ------------------------------------------------------- |
| C-1  | 双文档结构            | 本文档（需求与痛点，决策唯一源）+ 任务清单（执行细节）  |
| C-2  | 一任务一窗口一 commit | 默认期望，允许例外需标注；commit message 强制带任务编号 |
| C-3  | commit 语义闭环       | 代码 + 常青文档更新 + 任务清单勾选进**同一 commit**     |

***

## 二、结转：遗留未决项（260910-test-gap-fill 归档时未关闭）

| 编号  | 事项       | 状态     | 说明                                                                                             |
| --- | -------- | ------ | ---------------------------------------------------------------------------------------------- |
| P5  | 缓存一致性弱点 | 后续周期消化 | `LikeService.likeContent` 缓存更新在事务外；与 ContentCacheManager 拆分改造同源 → 属后续方向 C（缓存改造）；**本周期不管话题，只搬包不动逻辑** |
| P6  | 优惠券抢购限流 | 默认不做   | 需用户确认纳入才拆任务；改动面 `CouponService`/`CouponController`                                        |

> 后续方向预告（**均不在本周期范围**）：C 缓存改造（拆分 ContentCacheManager 653 行 god class + 统一缓存接口 + P5）；D feed 流改造（feed 缓存/推拉结合/关注列表分页）。本周期完成 B 后，C/D 的改动面将落在新包结构上。

***

## 三、本周期痛点（Why 梳理）

### 3.1 项目现状

| 项 | 现状 |
|----|------|
| 结构 | **按技术分层**：`com.itheima.controller/`、`.service/`、`.dao/`、`.model/`（entity/dto/vo/cache/audit/command 子包）、`.exception/`、`.util/`、`.filter/`、`.ioc/`、`.config/` |
| 规模 | 主代码 96 类 / 约 6500 行；HTTP 入口（Controller）18 个；Service 13 个；DAO 9 个 |
| 框架 | 纯 Servlet + 手写 IoC（**禁止 Spring/SpringBoot/MyBatis**）；Controller 用 `@WebServlet` 注解声明 URL |
| 测试安全网 | JUnit 201 例 + pytest 全绿（`all` 通过），覆盖地图显示 7 个零覆盖服务已全部补测 |

### 3.2 痛点

| 痛点 | 说明 |
|------|------|
| 1. 功能碎片化 | `ContentService`（内容）+ `FeedService`（关注流）+ `ProfileService`（主页）+ `StartController`（首页推荐）都围绕"内容"业务，却散落在 service/controller 各目录，读代码要跨 3~4 个包拼图 |
| 2. 技术分层与实际业务不匹配 | 找"关注"功能 = controller 翻 FollowController + service 翻 FollowService + dao 翻 FollowDao + model 翻 entity，实际是同一业务的三层碎片 |
| 3. 后续方向铺垫 | C（缓存改造拆 god class）与 D（feed 改造）的改动面跨多个技术层；**先按业务聚包**，C/D 的任务边界才清晰（用户此前已确认 B 必须先于 C/D，理由：缓存拆类在旧包做，后续改动还要二次搬家） |
| 4. 新增功能心智成本高 | 新同学/新 Agent 按"我要改用户注册"无法一次定位到 user 域，得先理解分层再做多包跳转 |

### 3.3 目标形态（Why 的答案）

把 `controller / service / dao / model` 四层按**业务能力**重组为域包，每域包含该业务自己的 controller/service/dao/model；`ioc / filter / util / exception / config` 与 web/dao 基建类**保持原位不动**（零风险面）。纯机械搬移，**行为零变化**。

***

## 四、目标包结构（已定稿，2026-09-10 用户逐项拍板）

### 4.1 包总览（8 业务域 + 基建不动）

```
com.itheima/
├── ioc/            # 基建不动：手写 IoC 容器与注解
├── filter/         # 基建不动：4 个 Servlet Filter（web.xml 全类名注册，勿动）
├── util/           # 基建不动：连接池/事务模板/JWT/密码/注入等
├── exception/      # 基建不动：异常体系（含通配 import exception.*，勿拆）
├── config/         # 基建不动：AppConfig
├── controller/     # 仅保留跨域基建：BaseServlet / BaseServletUtil / RequestParser（业务 Controller 全部搬出）
├── dao/            # 仅保留跨域基建：ResultMap（业务 DAO 全部搬出）
│
├── user/           # 用户/认证域
├── content/        # 内容域（含首页/搜索/详情/关注流/主页读接口 + 共享缓存组件）
├── follow/         # 关注域
├── like/           # 点赞域
├── comment/        # 评论域
├── coupon/         # 优惠券域
├── upload/         # 上传/媒体域
└── admin/          # 运维/审核域
```

> 关键机制确认（拆包可行性依据）：
> - **IoC**：`IocContainer.init()` 对 `ClassScanner.scan("com.itheima")` **整根递归**扫 `@Component` → 子包增减不影响 Bean 发现，**容器零改动**；
> - **URL**：Controller 全部经 `@WebServlet("…")` 注解声明，与 Java 包路径无关 → **前端 URL 与 pytest 一行不改**（2026-09-06 已确认结论）；
> - **web.xml**：只注册 4 个 filter（全类名）+ welcome-file，无 Servlet 类名引用 → 基建不动则 web.xml 零改动；
> - **类名唯一性**：已全量核对 96 个主类**无重名**，跨域搬移不产生 import 冲突（仅 3 处通配 import：`exception.*`×2、`util.*`×1，均属不动的基建包）。

### 4.2 各域类归属（对象清单，任务拆分的直接依据）

> 层级规则：每域内保留分层子包 `controller / service / dao / model`（model 下沿用 entity/cache/vo/command 等子分类），与现有技术层级命名一致，搬移成本最低。

#### user 域 — `com.itheima.user`
| 层 | 类 |
|----|-----|
| controller | LoginController（/user/*） |
| service | UserService |
| dao | UserDao |
| model | entity/User；dto/LoginDTO、RegisterDTO、ChangePasswordDTO；command/LoginCommand、RegisterCommand、ChangePasswordCommand、LoginType；vo/LoginVO |

#### content 域 — `com.itheima.content`（含 4 个读接口 + 共享组件）
| 层 | 类 |
|----|-----|
| controller | ContentController（/content/*）、StartController（/start）、SearchController（/search）、FeedController（/feed）、ProfileController（/profile）——**读接口并入 content（用户拍板，因共享缓存组件同域、内聚高）** |
| service | ContentService、ContentCacheManager、ContentStatusFiller、FeedService、ProfileService |
| dao | ContentDao、ContentMediaDao、ContentLikeDao |
| model | entity/ContentMedia；cache/ContentCacheDTO、CommentCacheDTO；vo/ContentVO、ContentDetailVO、CommentVO、**ProfileVO**；dto/PageResult、SearchDTO；command/CommandConverter、ContentType |

> **执行补充（2026-09-11）**：`ProfileVO`（主页响应 VO，依赖 PageResult/ContentVO、被 ProfileService/ProfileController 使用）原归属表未列举，探索发现后按内聚原则补入 content 域随迁；`ContentLikeDao` 归属以 like 域表为准（ContentService 级联清赞反向 import like）。

> **共享组件归属（用户拍板）**：ContentCacheManager（7 处引用）、ContentStatusFiller、ContentCacheDTO/CommentCacheDTO、PageResult、CommandConverter、ContentVO/ContentDetailVO/CommentVO **全部归本域**，其它域 import 引用（如 follow 域 FollowDao、admin 域 AdminContentVO 在各自域，content 需要时跨域 import 即可）。
> 取舍记录：CommandConverter 同时服务于 user/upload/comment 的请求转换，随共享组件归 content，形成 content→user/upload/comment 的单向跨域引用（由各 controller import content.CommandConverter），**可接受，Java 编译无环限制**。

#### follow 域 — `com.itheima.follow`
| 层 | 类 |
|----|-----|
| controller | FollowController（/follow/*） |
| service | FollowService |
| dao | FollowDao（FeedService/ProfileService/ContentStatusFiller 依赖它，跨域 import 到本域） |
| model | —（无专属 model） |

#### like 域 — `com.itheima.like`
| 层 | 类 |
|----|-----|
| controller | LikeController（/like/*） |
| service | LikeService、LikeCacheService |
| dao | ContentLikeDao、CommentLikeDao |
| model | —（无专属 model） |

> 注：ContentService 删除作品级联清点赞时 import `like.ContentLikeDao`（反向跨域），以代码现状为准，仅移动不改变行为。

#### comment 域 — `com.itheima.comment`
| 层 | 类 |
|----|-----|
| controller | CommentController（/comment/*） |
| service | CommentService |
| dao | CommentDao |
| model | dto/CommentDTO；command/CommentCommand（VO 的 CommentVO 已按共享组件归属归 content，本域无 vo） |

#### coupon 域 — `com.itheima.coupon`
| 层 | 类 |
|----|-----|
| controller | CouponController（/coupon/*） |
| service | CouponService |
| dao | CouponDao |
| model | dto/GrabCouponRequest |

#### upload 域 — `com.itheima.upload`
| 层 | 类 |
|----|-----|
| controller | UploadController（/api/upload/*）、UploadType |
| service | FileUploadService |
| dao | — |
| model | command/UploadCommand；vo/UploadResult |

#### admin 域 — `com.itheima.admin`
| 层 | 类 |
|----|-----|
| controller | MediaAdminController（/api/admin/media/*）、AdminContentController（/api/admin/content/*）、AdminCommentController（/api/admin/comment/*） |
| service | MediaAuditService |
| dao | —（复用 content.ContentDao / comment.CommentDao，跨域 import） |
| model | vo/AdminContentVO；audit/MediaAuditItem、MediaAuditResult、RestoreResult |

### 4.3 域间跨域引用原则

- 允许 feature 包间跨域 import（Java 无包依赖环限制，现有 service 交叉依赖继保持原样）；
- **唯一红线**：任何域不得反向依赖 `com.itheima.ioc / filter / util / exception / config`（它们保持原位，import 路径不变）；
- `controller.BaseServlet / BaseServletUtil / RequestParser`、`dao.ResultMap` 是跨域基建，**留在原包不动**，各域 controller 照常 import。

***

## 五、本周期范围与边界

### 5.1 范围（只做 B，方向定稿）

| 方向 | 说明 | 状态 |
|------|------|------|
| **B-feature package** | 按 8 域重组 controller/service/dao/model：纯 Java 机械搬移 + import/package 声明更新，**行为零变化** | **本周期做** |
| A 测试缺口收尾 | （上周期完成） | — |
| C 缓存改造 / D feed 流 | 后续周期 | 不做 |
| P5 / P6 | 缓存一致性 / 优惠券限流 | 不做 |
| 前端（SPA） | `@WebServlet` URL 不变 → 零改动 | 不做 |
| pytest 用例 | 端到端按验收行为组织，URL 不变 → **一行不改**（2026-09-06 已确认） | 不做 |

### 5.2 JUnit 与测试（2026-09-06 问询结论延续）

- **JUnit：与被测类同包跟随（技术性要求）**。`src/test/java` 下 12 个测试（11 service + 1 util）+ `tools/CouponAdmin` 中引用了主类包名的，其 `package` 声明与 import 需随被测类同步迁移：
  - `service/*Test.java`（11 个）→ 迁至对应域 `com.itheima.<域>.service` 同包；
  - `util/MyConnectionPoolTest.java` → 被测类未动（util 基建不动），**测试文件不迁**；
  - `tools/CouponAdmin.java` → 属 tools 测试脚本目录（非被测类包），确认其 import 的包名是否变化，若只引用 `coupon` 域类则同步 import，`package` 可保留。
- **pytest：一行不改**。
- 验收安全网：每个任务完成后 `JUnit 全绿`；`all`（pytest）放在改造全部结束的收尾任务统一跑一次确认零回归。

### 5.3 技术注意事项（执行前必读）

| # | 注意点 | 说明 |
|---|--------|------|
| 1 | **package 声明 + 所有 import 成对改** | 每个搬移类：改 `package com.itheima.xxx.yyy;`，并把所有引用它的文件（含其他域、测试、tools）的 import 同步改；`mvn compile` 是唯一判定 |
| 2 | **类名不改**（用户拍板） | 只搬移不改名，diff 干净、行为零变化，也是 C/D 后续改动回查的锚点 |
| 3 | **基建包通配 import 勿破坏** | `exception.*`（ContentService/CommentService）、`util.*`（UserService）通配 import 都指向不动的基建包，保持原样 |
| 4 | **@WebServlet 注解值不要动** | 只动物理包位置，URL 一个字都不能改，否则前端/pytest 全挂 |
| 5 | **IoC 扫描无需配置** | `scan("com.itheima")` 递归根包，搬移后 Bean 照常发现；只要不把类搬出 `com.itheima` 根即可 |
| 6 | **域间跨包引用逐一核** | 用 `rg 'com.itheima\.(service|dao|controller)'` 巡检搬移后的残留旧引用，确保清零 |
| 7 | **git 提交流程** | 分支创建与合并由用户手动执行（G8）；任务窗口只负责本任务的代码、测试与 commit（G1），不自行开分支/合 master |
| 8 | **小类随大域同 commit** | 单类域（coupon/upload）与多类域挨个迁移，每域一个 commit 即可，避免把一个域拆成多次半成品 |

## 七、决策与约束（2026-09-10 用户逐项拍板记录）

- **改造深度**：业务层（controller/service/dao/model）全拆入 8 域；基建（ioc/filter/util/exception/config + controller/BaseServlet/BaseServletUtil/RequestParser + dao/ResultMap）**保持不动**。web.xml 零改动。
- **共享组件归属**：ContentCacheManager / ContentStatusFiller / cache DTO（ContentCacheDTO、CommentCacheDTO）/ PageResult / CommandConverter / content 相关 VO（ContentVO、ContentDetailVO、CommentVO）**归 content 域**，其它域 import。
- **任务拆分粒度**：**分域多任务**（遵循 G1 一任务一 commit），建议 8 迁移 commit + 1 收尾 commit，可在任务清单定稿时微调。
- **类名**：**仅搬移不改名**（LoginController 仍叫 LoginController 等）。
- **读接口并入 content**：Start/Search/Feed/Profile 四组读接口与其共享缓存组件同归 content 域。
- **红线措辞约定延续**：发现不改"红线"就阻碍后续工作 → 先向用户申请并说明理由，批准后才能动手。
- **编号引用约定延续**：本文档禁用裸编号引用已归档周期元素（旧 T1-T8/N1-N6 等），如需引用用描述或带周期前缀；裸编号仅指本文档内部定义元素（A~D、C-1~C-4、P5/P6、B/C/D）。

***

## 八、变更记录

| 日期         | 版本  | 内容 |
| ---------- | --- | ---- |
| 2026-09-10 | 0.1 | 新建本文档（备选方向 B=feature package 定稿为本周期目标）：归档 260910-test-gap-fill；梳理痛点与现状（96 类按技术分层、读接口碎片化、为 C/D 铺垫）；目标包结构=8 业务域 + 基建不动；**用户逐项拍板**（改造深度=业务层全拆基建不动 / 共享组件归 content / 分域多任务 / 仅搬移不改名 / 读接口并入 content）；给出各域类归属清单、技术注意事项、任务拆分骨架（8 迁移+1 收尾）；待评审后进入任务清单文档 |
| 2026-09-10 | 0.2 | 精简：删除"六、任务拆分"整节（拆分骨架/收尾任务已在任务清单文档完整覆盖，仅保留任务清单为 How 唯一载体）；§5.3 第 7 条 git 流程改为"分支创建与合并由用户手动执行"，删除 integration 工作树/合入 master 的重复操作描述 |