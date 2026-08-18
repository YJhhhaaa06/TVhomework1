# 业务流程文档

> 版本：1.0
> 生成日期：2026-07-23
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
- [八、已发现的问题](#八已发现的问题)

---

## 一、总体架构

### 1.1 请求处理流程

```
客户端请求
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│                    Filter 链                             │
│  EncodingFilter → LoginFilter → AuthFilter              │
│  (UTF-8编码)      (解析Token)    (权限校验)               │
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
| 9 | 自动登录，返回 LoginVO | - |

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
| 4 | 查询用户信息 | 用户不存在返回 RuntimeException("USER_NOT_FOUND") |
| 5 | 验证手机号匹配 | 不匹配返回 RuntimeException("PHONE_INCORRECT") |
| 6 | 验证旧密码正确 | 不正确返回 RuntimeException("OLD_PASSWORD_ERROR") |
| 7 | 新密码 BCrypt 哈希 | - |
| 8 | 更新数据库密码 | 更新失败返回 RuntimeException("UPDATE_FAILED") |
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
POST /user/changeUserName?token=xxx&newName=新名字

步骤：
1. 验证登录状态
2. 校验新用户名长度 < 50
3. 开启事务
4. 检查用户存在
5. 更新用户名
6. 提交事务
```

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

### 3.1 内容缓存机制

```
┌─────────────────────────────────────────────────────────────────┐
│                    ContentService 缓存结构                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────┐    ┌─────────────────┐                    │
│  │  recommendList   │    │  contentCache   │                    │
│  │  (推荐列表)      │    │  (内容详情)     │                    │
│  │  List<ContentVO> │    │  Map<id, DTO>   │                    │
│  └─────────────────┘    └─────────────────┘                    │
│                                                                 │
│  ┌─────────────────┐    ┌─────────────────┐                    │
│  │  commentCache    │    │ typeCategoryIndex│                   │
│  │  (评论缓存)      │    │  (类型分区索引)  │                    │
│  │  Map<id, List>   │    │  Map<key, List>  │                    │
│  └─────────────────┘    └─────────────────┘                    │
│                                                                 │
│  定时刷新：每 10 分钟全量刷新                                    │
│  TTL：单条内容 10 分钟过期                                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

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
                                      │ 3. 保存到 D:/stone│
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

> **前端交互（2026-08-14 重构后）**：首页为 SPA 视图 `#/`；分区（推荐/游戏/音乐…）收纳在顶部导航「分类」下拉（选中跳 `#/?cat=<id>`）；类型筛选（全部/视频/图文）在首页内容区；「换一换」重新拉 `/start` 并在客户端打乱顺序以获得「新一批」观感；关注流独立为 `#/follow`（`/feed` 分页）。

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
┌──────────┐  GET /search?keyword=xxx  ┌─────────────────┐
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

#### 接口定义

```
GET /search?keyword=关键词&page=1&pageSize=10&token=xxx（可选）

成功响应：
{
    "code": 200,
    "data": {
        "list": [...],
        "total": 100,
        "page": 1,
        "pageSize": 10
    }
}
```

---

### 3.6 查看内容详情流程

> **前端交互（2026-08-14 重构后）**：详情为 SPA 视图 `#/video/:id`（原 `detail.html?contentId=` 已删除）；详情端点实为 `GET /search/IdSearch`（**无 `/detail`**）；右侧新增「相关推荐」栏，本轮用 `/start` 推荐流兜底（后端暂无推荐接口）；评论为树形，支持回复/点赞；视频类型用 `videoUrl` 播放，图文类型展示 `coverUrl` + `imageUrls` 画廊。

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

- 当前：`/api/admin/*` 仅要求登录，无管理员角色。
- 后续：上线管理员身份，并允许作者对自己的帖子和视频换源。

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
       │                                                     ┌──────────┐
       │                                                     │ 更新内存 │
       │                                                     │ 缓存计数 │
       │                                                     └──────────┘
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
| 7 | 更新 Redis 缓存 | 失败只记录日志 |
| 8 | 更新内存缓存 | 失败只记录日志 |

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
7. 更新 Redis 缓存
8. 更新内存缓存
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
5. 更新 Redis 缓存
6. 更新内存缓存
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
| 5 | 如果是回复，检查父评论归属正确 | 不正确返回 ConflictException |
| 6 | 插入 comment 表 | SQLException 回滚 |
| 7 | 更新 content 表 comment_count +1 | SQLException 回滚 |
| 8 | 提交事务 | - |
| 9 | 查询新评论详情 | - |
| 10 | 即时更新评论缓存 | 失败只记录日志 |

#### 接口定义

```
POST /comment/add
Content-Type: application/json
?token=xxx

请求体：
{
    "contentId": 123,
    "parentId": 0,       // 0=顶级评论，其他=回复
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

步骤：
1. 从缓存获取评论树
2. 如果已登录，批量查询点赞状态
3. 转换为 CommentVO 树
4. 返回评论列表

CommentVO 结构：
{
    "commentId": 1,
    "contentId": 123,
    "userId": 100,
    "username": "张三",
    "message": "评论内容",
    "parentId": 0,
    "likeCount": 5,
    "isLiked": false,
    "children": [...]  // 子评论
}
```

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
GET /follow/following?userId=123&token=xxx（可选）

步骤：
1. 查询用户的所有关注 ID
2. 批量查询用户信息
3. 如果已登录，查询当前用户对这些用户的关注状态
4. 返回用户列表

返回结构：
[
    {
        "userId": 456,
        "username": "李四",
        "isFollowed": true,   // 当前用户是否关注
        "isSelf": false       // 是否是自己
    },
    ...
]
```

---

#### 4.3.4 查看粉丝列表

```
GET /follow/followers?userId=123&token=xxx（可选）

步骤：
1. 查询用户的所有粉丝 ID
2. 批量查询用户信息
3. 如果已登录，查询当前用户对这些用户的关注状态
4. 返回用户列表
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
               &pageSize=10         │
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
GET /feed?page=1&pageSize=10&token=xxx

成功响应：
{
    "code": 200,
    "data": {
        "list": [...],
        "total": 50,
        "page": 1,
        "pageSize": 10
    }
}
```

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
| `/user/changePassword` | 精确 | ✓ |
| `/coupon/grab` | 精确 | ✓ |
| `/coupon/my` | 精确 | ✓ |

### 7.2 公开接口（无需登录）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/user/login` | POST | 登录 |
| `/user/register` | POST | 注册 |
| `/start` | GET | 首页推荐 |
| `/search` | GET | 搜索 |
| `/detail` | GET | 内容详情 |
| `/comment/show` | GET | 查看评论 |
| `/coupon/list` | GET | 优惠券列表 |
| `/profile` | GET | 用户主页 |
| `/follow/following` | GET | 关注列表 |
| `/follow/followers` | GET | 粉丝列表 |

### 7.3 资源所有权校验（当前缺失）

| 操作 | 应该校验 | 当前状态 |
|------|----------|----------|
| 删除内容 | userId == content.authorId | ❌ 未校验 |
| 删除评论 | userId == comment.userId | ❌ 未校验 |
| 修改密码 | userId == targetUserId | ✓ 已校验（通过 token） |
| 修改用户名 | userId == targetUserId | ✓ 已校验（通过 token） |

---

## 八、已发现的问题

### 8.1 业务逻辑问题

#### 问题1：异常类型不统一

**位置**: `UserService.changePassword()`

```java
// 当前代码 - 混用 RuntimeException 和 BusinessException
if (dbUser == null) {
    throw new RuntimeException("USER_NOT_FOUND");  // ✗ 应该用 NotFoundException
}
if (!phone.equals(dbUser.getPhone())) {
    throw new RuntimeException("PHONE_INCORRECT");  // ✗ 应该用 ParamException
}
```

**影响**: Controller 层无法统一捕获 BusinessException

**建议**: 统一使用具体业务异常类

---

#### 问题2：注册后自动登录缺少异常处理

**位置**: `LoginController.register()`

```java
protected void register(HttpServletRequest req, HttpServletResponse resp) throws Exception {
    RegisterDTO dto = RequestParser.parse(req, RegisterDTO.class);
    RegisterCommand rc = CommandConverter.registerToCommand(dto);
    long id = userService.registerAsUser(rc);
    LogInVO ls = userService.login(id, rc.getPassword());  // ⚠️ 如果登录失败？
    BaseServletUtil.writeSuccess(resp, ls);
}
```

**风险**: 注册成功但登录失败（理论上不应发生），会导致未返回 token

**建议**: 登录失败时返回注册成功但提示手动登录

---

#### 问题3：评论添加后缓存更新时机

**位置**: `CommentService.addComment()`

```java
conn.commit();
// 即时更新评论缓存
CommentCacheDTO newComment = commentDao.findCommentById(conn, commentId);  // ⚠️ 使用已提交的连接
```

**风险**: 事务已提交，但后续查询使用同一个连接，如果连接状态异常可能失败

**建议**: 缓存更新应该在独立的连接中进行，或者只依赖定时刷新

---

#### 问题4：点赞计数与缓存一致性

**位置**: `LikeService.likeContent()`

```java
contentDao.updateLikeCount(conn, contentId, 1);
conn.commit();

// 缓存更新在事务外
cache.likeContent(userId, contentId);
ContentService.updateContentLikeCount(contentId, 1);
```

**风险**: 如果缓存更新失败，数据库与缓存不一致

**缓解**: 有定时刷新机制，最终会一致

**建议**: 考虑使用消息队列保证最终一致性

---

#### 问题5：优惠券抢购无用户限流

**位置**: `CouponService.grabCoupon()`

```java
int rows = couponDao.deductStock(conn, couponId);
if (rows == 0) {
    throw new ConflictException("库存不足或活动未开始/已结束");
}
```

**风险**: 高并发下，大量请求同时通过库存检查，可能导致超卖

**当前缓解**: 依赖数据库行锁 + 唯一索引

**建议**: 可增加 Redis 预扣减或令牌桶限流

---

### 8.2 数据一致性问题

#### 问题6：关注数/粉丝数更新非原子

**位置**: `FollowService.follow()`

```java
userDao.updateFollowCount(conn, userId, 1);
userDao.updateFollowerCount(conn, followedUserId, 1);
```

**风险**: 如果第二步失败，关注数和粉丝数不一致

**当前缓解**: 在同一事务中，会回滚

---

#### 问题7：内容删除未清理关联数据

**位置**: `ContentService.deleteContent()`

```java
public void deleteContent(long contentId, long userId) {
    ContentCacheDTO dto = contentCache.get(contentId);
    if (dto != null) {
        removeFromIndex(contentId, dto.getType(), dto.getCategoryId());
    }
    evictContent(contentId);
    synchronized (recommendList) {
        recommendList.removeIf(cvo -> cvo.getId() == contentId);
    }
}
```

**风险**: 只清理了缓存，未删除数据库记录（评论、点赞等）

**建议**: 应该是软删除（is_deleted = 1），或者级联删除关联数据

---

### 8.3 安全问题

#### 问题8：缺少资源所有权校验

**位置**: 所有删除/修改操作

**风险**: 用户 A 可以删除用户 B 的内容/评论

**建议**: 在 Service 层添加所有权校验

---

#### 问题9：JWT 密钥硬编码

**位置**: `JwtUtil.java`

**风险**: 密钥泄露

**建议**: 从配置文件读取，生产环境使用环境变量

---

### 8.4 性能问题

#### 问题10：关注列表 N+1 查询

**位置**: `FollowService.getFollowingList()`

```java
List<Long> ids = followDao.getAllFollowedUserIds(conn, userId);
return buildUserList(conn, ids, currentUserId);
```

**当前优化**: 已使用批量查询 `findUsersByIds`

---

#### 问题11：缓存全量刷新开销大

**位置**: `ContentService.refresh()`

**风险**: 每 10 分钟全量刷新，如果数据量大，可能造成数据库压力

**建议**: 增量刷新或使用 Redis 作为主缓存

---

## 九、重构保护检查清单

在进行任何重构时，必须验证以下流程不受影响：

### 核心流程验证

| 测试项 | 验证方法 |
|--------|----------|
| 用户注册 | 注册新用户，返回 token |
| 用户登录 | 使用手机号/ID 登录 |
| 修改密码 | 修改后用新密码登录 |
| 发布视频 | 上传视频+封面，首页可见 |
| 发布动态 | 上传图片帖，首页可见 |
| 首页推荐 | 返回内容列表，有封面和计数 |
| 搜索 | 关键词搜索返回结果 |
| 内容详情 | 查看详情，有评论列表 |
| 点赞内容 | 点赞后计数+1，取消后-1 |
| 点赞评论 | 点赞后计数+1，取消后-1 |
| 发表评论 | 评论后即时显示 |
| 关注用户 | 关注后计数更新 |
| 取消关注 | 取关后计数更新 |
| 关注动态 | 只显示关注用户的内容 |
| 优惠券抢购 | 扣库存，生成兑换码 |
| 我的优惠券 | 显示已抢优惠券 |

### 边界条件验证

| 测试项 | 验证方法 |
|--------|----------|
| 重复点赞 | 返回 409 冲突 |
| 重复关注 | 返回 409 冲突 |
| 关注自己 | 返回 409 冲突 |
| 未登录访问 | 返回 401 未授权 |
| 资源不存在 | 返回 404 |
| 库存不足 | 返回 409 |
| 重复抢券 | 返回 409 |

---

## 十、API 接口汇总

### 10.1 用户相关

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| POST | /user/login | 登录 | ✗ |
| POST | /user/register | 注册 | ✗ |
| POST | /user/changePassword | 修改密码 | ✓ |
| POST | /user/changeUserName | 修改用户名 | ✓ |
| POST | /user/changePhone | 修改手机号 | ✓ |

### 10.2 内容相关

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| GET | /start | 首页推荐 | ✗ |
| GET | /search | 搜索 | ✗ |
| GET | /detail | 内容详情 | ✗ |
| GET | /feed | 关注动态 | ✓ |
| POST | /api/upload/video | 上传视频 | ✓ |
| POST | /api/upload/post | 上传动态 | ✓ |

### 10.3 社交相关

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| POST | /like/content/add | 点赞内容 | ✓ |
| POST | /like/content/remove | 取消点赞 | ✓ |
| POST | /like/comment/add | 点赞评论 | ✓ |
| POST | /like/comment/remove | 取消点赞 | ✓ |
| GET | /like/content/status | 点赞状态 | ✓ |
| GET | /like/comment/status | 点赞状态 | ✓ |
| POST | /comment/add | 发表评论 | ✓ |
| GET | /comment/show | 查看评论 | ✗ |
| POST | /follow/add | 关注 | ✓ |
| POST | /follow/remove | 取关 | ✓ |
| GET | /follow/following | 关注列表 | ✗ |
| GET | /follow/followers | 粉丝列表 | ✗ |

### 10.4 用户主页

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| GET | /profile | 用户主页 | ✗ |

### 10.5 优惠券相关

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| GET | /coupon/list | 可用优惠券 | ✗ |
| GET | /coupon/my | 我的优惠券 | ✓ |
| POST | /coupon/grab | 抢购优惠券 | ✓ |

### 10.6 媒体运维相关

| 方法 | 路径 | 说明 | 需要登录 |
|------|------|------|----------|
| GET | /api/admin/media/list | 扫描并返回媒体资源状态 | ✓ |
| POST | /api/admin/media/scan | 重新扫描并回写状态 | ✓ |
| POST | /api/admin/media/restore | 按数据库原文件名重新上传写回 | ✓ |

---

## 文档历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.1 | 2026-08-08 | 新增媒体运维流程（扫描/恢复）；上传路径更新为 D:/data/projects/VideoPlatform/stone；媒体 URL 改为动态 context path |
| 1.0 | 2026-07-23 | 初始版本 |
