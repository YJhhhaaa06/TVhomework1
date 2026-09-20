// ============================================================================
// 动态 #/follow —— 关注流（/feed，分页）
// 由左抽屉「动态」进入；未登录显示锁定提示。
// ============================================================================

import { request } from '../api.js';
import { isLoggedIn } from '../auth.js';
import { skeletonFeed, emptyBox, initialChar, avatarColor, formatDuration, formatTime, showToast } from '../utils.js';
import { navigate } from '../router.js';
import { createChunkedList } from '../chunkedList.js';

// T11-B：/feed 分块——一次拉 CHUNK_SIZE（顶现有公共 cap 50，后端改动归 T19），本地按 BATCH_SIZE
// 小批展示：请求数降到约 1/5，本地余量足够时「加载更多」0 请求。顺带获得跨 chunk 去重。
const CHUNK_SIZE = 50;
const BATCH_SIZE = 10;
let state = null;

export function mount(container) {
  state = { container, list: null };
  container.innerHTML = '<div class="home"></div>';
  if (isLoggedIn()) loadFirst();
  else setLock();
}

export function unmount() {
  state = null;
}

function box() {
  return state.container.querySelector('.home');
}

function setLock() {
  box().innerHTML = '<div class="empty"><div class="empty-icon">🔒</div>'
    + '<div class="empty-msg">登录后可查看关注动态</div>'
    + '<a class="btn-primary" href="#/login">去登录</a></div>';
}

// 首次加载（与 reset 等价）：新建分块列表实例并取首批
async function loadFirst() {
  const b = box();
  b.innerHTML = '<div class="feed-list">' + skeletonFeed(4) + '</div>';

  state.list = createChunkedList({
    fetchChunk: async (page) => request(`feed?page=${page}&pageSize=${CHUNK_SIZE}`),
    chunkSize: CHUNK_SIZE,
    batchSize: BATCH_SIZE,
    keyOf: (it) => it.id,
  });

  try {
    const batch = await state.list.nextBatch();
    if (!batch.length) { b.innerHTML = emptyBox('暂无关注动态', '📭'); return; }
    const feedList = document.createElement('div');
    feedList.className = 'feed-list';
    batch.forEach((it, i) => feedList.appendChild(createFeedItem(it, i)));
    b.innerHTML = '';
    b.appendChild(feedList);
    renderLoadMore();
  } catch (e) {
    if (e.code === 401 || e.code === 403) { setLock(); return; }
    b.innerHTML = emptyBox('加载失败，请刷新重试');
  }
}

// 「加载更多」：本地余量足够则不发请求；不足才由 helper 拉下一个 chunk
async function loadMoreFeed(btn) {
  btn.disabled = true;
  btn.textContent = '加载中...';
  try {
    const batch = await state.list.nextBatch();
    if (batch.length) {
      const feedList = box().querySelector('.feed-list');
      if (feedList) batch.forEach((it) => feedList.appendChild(createFeedItem(it)));
    }
    renderLoadMore();
  } catch (e) {
    btn.disabled = false;
    btn.textContent = '加载更多';
    showToast('加载失败，请重试');
  }
}

function renderLoadMore() {
  const b = box();
  const old = b.querySelector('.load-more');
  if (old) old.remove();
  if (state.list && state.list.hasMore()) {
    const wrap = document.createElement('div');
    wrap.className = 'load-more';
    const btn = document.createElement('button');
    btn.className = 'load-more-btn';
    btn.textContent = '加载更多';
    btn.addEventListener('click', () => loadMoreFeed(btn));
    wrap.appendChild(btn);
    b.appendChild(wrap);
  }
}

function createFeedItem(item, index) {
  const el = document.createElement('div');
  el.className = 'feed-item';
  el.style.animationDelay = (index % 8) * 40 + 'ms';
  el.addEventListener('click', () => navigate('/video/' + item.id));

  const cover = document.createElement('div');
  cover.className = 'feed-cover';
  const fb = document.createElement('div');
  fb.className = 'cover-fallback';
  fb.textContent = initialChar(item.title || item.authorName);
  fb.style.background = avatarColor(item.title || item.authorName);
  cover.appendChild(fb);
  if (item.coverUrl) {
    const img = document.createElement('img');
    img.src = item.coverUrl; img.alt = ''; img.loading = 'lazy';
    img.addEventListener('error', () => img.remove());
    cover.appendChild(img);
  }
  if (item.duration != null) {
    const d = document.createElement('span');
    d.className = 'duration-badge';
    d.textContent = typeof item.duration === 'number' ? formatDuration(item.duration) : item.duration;
    cover.appendChild(d);
  }

  const info = document.createElement('div');
  info.className = 'feed-info';
  const title = document.createElement('div');
  title.className = 'feed-title';
  title.textContent = item.title || '';
  const meta = document.createElement('div');
  meta.className = 'feed-meta';
  meta.textContent = `${item.authorName || ''} · ${formatTime(item.createTime)}`;
  const desc = document.createElement('div');
  desc.className = 'feed-desc';
  desc.textContent = item.description || '';

  info.appendChild(title);
  info.appendChild(meta);
  info.appendChild(desc);
  el.appendChild(cover);
  el.appendChild(info);
  return el;
}
