# TEST_SEED — 测试库基线种子说明

> 用途：记录测试库**基线种子**的内容与维护约定；生产库日常运维请勿改动下列种子行。
> 生成机制：`tools/init_test_db.py` 覆盖重建测试库（导入最新备份）后**自动追加**基线种子，幂等，无需手工干预。
> 同步约定：更改任何种子前，先同步 [tools/init_test_db.py](../../tools/init_test_db.py) 的常量/SQL 与 [conftest.py](../../src/test/python/conftest.py) 的 `SEED_COMMENT_CONTENT_PREFIX` / `SEED_COUPON_TITLE_PREFIX`，再重建测试库。

## 一、基线种子内容

### 用户（2 个 seed 相关用户）

1. **管理员**：`users.id=1`（一号员工）`role=1`——基线内容作者。
   - 重建时 init_test_db.py 幂等 `UPDATE` 提升；admin 用例（test_admin / test_hide_content / test_comment_delete）动态自建（提升再降级），不依赖此账号登录。
2. **楼中楼回复者**：`users.id=2`（内部人员）——种子评论链的"回复"作者（seed SQL 硬引用 `user_id=2`，楼中楼 `@reply_to_user_id=2`）。
   - 属种子相关用户，**勿删**；T5 cleanup_data 用户清理仅按测试前缀白名单（testA_ 等），中文用户名天然不命中。

### 内容与评论

3. **基线内容**：`seed_baseline_test_content`（**type=2 图文**、无 `content_media` 行、`comment_count=3`）+ 3 条评论链（主楼 user=1 / 回复 user=2 / 楼中楼 user=1 @2）。
   - **必须 type=2 图文**：缓存构建对视频（type=1）强制要求 content_media 媒体行，缺失即启动失败；图文允许无媒体。
   - 标题**刻意避开 `pytest_/smoke_` 前缀**：防 cleanup_data.py 自动清理误删种子，测试后自动清理链路保持种子存活。

### 优惠券

4. **固定券（T1/N1）**：`seed_baseline_coupon`——高库存（999999）、begin 固定过去（2026-01-01）、end 固定远期（2099-12-31），必抢成功、永不耗尽，coupon 用例零静默 skip。幂等：先按标题 DELETE 旧行再 INSERT（`coupon_order` 外键 `ON DELETE CASCADE` 级联清订单，无残留）。
5. **过期券参照**：历史 coupon `id=6`（conftest `EXPIRED_COUPON_ID`）——过期券 grab 必 409、不落订单不耗库存。改动固定券或备份时勿破坏此语义。

### 消费方

6. conftest `sample_comment_id`（基线评论）、`available_coupon_id`（固定券）按标题前缀确定性定位，缺失直接 fail 并提示重建测试库；`user_a/user_b` 等动态自建，不依赖种子。

## 二、重建 / 复现

```powershell
python tools\init_test_db.py          # 默认取 .docs/archive/DBbackups 最新 db.sql → 3307 测试库，导入后自动追加种子
python tools\init_test_db.py --dump path/to/db.sql
```

成功输出含种子校验摘要（管理员 role、基线内容标题/评论数、固定券库存）；重复 init 不重复插入。

## 三、维护约定（生产库）

1. 勿改/删 `users.id=1`、`users.id=2` 与 `seed_baseline_*` 内容、评论及固定券——重建会按种子 SQL 重置，但仍需生产侧保持稳定以免基线不一致。
2. 表结构/需求变更影响种子 SQL → 先同步代码（见同步约定）与本文档，再重建。

## 四、行为约定（测试侧）

- 基线评论会被 C-04/C-05 成对"点赞/取消"，完整运行净零；固定券用例只锁相对差值（抢后 = 抢前 - 1）、每用例最多抢一次。
- 测试中断（如超时刹车）可能残留 1 条指向种子评论的 `comment_like`，用 `python tools\check_integrity.py --fix` 修复计数即可，不影响种子有效性。