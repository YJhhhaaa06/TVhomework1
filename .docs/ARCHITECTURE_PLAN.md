# 架构优化规划文档

> 版本：1.0
> 生成日期：2026-07-23
> 状态：规划中

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

---

## 一、现状分析

### 1.1 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                        前端 (HTML)                          │
├─────────────────────────────────────────────────────────────┤
│                    Filter 链 (3个)                          │
│              Encoding → Login → Auth                        │
├─────────────────────────────────────────────────────────────┤
│                 Controller 层 (19个)                        │
├─────────────────────────────────────────────────────────────┤
│                  Service 层 (12个)                          │
├─────────────────────────────────────────────────────────────┤
│                    DAO 层 (11个)                            │
├─────────────────────────────────────────────────────────────┤
│            手写连接池 ──────── Redis 缓存                    │
├─────────────────────────────────────────────────────────────┤
│              MySQL ──────── Redis Server                    │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 已识别问题

| 维度 | 问题 | 严重程度 |
|------|------|----------|
| **分层** | Service 层过厚，职责不清 | 高 |
| **依赖** | IoC 只支持字段注入，无构造器注入 | 中 |
| **异常** | 混用 RuntimeException 和 BusinessException | 高 |
| **配置** | 数据库/Redis 配置硬编码 | 中 |
| **日志** | 部分使用 e.printStackTrace() | 中 |
| **安全** | SQL 参数化不完整，权限硬编码 | 高 |
| **性能** | 连接池无上限，缓存无淘汰策略 | 中 |
| **测试** | 无法 Mock 依赖，无单元测试 | 高 |

---

## 二、优化目标

### 2.1 核心原则

1. **功能不变**：所有优化不改变业务逻辑和 API 行为
2. **渐进式**：按优先级分阶段实施，每阶段可独立验证
3. **可回滚**：每项优化都有回退方案

### 2.2 量化目标

| 指标 | 当前 | 目标 |
|------|------|------|
| 最大类行数 | 778 行 | < 300 行 |
| 代码重复率 | ~15% | < 5% |
| 圈复杂度 | 部分方法 > 15 | 所有方法 < 10 |
| 测试覆盖 | 0% | > 60% |

---

## 三、分层架构优化

### 3.1 当前问题

```
问题1：Service 层职责过重
ContentService（778行）= 缓存管理 + 业务查询 + 内容发布 + 状态填充 + 缓存同步

问题2：缺少中间层
Controller 直接调用 Service，缺少应用服务层编排

问题3：DAO 层连接管理混乱
部分方法自己管理连接，部分接收外部连接
```

### 3.2 目标架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Controller 层                          │
│         职责：请求解析、参数校验、响应构建                      │
├─────────────────────────────────────────────────────────────┤
│                    Application 层（新增）                    │
│         职责：事务编排、跨服务协调、缓存策略                     │
├─────────────────────────────────────────────────────────────┤
│                      Service 层                             │
│         职责：纯业务逻辑、领域规则                              │
├─────────────────────────────────────────────────────────────┤
│                        DAO 层                               │
│         职责：纯数据访问，不管理连接                            │
├─────────────────────────────────────────────────────────────┤
│                    Infrastructure                            │
│         连接池、Redis、日志、配置                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 具体拆分方案

#### ContentService 拆分（778行 → 4个类）

```
ContentService (778行)
│
├──→ ContentCacheManager (~200行)
│    - 缓存 TTL 管理
│    - 类型/分区索引
│    - 定时刷新
│    - 缓存回填
│
├──→ ContentQueryService (~150行)
│    - getRecommend()
│    - search()
│    - getContentVO()
│    - getContentDetailVO()
│
├──→ ContentPublishService (~100行)
│    - addVideo()
│    - addPost()
│    - deleteContent()
│
└──→ ContentStatusFiller (~150行)
     - fillLikeAndFollowBatch()
     - fillFollowStatus()
     - fillContentLikeStatus()
```

#### UserService 拆分（242行 → 2个类）

```
UserService (242行)
│
├──→ UserService (~150行)
│    - login()
│    - register()
│    - changePassword()
│
└──→ UserProfileService (~90行)
     - changeUserName()
     - changePhone()
     - getProfile()
```

### 3.4 DAO 层统一规范

