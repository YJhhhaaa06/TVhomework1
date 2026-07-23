# 当前任务清单

> 生成日期：2026-07-23
> 状态：待执行
> 用途：供 AI 逐步执行架构优化任务

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

1. **严格按顺序执行**：必须完成前置依赖才能开始下一任务
2. **每完成一个任务**：将 `[ ]` 改为 `[x]`，并记录完成时间
3. **遇到问题**：将 `[ ]` 改为 `[!]`，并记录问题描述
4. **功能验证**：每个任务完成后必须验证功能不变

---

## 阶段一：基础清理（P0）

> 目标：清理无用代码，建立干净的代码基础

---

### TASK-001: 删除测试类

- **优先级**: P0
- **预估工时**: 30分钟
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

删除混在主代码中的测试类，这些类不应该出现在生产代码中。

#### 涉及文件

| 文件 | 操作 |
|------|------|
| `src/main/java/com/itheima/controller/TestServlet.java` | 删除 |
| `src/main/java/com/itheima/controller/Test3.java` | 删除 |
| `src/main/java/com/itheima/controller/RedisTest.java` | 删除 |
| `src/main/java/com/itheima/dao/daotest.java` | 删除 |
| `src/main/java/com/itheima/exception/Test.java` | 删除 |

#### 验证标准

- [ ] 上述文件已删除
- [ ] 项目编译通过：`mvn compile`
- [ ] web.xml 中如有对 TestServlet 的引用，需同步删除

---

### TASK-002: 删除空壳 Service

- **优先级**: P0
- **预估工时**: 15分钟
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

删除无实际功能的空壳 Service 类。

#### 涉及文件

| 文件 | 操作 |
|------|------|
| `src/main/java/com/itheima/service/MenuService.java` | 删除 |
| `src/main/java/com/itheima/service/ImageService.java` | 删除 |

#### 验证标准

- [ ] 上述文件已删除
- [ ] 项目编译通过：`mvn compile`

---

### TASK-003: 移动管理工具类

- **优先级**: P0
- **预估工时**: 30分钟
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

将管理员工具类从主代码移到测试目录。

#### 涉及文件

| 文件 | 操作 |
|------|------|
| `src/main/java/com/itheima/CouponAdmin.java` | 移动到 `src/test/tools/` |

#### 验证标准

- [ ] CouponAdmin.java 已移动
- [ ] 项目编译通过：`mvn compile`

---

### TASK-004: 删除空壳子模块

- **优先级**: P0
- **预估工时**: 15分钟
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

删除根目录下的空壳子模块目录。

#### 涉及目录

| 目录 | 操作 |
|------|------|
| `ssm_controller/` | 删除 |
| `ssm_dao/` | 删除 |
| `ssm_pojo/` | 删除 |
| `ssm_service/` | 删除 |
| `util/` | 删除 |
| `web/` | 删除 |

#### 验证标准

- [ ] 上述目录已删除
- [ ] 主项目不受影响

---

## 阶段二：包结构规范化（P1）

> 目标：统一包命名，合并分散的数据对象

---

### TASK-005: 包名 DTO → dto

- **优先级**: P1
- **预估工时**: 1小时
- **前置依赖**: TASK-001
- **状态**: `[ ]`

#### 任务描述

将 `DTO` 包重命名为 `dto`，符合 Java 包名全小写规范。

#### 操作步骤

1. 创建新包 `com.itheima.dto`
2. 移动所有文件从 `com.itheima.DTO` 到 `com.itheima.dto`
3. 更新所有文件的 `package` 声明
4. 更新所有引用这些类的 `import` 语句
5. 删除旧的 `DTO` 目录

#### 涉及文件

| 文件 | 需要更新 import 的位置 |
|------|------------------------|
| `com.itheima.DTO.*` | package 声明 |
| `com.itheima.controller.*` | import 语句 |
| `com.itheima.service.*` | import 语句 |
| `com.itheima.command.*` | import 语句 |

#### 验证标准

- [ ] 所有 DTO 类已在 `com.itheima.dto` 包下
- [ ] 无残留的 `com.itheima.DTO` 引用
- [ ] 项目编译通过：`mvn compile`

---

### TASK-006: 合并 pojo/DTO/command 为 model 包

- **优先级**: P1
- **预估工时**: 2小时
- **前置依赖**: TASK-005
- **状态**: `[ ]`

#### 任务描述

将分散的 `pojo`、`DTO`、`command` 包合并为统一的 `model` 包，并按职责细分子包。

