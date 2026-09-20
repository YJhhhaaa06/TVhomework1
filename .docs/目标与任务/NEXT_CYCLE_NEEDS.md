# 下一周期需求与痛点（NEEDS）

> 用途：回答"下一周期为什么做这些"——本周期要处理的需求与痛点、候选任务的优先级映射、以及开工前必须拍板的技术决策。
> 状态：**待立项（2026-09-19 建；2026-09-20 T11 窗口更新）**——第六期「杂务/准备周期」（`260919-prep-cleanup`，分支 `prep/next-phase`，T1~T9 全部完成）已归档，本档为**第七期结转稿**：二/三节承接归档周期的未关闭事项与留池项。**主线方向仍未拍板**；已完成：① 周期探查（2026-09-19）——4.1 登记 **N9~N16** 跨方向通用前置（N1~N8 已按用户指令移出）② **任务清单已建**（`NEXT_CYCLE_TASKS.md`：现 **13 个任务**——T10-A/T10-B 已完成、T11-A/T11-B/T11-C 已完成（2026-09-20）、T12~T18 清理类、**T19** 推后项）③ `UNPLANNED_ISSUES.md` 池已按 2026-09-19 评估收敛（见该档）；④ **T10-A/T10-B 已完成**（评论分页两键组 + 主楼窗口装载 + 楼中楼 K=2 + 展开接口，`refactor(cache-10a/10b)`）；⑤ **T11 已于 2026-09-20 窗口完成范围拍板并拆 T11-A/T11-B/T11-C**（接口口径 / 评论信封 + 公共 helper / 装载侧解耦），四要素已回写；⑥ **T11-A / T11-B / T11-C 均已于 2026-09-20 落地**（`refactor(cache-11a/11b/11c)`；T11-C 含 DDL 闭环 + 池 U-18 治本 + 池 U-21 处置）；⑦ **T12（池 U-14：事务边界同型未治点 2 处）已于 2026-09-20 落地**（`refactor(cache-12)`；`search` 与关注/粉丝列表装载的缓存读移出事务回调）；⑧ **T13（池 U-16：注册后自动登录缺兜底）已于 2026-09-20 落地**（`fix(user)`；`registerAndLogin` 兜底——注册成功即成功，自动登录失败返回 `token=null` 的 LoginVO，前端提示手动登录）；⑨ **R-03 已于 2026-09-20 T14 窗口拍板并落地**（① content↔comment 环**保留现状并显式登记**、② 分页信封上移 `common.model.dto` 且同形二分消除、③ `ResultMap` 按域拆方法 + 删 `dao` 包）→ **T14（包层结构清扫，治池 U-07 + U-19 + N14）已完成**（`refactor(cache-14)`；全链 exit 0 + JUnit 511/0/0/0 + pytest 145；顺带真消除 `content↔dao` / `dao↔user` 两环）。评审时按 `R-##` / `N#` / `T10`~`T19` 点单。
> 配套：How（拆任务）见 `目标与任务/NEXT_CYCLE_TASKS.md`（**已建**：T10/T11 预告条目 + T12~T14 清理类，含周期约定 G1~G11 与四要素模板）。
> 注意：二/三为**结转总账**，不等于本周期范围；本周期做什么、不做什么，将以 **4.3** 为准（现为空，待方向拍板）。
> 来源：`260919-prep-cleanup` 周期（第六期「杂务/准备周期」，T1~T9 全部完成）归档后（2026-09-19）：结转未完成需求与未拍板决策（二/三表）+ `UNPLANNED_ISSUES.md` 池（**收敛后仅剩 U-11 / U-17（已改写）**——`U-18` 已于 2026-09-20 T11 窗口处置转 T11-C 并随该任务落地，见该档"第七期评估"与"2026-09-20 T11 窗口处置"）；**4.1 的 N9~N16 = 2026-09-19 代码探查的跨方向通用前置**（逐条读码取证，结论已复核）；**4.2 = 两个待拍板任务**（评论分页 / 关注·粉丝列表分页，已落 T10/T11）。
> 术语约定：**周期 > 任务**。本文档只回答 Why（需求与决策），How（拆任务）在任务清单文档。

***

## 一、结转：仍有效的通用约定（来自已归档周期，随周期继续生效）

