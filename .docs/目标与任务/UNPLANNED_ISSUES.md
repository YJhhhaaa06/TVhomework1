# 未排期事项（待决策池）

> 用途：登记**发现需要改、但暂时没有排进任务清单**的内容（文档滞后 / 文案与实现不一致 / 代码债 / 观察项等），避免遗忘或散落在各任务探索结论里。
> 原则：本档只**登记与留存**，不承诺排期；是否消化、何时排期由用户评审决定。
> 分流：排进当/下周期任务清单 → 移出本档并注明去向；确认不改（won't fix）→ 标记"废弃"并留一句理由。

***

## 一、登记规则

| 项 | 规则 |
|----|------|
| 谁登记 | 任何任务执行中发现（G5 超范围暂停的落点之一）；用户/评审时也可直接登记 |
| 记什么 | 一句话问题 + 证据/位置 + 来源（周期/任务/日期）+ 建议方向（可选） |
| 何时消化 | 用户评审本档时决定：排期（进任务清单）/ 随手修 / 废弃 |
| 与结转关系 | 跨周期的"方向级"事项（如缓存改造、feed 改造）仍走需求与痛点文档的结转/方向预告；**本档只收"颗粒度小的具体问题"** |
| 保持同步 | 归档周期时本档同步归档至 `archive/目标与任务/<周期名>/`，新周期延续新本 |

***

## 二、已登记候选事项

| 编号 | 类别 | 问题 | 位置/证据 | 来源 | 状态 |
|----|------|------|-----------|------|------|
| U-01 | 文档滞后 | CURRENT_ARCHITECTURE 7.4/7.5 将 `/like/content/count`、`/like/comment/count` 标为**无需登录**，与 AuthFilter `/like` 前缀**实际要求登录**不符 | [CURRENT_ARCHITECTURE.md](file:///d:/javaproject/VideoPlatform/TVhomework1/.docs/常青/CURRENT_ARCHITECTURE.md) 七.4/七.5（7.4/7.5 表） | 上周期 T2 探索记录（明确"不在本期处理"） | 待定 |
| U-02 | 文档滞后 | BUSINESS_FLOW 2.3 步骤表"用户不存在抛 RuntimeException"表述过时，现状为 `UserNotFoundException → 401` | .docs/常青/BUSINESS_FLOW.md 二.2.3 | 上周期 T3 探索记录（纯文档滞后未改） | 待定 |
| U-03 | 文案与实现不一致 | 密码格式：正则实测 `^[a-zA-Z0-9]{1,16}$`，与界面/文档文案"6~16 位"不符（5 位纯字母数字实际可过） | 代码 PasswordUtil + 需求文案 | 上周期 T3 探索记录 | 待定 |
| U-04 | 代码债 | `LikeCacheService.deleteCommentLike` 用 `contentLikeKey(commentId)` 删除 `content:like:{id}` 而非 `comment:like:{id}`，疑似复制粘贴 bug（上周期只测现状未修） | LikeCacheService.java:258-263 | 上周期 T8 探索记录 | 待定 |
| U-05 | 代码债 | `LikeService.likeContent` 缓存更新在事务外，靠定时刷新兜底（计数暂可能不准）——已结转至后续方向 C，此处留痕 | LikeService.java（缓存写入点） | 结转向 P5（需求文档二） | 已结转方向 C |
| U-06 | 观察 | 业务异常不统一（UserService 等既有历史表述）——BUSINESS_FLOW 八.问题1 提及 | BUSINESS_FLOW.md 问题1 | 上周期 T3 红线记录（"既有技术债，本任务只测现状"） | 待定 |
| U-07 | 观察（包架构） | content ↔ comment 包层循环依赖：content 域共享组件（ContentCacheDTO 等）被 comment 域引用，而 content 域又引用 comment 域的 CommentService。**非 IoC/Bean 环**（依赖链 ContentService→CommentService→ContentCacheManager 有向无环，容器可正常构建、测试全绿），仅包架构不纯净，Java 允许 | ContentService 注入 CommentService；CommentService 注入 ContentCacheManager | 本期（B）探索记录（2026-09-10） | 待定（消化点：后续 C 缓存改造） |

> 注：U-05 与需求文档"二、结转遗留未决项 P5"重复，但按"颗粒度"归本档留痕，方向归属仍以需求文档为准（C 缓存改造时消化）。

***

## 三、登记模板

```markdown
| U-0N | 类别 | 一句话问题 | 位置/证据链接 | 来源（周期/任务/日期） | 待定 |
```

类别取值示例：文档滞后 / 文案与实现不一致 / 代码债 / 观察 / 其它。

***

## 四、变更记录

为了减缓文档膨胀，本文档不写变更记录