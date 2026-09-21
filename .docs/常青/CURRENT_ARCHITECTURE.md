# 当前系统架构地图

> 版本：3.11（2026-09-21 T19：四域分页口径统一——feed / search / profile / follow 均为「域级上限 100 + 域级信封 100」，前端只传 `page`；`BaseServletUtil` 归一逻辑下沉为 request 无关纯函数 `normalizePage`/`normalizePageSize`，无参 / 两参重载与公共 cap 50/10 已删）
> 最后更新：2026-09-21
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
│   ├── 报告/                        # 分析报告（覆盖率地图等）
│   ├── DBbackups/                   # DDL 备份（G9 备份闭环产物，不追踪）
│   ├── archive/                     # 历史存档（勿读，追踪可追溯；结构与顶层一致）
│   └── temp/                        # 临时文档（永不追踪，可删）
│
├── src/
│   ├── main/
│   │   ├── java/com/itheima/        # Java 源码
│   │   ├── resources/               # 配置文件（app.properties）
│   │   └── webapp/                  # Web 应用
│   │       ├── WEB-INF/web.xml      # Servlet 配置
│   │       ├── META-INF/context.xml # Tomcat 配置（/upload 静态挂载 base 为 ${upload.path:-默认} 占位符，见 §5.1.1）
│   │       ├── index.html           # 应用外壳（SPA 入口）
│   │       └── static/              # 前端资源（css/common.css + js/ 基础设施与视图模块）
│   │
│   └── test/
│       ├── java/com/itheima/        # JUnit 单元测试（按被测类同包随迁至各域 service 包）
│       └── python/                  # pytest 端到端脚本
│
├── logs/                            # 运行日志
├── temp_script/                     # 一次性临时脚本（gitignore，可删）
├── tools/                           # 工具脚本（统一入口 tools/tv.py）
└── target/                          # Maven 构建输出（gitignore；测试链路改用项目内 .stage8-target）
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
├── common/                 # 跨域共享模型（T14 新增）：model/dto/PageResult —— 全项目唯一分页信封
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

> 每域内部保留 `controller / service / dao / model` 分层子包，与既有技术层级命名一致（**行数口径 = `wc -l` 换行符数**，下表为 2026-09-20 T15 实测快照；仓库内部分 `.java` 末行无换行符，其 `wc -l` 比编辑器显示少 1 行；以实际代码为准）。
> 跨域依赖允许：feature 包间可互相 import（Java 无包环限制）；任何域不反向依赖基建包。
> **包层环实测（2026-09-20 T14）**：全仓 `src/main/java/com/itheima/**` 的双向包环在 **T14 改造前共 9 个**（`admin↔content`、`comment↔content`、`comment↔like`、`content↔dao`、`content↔like`、`content↔upload`、`content↔user`、`dao↔user`、`ioc↔util`）；T14 消除了其中 **2 个 dao 相关环**（`content↔dao`、`dao↔user`：`dao` 包删除，基础包不再 import 业务模型），**改造后剩 7 个**，且新增的 `common` 包**零出边**（未引入新环）。**`content↔comment` 按 R-03 口径①保留现状**（comment→content 由 10 条降至 9 条——信封上移所致）——其 15 条边中只有 5 条属"组件错位"，其余为真业务互依（内容删除级联软删评论、评论新增改 `comment_count` 并校验内容存在），纯搬移无法单向化，彻底解决需引入抽象层（属新能力，另行立项）。

### 4.2 基建包（保持原位不动）

#### ioc 包 — 依赖注入容器

| 类 | 行数 | 职责 |
|----|------|------|
| IocContainer | 313 | 单例容器：构造器注入优先、字段注入兼容，管理 Bean 生命周期（@PostConstruct → Initializable.init；关闭时 Disposable.destroy / 反射 shutdown）。**注入失败可观测（T17）**：`@Inject` 取不到 Bean 逐条 WARNING + 未注入清单汇总；`ioc.failFast` 可配置（默认 true）注入点即抛 |
| ClassScanner | 47 | 扫描 @Component 注解的类（`scan("com.itheima")` 整根递归，子包增减不影响 Bean 发现） |
| @Component | 10 | 标记为受管 Bean |
| @Inject | 10 | 字段依赖注入 |
| @InjectConstructor | 11 | 构造器依赖注入（带注解的构造器优先） |
| @PostConstruct | 10 | 初始化回调 |
| Initializable | 8 | 生命周期接口：依赖注入完成后调用 init() |
| Disposable | 8 | 生命周期接口：容器关闭时调用 destroy() |

> 4 个注解类在子包 `ioc/annotation/`；其余在 `ioc/` 根。

#### filter 包 — 请求过滤器

