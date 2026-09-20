# 下一周期任务清单

> 状态：**第七期（2026-09-19 建清单；**T11 关注·粉丝列表分页**原为**预告条目**，已于 2026-09-20 T11 窗口完成范围拍板并**拆为 T11-A（follow 域上限 200 + 后端固定信封 + 缺省归一为第一页 + sheet 接 chunkedList，**契约变更**）/ T11-B（评论域固定信封 + 公共 helper 去重/自适应 + 内容列表前端迁移）/ T11-C（装载侧解耦，池 U-18 治本，拆 C-1/C-2 两个 commit）**，四要素已回写（见四节）；**T11-A 已于 2026-09-20 完成**（执行回写见四节 T11-A 之后），另新增 **T19**（feed/search/profile 的后端大分页推后项）+ **T12~T18** 清理类（事务边界代码债 / 注册登录兜底 / 包层结构清扫 / 文档与代码一致性 / Controller 基建收敛 / IoC 注入可观测 / 配置卫生）。
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

## 三、任务总览（本周期 12 任务：T10-A/T10-B、T11-A/T11-B/T11-C、T12~T19）

> **DDL 标注（G9 要求）**：① **已完成**（2026-09-19 T10-A 窗口）——`comment` 加 `reply_count INT NOT NULL DEFAULT 0` + 复合索引 `(content_id, parent_id)`；② **新增（2026-09-20 T11 窗口定稿，T11-C-1 执行）**——`follow` 加 `idx_followed_user_user (followed_user_id, user_id)`（粉丝方向窗口查询同序；关注方向复用 `uk_user_follow`）。两次均走备份闭环（改前备份 → ALTER 3306 → 备份新结构 → 重建测试库 3307）并**向用户报备**。

| 编号 | 标题 | 对应候选 | 依赖 | 验收关键（动态） | 期望 commit 主题 | 状态 |
| -- | -- | ---- | -- | -------- | ------------ | -- |
| T10-A | 评论分页①：缓存结构拆分（主楼 List + 楼中楼 Hash）+ 主楼窗口装载（后端基座） | NEEDS 4.2 ①（= 池 U-20，U-20 治本） | 用户 2026-09-19 放开 T8"不动缓存装载结构"红线（本任务正是要动它） | **已完成（2026-09-19/20）**：两键组 + 主楼窗口装载 + DDL（reply_count + idx），契约零变化（pytest 评论用例零改动）；全链绿 | `refactor(cache-10a)` | **已完成** |
| T10-B | 评论分页②：楼中楼前 K=2 条装载 + replyCount 总数 + 展开加载回复接口 + 前端大 chunk/本地小批 + 公共"分块列表"helper（N15） | NEEDS 4.2 ① + N15 | T10-A 落地（已完成） | **已完成（2026-09-20）**：契约变更落地（children 前 2 + replyCount + `/comment/replies` 展开）；全链 JUnit 绿 + pytest 145 | `refactor(cache-10b)` | **已完成** |
| T11-A | 关注/粉丝列表：域级上限 200 + 后端固定信封（前端只传 `page`）+ 缺省归一为第一页 + sheet 接 `chunkedList` | NEEDS 4.2 ②（+ N15 复用） | 无 | **已完成（2026-09-20）**：`pageSize=51` 回显 51、`999`→200；缺省=第一页信封（与显式 `page=1&pageSize=200` 逐字节一致）；sheet「加载更多」走公共 helper；全链 exit 0 + JUnit 482/0/0/0 + pytest 145 | `refactor(cache-11a)` | **已完成** |
| T11-B | 评论域固定信封（default 200 / max 500）+ 公共 helper 去重与 `chunkSize` 自适应 + 内容列表前端迁移（feed/search/profile，chunk=50） | NEEDS 4.2 ② + 4.1 **N15** | T11-A（helper 自适应形态） | 评论域只传 `page` → `pageSize=200`；`chunkedList` 具 `keyOf`/`seen` 去重；**后端「页间不重不漏」断言一条未改**（去重不掩盖后端 bug）；三处内容列表走公共 helper | `refactor(cache-11b)` | **待执行** |
| T11-C | 关注/粉丝列表装载侧解耦（池 **U-18** 治本，P1 前缀窗口装载） | NEEDS 4.2 ② + 池 **U-18** | T11-A（信封/上限先行）；C-1 → C-2 顺序 | JUnit 断言"窗口装载只查 `[W, offset+count)`、不触发全量 loader"；`getMembers` 部分态补齐；写路径遇 `partial` 双 DEL；DDL 闭环留证；全链 exit 0 | `refactor(cache-11c)`（C-1 记为 `cache-11c1`） | **待执行** |
| T12 | 事务边界同型未治点 2 处（缓存读移出回调） | 池 **U-14**（2026-09-19 评估：进 TASKS） | 无 | 两处缓存读不再在事务回调内；对外行为零变化；JUnit + pytest 全绿 | `refactor(content)` / `refactor(follow)` | 待执行 |
| T13 | 注册后自动登录缺兜底 | 池 **U-16**（同上） | 无 | 自动登录失败 → 返回"注册成功 + 提示手动登录"，不再无 token 无提示；补用例 | `fix(user)` | 待执行 |
| T14 | 包层结构清扫：content↔comment 环 + 分页信封上移公共包 + `ResultMap` 反向依赖 | NEEDS **R-02/R-03**（= 池 U-07）+ 池 **U-19** + NEEDS 4.1 **N14** | **R-03 拍板**（环处置口径） | 包依赖按拍板口径单向；`PageResult` 唯一源在公共包；基础包不再反向依赖业务模型；全量 JUnit/pytest 绿且行为零变化 | `refactor` | 待执行（待 R-03 拍板） |
| T15 | 顺手清理：架构文档与代码一致性（目录树 / 鉴权名单 / URL 表） | NEEDS 4.1 **N16** + **N9**（文档部分） | 无 | 文档事实与代码一致（目录树、AuthFilter 精确名单、URL 表抽查）；`git diff` 只含事实修正 | `docs` | 待执行 |
| T16 | 顺手清理：Controller 基建收敛（三份 ObjectMapper / 重复 `write*` / 冗余继承） | NEEDS 4.1 **N11** | 无 | mapper 唯一源、无重复方法体、`BaseServletUtil` 不再继承 `HttpServlet`；响应形状逐字节不变；全量测试绿 | `refactor` | 待执行 |
| T17 | IoC 注入解析失败可观测（消除"取不到就跳过"） | NEEDS 4.1 **N12**（前半；声明式事务不在本任务） | 无 | 注入失败不再静默（先落 WARNING + 汇总清单；是否升级 fail-fast 由用户拍板）；启动无新增失败；测试绿 | `fix(ioc)` | 待执行 |
| T18 | 配置卫生：本机绝对路径可外部覆盖 + `AppConfig` 带默认值读取 | NEEDS 4.1 **N13**（分页上限参数化已随 T10-B/T11-A 落地，本任务不含） | 无 | 默认行为不变；换环境无需改源码/重打包；全量测试绿 | `chore` | 待执行 |
| T19 | feed / search / profile 的后端大分页（域级上限 + 域级信封） | **2026-09-20 T11 窗口推后项** | T11-B（三处前端已迁 helper） | 三域 `pageSize` 上限 200、只传 `page` 返回域级信封；相关 pytest 全绿；全链 exit 0 | `refactor(cache-19)` | 待执行 |

