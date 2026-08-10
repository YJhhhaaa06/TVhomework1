# 当前系统架构地图

> 版本：1.7
> 最后更新：2026-08-10
> 维护说明：每次架构改动后必须更新本文档

---

## 一、项目概述

| 属性 | 值 |
|------|-----|
| 项目名称 | untitled（仿短视频/内容社区平台） |
| groupId | com.itheima |
| 构建工具 | Maven |
| 打包方式 | war（部署到 Tomcat） |
| JDK 版本 | 25 |
| 核心约束 | **禁止使用 Spring、SpringBoot、MyBatis** |

---

## 二、技术栈

| 层面 | 技术 | 版本 |
|------|------|------|
| Web 框架 | 纯 Servlet (Jakarta) | 6.0.0 |
| 依赖注入 | 手写 IoC 容器 | - |
| 数据库访问 | 原生 JDBC | - |
| 数据库 | MySQL | 8.0.33 驱动 |
| 缓存 | Redis (Jedis) | 5.1.0 |
| 认证 | JWT | 4.4.0 |
| 密码加密 | BCrypt (Spring Security Crypto) | 6.4.5 |
| JSON | Jackson | 2.15.2 |
| 日志 | java.util.logging | - |
| 前端 | 原生 HTML/CSS/JavaScript | - |

---

## 三、目录结构

```
untitled/
├── pom.xml                          # Maven 配置
├── AGENTS.md                        # 项目约束（禁止Spring等）
├── .docs/                           # 项目文档
│   ├── CURRENT_ARCHITECTURE.md      # 本文件（系统地图）
│   ├── ARCHITECTURE_PLAN.md         # 架构优化规划
│   ├── CURRENT_TASK.md              # 当前任务清单
│   ├── BUSINESS_FLOW.md             # 业务流程文档
│   ├── TEST_COVERAGE.md             # 测试覆盖分析
│   ├── REMOVE_CODE.md               # 未引用方法清理记录
│   └── 项目分析报告.md              # 项目整体分析
│
├── src/
│   ├── main/
│   │   ├── java/com/itheima/        # Java 源码
│   │   ├── resources/               # 配置文件（待创建）
│   │   └── webapp/                  # Web 应用
│   │       ├── WEB-INF/web.xml      # Servlet 配置
│   │       ├── META-INF/context.xml # Tomcat 配置
│   │       ├── index.html           # 欢迎页（跳转 start.html）
│   │       └── *.html               # 前端页面（9个，含 recovery.html）
│   │
│   └── test/
│       ├── *.http                   # HTTP 测试文件（17个）
│       └── python/                  # pytest 测试脚本（35 例）
│
├── ssm_*/                           # 空壳子模块（待删除）
└── logs/                            # 运行日志
```

---

## 四、Java 包结构

### 4.1 包总览

```
com.itheima/
├── ioc/                    # 手写 IoC 容器（226行）
├── filter/                 # Servlet 过滤器（102行）
├── controller/             # 控制器层（1205行）
├── service/                # 业务逻辑层（2236行）
├── dao/                    # 数据访问层（1160行）
├── model/                  # 统一数据模型（28类，1147行）
│   ├── entity/             # User、ContentMedia
│   ├── dto/                # 请求/分页 DTO
│   ├── vo/                 # 视图对象
│   ├── cache/              # 缓存 DTO
│   ├── audit/              # 媒体审计/恢复结果
│   └── command/            # 命令对象与转换器
├── exception/              # 异常体系（58行）
├── util/                   # 工具类（370行）
```

### 4.2 各包详细清单

> 行数统计更新至 2026-08-10；个别类行数以实际代码为准。

#### ioc 包 — 依赖注入容器

| 类 | 行数 | 职责 |
|----|------|------|
| IocContainer | 189 | 单例容器，管理 Bean 生命周期 |
| ClassScanner | 47 | 扫描 @Component 注解的类 |
| @Component | - | 标记为受管 Bean |
| @Inject | - | 字段依赖注入 |
| @PostConstruct | - | 初始化回调 |

#### filter 包 — 请求过滤器