#### 目标结构

```
com.itheima.model/
├── entity/          # 数据库实体
│   ├── User.java
│   └── ContentMedia.java
├── dto/             # 数据传输对象（Controller 入参）
│   ├── LoginDTO.java
│   ├── RegisterDTO.java
│   ├── ChangePasswordDTO.java
│   ├── CommentDTO.java
│   ├── SearchDTO.java
│   ├── PageResult.java
│   └── GrabCouponRequest.java
├── vo/              # 视图对象（Controller 出参）
│   ├── LoginVO.java
│   ├── ProfileVO.java
│   ├── ContentVO.java
│   ├── ContentDetailVO.java
│   ├── CommentVO.java
│   └── UploadResult.java
├── cache/           # 缓存对象
│   ├── ContentCacheDTO.java
│   └── CommentCacheDTO.java
└── command/         # 命令对象
    ├── CommandConverter.java
    ├── LoginCommand.java
    ├── RegisterCommand.java
    ├── ChangePasswordCommand.java
    ├── UploadCommand.java
    ├── CommentCommand.java
    ├── ContentType.java
    └── LoginType.java
```

#### 迁移映射

| 原位置 | 目标位置 |
|--------|----------|
| `pojo.User` | `model.entity.User` |
| `pojo.ContentMedia` | `model.entity.ContentMedia` |
| `pojo.LogInVO` | `model.vo.LoginVO` (同时重命名) |
| `pojo.ProfileVO` | `model.vo.ProfileVO` |
| `pojo.ContentVO` | `model.vo.ContentVO` |
| `pojo.ContentDetailVO` | `model.vo.ContentDetailVO` |
| `pojo.CommentVO` | `model.vo.CommentVO` |
| `pojo.UploadResult` | `model.vo.UploadResult` |
| `pojo.ContentCacheDTO` | `model.cache.ContentCacheDTO` |
| `pojo.CommentCacheDTO` | `model.cache.CommentCacheDTO` |
| `DTO.*` | `model.dto.*` |
| `command.*` | `model.command.*` |

#### 验证标准

- [ ] 所有类已在新位置
- [ ] 无残留的旧包引用
- [ ] 项目编译通过：`mvn compile`
- [ ] LogInVO 已重命名为 LoginVO

---

### TASK-007: 统一类名 LogInVO → LoginVO

- **优先级**: P1
- **预估工时**: 15分钟
- **前置依赖**: TASK-006
- **状态**: `[ ]`

#### 任务描述

将 `LogInVO` 重命名为 `LoginVO`，统一命名风格。

#### 涉及文件

| 文件 | 操作 |
|------|------|
| `LogInVO.java` | 重命名为 `LoginVO.java`，更新类名 |
| 所有引用 `LogInVO` 的文件 | 更新 import 和使用 |

#### 验证标准

- [ ] 类已重命名为 `LoginVO`
- [ ] 无残留的 `LogInVO` 引用
- [ ] 项目编译通过：`mvn compile`

---

## 阶段三：异常体系统一（P1）

> 目标：建立统一的异常处理体系

---

### TASK-008: 创建 ErrorCode 枚举

- **优先级**: P1
- **预估工时**: 30分钟
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

将现有的 `ErrorCode` 接口改为枚举类型，统一错误码管理。

#### 文件位置

`src/main/java/com/itheima/exception/ErrorCode.java`

