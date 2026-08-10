# 当前任务清单

> 版本：2.0
> 生成日期：2026-08-09
> 状态：待执行（以 2026-08-09 实际代码为准）
> 用途：供 AI 与用户逐步执行架构优化与灾后收尾任务

---

## 使用说明

### 任务状态标记

| 标记 | 含义 |
|------|------|
| `[ ]` | 待执行 |
| `[~]` | 执行中 |
| `[x]` | 已完成 |
| `[!]` | 阻塞/有问题 |

### 执行规则

1. 严格按顺序执行：必须完成前置依赖才能开始下一任务。
2. 每完成一个任务：将 `[ ]` 改为 `[x]`，记录完成日期。
3. 遇到问题：改为 `[!]`，记录问题描述。
4. 每个任务完成后必须验证功能不变：`mvn compile` + `python tools/run_tests.py all`。
5. **数据库任何变更前先执行备份脚本（TASK-008 完成后强制使用）**。
6. 重构或架构改动后，按 AGENTS.md 同步更新 `CURRENT_ARCHITECTURE.md`；业务流程变化后更新 `BUSINESS_FLOW.md`。
7. **TASK-001（手动清理未引用方法）执行人为用户**；AI 只提供辅助清单与编译/测试验证，不代删。

### 已完成事项（截至 2026-08-09）

以下工作已在文档修订前完成，不重复计任务，仅作历史记录：

- TestServlet.java 已删除，web.xml 引用同步移除（commit 3a8a72d）。
- RedisTest.java、dao/daotest.java、exception/Test.java 已删除。
- `web/` 空壳目录已不存在。
- 灾后重建：RequestContext 动态 context path；DB 新增 `file_exists`/`last_verify_time`；MediaAuditService/MediaAdminController/recovery.html；34 个视频文件写回。
- 一键测试脚本 tools/run_tests.py 可用，8/8 验证 35/35 pytest 通过。
- 2026-08-09：手动清理完成（记录见 `.docs/REMOVE_CODE.md`）：移除 Service 12 个方法、DAO 31 个方法及 CouponDao 注释 DDL；`deleteContent`/隐藏/删除类功能确认不做。
- 2026-08-09：TASK-003/005/006/008/009 完成（UploadController 补 return、欢迎页 index.html、LogUtil 自动建 logs 目录、tools/backup.py、.http 基址/token/素材路径修正）。
- 2026-08-09：TASK-007 完成（数据清理：pytest/smoke 测试内容 46+2 条、孤儿 content_media 7 条、孤儿 comment_like 1 条；中文标题测试内容保留，遗留表未 DROP，报告见 CLEANUP_REPORT）。

---

## 阶段零：灾后收尾与备份（P0）

> 目标：清理重构前的误导源，修复已知 bug，建立备份机制，防止再次发生数据事故。

---

### TASK-001: 手动清理未被引用的方法（用户执行）

- **优先级**: P0
- **预估工时**: 1-2 天（用户人工）
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-09 完成，清理记录见 `.docs/REMOVE_CODE.md`）

#### 任务描述

重构开始前，用户在 IDEA 中逐包执行未使用分析（Analyze → Inspect Code / Find Unused 等价功能），AI 配合用 `rg` 复核并输出"疑似未引用方法清单"。用户人工确认后手动删除，删除时跳过已计划重构的类（如 ContentService 拆分目标方法先保留）。

#### 执行分工

- **执行人**：用户（IDEA 手工确认与删除）。
- **AI 职责**：提供 `rg` 复核清单、检查删除后的编译与测试结果，不代删。

#### 验证标准

- [ ] 用户确认无遗漏的未引用方法
- [ ] `mvn compile` 通过
- [ ] `python tools/run_tests.py all` 全绿

---

### TASK-002: 【已取消】修复 ContentDao 删除/隐藏 SQL 列名 bug

- **优先级**: P0
- **预估工时**: 15 分钟
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-09 取消：`deleteContent/hideContent/unhideContent` 已随清理删除，无需修复列名）

#### 任务描述

原任务为修复 `ContentDao.deleteContent/hideContent/unhideContent` 的 `content_id` 列名错误。2026-08-09 决策：删除/隐藏内容功能不做，三个方法随清理一并删除，本任务不再需要。

---

### TASK-003: 修复 UploadController action==null 缺 return

- **优先级**: P0
- **预估工时**: 5 分钟
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-09 完成）

#### 任务描述

`doPost` 中 `action == null` 分支写错误响应后没有 `return`，会继续执行 `switch (action)` 触发 NPE。

#### 涉及文件

`src/main/java/com/itheima/controller/UploadController.java`（约 45 行）

#### 验证标准

- [ ] 分支补 `return`（或改为提前返回结构）
- [ ] 无 pathInfo 的 POST /api/upload 返回 404 而非 500

---

### TASK-004: 【已取消】补齐 ContentService.deleteContent 语义

- **优先级**: P0
- **预估工时**: 1 小时
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-09 取消：删除功能确认不做，相关方法已删除）

#### 任务描述

原任务为补全 `ContentService.deleteContent`（作者校验 + 逻辑删除 + 文件删除 + 缓存清理）。2026-08-09 决策：删除视频/帖子功能暂不做，Service 与 DAO 相关方法已随清理删除；`FileUploadService.deleteFileQuietly` 仍由上传失败回滚使用，保留。

---

### TASK-005: 修复 web.xml 欢迎页

