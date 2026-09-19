# 下一周期任务清单

> 状态：**杂务/准备周期（分支 `prep/next-phase`）**——不做正式功能方向，处理代码卫生与铺垫清理。方向（R-03）已于 2026-09-18 拍板拆任务：T1~T6 第一批（基座/卫生，优先执行）+ T7~T9 第二批（用户点名主菜）。**进度：T1~T6 已全部完成，下一窗口进 T7（第二批主菜）。**
> 来源：`目标与任务/NEXT_CYCLE_NEEDS.md`（决策唯一源）4.1 的 N1~N14 + 二/三节结转项；N10 转留池（`UNPLANNED_ISSUES.md` U-15）；U-14 本周期不做（与 T7/T8 撞同一批 `FollowService` 方法，落地后再评）。
> 明确不做：R-02 索引全量读（明确保留）；R-04 包层环（继续待定）；U-11 停机 DB 兜底（留池）；正式功能方向（feed/日志体系/新功能另开分支）。
> **变更纪律（2026-09-18 用户拍板）**：本档无"变更记录"节——变更以 git 提交历史为准（模板已同步移除，沿用旧版时把变更记录节删掉）。

---

## 一、周期约定（方向拍板后已校准）

| 编号 | 约定 | 内容 |
| ---- | ---- | ---- |
| G1 | 一任务一窗口一 commit（默认期望） | 每个任务开独立窗口，默认期望 1 个 commit；因任务内部依赖需拆多 commit 或小任务合并时，**在该任务详情标注**；commit message 强制带任务编号，**本周期前缀 = `prep-0N`**（如 `chore(prep-01)`，N=任务号；T1 规范定稿后 T2~T9 按 T1 交付的 TYPE(SCOPE) 口径写，编号仍带 prep-0N）；引用任务一律用 `T1`~`T9`，不用 commit scope |
| G2 | 开窗协议（输入） | 新窗口顺序读取：① `.docs/INDEX.md` → ② `目标与任务/NEXT_CYCLE_NEEDS.md`（**决策节必读**：C-#、R-##、4.3 本周期范围、4.1 的 N#）→ ③ 本任务清单（一/二节约定，含 G11 质疑协议 + 当前任务） → ④ 常青文档（`常青/CURRENT_ARCHITECTURE.md` + `常青/BUSINESS_FLOW.md` 相关章节）→ ⑤ 上一个任务 commit |
| G3 | 收窗协议（输出） | ① 跑测试与反馈：每任务 = 编译 + 相关单测 + **相关端点 pytest 回归**；全量回归在影响面大的任务与收尾任务各跑一次；测试执行与回传依实际窗口环境安排，不指定角色 ② 勾选任务清单状态 → ③ 涉及架构/业务改动时同步常青文档 → ④ 提交 |
| G4 | commit 语义闭环 | 代码改动 + 其对应常青文档更新 + 任务清单勾选进同一 commit；message 强制带任务编号（T1 后按新规范） |
| G5 | 超范围暂停规则 | 执行中发现需求歧义、或任务实际远超预期（判定锚：触及清单未列模块 / 需新依赖 / 需改业务语义 / 改动面较预估倍增）→ 停在第一个决策点，回写任务清单（拆/改）并按 G11 分级登记，不得硬扛、不得擅自扩大范围 |
| G6 | 评审与返工 | 评审以"任务验收标准 + 测试结果"为准；返工记录在任务清单；连续返工 ≥2 次 → 执行 Agent 停手并登记"建议返回周期设计重新评估"，由**用户**裁决是否重开周期设计 |
| G7 | 决策唯一源 | `NEXT_CYCLE_NEEDS.md` 决策节为唯一决策源；窗口内发现新决策 → 回写该节并标记"已定/待定"，不许自行拍板 |
| G8 | 分支与合并 | **开分支 / 合并回 integration / 合入 master 均由用户手动执行**；任务窗口只负责本任务的代码、测试与 commit（G1），不自行创建/切换分支、不合并 |
| G9 | DDL 备份 | **本周期无 DDL**（分页不动表结构；follow 分页若升级 Redis 结构属缓存层不动 DB）。若出现例外 → 执行前先备份库结构与建表语句到 `.docs/DBbackups/`，并向用户报备 |
| G10 | 脚本规范 | 脚本一律 Python；临时一次性脚本放 `temp_script/`，长期复用/自动化脚本放 `tools/`（工具统一入口 `tools/tv.py`） |
| G11 | 质疑协议 | 执行 Agent 对 Why 层（NEEDS 真实性/必要性）与 How 层（任务/验收可操作性）**有质疑权、亦有报告义务**：四时点触发 + L1~L4 分级动作（完整协议见二节“质疑协议”）；**裁决权永远在用户**（G7 决策唯一源不破） |

---

## 二、任务清单理念与任务模板

> **任务清单理念**：本清单只回答"**要做什么、不做什么**"，**不提前过度详细设计**——四要素是骨架，具体执行方案由执行窗口的 Agent 探索细化。
> **红线边界的目的是"防跑偏"，不是把 Agent 限制死**：因此只列"明显越界"的项，不穷举做法；执行中若发现红线本身阻碍了正确做法，按下方"红线措辞约定"先申请再动手。
> 反面清单同样是清单的一部分：**"明确不做"要写出来**，避免 Agent 顺手扩张。

> **任务模板（每任务必含四要素）**：入口线索（从哪找）+ 红线边界（别碰什么）+ 强制探索步骤（动手前先确认什么）+ 验收（怎么算完成）。执行 Agent 允许动态调整，但**调整前先回写任务清单/需求文档（G5/G7），再动手**。

> **红线措辞约定（延续）**：红线只列"明显越界"的项，作用是**防跑偏，不是把执行 Agent 限制死**——不穷举做法、不做"一刀切禁止"。
> 若执行中发现**某条红线会阻碍正确做法**（过紧、过窄、或已不适用）：不允许硬扛，也不允许自行放开，**先向用户说明理由并申请调整**；获批准后按新口径动手，未获批准则维持原红线。

