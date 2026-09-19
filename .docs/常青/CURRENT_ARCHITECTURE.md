# 当前系统架构地图

> 版本：3.2（2026-09-19 T7：关注/粉丝列表分页——成员 key Set→ZSet 有序化 + 基建新增 ZSetCache 按序窗口读 + `/follow/following|followers` 可选 page/pageSize（缺省仍全量）+ 前端 user.js 分页）
> 最后更新：2026-09-19
> 维护说明：每次架构改动后必须更新本文档——只改**被改动影响的事实章节** + 头部「最后更新」日期与版本号；**不设变更记录**（变更以 git 提交历史为准，message 规范见 `.docs/说明书/COMMIT_CONVENTION.md`，决策明细落 `目标与任务/*/NEXT_CYCLE_NEEDS.md` 4.0 与 TASKS 执行回写）。

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
│   ├── 目标与任务/                   # 当前周期目标与任务（周期文档 + UNPLANNED_ISSUES 常驻）
│   ├── 说明书/                       # 按需读的参考手册（TEST_AUTOMATION / COMMIT_CONVENTION / TEST_SEED / DATABASE / AVAILABLE_TOOLS）
│   ├── archive/                     # 历史存档（追踪可追溯，勿读）
│   │   ├── 目标与任务/               # 已完成周期（ARCHITECTURE_PLAN、CURRENT_TASK 等）
│   │   ├── 说明书/                  # 已归档：TEST_GUIDE、ACCEPTANCE_CRITERIA（T1 文档收口）
│   │   └── 报告/                    # 项目分析报告、TEST_COVERAGE、覆盖率地图
│   └── temp/                        # 临时文档（永不追踪，可删）
│
├── src/
│   ├── main/
│   │   ├── java/com/itheima/        # Java 源码
│   │   ├── resources/               # 配置文件（app.properties）
│   │   └── webapp/                  # Web 应用
│   │       ├── WEB-INF/web.xml      # Servlet 配置
│   │       ├── META-INF/context.xml # Tomcat 配置
│   │       ├── index.html           # 应用外壳（SPA 入口）
│   │       └── static/              # 前端资源（css/common.css + js/ 基础设施与视图模块）
│   │
│   └── test/
│       ├── java/com/itheima/        # JUnit 单元测试（按被测类同包随迁至各域 service 包）
│       └── python/                  # pytest 端到端脚本
│
├── ssm_*/                           # 空壳子模块（待删除）
├── logs/                            # 运行日志
└── tools/                           # 工具脚本（统一入口 tools/tv.py）
```

---

## 四、Java 包结构（B 改造后：8 业务域 + 基建不动）

### 4.1 包总览

```
com.itheima/
├── ioc/                    # 基建不动：手写 IoC 容器
├── filter/                 # 基建不动：4 个 Servlet Filter
├── util/                   # 基建不动：连接池/事务模板/JWT/密码/注入等
├── exception/              # 基建不动：异常体系
├── config/                 # 基建不动：AppConfig
├── controller/             # 仅保留跨域基建：BaseServlet/BaseServletUtil/RequestParser/AppShutDownListener
├── dao/                    # 仅保留跨域基建：ResultMap
├── cache/                  # 统一缓存基建：Redis 访问+熔断/JSON 序列化/统一 key 规范/单飞/三态空标记/写失败 DEL 降级/SetCache/ZSetCache
│
├── user/                   # 用户/认证域
├── content/                # 内容域：含首页/搜索/详情/关注流/主页读接口 + 共享缓存组件
├── follow/                 # 关注域
├── like/                   # 点赞域
├── comment/                # 评论域
├── coupon/                 # 优惠券域
├── upload/                 # 上传/媒体域
└── admin/                  # 运维/审核域
```

> 每域内部保留 `controller / service / dao / model` 分层子包，与既有技术层级命名一致（行数统计为 2026-09-12 快照，以实际代码为准）。
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
- 精确：`/comment/add`、`/comment/delete`、`/content/commentEnabled`、`/user/changePassword`、`/user/changeUserName`、`/coupon/grab`、`/coupon/my`
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
| MyRedisPool | 49 | Redis 连接池（显式 connect/so 超时 + maxWait，8 参 JedisPool 构造器） |
| LogUtil | 61 | 日志工具（初始化先清空 root 既有 handler 再挂 Console+File，自身零 System.out/err） |
| RequestContext | 31 | 请求上下文路径（动态拼接媒体 URL） |
| StringUtil | 37 | 字符串校验 |
| ResultUtil | 26 | 响应格式构建 |
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

#### cache 包 — 统一缓存基建

> 归属：技术无关缓存基建放 `com.itheima.cache`，业务缓存类（内容/评论/点赞/关注）放各自业务域（各自域内定义 key 命名/TTL/失效逻辑，import 本基建）。内部基于 `util/MyRedisPool` 但不改其方法签名；不引入 Spring/MyBatis/MQ。

| 类 | 行数 | 职责 |
|----|------|------|
| CacheKeys | 143 | 统一 key 命名/生成规范（唯一源）+ 空标记常量（EMPTY_MARKER_TTL_SECONDS=60s）+ `domainOf` 统计域解析（长前缀优先）+ `contentIndex(type, categoryId)` 索引 key 生成（前缀 `CONTENT_INDEX_PREFIX`，生成/解析/匹配三处同源）+ 计数/成员/关注各 key 工厂 |
| CacheDomain | 26 | 统计分域枚举：CONTENT/COMMENT/LIKE/FOLLOW/OTHER（LIKE 域含用户维度点赞成员 key） |
| CacheStats | 131 | 观测统计组件：六类事件（HIT_DATA/HIT_EMPTY/MISS/LOAD/DEGRADE/WRITE_FAIL）AtomicLong 计数 + 分域分桶 + 惰性日志（每 N=1000 输出摘要），record 异常吞掉不影响主链路；DEGRADE=本次读未命中缓存、走 DB 兜底次数（含熔断快速失败） |
| JacksonCodec | 53 | JSON 序列化（jackson-databind + jsr310），异常抛 CacheException；忽略未知字段（旧缓存 JSON 兼容，DTO 删/改名后仍可反序列化）、日期 ISO-8601（WRITE_DATES_AS_TIMESTAMPS 关闭） |
| RedisAccess | 91 | 统一 Redis 访问封装：`execute`/`executeVoid` 回调式取还连接（支持同连接 pipeline/MULTI），Jedis 异常包装为 CacheException；唯一出入口接全局熔断（tryAcquire 拒绝即快速失败、finally 按成败回填） |
| RedisCircuitBreaker | 141 | 全局 Redis 熔断器：CLOSED→OPEN（连续失败 ≥5，可配）→（冷却 10s 期满唯一探针）HALF_OPEN→探针成功 CLOSED / 失败重开；AtomicInteger CAS 无锁，状态迁移打日志 |
| SingleFlight | 65 | 统一单飞组件：ConcurrentHashMap+FutureTask，失败/成功均 remove（防缓存失败结果 + 防泄漏）；降级读路径与 miss 回填共用同一 key 空间 |
| CacheStatus | 15 | 三态枚举：MISS / HIT_EMPTY / HIT_DATA |
| CacheResult | 39 | 三态读取结果载体（status + value，HIT_EMPTY 时 value=null） |
| CacheAside | 575 | 统一 Cache-Aside 封装：`read` 三态读 / `get` 带单飞回填 / `getBatch` 批量读（4 参与 5 参批量装载重载）/ `writeOrInvalidate`（写失败=DEL 自愈，写数据同时清空标记）/ `markEmpty`（存在守卫）/ `invalidate`；TTL ±10% 抖动；读路径 pipeline 化（EXISTS 空标记+GET 一趟往返）；命中滑动续期（空标记从不续期）；降级读与 miss 共用单飞、仅装载不写回；loader 抛 DatabaseException 视为加载失败——不写空标记、不 DEL 数据 key |
| SetCache | 393 | 原生 Set 缓存基建：单成员三态 `isMember` / 全量 `getMembers`（不排序，需确定性顺序的调用方自包装）/ 批量判定（`batchIsMember` 单 set 多成员、`batchKeysIsMember` 多 set 单成员）/ `writeSet` 回填（空→`cacheAside.markEmpty` 含存在守卫）/ `loadViaSingleFlight` 降级装载；探针续期精确 TTL 无抖动、空标记不续；批量 DB 答案失败上抛、回填 best-effort。**第六期 T7 起生产调用方 = like 域（`user:likeSet` / `user:commentLikeSet`）**；follow 域已迁 ZSetCache |
| ZSetCache | 403 | 有序集合（ZSet）缓存基建（T7 新增，A1「缓存有序结构」落点）：命令层 ZSCORE/ZRANGE/ZADD，**score = 成员自身数值**（故 ZRANGE 天然按成员数值升序）；API 与 SetCache 同构（`isMember` / `getMembers` / `batchIsMember` / `writeZSet` 回填（空→`markEmpty` 含存在守卫）/ `loadViaSingleFlight` 降级不写回）+ **新增按序窗口读 `getWindow(key, offset, count)`**——一趟 pipeline 取 `ZRANGE[start,stop]` + `ZCARD`，返回 `Window{ids,total}`（total 与页同源）；hit / miss（回填后重读）/ 降级（全量装载后按 score 口径升序切片）三路径**同序**；探针续期精确 TTL、空标记不续 |

> 测试：`src/test/java/com/itheima/cache/` 9 类单测（mockStatic MyRedisPool + mock Jedis，不碰真实 Redis），用例清单以 `surefire-reports` 为准（见九节指针）。

### 4.3 业务域包（每域 controller/service/dao/model 分层）

#### user 域 — `com.itheima.user`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | LoginController（80，/user/*） | 登录、注册、修改密码/用户名 |
| service | UserService（241） | 用户认证 + 管理员判定 + 改名后级联失效内容缓存（注入 ContentCache） |
| dao | UserDao（258） | users 用户 CRUD + 角色查询（`findUsersByIds` T7 起带 `ORDER BY id`：关注/粉丝列表顺序由此保证，唯一调用方 FollowService） |
| model | entity/User（89）、dto/LoginDTO（28）/RegisterDTO（41）/ChangePasswordDTO（35）/ChangeUserNameDTO（13）、command/LoginCommand（63）/RegisterCommand（44）/ChangePasswordCommand（44）/LoginType（7）、vo/LoginVO（40） | 用户实体与请求/命令/响应对象 |

#### content 域 — `com.itheima.content`（含共享缓存组件）

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | ContentController（182，/content/*）、StartController（49，/start）、SearchController（95，/search/*）、FeedController（57，/feed）、ProfileController（70，/profile） | 内容管理 + 首页推荐 + 搜索 + 关注流 + 用户主页 |
| service | ContentService（445）、ContentCache（656：Redis 内容缓存=三态 Cache-Aside+索引；loader 失败抛 DatabaseException 不污染空标记；`invalidateAuthorContentKeys` 改名级联失效；`getContentsBatch` miss/降级装载走 `loadContentsFromDb` 一趟事务两查）、CommentCache（176：Redis 评论缓存=三态 Cache-Aside+独立 TTL+空标记+显式失效；loader 失败抛 DatabaseException）、ContentStatusFiller（90）、FeedService（108）、ProfileService（118） | 内容业务 + Redis 内容缓存 + Redis 评论树缓存 + 状态填充 + 关注流 + 主页（Feed/Profile 的缓存批量读在 DB 事务外执行） |
| dao | ContentDao（384）、ContentMediaDao（168） | content/content_media 数据访问（ContentLikeDao 按 like 域归属）；`findContentsByIds` 批量 IN 查询（供批量缓存装载，列与 findContent 同源） |
| model | entity/ContentMedia（63）、cache/ContentCacheDTO（136）/CommentCacheDTO（110）、vo/ContentVO（42）/ContentDetailVO（26）/CommentVO（22）/ProfileVO（43）、dto/PageResult（62）/SearchDTO（51）、command/CommandConverter（139）/ContentType（16） | 内容模型 + 共享缓存 DTO + 共享 VO/DTO/转换器 |

> **共享组件归属**：ContentCache / CommentCache / ContentStatusFiller / ContentCacheDTO / CommentCacheDTO / PageResult / CommandConverter / ContentVO / ContentDetailVO / CommentVO 归本域，其它域 controller/service 跨域 import。

#### follow 域 — `com.itheima.follow`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | FollowController（114，/follow/*） | 关注/取关/关注列表/粉丝列表（**T7：列表支持可选 `page`/`pageSize`**——显式判"是否传分页参数"分支：缺省全量数组、传参走分页信封） |
| service | FollowService（190） | 关注业务（读路径委托 FollowCache；关注/取关 DB 提交后缓存双写；**T7 新增分页读**——缓存窗口取该页 ids+total，仅对该页 ids 做 DB 装载与批量判重，信封在事务外组装，事务边界与缺省路径一致） |
| service | FollowCache（446） | 关注关系 Redis 缓存（**双 ZSet（score=成员 id）+ 条件 MULTI 双写 + 失败双 DEL** + 三态读 + 单飞 + 降级单飞全量装载作答；读路径收口 **ZSetCache**——单成员三态/批量/全量/窗口走基建 + `sortIds` 归一升序，写路径 MULTI 双写语义保持；关注/粉丝计数 key 读写） |
| dao | FollowDao（107） | follow 关注关系（仅 FollowService 业务校验与 FollowCache 回填 loader 使用） |
| model | FollowPageResult（79） | 关注/粉丝列表分页信封（T7 B2）：`list/total/page/pageSize/totalPages`，与 content 域 `PageResult` 同形但**归属 follow 域**——避免 follow 反向 import content 形成新包层环（content 已 import `follow.FollowCache`） |

> 注：FeedService/ProfileService/ContentStatusFiller 跨域 import `follow.service.FollowCache`（服务层），不再直连 FollowDao。守关注读路径走缓存、写路径 DB 提交后双写。

#### like 域 — `com.itheima.like`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | LikeController（113，/like/*） | 点赞/取消点赞 |
| service | LikeService（205）、LikeCacheService（394） | 内容/评论点赞业务 + Redis 点赞缓存（计数/成员分离；成员 key 为用户维度，读路径收口 SetCache——content/comment 孪生方法合并为 id 维度参数化单实现、批量回填 best-effort；写路径 Lua 条件写原子化） |
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
| 连接池上限/超时 | 20 / 5000ms | app.properties (db.pool.*) / AppConfig |

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
| 连接超时 | 1000ms | app.properties (redis.connectTimeoutMs) / AppConfig |
| 读写超时 | 1000ms | app.properties (redis.soTimeoutMs) / AppConfig |
| 池借用等待 | 1000ms | app.properties (redis.pool.maxWaitMs) / AppConfig |

> 超时均为显式配置（`MyRedisPool` 8 参 JedisPool 构造器，password/clientName=null 保持原语义），防 Redis 不可用时逐请求长等。

### 6.2 Key 设计与三态语义

> 三态 Cache-Aside：数据 key 存 JSON；另起 `empty:{dataKey}` 独立 String key 标记"已确认无数据"，TTL 60s（短 TTL 自动过期）。缓存仅作加速器，任何缓存失败必须降级走 DB、不得导致业务失败。

| Key 模式 | 类型 | 用途 |
|----------|------|------|
| content:{contentId} | String(JSON) | 内容详情缓存（Cache-Aside 数据 key，TTL 30min+抖动） |
| content:index:{type}:{category} | LIST\<contentId\> | 类型分区索引（4 key/内容：t,c / t,-1 / -1,c / -1,-1；新前序；启动全量重建+懒重建） |
| content:comments:{contentId} | String(JSON) | 内容评论树缓存（独立 TTL cache.comment.ttlMinutes=10min+抖动，与内容解耦） |
| empty:{dataKey} | String "1" | 空标记：已加载确认无数据（短 TTL 60s，不参与滑动续期） |
| content:likeCount:{contentId} | String(int) | 内容点赞计数（高频读，计数/成员分离） |
| comment:likeCount:{commentId} | String(int) | 评论点赞计数 |
| user:likeSet:{userId} | Set\<contentId\> | 我点赞过的内容（用户维度成员，装载量=该用户点赞数，与内容热度解耦） |
| user:commentLikeSet:{userId} | Set\<commentId\> | 我点赞过的评论（用户维度成员） |
| user:following:{userId} | ZSet\<followedUserId\>（score=id） | 我关注了谁（MULTI 双写，失败双 DEL；**T7 起为 ZSet**：`ZRANGE[start,stop]` 窗口读 + `ZCARD` 取总数，ZRANGE 天然按 id 升序） |
| user:follower:{userId} | ZSet\<userId\>（score=id） | 谁关注了我（逻辑同 user:following） |
| user:followCount:{userId} | String(int) | 我的关注数（独立计数 key，Cache-Aside，0 合法；写路径条件 INCRBY、冷 key no-op 由读回填） |
| user:followerCount:{userId} | String(int) | 我的粉丝数（逻辑同 user:followCount） |

> 索引 key 生成/解析/匹配同源：唯一源 = `CacheKeys.contentIndex(type, categoryId)`（前缀常量 `CONTENT_INDEX_PREFIX="content:index:"`），`domainOf` 解析与索引 SCAN 匹配引用同一前缀，业务包不自行拼接 key。
> 旧 key（`content:like:{id}` / `comment:like:{id}`、内容/评论维度成员 `content:likeSet:{id}` / `comment:likeSet:{id}`）已随计数分离与成员反转停用，不双写，TTL 过期自然回收。
> 一致性由 **启动全量重建 + 索引懒重建 + 业务显式失效（增删改/计数/门禁/隐藏恢复）+ Cache-Aside 读自愈** 承担，无周期性全库重载。

### 6.3 统计观测（CacheStats）

- **组件**：`com.itheima.cache.CacheStats`（@Component，固定 `AtomicLong[5][6]` 计数数组，无锁无扩容）。纯计数与日志，不打任何新 Redis 命令、不改缓存读写语义。
- **六类事件**：HIT_DATA / HIT_EMPTY / MISS / LOAD / DEGRADE / WRITE_FAIL。
- **分域分桶**：`CacheKeys.domainOf(String dataKey)` 唯一解析源——**长前缀优先**（content:index / content:comments 先于通用 content:；user:like* / user:commentLike* 先于通用 user:）；`empty:` 空标记先解包到底层数据 key 再归域；映射：content:index / content:{id}→CONTENT、content:like*/comment:like* / user:like*/user:commentLike*→LIKE、content:comments / comment:*→COMMENT、user:following / user:follower / user:followCount*（user:* 兜底）→FOLLOW、未知/null→OTHER。
- **惰性日志输出**：每 N=1000 次记录输出一次各域摘要（INFO 单行）；不引入定时器、不新增 admin 端点。
- **挂点**：CacheAside 自动打点（三态读 + 降级 + LOAD + 写失败）；SetCache / ZSetCache 自动打点；LikeCacheService / FollowCache 原生路径手动打点（批量记录粒度=每 (数据 key, 决策) 记一次）。
- **红线段**：`record()` 自身异常吞掉记 WARNING，不影响主链路；统计不引入 MQ。
- **用途**：分域命中率/穿透曲线为分域 TTL 与读路径优化提供数据依据。

### 6.4 读路径

- **单 key pipeline 化**：`CacheAside.read` / `getInternal` 由"EXISTS 空标记 + GET 数据 key 两趟往返"合并为**一趟 pipeline**（内部 `probe(dataKey)` 复用，三态/空标记/单飞/降级语义与统计不变）。
- **批量读接口**：`CacheAside.getBatch`——一趟 pipeline 批量 EXISTS+GET，三态判断与单 key 完全一致（先空标记后数据 key），miss 项逐个单飞回填；**4 参重载**：脏 JSON 单 key / 整批 Redis 异常 → DEGRADE + 直接 loader 不写回；**5 参重载**（`BatchLoader`）：miss/整批降级子集经 `LoadMemo`/`LoadOutcome`（LOADED/EMPTY/FAILED）**一趟批量装载**，漏 key 按加载失败不写假空；逐 key 单飞去重、三态/续期/空标记/降级/打点口径不变。调用方保证 key 无重复。
- **内容批量接入**：`ContentCache.getContentsBatch(List<Long>)`（id → DTO 映射，null 值=hit-empty/DB 无数据透传）；miss/降级装载走 `loadContentsFromDb` **一趟事务两查**（`ContentDao.findContentsByIds` + `ContentMediaDao.findMediaByContentIds`）；Feed/Profile 使用。
- **推荐惰性探测**：`getRecommendByFilter` 按 shuffle 序**逐个 `getContent`、凑满 limit 即止**（探测量从"候选数 × 3 命令"收敛到 ≈3×(limit+跳过量)，与候选总量解耦）；shuffle 仍在全量去重 id 列表上一次性执行，返回集="shuffle 序前 limit 个非 null"，**推荐结果分布语义不变**。
- **Feed/Profile 事务外读**：事务回调只做 DB 查询（`ProfileDbData(user, pageIds, total)` / `FeedDbData(pageIds, total)` 私有 record 回传），提交归还连接后**事务外**批量读缓存 + 填点赞状态 + 组装 VO；两个早退分支（无关注 / total==0）与改造前一致**零缓存调用**；404/500 异常仍只在事务回调内产生。
- **索引遍历**：`forEachIndexKey` 用 **SCAN**（游标收敛于 "0"）替代 `KEYS "content:index:*"`（removeContent 的 LREM、重建的 DEL 两处；LREM/DEL 幂等，SCAN 重复 key 无害）。

### 6.5 TTL 与滑动续期

- **滑动续期（读命中顺带续期）**：`CacheAside.get`/`getInternal`（`probeRenew`）与 `getBatch` 在**同 pipeline** 内对数据 key 追加 `EXPIRE`，续期值=原 TTL ±10% 抖动（保底 1s），**零额外往返**；LikeCacheService / FollowCache 原生 Set 三态读命中同样续期（值=域 TTL 精确值）。
- **空标记不续期**：`empty:` key 从不被 EXPIRE，防"假空"窗口延长（EXPIRE 失败即 pipeline Redis 异常，走既有降级 loader）。
- **`read()` 不续期**：纯三态读、无 TTL 上下文、无生产调用方。
- **分域 TTL 取值**（app.properties，依据双轮压测 + 各域读写特性）：

| 域 | 配置键 | 取值 |
|----|--------|------|
| content | cache.content.ttlMinutes | 30min（启动全量重建 + 热读，命中率恒高） |
| comment | cache.comment.ttlMinutes | 10min（新鲜度敏感，增删/点赞失效频繁） |
| like | cache.like.ttlMinutes | 15min（显式失效清晰：点赞/取消失效 count+set） |
| follow | cache.follow.ttlMinutes | 30min（关系低频变 + MULTI 双写失效清晰） |

- **红线**：不改 key 命名、不改三态顺序、不改空标记 TTL（60s）、不设 TTL 永生；续期失败不影响读返回。

### 6.6 超时与熔断

目标：Redis 不可用时"缓存既不撒谎也不放量"——每个请求不再逐次等连接超时，熔断开启后立即降级走 DB；恢复后自动回到正常缓存路径。**不改三态/空标记/降级语义**：熔断抛出的 `CacheException` 由 `CacheAside`/业务类既有 catch 降级路径自然接住。

- **显式超时**：`MyRedisPool` 8 参 JedisPool 构造器（`(poolConfig, host, port, connTimeout, soTimeout, password, database, clientName)`，password/clientName=null）显式配置 connect/so 超时各 1s；`setMaxWait(1s)` 治池耗尽无限阻塞。public 方法签名零变化。
- **全局熔断器**：`RedisCircuitBreaker`（@Component 单例，AtomicInteger CAS 无锁）——CLOSED →（连续失败 ≥ 阈值 5）OPEN →（冷却 10s 期满，首个到达请求 CAS 成为唯一探针）HALF_OPEN → 探针成功 CLOSED / 探针失败回 OPEN（重置冷却）。参数：`redis.breaker.failureThreshold=5`、`redis.breaker.cooldownMillis=10000`。
- **接线点**：`RedisAccess.execute` 唯一出入口——开头 `tryAcquire()` 拒绝即抛 `CacheException`（快速失败，不取连接）；`finally` 按成败回填 `recordSuccess/recordFailure`。失败口径：从 execute 冒出的 CacheException 计一次失败。粒度：全局单熔断（单 Redis 实例）。双构造器（@InjectConstructor IoC + 无参测试直调兼容）。

### 6.7 降级路径：单飞去重 + 不写回

目标：Redis 不可用（熔断 OPEN/快速失败）后，**同一 key 的并发降级读只打一次 DB**——消除"降级放量"（并发请求全部各自打 DB）。`SingleFlight` 零改动，降级读与 miss 回填**共用同一单飞 key 空间**。

- **统一规则**：降级读 = 与 miss 路径同款"单飞 + 全量 loader"取数，但**仅装载、不写回**（对齐 D4）。
- **落点（10 处降级分支全量治理）**：`CacheAside` 3 处（`getInternal` catch / `getBatch` 整批 catch / `getBatch` 脏 JSON 单 key catch）；`FollowCache` 3 处（`isFollowing`/`getSetMembers` catch 全量装载作答、`batchIsFollowing` 增 degraded 标志降级态单飞全量作答）；`LikeCacheService` 4 处（`isContentLiked`/`isCommentLiked` catch 全量装载作答、两批量降级态逐 cid 单飞作答）。防漂移：`FollowCache.loadViaSingleFlight` / `LikeCacheService.loadLikersViaSingleFlight` 为降级装载唯一入口。
- **失败语义**：loader 失败 → 单飞条目以异常收场（失败不以数据形式共享）→ 条目 remove → 下一请求全新重试。
- **统计口径**：`LOAD` 从"每降级请求记一次"变为"实际去重后装载记一次（leader 记）"；`DEGRADE` 仍按请求/key 记。

### 6.8 负缓存治理：区分"确认无数据"与"加载失败"

目标：DB 瞬时抖动时，读路径**不把瞬时故障固化成假数据**——loader 的"确认无数据"与"加载失败"必须可区分，失败**不写 60s 空标记、不 DEL 既有数据 key**。对外行为零变化（内容 404 / 评论空 / 批量逐 key 跳过）。

- **loader 契约（ContentCache/CommentCache）**：`return null` 仅表示**确认无数据**（DB 无行 / 媒体损坏 / 未知类型）→ 允许 `markEmpty`；**抛 `DatabaseException`** 表示**加载失败**（SQLException 由事务模板包成它；意外异常统一包成 DatabaseException）；`ContentCache.addContent`/`refreshContent`（DB 提交后缓存同步）遇 DatabaseException 静默跳过（refresh 保留旧缓存读自愈）。
- **CacheAside 契约**：所有装载点（`getInternal` miss 与降级、`getBatch` miss 循环、整批降级、脏 JSON 单 key 降级）捕获 `DatabaseException` → 记日志转 null——**不写空标记、不 DEL 数据 key**。契约**仅对 `DatabaseException` 生效**——like/follow 域 loader 抛 `ServerException`（500 语义）不受影响。
- **单飞语义**：DatabaseException 在单飞 lambda 内被转 null 后 FutureTask 正常完成、条目必然 remove；并发等待者同得 null、下一请求全新重试。

### 6.9 写路径治理

- **空标记存在守卫**：`CacheAside.markEmpty` 改为 `if (!j.exists(dataKey))` 才 `setex(empty:{dataKey}, 60)`——数据 key 已存在（并发回填/业务写刚写入真数据）时跳过，**不写空标记、不 DEL**；守卫检查失败同归 catch：宁可少写空标记（多一次 DB 查），绝不误写假空。残余竞态（exists→setex 毫秒间隙）：空标记可能覆盖其上，但数据 key 未被删，60s 过期或下次业务写清空标记即自愈。
- **条件写 Lua 原子化（点赞）**：`LikeCacheService` 四方法（like/unlike content/comment）经脚本 `eval` 一趟原子执行——点赞 `DEL empty:` + `EXISTS setKey 才 SADD` + `EXISTS countKey 才 INCR`；取消点赞 `EXISTS setKey 才 SREM` + `EXISTS countKey 才 DECR`（**保持不清空标记**）。防并发失效 DEL 计数 key 后以 ±1 重建错误计数；冷 key 不创建残缺集、失败降级=失效计数 key 让读自愈、不抛出。Jedis 5.1.0 `eval(String, List, List)`。
- **索引写失败自愈**：`ContentCache.addToIndex` 写失败（catch CacheException）→ best-effort DEL 本内容所属 4 个索引 key（`indexKeysOf` 与 `lremAndLpush` 同源提取）→ 下次推荐读 `ensureIndex` 发现索引 key 缺失 → 既有单飞懒重建 → 内容重新进推荐；自愈 DEL 也失败仅记日志（读路径降级兜底，无新增伤害）。否决脏标记 key / 完整性校验（无便宜一致性信号）。

### 6.10 启动加载治理

- **事务外写**：`ContentCache.init()` 拆两段——DB 阶段事务内**只读**（`findAllContent` + `ContentMediaDao.findMediaByContentIds` 批量媒体装载 + 构建 DTO，DB 恒 2 次查询），事务提交后 `rebuildRedis` 在**事务外**写 Redis；DB 装载失败 → 记日志 return，不触发任何 Redis 写。
- **内容 key 批量写**：`CacheAside.writeBatch(map, ttl)`——一趟 pipeline `(setex[per-key TTL 抖动] + del empty:)×N`，失败 → 逐 key `deleteQuietly` 自愈 + WRITE_FAIL 打点；批内单命令 server 错误依赖 `Pipeline.sync()` 抛异常统一兜底。
- **索引 pipeline 化**：`rebuildIndexes` 单条 executeVoid——SCAN 顺序收集旧 `content:index:*` key → 一趟 pipeline DEL 全部 + `lremAndLpush` 全部。启动 Redis 往返从 ≈12N 降到 ≈3 次（内容 pipeline 1 + 索引 SCAN 页 + 索引 pipeline 1），与内容量解耦。
- **索引懒重建**：索引 key 缺失时 `ensureIndex` 单飞懒重建（`getRecommendByFilter` 首访触发，防 Redis 重启后 /start 空推荐）。

### 6.11 索引维护：重建失败退避 + 长尾漂移

- **冷却退避**：`ensureIndex` 重建失败（Redis 写失败）记进程内冷却（`cache.content.indexRebuildCooldownMillis=10000`，对齐熔断冷却先例），**窗口内跳过探测与重建**（Redis 停机期间 `/start` 从"逐请求 DB 全表查询"收敛到"每冷却窗口 1 次"）；冷却过期后下一请求自然重试，重建成功即恢复正常（与熔断探针恢复语义同构）。
- **长尾漂移结论**：`lrem(k,0,id)`（删全部出现）+ `lpush` 写即去重 → id 每 key 至多 1 条、索引大小 ≤ 该维度活跃内容数，**不随增删操作累积**，正常操作无系统性漂移、无需定期重建。残余窗口（已接受）：仅删除 LREM 失败（停机窗口）残留有界脏 id，读侧 null 跳过免疫、惰性探测每条至多消耗 1 个探测位；索引 key 缺失/启动全量重建时 SCAN+DEL 全量收敛。

### 6.12 Set 基建组件 SetCache 与域收口

- **SetCache（cache 包）**：原生 Set 缓存基建，与 CacheAside（JSON Cache-Aside）平行存在、互不改对方签名。API：`isMember`（单成员三态）/ `getMembers`（全量读，不排序）/ `batchIsMember`（单 set 多成员·Follow 形态）/ `batchKeysIsMember`（多 set 单成员·Like 形态）/ `writeSet`（非空 SADD+EXPIRE 精确 TTL，空→`cacheAside.markEmpty` 含 exists 守卫）/ `loadViaSingleFlight`（降级单飞装载、不写回）。语义要点：探针续期精确 TTL 无抖动、空标记从不续期；批量 miss 的 DB 答案失败上抛（DB 即真理）、回填 best-effort（缓存失败不得导致业务失败）。
- **域收口（like / follow 读路径）**：LikeCacheService 与 FollowCache 的 Set 成员读路径全部改走 SetCache——content/comment 孪生方法收敛为 id 维度参数化单实现（`DaoQuery<T>`/`BatchQuery` helper，用户可见异常文案逐字不变）；`FollowCache` 列表经 **`sortIds` 唯一包装点统一升序**（hit-data/miss/降级三路径一致，防热/冷读顺序波动）；follow **写路径（MULTI 条件双写 + 失败双 DEL）为双 key 原子语义保留在 FollowCache**。
- **行为差异记录**：批量 miss 回填 loader 失败由"上抛 500"统一为 best-effort（SetCache 契约，收敛方向）；批量 dbAnswer 失败仍上抛；单成员 miss/降级 loader 失败仍上抛。

### 6.13 点赞成员用户维度反转

- **key 反转**：点赞成员 key 由内容/评论维度（`content:likeSet:{id}` / `comment:likeSet:{id}`，Set<userId>，装载量=点赞者数随热度放大）反转为**用户维度**（`user:likeSet:{userId}` Set<contentId> / `user:commentLikeSet:{userId}` Set<commentId>，装载量=该用户点赞数，与 `user:following` 同构）；**不双写旧 key**，like TTL 过期自然回收。
- **读路径（全走 SetCache）**：单成员 `isMember(用户维度 set, contentId, loader=该用户全量点赞记录)`；批量 `batchIsMember`（单 set 多成员，替代原"多 set 单成员"，命令数与装载量双降）；dbAnswer = `findLikedContentIdsByUser` / `findLikedCommentIdsByUser`。
- **写路径（Lua 条件写）**：脚本常量零改动，仅 KEYS/ARGV 换维度（`user:likeSet:{userId}`、`user:commentLikeSet:{userId}`）；冷 key 不创建残缺集、写失败失效计数 key 让读自愈。
- **失效**：删除/下架级联**仅失效计数 key**——成员 key 为用户维度，删除无法反查点赞者逐一 SREM；残留成员不清理亦无害（物理删：DB 点赞行一并删除、id 不复用、UI 无查询路径永不外显；下架软删：点赞记录保留 DB，恢复后读自愈对齐）。

### 6.14 关注/粉丝计数入缓存

- **读路径**：关注/粉丝计数入独立 key（`user:followCount:{userId}` / `user:followerCount:{userId}`，String int，Cache-Aside，**0 合法**，与 `content:likeCount` 同构）——Profile 主页读路径在事务外经 `FollowCache.getFollowCount/getFollowerCount` 走缓存（miss 回填 DB 单列计数，DB 仍为最终真理），不再消费 user 行内计数字段。
- **写路径**：关注/取关 DB 提交后追加**条件增量 Lua**（`FOLLOW_COUNT_ADJUST_SCRIPT`：`exists 才 INCRBY±1`，冷 key no-op 由读回填；EVAL 失败只失效两计数 key 读自愈、不抛出）。成员 MULTI 双写与计数增量为两次独立 Redis 调用、失败隔离。
- **否决 SCARD 现算成员 set**：冷 set 返 0 且不触发装载；set TTL 过期后返 0；计数与成员加载状态/TTL 耦合——成员（who）与计数（how many）分离。

### 6.15 JSON 未知字段兼容

- `JacksonCodec` MAPPER 追加 `.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)`：DTO 删/改名后旧缓存 JSON 含未知字段仍可反序列化（多余字段忽略），不再反复降级走 DB；保留 `WRITE_DATES_AS_TIMESTAMPS` 原样（日期 ISO-8601），**无格式版本机制**（版本号/迁移不做）。
- 有意的定向行为变化：脏 JSON 由「反序列化失败 → DEGRADE → DB loader」变「成功解析（忽略未知字段）→ HIT_DATA」；对外 API/业务语义零变化。

### 6.16 authorName 冗余同步

- **场景**：内容缓存 DTO 的 `authorName` 是 `findContent`/`findAllContent` `JOIN users` 时的反规范化副本，只存在于内容数据 key——`content:index:*` 只存 id、评论/点赞缓存不含 authorName，故改名只需失效内容数据 key，索引无需失效。
- **落地**：`POST /user/changeUserName`（LoginController `/user/*` switch + AuthFilter 精确保护）；`UserService.changeUserName` DB 提交后调 `ContentCache.invalidateAuthorContentKeys(userId)`（事务内 `findContentIdsByUser` 查该作者全部内容 id → 事务外逐个失效内容 key + 空标记）；**读自愈**重新 JOIN users 回填新名；DB/Redis 失败仅记日志跳过、TTL 自愈，不影响改名成功语义。UserService 注入 ContentCache（user→content 跨域，对齐 like/comment 先例无环）。

### 6.17 关注关系有序化与分页窗口（T7）

- **Set→ZSet 有序化**：`user:following:{userId}` / `user:follower:{userId}` 由 Set 升级为 ZSet（score = 成员自身 id）；写路径 `SADD/SREM → ZADD/ZREM`（MULTI 条件双写 / 双 key 探针 / 空标记 / TTL 骨架不变），读路径收口**新建的 `ZSetCache`**（与 SetCache 平行，命令层 ZSCORE/ZRANGE/ZADD）。like 域成员 key 仍为 Set、仍走 SetCache（不受影响）。
- **等价性依据**：score = 成员数值 ⇒ `ZRANGE` 遍历序 = 成员数值升序 = 改造前 `sortIds` 升序口径，全量读/单项判定/批量判定的对外结果与顺序**逐条不变**。
- **分页窗口读**：`ZSetCache.getWindow(key, offset, count)` **窗口取数**为一趟 pipeline（`ZRANGE[start,stop]` + `ZCARD` → `Window{ids,total}`；探针一趟另行，与 SetCache 同模式）；**hit 路径 O(log n + N)**（不再 `SMEMBERS` 全量回传 + String→Long 装箱）；miss 单飞回填后重读窗口；Redis 异常 → 单飞全量装载 + 按 score 口径升序内存切片、**不写回**。**残留**：miss / 降级仍是全量装载形态（缓存装载既有形态，与 R-01 索引全量读同型 → 留池 **U-18**）。
- **顺序保证**：分页切片的成员集合与顺序来自 ZSet 升序（score=成员 id）；**列表最终输出顺序由 `UserDao.findUsersByIds` 决定**，T7 已为该查询补 `ORDER BY id`（此前无 ORDER BY，输出序依赖存储引擎默认序——HEAD 既有脆弱点，唯一调用方为 FollowService），使"ZSet 升序切片"与"DB 返回序"同口径，分页顺序稳定**由构造保证**而非巧合。
- **接口口径（B2）**：`GET /follow/following|followers` 传 `page` 或 `pageSize` **任一** → `data = {list,total,page,pageSize,totalPages}`（follow 域信封 `FollowPageResult`）；**两者都不传 → `data` 仍为全量数组**（逐字节兼容，既有调用方与 pytest 用例零破坏；不可用"默认 page=1&pageSize=50"实现兼容，上限 50 会截断全量）。分页参数解析复用 `BaseServletUtil.parsePage/parsePageSize`（默认 1 / 10、上限 50）；越界页返回空数组但保留 total。
- **total 口径**：同一 ZSet 的 `ZCARD`（与页内容同源）；不用独立计数 key `user:followCount`（两 key 可能瞬时不一致）。
- **前端**：`static/js/views/user.js` 的关注/粉丝 sheet 改为分页加载 + 「加载更多」（复用既有 `.load-more-btn` 样式；切换 following/followers 时重置页码与总页数）。
- **包层边界**：分页信封落在 follow 域（**不 import content 域 `PageResult`**——content 已 import `follow.FollowCache`，反向引用会形成新的 follow↔content 包层环）；"PageResult 上移公共包供多域复用"登记留池（跨域重构不在本任务范围）。

---

## 七、API 接口清单

### 7.1 用户模块

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| POST | /user/login | 登录 | ✗ |
| POST | /user/register | 注册 | ✗ |
| POST | /user/changePassword | 修改密码 | ✓ |
| POST | /user/changeUserName | 修改用户名（改名后级联失效该作者内容缓存 authorName） | ✓ |

### 7.2 内容模块

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| GET | /start | 首页推荐 | ✗ |
| GET | /search | 搜索 | ✗ |
| GET | /search/IdSearch | 内容详情（无 /detail 端点） | ✗ |
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
| GET | /follow/following | 关注列表（**可选 `page`/`pageSize`**：传参→分页信封 `{list,total,page,pageSize,totalPages}`；缺省→全量数组） | ✓ |
| GET | /follow/followers | 粉丝列表（分页口径同 /follow/following） | ✓ |
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

> 2026-08-14 起由「9 个独立 html（移动端优先）」重构为**单页应用（SPA）**：只保留 1 个 `index.html` 外壳 + 原生 hash 路由 + `static/js/views/` 视图模块，纯原生 HTML/CSS/JS、无构建工具。

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
            ├── user.js        # #/user/:id    个人主页（本人/他人合一；T7 起关注/粉丝 sheet 分页加载）
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

> 测试体系 = **JUnit（服务层单测，`src/test/java`，与被测类同包）+ pytest（端到端，`src/test/python`，`tools/run_tests.py` 驱动）**。跑法/分层定位/用例收集口径/排查/构建输出（stage8.buildDir 沙箱限制）见 `.docs/说明书/TEST_AUTOMATION.md`（跑测试唯一权威入口）；用例清单与数量以测试代码与 `surefire-reports` 为准，本文件不再维护明细表。

---

## 十、变更记录

> 本文件**不设变更记录**（2026-09-18 变更纪律，第六期 T2 起）：变更以 git 提交历史为准，message 规范见 `.docs/说明书/COMMIT_CONVENTION.md`（正文「问题/方案/验证/文档」四节承载决策）；周期决策明细另落 `目标与任务/*/NEXT_CYCLE_NEEDS.md` 4.0 决策节与 `目标与任务/*/NEXT_CYCLE_TASKS.md` 执行回写。版本 3.0 之前的逐条更新日志已随 T2 移除（可经 git 历史回溯）。

---

## 维护说明

### 何时更新本文档

- 新增/删除/重命名类、新增/删除 API 接口、数据库表结构、依赖注入关系、配置信息、包结构变更时，更新**被改动影响的事实章节**。
- 同步 bump 头部「最后更新」日期与版本号（minor）。
- **不写"第 N 期 T# 新增"式注记、不设变更记录**——历史归 git，决策明细归周期文档。

### 如何更新

1. 更新对应的章节（只动受影响小节，按章节按需阅读）
2. 更新头部"最后更新"日期 + 版本号
3. 无需在本文档追加变更记录（git 提交历史即记录，见 `COMMIT_CONVENTION.md`）