- **优先级**: P0
- **预估工时**: 15 分钟
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-09 完成：新增 index.html 跳转 start.html）

#### 任务描述

`web.xml` 欢迎页声明为 `index.jsp`，但项目不存在该文件，访问根路径会 404。决策：新建 `src/main/webapp/index.html`，自动跳转 `start.html`；`web.xml` 欢迎页改为 `index.html`。

#### 验证标准

- [ ] 访问 `/` 能跳转到首页而非 404
- [ ] `mvn compile` 通过

---

### TASK-006: 修复 LogUtil 日志目录

- **优先级**: P0
- **预估工时**: 30 分钟
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-09 完成：启动时自动创建 logs 目录）

#### 任务描述

`LogUtil` 用 `FileHandler("logs/system.log", true)`，但 `logs/` 目录不存在，FileHandler 创建失败导致文件日志不可用。改造：静态初始化时 `Files.createDirectories` 创建日志目录（路径可配置，默认 `logs/`），失败时降级为控制台并记录 WARNING。

#### 涉及文件

`src/main/java/com/itheima/util/LogUtil.java`

#### 验证标准

- [ ] 启动后 `logs/system.log` 文件生成且有内容
- [ ] 目录不存在时自动创建，不抛异常

---

### TASK-007: 数据清理（备份后执行）

- **优先级**: P0
- **预估工时**: 1 天
- **前置依赖**: TASK-008（先备份）
- **状态**: `[x]`（2026-08-09 完成，报告见 `.docs/CLEANUP_REPORT_2026-08-09.md`）

#### 任务描述

清理 8/8 事故遗留的脏数据（清理前必须整库备份，并生成清理报告）：

| 清理项 | 说明 |
|--------|------|
| 测试内容污染 | `pytest_test_video`/`smoke_test_video` 前缀内容及其 media/comment（2026-08-09 用户决策：中文标题的测试内容保留，不纳入清理） |
| 孤儿 content_media | 7 条（media id 50~56，指向不存在的 content 56~58）：先记录 URL 到清理报告，再删除 |
| 孤儿 comment_like | 1 条指向不存在的 comment 54，删除 |
| 遗留表 | `video`/`videoinfo` 已确认代码无引用，但暂不 DROP（2026-08-09 用户决策：先保留，仅清理数据时不涉及）；`comment_media` 空表结构保留不动 |

#### 涉及文件

`tools/cleanup_data.py` 已创建（默认只读预览，`--execute` 才写库；执行前自动备份；输出清理报告到 `.docs/`）

#### 验证标准

- [ ] 备份文件存在且 dump 非空
- [ ] Feed 中不再出现测试内容
- [ ] 清理报告记录每项删除的 id/URL
- [ ] `python tools/run_tests.py all` 全绿

---

### TASK-008: 备份自动化（tools/backup.py）

- **优先级**: P0
- **预估工时**: 半天
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-09 完成，已实跑验证一次）

#### 任务描述

新增 Python 脚本 `tools/backup.py`（AGENTS.md 要求脚本必须 Python）。**2026-08-09 用户决策：只备份数据库；媒体资源由用户手动打包备份；每次备份后由用户手动把数据库备份迁移到工作区外。**

1. `mysqldump --single-transaction --routines --triggers` 导出 `tvdatabase` 到 `D:/dev/WorkSpace/VideoPlatform/auto_backup/<yyyyMMdd_HHmmss>/db.sql`。
2. 生成 manifest：时间、库名、文件大小、SHA-256、备份说明。
3. 校验：db.sql 非空且包含 `Dump completed` 标记。
4. 不删除旧备份；退出码约定（0 成功 / 2 mysqldump 不可用 / 3 校验失败）。
5. 媒体资源（stone/video、image、cover）由用户手动打包备份，脚本不做。

#### 验证标准

- [ ] 脚本可运行并生成 db.sql + manifest.txt
- [ ] 校验逻辑能发现空/损坏的备份
- [ ] 第二次运行不覆盖第一次备份

---

### TASK-009: 修正 .http 测试文件

- **优先级**: P0
- **预估工时**: 2 小时
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-09 完成：基址去 /MyAPP、token 改占位符、素材指向 TestResource）

#### 任务描述

`.http` 文件仍使用 `/MyAPP` 基址（当前部署为根路径）、`D:/stone`/`D:/upload` 旧路径与过期 token。统一改为：

- 基址 `http://localhost:8080`（或说明以当前运行配置为准）。
- 文件路径改为实际存在的素材路径，失效用例删除或标注"需人工准备素材"。
- token 改为注释说明"先登录取 token"。

#### 验证标准

- [ ] 无 `D:/stone`、`D:/upload`、`/MyAPP` 残留
- [ ] 保留的用例可手工执行通过

---

## 阶段一：基础清理与包结构（P1）

> 目标：清理残留测试/空壳代码，统一包结构。

---

### TASK-010: 删除剩余测试/空壳类

- **优先级**: P1
- **预估工时**: 1 小时
- **前置依赖**: TASK-001
- **状态**: `[x]`（2026-08-10 完成：`git rm` 删除 6 个类并更新 `.http` 注释，复核零引用；`run_tests.py build` 与 35/35 pytest 全绿）

#### 任务描述

删除以下已确认无引用的类（先 `rg` 复核引用，删除后编译验证）：