**原则**：DAO 方法只负责单表操作，不管理连接生命周期

```java
// ✗ 当前写法：DAO 自己管理连接
public User getUserForLoginById(long id) throws SQLException {
    Connection conn = null;
    try {
        conn = MyConnectionPool.getConnection();
        return getUserForLoginById(conn, id);
    } finally {
        MyConnectionPool.release(conn);
    }
}

// ✓ 目标写法：DAO 只接收连接
public User getUserForLoginById(Connection conn, long id) throws SQLException {
    // 纯数据访问逻辑
}
```

**连接管理统一由 Application 层/Service 层负责**

---

## 四、依赖注入增强

### 4.1 当前 IoC 容器限制

| 限制 | 影响 |
|------|------|
| 只支持字段注入 | 无法声明依赖关系，难以测试 |
| 不支持接口注入 | 无法针对接口编程 |
| 不支持条件注入 | 无法根据配置切换实现 |
| 无作用域管理 | 所有 Bean 都是单例 |

### 4.2 增强方案

#### 4.2.1 支持构造器注入（优先级 P1）

```java
// 新增注解
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.CONSTRUCTOR)
public @interface InjectConstructor {}

// 使用方式
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

#### 4.2.2 支持接口注入（优先级 P2）

```java
// 绑定接口与实现
@Binding(CacheService.class)
@Component
public class RedisCacheService implements CacheService {
    // ...
}

// 注入接口
@Inject
private CacheService cacheService;
```

#### 4.2.3 生命周期管理增强（优先级 P1）

```java
public interface Disposable {
    void destroy();
}

public interface Initializable {
    void initialize();
}

// IoC 容器自动调用
public class IocContainer {
    public void shutdown() {
        for (Object bean : beans.values()) {
            if (bean instanceof Disposable) {
                ((Disposable) bean).destroy();
            }
        }
    }
}
```

### 4.3 改造计划

| 阶段 | 内容 | 影响 |
|------|------|------|
| 阶段1 | 新增 @InjectConstructor 注解 | 低 |
| 阶段2 | 新增 Initializable/Disposable 接口 | 低 |
| 阶段3 | 新增 @Binding 注解支持接口注入 | 中 |

---

## 五、异常处理体系

### 5.1 当前问题

```java
// 问题1：混用 RuntimeException 和 BusinessException
throw new RuntimeException("USER_NOT_FOUND");      // ✗ 错误
throw new AuthException("WRONG_PASSWORD");          // ✓ 正确

// 问题2：错误信息不统一
throw new NotFoundException("内容不存在");           // 中文
throw new RuntimeException("USER_NOT_FOUND");       // 英文常量

// 问题3：异常被吞掉
catch (Exception e) {
    LOGGER.warning("...");
    // 没有重新抛出或处理
}
```

### 5.2 统一异常体系

```
BusinessException (基类)
├── code: int
├── message: String
├── userMessage: String    // 新增：用户友好消息
└── errorCode: ErrorCode   // 新增：结构化错误码
│
├── AuthException (401)
│   ├── UserNotFoundException
│   ├── PasswordIncorrectException
│   └── TokenExpiredException
│
├── ForbiddenException (403)
│   └── AccessDeniedException
│
├── NotFoundException (404)
│   ├── ContentNotFoundException
│   └── CommentNotFoundException
│
├── ConflictException (409)
│   ├── DuplicateLikeException
│   └── DuplicatePhoneException
│
├── ParamException (400)
│   ├── InvalidPhoneException
│   └── InvalidPasswordException
│
└── ServerException (500)
    ├── DatabaseException
    └── CacheException
```

### 5.3 ErrorCode 枚举化

```java
public enum ErrorCode {
    // 认证相关 (401)
    USER_NOT_FOUND(401, "用户不存在"),
    WRONG_PASSWORD(401, "密码错误"),
    TOKEN_EXPIRED(401, "令牌已过期"),
    
    // 权限相关 (403)
    ACCESS_DENIED(403, "无权访问"),
    
    // 资源相关 (404)
    CONTENT_NOT_FOUND(404, "内容不存在"),
    COMMENT_NOT_FOUND(404, "评论不存在"),
    
    // 冲突相关 (409)
    DUPLICATE_LIKE(409, "不可重复点赞"),
    DUPLICATE_PHONE(409, "手机号已被使用"),
    