| 类 | 行数 | 职责 | URL 匹配 |
|----|------|------|----------|
| ExceptionFilter | 55 | 全局异常处理（业务异常按 code/msg 输出，未知异常 500） | /* |
| EncodingFilter | 21 | UTF-8 编码 | /* |
| LoginFilter | 41 | 解析 JWT Token，设置 userId | /* |
| AuthFilter | 59 | 权限校验 | /* |

**执行顺序**：ExceptionFilter → EncodingFilter → LoginFilter → AuthFilter（web.xml 注册）

**AuthFilter 保护路径**：
- 前缀：`/api/upload`、`/api/admin`、`/follow`、`/like`、`/feed`
- 精确：`/comment/add`、`/user/changePassword`、`/coupon/grab`、`/coupon/my`

#### controller 包 — 控制器

| 类 | URL 映射 | 行数 | 职责 |
|----|----------|------|------|
| BaseServlet | - | 40 | 基类，IoC 注入 + JSON 响应 |
| BaseServletUtil | - | 38 | 静态工具，writeSuccess/writeError |
| RequestParser | - | 69 | JSON 请求体解析 |
| AppShutDownListener | - | 33 | 容器生命周期管理 |
| LoginController | /user/* | 88 | 登录、注册、修改密码 |
| StartController | /start | 43 | 首页推荐 |
| SearchController | /search | 127 | 搜索 |
| FeedController | /feed | 64 | 关注动态流 |
| ProfileController | /profile | 76 | 用户主页 |
| UploadController | /api/upload/* | 164 | 上传视频/动态 |
| MediaAdminController | /api/admin/media/* | 83 | 媒体运维：扫描/恢复 |
| FollowController | /follow/* | 103 | 关注/取关 |
| LikeController | /like/* | 127 | 点赞 |
| CommentController | /comment/* | 98 | 评论 |
| CouponController | /coupon/* | 81 | 优惠券 |
| UploadType | - | 72 | 上传类型枚举 |

#### service 包 — 业务逻辑

| 类 | 行数 | 职责 | 依赖 |
|----|------|------|------|
| ContentCacheManager | 544 | 内容缓存管理（内存索引/评论树/实时计数） | ContentDao, ContentMediaDao, CommentDao, LikeCacheService, TransactionTemplate |
| ContentService | 153 | 内容管理（查询与发布） | ContentDao, ContentMediaDao, CommentService, LikeService, ContentCacheManager, ContentStatusFiller, TransactionTemplate |
| ContentStatusFiller | 100 | 内容状态填充（点赞/关注） | LikeService, FollowDao, TransactionTemplate |
| LikeService | 308 | 点赞业务 | ContentDao, CommentDao, ContentLikeDao, CommentLikeDao, LikeCacheService, ContentCacheManager, TransactionTemplate |
| LikeCacheService | 264 | Redis 点赞缓存 | - |
| CommentService | 99 | 评论业务 | CommentDao, ContentDao, ContentCacheManager, TransactionTemplate |
| UserService | 227 | 用户认证 | UserDao |
| FollowService | 118 | 关注业务 | FollowDao, UserDao |
| ProfileService | 91 | 用户主页 | UserDao, ContentDao, FollowDao, ContentCacheManager, LikeService, TransactionTemplate |
| FeedService | 80 | 关注动态流 | FollowDao, ContentDao, ContentCacheManager, LikeService, TransactionTemplate |
| FileUploadService | 78 | 文件上传 | - |
| MediaAuditService | 258 | 媒体完整性扫描与恢复 | ContentDao, ContentMediaDao |
| CouponService | 70 | 优惠券抢购 | CouponDao |

#### dao 包 — 数据访问

| 类 | 行数 | 对应表 | 职责 |
|----|------|--------|------|
| UserDao | 250 | users | 用户 CRUD |
| ContentDao | 290 | content | 内容 CRUD + 全文搜索 + 媒体状态更新 |
| CommentDao | 133 | comment | 评论 CRUD |
| CouponDao | 114 | coupon, coupon_order | 优惠券 CRUD |
| ContentLikeDao | 124 | content_like | 内容点赞 |
| CommentLikeDao | 132 | comment_like | 评论点赞 |
| ContentMediaDao | 99 | content_media | 内容媒体 + 媒体状态更新 |
| FollowDao | 107 | follow | 关注关系 |
| ResultMap | 66 | - | ResultSet → 对象映射 |

> 规范：所有 DAO 方法只接收 `Connection`，不自行获取/释放连接；连接与事务统一由 Service 通过 TransactionTemplate 管理。

#### model 包 — 统一数据模型（原 pojo/DTO/command 合并）

> entity/ 子包

| 类 | 行数 | 类型 | 用途 |
|----|------|------|------|
| User | 89 | 实体 | 用户实体 |
| ContentMedia | 63 | 实体 | 内容媒体实体 |

> dto/ 子包

| 类 | 行数 | 用途 |
|----|------|------|
| LoginDTO | - | 登录请求 |
| RegisterDTO | - | 注册请求 |
| ChangePasswordDTO | - | 修改密码请求 |
| CommentDTO | - | 评论请求 |
| SearchDTO | - | 搜索请求 |
| PageResult | - | 分页结果 |
| GrabCouponRequest | - | 抢券请求 |

> vo/ 子包

| 类 | 行数 | 类型 | 用途 |
|----|------|------|------|
| ProfileVO | 43 | VO | 个人主页视图 |
| ContentVO | 41 | VO | 内容列表视图（继承 ContentCacheDTO） |
| LoginVO | 40 | VO | 登录返回（原 LogInVO） |
| UploadResult | 31 | VO | 上传结果 |
| ContentDetailVO | 25 | VO | 内容详情视图（继承 ContentCacheDTO） |
| CommentVO | 21 | VO | 评论视图 |

> cache/ 子包

| 类 | 行数 | 类型 | 用途 |
|----|------|------|------|
| ContentCacheDTO | 127 | 缓存DTO | 内容缓存对象 |
| CommentCacheDTO | 92 | 缓存DTO | 评论缓存对象（含树形 children） |

> audit/ 子包

| 类 | 行数 | 类型 | 用途 |
|----|------|------|------|
| MediaAuditItem | 87 | 审计 | 媒体扫描结果项（灾后新增） |
| MediaAuditResult | 88 | 审计 | 媒体扫描汇总结果（灾后新增） |
| RestoreResult | 49 | 审计 | 媒体恢复结果（灾后新增） |

> command/ 子包

| 类 | 行数 | 用途 |
|----|------|------|
| CommandConverter | 134 | DTO → Command 转换器 |
| LoginCommand | 63 | 登录命令 |
| RegisterCommand | 44 | 注册命令 |
| ChangePasswordCommand | 44 | 修改密码命令 |
| UploadCommand | 46 | 上传命令 |
| CommentCommand | 50 | 评论命令 |
| ContentType | 16 | 内容类型枚举 |
| LoginType | 7 | 登录类型枚举 |

#### exception 包 — 异常体系

| 类 | 行数 | 错误码 |
|----|------|--------|
| BusinessException | 35 | 基类（code + message，支持 ErrorCode 构造） |
| ErrorCode | 55 | 错误码枚举（code + 中文默认消息） |
| AuthException | 8 | 401 |
| ForbiddenException | 9 | 403 |
| NotFoundException | 7 | 404 |
| ConflictException | 7 | 409 |
| ParamException | 7 | 400 |
| ServerException | 5 | 500 |
| UserNotFoundException / PasswordIncorrectException / TokenExpiredException | - | 401 |
| AccessDeniedException | - | 403 |
| ContentNotFoundException / CommentNotFoundException | - | 404 |
| DuplicateLikeException / DuplicatePhoneException | - | 409 |
| InvalidPhoneException / InvalidPasswordException | - | 400 |
| DatabaseException / CacheException | - | 500 |

#### util 包 — 工具类

| 类 | 行数 | 职责 |
|----|------|------|
| MyConnectionPool | 109 | JDBC 连接池 |
| TransactionTemplate | 50 | 统一事务模板（取连接/提交/回滚/归还，业务异常原样重抛） |
| PasswordUtil | 58 | BCrypt 密码哈希 |
| JwtUtil | 39 | JWT 生成/校验 |
| MyRedisPool | 37 | Redis 连接池 |
| LogUtil | 33 | 日志工具 |
| RequestContext | 24 | 请求上下文路径（动态拼接媒体 URL） |
| StringUtil | 27 | 字符串校验 |
| ResultUtil | 26 | 响应格式构建 |
| CountRepairTool | 59 | 数据修复工具 |
| TimeUtil | 15 | 时间工具 |

---

## 五、数据库设计

### 5.1 连接配置

| 配置项 | 值 | 位置 |
|--------|-----|------|
| 驱动 | com.mysql.cj.jdbc.Driver | app.properties (db.driver) / AppConfig |
| URL | jdbc:mysql://localhost:3306/TVDatabase?useSSL=false&serverTimezone=Asia/Shanghai | app.properties (db.url) / AppConfig |
| 用户名 | root | app.properties (db.username) / AppConfig |
| 密码 | MySQL | app.properties (db.password) / AppConfig |
| 连接池初始大小 | 5 | app.properties (db.pool.initSize) / AppConfig |
| 连接池上限/超时 | 20 / 5000ms（已定义，TASK-042 接线） | app.properties (db.pool.*) / AppConfig |

### 5.2 数据库表

| 表名 | 说明 | 关键字段 |
|------|------|----------|
| users | 用户表 | id, username, hashed_password, phone, follow_count, follower_count |
| content | 内容表 | id, user_id, title, description, type, category_id, comment_count, like_count, is_deleted, create_time, file_exists, last_verify_time |
| comment | 评论表 | id, content_id, user_id, message, parent_id, like_count, is_deleted |
| follow | 关注关系表 | user_id, followed_user_id |
| content_like | 内容点赞表 | user_id, content_id |
| comment_like | 评论点赞表 | user_id, comment_id |
| content_media | 内容媒体表 | content_id, url, media_type(1=视频,2=图片,3=封面), sort, file_exists, last_verify_time |
| coupon | 优惠券表 | id, title, stock, begin_time, end_time |
| coupon_order | 优惠券领取表 | coupon_id, user_id, coupon_code（唯一索引） |

### 5.3 特殊索引

- content 表：全文索引 `MATCH(title, description) AGAINST(? IN NATURAL LANGUAGE MODE)`
- coupon_order 表：唯一索引 `(coupon_id, user_id)`

---

## 六、Redis 设计

### 6.1 连接配置

| 配置项 | 值 | 位置 |
|--------|-----|------|
| 地址 | localhost:6379 | app.properties (redis.host/port) / AppConfig |
| 最大连接数 | 50 | app.properties (redis.maxTotal) / AppConfig |
| 最大空闲 | 10 | app.properties (redis.maxIdle) / AppConfig |
| 最小空闲 | 5 | app.properties (redis.minIdle) / AppConfig |

### 6.2 Key 设计

| Key 模式 | 类型 | 用途 |
|----------|------|------|
| content:like:{contentId} | Set\<userId\> | 内容点赞用户集合 |
| comment:like:{commentId} | Set\<userId\> | 评论点赞用户集合 |

---

## 七、API 接口清单

### 7.1 用户模块

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| POST | /user/login | 登录 | ✗ |
| POST | /user/register | 注册 | ✗ |
| POST | /user/changePassword | 修改密码 | ✓ |

### 7.2 内容模块

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| GET | /start | 首页推荐 | ✗ |
| GET | /search | 搜索 | ✗ |
| GET | /detail | 内容详情 | ✗ |
| GET | /feed | 关注动态流 | ✓ |
| POST | /api/upload/video | 上传视频 | ✓ |
| POST | /api/upload/post | 上传动态 | ✓ |

### 7.3 社交模块

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| POST | /like/content/add | 点赞内容 | ✓ |
| POST | /like/content/remove | 取消点赞 | ✓ |
| POST | /like/comment/add | 点赞评论 | ✓ |
| POST | /like/comment/remove | 取消点赞 | ✓ |
| GET | /like/content/status | 点赞状态 | ✓ |
| GET | /like/content/count | 点赞数 | ✗ |
| GET | /like/comment/status | 点赞状态 | ✓ |
| GET | /like/comment/count | 点赞数 | ✗ |
| POST | /comment/add | 发表评论 | ✓ |
| GET | /comment/show | 查看评论 | ✗ |
| POST | /follow/add | 关注 | ✓ |
| POST | /follow/remove | 取关 | ✓ |
| GET | /follow/following | 关注列表 | ✗ |
| GET | /follow/followers | 粉丝列表 | ✗ |
| GET | /profile | 用户主页 | ✗ |

### 7.4 优惠券模块

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| GET | /coupon/list | 可用优惠券 | ✗ |
| GET | /coupon/my | 我的优惠券 | ✓ |
| POST | /coupon/grab | 抢购优惠券 | ✓ |

### 7.5 媒体运维模块（当前仅要求登录，无管理员角色）

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| GET | /api/admin/media/list | 扫描并返回媒体资源状态 | ✓ |
| POST | /api/admin/media/scan | 重新扫描并回写状态 | ✓ |
| POST | /api/admin/media/restore | 按数据库原文件名重新上传写回 | ✓ |

---

## 八、前端页面

| 页面 | 行数 | 功能 | 路径 |
|------|------|------|------|
| login.html | 198 | 登录/注册 | /login.html |
| start.html | 552 | 首页/启动页 | /start.html |
| search.html | 563 | 搜索 | /search.html |
| detail.html | 589 | 内容详情 | /detail.html |
| publish.html | 581 | 发布内容 | /publish.html |
| space.html | 499 | 个人主页 | /space.html |
| profile.html | 360 | 个人资料 | /profile.html |
| coupon.html | 231 | 优惠券中心 | /coupon.html |
| recovery.html | - | 媒体资源运维（扫描/恢复） | /recovery.html |

---

## 九、测试文件

| 文件 | 用例数 | 覆盖模块 |
|------|--------|----------|
| loginTest.http | 22 | 登录、注册 |
| likeTest.http | 24 | 点赞 |
| commentTest.http | 18 | 评论 |
| followTest.http | 11 | 关注 |
| couponTest.http | 9 | 优惠券 |
| feedTest.http | ~10 | Feed 流 |
| searchTest.http | ~15 | 搜索 |
| startTest.http | ~8 | 首页推荐 |
| changePasswordTest.http | ~12 | 修改密码 |
| uploadTest.http | ~20 | 上传 |
| dataConsistencyTest.http | 23 | 数据一致性 |
| userProfileTest.http | 23 | 用户资料修改 |
| followListTest.http | 19 | 关注/粉丝列表 |
| feedDetailTest.http | 20 | Feed/详情 |
| searchCompleteTest.http | 15 | 搜索完整 |
| startCompleteTest.http | 14 | 首页推荐完整 |
| **合计** | **~265** | - |

---

## 十、代码统计

| 层级 | 文件数 | 代码行数 | 占比 |
|------|--------|----------|------|
| Service | 13 | 2,236 | 34.1% |
| Controller | 18 | 1,205 | 18.4% |
| DAO | 10 | 1,160 | 17.7% |
| Model | 28 | 1,147 | 17.6% |
| Util | 11 | 370 | 5.6% |
| IoC | 5 | 226 | 3.4% |
| Filter | 3 | 102 | 1.6% |
| Exception | 8 | 58 | 0.9% |
| **合计** | **96** | **6,504** | **100%** |

---

## 十一、依赖注入关系图

```
┌─────────────────────────────────────────────────────────────────┐
│                        IoC 容器                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐   │
│  │  UserDao      │     │  ContentDao   │     │  CommentDao   │   │
│  └──────────────┘     └──────────────┘     └──────────────┘   │
│         ▲                    ▲                    ▲            │
│         │                    │                    │            │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐   │
│  │  UserService  │     │ContentService│     │CommentService│   │
│  └──────────────┘     └──────────────┘     └──────────────┘   │
│         ▲                    ▲                    ▲            │
│         │                    │                    │            │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐   │
│  │LoginController│    │StartController│    │CommentController│ │
│  └──────────────┘     └──────────────┘     └──────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 十二、待删除/重构清单

### 12.1 待删除

> 2026-08-10 阶段一完成，本清单已清空：Test3/MenuService/ImageService/UploadServlet/CommentMediaDao/CheckUtil 与 ssm_*/util 全部删除，`mvn compile` 通过。

