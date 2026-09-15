# 当前系统架构地图

> 版本：2.14
> 最后更新：2026-09-14
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
├── cache/                  # 基建（C 周期 T1 新增；三期 T1 增熔断、T2 降级接入单飞）：统一缓存基建——Redis 访问+熔断/JSON 序列化/统一 key 规范/单飞/三态空标记/写失败 DEL 降级（1064 行）
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
| MyRedisPool | 49 | Redis 连接池（三期 T1：显式 connect/so 超时 + maxWait，8 参 JedisPool 构造器） |
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
| CacheKeys | 128 | 统一 key 命名/生成规范（本周期唯一源，与六.6.2 一致）+ 空标记常量（EMPTY_MARKER_TTL_SECONDS=60s）+ `domainOf` 统计域解析（T7 新增：key 生成与解析同源，长前缀优先）+ **三期 T6（cache-06）U-08 归一：`contentIndex(type, categoryId)` 生成方法 + `CONTENT_INDEX_PREFIX` 前缀常量——索引 key 生成 / `domainOf` 解析 / SCAN 匹配三处同源（原 `ContentCache.indexKey` 私有拼接移除）** |
| CacheDomain | 26 | 统计分域枚举（T7 新增）：CONTENT/COMMENT/LIKE/FOLLOW/OTHER |
| CacheStats | 131 | 观测统计组件（T7 新增）：六类事件（HIT_DATA/HIT_EMPTY/MISS/LOAD/DEGRADE/WRITE_FAIL）AtomicLong 计数 + 分域分桶 + 惰性日志（每 N=1000 输出摘要），record 异常吞掉不影响主链路；**三期 T6（cache-06）DEGRADE 语义明示**：=本次读未命中缓存、走 DB 兜底次数（按请求/key 计一次），含 Redis 操作异常与 T1 熔断开启快速失败（两者语义一致，熔断只是让失败更快） |
| JacksonCodec | 53 | JSON 序列化（复用 jackson-databind + jsr310），异常抛 CacheException |
| RedisAccess | 91 | 统一 Redis 访问封装：`execute`/`executeVoid` 回调式取还连接（支持同连接 pipeline/MULTI），Jedis 异常包装为 CacheException；**三期 T1（cache-01）接入全局熔断**——`tryAcquire` 拒绝即抛 CacheException 快速失败（不取连接），`finally` 按成败回填熔断器；双构造器（@InjectConstructor 注入熔断器 / 无参默认构造兼容既有测试直调） |
| RedisCircuitBreaker | 141 | 全局 Redis 熔断器（三期 T1 cache-01 新增）：CLOSED→OPEN（连续失败 ≥5，可配）→（冷却 10s 期满 CAS 唯一探针）HALF_OPEN→探针成功 CLOSED / 失败重开；AtomicInteger CAS 无锁，状态迁移打日志（开启 WARNING/探针与恢复 INFO） |
| SingleFlight | 65 | 统一单飞组件（4.9）：ConcurrentHashMap+FutureTask，失败/成功均 remove（防缓存失败结果 + 防泄漏）；**三期 T2 起降级读路径与 miss 回填共用同一 key 空间（本类零改动）** |
| CacheStatus | 15 | 三态枚举：MISS / HIT_EMPTY / HIT_DATA |
| CacheResult | 39 | 三态读取结果载体（status + value，HIT_EMPTY 时 value=null） |
| CacheAside | 399 | 统一 Cache-Aside 封装：`read` 三态读 / `get` 带单飞回填 / `writeOrInvalidate`（写失败=DEL 自愈，写数据同时清空标记）/ `markEmpty` / `invalidate`，TTL ±10% 简单抖动，缓存失败一律降级不抛业务异常；T7 起三态读/降级/LOAD/写失败处自动打点 CacheStats；**T8 起读路径 pipeline 化**（单 key 与批量均 EXISTS 空标记+GET 一趟往返）并新增 **`getBatch` 批量读接口**（三态语义与单 key 一致、miss 逐个单飞回填、解析失败/整批降级逐 key 直接 loader 不写回）；**T9 起读命中滑动续期**（`get`/`getInternal` 与 `getBatch` 命中数据 key 时同 pipeline 追加 EXPIRE，续期值=原 TTL ±10% 抖动，空标记从不续期；`read` 纯三态读不续期）；**三期 T2 起降级亦经单飞**（getInternal catch / getBatch 整批 catch / getBatch 脏 JSON catch 三处降级改 `singleFlight.get(key, loader)`：同 key 并发只打一次 DB、仅装载不写回、失败条目移除可重试）；**三期 T3 起"loader 失败不得上报为空"契约**（治 N2）：所有装载点捕获 `DatabaseException` 转 null——不写空标记、不 DEL 数据 key（miss 回填内联 try/catch 直接 return null 跳过 markEmpty；getInternal 降级 / getBatch 整批降级 / getBatch 脏 JSON 单 key 降级共用 `loadDegraded` helper） |
| SetCache | 330 | 原生 Set 缓存基建（**第四期 T1 cache-01 新增，U-09/N3 收敛落点**）：单成员三态读 `isMember` / 全量读 `getMembers`（不排序）/ 批量判定（`batchIsMember` 单 set 多成员·Follow 形态、`batchKeysIsMember` 多 set 单成员·Like 形态）/ 回填 `writeSet`（空→复用 `cacheAside.markEmpty`）/ 降级装载 `loadViaSingleFlight`；三态/空标记/降级语义与域缓存现状逐条一致（探针续期精确 TTL 无抖动、空标记不续、批量 DB 答案失败上抛而回填写 best-effort）；**尚未接入业务（T2/T3 收口 LikeCacheService/FollowCache）**，见 6.13 |

> 测试：`src/test/java/com/itheima/cache/` **8 类 117 例**（mockStatic MyRedisPool + mock Jedis，不碰真实 Redis，含 CacheStatsTest 域解析/计数/惰性输出 8 例；CacheAsideTest 43 例含单 key pipeline 往返断言与批量三态/降级/脏 JSON 用例 + **T9 滑动续期 4 例**：命中续期抖动/空标记不续/批量续期/续期失败降级 + **三期 T2 新增 3 例降级单飞**：降级并发同 key 只装载一次/降级 loader 失败不共享且可重试/批量降级逐 key 去重——并发用例用 mock RedisAccess 恒抛 CacheException（MockedStatic 线程局部不可跨线程）；**三期 T3 新增 5 例负缓存治理**：miss/降级/批量 miss 逐 key/批量整批降级/批量脏 JSON 单 key 降级 遇 DatabaseException 均转 null 且不写空标记不 DEL；**三期 T1 新增 RedisCircuitBreakerTest 8 例**（状态机全迁移含并发唯一探针）+ **RedisAccessTest 扩至 9 例**（熔断开启快速失败不触达连接池/连续失败达阈值拒绝/成功重置不误开/冷却期满探针恢复全链路/回调 CacheException 计失败口径）；**第四期 T1 新增 SetCacheTest 30 例**（三态/回填/降级/批量/空标记/续期/统计/并发单飞）），见九.9.2。

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
| service | ContentService（445）、ContentCache（502，T2 新增：Redis 内容缓存=三态 Cache-Aside+索引；**三期 T3 起 loader 失败抛 DatabaseException，不污染空标记**）、CommentCache（176，T3 新增：Redis 评论缓存=三态 Cache-Aside+独立 TTL+空标记+显式失效；**三期 T3 起 loader 失败抛 DatabaseException，不污染空标记**）、ContentStatusFiller（90）、FeedService（74）、ProfileService（86） | 内容业务 + Redis 内容缓存 + Redis 评论树缓存 + 状态填充 + 关注流 + 主页 |
| dao | ContentDao（366）、ContentMediaDao（163） | content/content_media 数据访问（ContentLikeDao 按 like 域归属） |
| model | entity/ContentMedia（63）、cache/ContentCacheDTO（136）/CommentCacheDTO（110）、vo/ContentVO（42）/ContentDetailVO（26）/CommentVO（22）/ProfileVO（43）、dto/PageResult（62）/SearchDTO（51）、command/CommandConverter（139）/ContentType（16） | 内容模型 + 共享缓存 DTO + 共享 VO/DTO/转换器 |

