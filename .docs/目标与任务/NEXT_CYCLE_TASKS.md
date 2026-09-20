# 下一周期任务清单

> 状态：**第七期（2026-09-19 建清单；**T11 关注·粉丝列表分页**原为**预告条目**，已于 2026-09-20 T11 窗口完成范围拍板并**拆为 T11-A（follow 域上限 200 + 后端固定信封 + 缺省归一为第一页 + sheet 接 chunkedList，**契约变更**）/ T11-B（评论域固定信封 + 公共 helper 去重/自适应 + 内容列表前端迁移）/ T11-C（装载侧解耦，池 U-18 治本，拆 C-1/C-2 两个 commit）**，四要素已回写（见四节）；**T11-A（2026-09-20）、T11-B（2026-09-20）与 T11-C（2026-09-20）已完成**（执行回写见四节各自之后），另新增 **T19**（feed/search/profile 的后端大分页推后项）+ **T12~T18** 清理类（**T12 事务边界同型未治点 ✅ 已完成 2026-09-20**（全链 exit 0 + JUnit 506/0/0/0 + pytest 145）/ **T13 注册后自动登录兜底 ✅ 已完成 2026-09-20**（全链 exit 0 + JUnit 511/0/0/0 + pytest 145）/ 包层结构清扫 / 文档与代码一致性 / Controller 基建收敛 / IoC 注入可观测 / 配置卫生）。
> 关联文档：`目标与任务/NEXT_CYCLE_NEEDS.md`（决策唯一源；本文档为执行细节）。
> 来源：NEEDS **4.2** 的两个候选任务（用户 2026-09-19 点名"分为评论分页和关注/粉丝列表分页两个任务"）+ NEEDS 二节留池项 + `UNPLANNED_ISSUES.md` 的 **2026-09-19 第七期评估**（进 TASKS 项：U-14 / U-16 / U-07+U-19）。
> 明确不做（本轮已定）：offset→游标（属 **feed 流方向**——该方向路线已预告为"读扩散查询 → 每人维护收件箱（写扩散）"）；日志体系改造相关（`U-15` 已移出池，随该方向立项盘点）；`R-01` 索引全量读（明确保留）；`U-11` 停机兜底推荐（留池，待产品决策）；评论热门排序（用户"另想对策"延后，登记 NEEDS 4.1 **N17**，**不占 T10 范围**）；NEEDS 4.1（N9~N16）中**已点单的已并入清单**：N9 文档部分→**T15**、N11→**T16**、N12 前半→**T17**、N13→**T18**、N14→**T14**、**N15→T10-B（2026-09-19 T10 窗口调整：公共"分块列表"helper 随 T10-B 前端改造抽，T11 直接复用）**、N16→**T15**；**未排的**：N9 的"声明式鉴权"、**N10**（admin 角色每请求查库）、N12 的"声明式事务"——属**新功能方向的能力建设**，待该方向立项时评估；**2026-09-20 T11 窗口新增推后项**：`/feed`·`/search`·`/profile` 的**后端**大分页（域级上限 + 域级信封）→ **新增 T19**（这三处内容列表的**前端**迁移仍在 T11-B 内完成）。
> **变更纪律（沿用 2026-09-18）**：本档无"变更记录"节——变更以 git 提交历史为准。

---

## 一、周期约定

| 编号 | 约定 | 内容 |
| ---- | ---- | ---- |
| G1 | 一任务一窗口一 commit（默认期望） | 每个任务开独立窗口，默认期望 1 个 commit；因任务内部依赖需拆多 commit 或小任务合并时，**在该任务详情标注**；commit message 按 `说明书/COMMIT_CONVENTION.md` 规范书写且**强制带任务编号**，**任务编号自 `T10` 起**（承接第六期 T1~T9）；**周期 commit scope 待方向拍板后定**（第六期为 `prep-0N` 风格）；引用任务一律用 `T10`~`T#`，不用 commit scope |
| G2 | 开窗协议（输入） | 新窗口顺序读取：① `.docs/INDEX.md` → ② `目标与任务/NEXT_CYCLE_NEEDS.md`（**决策节必读**：C-#、R-##、4.1 的 N#、4.2 候选、4.3 范围）→ ③ 本任务清单（一/二节约定，含 G11 质疑协议 + 当前任务） → ④ 常青文档（`常青/CURRENT_ARCHITECTURE.md` + `常青/BUSINESS_FLOW.md` 相关章节）→ ⑤ 上一个任务 commit |
| G3 | 收窗协议（输出） | ① 跑测试与反馈：每任务 = 编译 + 相关单测 + **相关端点 pytest 回归**；全量回归在影响面大的任务与收尾任务各跑一次（沙箱内可直接 `python tools\tv.py test all`） ② 勾选任务清单状态 → ③ 涉及架构/业务改动时同步常青文档 → ④ 提交 |
| G4 | commit 语义闭环 | 代码改动 + 其对应常青文档更新 + 任务清单勾选进同一 commit；message 按 `说明书/COMMIT_CONVENTION.md` 规范书写且强制带任务编号 |
| G5 | 超范围暂停规则 | 执行中发现需求歧义、或任务实际远超预期（判定锚：触及清单未列模块 / 需新依赖 / 需改业务语义 / 改动面较预估倍增）→ 停在第一个决策点，回写任务清单（拆/改）并按 G11 分级登记，不得硬扛、不得擅自扩大范围 |
| G6 | 评审与返工 | 评审以"任务验收标准 + 测试结果"为准；返工记录在任务清单；连续返工 ≥2 次 → 执行 Agent 停手并登记"建议返回周期设计重新评估"，由**用户**裁决是否重开周期设计 |
| G7 | 决策唯一源 | `NEXT_CYCLE_NEEDS.md` 决策节为唯一决策源；窗口内发现新决策 → 回写该节并标记"已定/待定"，不许自行拍板。**T10/T11 的范围拍板即属此列**（窗口内定完必须回写） |
| G8 | 分支与合并 | **开分支 / 合并回 integration / 合入 master 均由用户手动执行**；任务窗口只负责本任务的代码、测试与 commit（G1），不自行创建/切换分支、不合并 |
| G9 | DDL 备份 | **本周期 DDL 已定（2026-09-19 T10 窗口拍板）**：`comment` 表加 `reply_count INT NOT NULL DEFAULT 0`（T10-A 建字段、T10-B 消费）+ 复合索引 `(content_id, parent_id)`（基础索引，服务窗口查询，与热度排序无关）；执行前先按三步备份闭环（先备份改动前结构 → ALTER 3306 → 备份新结构 → `init_test_db.py` 重建 3307 测试库）并**向用户报备**。沙箱注意：`tools/backup.py` 默认输出到工作区外（`D:\dev\WorkSpace\VideoPlatform\auto_backup`）会被沙箱拦截 → 需给 backup.py 加 `--out` 参数输出到项目内 `.docs/DBbackups/`（默认行为不变）再执行 |
| G10 | 脚本规范 | 脚本一律 Python；临时一次性脚本放 `temp_script/`，长期复用/自动化脚本放 `tools/`（工具统一入口 `tools/tv.py`） |
| G11 | 质疑协议 | 执行 Agent 对 Why 层（NEEDS 真实性/必要性）与 How 层（任务/验收可操作性）**有质疑权、亦有报告义务**：四时点触发 + L1~L4 分级动作（完整协议见二节"质疑协议"）；**裁决权永远在用户**（G7 决策唯一源不破） |

