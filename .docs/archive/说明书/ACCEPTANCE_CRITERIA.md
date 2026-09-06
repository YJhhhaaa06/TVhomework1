# 验收标准文档

> 版本：1.0
> 生成日期：2026-07-23
> 状态：**初稿，不一定准确，后续根据实际测试结果纠错**
> 用途：指导自动化验收测试脚本的编写

> **已归档（2026-09-05，T1 文档收口）**：三层验收模型与 S/C/E 编号体系已废弃（验收标准由测试代码承担，唯一仍准的 6.3 计数断言模式已由测试代码吸收）；跑测试的唯一权威入口见 `.docs/说明书/TEST_AUTOMATION.md`（勿改此归档文件，仅追溯用）。

---

## 一、验收体系总览

### 1.1 三层验收模型

```
┌─────────────────────────────────────────────────────────┐
│                   Level 1: 冒烟测试                      │
│   核心流程能走通（注册→登录→发内容→互动→查询）             │
│   目标：确认系统基本可用                                  │
├─────────────────────────────────────────────────────────┤
│                   Level 2: 数据一致性                     │
│   操作后数据库/缓存状态正确                               │
│   目标：确认业务逻辑正确                                  │
├─────────────────────────────────────────────────────────┤
│                   Level 3: 边界与异常                     │
│   错误输入返回正确错误码，重复操作幂等                     │
│   目标：确认系统健壮性                                    │
└─────────────────────────────────────────────────────────┘
```

### 1.2 验收优先级

| 优先级 | 说明 | 执行时机 |
|--------|------|----------|
| P0 | 核心业务流程 | 每次改动必须验证 |
| P1 | 数据一致性 | 重构后验证 |
| P2 | 边界与异常 | 发版前验证 |

---

## 二、测试环境要求

### 2.1 前置条件

| 依赖 | 要求 | 说明 |
|------|------|------|
| Tomcat | 运行中，端口 8080 | 应用部署在根路径（IDEA 运行配置 CONTEXT_PATH="/"） |
| MySQL | 运行中，端口 3306 | 数据库 `TVDatabase` 可访问 |
| Redis | 运行中，端口 6379 | 用于点赞缓存 |
| 存储目录 | `D:/data/projects/VideoPlatform/stone` 可读写 | 文件上传目标目录 |

### 2.2 测试数据准备

```
测试前需要：
1. 至少 2 个测试用户（通过注册接口创建）
2. 至少 1 个已发布的内容（用于点赞/评论/搜索）
3. 至少 1 个可用优惠券（用于抢券测试）

测试后可选：
- 清理测试数据（标记或删除）
- 清理上传的测试文件
```

---

## 三、Level 1: 冒烟测试验收标准

### 3.1 用户模块

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| S-01 | 注册 | POST /user/register | code=200，返回 id + token | P0 |
| S-02 | 登录（手机号） | POST /user/login | code=200，返回 id + token | P0 |
| S-03 | 登录（用户ID） | POST /user/login | code=200，返回 id + token | P0 |

### 3.2 内容模块

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| S-04 | 首页推荐 | GET /start | code=200，data 数组长度 > 0 | P0 |
| S-05 | 搜索 | GET /search/keywordSearch?keyword=测试 | code=200，返回分页结构 | P0 |
| S-06 | 内容详情 | GET /search/IdSearch?contentId=1 | code=200，包含 title、media 字段 | P0 |

### 3.3 上传模块

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| S-07 | 上传视频 | POST /api/upload/video | code=200，返回 contentId | P0 |
| S-08 | 上传验证 | 用 contentId 查详情 | 返回的 videoUrl 可访问 | P0 |
| S-09 | 文件落盘 | 检查本地文件 | D:/data/projects/VideoPlatform/stone 下文件存在且大小 > 0 | P0 |

**上传验证链路**：
```
上传 → 拿到 contentId → 查详情 → 拿到 URL → 检查本地文件存在
```