> **共享组件归属**：ContentCache / CommentCache / ContentStatusFiller / ContentCacheDTO / CommentCacheDTO / PageResult / CommandConverter / ContentVO / ContentDetailVO / CommentVO 归本域，其它域 controller/service 跨域 import。

#### follow 域 — `com.itheima.follow`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | FollowController（91，/follow/*） | 关注/取关/关注列表/粉丝列表 |
| service | FollowService（142） | 关注业务（读路径委托 FollowCache；关注/取关 DB 提交后缓存双写） |
| service | FollowCache（524） | 关注关系 Redis 缓存（双 Set + 条件 MULTI 双写 + 失败双 DEL + 三态读 + 单飞 + **三期 T2 降级经单飞全量装载作答**；T5 新增） |
| dao | FollowDao（107） | follow 关注关系（仅 FollowService 业务校验与 FollowCache 回填 loader 使用） |
| model | — | 无专属 model |

> 注：FeedService/ProfileService/ContentStatusFiller 跨域 import `follow.service.FollowCache`（服务层），不再直连 FollowDao。守关注读路径走缓存、写路径 DB 提交后双写（NEEDS 4.10）。

#### like 域 — `com.itheima.like`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | LikeController（113，/like/*） | 点赞/取消点赞 |
| service | LikeService（205）、LikeCacheService（628） | 内容/评论点赞业务 + Redis 点赞缓存（计数/成员分离，读路径委托缓存类；**三期 T2 降级经单飞全量装载作答**） |
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
| 连接超时 | 1000ms | app.properties (redis.connectTimeoutMs) / AppConfig（T1 cache-01 显式化，此前走 Jedis 默认 2000ms） |
| 读写超时 | 1000ms | app.properties (redis.soTimeoutMs) / AppConfig（T1 cache-01） |
| 池借用等待 | 1000ms | app.properties (redis.pool.maxWaitMs) / AppConfig（T1 cache-01；默认 -1=池耗尽无限阻塞） |

### 6.2 Key 设计（T1 定稿：统一 Redis 缓存层，唯一源见 com.itheima.cache.CacheKeys）

> 三态 Cache-Aside（NEEDS 4.3/4.4）：数据 key 存 JSON；另起 `empty:{dataKey}` 独立 String key 标记"已确认无数据"，TTL 60s（空标记短 TTL 自动过期，不依赖 Redis 空容器回收；废弃旧 `__placeholder__` hack）。缓存仅作加速器，任何缓存失败必须降级走 DB、不得导致业务失败（4.2）。

| Key 模式 | 类型 | 用途 |
|----------|------|------|
| content:{contentId} | String(JSON) | 内容详情缓存（Cache-Aside 数据 key，TTL 30min+抖动，T9 分域取值） |
| content:index:{type}:{category} | LIST\<contentId\> | 类型分区索引（4 key/内容：t,c / t,-1 / -1,c / -1,-1；新前序；T2 启用，启动全量重建+懒重建） |
| content:comments:{contentId} | String(JSON) | 内容评论树缓存（独立 TTL cache.comment.ttlMinutes=10min+抖动，与内容解耦；T3 启用） |
| empty:{dataKey} | String "1" | 空标记：已加载确认无数据（短 TTL 60s，**T9 起确认为不参与滑动续期**） |
| content:likeCount:{contentId} | String(int) | 内容点赞计数（高频读，计数/成员分离 4.6；T4 启用） |
| content:likeSet:{contentId} | Set\<userId\> | 内容点赞成员（低频"谁点过"查询，miss 允许穿透；T4 启用） |
| comment:likeCount:{commentId} | String(int) | 评论点赞计数（T4 启用） |
| comment:likeSet:{commentId} | Set\<userId\> | 评论点赞成员（T4 启用） |
| user:following:{userId} | Set\<followedUserId\> | 我关注了谁（4.10，MULTI 双写，失败双 DEL；T5 启用） |
| user:follower:{userId} | Set\<userId\> | 谁关注了我（4.10，MULTI 双写，失败双 DEL；T5 启用） |

> 旧 key 演进：原 `content:like:{id}` / `comment:like:{id}`（单 Set 兼容 SCARD 计数）已随 T4 停用，由本表计数/成员分离 key 取代（旧 key 仅退款前历史遗留在 Redis，TTL 过期自然回收）；本表为新缓存层规范，按任务逐行启用（当前已启用：content / content:index / content:comments / content:likeCount / content:likeSet / comment:likeCount / comment:likeSet / user:following / user:follower；空标记随行）。
>
> 定时全量刷新（旧 `ContentCacheManager.startScheduler` 10min `scheduleAtFixedRate`）已随旧类移除（O-6 拍板，T6）：内容/索引一致性由**启动全量重建 + 索引懒重建 + 业务显式失效（增删改/计数/门禁/隐藏恢复）+ Cache-Aside 读自愈（按 key TTL 过期回填）** 承担，不再有周期性全库重载（原 H2/H7 雪崩与 N+1 痛点）。
>
> **三期 T6（cache-06）U-08 归一注记**：`content:index:{type}:{category}` 索引 key 生成唯一源 = `CacheKeys.contentIndex(type, categoryId)`（前缀常量 `CONTENT_INDEX_PREFIX="content:index:"`）；`domainOf` 解析（6.3）与 `forEachIndexKey` 的 SCAN 匹配模式均引用同一前缀常量——**生成 / 解析 / 匹配三处同源**，业务包不再自行拼接 key（原 `ContentCache.indexKey` 私有方法已移除）。

### 6.3 统计观测（T7 新增，治 H14 无观测能力）

> 二期"测量→优化→再测量"闭环的观测层（NEEDS 4.14），纯计数与日志，**不打任何新 Redis 命令、不改缓存读写语义**（三态判断顺序/空标记/DEL 降级路径一概不动，对外行为零变化）。

- **组件**：`com.itheima.cache.CacheStats`（@Component，固定 `AtomicLong[5][6]` 计数数组，无锁无扩容）。
- **六类事件**：hitData / hitEmpty / miss / loadCount / degradeCount / writeFailCount（`CacheStats.Event`：HIT_DATA/HIT_EMPTY/MISS/LOAD/DEGRADE/WRITE_FAIL）。
- **分域分桶**：`CacheKeys.domainOf(String dataKey)` 唯一解析源（key 生成与解析同源）——**长前缀优先**（content:index / content:like / content:comments 先于通用 content:）；`empty:` 空标记先解包到底层数据 key 再归域；映射：content:index→CONTENT、content:like*/comment:like*→LIKE、content:comments//comment:*→COMMENT、content:{id}→CONTENT、user:*→FOLLOW、未知/null→OTHER。
- **惰性日志输出**：每 **N=1000** 次记录输出一次各域摘要（INFO 单行 `CacheStats 摘要: total=.. content{hitData=.. …}`）；**不引入定时器、不新增 admin 端点**（与 O-6 移除定时刷新的决策一致：统计只读不重建）。
- **挂点**：CacheAside 自动打点（read/getInternal 三态读 + catch 降级 + `invokeLoader` 入口 LOAD + writeOrInvalidate/markEmpty/deleteQuietly 写失败）；LikeCacheService / FollowCache 原生 Set 三态读分支手动打点（含批量 pipeline 路径，批量记录粒度=**每 (数据 key, 决策) 记一次**：like 批量 key 各异按 id、follow 批量单 key 按一趟）；**SetCache（第四期 T1）自动打点**——原生 Set 路径（isMember/getMembers/批量/回填失败/降级），批量粒度保持现状（follow 单 set 一趟一次、like 每 (key,决策) 一次）。
- **红线段**：`record()` 自身异常吞掉记 WARNING，不影响主链路；统计不引入 MQ。
- **用途**：分域命中率/穿透曲线为 O-8（T9）分域 TTL 调参与 T8 读路径加固前后对比提供数据依据。