> **编号引用约定（延续）**：禁裸编号引用已归档周期元素（引用一律写 `<周期>/<编号>`）；裸编号仅指本档内部定义的元素（`T#`）与 `NEXT_CYCLE_NEEDS.md` 内部元素（C-# / R-## / N#）。执行中发现引用歧义 → 回写清单用文字澄清，不得自行猜义。

> **质疑协议（G11，2026-09-15 确立）**：执行 Agent 对 **Why 层**（NEEDS 真实性/必要性）与 **How 层**（任务可行性/验收可操作性）均有质疑权，亦有报告义务；**裁决权永远在用户**（G7 决策唯一源不破）。四个触发时点：① **开窗阅读**——文档内部矛盾；② **动手前**——强制探索步骤第 (0) 项复核证据不成立；③ **探索中**——代码事实与 NEEDS 描述矛盾 / 需求疑似已被覆盖 / 触发 G5 判定锚；④ **验收时**——验收标准全过但 4.1 对应痛点场景仍未消除。
>
> 分级动作：**L1 仅记录**（不影响本任务语义）写进执行回写；**L2 带疑继续**（影响后续任务/验收解读但不影响本任务）在 NEEDS 对应条目标"待裁决"照常执行；**L3 暂停**（前提证伪 / 需改语义扩范围）停在第一个决策点登记质疑待用户裁决；**L4 申请调整**（红线/约定阻碍正确做法）沿用"红线措辞约定"。
>
> **质疑记录格式**（Why 层落 `NEXT_CYCLE_NEEDS.md` 4.0；How 层与 L1 落本清单"执行回写"）：
> **质疑 <N#/R-##/C-#>（<日期>，T# 窗口，L<级别>）**：触发：…；证据：…；主张：…；建议：…；当前动作：…；用户裁决：…。
> **质疑成立后的去向**：对应 R/N 编号显式流转——转 `UNPLANNED_ISSUES.md` 留池 / 结转下周期 NEEDS 二节 / 在 git 提交信息记录废弃原因；任务清单中"搁置"状态的 T# 同理，对应编号不得悬空。**两条禁止**：不许为过验收绕过痛点本质（见任务模板"验收"）；不许以质疑为由擅自改语义/扩范围。

```markdown
### T# 任务标题

* **入口线索**：…
* **红线边界**：（只列"明显越界"的项，作用是防跑偏；若某条红线会阻碍正确做法 → 先说明理由申请调整，获批准后按新口径动手）
* **强制探索步骤**：动刀前先 (0) 复核本任务对应 NEEDS 编号（N#/R-##）的证据仍成立（G11——不成立 → L3 暂停登记质疑） (1) 检索… (2) 确认… (3) 若清单未覆盖 → 回写本文档再动手
* **验收**：…（验收通过 ≠ 目的达成：标准全过但痛点场景未消除 → 按 G11 登记质疑，不得直接标已完成）
```

---

## 三、任务总览（本周期 9 任务）

> **DDL 标注（G9 要求）**：本周期无 DDL。

| 编号 | 标题 | 对应候选 | 依赖 | 验收关键（动态） | 期望 commit 主题 | 状态 |
| -- | -- | ---- | -- | -------- | ------------ | -- |
| T1 | commit message 规范定稿 | N13 | 无 | 规范文档落地 + 模板 G1/G4/C-3 与延续约定同步 | `docs(prep-01)` | **已完成** |
| T2 | 常青文档瘦身（含头部版本对齐 N9） | N12 + N9 | T1（颗粒度口径） | 更新日志收敛（一条一行摘要或移除）、头部版本与内容一致、BUSINESS_FLOW 注记收敛；行数显著下降 | `docs(prep-02)` | **已完成** |
| T3 | pom Kotlin 残留清理 | N1 | 无 | 移除 kotlin 三件套后 `mvn -o` 编译 + 全量 JUnit 仍绿 | `build(prep-03)` | **已完成** |
| T4 | git 卫生：.idea 出库 + temp_script 例外出库 | N2 + N3 | 无 | `git ls-files` 无 `.idea/`、无 `temp_script/`；`.gitignore` 覆盖 | `chore(prep-04)` | **已完成** |
| T5 | 随手清理：死注释删除 + 分页解析收敛 | N4 + N5 + N6 | 无 | RequestParser/web.xml 注释块删除；parsePage/parsePageSize 收敛公共并替换调用点；相关单测绿 | `refactor(prep-05)` | **已完成** |
| T6 | 日志卫生：LogUtil 合规 + CountRepairTool 去留（R-05） | N7 + N8 | 无（R-05 开工前拍板） | LogUtil 内部改走 logger；CountRepairTool 按用户拍板删/迁 | `chore(prep-06)` | **已完成** |
| T7 | 关注/粉丝列表分页 | N11a（U-12） | T5 | `/follow/following\|followers` 支持分页参数、默认兼容；缓存/DAO 分页载体定稿；前端 follow.js 分页 | `feat(prep-07)` | 待执行 |
| T8 | 评论列表分页 | N11b（U-13） | T5 | `getCommentsForContent` 主楼分页 + 楼中楼整树；默认兼容；前端 detail.js 加载更多 | `feat(prep-08)` | 待执行 |
| T9 | 测试目录本地化 | N14 | 无（探索先行） | 沙箱边界探明报告 → 目录/端口本地化方案定稿 → 实现后实测沙箱内可跑或明确判定不可行 | `chore(prep-09)` | 待执行 |

> 状态取值：草稿 / 待执行 / 执行中 / 已完成 / 搁置。搁置的 T# 必须注明其"对应候选"编号去向（转 `UNPLANNED_ISSUES.md` / 结转下周期 NEEDS 二节），不得悬空（G11）。