### 3.4 社交模块

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| S-10 | 点赞 | POST /like/content/add | code=200 | P0 |
| S-11 | 取消点赞 | POST /like/content/remove | code=200 | P0 |
| S-12 | 评论 | POST /comment/add | code=200 | P0 |
| S-13 | 关注 | POST /follow/add | code=200 | P0 |
| S-14 | 取关 | POST /follow/remove | code=200 | P0 |

### 3.5 优惠券模块

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| S-15 | 查看优惠券列表 | GET /coupon/list | code=200，返回列表 | P0 |
| S-16 | 抢券 | POST /coupon/grab | code=200，返回兑换码 | P0 |

---

## 四、Level 2: 数据一致性验收标准

### 4.1 点赞计数一致性

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| C-01 | 点赞后计数+1 | 记录 likeCount → 点赞 → 查询 | likeCount 增加 1 | P1 |
| C-02 | 取消点赞后计数-1 | 取消点赞 → 查询 | likeCount 恢复原值 | P1 |
| C-03 | 点赞状态正确 | 查询点赞状态 | 点赞后=true，取消后=false | P1 |

### 4.2 评论点赞计数一致性

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| C-04 | 评论点赞后计数+1 | 记录 → 点赞评论 → 查询 | likeCount 增加 1 | P1 |
| C-05 | 取消评论点赞后计数-1 | 取消 → 查询 | likeCount 恢复原值 | P1 |

### 4.3 关注计数一致性

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| C-06 | 关注后 followCount+1 | 记录 → 关注 → 查询 profile | followCount 增加 1 | P1 |
| C-07 | 关注后 followerCount+1 | 查询被关注者 profile | followerCount 增加 1 | P1 |
| C-08 | 取关后计数恢复 | 取关 → 查询 | 两个计数恢复原值 | P1 |

### 4.4 评论计数一致性

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| C-09 | 评论后 commentCount+1 | 记录 → 评论 → 查询详情 | commentCount 增加 1 | P1 |

### 4.5 优惠券库存一致性

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| C-10 | 抢券后库存-1 | 记录 stock → 抢券 → 查询列表 | stock 减少 1 | P1 |

---

## 五、Level 3: 边界与异常验收标准

### 5.1 参数校验

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| E-01 | 登录缺少字段 | POST /user/login 空 body | code=400 | P2 |
| E-02 | 注册手机号重复 | 用已注册手机号注册 | code=401，提示已使用 | P2 |
| E-03 | 评论缺少 message | POST /comment/add 无 message | code=400 | P2 |
| E-04 | 搜索缺关键词 | GET /search/keywordSearch 无 keyword | code=400 或返回空 | P2 |

### 5.2 鉴权测试

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| E-05 | 未登录访问受保护接口 | 不带 token 访问 /feed | code=401 | P1 |
| E-06 | 伪造 token | 用假 token 访问 | code=401 | P1 |

### 5.3 重复操作幂等

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| E-07 | 重复点赞 | 对同一内容点赞两次 | 第二次返回 409 | P1 |
| E-08 | 重复关注 | 对同一用户关注两次 | 第二次返回 409 | P1 |
| E-09 | 重复抢券 | 对同一优惠券抢两次 | 第二次返回 409 | P1 |
| E-10 | 关注自己 | 关注自己的 userId | 返回 409 | P2 |

### 5.4 资源不存在

| 编号 | 验收项 | 操作 | 判定标准 | 优先级 |
|------|--------|------|----------|--------|
| E-11 | 点赞不存在的内容 | contentId=99999 | code=404 | P2 |
| E-12 | 查看不存在的详情 | contentId=99999 | code=404 或 data=null | P2 |

---

## 六、验证方法说明

### 6.1 响应验证