#### 枚举内容

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
    PARAM_ERROR(400, "参数错误"),
    
    // 服务器相关 (500)
    DATABASE_ERROR(500, "数据库操作失败"),
    CACHE_ERROR(500, "缓存操作失败"),
    SERVER_ERROR(500, "服务器内部错误");
    
    private final int code;
    private final String message;
    
    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
    
    public int getCode() { return code; }
    public String getMessage() { return message; }
}
```

#### 验证标准

- [ ] ErrorCode 已改为枚举
- [ ] 项目编译通过（可能需要暂时保留旧的常量兼容）

---

### TASK-009: 创建具体异常类

- **优先级**: P1
- **预估工时**: 30分钟
- **前置依赖**: TASK-008
- **状态**: `[ ]`

#### 任务描述

为每个 ErrorCode 创建对应的异常类，避免直接使用 RuntimeException。

#### 需要创建的异常类

| 异常类 | 继承 | ErrorCode |
|--------|------|-----------|
| `UserNotFoundException` | `AuthException` | USER_NOT_FOUND |
| `PasswordIncorrectException` | `AuthException` | WRONG_PASSWORD |
| `TokenExpiredException` | `AuthException` | TOKEN_EXPIRED |
| `ContentNotFoundException` | `NotFoundException` | CONTENT_NOT_FOUND |
| `CommentNotFoundException` | `NotFoundException` | COMMENT_NOT_FOUND |
| `DuplicateLikeException` | `ConflictException` | DUPLICATE_LIKE |
| `DuplicatePhoneException` | `ConflictException` | DUPLICATE_PHONE |
| `InvalidPhoneException` | `ParamException` | INVALID_PHONE |
| `InvalidPasswordException` | `ParamException` | INVALID_PASSWORD |
| `DatabaseException` | `ServerException` | DATABASE_ERROR |
| `CacheException` | `ServerException` | CACHE_ERROR |

#### 文件位置

`src/main/java/com/itheima/exception/`

#### 验证标准

- [ ] 所有异常类已创建
- [ ] 每个异常类有便捷构造器（只需 message）

---

### TASK-010: 替换 RuntimeException

- **优先级**: P1
- **预估工时**: 2小时
- **前置依赖**: TASK-009
- **状态**: `[ ]`

#### 任务描述

将所有 `throw new RuntimeException(...)` 替换为具体的业务异常。

#### 需要检查的文件

| 文件 | 预期改动 |
|------|----------|
| `UserService.java` | 替换 ~10 处 |
| `ContentService.java` | 替换 ~5 处 |
| `LikeService.java` | 替换 ~5 处 |
| `FollowService.java` | 替换 ~3 处 |
| `CommentService.java` | 替换 ~3 处 |
| `CouponService.java` | 替换 ~2 处 |

#### 替换映射

| 原代码 | 目标代码 |
|--------|----------|
| `throw new RuntimeException("USER_NOT_FOUND")` | `throw new UserNotFoundException()` |
| `throw new RuntimeException("WRONG_PASSWORD")` | `throw new PasswordIncorrectException()` |
| `throw new RuntimeException("PHONE_IN_USE")` | `throw new DuplicatePhoneException()` |
| ... | ... |

#### 验证标准

- [ ] 无 `throw new RuntimeException` 在业务代码中
- [ ] 项目编译通过：`mvn compile`

---

### TASK-011: 统一异常信息语言

- **优先级**: P1
- **预估工时**: 30分钟
- **前置依赖**: TASK-010
- **状态**: `[ ]`

#### 任务描述

统一异常信息为中文，与 ErrorCode 枚举中的 message 保持一致。

#### 检查点

- 所有异常抛出使用 ErrorCode 中定义的 message
- 避免硬编码异常信息

#### 验证标准

- [ ] 异常信息统一为中文
- [ ] 无英文常量（如 "USER_NOT_FOUND"）作为异常信息

---

## 阶段四：配置外部化（P1）

> 目标：将硬编码的配置抽取到配置文件

---

### TASK-012: 创建配置文件

- **优先级**: P1
- **预估工时**: 15分钟
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

创建 `app.properties` 配置文件。

#### 文件位置

`src/main/resources/app.properties`

#### 配置内容

```properties
# ===== 数据库配置 =====
db.driver=com.mysql.cj.jdbc.Driver
db.url=jdbc:mysql://localhost:3306/TVDatabase?useSSL=false&serverTimezone=Asia/Shanghai
db.username=root
db.password=MySQL
db.pool.initSize=5
db.pool.maxSize=20

# ===== Redis 配置 =====
redis.host=localhost
redis.port=6379
redis.maxTotal=50

# ===== JWT 配置 =====
jwt.secret=default-secret-key
jwt.expireHours=2

# ===== 文件上传配置 =====
upload.path=D:/stone