> 状态取值：草稿 / **预告** / 待执行 / 执行中 / 已完成 / 搁置。搁置的 T# 必须注明其"对应候选"编号去向（转 `UNPLANNED_ISSUES.md` / 结转下周期 NEEDS 二节），不得悬空（G11）。

> **顺序理由**：**T12~T19 互相独立、可任意穿插**——**T15（纯文档）可最先做**；T12 / T16 / T17 / T18 属小改动但都要跑测试回归；T14 需先拍板 **R-03**。**T10 已拆解拍板（T10-A → T10-B，均已完成）**：T10-A 先落地缓存结构拆分 + 主楼窗口装载 + DDL 闭环（对外契约零变化）；T10-B 依赖 T10-A，做楼中楼 K=2 + 展开接口 + 前端大 chunk/helper（契约变更）。**T11 已拆解拍板（2026-09-20 T11 窗口）**：**T11-A 先行**（follow 域上限 + 后端固定信封 + 缺省归一 + sheet 接 helper，契约变更，四要素已回写）；**T11-B** 依赖 A 的 helper 自适应形态（评论域信封 + helper 去重/自适应 + 三处内容列表前端迁移）；**T11-C** 依赖 A 的前端 chunk 落地（装载侧解耦，内部 C-1 → C-2 顺序：先 DDL + 窗口 DAO（行为零变化），再前缀装载）；**T19** 依赖 T11-B（三处前端已迁 helper，本任务补后端上限与信封）。三任务进窗口前无需再拍板（拍板结论已回写四要素）。

> **回填要求**：任务执行后在本节与"四、任务详情"同步状态与"执行回写"；拆/改任务必须在"对应候选"列保留 NEEDS/池编号，保持 Why→How 可追溯。

---

## 四、任务详情

> **共通注（本周期通用）**：技术红线沿用——禁 Spring/SpringBoot/MyBatis、不擅改 `@WebServlet` URL / web.xml 生效配置 / IoC 扫描、不为实现便利改业务逻辑语义；**不引入 MQ**（异步用进程内线程池）；**不引入新依赖**（T10/T11 的窗口读仍用 Jedis 既有命令）；**变更纪律**——不维护文档级变更记录，变更以 git 提交历史为准；**脚本规范**——Python，临时脚本 `temp_script/`，长期工具 `tools/` + `tv.py` 收录；**改动业务代码同步维护单元测试与 pytest**；**测试执行**——沙箱内可直接 `python tools\tv.py test all` 跑全链。
> **红线口径（与二节"红线措辞约定"一致）**：下面各任务的"红线边界"只列"明显越界"的项，作用是**防跑偏**、不穷举做法；若某条红线会阻碍正确做法（过紧 / 过窄 / 已不适用）→ 说明理由**申请调整**，不许硬扛、也不许自行放开。

### T11-A 关注/粉丝列表：域级上限 200 + 后端固定信封 + 缺省归一为第一页 + sheet 接 `chunkedList`