| 文件 | 说明 |
|------|------|
| `controller/Test3.java` | 测试类 |
| `service/MenuService.java`、`service/ImageService.java` | 空壳 |
| `controller/UploadServlet.java` | 遗留旧版上传 Servlet（确认无映射引用后删） |
| `dao/CommentMediaDao.java` | 空壳（表结构保留，无代码使用） |
| `util/CheckUtil.java` | 空壳 |

#### 验证标准

- [ ] 上述文件已删除且无引用残留
- [ ] `mvn compile` 通过

---

### TASK-011: 移动 CouponAdmin 到测试工具目录

- **优先级**: P1
- **预估工时**: 30 分钟
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-10 完成：移至 `src/test/java/com/itheima/tools/CouponAdmin.java`，包名 `com.itheima.tools`，`test-compile` 通过）

#### 任务描述

`CouponAdmin` 是带 main 的运维工具，不应进生产 war。移动到 `src/test/java/com/itheima/tools/CouponAdmin.java`，包名同步改为 `com.itheima.tools`。

#### 验证标准

- [ ] 主代码目录无 CouponAdmin
- [ ] 工具仍可独立运行（创建优惠券）

---

### TASK-012: 删除 ssm_*/util 空壳子模块

- **优先级**: P1
- **预估工时**: 30 分钟
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-10 完成：`git rm` 删除 5 个目录，`.idea/encodings.xml` 引用同步清理，`mvn compile` 通过）

#### 任务描述

删除根目录遗留的 `ssm_controller/`、`ssm_dao/`、`ssm_pojo/`、`ssm_service/`、`util/`（均被 git 跟踪，含 Test1/Test2 与 pom.xml）。先检查 `.idea/modules.xml` 是否引用，移除引用后 `git rm -r`。

#### 验证标准

- [ ] 上述目录已从工作区与 git 移除
- [ ] IDEA 无残留模块引用
- [ ] `mvn compile` 通过

---

### TASK-013: 包名 DTO → dto

- **优先级**: P1
- **预估工时**: 1 小时
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-10 完成：目录与 package/import 全部改为 `com.itheima.dto`，`mvn compile` 通过）

#### 任务描述

将 `com.itheima.DTO` 重命名为 `com.itheima.dto`，更新全部 package/import。

#### 验证标准

- [ ] 无 `com.itheima.DTO` 残留
- [ ] `mvn compile` 通过

---

### TASK-014: 合并 pojo/DTO/command 为 model 包

- **优先级**: P1
- **预估工时**: 2 小时
- **前置依赖**: TASK-013
- **状态**: `[x]`（2026-08-10 完成：28 个类按 entity/dto/vo/cache/audit/command 归入 `com.itheima.model`，通配符导入展开，`mvn compile` 通过）

#### 任务描述

合并为 `com.itheima.model`，按职责分子包：

```
com.itheima.model/
├── entity/    # User、ContentMedia
├── dto/       # LoginDTO、RegisterDTO、ChangePasswordDTO、CommentDTO、SearchDTO、PageResult、GrabCouponRequest
├── vo/        # LoginVO（原 LogInVO）、ProfileVO、ContentVO、ContentDetailVO、CommentVO、UploadResult
├── cache/     # ContentCacheDTO、CommentCacheDTO
├── audit/     # MediaAuditItem、MediaAuditResult、RestoreResult（灾后新增）
└── command/   # CommandConverter、LoginCommand、RegisterCommand、ChangePasswordCommand、
               # UploadCommand、CommentCommand、ContentType、LoginType
```

#### 验证标准

- [ ] 全部类迁移完成，无旧包引用
- [ ] `mvn compile` 通过

---

### TASK-015: 统一类名 LogInVO → LoginVO

- **优先级**: P1
- **预估工时**: 15 分钟
- **前置依赖**: TASK-014
- **状态**: `[x]`（2026-08-10 完成：类与全部引用改为 `LoginVO`，`mvn compile` 通过）

#### 任务描述

重命名 `LogInVO` 为 `LoginVO`，更新全部引用。

#### 验证标准

- [ ] 无 `LogInVO` 残留
- [ ] `mvn compile` 通过

---

## 阶段二：配置外部化（P1）

> 目标：消除全部硬编码配置。

---

### TASK-016: 创建 app.properties 与 AppConfig

- **优先级**: P1
- **预估工时**: 1 小时
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

创建 `src/main/resources/app.properties`（db/redis/jwt/upload/cache/log，见 ARCHITECTURE_PLAN 6.2）与 `com.itheima.config.AppConfig`（静态读取 + 环境变量覆盖：`DB_PASSWORD`、`JWT_SECRET`、`UPLOAD_PATH`、`LOG_PATH`）。

#### 验证标准

- [x] 配置在 classpath 可读取
- [x] 环境变量能覆盖默认值（LOG_PATH 端到端验证）
- [x] `mvn compile` 通过

---

### TASK-017: 改造 MyConnectionPool 使用配置

- **优先级**: P1
- **预估工时**: 30 分钟
- **前置依赖**: TASK-016
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

URL/USER/PASSWORD/INIT_SIZE 改为 `AppConfig` 读取。

#### 验证标准

- [x] 无硬编码连接串
- [x] 连接数据库正常

---

### TASK-018: 改造 MyRedisPool 使用配置

- **优先级**: P1
- **预估工时**: 15 分钟
- **前置依赖**: TASK-016
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

host/port/maxTotal/maxIdle/minIdle 改为 `AppConfig` 读取。

#### 验证标准

- [x] 无硬编码 Redis 地址
- [x] Redis 连接正常

