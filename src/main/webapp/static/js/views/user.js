// ============================================================================
// 用户主页 #/user/:id —— 他人主页与我的主页合一（space + profile 合并）
// 本人：显示菜单（改密/券包/退出）；他人：显示关注按钮。
// ============================================================================

import { request } from '../api.js';
import { isLoggedIn, getUserId, clearAuth } from '../auth.js';
import { createVideoCard, showToast, escapeHtml, initialChar, avatarColor } from '../utils.js';
import { navigate } from '../router.js';

const PAGE_SIZE = 10;
let state = null;

export function mount(container, params) {
  const rawId = params.id;
  const meId = getUserId();
  if (rawId === 'me' && !isLoggedIn()) {
    container.innerHTML = '<div class="empty"><div class="empty-icon">🔒</div><div class="empty-msg">请先登录</div><a class="btn-primary" href="#/login">去登录</a></div>';
    state = { container };
    return;
  }
  let profileUserId = rawId === 'me' ? meId : parseInt(rawId, 10);
  if (profileUserId == null || Number.isNaN(profileUserId)) {
    container.innerHTML = '<div class="empty"><div class="empty-msg">用户 ID 无效</div></div>';
    state = { container };
    return;
  }
  state = {
    container,
    profileUserId,
    currentUserId: isLoggedIn() ? meId : null,
    isFollowed: null,
    page: 1,
    totalPages: 0,
    listType: 'following',
  };
  state.isSelf = state.currentUserId != null && state.currentUserId === profileUserId;
  render();
  loadProfile(1);
}

export function unmount() {
  state = null;
}

function render() {
  const c = state.container;
  c.innerHTML = `
    <div class="user-page">
      <div class="user-card">
        <div class="user-top">
          <div class="user-avatar" id="avatar"></div>
          <div>
            <div class="user-name" id="displayName"></div>
            <div class="user-id" id="displayId"></div>
          </div>
        </div>
        <div class="user-stats">
          <div class="stat-item" id="statFollowing"><div class="num" id="followCount">-</div><div class="label">关注</div></div>
          <div class="stat-item" id="statFollowers"><div class="num" id="followerCount">-</div><div class="label">粉丝</div></div>
          <div class="stat-item"><div class="num" id="contentCount">-</div><div class="label">创作</div></div>
        </div>
        <div class="user-follow-wrap" id="followWrap"></div>
      </div>
      <div id="selfMenu"></div>
      <div class="home-content" id="contentGrid"></div>
      <div class="load-more" id="loadMore"></div>
    </div>

    <div class="sheet-overlay hidden" id="sheetOverlay">
      <div class="sheet">
        <div class="sheet-header"><span class="sheet-title" id="sheetTitle">关注</span><button class="sheet-close" id="sheetClose">✕</button></div>
        <div class="sheet-list" id="sheetList"></div>
      </div>
    </div>

    <div class="modal-overlay hidden" id="modalOverlay">
      <div class="modal">
        <div class="modal-title">修改密码</div>
        <input class="input" id="cpPhone" placeholder="手机号" maxlength="11">
        <input class="input" id="cpOldPwd" type="password" placeholder="旧密码">
        <input class="input" id="cpNewPwd" type="password" placeholder="新密码（6-16位）">
        <div class="btn-row">
          <button class="btn-cancel" id="cpCancel">取消</button>
          <button class="btn-confirm" id="cpConfirm">确认</button>
        </div>
      </div>
    </div>`;

  c.querySelector('#statFollowing').addEventListener('click', () => openUserList('following'));
  c.querySelector('#statFollowers').addEventListener('click', () => openUserList('followers'));
  c.querySelector('#sheetClose').addEventListener('click', closeUserList);
  c.querySelector('#sheetOverlay').addEventListener('click', (e) => { if (e.target.id === 'sheetOverlay') closeUserList(); });

  c.querySelector('#cpCancel').addEventListener('click', closeModal);
  c.querySelector('#cpConfirm').addEventListener('click', doChangePassword);
  c.querySelector('#modalOverlay').addEventListener('click', (e) => { if (e.target.id === 'modalOverlay') closeModal(); });

  renderSelfMenu();
}