> **已拍板（2026-09-20 T11 窗口，用户逐项确认）**：原 T11 预告条目经窗口调查后拆为 **T11-A / T11-B / T11-C**（+ 推后项落 **T19**）。本任务 = follow 域接口口径定稿，**属契约变更（已获批准）**；详细取证与设计见 `.docs/temp/T11_PLAN.md`（gitignored，窗口内过程档）。

* **意图**：① 单次可拉条数上限 50 → 200；② **信封大小改由后端域级常量决定**（前端不再传 `pageSize`、只传"第几个信封"）；③ 关闭"缺省不传参 = 拉全量"的放大后门（缺省归一为第一页信封）；④ sheet 接公共 `chunkedList`（大 chunk + 本地小批 + 去重）。
* **入口线索**：`follow/controller/FollowController.java:36-51,63-65`（`hasPagingParams` 双分支）、`:38/46`（`parsePageSize(req)` 上限 50）、`follow/service/FollowService.java:72-83`（两个缺省重载）、`controller/BaseServletUtil.java:65-82`（`parsePageSize` 两参实现）、`views/user.js:11,219-268`（sheet `PAGE_SIZE=10` + 手写 `renderSheetMore`）、`js/chunkedList.js`（T10-B 产物，直接复用）。
* **已拍板技术方案（回写 NEEDS 4.0）**：
  * 新增 `BaseServletUtil.parsePageSize(req, max, defaultSize)` **三参重载**：传了 → `min(s, max)`；没传 → `defaultSize`；现有两参重载委托 `(req, 50, 10)` → **公共语义与其它域接口零变化**。
  * follow 域自持常量 `FOLLOW_PAGE_SIZE_MAX = 200` + 信封 200（镜像 `CommentController.java:22` 的 500 先例）。
  * **删 `hasPagingParams` 双分支** → 统一 `parsePage` / `parsePageSize(req, 200, 200)`：缺省（不传参）= **第一页信封**（T7 的"缺省返回全量数组"契约**破坏性变更**，已获用户明确批准）。
  * 删 `FollowService` 两个缺省重载（`getFollowingList(long,Long)` / `getFollowerList(long,Long)`）：grep 实测调用方仅 Controller 缺省分支 + 测试，属死代码；连带删除 **8 条 JUnit 用例**（21 → 13，用例数变化在执行回写说明）。
  * 前端 `views/user.js` sheet 改用 `createChunkedList({ fetchChunk, batchSize: 10, keyOf })`：请求**只传 `page`**、不传 `pageSize`；`renderSheetMore` 改用 `hasMore()`；`openUserList` 切换 following/followers 时 `reset()`（防上一类本地余量串台）。
* **红线边界**：只动 **follow 域**接口口径与 `user.js` sheet；**公共 `DEFAULT_PAGE_SIZE_MAX = 50` 与两参重载语义不动**（`/feed`·`/search`·`/profile`·`/content`·`/coupon` 等零变化）；不动缓存结构与装载策略（属 T11-C）；不动 `buildUserList` 事务边界（属 T12）；不引入新依赖；不擅改 `@WebServlet` URL / web.xml / IoC 扫描。
* **强制探索步骤**：动刀前先 (0) 复核本文档与 NEEDS 4.2 ② 证据仍成立（G11——不成立 → L3 暂停） (1) 全量 grep 确认缺省重载无调用方、`follow/following|followers` 无其它前端消费方 (2) 确认三参重载不改变两参重载语义（其它域零影响） (3) 确认 `chunkedList` 在本任务时的可用形态——若 `chunkSize` 自适应（T11-B 落）尚未就绪，可先显式传 `chunkSize = 200`，与 T11-B 不冲突 (4) 若清单未覆盖 → 回写本文档再动手。
* **验收**：`pageSize=51` 信封回显 51、`pageSize=999` → 200（旧断言 50 改写）；缺省（不传参）与显式 `page=1&pageSize=200` 响应**逐字节一致**；只传 `page` 时 `pageSize` = 200；sheet「加载更多」本地余量内 0 请求（代码可检）；`test_follow_list.py` 相关断言改写后全绿；全链 `python tools\tv.py --env test test all` exit 0。
* **风险自查（执行时自检）**：受影响的既有断言必须逐条改写而非删除（`test_follow_list.py` 5 条 + `FollowServiceTest` 8 条）；契约反转的连带面须全量 grep（`follow/following|followers` 的其它消费方、缺省重载的隐藏调用方）。
* **过程档（非权威、可被清理，仅供取证参考）**：`.docs/temp/T11_PLAN.md`（证据表 E8~E15 对应本任务）。**决策源一律以本档四要素 + `NEXT_CYCLE_NEEDS.md` 4.0/4.2 为准。**

