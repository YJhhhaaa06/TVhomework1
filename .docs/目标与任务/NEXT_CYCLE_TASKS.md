# 下一周期任务清单

> 状态：**第七期（2026-09-19 建清单；**T11 关注·粉丝列表分页**原为**预告条目**，已于 2026-09-20 T11 窗口完成范围拍板并**拆为 T11-A（follow 域上限 200 + 后端固定信封 + 缺省归一为第一页 + sheet 接 chunkedList，**契约变更**）/ T11-B（评论域固定信封 + 公共 helper 去重/自适应 + 内容列表前端迁移）/ T11-C（装载侧解耦，池 U-18 治本，拆 C-1/C-2 两个 commit）**，四要素已回写（见四节）；**T11-A（2026-09-20）、T11-B（2026-09-20）与 T11-C（2026-09-20）已完成**（执行回写见四节各自之后），另新增 **T19**（feed/search/profile 的后端大分页推后项）+ **T12~T18** 清理类（**T12 事务边界同型未治点 ✅ 已完成 2026-09-20**（全链 exit 0 + JUnit 506/0/0/0 + pytest 145）/ **T13 注册后自动登录兜底 ✅ 已完成 2026-09-20**（全链 exit 0 + JUnit 511/0/0/0 + pytest 145）/ **T14 包层结构清扫 ✅ 已完成 2026-09-20**（R-03 拍板：环保留现状 / 信封上移 `common.model.dto` / `ResultMap` 按域拆方法；全链 exit 0 + JUnit 511/0/0/0 + pytest 145）/ **T15 文档与代码一致性 ✅ 已完成 2026-09-20**（**纯文档批，不跑测试**；目录树 / AuthFilter 名单 / URL 表 / 26 处行数 / 表结构 / 配置项全量核对）/ **T16 Controller 基建收敛（N11）✅ 已完成 2026-09-20**（全链 exit 0 + JUnit 511/0/0/0 + pytest 145；HTTP 侧 **4 处** `new ObjectMapper`——N11 记忆 3 处，实测含 `LoginController` 死字段共 4 处——收敛为 `BaseServletUtil.mapper` 唯一源、`RequestParser` 归并复用；删 `BaseServlet` 冗余 mapper 与 3 个 write*（全仓零调用者）、删零调用 3 参 username 重载、`BaseServletUtil` 去 `extends HttpServlet`；subagent 独立 review **APPROVE**，4 文件 +4/-39 行）/ IoC 注入可观测 / 配置卫生）。
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
| T14 | 包层结构清扫：content↔comment 环 + 分页信封上移公共包 + `ResultMap` 反向依赖 | NEEDS **R-02/R-03**（= 池 U-07）+ 池 **U-19** + NEEDS 4.1 **N14** | **R-03 拍板**（环处置口径） | **已完成（2026-09-20）**：R-03 拍板 = ① 环**保留现状**（实测全仓 9 个双向环，content↔comment 的 15 条边仅 5 条属组件错位，纯搬移无法单向化）② 信封上移 `com.itheima.common.model.dto`（唯一源，删同形的 `FollowPageResult`）③ `ResultMap` 6 个方法按域下沉 + 删 `dao` 包；**该口径顺带真消除 `content↔dao` / `dao↔user` 两环**；全链 exit 0 + JUnit 511/0/0/0 + pytest 145、对外行为零变化 | `refactor` | **已完成** |
| T15 | 顺手清理：架构文档与代码一致性（目录树 / 鉴权名单 / URL 表 / 行数 / 表结构） | NEEDS 4.1 **N16** + **N9**（文档部分） | 无 | **已完成（2026-09-20）**：目录树删 `ssm_*`；AuthFilter 精确名单 7 → 10 项；§7.2 `/search` → `/search/keywordSearch`；§4.2/§4.3 行数 **26 处**按 `wc -l` 修正；§5.2/§5.3 按 3306 实际 DDL 修正（comment `message`→`content` + `reply_count`、content_media `media_type`→`type`、补 `idx_content_parent`）；`git diff` 只含 2 个常青文档、无源码/测试/配置改动（纯文档批，不跑测试） | `docs` | **已完成** |
| T16 | 顺手清理：Controller 基建收敛（三份 ObjectMapper / 重复 `write*` / 冗余继承） | NEEDS 4.1 **N11** | 无 | **已完成（2026-09-20）**：HTTP 侧 4 处 `new ObjectMapper` 收敛为 `BaseServletUtil.mapper` 唯一源（`RequestParser` 归并、`LoginController` 死字段删除）；删 `BaseServlet` 冗余 mapper + 3 个 write*（全仓零调用者）与零调用 3 参 username 重载；`BaseServletUtil` 去 `extends HttpServlet`（web.xml 仅 4 Filter、无 Servlet 注册背书）；响应 JSON 形状逐字节不变（mapper 配置原样保留、请求 DTO 全无日期字段）；全链 exit 0 + JUnit 511/0/0/0 + pytest 145；subagent 独立 review **APPROVE**（仅 3 处装饰性空行，已修） | `refactor` | **已完成** |
| T17 | IoC 注入解析失败可观测（消除"取不到就跳过"） | NEEDS 4.1 **N12**（前半；声明式事务不在本任务） | 无 | 注入失败不再静默（先落 WARNING + 汇总清单；是否升级 fail-fast 由用户拍板）；启动无新增失败；测试绿 | `fix(ioc)` | 待执行 |
| T18 | 配置卫生：本机绝对路径可外部覆盖 + `AppConfig` 带默认值读取 | NEEDS 4.1 **N13**（分页上限参数化已随 T10-B/T11-A 落地，本任务不含） | 无 | 默认行为不变；换环境无需改源码/重打包；全量测试绿 | `chore` | 待执行 |
| T19 | feed / search / profile 的后端大分页（域级上限 + 域级信封） | **2026-09-20 T11 窗口推后项** | T11-B（三处前端已迁 helper） | 三域 `pageSize` 上限 200、只传 `page` 返回域级信封；相关 pytest 全绿；全链 exit 0 | `refactor(cache-19)` | 待执行 |