> **顺序理由**：**T1 最先**——commit 规范决定 T2~T9 每个 commit 的写法（前提 → 不先定则后续全部用旧格式）；**T2 紧跟 T1**——常青瘦身的颗粒度口径依赖 T1 定稿；T3/T4/T6 互相独立、无依赖，可与 T1/T2 任意穿插（优先级：T1>T2>T3>T4>T5>T6）；**T5（N6 分页解析收敛）必须先于 T7/T8**——T7/T8 的新分页接口直接复用公共方法，避免再复制第三份；T7/T8 依赖 T5，独立于其他；T9 探索先行、独立最后评估。T1~T6 为第一批（基座/卫生），T7~T9 为第二批（用户点名主菜）。

> **回填要求**：任务执行后在本节与"四、任务详情"同步状态与"执行回写"；拆/改任务必须在"对应候选"列保留 NEEDS 编号，保持 Why→How 可追溯。

---

## 四、任务详情

> **共通注（本周期通用）**：本周期为杂务/准备周期——**不引入任何新依赖**（T3 Kotlin 移除属"删"不属"加"；T7 若需 Redis 结构升级仍用 Jedis 既有命令）；技术红线沿用——禁 Spring/SpringBoot/MyBatis、不擅改 `@WebServlet` URL / web.xml 生效配置 / IoC 扫描、不为实现便利改业务逻辑语义；**变更纪律**——不维护文档级变更记录，变更以 git 提交历史为准；**脚本规范**——Python，临时脚本 `temp_script/`，长期工具 `tools/` + `tv.py` 收录；**改动业务代码同步维护单元测试与 pytest**（AGENTS 适配：T7/T8 属接口行为变化，必须同步测试）；**分页类接口默认兼容口径**——新增可选 `page/pageSize` 参数、缺省行为与现状一致（全量），避免破坏既有调用方（前端改造后新参数生效，属预期变化但缺省兼容）。
> **红线口径（与二节"红线措辞约定"一致）**：下面各任务的"红线边界"只列"明显越界"的项，作用是**防跑偏**、不穷举做法；若某条红线会阻碍正确做法（过紧 / 过窄 / 已不适用）→ 说明理由**申请调整**，不许硬扛、也不许自行放开。

### T1 commit message 规范定稿（N13）

* **入口线索**：`git log` 历史 commit（`002cb75`/`5610a9d`/`42fcfb5` 等超长正文无分节样本）；模板 `周期模板_NEEDS.md`/`周期模板_TASKS.md` 中 G1/G4/C-3"message 强制带任务编号"现文。目标：**定一版本仓库 commit message 规范**（type 表 + `SCOPE` 约定 + header 长度限制 + 正文固定分节），并回写模板。
* **红线边界**：不改历史 commit（不 rebase/rewrite）；规范不引入外部工具（不装 commitlint 等新依赖）；不定规"必须英文/必须中文"这类与现仓语言习惯冲突的硬性条文（可在规范里给出项目惯例建议）。
* **强制探索步骤**：(0) 复核 N13 证据（历史 commit 臃肿、无分节）仍成立 (1) 统计现仓 commit 常用 type（refactor/fix/docs/chore/feat/test 等）与 scope（cache-0N/package 等），据此设计 type/SCOPE 只有已用或合理新增的集合 (2) 与"变更纪律（不设文档变更记录，git 历史即记录）"对齐：正文分节应承载原"变更记录/更新日志"的职责，确认分节（建议：问题 / 方案 / 验证 / 文档）足够回溯 (3) 确认模板回写点：`周期模板_TASKS.md` G1/G4 + `周期模板_NEEDS.md` C-3/一续约定，写入规范要点与"本周期按规范写"句子——若清单未覆盖 → 回写本文档再动手。
* **验收**：规范文档落盘（建议 `.docs/说明书/COMMIT_CONVENTION.md` 或并入模板——执行窗口定，用户可改）；两份周期模板已同步（G1 的 type 表/SCOPE/分节引用规范，不再只写"强制带任务编号"）；`git log` 抽查 3 条后续 commit 符合规范（本任务自身 commit 即示范）。
* **执行回写（2026-09-18，docs(prep-01) 已落地）**：强制探索结论：现仓常用 type = refactor/fix/docs/chore/feat/test/build，SCOPE 天然存在模块与周期令牌两类 → 规范 type 表按现仓已用 + 合理新增定 7 类；正文分节定为「问题/方案/验证/文档」四节，承载原"变更记录/更新日志"职责（与变更纪律闭环）。实现：规范落盘 `.docs/说明书/COMMIT_CONVENTION.md`（v1：结构/type 表/scope 规则/header≤40 字/正文分节/任务编号/2 示例——周期立项示范 + 常规 fix 示范）；模板只留引用——`周期模板_TASKS.md` G1/G4、`周期模板_NEEDS.md` C-2/C-3 均改指规范；INDEX 说明书表新增 COMMIT_CONVENTION 行。验收对照：规范落盘 ✓ / 模板同步 ✓ / 示范 commit `c1efa1b`（周期立项）与本次 T1 commit 均按规范书写 ✓。验证：纯文档批，无代码改动，无需跑测试。L1 质疑：无。

### T2 常青文档瘦身（N12 + N9）

