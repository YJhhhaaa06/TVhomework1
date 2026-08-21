// ============================================================================
// 搜索 #/search?kw=…
// 无 kw 时展示搜索落地页（历史 + 热门占位）；有 kw 时展示结果网格 + 类型筛选 + 分页。
// ============================================================================

import { request } from '../api.js';
import { createVideoCard, emptyBox, escapeHtml, showToast } from '../utils.js';

const HISTORY_KEY = 'searchHistory';
const MAX_HISTORY = 10;
const PAGE_SIZE = 12;

// 热门搜索占位（后端暂无接口，前端预留）
const HOT_SEARCHES = [
  { word: '游戏实况', badge: '热' },
  { word: '搞笑日常', badge: '新' },
  { word: '音乐翻唱', badge: '热' },
  { word: '绘画教程', badge: '' },
  { word: '动物萌宠', badge: '热' },
  { word: '美食制作', badge: '' },
  { word: '科技资讯', badge: '新' },
  { word: '舞蹈挑战', badge: '' },
];

let state = null;

export function mount(container, params) {
  state = {
    container,
    keyword: '',
    results: [],
    filter: 0,
    page: 1,
    totalPages: 1,
    total: 0,
    searching: false,
  };
  const kw = params.query.kw;
  if (kw) doSearch(kw);
  else renderLanding();
}

export function unmount() {
  state = null;
}

function setGlobalInput(kw) {
  const input = document.getElementById('globalSearch');
  if (input) input.value = kw;
}

// ---------- 落地页 ----------
function renderLanding() {
  const c = state.container;
  const history = getHistory();
  c.innerHTML = `
    <div class="search-page">
      <div class="search-landing">
        <div class="search-section">
          <div class="search-section-header">
            <span class="label">搜索历史</span>
            <span class="action" id="clearHistory">🗑 清除</span>
          </div>
          <div class="history-tags" id="historyTags">${renderHistoryTags(history)}</div>
        </div>
        <div class="search-section">
          <div class="search-section-header"><span class="label">🔥 热门搜索</span></div>
          <div class="hot-list">${HOT_SEARCHES.map((item, i) => `
            <div class="hot-item" data-kw="${escapeHtml(item.word)}">
              <span class="rank${i < 3 ? ' top3' : ''}">${i + 1}</span>
              <span class="word">${escapeHtml(item.word)}</span>
              ${item.badge ? `<span class="badge">${item.badge}</span>` : ''}
            </div>`).join('')}
          </div>
        </div>
      </div>
    </div>`;

  c.querySelectorAll('.history-tag').forEach((tag) => {
    tag.addEventListener('click', () => doSearch(tag.dataset.kw));
  });
  c.querySelectorAll('.hot-item').forEach((item) => {
    item.addEventListener('click', () => doSearch(item.dataset.kw));
  });
  const clear = c.querySelector('#clearHistory');
  if (clear) clear.addEventListener('click', () => { clearHistory(); renderLanding(); });
}

function renderHistoryTags(list) {
  if (!list.length) return '<div class="empty"><div class="empty-msg">暂无搜索历史</div></div>';
  return list.map((kw) => `<span class="history-tag" data-kw="${escapeHtml(kw)}">${escapeHtml(kw)}</span>`).join('');
}

// ---------- 结果页 ----------
function renderResult() {
  const c = state.container;
  c.innerHTML = `
    <div class="search-page">
      <div class="filter-chips" id="filterChips">
        <button class="chip" data-type="0">全部</button>
        <button class="chip" data-type="1">视频</button>
        <button class="chip" data-type="2">图文</button>
      </div>
      <div class="search-result-info" id="resultInfo"></div>
      <div class="home-content" id="resultGrid"></div>
    </div>`;

  c.querySelectorAll('#filterChips .chip').forEach((chip) => {
    chip.classList.toggle('active', Number(chip.dataset.type) === state.filter);
    chip.addEventListener('click', () => {
      state.filter = Number(chip.dataset.type);
      c.querySelectorAll('#filterChips .chip').forEach((x) => x.classList.toggle('active', x === chip));
      renderResults();
    });
  });
}

function renderResults() {
  const c = state.container;
  const grid = c.querySelector('#resultGrid');
  const info = c.querySelector('#resultInfo');
  const filtered = state.filter === 0 ? state.results : state.results.filter((r) => r.type === state.filter);

  info.textContent = `“${state.keyword}” 共 ${state.total} 条结果`;

  if (!filtered.length) {
    grid.innerHTML = emptyBox('没有找到相关内容', '📭');
    grid.querySelector('.empty').style.padding = '40px 20px';
    return;
  }
  const g = document.createElement('div');
  g.className = 'grid';
  filtered.forEach((item, i) => g.appendChild(createVideoCard(item, { index: i })));
  grid.innerHTML = '';
  grid.appendChild(g);
}

// ---------- 搜索逻辑 ----------
async function doSearch(keyword) {
  if (!keyword || state.searching) return;
  state.searching = true;
  state.keyword = keyword;
  state.filter = 0;
  state.page = 1;
  setGlobalInput(keyword);
  addHistory(keyword);
  renderResult();

  const grid = state.container.querySelector('#resultGrid');
  grid.innerHTML = '<div class="grid">' + '<div class="v-card"><div class="v-card-cover skeleton"></div></div>'.repeat(8) + '</div>';

  try {
    const data = await request(`search/keywordSearch?keyword=${encodeURIComponent(keyword)}&page=1&pageSize=${PAGE_SIZE}`);
    state.results = data.list || [];
    state.total = data.total || 0;
    state.totalPages = data.totalPages || 1;
    state.page = 1;
    renderResults();
    renderLoadMore();
  } catch (e) {
    if (e.code === 401 || e.code === 403) return;
    grid.innerHTML = emptyBox('加载失败，请重试');
  } finally {
    state.searching = false;
  }
}

function renderLoadMore() {
  const c = state.container;
  const old = c.querySelector('.load-more');
  if (old) old.remove();
  if (state.page < state.totalPages) {
    const wrap = document.createElement('div');
    wrap.className = 'load-more';
    const btn = document.createElement('button');
    btn.className = 'load-more-btn';
    btn.textContent = '加载更多';
    btn.addEventListener('click', loadMore);
    wrap.appendChild(btn);
    c.querySelector('#resultGrid').appendChild(wrap);
  }
}

async function loadMore() {
  if (state.searching || state.page >= state.totalPages) return;
  state.searching = true;
  const c = state.container;
  const btn = c.querySelector('.load-more-btn');
  if (btn) { btn.disabled = true; btn.textContent = '加载中...'; }
  try {
    const data = await request(`search/keywordSearch?keyword=${encodeURIComponent(state.keyword)}&page=${state.page + 1}&pageSize=${PAGE_SIZE}`);
    const list = data.list || [];
    state.results = state.results.concat(list);
    state.total = data.total || state.total;
    state.totalPages = data.totalPages || state.totalPages;
    state.page += 1;
    renderResults();
  } catch (e) {
    showToast('加载失败，请重试');
  } finally {
    state.searching = false;
    renderLoadMore();
  }
}

// ---------- 历史 ----------
function getHistory() {
  try { return JSON.parse(localStorage.getItem(HISTORY_KEY)) || []; } catch (e) { return []; }
}
function addHistory(keyword) {
  let list = getHistory();
  list = list.filter((item) => item !== keyword);
  list.unshift(keyword);
  if (list.length > MAX_HISTORY) list.pop();
  localStorage.setItem(HISTORY_KEY, JSON.stringify(list));
}
function clearHistory() {
  localStorage.removeItem(HISTORY_KEY);
}