# ===== 缓存配置 =====
cache.content.refreshMinutes=10
cache.content.ttlMinutes=10
```

#### 验证标准

- [ ] 配置文件已创建
- [ ] 文件在 classpath 中可访问

---

### TASK-013: 创建 AppConfig 工具类

- **优先级**: P1
- **预估工时**: 30分钟
- **前置依赖**: TASK-012
- **状态**: `[ ]`

#### 任务描述

创建配置管理类，统一读取配置文件。

#### 文件位置

`src/main/java/com/itheima/config/AppConfig.java`

#### 类设计

```java
package com.itheima.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfig {
    
    private static final Properties props = new Properties();
    
    static {
        try (InputStream is = AppConfig.class.getClassLoader()
                .getResourceAsStream("app.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            System.err.println("加载配置文件失败: " + e.getMessage());
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
    
    // 数据库配置
    public static String getDbUrl() { return get("db.url"); }
    public static String getDbUsername() { return get("db.username"); }
    public static String getDbPassword() { return get("db.password"); }
    public static int getDbPoolInitSize() { return getInt("db.pool.initSize", 5); }
    public static int getDbPoolMaxSize() { return getInt("db.pool.maxSize", 20); }
    
    // Redis 配置
    public static String getRedisHost() { return get("redis.host"); }
    public static int getRedisPort() { return getInt("redis.port", 6379); }
    public static int getRedisMaxTotal() { return getInt("redis.maxTotal", 50); }
    
    // JWT 配置
    public static String getJwtSecret() { return get("jwt.secret"); }
    public static int getJwtExpireHours() { return getInt("jwt.expireHours", 2); }
    
    // 文件上传配置
    public static String getUploadPath() { return get("upload.path"); }
    
    // 缓存配置
    public static int getCacheRefreshMinutes() { return getInt("cache.content.refreshMinutes", 10); }
    public static int getCacheTtlMinutes() { return getInt("cache.content.ttlMinutes", 10); }
}
```

#### 验证标准

- [ ] AppConfig 类已创建
- [ ] 可以正确读取配置文件
- [ ] 项目编译通过：`mvn compile`

---

### TASK-014: 改造 MyConnectionPool 使用配置

- **优先级**: P1
- **预估工时**: 30分钟
- **前置依赖**: TASK-013
- **状态**: `[ ]`

#### 任务描述

修改 `MyConnectionPool` 从 `AppConfig` 读取配置，而不是硬编码。

#### 涉及文件

`src/main/java/com/itheima/util/MyConnectionPool.java`

#### 改动点

```java
// 原代码
private static final String URL = "jdbc:mysql://localhost:3306/TVDatabase?useSSL=false&serverTimezone=Asia/Shanghai";
private static final String USER = "root";
private static final String PASSWORD = "MySQL";
private static final int INIT_SIZE = 5;

// 改为
private static final String URL = AppConfig.getDbUrl();
private static final String USER = AppConfig.getDbUsername();
private static final String PASSWORD = AppConfig.getDbPassword();
private static final int INIT_SIZE = AppConfig.getDbPoolInitSize();
private static final int MAX_SIZE = AppConfig.getDbPoolMaxSize();
```

#### 验证标准

- [ ] 硬编码已移除
- [ ] 从配置文件读取
- [ ] 项目编译通过：`mvn compile`
- [ ] 功能正常：可以连接数据库

---

### TASK-015: 改造 MyRedisPool 使用配置

- **优先级**: P1
- **预估工时**: 15分钟
- **前置依赖**: TASK-013
- **状态**: `[ ]`

#### 任务描述

修改 `MyRedisPool` 从 `AppConfig` 读取配置。

#### 涉及文件

`src/main/java/com/itheima/util/MyRedisPool.java`

#### 改动点

```java
// 原代码
private static final String HOST = "localhost";
private static final int PORT = 6379;
private static final int MAX_TOTAL = 50;

// 改为
private static final String HOST = AppConfig.getRedisHost();
private static final int PORT = AppConfig.getRedisPort();
private static final int MAX_TOTAL = AppConfig.getRedisMaxTotal();
```

#### 验证标准

- [ ] 硬编码已移除
- [ ] 项目编译通过：`mvn compile`
- [ ] 功能正常：可以连接 Redis

---

### TASK-016: 改造 JwtUtil 使用配置

- **优先级**: P1
- **预估工时**: 15分钟
- **前置依赖**: TASK-013
- **状态**: `[ ]`

#### 任务描述

修改 `JwtUtil` 从 `AppConfig` 读取密钥和过期时间。

#### 涉及文件

`src/main/java/com/itheima/util/JwtUtil.java`

#### 改动点

```java
// 原代码
private static final String SECRET = "硬编码密钥";
private static final int EXPIRE_HOURS = 2;

// 改为
private static final String SECRET = AppConfig.getJwtSecret();
private static final int EXPIRE_HOURS = AppConfig.getJwtExpireHours();
```

#### 验证标准

- [ ] 硬编码已移除
- [ ] 项目编译通过：`mvn compile`
- [ ] 功能正常：JWT 生成和验证正常

---

### TASK-017: 改造 ContentService 使用配置

- **优先级**: P1
- **预估工时**: 15分钟
- **前置依赖**: TASK-013
- **状态**: `[ ]`

#### 任务描述

修改 `ContentService` 从 `AppConfig` 读取缓存配置。

#### 涉及文件

`src/main/java/com/itheima/service/ContentService.java`

#### 改动点

```java
// 原代码
private static final long CONTENT_TTL_MS = 10 * 60 * 1000;
scheduler.scheduleAtFixedRate(() -> { ... }, 0, 10, TimeUnit.MINUTES);

// 改为
private static final long CONTENT_TTL_MS = AppConfig.getCacheTtlMinutes() * 60 * 1000;
scheduler.scheduleAtFixedRate(() -> { ... }, 0, AppConfig.getCacheRefreshMinutes(), TimeUnit.MINUTES);
```

#### 验证标准

- [ ] 硬编码已移除
- [ ] 项目编译通过：`mvn compile`

---

## 阶段五：日志规范化（P2）

> 目标：统一日志格式，移除不规范的日志调用

---

### TASK-018: 删除所有 e.printStackTrace()

- **优先级**: P2
- **预估工时**: 30分钟
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

搜索并删除所有 `e.printStackTrace()` 调用，替换为 LOGGER。

#### 检查命令

```bash
grep -r "e.printStackTrace()" src/main/java/
```

#### 替换模式

```java
// 原代码
catch (Exception e) {
    e.printStackTrace();
}

// 替换为
catch (Exception e) {
    LOGGER.log(Level.WARNING, "操作失败", e);
}
```

#### 验证标准

- [ ] 无 `e.printStackTrace()` 调用
- [ ] 项目编译通过：`mvn compile`

---

### TASK-019: 统一日志上下文格式

- **优先级**: P2
- **预估工时**: 1小时
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

统一日志输出格式，添加关键上下文信息。

#### 规范格式

```
[方法名] 消息描述 | key1=value1, key2=value2
```

#### 示例

```java
// 原代码
LOGGER.log(Level.SEVERE, "用户注册失败, phone=" + phone, e);

// 统一为
LOGGER.log(Level.SEVERE, "register 用户注册失败 | phone=" + maskPhone(phone), e);
```

#### 涉及文件

- `UserService.java`
- `ContentService.java`
- `LikeService.java`
- 其他 Service 文件

#### 验证标准

- [ ] 日志格式统一
- [ ] 关键操作有上下文信息

---

## 阶段六：DAO 层优化（P2）

> 目标：提取基类，减少重复代码

---

### TASK-020: 创建 BaseDao 基类

- **优先级**: P2
- **预估工时**: 30分钟
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

创建 `BaseDao` 基类，封装通用的连接管理和异常处理。

#### 文件位置

`src/main/java/com/itheima/dao/base/BaseDao.java`

#### 类设计

```java
package com.itheima.dao.base;

import com.itheima.exception.ServerException;
import com.itheima.util.MyConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class BaseDao {
    
    /**
     * 执行需要返回值的查询
     */
    protected <T> T executeQuery(Function<Connection, T> action) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            return action.apply(conn);
        } catch (SQLException e) {
            throw new ServerException("数据库查询失败", e);
        } finally {
            MyConnectionPool.release(conn);
        }
    }
    
    /**
     * 执行需要事务的操作
     */
    protected void executeTransaction(Consumer<Connection> action) {
        Connection conn = null;
        try {
            conn = MyConnectionPool.getConnection();
            conn.setAutoCommit(false);
            action.accept(conn);
            conn.commit();
        } catch (SQLException e) {
            rollback(conn);
            throw new ServerException("数据库操作失败", e);
        } finally {
            MyConnectionPool.release(conn);
        }
    }
    
    private void rollback(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ex) {
                // 记录日志但不抛出
            }
        }
    }
}
```

#### 验证标准

- [ ] BaseDao 类已创建
- [ ] 项目编译通过：`mvn compile`

---

### TASK-021: 改造 UserDao 使用 BaseDao

- **优先级**: P2
- **预估工时**: 1小时
- **前置依赖**: TASK-020
- **状态**: `[ ]`

#### 任务描述

将 `UserDao` 中的重复连接管理代码替换为 `BaseDao` 方法。

#### 改造示例

```java
// 原代码
public User getUserForLoginById(long id) throws SQLException {
    Connection conn = null;
    try {
        conn = MyConnectionPool.getConnection();
        return getUserForLoginById(conn, id);
    } catch (SQLException e) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ex) {
                e.addSuppressed(ex);
            }
            conn = null;
        }
        throw e;
    } finally {
        MyConnectionPool.release(conn);
    }
}