### 6.4 读路径加固（T8 新增，治 H12/H13）

> 二期读路径加速（NEEDS 4.14 T8），**对外行为零变化**（推荐 shuffle 语义与结果分布、`LRANGE 0 -1` 全量读、索引 LREM+LPUSH 语义、三态判断顺序一概不变；仅合并往返/换非阻塞命令）。

- **单 key 读 pipeline 化**：`CacheAside.read` / `getInternal` 由"EXISTS 空标记 + GET 数据 key 两趟往返"合并为**一趟 pipeline**（内部 `probe(dataKey)` 复用，三态/空标记/单飞/降级语义与统计逐条不变）。
- **批量读接口**：`CacheAside.getBatch(List<String> dataKeys, Class<T>, Function<String,T>, long)` → `Map<String,T>`——一趟 pipeline 批量 EXISTS+GET，三态判断与单 key 完全一致（先空标记后数据 key），miss 项逐个单飞回填；**批量记录粒度=每 (数据 key, 决策) 记一次**（T7 口径延续）；单个 key 脏 JSON 或整批 Redis 异常 → 该 key/全部 key `DEGRADE` + 直接 loader 不写回（对齐单 key 降级语义）。调用方保证 key 无重复。
- **内容批量接入**：`ContentCache.getContentsBatch(List<Long>)`（id → DTO 映射，null 值=hit-empty/DB 无数据透传）；`getRecommendByFilter`（/start 推荐 12 条 ≈ 24+ 往返 → 一趟 pipeline + 少量 miss 回填）、`FeedService.getFeed`、`ProfileService.getProfile` 页循环均改批量读。
- **索引 KEYS→SCAN**：`ContentCache.forEachIndexKey`（`scan(cursor, ScanParams.match("content:index:*").count(100))` 游标收敛于 "0"）替换 `KEYS "content:index:*"`（removeContent 的 LREM、rebuildIndexes 的 DEL 两处，治 H13 Redis 主线程 O(N) 阻塞；LREM/DEL 幂等，SCAN 重复 key 无害）。
- **统计口径**：批量读打点与单 key 一致（T9 分域取参数据连续）；单 key 与批量均为 1 趟往返（CacheAsideTest 有 `pipelined()` 次数断言）。

### 6.5 TTL 精调（T9 新增，O-8 滑动续期 + 分域取值）

> 二期"测量→优化→再测量"闭环收口（NEEDS 4.14 T9），**对外行为零变化**：三态判断顺序/空标记/DEL 降级路径一概不动，仅命中热 key 时延长生命周期（热点常驻由续期自然达成，不设永不过期 key）。

- **滑动续期（读命中顺带续期）**：`CacheAside.get`/`getInternal`（单 key，内部 `probeRenew`）与 `getBatch`（批量）在**同 pipeline** 内对数据 key 追加 `EXPIRE`，续期值=原 TTL ±10% 抖动（复用 `applyJitter`，保底 1s），**零额外往返**；LikeCacheService（`scanLikeSet`/两个批量）与 FollowCache（`scanSet`/`getSetMembers`/`batchIsFollowing`）原生 Set 三态读命中同样续期（值=域 TTL 精确值，与写路径 expire 口径一致）。
- **空标记不续期（执行定稿）**：`empty:` key 从不被 EXPIRE，防"假空"窗口延长；实现上对数据 key 无条件入列 EXPIRE——hit-data 生效、hit-empty/miss 时数据 key 不存在 EXPIRE 返回 0 无效果，不影响读返回（EXPIRE 失败即 pipeline Redis 异常，走既有降级 loader）。
- **`read()` 不续期**：纯三态读、无 TTL 上下文、无生产调用方，保持原语义。
- **分域 TTL 取值（执行定稿）**：双轮轻量压测（temp_script/pressure_cache.py 造缓存流量触发 CacheStats 惰性日志，改动前基线 vs 续期后对比）+ 各域读写特性，见下表（数据依据记录于 NEEDS 4.14 T9 执行定稿）：

| 域 | 配置键 | 取值 | 依据（基线 → 续期后，total=21000 摘要） |
|----|--------|------|------|
| content | cache.content.ttlMinutes | 30min | 基线/续期后均恒 100% hitData（启动全量重建 + 热读），miss≈0 无穿透风险 |
| comment | cache.comment.ttlMinutes | 10min | 命中占比 38.4%→42.5%（134/349 → 148/348），新鲜度敏感（增删/点赞失效），保持短 TTL |
| like | cache.like.ttlMinutes | 15min | 命中占比 59.0%→64.8%（847/1436 → 931/1436），显式失效清晰（点赞/取消失效 count+set） |
| follow | cache.follow.ttlMinutes | 30min | 关系低频变 + MULTI 双写失效清晰；压测以空关系为主（hitEmpty 近 100%），依据写路径特性保守延长 |

- **红线核验**：不改 key 命名、不改三态顺序、不改空标记 TTL（60s）、不设 TTL 永生；续期失败不影响读返回（单测覆盖 `getRenewalFailureDegradesToLoaderWithoutThrowing`）。

### 6.6 超时与熔断（三期 T1 cache-01 新增，治 U-10/N1）

> 目标：Redis 不可用时"缓存既不撒谎也不放量"——每个请求不再逐次等连接超时，熔断开启后立即降级走 DB；恢复后自动回到正常缓存路径。**不改三态/空标记/降级语义**：熔断抛出的 `CacheException` 由 `CacheAside`/业务类既有 catch 降级路径自然接住，`CacheAside` 零改动。

- **显式超时（治 U-10）**：`MyRedisPool` 改用 Jedis 8 参构造器（`(poolConfig, host, port, connTimeout, soTimeout, password, database, clientName)`，password/clientName=null、database=默认库，保持原三参语义）显式配置 connect/so 超时各 1s；`setMaxWait(1s)` 治池耗尽无限阻塞。public 方法签名零变化。
- **全局熔断器**：`com.itheima.cache.RedisCircuitBreaker`（@Component 单例，无新依赖，AtomicInteger CAS 无锁）。状态机 CLOSED →（连续失败 ≥ 阈值 5）OPEN →（冷却 10s 期满，首个到达请求 CAS 成为唯一探针）HALF_OPEN → 探针成功 CLOSED（日志 INFO"熔断恢复"）/ 探针失败回 OPEN（重置冷却，日志 WARNING）。
- **接线点**：`RedisAccess.execute` 唯一出入口——开头 `tryAcquire()` 拒绝即抛 `CacheException`（快速失败，不取连接）；`finally` 按成败回填 `recordSuccess/recordFailure`。**失败口径（执行定稿）**：从 execute 冒出的 CacheException 计一次失败（包装异常均为 Redis 起源；回调自抛 CacheException 极罕见，计数偏差无害）。**粒度（执行定稿）**：全局单熔断（单 Redis 实例，按域只增探针流量）。双构造器：`@InjectConstructor`（IoC）+ 无参（既有测试直调兼容）。
- **参数（app.properties，均可配）**：`redis.breaker.failureThreshold=5`、`redis.breaker.cooldownMillis=10000`。
- **运行时验证（2026-09-13）**：① 黑洞地址注入（REDIS_HOST=203.0.113.1）→ 启动 init 期熔断开启，后续请求全部"快速失败（未访问 Redis）"；② 真实停 Redis（docker stop）→ 8 次 /start 全 200（13~78ms，无超时等待）→ 冷却期满探针失败重开（日志实证）→ Redis 恢复后探针成功"熔断恢复，回到正常缓存路径"，数据 38B→4KB。脚本：`temp_script/verify_cache_failfast.py`。

### 6.7 降级不放量：降级路径接入单飞（三期 T2 cache-02 新增，治 N1 放量面）