> 状态取值：草稿 / **预告** / 待执行 / 执行中 / 已完成 / 搁置。搁置的 T# 必须注明其"对应候选"编号去向（转 `UNPLANNED_ISSUES.md` / 结转下周期 NEEDS 二节），不得悬空（G11）。

> **顺序理由**：**T12~T19 互相独立、可任意穿插**——**T15（纯文档）✅ 已完成 2026-09-20**；**T16 ✅ 已完成 2026-09-20**（Controller 基建收敛）；T17 / T18 属小改动但都要跑测试回归；T14 需先拍板 **R-03**。**T10 已拆解拍板（T10-A → T10-B，均已完成）**：T10-A 先落地缓存结构拆分 + 主楼窗口装载 + DDL 闭环（对外契约零变化）；T10-B 依赖 T10-A，做楼中楼 K=2 + 展开接口 + 前端大 chunk/helper（契约变更）。**T11 已拆解拍板（2026-09-20 T11 窗口）**：**T11-A 先行**（follow 域上限 + 后端固定信封 + 缺省归一 + sheet 接 helper，契约变更，四要素已回写）；**T11-B 已完成（2026-09-20，依赖 A 的 helper 自适应形态）**：评论域固定信封 + helper 去重/自适应 + 三处内容列表前端迁移；**T11-C** 依赖 A 的前端 chunk 落地（装载侧解耦，内部 C-1 → C-2 顺序：先 DDL + 窗口 DAO（行为零变化），再前缀装载；**已完成 2026-09-20**）；**T19** 依赖 T11-B（三处前端已迁 helper，本任务补后端上限与信封）。**T11-C 进窗口前无需再拍板**（拍板结论已回写四要素）。

> **回填要求**：任务执行后在本节与"四、任务详情"同步状态与"执行回写"；拆/改任务必须在"对应候选"列保留 NEEDS/池编号，保持 Why→How 可追溯。

---

## 四、任务详情