| 类 | 行数 | 职责 | URL 匹配 |
|----|------|------|----------|
| ExceptionFilter | 53 | 全局异常处理（业务异常按 code/msg 输出，未知异常 500） | /* |
| EncodingFilter | 27 | UTF-8 编码 | /* |
| LoginFilter | 41 | 解析 JWT Token，设置 userId | /* |
| AuthFilter | 77 | 权限校验（登录 + /api/admin 管理员角色） | /* |

**执行顺序**：ExceptionFilter → EncodingFilter → LoginFilter → AuthFilter（web.xml 注册）

**AuthFilter 保护路径**（`PROTECTED_PREFIXES` 5 项 + `PROTECTED_EXACT` 10 项）：
- 前缀：`/api/upload`、`/api/admin`、`/follow`、`/like`、`/feed`
- 精确：`/comment/add`、`/comment/delete`、`/content/commentEnabled`、`/content/update`、`/content/mediaDelete`、`/content/delete`、`/user/changePassword`、`/user/changeUserName`、`/coupon/grab`、`/coupon/my`
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
| BaseServlet | 15 | 基类，`extends HttpServlet` + `init()` 做 IoC 注入（T16 起不再持有 JSON 响应/mapper，响应统一走 BaseServletUtil） |
| BaseServletUtil | 98 | 静态工具（T16 起不再 `extends HttpServlet`），HTTP 请求/响应侧**唯一 ObjectMapper**（`mapper`，public）+ writeSuccess/writeError + 分页参数解析。**T19 起归一逻辑下沉为 request 无关纯函数** `normalizePage(Integer)` / `normalizePageSize(Integer,max,defaultSize)`（供 JSON body 形态的接口复用同一口径），`parsePage` 与三参 `parsePageSize(req,max,defaultSize)` 委托二者；**无参 / 两参重载与 `DEFAULT_PAGE_SIZE_MAX`/`DEFAULT_PAGE_SIZE` 已删**（三域改用三参后成为死代码，两参重载自 T11-B 起即零调用）——新域一律显式声明自己的 `XXX_PAGE_SIZE_MAX/DEFAULT`；RequestParser 复用同一 mapper |
| RequestParser | 24 | JSON 请求体解析（`BaseServletUtil.mapper` 复用唯一 mapper） |
| AppShutDownListener | 102 | 容器生命周期管理（@WebListener，统一关闭 IoC 容器） |

#### common 包 — 跨域共享模型（T14 新增）

| 类 | 行数 | 职责 |
|----|------|------|
| model/dto/PageResult | 73 | 全项目**唯一**分页信封（`list / total / page / pageSize / totalPages`，`totalPages` 由 total 与 pageSize 推导、`pageSize <= 0` 时为 0）。T14 从 `content.model.dto` 上移，同一提交删除同形的 `follow.model.dto.FollowPageResult`（同形二分消除）；本包**零业务 import**，不引入新的包层环 |

> 规范：所有 DAO 方法只接收 `Connection`，不自行获取/释放连接；连接与事务统一由 Service 通过 TransactionTemplate 管理。
> **T14 起 `com.itheima.dao` 包已删除**：原 `ResultMap` 的 6 个 ResultSet→对象映射方法按域下沉为各自 DAO 的 `private static` 方法（`ContentDao` 2 / `ContentMediaDao` 1 / `CommentDao` 1 / `UserDao` 2），方法体逐行不变；基础包不再反向 import 业务域模型（原 5 条：content×3 / user×1 / admin×1）。

#### cache 包 — 统一缓存基建

> 归属：技术无关缓存基建放 `com.itheima.cache`，业务缓存类（内容/评论/点赞/关注）放各自业务域（各自域内定义 key 命名/TTL/失效逻辑，import 本基建）。内部基于 `util/MyRedisPool` 但不改其方法签名；不引入 Spring/MyBatis/MQ。

| 类 | 行数 | 职责 |
|----|------|------|
| CacheKeys | 193 | 统一 key 命名/生成规范（唯一源）+ 空标记常量（EMPTY_MARKER_TTL_SECONDS=60s）与**部分装载标记**常量（`PARTIAL_MARKER_VALUE`，T11-C）+ `domainOf` 统计域解析（长前缀优先；`empty:` / `partial:` 先解包到底层数据 key）+ `contentIndex(type, categoryId)` 索引 key 生成（前缀 `CONTENT_INDEX_PREFIX`，生成/解析/匹配三处同源）+ 计数/成员/关注各 key 工厂 |
| CacheDomain | 27 | 统计分域枚举：CONTENT/COMMENT/LIKE/FOLLOW/OTHER（LIKE 域含用户维度点赞成员 key） |
| CacheStats | 130 | 观测统计组件：六类事件（HIT_DATA/HIT_EMPTY/MISS/LOAD/DEGRADE/WRITE_FAIL）AtomicLong 计数 + 分域分桶 + 惰性日志（每 N=1000 输出摘要），record 异常吞掉不影响主链路；DEGRADE=本次读未命中缓存、走 DB 兜底次数（含熔断快速失败） |
| JacksonCodec | 64 | JSON 序列化（jackson-databind + jsr310），异常抛 CacheException；忽略未知字段（旧缓存 JSON 兼容，DTO 删/改名后仍可反序列化）、日期 ISO-8601（WRITE_DATES_AS_TIMESTAMPS 关闭） |
| RedisAccess | 90 | 统一 Redis 访问封装：`execute`/`executeVoid` 回调式取还连接（支持同连接 pipeline/MULTI），Jedis 异常包装为 CacheException；唯一出入口接全局熔断（tryAcquire 拒绝即快速失败、finally 按成败回填） |
| RedisCircuitBreaker | 141 | 全局 Redis 熔断器：CLOSED→OPEN（连续失败 ≥5，可配）→（冷却 10s 期满唯一探针）HALF_OPEN→探针成功 CLOSED / 失败重开；AtomicInteger CAS 无锁，状态迁移打日志 |
| SingleFlight | 70 | 统一单飞组件：ConcurrentHashMap+FutureTask，失败/成功均 remove（防缓存失败结果 + 防泄漏）；降级读路径与 miss 回填共用同一 key 空间 |
| CacheStatus | 15 | 三态枚举：MISS / HIT_EMPTY / HIT_DATA |
| CacheResult | 47 | 三态读取结果载体（status + value，HIT_EMPTY 时 value=null） |
| CacheAside | 619 | 统一 Cache-Aside 封装：`read` 三态读 / `get` 带单飞回填 / `getBatch` 批量读（4 参与 5 参批量装载重载）/ `writeOrInvalidate`（写失败=DEL 自愈，写数据同时清空标记）/ `markEmpty`（存在守卫）/ `invalidate`；TTL ±10% 抖动；读路径 pipeline 化（EXISTS 空标记+GET 一趟往返）；命中滑动续期（空标记从不续期）；降级读与 miss 共用单飞、仅装载不写回；loader 抛 DatabaseException 视为加载失败——不写空标记、不 DEL 数据 key |
| SetCache | 393 | 原生 Set 缓存基建：单成员三态 `isMember` / 全量 `getMembers`（不排序，需确定性顺序的调用方自包装）/ 批量判定（`batchIsMember` 单 set 多成员、`batchKeysIsMember` 多 set 单成员）/ `writeSet` 回填（空→`cacheAside.markEmpty` 含存在守卫）/ `loadViaSingleFlight` 降级装载；探针续期精确 TTL 无抖动、空标记不续；批量 DB 答案失败上抛、回填 best-effort。**第六期 T7 起生产调用方 = like 域（`user:likeSet` / `user:commentLikeSet`）**；follow 域已迁 ZSetCache |
| ZSetCache | 623 | 有序集合（ZSet）缓存基建（A1「缓存有序结构」落点）：命令层 ZSCORE/ZRANGE/ZADD，**score = 成员自身数值**（故 ZRANGE 天然按成员数值升序）；API 与 SetCache 同构（`isMember` / `getMembers` / `batchIsMember` / `writeZSet` 回填（空→`markEmpty` 含存在守卫）/ `loadViaSingleFlight` 降级不写回）+ **按序窗口读 `getWindow(key, offset, count, WindowLoader, totalLoader)`**——完整态一趟 pipeline `ZRANGE[start,stop]` + `ZCARD`（total 与页同源）；**T11-C 前缀窗口装载**（miss/部分态/降级/部分态三处配套的完整口径见 6.17）；探针续期精确 TTL（`partial:` 标记与数据 key 同步续期）、空标记不续；窗口装载单飞 key 带窗口指纹 `key@offset+count` 防不同页串用 |

> 测试：`src/test/java/com/itheima/cache/` 9 类单测（mockStatic MyRedisPool + mock Jedis，不碰真实 Redis），用例清单以 `surefire-reports` 为准（见九节指针）。

### 4.3 业务域包（每域 controller/service/dao/model 分层）

#### user 域 — `com.itheima.user`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | LoginController（95，/user/*） | 登录、注册、修改密码/用户名 |
| service | UserService（271） | 用户认证 + 管理员判定 + 改名后级联失效内容缓存（注入 ContentCache）；**`registerAndLogin`（T13，池 U-16 兜底）= 注册 + 自动登录编排**——自动登录失败不回抛，返回 `token=null` 的 LoginVO（注册已提交即算成功），注册本身失败仍抛错 |
| dao | UserDao（275） | users 用户 CRUD + 角色查询（`findUsersByIds` T7 起带 `ORDER BY id`：关注/粉丝列表顺序由此保证，唯一调用方 FollowService）；T14 起内含从 `dao.ResultMap` 下沉的 `buildUserForLogin` / `buildUserForProfile` 两个 `private static` 行映射方法 |
| model | entity/User（89）、dto/LoginDTO（28）/RegisterDTO（41）/ChangePasswordDTO（35）/ChangeUserNameDTO（15）、command/LoginCommand（63）/RegisterCommand（44）/ChangePasswordCommand（44）/LoginType（7）、vo/LoginVO（40） | 用户实体与请求/命令/响应对象 |

#### content 域 — `com.itheima.content`（含共享缓存组件）

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | ContentController（181，/content/*）、StartController（49，/start）、SearchController（101，/search/*）、FeedController（37，/feed）、ProfileController（50，/profile） | 内容管理 + 首页推荐 + 搜索 + 关注流 + 用户主页；**T19：三域统一为「域级上限 100 + 域级信封 100」**（各 Controller 自持 `XXX_PAGE_SIZE_MAX/DEFAULT`；`/search/keywordSearch` 的 GET 与 JSON body 两条分支共用 `BaseServletUtil.normalize*`，前端只传 `page`） |
| service | ContentService（497）、ContentCache（710）、CommentCache（716）、ContentStatusFiller（81）、FeedService（107）、ProfileService（118） | 内容业务（内容/搜索 + 评论读路径编排）+ Redis 内容缓存（三态 Cache-Aside + 索引 + `invalidateAuthorContentKeys` 改名级联失效 + `getContentsBatch` 批量装载）+ Redis 评论缓存（两键组 主楼 List + 楼中楼 Hash + 主楼窗口/count）+ 点赞/关注状态填充 + 关注流 + 主页；**Feed/Profile/Search 与关注·粉丝列表的缓存读一律在 DB 事务外**（见 6.4） |
| dao | ContentDao（446）、ContentMediaDao（202） | content/content_media 数据访问（ContentLikeDao 按 like 域归属）；`findContentsByIds` 批量 IN 查询（供批量缓存装载，列与 findContent 同源）；T14 起各自内含从 `dao.ResultMap` 下沉的 `private static` 行映射方法（2 / 1） |
| model | entity/ContentMedia（63）、cache/ContentCacheDTO（135）/CommentCacheDTO（119）、vo/ContentVO（42）/ContentDetailVO（25）/CommentVO（21）/ProfileVO（43）、dto/SearchDTO（51）、command/CommandConverter（139）/ContentType（16） | 内容模型 + 共享缓存 DTO + 共享 VO/DTO/转换器（`PageResult` 归 `common.model.dto`） |

> **共享组件归属**：ContentCache / CommentCache / ContentStatusFiller / ContentCacheDTO / CommentCacheDTO / CommandConverter / ContentVO / ContentDetailVO / CommentVO 归本域，其它域 controller/service 跨域 import（**`PageResult` 已于 T14 上移 `common` 包，不再归本域**）。

#### follow 域 — `com.itheima.follow`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | FollowController（104，/follow/*） | 关注/取关/关注列表/粉丝列表（**T11-A：列表只有分页入口**——`page`/`pageSize` 均可选，缺省归一为 page 1 / 信封 100；域级常量 `FOLLOW_PAGE_SIZE_MAX = 100` + 信封 `FOLLOW_PAGE_SIZE_DEFAULT = 100`（**T19 由 200 调整为 100**，与 feed/search/profile 同口径），T7 的「缺省返回全量数组」分支已删除） |
| service | FollowService（185） | 关注业务（读路径委托 FollowCache；关注/取关 DB 提交后缓存双写；**T7 新增分页读**——缓存窗口取该页 ids+total，仅对该页 ids 做 DB 装载与批量判重，信封在事务外组装；**T11-A 删除两个缺省全量重载**，分页读为唯一入口；**T12 起事务回调只做 DB 装载**（`findUsersByIds`），`batchIsFollowing` 与视图组装移事务外；**T14 起信封用公共 `common.model.dto.PageResult`**） |
| service | FollowCache（539） | 关注关系 Redis 缓存（**双 ZSet（score=成员 id）+ 条件 MULTI 双写 + 失败双 DEL** + 三态读 + 单飞 + 降级单飞全量装载作答；读路径收口 **ZSetCache**——单成员三态/批量/全量/窗口走基建 + `sortIds` 归一升序，写路径 MULTI 双写语义保持；关注/粉丝计数 key 读写。**T11-C**：窗口 loader 换 DAO **窗口 SQL**（分页读不再全量装载）、新增部分态判定回落 `isFollowingInDb`（单行）、`probePair` 扩为六探针且**任一侧 `partial:` → 三件套双 DEL**（增量写分支与 Redis 异常分支同口径：异常分支走新增私有 `invalidatePairQuietly`，而 `CacheAside.invalidate` 只删数据 key + 空标记）、删除已无主代码调用方的 `getFollowerIds`（池 U-21）） |
| dao | FollowDao（155） | follow 关注关系（仅 FollowService 业务校验与 FollowCache loader 使用；**T11-C-1 新增两个窗口查询**：`getFollowedUserIdsInWindow` / `getFollowerUserIdsInWindow`——`WHERE … ORDER BY … LIMIT ? OFFSET ?`，供前缀窗口装载；关注方向复用 `uk_user_follow`、粉丝方向走新增 `idx_followed_user_user`，EXPLAIN 均 `Using index`（覆盖索引）且无 filesort） |
| model | —（T14 起无专属 model） | 关注/粉丝列表分页信封改用公共 `common.model.dto.PageResult`（T14）；原 `FollowPageResult`（T7 B2 为避开 follow→content 环而自建的同形类）已随 T14 删除，同形二分消除 |

> 注：FeedService/ProfileService/ContentStatusFiller 跨域 import `follow.service.FollowCache`（服务层），不再直连 FollowDao。守关注读路径走缓存、写路径 DB 提交后双写。

#### like 域 — `com.itheima.like`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | LikeController（113，/like/*） | 点赞/取消点赞 |
| service | LikeService（215）、LikeCacheService（393） | 内容/评论点赞业务 + Redis 点赞缓存（计数/成员分离；成员 key 为用户维度，读路径收口 SetCache——content/comment 孪生方法合并为 id 维度参数化单实现、批量回填 best-effort；写路径 Lua 条件写原子化） |
| dao | ContentLikeDao（137）、CommentLikeDao（129） | content_like / comment_like 数据访问 |
| model | — | 无专属 model |

> 注：ContentService 删除作品级联清点赞时 import `like.ContentLikeDao`（反向跨域）。

#### comment 域 — `com.itheima.comment`

| 层 | 类（行数） | 职责 |
|----|------|------|
| controller | CommentController（160，/comment/*） | 评论发表/查询/删除（**T8/T11-B：`/show`、`/replies` 可选 `page`/`pageSize`**——`/show` 显式判"是否传分页参数"分支：缺省全量数组、传参走分页信封；`pageSize` 缺省取域级信封 **200**、上限 **500**） |
| service | CommentService（299） | 评论业务（楼中楼：发表归一化主楼 + 软删除：用户自删/管理员删）；**T14 起分页信封改用公共 `common.model.dto.PageResult`**（原 import content 域信封） |
| dao | CommentDao（368） | comment 评论 CRUD + 软删除（整楼/单条）+ 楼内回复计数 + 评论所属内容定位；T14 起内含从 `dao.ResultMap` 下沉的 `buildComment` `private static` 行映射方法 |
| model | dto/CommentDTO（44）、command/CommentCommand（50） | 评论请求/命令（CommentVO 归 content 域；**T14 R-03 口径①：content↔comment 包层环保留现状**，未拆分共享组件——环的 15 条边中仅 5 条属组件错位，其余为业务互依） |

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
| controller | MediaAdminController（74，/api/admin/media/*）、AdminContentController（73，/api/admin/content/*）、AdminCommentController（46，/api/admin/comment/*） | 媒体运维 + 内容审核下架 + 评论运维（仅管理员） |
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

### 5.1.1 配置外部覆盖机制（T18，N13）

- **加载**：`config/AppConfig` 从 classpath 的 `app.properties` 加载全部键（缺失/解析失败启动即失败）。
- **外部覆盖两级**（优先级从高到低）：
  1. **环境变量**：键去点转大写（`db.password` → `DB_PASSWORD`；`log.file` 额外支持 `LOG_PATH` 别名）；
  2. **JVM 系统属性**：`-Dkey=value`（T18 新增，与 Tomcat context.xml 的 `${key:-default}` 占位符**同源**）。
- **`upload.path` 与 `/upload` 挂载**：`src/main/webapp/META-INF/context.xml` 的 `<PostResources base="${upload.path:-D:/data/projects/VideoPlatform/stone}"/>`（T18）——
  Tomcat 对 `${...}` 做系统属性替换（SystemPropertySource 始终启用），**默认值必须与 `app.properties` 的 `upload.path` 保持同步**；
  换环境部署时用 `-Dupload.path=<目录>`（`CATALINA_OPTS`/`JAVA_OPTS`/setenv，不改源码/不重打包）即可同时覆盖静态挂载与上传落盘；
  `AppShutDownListener` 启动强校验"Tomcat 实际挂载 == AppConfig 实际使用"，不一致拒绝启动。
  ⚠️ 只设 `UPLOAD_PATH` 环境变量（不设 `-D`）时 context.xml 挂载仍是打包默认值 → 校验拒绝启动（与 T18 前行为一致）；覆盖 `upload.path` **推荐用 `-Dupload.path`**。
- **带默认值读取**：`AppConfig` 提供 `get/getInt/getLong/getBoolean(key, default)` 包可见重载（T18，键缺失/空 → 默认值；数值解析失败照旧抛）——新配置键无需预置 app.properties 默认即可读取。

### 5.2 数据库表

| 表名 | 说明 | 关键字段 |
|------|------|----------|
| users | 用户表 | id, username, hashed_password, phone, follow_count, follower_count, role（0=普通/1=管理员） |
| content | 内容表 | id, user_id, title, description, type, category_id, comment_count, like_count, comment_enabled, is_deleted, create_time, file_exists, last_verify_time |
| comment | 评论表 | comment_id, content_id, user_id, content, parent_id, reply_to_user_id, like_count, is_deleted, reply_count |
| follow | 关注关系表 | user_id, followed_user_id |
| content_like | 内容点赞表 | user_id, content_id |
| comment_like | 评论点赞表 | user_id, comment_id |
| content_media | 内容媒体表 | content_id, url, media_type(1=视频,2=图片,3=封面), sort, file_exists, last_verify_time |
| coupon | 优惠券表 | id, title, stock, begin_time, end_time |
| coupon_order | 优惠券领取表 | coupon_id, user_id, coupon_code（唯一索引） |

> `content.is_deleted` 语义：`0=正常 / 1=作者删除（A1，不可恢复）/ 2=管理员下架（A2，可恢复）`；前台可见性统一按 `is_deleted = 0` 过滤。

> 库中另有 **3 张遗留表** `video` / `videoinfo` / `comment_media`（原型期残留，仍有数据）——`src/main` 与 `src/test` 全量 grep **零引用**，本表只列业务在用表。

### 5.3 特殊索引

- content 表：全文索引 `MATCH(title, description) AGAINST(? IN NATURAL LANGUAGE MODE)`
- coupon_order 表：唯一索引 `(coupon_id, user_id)`
- comment 表：`idx_content_parent (content_id, parent_id)`（**T10-A**，G9 备份闭环已落地）——主楼区间扫描与楼中楼按主楼批量取数（`parent_id IN (…)`）需要"等值列 + 范围/IN 列"同序
- follow 表：`idx_followed_user_user (followed_user_id, user_id)`（**T11-C**，G9 备份闭环已落地）——粉丝方向**窗口查询** `WHERE followed_user_id=? ORDER BY user_id LIMIT ? OFFSET ?` 需要"等值列 + 排序列"同序，原 `idx_followed_user_id(followed_user_id)` 只能等值定位、排序仍需 filesort；关注方向复用既有 `uk_user_follow(user_id, followed_user_id)`（已有同序），不新建索引。EXPLAIN 实测两方向均 `Using index`（覆盖索引）且无 `Using filesort`。

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
| content:comments:{contentId}:roots | LIST\<主楼 JSON，无 children\> | 评论主楼序列（**T10-A 两键组①**：comment_id 升序=装载序，LRANGE 窗口读 + RPUSH 尾追加；水位不足时 DB keyset 窗口装载） |
| content:comments:{contentId}:replies | HASH\<field=主楼 id, value=children JSON\> | 评论楼中楼（**T10-A 两键组②**：HMGET 只拉该页主楼，页成本 ∝ 该页；field 缺失懒载） |
| content:comments:{contentId}:count | String(int) | 评论主楼总数（**T10-A 真实 total**：首装惰性 COUNT 一次，增删主楼随 roots 失效重算） |
| empty:{dataKey} | String "1" | 空标记：已加载确认无数据（短 TTL 60s，不参与滑动续期） |
| partial:{dataKey} | String "1" | **部分装载标记（T11-C）**：存在 ⇒ 集合**不完整**（已知内容为数据 key 按序的**前 W 个**，W = ZCARD）；不存在 ⇒ 完整（取代"数据 key 存在 ⇒ 完整"的旧不变量）。与 `empty:` 互斥；TTL = 域 TTL 且**与数据 key 读命中时同步续期**（两者生命周期错位会让前缀被误判为完整集合 → 静默漏成员）；写路径失效时与数据 key / 空标记**三件套一起 DEL** |
| content:likeCount:{contentId} | String(int) | 内容点赞计数（高频读，计数/成员分离） |
| comment:likeCount:{commentId} | String(int) | 评论点赞计数 |
| user:likeSet:{userId} | Set\<contentId\> | 我点赞过的内容（用户维度成员，装载量=该用户点赞数，与内容热度解耦） |
| user:commentLikeSet:{userId} | Set\<commentId\> | 我点赞过的评论（用户维度成员） |
| user:following:{userId} | ZSet\<followedUserId\>（score=id） | 我关注了谁（MULTI 双写，失败双 DEL；**T7 起为 ZSet**：`ZRANGE[start,stop]` 窗口读 + `ZCARD` 取总数，ZRANGE 天然按 id 升序；**T11-C 起可为"前缀"**——带 `partial:` 标记时成员只是 DB 按序的前 W 个，判定未命中需回落 DB、全量读需补齐） |
| user:follower:{userId} | ZSet\<userId\>（score=id） | 谁关注了我（逻辑同 user:following） |
| user:followCount:{userId} | String(int) | 我的关注数（独立计数 key，Cache-Aside，0 合法；写路径条件 INCRBY、冷 key no-op 由读回填） |
| user:followerCount:{userId} | String(int) | 我的粉丝数（逻辑同 user:followCount） |

> 索引 key 生成/解析/匹配同源：唯一源 = `CacheKeys.contentIndex(type, categoryId)`（前缀常量 `CONTENT_INDEX_PREFIX="content:index:"`），`domainOf` 解析与索引 SCAN 匹配引用同一前缀，业务包不自行拼接 key。
> 旧 key（`content:like:{id}` / `comment:like:{id}`、内容/评论维度成员 `content:likeSet:{id}` / `comment:likeSet:{id}`）已随计数分离与成员反转停用，不双写，TTL 过期自然回收。
> 一致性由 **启动全量重建 + 索引懒重建 + 业务显式失效（增删改/计数/门禁/隐藏恢复）+ Cache-Aside 读自愈** 承担，无周期性全库重载。

### 6.3 统计观测（CacheStats）

- **组件**：`com.itheima.cache.CacheStats`（@Component，固定 `AtomicLong[5][6]` 计数数组，无锁无扩容）。纯计数与日志，不打任何新 Redis 命令、不改缓存读写语义。
- **六类事件**：HIT_DATA / HIT_EMPTY / MISS / LOAD / DEGRADE / WRITE_FAIL。
- **分域分桶**：`CacheKeys.domainOf(String dataKey)` 唯一解析源——**长前缀优先**（content:index / content:comments 先于通用 content:；user:like* / user:commentLike* 先于通用 user:）；`empty:` 空标记与 `partial:` 部分装载标记（T11-C）先解包到底层数据 key 再归域；映射：content:index / content:{id}→CONTENT、content:like*/comment:like* / user:like*/user:commentLike*→LIKE、content:comments / comment:*→COMMENT、user:following / user:follower / user:followCount*（user:* 兜底）→FOLLOW、未知/null→OTHER。
- **惰性日志输出**：每 N=1000 次记录输出一次各域摘要（INFO 单行）；不引入定时器、不新增 admin 端点。
- **挂点**：CacheAside 自动打点（三态读 + 降级 + LOAD + 写失败）；SetCache / ZSetCache 自动打点；LikeCacheService / FollowCache 原生路径手动打点（批量记录粒度=每 (数据 key, 决策) 记一次）。
- **红线段**：`record()` 自身异常吞掉记 WARNING，不影响主链路；统计不引入 MQ。
- **用途**：分域命中率/穿透曲线为分域 TTL 与读路径优化提供数据依据。