// 改造后
public User getUserForLoginById(long id) {
    return executeQuery(conn -> getUserForLoginById(conn, id));
}
```

#### 需要改造的方法

- `findUsernameById`
- `findUsernameByPhone`
- `getUserForLoginById` (无参版本)
- `getUserForLoginByPhone` (无参版本)
- `getUserForProfileById` (无参版本)
- `getUserForProfileByPhone` (无参版本)
- `isPhoneUsed` (无参版本)
- `isUserExist` (无参版本)
- `findIDbyPhone`
- `findPhoneById`

#### 验证标准

- [ ] 所有无参方法已改造
- [ ] 项目编译通过：`mvn compile`
- [ ] 功能正常：用户登录、注册正常

---

### TASK-022: 改造其他 DAO 使用 BaseDao

- **优先级**: P2
- **预估工时**: 1小时
- **前置依赖**: TASK-021
- **状态**: `[ ]`

#### 任务描述

将其他 DAO 类改造为继承 `BaseDao`。

#### 涉及文件

- `ContentDao.java`
- `CommentDao.java`
- `FollowDao.java`
- `ContentLikeDao.java`
- `CommentLikeDao.java`
- `CouponDao.java`
- `ContentMediaDao.java`

#### 验证标准

- [ ] 所有 DAO 继承 BaseDao
- [ ] 项目编译通过：`mvn compile`

---

## 阶段七：Service 层重构（P2）

> 目标：拆分过大的 Service 类，明确职责边界

---

### TASK-023: 拆分 ContentService - 创建 ContentCacheManager

- **优先级**: P2
- **预估工时**: 2小时
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

从 `ContentService` 中提取缓存管理相关代码到 `ContentCacheManager`。

#### 新文件位置

`src/main/java/com/itheima/service/ContentCacheManager.java`

#### 迁移的方法和字段

```java
@Component
public class ContentCacheManager {
    // 迁移的字段
    private static List<ContentVO> recommendList = new ArrayList<>();
    private static Map<Long, ContentCacheDTO> contentCache = new HashMap<>();
    private static Map<Long, List<CommentCacheDTO>> commentCache = new HashMap<>();
    private static Map<Long, Long> contentTimestamps = new HashMap<>();
    private static final long CONTENT_TTL_MS = ...;
    private static Map<String, List<Long>> typeCategoryIndex = new HashMap<>();
    private ScheduledExecutorService scheduler;
    