---

### TASK-019: 改造 JwtUtil 使用配置

- **优先级**: P1
- **预估工时**: 15 分钟
- **前置依赖**: TASK-016
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

`SECRET`/`EXPIRE_TIME` 改为 `AppConfig` 读取。

#### 验证标准

- [x] 密钥与过期时间来自配置
- [x] 登录 token 生成/校验正常

---

### TASK-020: 改造 FileUploadService 与 context.xml

- **优先级**: P1
- **预估工时**: 30 分钟
- **前置依赖**: TASK-016
- **状态**: `[x]`（2026-08-10 完成，采用启动校验方案）

#### 任务描述

`FileUploadService.BASE_PATH` 改为 `AppConfig.getUploadPath()`；`context.xml` 的 `<Resources base=...>` 改为 `${upload.path}` 对应值（或启动时由监听器校验一致）。

#### 验证标准

- [x] 上传文件写入配置路径
- [x] `/upload` 静态资源映射正常

---

### TASK-021: 改造 LogUtil 使用配置

- **优先级**: P1
- **预估工时**: 15 分钟
- **前置依赖**: TASK-016
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

日志路径与级别改为 `AppConfig`（`log.file`、`log.level`），保留 TASK-006 的自动建目录逻辑。

#### 验证标准

- [x] 日志写入配置路径
- [x] 级别配置生效

---

### TASK-022: 改造 ContentService 缓存配置

- **优先级**: P1
- **预估工时**: 15 分钟
- **前置依赖**: TASK-016
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

`CONTENT_TTL_MS` 与定时刷新间隔改为 `AppConfig`（`cache.content.ttlMinutes`、`cache.content.refreshMinutes`）。

#### 验证标准

- [x] 无硬编码 10 分钟常量
- [x] 缓存刷新正常

---

## 阶段三：异常与日志规范化（P1/P2）

> 目标：统一异常体系与日志规范。

---

### TASK-023: ErrorCode 接口 → 枚举

- **优先级**: P1
- **预估工时**: 30 分钟
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

按 ARCHITECTURE_PLAN 5.3 改为枚举，**必须保留 UNAUTHORIZED=401、FORBIDDEN=403、NOT_FOUND=404、CONFLICT=409、PARAM_ERROR=400、SERVER_ERROR=500 全部现有码**。

#### 验证标准

- [x] 枚举包含全部现有码
- [x] `mvn compile` 通过（一次性替换全部引用）

---

### TASK-024: 创建具体异常类

- **优先级**: P1
- **预估工时**: 30 分钟
- **前置依赖**: TASK-023
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

创建 UserNotFoundException、PasswordIncorrectException、TokenExpiredException、AccessDeniedException、ContentNotFoundException、CommentNotFoundException、DuplicateLikeException、DuplicatePhoneException、InvalidPhoneException、InvalidPasswordException、DatabaseException、CacheException（均继承现有 BusinessException 子类）。

#### 验证标准

- [x] 异常类齐全
- [x] 每个异常有便捷构造器（仅 message）

---

### TASK-025: 替换 RuntimeException

- **优先级**: P1
- **预估工时**: 2 小时
- **前置依赖**: TASK-024
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

业务代码中的 `throw new RuntimeException("USER_NOT_FOUND")` 等全部替换为具体业务异常（UserService 约 12 处、ContentService、FileUploadService、IocContainer 基础设施类保留容器异常）。

#### 验证标准

- [x] 业务包无 `throw new RuntimeException`
- [x] `mvn compile` 通过

---

### TASK-026: 统一异常信息

- **优先级**: P1
- **预估工时**: 30 分钟
- **前置依赖**: TASK-025
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

异常消息统一为 ErrorCode 中的中文消息，无英文常量直接作为用户可见信息。

#### 验证标准

- [x] 无 `"USER_NOT_FOUND"` 等英文常量异常消息

---

### TASK-027: 全局异常处理

- **优先级**: P1
- **预估工时**: 1 小时
- **前置依赖**: TASK-025
- **状态**: `[x]`（2026-08-10 完成，采用 ExceptionFilter 方案）

#### 任务描述

新增全局异常处理 Filter（或统一 BaseServlet 出口），Controller 删除重复 `catch + printStackTrace`；未处理异常返回 500 统一消息。

#### 验证标准

- [x] Controller 无重复 catch 样板
- [x] 业务异常返回对应 code，未知异常返回 500
- [x] `python tools/run_tests.py all` 全绿

---

### TASK-028: 日志清理与脱敏

- **优先级**: P2
- **预估工时**: 1 小时
- **前置依赖**: TASK-006、TASK-027
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

删除全部业务路径 `e.printStackTrace()` 与 `System.out.println`；手机号/密码/token 脱敏；日志级别按规范调整。

#### 验证标准

- [x] 业务代码无 `e.printStackTrace()`（`rg` 验证）
- [x] 日志无明文密码/完整 token

---

## 阶段四：事务与 DAO 统一（P2）

> 目标：统一连接与事务管理，消除 DAO 自取连接。

---

### TASK-029: 创建 TransactionTemplate

- **优先级**: P2
- **预估工时**: 1 小时
- **前置依赖**: TASK-017
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

`com.itheima.util.TransactionTemplate`：封装 `getConnection → setAutoCommit(false) → 回调(conn) → commit → finally release`，异常时 rollback 并抛 ServerException；支持 `execute(TransactionAction<T>)`。

#### 验证标准