    // 参数相关 (400)
    INVALID_PHONE(400, "手机号格式错误"),
    INVALID_PASSWORD(400, "密码格式错误"),
    
    // 服务器相关 (500)
    DATABASE_ERROR(500, "数据库操作失败"),
    CACHE_ERROR(500, "缓存操作失败");
    
    private final int code;
    private final String message;
}
```

### 5.4 全局异常处理器

```java
@Component
public class GlobalExceptionHandler {
    
    private static final Logger LOGGER = LogUtil.getLogger(GlobalExceptionHandler.class);
    
    public void handle(Exception e, HttpServletResponse response) throws IOException {
        if (e instanceof BusinessException be) {
            LOGGER.log(Level.WARNING, "业务异常: " + be.getMessage(), be);
            writeError(response, be.getCode(), be.getMessage());
        } else {
            LOGGER.log(Level.SEVERE, "系统异常", e);
            writeError(response, 500, "服务器内部错误");
        }
    }
}
```

### 5.5 改造计划

| 步骤 | 内容 | 工作量 |
|------|------|--------|
| 1 | 定义 ErrorCode 枚举 | 小 |
| 2 | 创建具体异常类 | 中 |
| 3 | 替换所有 RuntimeException | 大 |
| 4 | 创建全局异常处理器 | 中 |
| 5 | Controller 统一使用异常处理器 | 中 |

---

## 六、配置管理外部化

### 6.1 当前硬编码清单

| 配置项 | 位置 | 硬编码值 |
|--------|------|----------|
| 数据库 URL | MyConnectionPool | `jdbc:mysql://localhost:3306/TVDatabase...` |
| 数据库用户名 | MyConnectionPool | `root` |
| 数据库密码 | MyConnectionPool | `MySQL` |
| 连接池初始大小 | MyConnectionPool | `5` |
| Redis 地址 | MyRedisPool | `localhost:6379` |
| Redis 最大连接数 | MyRedisPool | `50` |
| JWT 密钥 | JwtUtil | 硬编码字符串 |
| JWT 过期时间 | JwtUtil | `2小时` |
| 上传路径 | context.xml | `D:/stone` |
| 缓存刷新间隔 | ContentService | `10分钟` |

### 6.2 配置文件设计

#### app.properties

```properties
# ===== 数据库配置 =====
db.driver=com.mysql.cj.jdbc.Driver
db.url=jdbc:mysql://localhost:3306/TVDatabase?useSSL=false&serverTimezone=Asia/Shanghai
db.username=root
db.password=MySQL
db.pool.initSize=5
db.pool.maxSize=20
db.pool.maxIdle=10
db.pool.minIdle=5

# ===== Redis 配置 =====
redis.host=localhost
redis.port=6379
redis.maxTotal=50
redis.maxIdle=10
redis.minIdle=5

# ===== JWT 配置 =====
jwt.secret=${JWT_SECRET:default-secret-key}
jwt.expireHours=2

# ===== 文件上传配置 =====
upload.path=${UPLOAD_PATH:D:/stone}
upload.maxSize=104857600

# ===== 缓存配置 =====
cache.content.refreshMinutes=10
cache.content.ttlMinutes=10

# ===== 日志配置 =====
log.level=INFO
log.file=logs/app.log
```

### 6.3 配置管理类设计

```java
@Component
public class AppConfig {
    
    private static final Properties props = new Properties();
    
    static {
        try (InputStream is = AppConfig.class.getClassLoader()
                .getResourceAsStream("app.properties")) {
            if (is != null) {
                props.load(is);
            }
            // 支持环境变量覆盖
            for (String key : props.stringPropertyNames()) {
                String envValue = System.getenv(key.replace('.', '_').toUpperCase());
                if (envValue != null) {
                    props.setProperty(key, envValue);
                }
            }
        } catch (IOException e) {
            LogUtil.getLogger(AppConfig.class)
                .log(Level.SEVERE, "加载配置文件失败", e);
        }
    }
    
    public static String get(String key) {
        return props.getProperty(key);
    }
    
    public static String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }
    
    public static int getInt(String key, int defaultValue) {
        String value = props.getProperty(key);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }
    
    // 数据库配置快捷方法
    public static String getDbUrl() { return get("db.url"); }
    public static String getDbUsername() { return get("db.username"); }
    public static String getDbPassword() { return get("db.password"); }
    
    // Redis 配置快捷方法
    public static String getRedisHost() { return get("redis.host"); }
    public static int getRedisPort() { return getInt("redis.port", 6379); }
    
    // JWT 配置快捷方法
    public static String getJwtSecret() { return get("jwt.secret"); }
    public static int getJwtExpireHours() { return getInt("jwt.expireHours", 2); }
}
```