---

## 二、任务清单理念与任务模板

> **任务清单理念**：本清单只回答"**要做什么、不做什么**"，**不提前过度详细设计**——四要素是骨架，具体执行方案由执行窗口的 Agent 探索细化。
> **红线边界的目的是"防跑偏"，不是把 Agent 限制死**：因此只列"明显越界"的项，不穷举做法；执行中若发现红线本身阻碍了正确做法，按下方"红线措辞约定"先申请再动手。
> 反面清单同样是清单的一部分：**"明确不做"要写出来**，避免 Agent 顺手扩张。

> **任务模板（每任务必含四要素）**：入口线索（从哪找）+ 红线边界（别碰什么）+ 强制探索步骤（动手前先确认什么）+ 验收（怎么算完成）。执行 Agent 允许动态调整，但**调整前先回写任务清单/需求文档（G5/G7），再动手**。
> **预告条目例外（本周期 T10/T11）**：经用户 2026-09-19 拍板，这两项**只登记意图与前置**，四要素留待执行窗口调查后**拍板并回写**（目的：避免在需求/清单层提前设计执行方案）。**状态（2026-09-20）**：T10 → 已拆 T10-A/T10-B 并全部完成；**T11 → 已拆 T11-A/T11-B/T11-C（四要素已回写，见四节）**，本例外条款对 T10/T11 均已用毕。

> **红线措辞约定（延续）**：红线只列"明显越界"的项，作用是**防跑偏，不是把执行 Agent 限制死**——不穷举做法、不做"一刀切禁止"。
> 若执行中发现**某条红线会阻碍正确做法**（过紧、过窄、或已不适用）：不允许硬扛，也不允许自行放开，**先向用户说明理由并申请调整**；获批准后按新口径动手，未获批准则维持原红线。

> **编号引用约定（延续）**：禁裸编号引用已归档周期元素（引用一律写 `<周期>/<编号>`，如 `260919/R-01`）；裸编号仅指本档内部定义的元素（`T10`~`T#`）与 `NEXT_CYCLE_NEEDS.md` 内部元素（C-# / R-## / N#）。`U-xx` 为 `UNPLANNED_ISSUES.md` 的全局唯一池编号（不加周期前缀）。执行中发现引用歧义 → 回写清单用文字澄清，不得自行猜义。

> **质疑协议（G11，2026-09-15 确立）**：执行 Agent 对 **Why 层**（NEEDS 真实性/必要性）与 **How 层**（任务可行性/验收可操作性）均有质疑权，亦有报告义务；**裁决权永远在用户**（G7 决策唯一源不破）。四个触发时点：
> ① **开窗阅读**——文档内部矛盾（交叉引用错乱、两节口径冲突）；
> ② **动手前**——强制探索步骤第 (0) 项复核本任务对应 NEEDS 编号的证据，发现不成立；
> ③ **探索中**——代码事实与 NEEDS 描述矛盾 / 需求疑似已被现有实现覆盖 / 触发 G5 判定锚；
> ④ **验收时**——验收标准全过但对应痛点场景仍未消除。
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
> **质疑成立后的去向**：对应 R/N 编号显式流转——转 `UNPLANNED_ISSUES.md` 留池 / 结转下周期 NEEDS 二节 / 在 git 提交信息记录废弃原因；任务清单中"搁置"状态的 T# 同理，对应编号不得悬空。**两条禁止**：不许为过验收绕过痛点本质（见任务模板"验收"）；不许以质疑为由擅自改语义/扩范围。

```markdown
### T# 任务标题

* **入口线索**：…
* **红线边界**：（只列"明显越界"的项，作用是防跑偏；若某条红线会阻碍正确做法 → 先说明理由申请调整，获批准后按新口径动手）
* **强制探索步骤**：动刀前先 (0) 复核本任务对应 NEEDS 编号（N#/R-##）的证据仍成立（G11——不成立 → L3 暂停登记质疑） (1) 检索… (2) 确认… (3) 若清单未覆盖 → 回写本文档再动手
* **验收**：…（验收通过 ≠ 目的达成：标准全过但痛点场景未消除 → 按 G11 登记质疑，不得直接标已完成）
```

---

## 三、任务总览（本周期 13 任务：T10-A/T10-B、T11-A/T11-B/T11-C、T12~T19；**T10/T11 拆解后计数**——原"12 任务"是按 T10 未拆时写的，编号未变）

> **DDL 标注（G9 要求）**：① **已完成**（2026-09-19 T10-A 窗口）——`comment` 加 `reply_count INT NOT NULL DEFAULT 0` + 复合索引 `(content_id, parent_id)`；② **已完成**（2026-09-20 T11-C-1 窗口）——`follow` 加 `idx_followed_user_user (followed_user_id, user_id)`（粉丝方向窗口查询同序；关注方向复用 `uk_user_follow`）；命名按拍板保持默认值。两次均走备份闭环（改前备份 → ALTER 3306 → 备份新结构 → 备份归档为测试库重建源 → 重建测试库 3307）并**向用户报备**（本次 C-1 执行前已将 DDL 原文交用户过目）。

