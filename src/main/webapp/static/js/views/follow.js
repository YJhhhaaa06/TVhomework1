// ============================================================================
// 动态 #/follow —— 关注流（/feed，分页）
// 由左抽屉「动态」进入；未登录显示锁定提示。
// ============================================================================

import { request } from '../api.js';
import { isLoggedIn } from '../auth.js';
import { skeletonFeed, emptyBox, initialChar, avatarColor, formatDuration, formatTime, showToast } from '../utils.js';
import { navigate } from '../router.js';

const PAGE_SIZE = 10;
let state = null;

export function mount(container) {
  state = { container, page: 1, totalPages: 0 };
  container.innerHTML = '<div class="home"></div>';
  if (isLoggedIn()) loadFeed(1);
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

async function loadFeed(page) {
  const b = box();
  if (page === 1) b.innerHTML = '<div class="feed-list">' + skeletonFeed(4) + '</div>';
  else setLoadMore('loading');

  try {
    const data = await request(`feed?page=${page}&pageSize=${PAGE_SIZE}`);
    state.page = data.page;
    state.totalPages = data.totalPages;
    const list = data.list || [];

    if (page === 1) {
      if (!list.length) { b.innerHTML = emptyBox('暂无关注动态', '📭'); return; }
      const feedList = document.createElement('div');
      feedList.className = 'feed-list';
      list.forEach((it, i) => feedList.appendChild(createFeedItem(it, i)));
      b.innerHTML = '';
      b.appendChild(feedList);
    } else {
      const feedList = b.querySelector('.feed-list');
      if (feedList) list.forEach((it) => feedList.appendChild(createFeedItem(it)));
    }
    renderLoadMore();
  } catch (e) {
    if (e.code === 401 || e.code === 403) { setLock(); return; }
    if (page === 1) b.innerHTML = emptyBox('加载失败，请刷新重试');
    else { setLoadMore('retry'); showToast('加载失败，请重试'); }
  }
}

function renderLoadMore() {
  const b = box();
  const old = b.querySelector('.load-more');
  if (old) old.remove();
  if (state.page < state.totalPages) {
    const wrap = document.createElement('div');
    wrap.className = 'load-more';
    const btn = document.createElement('button');
    btn.className = 'load-more-btn';
    btn.textContent = '加载更多';
    btn.addEventListener('click', () => loadFeed(state.page + 1));
    wrap.appendChild(btn);
    b.appendChild(wrap);
  }
}

function setLoadMore(mode) {
  const btn = box().querySelector('.load-more-btn');
  if (!btn) return;
  if (mode === 'loading') { btn.disabled = true; btn.textContent = '加载中...'; }
  else { btn.disabled = false; btn.textContent = '加载更多'; }
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