> **执行回写（2026-09-20，第七期 T11-A 窗口）**
>
> * **落地（代码）**：① `BaseServletUtil` 新增三参重载 `parsePageSize(req, max, defaultSize)`（传了 → `min(s, max)`；未传 / 非法 / ≤0 → `min(defaultSize, max)` —— **返回值恒 ≤ max**），两参重载改为委托 `(req, max, DEFAULT_PAGE_SIZE)`（= 10），并抽出常量 `DEFAULT_PAGE_SIZE = 10`（实测 83 → 99 行；**其它域零变化**）；② `FollowController` 自持 `FOLLOW_PAGE_SIZE_MAX = 200` + `FOLLOW_PAGE_SIZE_DEFAULT = 200`，**删除 `hasPagingParams` 双分支**，两个 case 统一 `parsePage(req)` + `parsePageSize(req, 200, 200)`（114 → 104 行）；③ `FollowService` **删除两个缺省全量重载**（`getFollowingList(long,Long)` / `getFollowerList(long,Long)`，189 → 173 行）——分页读成为唯一入口；④ `views/user.js` sheet 改接公共 `chunkedList`（`batchSize=10` + 显式 `chunkSize=200`、请求**只传 `page`**），`renderSheetMore` 改用 `hasMore()`、删 `setSheetMore`、`state.listPage/listTotalPages` 换成 `state.sheetList`（每次 `openUserList` 重建实例 = 等价 reset）。
> * **契约变更（已获批准）**：列表**恒为信封**；缺省（不传任何分页参数）= 第一页信封（page 1 / pageSize 200），与显式 `page=1&pageSize=200` 响应**逐字节一致**；`pageSize` 上限 50 → 200，**显式传值仍生效**（不采纳"后端硬忽略参数"）。
> * **否决的备选**：① 「默认 page=1&pageSize=50」实现缺省兼容 → 上限 50 会截断全量，语义不等价（T7 已写明）；② 后端硬忽略 `pageSize` → 摧毁 pytest 用小信封逐页比对"页间不重不漏"的能力（T11_PLAN 7.1 论证）——"保留显式参数"是让"上限 200"与"跨页验证"两条能力同时成立的最短路径。
> * **验证（落盘证据）**：`python tools\tv.py test all` **exit 0**（`build_ok=True`、pytest **145 passed**、`port_18080_open_after=false`）；`python tools\tv.py test junit` **exit 0**、`latest.json` junit = **482 / 0 / 0 / 0**（26 个 surefire 报告逐类汇总亦 482/0/0/0）。⚠️ 过程坑：`test all` 的构建是 `mvn package -DskipTests`，**不跑 JUnit**，且 `all` 不写 junit 计数（`junit.tests = null`）——**JUnit 必须单独跑 `test junit`**，否则读到的是上一任务留下的陈旧 `surefire-reports/*.txt`（本次初读即拿到 T10-B 的 `FollowServiceTest 21`）。
> * **用例数变化**：JUnit **487 → 482（−5）**——`FollowServiceTest` 21 → 16：删 8 条（5 条 `getFollowingList` 缺省读 + 2 条 `getFollowerList` 缺省读 + 1 条缺省回归 `getFollowingListDefaultPathDoesNotUseWindowRead`，均随缺省重载作废），**同时把其中仍有语义价值的 3 条断言迁入分页路径**（`getFollowingListPagedMarksSelfWhenIdMatchesCurrentUser` / `getFollowingListPagedWithoutCurrentUserSkipsFollowQuery` / `getFollowerListPagedEmptyPageSkipsDbAndFollowQuery`）→ 覆盖面未削弱（isSelf 判定、`currentUserId == null` 跳过判重、空窗口不打事务不装载，三条语义继续有断言）；pytest 用例数**不变**（`test_follow_list.py` 12 条，全部**逐条改写**、无删除）。
> * **pytest 改写清单（逐条）**：`:56/:119` 空列表 → 断言 `data` 为信封且 `list == [] && total == 0`；`:68/:131` 列表态 → 取 `data["list"]` 再比对条目；`:206` 「缺省仍返回数组」**反转为** `test_default_path_equals_explicit_first_page`（新增信封字段集断言 + `totalPages` 推导 + `json.dumps(sort_keys=True)` **逐字节比对**）；`:226` 只传 `page` 的 `pageSize` 10 → 200；`:236` 上限 50 → 200，并新增 `pageSize=51` 原样回显；`:257` 缺省全量前缀 → 缺省第一页前缀（`data["list"]`）。
> * **红线合规**：公共 `DEFAULT_PAGE_SIZE_MAX = 50` 与两参重载**语义零变化**（仅实现改为委托）；`/feed`·`/search`·`/profile` 的 `parsePageSize(req)` 调用点未动（grep 复核）；未动缓存结构与装载策略（属 T11-C）；未动 `buildUserList` 事务边界（属 T12）；无新依赖；未改 `@WebServlet` URL / web.xml / IoC 扫描。**R5 关闭**：全量 grep 确认 `follow/following|followers` 的前端消费方仅 `user.js`。
> * **G11 记录（L1，仅记录不打断）**：① `FollowCache.getFollowerIds(long)`（`FollowCache.java:154`）在缺省重载删除后**主代码已无调用方**（仅 `FollowCacheTest:478` 引用）→ 变成 follow 域公共死方法；本任务删的是 Service 层缺省重载，**未连带删缓存层方法**（越界）→ **去向：T11-C 窗口一并评估**（C-2 要改的正是这批读路径方法）。② 架构文档 `BaseServletUtil` 行数原记 49、实际 83（本任务顺手改为 99；属 T15 同类事实问题）。③ `static/js/chunkedList.js` 为 T10-B 产物但**未登记**进架构文档前端目录树（本任务补登）。④ 四要素验收里写的 `python tools\tv.py --env test test all` 中 `--env` 对 `test` 子命令无效（test 固定 test 环境），实际执行 `python tools\tv.py test all`。
> * **覆盖边界（如实记录）**：项目**无 JS 测试层** → `user.js` 的 sheet 改造（含"本地余量内 0 请求"）只能靠**代码审查**，无自动化断言；"逐字节一致"断言在 pytest 侧比对的是**解包后的 `data` 载荷**（`json.dumps(sort_keys=True)`），非原始 HTTP body（Jackson 字段序稳定但未显式声明 `@JsonPropertyOrder`）。
> * **文档（G4 同 commit）**：`常青/CURRENT_ARCHITECTURE.md`（头部 3.4 + `BaseServletUtil` 行 99 + follow 域两行行数/职责 + 6.17 标题与接口口径/前端两段 + API 表 /follow 两行 + 前端目录树补登 `chunkedList.js`）、`常青/BUSINESS_FLOW.md`（头部 2.1 + 3.1 关注读路径注 + 4.3.3 返回结构与分页段 + 4.3.4 分页段）、本档三节/四节、`NEXT_CYCLE_NEEDS.md` 4.0/4.2、`INDEX.md` 周期状态行。
> * **待用户裁决**：无（本任务无 L3/L4 升级项）。**探针之外的提醒**：`FollowCache.getFollowerIds` 死方法（池 **U-21**）与"`chunkedList` 自适应 `chunkSize`（T11-B）"两件事都落在后续窗口，见上条去向。
> * **独立评审（subagent，只读）结论与处置（2026-09-20）**：**无 🔴 阻断项**，总体判定"可提交"；6 处 🟡 全部处置：
>   ① 🟡 `defaultSize` 未与 `max` 夹取（若某域误配 `(req, 200, 500)` 会越过上限）→ **已采纳**：fallback 改为 `Math.min(defaultSize, max)`，把"返回值恒 ≤ max"收进方法内（现有调用方 `(req,500,?)`／`(req,200,200)` 行为零变化）；
>   ② 🟡 "缺省 vs 显式"逐字节比对在**空列表**下近乎恒真 → **已采纳**：该用例改为在"b 关注 a 后查 a 的粉丝列表"的**非空**信封上比对，并加 `assert default_data["list"]` 前提，使 `list` 条目字段与顺序进入比对范围；
>   ③ 🟡 删 8 条 JUnit 用例后，`username` 装箱与**粉丝路径** `isSelf` 断言只剩隐性覆盖 → **已采纳**：在迁入的分页用例上补 2 条显式断言（`getFollowerListPagedAssemblesPageFromWindow` 加 `username` + `isSelf==false`；`getFollowingListPagedMarksSelfWhenIdMatchesCurrentUser` 加 `username`）；
>   ④ 🟡 回写里的"改动前"行数不准（`BaseServletUtil` 原记 49、实测 83；`FollowService` 原记 190、实测 189）→ **已采纳**：本段行数一律改为 `wc -l` 实测值（83 → 99 / 189 → 173 / 114 → 104），并在 G11 记录里保留"文档旧值 49 与实际 83 长期不符"这条事实；
>   ⑤ 🟡 前端无自动化断言（项目无 JS 测试层）→ **维持**，属既有覆盖缺口，已在"覆盖边界"如实记录；
>   ⑥ 🟡 `FollowCache.getFollowerIds` 死方法处置 → **维持**（登记 **U-21** + 留 T11-C 窗口评估），评审确认"现在删会与 C-2 的 `partial` 改造打架"。
>   评审同时核对通过：契约反转连带面（`follow/following|followers` 消费方仅 `user.js` + pytest）、三档重载与旧两参逐字节等价、其它域调用点零变化、`hasPagingParams` 无残留引用、文档结构（NEEDS 4.0 两段标题在位、UNPLANNED U-17/U-18/U-21 三行完整）、无 BOM/无新增行尾问题。
> * **评审后复跑复核（2026-09-20，处置后同一工作树）**：`test junit` exit 0、`{tests: 482, failures: 0, errors: 0, skipped: 0}`（`.stage8-target/surefire-reports/` 26 个报告逐类汇总亦 482/0/0/0；`FollowServiceTest` 仍 16）；`test all` exit 0、`build_ok=True`、pytest **145 passed / 0 failed**、`port_18080_open_after=false`。**首次全链与复跑数字一致**——本轮处置只涉及 fallback 的 `min(defaultSize, max)` 夹取、pytest 用例改为**非空**数据前提、JUnit 补 2 条断言，**用例总数未变**。

