# TEST_SEED — 测试库基线种子说明

> 用途：记录测试库**基线种子**的内容与维护约定；生产库后续运维请尽量不动本文档列出的种子行。
> 生成机制：`tools/init_test_db.py` 覆盖重建测试库（导入最新生产备份）后**自动追加**基线种子，无需手工干预。
> 变更约定：修改种子前先同步 [tools/init_test_db.py](../../tools/init_test_db.py) 的常量与 SQL、以及 [conftest.py](../../src/test/python/conftest.py) 的 `SEED_COMMENT_CONTENT_PREFIX` / `SEED_COUPON_TITLE_PREFIX`。

## 一、基线种子内容（4 条）

1. **管理员**：`users.id=1`（一号员工）`role=1`。
   - 重建测试库时由 init_test_db.py 自动 `UPDATE` 提升（幂等）。
   - admin 相关用例（test_admin / test_hide_content / test_comment_delete）**保持动态自建**（注册/挑选 + 提升 + 降级），不依赖本账号登录。
2. **基线内容**：标题前缀 `seed_baseline_` 的一条内容（当前标题 `seed_baseline_test_content`，**type=2 图文**、无 `content_media` 行、`file_exists=1`、`comment_enabled=1`、`comment_count=3`）+ 3 条评论链（主楼 / 回复 / 楼中楼@）。
   - **必须用 type=2 图文**：缓存构建 `buildContentMedia` 对 type=1（视频）**强制要求** `content_media` 的视频行，缺失即抛「资源已丢失」导致应用启动失败；type=2 图文允许无媒体行（纯文字内容，媒体扫描 `MediaAuditService.scanAll` 视为完整，`file_exists` 回写 1）。
   - 标题**刻意避开 `pytest_/smoke_` 前缀**：`cleanup_data.py` 只清这两个前缀的内容及关联记录，不会误删种子，测试后自动清理（`run_tests.py test/all` 尾部）保持种子存活。`file_exists` 语义见 db.sql schema 注释（媒体完整性聚合），非「无媒体即置 1」的表意。
3. **固定券（T1）**：标题前缀 `seed_baseline_coupon`（当前标题 `seed_baseline_coupon`）的一张**确定有货**的券——高库存（999999）、`begin_time` 固定过去（2026-01-01）、`end_time` 固定远期（2099-12-31），可抢必 200、永不耗尽。
   - **用途（N1 痛点）**：测试库历史券要么过期（coupon 6）、要么有限库存递减（coupon 7 库存 918），库存数不确定导致 coupon 用例静默 skip。固定券让 `available_coupon_id` 按标题前缀确定性命中，coupon 用例零静默 skip。
   - **幂等**：种子 SQL 先按标题 DELETE 旧行再 INSERT，重复 init 不重复插入；`coupon_order` 外键 `ON DELETE CASCADE` 会级联清掉指向它的订单，无残留。
   - **断言约定**：用例只锁相对差值（抢后库存 = 抢前 - 1），不锁绝对库存；每用例最多抢一次（E-09 用 userA、C-10 用 userB 错开）。
   - **过期券 E-09b 不受影响**：仍用历史过期券 coupon id=6（conftest `EXPIRED_COUPON_ID`），grab 必 409 语义不动。
4. **消费方**：conftest 的 `sample_comment_id` 经 `/search/keywordSearch` 用种子标题前缀确定性定位基线评论（`/start` 为随机推荐不可靠；首页扫描仅作兜底），供 test_consistency C-04/C-05 评论点赞计数；`available_coupon_id` 按 `SEED_COUPON_TITLE_PREFIX` 命中国定券（缺失直接 fail）；`user_a/user_b/sample_content_id` 等均为动态自建，不依赖种子。

## 二、重建 / 复现

```powershell
python tools\init_test_db.py          # 默认取 .docs/archive/DBbackups 最新 db.sql → 3307 测试库，导入后自动追加种子
python tools\init_test_db.py --dump path/to/db.sql
```

成功输出包含基线种子校验摘要（管理员 role、基线内容标题、评论条数）；种子 SQL 幂等，重复 init 不会重复插入。

## 三、维护约定（生产库）

1. **尽量避免**修改/删除 `users.id=1` 的角色与 `seed_baseline_*` 内容及其评论，否则重建测试库得到的基线不一致（重建会按种子 SQL 重置，但仍建议保持生产侧稳定）。
2. 如生产库表结构/需求变更导致种子 SQL 需同步，先按上方"变更约定"改代码与本文档，再重建测试库。

## 四、行为约定（测试侧）

- 基线评论会被 C-04/C-05 成对"点赞/取消"，完整运行时净零（`like_count` 回 0）。
- 若测试在中途中断（如超时被刹车）残留 1 条指向种子评论的 `comment_like`，使用 `python tools\check_integrity.py --fix` 修复计数即可，不影响种子有效性。