function renderSelfMenu() {
  const box = state.container.querySelector('#selfMenu');
  if (!state.isSelf) { box.innerHTML = ''; return; }
  box.innerHTML = `
    <div class="user-menu">
      <div class="user-menu-item" id="menuPwd"><span>🔒 修改密码</span><span class="arrow">›</span></div>
      <div class="user-menu-item" id="menuCoupon"><span>🎫 券包</span><span class="arrow">›</span></div>
      <div class="user-menu-item danger" id="menuLogout"><span>🚪 退出登录</span><span class="arrow">›</span></div>
    </div>`;
  box.querySelector('#menuPwd').addEventListener('click', openModal);
  box.querySelector('#menuCoupon').addEventListener('click', () => navigate('/coupon'));
  box.querySelector('#menuLogout').addEventListener('click', () => { clearAuth(); navigate('/'); });
}

async function loadProfile(page) {
  const grid = state.container.querySelector('#contentGrid');
  if (page === 1) {
    grid.innerHTML = '<div class="grid">' + '<div class="v-card"><div class="v-card-cover skeleton"></div></div>'.repeat(6) + '</div>';
  }
  try {
    const data = await request(`profile?userId=${state.profileUserId}&page=${page}&pageSize=${PAGE_SIZE}`);
    renderProfile(data, page);
  } catch (e) {
    if (e.code === 401 || e.code === 403) return;
    if (page === 1) grid.innerHTML = '<div class="empty"><div class="empty-msg">加载失败，请刷新重试</div></div>';
  }
}

function renderProfile(profile, page) {
  const c = state.container;
  const contentPage = profile.contentPage;

  c.querySelector('#displayName').textContent = profile.username || '未知用户';
  const avatar = c.querySelector('#avatar');
  avatar.textContent = initialChar(profile.username);
  c.querySelector('#displayId').textContent = 'ID: ' + profile.userId;
  c.querySelector('#followCount').textContent = profile.followCount != null ? profile.followCount : '-';
  c.querySelector('#followerCount').textContent = profile.followerCount != null ? profile.followerCount : '-';
  c.querySelector('#contentCount').textContent = contentPage ? contentPage.total : '-';

  state.isFollowed = profile.isFollowed;
  renderFollowBtn();

  state.totalPages = contentPage ? contentPage.totalPages : 0;
  const list = contentPage ? (contentPage.list || []) : [];
  if (page === 1) {
    const grid = c.querySelector('#contentGrid');
    grid.innerHTML = '';
    if (!list.length) {
      grid.innerHTML = '<div class="empty"><div class="empty-msg">暂无创作内容</div></div>';
    } else {
      const g = document.createElement('div');
      g.className = 'grid';
      list.forEach((item, i) => g.appendChild(createVideoCard(item, { index: i })));
      grid.appendChild(g);
    }
  } else {
    const g = c.querySelector('#contentGrid .grid');
    if (g) list.forEach((item) => g.appendChild(createVideoCard(item)));
  }

  renderLoadMore();
  state.page = page;
}

function renderFollowBtn() {
  const wrap = state.container.querySelector('#followWrap');
  if (state.isSelf || !isLoggedIn()) { wrap.innerHTML = ''; return; }
  const followed = state.isFollowed === true;
  const btn = document.createElement('button');
  btn.className = 'user-follow-btn ' + (followed ? 'followed' : 'follow');
  btn.textContent = followed ? '已关注' : '+ 关注';
  btn.addEventListener('click', toggleFollow);
  wrap.innerHTML = '';
  wrap.appendChild(btn);
}

function renderLoadMore() {
  const box = state.container.querySelector('#loadMore');
  if (state.page < state.totalPages) {
    const btn = document.createElement('button');
    btn.className = 'load-more-btn';
    btn.textContent = '加载更多';
    btn.addEventListener('click', () => loadProfile(state.page + 1));
    box.innerHTML = '';
    box.appendChild(btn);
  } else {
    box.innerHTML = '';
  }
}