### 6.4 环境变量支持

| 配置项 | 环境变量 | 说明 |
|--------|----------|------|
| db.password | DB_PASSWORD | 生产环境必须设置 |
| jwt.secret | JWT_SECRET | 生产环境必须设置 |
| redis.host | REDIS_HOST | 可选 |

### 6.5 改造计划

| 步骤 | 内容 | 风险 |
|------|------|------|
| 1 | 创建 app.properties 文件 | 低 |
| 2 | 创建 AppConfig 类 | 低 |
| 3 | 修改 MyConnectionPool 使用配置 | 中 |
| 4 | 修改 MyRedisPool 使用配置 | 中 |
| 5 | 修改 JwtUtil 使用配置 | 中 |
| 6 | 修改 ContentService 使用配置 | 低 |

---

## 七、日志体系规范

### 7.1 当前问题

```java
// 问题1：使用 e.printStackTrace()
e.printStackTrace();  // ✗ 输出到 stderr，无格式，无法控制级别

// 问题2：日志级别使用不规范
LOGGER.warning("缓存刷新失败");  // 应该是 SEVERE

// 问题3：缺少上下文信息
LOGGER.severe("操作失败");  // 缺少 userId、contentId 等

// 问题4：敏感信息泄露
LOGGER.info("用户登录: " + password);  // ✗ 记录了密码
```

### 7.2 日志规范

#### 级别使用标准

| 级别 | 使用场景 | 示例 |
|------|----------|------|
| SEVERE | 操作最终失败，需要抛出异常 | SQLException 导致数据库写入失败 |
| WARNING | 出现问题但被恢复/降级 | 事务回滚成功、缓存失败走 DB 兜底 |
| INFO | 关键业务节点 | 用户登录、缓存初始化完成 |
| FINE | 开发调试信息 | SQL 语句、请求参数 |

#### 日志格式规范

```
[时间] [级别] [类名] [方法] - 消息 | 上下文键值对
```

示例：
```
2026-07-23 10:30:45 SEVERE [UserService] [register] - 用户注册失败 | userId=123, phone=138****1234
```

#### 敏感信息脱敏

| 字段 | 脱敏规则 | 示例 |
|------|----------|------|
| 手机号 | 中间4位 | 138****1234 |
| 密码 | 完全隐藏 | ****** |
| Token | 前8位 | eyJhbGc... |

### 7.3 Logger 工具增强

```java
public class LogUtil {
    
    // 敏感字段脱敏
    private static String mask(String value, String type) {
        if (value == null) return "null";
        return switch (type) {
            case "phone" -> value.substring(0, 3) + "****" + value.substring(7);
            case "password" -> "******";
            case "token" -> value.substring(0, 8) + "...";
            default -> value;
        };
    }
    
    // 便捷方法
    public static void info(Logger logger, String method, String message, Object... context) {
        logger.info(() -> buildMessage(method, message, context));
    }
    
    public static void severe(Logger logger, String method, String message, Throwable e, Object... context) {
        logger.log(Level.SEVERE, buildMessage(method, message, context), e);
    }
    
    private static String buildMessage(String method, String message, Object[] context) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(method).append("] - ").append(message);
        if (context.length > 0) {
            sb.append(" | ");
            for (int i = 0; i < context.length; i += 2) {
                if (i > 0) sb.append(", ");
                sb.append(context[i]).append("=").append(context[i + 1]);
            }
        }
        return sb.toString();
    }
}
```

### 7.4 改造计划

| 步骤 | 内容 | 工作量 |
|------|------|--------|
| 1 | 删除所有 e.printStackTrace() | 小 |
| 2 | 增强 LogUtil 工具类 | 中 |
| 3 | 统一日志格式和上下文 | 大 |
| 4 | 添加敏感信息脱敏 | 中 |

---

