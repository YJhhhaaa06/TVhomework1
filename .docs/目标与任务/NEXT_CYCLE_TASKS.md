# 下一周期任务清单

> 关联文档：目标与任务/NEXT_CYCLE_NEEDS.md（决策唯一源；此文件为执行细节）
> 状态：**已拆任务（2026-09-17）**——R-03 拍板为「**缓存读路径治理**（authorName 冗余同步 + 批量装载合并 + 事务边界瘦身）」，本周期 **3 个任务 T1~T3**（草稿态）。四要素为骨架，**执行方案由执行窗口的 Agent 探索细化**（G5 允许回写）。
> 工作流：每个任务开独立窗口执行（一任务一窗口单独探索、修改、review）；"任务清单 + 需求与痛点"为窗口间唯一交接载体。
> 来源：第五期 `NEXT_CYCLE_NEEDS.md`——二节 R-01（authorName 冗余同步，结转 `260917/R-03`）+ 4.1 代码复查 N1（批量读逐 key 装载 DB 放大）/ N2（Feed/Profile 事务内嵌套缓存装载）。
> **本周期明确不做**：R-02 索引全量读本体、R-04 包层环、U-11 停机 DB 兜底推荐、U-12/U-13 懒加载分页范畴、feed 流改造——完整清单见 NEEDS 4.3。

---

## 一、周期约定（R-03 拍板后已校准）

| 编号 | 约定 | 内容 |
| ---- | ---- | ---- |
| G1 | 一任务一窗口一 commit（默认期望） | 每个任务开独立窗口，默认期望 1 个 commit；因任务内部依赖需拆多 commit 或小任务合并时，**在该任务详情标注**；commit message 强制带任务编号，本周期前缀 = **`refactor(cache-0N)`**（T1~T3 统一前缀，便于周期追溯；个别任务性质差异在 commit message 正文体现；引用任务一律用 `T1`~`T3`，不用 commit scope） |
| G2 | 开窗协议（输入） | 新窗口顺序读取：① `.docs/INDEX.md` → ② `目标与任务/NEXT_CYCLE_NEEDS.md`（**决策节必读**：C-#、R-01~R-04、4.3 本周期范围、4.1 的 N1/N2）→ ③ 本任务清单（一/二节约定，含 G11 质疑协议 + 当前任务） → ④ 常青文档（`常青/CURRENT_ARCHITECTURE.md` 六.Redis 设计 + `常青/BUSINESS_FLOW.md` 相关章节）→ ⑤ 上一个任务 commit |
| G3 | 收窗协议（输出） | ① 跑测试与反馈：每任务 = `mvn compile` + 相关 JUnit + **相关端点 pytest 回归**；全量回归（`pytest all`）在 T2（基建改动面大）与收尾任务各跑一次 ② 勾选任务清单状态 → ③ 涉及架构/业务改动时同步常青文档 → ④ 提交 |
| G4 | commit 语义闭环 | 代码改动 + 其对应常青文档更新 + 任务清单勾选进同一 commit；message 强制带任务编号 |
| G5 | 超范围暂停规则 | 执行中发现需求歧义、或任务实际远超预期（判定锚：触及清单未列模块 / 需新依赖 / 需改业务语义 / 改动面较预估倍增）→ 停在第一个决策点，回写任务清单（拆/改）并按 G11 分级登记，不得硬扛、不得擅自扩大范围 |
| G6 | 评审与返工 | 评审以"任务验收标准 + 测试结果"为准；返工记录在任务清单；连续返工 ≥2 次 → 执行 Agent 停手并登记"建议返回周期设计重新评估"，由**用户**裁决是否重开周期设计 |
| G7 | 决策唯一源 | `NEXT_CYCLE_NEEDS.md` 决策节为唯一决策源；窗口内发现新决策 → 回写该节并标记"已定/待定"，不许自行拍板 |
| G8 | 分支与合并 | **开分支 / 合并回 integration / 合入 master 均由用户手动执行**；任务窗口只负责本任务的代码、测试与 commit（G1），不自行创建/切换分支、不合并 |
| G9 | DDL 备份 | **本周期无 DDL**（缓存层改造：R-01 走既有 user 表 username 字段与内容缓存失效、N1 复用既有 content/media 表查询、N2 仅移动读调用位置，均不涉及表结构）。若出现例外 → 执行前先备份库结构与建表语句到 `.docs/DBbackups/`，并向用户报备 |
| G10 | 脚本规范 | 脚本一律 Python；临时一次性脚本放 `temp_script/`，长期复用/自动化脚本放 `tools/`（工具统一入口 `tools/tv.py`） |
| G11 | 质疑协议 | 执行 Agent 对 Why 层（NEEDS 真实性/必要性）与 How 层（任务/验收可操作性）**有质疑权、亦有报告义务**：四时点触发 + L1~L4 分级动作（完整协议见二节"质疑协议"）；**裁决权永远在用户**（G7 决策唯一源不破） |