### T11-B 评论域固定信封 + 公共 helper 去重/自适应 + 内容列表前端迁移（feed/search/profile）

> **已拍板（2026-09-20 T11 窗口）**：① 评论域信封大小改由后端定（`default 200 / max 500`）；② `chunkedList` 增加**去重**与 `chunkSize` 自适应；③ 评论列表接入去重；④ `feed`·`search`·`profile` 三处内容列表**仅前端**迁移（`chunk=50` 顶现有 cap、`batch=10/12`），**后端改动不做**（上限与信封归 T19）。

* **意图**：① 评论域"信封大小由后端定"（前端不再传 `pageSize`）；② 公共 helper 具备**去重**能力，防热门内容/热门博主在翻页时重复展示同一条目；③ 三处内容列表请求数降到约 1/5（纯前端，零后端风险）。
* **入口线索**：`comment/controller/CommentController.java:22,93,117`（`COMMENT_PAGE_SIZE_MAX=500`，两处 case）、`views/detail.js:10-14,269-292`（评论 chunkedList）、`:455`（展开回复手写分页）、`js/chunkedList.js:16-65`、`views/follow.js:11,35-79`、`views/search.js:11,153,177-191`、`views/publish.js:115,236`、`views/user.js:11,119-194`（创作网格）。
* **已拍板技术方案（回写 NEEDS 4.0）**：
  * 评论域 `parsePageSize(req, 500, 200)`（`comment/show` 与 `comment/replies` 两处）：**上限 500 保留**（T10-B 既定口径不变）、**信封 200**（前端行为不变，只是决定权从"前端传 200"挪到"后端默认 200"）。
  * `chunkedList` 新增 `keyOf`（缺省依次取 `item.userId` / `item.commentId` / `item.id`）+ 内部 `seen` Set：`nextBatch` 过滤已出现条目；批内不足时继续消费本地余量或拉下一 chunk；**加循环上限防"整页重复"死循环**；`reset()` 清空 `seen`；未传 `keyOf` 时行为不变（向后兼容）。
  * `chunkedList` 支持"请求不带 `pageSize`"，并用响应回显的 `pageSize` 覆盖自身 `chunkSize`（自适应；首次失败用默认值兜底）。
  * 内容列表三处：`chunk = 50`（顶现有公共 cap，**后端不动**）+ `batch = 10/12`，顺带获得去重。
