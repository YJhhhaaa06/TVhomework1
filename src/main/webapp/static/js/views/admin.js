// ============================================================================
// 媒体运维 #/admin —— 仅管理员（AuthFilter 对 /api/admin/* 校验 role==1，非管理员 403）
// ============================================================================

import { request } from '../api.js';
import { isLoggedIn } from '../auth.js';
import { showToast, escapeHtml } from '../utils.js';

let state = null;

export function mount(container) {
  state = { container, result: null, filter: 'missing' };
  if (!isLoggedIn()) {
    container.innerHTML = '<div class="empty"><div class="empty-icon">🔒</div><div class="empty-msg">请先登录</div><a class="btn-primary" href="#/login">去登录</a></div>';
    return;
  }
  render();
  init();
}

export function unmount() {
  state = null;
}

function render() {
  state.container.innerHTML = `
    <div class="admin">
      <div class="admin-top">
        <h1>🔧 媒体资源运维</h1>
        <button class="btn-primary" id="scanBtn">重新扫描</button>
      </div>
      <div class="admin-stats" id="stats"></div>
      <div class="admin-toolbar">
        <select id="filter">
          <option value="all">全部</option>
          <option value="missing" selected>仅缺失/异常</option>
          <option value="exists">仅存在</option>
        </select>
      </div>
      <div class="admin-card" id="commentTool">
        <div class="admin-card-title">删除评论（软删除，不可恢复）</div>
        <div class="admin-comment-tool">
          <input type="number" class="input" id="commentIdInput" placeholder="评论ID">
          <button class="btn-primary" id="commentDelBtn">删除</button>
        </div>
      </div>
      <table class="media-table">
        <thead><tr>
          <th>mediaId</th><th>contentId</th><th>标题</th><th>类型</th><th>状态</th>
          <th>URL</th><th>期望路径</th><th>最近校验</th><th>恢复</th>
        </tr></thead>
        <tbody id="tbody"></tbody>
      </table>
    </div>`;

  state.container.querySelector('#scanBtn').addEventListener('click', scan);
  state.container.querySelector('#filter').addEventListener('change', (e) => { state.filter = e.target.value; renderTable(); });
  state.container.querySelector('#commentDelBtn').addEventListener('click', deleteComment);
}

async function deleteComment() {
  const input = state.container.querySelector('#commentIdInput');
  const value = input.value.trim();
  if (!value) { showToast('请输入评论ID'); return; }
  try {
    await request(`api/admin/comment/delete?commentId=${value}`, { method: 'POST' });
    showToast('删除成功');
    input.value = '';
  } catch (e) {
    if (e.code !== 401) showToast(e.message || '删除失败');
  }
}

async function init() {
  try {
    const ok = await request('api/admin/media/me');
    if (ok === true) await loadList();
    else renderNoPermission();
  } catch (e) {
    if (e.code === 403) renderNoPermission();
    else if (e.code !== 401) { showToast(e.message || '加载失败'); renderNoPermission(); }
  }
}

function renderNoPermission() {
  const c = state.container;
  c.querySelector('#scanBtn').style.display = 'none';
  c.querySelector('.admin-toolbar').style.display = 'none';
  c.querySelector('#commentTool').style.display = 'none';
  c.querySelector('thead').style.display = 'none';
  c.querySelector('#stats').innerHTML = '';
  c.querySelector('#tbody').innerHTML = '<tr><td colspan="9" class="admin-empty">无权限访问：该页面仅管理员可用</td></tr>';
}

async function loadList() {
  try {
    state.result = await request('api/admin/media/list');
    renderAll();
  } catch (e) {
    if (e.code !== 401) showToast(e.message || '加载失败');
  }
}

async function scan() {
  showToast('扫描中...');
  try {
    state.result = await request('api/admin/media/scan', { method: 'POST' });
    renderAll();
    showToast('扫描完成');
  } catch (e) {
    if (e.code !== 401) showToast(e.message || '扫描失败');
  }
}

function renderAll() {
  renderStats();
  renderTable();
}

function renderStats() {
  const s = state.result;
  if (!s) return;
  state.container.querySelector('#stats').innerHTML =
    `<div class="admin-stat"><b>${s.total}</b>总数</div>` +
    `<div class="admin-stat ok"><b>${s.existing}</b>存在</div>` +
    `<div class="admin-stat missing"><b>${s.missing}</b>缺失</div>` +
    `<div class="admin-stat"><b>${s.invalid}</b>URL异常</div>` +
    `<div class="admin-stat"><b>${(s.orphanMediaIds || []).length}</b>孤儿media</div>` +
    `<div class="admin-stat"><b>${(s.contentsWithoutMedia || []).length}</b>无媒体内容</div>`;
}

function typeName(t) {
  return t === 1 ? '视频' : t === 2 ? '图片' : t === 3 ? '封面' : '未知';
}
function statusTag(item) {
  if (item.status === 'EXISTS') return '<span class="tag ok">存在</span>';
  if (item.status === 'MISSING') return '<span class="tag missing">缺失</span>';
  return '<span class="tag invalid">URL异常</span>';
}

function renderTable() {
  const s = state.result;
  const tbody = state.container.querySelector('#tbody');
  if (!s) return;
  const items = (s.items || []).filter((item) => {
    if (state.filter === 'all') return true;
    if (state.filter === 'exists') return item.status === 'EXISTS';
    return item.status !== 'EXISTS';
  });
  if (!items.length) { tbody.innerHTML = '<tr><td colspan="9" class="admin-empty">没有符合条件的资源</td></tr>'; return; }
  tbody.innerHTML = items.map((item) => `
    <tr>
      <td>${item.mediaId}</td>
      <td>${item.contentId}</td>
      <td>${escapeHtml(item.contentTitle || '(孤儿/已删除)')}</td>
      <td>${typeName(item.type)}</td>
      <td>${statusTag(item)}</td>
      <td class="media-url">${escapeHtml(item.url)}</td>
      <td class="media-path">${escapeHtml(item.expectedPath || '-')}</td>
      <td>${item.lastVerifyTime ? escapeHtml(String(item.lastVerifyTime)) : '-'}</td>
      <td>
        <form class="restore-form" data-media="${item.mediaId}">
          <input type="file" name="file" required>
          <button class="btn-ghost" type="submit">恢复</button>
        </form>
      </td>
    </tr>`).join('');

  tbody.querySelectorAll('.restore-form').forEach((form) => {
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      restore(Number(form.dataset.media), form);
    });
  });
}

async function restore(mediaId, form) {
  const fileInput = form.querySelector('input[type=file]');
  if (!fileInput.files || !fileInput.files.length) return;
  const fd = new FormData();
  fd.append('mediaId', mediaId);
  fd.append('file', fileInput.files[0]);
  showToast('上传中...');
  try {
    const data = await request('api/admin/media/restore', { method: 'POST', body: fd });
    showToast('恢复成功：' + data.targetPath);
    await loadList();
  } catch (e) {
    if (e.code !== 401) showToast(e.message || '恢复失败');
  }
}