* **入口线索**：`常青/CURRENT_ARCHITECTURE.md`（887+ 行，"十二、更新日志"L844-883 单条 400+ 字）；`常青/BUSINESS_FLOW.md` 逐 commit 注记。目标：**将常青收敛为"地图/导航"颗粒度**——按 T1 定稿口径，更新日志每条压成一行摘要（或整体移除、指引 git 历史），头部版本与内容一致（N9：2.23/09-16 vs 更新日志 2.26/09-18），BUSINESS_FLOW 注记同样收敛。
* **红线边界**：**不改文档内容的事实性描述**（结构/模块/API/流程 现行状态必须保留，只压缩"历史流水"）；不删任何"现行有效"的配置/接口/结构信息——误删即破坏唯一事实源；更新日志移除前确认 commit 历史可回溯（git 有记录即可放心）。
* **强制探索步骤**：(0) 复核 N12/N9 证据（更新日志行数与头部滞后）仍成立 (1) 以 T1 交付的规范读一遍更新日志，判断哪些是"流水账"可压、哪些承载了正文未记录的关键决策（如 2.14 负缓存契约、2.17 header 未 bump 的 L1——这类决策信息若 commit 已有则压，若只有文档有则保留摘要进 4.0 或正文对应节）(2) 确认 PROJECT 内部是否已有规划把"决策明细"落点（NEEDS 4.0 已回写机制）——避免瘦身后关键决策无处溯源 (3) 统计瘦身前后行数差，明确收敛目标（如更新日志节 ≤ 30 行）——若清单未覆盖 → 回写本文档再动手。
* **验收**：CURRENT_ARCHITECTURE 头部版本号与内容一致（bump 到 3.0 或对应版本）；"十二、更新日志"收敛为一行摘要式（或无，改引 git 历史）；正文现行事实未删（抽查模块表/API 清单/Redis key 表仍完整）；BUSINESS_FLOW 注记收敛；JUnit/pytest 无需跑（纯文档，但跑一次 `tv.py` 确认环境无碍）。
* **执行回写（2026-09-18，docs(prep-02) 已落地）**：强制探索结论：N12/N9 证据复核成立（CA 887+ 行/140KB、更新日志单条 400+ 字；头部 2.23/09-16 vs 日志 2.26/09-18；BF 头部 1.0 vs 文末历史 1.1、3.1 注记叠加）。更新日志通读结论：关键决策（负缓存契约/SCARD 否决/authorName 方案 A 等）均已落 CA 正文对应小节 + NEEDS 4.0 + git commit，**无"仅日志独有"的决策** → 可整节删除；决策明细落点确认 = git commit 正文四节（T1 规范）+ NEEDS 4.0 + TASKS 执行回写 + archive，常青不再重复。收敛目标：更新日志节 ≤30 行 → 实际删除整节改 6 行指针。实现：CA 头部 bump 3.0/日期对齐 09-18；六节 22 小节按主题归并为 16 小节、去除全部"第N期 T# 新增，治 X"标注与验证/运行时验证/review 段落（只留现行事实 + 关键决策摘要）；九节测试明细表（130/434 例）压为指针引 TEST_AUTOMATION.md；十节代码统计行数删除（四节包表已承载）；十一节过时 DI 图删除（ioc 机制在 4.2）；十二节更新日志删除 → 新增"十、变更记录"指针（引 git + COMMIT_CONVENTION）；维护说明重写为新纪律（只改事实章节 + 头部，不设变更记录）。BF：头部 1.0→2.0 对齐；3.1 注记块（三期/四期/五期约 90 行）压为 3 行指针 + 关键语义保留；2.4 标题与流程表（4.1/4.3/3.9/3.10）周期标注清理；八节已发现的问题删除（问题2/5 转 UNPLANNED_ISSUES **U-16/U-17**，问题9 已由 1.4 配置化解决）；九节重构检查清单删除（pytest/JUnit 已全覆盖）；十节 API 汇总删除（与 CA 七重复且自身矛盾）；文末文档历史删除。验证：纯文档批，无代码改动；`tv.py` 环境确认无碍；不跑 JUnit/pytest。L1 质疑 1 条：CA 7.2 与 BF 7.2 原列 `/detail`（无对应 servlet，代码 `@WebServlet` 实证 14 URL），统一修正为 `/search/IdSearch`（与 BF 3.6 自注一致）——事实修正非误删。行数：CA 887+→624（140KB→48.2KB）、BF 1637→1312；合计 2530→1936（-23% 行数 / -58% 体积，CA 占大头）。

### T3 pom Kotlin 残留清理（N1）

* **入口线索**：`pom.xml` L19（`kotlin.version`）、L60-70（kotlin-stdlib-jdk8 / kotlin-test）、L132-160（kotlin-maven-plugin + maven-compiler-plugin 默认 execution 被置 phase=none 并显式重建）。目标：**移除 Kotlin 三件套并保持构建全绿**——无任何 .kt 源文件，Kotlin 参与构建无意义；移除后确认 Java 编译由 maven-compiler-plugin 显式 execution 承接。
* **红线边界**：**不动编译产出结构**（构建后仍产 war、class 位置不变）；不改 Java 源码；**不新增/替换依赖**；若发现 maven-compiler-plugin 移除 Kotlin 后编译中断或产物异常 → 不是硬删，回写本任务按 G11 申请调整（可能需补 compiler args 或改回 maven 默认执行）。
* **强制探索步骤**：(0) 复核 N1 证据（git 全历史无 .kt、磁盘无 .kt）成立 (1) 先 `mvn -o`（IDEA 内置 maven3 路径见项目记忆）跑一次 **clean + compile** 建立"移除前"基线通过与耗时 (2) 移除三件套后复跑 clean + compile + test（JUnit，surefire），对比基线；确认 jacoco 仍出报告 (3) 抽查 `target/classes` 字节码完整性 + `javap` 一个既有类确认产物正常——若清单未覆盖（如隐式依赖谁在用 kotlin）→ 回写本文档再动手。
* **验收**：pom.xml 无 kotlin 关键字；`mvn -o clean test`（或 tv.py test junit）JUnit 全绿且与移除前数量一致；编译产物结构不变；commit 里带 pom 变更说明与回归结果。
* **执行回写（2026-09-19，build(prep-03) 已落地）**：强制探索结论：N1 证据复核成立（磁盘/`git log --all` 全历史零 .kt、`src/` 零 kotlin 引用、pom 三处残留实证）；基线 `mvn -o clean compile` 通过且实录 `kotlin:2.2.20:compile` 报 "No sources to compile"、128 个 Java 源由 `maven-compiler-plugin:3.13.0` 显式 execution 编译——D1（Java 编译由 compiler 显式 execution 承接）判断成立。实现：删除 `kotlin.version` 属性 + `kotlin-stdlib-jdk8`/`kotlin-test` 依赖 + `kotlin-maven-plugin` 插件块共 3 处；maven-compiler-plugin 显式 execution / surefire / jacoco / stage8.buildDir 均不动。验收对照：pom 无 kotlin 关键字 ✓；`mvn -o clean test` JUnit 430+4（MyConnectionPoolTest 独立执行）= **434 全绿**，与移除前一致 ✓；`target/site/jacoco/jacoco.xml` 仍出（130 classes 分析）✓；`target/classes` 141 个字节码完整、`javap` LogUtil 正常 ✓；`python tools\tv.py test all` 全量回归 pytest 130 passed、war 生成、`port_18080_open_after=false`、exit=0（`latest.json` 复核）✓。L1 观察（未处理，非本任务引入）：pom 原带 `maven-compiler-plugin` 无 version 的 WARNING 属既有，收口回归同通过，留待后续随手清理。