## 八、安全性加固

### 8.1 当前风险清单

| 风险 | 位置 | 严重程度 |
|------|------|----------|
| SQL 注入风险 | 部分 DAO 方法未使用参数化 | 高 |
| 权限校验缺失 | 未校验资源所有权 | 高 |
| XSS 风险 | 用户输入未过滤 | 中 |
| CSRF 风险 | 无 CSRF Token | 中 |
| 密钥硬编码 | JWT 密钥在代码中 | 高 |
| 敏感信息泄露 | 日志记录密码等 | 中 |

### 8.2 SQL 注入防护

#### 当前安全的方法

```java
// ✓ 使用 PreparedStatement 参数化
String sql = "select * from users where id=?";
pstmt.setLong(1, id);
```

#### 需要检查的方法

```java
// ✗ 检查是否存在字符串拼接
String sql = "select * from users where username='" + username + "'";
```

**改造计划**：
1. 审计所有 DAO 方法，确保使用 PreparedStatement
2. 创建 SQL 审计工具，静态检查 SQL 拼接

### 8.3 权限校验增强

#### 当前问题

```java
// 删除内容时未校验是否是作者
public void deleteContent(long contentId, long userId) {
    // 只检查了登录状态，未检查是否是内容作者
}
```

#### 增强方案

```java
// 1. 创建权限校验注解
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireOwnership {
    String resourceType();  // "content", "comment", "user"
    String idParam();       // 参数名
}

// 2. 在 Filter 或 AOP 中校验
@RequireOwnership(resourceType = "content", idParam = "contentId")
public void deleteContent(long contentId, long userId) {
    // 框架自动校验 userId 是否是 content 的作者
}
```

#### 需要校验的接口

| 接口 | 资源类型 | 校验逻辑 |
|------|----------|----------|
| DELETE /content/{id} | content | userId == content.authorId |
| PUT /user/profile | user | userId == targetUserId |
| DELETE /comment/{id} | comment | userId == comment.userId |

### 8.4 XSS 防护

```java
// 创建 XSS 过滤器
public class XssFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        chain.doFilter(new XssRequestWrapper((HttpServletRequest) request), response);
    }
}

// 请求包装器
public class XssRequestWrapper extends HttpServletRequestWrapper {
    @Override
    public String getParameter(String name) {
        String value = super.getParameter(name);
        return value != null ? cleanXSS(value) : null;
    }
    
    private String cleanXSS(String value) {
        // 移除危险字符
        value = value.replaceAll("<", "&lt;").replaceAll(">", "&gt;");
        value = value.replaceAll("\\(", "&#40;").replaceAll("\\)", "&#41;");
        value = value.replaceAll("'", "&#39;");
        value = value.replaceAll("eval\\((.*)\\)", "");
        value = value.replaceAll("[\\\"\\'][\\s]*javascript:(.*)[\\\"\\']", "\"\"");
        return value;
    }
}
```

### 8.5 改造计划

| 步骤 | 内容 | 优先级 |
|------|------|--------|
| 1 | 审计 SQL 注入风险 | P0 |
| 2 | 添加资源所有权校验 | P0 |
| 3 | 配置外部化（密钥等） | P0 |
| 4 | 添加 XSS 过滤器 | P1 |
| 5 | 添加 CSRF Token | P2 |

---

## 九、性能优化

### 9.1 当前瓶颈分析

| 瓶颈 | 位置 | 影响 |
|------|------|------|
| 连接池无上限 | MyConnectionPool | 可能耗尽数据库连接 |
| 缓存无淘汰策略 | ContentService | 内存可能溢出 |
| N+1 查询 | 部分列表查询 | 数据库压力大 |
| 无分页缓存 | 每次查询都访问 DB | 响应慢 |

### 9.2 连接池优化

#### 当前问题

```java
// 无最大连接数限制
if (pool.isEmpty()) {
    createConnection();  // 可能无限创建
}
```

#### 优化方案

