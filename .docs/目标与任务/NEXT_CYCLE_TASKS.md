# 下一周期任务清单

> 关联文档：目标与任务/NEXT_CYCLE_NEEDS.md（决策唯一源；此文件为执行细节）  
> 状态：**已拆任务（2026-09-15）**——R-10 拍板为「**缓存体系综合改造**（结构收敛 + 装载反转 + 读路径优化 + 补缺口）」，本周期 **8 个任务 T1~T8**（草稿态）。四要素为骨架，**执行方案由执行窗口的 Agent 探索细化**（G5 允许回写）。  
> 工作流：每个任务开独立窗口执行（用户 2026-09-15 定调：能拆的任务均排进本周期，一任务一窗口单独探索、修改、review）；"任务清单 + 需求与痛点"为窗口间唯一交接载体。  
> 来源：260914-cache-hardening 归档周期结转（NEEDS 二/三表 R-01~R-12）+ `UNPLANNED_ISSUES.md` 留池（U-09/U-11）+ **2026-09-15 代码复查新发现（NEEDS 4.1 的 N1~N3）**。  
> **本周期明确不做**：D 方向 feed 流改造（延后第五期）、U-11 停机 DB 兜底推荐（行为变更未拍板）、R-02/R-03/R-04 本体/R-05、R-11/R-12 —— 完整清单见 NEEDS 4.3。

---

## 一、周期约定（R-10 拍板后已校准）

| 编号  | 约定                   | 内容                                                                                                                                                                                       |
| --- | -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| G1  | 一任务一窗口一 commit（默认期望） | 每个任务开独立窗口，默认期望 1 个 commit；因任务内部依赖需拆多 commit 或小任务合并时，**在该任务详情标注**；commit message 强制带任务编号，本周期前缀 = **`refactor(cache-0N)`**（T1→`cache-01` … T8→`cache-08`；T7 为小项合并、T4/T6 含方案拍板——个别任务的性质差异在 commit message 正文体现，前缀统一便于周期追溯；引用任务一律用 `T1`~`T8`，不用 commit scope）              |
| G2  | 开窗协议（输入）             | 新窗口顺序读取：① `.docs/INDEX.md` → ② `目标与任务/NEXT_CYCLE_NEEDS.md`（**决策节必读**：C-#、R-01~R-12、4.3 本周期范围、4.1 的 N1~N3）→ ③ 本任务清单（一/二节约定，含 G11 质疑协议 + 当前任务） → ④ 常青文档（`常青/CURRENT_ARCHITECTURE.md` 六.Redis 设计 + `常青/BUSINESS_FLOW.md` 3.1 缓存机制）→ ⑤ 上一个任务 commit |
| G3  | 收窗协议（输出）             | ① **跑测试与反馈**：每任务 = `mvn compile` + 相关 JUnit + **相关端点 pytest 回归**；**`pytest all` 全量在 T3（收口完成第一个域闭环）、T5（读路径影响面大）、T8（收尾）各跑一次**；测试执行与回传依实际窗口环境安排，不指定角色 ② 勾选任务清单状态 → ③ 涉及架构/业务改动时同步常青文档 → ④ 提交                                |
| G4  | commit 语义闭环          | 代码改动 + 其对应常青文档更新 + 任务清单勾选进同一 commit；message 强制带任务编号                                                                                                                                        |
| G5  | 超范围暂停规则              | 执行中发现需求歧义、或任务实际远超预期（判定锚：触及清单未列模块 / 需新依赖 / 需改业务语义 / 改动面较预估倍增）→ 停在第一个决策点，回写任务清单（拆/改）并按 G11 分级登记，不得硬扛、不得擅自扩大范围 |
| G6  | 评审与返工                | 评审以"任务验收标准 + 测试结果"为准；返工记录在任务清单；连续返工 ≥2 次 → 执行 Agent 停手并登记"建议返回周期设计重新评估"，由**用户**裁决是否重开周期设计 |
| G7  | 决策唯一源                | `NEXT_CYCLE_NEEDS.md` 决策节为唯一决策源；窗口内发现新决策 → 回写该节并标记"已定/待定"，不许自行拍板 |
| G8  | 分支与合并                | **开分支 / 合并回 integration / 合入 master 均由用户手动执行**；任务窗口只负责本任务的代码、测试与 commit（G1），不自行创建/切换分支、不合并 |
| G9  | DDL 备份               | **本周期无 DDL**（缓存层改造：R-01 读 user 表现有计数列、R-08 用现有 like 表，均不涉及表结构；已在任务总览显式标注）。若出现例外 → 执行前先备份库结构与建表语句到 `.docs/DBbackups/`，并向用户报备 |
| G10 | 脚本规范                 | 脚本一律 Python；临时一次性脚本放 `temp_script/`，长期复用/自动化脚本放 `tools/`（工具统一入口 `tools/tv.py`） |
| G11 | 质疑协议                 | 执行 Agent 对 Why 层（NEEDS 真实性/必要性）与 How 层（任务/验收可操作性）**有质疑权、亦有报告义务**：四时点触发 + L1~L4 分级动作（完整协议见二节"质疑协议"）；**裁决权永远在用户**（G7 决策唯一源不破） |

---

## 二、任务清单理念与任务模板

> **任务清单理念**：本清单只回答"**要做什么、不做什么**"，**不提前过度详细设计**——四要素是骨架，具体执行方案由执行窗口的 Agent 探索细化。
> **红线边界的目的是"防跑偏"，不是把 Agent 限制死**：因此只列"明显越界"的项，不穷举做法；执行中若发现红线本身阻碍了正确做法，按下方"红线措辞约定"先申请再动手。
> 反面清单同样是清单的一部分：**"明确不做"要写出来**，避免 Agent 顺手扩张。

> **任务模板（每任务必含四要素）**：入口线索（从哪找）+ 红线边界（别碰什么）+ 强制探索步骤（动手前先确认什么）+ 验收（怎么算完成）。执行 Agent 允许动态调整，但**调整前先回写任务清单/需求文档（G5/G7），再动手**。

> **红线措辞约定（延续）**：红线只列"明显越界"的项，作用是**防跑偏，不是把执行 Agent 限制死**——不穷举做法、不做"一刀切禁止"。  
> 若执行中发现**某条红线会阻碍正确做法**（过紧、过窄、或已不适用）：不允许硬扛，也不允许自行放开，**先向用户说明理由并申请调整**；获批准后按新口径动手，未获批准则维持原红线。

> **编号引用约定（延续）**：禁裸编号引用已归档周期元素（引用一律写 `<周期>/<编号>`，如 `260914/R-02`）；裸编号仅指本档内部定义的元素（`T1`~`T8`）与 `NEXT_CYCLE_NEEDS.md` 内部元素（C-# / R-01~R-12 / N1~N3）。执行中发现引用歧义 → 回写清单用文字澄清，不得自行猜义。

