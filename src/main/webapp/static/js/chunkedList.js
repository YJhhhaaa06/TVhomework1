// ============================================================================
// chunkedList.js —— 公共"分块列表"helper（T10-B，N15 第一步）
// 大 chunk 拉取 + 本地小批展示：一次拉大量条目，本地按 batchSize 分批消费；
// 本地余量足够时不发请求，用尽才拉下一个 chunk（页）。T11 关注/粉丝列表复用。
// 用法：
//   const list = createChunkedList({
//     fetchChunk: async (page) => await request(`xxx?page=${page}&pageSize=${chunkSize}`),
//     chunkSize: 200,
//     batchSize: 10,
//   });
//   // 首次/刷新：list.reset(); const first = await list.nextBatch(); render(first);
//   // 「加载更多」：const batch = await list.nextBatch(); if (!batch.length) 无更多;
//   //   按钮显隐：list.hasMore()
// ============================================================================

export function createChunkedList({ fetchChunk, chunkSize = 200, batchSize = 10 }) {
  let items = [];        // 已拉取全部条目（大 chunk 累积）
  let cursor = 0;        // 本地已消费位置
  let page = 0;          // 已拉取的 chunk 页号
  let totalPages = 1;    // 服务器总页数（未知按 1）
  let loadedAll = false; // 服务器已无更多
  let loading = false;

  // 本地余量不足且未耗尽 → 拉下一 chunk
  async function ensureChunk() {
    if (cursor < items.length) return;
    if (loadedAll || loading) return;
    loading = true;
    try {
      const data = await fetchChunk(page + 1);
      page = data.page || page + 1;
      totalPages = data.totalPages || totalPages;
      const list = data.list || [];
      items = items.concat(list);
      if (list.length < chunkSize || page >= totalPages) loadedAll = true;
    } finally {
      loading = false;
    }
  }

  // 取下一批（本地不足自动发请求）；返回数组（空 = 已无更多）
  async function nextBatch() {
    await ensureChunk();
    if (cursor >= items.length) return [];
    const batch = items.slice(cursor, cursor + batchSize);
    cursor += batch.length;
    return batch;
  }

  // 是否还有更多可展示（本地余量 or 服务器未耗尽）
  function hasMore() {
    return cursor < items.length || (!loadedAll && page < totalPages);
  }

  // 重置（发/删数据后回到第 1 页重新累积）
  function reset() {
    items = [];
    cursor = 0;
    page = 0;
    totalPages = 1;
    loadedAll = false;
    loading = false;
  }

  return { nextBatch, hasMore, reset };
}