| 编号 | 标题 | 对应候选 | 依赖 | 验收关键（动态） | 期望 commit 主题 | 状态 |
| -- | -- | ---- | -- | -------- | ------------ | -- |
| T10-A | 评论分页①：缓存结构拆分（主楼 List + 楼中楼 Hash）+ 主楼窗口装载（后端基座） | NEEDS 4.2 ①（= 池 U-20，U-20 治本） | 用户 2026-09-19 放开 T8"不动缓存装载结构"红线（本任务正是要动它） | **已完成（2026-09-19/20）**：两键组 + 主楼窗口装载 + DDL（reply_count + idx），契约零变化（pytest 评论用例零改动）；全链绿 | `refactor(cache-10a)` | **已完成** |
| T10-B | 评论分页②：楼中楼前 K=2 条装载 + replyCount 总数 + 展开加载回复接口 + 前端大 chunk/本地小批 + 公共"分块列表"helper（N15） | NEEDS 4.2 ① + N15 | T10-A 落地（已完成） | **已完成（2026-09-20）**：契约变更落地（children 前 2 + replyCount + `/comment/replies` 展开）；全链 JUnit 绿 + pytest 145 | `refactor(cache-10b)` | **已完成** |
| T11-A | 关注/粉丝列表：域级上限 200 + 后端固定信封（前端只传 `page`）+ 缺省归一为第一页 + sheet 接 `chunkedList` | NEEDS 4.2 ②（+ N15 复用） | 无 | **已完成（2026-09-20）**：`pageSize=51` 回显 51、`999`→200；缺省=第一页信封（与显式 `page=1&pageSize=200` 逐字节一致）；sheet「加载更多」走公共 helper；全链 exit 0 + JUnit 482/0/0/0 + pytest 145 | `refactor(cache-11a)` | **已完成** |
| T11-B | 评论域固定信封（default 200 / max 500）+ 公共 helper 去重与 `chunkSize` 自适应 + 内容列表前端迁移（feed/search/profile，chunk=50） | NEEDS 4.2 ② + 4.1 **N15** | T11-A（helper 自适应形态） | **已完成（2026-09-20）**：评论域只传 `page` → `pageSize=200`（上限 500 保留）；`chunkedList` 具 `keyOf`/`seen` 去重（批内 + 跨 chunk）与 `chunkSize` 自适应；**后端「页间不重不漏」断言一条未改**（去重不掩盖后端 bug）；三处内容列表走公共 helper；全链 exit 0 + JUnit 482/0/0/0 + pytest 145 | `refactor(cache-11b)` | **已完成** |
| T11-C | 关注/粉丝列表装载侧解耦（池 **U-18** 治本，P1 前缀窗口装载） | NEEDS 4.2 ② + 池 **U-18** | T11-A（信封/上限先行）；C-1 → C-2 顺序 | **已完成（2026-09-20）**：JUnit 断言"窗口装载只查 `[0, offset+count)` / 部分态只补 `[W, offset+count)`、不触发全量 loader"、"部分态判定未命中回落 DB"、"`getMembers` 部分态补齐"、"写路径遇 `partial` 三件套双 DEL"、"降级走 DB 窗口直查不装载"；DDL 闭环留证（改前/改后备份 + 3307 重建 + 两库索引核验 + EXPLAIN `Using index`）；全链 exit 0（JUnit 500/0/0/0、pytest 145） | `refactor(cache-11c)`（C-1 记为 `cache-11c1`） | **已完成** |
| T12 | 事务边界同型未治点 2 处（缓存读移出回调） | 池 **U-14**（2026-09-19 评估：进 TASKS） | 无 | **已完成（2026-09-20）**：`search` 回调只留两次 DAO 查询（`SearchDbData` record 回传）、页内 `getContentsBatch` + 点赞/关注状态填充在事务外；列表装载回调只留 `findUsersByIds`、`batchIsFollowing` 与视图组装在事务外——结构可检（56 处 `transactionTemplate.execute` 全仓扫描：回调内零缓存读）；对外行为零变化（事务内语句集 / 返回集与顺序 / 跳过 null / 异常语义不变）；JUnit 506/0/0/0 + pytest 145 + 全链 exit 0 | `refactor(content)` / `refactor(follow)`（实际单 commit 取周期令牌 `refactor(cache-12)`） | **已完成** |
| T13 | 注册后自动登录缺兜底 | 池 **U-16**（同上） | 无 | **已完成（2026-09-20）**：注册提交后自动登录失败 → **仍 200** + `token=null` 的 LoginVO（不再无 token 无提示、不再误报注册失败）；前端提示「注册成功，请手动登录」+ 切回登录 tab 预填手机号；注册本身失败仍照旧抛错；JUnit 511/0/0/0 + pytest 145 + 全链 exit 0 | `fix(user)` | **已完成** |
| T14 | 包层结构清扫：content↔comment 环 + 分页信封上移公共包 + `ResultMap` 反向依赖 | NEEDS **R-02/R-03**（= 池 U-07）+ 池 **U-19** + NEEDS 4.1 **N14** | **R-03 拍板**（环处置口径） | 包依赖按拍板口径单向；`PageResult` 唯一源在公共包；基础包不再反向依赖业务模型；全量 JUnit/pytest 绿且行为零变化 | `refactor` | 待执行（待 R-03 拍板） |
| T15 | 顺手清理：架构文档与代码一致性（目录树 / 鉴权名单 / URL 表） | NEEDS 4.1 **N16** + **N9**（文档部分） | 无 | 文档事实与代码一致（目录树、AuthFilter 精确名单、URL 表抽查）；`git diff` 只含事实修正 | `docs` | 待执行 |
| T16 | 顺手清理：Controller 基建收敛（三份 ObjectMapper / 重复 `write*` / 冗余继承） | NEEDS 4.1 **N11** | 无 | mapper 唯一源、无重复方法体、`BaseServletUtil` 不再继承 `HttpServlet`；响应形状逐字节不变；全量测试绿 | `refactor` | 待执行 |
| T17 | IoC 注入解析失败可观测（消除"取不到就跳过"） | NEEDS 4.1 **N12**（前半；声明式事务不在本任务） | 无 | 注入失败不再静默（先落 WARNING + 汇总清单；是否升级 fail-fast 由用户拍板）；启动无新增失败；测试绿 | `fix(ioc)` | 待执行 |
| T18 | 配置卫生：本机绝对路径可外部覆盖 + `AppConfig` 带默认值读取 | NEEDS 4.1 **N13**（分页上限参数化已随 T10-B/T11-A 落地，本任务不含） | 无 | 默认行为不变；换环境无需改源码/重打包；全量测试绿 | `chore` | 待执行 |
| T19 | feed / search / profile 的后端大分页（域级上限 + 域级信封） | **2026-09-20 T11 窗口推后项** | T11-B（三处前端已迁 helper） | 三域 `pageSize` 上限 200、只传 `page` 返回域级信封；相关 pytest 全绿；全链 exit 0 | `refactor(cache-19)` | 待执行 |