| 编号 | 约定 | 说明 |
| ---- | ---- | ---- |
| C-1 | 双文档结构 | 本文档（需求与痛点，决策唯一源）+ 任务清单（执行细节） |
| C-2 | 一任务一窗口一 commit | 默认期望，允许例外需标注；commit message 强制带任务编号（格式按 `说明书/COMMIT_CONVENTION.md`） |
| C-3 | commit 语义闭环 | 代码 + 常青文档更新 + 任务清单勾选进**同一 commit**；message 按 `说明书/COMMIT_CONVENTION.md` 规范书写（type/scope/header/正文分节/任务编号细则见该规范） |

> 另有若干"延续性约定"不单独编号，随周期生效（原文见 `archive/目标与任务/260919-prep-cleanup/NEXT_CYCLE_NEEDS.md` 一节，逐条核对仍有效）：
> ① **红线措辞约定**：红线只列"明显越界"的项，作用是**防跑偏、不把执行 Agent 限制死**（不穷举做法、不做一刀切禁止）；若某条红线会阻碍正确做法（过紧 / 过窄 / 已不适用）——不允许硬扛、也不允许自行放开，**先说明理由申请调整**，获批准后按新口径动手，未获批准则维持原红线（完整表述见 `NEXT_CYCLE_TASKS.md` 二节）；
> ② **编号引用约定**：禁裸编号引用已归档周期元素，引用一律写成 `<周期>/<编号>`（如 `260919/R-02`）；裸编号仅指本文档内部定义的元素；
> ③ **技术红线**：禁 Spring/SpringBoot/MyBatis；不擅改 `@WebServlet` URL / web.xml / IoC 扫描；不为实现便利改业务逻辑语义；
> ④ **不引入 MQ**：异步（若需）用进程内线程池（`ExecutorService`），不引入 RabbitMQ/Kafka/RocketMQ；
> ⑤ **脚本规范**：脚本一律 Python；临时一次性脚本放 `temp_script/`，长期复用/自动化放 `tools/`（统一入口 `tools/tv.py`）；
> ⑥ **DDL 备份**：出现表结构改动，执行前先备份库结构与建表语句到 `.docs/DBbackups/`；
> ⑦ **质疑协议（G11）**：执行 Agent 对 Why 层（需求真实性/必要性）与 How 层（任务/验收可操作性）有质疑权、亦有报告义务——四时点触发（开窗阅读 / 动手前复核 / 探索中 / 验收时）× L1~L4 分级动作，质疑记录回写 4.0，**裁决权永远在用户**（完整协议与记录格式见 `NEXT_CYCLE_TASKS.md` 二节）。
>
> 另有两条由第六期 T1/T9 落地、已并入上述约定的执行口径：**变更纪律**——周期/模板文档不设"变更记录"节，变更以 git 提交历史为准（commit message 按 `COMMIT_CONVENTION.md` 的「问题/方案/验证/文档」分节承载）；**测试执行**——沙箱内可直接 `python tools\tv.py test all` 跑全链（buildDir / 日志 / 媒体均已收进项目内 `.stage8-target`），git 提交仍由用户手动执行（沿用 G8）。

***

## 二、结转：未完成的需求（`260919-prep-cleanup` 归档时未关闭）

> **编号体系（本档内部）**：`C-#` 通用约定；**`R-##` 待评审事项**（本节与三节连续编号，评审时按 R 编号点单）；`N#` 本周期新探查候选痛点（4.1）；`T#` 任务（见任务清单文档）。引用**已归档周期**的元素仍写 `<周期>/<编号>`（见一②）；每行"来源"列保留原编号以便追溯，正文不使用带周期前缀的旧编号。

| 编号 | 事项 | 类别 | 来源（归档周期） | 状态 | 说明 |
| ---- | ---- | ---- | ---- | ---- | ---- |
| R-01 | 索引全量读 + 拷贝 shuffle 本体 | 需求（性能） | `260919/R-01`（= `260918/R-02`） | 明确保留 | `LRANGE 0 -1` 全量读保留（推荐 shuffle 对外语义不变），`getRecommendByFilter` 读面每次全量去重 + shuffle 只取 12 条，数据量大时 O(n)；待 feed 改造或真流量触发再评估 |
| R-02 | content ↔ comment 包层循环依赖 | 观察（包架构） | `260919/R-02`（= `260918/R-04` = `UNPLANNED_ISSUES.md` U-07） | **已按口径处置（2026-09-20 T14，见三节 R-03）** | content 域共享组件被 comment 域引用、content 又引用 comment 的 `CommentService`；**非 IoC/Bean 环**仅包架构不纯净；自 260910 起多周期复查仍在。**2026-09-20 T14 结论**：全仓实测有 **9 个**双向包环，本环 15 条边中仅 5 条属"组件错位"，其余为真业务互依 → 按 R-03 口径① **保留现状 + 在常青 CA 4.1 显式登记**（不再"无人知晓"）；T14 真正消除的是 `content↔dao` / `dao↔user` 两环 |



