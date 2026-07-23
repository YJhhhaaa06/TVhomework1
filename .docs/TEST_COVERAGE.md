# 测试覆盖分析报告

> 生成日期：2026-07-23
> 测试类型：HTTP 集成测试（.http 文件）

---

## 一、现有测试用例统计

### 1.1 测试文件概览

| 测试文件 | 用例数 | 覆盖模块 |
|----------|--------|----------|
| loginTest.http | 22 | 登录、注册 |
| likeTest.http | 24 | 内容点赞、评论点赞 |
| commentTest.http | 18 | 发表评论、查看评论 |
| followTest.http | 11 | 关注、取关 |
| couponTest.http | 9 | 优惠券抢购 |
| feedTest.http | ~10 | 关注动态流 |
| searchTest.http | ~15 | 搜索 |
| startTest.http | ~8 | 首页推荐 |
| changePasswordTest.http | ~12 | 修改密码 |
| uploadTest.http | ~20 | 上传视频/动态 |
| **dataConsistencyTest.http** | **23** | **数据一致性验证（新增）** |
| **userProfileTest.http** | **23** | **用户资料修改（新增）** |
| **followListTest.http** | **19** | **关注/粉丝列表（新增）** |
| **feedDetailTest.http** | **20** | **Feed流/内容详情（新增）** |
| **searchCompleteTest.http** | **15** | **搜索完整测试（新增）** |
| **startCompleteTest.http** | **14** | **首页推荐完整测试（新增）** |
| **合计** | **~265** | - |

### 1.2 测试覆盖维度

| 维度 | 覆盖情况 | 说明 |
|------|----------|------|
| ✅ 正常流程 | 良好 | 每个接口都有正常调用用例 |
| ✅ 参数校验 | 良好 | 缺失字段、空值、格式错误 |
| ✅ 鉴权测试 | 良好 | 无 token、伪造 token |
| ✅ 重复操作 | 良好 | 重复点赞、重复关注、重复抢券 |
| ✅ 资源不存在 | 良好 | 不存在的内容、评论、用户 |
| ⚠️ 边界条件 | 部分 | 部分边界未覆盖 |
| ❌ 并发测试 | 无 | 高并发场景未测试 |
| ❌ 性能测试 | 无 | 响应时间、吞吐量未测试 |

---

## 二、按模块覆盖分析

### 2.1 用户认证模块

| 测试项 | 用例数 | 覆盖状态 |
|--------|--------|----------|
| 正常登录（ID） | 1 | ✅ |
| 正常登录（手机号） | 1 | ✅ |
| 错误密码 | 1 | ✅ |
| 用户不存在 | 2 | ✅ |
| 缺少字段 | 3 | ✅ |
| 空请求体 | 1 | ✅ |
| 格式错误 | 2 | ✅ |
| 正常注册 | 1 | ✅ |
| 注册参数校验 | 5 | ✅ |
| 手机号重复 | 1 | ✅ |
| 修改密码 | ~12 | ✅ |
| 修改用户名 | - | ❌ 缺失 |
| 修改手机号 | - | ❌ 缺失 |

**覆盖率**: 85%

**缺失用例**:
- 修改用户名正常流程
- 修改用户名边界（长度=50）
- 修改手机号正常流程
- 修改手机号（新旧相同）
- 修改手机号（新手机号已被使用）

---

### 2.2 内容点赞模块

| 测试项 | 用例数 | 覆盖状态 |
|--------|--------|----------|
| 正常点赞 | 1 | ✅ |
| 正常取消点赞 | 1 | ✅ |
| 重复点赞 | 1 | ✅ |
| 取消未点赞 | 1 | ✅ |
| 点赞不存在内容 | 1 | ✅ |
| 缺少参数 | 1 | ✅ |
| 参数格式错误 | 1 | ✅ |
| 无 token | 1 | ✅ |
| 伪造 token | 1 | ✅ |
| 查询点赞状态 | 2 | ✅ |
| 查询点赞数 | 1 | ✅ |

**覆盖率**: 95%

**缺失用例**:
- 点赞后点赞数是否正确增加
- 取消点赞后点赞数是否正确减少

---

### 2.3 评论点赞模块

| 测试项 | 用例数 | 覆盖状态 |
|--------|--------|----------|
| 正常点赞 | 1 | ✅ |
| 正常取消点赞 | 1 | ✅ |
| 重复点赞 | 1 | ✅ |
| 取消未点赞 | 1 | ✅ |
| 点赞不存在评论 | 1 | ✅ |
| 缺少参数 | 1 | ✅ |
| 参数格式错误 | 1 | ✅ |

