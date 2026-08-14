// ============================================================================
// 首页 #/ —— 推荐流（/start）
// 分区筛选在顶部导航「分类」下拉（由 main.js 通过 ?cat= 路由到本页）；
// 本页只保留类型筛选（全部/视频/图文）+ 4 列网格 + 右下角「换一换」刷新。
// ============================================================================

import { request } from '../api.js';
import { createVideoCard, skeletonCards, emptyBox } from '../utils.js';

const ICON_REFRESH = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-2.64-6.36"/><path d="M21 3v6h-6"/></svg>';

let state = null;

export function mount(container, params) {
  state = {
    container,
    category: params.query.cat != null ? Number(params.query.cat) : -1,
    type: 0,
  };
  render();
  loadRecommend();
}

export function unmount() {
  state = null;
}

function render() {
  const c = state.container;
  c.innerHTML = `
    <div class="home">
      <div class="filter-chips" id="filterChips">
        <button class="chip" data-type="0">全部</button>
        <button class="chip" data-type="1">视频</button>
        <button class="chip" data-type="2">图文</button>
      </div>
      <div class="home-content" id="homeContent"></div>
      <button class="refresh-fab" id="refreshFab" title="换一换">${ICON_REFRESH}</button>
    </div>`;

  // 类型筛选
  c.querySelectorAll('#filterChips .chip').forEach((chip) => {
    chip.classList.toggle('active', Number(chip.dataset.type) === state.type);
    chip.addEventListener('click', () => {
      const t = Number(chip.dataset.type);
      if (state.type === t) return;
      state.type = t;
      c.querySelectorAll('#filterChips .chip').forEach((x) => x.classList.toggle('active', x === chip));
      loadRecommend();
    });
  });

  // 换一换（右下角悬浮按钮）
  c.querySelector('#refreshFab').addEventListener('click', () => loadRecommend(true));
}

async function loadRecommend(shuffle) {
  const content = state.container.querySelector('#homeContent');
  content.innerHTML = '<div class="grid">' + skeletonCards(8) + '</div>';
  try {
    const params = [];
    if (state.type !== 0) params.push('type=' + state.type);
    if (state.category !== -1) params.push('categoryId=' + state.category);
    const qs = params.length ? '?' + params.join('&') : '';
    let list = await request('start' + qs);
    // 换一换：后端 /start 返回固定缓存列表，客户端打乱顺序以获得「新一批」观感。
    if (shuffle) list = shuffleArr(list || []);
    renderRecommend(list);
  } catch (e) {
    if (e.code === 401 || e.code === 403) return;
    content.innerHTML = emptyBox('加载失败，请刷新重试');
  }
}

function renderRecommend(list) {
  const content = state.container.querySelector('#homeContent');
  if (!list || list.length === 0) {
    content.innerHTML = emptyBox('暂无内容', '📭');
    return;
  }
  const grid = document.createElement('div');
  grid.className = 'grid';
  list.forEach((item, i) => grid.appendChild(createVideoCard(item, { index: i })));
  content.innerHTML = '';
  content.appendChild(grid);
}

function shuffleArr(arr) {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}