* **红线边界**：**去重不得掩盖后端分页 bug**——pytest 直打 API 的「页间不重不漏」断言**一条都不动**（后端契约仍保证不重不漏；去重只兜"翻页期间集合变化导致的 offset 漂移"，不替代正确性）；**不改** `/feed`·`/search`·`/profile` 的后端上限与默认值（留 T19）；不动评论缓存结构（T10-A/T10-B 已定稿）；不改 `/comment/*` URL 与信封形状；不引入前端依赖或构建工具（保持原生 ESM）。
* **强制探索步骤**：动刀前先 (0) 复核 `chunkedList` 现有契约与其在 `detail.js` 的使用形态、评论域两处分页解析点（G11） (1) 确认 `keyOf` 缺省顺序对 5 个列表都成立（评论主楼/回复 = `commentId`；用户 = `userId`；内容 = `id`） (2) 确认评论域 `defaultSize = 200` 不破坏 T10-B 既定口径（`max = 500` 保留） (3) 确认三处内容列表 `chunk = 50` 时 `loadedAll` 判定正确（`list.length < chunkSize` 语义） (4) 若清单未覆盖 → 回写本文档再动手。
* **验收**：`comment/show` 只传 `page` → `pageSize = 200`（旧断言 10 改写）、`pageSize = 999` → 500 不变；`chunkedList` 具 `keyOf`/`seen` 去重与 `chunkSize` 自适应（代码可检）；**后端「页间不重不漏」断言未改且全绿**；三处内容列表走公共 helper（代码可检 import）；全链 exit 0。**覆盖缺口（如实记录）**：项目无 JS 测试层，前端改动只能靠代码审查 + 手工验证，无自动化断言。

### T11-C 关注/粉丝列表装载侧解耦（池 U-18 治本：P1 前缀窗口装载）

> **已拍板（2026-09-20 T11 窗口）**：形态 = **P1 前缀窗口装载**（α"分页直读 DB"、β"任意窗口装载"均否决）；**拆两个 commit**：**C-1** = DDL + 窗口 DAO（纯新增、行为零变化）；**C-2** = 前缀装载 + 判定回落 + 写路径 `partial`。