**覆盖率**: 95%

---

### 2.4 评论模块

| 测试项 | 用例数 | 覆盖状态 |
|--------|--------|----------|
| 正常发评论 | 1 | ✅ |
| 正常回复评论 | 1 | ✅ |
| 缺少 token | 1 | ✅ |
| 伪造 token | 1 | ✅ |
| 缺少 contentId | 1 | ✅ |
| 缺少 message | 1 | ✅ |
| message 为空 | 1 | ✅ |
| 内容不存在 | 1 | ✅ |
| 父评论不存在 | 1 | ✅ |
| 父评论归属错误 | 1 | ✅ |
| 登录用户查看评论 | 1 | ✅ |
| 未登录查看评论 | 1 | ✅ |
| 查看不存在内容评论 | 1 | ✅ |

**覆盖率**: 90%

**缺失用例**:
- 评论后评论数是否正确增加
- 评论树结构是否正确（嵌套回复）

---

### 2.5 关注模块

| 测试项 | 用例数 | 覆盖状态 |
|--------|--------|----------|
| 正常关注 | 1 | ✅ |
| 正常取关 | 1 | ✅ |
| 重复关注 | 1 | ✅ |
| 取关未关注 | 1 | ✅ |
| 关注自己 | 1 | ✅ |
| 取关自己 | 1 | ✅ |
| 缺少参数 | 1 | ✅ |
| 参数格式错误 | 1 | ✅ |
| 无 token | 1 | ✅ |
| 伪造 token | 1 | ✅ |

**覆盖率**: 85%

**缺失用例**:
- 关注后关注数是否正确增加
- 关注后粉丝数是否正确增加
- 取关后计数是否正确减少
- 查看关注列表
- 查看粉丝列表

---

### 2.6 优惠券模块

| 测试项 | 用例数 | 覆盖状态 |
|--------|--------|----------|
| 查询可用优惠券 | 1 | ✅ |
| 正常抢购 | 1 | ✅ |
| 重复抢购 | 1 | ✅ |
| 查询我的优惠券 | 1 | ✅ |
| 缺少参数 | 1 | ✅ |
| 优惠券不存在 | 1 | ✅ |
| 无 token | 1 | ✅ |
| 伪造 token | 1 | ✅ |

**覆盖率**: 85%

**缺失用例**:
- 库存不足场景
- 活动未开始/已结束场景
- 抢购后库存是否正确减少

---

### 2.7 Feed 流模块

| 测试项 | 用例数 | 覆盖状态 |
|--------|--------|----------|
| 正常获取动态 | 1 | ✅ |
| 分页参数 | ~3 | ✅ |
| 无关注用户 | 1 | ✅ |

**覆盖率**: 70%

**缺失用例**:
- 关注用户后动态是否出现
- 取消关注后动态是否消失
- 新发布内容是否出现在动态中

---

## 三、测试缺口分析

### 3.1 高优先级缺口（必须补充）

| 缺口 | 风险 | 建议 |
|------|------|------|
| 数据一致性验证 | 高 | 点赞/关注/评论后计数是否正确 |
| 资源所有权校验 | 高 | 用户 A 不能删除用户 B 的内容 |
| 事务回滚验证 | 高 | 操作失败后数据是否回滚 |

### 3.2 中优先级缺口（建议补充）

| 缺口 | 风险 | 建议 |
|------|------|------|
| 修改用户名流程 | 中 | 补充正常和异常用例 |
| 修改手机号流程 | 中 | 补充正常和异常用例 |
| 关注/粉丝列表 | 中 | 补充查询用例 |
| 评论树结构 | 中 | 验证嵌套回复正确性 |

### 3.3 低优先级缺口（可选）

| 缺口 | 风险 | 建议 |
|------|------|------|
| 并发测试 | 低 | 高并发点赞/抢券 |
| 性能测试 | 低 | 响应时间、吞吐量 |
| 边界值测试 | 低 | 极大/极小值 |

---

## 四、是否需要单元测试？

### 4.1 当前测试类型分析

| 测试类型 | 当前状态 | 说明 |
|----------|----------|------|
| HTTP 集成测试 | ✅ 有 | .http 文件，测试 API 接口 |
| 单元测试 | ❌ 无 | 测试单个类/方法的逻辑 |
| 集成测试 | ❌ 无 | 测试多个组件协作 |