> 目标：Redis 不可用（熔断 OPEN/快速失败）后，**同一 key 的并发读只打一次 DB**——消除 N1 的"降级放量"（此前降级 catch 直接调 loader/DAO，并发请求全部各自打 DB）。`SingleFlight` 类零改动，降级读与 miss 回填**共用同一单飞 key 空间**。

- **统一规则（执行定稿）**：降级读 = 与 miss 路径同款"单飞 + 全量 loader"取数，但**仅装载、不写回**（对齐 D4 与红线"直接走 DB、失败不写回"）。
- **落点（10 处降级分支全量治理）**：`CacheAside` 3 处（`getInternal` catch / `getBatch` 整批 catch / `getBatch` 脏 JSON 单 key catch → `singleFlight.get(key, loader)`）；`FollowCache` 3 处（`isFollowing`/`getSetMembers` catch → 全量装载作答（替代原单行/targeted 查询）；`batchIsFollowing` 增 degraded 标志：降级态单飞全量装载作答、不再走 targeted 批量查询与必失败的回填写入尝试，正常 miss 路径不变）；`LikeCacheService` 4 处（`isContentLiked`/`isCommentLiked` catch 全量装载作答；`batchIsContentLiked`/`batchIsCommentLiked` 降级态逐 cid 单飞装载作答，正常 miss 的 backfill 不变）。防漂移：`FollowCache.loadViaSingleFlight` / `LikeCacheService.loadLikersViaSingleFlight` 为降级装载唯一入口。`CacheAside.read`（无生产调用方）、写路径 catch、`ensureIndex`（已单飞）、`readIndex`（降级空、无 DB 装载，U-11 既有语义）不在范围。
- **失败语义（执行定稿）**：loader 失败 → FutureTask 异常完成 → leader/joiner 均以异常收场（失败不以数据形式共享，无人拿到伪结果）→ 条目 remove → 下一请求全新重试（`SingleFlightTest` 语义保持）；等待无超时=与现状逐请求阻塞等价（分布式锁/超时=R-03）。
- **与 T1 熔断的关系（执行定稿）**：正交互补——熔断管"Redis 访问快速失败"，单飞管"降级后 DB 去重"；熔断 OPEN 后每请求仍进降级分支，单飞仍然必需。
- **统计口径微调**：`LOAD` 从"每降级请求记一次"变为"实际去重后装载记一次（leader 记）"，与 miss 单飞口径一致；`DEGRADE` 仍按请求/key 记。
- **验证**：JUnit **337 例全绿**（CacheAsideTest +3：降级并发同 key 只装载一次且无写回/降级 loader 失败不共享且可重试/批量降级逐 key 去重——并发用例用 mock RedisAccess 恒抛 CacheException，因 MockedStatic 线程局部；FollowCacheTest/LikeCacheServiceTest 降级断言改造 + 各 +1 并发去重）+ pytest all 124 passed + **运行时黑洞验证**（REDIS_HOST=203.0.113.1 启动即熔断开启，20 线程并发同 key `/search/IdSearch`：全部 200 且数据一致，MySQL Com_select 差值仅 **8** 次（无单飞应 ≈60-120，未随并发线性放大），日志实证"熔断开启中，快速失败（未访问 Redis）"；脚本 `temp_script/verify_cache02_degrade_singleflight.py`）+ 独立 subagent 评审通过（无🔴；🟡 抽降级装载公共方法防漂移已落实、🟡 并发窗口观测维持 sleep 惯例记录不修）。

### 6.8 负缓存治理：区分"确认无数据"与"加载失败"（三期 T3 cache-03 新增，治 N2）

> 目标：DB 瞬时抖动时，读路径**不把瞬时故障固化成假数据**——loader 的"确认无数据"与"加载失败"必须可区分，失败**不写 60s 空标记、不 DEL 既有数据 key**。对外行为零变化（内容 404 / 评论空 / 批量逐 key 跳过），只治理缓存写层（用户 2026-09-14 拍板=行为保持，不统一各域对外错误约定）。

- **loader 契约（ContentCache/CommentCache）**：`return null` 仅表示**确认无数据**（DB 无行 / 媒体损坏 / 未知类型）→ 允许 `markEmpty`；**抛 `DatabaseException`** 表示**加载失败**（`TransactionTemplate` 已把 SQLException 包成它；意外异常统一包成 DatabaseException，防 NPE 等静默污染）；`ContentCache.addContent`/`refreshContent`（DB 提交后缓存同步）遇 DatabaseException 静默跳过（refresh 保留旧缓存读自愈，防提交后 500）。
- **CacheAside 契约（新增）**：所有装载点捕获 `DatabaseException` → 记日志转 null——**不写空标记、不 DEL 数据 key**。5 个装载点：`getInternal` miss（内联 try/catch 直接 return null，**跳过 markEmpty**）/ `getInternal` 降级、`getBatch` 整批降级、`getBatch` 脏 JSON 单 key 降级（共用 `loadDegraded` helper，仅装载不写回 D4）/ `getBatch` miss 循环（内联 try/catch 跳过 markEmpty）。契约**仅对 `DatabaseException` 生效**——like/follow 域 loader 抛 `ServerException`（500 语义）不受影响，不统一对外错误约定。
- **单飞语义**：DatabaseException 在单飞 lambda 内被转 null 后 FutureTask 正常完成、条目必然 remove（无残留）；并发等待者同得 null、下一请求全新重试，失败不以数据形式共享。
- **验证**：JUnit **348 例全绿**（surefire 344 + pool 4；新增 7 例：CacheAsideTest +5——miss/降级/批量 miss 逐 key/批量整批降级/批量脏 JSON 单 key 降级 失败均不写空标记不 DEL；ContentCacheTest +2——loader 抛 DatabaseException、addContent 静默跳过；CommentCacheTest 改 2 断言 assertThrows）+ pytest all 124 passed + 独立 subagent 评审通过（无🔴；🟡 4 条：批量降级两路径补 2 例已落实、测试 mock 未走真实事务模板包装=已知限制、双日志级别可接受、全限定名已修）。

### 6.9 空标记写入存在守卫（三期 T4-① cache-04a 新增，治 N3）

> 目标：写路径竞态治理——miss 回填 leader 的 loader 读 DB（读到"无数据"）与回填写空标记之间，若并发业务写刚把真数据写入数据 key，旧实现 `setex(empty:)+del(dataKey)` 会把真数据删掉并固化成 60s 假空（刚发布的内容/评论/点赞状态不可见）。对外行为零变化，只治缓存写层。

- **markEmpty 存在守卫**：`CacheAside.markEmpty` 改为 `if (!j.exists(dataKey))` 才 `setex(empty:{dataKey}, 60)`——数据 key 已存在（并发回填/业务写 `writeOrInvalidate` 刚写入真数据）时跳过，**不写空标记、不 DEL**；**原 `del(dataKey)` 随守卫移除**（守卫内为死代码——进入分支前提即数据 key 不存在；且旧实现竞态下它是 N3 危害组成部分——删并发刚写入的真数据）。守卫检查（exists）失败同归 `CacheException` catch：宁可少写空标记（多一次 DB 查），绝不误写（假空）。
- **残余竞态（已接受）**：exists 检查→setex 之间的毫秒间隙内并发写入时空标记可能覆盖其上——数据 key 未被删，空标记 60s 过期或下次业务写 `writeOrInvalidate` 清空标记即自愈，无真数据丢失。
- **del 移除的行为差异（评审备注，接受）**：缓存 set 已有数据但 DB 已变空（陈旧缓存）时，回填读 DB 空集 → 守卫见数据 key 存在跳过 → **陈旧 set 存活至自身 TTL**（旧实现会 DEL 并固化空标）；期间读命中陈旧数据、TTL 过期后自愈——属"守卫不删真数据"设计的对称代价，权衡可接受。
- **writeSet 定向复用（U-09）**：`FollowCache.writeSet` 空分支由手写"exists 守卫 + setex + del(setKey)"（260913 T5 review 必修②的先例，同一坑只修了 follow 一半）改为统一调 `cacheAside.markEmpty(setKey)`——守卫语义同源、先例守卫内的 del 一并消除；5 处 markEmpty 调用方（CacheAside miss 两处 / LikeCacheService 空分支两处 / FollowCache.writeSet 空分支）全部内聚同一实现。
- **验证**：JUnit **351 例全绿**（T3 末 348 +3：markEmptySkipsWhenDataKeyExists 守卫生效 / markEmptyExistsFailureSkipsQuietly 守卫检查失败保守不写 / concurrentBackfillAndWriteNoFakeEmpty 两线程确定性时序——B 写真数据完成后 A 的 loader 才返回 null，断言无空标记无 DEL 真数据保留；测试适配 6 处断言随 del 移除与 writeSet 复用调整）+ pytest all 124 passed 无回归。