***

## 三、结转：未拍板的决策（进任务清单前须拍板）

> 编号接续二（`R-##` 连续）；本表是"必须拍板才能开工"的决策项，二表是"待消化的事实项"。

| 编号 | 待拍板事项 | 来源（归档周期） | 当前状态 | 说明 |
| ---- | ---- | ---- | ---- | ---- |
| R-03 | content ↔ comment 包层环（= 二节 R-02）怎么处置 | `260919/R-04`（= 池 `U-07`） | **已拍板（2026-09-20 T14 窗口，用户裁决）** | 已与池 `U-19`（分页信封上移公共包）合并为 **T14「包层结构清扫」**（✅ 已完成 2026-09-20）。**拍板结论**：① 环的处置口径 = **允许现状不治**（否决"搬移错位组件回 comment 域"：环不消除、content→comment 边反而 5→6、成本换"归属观感"）；② 信封上移的目标包 = **`com.itheima.common.model.dto`**，范围 = 全项目唯一信封（删同形的 `FollowPageResult`；涉 content 6 main + 4 test import 调整）；③ `ResultMap` 反向依赖 = **按域拆方法 + 删 `dao` 包**。多周期未动的问题本窗口收口：环从"无人知晓"变"已登记已接受"，`dao` 相关 2 环真消除 |

***

## 四、本周期痛点与目标方案

### 4.0 已回写技术决策与质疑记录（执行中拍板/质疑，按任务追加）

> 每落地一个任务的技术决策在此追加一段（G7：窗口内新决策先回写本节的"已定/待定"状态，不许自行拍板）：任务编号 + commit 前缀 + 拍板日期 + 拍板取向与理由 + 关键实现点 + 落点（类/模块）+ 验证摘要。
> **质疑记录（G11）**：Why 层质疑（前提证伪 / 已满足 / 必要性存疑 / 内部矛盾）在此登记——L2 带疑继续、L3 暂停必须留痕（L1 仅记录落任务清单"执行回写"）；**裁决权在用户**，裁决结果即一条新决策，同节留痕。

```markdown
**T#（<commit scope>，<日期> 拍板并落地——<技术决策主题>）**：
<拍板取向与理由>；<关键实现点>；<残余窗口与接受理由（如有）>。落点：`<类/模块>`（详见 `常青/CURRENT_ARCHITECTURE.md` <章节>；验证 JUnit <n> + pytest <n> + <专项验证> 见 `NEXT_CYCLE_TASKS.md` T# 执行回写）。

**质疑 <N#/R-##/C-#>（<日期>，T# 窗口，L<级别>）**：
触发：<开窗阅读/动手前复核/探索中/验收时>；证据：<文件:行 / 复现结果 / 日志>；主张：<前提证伪/已满足/必要性存疑/内部矛盾>；建议：<废弃/降级留池/改范围/仅记录>；当前动作：<已暂停/带疑继续>；用户裁决：<待裁决 → <裁决结果 + 日期>>。

```

**T13（`fix(user)`，2026-09-20 拍板并落地——注册后自动登录兜底的口径）**：
落点 = **兜底下沉到 `UserService.registerAndLogin`**（否决"仅在 Controller 里 try/catch"：Controller 无测试层、且该失败路径 E2E 不可复现，详见 `NEXT_CYCLE_TASKS.md` T13 执行回写 D1）；契约形态 = **复用 `LoginVO`，以 `token == null` 表示"注册成功但自动登录失败"**（否决新增 `RegisterVO`/新字段——会造出与 LoginVO 同形的第二份 VO，与 T14/池 U-19 的"同形二分"清扫方向相反；否决复用 `msg` 传提示——`api.js` 的 `request()` 只把 `data` 交给调用方，要暴露 msg 就得改全站请求封装）；catch 范围限 `BusinessException`（非业务异常照旧 500），失败记 WARNING 不静默；前端 `views/login.js` 据 `!data.token` 提示"注册成功，请手动登录"并切回登录 tab（不 navigate）。落点：`user/service/UserService`、`user/controller/LoginController`、`static/js/views/login.js`（详见 `常青/CURRENT_ARCHITECTURE.md` 4.3 user 域 + 7.1、`常青/BUSINESS_FLOW.md` 2.2；验证 JUnit 511 + pytest 145 见 TASKS T13 执行回写）。