```java
public class MyConnectionPool {
    private static final int MAX_SIZE = 20;      // 新增：最大连接数
    private static final long TIMEOUT_MS = 5000; // 新增：获取超时
    
    public static synchronized Connection getConnection() throws SQLException {
        if (isClosed) {
            throw new IllegalStateException("连接池已关闭");
        }
        
        // 等待可用连接
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (pool.isEmpty()) {
            if (allConnections.size() < MAX_SIZE) {
                return createConnection();
            }
            try {
                long waitTime = deadline - System.currentTimeMillis();
                if (waitTime <= 0) {
                    throw new SQLException("获取连接超时");
                }
                MyConnectionPool.class.wait(waitTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("获取连接被中断", e);
            }
        }
        
        Connection conn = pool.removeFirst();
        allConnections.add(conn);
        return conn;
    }
    
    public static synchronized void release(Connection conn) {
        // ... 原有逻辑
        pool.addLast(conn);
        MyConnectionPool.class.notifyAll(); // 唤醒等待的线程
    }
}
```

### 9.3 缓存淘汰策略

#### 当前问题

```java
// 缓存只增不减，无淘汰策略
private static Map<Long, ContentCacheDTO> contentCache = new HashMap<>();
```

#### 优化方案：LRU 缓存

```java
public class LruCache<K, V> extends LinkedHashMap<K, V> {
    private final int maxSize;
    
    public LruCache(int maxSize) {
        super(maxSize, 0.75f, true); // accessOrder = true
        this.maxSize = maxSize;
    }
    
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxSize;
    }
}

// 使用
private static Map<Long, ContentCacheDTO> contentCache = 
    new LruCache<>(1000); // 最多缓存 1000 个内容
```

### 9.4 批量查询优化

#### 当前 N+1 问题

```java
// ✗ N+1 查询
for (Long contentId : contentIds) {
    ContentCacheDTO dto = contentDao.findContent(conn, contentId);  // N 次查询
}
```

#### 优化方案

```java
// ✓ 批量查询
public List<ContentCacheDTO> findContentsByIds(Connection conn, List<Long> ids) {
    if (ids == null || ids.isEmpty()) return Collections.emptyList();
    
    String sql = "SELECT * FROM content WHERE id IN (" + 
        ids.stream().map(id -> "?").collect(Collectors.joining(",")) + ")";
    
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        for (int i = 0; i < ids.size(); i++) {
            ps.setLong(i + 1, ids.get(i));
        }
        // ...
    }
}
```

### 9.5 改造计划

| 步骤 | 内容 | 预期收益 |
|------|------|----------|
| 1 | 连接池增加最大限制 | 防止连接耗尽 |
| 2 | 缓存增加 LRU 淘汰 | 控制内存使用 |
| 3 | 优化 N+1 查询 | 减少数据库压力 |
| 4 | 增加查询结果缓存 | 提升响应速度 |

---

## 十、可测试性改进

### 10.1 当前测试困境

```
问题1：无法 Mock 依赖
- 字段注入无法替换依赖
- 无法隔离测试单个类

问题2：依赖外部资源
- 测试需要真实数据库
- 测试需要真实 Redis

问题3：无单元测试
- 只有 .http 集成测试
- 无法自动化回归
```

### 10.2 可测试性设计原则

1. **依赖注入**：通过构造器注入，便于替换 Mock
2. **接口隔离**：针对接口编程，便于 Mock 实现
3. **资源抽象**：数据库、Redis 通过接口访问，测试时使用内存实现

### 10.3 测试基础设施

#### Mock 框架支持

```xml
<!-- pom.xml 添加测试依赖 -->
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>5.5.0</version>
    <scope>test</scope>
</dependency>
```

#### 测试示例

```java
class UserServiceTest {
    
    @Mock
    private UserDao userDao;
    
    @Mock
    private PasswordUtil passwordUtil;
    
    @InjectMocks
    private UserService userService;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    @Test
    void login_success() {
        // Arrange
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setHashedPassword("hashed123");
        
        when(userDao.getUserForLoginByPhone("13800138000"))
            .thenReturn(mockUser);
        when(passwordUtil.isPasswordCorrect("password", "hashed123"))
            .thenReturn(true);
        
        // Act
        LoginCommand command = new LoginCommand();
        command.setPhone("13800138000");
        command.setPassword("password");
        LoginVO result = userService.login(command);
        
        // Assert
        assertNotNull(result);
        assertEquals(1L, result.getUserId());
    }
}
```

### 10.4 测试分层策略