### 6.4 读路径

- **单 key pipeline 化**：`CacheAside.read` / `getInternal` 由"EXISTS 空标记 + GET 数据 key 两趟往返"合并为**一趟 pipeline**（内部 `probe(dataKey)` 复用，三态/空标记/单飞/降级语义与统计不变）。
- **批量读接口**：`CacheAside.getBatch`——一趟 pipeline 批量 EXISTS+GET，三态判断与单 key 完全一致（先空标记后数据 key），miss 项逐个单飞回填；**4 参重载**：脏 JSON 单 key / 整批 Redis 异常 → DEGRADE + 直接 loader 不写回；**5 参重载**（`BatchLoader`）：miss/整批降级子集经 `LoadMemo`/`LoadOutcome`（LOADED/EMPTY/FAILED）**一趟批量装载**，漏 key 按加载失败不写假空；逐 key 单飞去重、三态/续期/空标记/降级/打点口径不变。调用方保证 key 无重复。
- **内容批量接入**：`ContentCache.getContentsBatch(List<Long>)`（id → DTO 映射，null 值=hit-empty/DB 无数据透传）；miss/降级装载走 `loadContentsFromDb` **一趟事务两查**（`ContentDao.findContentsByIds` + `ContentMediaDao.findMediaByContentIds`）；Feed/Profile 使用，**Search 于 T12 接入**（原逐 key `getContent`）。
- **推荐惰性探测**：`getRecommendByFilter` 按 shuffle 序**逐个 `getContent`、凑满 limit 即止**（探测量从"候选数 × 3 命令"收敛到 ≈3×(limit+跳过量)，与候选总量解耦）；shuffle 仍在全量去重 id 列表上一次性执行，返回集="shuffle 序前 limit 个非 null"，**推荐结果分布语义不变**。
- **Feed/Profile 事务外读**：事务回调只做 DB 查询（`ProfileDbData(user, pageIds, total)` / `FeedDbData(pageIds, total)` 私有 record 回传），提交归还连接后**事务外**批量读缓存 + 填点赞状态 + 组装 VO；两个早退分支（无关注 / total==0）与改造前一致**零缓存调用**；404/500 异常仍只在事务回调内产生。
- **Search/关注·粉丝列表事务外读（T12，治池 U-14，同型未治点清零）**：`ContentService.search` 的事务回调只留两次 DAO 查询（`SearchDbData(contentIds, total)` 私有 record 回传；命中总数与页内 id **都不省略**），`FollowService.loadUserList` 的事务回调只留 `userDao.findUsersByIds`（`List<User>` 直接回传）；两者提交归还连接后，才在**事务外**做缓存批量读（`getContentsBatch` + `fillLikeAndFollowBatch`（点赞/关注状态）／`batchIsFollowing`）并按原序组装结果。`FollowService` 的判重仍按**该页 ids** 批量查（不因 users 为空而跳过），`SQLException → ServerException("查询失败"/"搜索失败，请重试")` 仍只在回调内产生；空 ids 分支保持"不打事务、不触碰缓存"。全仓扫描（56 处 `transactionTemplate.execute`）确认回调内**无缓存读**残留（缓存类内部 loader 自身的事务除外）。
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
- **落点（降级分支全量治理）**：`CacheAside` 3 处（`getInternal` catch / `getBatch` 整批 catch / `getBatch` 脏 JSON 单 key catch）；`FollowCache`/`ZSetCache` 3 处（`isFollowing`、`batchIsFollowing` 为单飞全量装载作答；**`getWindow` 窗口读自 T11-C 起改为"单飞 + DB 窗口直查"**——只查被看的那一段、**不装载不写回**，单飞 key 带窗口指纹 `key@offset+count`）；`LikeCacheService` 4 处（`isContentLiked`/`isCommentLiked` catch 全量装载作答、两批量降级态逐 cid 单飞作答）。防漂移：`SetCache.loadViaSingleFlight` / `ZSetCache.loadViaSingleFlight` / `LikeCacheService.loadLikersViaSingleFlight` 为降级装载唯一入口。
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

