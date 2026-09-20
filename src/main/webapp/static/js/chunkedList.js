// ============================================================================
// chunkedList.js —— 公共"分块列表"helper（T10-B 抽出，N15 第一步；T11 复用并增强）
// 大 chunk 拉取 + 本地小批展示 + 跨 chunk 去重：
//   一次拉大量条目（chunk），本地按 batchSize 分批消费；本地余量足够时不发请求，
//   用尽才拉下一个 chunk（页）。翻页期间集合变化会让同一条目在相邻 chunk 重复出现
//   （offset 漂移），helper 用 seen 集合按 keyOf 过滤——**去重只兜漂移，不替代后端
//   "页间不重不漏"的正确性**（后端契约仍由 pytest 直打 API 验证，去重不得掩盖后端 bug）。
// 用法：
//   const list = createChunkedList({
//     fetchChunk: async (page) => await request(`xxx?page=${page}`),
//     keyOf: (it) => it.commentId,   // 见下方"keyOf"
//     batchSize: 10,
//   });
//   // 首次/刷新：list.reset(); const first = await list.nextBatch(); render(first);
//   // 「加载更多」：const batch = await list.nextBatch(); if (!batch.length) 无更多;
//   //   按钮显隐：list.hasMore()
//
// keyOf（去重键，T11-B）：
//   缺省依次取 item.userId / item.commentId / item.id（覆盖用户列表与内容列表）。
//   ⚠️ 评论 VO **同时含 userId（作者）与 commentId**——缺省会落到 userId，导致"同一作者的
//   多条评论被折叠成一条"。**评论类列表必须显式传 `keyOf: (c) => c.commentId`**；
//   同理内容类列表显式传 `(it) => it.id`，不依赖缺省推导。
//   keyOf 返回 null/undefined 的条目不参与去重（缺 id 时仍照常展示，不吞条目）。
//
// chunkSize（信封大小，T11-B 自适应）：
//   传入值只是**初始/兜底值**（首次请求失败时用它判断"本 chunk 是否到末页"）；首次成功响应后
//   用响应回显的 `pageSize` 覆盖——信封大小由**后端域级常量**决定，前端不再写死"要多少条"，
//   且 `fetchChunk` 可以只传 page、不传 pageSize（后端缺省即域级信封）。reset() 不回退该值。
// ============================================================================

/** 信封大小兜底值（未传 chunkSize / 首次请求失败时使用）。 */
const DEFAULT_CHUNK_SIZE = 200;
/** 单次 nextBatch 内最多再拉几个 chunk（防"整页都是重复条目"导致的死循环）。 */
const MAX_CHUNK_FETCHES_PER_BATCH = 10;

/** 缺省去重键：userId → commentId → id（带类型前缀，避免不同实体 id 相撞）。 */
function defaultKeyOf(item) {
  if (item == null) return null;
  if (item.userId != null) return 'u:' + item.userId;
  if (item.commentId != null) return 'c:' + item.commentId;
  if (item.id != null) return 'i:' + item.id;
  return null;
}

export function createChunkedList({
  fetchChunk,
  chunkSize = DEFAULT_CHUNK_SIZE,
  batchSize = 10,
  keyOf = defaultKeyOf,
}) {
  let items = [];        // 已拉取全部条目（大 chunk 累积）
  let cursor = 0;        // 本地已消费位置
  let page = 0;          // 已拉取的 chunk 页号
  let totalPages = 1;    // 服务器总页数（未知按 1）
  let loadedAll = false; // 服务器已无更多
  let loading = false;
  let size = chunkSize;  // 自适应后的信封大小（响应回显为准）
  const seen = new Set(); // 已展示过的条目 key（跨 chunk 去重）

  // 本地余量不足且未耗尽 → 拉下一 chunk（`pos` = 本批**未提交**的消费位置；
  // 用 pos 而非 cursor 判定，`batchSize > chunkSize` 时"批内续拉"才成立——评审 🟡①）
  async function ensureChunk(pos) {
    if (pos < items.length) return;
    if (loadedAll || loading) return;
    loading = true;
    try {
      const data = await fetchChunk(page + 1);
      page = data.page || page + 1;
      totalPages = data.totalPages || totalPages;
      // 自适应：信封大小以响应回显为准（请求可不带 pageSize）
      if (typeof data.pageSize === 'number' && data.pageSize > 0) size = data.pageSize;
      const list = data.list || [];
      items = items.concat(list);
      if (list.length < size || page >= totalPages) loadedAll = true;
    } finally {
      loading = false;
    }
  }

  // 取下一批（本地不足自动发请求；去重后仍不足则继续消费/续拉）；返回数组（空 = 已无更多）
  async function nextBatch() {
    const batch = [];
    const pending = new Set(); // 本批新增的去重键（成功返回时才提交，失败不吞条目）
    let pos = cursor;          // 未提交的消费位置
    let guard = 0;
    for (;;) {
      await ensureChunk(pos);
      while (pos < items.length && batch.length < batchSize) {
        const item = items[pos++];
        const key = keyOf(item);
        if (key != null) {
          // 已展示过（seen）或本批已取过（pending）→ 跳过
          if (seen.has(key) || pending.has(key)) continue;
          pending.add(key);
        }
        batch.push(item);
      }
      if (batch.length >= batchSize) break; // 凑满一批
      if (loadedAll) break;                 // 服务器已无更多
      guard += 1;
      if (guard > MAX_CHUNK_FETCHES_PER_BATCH) break; // 防"整页重复"死循环
    }
    cursor = pos;
    pending.forEach((k) => seen.add(k));
    return batch;
  }

  // 是否还有更多可展示（本地余量 or 服务器未耗尽）
  function hasMore() {
    return cursor < items.length || (!loadedAll && page < totalPages);
  }

  // 重置（发/删数据后回到第 1 页重新累积）；连去重集与已展示集一起清空
  function reset() {
    items = [];
    cursor = 0;
    page = 0;
    totalPages = 1;
    loadedAll = false;
    loading = false;
    seen.clear();
  }

  return { nextBatch, hasMore, reset };
}