> **质疑协议（G11，2026-09-15 确立）**：执行 Agent 对 **Why 层**（NEEDS 真实性/必要性）与 **How 层**（任务可行性/验收可操作性）均有质疑权，亦有报告义务；**裁决权永远在用户**（G7 决策唯一源不破）。四个触发时点：
> ① **开窗阅读**——文档内部矛盾（不依赖代码事实即可判定：交叉引用错乱、两节口径冲突）；
> ② **动手前**——强制探索步骤第 (0) 项复核本任务对应 NEEDS 编号的证据，发现不成立；
> ③ **探索中**——代码事实与 NEEDS 描述矛盾 / 需求疑似已被现有实现覆盖 / 触发 G5 判定锚；
> ④ **验收时**——验收标准全过但 4.1 对应痛点场景仍未消除。
>
> 分级动作：
>
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
> **质疑成立后的去向**：对应 R/N 编号显式流转——转 `UNPLANNED_ISSUES.md` 留池 / 结转下周期 NEEDS 二节 / 在变更记录登记废弃原因；任务清单中"搁置"状态的 T# 同理，对应编号不得悬空。**两条禁止**：不许为过验收绕过痛点本质（见任务模板"验收"）；不许以质疑为由擅自改语义/扩范围。

```markdown
### T1 任务标题

* **入口线索**：…
* **红线边界**：（只列"明显越界"的项，作用是防跑偏；若某条红线会阻碍正确做法 → 先说明理由申请调整，获批准后按新口径动手）
* **强制探索步骤**：动刀前先 (0) 复核本任务对应 NEEDS 编号（N#/R-##）的证据仍成立（G11——不成立 → L3 暂停登记质疑） (1) 检索… (2) 确认… (3) 若清单未覆盖 → 回写本文档再动手
* **验收**：…（验收通过 ≠ 目的达成：标准全过但痛点场景未消除 → 按 G11 登记质疑，不得直接标已完成）
```

---

## 三、任务总览（本周期 8 任务）

> **DDL 标注（G9 要求）**：本周期**无 DDL**——8 个任务均只动 Java 代码与配置，不涉及表结构变更。

| 编号 | 标题 | 对应候选 | 依赖 | 验收关键（动态） | 期望 commit 主题 | 状态 |
| -- | -- | ---- | -- | -------- | ------------ | -- |
| T1 | 基建：Set 缓存组件收敛 | U-09、N3 | — | 新组件覆盖原生 Set 三态读/回填/批量/降级装载，单测齐全；**此时尚无业务接入，全量行为零变化** | `refactor(cache-01)` | 已完成 |
| T2 | LikeCacheService 收口 + 孪生合并 | U-09、N3 | T1 | content/comment 成对方法合并、扫描/回填/降级走基建；行为与统计打点口径零变化；JUnit + 相关 pytest 绿 | `refactor(cache-02)` | 已完成 |
| T3 | FollowCache 收口 | U-09、N3 | T1 | 接入基建；MULTI 双写等 follow 特有逻辑语义保持；**`pytest all` 全量绿**（收口后第一个全量回归点） | `refactor(cache-03)` | 已完成 |
| T4 | 点赞成员装载反转 | R-08 | T2（硬） | `content:likeSet`/`comment:likeSet` → `user:likeSet`/`user:commentLikeSet`（内容+评论全反转，2026-09-15 拍板）；**方案开工前经用户拍板**（G7）；对外行为零变化；装载量前后对比已落执行回写 | `refactor(cache-04)` | 已完成 |
| T5 | 推荐读路径优化 + 索引重建退避 | N1、N2、R-04、R-07、U-11 加重面 | —（独立，可并行窗口） | 惰性探测（探测量前后对比）；停机期间不再逐请求全表查询（可复现验证）；R-07 评估结论回写 NEEDS；**`pytest all` 全量绿** | `refactor(cache-05)` | 已完成 |
| T6 | 关注/粉丝计数入缓存 | R-01 | T3（软） | Profile 计数命中缓存；follow/unfollow 后计数一致性方案拍板落地（G7）；SCARD 冷 set 返 0 的坑有结论 | `refactor(cache-06)` | 已完成 |
| T7 | 小项打包：JSON 兼容 + 批量续期补测 | R-09、R-06 | —（独立） | Jackson 关 `FAIL_ON_UNKNOWN_PROPERTIES`（含多余字段兼容单测）；批量续期断言补齐；**G1 标注：小任务合并为一个 commit** | `refactor(cache-07)` | 已完成 |
| T8 | 收尾 | 全周期 | T1~T7 | T1~T7 无残留；`@WebServlet`/web.xml/IoC 原样；常青文档同步；覆盖率地图 rerun 无回归；**`pytest all` 全量绿** | `refactor(cache-08)` | 已完成 |

> 状态取值：草稿 / 待执行 / 执行中 / 已完成 / 搁置。搁置的 T# 必须注明其"对应候选"编号去向（转 `UNPLANNED_ISSUES.md` / 结转下周期 NEEDS 二节），不得悬空（G11）。

> **顺序理由**：T1 基建先行——收敛面先就位，域缓存才有统一落点；T2 先于 T3——Like（628 行，含孪生合并，改造最深）先验证收敛模式，T3 Follow（524 行，同构）随后；T4 硬依赖 T2——key 反转在合并后的单实现上做，改动面最小（若先反转后收口等于在旧 5 份实现上做一遍再搬一遍）；T5 独立（ContentCache 推荐路径，不碰域缓存类），可与其他任务并行开窗；T6 软依赖 T3——follow 域写路径收口后再加计数维护最干净；T7 独立小项随时可做；T8 收尾统一回归与文档。**T4 与 T6 开工前均须方案拍板（G7），两个任务的拍板点已写入任务详情**。

> **回填要求**：任务执行后在本节与"四、任务详情"同步状态与"执行回写"；拆/改任务必须在"对应候选"列保留 NEEDS 编号，保持 Why→How 可追溯。

---

## 四、任务详情

> **共通注（本周期通用）**：缓存仅作加速器，**任何缓存失败不得导致业务失败**（`260913/4.2` 确立，历周期不破）；T1~T3 收口为**行为零变化重构**——key 命名、三态语义、空标记 TTL、降级语义、CacheStats 打点口径一概不变；不引入新的第三方依赖。技术红线沿用——禁 Spring/SpringBoot/MyBatis、不擅改 `@WebServlet` URL / web.xml / IoC 扫描、不为实现便利改业务逻辑语义；不引入 MQ。  
> **红线口径（与二节"红线措辞约定"一致）**：下面各任务的"红线边界"只列"明显越界"的项，作用是**防跑偏**、不穷举做法；若某条红线会阻碍正确做法（过紧 / 过窄 / 已不适用）→ 说明理由**申请调整**，不许硬扛、也不许自行放开。

### T1 基建：Set 缓存组件收敛