### 6.10 索引写失败自愈（三期 T4-② cache-04b 新增，治 N4）

> 目标：发布内容时 Redis 一次抖动导致 `addToIndex` 失败（content key 已写成功）→ 该内容不在任何 `content:index:*` 里，而 `ensureIndex` 只判索引 key 是否存在 → 懒重建永不触发 → **内容长期不进首页推荐**，只能等重启 init 全量重建。对外行为零变化。

- **自愈机制**：`ContentCache.addToIndex` 写失败（catch `CacheException`）时 **best-effort DEL 本内容所属 4 个索引 key**（`indexKeysOf(type, categoryId)` 与 `lremAndLpush` 同源提取，防两处硬编码漂移）→ 下次推荐读 `getRecommendByFilter` → `ensureIndex` 发现索引 key 缺失 → 走既有单飞懒重建（`INDEX_REBUILD_KEY` 单飞 + `loadAllWithoutMedia` + `rebuildIndexes` 全量 DEL/重建）→ 内容重新进推荐。**复用既有懒重建基建、零新增 key**（否决脏标记 key：触碰 key 命名边界且 T6 U-08 归一要管；否决完整性校验：无便宜一致性信号）。
- **双层 best-effort**：自愈 DEL 也失败（Redis 持续挂）→ `deleteIndexKeysQuietly` 仅记日志不抛——读路径同样降级走 DB，与现状一致、无新增伤害（"任何缓存失败不得导致业务失败"）。
- **失败场景三分收敛**：抖动已过 → DEL 成功自愈；Redis 持续挂 → DEL 也失败与现状一致；DEL 部分成功（命令序列中断）→ 已 DEL 的 key 缺失照样触发全量重建（rebuildIndexes 本身全量 DEL+重建）→ 收敛。
- **残余窗口（已接受）**：Redis 持续挂恢复后索引仍可能不完整（与现状一致，读路径降级兜底）；懒重建触发面仍限 `getRecommendByFilter` 读路径（与现状一致，不做 R-10 定期重建）。
- **验证**：JUnit **353 例全绿**（04a 末 351 +2：addContentIndexWriteFailureDeletesIndexKeysForSelfHeal——写抛/DEL 直通差异化 stub，断言 4 key 各 DEL 一次且无 lpush；addContentIndexWriteAndHealDeleteFailureDoesNotThrow——恒抛双层失败不抛）+ pytest all 124 passed 无回归。

### 6.11 条件写 Lua 原子化（三期 T4-③ cache-04c 新增，治 N7）

> 目标：点赞条件写"探 exists → 再 INCR/SADD"两步非原子——并发失效（内容下架 `deleteContentLike` / 另一请求写失败 `invalidate`）把 count key DEL 后，INCR 以 **1** 重建、DECR 以 **-1** 重建，点赞数在 TTL（15 分钟）内对所有人显示错误值（真实可能上千）。对外行为零变化，仅原子性增强。

- **脚本机制**：`LikeCacheService` 两个 `static final` 脚本常量（包可见，同包测试引用）——
  - `LIKE_CONDITIONAL_SCRIPT`（KEYS=[setKey, countKey, emptySetKey]，ARGV=[userId]）：`DEL empty:` + `EXISTS setKey 才 SADD` + `EXISTS countKey 才 INCR`——与原"pipeline 探测 + 条件写"两趟语义逐条一致，且**合并为一趟 EVAL 往返**；
  - `UNLIKE_CONDITIONAL_SCRIPT`（KEYS=[setKey, countKey]）：`EXISTS setKey 才 SREM` + `EXISTS countKey 才 DECR`——**保持"不清空标记"语义**（空标记=确认无点赞者，unlike 不改变该事实）。
- **治理面 4 方法**：`likeContent` / `unlikeContent` / `likeComment` / `unlikeComment` 统一 `executeVoid(j -> j.eval(...))`——入口线索字面仅点名 like 两方法，unlike 的 DECR 同款竞态（并发失效后以 -1 重建）经用户拍板对称孪生同修（对齐 T2 先例）。
- **语义保持核对**：零新增 key（KEYS 均为既有 key）；不设 TTL（miss 回填路径维护，与现状一致）；冷 key 不创建残缺缓存语义不变；失败降级不变——`executeVoid` 委托 `execute`，一切 RuntimeException 统一包装 `CacheException` + 熔断成败回填，eval 异常/脚本错误走同一通道（catch 降级 = 日志 + WRITE_FAIL + `cacheAside.invalidate(countKey, setKey)`）；Jedis 5.1.0 `eval(String, List<String>, List<String>)` javap 实证，RedisAccess 零改动。
- **验证口径（已接受）**：条件语义内聚脚本由 Redis 服务端原子执行，单测验证 eval 调用参数（脚本 + KEYS/ARGV）——`likeContentSkipsWhenKeysAbsent` 随语义迁移删除，comment 版写路径对称补测 + unlike 失效降级对称覆盖填补既有零覆盖缺口；**运行时 Lua 冒烟**（`temp_script/verify_cache04c_lua.py` 对真实 Redis EVAL，5 场景 10 断言全过：like 命中含清空标记 / like 冷 key 不建残缺缓存 / unlike 命中 / unlike 冷 key 防以 -1 重建 / unlike 保留空标记）。
- **验证**：JUnit **356 例全绿**（04b 末 353 −1 +2 参数断言 +2 对称补测 likeComment/unlikeComment 写路径与 unlike 失效降级）+ pytest all 124 passed 无回归。

### 6.12 启动加载治理（三期 T5 cache-05 新增，治 N5/R-01/R-04，R-01 拍板=全量+工程化优化）

> 目标：启动初始化不再"内容量 × Redis 往返"线性放大（N5：5 万条时约 10 万次 Redis 往返 + 40 万次索引往返），Redis 写入不再占着 DB 事务（H3 原则）。**R-01 取向 = 全量 + 工程化优化**（用户 2026-09-14 拍板：保留"启动预加载全部内容+索引"语义，个人项目流量小、全量在当前数据量无压力；未来若数据量成瓶颈再单独周期评估按需回填/分级加载，本期不预埋开关）。对外行为零变化。