**T14（`refactor(cache-14)`，2026-09-20 拍板并落地——包层结构清扫的三处口径）**：
① **content↔comment 包层环 = 保留现状 + 显式登记**（否决"搬移 `CommentCacheDTO` / `CommentVO` / `CommentCache` 回 comment 域"——环**不消除**、`content→comment` 边反而由 5 升到 6、成本换来的是"归属观感"，对外行为/能力/可测性零变化）；依据 = **全仓实测 9 个双向包环**（一次性脚本 `temp_script/t14_domain_matrix.py`）与该环 15 条边中**仅 5 条属"组件错位"**的量化事实，且 CA 4.1 本就约定"跨域依赖允许"；② **分页信封唯一源 = 新增 `com.itheima.common.model.dto.PageResult`**（否决 `com.itheima.model.dto`——与 `util`/`cache` 并列易被误读成"全局实体模型"；否决 `com.itheima.controller`——`ProfileVO` 等 model 层会反向 import controller 层，层次倒置），同一提交删除同形的 `content.model.dto.PageResult` 与 `follow.model.dto.FollowPageResult`，**JSON 字段名与 `totalPages` 推导公式逐字段不变 → 前端零改动**；③ **`ResultMap` 反向依赖 = 按域拆方法 + 删 `dao` 包**（否决"整体搬 `content/dao`"= 问题平移、新增 2 条反向边；否决"保留为泛型行读取工具"= 6 处映射体逐字段重写，是**重写**不是搬移）；副产品 = `content↔dao` / `dao↔user` **两环真消除**。残余窗口：其余 7 个环（含 `content↔comment`）**保留现状**，已在 CA 4.1 登记为"已接受"；彻底单向化需引入抽象层（属新能力，另行立项，本任务不治）。落点：`common/model/dto/PageResult`（新增）、`content/dao/ContentDao`、`content/dao/ContentMediaDao`、`comment/dao/CommentDao`、`user/dao/UserDao`、`follow/service/FollowService`、`comment/service/CommentService`（详见 `常青/CURRENT_ARCHITECTURE.md` 4.1/4.2/4.3；验证 JUnit 511 + pytest 145 + "6 个方法体逐行相等"脚本见 `NEXT_CYCLE_TASKS.md` T14 执行回写）。

### 4.1 候选痛点（跨方向通用前置，**待评审纳入，尚未拍板**）

> **本次探查目的（2026-09-19）**：本分支（`prep/next-phase`）既有工作是为 **日志体系改造 / feed 流改造 / 新功能加入** 三大方向腾地基。
> **证据须可复核定位**（G11 配套）：文件:行号 / 复现命令 / 日志片段，不接受纯文字断言——执行窗口动手前按 G11 第 (0) 项复核证据，不成立 → L3 暂停并登记质疑。
> 评审时逐条决定：纳入本周期 / 转 `UNPLANNED_ISSUES.md` 留痕 / 废弃。任一项经评审纳入后升格为正式编号（并入 `R-##` 或任务 `T#`）。
> **与已登记项的分工**：日志方向主干项 `U-15`、feed 方向事项（`R-01` 索引全量读、`U-18` 装载全量等）均随各自方向立项盘点，本节不重复；`U-14`（事务回调内缓存读）、`U-19`（信封同形二分）另有独立登记（**`U-14` 已随 T12 ✅、`U-19` 已随 T14 ✅ 落地**）。