    // 迁移的方法
    public boolean isContentExpired(long contentId) { ... }
    public void evictContent(long contentId) { ... }
    public void cacheContent(long contentId, ContentCacheDTO detail, List<CommentCacheDTO> comments) { ... }
    public void cacheContentBasic(long contentId, ContentCacheDTO detail) { ... }
    public void addToIndex(long contentId, int type, int categoryId) { ... }
    public void removeFromIndex(long contentId, int type, int categoryId) { ... }
    public ContentCacheDTO getContentFromCache(long contentId) { ... }
    public List<ContentVO> getRecommendList() { ... }
    public Map<Long, ContentCacheDTO> getContentCache() { ... }
    public Map<Long, List<CommentCacheDTO>> getCommentCache() { ... }
    
    // 初始化和刷新
    @PostConstruct
    public void init() { ... }
    public void refresh() { ... }
    public void startScheduler() { ... }
    public void shutdown() { ... }
}
```

#### 验证标准

- [ ] ContentCacheManager 已创建
- [ ] ContentService 中的缓存代码已移除
- [ ] 项目编译通过：`mvn compile`
- [ ] 功能正常：缓存刷新正常

---

### TASK-024: 拆分 ContentService - 创建 ContentStatusFiller

- **优先级**: P2
- **预估工时**: 1小时
- **前置依赖**: TASK-023
- **状态**: `[ ]`

#### 任务描述

从 `ContentService` 中提取状态填充相关代码到 `ContentStatusFiller`。

#### 新文件位置

`src/main/java/com/itheima/service/ContentStatusFiller.java`

#### 迁移的方法

```java
@Component
public class ContentStatusFiller {
    @Inject
    private LikeService likeService;
    @Inject
    private FollowDao followDao;
    
