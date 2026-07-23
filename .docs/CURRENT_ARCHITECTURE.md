# 当前系统架构地图

> 版本：1.0
> 最后更新：2026-07-23
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
├── CLAUDE.md                        # 项目约束（禁止Spring等）
├── .docs/                           # 项目文档
│   ├── CURRENT_ARCHITECTURE.md      # 本文件（系统地图）
│   ├── ARCHITECTURE_PLAN.md         # 架构优化规划
│   ├── CURRENT_TASK.md              # 当前任务清单
│   ├── BUSINESS_FLOW.md             # 业务流程文档
│   ├── TEST_COVERAGE.md             # 测试覆盖分析
│   └── 项目分析报告.md              # 项目整体分析
│
├── src/
│   ├── main/
│   │   ├── java/com/itheima/        # Java 源码
│   │   ├── resources/               # 配置文件（待创建）
│   │   └── webapp/                  # Web 应用
│   │       ├── WEB-INF/web.xml      # Servlet 配置
│   │       ├── META-INF/context.xml # Tomcat 配置
│   │       └── *.html               # 前端页面（8个）
│   │
│   └── test/
│       ├── *.http                   # HTTP 测试文件（17个）
│       └── python/                  # Python 测试脚本（待创建）
│
├── ssm_*/                           # 空壳子模块（待删除）
├── web/                             # 空壳目录（待删除）
└── logs/                            # 运行日志
```

---

## 四、Java 包结构

### 4.1 包总览

```
com.itheima/
├── ioc/                    # 手写 IoC 容器（266行）
├── filter/                 # Servlet 过滤器（121行）
├── controller/             # 控制器层（1308行）
├── service/                # 业务逻辑层（2415行）
├── dao/                    # 数据访问层（1802行）
├── pojo/                   # 实体/VO/缓存DTO（567行）
├── DTO/                    # 数据传输对象（267行）
├── command/                # 命令对象（404行）
├── exception/              # 异常体系（78行）
├── util/                   # 工具类（407行）
└── CouponAdmin.java        # 管理员工具（60行）
```

### 4.2 各包详细清单

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
| EncodingFilter | 21 | UTF-8 编码 | /* |
| LoginFilter | 41 | 解析 JWT Token，设置 userId | /* |
| AuthFilter | 59 | 权限校验 | /* |

**执行顺序**：EncodingFilter → LoginFilter → AuthFilter

**AuthFilter 保护路径**：
- 前缀：`/api/upload`、`/follow`、`/like`、`/feed`
- 精确：`/comment/add`、`/user/changePassword`、`/coupon/grab`、`/coupon/my`

#### controller 包 — 控制器

| 类 | URL 映射 | 行数 | 职责 |
|----|----------|------|------|
| BaseServlet | - | 40 | 基类，IoC 注入 + JSON 响应 |
| BaseServletUtil | - | 38 | 静态工具，writeSuccess/writeError |
| RequestParser | - | 69 | JSON 请求体解析 |
| AppShutDownListener | - | 30 | 容器生命周期管理 |
| LoginController | /user/* | 88 | 登录、注册、修改密码 |
| StartController | /start | 42 | 首页推荐 |
| SearchController | /search | 126 | 搜索 |
| FeedController | /feed | 63 | 关注动态流 |
| ProfileController | /profile | 75 | 用户主页 |
| UploadController | /api/upload/* | 162 | 上传视频/动态 |
| FollowController | /follow/* | 103 | 关注/取关 |
| LikeController | /like/* | 126 | 点赞 |
| CommentController | /comment/* | 98 | 评论 |
| CouponController | /coupon/* | 80 | 优惠券 |
| UploadServlet | (旧版) | 57 | 已废弃 |
| TestServlet | /test | 14 | 测试用（待删除） |
| Test3 | - | 11 | 测试用（待删除） |
| RedisTest | - | 14 | 测试用（待删除） |

#### service 包 — 业务逻辑

| 类 | 行数 | 职责 | 依赖 |
|----|------|------|------|
| ContentService | 778 | 内容管理（核心），含缓存 | ContentDao, CommentDao, ContentMediaDao, FollowDao, CommentService, LikeService, LikeCacheService |
| LikeService | 370 | 点赞业务 | ContentDao, CommentDao, ContentLikeDao, CommentLikeDao, LikeCacheService |
| LikeCacheService | 263 | Redis 点赞缓存 | - |
| CommentService | 261 | 评论业务 | CommentDao, ContentDao, LikeService |
| UserService | 242 | 用户认证 | UserDao |
| FollowService | 138 | 关注业务 | FollowDao, UserDao |
| ProfileService | 103 | 用户主页 | UserDao, ContentService, FollowService, LikeService |
| FeedService | 95 | 关注动态流 | FollowDao, ContentDao, ContentService, LikeService |
| FileUploadService | 76 | 文件上传 | - |
| CouponService | 75 | 优惠券抢购 | CouponDao |
| MenuService | 7 | 空壳（待删除） | - |
| ImageService | 7 | 空壳（待删除） | - |

#### dao 包 — 数据访问

| 类 | 行数 | 对应表 | 职责 |
|----|------|--------|------|
| UserDao | 438 | users | 用户 CRUD |
| ContentDao | 404 | content | 内容 CRUD + 全文搜索 |
| CommentDao | 193 | comment | 评论 CRUD |
| CouponDao | 157 | coupon, coupon_order | 优惠券 CRUD |
| ContentLikeDao | 140 | content_like | 内容点赞 |
| CommentLikeDao | 132 | comment_like | 评论点赞 |
| ContentMediaDao | 100 | content_media | 内容媒体 |
| FollowDao | 95 | follow | 关注关系 |
| ResultMap | 65 | - | ResultSet → 对象映射 |
| CommentMediaDao | 8 | comment_media | 空壳 |
| daotest | 70 | - | 测试类（待删除） |

#### pojo 包 — 实体/VO

| 类 | 行数 | 类型 | 用途 |
|----|------|------|------|
| ContentCacheDTO | 126 | 缓存DTO | 内容缓存对象 |
| CommentCacheDTO | 91 | 缓存DTO | 评论缓存对象（含树形 children） |
| User | 89 | 实体 | 用户实体 |
| ContentMedia | 63 | 实体 | 内容媒体实体 |
| ProfileVO | 42 | VO | 个人主页视图 |
| ContentVO | 41 | VO | 内容列表视图（继承 ContentCacheDTO） |
| LogInVO | 40 | VO | 登录返回（待重命名为 LoginVO） |
| UploadResult | 31 | VO | 上传结果 |
| ContentDetailVO | 24 | VO | 内容详情视图（继承 ContentCacheDTO） |
| CommentVO | 20 | VO | 评论视图 |

#### DTO 包 — 数据传输对象

| 类 | 行数 | 用途 |
|----|------|------|
| LoginDTO | - | 登录请求 |
| RegisterDTO | - | 注册请求 |
| ChangePasswordDTO | - | 修改密码请求 |
| CommentDTO | - | 评论请求 |
| SearchDTO | - | 搜索请求 |
| PageResult | - | 分页结果 |
| GrabCouponRequest | - | 抢券请求 |

#### command 包 — 命令对象

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
| BusinessException | 19 | 基类 |
| ErrorCode | 11 | 错误码常量接口 |
| AuthException | 8 | 401 |
| ForbiddenException | 9 | 403 |
| NotFoundException | 7 | 404 |
| ConflictException | 7 | 409 |
| ParamException | 7 | 400 |
| ServerException | 5 | 500 |
| Test | 5 | 测试用（待删除） |

#### util 包 — 工具类

| 类 | 行数 | 职责 |
|----|------|------|
| MyConnectionPool | 109 | JDBC 连接池 |
| PasswordUtil | 58 | BCrypt 密码哈希 |
| JwtUtil | 39 | JWT 生成/校验 |
| MyRedisPool | 37 | Redis 连接池 |
| LogUtil | 33 | 日志工具 |
| StringUtil | 27 | 字符串校验 |
| ResultUtil | 26 | 响应格式构建 |
| CountRepairTool | 59 | 数据修复工具 |
| TimeUtil | 15 | 时间工具 |
| CheckUtil | 4 | 校验工具 |

---

## 五、数据库设计

### 5.1 连接配置

| 配置项 | 值 | 位置 |
|--------|-----|------|
| URL | jdbc:mysql://localhost:3306/TVDatabase?useSSL=false&serverTimezone=Asia/Shanghai | MyConnectionPool.java |
| 用户名 | root | MyConnectionPool.java |
| 密码 | MySQL | MyConnectionPool.java |
| 连接池初始大小 | 5 | MyConnectionPool.java |

### 5.2 数据库表

| 表名 | 说明 | 关键字段 |
|------|------|----------|
| users | 用户表 | id, username, hashed_password, phone, follow_count, follower_count |
| content | 内容表 | id, user_id, title, description, type, category_id, comment_count, like_count, is_deleted, create_time |
| comment | 评论表 | id, content_id, user_id, message, parent_id, like_count, is_deleted |
| follow | 关注关系表 | user_id, followed_user_id |
| content_like | 内容点赞表 | user_id, content_id |
| comment_like | 评论点赞表 | user_id, comment_id |
| content_media | 内容媒体表 | content_id, url, media_type(1=视频,2=图片,3=封面), sort |
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
| 地址 | localhost:6379 | MyRedisPool.java |
| 最大连接数 | 50 | MyRedisPool.java |
| 最大空闲 | 10 | MyRedisPool.java |
| 最小空闲 | 5 | MyRedisPool.java |

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
| POST | /user/changeUserName | 修改用户名 | ✓ |
| POST | /user/changePhone | 修改手机号 | ✓ |

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
| Service | 12 | 2,415 | 31.4% |
| DAO | 11 | 1,802 | 23.4% |
| Controller | 19 | 1,308 | 17.0% |
| POJO | 10 | 567 | 7.4% |
| Util | 10 | 407 | 5.3% |
| Command | 8 | 404 | 5.2% |
| DTO | 7 | 267 | 3.5% |
| IoC | 5 | 266 | 3.5% |
| Filter | 3 | 121 | 1.6% |
| Exception | 9 | 78 | 1.0% |
| 其他 | 1 | 60 | 0.8% |
| **合计** | **95** | **7,695** | **100%** |

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

| 文件/目录 | 原因 |
|-----------|------|
| TestServlet.java | 测试类混入主代码 |
| Test3.java | 测试类混入主代码 |
| RedisTest.java | 测试类混入主代码 |
| daotest.java | 测试类混入主代码 |
| exception/Test.java | 测试类混入主代码 |
| MenuService.java | 空壳 |
| ImageService.java | 空壳 |
| ssm_*/ | 空壳子模块 |
| web/ | 空壳目录 |

### 12.2 待重构

| 项目 | 目标 |
|------|------|
| DTO 包名 | DTO → dto（全小写） |
| LogInVO | 重命名为 LoginVO |
| pojo/DTO/command | 合并为 model 包 |
| ContentService | 拆分为 3-4 个类 |
| DAO 层 | 提取 BaseDao 基类 |
| 异常处理 | 统一使用 BusinessException |
| 配置管理 | 硬编码 → app.properties |

---

## 十三、更新日志

| 日期 | 版本 | 更新内容 |
|------|------|----------|
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