### 6.17 关注关系有序化与分页窗口（T7 → T11-A 域级信封/缺省反转 → T11-C 前缀窗口装载 → T19 信封 100）

- **Set→ZSet 有序化**：`user:following:{userId}` / `user:follower:{userId}` 由 Set 升级为 ZSet（score = 成员自身 id）；写路径 `SADD/SREM → ZADD/ZREM`（MULTI 条件双写 / 双 key 探针 / 空标记 / TTL 骨架不变），读路径收口**新建的 `ZSetCache`**（与 SetCache 平行，命令层 ZSCORE/ZRANGE/ZADD）。like 域成员 key 仍为 Set、仍走 SetCache（不受影响）。
- **等价性依据**：score = 成员数值 ⇒ `ZRANGE` 遍历序 = 成员数值升序 = 改造前 `sortIds` 升序口径，全量读/单项判定/批量判定的对外结果与顺序**逐条不变**。
- **分页窗口读**：`ZSetCache.getWindow(key, offset, count, WindowLoader, totalLoader)` **窗口取数**在完整态为一趟 pipeline（`ZRANGE[start,stop]` + `ZCARD` → `Window{ids,total}`；探针一趟另行，与 SetCache 同模式）；**hit 路径 O(log n + N)**（不再 `SMEMBERS` 全量回传 + String→Long 装箱）。
- **前缀窗口装载（T11-C，治池 U-18）**：装载量改与**页位置**相关、而非列表总量（场景锚：某博主 100 万粉丝时，任何一次粉丝列表分页都不得触发百万行装载）——`miss`（冷 key）只装载 `[0, offset+count)`；命中但带 `partial:` 标记时，页落在已知前缀 `[0, W)`（W=ZCARD）内直接 `ZRANGE`（**零 DB**），越过 `W` 则**只补** `[W, offset+count)`（DAO 窗口 SQL `ORDER BY … LIMIT ? OFFSET ?`），DB 返回不足即到底 → 清 `partial:` 转完整态（此后回到 ZCARD 口径）；**补齐上界不依赖 total**（DB 返回不足即到底，计数漂移不影响装载量）。**降级（Redis 异常）语义随之变化**：由"全量装载 + 内存切片"改为 **DB 窗口直查**（只查被看的那一段、单飞去重、不装载不写回）——第三期 T2"降级不放量"口径不变，首次在常青文档写明（不是遗漏）。
- **部分态三处配套（T11-C）**：① **判定**——`isFollowing` 的 ZSCORE 未命中、`batchIsFollowing` 的未命中成员，在带 `partial:` 时**回落 DB**（前者单行 `isFollowingInDb`、后者复用既有批量 `dbAnswer`；前缀里查不到 ≠ 不是成员），且批量路径**跳过全量回填**（不把装载量重新放大）；② **全量读**——`getMembers` 遇 `partial:` **必须先补齐**再返回，且**返回 DB 装载结果本身、不回读缓存**（补齐的缓存写回是 best-effort，写失败时回读只能拿到前缀；`FeedService` 依赖全量关注 ids，返回前缀会静默漏关注者——本设计最危险点，JUnit 已覆盖含写失败路径）；③ **写路径**——`probePair` 扩为六探针，**任一侧 `partial:` → 三件套双 DEL**（取关会在前缀里留"洞"、关注会插入非前缀成员，两者都破坏 `ZRANGE offset` 的偏移语义），与既有"冷 key → 双 DEL"同构。**残留（不在本任务范围）**：`getFollowingIds` 的 feed 全量关注 ids 读路径本身（R-01 明确保留）。
- **顺序保证**：分页切片的成员集合与顺序来自 ZSet 升序（score=成员 id）；**列表最终输出顺序由 `UserDao.findUsersByIds` 决定**，T7 已为该查询补 `ORDER BY id`（此前无 ORDER BY，输出序依赖存储引擎默认序——HEAD 既有脆弱点，唯一调用方为 FollowService），使"ZSet 升序切片"与"DB 返回序"同口径，分页顺序稳定**由构造保证**而非巧合。
- **接口口径（T7 B2 → T11-A 契约变更，已获用户批准）**：`GET /follow/following|followers` **始终**返回 `data = {list,total,page,pageSize,totalPages}`（分页信封 `com.itheima.common.model.dto.PageResult`——**T14 起全项目唯一信封**，原 follow 域 `FollowPageResult` 已删除）。
  - `page` 缺省 1；`pageSize` 缺省 **100**（`FOLLOW_PAGE_SIZE_DEFAULT` = 域级信封）、上限 **100**（`FOLLOW_PAGE_SIZE_MAX`；T11-A 原 50 → 200，**T19 调整为 100**）。显式传 `pageSize` 仍生效（**不采纳"后端硬忽略参数"**：T19 窗口复核——该能力对 follow/comment 是**既有用例依赖**，对 feed/search/profile 是**未来唯一低成本验证手段**，硬忽略只会封死验证路径而不带来收益）。
  - **缺省（不传任何分页参数）= 第一页信封**，与显式 `page=1&pageSize=100` 响应**逐字节一致**；T7 的「两者都不传 → `data` 仍为全量数组」分支**已删除**——该分支同时是"一次拉全量"的攻击放大面。
  - 参数解析走 `BaseServletUtil.parsePageSize(req, max, defaultSize)` **三参重载**（T11-A 新增，**T19 起为唯一的 request 形态重载**）：传了 → `min(s, max)`、未传 → `defaultSize`；超上限/非法/≤0 一律回落域级信封。`page < 1` 归一为 1；越界页返回空数组但保留 total。