| 层级 | 测试类型 | 工具 | 覆盖目标 |
|------|----------|------|----------|
| 单元测试 | Service 层 | JUnit + Mockito | 业务逻辑 |
| 集成测试 | DAO 层 | H2 内存数据库 | SQL 正确性 |
| 接口测试 | Controller 层 | MockMvc / HTTP Client | API 行为 |
| 端到端测试 | 全链路 | Selenium / Playwright | 用户流程 |

### 10.5 改造计划

| 步骤 | 内容 | 前置条件 |
|------|------|----------|
| 1 | 添加测试依赖 | 无 |
| 2 | 改造 Service 支持构造器注入 | IoC 增强 |
| 3 | 创建 DAO 测试基类 | H2 数据库配置 |
| 4 | 编写核心业务单元测试 | 步骤 2 完成 |

---

## 十一、前端架构优化

### 11.1 当前问题

| 问题 | 影响 |
|------|------|
| 8 个 HTML 文件平铺 | 难以管理 |
| JS 代码内联在 HTML | 无法复用 |
| CSS 样式重复 | 维护困难 |
| 无构建工具 | 无法压缩、打包 |

### 11.2 目录结构优化

```
webapp/
├── WEB-INF/
├── static/
│   ├── css/
│   │   ├── common.css        # 公共样式
│   │   ├── reset.css          # 样式重置
│   │   └── pages/
│   │       ├── login.css
│   │       ├── start.css
│   │       └── ...
│   ├── js/
│   │   ├── common/
│   │   │   ├── api.js         # API 请求封装
│   │   │   ├── auth.js        # 认证相关
│   │   │   ├── utils.js       # 工具函数
│   │   │   └── constants.js   # 常量定义
│   │   └── pages/
│   │       ├── login.js
│   │       ├── start.js
│   │       └── ...
│   └── images/
├── pages/
│   ├── login.html
│   ├── start.html
│   └── ...
└── index.html                 # 入口重定向
```

### 11.3 JavaScript 模块化

```javascript
// common/api.js - API 请求封装
const API = {
    baseUrl: '/MyAPP',
    
    async request(url, options = {}) {
        const token = localStorage.getItem('token');
        const headers = {
            'Content-Type': 'application/json',
            ...options.headers
        };
        if (token) {
            headers['Authorization'] = `Bearer ${token}`;
        }
        
        const response = await fetch(this.baseUrl + url, {
            ...options,
            headers
        });
        
        if (response.status === 401) {
            window.location.href = '/pages/login.html';
            return;
        }
        
        return response.json();
    },
    
    get(url) {
        return this.request(url);
    },
    
    post(url, data) {
        return this.request(url, {
            method: 'POST',
            body: JSON.stringify(data)
        });
    }
};
```

### 11.4 改造计划

| 步骤 | 内容 | 工作量 |
|------|------|--------|
| 1 | 创建 static 目录结构 | 小 |
| 2 | 抽取公共 CSS/JS | 中 |
| 3 | 页面移入 pages 目录 | 小 |
| 4 | 更新资源引用路径 | 中 |

---

## 十二、部署运维改进

### 12.1 当前部署方式

```
手动操作：
1. mvn package
2. 复制 war 到 Tomcat webapps
3. 重启 Tomcat
```

### 12.2 自动化部署脚本

#### deploy.sh

```bash
#!/bin/bash

# 配置
TOMCAT_HOME=/opt/tomcat
APP_NAME=MyAPP
DEPLOY_PATH=$TOMCAT_HOME/webapps

# 1. 构建
echo "Building..."
mvn clean package -DskipTests

# 2. 停止应用
echo "Stopping application..."
$TOMCAT_HOME/bin/shutdown.sh

# 3. 备份旧版本
echo "Backing up..."
if [ -f "$DEPLOY_PATH/$APP_NAME.war" ]; then
    mv "$DEPLOY_PATH/$APP_NAME.war" "$DEPLOY_PATH/$APP_NAME.war.bak"
fi

# 4. 部署新版本
echo "Deploying..."
cp target/$APP_NAME.war $DEPLOY_PATH/

# 5. 启动应用
echo "Starting application..."
$TOMCAT_HOME/bin/startup.sh

echo "Deployment completed!"
```

### 12.3 健康检查接口