| 编号 | 问题 | 证据（可复核定位） | 不改会怎样（具体场景） |
| ---- | ---- | ---- | ---- |
| N9 | **[新功能]** 鉴权名单硬编码（两个手写 `Set`），且架构文档的名单**已滞后于代码** | `filter/AuthFilter.java:20-40`（`PROTECTED_PREFIXES` + `PROTECTED_EXACT` 手写 15 项）vs `常青/CURRENT_ARCHITECTURE.md:135`（精确名单只列 7 项，缺 `/content/update`、`/content/mediaDelete`、`/content/delete`） | 加新受保护接口漏往 Set 里加 → 接口静默变成免登录可调，无编译期/启动期提示；照文档配鉴权则必然漏配（文档已滞后） |
| N10 | **[新功能]** 管理员鉴权每请求查库 | `filter/AuthFilter.java:59`（`if (userId == null || !userService.isAdmin(userId))`）+ `常青/CURRENT_ARCHITECTURE.md:136` 自述"每次请求查库" | admin 接口（媒体运维/内容审核/评论运维）每请求一次 DB；接口变多后是固定开销，并会污染日志改造要建立的"每接口耗时"基线 |
| N11 | **[新功能]** Controller 基建重复：**三份** ObjectMapper、两份逐字重复的 `write*`、工具类却继承 `HttpServlet` | `controller/BaseServlet.java:17-19` 与 `controller/BaseServletUtil.java:17-19`（ObjectMapper 配置逐字重复）、两类 `writeSuccess/writeError` 方法体逐字相同；`controller/RequestParser.java:11`（第三份 `new ObjectMapper()`，未注册 JavaTimeModule/未禁 timestamps → 日期口径与另两份不一致）；`BaseServletUtil.java:15`（`extends HttpServlet` 但无任何 URL 映射） | 加新域 Controller 要照抄整套模板；三份 mapper 配置漂移（日期序列化口径不一致，前端解析行为可能不同）；统一响应格式/统一打点要改多处 |
| N12 | **[新功能]** IoC 注入失败**静默跳过**；无声明式事务，写方法全靠手包 `transactionTemplate.execute` | `ioc/IocContainer.java:140-141`（`Object dependency = beans.get(field.getType()); if (dependency != null) { ... }` —— 取不到就跳过、不报错）；**两类注入方式并存且都走这条静默路径**：Controller 用 `@Inject` 字段注入（`content/controller/FeedController.java:19`），Service 用 `@InjectConstructor` 构造器注入（`content/service/FeedService.java:30`）；写路径手包事务示例 `content/service/ContentService.java:201`、`content/service/FeedService.java:58` | 新服务把依赖类型写错、或漏 `@Component` → 启动不报错、运行期 NPE；忘包事务则静默丢回滚语义；加新功能缺"启动期自检"这道网 |
| N13 | **[新功能]** 配置接入散点：本机绝对路径随包入库 + 阈值硬编码 + 逐键 getter | `app.properties:32`（`upload.path=D:/data/projects/VideoPlatform/stone`）；`controller/BaseServletUtil.java:68`（`Math.min(s, 50)` 分页上限硬编码、未参数化）；`config/AppConfig`（每键一个静态 getter） | 换环境部署时上传/媒体直接不可用（T9 只本地化了**测试**链路，生产配置仍是本机绝对路径）；新列表想要不同页大小只能改公共 util（波及所有分页接口） |
| N14 | **[新功能]** 基础包反向依赖业务模型：`dao/ResultMap` import content 域的缓存 DTO | `dao/ResultMap.java:2`（`import com.itheima.content.model.cache.CommentCacheDTO;`） | 想把它做成真正的公共基建就被业务模型绑死；新域要么改公共类扩大耦合，要么在 DAO 里重抄 try/PreparedStatement/映射（DAO 层共 83 个 `Connection` 首参方法，而 `ResultMap` 只被 4 个 DAO 引用——content/comment/user 域，另 4 个 DAO（follow/like/coupon）全部内联 `rs.get` 手写映射），加新域的固定成本下不来 |
| N15 | **[新功能]** 前端加页面/加列表的固定成本：路由注册硬编码 + "加载更多"6 份重复实现 | `static/js/main.js:160-168`（9 条 `register(...)`，另需同步同文件 import 与抽屉导航）；"加载更多"按钮构造散布 `views/detail.js:281`、`views/follow.js:73`、`views/publish.js:235`、`views/search.js:176`、`views/user.js:186/257` | 新增页面要改 main.js 多处（漏一处 = 有路由无入口/无高亮）；列表分页交互 6 份各自维护、行为易不一致（用户两次点名的"分页加载"每次都要重写一遍） |
| N16 | **[文档滞后]** 常青架构文档目录树含**已不存在**的 `ssm_*` | `常青/CURRENT_ARCHITECTURE.md:72`（`ssm_*/ # 空壳子模块（待删除）`）vs 仓库根实测无 `ssm_*`（`ls` 仅 AGENTS.md / logs / pom.xml / src / target / temp_script / tools） | 新窗口按文档找目录会扑空；与 N9 同源（事实章节未随代码更新），文档可信度下降，影响"照文档动手"的可靠性 |
| N17 | **[新功能]** 评论热门排序（order=hot）：用户 2026-09-19 T10 窗口提出的"热门评论"思路（content 加 comment_like_count + hot_comments 打分表 + 前端去重），评审判为**结构性硬伤**（触发条件/打分/K 未定义、写热路径放大、分页语义被前端去重破坏）→ **延后（用户"另想对策"）**；登记最小可行替代 = **排序参数化**：`/comment/show` 加 `order=hot|time`（默认 time 向后兼容），hot 序 `ORDER BY like_count DESC, comment_id` 由**构造保证**页间不重不漏（静态排序、前端零去重）；缓存侧主楼有序结构需换代（List → ZSet，score=like_count，点赞 ZADD 刷分）；候选索引 `(content_id, is_deleted, like_count)`（替代/叠加 T10-A 的基础索引）；`reply_count`（T10-A 已建）亦可作热度信号 | 新增排序语义属对外行为变化，与 T10 的"时间序契约"冲突；主楼有序结构换代影响 T10-A 两键组设计（T10-A 先按 List，热度需换 ZSet 时单独立项评估） | T10 窗口讨论（2026-09-19） | **延后留池**——用户另想对策，不占 T10 范围 |