- **total 口径（T11-C 双口径）**：**完整态**（无 `partial:` 标记）= 同一 ZSet 的 `ZCARD`（与页内容同源，与 T7 **逐字节一致**）；**部分态 / 降级态** = 本域**计数口径**（`FollowCache.getFollowCount` / `getFollowerCount` → `user:followCount`/`user:followerCount`，miss 回落 `users` 表计数列）——此时成员集只是前缀，ZCARD 会低估总数。完整态不用计数 key 顶替（两 key 可能瞬时不一致）。
- **前端**：`static/js/views/user.js` 的关注/粉丝 sheet 接公共 **`chunkedList`** helper（`static/js/chunkedList.js`——T10-B 抽出，T11-A 为第二个消费方，T11-B 起的完整消费方清单见 6.19）：请求**只传 `page`**（信封大小由后端域常量决定，前端不再出现 pageSize 魔法数）、大 chunk 100（**T19 由 200 调整为 100**）+ 本地小批 10，本地余量足够时「加载更多」**0 请求**；每次 `openUserList` 重建实例（等价 reset，防 following/followers 本地余量串台）。
- **包层边界（T14 更新）**：分页信封**已上移公共包** `com.itheima.common.model.dto.PageResult`（T14 治池 U-19）——原 T7 的规避口径"信封落在 follow 域、不 import content 域 `PageResult`（会形成新的 follow↔content 包层环）"及其"登记留池待上移"的尾巴**随公共包落地而失效**；`common` 包零业务 import，`FollowService` 由 follow→common 单向引用，不新增包层环。