- [x] 提交/回滚/释放路径正确
- [x] 回调抛异常时连接回滚并归还

---

### TASK-030: 改造 Service 使用 TransactionTemplate

- **优先级**: P2
- **预估工时**: 2 小时
- **前置依赖**: TASK-029
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

UserService、CommentService、FollowService、LikeService、ContentService、MediaAuditService 中手工 `setAutoCommit(false)/commit/rollback` 全部替换为 TransactionTemplate；只读操作统一走模板或连接获取封装。

#### 验证标准

- [x] Service 无手工事务样板代码
- [x] 点赞/评论/关注/注册等事务操作回归通过

---

### TASK-031: DAO 统一接收 Connection

- **优先级**: P2
- **预估工时**: 2 小时
- **前置依赖**: TASK-030
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

删除 UserDao/ContentDao/CouponDao/CommentLikeDao/ContentLikeDao 等内部 `MyConnectionPool.getConnection/release` 包装方法，只保留接收 Connection 的重载；如保留 BaseDao 仅作为 ResultSet 映射工具，**不得自行获取连接**。

#### 验证标准

- [x] DAO 无自取连接代码
- [x] 全部 DAO 调用点适配
- [x] `python tools/run_tests.py all` 全绿

---

## 阶段五：Service 拆分（P2）

> 目标：拆解大 Service，明确职责。

---

### TASK-032: 创建 ContentCacheManager

- **优先级**: P2
- **预估工时**: 3 小时
- **前置依赖**: TASK-031
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

从 ContentService 迁移缓存字段与方法到 `ContentCacheManager`（@Component 实例 Bean，非静态）：recommendList/contentCache/commentCache/contentTimestamps/typeCategoryIndex、TTL 与索引管理、定时刷新。

**关键**：同步迁移 CommentService（2 处）与 LikeService（4 处）对 ContentService 静态缓存方法的调用，改为注入 ContentCacheManager 后调用实例方法。

#### 验证标准

- [x] 缓存代码已迁出 ContentService
- [x] 无 `ContentService.` 静态缓存调用残留
- [x] 缓存刷新/回填回归通过

---

### TASK-033: 创建 ContentStatusFiller

- **优先级**: P2
- **预估工时**: 1 小时
- **前置依赖**: TASK-032
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

迁移状态填充方法：`fillLikeAndFollowBatch`、`fillFollowStatus`、`fillContentLikeStatus`（注入 LikeService/FollowDao）。

#### 验证标准

- [x] 状态填充代码已迁出
- [x] 列表/详情点赞关注状态正确

---

### TASK-034: 重构 ContentService

- **优先级**: P2
- **预估工时**: 2 小时
- **前置依赖**: TASK-032、TASK-033
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

ContentService 注入 ContentCacheManager/ContentStatusFiller，保留查询与发布方法；行数目标 < 300。

#### 验证标准

- [x] ContentService 精简（153 行；行数为目标、非强制验收）
- [x] 首页/搜索/详情/上传/删除全部回归通过
- [x] `python tools/run_tests.py all` 全绿

---

### TASK-035: 拆分 UserService（P3 可选）

- **优先级**: P3
- **预估工时**: 1 小时
- **前置依赖**: TASK-030
- **状态**: `[ ]`

#### 任务描述

认证方法（login/register/changePassword）与资料方法拆分；若拆分为 UserProfileService，同步更新控制器引用。

#### 验证标准

- [ ] 用户认证与资料逻辑分离
- [ ] 登录/注册/改密回归通过

---

## 阶段六：安全加固（P1）

> 目标：消除权限与数据安全风险。

---

### TASK-036: SQL 注入复查

- **优先级**: P1
- **预估工时**: 1 小时
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

全量复查 DAO：确认 PreparedStatement 参数化、动态 SQL 全占位符、无 `Statement` 拼接；审计结果记录到 CURRENT_ARCHITECTURE.md。

#### 验证标准

- [x] 审计记录完成
- [x] 无注入风险点

---

### TASK-037: 管理员角色

- **优先级**: P1
- **预估工时**: 1 天
- **前置依赖**: TASK-008（备份）
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

1. `users` 表新增 `role TINYINT NOT NULL DEFAULT 0`（0 普通 / 1 管理员），先备份。
2. AuthFilter 对 `/api/admin/*` 校验 `role == 1`，非管理员 403。
3. recovery.html 非管理员隐藏入口与操作。
4. 提供 Python 脚本创建/提升管理员。

#### 验证标准