### 4.2 HTTP 集成测试的局限性

```
HTTP 集成测试只能验证：
✅ API 输入输出
✅ HTTP 状态码
✅ 响应格式

HTTP 集成测试无法验证：
❌ Service 内部逻辑
❌ 缓存一致性
❌ 事务是否正确提交/回滚
❌ 边界条件的详细处理
❌ 异常处理路径
```

### 4.3 单元测试的价值

| 价值 | 说明 |
|------|------|
| 快速反馈 | 不需要启动服务器，毫秒级执行 |
| 精确定位 | 测试失败时精确到具体方法 |
| 回归保障 | 重构时快速验证逻辑不变 |
| 文档作用 | 测试用例即文档 |

### 4.4 建议的测试策略

```
                    ┌─────────────────────────────────────┐
                    │           测试金字塔                  │
                    ├─────────────────────────────────────┤
                    │                                     │
                    │          ╱╲                         │
                    │         ╱  ╲      E2E 测试          │
                    │        ╱    ╲     (少量)            │
                    │       ╱──────╲                      │
                    │      ╱        ╲   集成测试          │
                    │     ╱          ╲  (中量)            │
                    │    ╱────────────╲                   │
                    │   ╱              ╲  单元测试        │
                    │  ╱                ╲ (大量)          │
                    │ ╱──────────────────╲                │
                    │                                     │
                    └─────────────────────────────────────┘
```

---

## 五、单元测试补充建议

### 5.1 优先补充的单元测试

#### 5.1.1 Service 层核心逻辑

| 类 | 测试方法 | 优先级 |
|----|----------|--------|
| `UserService` | login - 用户不存在 | P0 |
| `UserService` | login - 密码错误 | P0 |
| `UserService` | register - 手机号重复 | P0 |
| `UserService` | register - 用户名重复 | P0 |
| `LikeService` | likeContent - 重复点赞 | P0 |
| `LikeService` | likeContent - 内容不存在 | P0 |
| `FollowService` | follow - 关注自己 | P0 |
| `FollowService` | follow - 重复关注 | P0 |
| `CommentService` | addComment - 内容不存在 | P0 |
| `CommentService` | addComment - 父评论归属错误 | P0 |
| `CouponService` | grabCoupon - 库存不足 | P0 |
| `CouponService` | grabCoupon - 重复抢购 | P0 |

#### 5.1.2 缓存逻辑

| 类 | 测试方法 | 优先级 |
|----|----------|--------|
| `ContentService` | getContentFromCache - 缓存命中 | P1 |
| `ContentService` | getContentFromCache - 缓存 miss 回填 | P1 |
| `ContentService` | getContentFromCache - 缓存过期 | P1 |
| `LikeCacheService` | isContentLiked - 缓存命中 | P1 |
| `LikeCacheService` | batchIsContentLiked - Pipeline 批量查询 | P1 |

#### 5.1.3 工具类

| 类 | 测试方法 | 优先级 |
|----|----------|--------|
| `PasswordUtil` | hashPassword - 生成哈希 | P1 |
| `PasswordUtil` | isPasswordCorrect - 验证正确密码 | P1 |
| `PasswordUtil` | isPasswordCorrect - 验证错误密码 | P1 |
| `JwtUtil` | generateToken - 生成 Token | P1 |
| `JwtUtil` | validateToken - 验证有效 Token | P1 |
| `JwtUtil` | validateToken - 验证过期 Token | P1 |
| `StringUtil` | phoneCheck - 各种手机号格式 | P2 |

### 5.2 单元测试示例

#### UserService 单元测试

