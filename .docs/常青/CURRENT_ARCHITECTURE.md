# 当前系统架构地图

> 版本：2.8
> 最后更新：2026-09-12
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
├── .docs/                           # 项目文档（入口：.docs/INDEX.md）
│   ├── INDEX.md                     # 文档索引（唯一入口导航）
│   ├── 常青/                        # 启动必读，随代码更新
│   │   ├── CURRENT_ARCHITECTURE.md  # 本文件（系统地图）
│   │   └── BUSINESS_FLOW.md         # 业务流程文档
│   ├── 目标与任务/                   # 当前目标与任务（暂空）
│   ├── 说明书/                       # 按需读的参考手册
│   │   ├── TEST_AUTOMATION.md       # 测试自动化脚本说明（跑测试唯一权威入口）
│   │   ├── DATABASE.md              # 数据库建表语句（本机，不追踪）
│   │   └── AVAILABLE_TOOLS.md       # 工具路径清单（本机，不追踪）
│   ├── archive/                     # 历史存档（追踪可追溯，勿读）
│   │   ├── 目标与任务/               # 已完成：ARCHITECTURE_PLAN、CURRENT_TASK
│   │   ├── 说明书/                  # 已归档：TEST_GUIDE、ACCEPTANCE_CRITERIA（T1 文档收口）
│   │   └── 报告/                    # 项目分析报告、TEST_COVERAGE
│   └── temp/                        # 临时文档（永不追踪，可删）
│
├── src/
│   ├── main/
│   │   ├── java/com/itheima/        # Java 源码
│   │   ├── resources/               # 配置文件（待创建）
│   │   └── webapp/                  # Web 应用
│   │       ├── WEB-INF/web.xml      # Servlet 配置
│   │       ├── META-INF/context.xml # Tomcat 配置
│   │       ├── index.html           # 应用外壳（SPA 入口）
│   │       └── static/              # 前端资源（css/common.css + js/ 基础设施与视图模块）
│   │
│   └── test/
│       ├── java/com/itheima/        # JUnit 单元测试（按被测类同包随迁至各域 service 包，201 例）
│       └── python/                  # pytest 端到端脚本（103 例）
│
├── ssm_*/                           # 空壳子模块（待删除）
└── logs/                            # 运行日志
```

---

## 四、Java 包结构（B 改造后：8 业务域 + 基建不动）

### 4.1 包总览

```
com.itheima/
├── ioc/                    # 基建不动：手写 IoC 容器（366 行）
├── filter/                 # 基建不动：4 个 Servlet Filter（197 行）
├── util/                   # 基建不动：连接池/事务模板/JWT/密码/注入等（567 行）
├── exception/              # 基建不动：异常体系（326 行）
├── config/                 # 基建不动：AppConfig（146 行）
├── controller/             # 仅保留跨域基建：BaseServlet/BaseServletUtil/RequestParser/AppShutDownListener（261 行）
├── dao/                    # 仅保留跨域基建：ResultMap（88 行）
├── cache/                  # 基建（C 周期 T1 新增）：统一缓存基建——Redis 访问/JSON 序列化/统一 key 规范/单飞/三态空标记/写失败 DEL 降级（465 行）
│
├── user/                   # 用户/认证域（937 行）
├── content/                # 内容域：含首页/搜索/详情/关注流/主页读接口 + 共享缓存组件（3069 行）
├── follow/                 # 关注域（321 行）
├── like/                   # 点赞域（925 行）
├── comment/                # 评论域（555 行）
├── coupon/                 # 优惠券域（249 行）
├── upload/                 # 上传/媒体域（471 行）
└── admin/                  # 运维/审核域（737 行）
```

> 每域内部保留 `controller / service / dao / model` 分层子包，与既有技术层级命名一致（B 改造后主代码 114 类 / 9215 行；C 周期 T1 新增 cache 基建 7 类 / 465 行，主代码 121 类 / 9680 行，行数统计 2026-09-12）。
> 跨域依赖允许：feature 包间可互相 import（Java 无包环限制）；任何域不反向依赖基建包。

### 4.2 基建包（保持原位不动）

#### ioc 包 — 依赖注入容器

| 类 | 行数 | 职责 |
|----|------|------|
| IocContainer | 258 | 单例容器：构造器注入优先、字段注入兼容，管理 Bean 生命周期（@PostConstruct → Initializable.init；关闭时 Disposable.destroy / 反射 shutdown） |
| ClassScanner | 48 | 扫描 @Component 注解的类（`scan("com.itheima")` 整根递归，子包增减不影响 Bean 发现） |
| @Component | 11 | 标记为受管 Bean |
| @Inject | 11 | 字段依赖注入 |
| @InjectConstructor | 11 | 构造器依赖注入（带注解的构造器优先） |
| @PostConstruct | 11 | 初始化回调 |
| Initializable | 8 | 生命周期接口：依赖注入完成后调用 init() |
| Disposable | 8 | 生命周期接口：容器关闭时调用 destroy() |

#### filter 包 — 请求过滤器

| 类 | 行数 | 职责 | URL 匹配 |
|----|------|------|----------|
| ExceptionFilter | 53 | 全局异常处理（业务异常按 code/msg 输出，未知异常 500） | /* |
| EncodingFilter | 27 | UTF-8 编码 | /* |
| LoginFilter | 41 | 解析 JWT Token，设置 userId | /* |
| AuthFilter | 76 | 权限校验（登录 + /api/admin 管理员角色） | /* |

**执行顺序**：ExceptionFilter → EncodingFilter → LoginFilter → AuthFilter（web.xml 注册）

**AuthFilter 保护路径**：
- 前缀：`/api/upload`、`/api/admin`、`/follow`、`/like`、`/feed`
- 精确：`/comment/add`、`/comment/delete`、`/content/commentEnabled`、`/user/changePassword`、`/coupon/grab`、`/coupon/my`
- `/api/admin/*` 额外校验 `role == 1`，非管理员返回 403（每次请求查库）

#### exception 包 — 异常体系

| 类 | 行数 | 错误码 |
|----|------|--------|
| BusinessException | 29 | 基类（code + message，支持 ErrorCode 构造） |
| ErrorCode | 52 | 错误码枚举（code + 中文默认消息） |
| AuthException | 12 | 401 |
| ForbiddenException | 13 | 403 |
| NotFoundException | 11 | 404 |
| ConflictException | 11 | 409 |
| ParamException | 11 | 400 |
| ServerException | 7 | 500 |
| UserNotFoundException / PasswordIncorrectException / TokenExpiredException | 15 | 401 |
| AccessDeniedException | 15 | 403 |
| ContentNotFoundException / CommentNotFoundException | 15 | 404 |
| DuplicateLikeException / DuplicatePhoneException | 15 | 409 |
| InvalidPhoneException / InvalidPasswordException | 15 | 400 |
| DatabaseException / CacheException | 15 | 500 |

#### util 包 — 工具类

| 类 | 行数 | 职责 |
|----|------|------|
| MyConnectionPool | 138 | JDBC 连接池（上限 20、获取超时 5000ms、等待归还） |
| TransactionTemplate | 61 | 统一事务模板（取连接/提交/回滚/归还，业务异常原样重抛） |
| PasswordUtil | 58 | BCrypt 密码哈希 |
| JwtUtil | 40 | JWT 生成/校验 |
| MyRedisPool | 38 | Redis 连接池 |
| LogUtil | 54 | 日志工具 |
| RequestContext | 31 | 请求上下文路径（动态拼接媒体 URL） |
| StringUtil | 37 | 字符串校验 |
| ResultUtil | 26 | 响应格式构建 |
| CountRepairTool | 69 | 数据修复工具 |
| TimeUtil | 15 | 时间工具 |

#### controller 包（跨域基建，业务 Controller 已全部搬出）

| 类 | 行数 | 职责 |
|----|------|------|
| BaseServlet | 41 | 基类，IoC 注入 + JSON 响应 |
| BaseServletUtil | 49 | 静态工具，writeSuccess/writeError |
| RequestParser | 69 | JSON 请求体解析 |
| AppShutDownListener | 102 | 容器生命周期管理（@WebListener，统一关闭 IoC 容器） |

#### dao 包（跨域基建，业务 DAO 已全部搬出）

| 类 | 行数 | 职责 |
|----|------|------|
| ResultMap | 88 | ResultSet → 对象映射 |

> 规范：所有 DAO 方法只接收 `Connection`，不自行获取/释放连接；连接与事务统一由 Service 通过 TransactionTemplate 管理。

#### cache 包 — 统一缓存基建（C 周期 T1 新增，2026-09-12）

> 归属决策见 NEEDS 4.13：技术无关缓存基建放 `com.itheima.cache`，业务缓存类（内容/评论/点赞/关注）放各自业务域（各自域内定义 key 命名/TTL/失效逻辑，import 本基建）。
> 全部为纯新增（只增不改）：不改任何业务读路径；内部基于 `util/MyRedisPool` 但不改其方法签名；不引入 Spring/MyBatis；不引入 MQ（4.7）。

| 类 | 行数 | 职责 |
|----|------|------|
| CacheKeys | 58 | 统一 key 命名/生成规范（本周期唯一源，与六.6.2 一致）+ 空标记常量（EMPTY_MARKER_TTL_SECONDS=60s） |
| JacksonCodec | 53 | JSON 序列化（复用 jackson-databind + jsr310），异常抛 CacheException |
| RedisAccess | 51 | 统一 Redis 访问封装：`execute`/`executeVoid` 回调式取还连接（支持同连接 pipeline/MULTI），Jedis 异常包装为 CacheException |
| SingleFlight | 65 | 统一单飞组件（4.9）：ConcurrentHashMap+FutureTask，失败/成功均 remove（防缓存失败结果 + 防泄漏） |
| CacheStatus | 15 | 三态枚举：MISS / HIT_EMPTY / HIT_DATA |
| CacheResult | 39 | 三态读取结果载体（status + value，HIT_EMPTY 时 value=null） |
| CacheAside | 184 | 统一 Cache-Aside 封装：`read` 三态读 / `get` 带单飞回填 / `writeOrInvalidate`（写失败=DEL 自愈，写数据同时清空标记）/ `markEmpty` / `invalidate`，TTL ±10% 简单抖动，缓存失败一律降级不抛业务异常 |

> 测试：`src/test/java/com/itheima/cache/` 5 类 36 例（mockStatic MyRedisPool + mock Jedis，不碰真实 Redis），见九.9.2。

### 4.3 业务域包（每域 controller/service/dao/model 分层）

#### user 域 — `com.itheima.user`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | LoginController（80，/user/*） | 登录、注册、修改密码 |
| service | UserService（241） | 用户认证 + 管理员判定 |
| dao | UserDao（225） | users 用户 CRUD + 角色查询 |
| model | entity/User（89）、dto/LoginDTO（28）/RegisterDTO（41）/ChangePasswordDTO（35）、command/LoginCommand（63）/RegisterCommand（44）/ChangePasswordCommand（44）/LoginType（7）、vo/LoginVO（40） | 用户实体与请求/命令/响应对象 |

#### content 域 — `com.itheima.content`（含共享缓存组件）

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | ContentController（182，/content/*）、StartController（49，/start）、SearchController（95，/search/*）、FeedController（57，/feed）、ProfileController（70，/profile） | 内容管理 + 首页推荐 + 搜索 + 关注流 + 用户主页 |
| service | ContentService（445）、ContentCache（400，T2 新增：Redis 内容缓存=三态 Cache-Aside+索引）、CommentCache（173，T3 新增：Redis 评论缓存=三态 Cache-Aside+独立 TTL+空标记+显式失效）、ContentStatusFiller（90）、FeedService（74）、ProfileService（86） | 内容业务 + Redis 内容缓存 + Redis 评论树缓存 + 状态填充 + 关注流 + 主页 |
| dao | ContentDao（366）、ContentMediaDao（163） | content/content_media 数据访问（ContentLikeDao 按 like 域归属） |
| model | entity/ContentMedia（63）、cache/ContentCacheDTO（136）/CommentCacheDTO（110）、vo/ContentVO（42）/ContentDetailVO（26）/CommentVO（22）/ProfileVO（43）、dto/PageResult（62）/SearchDTO（51）、command/CommandConverter（139）/ContentType（16） | 内容模型 + 共享缓存 DTO + 共享 VO/DTO/转换器 |

> **共享组件归属**：ContentCache / CommentCache / ContentStatusFiller / ContentCacheDTO / CommentCacheDTO / PageResult / CommandConverter / ContentVO / ContentDetailVO / CommentVO 归本域，其它域 controller/service 跨域 import。

#### follow 域 — `com.itheima.follow`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | FollowController（91，/follow/*） | 关注/取关/关注列表/粉丝列表 |
| service | FollowService（142） | 关注业务（读路径委托 FollowCache；关注/取关 DB 提交后缓存双写） |
| service | FollowCache（484） | 关注关系 Redis 缓存（双 Set + 条件 MULTI 双写 + 失败双 DEL + 三态读 + 单飞；T5 新增） |
| dao | FollowDao（107） | follow 关注关系（仅 FollowService 业务校验与 FollowCache 回填 loader 使用） |
| model | — | 无专属 model |

> 注：FeedService/ProfileService/ContentStatusFiller 跨域 import `follow.service.FollowCache`（服务层），不再直连 FollowDao。守关注读路径走缓存、写路径 DB 提交后双写（NEEDS 4.10）。

#### like 域 — `com.itheima.like`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | LikeController（113，/like/*） | 点赞/取消点赞 |
| service | LikeService（205）、LikeCacheService（558） | 内容/评论点赞业务 + Redis 点赞缓存（计数/成员分离，读路径委托缓存类） |
| dao | ContentLikeDao（137）、CommentLikeDao（129） | content_like / comment_like 数据访问 |
| model | — | 无专属 model |

> 注：ContentService 删除作品级联清点赞时 import `like.ContentLikeDao`（反向跨域）。

#### comment 域 — `com.itheima.comment`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | CommentController（105，/comment/*） | 评论发表/查询/删除 |
| service | CommentService（197） | 评论业务（楼中楼：发表归一化主楼 + 软删除：用户自删/管理员删） |
| dao | CommentDao（179） | comment 评论 CRUD + 软删除（整楼/单条）+ 楼内回复计数 + 评论所属内容定位 |
| model | dto/CommentDTO（44）、command/CommentCommand（50） | 评论请求/命令（CommentVO 归 content 域） |

#### coupon 域 — `com.itheima.coupon`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | CouponController（72，/coupon/*） | 优惠券抢购/列表/我的 |
| service | CouponService（74） | 优惠券抢购 |
| dao | CouponDao（95） | coupon, coupon_order 优惠券 CRUD |
| model | dto/GrabCouponRequest（8） | 抢券请求 |

#### upload 域 — `com.itheima.upload`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | UploadController（205，/api/upload/*）、UploadType（84） | 上传视频/动态 + 作者换源；上传类型枚举 |
| service | FileUploadService（103） | 文件上传/按 URL 清理旧文件 |
| dao | — | 无专属 DAO |
| model | command/UploadCommand（48）、vo/UploadResult（31） | 上传命令/结果 |

#### admin 域 — `com.itheima.admin`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | MediaAdminController（74，/api/admin/media/*）、AdminContentController（73，/api/admin/content/*）、AdminCommentController（47，/api/admin/comment/*） | 媒体运维 + 内容审核下架 + 评论运维（仅管理员） |
| service | MediaAuditService（263） | 媒体完整性扫描与恢复 |
| dao | —（复用 content.ContentDao / comment.CommentDao，跨域 import） | 数据访问 |
| model | vo/AdminContentVO（56）、audit/MediaAuditItem（87）/MediaAuditResult（88）/RestoreResult（49） | 管理端清单 VO + 媒体审计/恢复结果 |

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
| 连接池上限/超时 | 20 / 5000ms（TASK-042 已接线） | app.properties (db.pool.*) / AppConfig |

### 5.2 数据库表

| 表名 | 说明 | 关键字段 |
|------|------|----------|
| users | 用户表 | id, username, hashed_password, phone, follow_count, follower_count, role（0=普通/1=管理员） |
| content | 内容表 | id, user_id, title, description, type, category_id, comment_count, like_count, comment_enabled, is_deleted, create_time, file_exists, last_verify_time |
| comment | 评论表 | id, content_id, user_id, message, parent_id, reply_to_user_id, like_count, is_deleted |
| follow | 关注关系表 | user_id, followed_user_id |
| content_like | 内容点赞表 | user_id, content_id |
| comment_like | 评论点赞表 | user_id, comment_id |
| content_media | 内容媒体表 | content_id, url, media_type(1=视频,2=图片,3=封面), sort, file_exists, last_verify_time |
| coupon | 优惠券表 | id, title, stock, begin_time, end_time |
| coupon_order | 优惠券领取表 | coupon_id, user_id, coupon_code（唯一索引） |

> `content.is_deleted` 语义：`0=正常 / 1=作者删除（A1，不可恢复）/ 2=管理员下架（A2，可恢复）`；前台可见性统一按 `is_deleted = 0` 过滤。

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

### 6.2 Key 设计（T1 定稿：统一 Redis 缓存层，唯一源见 com.itheima.cache.CacheKeys）

> 三态 Cache-Aside（NEEDS 4.3/4.4）：数据 key 存 JSON；另起 `empty:{dataKey}` 独立 String key 标记"已确认无数据"，TTL 60s（空标记短 TTL 自动过期，不依赖 Redis 空容器回收；废弃旧 `__placeholder__` hack）。缓存仅作加速器，任何缓存失败必须降级走 DB、不得导致业务失败（4.2）。

| Key 模式 | 类型 | 用途 |
|----------|------|------|
| content:{contentId} | String(JSON) | 内容详情缓存（Cache-Aside 数据 key，TTL 10min+抖动） |
| content:index:{type}:{category} | LIST\<contentId\> | 类型分区索引（4 key/内容：t,c / t,-1 / -1,c / -1,-1；新前序；T2 启用，启动全量重建+懒重建） |
| content:comments:{contentId} | String(JSON) | 内容评论树缓存（独立 TTL cache.comment.ttlMinutes=10min+抖动，与内容解耦；T3 启用） |
| empty:{dataKey} | String "1" | 空标记：已加载确认无数据（短 TTL 60s） |
| content:likeCount:{contentId} | String(int) | 内容点赞计数（高频读，计数/成员分离 4.6；T4 启用） |
| content:likeSet:{contentId} | Set\<userId\> | 内容点赞成员（低频"谁点过"查询，miss 允许穿透；T4 启用） |
| comment:likeCount:{commentId} | String(int) | 评论点赞计数（T4 启用） |
| comment:likeSet:{commentId} | Set\<userId\> | 评论点赞成员（T4 启用） |
| user:following:{userId} | Set\<followedUserId\> | 我关注了谁（4.10，MULTI 双写，失败双 DEL；T5 启用） |
| user:follower:{userId} | Set\<userId\> | 谁关注了我（4.10，MULTI 双写，失败双 DEL；T5 启用） |

> 旧 key 演进：原 `content:like:{id}` / `comment:like:{id}`（单 Set 兼容 SCARD 计数）已随 T4 停用，由本表计数/成员分离 key 取代（旧 key 仅退款前历史遗留在 Redis，TTL 过期自然回收）；本表为新缓存层规范，按任务逐行启用（当前已启用：content / content:index / content:comments / content:likeCount / content:likeSet / comment:likeCount / comment:likeSet / user:following / user:follower；空标记随行）。
>
> 定时全量刷新（旧 `ContentCacheManager.startScheduler` 10min `scheduleAtFixedRate`）已随旧类移除（O-6 拍板，T6）：内容/索引一致性由**启动全量重建 + 索引懒重建 + 业务显式失效（增删改/计数/门禁/隐藏恢复）+ Cache-Aside 读自愈（按 key TTL 过期回填）** 承担，不再有周期性全库重载（原 H2/H7 雪崩与 N+1 痛点）。

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
| POST | /api/upload/replace | 作者换源（替换媒体，含单图替换） | ✓ |
| POST | /content/update | 作者编辑标题/简介 | ✓ |
| POST | /content/mediaDelete | 作者删除单条媒体（仅图文图片） | ✓ |
| POST | /content/delete | 作者删除自己作品（软删除 + 级联清理评论/点赞/媒体） | ✓ |

### 7.3 社交模块

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| POST | /like/content/add | 点赞内容 | ✓ |
| POST | /like/content/remove | 取消点赞 | ✓ |
| POST | /like/comment/add | 点赞评论 | ✓ |
| POST | /like/comment/remove | 取消点赞 | ✓ |
| GET | /like/content/status | 点赞状态 | ✓ |
| GET | /like/content/count | 点赞数 | ✓ |
| GET | /like/comment/status | 点赞状态 | ✓ |
| GET | /like/comment/count | 点赞数 | ✓ |
| POST | /comment/add | 发表评论 | ✓ |
| GET | /comment/show | 查看评论 | ✗ |
| POST | /comment/delete | 删除评论（软删除，仅自己） | ✓ |
| POST | /content/commentEnabled | 作者开关自己作品的评论区（0=关/1=开） | ✓ |
| POST | /follow/add | 关注 | ✓ |
| POST | /follow/remove | 取关 | ✓ |
| GET | /follow/following | 关注列表 | ✓ |
| GET | /follow/followers | 粉丝列表 | ✓ |
| GET | /profile | 用户主页 | ✗ |

### 7.4 优惠券模块

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| GET | /coupon/list | 可用优惠券 | ✗ |
| GET | /coupon/my | 我的优惠券 | ✓ |
| POST | /coupon/grab | 抢购优惠券 | ✓ |

### 7.5 媒体运维模块（仅管理员，AuthFilter 校验 role==1）

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| GET | /api/admin/media/me | 管理员身份检查（供运维页隐藏入口） | ✓ |
| GET | /api/admin/media/list | 扫描并返回媒体资源状态 | ✓ |
| POST | /api/admin/media/scan | 重新扫描并回写状态 | ✓ |
| POST | /api/admin/media/restore | 按数据库原文件名重新上传写回 | ✓ |
| POST | /api/admin/comment/delete | 管理员删除任意评论（软删除） | ✓ |

### 7.6 内容审核模块（仅管理员，A2，AuthFilter 校验 role==1）

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| GET | /api/admin/content/list | 管理端内容清单（含正常与已下架，不含已删除） | ✓ |
| POST | /api/admin/content/hide | 下架内容（is_deleted 0→2，仅改状态不动关联数据） | ✓ |
| POST | /api/admin/content/unhide | 恢复内容（is_deleted 2→0） | ✓ |

---

## 八、前端页面

> 2026-08-14 起由「9 个独立 html（移动端优先）」重构为**单页应用（SPA）**：只保留 1 个 `index.html` 外壳 + 原生 hash 路由 + `static/js/views/` 视图模块，纯原生 HTML/CSS/JS、无构建工具，本轮不改后端。

### 8.1 文件结构

```
src/main/webapp/
├── index.html                 # 应用外壳：顶部导航 + 左抽屉 + <main id="app">
└── static/
    ├── css/common.css         # reset + 变量 + 布局 + 全部公共组件
    └── js/
        ├── main.js            # 渲染外壳 + 注册路由 + 导航高亮
        ├── router.js          # hash 路由（视图 mount/unmount 生命周期）
        ├── api.js             # request()：token 头 + {code,msg,data} 解包 + 401 跳登录
        ├── auth.js            # token/username/userId 存取、JWT sub 解码
        ├── utils.js           # 工具 + createVideoCard（字段降级收敛）
        ├── editWork.js        # 编辑作品弹层（作者改标题/简介 + 替换/删除媒体，创作中心与详情共用）
        └── views/
            ├── home.js        # #/            首页（推荐流 + 换一换）
            ├── follow.js      # #/follow      关注流（/feed 分页）
            ├── detail.js      # #/video/:id   详情（播放器 + 楼中楼评论 + 相关推荐）
            ├── search.js      # #/search?kw=  搜索
            ├── user.js        # #/user/:id    个人主页（本人/他人合一）
            ├── publish.js     # #/publish     创作中心（我的投稿 + 投稿上传）
            ├── login.js       # #/login       登录/注册
            ├── coupon.js      # #/coupon      优惠券中心
            └── admin.js       # #/admin       媒体运维 + 删评论工具 + 内容下架管理（仅管理员）
```

### 8.2 路由表

| 路由 | 视图 | 需要登录 |
|------|------|----------|
| `#/` | home | ✗（关注流需登录） |
| `#/follow` | follow | ✓ |
| `#/video/:id` | detail | ✗（点赞/评论/关注需登录） |
| `#/search?kw=…` | search | ✗ |
| `#/user/:id` | user | ✗（本人操作需登录） |
| `#/publish` | publish | ✓ |
| `#/login` | login | ✗ |
| `#/coupon` | coupon | ✗（抢券需登录） |
| `#/admin` | admin | ✓（且需管理员） |

> 说明：选 hash 路由（`#/…`）而非 History API，纯 Tomcat 下刷新无需服务端重写；`space.html`/`profile.html` 合并为 `user.js`（用「是否本人」决定展示与操作）。分区导航收纳在顶部「分类」下拉（`#/?cat=<id>`），首页只留类型筛选 + 内容网格。

---

## 九、测试文件

> 2026-09-01 起测试体系 = **JUnit（服务层单元基准，113 例）+ pytest（端到端，103 例）** 两套；`.http` 文件已全部移除（不纳入测试体系、调试价值有限，见 TEST_AUTOMATION.md 〇节）。

### 9.1 pytest 自动化用例（tools/run_tests.py 驱动）

| 文件 | 用例数 | 覆盖模块 |
|------|--------|----------|
| test_smoke.py / test_consistency.py / test_boundary.py | 35 | 冒烟/一致性/边界（既有） |
| test_comment_delete.py | 6 | 评论软删除（阶段一新增：自删主楼/回复、403/404/401、管理员删） |
| test_admin.py | 11（1 条件跳过） | 管理员权限 401/403/200、媒体扫描/恢复（阶段八） |
| test_edit_work.py | 21 | 编辑作品：换源（含图文单张替换）/删图/改文案的 200/403/401/400/404 + 文件落盘/旧文件删除（阶段三） |
| test_delete_content.py | 7 | 删除作品：作者删视频/图文后详情 404 + 主页不展示 + 物理文件删除；非作者/未登录/不存在/已删/缺参（阶段四） |
| test_hide_content.py | 15 | 内容审核下架/恢复：管理员下架视频/图文后详情 404 + 主页不列出 + 恢复后数据完好；hide/unhide/list 的 401/403/404/409/400/list 结构（阶段五） |

> T2 媒体隔离（2026-09-06）：18080 测试实例媒体落盘（`UPLOAD_PATH` env 注入 `media-test`）与 `/upload` 挂载（run_tests 对部署的 ROOT.war 内 context.xml 做 zip 补丁改写 base）同指 `D:\data\projects\VideoPlatform\media-test`，`AppShutDownListener` 启动强校验保持两者一致；生产 8080 实例仍用 app.properties upload.path 不受影响。旧测试媒体在 fresh-start 时整目录**移动式回收**至 `test_trash`（只移不删，用户手动清理）后重建白名单目录 media-test（白名单硬编码于 run_tests.py，防误移动）。工具链媒体根按库判定（tools/media_paths.py）：测试库 → media-test，生产 → upload.path。

### 9.2 JUnit 单元测试（src/test/java，与被测类同包随迁）

| 文件（包） | 用例数 | 覆盖模块 |
|------|--------|----------|
| user/service/UserServiceTest | 23 | 登录/注册/改密/改资料/isAdmin |
| content/service/ContentServiceTest | 48 | 搜索/详情/评论查询/发布/评论区开关/编辑作品（换源/删图/改文案）/删除作品/内容审核下架恢复 |
| content/service/ContentCacheTest | 12 | Redis 内容缓存：三态 loader 构建（含媒体 URL）/DB 无媒体损坏降级/索引读取与懒重建/写路径失效契约/init 重建不 crash/失效方法/VO 复制 |
| content/service/CommentCacheTest | 11 | Redis 评论缓存：三态 loader（树构建/deep-chain 归一化/无评论 null/DB 降级）/invalidateComments 显式失效/评论点赞定位失效/collectCommentIds 展平 |
| content/service/FeedServiceTest | 8 | 关注动态流 |
| content/service/ProfileServiceTest | 12 | 用户主页 |
| like/service/LikeServiceTest | 16 | 点赞/取消/读路径委托缓存类/空输入空 map |
| like/service/LikeCacheServiceTest | 20 | Redis 点赞缓存：计数/成员分离三态+单飞回填+空标记+写失败失效+降级 +批量 pipeline+DB 兜底 +delete 失效 |
| comment/service/CommentServiceTest | 16 | 评论归属/楼中楼归一化/软删除（自删+管理员删）/缓存更新 |
| follow/service/FollowServiceTest | 15 | 关注/取关/列表（读路径委托 FollowCache；写路径 DB 提交后缓存双写） |
| follow/service/FollowCacheTest | 26 | Redis 关注缓存：双 Set 三态+单飞回填+空标记（set 存在守卫防并发覆盖）/批量 pipeline+DB 兜底+best-effort 回填/列表 smembers 排序/条件 MULTI 双写+失败双 DEL+降级 |
| coupon/service/CouponServiceTest | 11 | 抢券/幂等/库存 |
| upload/service/FileUploadServiceTest | 9 | 上传校验/清理旧文件 |
| admin/service/MediaAuditServiceTest | 14 | 媒体扫描/恢复 |
| util/MyConnectionPoolTest | 4 | 满池超时/归还重取/失效移除/关闭后拒绝 |
| cache/CacheKeysTest | 8 | 统一 key 生成格式、empty 前缀、空标记常量 |
| cache/JacksonCodecTest | 4 | DTO 往返、null 处理、TypeReference 泛型、非法 JSON 抛 CacheException |
| cache/RedisAccessTest | 4 | execute/executeVoid 取还连接、异常包装 CacheException（含连接获取失败） |
| cache/SingleFlightTest | 4 | 并发同 key 只 load 一次、失败/成功 remove、不同 key 独立 |
| cache/CacheAsideTest | 16 | 三态 read、Cache-Aside get 命中/回填/空标记、降级不写回、写失败 DEL、清空标记防假空、markEmpty/invalidate best-effort |
| **合计** | **288** | - |

> 注：`com.itheima.tools.CouponAdmin` 属 tools 测试脚本目录（非测试类，package 保留 `com.itheima.tools`，仅 import java.*，无主代码引用）；`util/MyConnectionPoolTest` 被测类未动（基建），测试文件留在 util 包不迁。
> 用例数取自 `stage8-target/surefire-reports`（2026-09-12 实测，`mvn clean test` 全绿 288 例 = T5 末尾 293 − 删除旧 ContentCacheManagerLifecycleTest 5 例（旧类已随 T6 移除）；ContentServiceTest 断言随新删除/下架路径（likeService.deleteContentLike）调整）。

> 构建输出：沙箱内 Maven 通过 `-Dstage8.buildDir` 指向 `D:\data\projects\VideoPlatform\stone\temp\stage8-target`（pom 默认 `./target`），原因是沙箱内 javac 无法把 worktree `target/classes` 作为 classpath（报"程序包不存在"）。
> 离线仓库：新增测试依赖（junit/mockito/bytebuddy/surefire 等）的 `_remote.repositories` 已补 `>aliyun=` 来源行（只追加不删除），默认 aliyun 镜像下可离线解析。

---

## 十、代码统计（B 改造后：按业务域 + 基建）

### 10.1 业务域（8 域）

| 域 | 文件数 | 代码行数 | 占比 |
|------|--------|----------|------|
| content | 24 | 3,103 | 29.7% |
| user | 12 | 937 | 9.0% |
| like | 5 | 1,142 | 10.9% |
| admin | 8 | 737 | 7.1% |
| comment | 5 | 555 | 5.3% |
| upload | 5 | 471 | 4.5% |
| follow | 4 | 824 | 7.9% |
| coupon | 4 | 249 | 2.4% |
| **业务域小计** | **67** | **8,018** | **76.8%** |

### 10.2 基建（不动 + cache 新增）

| 包 | 文件数 | 代码行数 | 占比 |
|------|--------|----------|------|
| util | 11 | 567 | 5.4% |
| ioc | 8 | 366 | 3.5% |
| exception | 20 | 326 | 3.1% |
| controller（基建 4 类） | 4 | 261 | 2.5% |
| filter | 4 | 197 | 1.9% |
| config | 1 | 150 | 1.4% |
| dao（基建 ResultMap） | 1 | 88 | 0.8% |
| cache（C 周期 T1 新增） | 7 | 465 | 4.5% |
| **基建小计** | **56** | **2,420** | **23.2%** |
| **合计** | **123** | **10,438** | **100%** |

> 行数统计 2026-09-12（B 改造后 + C 周期 T1 cache 基建 + T2 ContentCache 内容域增量，与第四章包清单一致；行数为快照，以实际代码为准）。

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

## 十二、更新日志

| 日期 | 版本 | 更新内容 |
|------|------|----------|
| 2026-09-12 | 2.9 | **C 缓存改造 T6 收尾（refactor(cache-06)）**：旧 `ContentCacheManager`（内存 HashMap 缓存/索引/推荐列表/定时刷新）整体移除，删除 ContentService 三处旧调用（deleteContent/hideContent 的 removeContent、unhideContent 的 refreshContent），其"旧格式点赞 key 清理"副作用迁入 `LikeService.deleteContentLike`（新委托，ContentService 在 DB 提交后显式调用，失效 count+set+empty 三 key）；死配置 `AppConfig.getContentRefreshMinutes` + app.properties `cache.content.refreshMinutes` 删除；**定时全量刷新去留（O-6）拍板=移除**，一致性由启动全量重建 + 索引懒重建 + 业务显式失效 + Cache-Aside 读自愈承担；ContentCacheManagerLifecycleTest 删除（旧类已无）、ContentServiceTest 断言随新路径调整；`mvn clean test` 干净构建无残留（默认 surefire 284 + pool 4 = 288 例全绿，stage8-target 已无 ContentCacheManager 字节码）+ pytest all 124 passed；本文件 4.3（content 域）/6.2（Redis 设计）/9.2（JUnit 表）/12 同步；BUSINESS_FLOW 3.1/4.x 缓存失效流程同步 |
| 2026-09-12 | 2.8 | **C 缓存改造 T3 评论缓存重制（refactor(cache-03)）**：新建 `com.itheima.content.service.CommentCache`（拆 ContentCacheManager 评论职责）：评论树走 T1 CacheAside 三态/空标记 60s/独立 TTL（新增 cache.comment.ttlMinutes=10+抖动）/写失败 DEL；评论增/删/点赞 = 失效 `content:comments:{id}` 读自愈（4.5 业务显式失效，替代旧内存树原地增删，消除 H1），评论点赞经 CommentDao 新增轻查询 getContentIdByCommentId 定位所属内容后失效；getCommentsForContent 增加 dto==null 短路（读评论前先确认 content 存在，防隐藏内容评论泄漏）；删除/下架内容级联失效评论 key（deleteContent/hideContent）；ContentCacheManager 删除评论字段/方法（init 不再全量加载评论，缓解 H7），内存残留 T6 清理；业务逻辑（楼中楼/软删/开关门禁）/@WebServlet 零改动；JUnit 新增 CommentCacheTest 11 例 + 评论短路用例 1 例（tv.py test junit 261 例全绿）+ pytest all 124 passed；本文件 4.3/6.2/9.2/10.1/10.2 同步；BUSINESS_FLOW 3.1/4.1.2/4.2.x 评论缓存失效流程同步 |
| 2026-09-12 | 2.7 | **C 缓存改造 T2 内容缓存重制（refactor(cache-02)）**：新建 `com.itheima.content.service.ContentCache`（Redis 内容缓存，拆 ContentCacheManager 内容职责）：内容详情走 T1 CacheAside 三态/空标记 60s/单飞/写失败 DEL，类型分区索引迁为 Redis LIST `content:index:{t}:{c}`（4 key/内容，启动 init 全量重建 + 索引缺失单飞懒重建），点赞/评论数/评论区开关变更 = 失效内容 key 读自愈（DB 列为源真理）；业务读路径（/start /search /detail /feed /profile）全切新缓存，业务逻辑/@WebServlet 零改动；H3 修复：addVideo/addPost 的 Redis 缓存写入移出 DB 事务；评论树内存缓存暂留 ContentCacheManager（T3 迁出），旧 updateCacheAfterAdd/removeContent/refreshContent 仅保留评论树/旧点赞 key 遗产副作用（代码注释标注 T3/T4 清理）；JUnit 新增 ContentCacheTest 12 例（tv.py test junit 252 例全绿）+ pytest all 124 passed；本文件 4.3/6.2/9.2/10.1/10.2 同步；BUSINESS_FLOW 3.1 缓存机制重写 |
| 2026-09-12 | 2.6 | **C 缓存改造 T1 基建完成（refactor(cache-01)）**：新建 `com.itheima.cache` 基建包（7 类，纯新增零改动）：CacheKeys（统一 key 规范定稿 + 空标记 60s 常量）/ JacksonCodec（JSON 序列化）/ RedisAccess（回调式取还连接，支持同连接 pipeline/MULTI，异常包 CacheException）/ SingleFlight（单飞，失败/成功均 remove）/ CacheStatus+CacheResult（三态）/ CacheAside（三态 Cache-Aside 读 + 空标记独立 key + 写失败=DEL 自愈降级 + TTL ±10% 抖动）；KEY 规范：内容/评论/点赞计数与成员分离/关注双 Set/`empty:` 空标记；本任务不改任何业务读路径、不改 MyRedisPool、不引入 Spring/MyBatis/MQ；JUnit 新增 5 类 36 例（tv.py test junit 240 例全绿）；本文件 4.1/4.2/6.2/9.2/10.1/10.2 同步 |
| 2026-09-11 | 2.5 | **B-feature package 改造完成（T1~T9，8 域迁移 + 收尾）**：业务 controller/service/dao/model 全部按 8 业务域重组（user/content/follow/like/comment/coupon/upload/admin），每域保留分层子包；共享组件（ContentCacheManager/ContentStatusFiller/ContentCacheDTO/CommentCacheDTO/PageResult/CommandConverter/Content相关VO）归 content 域；基建（ioc/filter/util/exception/config + controller 的 BaseServlet/BaseServletUtil/RequestParser/AppShutDownListener + dao 的 ResultMap）保持原位不动；web.xml / @WebServlet URL / IoC 扫描（`scan("com.itheima")`）/ 前端 / pytest 一行不改；JUnit 同包随迁（201 例全绿）；主代码 96 类 → 114 类（含 annotation 子包 4 注解类，行数 6,504 → 9,215 口径含基建）；本章第四章（包结构）、第九章（JUnit 表）、第十章（代码统计）同步重写 |
| 2026-08-29 | 2.4 | 阶段五完成（A2 内容审核下架）：content.is_deleted 语义扩展为 0正常/1作者删除/2管理员下架（复用字段，无 DDL）；新增 AdminContentController（GET /api/admin/content/list、POST /api/admin/content/hide、POST /api/admin/content/unhide，AuthFilter /api/admin/* role==1 保护）；ContentDao 新增 getContentStatus/updateContentDeletedState/findContentForAdmin；ContentService 新增 listContentForAdmin/hideContent/unhideContent（下架剔除缓存、恢复回填缓存）；前端 admin.js 新增「内容下架管理（审核）」区块；BUSINESS_FLOW 新增 3.10。测试用例待后续补充 |
| 2026-08-28 | 2.3 | 评论楼中楼回复增强：comment 表新增 reply_to_user_id（楼中楼 @ 引用）；CommentCacheDTO/ResultMap/CommentDao/CommentService 贯通该字段（回复楼内回复时上溯挂主楼并记录被回复作者）；详情页楼内回复增加回复按钮 + 「回复 @xxx」展示；commentTest/pytest/单测同步 |
| 2026-08-28 | 2.2 | 阶段二完成（C2 作者开关评论区）：content 表新增 comment_enabled；ContentCacheDTO/CacheManager 贯通该字段；新增 ContentController（POST /content/commentEnabled，作者所有权校验）；AuthFilter 新增精确保护；评论发表/查询按开关门禁（add 409 / show 空）；创作中心卡片开关按钮 + 详情页评论区门禁展示 |
| 2026-08-18 | 2.1 | 目录结构更新：.docs/ 由平铺改为分层（常青/目标与任务/说明书/archive/temp），本文件随结构归档至 .docs/常青/，入口改为 .docs/INDEX.md |
| 2026-08-14 | 2.0 | 前端重构（阶段九）：9 个独立 html 改为单页应用（SPA）——只留 index.html 外壳 + 原生 hash 路由 + static/js/views 视图模块（首页/关注流/详情/搜索/用户主页/创作中心/登录/券包/媒体运维），纯原生 HTML/CSS/JS、无构建工具，后端不动；新增首页分类下拉 + 换一换、关注流独立 #/follow、详情右侧相关推荐（/start 兜底）、创作中心「我的投稿」列表 |
| 2026-08-10 | 1.9 | 阶段七完成：IocContainer 支持 @InjectConstructor 构造器注入（11 个服务类迁移，字段注入保留兼容）；新增 Initializable/Disposable 生命周期接口并接入容器（ContentCacheManager 迁移，AppShutDownListener 改走容器统一关闭，反射 shutdown 兼容保留）；MyConnectionPool 增加上限 20 与获取超时 5000ms（满池等待、超时抛 SQLException、失效连接从 allConnections 移除）；35/35 pytest 通过 |
| 2026-08-10 | 1.8 | 阶段六完成：SQL 注入与资源所有权审计记录（全参数化、无注入点）；users 表新增 role 列（0=普通/1=管理员）；UserDao.getUserRole + UserService.isAdmin；AuthFilter 对 /api/admin/* 校验管理员角色（每次请求查库）；MediaAdminController 新增 GET /api/admin/media/me；recovery.html 区分 403 并隐藏非管理员操作；新增 tools/admin.py（--list/--promote/--demote）；迁移前已备份 |
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