    // 迁移的方法
    public void fillContentLikeStatus(ContentVO cVO, long contentId, long userId) { ... }
    public void fillContentLikeStatus(ContentDetailVO cdVO, long contentId, long userId) { ... }
    public void fillLikeAndFollowBatch(List<ContentVO> list, Long userId) { ... }
    public void fillFollowStatus(List<ContentVO> list, Long userId) { ... }
    public void fillFollowStatus(ContentVO vo, Long userId) { ... }
    public void fillFollowStatus(ContentDetailVO vo, Long userId) { ... }
}
```

#### 验证标准

- [ ] ContentStatusFiller 已创建
- [ ] ContentService 中的状态填充代码已移除
- [ ] 项目编译通过：`mvn compile`

---

### TASK-025: 重构 ContentService 剩余代码

- **优先级**: P2
- **预估工时**: 1小时
- **前置依赖**: TASK-023, TASK-024
- **状态**: `[ ]`

#### 任务描述

重构 `ContentService`，注入 `ContentCacheManager` 和 `ContentStatusFiller`。

#### 最终的 ContentService 结构

```java
@Component
public class ContentService {
    @Inject
    private ContentCacheManager cacheManager;
    @Inject
    private ContentStatusFiller statusFiller;
    @Inject
    private ContentDao contentDao;
    @Inject
    private ContentMediaDao contentMediaDao;
    @Inject
    private CommentService commentService;
    
    // 业务查询方法
    public List<ContentVO> getRecommend(int limit) { ... }
    public List<ContentVO> getRecommendByFilter(Integer type, Integer categoryId, int limit) { ... }
    public PageResult<ContentVO> search(String keyword, Long userId, int page, int pageSize) { ... }
    public ContentVO getContentVO(long contentId, Long userId) { ... }
    public ContentDetailVO getContentDetailVO(long contentId, Long userId) { ... }
    