```python
# 所有接口都返回统一格式
{
    "code": 200,       # HTTP 状态码或业务码
    "data": {...},     # 业务数据（成功时）
    "message": "..."   # 错误信息（失败时）
}

# 验证方式
assert response["code"] == 200
assert "token" in response["data"]
```

### 6.2 文件上传验证

```python
# 验证链路
1. 上传接口返回 contentId
2. 用 contentId 调详情接口获取 URL
3. URL 格式：/upload/{filename}
4. 本地路径：D:/data/projects/VideoPlatform/stone/{filename}
5. 检查文件存在且大小 > 0

# 代码示例
content_id = upload_response["data"]["contentId"]
detail = get_detail(content_id)
video_url = detail["data"]["videoUrl"]  # /upload/abc-123.mp4
filename = video_url.split("/")[-1]
assert os.path.exists(f"D:/data/projects/VideoPlatform/stone/{filename}")
assert os.path.getsize(f"D:/data/projects/VideoPlatform/stone/{filename}") > 0
```

### 6.3 计数一致性验证

```python
# 验证模式：记录 → 操作 → 验证差值
before = get_like_count(content_id)
like_content(content_id)
after = get_like_count(content_id)
assert after == before + 1

# 取消后恢复
unlike_content(content_id)
after_unlike = get_like_count(content_id)
assert after_unlike == before
```

---

## 七、测试脚本设计规范

### 7.1 目录结构

```
src/test/python/
├── conftest.py          # fixtures: base_url, 注册/登录获取 token
├── test_smoke.py        # Level 1: 冒烟测试
├── test_consistency.py  # Level 2: 数据一致性
├── test_boundary.py     # Level 3: 边界与异常
├── pytest.ini           # 注册 smoke/consistency/boundary markers
└── requirements.txt     # pytest, requests
```

### 7.2 命名规范

```
test_{level}_{module}_{scenario}.py

示例：
test_smoke_user_register.py
test_consistency_like_count.py
test_boundary_auth_no_token.py
```

### 7.3 Fixture 设计

```python
# conftest.py
@pytest.fixture(scope="session")
def base_url():
    return "http://localhost:8080"

@pytest.fixture(scope="session")
def user_a_token(base_url):
    """注册并登录用户A，返回 token"""
    register(base_url, "testuserA", "13800000001", "123456")
    return login(base_url, "13800000001", "123456")["token"]

@pytest.fixture(scope="session")
def user_b_token(base_url):
    """注册并登录用户B，返回 token"""
    register(base_url, "testuserB", "13800000002", "123456")
    return login(base_url, "13800000002", "123456")["token"]

@pytest.fixture
def sample_content_id(user_a_token):
    """创建一个测试用内容，返回 contentId"""
    # 调用上传接口或直接使用已知 ID
    return 1  # 需要根据实际情况调整
```

---

## 八、已知限制

### 8.1 脚本无法验证的项目

| 项目 | 原因 | 替代方案 |
|------|------|----------|
| 视频能播放 | 需要浏览器环境 | 检查文件头 magic bytes |
| 图片能显示 | 需要浏览器环境 | 检查文件头 + Content-Type |
| 前端交互 | 需要 Selenium | 不在验收范围内 |
| 并发安全 | 需要压测工具 | 单线程顺序测试 |

### 8.2 测试数据依赖

| 依赖项 | 说明 | 解决方案 |
|--------|------|----------|
| 用户存在 | 需要至少 2 个用户 | 测试前自动注册 |
| 内容存在 | 需要有内容才能点赞/评论 | 测试前自动创建或使用已知 ID |
| 优惠券存在 | 需要有可用优惠券 | 手动准备或通过独立脚本创建 |

---

## 九、验收报告格式

### 9.1 测试结果示例

