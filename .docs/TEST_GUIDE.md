# 测试运行指南

> 版本：1.0
> 更新日期：2026-07-23
> 用途：指导如何运行自动化验收测试

---

## 一、环境要求

### 1.1 基础环境

| 依赖 | 版本要求 | 说明 |
|------|----------|------|
| Python | 3.8+ | 用于运行 pytest |
| Tomcat | 9.0+ | 应用服务器 |
| MySQL | 8.0+ | 数据库 |
| Redis | 6.0+ | 缓存 |

### 1.2 Python 依赖

```bash
pip install pytest requests
```

或使用 requirements.txt：

```bash
cd src/test/python
pip install -r requirements.txt
```

---

## 二、测试前准备

### 2.1 启动服务

1. **启动 MySQL** - 确保 `TVDatabase` 数据库可访问
2. **启动 Redis** - 端口 6379
3. **启动 Tomcat** - 在 IDEA 中运行，确保应用部署到 `/MyAPP`

### 2.2 验证服务可用

```bash
# 测试首页推荐接口
curl http://localhost:8080/MyAPP/start

# 预期返回：
# {"msg":"success","code":200,"data":[...]}
```

---

## 三、运行测试

### 3.1 运行全部测试

```bash
cd D:\javaproject\TVtest\202604\untitled\src\test\python
pytest -v
```

### 3.2 按级别运行

```bash
# 冒烟测试
pytest -v -m smoke

# 数据一致性测试
pytest -v -m consistency

# 边界与异常测试
pytest -v -m boundary
```

### 3.3 运行单个测试文件

```bash
pytest test_smoke.py -v
pytest test_consistency.py -v
pytest test_boundary.py -v
```

### 3.4 运行单个测试

```bash
pytest test_smoke.py::TestUserModule::test_S01_register_returns_token -v
```

---

## 四、测试结果解读

### 4.1 结果状态

| 状态 | 含义 |
|------|------|
| PASSED | 测试通过 |
| FAILED | 测试失败，需要排查 |
| SKIPPED | 测试跳过（通常是缺少测试数据） |
| ERROR | 测试执行出错（通常是 fixture 失败） |

### 4.2 当前已知的 SKIPPED 测试

| 测试 | 原因 | 解决方案 |
|------|------|----------|
| E-09 | 无可用优惠券 | 手动创建优惠券 |
| C-10 | 无可用优惠券 | 手动创建优惠券 |
| S-16 | 无可用优惠券 | 手动创建优惠券 |

### 4.3 创建测试优惠券

如果需要测试优惠券功能，需要通过 Java 的 main 方法创建优惠券：

```java
// 参考项目中的 CouponAdmin.java
```

---

## 五、测试覆盖范围

### 5.1 Level 1: 冒烟测试 (test_smoke.py)

| 编号 | 测试项 | 说明 |
|------|--------|------|
| S-01 | 注册 | 返回 token |
| S-02 | 登录（手机号） | 返回 token |
| S-03 | 登录（用户ID） | 返回 token |
| S-04 | 首页推荐 | 返回内容列表 |
| S-05 | 搜索 | 返回搜索结果 |
| S-06 | 内容详情 | 返回详情字段 |
| S-07 | 上传视频 | 返回 contentId |
| S-08 | 上传验证 | 详情包含 URL |
| S-09 | 文件落盘 | D:/stone 下文件存在 |
| S-10/S-11 | 点赞/取消点赞 | 计数变化正确 |
| S-12 | 评论 | 评论成功 |
| S-13/S-14 | 关注/取关 | 计数变化正确 |
| S-15 | 优惠券列表 | 返回列表 |
| S-16 | 抢券 | 返回兑换码 |

### 5.2 Level 2: 数据一致性 (test_consistency.py)

| 编号 | 测试项 | 说明 |
|------|--------|------|
| C-01 | 点赞后计数+1 | likeCount 增加 |
| C-02 | 取消点赞后计数-1 | likeCount 恢复 |
| C-03 | 点赞状态一致 | status 字段正确 |
| C-04 | 评论点赞+1 | likeCount 增加 |
| C-05 | 取消评论点赞-1 | likeCount 恢复 |
| C-06/C-07 | 关注计数 | followCount + followerCount |
| C-08 | 取关计数恢复 | 两个计数恢复 |
| C-09 | 评论计数+1 | commentCount 增加 |
| C-10 | 优惠券库存-1 | stock 减少 |

### 5.3 Level 3: 边界与异常 (test_boundary.py)

| 编号 | 测试项 | 说明 |
|------|--------|------|
| E-01 | 登录缺字段 | 返回 400 |
| E-02 | 注册手机号重复 | 返回 401 |
| E-03 | 评论缺 message | 返回 400 |
| E-04 | 搜索缺关键词 | 返回 400 |
| E-05 | 未登录访问 | 返回 401 |
| E-06 | 伪造 token | 返回 401 |
| E-07 | 重复点赞 | 返回 409 |
| E-08 | 重复关注 | 返回 409 |
| E-09 | 重复抢券 | 返回 409 |
| E-10 | 关注自己 | 返回 409 |
| E-11 | 点赞不存在内容 | 返回 404 |
| E-12 | 查看不存在详情 | 返回 404 |

---

## 六、测试数据说明

### 6.1 自动创建的数据

测试脚本会自动创建：

- 2 个测试用户（userA, userB）
- 1 个测试视频
- 1 个测试动态

### 6.2 测试数据清理

测试数据会残留在数据库中，建议定期清理：

```sql
-- 清理测试用户
DELETE FROM users WHERE username LIKE 'testA_%' OR username LIKE 'testB_%';

-- 清理测试内容
DELETE FROM content WHERE title = 'pytest_test_video' OR title = 'pytest_test_post';
```

### 6.3 文件存储

测试上传的文件存储在 `D:/stone/` 目录下：

- 视频：`D:/stone/video/`
- 图片：`D:/stone/image/`
- 封面：`D:/stone/cover/`

---

## 七、常见问题

### 7.1 服务器未启动

```
ERROR at setup of ...
Cannot create userA: register={'msg': '...', 'code': 400}
```

**解决方案**：确保 Tomcat 已启动，应用已部署。

### 7.2 手机号格式错误

```
{'msg': '电话号码格式不正确', 'code': 400}
```

**原因**：手机号不是 11 位纯数字且以 1 开头。

**解决方案**：检查 conftest.py 中的手机号生成逻辑。

### 7.3 评论列表为空

```
AssertionError: No comments found for this content
```

**原因**：评论缓存是懒加载的，需要先访问内容详情。

**解决方案**：已在测试脚本中添加缓存触发逻辑。

### 7.4 文件不存在

```
AssertionError: File not found on disk: D:/stone/xxx.mp4
```

**原因**：文件存储在子目录中（video/, image/, cover/）。

**解决方案**：已在测试脚本中修正路径拼接逻辑。

---

## 八、持续集成建议

### 8.1 CI 流程

```yaml
# 示例 GitHub Actions 配置
steps:
  - name: Start MySQL
    run: sudo systemctl start mysql

  - name: Start Redis
    run: sudo systemctl start redis

  - name: Deploy to Tomcat
    run: mvn package && cp target/*.war $TOMCAT_HOME/webapps/

  - name: Wait for server
    run: sleep 10

  - name: Run tests
    run: |
      cd src/test/python
      pip install -r requirements.txt
      pytest -v --junitxml=report.xml
```

### 8.2 测试报告

pytest 支持生成 JUnit XML 格式的报告：

```bash
pytest -v --junitxml=test-report.xml
```

---

## 文档历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-07-23 | 初始版本 |