---

## 二、任务清单理念与任务模板

> **任务清单理念**：本清单只回答"**要做什么、不做什么**"，**不提前过度详细设计**——四要素是骨架，具体执行方案由执行窗口的 Agent 探索细化。
> **红线边界的目的是"防跑偏"，不是把 Agent 限制死**：因此只列"明显越界"的项，不穷举做法；执行中若发现红线本身阻碍了正确做法，按下方"红线措辞约定"先申请再动手。
> 反面清单同样是清单的一部分：**"明确不做"要写出来**，避免 Agent 顺手扩张。

> **任务模板（每任务必含四要素）**：入口线索（从哪找）+ 红线边界（别碰什么）+ 强制探索步骤（动手前先确认什么）+ 验收（怎么算完成）。执行 Agent 允许动态调整，但**调整前先回写任务清单/需求文档（G5/G7），再动手**。

> **红线措辞约定（延续）**：红线只列"明显越界"的项，作用是**防跑偏，不是把执行 Agent 限制死**——不穷举做法、不做"一刀切禁止"。
> 若执行中发现**某条红线会阻碍正确做法**（过紧、过窄、或已不适用）：不允许硬扛，也不允许自行放开，**先向用户说明理由并申请调整**；获批准后按新口径动手，未获批准则维持原红线。

> **编号引用约定（延续）**：禁裸编号引用已归档周期元素（引用一律写 `<周期>/<编号>`，如 `260917/R-03`）；裸编号仅指本档内部定义的元素（`T1`~`T3`）与 `NEXT_CYCLE_NEEDS.md` 内部元素（C-# / R-01~R-04 / N1~N2）。执行中发现引用歧义 → 回写清单用文字澄清，不得自行猜义。

> **质疑协议（G11，2026-09-15 确立，历周期沿用）**：执行 Agent 对 **Why 层**（NEEDS 真实性/必要性）与 **How 层**（任务可行性/验收可操作性）均有质疑权，亦有报告义务；**裁决权永远在用户**（G7 决策唯一源不破）。四个触发时点：
> ① **开窗阅读**——文档内部矛盾（不依赖代码事实即可判定：交叉引用错乱、两节口径冲突）；
> ② **动手前**——强制探索步骤第 (0) 项复核本任务对应 NEEDS 编号的证据，发现不成立；
> ③ **探索中**——代码事实与 NEEDS 描述矛盾 / 需求疑似已被现有实现覆盖 / 触发 G5 判定锚；
> ④ **验收时**——验收标准全过但 4.1 对应痛点场景仍未消除。
>
> 分级动作：

