// ============================================================================
// 通用工具：转义 / 时间 / 数字缩写 / toast / 卡片渲染
// 「字段缺失优雅降级」统一收敛在 createVideoCard 一处：
//   播放量 viewCount、弹幕数 danmakuCount、时长 duration、头像 avatarUrl 缺失则隐藏或占位。
// ============================================================================

import { navigate } from './router.js';

const ICON_PLAY = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>';
const ICON_VIEW = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 5c-5 0-8.5 3.6-9.7 7 .3 3.4 3.8 7 9.7 7s9.4-3.6 9.7-7c-1.2-3.4-4.7-7-9.7-7zm0 11.5a4.5 4.5 0 1 1 0-9 4.5 4.5 0 0 1 0 9z"/><circle cx="12" cy="12" r="2"/></svg>';
const ICON_DANMAKU = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 4h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-6l-3 4-3-4H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z"/></svg>';

export function escapeHtml(text) {
  if (text == null) return '';
  const div = document.createElement('div');
  div.textContent = String(text);
  return div.innerHTML;
}

/** 相对时间：刚刚 / n分钟前 / n小时前 / n天前 / YYYY-MM-DD */
export function formatTime(t) {
  if (!t) return '';
  const d = new Date(t);
  if (Number.isNaN(d.getTime())) return '';
  const diff = Date.now() - d.getTime();
  if (diff < 60000) return '刚刚';
  if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前';
  if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前';
  if (diff < 604800000) return Math.floor(diff / 86400000) + '天前';
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
function pad(n) { return String(n).padStart(2, '0'); }

/** "2026-08-14T12:00:00" → "2026-08-14 12:00" */
export function formatDateTime(str) {
  if (!str) return '';
  return String(str).replace('T', ' ').substring(0, 16);
}

/** 数字缩写：12345 → 1.2万；123456789 → 1.2亿 */
export function formatNumber(n) {
  if (n == null || Number.isNaN(n)) return '';
  if (n >= 100000000) return trimZero((n / 100000000).toFixed(1)) + '亿';
  if (n >= 10000) return trimZero((n / 10000).toFixed(1)) + '万';
  return String(n);
}
function trimZero(s) { return s.replace(/\.0$/, ''); }

/** 秒 → mm:ss 或 h:mm:ss */
export function formatDuration(sec) {
  if (sec == null || Number.isNaN(sec)) return '';
  sec = Math.floor(sec);
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`;
}

export function formatSize(bytes) {
  if (bytes < 1024) return bytes + 'B';
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + 'KB';
  return (bytes / 1048576).toFixed(1) + 'MB';
}

export function debounce(fn, wait = 300) {
  let timer = null;
  return function (...args) {
    clearTimeout(timer);
    timer = setTimeout(() => fn.apply(this, args), wait);
  };
}

// ---------- 头像占位 ----------
const AVATAR_GRADIENTS = [
  'linear-gradient(135deg,#fb7299,#ff9cbb)',
  'linear-gradient(135deg,#ff9a44,#fc6076)',
  'linear-gradient(135deg,#4facfe,#00d4ff)',
  'linear-gradient(135deg,#43e97b,#38d9a9)',
  'linear-gradient(135deg,#a18cd1,#fbc2eb)',
  'linear-gradient(135deg,#f093fb,#f5576c)',
];
export function avatarColor(name) {
  const s = String(name || '');
  let sum = 0;
  for (let i = 0; i < s.length; i++) sum += s.charCodeAt(i);
  return AVATAR_GRADIENTS[sum % AVATAR_GRADIENTS.length];
}
export function initialChar(name) {
  return (String(name || '?').trim()[0] || '?').toUpperCase();
}

// ---------- toast ----------
let toastTimer = null;
export function showToast(msg) {
  const old = document.querySelector('.toast');
  if (old) old.remove();
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = msg;
  document.body.appendChild(el);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.remove(), 2000);
}

// ---------- 空态 / 骨架 ----------
export function emptyBox(msg, icon = '😞') {
  return `<div class="empty"><div class="empty-icon">${icon}</div><div class="empty-msg">${escapeHtml(msg)}</div></div>`;
}

export function skeletonCards(n = 8) {
  let html = '';
  for (let i = 0; i < n; i++) {
    html += '<div class="v-card"><div class="v-card-cover skeleton"></div>'
      + '<div class="v-card-body"><div class="skeleton-line" style="width:92%"></div>'
      + '<div class="skeleton-line short"></div></div></div>';
  }
  return html;
}

export function skeletonFeed(n = 4) {
  let html = '';
  for (let i = 0; i < n; i++) {
    html += '<div class="feed-item"><div class="feed-cover skeleton"></div>'
      + '<div class="feed-info"><div class="skeleton-line" style="width:80%"></div>'
      + '<div class="skeleton-line short"></div></div></div>';
  }
  return html;
}

// ---------- 卡片渲染 ----------
/**
 * 构建内容卡片（首页/搜索/主页共用）。
 * 字段兜底：coverUrl 缺失/加载失败 → 首字渐变占位；
 *          viewCount/danmakuCount/duration/avatarUrl 缺失 → 隐藏对应元素，不报错。
 */
export function createVideoCard(item, opts = {}) {
  const card = document.createElement('div');
  card.className = 'v-card';
  card.setAttribute('role', 'button');
  card.tabIndex = 0;
  if (opts.index != null) card.style.animationDelay = (opts.index % 12) * 45 + 'ms';

  const cover = document.createElement('div');
  cover.className = 'v-card-cover';

  // 占位底（无封面/加载失败时可见）
  const fallback = document.createElement('div');
  fallback.className = 'cover-fallback';
  fallback.textContent = initialChar(item.title || item.authorName);
  fallback.style.background = avatarColor(item.title || item.authorName);
  cover.appendChild(fallback);

  if (item.coverUrl) {
    const img = document.createElement('img');
    img.className = 'cover-img';
    img.src = item.coverUrl;
    img.alt = '';
    img.loading = 'lazy';
    img.addEventListener('error', () => img.remove());
    cover.appendChild(img);
  }

  // hover 蒙层
  const mask = document.createElement('div');
  mask.className = 'cover-mask';
  if (item.type === 1) {
    const play = document.createElement('div');
    play.className = 'cover-play';
    play.innerHTML = ICON_PLAY;
    cover.appendChild(play);
  }
  let statsHtml = '';
  if (item.viewCount != null) statsHtml += `<span>${ICON_VIEW} ${formatNumber(item.viewCount)}</span>`;
  if (item.danmakuCount != null) statsHtml += `<span>${ICON_DANMAKU} ${formatNumber(item.danmakuCount)}</span>`;
  if (statsHtml) {
    const stats = document.createElement('div');
    stats.className = 'cover-stats';
    stats.innerHTML = statsHtml;
    mask.appendChild(stats);
  }
  if (item.duration != null) {
    const dur = document.createElement('span');
    dur.className = 'duration-badge';
    dur.textContent = typeof item.duration === 'number' ? formatDuration(item.duration) : item.duration;
    cover.appendChild(dur);
  }
  cover.appendChild(mask);

  const body = document.createElement('div');
  body.className = 'v-card-body';
  const title = document.createElement('div');
  title.className = 'v-card-title';
  title.textContent = item.title || '';
  const meta = document.createElement('div');
  meta.className = 'v-card-meta';
  const up = document.createElement('span');
  up.className = 'up-name';
  up.textContent = item.authorName || '';
  up.addEventListener('click', (e) => {
    e.stopPropagation();
    if (item.authorId != null) navigate('/user/' + item.authorId);
  });
  const stats = document.createElement('span');
  stats.className = 'meta-stats';
  stats.textContent = `❤ ${item.likeCount || 0}   💬 ${item.commentCount || 0}`;
  meta.appendChild(up);
  meta.appendChild(stats);
  body.appendChild(title);
  body.appendChild(meta);

  card.appendChild(cover);
  card.appendChild(body);

  const open = () => navigate('/video/' + item.id);
  card.addEventListener('click', open);
  card.addEventListener('keydown', (e) => { if (e.key === 'Enter') open(); });

  return card;
}