* **意图**：冷/降级路径的装载量从 **O(列表总量)** 降到 **O(offset + 页大小)**；热点页面命中缓存 0 DB；完全装载后自动退化为「完整 ZSet」（等价 T7 现状）。场景锚：某博主 **100 万粉丝**时，任何一次粉丝/关注列表分页都不得触发百万行装载。
* **入口线索**：`cache/ZSetCache.java:159-193`（`getWindow` 的 miss 单飞**全量** loader 与降级**全量**装载 + 内存切片）、`:67-141`（`isMember` / `getMembers`）、`:195-276`（`batchIsMember`）、`follow/service/FollowCache.java:114-179`（判定/全量/窗口四读）、`:242-307,359-368`（写路径条件双写 + `probePair`）、`follow/dao/FollowDao.java:47-74`（全量 SQL 无 LIMIT/ORDER BY）、`cache/CacheKeys.java:72`（`empty()` 工厂）、`.docs/DBbackups/20260906_132538/db.sql:273-281`（follow 实索引实测）。
* **已拍板技术方案（回写 NEEDS 4.0）**：
  * **结构与不变量**：ZSet 成员 = DB 中按 id 升序的**前 W 个**（W = ZCARD）；新增 `partial:{数据key}` 标记——**无标记 = 完整**（等价 T7"key 存在即完整"，把不变量从存在性放宽为标记）、有标记 = 已知前缀；`empty:` 仍表示确认无数据。
  * **分页读**：`offset + count ≤ W` → `ZRANGE` + total（**无标记 → ZCARD（与 T7 逐字节一致）；有标记 → 计数 key**）；`> W` → 单飞内 DB 查 `[W, offset+count)`（**不依赖 total 做上界**，DB 返回不足即到底）追加 ZADD（不 DEL，合并并发写）+ 续期，到底则 DEL `partial`；miss → 窗口装载；**降级 → DB 窗口直查，不装载不写回**（取代原"全量装载 + 内存切片"）。
  * **判定**：`partial` 存在时 `isMember` / `batchIsMember` 的**未命中回落 DB**（批量复用既有 `dbAnswer` 机制，仅扩展触发条件）；单飞 key 需带**窗口指纹**，防不同页并发串用装载结果。
  * **全量读**：`getMembers` 遇 `partial` **必须补齐**（`FeedService.java:51` 依赖全量关注 ids，否则 feed 静默漏关注者 —— **本设计最危险点，评审重点**）。
  * **写路径**：`probePair` 增探 `partial`，**任一侧 `partial` → 双 DEL**（取关会在前缀留洞、关注会插入非前缀成员，二者均破坏 `ZRANGE offset` 语义），与既有"冷 key → 双 DEL"同构。
  * **DDL（G9，本任务定稿）**：`ALTER TABLE follow ADD KEY idx_followed_user_user (followed_user_id, user_id);`（粉丝方向窗口查询同序；关注方向复用 `uk_user_follow`）。命名在 C-1 执行前可调整；执行走三步备份闭环并向用户报备。
* **红线边界**：缓存失败不得导致业务失败；loader 抛 `ServerException`（500 语义）→ **不写任何 key、不写空标记**；降级不写回（三期 T2 口径）；**不改对外信封形状与排序口径**（仍 id 升序、`FollowPageResult` 字段不变）；不引入新依赖（Jedis 既有命令）；不碰 `buildUserList` 事务边界（T12）；`getFollowingIds` 的 feed 全量路径（R-01 保留）不在本任务范围。
* **强制探索步骤**：动刀前先 (0) 复核 U-18 证据（`ZSetCache` miss/降级全量装载、`FollowDao` 无 LIMIT）仍成立（G11） (1) 确认"前缀"不变量在 miss / 补齐 / 写路径三处都成立（写路径遇 `partial` 必须双 DEL） (2) 确认 `getMembers` 补齐后不再返回不完整集合（E7 风险） (3) 确认 total 双口径在完整态与 T7 逐字节一致（现有 pytest 断言不破） (4) DDL 走 G9 备份闭环并向用户报备 (5) 若清单未覆盖 → 回写本文档再动手。
* **验收**：JUnit 断言"窗口装载只查 `[W, offset+count)`、不触发全量 loader"、"`partial` 态判定未命中回落 DB"、"`getMembers` 部分态补齐"、"写路径遇 `partial` 双 DEL"、"降级走窗口直查"；pytest 分页用例（跨页不重不漏、顺序升序、total 与页内容一致）全绿；DDL 前后备份留证 + 3307 重建校验；全链 exit 0。
* **风险自查（执行时逐条自检；源自窗口过程档）**：
  * **R1 前缀不变量被写操作破坏**（取关留洞 / 关注插入非前缀成员 → `ZRANGE offset` 偏移语义失效）：已由"任一侧 `partial` → 双 DEL"堵住，**JUnit 必须覆盖**。
  * **R2 部分态 total 失真**（计数 key 与成员集瞬时不一致）：total 只影响展示与 `totalPages` 推导，页内容不受影响；写路径遇 `partial` 双 DEL 可在一个请求内收敛。
  * **R3 `getMembers` 返回不完整集合**（`FeedService.java:51` 静默漏关注者）：**本设计最危险点**，强制补齐 + JUnit 覆盖，**评审重点盯**。
  * **R4 降级语义变化**：降级从"全量装载 + 内存切片"改为"DB 窗口直查不写回" → 需在 `常青/CURRENT_ARCHITECTURE.md` 写明该语义变化（不是遗漏）。
  * **R6 证据层限制**：pytest 侧无 Redis 客户端（`requirements.txt` 仅 pytest + requests）→ **水位/装载量类断言只能落 JUnit**，不要试图用 pytest 断言 ZCARD（若确需，须先引入 `redis` 测试依赖，本任务不做）。
* **过程档（非权威、可被清理，仅供取证参考）**：`.docs/temp/T11_PLAN.md`（含证据表 E1~E17 与 P1 详细设计 4.1~4.7）。**决策源一律以本档四要素 + `NEXT_CYCLE_NEEDS.md` 4.0/4.2 为准。**

### T12 事务边界同型未治点 2 处（池 U-14）