> | 级别 | 触发情形 | 动作 |
> | -- | -- | -- |
> | L1 仅记录 | 疑问不影响本任务语义与范围（含文档内部小矛盾） | 写进执行回写，评审时一并过，不单独打断 |
> | L2 带疑继续 | 疑问影响后续任务/验收解读，但不影响本任务（不改语义、不扩范围） | 在 NEEDS 对应条目标"待裁决"，照清单执行 |
> | L3 暂停 | 前提证伪 / 需求疑似已满足 / 需改语义或扩范围才能继续 | 停在第一个决策点（G5 措辞），登记质疑记录，待用户裁决 |
> | L4 申请调整 | 红线/约定阻碍正确做法 | 沿用"红线措辞约定"：说明理由申请，未批准维持原口径 |
>
> **质疑记录格式**（Why 层落 `NEXT_CYCLE_NEEDS.md` 4.0；How 层与 L1 落本清单"执行回写"）：
>
> **质疑 <N#/R-##/C-#>（<日期>，T# 窗口，L<级别>）**：触发：<开窗阅读/动手前复核/探索中/验收时>；证据：<文件:行 / 复现结果 / 日志>；主张：<前提证伪/已满足/必要性存疑/内部矛盾/验收不可操作>；建议：<废弃/降级留池/改范围/仅记录>；当前动作：<已暂停/带疑继续>；用户裁决：<待裁决>。
>
> **质疑成立后的去向**：对应 R/N 编号显式流转——转 `UNPLANNED_ISSUES.md` 留池 / 结转下周期 NEEDS 二节 / 在变更记录登记废弃原因；任务清单中"搁置"状态的 T# 同理，对应编号不得悬空（G11）。**两条禁止**：不许为过验收绕过痛点本质（见任务模板"验收"）；不许以质疑为由擅自改语义/扩范围。

---

## 三、任务总览（本周期 3 任务）

> **DDL 标注（G9 要求）**：本周期**无 DDL**。

| 编号 | 标题 | 对应候选 | 依赖 | 验收关键（动态） | 期望 commit 主题 | 状态 |
| -- | -- | ---- | -- | -------- | ------------ | -- |
| T1 | authorName 冗余同步（改名后内容缓存同步机制） | R-01 | —（独立，可并行） | 方案经用户拍板（G7：无改名接口现状——补改名接口 or 仅机制预留）；改名后内容缓存 authorName 一致性可验证；对外行为零变化或按拍板口径 | `refactor(cache-01)` | 草稿 |
| T2 | 批量缓存读装载合并（N1：批量 miss/降级逐 key 独立 DB 装载 → 批量装载） | N1 | T1 可并行 | 批量 miss 装载 DB 查询收敛（前后对比落执行回写）；冷数据页 DB 查询次数显著下降；三态/续期/降级语义不变；`pytest all` 全量绿 | `refactor(cache-02)` | 草稿 |
| T3 | Feed/Profile 缓存读移出业务事务（N2：事务内嵌套缓存装载） | N2 | T2 可并行（若 T2 改了批量读签名则软依赖） | 缓存批量读不再落在 DB 事务回调内；冷缓存高峰无"事务持连接 + 装载再取连接"叠加；对外行为零变化 | `refactor(cache-03)` | 草稿 |

> 状态取值：草稿 / 待执行 / 执行中 / 已完成 / 搁置。搁置的 T# 必须注明其"对应候选"编号去向（转 `UNPLANNED_ISSUES.md` / 结转下周期 NEEDS 二节），不得悬空（G11）。

> **顺序理由**：T1 独立（authorName 域：User 改名 vs 内容缓存，不碰批量读/事务路径），可先行或并行；T2 基建改动面大（`CacheAside.getBatch` 装载粒度 + ContentCache loader），先于 T3 跑通批量装载收敛模式；T3 只动调用位置（FeedService/ProfileService 把缓存读移出事务），若 T2 未改签名则完全独立。**T1 开工前须方案拍板（G7：改名接口缺失现状下的处置口径）**，拍板点已写入任务详情。

> **回填要求**：任务执行后在本节与"四、任务详情"同步状态与"执行回写"；拆/改任务必须在"对应候选"列保留 NEEDS 编号，保持 Why→How 可追溯。

---

## 四、任务详情

> **共通注（本周期通用）**：缓存仅作加速器，**任何缓存失败不得导致业务失败**（`260913/4.2` 确立，历周期不破）；T2/T3 为**行为零变化**改动——key 命名、三态语义、空标记 TTL、降级语义、CacheStats 打点口径一概不变；不引入新的第三方依赖。技术红线沿用——禁 Spring/SpringBoot/MyBatis、不擅改 `@WebServlet` URL / web.xml / IoC 扫描、不为实现便利改业务逻辑语义；不引入 MQ。
> **红线口径（与二节"红线措辞约定"一致）**：下面各任务的"红线边界"只列"明显越界"的项，作用是**防跑偏**、不穷举做法；若某条红线会阻碍正确做法（过紧 / 过窄 / 已不适用）→ 说明理由**申请调整**，不许硬扛、也不许自行放开。