### T4 git 卫生：.idea 出库 + temp_script 例外出库（N2 + N3）

* **入口线索**：`git ls-files .idea/`（11 文件，含 dataSources.xml/db-forest-config.xml/kotlinc.xml/copilot 迁移文件/runConfigurations）；`git ls-files temp_script/`（仅 migrate_comments_to_two_level.py）；`.gitignore` L7-14（.idea 只逐文件忽略）与 L66（`/temp_script`）。目标：**`.idea/` 整目录出库 + ignore，temp_script 例外出库**，仓库恢复干净。
* **红线边界**：`git rm --cached` 只出库**不删工作区文件**（勿用 `--cached` 以外参数误删）；不出库 `tools/env/*.conf.example` 这类"应入库模板"；不动其他业务文件。
* **强制探索步骤**：(0) 复核 N2/N3 证据（追踪清单）成立 (1) 逐个看过 11 个 .idea 文件，向用户确认是否有意保留共享项（尤其 `runConfigurations/Tomcat_10_1_54.xml` 是共用启动配置——缺省建议全部出库，IDEA 会本地重建；若用户要保留该单文件则 .gitignore 排除它） (2) 确认 `.gitignore` 新增 `/.idea/`（整目录）规则写法与 G9/G10 无冲突 (3) `git rm --cached` 两处 + `git status` 复核，确认此 commit 只改 .gitignore 与删除索引条目——若清单未覆盖 → 回写本文档再动手。
* **验收**：`git ls-files` 无 `.idea/`、无 `temp_script/`；`.gitignore` 含 `/.idea/`；工作区 .idea 文件仍在（本地可用）；无业务文件被误动。
* **执行回写（2026-09-19，chore(prep-04) 已落地）**：强制探索结论：N2/N3 证据复核成立（`git ls-files .idea/` 恰 11 文件、`temp_script/` 恰 1 文件）；逐个看过 11 个 .idea 文件，共享价值点仅 `runConfigurations/Tomcat_10_1_54.xml`（内置 Tomcat 8080 启动配置，含本机 BASE_DIRECTORY_NAME/DEBUG_PORT），`dataSources.xml` 含本机 DB(3306)/Redis(6379) 连接串——**用户拍板全部出库**（缺省建议，Tomcat 配置在各自 IDEA 本地重建）。实现：`.gitignore` 新增 `/.idea/`（整目录，与既有 `.idea/*` 逐文件规则并存无害）；`git rm --cached -r` 出库 .idea/ 11 文件 + `temp_script/migrate_comments_to_two_level.py`（`/temp_script` L66 规则已覆盖，出库后入 ignore）；仅动 .gitignore 与索引条目，零业务文件。验收对照：`git ls-files` 无 `.idea/`、无 `temp_script/` ✓；`.gitignore` 含 `/.idea/` ✓；工作区 `.idea/` 文件仍在（Test-Path 实证）✓；`git status` 复核仅 .gitignore 修改 + 12 条索引删除 ✓。L1 质疑：无。

### T5 随手清理：死注释删除 + 分页解析收敛（N4 + N5 + N6）

* **入口线索**：`RequestParser.java` L19-56（三段落灰 getBody）；`web.xml` L1-6（注释掉的旧 web-app 块）；`FeedController.java` L32-56 与 `ProfileController.java` L45-70（逐字符相同的 parsePage/parsePageSize）。目标：**删除两处死注释；分页解析收敛为公共方法**（建议 `BaseServletUtil` 新增静态 parsePage/parsePageSize，替换 Feed/Profile 两处私有实现）。
* **红线边界**：web.xml **只删纯注释块，不动任何生效 mapper/servlet/filter 声明**；RequestParser 只删注释不改变现役 getBody/parse 逻辑；公共分页方法默认值/上限（page=1、pageSize 默认 10、上限 50）必须与原私有实现逐字符一致（防行为漂移）。
* **强制探索步骤**：(0) 复核 N4/N5/N6 证据（注释块存在、两份实现相同）成立 (1) diff Feed/Profile 两份 parsePage/parsePageSize 确认完全一致（含异常分支），再设计公共签名（建议 `BaseServletUtil.parsePage(req)` / `parsePageSize(req)`） (2) 确认现役 getBody/parse 调用点不受注释删除影响（grep 引用） (3) 抽公共后跑 Feed/Profile 相关 JUnit + /feed /profile 端点 pytest，验证默认分页行为不变——若清单未覆盖 → 回写本文档再动手。
* **验收**：三处注释块从代码/配置移除；Feed/Profile 两 Controller 不再有自己的 parsePage/parsePageSize（引用公共）；相关单测 + pytest（/feed、/profile、分页缺省/越界用例任抽 3 条）绿。
* **执行回写（2026-09-19，refactor(prep-05) 已落地）**：强制探索结论：N4/N5/N6 证据复核成立（RequestParser L19-56 三段落灰 getBody 实存、web.xml L1-6 旧 web-app 注释块实存、Feed/Profile 两份 parsePage/parsePageSize 逐字符一致）；现役 `RequestParser.parse` 6 处调用点（coupon/comment/search/login）经 grep 确认不触达注释块，删除无损；全仓 grep 确认分页解析仅此两份、无第三份拷贝。实现：① 删除 RequestParser 三段落灰 getBody + 随之失效的 `java.io.BufferedReader` import（现役 getBody/parse 零改动）；② 删除 web.xml L1-6 注释块（生效 mapper/filter 声明零触碰）；③ `BaseServletUtil` 新增公共静态 `parsePage`/`parsePageSize`（默认 page=1、pageSize=10、上限 50，与原私有实现逐字符一致），Feed/Profile 两 Controller 删除私有实现、改调 `BaseServletUtil.parsePage/parsePageSize`。验收对照：三处注释块全部移除 ✓；两 Controller 无私有分页方法（grep 仅剩 4 处公共调用）✓；`mvn -o clean test` JUnit 430+4=**434 全绿** ✓；`python tools\tv.py test all` 全量 pytest **130 passed**（含 /feed、/profile、鉴权 401 与越界用例）、war 生成、`port_18080_open_after=false`、exit=0（`latest.json` 复核）✓。L1 质疑：无。