### 12.2 待重构

| 项目 | 目标 |
|------|------|
| ContentService | ~~拆分为 3-4 个类~~（阶段五已完成：ContentCacheManager + ContentStatusFiller，2026-08-10） |
| DAO 层 | TransactionTemplate + DAO 只接收 Connection（v2.0 修正，废弃 BaseDao 自取连接方案） |
| 异常处理 | 统一使用 BusinessException |
| 运维权限 | /api/admin/* 增加管理员角色 |

---

## 十三、更新日志

| 日期 | 版本 | 更新内容 |
|------|------|----------|
| 2026-08-10 | 1.7 | 阶段五完成：ContentService 缓存职责迁入 ContentCacheManager（@Component 实例 Bean，评论树加载一并迁入以消除循环依赖）；状态填充迁入 ContentStatusFiller；ContentService 精简为查询与发布；CommentService/LikeService/FeedService/ProfileService/StartController 调用点改为注入新 Bean；缓存策略原样保留 |
| 2026-08-10 | 1.6 | 阶段四完成：新增 TransactionTemplate 统一事务管理；UserService/CommentService/FollowService/LikeService/ContentService/CouponService/MediaAuditService/FeedService/ProfileService 手工事务样板全部替换；DAO 全部只接收 Connection（UserDao/CouponDao/ContentLikeDao/CommentLikeDao 移除自取连接包装）；媒体扫描改为单事务 |
| 2026-08-10 | 1.5 | 阶段三完成：ErrorCode 枚举化（code+中文消息）与 12 个具体异常；业务 RuntimeException/英文消息替换为具体异常；新增 ExceptionFilter 全局异常处理并清理 Controller catch 样板；日志清理（printStackTrace/System.out）与手机号脱敏；登录用户不存在调整为 401，非法上传类型调整为 400 |
| 2026-08-10 | 1.4 | 阶段二完成：新增 app.properties + AppConfig（环境变量覆盖）；MyConnectionPool/MyRedisPool/JwtUtil/FileUploadService/LogUtil/ContentService 全部读配置；AppShutDownListener 启动校验 context.xml 与 upload.path 一致；JWT 密钥/有效期、缓存 TTL/刷新、日志路径/级别配置化 |
| 2026-08-10 | 1.3 | 阶段一完成：删除残留测试/空壳类与 ssm_*/util 子模块；CouponAdmin 移至 src/test；pojo/DTO/command 合并为 com.itheima.model（entity/dto/vo/cache/audit/command）；DTO 包名全小写；LogInVO 重命名为 LoginVO |
| 2026-08-09 | 1.2 | 手动清理未引用/未接线方法（Service 12 个、DAO 31 个，见 REMOVE_CODE.md）；删除功能确认不做；代码统计刷新至 97 文件 / 6,559 行；API 清单删除不存在的 /user/changeUserName、/user/changePhone；修复 UploadController return、欢迎页、日志目录 |
| 2026-08-08 | 1.1 | 新增媒体运维：content/content_media 增加 file_exists、last_verify_time；新增 MediaAuditService、MediaAdminController、recovery.html；jointUrl 改为动态 context path |
| 2026-07-23 | 1.0 | 初始版本 |

---

## 维护说明

### 何时更新本文档

- 新增/删除/重命名类
- 新增/删除 API 接口
- 新增/删除数据库表
- 修改依赖注入关系
- 修改配置信息
- 重构包结构

### 如何更新

1. 更新对应的章节
2. 更新"最后更新"日期
3. 在"更新日志"中记录变更