> **本次探查已排除项（防下次重复探查）**：① 主代码无 `System.out`/`System.err`/`printStackTrace` 残留（仅测试工具 `src/test/java/com/itheima/tools/CouponAdmin.java:51`）；② feed 页内点赞状态已是批量查询（`FeedService.java:92`），非 N+1；③ 敏感信息已脱敏（`UserService.java:106` `maskPhone`），无密码/token 明文日志；④ `LogUtil` 文件不可写时有降级日志；⑤ 无绕过 IoC 容器的 `new *Service(`/`new *Dao(`。

> **候选去向（2026-09-19 建 TASKS 后）**：
> **已排进清单**（清单"对应候选"列保留 N 编号，Why→How 可追溯）——`N9`（文档名单部分）→ **T15**（✅ 已完成 2026-09-20）；`N11` → **T16**；`N12`（前半：注入静默失败）→ **T17**；`N13` → **T18**（注：**分页上限参数化已随 T10-B/T11-A 落地**，本任务不含该部分）；`N14` → **T14**（✅ 已完成 2026-09-20）；**`N15` → T10-B（首步：抽公共 helper）+ T11-A/T11-B（接入与增强：关注/粉丝 sheet、评论列表、feed/search/profile 内容列表）**（✅ 已完成 2026-09-19/20）；`N16` → **T15**（✅ 已完成 2026-09-20）。**`N15` 残留未治部分**：`main.js` 路由注册硬编码（9 条 `register(...)` + import + 抽屉导航三处同改）——不在 T11 范围，留待后续（与前端结构改造同批）。
> **未排**（属**新功能方向的能力建设**，待该方向立项时评估）：`N9` 的"声明式鉴权"（注解/路由表）、`N10`（admin 角色每请求查库）、`N12` 的"声明式事务 / AOP"。
> **待你点单调整**：以上归组如与预期不符（例如希望 `N10` 也本期做、或某条改期），直接在评审时点名即可——清单与本节同步改。

### 4.2 候选方向（拍板后记录结论）