> 状态取值：草稿 / **预告** / 待执行 / 执行中 / 已完成 / 搁置。搁置的 T# 必须注明其"对应候选"编号去向（转 `UNPLANNED_ISSUES.md` / 结转下周期 NEEDS 二节），不得悬空（G11）。

> **顺序理由**：**T12~T19 互相独立、可任意穿插**——**T15（纯文档）可最先做**；T12 / T16 / T17 / T18 属小改动但都要跑测试回归；T14 需先拍板 **R-03**。**T10 已拆解拍板（T10-A → T10-B，均已完成）**：T10-A 先落地缓存结构拆分 + 主楼窗口装载 + DDL 闭环（对外契约零变化）；T10-B 依赖 T10-A，做楼中楼 K=2 + 展开接口 + 前端大 chunk/helper（契约变更）。**T11 已拆解拍板（2026-09-20 T11 窗口）**：**T11-A 先行**（follow 域上限 + 后端固定信封 + 缺省归一 + sheet 接 helper，契约变更，四要素已回写）；**T11-B 已完成（2026-09-20，依赖 A 的 helper 自适应形态）**：评论域固定信封 + helper 去重/自适应 + 三处内容列表前端迁移；**T11-C** 依赖 A 的前端 chunk 落地（装载侧解耦，内部 C-1 → C-2 顺序：先 DDL + 窗口 DAO（行为零变化），再前缀装载；**已完成 2026-09-20**）；**T19** 依赖 T11-B（三处前端已迁 helper，本任务补后端上限与信封）。**T11-C 进窗口前无需再拍板**（拍板结论已回写四要素）。

> **回填要求**：任务执行后在本节与"四、任务详情"同步状态与"执行回写"；拆/改任务必须在"对应候选"列保留 NEEDS/池编号，保持 Why→How 可追溯。

---

## 四、任务详情

> **共通注（本周期通用）**：技术红线沿用——禁 Spring/SpringBoot/MyBatis、不擅改 `@WebServlet` URL / web.xml 生效配置 / IoC 扫描、不为实现便利改业务逻辑语义；**不引入 MQ**（异步用进程内线程池）；**不引入新依赖**（T10/T11 的窗口读仍用 Jedis 既有命令）；**变更纪律**——不维护文档级变更记录，变更以 git 提交历史为准；**脚本规范**——Python，临时脚本 `temp_script/`，长期工具 `tools/` + `tv.py` 收录；**改动业务代码同步维护单元测试与 pytest**；**测试执行**——沙箱内可直接 `python tools\tv.py test all` 跑全链。
> **红线口径（与二节"红线措辞约定"一致）**：下面各任务的"红线边界"只列"明显越界"的项，作用是**防跑偏**、不穷举做法；若某条红线会阻碍正确做法（过紧 / 过窄 / 已不适用）→ 说明理由**申请调整**，不许硬扛、也不许自行放开。

### T12 事务边界同型未治点 2 处（池 U-14）

