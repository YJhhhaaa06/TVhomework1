# 架构优化规划文档

> 版本：2.0
> 生成日期：2026-08-09
> 状态：规划中（本文档以 2026-08-09 实际代码为准，代码与文档不一致时以代码为准）

---

## 目录

- [一、现状分析](#一现状分析)
- [二、优化目标](#二优化目标)
- [三、分层架构优化](#三分层架构优化)
- [四、依赖注入增强](#四依赖注入增强)
- [五、异常处理体系](#五异常处理体系)
- [六、配置管理外部化](#六配置管理外部化)
- [七、日志体系规范](#七日志体系规范)
- [八、安全性加固](#八安全性加固)
- [九、性能优化](#九性能优化)
- [十、可测试性改进](#十可测试性改进)
- [十一、前端架构优化](#十一前端架构优化)
- [十二、部署运维改进](#十二部署运维改进)
- [十三、实施路线图](#十三实施路线图)
- [附录](#附录)

---

## 一、现状分析

> 本版数据为 2026-08-09 对源码的实测统计。8/8 数据删除事故后，项目已完成了部分灾后重建（媒体运维、动态 context path、视频写回），但这些内容此前未进入架构规划文档，v2.0 一并纳入。

### 1.1 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                        前端 (9 个 HTML)                     │
├─────────────────────────────────────────────────────────────┤
│                    Filter 链 (3 个)                         │
│              Encoding → Login → Auth                       │
├─────────────────────────────────────────────────────────────┤
│                 Controller 层 (18 个文件)                   │
├─────────────────────────────────────────────────────────────┤
│                  Service 层 (13 个文件)                     │
│              （事务连接目前在 Service 内手工管理）           │
├─────────────────────────────────────────────────────────────┤
│                    DAO 层 (10 个文件)                       │
│              （部分 DAO 自取连接，部分接收外部连接）         │
├─────────────────────────────────────────────────────────────┤
│            手写连接池 ──────── Redis (Jedis) 缓存           │
├─────────────────────────────────────────────────────────────┤
│              MySQL ──────── Redis Server                    │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 代码规模（2026-08-09 实测）

| 层级/包 | 文件数 | 代码行数 | 说明 |
|---------|-------:|---------:|------|
| controller | 18 | 1,205 | 含 1 个遗留测试类 Test3 与遗留 UploadServlet |
| service | 13 | 2,236 | 含 2 个空壳（MenuService、ImageService） |
| dao | 10 | 1,160 | 含 1 个空壳（CommentMediaDao） |
| pojo | 13 | 614 | 含灾后新增 MediaAuditItem/MediaAuditResult/RestoreResult |
| DTO | 7 | 206 | 包名大写，不符合 Java 规范 |
| command | 8 | 327 | 与 DTO/pojo 职责重叠，待合并 |
| exception | 8 | 58 | ErrorCode 仍为接口常量 |
| filter | 3 | 102 | 认证/权限过滤 |
| ioc | 5 | 226 | 仅支持字段注入 |
| util | 11 | 370 | 含 RequestContext（灾后新增） |
| 根包 CouponAdmin | 1 | 55 | 运维工具类混在主代码 |
| **合计** | **97** | **6,559** | |

关键大类的实测行数：

| 类 | 行数 | 说明 |
|----|-----:|------|
| ContentService | 709 | 缓存 + 查询 + 发布 + 状态填充混合 |
| UserDao | 250 | 含自取连接的包装方法（部分已随清理删除） |
| ContentDao | 290 | 含全文搜索、媒体状态更新 |
| LikeService | 371 | 点赞业务 |
| LikeCacheService | 264 | Redis 点赞缓存 |
| CommentService | 188 | 评论业务（已清理未接线方法） |
| MediaAuditService | 259 | 灾后新增媒体审计 |
| UserService | 242 | 认证 + 资料 |

### 1.3 已识别问题（v2.0）

| 维度 | 问题 | 严重程度 |
|------|------|----------|
| **灾备** | 无自动备份机制；8/8 事故暴露数据库行丢失与上传文件全丢风险 | **极高** |
| **分层** | ContentService 709 行，职责过厚；Service 与 DAO 连接管理方式混用 | 高 |
| **安全** | `/api/admin/media/*` 对所有登录用户开放（无管理员角色）；UploadController `action==null` 分支缺 `return` 会 NPE；内容删除功能已确认不做，相关代码已清理 | 高 |
| **异常** | 业务代码混用 `RuntimeException("USER_NOT_FOUND")` 等英文常量；Controller 各自 catch 并 `printStackTrace()` | 高 |
| **配置** | 数据库/Redis/JWT/上传路径/日志路径全部硬编码 | 高 |
| **日志** | `e.printStackTrace()` 约 20 处；`logs/` 目录缺失导致文件日志实际不可用 | 中 |
| **性能** | 手写连接池无最大连接数、无获取超时；JVM 缓存无淘汰策略 | 中 |
| **测试** | 已有 35 个 pytest 集成用例，但无 JUnit 单元测试 | 中 |
| **遗留** | Test3、MenuService、ImageService、UploadServlet、CommentMediaDao、CheckUtil、ssm_* 子模块等残留 | 低 |

### 1.4 SQL 注入现状（复查结论）

已对全部 DAO 代码复查：所有 SQL 均使用 `PreparedStatement` 参数化，动态 IN 查询也是先生成 `?` 占位符再 `setXxx` 绑定，未发现字符串拼接注入点。因此 v2.0 将"SQL 注入审计"从 P0 高风险降为"复查 + 记录审计结果"任务。

### 1.5 已完成事项（截至 2026-08-09）

- TestServlet.java 已删除，web.xml 引用同步移除（commit 3a8a72d）。
- RedisTest.java、dao/daotest.java、exception/Test.java 已删除；`web/` 空壳目录已不存在。
- 灾后重建已完成：`RequestContext` 动态 context path（替换 `/MyAPP` 硬编码）、DB 新增 `file_exists`/`last_verify_time` 列、MediaAuditService/MediaAdminController/recovery.html、34 个视频文件写回。
- 一键测试脚本 `tools/run_tests.py`（Maven 打包 → 独立 Tomcat 18080 → pytest → 关停）已可用，8/8 验证 35/35 通过。
- 2026-08-09：手动清理未引用/未接线方法（记录见 `.docs/REMOVE_CODE.md`），Service 12 个、DAO 31 个方法移除；`deleteContent`/隐藏/删除类功能确认不做。
- 2026-08-09：TASK-003/005/006/008/009 完成——UploadController 补 return、欢迎页 index.html、LogUtil 自动建日志目录、`tools/backup.py`（仅数据库备份到 auto_backup）、.http 测试修正。
- 2026-08-09：TASK-007 完成——清理 pytest/smoke 测试内容（46+2 条）及孤儿 content_media/comment_like；中文标题测试内容保留，遗留表 video/videoinfo 暂不 DROP。

---

## 二、优化目标

### 2.1 核心原则

1. **功能不变**：所有优化不改变业务逻辑和 API 行为。
2. **渐进式**：按优先级分阶段实施，每阶段可独立验证（`mvn compile` + `python tools\run_tests.py all`）。
3. **可回滚**：数据库变更前先整库备份；文件删除前先记录清单。

### 2.2 量化目标

| 指标 | 当前（2026-08-09） | 目标 |
|------|--------------------|------|
| 最大类行数 | ContentService 709 行 | 所有类 < 300 行（当前最大 ContentService 709、LikeService 371） |
| 代码重复率 | ~15%（估计） | < 5% |
| 圈复杂度 | 部分方法 > 15（估计） | 所有方法 < 10 |
| 单元测试 | 0 个 JUnit 用例 | 核心 Service（用户/内容/点赞/评论）关键路径有单测 |
| 集成测试 | 35 个 pytest 用例 | 保持全绿，并随新接口扩展 |
| **事故可恢复性** | 无自动备份 | 一键备份 DB + 上传目录，恢复演练可通过 |

---

## 三、分层架构优化

### 3.1 当前问题

```
问题1：ContentService（709行）= 缓存管理 + 业务查询 + 内容发布 + 状态填充 + 缓存同步
问题2：事务连接在多个 Service 内手工 setAutoCommit(false)/commit/rollback，重复且易漏
问题3：DAO 连接管理方式不统一
       - 部分 DAO（UserDao/ContentDao 等）自己 getConnection/release
       - 部分 Service（UserService/CommentService/FollowService）管理事务连接后调用 DAO
问题4：静态缓存方法与其它 Service 强耦合
       - CommentService 2 处、LikeService 4 处直接调用 ContentService 的静态缓存方法
```

### 3.2 目标架构

**v2.0 决策：不引入 Application 层。** 个人学习项目保留 Controller → Service → DAO 两层即可；原 v1.0 的 Application 层方案取消，事务编排由 Service 通过统一的 TransactionTemplate 完成。

```
┌─────────────────────────────────────────────────────────────┐
│                      Controller 层                          │
│         职责：请求解析、参数校验、响应构建                      │
├─────────────────────────────────────────────────────────────┤
│                      Service 层                             │
│         职责：纯业务逻辑 + 事务编排（TransactionTemplate）    │
├─────────────────────────────────────────────────────────────┤
│                        DAO 层                               │
│         职责：纯数据访问，只接收 Connection，不管理连接        │
├─────────────────────────────────────────────────────────────┤
│                    Infrastructure                            │
│         连接池、Redis、日志、配置、备份脚本                    │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 ContentService 拆分（709 行 → 4 个类）

```
ContentService (709行)
│
├──→ ContentCacheManager（@Component 实例 Bean）
│    - 迁移 recommendList/contentCache/commentCache/contentTimestamps/typeCategoryIndex
│    - 缓存 TTL、索引、定时刷新、缓存回填、删除/失效
│    - 原静态方法改为实例方法；同步迁移 CommentService/LikeService 的 6 处调用点
│    - 所有共享 Map 保持现有 synchronized 语义并补线程安全注释
│
├──→ ContentQueryService
│    - getRecommendByFilter() / search() / getContentDetailVO() / getCommentsForContent()
│
├──→ ContentPublishService
│    - addVideo() / addPost()（删除功能已确认不做，相关方法已移除）
│
└──→ ContentStatusFiller
     - fillLikeAndFollowBatch() / fillFollowStatus() / fillContentLikeStatus()
```

### 3.4 UserService 拆分（P3，可选）

```
UserService (242行)
│
├──→ UserService（认证）
│    - login() / register() / changePassword()
└──→ UserProfileService
     - getProfile() 等资料查询
```

### 3.5 DAO 层统一规范（v2.0 修正）

**决策：DAO 方法只接收 `Connection`，不自行获取/释放连接；连接与事务统一由 Service 通过 TransactionTemplate 管理。**

```java
// ✓ 目标写法：DAO 只接收连接
public User getUserForLoginById(Connection conn, long id) throws SQLException {
    // 纯数据访问逻辑
}
```

**v2.0 明确废弃 v1.0 的 BaseDao 自取连接方案**（原方案会让 DAO 重新管理连接，与事务需求冲突）。TransactionTemplate 设计见 CURRENT_TASK TASK-028/029/030：

```java
// Service 层事务统一入口（示例）
public void someTxMethod(...) {
    transactionTemplate.execute(conn -> {
        userDao.update(conn, ...);
        contentDao.update(conn, ...);
        return null;
    });
}
```

---

## 四、依赖注入增强

### 4.1 当前 IoC 容器限制

| 限制 | 影响 |
|------|------|
| 只支持无参构造 + 字段注入 | 无法声明构造器依赖，测试时只能反射注入 |
| 无生命周期接口 | 仅通过反射约定调用名为 `shutdown()` 的方法 |
| 不支持接口注入 | 无法针对接口编程 |
| 无作用域管理 | 所有 Bean 单例（当前可接受） |

### 4.2 增强方案

#### 4.2.1 构造器注入（P1）

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.CONSTRUCTOR)
public @interface InjectConstructor {}

@Component
public class UserService {
    private final UserDao userDao;
    private final PasswordUtil passwordUtil;

    @InjectConstructor
    public UserService(UserDao userDao, PasswordUtil passwordUtil) {
        this.userDao = userDao;
        this.passwordUtil = passwordUtil;
    }
}
```

IocContainer 增加：创建 Bean 时优先找 `@InjectConstructor` 构造器，按参数类型从已构建 Bean 中解析；未标注时回退到无参构造 + 字段注入。

#### 4.2.2 生命周期接口（P2）

```java
public interface Initializable { void initialize(); }
public interface Disposable    { void destroy(); }
```

容器 `init()` 末尾调用 `initialize()`；`shutdown()` 时调用 `destroy()`，**同时保留现有 `shutdown()` 反射兼容**，避免漏掉当前依赖该约定的类。

#### 4.2.3 接口注入（P3，可选）

`@Binding` 绑定接口与实现，按需实施，不做 P0/P1 承诺。

---

## 五、异常处理体系

### 5.1 当前问题

```java
throw new RuntimeException("USER_NOT_FOUND");   // ✗ 英文常量
throw new RuntimeException("PHONE_IN_USE");     // ✗ 直接 RuntimeException
// Controller catch 后 e.printStackTrace() + writeError  ✗ 每个 Controller 重复
```

### 5.2 统一异常体系

```
BusinessException (基类：code + message)
├── AuthException (401)        UserNotFoundException / PasswordIncorrectException / TokenExpiredException
├── ForbiddenException (403)   AccessDeniedException
├── NotFoundException (404)    ContentNotFoundException / CommentNotFoundException
├── ConflictException (409)    DuplicateLikeException / DuplicatePhoneException
├── ParamException (400)       InvalidPhoneException / InvalidPasswordException
└── ServerException (500)      DatabaseException / CacheException
```

### 5.3 ErrorCode 枚举化（v2.0 必须包含全部现有码）

```java
public enum ErrorCode {
    // 参数 (400)
    PARAM_ERROR(400, "参数错误"),
    INVALID_PHONE(400, "手机号格式错误"),
    INVALID_PASSWORD(400, "密码格式错误"),

    // 认证 (401) —— 必须保留，AuthFilter 正在使用
    UNAUTHORIZED(401, "请先登录"),
    USER_NOT_FOUND(401, "用户不存在"),
    WRONG_PASSWORD(401, "密码错误"),
    TOKEN_EXPIRED(401, "令牌已过期"),

    // 权限 (403)
    FORBIDDEN(403, "无权访问"),
    ACCESS_DENIED(403, "无权访问"),

    // 资源 (404)
    NOT_FOUND(404, "资源不存在"),
    CONTENT_NOT_FOUND(404, "内容不存在"),
    COMMENT_NOT_FOUND(404, "评论不存在"),

    // 冲突 (409)
    CONFLICT(409, "操作冲突"),
    DUPLICATE_LIKE(409, "不可重复点赞"),
    DUPLICATE_PHONE(409, "手机号已被使用"),

    // 服务器 (500)
    SERVER_ERROR(500, "服务器内部错误"),
    DATABASE_ERROR(500, "数据库操作失败"),
    CACHE_ERROR(500, "缓存操作失败");

    private final int code;
    private final String message;
    // getCode() / getMessage()
}
```

### 5.4 全局异常处理 Filter

新增 `ExceptionFilter`（或扩展 BaseServlet 统一出口），在 Filter 链末端捕获业务异常：

```java
if (e instanceof BusinessException be) {
    writeError(resp, be.getCode(), be.getMessage());
} else {
    writeError(resp, 500, "服务器内部错误");
}
```

目标：各 Controller 不再重复 `catch + printStackTrace`。

### 5.5 改造计划

| 步骤 | 内容 | 工作量 |
|------|------|--------|
| 1 | ErrorCode 接口 → 枚举（保留全部现有码） | 小 |
| 2 | 创建 11 个具体异常类 | 中 |
| 3 | 替换所有业务 `RuntimeException` | 大 |
| 4 | 统一异常信息为中文 | 中 |
| 5 | 新增全局异常处理 Filter 并清理 Controller catch | 中 |

---

## 六、配置管理外部化

### 6.1 当前硬编码清单（2026-08-09 实测）

| 配置项 | 位置 | 硬编码值 |
|--------|------|----------|
| 数据库 URL | MyConnectionPool | `jdbc:mysql://localhost:3306/TVDatabase?...` |
| 数据库用户名/密码 | MyConnectionPool | `root` / `MySQL` |
| 连接池初始大小 | MyConnectionPool | `5` |
| Redis 地址 | MyRedisPool | `localhost:6379` |
| Redis 池参数 | MyRedisPool | maxTotal=50 / maxIdle=10 / minIdle=5 |
| JWT 密钥 | JwtUtil | `"STONE"` |
| JWT 过期时间 | JwtUtil | 2 小时 |
| **上传根路径** | **FileUploadService** | **`D:/data/projects/VideoPlatform/stone`** |
| **静态资源映射** | **context.xml** | **`D:/data/projects/VideoPlatform/stone`** |
| **日志文件路径** | **LogUtil** | **`logs/system.log`（logs 目录缺失，文件日志不可用）** |
| 缓存 TTL/刷新 | ContentService | 10 分钟 |
| 数据库 URL | CouponAdmin | 同 MyConnectionPool |

### 6.2 配置文件设计（app.properties）

```properties
# ===== 数据库配置 =====
db.driver=com.mysql.cj.jdbc.Driver
db.url=jdbc:mysql://localhost:3306/TVDatabase?useSSL=false&serverTimezone=Asia/Shanghai
db.username=root
db.password=MySQL
db.pool.initSize=5
db.pool.maxSize=20
db.pool.timeoutMs=5000

# ===== Redis 配置 =====
redis.host=localhost
redis.port=6379
redis.maxTotal=50
redis.maxIdle=10
redis.minIdle=5

# ===== JWT 配置 =====
jwt.secret=STONE
jwt.expireHours=2

# ===== 文件上传配置 =====
upload.path=D:/data/projects/VideoPlatform/stone
upload.maxSize=104857600

# ===== 缓存配置 =====
cache.content.refreshMinutes=10
cache.content.ttlMinutes=10

# ===== 日志配置 =====
log.level=INFO
log.file=logs/system.log
```

### 6.3 环境变量覆盖

`AppConfig` 加载 properties 后，用 `DB_PASSWORD`、`JWT_SECRET`、`UPLOAD_PATH`、`LOG_PATH` 等环境变量覆盖（`db.password` → `DB_PASSWORD`，规则为 `key` 去点转大写）。

### 6.4 改造计划

| 步骤 | 内容 | 风险 |
|------|------|------|
| 1 | 创建 app.properties + AppConfig | 低 |
| 2 | 改造 MyConnectionPool | 中 |
| 3 | 改造 MyRedisPool | 中 |
| 4 | 改造 JwtUtil | 中 |
| 5 | 改造 FileUploadService + context.xml（upload.path） | 中 |
| 6 | 改造 LogUtil（log.file + 自动建目录） | 低 |
| 7 | 改造 ContentService 缓存配置 | 低 |

---

## 七、日志体系规范

### 7.1 当前问题

```java
e.printStackTrace();                          // ✗ 约 20 处
System.out.println("Business"+e.getMessage()); // ✗ 控制台杂音
// LogUtil FileHandler("logs/system.log")      // ✗ logs 目录不存在，文件日志失败
// LOGGER.info("用户登录: " + password)         // ✗ 若出现即泄密，需脱敏
```

### 7.2 级别使用标准

| 级别 | 使用场景 |
|------|----------|
| SEVERE | 操作最终失败并抛出异常（DB 写入失败） |
| WARNING | 问题已恢复/降级（缓存失败走 DB 兜底、事务回滚） |
| INFO | 关键业务节点（登录、发布、缓存初始化） |
| FINE | 调试信息（SQL、请求参数） |

### 7.3 LogUtil 增强

- 启动时自动 `mkdirs()` 创建日志目录（默认 `logs/`，路径可配置）。
- 提供脱敏工具：手机号 `138****1234`、密码 `******`、token 前 8 位。
- 统一格式：`[时间] [级别] [类名] [方法] - 消息 | key=value`。

### 7.4 改造计划

| 步骤 | 内容 | 工作量 |
|------|------|--------|
| 1 | 修复日志目录并接入配置 | 小 |
| 2 | 删除全部 e.printStackTrace() / System.out（业务路径） | 小 |
| 3 | 统一级别与上下文 | 中 |
| 4 | 敏感信息脱敏 | 中 |

---

## 八、安全性加固

### 8.1 风险清单（v2.0 更新）

| 风险 | 位置 | 严重程度 |
|------|------|----------|
| 运维接口无管理员权限 | `/api/admin/media/*` 仅要求登录，任何登录用户可扫描/恢复任意媒体 | 高 |
| UploadController NPE | `action == null` 分支写错误响应后未 `return`，继续执行 switch | 高 |
| 密钥/路径硬编码 | JWT 密钥、DB 密码、上传路径 | 高 |
| SQL 注入 | 已复查：全部参数化，风险已排除，保留复查任务 | 低 |

### 8.2 SQL 注入复查（保留为审计任务）

全量检查 DAO：确认 `PreparedStatement` 参数化、无字符串拼接 SQL、动态 IN 全占位符；输出审计记录到文档。

### 8.3 管理员角色（v2.0 新增）

- `users` 表新增 `role TINYINT NOT NULL DEFAULT 0`（0=普通用户，1=管理员）。
- `AuthFilter` 对 `/api/admin/*` 额外校验 `role == 1`，非管理员返回 403。
- `recovery.html` 对非管理员隐藏入口与操作按钮。
- 提供 Python 脚本（或复用 CouponAdmin 模式）创建/提升管理员，DB 变更前先备份。

### 8.4 资源所有权校验（按实际接口）

| 接口/方法 | 校验逻辑 |
|-----------|----------|
| /user/changePassword | 已使用 token 中的 userId，无需二次校验 |
| /api/admin/media/restore | 仅管理员 |

> v1.0 中"DELETE /content/{id}、DELETE /comment/{id}"权限表基于不存在的接口，v2.0 已删除；2026-08-09 起内容删除功能确认不做，相关 Service/DAO 方法已清理；评论暂无删除接口。

### 8.5 XSS / CSRF（P2，可选）

- XSS：参数校验 + 输出编码（Jackson 默认转义 HTML），不做全局替换过滤器。
- CSRF：本地学习项目暂不引入 Token 体系，若引入置于 P2。

---

## 九、性能优化

### 9.1 连接池优化

`MyConnectionPool` 增加最大连接数（默认 20）、获取超时（默认 5s）与 `wait/notifyAll` 等待机制；超时抛 SQLException。

### 9.2 缓存淘汰策略

JVM 缓存（contentCache/commentCache/recommendList）增加 LRU 上限（如 1000 条），所有共享结构保持线程安全（现有 synchronized 语义不丢失）。

### 9.3 批量查询（N+1 复查）

部分查询已用 `IN (?,...)` 批量（如 findUsersByIds、findContentIdsByUsers）；对状态填充的 N+1 场景复查并批量化为 IN 查询。

### 9.4 改造计划

| 步骤 | 内容 | 优先级 |
|------|------|--------|
| 1 | 连接池最大限制 + 超时 | P1 |
| 2 | 缓存 LRU | P2 |
| 3 | 状态填充批量查询 | P2 |

---

## 十、可测试性改进

### 10.1 原则

1. 构造器注入优先，便于测试替换依赖。
2. 资源抽象：DB/Redis 通过接口访问（测试用 Mock）。
3. **v2.0 决策：不使用 H2 与 MockMvc**（H2 不兼容 MySQL FULLTEXT；MockMvc 属 Spring 体系，学校禁止）。

### 10.2 测试分层

| 层级 | 类型 | 工具 | 覆盖目标 |
|------|------|------|----------|
| Service 单元测试 | 纯业务逻辑 | JUnit Jupiter + Mockito | UserService、ContentService（拆分后）、LikeService、CommentService |
| DAO/接口集成测试 | 真实 MySQL/Redis + Tomcat | pytest + tools/run_tests.py | 现有 35 例 + 新增用例 |
| 前端 | 手工 | 浏览器 | 不在自动化范围 |

pom.xml 需新增测试依赖：JUnit Jupiter 5.x + Mockito 5.x，**具体版本以离线 m2-repo 可用且兼容 JDK 25 为准**。

### 10.3 改造计划

| 步骤 | 内容 | 前置条件 |
|------|------|----------|
| 1 | 添加 JUnit/Mockito 依赖 | 离线仓库有包 |
| 2 | IoC 构造器注入 | TASK-039 |
| 3 | 核心 Service 单元测试 | Service 拆分后 |
| 4 | pytest 集成用例扩展 | 新接口上线 |

---

## 十一、前端架构优化

### 11.1 当前问题

- 9 个 HTML 平铺在 `src/main/webapp/`（含灾后新增 recovery.html）。
- JS/CSS 内联在 HTML，无法复用。
- 无构建工具（保持纯静态，不引入 Node 构建链）。

### 11.2 目标目录结构

```
webapp/
├── WEB-INF/
├── META-INF/
├── static/
│   ├── css/common/           # common.css、reset.css
│   ├── js/common/            # api.js、auth.js、utils.js、constants.js
│   └── images/
└── pages/                    # login.html、start.html、search.html、detail.html、
                              # publish.html、space.html、profile.html、coupon.html、recovery.html
```

### 11.3 改造计划

| 步骤 | 内容 | 工作量 |
|------|------|--------|
| 1 | 创建 static 目录结构 | 小 |
| 2 | 抽取公共 JS（API 封装、token 管理、工具函数） | 中 |
| 3 | 抽取公共 CSS | 中 |
| 4 | 页面迁移与引用更新（9 个页面） | 中 |

---

## 十二、部署运维改进

### 12.1 现状

- 手工 `mvn package` → 复制 war → 启动 Tomcat（IDEA 运行配置 CONTEXT_PATH="/"）。
- `tools/run_tests.py` 已实现沙盒内"打包 → 独立 Tomcat 18080 → pytest → 关停"。
- **无生产部署脚本、无健康检查、无自动备份**。

### 12.2 目标

| 项 | 方案 | 优先级 |
|----|------|--------|
| 部署脚本 | Python 脚本 `tools/deploy.py`：构建 → 备份旧 war → 部署 → 启动 → 探活（复用 run_tests.py 的隔离与停止约定；AGENTS.md 要求脚本必须 Python） | P2 |
| 健康检查 | 新增 `/health`：检查 DB 连接、Redis ping、上传目录可写 | P2 |
| 自动备份 | `tools/backup.py`：仅 mysqldump 整库备份到 `D:/dev/WorkSpace/VideoPlatform/auto_backup/<时间戳>/`，含 manifest 与校验；媒体资源由用户手动打包备份（2026-08-09 决策） | P0 |
| 监控指标 | 响应时间、错误率、连接池使用率（P3 可选） | P3 |

---

## 十三、实施路线图

> 详细任务、状态与验收标准见 `CURRENT_TASK.md` v2.0。任务编号全部重排为 TASK-001 起。

| 阶段 | 目标 | 优先级 |
|------|------|--------|
| 阶段零：灾后收尾与备份 | 手动清理未引用方法、修已知 bug、数据清理、备份自动化 | P0 |
| 阶段一：基础清理与包结构 | 删除残留测试/空壳、合并 model 包 | P1 |
| 阶段二：配置外部化 | app.properties + AppConfig + 全部硬编码改造 | P1 |
| 阶段三：异常与日志 | ErrorCode 枚举、具体异常、全局处理、日志规范 | P1 |
| 阶段四：事务与 DAO 统一 | TransactionTemplate、DAO 只接收 Connection | P2 |
| 阶段五：Service 拆分 | ContentCacheManager/StatusFiller/ContentService/UserService | P2 |
| 阶段六：安全加固 | SQL 复查、管理员角色、所有权校验 | P1 |
| 阶段七：IoC 增强与连接池 | 构造器注入、生命周期、连接池上限 | P2 |
| 阶段八：测试建设 | JUnit+Mockito 单测、pytest 扩展 | P1 |
| 阶段九：前端优化 | static 目录、公共 JS/CSS | P3 |
| 阶段十：部署运维 | deploy.py、/health、监控 | P2/P3 |

每阶段完成后的验收标准统一包含：`mvn compile` 通过、`python tools\run_tests.py all` 全绿、按 AGENTS.md 同步更新 `CURRENT_ARCHITECTURE.md` 与 `BUSINESS_FLOW.md`。

---

## 附录

### A. 术语表

| 术语 | 说明 |
|------|------|
| TransactionTemplate | 事务模板：统一获取连接、开启/提交/回滚事务、归还连接 |
| LRU | Least Recently Used，最近最少使用淘汰 |
| VO/DTO/Command | 视图对象 / 数据传输对象 / 命令对象（阶段一合并为 model） |
| 媒体审计 | 扫描 content_media 文件是否存在并回写 file_exists/last_verify_time |

### B. 参考资料

1. [Java 编码规范](https://google.github.io/styleguide/javaguide.html)
2. [OWASP 安全指南](https://owasp.org/www-project-top-ten/)
3. [Servlet 规范](https://jakarta.ee/specifications/servlet/)

### C. 文档历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-07-23 | 初始版本 |
| 2.0 | 2026-08-09 | 基于 8/8 灾后状态全面修订：取消 Application 层、DAO 事务方案改为 TransactionTemplate、补齐灾后收尾/备份/清理/运维权限、测试策略去掉 H2/MockMvc、代码规模与硬编码清单刷新 |
| 2.1 | 2026-08-09 | 同步清理结果：删除功能确认不做，风险清单/拆分方案/权限表更新；代码规模按清理后实测刷新 |
| 2.2 | 2026-08-09 | TASK-003/005/006/008/009 完成；备份方案调整为仅数据库备份到 auto_backup，媒体备份由用户手动执行 |
| 2.3 | 2026-08-09 | TASK-007 数据清理完成；备份与清理脚本（backup.py / cleanup_data.py）投入使用 |