- [x] 普通用户访问 /api/admin/* 返回 403
- [x] 管理员可正常扫描/恢复
- [x] 运维页对非管理员隐藏

---

### TASK-038: 资源所有权校验

- **优先级**: P1
- **预估工时**: 1 小时
- **前置依赖**: TASK-037
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

确认修改密码使用 token 中的 userId；运维恢复仅管理员（依赖 TASK-037）。内容删除功能已取消，无删除接口需要校验。

#### 验证标准

- [x] 普通用户无法执行运维扫描/恢复
- [x] 越权返回 403

---

### TASK-039: XSS/CSRF 加固（P2 可选）

- **优先级**: P2
- **预估工时**: 1 天
- **前置依赖**: 无
- **状态**: `[ ]`（2026-08-10 本轮跳过，后续再做）

#### 任务描述

参数校验 + 输出编码复查（Jackson 默认转义）；CSRF 暂不引入 Token 体系，记录决策。

#### 验证标准

- [ ] 输入校验覆盖用户可控字段
- [ ] 文档记录 CSRF 决策

---

## 阶段七：IoC 增强与连接池（P2）

---

### TASK-040: IoC 构造器注入

- **优先级**: P2
- **预估工时**: 1 天
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

新增 `@InjectConstructor`；IocContainer 优先解析带注解构造器参数，未注解回退无参+字段注入；改造核心 Service 使用构造器注入。

#### 验证标准

- [x] 容器启动正常，无循环依赖
- [x] 构造器注入的 Bean 可正常获取

---

### TASK-041: IoC 生命周期接口

- **优先级**: P2
- **预估工时**: 半天
- **前置依赖**: TASK-040
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

新增 Initializable/Disposable 接口并接入容器；保留现有 `shutdown()` 反射兼容。

#### 验证标准

- [x] 初始化/销毁回调顺序正确
- [x] 原有 shutdown 逻辑不丢失

---

### TASK-042: 连接池上限与超时

- **优先级**: P2
- **预估工时**: 1 小时
- **前置依赖**: TASK-017
- **状态**: `[x]`（2026-08-10 完成）

#### 任务描述

MyConnectionPool 增加 MAX_SIZE（配置 `db.pool.maxSize`，默认 20）与获取超时（`db.pool.timeoutMs`，默认 5000），池空且未满时新建、满时等待、超时抛 SQLException。

#### 验证标准

- [x] 超过上限不无限创建连接
- [x] 超时抛出异常
- [x] 回归测试通过

---

## 阶段八：测试建设（P1）

> 目标：建立单元测试与集成测试体系（JUnit + Mockito + 现有 pytest）。

---

### TASK-043: pom 添加 JUnit/Mockito 依赖

- **优先级**: P1
- **预估工时**: 30 分钟
- **前置依赖**: 无
- **状态**: `[x]`（2026-08-10 完成：JUnit 6.0.0 + Mockito 5.20.0 + ByteBuddy 1.17.8 + Surefire 3.2.5）

#### 任务描述

添加 JUnit Jupiter 5.x 与 Mockito 5.x（scope=test）。**具体版本以离线 m2-repo（D:/dev/WorkSpace/VideoPlatform/maven）中可用且兼容 JDK 25 为准**；若无对应包，暂停本任务并请用户补充离线仓库。

> 2026-08-10 实际落地：离线仓库无完整 JUnit 5.x 套装（launcher 1.12.1 缺失且 5.12.1+1.9.3 版本不对齐），经用户确认改用 **JUnit 6.0.0 全套 + Mockito 5.20.0 + Surefire 3.2.5**，已在 JDK 25 离线端到端验证。

#### 验证标准

- [x] `mvn test-compile` 通过
- [x] 一个最小 JUnit 用例可运行

---

### TASK-044: UserService 单元测试

- **优先级**: P1
- **预估工时**: 1 天
- **前置依赖**: TASK-040、TASK-043
- **状态**: `[x]`（2026-08-10 完成：UserServiceTest 23 例）

#### 任务描述

Mock UserDao/PasswordUtil，覆盖 login 成功/密码错误/用户不存在、register 手机号重复、changePassword 旧密码错误等。

#### 验证标准

- [x] 核心分支有断言
- [x] `mvn test` 通过

---

### TASK-045: ContentService（拆分后）单元测试

- **优先级**: P1
- **预估工时**: 2 天
- **前置依赖**: TASK-034、TASK-043
- **状态**: `[x]`（2026-08-10 完成：ContentServiceTest 11 例）

#### 任务描述

Mock DAO/CacheManager/StatusFiller，覆盖推荐、搜索、详情、发布、删除校验。（删除功能未实现，校验类分支不适用）

#### 验证标准

- [x] 核心查询/发布分支有断言
- [x] `mvn test` 通过

---

### TASK-046: LikeService/CommentService 单元测试

- **优先级**: P2
- **预估工时**: 1 天
- **前置依赖**: TASK-043
- **状态**: `[x]`（2026-08-10 完成：LikeServiceTest 14 例 + CommentServiceTest 5 例）

#### 任务描述

覆盖重复点赞/评论归属/计数更新等关键分支。

#### 验证标准

- [x] 核心分支有断言
- [x] `mvn test` 通过

---

### TASK-047: pytest 集成用例扩展

- **优先级**: P1
- **预估工时**: 1 天
- **前置依赖**: TASK-037、TASK-038
- **状态**: `[x]`（2026-08-10 完成：test_admin.py 11 例，其中正向恢复按条件跳过）

#### 任务描述

补充：管理员权限（401/403/200）、媒体运维扫描/恢复。（备份脚本校验按用户决定不做自动化）

#### 验证标准

- [x] 新增用例全部通过（45 passed + 1 条件跳过）
- [x] 原有 35 例不回归

---

## 阶段九：前端优化（P3）

---

### TASK-048: 创建前端目录结构

- **优先级**: P3
- **预估工时**: 15 分钟
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

创建 `static/css/common`、`static/js/common`、`static/images` 目录。

#### 验证标准

- [ ] 目录已创建

---

### TASK-049: 抽取公共 JavaScript

- **优先级**: P3
- **预估工时**: 2 小时
- **前置依赖**: TASK-048
- **状态**: `[ ]`

#### 任务描述

抽取 `api.js`（请求封装 + 401 跳转）、`auth.js`（token 管理）、`utils.js`（工具函数）。

#### 验证标准

- [ ] 9 个页面不再各自重复请求逻辑
- [ ] 页面功能无回归

---

### TASK-050: 抽取公共 CSS

- **优先级**: P3
- **预估工时**: 2 小时
- **前置依赖**: TASK-048
- **状态**: `[ ]`

#### 任务描述

抽取 `reset.css`、`common.css`（布局、按钮、卡片）。

#### 验证标准

- [ ] 公共样式集中管理
- [ ] 页面样式无回归

---

### TASK-051: 页面迁移与引用更新

- **优先级**: P3
- **预估工时**: 2 小时
- **前置依赖**: TASK-049、TASK-050
- **状态**: `[ ]`

#### 任务描述

9 个页面（含 recovery.html）移入 `pages/`，更新静态资源引用与跳转路径。

#### 验证标准

- [ ] 所有页面可正常访问
- [ ] 资源引用无 404

---

## 阶段十：部署运维（P2/P3）

---

### TASK-052: tools/deploy.py 部署脚本

- **优先级**: P2
- **预估工时**: 半天
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

Python 部署脚本：构建 war → 备份旧 war → 部署到 Tomcat → 启动 → `/health` 探活。复用 run_tests.py 的隔离与进程清理约定，不误杀用户进程。

#### 验证标准

- [ ] 一键部署成功且探活通过
- [ ] 停止逻辑安全（不清理无关进程）

---

### TASK-053: /health 健康检查

- **优先级**: P2
- **预估工时**: 1 小时
- **前置依赖**: TASK-017、TASK-018、TASK-020
- **状态**: `[ ]`

#### 任务描述

新增 `/health`：DB 连接、Redis ping、上传目录可写；任一异常返回 503 与状态 JSON。

#### 验证标准

- [ ] 正常时返回 200 且各组件 UP
- [ ] 停 Redis 后返回 503

---

### TASK-054: 基础监控指标（P3 可选）

- **优先级**: P3
- **预估工时**: 1 天
- **前置依赖**: TASK-053
- **状态**: `[ ]`

#### 任务描述

Filter 统计响应时间与错误率，日志输出；连接池使用率统计。

#### 验证标准

- [ ] 指标可查询
- [ ] 不影响正常请求

---

## 任务依赖图

```
阶段零（灾后收尾与备份）
├── TASK-001 手动清理未引用方法（用户执行）──────────────┐
├── TASK-002 列名 bug（已取消）─────────────────────────┤
├── TASK-003 UploadController return ────────────────────┤
├── TASK-004 deleteContent（已取消）────────────────────┤
├── TASK-005 欢迎页 ─────────────────────────────────────┤
├── TASK-006 LogUtil 目录 ───────────────────────────────┤
├── TASK-007 数据清理 ◄── TASK-008（先备份） ─────────────┤
├── TASK-008 备份自动化 ─────────────────────────────────┤
└── TASK-009 .http 修正 ─────────────────────────────────┤
                                                        ▼
阶段一（基础清理）
├── TASK-010 删除残留测试/空壳 ◄── TASK-001
├── TASK-011 CouponAdmin 迁移
├── TASK-012 删除 ssm_*/util
├── TASK-013 DTO → dto
├── TASK-014 合并 model ◄── TASK-013
└── TASK-015 LogInVO → LoginVO ◄── TASK-014