### T6 日志卫生：LogUtil 合规 + CountRepairTool 去留（N7 + N8，R-05 开工前拍板）

* **入口线索**：`util/LogUtil.java` L25/L36/L38/L46（System.out/err 直打初始化信息）；`util/CountRepairTool.java` 全文（main CLI + System.out/err + printStackTrace，javadoc 自述已被 `tools/check_integrity.py --fix` 替代）。目标：**LogUtil 初始化与失败信息改走 java.util.logging 自身通道；CountRepairTool 按 R-05 用户拍板处置**（候选：删除 / 迁出主代码 / 保留）。
* **红线边界**：LogUtil 只改"自身输出方式"，**不改日志级别体系与 API**（业务侧 Logger 获取方式不变）；若 R-05 拍板保留 CountRepairTool → 至少将其 System.out/err 与 printStackTrace 改为 logger（若保留）；删除时确认 `check_integrity.py --fix` 覆盖其计数 SQL 语义（对照 .docs/说明书/TEST_SEED 相关约定）；**R-05 未拍板前不自行选择**——先在 NEEDS 三节挂"待用户裁决"，T6 开工即问。
* **强制探索步骤**：(0) 复核 N7 证据（LogUtil 直打）成立；R-05 向用户要拍板（建议默认=删除，理由：统一入口已在、javadoc 已写明替代、无测试引用——实体确认无引用后执行） (1) LogUtil：确认 java.util.logging 在"初始化日志系统"这一时点可用（失败路径用 `Logger.getGlobal()` 或 `System.err` 仅作极端兜底并注明） (2) CountRepairTool：grep 确认无生产引用（除 main 自身），对照 check_integrity.py 的修复 SQL 清单（content.like_count / comment.like_count / content.comment_count / users.follow_count / users.follower_count） (3) 改后跑 JUnit + pytest 冒烟（日志输出从 console 落到 system.log 处验证）——若清单未覆盖 → 回写本文档再动手。
* **验收**：LogUtil 内不再出现 System.out/err（或仅保留有注释的极端兜底）；CountRepairTool 按拍板处置完毕（删除则代码库无该类；保留则无直打输出）；JUnit 全绿 + pytest 冒烟通过。
* **执行回写（2026-09-19，chore(prep-06) 已落地）**：**强制探索结论**：N7 证据复核成立（`LogUtil` L25/L36/L38/L46 四处直打，本窗口逐行核对）；N8 复核成立，**R-05 由用户拍板 A 案删除**（决策段含否决 B/C 理由与"反转 `260902/T4` 保留决议"的显式记录，见 `NEXT_CYCLE_NEEDS.md` 4.0）；grep 全仓 + `.idea/` 本地配置确认该类**零生产引用、零测试引用**；对照 `check_integrity.py` 的 `COUNT_CHECKS`/`FIX_STATEMENTS` 逐条比对，5 条计数 SQL 与待删类**逐条等价**（含 `content.comment_count` 的 `is_deleted=0`），统一入口为严格超集。
* **落地**：① `LogUtil` 静态块重排为「移除默认 handler → **先挂 ConsoleHandler** → 解析级别（非法值经 logger 打 warning）→ 建 FileHandler（失败降级为仅控制台，SEVERE + 堆栈入日志）」——**先有通道再打记录**，否则记录会因 root 无 handler 被丢弃；类内零 `System.out/err`（硬口径，用户拍板）；`getLogger(Class)` API 与级别体系逐字未动；② 删除 `src/main/java/com/itheima/util/CountRepairTool.java`；③ 同步引用 4 处：`tools/check_integrity.py` L110/L137 注释（去掉指向已删类的悬空表述，改标"2026-09-19 T6 起本文件为唯一计数修复入口"）、`.docs/说明书/TEST_AUTOMATION.md` 6.2 职责边界（改指 `FIX_STATEMENTS` 为语义唯一基准）、`CURRENT_ARCHITECTURE.md` util 表（删该类行、LogUtil 行 54→**61** 并注明新口径）、CA 头部版本 3.0→3.1。
* **验证（沙箱外，只信落盘证据）**：`mvn -o clean package -DskipTests`（复用 run_tests 的 maven 路径/参数并加 `clean`）exit=0，war 内 `WEB-INF/classes/com/itheima/util/CountRepairTool.class` **NOT FOUND**（clean 前同一路径实测存在 → 对照结论成立；另外该类被删后不 clean 的 war 仍会带上陈旧 `.class`，见 L1-2）✓；`python tools\tv.py test junit`（clean 态）exit=0、JUnit **434 / 0 / 0 / 0**（`latest.json`）✓；`python tools\tv.py test all`（clean 态）exit=0、pytest **130 passed**（31.71s）、`build_ok=true`、`port_18080_open_after=false` ✓；**覆盖率口径**：以 `-Dstage8.buildDir` 生效后的报告为准（`D:\data\projects\VideoPlatform\stone\temp\stage8-target\site\jacoco\jacoco.csv`，13:33 生成）——该类行已消失，`LogUtil` 26/94 指令（改造前 24/90，因新增初始化分支与注释；仓库内 `target/site/jacoco/` 是 00:25 的旧副本、不含本次结果，勿据此核对）✓；日志落点验证：`tomcat_stderr.log:41` 出现 `信息: successfully load logs`（ConsoleHandler 通道，改造前为 stdout 裸行；stdout 侧已无该串），`system.log` **首次**出现同义 INFO 行（改造前该信息从不进文件——正是 N7 痛点）✓。**覆盖边界（显式声明）**：434 个 JUnit 与 130 个 pytest 用例中**无任何一条断言 LogUtil 初始化日志的内容或通道**，故本次"初始化信息走 logger"属**未被自动化测试覆盖的行为变化**，仅靠上述落盘日志实测佐证。评审处置（仅改代码注释，不改字节码）后，在**最终代码状态**复跑 `tv.py test junit` 仍为 **434 / 0 / 0 / 0**（exit=0）✓。红线合规：未改 `getLogger` API/级别体系/配置语义，未动业务代码、接口契约、`@WebServlet` URL/web.xml/IoC，未引入依赖；`TEST_SEED.md` 相关口径（L48 已指向 `check_integrity.py --fix`）与本次删除一致，无需改。
* **subagent 独立评审（2026-09-19，只读评审，结论与处置）**：**🔴 1 条（经核实为误判）**——评审员据本窗口诊断文件 `.docs/temp/_t6_jacoco2.txt` 判"LogUtil 24/90 与回写 26/94 矛盾"；实测该文件读的是**仓库内 `target/site/jacoco/jacoco.csv`（mtime 00:25，T6 前的旧副本）**，而 `-Dstage8.buildDir` 生效后报告落在 stage8 内（mtime 13:33，含本次结果）→ 回写数字无误，已改写该诊断文件并在回写注明口径（见"验证"）。**🟡 4 条，全部处置**：① root handler 清空会连带接管容器/第三方 JUL 日志 → 代码注释改为准确表述（原注"移除默认的 ConsoleHandler"不准确，实为移除 root 全部 handler）+ CA 行内含该事实（HEAD 既有行为，非本次引入）；② 目录创建失败改 `warning` 后在 `log.level>=SEVERE` 时不可见 → 并入 NEEDS 4.0"已声明的语义变化"①；③ `AppConfig.getLogLevel()` 仍在 try 之外（其抛异常→`ExceptionInInitializerError`）→ 属 HEAD 既有脆弱性，转 L1-4 不修（修=改初始化语义，超出 T6 边界）；④ war 条目 251→249 与"仅删 1 类"表面不符 → 不再以条目数作为论据，改用"按类名检索 NOT FOUND"+clean 前后对照（另 1 个陈旧条目一并消失的原因未定位，属产物目录残留，见 L1-2）。**🟢**：LogUtil 全文零 `System.out/err`、`parseLevel`/`getLogger` 签名与级别体系未变；静态块重排反而修掉 HEAD 缺陷（HEAD 若 FileHandler 失败则 root 两手空空、JUL 日志全丢）；被删类 5 条 SQL 与 `FIX_STATEMENTS` 逐条等价、单事务/回滚边界等价；CA 无误删现行事实、LogUtil 行数与文件实际一致；434/130 与 `latest.json` 一致；`git status` 7 改 + 1 删与改动清单吻合。**无法验证项（已如实登记）**：容器日志路由的实际影响需真实容器验证；"初始化日志"无自动化断言（已在"验证"显式声明为覆盖边界）。
* **G11 记录**：L1 ×4，L3/L4 无。**L1-1**：`NEXT_CYCLE_NEEDS.md` 头部仍写"评审/拆任务待用户最终确认"、4.0 仍写"本周期尚未开工，此节留空"——T1~T5 落地时未同步（文档滞后，非语义变更），本任务一并纠正。**L1-2**：日常 `tv.py` 构建不带 `clean`，被删类的 `.class` 会残留在 `stage8-target/classes` 并被**重新打进 war**（实测：不 clean 时 war 仍含该类）——war 内容核验必须走 `clean` 构建，且 clean 时另有 1 个陈旧产物条目一并消失（未定位，属产物目录残留）；`target/` 与 `stage8-target` 均为产物目录、不入库，不影响本次提交。**L1-3**：测试实例的 `logs/system.log` 落在 `D:\dev\DevTools\tomcat\apache-tomcat-10.1.54\logs\`（未隔离进仓库），属 N14/T9 范围，本任务不处理。**L1-4**：`LogUtil` 的 `AppConfig.getLogLevel()`/`getLogFile()` 读取在 try 之外（含其静态初始化），任一抛异常即 `ExceptionInInitializerError`、日志系统整体不可用——HEAD 既有，本次未引入未改善，后续若要加固需单独排任务（会改初始化语义）。

### T7 关注/粉丝列表分页（N11a / U-12）

* **入口线索**：`FollowController.java` L34-37（`/following`/`/followers` 无分页参数）；`FollowService.getFollowingList/getFollowerList`（经 `buildUserList`，注意此方法同时是 **U-14②号点**所在，本任务只做分页不动事务边界）；`SetCache.getMembers`（SMEMBERS 全量）；`FollowCache` 双 Set 存储。目标：**关注/粉丝列表支持分页**（新增可选 page/pageSize，缺省兼容现状全量）；前端 `static/js/views/follow.js` 改造为分页加载。
* **红线边界**：**不改 `FollowService` 的事务边界语义（U-14 留池不并入本任务）**；不破坏缺省调用（不分页参数时必须与现状完全一致）；Set 结构升级（如 Set→ZSet）**仅 Redis 内部、对外接口/返回结构零变化**；不新增第三方依赖（Jedis 命令够用）。
* **强制探索步骤**：(0) 复核 N11a 证据成立（如前） (1) **缓存分页载体拍板**（本任务核心技术决策，开工向用户对齐或探索后按 G7 回写待定）：候选 A=Set **补一个有序结构**（如 `ZADD member=followedUserId` 或 List 按关注序）用 ZRANGE/LRANGE 分页稳定；候选 B=缓存保持全量、**DB 分页读取**（LIMIT/OFFSET）+ 缓存仅作"列表判重/数量"等使用——评估两案对 hit 率/装载量/SMEMBERS 全量回传的影响 (2) 接口契约：page/pageSize 解析复用 T5 公共方法，默认值与 T5 一致；返回结构保持 List（分页切片后仍 List<…>）还是包 PageResult（**包 PageResult 属接口变化，向用户确认**——建议保持 List 以零破坏） (3) 前端 follow.js 分页加载改造 + matches 单测/pytest（新增分页参数用例、缺省兼容用例）——若清单未覆盖 → 回写本文档再动手。
* **验收**：`/follow/following`、`/follow/followers` 带 page/pageSize 返回对应页且顺序稳定；不带参数返回与改造前一致（pytest 旧用例不破）；命中/降级路径分页都正确；前端 follow.js 加载更多可用；相关 JUnit + pytest 绿。
* **执行回写（<日期>，feat(prep-07) 已落地）**：待填。

### T8 评论列表分页（N11b / U-13）

* **入口线索**：`CommentController.java`（`getCommentsForContent` 无分页整树渲染）；`CommentService`/`CommentCache.loadCommentTree`（整树缓存 + buildCommentTree 内存重排）。目标：**`getCommentsForContent` 主楼分页 + 楼中楼整树**（每页 N 条主评论，每条主评论携带其完整楼中楼）；前端 `detail.js` 评论区加载更多。
* **红线边界**：**不动缓存装载结构**（整树缓存保留——评论树分页属展示层切片，不是缓存重构）；主楼分页不得撕裂楼中楼（楼中楼必须随主评论整体返回）；不改评论树构建语义（buildCommentTree 逻辑原样）；缺省不传分页参数时行为与现状一致（历史兼容）。
* **强制探索步骤**：(0) 复核 N11b 证据成立 (1) **分页形态向用户拍板**（建议方案 A：主楼分页 + 楼中楼整树；替代 B：主楼+楼中楼都分页——A 改动最小、对用户更友好，探明 buildCommentTree 输出是否天然"主楼列表 + 每条子楼"结构便于切片） (2) 确认缓存命中时从整树切片、miss/降级时装载后直接切片不落缓存或按既有规则回填——保持读路径语义 (3) Controller/Service/前端（detail.js 评论加载更多）+ 单测/pytest（新分页用例、缺省兼容、楼中楼完整断言）——若清单未覆盖 → 回写本文档再动手。
* **验收**：分页参数下返回"该页主评论+完整楼中楼"，页间主楼不重复不遗漏；缺省与现状一致；评论点赞/软删/开关门禁路径不受影响（pytest 回归含 comment 域既有用例）；相关 JUnit + pytest 绿。
* **执行回写（<日期>，feat(prep-08) 已落地）**：待填。

### T9 测试目录本地化（N14）

* **入口线索**：`pom.xml` L20-22（stage8.buildDir 注释："沙箱内 javac 无法枚举 worktree 的 target/ 作 classpath，-Dstage8.buildDir 指向 D 盘可写目录"）；`src/main/webapp/META-INF/context.xml` L2-8（`D:/data/projects/VideoPlatform/stone` 硬编码）；`tools/run_tests.py` L68-70（SHUTDOWN_PORT/HTTP_PORT/BASE_URL 硬编码）；项目记忆："trae 沙箱拦截 run_tests*.py 的 process/port/network" 与 "沙箱拦截写 `D:\data\projects\VideoPlatform\stone\temp\`"。目标：**探明沙箱边界，把测试可写目录收进仓库可写区，评估沙箱内直接跑测试的可行性并落地可落地部分**。
* **红线边界**：**不擅改 `@WebServlet` URL / web.xml / IoC 扫描 / 业务语义**（context.xml 的 docBase/upload 路径属部署配置，改路径需保证上传/访问功能不受破坏并回归）；不经用户同意不把 media 路径改成会撞 gitignore 或影响其他环境判断的怪路径（须可配置、可回退）；pom 改动须回归（同 T3）。
* **强制探索步骤**：(0) 复核 N14 证据成立（buildDir 注释 / context.xml 绝对路径 / run_tests 硬编码端口） (1) **第一步先做边界实测**：在沙箱内最小化复现——能否 `mvn -o` 编译到项目内 target？能否启动一个嵌入式/独立 Tomcat 进程并 bind 端口？能否写项目内新目录？产出"沙箱内可做/不可做"清单（这是本任务核心探索，结论决定 T9 是否立项为"落地"还是"缓解"） (2) 基于 (1) 落到具体面：buildDir 是否可改回项目内（如 `target/` 或 `.stage8-target/` + gitignore）并让 run_tests 不再依赖外部目录；日志目录（LOG_PATH/`tomcat-test-18080/logs`）是否收进项目内 ignore 目录；media upload 路径（AppConfig.upload.path / context.xml）改为项目内 ignore 目录并回归上传/媒体巡检；端口与 BASE_URL 参数化进 test.conf (3) 每项改动都跑对应回归（构建 / 单测 / pytest 全套），最终产出"沙箱内跑测试可行性结论"（可跑 → 交代命令；不可跑 → 交代卡点与缓解边界）——若清单未覆盖 → 回写本文档再动手。
* **验收**：产出边界探明报告（沙箱内可做/不可做清单，落 `.docs/temp/`）；可落地部分（buildDir / 日志 / media / 端口参数化）实现完成且构建 + 单测 + pytest 全绿；不可落地部分明确成文并登记留池去向（可转 `UNPLANNED_ISSUES.md` 或回 NEEDS 4.1 待定），**绝不硬扛"沙箱外才能跑"的现状为成功**。
* **执行回写（<日期>，chore(prep-09) 已落地）**：待填。