### T1 authorName 冗余同步

* **入口线索**：`content/model/cache/ContentCacheDTO.authorName`（[ContentCacheDTO.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/model/cache/ContentCacheDTO.java#L16-L26)）；`user/model/entity/User.userName` + `user.dao.UserDao`（无改名方法）；`content.service.ContentCache.loadContentFromDb`（authorName 来源 JOIN user 表）。目标：**用户改名后内容缓存仍带旧 authorName 的一致性问题**——先确认系统当前是否有改名/更新用户名入口，再定同步方案（NEEDS 二节 R-01：当前无改名接口，属潜在项）。
* **红线边界**：不做"顺手"补一整套个人资料编辑（仅用户名）；不引入 MQ；不改内容缓存三态/降级/TTL 语义；若拍板"补改名接口"——不改 `@WebServlet` URL / web.xml / IoC 扫描（新接口按既有 Servlet 注册规范落位）。
* **强制探索步骤**：动刀前先 (0) 复核 R-01 证据仍成立（全文检索 user 域/service 是否有 updateUsername/changeName/改名接口；确认 `UserDao` 无改名方法）(1) 盘点 authorName 的消费链（`ContentCacheDTO` → `toContentVO/toDetailVO` → 推荐/Feed/Profile/详情/搜索，确认全部来自内容缓存）(2) 探索两种候选方案的成本与一致性口径：A 补用户名修改接口（DB UPDATE + 级联失效该用户全部内容 key 或按 authorId 索引批量重载）；B 仅做缓存侧机制预留（不改名接口存在前无触达路径，可能结论=留池待改名需求）(3) **若协商后须动 DB/接口契约 → 落方案前回写 NEEDS 4.0 待用户拍板（G7），不得自行定调**——若清单未覆盖 → 回写本文档再动手。
* **验收**：无论方案 A/B，拍板结果回写 NEEDS 4.0（G7 决策留痕）；若 A——改名接口 + 改名后内容缓存 authorName 一致性可验证（运行时：改名 → 内容详情/推荐/Feed 返回新名，篡改探针或直接断言）；若 B——结论=现状无触达路径、标注留池待改名需求，归档评估结论即可。JUnit + 相关端点 pytest 绿；无 DDL。
* **执行回写（待执行）**：`<完成后追加>`。

---

### T2 批量缓存读装载合并

* **入口线索**：`cache/CacheAside.getBatch`（[CacheAside.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/cache/CacheAside.java#L186-L263)——miss 循环逐个 `singleFlight.get` + `loadDegraded` 逐 key；整批降级逐 key loader）；`content/service/ContentCache.getContentsBatch`（[ContentCache.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ContentCache.java#L117-L132)——loader = `loadContentFromDb` = `findContent`+`findMedia` 两趟事务查询/key）。目标：**N1**——批量 miss/降级时逐 key 独立 DB 装载（每 key 2 次事务查询）收敛为批量装载（如复用三期 T5 的 `findMediaByContentIds` 批量媒体 + 批量内容查询 + 内存分组），冷数据页 DB 查询从"逐条 2 次"降到"常量趟数"。
* **红线边界**：**不改 `CacheAside.getBatch` 对外签名与三态/续期/降级/打点语义**（批量粒度、miss 单飞去重保持不变，仅把"逐 key loader"换成"批量 loader 一次性拉取"）；不动 Like/Follow 域批量（其 loader 已是批量 answer，不是本痛点）；不改 key 命名/TTL；不做"顺手"把 SetCache 批量也改掉。
* **强制探索步骤**：动刀前先 (0) 复核 N1 证据仍成立（重读 `getBatch` miss/降级两段与 `loadContentFromDb`，确认逐 key 两趟查询）(1) 确认 `ContentMediaDao.findMediaByContentIds`（三期 T5 已存在）可复用，无批量 content 查询则评估新增 `ContentDao.findContentsByIds` 的形态与返回（保持 loader 契约：DatabaseException 语义）(2) 探索批量装载挂点：getBatch 内 loader 批量化的最小侵入形态（如 miss 子集一次 loader 返回 Map）vs 保持逐 key 但 DB 层批量（每 key 仍是独立 loader 调用、改 ContentCache 内部用批量 DAO + 内存缓存）——**选型在窗口内探索并回写**（3) 确认 Feed/Profile 的 getContentsBatch 调用点无需改签名（T3 依赖判断）——若清单未覆盖 → 回写本文档再动手。
* **验收**：冷数据页（如 10 条全 miss）DB 查询趟数前后对比落执行回写（目标：从 ≈20 次事务查询收敛到常量趟数）；三态/续期/空标记/降级/单飞语义零变化（相关单测断言不变或等价平移）；`pytest all` 全量绿（本周期基建改动面回归点）；JUnit 全绿无回归。
* **执行回写（待执行）**：`<完成后追加>`。

---

### T3 Feed/Profile 缓存读移出业务事务

* **入口线索**：`content/service/ProfileService.getProfile`（[ProfileService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/ProfileService.java#L55-L98)——`transactionTemplate.execute` 回调内 L70 `getContentsBatch`、L82 `batchIsContentLiked`）；`content/service/FeedService.getFeed`（[FeedService.java](file:///d:/javaproject/VideoPlatform/TVhomework1/src/main/java/com/itheima/content/service/FeedService.java#L49-L87——L61/L73 同）；`util/TransactionTemplate`（无传播语义，每次 execute 独立取连接）。目标：**N2**——缓存批量读（含 miss 装载）从 DB 事务回调内移到事务外，消除"外层事务持连接 + 装载再取新连接"的叠加与事务持有期拉长；与 FollowCache 计数读"事务外"口径对齐。
* **红线边界**：不动 DB 查询逻辑（`findContentIdsByUser`/`countContentByUsers`/`findContentIdsByUsers` 仍在事务内）；不改缓存类签名（若 T2 改了签名则跟随适配，无则零改动）；不动 `content:index`/推荐路径（N1 已由 T2 治）；不改接口 URL/参数/返回结构（对外行为零变化）。
* **强制探索步骤**：动刀前先 (0) 复核 N2 证据仍成立（确认 `getContentsBatch`/`batchIsContentLiked` 确实在事务回调内，且自研事务模板无传播语义）(1) 探索最小改动形态：把 `getContentsBatch`+`batchIsContentLiked` 从 `transactionTemplate.execute` 回调内**上提到事务外**（DB 结果先取出 → 事务外批量读缓存 + 批量点赞状态 → 组装 VO），确认数据依赖（isLiked 依赖 contentVOList 由缓存产生的 id 列表，注意懒扫描 vs 预取的分层）(2) 确认两个 Service 的事务块内不再有任何缓存调用（grep 复核）(3) 若 T2 已改批量读签名或 ContentCache 暴露批量装载新 API → 先对齐 T2 再动——若清单未覆盖 → 回写本文档再动手。
* **验收**：`ProfileService.getProfile` / `FeedService.getFeed` 事务回调内**零缓存调用**（grep 实证）；冷缓存高峰不再出现"事务持连接 + miss 装载嵌套取连接"（代码路径证明即可，可选运行时观测）；三态/降级语义零变化；相关端点 pytest 绿。
* **执行回写（待执行）**：`<完成后追加>`。

---

## 五、变更记录

| 日期 | 版本 | 内容 |
| ---- | ---- | ---- |
| 2026-09-17 | 0.1 | 新建本文档（按 NEEDS R-03 拍板拆分）：T1（R-01 authorName 冗余同步，方案开工前拍板）/ T2（N1 批量缓存读装载合并）/ T3（N2 Feed/Profile 缓存读移出业务事务）——一至四节按模板就位，四要素为骨架待执行窗口细化 |