- **事务外写（治 N5 一半）**：`ContentCache.init()` 拆两段——DB 阶段 `transactionTemplate.execute(this::loadBuildableFromDb)` 事务内**只读**（findAllContent + 批量媒体装载 + 构建 DTO，无任何 Redis 调用），事务提交后 `rebuildRedis` 在**事务外**写 Redis。DB 装载失败 → 记日志 return，不触发任何 Redis 写（缓存走读自愈）。
- **批量媒体装载（治 R-04 N+1）**：新增 `ContentMediaDao.findMediaByContentIds(conn, contentIds)`（IN 查询，返回扁平行），`loadBuildableFromDb` 一趟装载全部媒体 + 内存 `groupMediaByContent` 按 contentId 分组，替代原逐条 `findMedia`（DB 查询 N+1 → 恒 2 次）。不复用 `findAllMedia`（避免加载已删除/孤儿内容媒体）。媒体损坏内容跳过 content key 与索引的逻辑原样保留（新批量路径由单测覆盖）。
- **内容 key 批量写**：新增 `CacheAside.writeBatch(map, ttl)`——一趟 pipeline `(setex[per-key TTL 抖动] + del empty:)×N`，失败 → 逐 key `deleteQuietly` 自愈 + WRITE_FAIL 打点（与 `writeOrInvalidate` 失败=DEL 语义一致）；批内单命令 server 错误依赖 `Pipeline.sync()` 抛异常统一兜底。`addContent`/`refreshContent` 单写仍走 `writeOrInvalidate`。
- **索引 pipeline 化**：`rebuildIndexes` 由两次 executeVoid（SCAN-DEL + 逐条 lremAndLpush）并为单条 executeVoid——SCAN 顺序收集旧 `content:index:*` key（游标需顺序读，无法入 pipeline）→ 一趟 pipeline `DEL 全部 + lremAndLpush 全部`。新增 `lremAndLpush(Pipeline, ...)` 重载；`ensureIndex` 懒重建 / `addToIndex` / `removeContent` 零改动。
- **前后对比（验收口径）**：前 = DB N+1 + Redis ≈ **12N 往返**（writeContent 2 + 索引 8 每内容）；后 = DB 恒 **2** + Redis ≈ **3**（内容 pipeline 1 + 索引 SCAN 页 + 索引 pipeline 1），与 N 解耦。运行时前后对比（`INFO commandstats`）可选做；单测 `initRedisRoundTripsConstantForLargeContentCount`（executeVoid 恒 1/execute 恒 0）+ `initLoadsMediaInOneBatchQueryEliminatingNPlusOne` + `initMovesRedisWritesOutsideDbTransaction`（InOrder 事务先于写）为断言依据。
- **验证**：JUnit **362 例全绿**（T4 末 356 +6：ContentCacheTest init 区重写 +5 含媒体损坏跳过/事务外写/批量装载/N+1 消除/往返恒定，CacheAsideTest writeBatch 2——原 04a 单测基数 356）无删除；pytest all **124 passed** 无回归；独立 subagent review 通过（无🔴；🟡 4 条全部落实：媒体损坏跳过补测、writeBatch sync 兜底 javadoc、往返断言注释修正、文档回写随 commit）。

### 6.13 Set 基建组件 SetCache（第四期 T1 cache-01 新增，U-09/N3 收敛落点）

> 目标：把原生 Set 缓存行为（三态读含单成员判定 / 全量读 / 回填含空标记 / 批量判定 / 降级单飞装载）从域类（LikeCacheService / FollowCache 跨类逐字重复）收敛为 cache 包内一处组件，治"改一处缺陷花五遍钱"（N3）。**行为零变化重构**——key 命名、三态语义、空标记 TTL、降级语义一概不变；本任务不接入任何业务（T2/T3 才收口），对外行为零变化。