### 6.18 评论列表分页（T8 → T10-A/T10-B：两键组 + 主楼窗口装载 + 楼中楼前 K + 展开接口）

- **形态（T10-A 起）**：**两键组 + 主楼窗口装载**——`content:comments:{id}:roots`（LIST，主楼 JSON 无 children，comment_id 升序）+ `content:comments:{id}:replies`（HASH，field=主楼 id，值=该主楼 children **前 K=2 条** JSON）+ `:count`（真实主楼总数）。命中路径 = 主楼 LRANGE 窗口 + 楼中楼 HMGET 该页主楼（**单次成本 ∝ 该页**）；DB 窗口装载只发生在 List 水位不足时（keyset `comment_id > lastId LIMIT`）；**楼中楼缓存只存前 K**（懒载 DB 全量取该主楼、HSET 截断前 2），命中路径反序列化与楼中楼总量解耦。
- **契约（T10-B，已批准变更）**：分页信封每主楼只带 **children 前 K=2 条 + `replyCount` 字段**（该主楼回复总数，= 建树上溯后 children 数，来自 DB `comment.reply_count`）；**展开剩余走 `/comment/replies`**；**缺省数组**（不传参）children 全量随行、逐字节兼容，实现 = **DB 全量直取**（getComments + 上溯建树，不占两键组）。
- **展开接口 `/comment/replies`**（T10-B）：`rootId&page&pageSize` → 分页信封；`total` = 该主楼 `reply_count`（与 children 前 K 口径一致）；list = 直接回复 + 间接二级回复（keyset 升序，与建树上溯口径一致——新数据 parent 归一挂主楼、seed 存量最多二级间接），点赞态仅该页批量。
- **切片点**：原 `ContentService.sliceRoots` 已删除（T10-A）；切片由 `CommentCache.getRootPage`（LRANGE 窗口取数）承担；越界页空列表但 `total` 真实。
- **失效重映射（DB 源真理 + 失效自愈）**：增主楼 → `invalidateRoots`（roots+count，读懒重建）；**增回复/删回复/点赞** → `reply_count` 增量维护（+1/−1 防负守卫，删主楼不扣）+ 定向 HDEL 该主楼 replies field（懒载刷新前 K）；`notifyCommentLikeChanged` 经 `getRootIdByCommentId` 上溯主楼后定向失效。
- **接口口径**：`GET /comment/show` 传任一 → 信封；不传 → 全量数组。分页解析：page 默认 1；`pageSize` **缺省 = 评论域信封 200**（`CommentController.COMMENT_PAGE_SIZE_DEFAULT`，T11-B——前端只传 `page`）、上限 **500**（`parsePageSize(req, max, defaultSize)` 三参重载，显式传参仍生效）。**评论域 T19 不动**：上限/信封保持 500/200，是四域中唯一未收敛到 100 的域（后续域级改造另立任务评估，可能含热度排序）。
- **total 口径**：主楼 `total` = `:count` key（首装惰性 COUNT）；`replyCount` = DB `comment.reply_count`；与详情接口 `commentCount`（含楼中楼的总评论数）口径不同，前端头部计数仍取 `commentCount`。
- **顺序**：主楼/展开回复均 `ORDER BY c.comment_id`（键集升序），页间不重不漏由构造保证。
- **前端**：`static/js/views/detail.js` 评论列表走公共 **`chunkedList`** helper（`static/js/chunkedList.js`，T10-B 抽出、T11 复用）——**只传 `page`**（T11-B：信封大小由后端域常量决定），本地小批 10，本地余量用尽才发下一个 chunk 请求；每条主楼首次只显示前 2 条回复 + "共 N 条回复"，展开时按需拉 `/comment/replies`（同样只传 `page`）；发/删评论后重置回第 1 页。