> **共通注（本周期通用）**：技术红线沿用——禁 Spring/SpringBoot/MyBatis、不擅改 `@WebServlet` URL / web.xml 生效配置 / IoC 扫描、不为实现便利改业务逻辑语义；**不引入 MQ**（异步用进程内线程池）；**不引入新依赖**（T10/T11 的窗口读仍用 Jedis 既有命令）；**变更纪律**——不维护文档级变更记录，变更以 git 提交历史为准；**脚本规范**——Python，临时脚本 `temp_script/`，长期工具 `tools/` + `tv.py` 收录；**改动业务代码同步维护单元测试与 pytest**；**测试执行**——沙箱内可直接 `python tools\tv.py test all` 跑全链。
> **红线口径（与二节"红线措辞约定"一致）**：下面各任务的"红线边界"只列"明显越界"的项，作用是**防跑偏**、不穷举做法；若某条红线会阻碍正确做法（过紧 / 过窄 / 已不适用）→ 说明理由**申请调整**，不许硬扛、也不许自行放开。

### T16 顺手清理：Controller 基建收敛（N11）

* **入口线索**：`controller/BaseServlet.java:17-19` 与 `controller/BaseServletUtil.java:17-19`（ObjectMapper 配置逐字重复）；两类 `writeSuccess/writeError` 方法体逐字相同；`controller/RequestParser.java:11`（第三份 `new ObjectMapper()`，未注册 JavaTimeModule / 未禁 timestamps）；`BaseServletUtil.java:15`（工具类却 `extends HttpServlet`，无任何 URL 映射）。目标：收敛为**唯一 mapper + 唯一响应写出实现**，去掉冗余继承。
* **红线边界**：不改响应 JSON 形状、不改 `@WebServlet` URL / web.xml / IoC 扫描、不改业务语义；**只做收敛与去重**；不引入新依赖。
* **强制探索步骤**：动刀前先 (0) 复核 N11 证据 (1) 确认三份 mapper 配置差异对**现有 DTO 的实际影响**（请求体里有无 `java.time` 类型 / 日期字符串字段）——若统一配置会改变既有解析行为，则该处不合并或显式注明差异，**不得悄悄改行为** (2) grep 全部 `writeSuccess/writeError` 调用点，确认可见性（`public`/`protected`）与重载签名不变 (3) 去掉 `extends HttpServlet` 前确认无 web.xml / 注解把它注册为 Servlet、无 `getServletConfig()` 类调用——若清单未覆盖 → 回写本文档再动手。
* **验收**：mapper 唯一源；无重复方法体；`BaseServletUtil` 不再继承 `HttpServlet`；抽若干端点 pytest 核对响应**逐字节不变**；全量 JUnit + pytest 绿。
* **执行回写（2026-09-20 T16 窗口）**：**实测 4 处** `new ObjectMapper`（N11 记 3 处——`BaseServlet` / `BaseServletUtil` / `RequestParser`；实测还有 `LoginController.java:31` 私有死字段 `new ObjectMapper()` + 死字段 `requestParser`，全类零引用）→ 收敛为 `BaseServletUtil.mapper` 唯一源（可见性 protected→public），`RequestParser.parse` 归并复用（请求 DTO 7 类全无 `java.time`/日期字段，`readValue` 行为等价）；`BaseServlet` 删 mapper + 3 个 write*（全仓 109 处 write* 调用点全为 `BaseServletUtil.` 限定，`BaseServlet` 继承方法**零调用者**）并保留 `extends HttpServlet` + `init()` IoC 注入；`BaseServletUtil` 去 `extends HttpServlet`（web.xml 仅 4 Filter、无 Servlet 注册/无 `getServletConfig` 调用背书）；删**零调用** 3 参 `writeSuccess(resp,data,username)` 重载（D1）与 `LoginController` 死字段。`cache/JacksonCodec.MAPPER` 属 cache 层序列化器，**排除**（D3）。改动 4 文件 +4/-39 行；全链 exit 0（build 通过）+ JUnit **511/0/0/0** + pytest **145/0/0/0**；subagent 独立 review **APPROVE**（3 处装饰性空行已修）。响应形状逐字节不变由全量 pytest 端点回归兜底。

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