阶段二（配置外部化）
└── TASK-016 app.properties + AppConfig
    ├── TASK-017 MyConnectionPool
    ├── TASK-018 MyRedisPool
    ├── TASK-019 JwtUtil
    ├── TASK-020 FileUploadService/context.xml
    ├── TASK-021 LogUtil
    └── TASK-022 ContentService 缓存配置

阶段三（异常与日志）
├── TASK-023 ErrorCode 枚举
├── TASK-024 具体异常 ◄── TASK-023
├── TASK-025 替换 RuntimeException ◄── TASK-024
├── TASK-026 统一异常信息 ◄── TASK-025
├── TASK-027 全局异常处理 ◄── TASK-025
└── TASK-028 日志清理与脱敏 ◄── TASK-006/027

阶段四（事务与 DAO）
├── TASK-029 TransactionTemplate ◄── TASK-017
├── TASK-030 Service 使用模板 ◄── TASK-029
└── TASK-031 DAO 统一 Connection ◄── TASK-030

阶段五（Service 拆分）
├── TASK-032 ContentCacheManager ◄── TASK-031
├── TASK-033 ContentStatusFiller ◄── TASK-032
├── TASK-034 重构 ContentService ◄── TASK-032/033
└── TASK-035 UserService 拆分 ◄── TASK-030

阶段六（安全）
├── TASK-036 SQL 注入复查
├── TASK-037 管理员角色 ◄── TASK-008
├── TASK-038 所有权校验 ◄── TASK-037
└── TASK-039 XSS/CSRF

阶段七（IoC/连接池）
├── TASK-040 构造器注入
├── TASK-041 生命周期接口 ◄── TASK-040
└── TASK-042 连接池上限 ◄── TASK-017

阶段八（测试）
├── TASK-043 添加 JUnit/Mockito
├── TASK-044 UserService 单测 ◄── TASK-040/043
├── TASK-045 ContentService 单测 ◄── TASK-034/043
├── TASK-046 Like/Comment 单测 ◄── TASK-043
└── TASK-047 pytest 扩展 ◄── TASK-037/038