| 候选 | 内容 | 依据 | 结论 |
| ---- | ---- | ---- | ---- |
| **评论分页（后端大分页 + 前端小分页）** | **已拍板（2026-09-19 T10 窗口，拆 T10-A/T10-B）**：命中路径单次成本与评论总量**弱相关**、请求数下降。方向 = 主楼/楼中楼缓存分开（整树单键 → **两键组**：主楼 List + 楼中楼 Hash）+ 主楼**窗口装载**（不一次性查全库）+ 楼中楼前 **K=2** 条 + `reply_count` 总数 + 展开加载回复 + 前端大 chunk/本地小批 | 用户 2026-09-19 澄清（后端大分页不止改前端 chunk，还含评论主楼/楼中楼缓存分开、可能动 DB 索引）；T8 的 D1=A 仅做展示层切片、命中路径仍整树反序列化（原 `U-20`）；切片接缝已收敛为 `ContentService.sliceRoots` 唯一方法；这次窗口进一步明确了"窗口**装载**"诉求（不只窗口读） | **已落 T10-A / T10-B（四要素已回写）**：T10-A = 两键组 + 主楼窗口装载 + DDL（`reply_count` + `(content_id,parent_id)` 索引），**对外契约零变化**；T10-B = 楼中楼 K=2 + 展开接口 + 前端大 chunk/helper（契约变更，已批准）。**前置已解决**：① T8 红线已由用户放开 ② comment 索引已拍板并按 G9 备份闭环。**不做**：offset→游标（属 feed 流方向）；hot_comments 打分表/前端去重（结构性硬伤，登记 N17 延后）；权限热度排序本窗口不做 |
| **关注/粉丝列表分页（后端大分页 + 前端小分页）** | **已拍板（2026-09-20 T11 窗口，拆 T11-A/T11-B/T11-C）**：① follow 域上限 **200** + **信封大小由后端定**（三参 `parsePageSize(req, max, defaultSize)`，前端只传 `page`）+ 缺省**反转为「= 第一页信封」**（**破坏性契约变更，已批准**）；② 评论域信封 `default 200 / max 500` + 公共 helper **去重**（`keyOf`+`seen`）与 `chunkSize` 自适应 + feed/search/profile **仅前端**迁移（`chunk=50`）；③ **P1 前缀窗口装载**治池 **U-18**（冷/降级装载 O(总量) → O(offset+页大小)） | T7 已给 ZSet 有序化 + `ZSetCache.getWindow` 窗口读（命中路径成本已 ∝ 页）+ B2 信封；**U-18 实测触发点 = `ZSetCache.getWindow` miss 单飞全量 loader 与降级全量装载**（`ZSetCache.java:174-192`）+ `FollowDao` 全量 SQL 无 LIMIT/ORDER BY（`:47-74`）；判定路径 key 一律是 `user:following:{当前用户}`（与"大 V 百万粉丝"无关）；`follow` 实索引支持关注方向窗口查询、粉丝方向需补复合索引（备份实测）。详见 `.docs/temp/T11_PLAN.md` 证据表 E1~E17 | **已落 T11-A / T11-B / T11-C（四要素已回写）**；**T11-A / T11-B / T11-C 均已于 2026-09-20 完成**（结论见 4.0 与 `NEXT_CYCLE_TASKS.md` 执行回写；T11-C 实现层两处偏离与残余竞态见该档 G11 记录）；`feed`/`search`/`profile` 的**后端**大分页推后为 **T19**（三处前端迁移仍在 T11-B）。**不做**：offset→游标（属 feed 流方向）；删 `pageSize` 参数（会摧毁 pytest 小信封跨页验证能力）；`getFollowingIds` 的 feed 全量读（R-01 保留） |

> **（2026-09-19 按用户口径调整；2026-09-20 T11 窗口更新）**：候选**已拆为两个任务**——**T10 评论分页 / T11 关注·粉丝列表分页**（见 `NEXT_CYCLE_TASKS.md`）。**T10 已于 2026-09-19 T10 窗口完成拍板并拆 T10-A/T10-B**（结论见上表与本档 4.0）；**T11 已于 2026-09-20 T11 窗口完成拍板并拆 T11-A（接口口径）/ T11-B（评论信封 + 公共 helper）/ T11-C（装载侧解耦）**，另立推后项 **T19**（feed/search/profile 后端大分页）；实现形态选中"缓存窗口读 + 前缀装载"（非 DB 窗口读）、`follow` 表索引改动已定（`idx_followed_user_user`，走 G9），四要素均已回写 `NEXT_CYCLE_TASKS.md` 四节；**T11-A / T11-B / T11-C 均已于 2026-09-20 落地**（各含独立 commit；T11-C 拆 C-1 DDL + 窗口 DAO / C-2 前缀装载两个 commit），池 U-18 随之关闭、U-21 随 C-2 删除方法关闭。


### 4.3 本周期范围（方向拍板结果）

**主题**：待拍板（T10-A/T10-B 已完成；**T11-A/T11-B/T11-C 已于 2026-09-20 完成**；**T12 事务边界同型未治点 2 处已于 2026-09-20 完成**；**T13 注册后自动登录兜底已于 2026-09-20 完成**；**T14 包层结构清扫（R-03 拍板 + 池 U-07/U-19 + N14）已于 2026-09-20 完成**；**T15 文档与代码一致性清理（N16 + N9 文档部分）已于 2026-09-20 完成**（纯文档批，不跑测试）；T16~T19 待执行）。