```java
@WebServlet("/health")
public class HealthController extends BaseServlet {
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws IOException {
        Map<String, Object> status = new HashMap<>();
        
        // 检查数据库连接
        try (Connection conn = MyConnectionPool.getConnection()) {
            status.put("database", "UP");
        } catch (SQLException e) {
            status.put("database", "DOWN");
            resp.setStatus(503);
        }
        
        // 检查 Redis 连接
        try (Jedis jedis = MyRedisPool.getResource()) {
            jedis.ping();
            status.put("redis", "UP");
        } catch (Exception e) {
            status.put("redis", "DOWN");
        }
        
        status.put("timestamp", System.currentTimeMillis());
        
        resp.setContentType("application/json");
        mapper.writeValue(resp.getWriter(), status);
    }
}
```

### 12.4 监控指标

| 指标 | 采集方式 | 告警阈值 |
|------|----------|----------|
| 响应时间 | Filter 记录 | > 3s |
| 错误率 | 异常计数 | > 5% |
| 连接池使用率 | 连接池统计 | > 80% |
| 内存使用率 | JVM 监控 | > 85% |

### 12.5 改造计划

| 步骤 | 内容 | 优先级 |
|------|------|--------|
| 1 | 创建部署脚本 | P1 |
| 2 | 添加健康检查接口 | P1 |
| 3 | 添加基础监控指标 | P2 |
| 4 | 配置日志收集 | P2 |

---

## 十三、实施路线图

### 阶段一：基础加固（1-2周）

```
目标：消除技术债务，建立基础设施
```

| 任务 | 优先级 | 工作量 |
|------|--------|--------|
| 删除测试类/空壳类 | P0 | 1天 |
| 包名规范化 (DTO → dto) | P1 | 1天 |
| 配置外部化 | P1 | 2天 |
| 异常体系统一 | P1 | 2天 |
| 日志规范统一 | P2 | 1天 |

### 阶段二：架构优化（2-3周）

```
目标：优化分层，提升代码质量
```

| 任务 | 优先级 | 工作量 |
|------|--------|--------|
| 合并 pojo/DTO/command 为 model | P1 | 2天 |
| ContentService 拆分 | P1 | 3天 |
| 提取 BaseDao 基类 | P2 | 2天 |
| 提取 TransactionTemplate | P2 | 1天 |
| 连接池优化 | P2 | 1天 |

### 阶段三：安全加固（1-2周）

```
目标：消除安全漏洞
```

| 任务 | 优先级 | 工作量 |
|------|--------|--------|
| SQL 注入审计 | P0 | 2天 |
| 权限校验增强 | P0 | 2天 |
| XSS 防护 | P1 | 1天 |
| 敏感信息脱敏 | P1 | 1天 |

### 阶段四：测试建设（2-3周）

```
目标：建立自动化测试体系
```

| 任务 | 优先级 | 工作量 |
|------|--------|--------|
| IoC 支持构造器注入 | P1 | 2天 |
| 添加测试依赖 | P1 | 0.5天 |
| 核心 Service 单元测试 | P2 | 5天 |
| DAO 集成测试 | P2 | 3天 |

### 阶段五：运维优化（1周）

```
目标：提升部署和监控能力
```

| 任务 | 优先级 | 工作量 |
|------|--------|--------|
| 部署脚本 | P1 | 1天 |
| 健康检查接口 | P1 | 0.5天 |
| 基础监控指标 | P2 | 2天 |
| 前端目录优化 | P3 | 2天 |

---

## 附录

### A. 术语表

| 术语 | 说明 |
|------|------|
| IoC | Inversion of Control，控制反转 |
| DAO | Data Access Object，数据访问对象 |
| VO | View Object，视图对象 |
| DTO | Data Transfer Object，数据传输对象 |
| LRU | Least Recently Used，最近最少使用 |
| XSS | Cross-Site Scripting，跨站脚本 |
| CSRF | Cross-Site Request Forgery，跨站请求伪造 |

### B. 参考资料

1. [Java 编码规范](https://google.github.io/styleguide/javaguide.html)
2. [OWASP 安全指南](https://owasp.org/www-project-top-ten/)
3. [Servlet 规范](https://jakarta.ee/specifications/servlet/)

### C. 文档历史

| 版本 | 日期 | 作者 | 说明 |
|------|------|------|------|
| 1.0 | 2026-07-23 | - | 初始版本 |