### 6.19 前端列表分块与去重（T10-B 抽出 → T11-A/T11-B 消费）

- **helper 语义**（`static/js/chunkedList.js`）：`createChunkedList({fetchChunk, chunkSize, batchSize, keyOf})` → `nextBatch()/hasMore()/reset()`。"大 chunk 拉取 + 本地小批展示"：本地余量足够时不发请求，用尽才拉下一页。
- **去重（T11-B）**：内部 `seen` 集合按 `keyOf` 过滤已展示条目——只兜"翻页期间集合变化导致的 offset 漂移"，**不替代后端契约**（后端"页间不重不漏"仍由 pytest 直打 API 验证，去重不得掩盖后端分页 bug）。`keyOf` 缺省依次取 `item.userId`/`item.commentId`/`item.id`；**评论 VO 同时含 `userId`（作者）与 `commentId`，评论类列表必须显式传 `keyOf: c => c.commentId`**，否则同一作者的多条评论会被折叠；`keyOf` 返回 `null` 的条目不参与去重（不吞条目）。单次 `nextBatch` 内最多再拉 10 个 chunk（防"整页重复"死循环）。
- **信封大小自适应（T11-B）**：`chunkSize` 只作初始/兜底值，首次成功响应后用响应回显的 `pageSize` 覆盖——信封大小由**后端域级常量**决定，前端可只传 `page`。
- **消费方（5 处；T19 起全部只传 `page`）**：`views/detail.js`（评论主楼，chunk 兜底 200 / 小批 10）、`views/user.js`（关注/粉丝 sheet 与创作网格 `/profile`，chunk 100 / 小批 10）、`views/follow.js`（`/feed`，chunk 100 / 小批 10）、`views/search.js`（结果，chunk 100 / 小批 12）、`views/publish.js`（我的投稿 `/profile`，chunk 100 / 小批 12）。**信封大小一律由后端域常量决定**（feed/search/profile/follow = 100，评论 = 200），消费方不再传 `pageSize`；chunk 常量仅作"首次请求失败时判末页"的兜底（T19 前 `follow`/`search`/`profile` 三处的后端上限未参数化、被公共 cap 50 顶住）。
- **本地重渲染口径（T11-B）**：`publish.js` 的"删除模式切换 / 删除卡片 / 编辑保存"从"重拉当前页"改为**本地条目集重渲染**（`state.myItems`；编辑走 `search/IdSearch` 定向刷新单条）——原实现在第 N 页会重复追加第 N 页卡片。