* **入口线索**：`content/service/ContentService.search`（事务回调内逐 key `contentCache.getContent` + `ContentStatusFiller.fillLikeAndFollowBatch`）；`follow/service/FollowService.buildUserList`（回调内 `followCache.batchIsFollowing`，调用点在其分页读与缺省读路径）。目标：与第五期 T3（`260918/T3`）同款——**DB 查询与缓存读分离**，缓存读移到事务外，消除"外层事务持连接期间再去取连接装载"的叠加。
* **红线边界**：**不改对外行为**（返回集、异常语义、分页口径一概不变）；不动 T7 已落地的分页读路径语义；不为省事改事务语义或连接池参数。
* **强制探索步骤**：动刀前先 (0) 复核池 U-14 证据（两处位置与形态）仍成立（G11——不成立 → L3 暂停登记质疑） (1) 确认 `search` 侧是否把逐 key `getContent` 换成批量读（与 T2 已做的批量装载合并同源），并确认批量读口径与既有降级语义一致 (2) 确认两处早退分支（无命中/空结果）与异常产生位置保持 (3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：两处在事务回调内不再触碰缓存（结构可检）；相关 JUnit + 相关端点 pytest 全绿；对外行为零变化。

> **执行回写（2026-09-20，第七期 T12 窗口）**
>
> * **证据复核（G11 第 (0) 项）**：两处均成立——① `ContentService.search`（回调内逐 key `contentCache.getContent` + `fillLikeAndFollowBatch`，改造前 L77/L83）；② `FollowService` 经私有 `buildUserList` 在回调内调 `followCache.batchIsFollowing`（L105 → L126 → L131）。**事实修正（L1）**：四要素原文写②号点"在其分页读与缺省读路径"，缺省读路径已随 **T11-A** 删除，现只剩分页读（`loadUserList`）。
> * **全仓同型点扫描（含"经私有方法间接调用"这一纯文本扫不到的形态）**：13 文件 / 56 处 `transactionTemplate.execute`，改造前命中 4 处 = 本任务目标 2 处 + **同族但不同判据** 1 处（`CommentService.addComment` 回调内 `contentCache.notifyCommentCountChanged`——缓存**失效写**、无嵌套装载、非 U-14 判据）+ 假阳性 1 处（`ContentCache:427` 仅 `CacheKeys` 构 key）。改造后同脚本只剩后 2 处。
> * **落地**：① `ContentService`（475 → 497）——新增私有 record `SearchDbData(contentIds,total)`，回调只留 `countKeywordSearch` + `keywordSearchInBrief`（**两次 DAO 都不省略、顺序不变**），事务外走 `getContentsBatch`（逐 key `getContent` → 一趟批量读，对齐 Feed/Profile 的 T8 口径）+ `fillLikeAndFollowBatch` + 组装；顺带把 `getCommentsForContent` javadoc 的过时上限 `1~50` 修正为 `1~500 / 缺省 200`（T11-B 已改口径、注释滞后；**纯注释**）。② `FollowService`（173 → 184）——`loadUserList` 回调只留 `userDao.findUsersByIds`（`List<User>` 回传），新增**事务外** `buildUserViews` 承担 `batchIsFollowing` 判重 + 视图组装（判重仍按**该页 ids**、不因 users 为空而跳过），删旧 `buildUserList` 与不再使用的 `import java.sql.Connection`。**单 commit**（两域无内部依赖，未拆）。
> * **否决/未采纳**：① 在 `search` 回调里加 `total == 0` 早退（Feed 的该分支是**既有**语义）——search 改造前不省略 `keywordSearchInBrief`，加早退会改"事务内语句集"，触红线；② 顺带把 `CommentService` 的缓存失效写移出回调 → 越界（非 U-14 判据 + 会改失效时序语义）→ 登记池 **U-22**。
> * **验证（落盘证据）**：`python tools\tv.py test junit` **exit 0**、`latest.json` junit = **506 / 0 / 0 / 0**（`ContentServiceTest` 58 → **61**、`FollowServiceTest` 16 → **19**：+2 探针/边界 + 评审补 1 例；逐类 surefire 复核）；`python tools\tv.py test all` **exit 0**（`build_ok=True`、pytest **145 passed / 0 failed**、`port_18080_open_after=false`）。pytest **用例数不变**（无新增/改写：断言意图全部命中 C1）。
> * **红线合规**：未改 `@WebServlet` URL / web.xml / IoC 扫描；无新依赖；未改缓存类签名/key/TTL/三态/降级/打点；未动 T7/T11 的分页口径与信封；无 DDL。
> * **覆盖边界（如实记录）**：① pytest 侧为**回归**（`/search/keywordSearch` 的 S-05/E-04 + `/follow/following|followers` 12 条）——新增断言意图全命中 C1（结构可检）→ 按 §〇.2 归 JUnit，不重复入 pytest；关键词搜索的**结果集**不新增断言（DAO 全文索引分词不稳定，沿用 `test_delete_content.py` 既有口径）；② 探针口径与 T3 一致（DAO 回调内自检 + 缓存读断言 `inTransaction=false`），**不覆盖"连接池叠加"的运行时表现**（需压测，非本任务）。
> * **G11 记录**：L1 ×3（① 缺省读路径已随 T11-A 消失；② `CommentService` 缓存失效写在回调内 → 新登记 **U-22**；③ content 域模块表行数/描述自 `260919/T8` 起长期滞后：`ContentService` 501→实测 475、`CommentCache` 176→716、`ContentCache` 656→710、`ContentStatusFiller` 90→81、`FeedService` 108→107——本任务已按实测重写该单元格，**评论域同表行数与其它未核章节的滞后仍在**，建议 T15 窗口一并扫）。L2/L3/L4 无。
> * **文档（G4 同 commit）**：`常青/CURRENT_ARCHITECTURE.md`（头部 3.7 + 6.4 两处 + content/follow 模块表行数）、`常青/BUSINESS_FLOW.md`（3.1 要点一句 + 3.5 事务边界注）、本档三节/四节、`INDEX.md` 周期状态行、`UNPLANNED_ISSUES.md`（U-22）。**未写 NEEDS 4.0 决策段**——本任务无新决策（形态由 `260918/T3` 先例 + 四要素探索步骤既定），且用户已于 2026-09-20 把 T10/T11 的技术决策移出该节。
> * **commit 主题口径**：四要素"期望 commit 主题"写 `refactor(content)` / `refactor(follow)`，实际为**单个**跨域 commit → 按 `COMMIT_CONVENTION` §三取周期令牌 `refactor(cache-12)`（对齐 T10/T11 先例）。
> * **独立评审（subagent，只读）结论与处置（2026-09-20）**：**无 🔴 阻断项**，总体判定"可提交"；2 处 🟡：
>   ① 🟡 "`currentUserId != null` 但 DB 装载结果为空（该页 ids 指向已删用户）时是否仍调 `batchIsFollowing`"只有代码审查、无用例 → **已采纳**：补 `getFollowingListPagedEmptyDbResultStillJudgesThatPage`（断言 users 为空仍按该页 ids 判重、total 仍取缓存窗口）；
>   ② 🟡 `ContentService` 的 `pageSize` javadoc 由 `1~50` 修正为 `1~500 / 缺省 200` → **维持**（评审确认与 `CommentController` 的 `COMMENT_PAGE_SIZE_MAX = 500` / `DEFAULT = 200` 实际口径一致，属滞后注释修正、无行为变更）。
>   评审另行核对通过：`CacheAside.getBatch` 对每个请求 key 都写结果映射（含 null 值）→ 与逐 key `getContent` 逐位等价；空 ids / 空结果 / 结果为空不调填充三个分支与改造前一致；异常文案与产生位置未变（`ContentService.java:94-95`、`FollowService.java:116-118`）；`import java.sql.Connection` 删除后无残留引用；文档被改行数经 `wc -l` 复核全部一致。
> * **评审后复跑（2026-09-20，处置后同一工作树）**：`test junit` exit 0、**506 / 0 / 0 / 0**（`FollowServiceTest` 19）；`test all` exit 0、`build_ok=True`、pytest **145 passed / 0 failed**、`port_18080_open_after=false`。**pytest 数字与首次一致**（本轮只加 JUnit 用例、未动主代码）。

### T13 注册后自动登录缺兜底（池 U-16）

* **入口线索**：`user/controller/LoginController.register`（注册成功后调用 `userService.login(id, password)`，其失败路径无兜底）。目标：自动登录失败时返回"**注册成功、请手动登录**"，而不是无 token、无提示。
* **红线边界**：不改注册/登录的既有成功语义与错误码；**不引入自动重试/自动补登**；不改前端路由行为（仅在提示文案/响应字段上做最小改动，若涉及 `views/login.js` 消费方式需在窗口内确认后一并改）。
* **强制探索步骤**：动刀前先 (0) 复核池 U-16 证据（`LoginController.register` 失败路径）仍成立 (1) 确认返回结构变更方式（复用 `msg` 还是新增字段）与前端消费点 (2) 补可复现该失败路径的用例（JUnit 或 pytest） (3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：该失败路径有明确响应与用户可见提示；"注册成功但无 token 且无提示"的场景消失；相关测试全绿。

> **执行回写（2026-09-20，第七期 T13 窗口）**
>
> * **证据复核（G11 第 (0) 项）**：成立——旧 `LoginController.register`（改造前 L68/L69）为 `long id = userService.registerAsUser(rc); LoginVO ls = userService.login(id, rc.getPassword());`：**注册事务已提交**后，`login` 的失败（`UserNotFoundException` / `PasswordIncorrectException` / 登录期 `DatabaseException`）直接冒到 `ExceptionFilter` → 返回错误码 + 错误文案，前端 `catch` 里 toast「用户不存在 / 密码错误」，而账号其实**已注册成功**（用户重试注册还会撞「手机号已被占用」）。与池 U-16 原文一致（"注册成功但自动登录失败时无返回 token 兜底"）。
> * **选型与否决理由**：**D1 兜底下沉到 Service**（新增 `UserService.registerAndLogin`，Controller 只留转换 + 写出）——否决"仅在 `LoginController.register` 包 try/catch"（四要素入口线索的原始落点）：本项目 JUnit 只覆盖服务层（`说明书/TEST_AUTOMATION.md` §0.1）、Controller 无测试层，放 Controller 则该失败路径**没有任何自动化断言**（违背 AGENTS.md「改业务代码同步维护测试」）；且该路径 **E2E 不可复现**（`UserDao.getUserForLoginById` = `select * from users where id=?` 无过滤、密码同源哈希 → 注册后自动登录必然成功），pytest 造不出失败。**D2 契约形态 = 复用 `LoginVO`，以 `token == null` 表示"注册成功但未自动登录"**——否决"新增 `RegisterVO` / 加新字段"：会造出与 `LoginVO` 同形的第二份 VO（正是 U-19/**T14** 要清扫的"同形二分"），且现在注册成功路径的字段集与 JSON 形状**逐字节不变**（conftest 与既有 pytest 消费方零改动）；否决"复用 `msg` 传提示"：`api.js` 的 `request()` 只把 `data` 交给调用方，`msg` 对前端不可见，要暴露就得改全站请求封装（波及所有端点）。**D3 catch 范围 = `BusinessException`**（登录失败的全部既有形态）；非业务异常照旧冒泡成 500 + `ExceptionFilter` 日志，**不放宽异常语义**。**D4** 失败记 WARNING 日志（userId + 异常），不静默吞。**D5** 前端只在 `views/login.js` 加分支（`!data.token` → 提示 + 切回登录 tab + 手机号预填），**不 `navigate`**（不改前端路由行为）。
> * **落地**：① `UserService`（250 → 271）新增 `registerAndLogin(RegisterCommand)` = `registerAsUser` → `login(id, pw)`，catch `BusinessException` → `new LoginVO(id, rc.getUsername(), null)`；注册与自动登录仍是**两个独立事务**（注册先提交，再做登录查询，语句集与改造前一致）；`registerAsUser` / 两个 `login` 重载**签名与语义零改动**。② `LoginController`（95，行数不变）`register` 收敛为单行调用。③ `views/login.js`（105 → 115）`doRegister` 增 `!data || !data.token` 分支（先 `showToast` 再 `if (!state) return;` —— 请求期间视图已 unmount 时只提示不碰 DOM，见评审 🟡①）。④ 单测 `UserServiceTest`（282 → 357；**24 → 29 例**）：新增 5 例 = 成功路径仍返回 token / 自动登录三类失败（查不到、密码不匹配、登录期 `SQLException` → `DatabaseException`）均兜底为 `token=null` 且 `addUser` 已提交 / **注册本身失败仍抛 `DuplicatePhoneException` 且不进入登录查询**（`verify(never)`）；另抽 `stubRegisterSuccess()` 打桩 helper（原 `registerSuccessHashesPasswordAndReturnsId` 三行打桩等价替换，无行为变化）。
> * **明确不做**：不自动重试 / 不自动补登；不新增响应字段、不动 `ResultUtil` 与 `api.js`；不改注册/登录既有成功语义与错误码；不新增 pytest 用例（理由见覆盖边界）。
> * **验证（落盘证据）**：`python tools\tv.py test junit` **exit 0**、`latest.json` junit = **511 / 0 / 0 / 0**（506 → 511；`UserServiceTest` 24 → **29**，surefire 逐类求和复核一致）；`python tools\tv.py test all` **exit 0**（`build_ok=True`、pytest **145 passed / 0 failed**、`port_18080_open_after=false`）。**pytest 用例数不变**（无新增/改写）。
> * **红线合规**：未改 `@WebServlet` URL / web.xml / IoC 扫描；无新依赖；未改注册/登录既有成功语义与错误码；未改前端路由行为（仅视图内切 tab）；无 DDL；`RegisterDTO`/`RegisterCommand`/`LoginVO` 形状未动。
> * **覆盖边界（如实记录）**：① 兜底分支**只有 JUnit 断言**（C1：逻辑契约 + DAO 打桩），E2E 无法构造该失败（见 D1）；pytest 侧为**回归**——`test_smoke.py::test_S01_register_returns_token` 已断言「注册 → 200 + 有效 token」，正是本任务成功路径的端到端守卫（用例数 145 不变）。② 前端 `token=null` 分支**仅代码审查、无自动化断言**（项目无 JS 测试层；该分支在视图函数内、依赖 DOM 与多个模块 import，无法用一次性 Node 核验）——本次只做 `node --check` 语法核验（一次性、未落库）。③ 未断言"兜底响应的 HTTP 逐字节"（JUnit 不碰 HTTP、Controller 无测试层）——由 `LoginVO` 形状未变 + 前端以 `token` 判空共同保证。
> * **G11 记录**：L1 ×1（四要素"入口线索"点名落点 `LoginController.register`，实际把编排下沉到 `UserService`——属执行方案细化、非语义/范围变更，理由见 D1）。L2/L3/L4 无。
> * **文档（G4 同 commit）**：`常青/CURRENT_ARCHITECTURE.md`（头部 3.8 + user 域模块表两行 + 7.1 `/user/register` 行）、`常青/BUSINESS_FLOW.md`（头部 2.4 + 2.2 步骤表与接口定义注 + 7.2 公开接口行）、本档三节/四节、`NEXT_CYCLE_NEEDS.md`（4.0 决策段 + 4.3/五节状态）、`.docs/INDEX.md` 周期状态行、`UNPLANNED_ISSUES.md`（U-16 关闭留痕）。**未写 DDL、未动周期约定**。
> * **commit 主题口径**：四要素"期望 commit 主题"= `fix(user)`，与实际一致（域 scope + `fix`）。
> * **独立评审（subagent，只读）结论与处置（2026-09-20）**：**无 🔴**，总体判定"可提交"；**🟡 1 条**（自报与实列一致）：
>   ① 🟡 `views/login.js` 兜底分支的 `switchTab('login')` 依赖 `state`——请求期间若视图被 `unmount()`（`state = null`）则 `switchTab` 抛 `TypeError`，被外层 `catch` 捕获 → 实际注册成功却 toast「注册失败」→ **已采纳**：兜底分支改为**先 `showToast('注册成功，请手动登录')`，再 `if (!state) return;`**（请求期间已离开视图时只提示、不碰 DOM），之后才 `switchTab` + 预填。
>   评审另行核对通过：成功路径与改造前逐字段一致（`LoginVO` 三字段 + 事务语句集与顺序）；`registerAsUser` 与两个 `login` 重载零改动；`BusinessException` 覆盖 `login` 全部失败出口、非业务异常照旧 500；`BaseServletUtil.mapper` 无 `NON_NULL` → `token:null` 必落 JSON；`request()` 仅在 `code===200` 返回 `data` → 注册失败（非 200）**不可达**兜底分支；5 个新用例均非恒真（异常未捕获则测试失败而非通过）；`stubRegisterSuccess` 与旧内联打桩逐条等价（`git show HEAD` 对比）；`registerAsUser` 仍有调用方、非死代码；文档行数经 `wc -l` 复核一致（`LoginController` 95 / `UserService` 271 / `login.js` 115 / `UserServiceTest` 357）。
> * **评审后复跑（2026-09-20，处置后同一工作树）**：`test junit` exit 0、**511 / 0 / 0 / 0**；`test all` exit 0、`build_ok=True`、pytest **145 passed / 0 failed**、`port_18080_open_after=false`。**两层数字与首次一致**（本轮只改 `login.js`）；另复跑 `node --check`（一次性、未落库）通过。

### T14 包层结构清扫：content↔comment 环 + 分页信封上移公共包 + `ResultMap` 反向依赖（R-02/R-03 + 池 U-19 + N14）

* **入口线索**：`content.ContentService` 注入 `comment.CommentService`（content→comment）；comment 域引用 `content.service.ContentCache` / `CommentCache`（comment→content）→ **包层环**（非 IoC/Bean 环）；`content/model/dto/PageResult` 与 `follow/model/dto/FollowPageResult` 字段与 JSON 形状完全一致（**同形二分**）；`dao/ResultMap.java:2` **反向 import** content 域的 `CommentCacheDTO`（基础包依赖业务模型，N14）。目标：包/依赖结构清扫，**行为零变化**。
* **红线边界**：**只做搬移与依赖调整**——不改接口契约、不改 JSON 形状、不动业务逻辑；不做跨域功能重构；不顺手改 `@WebServlet` URL / web.xml / IoC 扫描。
* **强制探索步骤**：动刀前先 (0) 复核 R-02（= 池 U-07）与 U-19 证据仍成立 (1) **先取得 R-03 拍板结论**（环的处置口径：拆共享组件 / 允许现状不治；信封上移的目标包与范围——涉 content 域 6 个 main 文件 + 4 个测试文件 import 调整）——未拍板 → L3 暂停 (2) 确认上移后 `JacksonCodec` 序列化与前端解析不受影响（JSON 形状不变） (3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：按拍板口径，包依赖单向（或环已按口径处置）；`PageResult` 唯一源落在公共包、无同形二分；`dao` 基础包不再 import 业务域模型；全量 JUnit + pytest 全绿且对外行为零变化。

### T15 顺手清理：架构文档与代码一致性（N16 + N9 文档部分）

* **入口线索**：`常青/CURRENT_ARCHITECTURE.md:72` 目录树里的 `ssm_*` 实际不存在；同文件 `:135` 的 AuthFilter 精确名单只列 7 项、代码实为 10 项（缺 `/content/update`、`/content/mediaDelete`、`/content/delete`）。**（2026-09-20 T12 窗口补充）** 四.3 业务域包模块表的**行数大量滞后**（T12 已顺手修正 content 域 service 单元格，其余未动）：`comment` 域 `CommentService` 197→实测 **299**、`CommentDao` 179→**343**；`user` 域 `UserService` 241→**250**；`like` 域 `LikeService` 205→**215**、`LikeCacheService` 394→**393**——请按 `wc -l` 实测逐格核对（含职责描述是否已随 T10/T11 失效）。目标：把"文档 vs 代码"的事实差异**一次扫完并修正**。
* **红线边界**：只修事实错误，不做章节重写/扩写（颗粒度纪律见该文件"维护说明"）；不改代码、不改业务语义。
* **强制探索步骤**：动刀前先 (0) 复核 N16/N9 证据 (1) **交叉核对全量清单**：`@WebServlet` 全量 URL ↔ 架构文档七节 API 表 ↔ `AuthFilter` 两个名单（一次扫完，别只修这两处） (2) 发现其它滞后章节 → 能顺手修的修，超出范围的登记（池 / NEEDS） (3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：目录树、鉴权名单、URL 表与代码一致（全量 grep 对照 + 抽查）；`git diff` 只含事实修正；纯文档批（不跑测试）。

### T16 顺手清理：Controller 基建收敛（N11）

* **入口线索**：`controller/BaseServlet.java:17-19` 与 `controller/BaseServletUtil.java:17-19`（ObjectMapper 配置逐字重复）；两类 `writeSuccess/writeError` 方法体逐字相同；`controller/RequestParser.java:11`（第三份 `new ObjectMapper()`，未注册 JavaTimeModule / 未禁 timestamps）；`BaseServletUtil.java:15`（工具类却 `extends HttpServlet`，无任何 URL 映射）。目标：收敛为**唯一 mapper + 唯一响应写出实现**，去掉冗余继承。
* **红线边界**：不改响应 JSON 形状、不改 `@WebServlet` URL / web.xml / IoC 扫描、不改业务语义；**只做收敛与去重**；不引入新依赖。
* **强制探索步骤**：动刀前先 (0) 复核 N11 证据 (1) 确认三份 mapper 配置差异对**现有 DTO 的实际影响**（请求体里有无 `java.time` 类型 / 日期字符串字段）——若统一配置会改变既有解析行为，则该处不合并或显式注明差异，**不得悄悄改行为** (2) grep 全部 `writeSuccess/writeError` 调用点，确认可见性（`public`/`protected`）与重载签名不变 (3) 去掉 `extends HttpServlet` 前确认无 web.xml / 注解把它注册为 Servlet、无 `getServletConfig()` 类调用——若清单未覆盖 → 回写本文档再动手。
* **验收**：mapper 唯一源；无重复方法体；`BaseServletUtil` 不再继承 `HttpServlet`；抽若干端点 pytest 核对响应**逐字节不变**；全量 JUnit + pytest 绿。

### T17 IoC 注入解析失败可观测（N12 前半）

* **入口线索**：`ioc/IocContainer.java:140-141`（`Object dependency = beans.get(field.getType()); if (dependency != null) { ... }` —— 取不到即静默跳过、不报错）；两类注入并存：Controller 用 `@Inject` 字段注入（`content/controller/FeedController.java:19`）、Service 用 `@InjectConstructor`（`content/service/FeedService.java:30`）。目标：**注入失败不再静默**（至少可观测）。
* **红线边界**：**不改变注入语义**（构造器优先、字段兼容、`@PostConstruct`/`Disposable` 顺序不动）；**不做声明式事务 / AOP**（属新功能方向，另行评估）；不引入新依赖。
* **强制探索步骤**：动刀前先 (0) 复核 N12 证据 (1) **先做静态核对**：列出全部 `@Inject` 字段类型 vs 容器实际注册的 Bean 类型，确认当前是否存在"静默未注入"的实例（有 → L2 记录，先修数据/代码再谈机制） (2) 明确落点与形态：**建议先落"启动期 WARNING + 未注入清单汇总"**（可观测，但不把既有隐性问题变成启动失败）；**是否升级为 fail-fast 抛错由用户拍板**（G7） (3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：注入解析失败有明确日志/汇总（可复现验证：临时造一个未注册依赖，观察是否被报出）；启动无新增失败；全量 JUnit + pytest 绿。

### T18 配置卫生：本机绝对路径可外部覆盖 + `AppConfig` 带默认值读取（N13）

* **入口线索**：`app.properties:32`（`upload.path=D:/data/projects/VideoPlatform/stone` —— 本机绝对路径随包入库）；`config/AppConfig`（每键一个静态 getter，新增配置要改多处）。目标：换环境时**不改源码 / 不重打包**即可覆盖（默认值不变）。
* **红线边界**：**默认值不变**（行为零变化）；不引入配置框架/新依赖；不改本机说明类文档（如 `说明书/AVAILABLE_TOOLS.md`）；分页上限参数化**已随 T10-B/T11-A 落地**（`parsePageSize(req, max)` / `(req, max, defaultSize)`），不在本任务。
* **强制探索步骤**：动刀前先 (0) 复核 N13 证据 (1) 梳理 `upload.path` 的全部消费点（`AppConfig` / `FileUploadService` / `context.xml` / 媒体巡检工具）与覆盖机制的最小改动面（环境变量 / 启动参数 / 外部 properties 的优先级） (2) 确认给 `AppConfig` 增加"带默认值读取"不改变既有 getter 语义 (3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：以外部方式覆盖 `upload.path` 生效且默认行为不变（可复现验证）；全量 JUnit + pytest 绿。

### T19 feed / search / profile 的后端大分页（域级上限 + 域级信封）

> **来源（2026-09-20 T11 窗口推后项）**：T11 窗口拍板"固定信封只做 follow 与评论；feed 与 search（含 profile 创作网格）的后端改动忽略，只做前端顺手迁移"→ 后端侧补齐另立本条，编号不悬空。

* **意图**：把三处内容列表的后端口径补齐——域级 `pageSize` 上限与域级信封大小，使前端可"只传 `page`"；T11-B 已把三处前端迁到公共 helper（`chunk=50` 受现有 cap 限制），本任务把 cap 提升后 chunk 可随之上调。
* **入口线索**：`content/controller/FeedController.java:26`、`content/controller/ProfileController.java:26`（均 `BaseServletUtil.parsePageSize(req)`）、`content/controller/SearchController.java`（`keywordSearch` 分页解析处——**⚠️ T11-B 窗口实测：该处 `page`/`pageSize` 不走 `BaseServletUtil`，`pageSize` 默认 12 且无上限，直接进 `SearchDTO`；上限归一需连解析点一并参数化**）、`views/follow.js`、`views/search.js`、`views/publish.js`、`views/user.js`（三处前端已在 T11-B 接入 helper，`chunk = 50`）。
* **红线边界**：只放开上限与信封口径 + 前端 chunk 上调；**不改分页语义 / 返回集 / 排序 / 跳过 null 口径**；`/profile` 的 `ProfileVO{contentPage}` 形状不变；不动 `/feed` 的关注 ids 全量读路径（R-01 保留）；不引入新依赖。
* **强制探索步骤**：动刀前先 (0) 复核三处 Controller 上限现状（公共 50）与前端 chunk 现状（T11-B 后为 50） (1) 定各域限额（建议 `200/200`）与前端 `batch` 的匹配 (2) 确认 `publish.js` 与 `user.js` 共用 `/profile` 的一致性（一次改两处消费） (3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：三域 `pageSize` 上限 200 生效、只传 `page` 返回域级信封；前端三处改为只传 `page` 且 chunk 上调；相关 pytest 全绿（如有上限断言同步改写）；全链 exit 0。