```java
class UserServiceTest {
    
    @Mock
    private UserDao userDao;
    
    @Mock
    private PasswordUtil passwordUtil;
    
    @InjectMocks
    private UserService userService;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    // ===== 登录测试 =====
    
    @Test
    void login_byPhone_success() {
        // Arrange
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUserName("张三");
        mockUser.setHashedPassword("hashed123");
        
        when(userDao.getUserForLoginByPhone("13800138000"))
            .thenReturn(mockUser);
        when(passwordUtil.isPasswordCorrect("123", "hashed123"))
            .thenReturn(true);
        
        LoginCommand command = new LoginCommand();
        command.setPhone("13800138000");
        command.setPassword("123");
        
        // Act
        LoginVO result = userService.login(command);
        
        // Assert
        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("张三", result.getUsername());
        assertNotNull(result.getToken());
    }
    
    @Test
    void login_byPhone_userNotFound() {
        // Arrange
        when(userDao.getUserForLoginByPhone("13800138000"))
            .thenReturn(null);
        
        LoginCommand command = new LoginCommand();
        command.setPhone("13800138000");
        command.setPassword("123");
        
        // Act & Assert
        assertThrows(NotFoundException.class, () -> {
            userService.login(command);
        });
    }
    
    @Test
    void login_byPhone_wrongPassword() {
        // Arrange
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setHashedPassword("hashed123");
        
        when(userDao.getUserForLoginByPhone("13800138000"))
            .thenReturn(mockUser);
        when(passwordUtil.isPasswordCorrect("wrong", "hashed123"))
            .thenReturn(false);
        
        LoginCommand command = new LoginCommand();
        command.setPhone("13800138000");
        command.setPassword("wrong");
        
        // Act & Assert
        assertThrows(AuthException.class, () -> {
            userService.login(command);
        });
    }
    
    // ===== 注册测试 =====
    
    @Test
    void register_phoneAlreadyUsed() {
        // Arrange
        when(userDao.isPhoneUsed(any(), eq("13800138000")))
            .thenReturn(true);
        
        RegisterCommand command = new RegisterCommand();
        command.setUsername("张三");
        command.setPhone("13800138000");
        command.setPassword("123");
        
        // Act & Assert
        assertThrows(AuthException.class, () -> {
            userService.registerAsUser(command);
        });
    }
}
```

#### LikeService 单元测试

```java
class LikeServiceTest {
    
    @Mock
    private ContentDao contentDao;
    
    @Mock
    private ContentLikeDao contentLikeDao;
    
    @Mock
    private LikeCacheService cache;
    
    @InjectMocks
    private LikeService likeService;
    
    @Test
    void likeContent_success() {
        // Arrange
        when(contentDao.isContentExist(any(), eq(1L)))
            .thenReturn(true);
        when(contentLikeDao.isLiked(any(), eq(1L), eq(1L)))
            .thenReturn(false);
        
        // Act
        likeService.likeContent(1L, 1L);
        
        // Assert
        verify(contentLikeDao).addLike(any(), eq(1L), eq(1L));
        verify(contentDao).updateLikeCount(any(), eq(1L), eq(1));
        verify(cache).likeContent(1L, 1L);
    }
    
    @Test
    void likeContent_alreadyLiked() {
        // Arrange
        when(contentDao.isContentExist(any(), eq(1L)))
            .thenReturn(true);
        when(contentLikeDao.isLiked(any(), eq(1L), eq(1L)))
            .thenReturn(true);
        
        // Act & Assert
        assertThrows(ConflictException.class, () -> {
            likeService.likeContent(1L, 1L);
        });
    }
    
    @Test
    void likeContent_contentNotFound() {
        // Arrange
        when(contentDao.isContentExist(any(), eq(999L)))
            .thenReturn(false);
        
        // Act & Assert
        assertThrows(NotFoundException.class, () -> {
            likeService.likeContent(1L, 999L);
        });
    }
}
```

---

## 六、测试基础设施

### 6.1 需要添加的依赖

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>5.5.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-junit-jupiter</artifactId>
    <version>5.5.0</version>
    <scope>test</scope>
</dependency>
```

### 6.2 测试目录结构

```
src/test/java/com/itheima/
├── service/
│   ├── UserServiceTest.java
│   ├── LikeServiceTest.java
│   ├── FollowServiceTest.java
│   ├── CommentServiceTest.java
│   ├── CouponServiceTest.java
│   └── ContentServiceTest.java
├── util/
│   ├── PasswordUtilTest.java
│   ├── JwtUtilTest.java
│   └── StringUtilTest.java
└── ioc/
    └── IocContainerTest.java
```

---

## 七、测试执行建议

### 7.1 开发阶段

```
1. 编写代码前：先写单元测试（TDD）
2. 编写代码中：运行单元测试验证
3. 编写代码后：运行 HTTP 测试验证接口
```

### 7.2 重构阶段

```
1. 重构前：确保所有测试通过
2. 重构中：频繁运行单元测试
3. 重构后：运行全部测试（单元 + HTTP）
```

### 7.3 CI/CD 集成

```bash
# 运行单元测试
mvn test