| 纳入 | 对应编号 | 落到任务 | 一句话 |
| ---- | ---- | ---- | ---- |
| 评论分页①：缓存结构拆分 + 主楼窗口装载 + DDL | 4.2 ①（= 池 U-20，治本） | **T10-A** | 两键组（主楼 List + 楼中楼 Hash）+ 主楼窗口装载；对外契约零变化；DDL `reply_count` + 索引 |
| 评论分页②：楼中楼 K=2 + 展开接口 + 前端大 chunk/helper | 4.2 ① + N15 | **T10-B**（依赖 T10-A） | children 前 2 条 + replyCount 总数 + 展开加载回复 + 域级 pageSize 上限 + 公共分块列表 helper（N15，T11 复用） |
| 关注/粉丝列表接口口径：域级上限 200 + 后端固定信封 + 缺省归一为第一页 + sheet 接 helper | 4.2 ② + N15 | **T11-A**（✅ 已完成 2026-09-20） | follow 域 `pageSize` 上限 200、信封由后端定（前端只传 `page`）、缺省 = 第一页信封（契约变更）；sheet 接 `chunkedList` |
| 评论域固定信封 + 公共 helper 去重/自适应 + 内容列表前端迁移 | 4.2 ② + 4.1 **N15** | **T11-B**（✅ 已完成 2026-09-20） | 评论域 `default 200 / max 500`；`keyOf`+`seen` 去重与 `chunkSize` 自适应；feed/search/profile 前端迁移（`chunk=50`，后端不动） |
| 关注/粉丝列表装载侧解耦（池 U-18 治本） | 4.2 ② + 池 **U-18** | **T11-C**（✅ 已完成 2026-09-20；依赖 T11-A，内部 C-1 → C-2） | P1 前缀窗口装载：冷/降级装载 O(总量) → O(offset+页大小)；DDL 粉丝方向复合索引 `idx_followed_user_user`；集合完整性由 `partial:` 标记表达 |

**本周期明确不做（反面清单，与"纳入"同等重要）**：

| 不做 | 原因 / 去向 |
| ---- | ---- |
| `feed` / `search` / `profile` 的**后端**大分页（域级上限 + 域级信封） | 2026-09-20 T11 窗口用户拍板推后 → **另立 T19**（三处内容列表的**前端**迁移仍在 T11-B 内完成），编号不悬空 |
| 删除 `pageSize` 参数 / 由后端硬忽略该参数 | 会摧毁 pytest 的小信封跨页验证能力（`test_comment_paging` 用 `pageSize=1/3`、`test_follow_list` 用 `page_size=1` 验证页间不重不漏；信封固定 200 需造 200+ 行数据）→ 改采"保留参数 + 域级 `defaultSize`" |
| offset → 游标（keyset 分页） | 属 **feed 流方向**；前端去重只作漂移缓解，不替代正确性 |
| `getFollowingIds` 的 feed 全量关注 ids 读路径 | 与 R-01（索引全量读）同型，明确保留 |

***

## 五、本周期范围与边界（指针节，范围一律以 4.3 为准）

> 本节**不单独维护范围清单**，避免与 4.3 口径分叉：本周期做什么、不做什么（含反面清单），一律以 **4.3 为唯一落点**。本节只保留 4.3 不覆盖的两类边界：

- **留池未排**：见二与 `UNPLANNED_ISSUES.md` **当前有效项 = U-11 / U-17 / U-22**（`U-22` 为 2026-09-20 T12 窗口新登记）；其余原池项去向均已落定（**`U-07`+`U-19`→T14 ✅ 已完成 2026-09-20**、**`U-14`→T12 ✅ 已完成 2026-09-20**、`U-16`→T13 ✅ 已完成 2026-09-20、`U-18`→T11-C ✅、`U-20`→T10、`U-21` 随 T11-C-2 关闭、`U-15` 属日志体系改造方向）。
- **禁止（沿用技术红线）**：Spring/SpringBoot/MyBatis；擅改 `@WebServlet` URL、web.xml、IoC 扫描；为实现便利改业务逻辑语义；不引入 MQ（异步用进程内线程池）。