* **入口线索**：`content/service/ContentService.search`（事务回调内逐 key `contentCache.getContent` + `ContentStatusFiller.fillLikeAndFollowBatch`）；`follow/service/FollowService.buildUserList`（回调内 `followCache.batchIsFollowing`，调用点在其分页读与缺省读路径）。目标：与第五期 T3（`260918/T3`）同款——**DB 查询与缓存读分离**，缓存读移到事务外，消除"外层事务持连接期间再去取连接装载"的叠加。
* **红线边界**：**不改对外行为**（返回集、异常语义、分页口径一概不变）；不动 T7 已落地的分页读路径语义；不为省事改事务语义或连接池参数。
* **强制探索步骤**：动刀前先 (0) 复核池 U-14 证据（两处位置与形态）仍成立（G11——不成立 → L3 暂停登记质疑） (1) 确认 `search` 侧是否把逐 key `getContent` 换成批量读（与 T2 已做的批量装载合并同源），并确认批量读口径与既有降级语义一致 (2) 确认两处早退分支（无命中/空结果）与异常产生位置保持 (3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：两处在事务回调内不再触碰缓存（结构可检）；相关 JUnit + 相关端点 pytest 全绿；对外行为零变化。

### T13 注册后自动登录缺兜底（池 U-16）

* **入口线索**：`user/controller/LoginController.register`（注册成功后调用 `userService.login(id, password)`，其失败路径无兜底）。目标：自动登录失败时返回"**注册成功、请手动登录**"，而不是无 token、无提示。
* **红线边界**：不改注册/登录的既有成功语义与错误码；**不引入自动重试/自动补登**；不改前端路由行为（仅在提示文案/响应字段上做最小改动，若涉及 `views/login.js` 消费方式需在窗口内确认后一并改）。
* **强制探索步骤**：动刀前先 (0) 复核池 U-16 证据（`LoginController.register` 失败路径）仍成立 (1) 确认返回结构变更方式（复用 `msg` 还是新增字段）与前端消费点 (2) 补可复现该失败路径的用例（JUnit 或 pytest） (3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：该失败路径有明确响应与用户可见提示；"注册成功但无 token 且无提示"的场景消失；相关测试全绿。

### T14 包层结构清扫：content↔comment 环 + 分页信封上移公共包 + `ResultMap` 反向依赖（R-02/R-03 + 池 U-19 + N14）

* **入口线索**：`content.ContentService` 注入 `comment.CommentService`（content→comment）；comment 域引用 `content.service.ContentCache` / `CommentCache`（comment→content）→ **包层环**（非 IoC/Bean 环）；`content/model/dto/PageResult` 与 `follow/model/dto/FollowPageResult` 字段与 JSON 形状完全一致（**同形二分**）；`dao/ResultMap.java:2` **反向 import** content 域的 `CommentCacheDTO`（基础包依赖业务模型，N14）。目标：包/依赖结构清扫，**行为零变化**。
* **红线边界**：**只做搬移与依赖调整**——不改接口契约、不改 JSON 形状、不动业务逻辑；不做跨域功能重构；不顺手改 `@WebServlet` URL / web.xml / IoC 扫描。
* **强制探索步骤**：动刀前先 (0) 复核 R-02（= 池 U-07）与 U-19 证据仍成立 (1) **先取得 R-03 拍板结论**（环的处置口径：拆共享组件 / 允许现状不治；信封上移的目标包与范围——涉 content 域 6 个 main 文件 + 4 个测试文件 import 调整）——未拍板 → L3 暂停 (2) 确认上移后 `JacksonCodec` 序列化与前端解析不受影响（JSON 形状不变） (3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：按拍板口径，包依赖单向（或环已按口径处置）；`PageResult` 唯一源落在公共包、无同形二分；`dao` 基础包不再 import 业务域模型；全量 JUnit + pytest 全绿且对外行为零变化。

### T15 顺手清理：架构文档与代码一致性（N16 + N9 文档部分）

* **入口线索**：`常青/CURRENT_ARCHITECTURE.md:72` 目录树里的 `ssm_*` 实际不存在；同文件 `:135` 的 AuthFilter 精确名单只列 7 项、代码实为 10 项（缺 `/content/update`、`/content/mediaDelete`、`/content/delete`）。目标：把"文档 vs 代码"的事实差异**一次扫完并修正**。
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
* **入口线索**：`content/controller/FeedController.java:26`、`content/controller/ProfileController.java:26`（均 `BaseServletUtil.parsePageSize(req)`）、`content/controller/SearchController.java`（`keywordSearch` 分页解析处）、`views/follow.js:41`、`views/search.js:153,191`、`views/publish.js:115`、`views/user.js:125`（三处前端已在 T11-B 接入 helper）。
* **红线边界**：只放开上限与信封口径 + 前端 chunk 上调；**不改分页语义 / 返回集 / 排序 / 跳过 null 口径**；`/profile` 的 `ProfileVO{contentPage}` 形状不变；不动 `/feed` 的关注 ids 全量读路径（R-01 保留）；不引入新依赖。
* **强制探索步骤**：动刀前先 (0) 复核三处 Controller 上限现状（公共 50）与前端 chunk 现状（T11-B 后为 50） (1) 定各域限额（建议 `200/200`）与前端 `batch` 的匹配 (2) 确认 `publish.js` 与 `user.js` 共用 `/profile` 的一致性（一次改两处消费） (3) 若清单未覆盖 → 回写本文档再动手。
* **验收**：三域 `pageSize` 上限 200 生效、只传 `page` 返回域级信封；前端三处改为只传 `page` 且 chunk 上调；相关 pytest 全绿（如有上限断言同步改写）；全链 exit 0。