- **边界**：`CacheAside` 管 JSON String 的 Cache-Aside；`SetCache` 管原生 Set（SISMEMBER/SMEMBERS/SADD），两者平行存在、互不改对方签名。
- **API 面**：`isMember(setKey, member, loader, ttl)` 单成员三态（empty→false / set 存在→SISMEMBER / miss→单飞回填后判成员 / 降级→单飞装载作答不写回）；`getMembers(setKey, loader, ttl)` 全量读（不排序，需确定性顺序的调用方自包装）；`batchIsMember(setKey, members, dbAnswer, fullLoader, ttl)` 单 set 多成员（Follow 形态：miss→DB 批量作答 + 单飞全量回填一次）；`batchKeysIsMember(setKeys, member, dbAnswer, keyLoader, ttl)` 多 set 单成员（Like 形态：miss→DB 批量作答 + 逐 key 单飞回填）；`writeSet` 回填（非空 SADD+EXPIRE 精确 TTL，空→复用 `cacheAside.markEmpty` 含 exists 守卫）；`loadViaSingleFlight` 降级装载（单飞 + LOAD 打点、不写回 D4）。
- **语义要点**：探针续期精确 TTL 无抖动（空标记 key 从不续期）；批量 miss 的 DB 答案（dbAnswer）失败上抛（DB 即真理），回填写 best-effort（失败仅记日志、DB 答案照常返回，4.2 缓存失败不得导致业务失败——**差异记录**：当前 LikeCacheService 批量回填失败上抛，T2 收口后变 best-effort，属收敛方向，T2 执行回写对照）；统计打点粒度保持现状（follow 单 set 一趟一次 / like 每 (key,决策) 一次）。
- **验证**：SetCacheTest 30 例全绿（三态/回填/降级/批量/空标记/续期/统计/并发单飞）；全量 JUnit **395 例**（基线 365 + 30）无回归；pytest all **124 passed** 基线不变。

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
| content/service/ContentCacheTest | 23 | Redis 内容缓存：三态 loader 构建（含媒体 URL）/DB 无媒体损坏降级/索引读取与懒重建（**三期 T5 pipeline 化断言**）/**getContentsBatch 批量读映射与空值透传/推荐批量跳过 null 截断 limit/SCAN 遍历索引**/写路径失效契约/init 重建（**三期 T5：事务外写 InOrder/批量媒体装载 N+1 消除/往返恒定/媒体损坏跳过**）不 crash/失效方法/VO 复制 +**三期 T3 负缓存（loader SQLException 抛 DatabaseException、addContent DB 失败静默跳过）** |
| content/service/CommentCacheTest | 11 | Redis 评论缓存：三态 loader（树构建/deep-chain 归一化/无评论 null/**三期 T3 起 SQLException 与意外异常抛 DatabaseException**）/invalidateComments 显式失效/评论点赞定位失效/collectCommentIds 展平 |
| content/service/FeedServiceTest | 8 | 关注动态流 |
| content/service/ProfileServiceTest | 12 | 用户主页 |
| like/service/LikeServiceTest | 16 | 点赞/取消/读路径委托缓存类/空输入空 map |
| like/service/LikeCacheServiceTest | 23 | Redis 点赞缓存：计数/成员分离三态+单飞回填+空标记+写失败失效+降级 +批量 pipeline+DB 兜底 +delete 失效 +T7 统计接线（LIKE 域 HIT_EMPTY 计数）+**T9 续期接线（hit-data 续期 set key、空标记不续）**+**三期 T2 降级单飞（Redis 异常全量装载作答不写回 + 并发同 key 只装载一次）** |
| comment/service/CommentServiceTest | 16 | 评论归属/楼中楼归一化/软删除（自删+管理员删）/缓存更新 |
| follow/service/FollowServiceTest | 15 | 关注/取关/列表（读路径委托 FollowCache；写路径 DB 提交后缓存双写） |
| follow/service/FollowCacheTest | 29 | Redis 关注缓存：双 Set 三态+单飞回填+空标记（set 存在守卫防并发覆盖）/批量 pipeline+DB 兜底+best-effort 回填/列表 smembers 排序/条件 MULTI 双写+失败双 DEL+降级 +T7 统计接线（FOLLOW 域 MISS/LOAD 计数）+**T9 续期接线（hit-data 续期 set key、空标记不续）**+**三期 T2 降级单飞（Redis 异常全量装载作答不写回 + 并发同 key 只装载一次）** |
| coupon/service/CouponServiceTest | 11 | 抢券/幂等/库存 |
| upload/service/FileUploadServiceTest | 9 | 上传校验/清理旧文件 |
| admin/service/MediaAuditServiceTest | 14 | 媒体扫描/恢复 |
| util/MyConnectionPoolTest | 4 | 满池超时/归还重取/失效移除/关闭后拒绝 |
| cache/CacheKeysTest | 11 | 统一 key 生成格式、empty 前缀、空标记常量 + **三期 T6 U-08（contentIndex 生成格式/生成与 domainOf 解析同源/前缀常量供 SCAN 匹配）** |
| cache/JacksonCodecTest | 4 | DTO 往返、null 处理、TypeReference 泛型、非法 JSON 抛 CacheException |
| cache/RedisAccessTest | 9 | execute/executeVoid 取还连接、异常包装 CacheException（含连接获取失败）+ **三期 T1 熔断接线（熔断开启快速失败不触达连接池/连续失败达阈值拒绝/成功重置不误开/冷却期满探针恢复全链路/回调 CacheException 计失败口径）** |
| cache/RedisCircuitBreakerTest | 8 | 熔断状态机（三期 T1）：默认 CLOSED 放行/连续失败达阈值 OPEN/成功重置计数/冷却期内拒绝/期满唯一探针（含并发抢闸恰一放行）/探针成功闭合/探针失败重开重置冷却 |
| cache/SingleFlightTest | 4 | 并发同 key 只 load 一次、失败/成功 remove、不同 key 独立 |
| cache/CacheAsideTest | 40 | 三态 read、Cache-Aside get 命中/回填/空标记、降级不写回、写失败 DEL、清空标记防假空、markEmpty/invalidate best-effort +T7 统计接线（hitData/MISS+LOAD/降级计数）+**T8 批量读（混合三态/全空标记跳过 loader/miss 空标记回填/整批降级/脏 JSON 单 key 降级/空入参/批量统计打点 + 单 key 一趟 pipeline 往返断言）** +**T9 滑动续期（命中续期抖动/空标记不续/批量续期/续期失败降级不影响读）**+**三期 T2 降级单飞（降级并发同 key loader 只执行一次且无写回/降级 loader 失败异常传播不缓存且下次重试/批量降级逐 key 去重）**+**三期 T3 负缓存（miss/降级/批量 miss 逐 key/批量整批降级/批量脏 JSON 单 key 降级 遇 DatabaseException 转 null 且不写空标记不 DEL）**+**三期 T5 writeBatch（一趟 pipeline 往返+per-key TTL 抖动+清空标记 / 失败逐 key self-heal）** |
| cache/CacheStatsTest | 8 |（T7 新增）观测统计组件：domainOf 域解析全形态/前缀重叠优先级、六类计数分桶、惰性日志触发与摘要、打点异常吞掉 |
| config/AppConfigCacheTtlTest | 5 |（T9 新增）分域 TTL 配置读取：content/comment/like/follow 四 getter 与 app.properties 绑定生效、非 0 互不串读 |
| **合计** | **365** | - |

> 注：`com.itheima.tools.CouponAdmin` 属 tools 测试脚本目录（非测试类，package 保留 `com.itheima.tools`，仅 import java.*，无主代码引用）；`util/MyConnectionPoolTest` 被测类未动（基建），测试文件留在 util 包不迁。
> 用例数取自 `stage8-target/surefire-reports`（2026-09-14 实测，`mvn test` 全绿 365 例 = surefire 361 + 独立 fork pool-test 4；**三期 T6 新增 3 例**：CacheKeysTest contentIndex 生成格式/生成与解析同源/前缀常量）。

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
| 2026-09-14 | 2.16 | **三期缓存加固 T6 收尾（fix(cache-06)，治 U-08 + 全周期闭环）**：**U-08 归一**——索引 key 生成与解析同源，`CacheKeys` 为唯一源：新增 `CacheKeys.contentIndex(type, categoryId)` 生成方法 + `CONTENT_INDEX_PREFIX` 常量，`domainOf` 解析与 `forEachIndexKey` 的 SCAN 匹配模式均引用同一前缀常量（生成/解析/匹配三处同源），原 `ContentCache.indexKey` 私有拼接移除、调用点（buildQueryKey/indexKeysOf）改走 `CacheKeys.contentIndex`；**CacheStats 观测口径确认（T6 强制探索③）**——T1 熔断后 DEGRADE 计数含义仍准确（=本次读未命中缓存、走 DB 兜底次数，含熔断开启快速失败，两者语义一致），enum javadoc 补明示；**残留巡检（T6 强制探索①②）**：T1~T5 无残留（*FromDb 助手/loadBatch/临时开关/临时日志均无；@WebServlet 14 URL、web.xml、IoC 扫描 `scan("com.itheima")` 原样）；JUnit **365 例全绿**（surefire 361 + pool 4，T5 362 + 3：CacheKeysTest 新增 contentIndex 生成格式/生成与 domainOf 解析同源/前缀常量供 SCAN 匹配）+ pytest all 124 passed + 覆盖率地图 rerun 无回归（41 端点全有 pytest）；本文件 4.2/6.2/9.2/12 同步；BUSINESS_FLOW 3.1 注记；TASKS T6 已完成 + 执行回写；NEEDS 4.0 T6 执行定稿；UNPLANNED_ISSUES U-08 标注已消化 |
| 2026-09-14 | 2.15 | **三期缓存加固 T5 启动加载治理（fix(cache-05)，治 N5/R-01/R-04，R-01 拍板=全量+工程化优化）**：`ContentCache.init()` 拆两段——DB 阶段事务内只读（`transactionTemplate.execute(this::loadBuildableFromDb)`：findAllContent + **批量媒体装载** `ContentMediaDao.findMediaByContentIds`（IN 查询，替代逐条 findMedia，DB N+1→恒 2）+ 媒体损坏跳过逻辑原样），事务提交后 `rebuildRedis` 在**事务外**写 Redis（治 N5/H3：Redis 写入不占 DB 事务；DB 失败记日志 return 不触发任何 Redis 写）；内容 key 批量写走新增 `CacheAside.writeBatch`（一趟 pipeline `setex[per-key TTL 抖动]+del empty:×N`，失败→逐 key deleteQuietly 自愈 + WRITE_FAIL，与 writeOrInvalidate 语义一致；批内单命令 server 错误依赖 `Pipeline.sync()` 抛异常兜底）；`rebuildIndexes` pipeline 化（SCAN 顺序收集旧索引 key → 一趟 pipeline DEL 全部 + lremAndLpush 全部，新增 `lremAndLpush(Pipeline,...)` 重载；ensureIndex 懒重建/removeContent/addToIndex 零改动）；**前后对比：Redis ≈12N 往返（内容 2 + 索引 8/内容）→ ≈3 往返（内容 pipeline 1 + 索引 SCAN 页 + 索引 pipeline 1），DB N+1 → 2**，与内容量解耦；JUnit **362 例全绿**（surefire 358 + pool 4 = T4 356 + 6：ContentCacheTest init 区 2→5 例含媒体损坏跳过/事务外写 InOrder/N+1 消除/往返恒定 + CacheAsideTest writeBatch 2）+ pytest all 124 passed + 独立 subagent 评审通过（无🔴，🟡4 条全部落实）；本文件 4.2/4.3/6.12/9.2/12 同步；BUSINESS_FLOW 3.1 注记；TASKS T5 已完成 + 执行回写；NEEDS 4.0 T5 决策 + R-01 已拍板 |
| 2026-09-14 | 2.14 | **三期缓存加固 T3 负缓存治理（fix(cache-03)，治 N2：区分"确认无数据"与"加载失败"）**：`ContentCache.loadContentFromDb` / `CommentCache.loadCommentTree` 移除内层 `catch(SQLException)→null`，SQLException 交由事务模板包成 `DatabaseException` 上抛（=加载失败，不污染空标记）；`return null` 仅保留"确认无数据"（DB 无行/媒体损坏/未知类型）；意外异常统一包成 DatabaseException；`ContentCache.addContent`/`refreshContent`（DB 提交后缓存同步）遇 DatabaseException 静默跳过（refresh 保留旧缓存读自愈，防提交后 500）；`CacheAside` 新增"loader 失败不得上报为空"契约——5 个装载点捕获 DatabaseException 转 null（getInternal miss / getBatch miss 循环 内联 try/catch 跳过 markEmpty；getInternal 降级 / getBatch 整批降级 / getBatch 脏 JSON 单 key 降级 共用 `loadDegraded` helper，删死代码 `loadBatch`），**不写空标记、不 DEL 数据 key**；契约仅对 DatabaseException 生效（like/follow 的 ServerException 500 语义不受影响）；用户拍板=对外行为保持（内容 404/评论空/批量逐 key 跳过），三态/空标记/TTL/key 命名/对外错误约定零改动；JUnit **348 例全绿**（surefire 344 + pool 4，T3 新增 7 例）+ pytest all 124 passed + 独立 subagent 评审通过（无🔴）；本文件 4.2/4.3/6.8/9.2/12 同步；BUSINESS_FLOW 3.1 注记；TASKS T3 已完成 + 执行回写；NEEDS 4.0 T3 执行定稿 |
| 2026-09-13 | 2.13 | **三期缓存加固 T2 降级不放量（fix(cache-02)，治 N1 放量面：降级路径接入单飞）**：`CacheAside` 3 处降级 catch（getInternal / getBatch 整批 / getBatch 脏 JSON 单 key）改 `singleFlight.get(key, loader)`；`FollowCache` isFollowing/getSetMembers catch 改单飞全量装载作答（替代原单行/targeted 查询，删 isFollowingFromDb）、batchIsFollowing 增 degraded 标志降级态单飞全量作答（不再 targeted 批量查询与必失败的回填写入尝试）、删 isFollowingFromDb；`LikeCacheService` isContentLiked/isCommentLiked catch 全量装载作答（删 isContentLikedFromDb/isCommentLikedFromDb）、两批量降级态逐 cid 单飞装载作答；防漂移公共入口 `FollowCache.loadViaSingleFlight` / `LikeCacheService.loadLikersViaSingleFlight`；**统一规则=降级读与 miss 回填共用同一单飞 key 空间、仅装载不写回（D4）；SingleFlight 类零改动；失败异常传播不缓存可重试；与 T1 熔断正交**；LOAD 口径改为 leader 记一次；DAO 单行方法保留（写路径仍用）；JUnit **337 例全绿**（+5：CacheAsideTest +3 / LikeCacheServiceTest +1 / FollowCacheTest +1，并发用例用 mock RedisAccess 因 MockedStatic 线程局部）+ pytest all 124 passed + 运行时黑洞验证（20 并发同 key Com_select 差值仅 8、响应一致、熔断日志实证，temp_script/verify_cache02_degrade_singleflight.py）+ 独立 subagent 评审通过（无🔴）；本文件 4.1/4.2/4.3/6.7/9.2/12 同步；BUSINESS_FLOW 3.1 注记；TASKS T2 已完成 + 执行回写；NEEDS 4.0 T2 执行定稿 |
| 2026-09-13 | 2.12 | **三期缓存加固 T1 韧性底座（fix(cache-01)，治 U-10/N1：Redis 超时 + 熔断快速失败）**：`MyRedisPool` 显式超时化（connect/so 各 1000ms + `setMaxWait(1000ms)`，8 参 JedisPool 构造器 `password/clientName=null` 保持原语义，public 签名零变化；治 U-10 此前走 Jedis 默认 2000ms 未显式化 + 池耗尽无限阻塞）；新建 `cache/RedisCircuitBreaker`（141 行，@Component，无新依赖）——全局单熔断（执行定稿：单 Redis 实例按域无收益）、连续失败 ≥5 开断、冷却 10s 期满 CAS 放行唯一探针、探针成功闭合/失败重开重置冷却（半开重开分支"先写冷却起点后 CAS"，写序经独立评审修正），AtomicInteger CAS 无锁 + 状态迁移日志；`RedisAccess.execute` 接线（tryAcquire 拒绝即抛 CacheException 快速失败不取连接 + finally 按成败回填；失败口径=从 execute 冒出的 CacheException 计一次，执行定稿已回写任务清单），熔断异常由 CacheAside/业务既有 catch 降级路径自然接住、**CacheAside 零改动**；`AppConfig` +5 getter、app.properties +5 键（connectTimeoutMs/soTimeoutMs/pool.maxWaitMs/breaker.failureThreshold/breaker.cooldownMillis）；双构造器兼容（@InjectConstructor IoC + 无参测试直调）；**验证**：JUnit **332 例全绿**（cache 包 58→71：RedisCircuitBreakerTest 8 例 + RedisAccessTest 4→9）+ pytest all 124 passed + 运行时双验证（黑洞地址注入 + 真实 docker stop Redis：熔断开启→快速失败×21 全 200 无超时等待→冷却期满探针失败重开→Redis 恢复探针成功"熔断恢复"，脚本 temp_script/verify_cache_failfast.py）+ 独立 subagent 评审通过（无🔴，🟡 写序与补测两条已落实）；发现既有问题 /start 索引路径 Redis 停机降级为空列表（非 T1 引入，登记 UNPLANNED_ISSUES U-11）；本文件 4.2/4.3/6.1/6.6/9.2/12 同步；BUSINESS_FLOW 3.1 注记；TASKS T1 已完成 + 执行回写；NEEDS 四.0 T1 执行定稿 |
| 2026-09-13 | 2.11 | **C 缓存改造 T9 二期 O-8 TTL 精调（refactor(cache-09)，滑动续期 + 分域取值）**：`CacheAside` 读命中滑动续期——`get`/`getInternal`（新增 `probeRenew`）与 `getBatch` 命中数据 key 时同 pipeline 追加 `EXPIRE`（续期值=原 TTL ±10% 抖动、零额外往返），**空标记（`empty:`）从不续期**（防"假空"窗口延长，执行定稿），`read` 纯三态读不续期；`LikeCacheService`（scanLikeSet/两个批量）与 `FollowCache`（scanSet/getSetMembers/batchIsFollowing）原生 Set 三态读命中同样续期（值=域 TTL）；分域 TTL 取值（app.properties：content 10→30min、comment 保持 10min、like 10→15min、follow 10→30min），依据=双轮轻量压测 CacheStats 摘要（temp_script/pressure_cache.py，基线 vs 续期后：content 恒 100% hitData、comment 38.4%→42.5%、like 59.0%→64.8%）+ 各域读写特性；对外行为零变化（三态/空标记/DEL 降级/key 命名/TTL 永生一律不碰）；JUnit 323 例全绿（surefire 319 + pool 4 = T8 末尾 312 + 新增 11：CacheAsideTest +4 续期、LikeCacheServiceTest/FollowCacheTest 各 +1、AppConfigCacheTtlTest +5）+ pytest all 124 passed；本文件 4.2/6.2/6.5/9.2/12 同步；BUSINESS_FLOW 3.1 注记；NEEDS 4.14 T9 执行定稿 |
| 2026-09-12 | 2.10 | **C 缓存改造 T8 二期读路径加固（refactor(cache-08)，治 H12/H13）**：`CacheAside` 读路径 pipeline 化——`read`/`getInternal`（EXISTS 空标记+GET 一趟往返，内部 `probe` 复用）+ 新增 `getBatch` 批量读接口（一趟 pipeline 批量 EXISTS+GET，三态语义与单 key 完全一致、miss 逐个单飞回填、脏 JSON 单 key/整批降级直接 loader 不写回，统计按 (key, 决策) 打点）；`ContentCache` 新增 `getContentsBatch`，`getRecommendByFilter`（/start 12 条 ≈24+ 往返 → 一趟 pipeline+少量回填）、`FeedService.getFeed`、`ProfileService.getProfile` 页循环改批量读；索引 `KEYS "content:index:*"` → **SCAN**（`forEachIndexKey`，removeContent LREM 与 rebuildIndexes DEL 两处，治 H13）；对外行为零变化（推荐 shuffle/`LRANGE 0 -1`/LREM+LPUSH/三态顺序一概不变）；JUnit 新增 11 例（surefire 308 + pool 4 = 312 例全绿，CacheAsideTest 26 例含批量三态/降级与单 key 一趟往返断言、ContentCacheTest 16 例含批量与 SCAN 多游标）+ pytest all 124 passed；本文件 4.2/4.3/6.4/9.2/12 同步；BUSINESS_FLOW 3.1 读路径往返/KEYS 表述同步 |
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