    // 内容发布方法
    public void addVideo(UploadCommand uc, String videoUrl, String coverUrl) { ... }
    public void addPost(UploadCommand uc, String coverUrl, List<String> imageUrls) { ... }
    public void deleteContent(long contentId, long userId) { ... }
}
```

#### 验证标准

- [ ] ContentService 代码量 < 300 行
- [ ] 项目编译通过：`mvn compile`
- [ ] 功能正常：所有内容相关功能正常

---

## 阶段八：安全性加固（P1）

> 目标：消除安全漏洞

---

### TASK-026: SQL 注入审计

- **优先级**: P1
- **预估工时**: 1小时
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

审计所有 DAO 方法，确保使用 PreparedStatement 参数化查询。

#### 检查命令

```bash
# 检查字符串拼接的 SQL
grep -n "sql.*+" src/main/java/com/itheima/dao/*.java
```

#### 检查清单

| DAO 文件 | 检查结果 | 是否安全 |
|----------|----------|----------|
| UserDao.java | | |
| ContentDao.java | | |
| CommentDao.java | | |
| FollowDao.java | | |
| ContentLikeDao.java | | |
| CommentLikeDao.java | | |
| CouponDao.java | | |
| ContentMediaDao.java | | |

#### 验证标准

- [ ] 所有 SQL 使用 PreparedStatement
- [ ] 无字符串拼接 SQL

---

### TASK-027: 添加资源所有权校验

- **优先级**: P1
- **预估工时**: 1小时
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

在删除/修改资源时，校验当前用户是否是资源所有者。

#### 需要校验的接口

| 接口 | Controller | 校验逻辑 |
|------|------------|----------|
| 删除内容 | ContentController | userId == content.authorId |
| 删除评论 | CommentController | userId == comment.userId |
| 修改密码 | UserController | userId == targetUserId |
| 修改用户名 | UserController | userId == targetUserId |

#### 改造示例

```java
// 在 Service 中添加校验
public void deleteContent(long contentId, long userId) {
    ContentCacheDTO content = cacheManager.getContentFromCache(contentId);
    if (content == null) {
        throw new ContentNotFoundException();
    }
    if (content.getAuthorId() != userId) {
        throw new AccessDeniedException("无权删除他人内容");
    }
    // 继续删除逻辑...
}
```

#### 验证标准

- [ ] 所有修改/删除操作有权限校验
- [ ] 无权访问返回 403 错误

---

## 阶段九：连接池优化（P2）

> 目标：优化连接池，防止资源耗尽

---

### TASK-028: 连接池增加最大连接数限制

- **优先级**: P2
- **预估工时**: 30分钟
- **前置依赖**: TASK-014
- **状态**: `[ ]`

#### 任务描述

为 `MyConnectionPool` 添加最大连接数限制和超时机制。

#### 改动点

```java
public class MyConnectionPool {
    private static final int MAX_SIZE = AppConfig.getDbPoolMaxSize();
    private static final long TIMEOUT_MS = 5000;
    
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
        
        return pool.removeFirst();
    }
    
    public static synchronized void release(Connection conn) {
        // ... 原有逻辑
        MyConnectionPool.class.notifyAll();
    }
}
```

#### 验证标准

- [ ] 连接池有最大限制
- [ ] 超时抛出异常
- [ ] 项目编译通过：`mvn compile`

---

## 阶段十：前端优化（P3）

> 目标：优化前端目录结构，抽取公共代码

---

### TASK-029: 创建前端目录结构

- **优先级**: P3
- **预估工时**: 15分钟
- **前置依赖**: 无
- **状态**: `[ ]`

#### 任务描述

创建标准的前端目录结构。

#### 目录结构

```
src/main/webapp/
├── static/
│   ├── css/
│   │   └── common/
│   ├── js/
│   │   └── common/
│   └── images/
└── pages/
```

#### 验证标准

- [ ] 目录已创建

---

### TASK-030: 抽取公共 JavaScript

- **优先级**: P3
- **预估工时**: 1小时
- **前置依赖**: TASK-029
- **状态**: `[ ]`

#### 任务描述

从各页面抽取公共的 JavaScript 代码。

#### 需要创建的文件

| 文件 | 内容 |
|------|------|
| `static/js/common/api.js` | API 请求封装 |
| `static/js/common/auth.js` | 认证相关（token 管理） |
| `static/js/common/utils.js` | 工具函数 |

#### 验证标准

- [ ] 公共 JS 文件已创建
- [ ] 页面可以引用公共 JS

---

## 任务依赖图

```
阶段一（基础清理）
├── TASK-001: 删除测试类 ──────────────┐
├── TASK-002: 删除空壳 Service ────────┤
├── TASK-003: 移动管理工具类 ──────────┤
└── TASK-004: 删除空壳子模块 ──────────┘
                                       │
阶段二（包结构规范化）                   ▼
├── TASK-005: 包名 DTO → dto ◄────────┘
└── TASK-006: 合并为 model 包 ◄───────┘
    └── TASK-007: 统一类名 ◄──────────┘

阶段三（异常体系统一）
├── TASK-008: 创建 ErrorCode 枚举 ────┐
└── TASK-009: 创建具体异常类 ◄────────┘
    └── TASK-010: 替换 RuntimeException ◄──┘
        └── TASK-011: 统一异常信息 ◄───────┘

阶段四（配置外部化）
├── TASK-012: 创建配置文件 ────────────┐
└── TASK-013: 创建 AppConfig ◄────────┘
    ├── TASK-014: 改造 MyConnectionPool
    ├── TASK-015: 改造 MyRedisPool
    ├── TASK-016: 改造 JwtUtil
    └── TASK-017: 改造 ContentService

阶段五（日志规范化）
├── TASK-018: 删除 e.printStackTrace()
└── TASK-019: 统一日志上下文格式

阶段六（DAO 层优化）
├── TASK-020: 创建 BaseDao ────────────┐
├── TASK-021: 改造 UserDao ◄──────────┘
└── TASK-022: 改造其他 DAO ◄──────────┘

阶段七（Service 层重构）
├── TASK-023: 创建 ContentCacheManager
├── TASK-024: 创建 ContentStatusFiller ◄──┘
└── TASK-025: 重构 ContentService ◄───────┘

阶段八（安全性加固）
├── TASK-026: SQL 注入审计
└── TASK-027: 添加资源所有权校验

阶段九（连接池优化）
└── TASK-028: 连接池最大限制 ◄─────── TASK-014

阶段十（前端优化）
├── TASK-029: 创建前端目录结构
└── TASK-030: 抽取公共 JavaScript ◄──┘
```

---

## 执行建议

### 推荐执行顺序

1. **第一批**（快速见效）：TASK-001 → TASK-002 → TASK-003 → TASK-004
2. **第二批**（规范化）：TASK-005 → TASK-006 → TASK-007
3. **第三批**（异常处理）：TASK-008 → TASK-009 → TASK-010 → TASK-011
4. **第四批**（配置管理）：TASK-012 → TASK-013 → TASK-014 → TASK-015 → TASK-016 → TASK-017
5. **后续批次**：按依赖关系逐步执行

### 每个任务的验收标准

- [ ] 代码改动完成
- [ ] 项目编译通过：`mvn compile`
- [ ] 核心功能验证：登录、发布、点赞、评论
- [ ] 无新增警告或错误

---

## 文档历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-07-23 | 初始版本，30 个任务 |