* **入口线索**：`cache/CacheAside`（JSON String 的 Cache-Aside 封装，无原生 Set 支持）；待收敛的重复面——`FollowCache.scanSet`/`writeSet`/`getSetMembers`/`batchIsFollowing` 扫描段、`LikeCacheService.scanLikeSet`/`writeContentLikers`/`writeCommentLikers`/批量扫描段（NEEDS 4.1 N3 的证据清单）。目标：原生 Set 的"三态读（含单成员判定）/全量读/回填（含空标记）/批量判定/降级单飞装载"收敛为 cache 包内一处组件。
* **红线边界**：不改 `CacheAside` 既有对外签名（他处仍在用，新组件平行存在或按窗口探索定形态）；不改 key 命名/三态语义/空标记 TTL/降级语义；不做"顺手全量收口各域"（T2/T3 的事）。
* **强制探索步骤**：动刀前先 (0) 复核 N3 证据仍成立（G11） (1) 逐方法比对 5 份实现的差异点（成员类型 String/Long、TTL 来源、打点 key、排序语义）确认泛型化参数化可行 (2) 定组件边界与 API 面（单成员/批量/全量/回填/降级装载各暴露什么）(3) loader 样板（transactionTemplate + SQLException→ServerException 包装）是否随组件收敛——若清单未覆盖 → 回写本文档再动手。
* **验收**：新组件单测覆盖三态/回填/降级/批量（含空标记与续期语义）；**业务代码零改动**（此时尚无接入，全量 JUnit + pytest 基线不变）。
* **执行回写（2026-09-15，`refactor(cache-01)` 已落地）**：N3 证据复核**成立**（FollowCache/LikeCacheService 逐字重复面 2.1 表，G11 第 0 步过）；新增 `cache/SetCache`（@Component，330 行，六 API 面：`isMember`/`getMembers`/`batchIsMember`（单 set 多成员·Follow 形态）/`batchKeysIsMember`（多 set 单成员·Like 形态）/`writeSet`/`loadViaSingleFlight`）+ `SetCacheTest`（30 例）。设计决策：成员类型固定 long（两域均 Long，泛型化属过度设计）；TTL 调用方传参、探针续期/回填精确 TTL 无抖动；组件不排序（follow 域排序留 T3 包装，三路径 hit-data/miss/降级须同一包装点排序防热冷读波动）；批量回填写 best-effort、DB 答案（dbAnswer 不得返回 null）失败上抛（**差异记录 L2**：当前 LikeCacheService 批量回填失败上抛，T2 收口后变 best-effort，T2 回写对照）；loader 样板（transactionTemplate+DAO 包装）不随组件收敛；空分支复用 `cacheAside.markEmpty`（exists 守卫同源）。验证：mvn compile 过；全量 JUnit **395 例全绿**（基线 365，三期 T6 收尾口径 + SetCacheTest 30；6.12 记录的 362 为三期 T5 末口径，T6 已增至 365）；pytest all **124 passed** 无回归（基线不变）；业务代码零改动（仅新增文件 + 文档）；常青文档同步见 6.13；subagent review 通过（无🔴，🟡 5 条全部落实：数字口径修正/4.2 测试计数更新/dbAnswer 契约与 getMembers 排序交接提示入 javadoc/并发用例维持既有范式）。

### T2 LikeCacheService 收口 + 孪生合并