阶段九（前端）
├── TASK-048 目录结构
├── TASK-049 公共 JS ◄── TASK-048
├── TASK-050 公共 CSS ◄── TASK-048
└── TASK-051 页面迁移 ◄── TASK-049/050

阶段十（部署运维）
├── TASK-052 deploy.py
├── TASK-053 /health ◄── TASK-017/018/020
└── TASK-054 监控 ◄── TASK-053
```

---

## 推荐执行顺序

1. **第一批（灾后收尾）**：TASK-008 → TASK-001 → TASK-003 → TASK-005 → TASK-006 → TASK-007 → TASK-009（TASK-002/004 已取消）
2. **第二批（基础清理）**：TASK-010 → TASK-011 → TASK-012 → TASK-013 → TASK-014 → TASK-015
3. **第三批（配置外部化）**：TASK-016 → TASK-017 → TASK-018 → TASK-019 → TASK-020 → TASK-021 → TASK-022
4. **第四批（异常与日志）**：TASK-023 → TASK-024 → TASK-025 → TASK-026 → TASK-027 → TASK-028
5. **第五批（事务与 DAO）**：TASK-029 → TASK-030 → TASK-031
6. **第六批（Service 拆分）**：TASK-032 → TASK-033 → TASK-034 → TASK-035
7. **第七批（安全）**：TASK-036 → TASK-037 → TASK-038 → TASK-039
8. **第八批（IoC/连接池）**：TASK-040 → TASK-041 → TASK-042
9. **第九批（测试）**：TASK-043 → TASK-044 → TASK-045 → TASK-046 → TASK-047
10. **第十批（前端）**：TASK-048 → TASK-049 → TASK-050 → TASK-051
11. **第十一批（部署运维）**：TASK-052 → TASK-053 → TASK-054

---

## 文档历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-07-23 | 初始版本，30 个任务 |
| 2.0 | 2026-08-09 | 全面修订：基于 8/8 灾后状态重排为 54 个任务；新增阶段零（手动清理未引用方法、bug 修复、数据清理、备份自动化）；补齐事务/DAO、测试、安全、前端、部署等缺失阶段；任务状态与已完成事项同步 |
| 2.1 | 2026-08-09 | 同步清理结果：TASK-001 完成，TASK-002/004 随"删除功能不做"取消，安全/测试任务与依赖图更新；离线仓库路径更新 |
| 2.2 | 2026-08-09 | TASK-003/005/006/008/009 完成；备份方案改为仅数据库备份到 auto_backup（媒体由用户手动打包） |
| 2.3 | 2026-08-09 | TASK-007 完成：清理 pytest/smoke 测试内容与孤儿数据；中文标题测试内容保留、遗留表暂不 DROP（用户决策） |
| 2.4 | 2026-08-10 | TASK-010~015 完成（阶段一）：删除残留测试/空壳类与 ssm_*/util；CouponAdmin 迁至测试目录；DTO → dto；pojo/DTO/command 合并为 com.itheima.model；LogInVO → LoginVO |
| 2.5 | 2026-08-10 | TASK-016~022 完成（阶段二）：app.properties + AppConfig（环境变量覆盖）；连接池/Redis/JWT/上传/日志/缓存全部读配置；context.xml 启动校验；35/35 pytest 通过 |
| 2.6 | 2026-08-10 | TASK-023~028 完成（阶段三）：ErrorCode 枚举化 + 12 个具体异常；业务 RuntimeException/英文消息全部替换；ExceptionFilter 全局异常处理；日志清理与手机号脱敏；35/35 pytest 通过 |
| 2.7 | 2026-08-10 | TASK-029~031 完成（阶段四）：TransactionTemplate 统一事务；9 个 Service 手工事务样板全部替换；DAO 只接收 Connection（删除自取连接包装）；35/35 pytest 通过 |
| 2.8 | 2026-08-10 | TASK-032~034 完成（阶段五）：ContentService 缓存职责迁入 ContentCacheManager、状态填充迁入 ContentStatusFiller；CommentService/LikeService/FeedService/ProfileService/StartController 调用点更新；TASK-035（UserService 拆分）按用户决定暂缓；35/35 pytest 通过 |
| 2.9 | 2026-08-10 | TASK-036~038 完成（阶段六）：SQL 注入与资源所有权审计记录（全参数化、无注入点）；users 表新增 role 列 + tools/admin.py（--list/--promote/--demote）；AuthFilter 对 /api/admin/* 校验管理员角色；recovery.html 区分 403 并隐藏非管理员操作；TASK-039（XSS/CSRF）按用户决定暂缓；35/35 pytest 通过 |
| 3.0 | 2026-08-10 | TASK-040~042 完成（阶段七）：@InjectConstructor 构造器注入（11 个服务类迁移，字段注入兼容）；Initializable/Disposable 生命周期接口接入容器（ContentCacheManager 迁移，AppShutDownListener 统一关闭）；MyConnectionPool 上限 20 + 获取超时 5000ms（等待/超时抛 SQLException/失效连接移除）；35/35 pytest 通过 |
| 3.1 | 2026-08-10 | TASK-043~047 完成（阶段八）：JUnit 6.0.0 + Mockito 5.20.0 + Surefire 3.2.5 测试体系（61 例 JUnit，含连接池与缓存生命周期补测）；pytest 新增 test_admin.py（45 passed + 1 条件跳过）；构建输出经 -Dstage8.buildDir 指向 D 盘（沙箱 javac 无法读 worktree target/）；离线仓库补 aliyun 来源记录 |