---

## 七、API 接口清单

### 7.1 用户模块

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| POST | /user/login | 登录 | ✗ |
| POST | /user/register | 注册（自动登录失败时仍 200，`data.token` 为 null —— 注册成功、请手动登录，T13） | ✗ |
| POST | /user/changePassword | 修改密码 | ✓ |
| POST | /user/changeUserName | 修改用户名（改名后级联失效该作者内容缓存 authorName） | ✓ |

### 7.2 内容模块

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| GET | /start | 首页推荐 | ✗ |
| GET | /search/keywordSearch | 关键词搜索（`SearchController` 无裸 `/search` 分支，`/search` 命中默认分支返回"未识别功能"；**T19：`pageSize` 缺省/上限均 100**，前端只传 `page`，非法值回落缺省而非 500） | ✗ |
| GET | /search/IdSearch | 内容详情（无 /detail 端点） | ✗ |
| GET | /feed | 关注动态流（**T19：`pageSize` 缺省/上限均 100**，前端只传 `page`） | ✓ |
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
| GET | /comment/show | 查看评论（可选 `page`/`pageSize`：传任一参数返回 `{list,total,page,pageSize,totalPages}`，`total`=主楼条数，**每主楼只带前 2 条楼中楼 + `replyCount` 总数**；缺省仍全量数组；pageSize 缺省域级信封 **200**、上限 **500**） | ✗ |
| GET | /comment/replies | **T10-B**：展开某主楼全部回复（`rootId&page&pageSize` → 分页信封，`total`=该主楼回复总数；pageSize 缺省 **200**、上限 500，T11-B） | ✗ |
| POST | /comment/delete | 删除评论（软删除，仅自己） | ✓ |
| POST | /content/commentEnabled | 作者开关自己作品的评论区（0=关/1=开） | ✓ |
| POST | /follow/add | 关注 | ✓ |
| POST | /follow/remove | 取关 | ✓ |
| GET | /follow/following | 关注列表（**始终分页信封** `{list,total,page,pageSize,totalPages}`；T11-A：缺省=第一页，`pageSize` 缺省/上限均 **100**——T19 由 200 调整为 100） | ✓ |
| GET | /follow/followers | 粉丝列表（分页口径同 /follow/following） | ✓ |
| GET | /profile | 用户主页（**T19：`contentPage` 的 `pageSize` 缺省/上限均 100**，前端只传 `page`；`ProfileVO` 形状不变） | ✗ |

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
        ├── chunkedList.js     # 公共「分块列表」helper（大 chunk 拉取 + 本地小批展示 + keyOf 去重 + 信封大小自适应；消费方：detail 评论、user 关注/粉丝 sheet 与创作网格、follow、search、publish）
        └── views/
            ├── home.js        # #/            首页（推荐流 + 换一换）
            ├── follow.js      # #/follow      关注流（/feed 分块：只传 page，信封 100 / 小批 10，T19）
            ├── detail.js      # #/video/:id   详情（播放器 + 楼中楼评论 + 相关推荐；评论只传 page，T11-B）
            ├── search.js      # #/search?kw=  搜索（结果分块：只传 page，信封 100 / 小批 12，T19）
            ├── user.js        # #/user/:id    个人主页（本人/他人合一；关注/粉丝 sheet 只传 page（T11-A，信封 100，T19）、创作网格走公共 chunkedList（只传 page，信封 100，T19））
            ├── publish.js     # #/publish     创作中心（我的投稿 + 投稿上传；我的投稿走公共 chunkedList，只传 page，信封 100，T19）
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