* **入口线索**：`like/service/LikeCacheService`（628 行）——content/comment 成对方法（load×4 / write×2 / backfill×2 / degrade×2 / batch×2）+ 与 T1 组件重复的扫描/回填/降级装载。目标：读路径全部走 T1 组件，成对方法合并为单实现（id 维度参数化）。
* **红线边界**：行为零变化——key 命名、三态顺序、打点口径（CacheStats 分域与 key 粒度）、TTL、降级语义不变；**Lua 条件写 4 方法不在收口面**（`260914` 三期 T4-③ 已原子化，无重复，写路径不动）；不动 `LikeService` 业务调用方签名。
* **强制探索步骤**：动刀前先 (0) 复核 N3 证据 (1) 孪生差异盘点（DAO 方法名、异常文案、统计 key 前缀）确认合并方式 (2) `backfillBatchContentLikers` 的"answer 查询与回填查询两套"结构在合并后如何保持（语义不变）(3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：成对方法消除（或收敛为参数化单实现）；JUnit 全绿 + 相关端点 pytest 无回归；`mvn compile` 过。
* **执行回写（2026-09-15，`refactor(cache-02)` 已落地）**：n3 证据复核**成立**（读/装载/批量孪生逐字重复，G11 第 (0) 步过）。**孪生差异盘点**：DAO 方法名（findLikerIds/count/findLiked… 成对）、日志前缀（内容/评论，`contentId=`/`commentId=` 标签统一为 `id=`，L1）、用户可见异常文案两域**逐字相同**、统计 key=数据 setKey（domainOf 两域均归 LIKE）、TTL 同源。**落点**：`isXxxLiked`/`batchIsXxxLiked` → `SetCache.isMember`/`batchKeysIsMember`（调用点 `idToKey`/`keyToId` 双 Map 桥接，不改 CacheKeys）；`getXxxLikeCount` 合并为 `getLikeCount(key, loader)`（CacheAside 语义含兜底 LOAD 打点不变）；loader 与批量 answer 收敛为 `DaoQuery<T>`/`BatchQuery` 参数化 helper（transactionTemplate + DAO lambda，"answer 查询与回填查询两套"结构保持）；删除 `scanLikeSet`/`writeXxxLikers`/`loadLikersViaSingleFlight`/`backfillBatch*`/`degradeBatch*`（628→420 行）；Lua 条件写 4 方法 / `deleteXxxLike` / `LikeService` 及业务调用方签名零改动。**L2 差异对照（T1 登记，本任务确认收敛落地）**：批量 miss 回填全量成员 loader DB 失败由"上抛 500"变 SetCache 契约 best-effort（DB 答案照常返回、仅记日志，4.2 缓存失败不得导致业务失败）；批量 dbAnswer 失败仍上抛；单成员 miss/降级 loader 失败仍上抛（无漂移）。其他零变化确认：三态顺序/续期（探针精确 TTL、空标记不续）/打点粒度（每 (key,决策)）/降级语义（单飞装载作答不写回 D4）逐位一致。验证：`mvn compile`（tv.py junit 链）过；JUnit **397 例全绿**（T1 基线 395 + 新增 2：批量回填 best-effort / 单成员 miss 装载失败仍上抛）；pytest all **124 passed** 无回归；常青文档同步见 6.14；subagent review 通过（无🔴，🟡 1 条=批量 loader 内部日志措辞词序变化——已在 NEEDS 4.0 登记为 L1 非契约项，无需修复）。关联：T1 执行回写"差异记录 L2：T2 回写对照"——已在本回写对照记录。

### T3 FollowCache 收口

* **入口线索**：`follow/service/FollowCache`（524 行）——`scanSet`/`writeSet`/`getSetMembers`/`batchIsFollowing` 扫描段与 `loadViaSingleFlight` 走 T1 组件。目标：同 T2 模式接入基建。
* **红线边界**：行为零变化（同 T2 口径）；**MULTI 双写、`probePair`、`invalidateKeysQuietly` 等 follow 特有写路径逻辑语义保持**（写路径是双 key 原子语义，不在收口面，是否部分复用由窗口探索定并回写）；`getSetMembers` 的排序语义（确定性顺序）不变。
* **强制探索步骤**：动刀前先 (0) 复核证据 (1) 参照 T2 落地模式适配差异点（双 Set、Loader 泛型、排序）(2) `batchIsFollowing` miss 回填的"answer + 回填两趟"结构保持 (3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：读路径走基建；`pytest all` 全量绿（收口后第一个全量回归点）；JUnit 全绿。
* **执行回写（2026-09-15，`refactor(cache-03)` 已落地）**：N3 证据复核**成立**（FollowCache 的 `scanSet`/`writeSet`/`getSetMembers`/`loadViaSingleFlight`/批量扫描段与 SetCache 逐字重复，G11 第 (0) 步过）。**落点**：`isFollowing` → `SetCache.isMember`；`batchIsFollowing` → `SetCache.batchIsMember`（单 set 多成员·Follow 形态，"answer 查询 + 回填全量两趟"结构由组件内保持，空输入早退守卫保留）；`getFollowingIds/getFollowerIds` → `SetCache.getMembers` + `sortIds` 唯一包装点统一升序（hit-data/miss/降级三路径一致，落实 SetCache.getMembers javadoc 排序交接提示）；构造注入 `SingleFlight`→`SetCache`；删除类内与 SetCache 重复的 `scanSet`/`writeSet`/`loadViaSingleFlight`/`getSetMembers`/`toSortedLongs`/`Loader` 接口（524→326 行）；关注/粉丝列表孪生 loader 收敛为 `DaoQuery<T>` 参数化 helper（日志 label 与用户可见异常文案逐字不变），批量 answer `loadFollowedIdsByUser` 单处使用保留独立方法（不过度设计）。**写路径零改动**：`cacheFollow`/`cacheUnfollow`/`probePair`/`invalidateKeysQuietly`/`Probe`/WRITE_FAIL 打点逐字保持（follow 特有双 key 原子语义不在收口面）。**行为对照**：批量 miss 回填 FollowCache 现状**已是 best-effort**（与 SetCache 契约一致，无 T2 那种"上抛→best-effort"的 L2 差异）；批量 dbAnswer 失败仍上抛；单成员 miss/降级 loader 失败仍上抛；空集回填统一 `cacheAside.markEmpty`（exists 守卫同源）；降级经 SetCache 单飞装载作答不写回（D4）。**L1 仅记录**：域类内部日志措辞（"关注状态缓存读失败"等）统一为 SetCache 措辞，非对外契约（T2 同先例）。验证：`mvn compile` + `mvn test-compile` 过；JUnit **397 例全绿**（FollowCacheTest 29 例平移适配注入 SetCache，stub 与断言逐字可平移，无增减）；**pytest all 124 passed 无回归**（本周期收口后第一个全量回归点）；常青文档同步见 6.15/2.18；subagent review 通过（无🔴）。关联：T1 执行回写"差异记录 L2：T2 回写对照"——follow 域批量回填本就 best-effort，无新增差异。

### T4 点赞成员装载反转

* **入口线索**：`LikeCacheService` 读路径（T2 合并后的单实现）+ `LikeService` 写路径 + `cache/CacheKeys`。目标：`content:likeSet:{contentId}`（装载量 = 内容点赞数）→ `user:likeSet:{userId}`（装载量 = 用户点赞数），与 `user:following` 同构；判断"我是否点过赞"的装载量与热门内容解耦。
* **红线边界**：**方案必须先经用户拍板再动手**（G7——key 语义反转涉及读/写/空标记/批量全线变化，不许窗口自行定）；对外行为零变化（接口返回、点赞计数 key `content:likeCount` 不动）；反转后不得保留旧 key 双写过渡（个人项目直接切换，旧 key TTL 自然回收）。
* **强制探索步骤**：动刀前先 (0) 复核 R-08 证据 (1) 确认全仓无"该内容的点赞者列表"查询面（反转后该能力自然消失——若存在需报备另案）(2) 设计 `user:likeSet` 的 TTL/空标记/批量判定/降级语义（参照 `user:following` 同构先例）与写路径 Lua 脚本的条件写改法 (3) **停决策点向用户报备方案拍板后再动手（G7）**。
* **验收**：单次"是否点赞"判断的装载量从"内容维度全量"变为"用户维度全量"（有前后对比）；点赞/取消/批量判定/计数对外零变化；JUnit 全绿 + 相关 pytest 无回归。
* **执行回写（2026-09-16，`refactor(cache-04)` 已落地）**：R-08 证据复核**成立**（10 万赞内容单次 isContentLiked miss 装载 10 万行点赞者、/start 推荐位 12 条冷 key 一次 12 倍量级、Redis 挂降级逐条全量查，G11 第 (0) 步过；全仓检索确认**无**「该内容的点赞者列表」对外查询面，反转后能力消失不需另案）。**方案拍板（G7）**：2026-09-15 用户裁决**内容+评论全反转**（`content:likeSet`/`comment:likeSet` → `user:likeSet:{userId}` Set&lt;contentId / `user:commentLikeSet:{userId}` Set&lt;commentId，与 `user:following` 同构）。**落点**：`CacheKeys` 新增 `userLikeSet`/`userCommentLikeSet`、删除旧两工厂，`domainOf` 在 `user:`→FOLLOW 之前插入 `user:commentLike`/`user:like`→LIKE（统计归位，T8 预期项前置）；读路径 `isXxxLiked` → `isMember`（loader=用户点赞全量 `findLikedContentIdsByUser`/`findLikedCommentIdsByUser`），批量 → **`batchIsMember` 单 set 多成员**（替代多 set 单成员 `batchKeysIsMember`——N 个内容 key ×(探针+判成员+续期) 收敛为 1 组探针 + N×SISMEMBER + 1 次续期，命令数与装载量双降；dbAnswer `findLikedContentIds`/`findLikedCommentIds` 原样复用）；写路径 **Lua 常量零改动、仅 KEYS/ARGV 换维度**（likeContent KEYS=[user:likeSet:{userId}, content:likeCount:{contentId}, empty:user:likeSet:{userId}]、ARGV=[contentId]，评论对称）；`deleteContentLike`/`deleteCommentLike` 改**仅失效计数 key**（成员残留不清理亦无害：物理删除 DB 点赞行一并删除、id 不复用/UI 无查询路径永不外显；软删隐藏点赞记录保留、恢复读自愈对齐）；删 628→420 基础上再收敛 420→**394 行**；DAO 新增 `findLikedContentIdsByUser`/`findLikedCommentIdsByUser`、删除 `findLikerIdsByContentId`/`findLikerIdsByCommentId`（死代码）。**装载量前后对比（验收证据）**：单次 isContentLiked miss/降级装载 = 内容点赞者数（爆款可 10 万行）→ **该用户点赞内容数**（单用户行为，与热度解耦）；/start 12 冷 key 批量 = 12 次内容全量点赞者装载 → **1 次用户全量点赞内容装载**；批量命令/趟 = 12×4 → **1 趟(2+N+1)**。已知取舍（拍板接受）：「单用户点赞量极大」极端账号 miss 装载反向放大；删除后残留成员不清理。验证：`mvn compile` + `tv.py test junit` 过——JUnit **403 例全绿**（LikeCacheServiceTest 28→32：T4 反转翻译 + 评论侧批量 hit/miss 对称 2 例；CacheKeysTest 11→13：userLikeSet 格式 + domainOf 归位）；**pytest all 124 passed 无回归**（`tv.py test`，沙箱外）；常青文档同步见 6.16（header 2.19）；subagent review 通过（无🔴，🟡 4 条全部落实：deleteContentLike javadoc 事实性修正——物理删除与软删隐藏两场景区分 / `batchKeysIsMember` 停用预留注记（T4 后无生产调用，组件保留）/ 评论批量对称补测 / 常青 key 表与流程同步）。关联：T1 执行回写"差异记录 L2"不涉本任务（批量回填 best-effort 契约由 SetCache 承接不变）。

### T5 推荐读路径优化 + 索引重建退避

* **入口线索**：`ContentCache.getRecommendByFilter`（L126-151，N2 证据）与 `ensureIndex`（L406-424，N1 证据）。目标：① 按 shuffle 序惰性探测——凑满 limit 即止，探测量与候选总量解耦；② 重建失败后进程内冷却退避——停机期间不再逐请求全表查询；③ R-07 附带评估：LREM+LPUSH 语义下索引是否存在长尾漂移，结论回写 NEEDS。
* **红线边界**：推荐结果分布语义不变（shuffle 均匀性——惰性探测只影响"探测多少"，不影响"取哪些"的随机性论证须在执行回写记录）；零行为变化（N1 退避不改"停机空推荐"对外语义——U-11 本体不在本期）；`readIndex` 全量 `LRANGE` 保留（NEEDS 4.3 明确不做 R-04 本体）。
* **强制探索步骤**：动刀前先 (0) 复核 N1/N2 证据 (1) 惰性探测的均匀性/跳过量论证（null 跳过语义：hit-empty/DB 无数据的候选会消耗探测位，最坏全探测——退化场景与现状等价）(2) 退避的冷却参数与进程内记录形态（对齐既有熔断冷却先例）(3) R-07 评估的漂移定义与判定依据——若清单未覆盖 → 回写本文档再动手。
* **验收**：探测量前后对比（候选数 × 3 命令 → ~limit+跳过量）；停机期间 `/start` 不再逐请求触发 `loadAllWithoutMedia`（可复现验证，参照三期 T1/T2 的运行时验证脚本先例）；R-07 评估结论回写 NEEDS；`pytest all` 全量绿。
* **执行回写（2026-09-16，`refactor(cache-05)` 已落地）**：N1/N2 证据复核**均成立**（N2：`getRecommendByFilter` L135-149 全量 LRANGE→去重→全量 shuffle→`getContentsBatch(distinctIds)` 对全部候选一趟 pipeline（每 key EXISTS+GET+EXPIRE=3 命令）→按序收 limit=12；N1：`ensureIndex` L406-424 exists 失败→单飞重建→`rebuildIndexes` **内部吞 CacheException 不抛出**（失败不可观测，改造返回 boolean）+ `loadAllWithoutMedia` 每请求 1 次 DB 全表查询 + 单飞只防并发重叠不防串行重复，G11 第 (0) 步过）。**惰性探测落点**：`getRecommendByFilter` 内 `getContentsBatch(distinctIds)` → **按 shuffle 序逐个 `getContent(contentId)`**，null 跳过、收满 limit 即 break；`getContentsBatch`/`CacheAside.getBatch`/Feed/Profile **零改动**（仅剩 Feed/Profile 使用）。**均匀性论证（红线要求记录）**：shuffle 仍在全量去重 id 列表上一次性执行，返回集 = "shuffle 序前 limit 个非 null"，与批量读后按同一 shuffle 序收集**逐位一致**——惰性探测只改"探测到第几个停止"、不改"取哪些"；null 跳过语义等价，最坏全探测 = 现状退化场景（JUnit 全 null 用例锁等价）。**退避落点**：`rebuildIndexes` 返回 boolean（写失败 false，init 调用点忽略返回值）；`ensureIndex` 失败记 `lastFailedRebuildAtMillis`（进程内 volatile 单时间戳）、成功清 0，冷却窗口（`cache.content.indexRebuildCooldownMillis=10000`，对齐熔断冷却先例）内跳过 exists+重建（零 Redis 零 DB）；冷却过期自然重试，与熔断探针恢复语义同构；失败口径 = `rebuildIndexes` false（exists 检查失败不单独记）；ContentCache 双构造（@InjectConstructor + 包级注入冷却值，对齐 RedisCircuitBreaker 先例）、AppConfig 新 getter。**探测量前后对比**：前 = 3×候选（1 万候选=3 万命令 + 全部回填/续期）→ 后 = ≤3×(limit+跳过量) ≈ 36~90 命令（未命中候选不再预装载/续期）；JUnit 锁 100 候选 limit=12 探测恰 12 次、全 null 最坏恰 100 次。**停机验证（可复现，脚本 `temp_script/verify_cache05_rebuild_backoff.py`）**：REDIS_HOST=203.0.113.1 黑洞注入启动，预热 1 次 /start 后连续 20 次 /start 全部 code=200 + 空推荐（**U-11 停机空推荐语义不变**），**Δ Com_select = 0**（无退避应 ≈20——每请求 1 次 findAllContent），日志实证 2 次"索引重建失败"（init + 预热各 1，预热即记冷却）。**R-07 评估结论**：LREM(count=0) 删全部出现 + LPUSH 写即去重、removeContent 对全部 4 索引 key 幂等 LREM → **正常操作无系统性长尾漂移、无需定期重建**；仅删除 LREM 失败（停机窗口）残留有界脏 id，惰性探测后每条至多消耗 1 个探测位，索引 key 缺失/启动全量重建即收敛——已回写 NEEDS 二表 R-07。**L1 观察（登记不修）**：`loadAllWithoutMedia` DB 失败返回空列表 → `rebuildIndexes(空)` 在 Redis 正常时 SCAN+DEL 清索引（既有行为，DB 瞬断清索引；零行为变化红线不随 T5 改动）。**红线遵守**：`readIndex` 全量 LRANGE 保留（R-04 本体不做）；@WebServlet/web.xml/IoC 零改动；无 DDL、无新依赖。验证：`mvn -o` 全量 JUnit **407 例全绿**（surefire 403 + pool 4；ContentCacheTest 24→28=改造 3+新增 4）无回归；**pytest all 124 passed**（tv.py test all，沙箱外）；常青文档同步见 6.17/6.4/header 2.20、BUSINESS_FLOW 3.1；subagent review 通过（无🔴，🟡 5 条全部处置：① 探测量口径统一为命令数所得（6.17"≤3×(limit+跳过量)≈36~90 命令"）② 空库重建"成功"不记冷却的反向边 → L1 登记（NEEDS 4.0/ARCH 6.17）③ verify 脚本判据收紧 `Δ<=1` 且空推荐逐次断言（已按记录 Δ=0 满足）④ 配置毫秒单位接受不改 ⑤ 重桩覆盖注释入 ContentCacheTest）。

### T6 关注/粉丝计数入缓存

* **入口线索**：`content/service/ProfileService.getProfile`（L89-90——followCount/followerCount 每次从 DB user 表读）+ `follow/service/FollowService` 写路径（follow/unfork 维护 user 表计数）+ `FollowCache`（T3 收口后）。目标：计数独立 key 入缓存，Profile 读路径命中缓存。
* **红线边界**：**计数一致性方案（缓存计数与 DB 计数的失效/维护时序）须先经用户拍板再动手**（G7）；SCARD 冷 set 返 0 的坑必须有明确结论（计数 key 与 Set key 的关系——独立计数 key 还是 SCARD 现算，方案随拍板定）；不引入新第三方依赖。
* **强制探索步骤**：动刀前先 (0) 复核 R-01 证据 (1) 现状梳理：follow/unfollow 事务内是否 UPDATE user 表计数（最终真理在哪）(2) 方案对比：独立计数 key（String int，Cache-Aside）vs Set SCARD（冷 set 返 0 的坑）vs 直读 DB 维持——**停决策点报备拍板** (3) TTL 与失效策略（follow/unfollow 后失效 or 增量维护）。
* **验收**：Profile 二次访问命中缓存（可观察）；并发关注/取关后计数最终一致（偏差窗口在拍板容忍内）；JUnit 全绿 + 相关 pytest 无回归。
* **执行回写（2026-09-16，`refactor(cache-06)` 已落地）**：R-01 证据复核**成立**（`ProfileService.getProfile` L89-90 计数从 user 整行读；`FollowService` follow/unfollow 事务内 `updateFollowCount/updateFollowerCount` → **DB 为最终真理**，DB 提交后才调 `followCache`，G11 第 (0) 步过）。**方案拍板（G7，2026-09-16 用户三连拍板）**：① 计数 key = **独立计数 key + Cache-Aside**（`user:followCount:{userId}` / `user:followerCount:{userId}`，String int，0 合法，与 `content:likeCount` 同构）；② **SCARD 冷 set 返 0 的坑结论 = 否决 SCARD**——miss/冷 set 返 0 且不触发装载、set TTL 过期返 0、计数与成员加载状态/TTL 耦合（成员与计数分离 4.6 先例）；③ 一致性 = **条件增量 Lua**（DB 提交后 `FOLLOW_COUNT_ADJUST_SCRIPT`：`exists 才 INCRBY±1`，冷 key no-op 由读回填，失败只失效两计数 key 读自愈），替代失效 DEL（失效-重载竞态）。**G11 质疑（L3 上报，用户裁决「照做」留痕，见 NEEDS 4.0）**：`getProfile` 必须读 user 整行（username 等），计数缓存**不减少任何 DB 查询**、冷 key 首访反而多 1~2 次单列计数查询——用户接受，唯一消费方仍按「读路径命中缓存」落地（验收锚=二次访问命中可观察）。**落点**：`CacheKeys` 新增两工厂，`domainOf` 无需扩展（`user:` 兜底归 FOLLOW，T8 巡检项随 T6 确认放行）；`UserDao` 新增 `getFollowCountById/getFollowerCountById` 单列查询；`FollowCache` 注入 `UserDao`，读新增 `getFollowCount/getFollowerCount`（`cacheAside.get` + loader 样板，镜像 `getLikeCount` 防御分支），写新增 `adjustCountsQuietly`（独立于成员 MULTI 块，`cacheFollow` → +1、`cacheUnfollow` → -1，失败隔离：成员写失败不影响计数增量、计数写失败只失效计数 key）；`ProfileService.getProfile` 事务外读两计数，不再消费行内 `user.getFollowCount()/getFollowerCount()`。TTL 沿用 `cache.follow.ttlMinutes`（30min，滑动续期由 CacheAside 承担），**app.properties 零改动**。**L1 观察 4 条（登记不修）**：404 用户不存在场景多打计数 loader / DB 全挂错误文案由"获取用户主页失败"变"服务器异常，查询关注数/粉丝数失败"（同 like 域先例，均 500 无契约）/ 并发"DB 提交后→INCRBY 前"读 miss 回填含 +1 真值再 INCR 瞬时多计（like 4.6 先例内已知上限，TTL 自愈）/ DECR 理论负值（仅缓存值已低于 DB 真值才可能，miss 即纠）。验证：`mvn compile` + `tv.py test junit` 过——JUnit **416 例全绿**（surefire 412 + pool 4，T5 407 + 9：FollowCacheTest 29→37 计数读写 8 例、CacheKeysTest 13→14 1 例）；**pytest all 124 passed 无回归**（`tv.py test all`，沙箱外）；**运行时验证 `temp_script/verify_t6_count_cache.py` 8/8 通过**——冷 key 回填一致（Redis==API==DB）、follow 后 warm key 条件 INCRBY 同步 +1、**篡改探针（SET 计数 key=999 → GET /profile 返回 999 ≠ DB）证明读路径命中缓存（二次访问可观察）**、DEL 自愈、unfollow 复原、冷 key no-op 由读回填（no-op 未产生错误计数）；常青文档同步见 6.18/6.2/9.2/12、BUSINESS_FLOW 3.1；subagent review 通过（无🔴，🟡5 条全部处置：均为先例内已知项或 L1 登记——INCR 瞬时多计上限、404/文案漂移、DECR 负值、死分支防御、测试缺口评估，无需代码改动）。

### T7 小项打包：JSON 兼容 + 批量续期补测

* **入口线索**：`cache/JacksonCodec`（L20-22——MAPPER 未 disable `FAIL_ON_UNKNOWN_PROPERTIES`，R-09 证据）；`CacheAside.getBatch`（miss key 续期未覆盖）与 Like/Follow 批量 pipeline 续期（R-06 两个断言缺口）。目标：① Jackson 关闭未知字段失败（DTO 删/改名后旧 JSON 仍可反序列化，多余字段忽略）；② 补齐批量续期测试断言。
* **红线边界**：只关 `FAIL_ON_UNKNOWN_PROPERTIES`，**不做全量格式版本机制**（版本号/迁移逻辑不做，NEEDS 4.3）；R-06 只补测试不改行为。
* **强制探索步骤**：动刀前先 (0) 复核 R-09/R-06 证据 (1) 关闭 feature 后的兼容性单测（含多余字段的 JSON 能反序列化、既有 DTO 序列化往返不变）(2) 确认 `SerializationFeature` 现有配置不受影响。
* **验收**：新增兼容单测绿；批量续期断言补齐（getBatch miss key 场景 + Like/Follow 批量 pipeline）；全量 JUnit 无回归。**G1 标注：本任务为小任务合并，1 个 commit**。
* **执行回写（2026-09-16，`refactor(cache-07)` 已落地）**：R-09/R-06 证据复核均**成立**（G11 第 (0) 步过）：R-09 = `JacksonCodec` MAPPER（L20-22）未关 `FAIL_ON_UNKNOWN_PROPERTIES`——DTO 删/改名后旧缓存 JSON 含未知字段 → 反序列化失败 → DEGRADE 每读走 DB（TTL 内反复）；R-06 ① = `CacheAside.getBatch` 续期用例只覆盖 hit-data/hit-empty、无 miss key 场景；R-06 ② = `SetCache` 批量（batchIsMember/batchKeysIsMember）与 Like/Follow 域层批量用例均无 pipeline `expire` 直接断言（仅单 key hit-data 有，`isContentLikedHitDataRenews*`/`isFollowingHitDataRenews*`）。**落点（G1 小任务合并 1 commit）**：① 业务一行修——JacksonCodec MAPPER 追加 `.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)`（保留 `WRITE_DATES_AS_TIMESTAMPS` 原样，无版本号/迁移机制，红线遵守；`BaseServletUtil`/`BaseServlet` 控制器层 ObjectMapper 不碰）；② JacksonCodecTest +2 例——`jsonWithUnknownFieldsIgnoresExtraProperties`（多余字段忽略，feature 未关则红）/`dateSerializationStaysIsoNotTimestamp`（钉死 WRITE_DATES_AS_TIMESTAMPS 仍关闭）；③ CacheAsideTest +1 例——`getBatchMissKeyQueuesRenewalAndBackfillWritesTtl`（miss key 亦入列续期（无效果）+ 回填 writeOrInvalidate setex TTL + 清空标记，空标记不续）；④ SetCacheTest/LikeCacheServiceTest/FollowCacheTest 仅补断言行（无新增用例）——批量三态（hit-data/hit-empty/miss）全部直接断言 pipeline `expire`（SetCache 精确 TTL 无抖动、CacheAside 批量 ±10% 抖动 longThat(90..110)）+ 空标记不续。**行为说明（R-09 有意的定向变化）**：脏 JSON（含未知字段）由「反序列化失败→DEGRADE→DB loader」变「成功解析（忽略未知字段）→HIT_DATA」，正是 R-09 要消除的反复钻 DB；对外 API/业务语义零变化。验证：`tv.py test junit`（沙箱外）JUnit **419 例全绿**（surefire 415 + pool 4，T6 416 + 3）无回归；**pytest all 124 passed**（`tv.py test all`，沙箱外）；常青文档同步见 6.19/9.2/12；subagent review 通过（无🔴，🟡 2 条全部落实：batchKeysIsMember 混合态补 k1 hit-empty 续期断言 / JacksonCodecTest 末尾换行）。关联：本任务无 L2/L3 差异（R-06 只补测试不改行为；R-09 业务改动仅 JacksonCodec 一行 + javadoc）。

### T8 收尾

* **入口线索**：全仓库巡检 + 回归 + 文档同步 + 覆盖率。基于 T1~T7 完成态。
* **红线边界**：不引入新功能改动；只做残留清理、验证与文档；发现"要改但未排期"的内容 → **登记 `UNPLANNED_ISSUES.md`**，不在本周期硬做。
* **强制探索步骤**：动刀前先 (1) `rg` 确认 T1~T7 无残留（旧的重复实现、TODO/临时开关/临时日志）(2) 校验 `@WebServlet` URL / web.xml / IoC 扫描原样 (3) 确认 CacheStats 打点口径在收口/反转/新增 key 后仍准确（T2/T4/T6 都动过缓存路径——`domainOf` 的域归映射是否需要随新 key 扩展）。
* **验收**：`pytest all` 全量回归全绿；常青文档同步（`CURRENT_ARCHITECTURE.md` 六.Redis 设计 + `BUSINESS_FLOW.md` 3.1 缓存机制）；NEEDS 中已拍板决策与实现一致；覆盖率地图 rerun 无回归。
* **执行回写（2026-09-16，`refactor(cache-08)` 已落地）**：**① 残留巡检**（rg 全仓库 Java + 代码复查）：T1~T7 无残留——旧重复实现（`scanSet`/`writeSet`/`loadViaSingleFlight`/`getSetMembers`/`toSortedLongs`/`scanLikeSet`/`writeXxxLikers`/`backfillBatch*`/`degradeBatch*`/`findLikerIdsBy*`）均已随 T2/T3/T4 删除，命中仅余 T1 基建组件 SetCache 自身方法与 javadoc 说明；**无 TODO/FIXME/临时开关/临时日志**（`System.out.println` 仅 CouponAdmin/LogUtil/CountRepairTool 既有工具类，非本期引入）；`batchKeysIsMember` **生产无调用、仅 SetCacheTest 测试保留**（T4 停用预留注记落实）。**② @WebServlet/web.xml/IoC**：14 个 `@WebServlet` URL、`web.xml`（4 filter）、IoC 扫描 `ClassScanner.scan("com.itheima")` 全部**原样**。**③ CacheStats 打点口径**：`domainOf` 对全 key 工厂分支逐一核对——T4 新 key `user:likeSet`/`user:commentLikeSet` 于 `user:*` 兜底前归 LIKE、T6 新 key `user:followCount`/`user:followerCount` 经 `user:*` 兜底归 FOLLOW、T4 反转后 `content:like*`/`comment:like*` 归 LIKE 不变，长前缀优先顺序（content:index → content:like → content:comments → comment:like → comment: → content: → user:commentLike → user:like → user:）核对无漂移；SetCache/CacheAside/LikeCacheService/FollowCache 挂点事件（三态/LOAD/DEGRADE/WRITE_FAIL）口径与 6.3 一致。**④ 回归**：`tv.py test junit`（沙箱外）JUnit **419 例全绿**（surefire 415 + pool 4，与 T7 基线一致无回归）；`tv.py test all`（沙箱外）**pytest all 124 passed**（本周期收尾全量回归点）；覆盖率地图 rerun **41 端点全有 pytest、无用例 0**（无回归，与三期 T6 记录一致）。**⑤ 文档**：常青 CURRENT_ARCHITECTURE 2.23（更新日志行；6.2 key 表 / 6.15~6.19 核对一致）+ BUSINESS_FLOW 3.1 注记核对一致（T3/T5/T6 均已各自同步）；NEEDS 4.0 追加 T8 收尾登记。**G11 质疑**：无 L2/L3（T2 遗留的"header 版本滞后 L1 观察"已随 T7/T8 版本链 2.19→2.23 自然对齐；`batchKeysIsMember` 停用预留维持 T4 拍板）。**验证与验收对照**：验收四项（残留/原样/文档/覆盖率 + pytest all 全绿）全部达成。

---

## 五、变更记录

| 日期 | 版本 | 内容                                                                                                                                                                       |
| ---- | ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-09-16 | 0.9 | **T8 完成（`refactor(cache-08)`）**：任务总览 T8 状态 → 已完成；四、任务详情 T8 追加执行回写（残留巡检 T1~T7 无残留 / @WebServlet 14 URL+web.xml+IoC 原样 / CacheStats domainOf 全 key 分支核对无漂移 / JUnit 419 + pytest all 124 + 覆盖率地图 41 端点无回归 / 常青 2.23 + BUSINESS_FLOW 核对一致 / 无 L2/L3）；变更记录 0.9 行 |
| 2026-09-16 | 0.8 | **T7 完成（`refactor(cache-07)`）**：任务总览 T7 状态 → 已完成；四、任务详情 T7 追加执行回写（R-09/R-06 证据复核成立 / JacksonCodec 关 `FAIL_ON_UNKNOWN_PROPERTIES` 一行修（保留 WRITE_DATES_AS_TIMESTAMPS，无版本机制）/ JacksonCodecTest +2（未知字段忽略 + 日期格式钉死）/ CacheAsideTest +1（getBatch miss key 续期与回填）/ SetCache/Like/Follow 批量三态续期断言行补齐 / 脏 JSON 路径有意定向变化说明；JUnit 419（surefire 415 + pool 4）+ pytest all 124 passed + subagent review 无🔴🟡2 全落实）；变更记录 0.8 行 |
| 2026-09-16 | 0.7 | **T6 完成（`refactor(cache-06)`）**：任务总览 T6 状态 → 已完成；四、任务详情 T6 追加执行回写（R-01 证据复核成立 / 2026-09-16 用户三连拍板：独立计数 key+Cache-Aside、SCARD 否决、条件增量 Lua / G11 质疑 L3 上报·用户裁决照做 / 落点：CacheKeys 两工厂+UserDao 两单列查询+FollowCache 读写+ProfileService 事务外读 / L1 观察 4 条 / JUnit 416 + pytest 124 + verify 8/8 篡改探针证命中缓存 + subagent review 无🔴🟡5 全处置）；变更记录 0.7 行 |
| 2026-09-16 | 0.6 | **T5 完成（`refactor(cache-05)`）**：任务总览 T5 状态 → 已完成；四、任务详情 T5 追加执行回写（N1/N2 证据复核成立 / 惰性探测落点与均匀性论证 / 退避参数与记录形态 / 探测量前后对比表 / 停机运行时验证 Δ Com_select=0 / R-07 评估结论 / L1 观察；JUnit 407 + pytest 124 + subagent review 无🔴）；变更记录 0.6 行 |
| 2026-09-16 | 0.5 | **T4 完成（`refactor(cache-04)`）**：任务总览 T4 状态 → 已完成；四、任务详情 T4 追加执行回写（R-08 证据复核成立 / 用户 2026-09-15 拍板内容+评论全反转 / key 与 domainOf 落点 / 批量收敛单 set 多成员 / Lua 仅换 KEYS·ARGV / 失效仅计数 key / DAO 增删 / 装载量前后对比表 / 已知取舍；JUnit 403 + pytest 124 + subagent review 无🔴🟡4 全落实）；常青 CURRENT_ARCHITECTURE header 2.19（4.2 两行、6.2/6.3/6.16/9.2/12 同步）+ BUSINESS_FLOW 3.1/4.1.1/3.9/3.10 点赞与失效口径同步 |
| 2026-09-15 | 0.4 | **T3 完成（`refactor(cache-03)`）**：任务总览 T3 状态 → 已完成；四、任务详情 T3 追加执行回写（读路径收口 SetCache——isMember/batchIsMember/getMembers+sortIds、524→326 行、写路径 MULTI 双写零改动、批量回填本已 best-effort 无 L2 差异、L1 日志措辞统一；JUnit 397 全绿 + pytest all 124 passed 本周期首个全量回归点）；常青 CURRENT_ARCHITECTURE 2.18（follow 域行 / 新增 6.15 / 9.2 FollowCacheTest 注记 / 12 更新日志）+ BUSINESS_FLOW 3.1 收口注记 |
| 2026-09-15 | 0.3 | **T2 完成（`refactor(cache-02)`）**：任务总览 T2 状态 → 已完成；四、任务详情 T2 追加执行回写（孪生差异盘点 / SetCache 落点 / L2 差异对照记录——T1 登记"批量回填上抛→best-effort"收敛落地；JUnit 397 pytest 124）；常青 CURRENT_ARCHITECTURE 2.17（4.2 SetCache 行与 like 域行 / 新增 6.14 / 9.2 LikeCacheServiceTest 23→25 / 12 更新日志） |
| 2026-09-15 | 0.2 | **R-10 拍板后拆任务**：方向 = 缓存体系综合改造（结构收敛 + 装载反转 + 读路径优化 + 补缺口）；G1/G3/G9 校准（commit 前缀 `refactor(cache-0N)`、`pytest all` 全量在 T3/T5/T8 三点、本周期无 DDL）；任务总览填入 **T1~T8**（含 NEEDS 编号映射与顺序理由——T1 基建 → T2 Like 收口（最深）→ T3 Follow 收口 → T4 反转（硬依赖 T2）→ T5 读路径（独立可并行）→ T6 计数（软依赖 T3）→ T7 小项 → T8 收尾）；T4/T6 标注开工前方案拍板点（G7）；"四、任务详情"填入 8 个任务的四要素骨架 |
| 2026-09-15 | 0.1 | 新建本文档（模板就位）：接 260914-cache-hardening 归档（第三期「缓存加固」T1~T6 全部完成），结转周期约定 G1~G11（G1 commit 前缀 / G9 DDL 标注待方向拍板后校准；G11 质疑协议为 2026-09-15 新增）+ 任务模板四要素（含 (0) 证据复核步）+ 红线措辞 / 编号引用 / 质疑协议约定；任务总览与任务详情**留空待填**（方向待 NEEDS 三节 R-10 拍板） |
