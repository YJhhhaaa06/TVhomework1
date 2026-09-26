# 业务流程文档

> 版本：2.7
> 最后更新：2026-09-26（feed1-20 T20 收尾：新增 §6.2 写扩散与收件箱重建——影子期只写不读，`/feed` 对外流程与权限矩阵**零变化**，本节为 Redis 侧派生副本的建立与二期切读门。上一版 2.6 = 2026-09-22 日志周期 T4 收尾：§1.1 Filter 链图补最外层 `AccessLogFilter`。历史变更见 git 提交历史与 `NEXT_CYCLE_TASKS.md` 执行回写）
> 用途：保障重构时不破坏业务逻辑

---

## 目录

- [一、总体架构](#一总体架构)
- [二、用户认证模块](#二用户认证模块)
- [三、内容管理模块](#三内容管理模块)
- [四、社交互动模块](#四社交互动模块)
- [五、优惠券模块](#五优惠券模块)
- [六、Feed 流模块](#六feed-流模块)
- [七、权限控制矩阵](#七权限控制矩阵)

---

## 一、总体架构

### 1.1 请求处理流程

```
客户端请求
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│                    Filter 链                             │
│  AccessLogFilter → ExceptionFilter → EncodingFilter     │
│  (访问日志)        (全局异常)        (UTF-8编码)        │
│  → LoginFilter（解析Token）→ AuthFilter（权限校验）     │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│                   Controller 层                          │
│  职责：解析请求参数 → 调用 Service → 构建响应               │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│                    Service 层                            │
│  职责：业务逻辑 → 事务管理 → 缓存操作                      │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│                      DAO 层                              │
│  职责：数据库操作（原生 JDBC）                             │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────┐    ┌──────────────┐
│    MySQL     │    │    Redis     │
│   (主存储)    │    │   (缓存)     │
└──────────────┘    └──────────────┘
```

### 1.2 认证机制

- **Token 类型**: JWT (HMAC256)
- **过期时间**: 2 小时
- **传递方式**: 请求参数 `token`
- **解析位置**: LoginFilter
- **存储位置**: request attribute `userId`

---

## 二、用户认证模块

### 2.1 登录流程

```
┌──────────┐     POST /user/login      ┌──────────────┐
│  客户端   │ ────────────────────────► │ LoginController │
└──────────┘     {phone, password}      └──────────────┘
                                              │
                                              ▼
                                      ┌──────────────┐
                                      │  UserService  │
                                      └──────────────┘
                                              │
                         ┌────────────────────┼────────────────────┐
                         ▼                    ▼                    ▼
                  ┌────────────┐       ┌────────────┐       ┌────────────┐
                  │ 参数校验   │       │ 查询用户   │       │ 密码验证   │
                  │ (Command)  │       │ (UserDao)  │       │ (BCrypt)   │
                  └────────────┘       └────────────┘       └────────────┘
                                              │
                                              ▼
                                      ┌──────────────┐
                                      │  生成 JWT    │
                                      └──────────────┘
                                              │
                                              ▼
                                      ┌──────────────┐
                                      │ 返回 LoginVO │
                                      │ {id, name,   │
                                      │  token}      │
                                      └──────────────┘
```

#### 详细步骤

| 步骤 | 操作 | 失败处理 |
|------|------|----------|
| 1 | 解析 JSON 为 LoginDTO | 返回 400 参数错误 |
| 2 | 转换为 LoginCommand | - |
| 3 | 根据登录类型（ID/手机号）查询用户 | 用户不存在返回 NotFoundException |
| 4 | BCrypt 验证密码 | 密码错误返回 AuthException |
| 5 | 生成 JWT Token | - |
| 6 | 返回 LoginVO (id, username, token) | - |

#### 接口定义

```
POST /user/login
Content-Type: application/json

请求体：
{
    "loginType": 1,        // 1=按手机号, 2=按ID
    "phone": "13800138000", // 按手机号登录时必填
    "id": 123,              // 按ID登录时必填
    "password": "abc123"
}

成功响应：
{
    "code": 200,
    "data": {
        "id": 1,
        "username": "张三",
        "token": "eyJhbGciOiJIUzI1NiIs..."
    }
}

失败响应：
{
    "code": 401,
    "message": "用户不存在"
}
```

---

### 2.2 注册流程

```
┌──────────┐   POST /user/register   ┌──────────────┐
│  客户端   │ ───────────────────────► │ LoginController │
└──────────┘   {username,phone,pwd}   └──────────────┘
                                              │
                                              ▼
                                      ┌──────────────┐
                                      │  UserService  │
                                      └──────────────┘
                                              │
                ┌─────────────┬───────────────┼───────────────┬─────────────┐
                ▼             ▼               ▼               ▼             ▼
         ┌──────────┐  ┌──────────┐   ┌──────────┐   ┌──────────┐  ┌──────────┐
         │ 手机号   │  │ 用户名   │   │ 密码     │   │ 插入     │  │ 自动登录 │
         │ 唯一性   │  │ 唯一性   │   │ 哈希     │   │ 数据库   │  │ 返回Token│
         │ 检查     │  │ 检查     │   │ (BCrypt) │   │ (UserDao)│  │          │
         └──────────┘  └──────────┘   └──────────┘   └──────────┘  └──────────┘
```

#### 详细步骤

| 步骤 | 操作 | 失败处理 |
|------|------|----------|
| 1 | 解析 JSON 为 RegisterDTO | 返回 400 参数错误 |
| 2 | 转换为 RegisterCommand | - |
| 3 | 开启事务 | - |
| 4 | 检查手机号是否已使用 | 已使用返回 AuthException |
| 5 | 检查用户名是否已占用 | 已占用返回 AuthException |
| 6 | 密码 BCrypt 哈希 | - |
| 7 | 插入用户记录 | SQLException 回滚 |
| 8 | 提交事务 | - |
| 9 | 自动登录（`UserService.registerAndLogin`，T13）：查询用户 → BCrypt 校验 → 生成 JWT，返回 LoginVO | **自动登录失败（用户查不到/密码不匹配/登录期 DB 异常）不再抛错**：仍返回 200 + `token=null` 的 LoginVO（注册已提交即算成功），前端提示「注册成功，请手动登录」并切回登录 tab；**注册本身失败（步骤 4~7）照旧抛错** |

#### 接口定义

```
POST /user/register
Content-Type: application/json

请求体：
{
    "username": "张三",
    "phone": "13800138000",
    "password": "abc123"
}

成功响应：
{
    "code": 200,
    "data": {
        "id": 1,
        "username": "张三",
        "token": "eyJhbGciOiJIUzI1NiIs..."
    }
}

失败响应：
{
    "code": 401,
    "message": "电话号码已被使用"
}
```

> **自动登录兜底（T13）**：注册已提交后自动登录失败时，**不是失败响应**，而是成功响应 + `"token": null`（`data.id`/`data.username` 为已注册用户）——`token` 是本接口唯一的"是否已登录"信号，前端据此提示「注册成功，请手动登录」并切回登录 tab 预填手机号（提示文案在前端，后端不新增字段）。

---

### 2.3 修改密码流程

```
┌──────────┐  POST /user/changePassword  ┌──────────────┐
│  客户端   │ ──────────────────────────► │ LoginController │
└──────────┘   {phone, oldPwd, newPwd}    └──────────────┘
       ▲                                        │
       │                                        ▼
       │                                 ┌──────────────┐
       │                                 │  UserService  │
       │                                 └──────────────┘
       │                                        │
       │                ┌─────────────┬─────────┴─────────┬─────────────┐
       │                ▼             ▼                   ▼             ▼
       │         ┌──────────┐  ┌──────────┐       ┌──────────┐  ┌──────────┐
       │         │ 验证用户 │  │ 验证手机 │       │ 验证旧密 │  │ 更新密码 │
       │         │ 存在     │  │ 号匹配   │       │ 码正确   │  │ (哈希)   │
       │         └──────────┘  └──────────┘       └──────────┘  └──────────┘
       │
       └──────────────────────────────── 返回成功 ──────┘
```

#### 详细步骤

| 步骤 | 操作 | 失败处理 |
|------|------|----------|
| 1 | 从 request attribute 获取 userId | 未登录返回 401 |
| 2 | 解析 JSON 为 ChangePasswordDTO | 返回 400 参数错误 |
| 3 | 开启事务 | - |
| 4 | 查询用户信息 | 用户不存在抛 UserNotFoundException（401） |
| 5 | 验证手机号匹配 | 不匹配抛 ParamException（400） |
| 6 | 验证旧密码正确 | 不正确抛 PasswordIncorrectException（401） |
| 7 | 新密码 BCrypt 哈希 | - |
| 8 | 更新数据库密码 | 更新失败抛 DatabaseException（500） |
| 9 | 提交事务 | - |

#### 接口定义

```
POST /user/changePassword
Content-Type: application/json
?token=xxx

请求体：
{
    "phone": "13800138000",
    "oldPassword": "abc123",
    "newPassword": "def456"
}

成功响应：
{
    "code": 200,
    "data": null
}
```

---

### 2.4 修改用户名流程

```
┌──────────┐  POST /user/changeUserName  ┌──────────────┐
│  客户端   │ ──────────────────────────► │ LoginController │
└──────────┘   {"userName": "新名字"}       └──────────────┘
       ▲                                        │ (AuthFilter 精确保护)
       │                                        ▼
       │                                 ┌──────────────┐
       │                                 │  UserService  │
       │                                 │ changeUserName│
       │                                 └──────────────┘
       │                                        │
       │        ┌───────────────┬───────────────┼──────────────┐
       │        ▼               ▼               ▼              ▼
       │   ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌──────────────┐
       │   │ 校验用户名  │ │ 检查用户   │ │ 唯一性预校验│ │ UPDATE       │
       │   │ 非空/非超长 │ │ 存在       │ │ (isUsername │ │ users.username│
       │   │ (400)      │ │ (401)      │ │  Used→409) │ │ └──────┬──────┘
       │   └────────────┘ └────────────┘ └────────────┘        ▼
       │                                                  ┌──────────────┐
       │                                                  │ 提交事务后    │
       │                                                  │ contentCache. │
       │                                                  │ invalidateAuth│
       │                                                  │ orContentKeys │
       │                                                  └──────────────┘
       └──────────────────────── 返回成功（缓存读自愈回填新名）─┘
```

#### 详细步骤

| 步骤 | 操作 | 失败处理 |
|------|------|----------|
| 1 | AuthFilter 精确保护 `/user/changeUserName` + Controller 取 userId（request attribute） | 未登录返回 401 |
| 2 | 解析 JSON 为 ChangeUserNameDTO | 返回 400 参数错误 |
| 3 | Service 校验：`null / isBlank / length >= 50` | ParamException（400） |
| 4 | 开启事务，检查用户存在 | 不存在抛 UserNotFoundException（401） |
| 5 | 唯一性预校验 `isUsernameUsed`（对齐全册注册先例，避免撞 DB UNIQUE 变 500） | 已占用抛 ConflictException（409，含改名成自己当前名） |
| 6 | `UPDATE users SET username=?` | 更新失败抛 DatabaseException（500） |
| 7 | 提交事务后调用 `ContentCache.invalidateAuthorContentKeys(userId)`：事务内查该用户全部内容 id → 事务外逐个失效 `content:{id}` key + 空标记 | DB/Redis 失败仅记日志跳过，缓存 TTL 自愈，不影响改名成功 |

#### 接口定义

```
POST /user/changeUserName
Content-Type: application/json

请求体：
{
    "userName": "新名字"
}

成功响应：
{
    "code": 200,
    "data": null
}
```

> 一致性说明：内容缓存的 `authorName` 是 `findContent` `JOIN users` 时的反规范化副本；改名后失效该作者全部内容 key，下次读 `loadContentFromDb` 重新 JOIN users 回填新名（推荐/Feed/Profile/详情/搜索全部经内容缓存消费，索引 `content:index:*` 只存 id 无需失效）。

### 2.5 修改手机号流程

```
POST /user/changePhone?token=xxx&oldPhone=13800138000&newPhone=13900139000

步骤：
1. 验证登录状态
2. 开启事务
3. 查询用户信息
4. 验证旧手机号匹配
5. 校验新手机号格式
6. 检查新旧手机号不能相同
7. 检查新手机号未被使用
8. 更新手机号
9. 提交事务
```

---

## 三、内容管理模块

### 3.1 内容与评论缓存机制

> 缓存机制细节（Key 设计/三态语义/超时熔断/降级单飞/负缓存/空标记守卫/Lua 条件写/TTL 与滑动续期/读路径优化/启动加载/索引维护/SetCache 基建/成员反转/计数入缓存/JSON 兼容/authorName 同步）统一见 `CURRENT_ARCHITECTURE` 六节；本文档只表达"业务写路径触发什么缓存动作"，不重复机制描述、不记录变更历史（变更以 git 提交历史为准）。

```
┌─────────────────────────────────────────────────────────────────┐
│  前台读路径（Start/Search/Detail/Feed/Profile）                  │
│    ↓  ContentCache（com.itheima.content.service）               │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ 内容详情：content:{id} → JSON（T1 CacheAside 三态）        │   │
│  │   miss=查 DB 回填（单飞）；hit-empty=空标记 60s 防穿透；    │   │
│  │   hit-data=直接返回；Redis 挂=降级走 DB                    │   │
│  │ 类型分区索引：content:index:{type}:{category}（Redis LIST）│   │
│  │   4 key/内容（含 type=-1 / category=-1 通配），新前序      │   │
│  │ TTL：内容 30min（+±10% 抖动，4.12 一版）                  │   │
│  └──────────────────────────────────────────────────────────┘   │
│  评论读路径（/comment/show、详情页评论区）                       │
│    ↓  CommentCache（com.itheima.content.service，T3）          │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ 评论树：content:comments:{id} → JSON（三态 Cache-Aside）   │   │
│  │   hit-empty（空标记）=已确认无评论→直接空，不查 DB；        │   │
│  │   miss=查 DB 回填整树（单飞；无评论→写空标记）；            │   │
│  │   hit-data=直接返回；评论 miss ≠ 没有评论（4.3）           │   │
│  │ 独立 TTL：cache.comment.ttlMinutes=10min（+抖动）          │   │
│  │ 读评论前先确认内容存在（dto==null 直接空，4.5）            │   │
│  └──────────────────────────────────────────────────────────┘   │
│  写路径（DB 事务提交后）：                                      │
│    addVideo/addPost    → contentCache.addContent(id) 入缓存      │
│    编辑媒体/文案、恢复   → contentCache.refreshContent(id)       │
│    删除/下架            → contentCache.removeContent(id)（失效+索引剔除）│
│    + commentCache.invalidateComments(id)（级联失效评论树 key）    │
│    点赞/取消            → 失效 content:{id}（读自愈回填 DB 计数）  │
│    评论增删             → 失效 content:{id}（回填 comment_count） │
│                        + commentCache.invalidateComments(id)（回填评论树）│
│    评论点赞/取消         → commentCache.notifyCommentLikeChanged(id)（定位所属内容后失效评论树）│
│    评论区开关           → 失效 content:{id}（读自愈回填 comment_enabled）│
│  ────────────────────────────────────────────────                  │
│  关注读路径（/follow/following|followers、内容卡片/主页 isFollowed、│
│  feed 关注列表）                                                  │
│    ↓  FollowCache（com.itheima.follow.service，T5）               │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ user:following / user:follower 双 ZSet（score=id）           │   │
│  │ 三态：empty 空标记（60s）=确认真无；key 存在=ZSCORE/ZRANGE     │   │
│  │  miss=单飞回填 DB全量（非空 ZADD+EXPIRE 30min；空集→空标记， │   │
│  │  分页=窗口读 ZRANGE[offset, offset+N) + ZCARD（hit O(log n+N)）  │   │
│  │  空标记写入带 key 存在守卫防并发覆盖新写）；Redis 挂=降级 DB  │   │
│  │ user:followCount:{userId} / user:followerCount:{userId}     │   │
│  │  （String int，T6 计数入缓存 R-01：Cache-Aside、0 合法、     │   │
│  │   miss/降级走 DB 单列计数 loader，与 content:likeCount 同构）│   │
│  └──────────────────────────────────────────────────────────┘   │
│  关注/取关写路径（FollowService DB 提交后）：                     │
│    两 key 均"已加载"（key 或空标记存在）→ MULTI 原子 ZADD/ZREM 双写+续 TTL │
│    （新关注时解除空标记）；任一侧冷 key 或空标记命中 → 双双 DEL 失效让读自愈 │
│    ；Redis 异常 → 双 DEL（4.10 失败双 DEL），不抛出、不影响业务     │
│    + 计数条件增量（T6）：EVAL"计数 key 存在才 INCRBY±1"，冷 key   │
│    no-op 由读回填，失败只失效两计数 key（读自愈），不抛出          │
└─────────────────────────────────────────────────────────────────┘
```

> 缓存读写语义要点：关注读路径（`isFollowing` 单成员 / `batchIsFollowing` 批量 / `getFollowingIds` 全量关注列表 / **`getFollowingWindow`/`getFollowerWindow` 分页窗口（T7）**）统一走基建 `ZSetCache`——成员 key 为 ZSet（score=成员 id），故 `ZRANGE` 天然升序，列表升序另由 `FollowCache.sortIds` 归一（写路径 MULTI 条件双写 + 失败双 DEL 保留在 FollowCache）；**关注/粉丝列表接口恒返回分页信封 `{list,total,page,pageSize,totalPages}`——缺省（不传参）= 第一页信封（page 1 / pageSize 100），与显式 `page=1&pageSize=100` 逐字节一致；`pageSize` 上限 100（T11-A 契约变更、**T19 由 200 调整为 100**，T7 的"缺省返回全量数组"已删除）**；**T11-C 起分页读只装载"被看的那一段"**（冷 key 取 `[0, offset+count)`、前缀不足只补差量、Redis 降级改 DB 窗口直查）——集合完整性由 `partial:{数据key}` 标记表达，带标记时判定不命中回落 DB、全量读先补齐、写路径任一侧带标记则三件套双 DEL；关注/粉丝计数入独立 key（`user:followCount`/`user:followerCount`，Cache-Aside、0 合法、条件 INCRBY）；Feed/Profile/Search 的缓存批量读、以及关注·粉丝列表装载的关注态批量读（T12）都在 DB 事务外执行（防连接池互相等连接）；详情见 `CURRENT_ARCHITECTURE` 6.4/6.12/6.17。

> 关键语义：内容与评论读/写**全部收敛 Redis**；**任何缓存失败降级走 DB、不导致业务失败**；计数（like_count/comment_count/comment_enabled）与评论树内容以 DB 为源真理，变更即失效让读自愈；类型分区索引启动 init 全量重建 + 索引 key 缺失时单飞懒重建（防 Redis 重启后 /start 空推荐）；评论树不再原地增删：评论增/删/点赞 = 失效 `content:comments:{id}` + 空标记，下次读 miss 单飞回填 DB 最新整树。
>
> **评论查询分页（T8 → T11-B）**：`/comment/show` 传 `page`/`pageSize` **任一** → 返回分页信封 `{list,total,page,pageSize,totalPages}`（`total`=**主楼条数**）；**两者都不传 → 仍返回全量数组**（零破坏）。`pageSize` **缺省 = 评论域信封 200**（T11-B：前端只传 `page`，决定权在后端域常量）、上限 **500**（显式传参仍生效）。命中路径与装载形态（两键组 / 主楼窗口装载 / 楼中楼前 K + 展开接口）见 `CURRENT_ARCHITECTURE` 6.18；前端分块与去重见 6.19。
>
> 启动与索引维护：启动全量重建拆两段（DB 事务内只读、事务外写 Redis，Redis 往返 ≈3 次与内容量解耦）；索引 key 生成/解析/匹配同源（`CacheKeys.contentIndex`）；读命中滑动续期（分域 TTL：content 30min / comment 10min / like 15min / follow 30min，空标记不续）；推荐读惰性探测（凑满 limit 即止，分布语义不变）+ 索引重建失败冷却退避（**T14 起含 DB 装载失败：跳过重建、保留旧索引**，失败不再当空表）+ 索引长尾无系统性漂移；详情见 `CURRENT_ARCHITECTURE` 6.5/6.10/6.11。

### 3.2 发布视频流程

```
┌──────────┐  POST /api/upload/video  ┌────────────────┐
│  客户端   │ ───────────────────────► │ UploadController │
└──────────┘   multipart/form-data    └────────────────┘
                                              │
                                              ▼
                                      ┌──────────────────┐
                                      │ FileUploadService │
                                      │ 1. 校验文件类型   │
                                      │ 2. 生成UUID文件名 │
                                      │ 3. 保存到上传根目录│
                                      └──────────────────┘
                                              │
                                              ▼
                                      ┌──────────────────┐
                                      │ ContentService    │
                                      │ 1. 开启事务       │
                                      │ 2. 插入content表  │
                                      │ 3. 插入media表    │
                                      │ 4. 提交事务       │
                                      │ 5. 更新内存缓存   │
                                      └──────────────────┘
                                              │
                                              ▼
                                      ┌──────────────────┐
                                      │ 返回 UploadResult │
                                      │ {contentId, urls} │
                                      └──────────────────┘
```

#### 详细步骤

| 步骤 | 操作 | 失败处理 |
|------|------|----------|
| 1 | 解析 multipart 请求 | 文件过大返回 400 |
| 2 | 校验文件类型（视频/图片） | 类型不支持返回 400 |
| 3 | 生成 UUID 文件名 | - |
| 4 | 保存文件到 D:/data/projects/VideoPlatform/stone | IO 异常返回 500 |
| 5 | 开启事务 | - |
| 6 | 插入 content 表 | SQLException 回滚 |
| 7 | 插入 content_media 表（视频+封面） | SQLException 回滚 |
| 8 | 提交事务 | - |
| 9 | 更新内存缓存 | 失败只记录日志，不影响主流程 |
| 10 | 返回上传结果 | - |

#### 接口定义

```
POST /api/upload/video
Content-Type: multipart/form-data
?token=xxx

表单字段：
- title: 视频标题
- description: 视频描述
- categoryId: 分区ID
- video: 视频文件
- cover: 封面图片

成功响应：
{
    "code": 200,
    "data": {
        "contentId": 123,
        "videoUrl": "/upload/xxx.mp4",
        "coverUrl": "/upload/xxx.jpg"
    }
}
```

---

### 3.3 发布动态（图文帖）流程

```
POST /api/upload/post
Content-Type: multipart/form-data
?token=xxx

表单字段：
- title: 动态标题
- description: 动态描述
- categoryId: 分区ID
- cover: 封面图片（可选）
- images: 图片文件（多个）

步骤：
1. 解析并保存图片文件
2. 开启事务
3. 插入 content 表（type=2）
4. 插入 content_media 表（封面+图片列表）
5. 提交事务
6. 更新内存缓存
```

---

### 3.4 首页推荐流程

> **前端交互（2026-08-14 重构后；2026-09-20 T11-B 分块化）**：首页为 SPA 视图 `#/`；分区（推荐/游戏/音乐…）收纳在顶部导航「分类」下拉（选中跳 `#/?cat=<id>`）；类型筛选（全部/视频/图文）在首页内容区；「换一换」重新拉 `/start` 并在客户端打乱顺序以获得「新一批」观感；关注流独立为 `#/follow`（`/feed` 分页）。**分页列表统一走公共 `chunkedList`**（大 chunk 一次拉取 + 本地小批展示 + 跨 chunk 去重）：`/feed` 与 `/profile` 创作网格每次拉 100 条、本地按 10 条展示，`/search` 拉 100 条、本地按 12 条展示，评论主楼与跟随关系 sheet 由后端域常量决定信封大小（T19：五处列表的请求一律**只传 `page`**，信封大小全部由后端域常量决定）——「加载更多」在本地余量内**不发请求**（详见 `CURRENT_ARCHITECTURE` 6.19）。

```
┌──────────┐   GET /start   ┌────────────────┐
│  客户端   │ ─────────────► │ StartController │
└──────────┘                 └────────────────┘
                                      │
                                      ▼
                             ┌──────────────────┐
                             │ ContentService    │
                             │ getRecommend()    │
                             └──────────────────┘
                                      │
                                      ▼
                             ┌──────────────────┐
                             │ 从 recommendList  │
                             │ 随机取 limit 条   │
                             └──────────────────┘
                                      │
                                      ▼
                             ┌──────────────────┐
                             │ 填充点赞状态      │
                             │ 填充关注状态      │
                             │ (如果已登录)      │
                             └──────────────────┘
                                      │
                                      ▼
                             ┌──────────────────┐
                             │ 返回 List<ContentVO>│
                             └──────────────────┘
```

#### 接口定义

```
GET /start?limit=10&token=xxx（可选）

成功响应：
{
    "code": 200,
    "data": [
        {
            "id": 1,
            "authorId": 100,
            "authorName": "张三",
            "type": 1,
            "title": "视频标题",
            "coverUrl": "/upload/xxx.jpg",
            "likeCount": 50,
            "commentCount": 10,
            "isLiked": false,
            "isFollowed": false,
            "createTime": "2026-07-23T10:00:00"
        },
        ...
    ]
}
```

---

### 3.5 搜索流程

```
┌──────────┐  GET /search/keywordSearch?keyword=xxx  ┌─────────────────┐
│  客户端   │ ────────────────────────► │ SearchController │
└──────────┘                            └─────────────────┘
                                              │
                                              ▼
                                      ┌──────────────────┐
                                      │ ContentService    │
                                      │ search()          │
                                      └──────────────────┘
                                              │
                              ┌───────────────┼───────────────┐
                              ▼               ▼               ▼
                       ┌──────────┐   ┌──────────┐   ┌──────────┐
                       │ MySQL    │   │ 缓存     │   │ 填充     │
                       │ 全文索引 │   │ 查询     │   │ 点赞/关注│
                       │ MATCH    │   │ 详情     │   │ 状态     │
                       │ AGAINST  │   │          │   │          │
                       └──────────┘   └──────────┘   └──────────┘
```

#### 详细步骤

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 开启连接 | - |
| 2 | MySQL 全文索引搜索 | `MATCH(title, description) AGAINST(? IN NATURAL LANGUAGE MODE)` |
| 3 | 获取搜索结果总数 | 用于分页 |
| 4 | 获取当前页内容 ID 列表 | 分页查询 |
| 5 | 从缓存获取内容详情 | 缓存 miss 会回填 |
| 6 | 批量填充点赞状态 | 如果已登录 |
| 7 | 返回分页结果 | PageResult |

> 事务边界（T12）：步骤 2~4 在 DB 事务回调内（命中总数 + 该页内容 id），步骤 5（`getContentsBatch` 批量读，原逐 key `getContent`）与步骤 6（点赞/关注状态缓存读）在事务提交、连接归还后执行；跳过 null 与"结果为空不调状态填充"两个既有分支不变，对外行为零变化。全文同类口径同见 `CURRENT_ARCHITECTURE` 6.4。

#### 接口定义

```
GET /search/keywordSearch?keyword=关键词&page=1&token=xxx（可选）

成功响应（**T19：信封大小由后端 search 域常量决定 = 100，前端只传 page**；缺省/上限均 100）：
{
    "code": 200,
    "data": {
        "list": [...],
        "total": 100,
        "page": 1,
        "pageSize": 100
    }
}
```

---

### 3.6 查看内容详情流程

> **前端交互（2026-08-14 重构后）**：详情为 SPA 视图 `#/video/:id`（原 `detail.html?contentId=` 已删除）；详情端点实为 `GET /search/IdSearch`（**无 `/detail`**）；右侧新增「相关推荐」栏，本轮用 `/start` 推荐流兜底（后端暂无推荐接口）；评论为**楼中楼两级**（2026-08-28 阶段一：主楼 + 楼内回复折叠列表），支持回复（仅主楼下）/点赞/删除（自己的评论，管理员走运维页）；视频类型用 `videoUrl` 播放，图文类型展示 `coverUrl` + `imageUrls` 画廊。

```
GET /search/IdSearch?contentId=123&token=xxx（可选）

步骤：
1. 从缓存获取内容详情
2. 从缓存获取评论列表
3. 填充点赞状态（如果已登录）
4. 填充关注状态（如果已登录）
5. 返回 ContentDetailVO

ContentDetailVO 包含：
- 内容基本信息
- 视频URL / 图片URL列表
- 评论列表（树形结构）
- 当前用户是否点赞
- 当前用户是否关注作者
```

---

### 3.7 媒体资源运维流程（扫描/恢复）

**背景**：灾后检查发现历史上传文件大量丢失，新增媒体运维能力用于持续扫描与人工恢复。

#### 扫描流程

1. 运维页面 `recovery.html` 加载或点击“重新扫描”。
2. 调用 `GET/POST /api/admin/media/*`，进入 `MediaAuditService.scanAll()`。
3. 遍历 `content_media` 全部记录，将 `/upload/<type>/<文件名>` 映射为 `stone/<type>/<文件名>`。
4. 用 `Files.exists()` 判断文件是否存在，回写 `content_media.file_exists`、`last_verify_time`。
5. 按 content 聚合：任一媒体缺失则 `content.file_exists=0`，全部存在或无媒体的纯文字帖为 1。
6. 返回统计：总数、存在、缺失、URL 异常、孤儿 media、无媒体内容。

#### 恢复流程

1. 页面针对缺失资源选择本地文件并提交 `POST /api/admin/media/restore`。
2. 校验 mediaId 存在、URL 合法、上传文件扩展名与目标一致。
3. 文件写入 `stone/<type>/<数据库原文件名>`，覆盖同名文件。
4. 更新 `content_media.file_exists=1`，并重新计算该 content 的聚合状态。

#### 权限

- 当前：`/api/admin/*` 需 role==1（管理员）方可扫描/恢复。
- 作者可对自己的帖子和视频换源、删图、改文案（见 3.8，阶段三已实现）。

### 3.8 作者编辑作品流程（换源 / 删图 / 改文案）

**入口**：创作中心「我的投稿」卡片或详情页（作者本人）的「编辑」按钮 → 编辑作品弹层（`static/js/editWork.js`），可看到已上传媒体缩略图与当前标题/简介，直接编辑，无需完整重新上传。

#### 文案编辑（A3）

1. 弹层修改标题/简介 → `POST /content/update?contentId=&title=&description=`。
2. `ContentService.updateContentInfo`：校验内容存在（404）→ 作者本人（403）→ title 非空且 ≤50、简介 ≤5000 → `ContentDao.updateContentInfo`。
3. 全文索引由 MySQL 自动维护（DML 即时生效）；事务后 `ContentCache.refreshContent` 回填内容缓存与索引（详情/搜索用新值）。

#### 换源 / 替换媒体

1. 弹层对目标媒体（视频文件 type=1/sort=1、封面 type=3/sort=1、图文第 i 张图 type=2/sort=i+1）选择新文件 → `POST /api/upload/replace?contentId=&type=&sort=` + file（multipart）。
2. 新文件落盘（`FileUploadService.saveFile` 含后缀校验）→ `ContentService.replaceMedia` 校验所有权 → 更新 `content_media`（url、file_exists=1、last_verify_time）与 `content.file_exists=1` → 返回旧 url → Controller `FileUploadService.deleteFileByUrl` 清理旧文件（尽力而为）；任一步失败删除新文件回滚。
3. 事务后 `refreshContent` 同步缓存，详情/首页卡片即时展示新源。

#### 单图删除（仅图文图片）

1. 弹层对图片（type=2）点删除 → 确认 → `POST /content/mediaDelete?contentId=&type=&sort=`。
2. `ContentService.deleteMedia`：校验所有权 → 删除记录 → 对剩余图片 `compactImageSort` 重排 sort（保持 1..n 连续，保证前端 index+1 定位成立）→ 返回旧 url → Controller 清理物理文件。
3. 事务后 `refreshContent` 同步缓存；媒体运维扫描（3.7）不再看到已删媒体。

#### 权限

- 三个接口均要求登录（AuthFilter 精确保护 `/content/update`、`/content/mediaDelete`；`/api/upload/*` 前缀保护）；所有权由 Service 校验，非作者一律 403。
- 视频文件与封面（含图文封面）只可替换不可删除；仅图文图片（type=2）可删除，避免作品结构性资源失效。

### 3.9 删除内容流程（作者本人，A1）

**入口**：创作中心「我的投稿」顶部独立「删除」按钮 → 进入删除模式后各卡片右上角出现删除钮（默认卡片无删除按钮）→ 点击某张卡片 → 确认弹窗 → `POST /content/delete?contentId=`。

```
客户端点「删除」→ 进入删除模式 → 点某卡片 ✕ → 确认
    → POST /content/delete?contentId=X（AuthFilter 登录保护）
    → ContentService.deleteContent（事务）:
        1. findOwnedContent：内容存在（404）+ 作者本人（403）
        2. contentMediaDao.findMedia 收集全部媒体 url（供删物理文件）
        3. contentDao.softDeleteContent：content.is_deleted = 1（软删）
        4. commentDao.softDeleteByContentId：该内容全部评论软删（含主楼与楼内回复）
        5. contentLikeDao.deleteByContentId：点赞记录物理删除
        6. contentMediaDao.deleteByContentId：媒体记录物理删除
    → 事务提交后缓存同步（4.5 显式失效）:
      ContentCache.removeContent（失效 content:{id} + 索引剔除，读自愈 404）
      + CommentCache.invalidateComments（级联失效 content:comments:{id} + 空标记）
      + LikeService.deleteContentLike（失效 content:likeCount 计数 key；成员 key 为用户维度，删除不清理——残留成员指向已删除内容，id 不复用/UI 无查询路径，永不外显）
    → Controller 逐个 FileUploadService.deleteFileByUrl 删物理文件（尽力而为）
```

**效果**：删除后软删内容在首页 `/start`（索引剔除）、搜索（`is_deleted=0` 过滤）、关注流 `/feed`、用户主页 `/profile` 均不可见；详情 `/search/IdSearch` 返回 404「找不到对应内容」。删除不可恢复；非作者 403、未登录 401。

---

### 3.10 隐藏/取消隐藏内容流程（管理员下架，A2）

**入口**：管理员在 `#/admin` 管理页「内容下架管理（审核）」区块查看全部内容（含正常与已下架）清单，每行提供「下架/恢复」按钮。

**权限**：`/api/admin/content/*` 已被 AuthFilter 的 `/api/admin/*` 前缀保护覆盖（需登录且 role==1，非管理员 403、未登录 401）；无所有权校验，管理员可操作任意内容。

**清单**：`GET /api/admin/content/list` → `ContentService.listContentForAdmin` → `ContentDao.findContentForAdmin`（`WHERE c.is_deleted IN (0,2)`，不含已删除内容），返回 contentId/标题/作者/类型/是否下架。

**下架**：`POST /api/admin/content/hide?contentId=X`

1. `ContentService.hideContent`：`getContentStatus` 校验内容存在（404）→ 未被作者删除（409「内容已删除，无法下架」）→ 未处于下架态（409「内容已下架」）→ `updateContentDeletedState(conn, id, 2)`。
2. 仅改 `content.is_deleted=2` 一个字段；**不动**评论/点赞/媒体记录/物理文件（隐藏≠删除）。
3. 事务提交后缓存同步：ContentCache.removeContent（失效 content:{id} + 索引剔除）+ CommentCache.invalidateComments（级联失效评论树）+ LikeService.deleteContentLike（失效点赞计数 key；成员 key 为用户维度不清理，隐藏时点赞记录保留 DB，残留成员=DB 真理，恢复后读自愈对齐），前台即时不可见。

**恢复**：`POST /api/admin/content/unhide?contentId=X`

1. `ContentService.unhideContent`：校验存在（404）→ 未被删除（409）→ 当前处于下架态（409「内容未下架」）→ `updateContentDeletedState(conn, id, 0)`。
2. 事务提交后 `ContentCache.refreshContent` 回填内容缓存与索引，前台立即重新可见；评论树无需额外动作（hide 已失效评论 key，读时 miss 回填 DB 现存评论）；点赞 key 无需处理（hide 已失效计数 key，读时 miss 回填 DB 现存计数；**用户维度成员 key 残留=DB 真理（软删保留点赞记录）**，恢复后一致）。

**效果**：下架后内容在首页 `/start`（索引剔除）、搜索（`is_deleted=0` 过滤）、关注流 `/feed`、用户主页 `/profile`、作者本人「我的投稿」均不可见；详情 `/search/IdSearch` 返回 404。恢复后重新可见，且评论/点赞数/媒体数据完好。

**与 A1 删除的差异**：

| 维度 | A1 作者删除 | A2 管理员下架 |
|------|------------|--------------|
| 状态值 | is_deleted=1 | is_deleted=2 |
| 操作者 | 作者本人 | 管理员（role==1） |
| 关联数据 | 级联软删评论 / 物理删点赞 / 物理删媒体 / 删物理文件 | 全部保留 |
| 可恢复 | 否 | 是（管理员恢复） |

---

## 四、社交互动模块

### 4.1 点赞流程

#### 4.1.1 内容点赞

```
┌──────────┐  POST /like/content/add  ┌────────────────┐
│  客户端   │ ────────────────────────► │ LikeController  │
└──────────┘   ?contentId=123          └────────────────┘
       ▲                                      │
       │                                      ▼
       │                              ┌──────────────┐
       │                              │ LikeService   │
       │                              └──────────────┘
       │                                      │
       │          ┌─────────────┬─────────────┼─────────────┬─────────────┐
       │          ▼             ▼             ▼             ▼             ▼
       │   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
       │   │ 检查内容 │  │ 检查是否 │  │ 插入点赞 │  │ 更新点赞 │  │ 更新缓存 │
       │   │ 存在     │  │ 已点赞   │  │ 记录     │  │ 计数     │  │ Redis    │
       │   └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘
       │                                                            │
       │                                                            ▼
       │                                                     ┌──────────────┐
       │                                                     │ 失效内容 key │
       │                                                     │ (读自愈回填) │
       │                                                     └──────────────┘
       │
       └───────────────────── 返回 "点赞成功" ─────────────────┘
```

#### 详细步骤

| 步骤 | 操作 | 失败处理 |
|------|------|----------|
| 1 | 开启事务 | - |
| 2 | 检查内容是否存在 | 不存在返回 NotFoundException |
| 3 | 检查是否已点赞 | 已点赞返回 ConflictException |
| 4 | 插入 content_like 表 | SQLException 回滚 |
| 5 | 更新 content 表 like_count +1 | SQLException 回滚 |
| 6 | 提交事务 | - |
| 7 | 点赞缓存写：计数/成员分离条件写（`content:likeCount:{id}` 存在才 INCR + `user:likeSet:{userId}` 存在才 SADD contentId + 清空 `empty:user:likeSet:{userId}` 标记；成员 key 为用户维度） | 写失败→失效 count key 让读自愈（4.2），不阻塞主流程 |
| 8 | 失效内容 key `content:{id}`（`contentCache.notifyLikeCountChanged`，DB like_count 列为源真理，读自愈回填） | 失败只记录日志 |

> 说明：内存计数残留（旧 updateContentLikeCount 死代码）已删除；count/用户维度成员 key 均带 TTL（cache.like.ttlMinutes=15）自愈，Redis 挂时读写路径降级走 DB，点赞接口不会 500（H5）。

#### 取消点赞流程

```
POST /like/content/remove?contentId=123

步骤：
1. 开启事务
2. 检查内容存在
3. 检查是否已点赞（未点赞返回 ConflictException）
4. 删除 content_like 记录
5. 更新 content 表 like_count -1
6. 提交事务
7. 更新 Redis 缓存（条件 DECR/SREM，计数/成员分离；SREM 作用于用户维度 `user:likeSet:{userId}`）
8. 失效内容 key 读自愈回填 DB 最新计数
```

---

#### 4.1.2 评论点赞

```
POST /like/comment/add?commentId=456

步骤与内容点赞类似：
1. 检查评论存在
2. 检查是否已点赞
3. 插入 comment_like 记录
4. 更新 comment 表 like_count +1
5. 更新 Redis 点赞缓存（LikeCacheService）
6. 失效评论所属内容评论树 key（commentCache.notifyCommentLikeChanged，读自愈回填最新 likeCount）
```

---

### 4.2 评论流程

#### 4.2.1 发表评论

```
┌──────────┐  POST /comment/add  ┌──────────────────┐
│  客户端   │ ──────────────────► │ CommentController  │
└──────────┘   {contentId,       └──────────────────┘
                parentId,                   │
                message}                    ▼
                                      ┌──────────────────┐
                                      │  CommentService   │
                                      │  addComment()     │
                                      └──────────────────┘
                                              │
                ┌─────────────┬───────────────┼───────────────┬─────────────┐
                ▼             ▼               ▼               ▼             ▼
         ┌──────────┐  ┌──────────┐   ┌──────────┐   ┌──────────┐  ┌──────────┐
         │ 检查内容 │  │ 检查父评 │   │ 插入评论 │   │ 更新评论 │  │ 更新缓存 │
         │ 存在     │  │ 论归属   │   │ 记录     │   │ 计数     │  │ 即时同步 │
         └──────────┘  └──────────┘   └──────────┘   └──────────┘  └──────────┘
```

#### 详细步骤

| 步骤 | 操作 | 失败处理 |
|------|------|----------|
| 1 | 解析 CommentDTO | - |
| 2 | 转换为 CommentCommand | - |
| 3 | 开启事务 | - |
| 4 | 检查内容是否存在 | 不存在返回 NotFoundException |
| 5 | 如果是回复，查询被回复评论：不存在/不在该内容下 → Conflict；若被回复评论本身是回复，则上溯挂到其主楼 id，并记录 reply_to_user_id=被回复评论作者 id（楼中楼 @ 引用） | 不正确返回 ConflictException |
| 6 | 插入 comment 表（楼中楼：回复一律 parent_id=主楼 id） | SQLException 回滚 |
| 7 | 更新 content 表 comment_count +1（并失效内容 key `content:{id}` 读自愈回填 comment_count） | SQLException 回滚 |
| 8 | 提交事务 | - |
| 9 | 查询新评论详情 | - |
| 10 | 失效评论树 key（commentCache.invalidateComments，读自愈回填整树） | 失败只记录日志，不阻塞主流程 |

#### 接口定义

```
POST /comment/add
Content-Type: application/json
?token=xxx

请求体：
{
    "contentId": 123,
    "parentId": 0,       // 0/NULL=主楼（一级）；其他=被回复的评论 id（主楼或楼内回复均可，回复楼内回复时后端自动上溯挂主楼）
    "message": "评论内容"
}

成功响应：
{
    "code": 200,
    "message": "评论成功"
}
```

---

#### 4.2.2 查看评论

```
GET /comment/show?contentId=123&token=xxx（可选）
GET /comment/show?contentId=123&page=2&pageSize=10（可选分页，T8）

步骤：
1. 先确认内容存在且评论区开启（contentCache.getContent：内容不存在/隐藏/删除或作者关闭 → 直接返回空）
2. 从评论缓存获取评论树（CommentCache 三态：hit-empty=无评论直接空；miss=查 DB 回填整树+单飞；hit-data=直接返回；楼中楼两级：主楼 + 楼内回复平铺挂主楼）
3. 如果已登录，批量查询点赞状态
4. 转换为 CommentVO 树
5. 返回评论列表

分页（T8 → T10-A/T10-B → T11-B）：
- 传 page/pageSize 任一 → 返回分页信封 {list,total,page,pageSize,totalPages}（total=主楼条数）；
  第 3 步的点赞批量查询只针对该页评论 id。页内容形态（主楼窗口 + 每主楼 children 前 K=2 +
  replyCount，展开走 /comment/replies）见 CURRENT_ARCHITECTURE 6.18
- 两者都不传 → 仍返回全量数组（与改造前逐字节一致）
- 分页解析（T11-B；**T19 起公共 cap 50 已删除**）：page 缺省 1；pageSize **缺省 = 评论域信封 200**
  （由后端域常量决定，前端只传 page）、上限 **500**（评论域本轮不动；feed / search / profile / follow
  四域 T19 已统一为缺省/上限 **100**）
- 缓存侧：两键组（主楼 LIST + 楼中楼 HASH + count），不是整树 JSON（T10-A 起）

CommentVO 结构：
{
    "commentId": 1,
    "contentId": 123,
    "userId": 100,
    "username": "张三",
    "message": "评论内容",
    "parentId": 0,       // NULL=主楼；其他=所属主楼 id
    "replyToUserId": 200,    // 楼中楼 @ 引用：被回复评论作者 id；NULL=主楼或直接回复主楼
    "replyToUsername": "李四", // 被回复评论作者用户名（冗余存储，被 @ 评论被删后仍可展示）
    "likeCount": 5,
    "isLiked": false,
    "children": [...]  // 仅主楼有：楼内回复平铺列表（回复的回复也挂这里）
}
```

---

#### 4.2.3 删除评论（软删除，不可恢复；楼中楼规则）

```
┌──────────┐  POST /comment/delete  ┌──────────────────┐
│  客户端   │ ─────────────────────► │ CommentController │
└──────────┘   ?commentId=           └──────────────────┘
                                              │
                                              ▼
                                      ┌──────────────────┐
                                      │  CommentService   │
                                      │  deleteCommentByUser()
                                      └──────────────────┘
```

| 步骤 | 操作 | 失败处理 |
|------|------|----------|
| 1 | 校验登录（AuthFilter 精确匹配 `/comment/delete`） | 401 |
| 2 | 检查评论存在且未删除 | 不存在返回 NotFoundException（404） |
| 3 | 校验 userId == comment.userId | 否则 ForbiddenException（403） |
| 4 | 删**主楼**（parent_id IS NULL）：整栋软删 `WHERE comment_id=? OR parent_id=?`；deletedCount = 1+楼内回复数 | - |
| 5 | 删**回复**：仅软删自己 `WHERE comment_id=?`；deletedCount = 1 | - |
| 6 | content 表 comment_count -= deletedCount | SQLException 回滚 |
| 7 | 提交事务后同步缓存：失效内容 key `content:{id}`（回填 comment_count）+ 失效评论树 key（commentCache.invalidateComments，回填整树） | 失败只记录日志 |

> **管理员删除**：`POST /api/admin/comment/delete?commentId=X`（AuthFilter 校验 role==1）。逻辑同步骤 4-7，跳过步骤 3 的所有权校验。
>
> 删除规则汇总（楼中楼 v0.4 定案）：
>
> | 删除对象 | 规则 | SQL 形态 |
> |---------|------|---------|
> | 主楼 | 整栋楼软删 | `UPDATE comment SET is_deleted=1 WHERE comment_id=? OR parent_id=?` |
> | 回复 | 只删自己 | `UPDATE comment SET is_deleted=1 WHERE comment_id=?` |
>
> 均**不可恢复**；自删与管理员删不区分删除者，无恢复入口。

---

#### 4.2.4 评论区开关（作者本人，阶段二）

```
┌──────────┐  POST /content/commentEnabled  ┌──────────────────┐
│  客户端   │ ─────────────────────────────► │ ContentController │
└──────────┘   ?contentId=X&enabled=0|1      └──────────────────┘
                                                      │
                                                      ▼
                                              ┌──────────────────┐
                                              │  ContentService   │
                                              │  setCommentEnabled()
                                              └──────────────────┘
```

| 步骤 | 操作 | 失败处理 |
|------|------|----------|
| 1 | 校验登录（AuthFilter 精确匹配 `/content/commentEnabled`） | 401 |
| 2 | 查询内容（`comment_enabled` 随查询读取） | 不存在/已删除返回 NotFoundException（404） |
| 3 | 校验 content.author_id == 操作者 userId | 否则 ForbiddenException（403） |
| 4 | `UPDATE content SET comment_enabled=? WHERE id=?`（1=开, 0=关） | SQLException 回滚 |
| 5 | 提交事务后同步内存缓存：contentCache / recommendList 的 commentEnabled | 失败只记录日志 |

**语义**（与评论软删除彻底分离，不逐条动 comment.is_deleted）：

| 状态 | 发表（/comment/add） | 查询（/comment/show） |
|------|---------------------|----------------------|
| 评论开启 | 正常 | 返回评论树 |
| 评论关闭 | ConflictException（409「评论区已关闭」） | 返回空列表 |

- 关闭仅隐藏：评论数据保留，重新开启后原评论恢复；已软删评论不受开关影响；`comment_count` 不因开关增减。
- 数据载体：`content.comment_enabled TINYINT NOT NULL DEFAULT 1`；字段贯通 `ContentCacheDTO`（backfill/刷新）与 /start、/profile、详情等 VO。
- 前端入口：创作中心「我的投稿」卡片「关闭/开启评论区」按钮（仅作者本人页面）；详情页按 `commentEnabled` 决定是否展示评论区入口/输入框。

---

### 4.3 关注流程

#### 4.3.1 关注用户

```
┌──────────┐  POST /follow/add  ┌─────────────────┐
│  客户端   │ ─────────────────► │ FollowController  │
└──────────┘   ?followedUserId=  └─────────────────┘
               456                        │
                                          ▼
                                  ┌──────────────┐
                                  │ FollowService │
                                  └──────────────┘
                                          │
                      ┌───────────────────┼───────────────────┐
                      ▼                   ▼                   ▼
               ┌──────────┐        ┌──────────┐        ┌──────────┐
               │ 不能关注 │        │ 插入关注 │        │ 更新计数 │
               │ 自己     │        │ 记录     │        │ 双向     │
               └──────────┘        └──────────┘        └──────────┘
```

#### 详细步骤

| 步骤 | 操作 | 失败处理 |
|------|------|----------|
| 1 | 检查不能关注自己 | 返回 ConflictException |
| 2 | 开启事务 | - |
| 3 | 插入 follow 表 | 返回 0 表示已关注 |
| 4 | 更新关注者 follow_count +1 | - |
| 5 | 更新被关注者 follower_count +1 | - |
| 6 | 提交事务 | - |
| 7 | 提交后缓存双写 FollowCache.cacheFollow：两 key 已加载且**均非部分装载态** → MULTI ZADD 双写（score=成员 id，2026-09-19 T7 起成员 key 为 ZSet）；冷 key/空标记/任一侧带 `partial:` 标记（T11-C：插入非前缀成员会破坏 `ZRANGE offset` 语义）→ 三件套双 DEL（数据 key + 空标记 + `partial:` 标记）失效 | 缓存失败降级（双 DEL），不影响业务 |

#### 接口定义

```
POST /follow/add?followedUserId=456&token=xxx

成功响应：
{
    "code": 200,
    "message": "关注成功"
}

失败响应：
{
    "code": 409,
    "message": "不能关注自己"
}
```

---

#### 4.3.2 取消关注

```
POST /follow/remove?followedUserId=456&token=xxx

步骤：
1. 检查不能取关自己
2. 开启事务
3. 删除 follow 记录（返回 0 表示未关注）
4. 更新关注者 follow_count -1
5. 更新被关注者 follower_count -1
6. 提交事务
```

---

#### 4.3.3 查看关注列表

```
GET /follow/following?userId=123&token=xxx（必填：/follow/* 前缀守卫需登录，2026-09-09 按代码修正"可选"标注）

步骤：
1. 取该页关注 ID（缓存**窗口读**；冷 key / 前缀不足时只装载该页所需的那一段，T11-C）
2. 批量查询用户信息
3. 如果已登录，查询当前用户对这些用户的关注状态
4. 返回用户列表

返回结构（列表恒为分页信封，2026-09-20 T11-A；**2026-09-21 T19 信封 200 → 100**）：
{
    "list": [
        {
            "userId": 456,
            "username": "李四",
            "isFollowed": true,   // 当前用户是否关注
            "isSelf": false       // 是否是自己
        },
        ...
    ],
    "total": N, "page": 1, "pageSize": 100, "totalPages": t
}

分页（2026-09-19 T7 → 2026-09-20 T11-A 契约变更，已获用户批准 → **2026-09-21 T19 域级信封调整**）：
  - **列表恒为信封** {"list": [...], "total": N, "page": p, "pageSize": s, "totalPages": t}
    （T7 的"缺省不传参 → 返回全量数组"分支已**删除**）
  - **缺省（不传任何分页参数）= 第一页信封**（page 1 / pageSize 100），与显式
    page=1&pageSize=100 响应**逐字节一致**（信封大小由后端 follow 域常量决定，前端只传 page）
  - page 缺省 1、page<1 归一为 1；pageSize 缺省 **100**、上限 **100**（T11-A 曾为 200，T19 调整为 100）；
    显式传 pageSize 仍生效（保留 pytest 用小信封逐页比对"页间不重不漏"的能力）
  - 越界页（offset ≥ total）→ list 为空数组，total 照常返回（前端据此判末页）

装载与 total 口径（2026-09-20 T11-C，对外契约不变、仅内部装载形态变化）：
  - 装载量与**页位置**相关：冷 key 只装载 [0, offset+count)、已知前缀不足只补差量；
    集合可能处于"前缀"态（带 partial: 标记），完全装载后自动退化为完整 ZSet
  - total：**完整态 = ZCARD**（与页内容同源，与 T7 一致）；**部分态 / Redis 降级态 = 域级计数 key**
    （user:followCount / user:followerCount，miss 回落 users 表计数列）——此时 ZCARD 只是已知前缀大小
  - Redis 降级：DB **窗口直查**（只查被看的那一段）、不装载不写回（第三期 T2"降级不放量"口径保持）
  - 内部实现与不变量（partial: 标记 / 判定回落 DB / 全量读补齐 / 写路径双 DEL）见
    CURRENT_ARCHITECTURE 6.17
```

---

#### 4.3.4 查看粉丝列表

```
GET /follow/followers?userId=123&token=xxx（必填：/follow/* 前缀守卫需登录，2026-09-09 按代码修正"可选"标注）

步骤：
1. 查询用户的所有粉丝 ID
2. 批量查询用户信息
3. 如果已登录，查询当前用户对这些用户的关注状态
4. 返回用户列表

分页（2026-09-19 T7 → 2026-09-20 T11-A → **2026-09-21 T19**）：口径与 4.3.3 完全一致（列表恒为
{"list","total","page","pageSize","totalPages"} 信封；缺省=第一页信封；pageSize 缺省/上限均 **100**
（T19 由 200 调整为 100）；越界页空 list + total 照常）
```

---

## 五、优惠券模块

### 5.1 优惠券抢购流程

```
┌──────────┐  POST /coupon/grab  ┌─────────────────┐
│  客户端   │ ──────────────────► │ CouponController  │
└──────────┘   {couponId}        └─────────────────┘
                                          │
                                          ▼
                                  ┌──────────────────┐
                                  │  CouponService    │
                                  │  grabCoupon()     │
                                  └──────────────────┘
                                          │
                      ┌───────────────────┼───────────────────┐
                      ▼                   ▼                   ▼
               ┌──────────┐        ┌──────────┐        ┌──────────┐
               │ 扣减库存 │        │ 生成兑换 │        │ 插入订单 │
               │ (乐观锁) │        │ 码       │        │ (唯一索引)│
               └──────────┘        └──────────┘        └──────────┘
```

#### 详细步骤

| 步骤 | 操作 | 失败处理 |
|------|------|----------|
| 1 | 开启事务 | - |
| 2 | 扣减库存（UPDATE WHERE stock > 0） | 返回 0 表示库存不足 |
| 3 | 生成兑换码（UUID 前16位） | - |
| 4 | 插入 coupon_order 表 | 唯一索引冲突表示已抢过 |
| 5 | 提交事务 | - |
| 6 | 返回兑换码 | - |

#### 接口定义

```
POST /coupon/grab
Content-Type: application/json
?token=xxx

请求体：
{
    "couponId": 1
}

成功响应：
{
    "code": 200,
    "data": "A1B2C3D4E5F6G7H8"  // 兑换码
}

失败响应（库存不足）：
{
    "code": 409,
    "message": "库存不足或活动未开始/已结束"
}

失败响应（重复抢购）：
{
    "code": 409,
    "message": "您已抢过该优惠券"
}
```

---

### 5.2 查看可用优惠券

```
GET /coupon/list

步骤：
1. 查询 coupon 表（当前时间在 begin_time 和 end_time 之间）
2. 返回优惠券列表
```

### 5.3 查看我的优惠券

```
GET /coupon/my?token=xxx

步骤：
1. 查询 coupon_order 表（按 userId）
2. 关联 coupon 表获取优惠券信息
3. 返回兑换码列表
```

---

## 六、Feed 流模块

### 6.1 关注动态流流程

```
┌──────────┐   GET /feed   ┌─────────────────┐
│  客户端   │ ────────────► │ FeedController    │
└──────────┘   ?page=1     └─────────────────┘
               (pageSize=100)      │
                                    ▼
                            ┌──────────────────┐
                            │   FeedService     │
                            │   getFeed()       │
                            └──────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
             ┌──────────┐   ┌──────────┐   ┌──────────┐
             │ 查询关注 │   │ 查询内容 │   │ 填充点赞 │
             │ 列表     │   │ (分页)   │   │ 状态     │
             │          │   │          │   │          │
             │ followDao│   │ contentDao│  │ likeService│
             └──────────┘   └──────────┘   └──────────┘
```

#### 详细步骤

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 查询当前用户的关注列表 | followDao.getAllFollowedUserIds() |
| 2 | 如果无关注，返回空列表 | - |
| 3 | 查询关注用户的内容总数 | contentDao.countContentByUsers() |
| 4 | 计算分页偏移量 | offset = (page-1) * pageSize |
| 5 | 查询当前页内容 ID | contentDao.findContentIdsByUsers() |
| 6 | 从缓存获取内容详情 | contentService.getContentFromCache() |
| 7 | 批量填充点赞状态 | likeService.batchIsContentLiked() |
| 8 | 返回分页结果 | PageResult |

#### 接口定义

```
GET /feed?page=1&token=xxx

成功响应（**T19：信封大小由后端 feed 域常量决定 = 100，前端只传 page**）：
{
    "code": 200,
    "data": {
        "list": [...],
        "total": 50,
        "page": 1,
        "pageSize": 100
    }
}
```

### 6.2 写扩散与收件箱重建（影子期：只写不读）

> 一期「feed 推拉结合改造」的地基。**对外行为零变化**：`/feed` 仍走 6.1 的拉模式，收件箱只写不读；本节描述的是 Redis 侧的**派生副本**如何逐步建立，供二期切读评估。

两层机制（都对业务链路**只降级、不抛出**；MQ 不可用不得阻断应用启动）：

```
发布（addVideo / addPost，事务提交后）
        │ publish feed.push.content
        ▼
   feed.push.queue ─► FeedPushConsumer ─► 按作者粉丝窗口迭代（分页）
                                            │  ZADD feed:inbox:{fanId} + EXPIRE
                                            ▼
                                  粉丝收件箱（Redis ZSet，member = score = contentId）

关注 / 取关（事务提交后）
        │ publish feed.rebuild.inbox（对象 = 发起方本人）
        ▼
  feed.rebuild.queue ─► FeedRebuildConsumer ─► DEL feed:inbox:{id} + DEL 完整态标记
                                                → DB 重查（content ⋈ follow，与拉模式同一 SQL）
                                                → 分批 ZADD + SET feed:inbox:full:{id}
```

| 机制 | 触发 | 产物 | 幂等 / 失败 |
|------|------|------|-------------|
| **写扩散**（fanout，增量） | 发布视频 / 动态（**事务提交后**投 MQ） | `feed:inbox:{fanId}` 追加 contentId + 滑动续期 TTL | `ZADD` 幂等；投递 / 写入失败只记日志、**绝不抛穿发布接口**；不写、不删完整态标记 |
| **收件箱重建**（rebuild，全量 / 失效） | 关注 / 取关（**事务提交后**投 MQ，对象 = **发起方本人**） | 收件箱重算 + `feed:inbox:full:{id}` 完整态标记（`SET … EX feed.inbox.ttlMinutes`，默认 60min） | 三步 `DEL → DB 重查 → ZADD` 顺序**不可调换**；失败一律降级 ACK（仅载荷非法转死信）；`SET NX EX` 去重锁只是优化，锁超时交叉的产物 = 两次快照**并集**（只多不丢，由下次重建收口） |

> 残余（已登记、一期无影响）：**fanout 的滑动续期只续收件箱、不续完整态标记** ⇒ 两者 TTL 可漂移；二期切读时统一续期口径。详见 `目标与任务/NEXT_CYCLE_TASKS.md` T19 段 G11 ③。

- **真相源与派生**：收件箱 = **派生副本**，真相源 = `content` + `follow`（可重算、可重建自愈）；收件箱只存 DB 可重算的内容。
- **完整性闸门**：`feed:inbox:full:{id}` **只由重建写**、`fanout` 从不触碰 ⇒ 只有"发生过关注 / 取关"的用户才有标记；**无标记 ⇒ 完整性未知 ⇒ 二期回退拉模式**（标记缺失 = 已知设计边界，不是缺陷）。
- **二期切读门**：仅"标记存在"的收件箱可切换为读来源；一致性 / 覆盖率的测量由 **T20 只读工具** `tools/feed_shadow_check.py`（`tv.py feed-shadow`）承担——按"标记是否存在"分组，有标记者与拉模式 oracle 逐条比对。

---

## 七、权限控制矩阵

### 7.1 AuthFilter 保护路径

| 路径 | 匹配方式 | 需要登录 |
|------|----------|----------|
| `/api/upload/*` | 前缀 | ✓ |
| `/follow/*` | 前缀 | ✓ |
| `/like/*` | 前缀 | ✓ |
| `/feed` | 前缀 | ✓ |
| `/api/admin/*` | 前缀 | ✓ |
| `/comment/add` | 精确 | ✓ |
| `/comment/delete` | 精确 | ✓ |
| `/content/commentEnabled` | 精确 | ✓ |
| `/content/update` | 精确 | ✓ |
| `/content/mediaDelete` | 精确 | ✓ |
| `/content/delete` | 精确 | ✓ |
| `/user/changePassword` | 精确 | ✓ |
| `/user/changeUserName` | 精确 | ✓ |
| `/coupon/grab` | 精确 | ✓ |
| `/coupon/my` | 精确 | ✓ |

### 7.2 公开接口（无需登录）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/user/login` | POST | 登录 |
| `/user/register` | POST | 注册（自动登录失败时仍 200、`data.token=null`：注册成功但需手动登录，T13） |
| `/start` | GET | 首页推荐 |
| `/search/keywordSearch` | GET | 搜索 |
| `/search/IdSearch` | GET | 内容详情（无 /detail 端点） |
| `/comment/show` | GET | 查看评论（T8 起可选 `page`/`pageSize`：传任一返回分页信封 `{list,total,...}`，`total`=主楼条数；缺省仍全量数组；`pageSize` 缺省 200（域级信封，T11-B）、上限 500） |
| `/comment/replies` | GET | 展开某主楼全部回复（`rootId&page&pageSize`；`pageSize` 缺省 200、上限 500） |
| `/coupon/list` | GET | 优惠券列表 |
| `/profile` | GET | 用户主页 |

### 7.3 资源所有权校验（当前缺失）

| 操作 | 应该校验 | 当前状态 |
|------|----------|----------|
| 删除内容 | userId == content.authorId | ✓ 已校验（阶段四，作者本人软删除；管理员走 /api/admin/content/* 下架） |
| 删除评论 | userId == comment.userId（管理员走 /api/admin/comment/delete） | ✓ 已校验（阶段一，软删除） |
| 开关评论区 | userId == content.authorId | ✓ 已校验（阶段二） |
| 下架/恢复内容 | 仅管理员（role==1，AuthFilter /api/admin/*） | ✓ 已校验（阶段五） |
| 修改密码 | userId == targetUserId | ✓ 已校验（通过 token） |
| 修改用户名 | userId == targetUserId | ✓ 已校验（通过 token） |

---