# 运行 HTTP 测试（需要启动服务器）
# 使用 IntelliJ HTTP Client 或 Newman
```

---

## 八、新增测试用例说明

### 8.1 dataConsistencyTest.http（数据一致性验证）

| 测试项 | 用例数 | 说明 |
|--------|--------|------|
| 内容点赞计数一致性 | 7 | 验证点赞/取消点赞后 like_count 正确变化 |
| 评论点赞计数一致性 | 5 | 验证评论点赞/取消后计数正确变化 |
| 关注计数一致性 | 8 | 验证关注/取关后 follow_count 和 follower_count 正确变化 |
| 评论计数一致性 | 3 | 验证评论后 comment_count 正确增加 |

### 8.2 userProfileTest.http（用户资料修改）

| 测试项 | 用例数 | 说明 |
|--------|--------|------|
| 修改用户名正常流程 | 2 | 正常修改、边界值（长度=50） |
| 修改用户名异常 | 5 | 超长、空值、缺参数、未登录、伪造token |
| 修改手机号正常流程 | 1 | 正常修改 |
| 修改手机号异常 | 8 | 旧号不匹配、格式错误、相同号码、已被使用、缺参数、未登录 |
| 修改密码异常 | 7 | 旧密码错误、手机号不匹配、缺参数、未登录 |

### 8.3 followListTest.http（关注/粉丝列表）

| 测试项 | 用例数 | 说明 |
|--------|--------|------|
| 关注列表查询 | 8 | 自己/他人列表、未登录、缺参数、结构验证、isSelf字段 |
| 粉丝列表查询 | 6 | 自己/他人列表、未登录、缺参数、结构验证 |
| 关注/取关后列表变化 | 5 | 验证关注后出现、取关后消失 |

### 8.4 feedDetailTest.http（Feed流/内容详情）

| 测试项 | 用例数 | 说明 |
|--------|--------|------|
| Feed流正常获取 | 3 | 登录/未登录、分页 |
| Feed流结构验证 | 3 | 列表项结构、点赞状态、关注状态 |
| 关注后Feed变化 | 4 | 关注后出现、取关后消失 |
| 内容详情查询 | 9 | 正常/未登录/不存在、缺参数、类型验证、评论列表 |

### 8.5 searchCompleteTest.http（搜索完整测试）

| 测试项 | 用例数 | 说明 |
|--------|--------|------|
| 正常搜索 | 3 | 登录/未登录、中文关键词 |
| 分页测试 | 3 | 第一页、第二页、超出范围 |
| 结构验证 | 3 | 列表项结构、点赞状态、关注状态 |
| 边界条件 | 6 | 空关键词、缺参数、特殊字符、超长关键词 |

### 8.6 startCompleteTest.http（首页推荐完整测试）

| 测试项 | 用例数 | 说明 |
|--------|--------|------|
| 正常获取 | 2 | 登录/未登录 |
| 数量限制 | 5 | limit=1/5/20、缺参数、0、负数 |
| 结构验证 | 4 | 列表项结构、点赞状态、关注状态、类型验证 |
| 内容类型 | 3 | type字段值、视频封面 |

---

## 九、总结

### 9.1 现有测试评估

| 维度 | 评分 | 说明 |
|------|------|------|
| 接口覆盖 | ⭐⭐⭐⭐⭐ | 主要接口都有测试 |
| 异常覆盖 | ⭐⭐⭐⭐⭐ | 参数校验、鉴权覆盖良好 |
| 数据验证 | ⭐⭐⭐⭐ | 新增数据一致性验证 |
| 并发测试 | ⭐ | 无（可选） |
| 单元测试 | ⭐ | 无（建议重构时补充） |

### 8.2 建议

| 建议 | 优先级 | 工作量 |
|------|--------|--------|
| 补充数据一致性验证的 HTTP 测试 | P0 | 2小时 |
| 补充 Service 层单元测试 | P1 | 1-2天 |
| 补充工具类单元测试 | P2 | 2小时 |
| 补充并发测试 | P3 | 可选 |

### 8.3 结论

**现有 HTTP 测试基本足够保障日常开发和重构**，但建议补充：

1. **数据一致性验证**（P0）：验证点赞/关注/评论后计数是否正确
2. **核心 Service 单元测试**（P1）：提高重构信心，快速定位问题
3. **资源所有权校验**（P0）：当前缺失的安全测试

---

## 文档历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-07-23 | 初始版本 |