async function toggleFollow() {
  const followed = state.isFollowed === true;
  const action = followed ? 'remove' : 'add';
  state.isFollowed = !followed;
  renderFollowBtn();
  updateFollowerCount(followed ? -1 : 1);
  try {
    await request(`follow/${action}?followedUserId=${state.profileUserId}`, { method: 'POST' });
  } catch (e) {
    state.isFollowed = followed;
    renderFollowBtn();
    updateFollowerCount(followed ? 1 : -1);
    showToast(e.message || '操作失败');
  }
}

function updateFollowerCount(delta) {
  const el = state.container.querySelector('#followerCount');
  const cur = parseInt(el.textContent, 10);
  if (!Number.isNaN(cur)) el.textContent = cur + delta;
}

// ---------- 关注/粉丝列表 ----------
async function openUserList(type) {
  if (!state.profileUserId) return;
  state.listType = type;
  const c = state.container;
  c.querySelector('#sheetTitle').textContent = type === 'following' ? '关注' : '粉丝';
  c.querySelector('#sheetList').innerHTML = '<div class="sheet-empty">加载中...</div>';
  c.querySelector('#sheetOverlay').classList.remove('hidden');
  try {
    const data = await request(`follow/${type}?userId=${state.profileUserId}`);
    renderUserList(data || []);
  } catch (e) {
    c.querySelector('#sheetList').innerHTML = '<div class="sheet-empty">加载失败</div>';
  }
}

function closeUserList() {
  state.container.querySelector('#sheetOverlay').classList.add('hidden');
}

function renderUserList(users) {
  const box = state.container.querySelector('#sheetList');
  if (!users.length) { box.innerHTML = '<div class="sheet-empty">暂无数据</div>'; return; }
  box.innerHTML = '';
  users.forEach((u) => {
    const item = document.createElement('div');
    item.className = 'user-list-item';
    const av = document.createElement('div');
    av.className = 'u-avatar';
    av.textContent = initialChar(u.username);
    av.style.background = avatarColor(u.username);
    const info = document.createElement('div');
    info.className = 'u-info';
    info.innerHTML = `<div class="u-name">${escapeHtml(u.username)}</div><div class="u-id">ID: ${u.userId}</div>`;
    info.addEventListener('click', () => { closeUserList(); navigate('/user/' + u.userId); });
    item.appendChild(av);
    item.appendChild(info);
    if (!u.isSelf) {
      const btn = document.createElement('button');
      btn.className = 'u-btn ' + (u.isFollowed ? 'followed' : 'follow');
      btn.textContent = u.isFollowed ? '已关注' : '+ 关注';
      btn.addEventListener('click', () => toggleUserFollow(u, btn));
      item.appendChild(btn);
    }
    box.appendChild(item);
  });
}

async function toggleUserFollow(u, btn) {
  const followed = u.isFollowed;
  const action = followed ? 'remove' : 'add';
  btn.disabled = true;
  try {
    await request(`follow/${action}?followedUserId=${u.userId}`, { method: 'POST' });
    u.isFollowed = !followed;
    btn.textContent = followed ? '+ 关注' : '已关注';
    btn.className = 'u-btn ' + (followed ? 'follow' : 'followed');
    if (state.isSelf) updateFollowerCount(followed ? -1 : 1);
  } catch (e) {
    showToast(e.message || '操作失败');
  } finally {
    btn.disabled = false;
  }
}

// ---------- 修改密码 ----------
function openModal() {
  state.container.querySelector('#modalOverlay').classList.remove('hidden');
}
function closeModal() {
  const c = state.container;
  c.querySelector('#modalOverlay').classList.add('hidden');
  c.querySelector('#cpPhone').value = '';
  c.querySelector('#cpOldPwd').value = '';
  c.querySelector('#cpNewPwd').value = '';
}
async function doChangePassword() {
  const c = state.container;
  const phone = c.querySelector('#cpPhone').value.trim();
  const oldPassword = c.querySelector('#cpOldPwd').value;
  const newPassword = c.querySelector('#cpNewPwd').value;
  if (!phone || !oldPassword || !newPassword) { showToast('请填写所有字段'); return; }
  try {
    await request('user/changePassword', { jsonBody: { phone, oldPassword, newPassword } });
    showToast('密码修改成功');
    closeModal();
  } catch (e) {
    showToast(e.message || '修改失败');
  }
}