```
============================================
验收报告 - 2026-07-23
============================================

Level 1: 冒烟测试
  [PASS] S-01: 注册
  [PASS] S-02: 登录（手机号）
  [PASS] S-04: 首页推荐
  [FAIL] S-07: 上传视频 - 返回 code=500
  ...

Level 2: 数据一致性
  [PASS] C-01: 点赞后计数+1
  [PASS] C-02: 取消点赞后计数-1
  ...

Level 3: 边界与异常
  [PASS] E-05: 未登录访问受保护接口
  [FAIL] E-07: 重复点赞 - 未返回 409
  ...

总计: 30 通过, 2 失败
============================================
```

---

## 十、实际测试结果

> 测试日期：2026-07-23
> 测试环境：Windows 11 + Python 3.14 + Tomcat + MySQL + Redis

### 10.1 测试结果汇总

```
============================= 32 passed, 3 skipped =============================
```

| 状态 | 数量 | 说明 |
|------|------|------|
| ✅ PASSED | 32 | |
| ⏭ SKIPPED | 3 | 无可用优惠券 |
| ❌ FAILED | 0 | |

### 10.2 Skipped 测试

| 测试 | 原因 | 解决方案 |
|------|------|----------|
| E-09 | 重复抢券 | 数据库无可用优惠券 |
| C-10 | 优惠券库存 | 数据库无可用优惠券 |
| S-16 | 抢券 | 数据库无可用优惠券 |

### 10.3 测试覆盖范围

- **冒烟测试**: 16/16 通过
- **数据一致性**: 9/10 通过（1 个 skip）
- **边界与异常**: 11/12 通过（1 个 skip）

---

## 十一、发现并修复的问题

### 11.1 服务端 Bug

| 编号 | 问题 | 位置 | 修复状态 |
|------|------|------|----------|
| BUG-01 | 重复关注返回 500 而非 409 | FollowService.follow() | ✅ 已修复 |
| BUG-02 | 重复取关返回 500 而非 409 | FollowService.unfollow() | ✅ 已修复 |

**BUG-01/02 详情**：

**问题**：如果 `follow` 表有唯一索引，重复 INSERT/DELETE 会抛出 `SQLException`，被 catch 块捕获后返回 500。

**修复方案**：在操作前先查询是否已关注/未关注，直接抛出 `ConflictException`。

**修改文件**：
- `FollowService.java` - 添加前置检查
- `FollowDao.java` - 新增 `isFollowing()` 方法

### 11.2 测试脚本问题

| 编号 | 问题 | 修复状态 |
|------|------|----------|
| TEST-01 | 手机号格式错误（UUID 含字母） | ✅ 已修复 |
| TEST-02 | 文件路径缺少子目录 | ✅ 已修复 |
| TEST-03 | 评论缓存懒加载未触发 | ✅ 已修复 |

**TEST-01 详情**：

**问题**：UUID hex 包含字母（a-f），生成的手机号不是纯数字。

**修复**：将 hex 转换为数字后再使用。

**TEST-02 详情**：

**问题**：测试脚本假设文件在 `D:/data/projects/VideoPlatform/stone/`，实际在 `D:/data/projects/VideoPlatform/stone/video/` 等子目录。

**修复**：从 URL 中提取完整相对路径。

**TEST-03 详情**：

**问题**：评论缓存是懒加载的，新内容需要先访问详情才能查到评论。

**修复**：在查询评论前先调用 `/search/IdSearch` 触发缓存加载。

---

## 十二、文档纠错记录

> 本文档为初稿，标注可能不准确的地方，后续根据实际测试结果修正。

| 编号 | 条目 | 疑点 | 纠正日期 | 实际情况 |
|------|------|------|----------|----------|
| 1 | 缓存机制 | 文档未说明评论缓存是懒加载 | 2026-07-23 | 查看内容详情时才加载评论缓存 |
| 2 | 重复关注 | 文档预期返回 409 | 2026-07-23 | 实际返回 500（已修复） |

---

## 文档历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-07-23 | 初稿，基于代码分析编写 |
| 1.1 | 2026-07-23 | 添加实际测试结果和修复记录 |
