// ============================================================================
// 用户主页 #/user/:id —— 他人主页与我的主页合一（space + profile 合并）
// 本人：显示菜单（改密/券包/退出）；他人：显示关注按钮。
// ============================================================================

import { request } from '../api.js';
import { isLoggedIn, getUserId, clearAuth } from '../auth.js';
import { createVideoCard, showToast, escapeHtml, initialChar, avatarColor } from '../utils.js';
import { navigate } from '../router.js';
import { createChunkedList } from '../chunkedList.js';

// 创作网格（/profile）分块：一次拉 PROFILE_CHUNK_SIZE（顶后端公共上限 50，域级信封归 T19），
// 本地按 PROFILE_BATCH_SIZE 小批展示（T11-B 接入公共 chunkedList，N15 收敛点）。
const PROFILE_CHUNK_SIZE = 50;
const PROFILE_BATCH_SIZE = 10;
// 关注/粉丝 sheet（T11-A）：信封大小由**后端 follow 域常量**决定（200），前端**只传 `page`**；
// chunkSize 仅用于「本 chunk 是否已到末页」的本地判定，与后端信封保持一致。
const SHEET_CHUNK_SIZE = 200;
const SHEET_BATCH_SIZE = 10;
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
    profile: null,      // T11-B：最近一次 /profile 响应（头部信息随任一页返回）
    listType: 'following',
    contentList: null,  // T11-B：创作网格 createChunkedList 实例（大 chunk + 本地小批）
    sheetList: null,    // T11-A：关注/粉丝 sheet 实例
  };
  state.isSelf = state.currentUserId != null && state.currentUserId === profileUserId;
  render();
  loadContent();
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
        <div class="load-more" id="sheetMore"></div>
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

// ---------- 创作网格（/profile，T11-B 接公共 chunkedList） ----------
function ensureContentList() {
  if (!state.contentList) {
    state.contentList = createChunkedList({
      fetchChunk: async (page) => {
        const res = await request(`profile?userId=${state.profileUserId}&page=${page}&pageSize=${PROFILE_CHUNK_SIZE}`);
        state.profile = res; // 头部信息（用户名/关注数/粉丝数/创作总数）随任一页返回
        return res.contentPage || { list: [], page, pageSize: PROFILE_CHUNK_SIZE, totalPages: 0 };
      },
      chunkSize: PROFILE_CHUNK_SIZE,
      batchSize: PROFILE_BATCH_SIZE,
      keyOf: (it) => it.id,
    });
  }
  return state.contentList;
}

// 首次加载：重置分块列表并取首批（本地小批展示）
async function loadContent() {
  const grid = state.container.querySelector('#contentGrid');
  grid.innerHTML = '<div class="grid">' + '<div class="v-card"><div class="v-card-cover skeleton"></div></div>'.repeat(6) + '</div>';
  const list = ensureContentList();
  list.reset();
  try {
    const batch = await list.nextBatch();
    renderProfileHead();
    renderContentGrid(batch);
    renderContentLoadMore();
  } catch (e) {
    if (e.code === 401 || e.code === 403) return;
    grid.innerHTML = '<div class="empty"><div class="empty-msg">加载失败，请刷新重试</div></div>';
  }
}

// 「加载更多」：本地余量足够则不发请求；不足才由 helper 拉下一个 chunk
async function loadMoreContent(btn) {
  btn.disabled = true;
  btn.textContent = '加载中...';
  try {
    const batch = await ensureContentList().nextBatch();
    appendContentGrid(batch);
    renderContentLoadMore();
  } catch (e) {
    btn.disabled = false;
    btn.textContent = '加载更多';
    showToast('加载失败，请重试');
  }
}

function renderProfileHead() {
  const c = state.container;
  const profile = state.profile || {};
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
}

function renderContentGrid(list) {
  const grid = state.container.querySelector('#contentGrid');
  grid.innerHTML = '';
  if (!list.length) {
    grid.innerHTML = '<div class="empty"><div class="empty-msg">暂无创作内容</div></div>';
    return;
  }
  const g = document.createElement('div');
  g.className = 'grid';
  list.forEach((item, i) => g.appendChild(createVideoCard(item, { index: i })));
  grid.appendChild(g);
}

function appendContentGrid(list) {
  const g = state.container.querySelector('#contentGrid .grid');
  if (g) list.forEach((item) => g.appendChild(createVideoCard(item)));
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

function renderContentLoadMore() {
  const box = state.container.querySelector('#loadMore');
  box.innerHTML = '';
  if (state.contentList && state.contentList.hasMore()) {
    const btn = document.createElement('button');
    btn.className = 'load-more-btn';
    btn.textContent = '加载更多';
    btn.addEventListener('click', () => loadMoreContent(btn));
    box.appendChild(btn);
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

// ---------- 关注/粉丝列表（T7 后端有序分页 → T11-A 接公共 chunkedList） ----------
// 信封大小由后端 follow 域常量决定（200），请求**只传 `page`**；本地按 10 条小批消费，
// 本地余量足够时「加载更多」0 请求（N15 收敛点，helper 见 js/chunkedList.js）。
async function openUserList(type) {
  if (!state.profileUserId) return;
  state.listType = type;
  const c = state.container;
  c.querySelector('#sheetTitle').textContent = type === 'following' ? '关注' : '粉丝';
  c.querySelector('#sheetList').innerHTML = '<div class="sheet-empty">加载中...</div>';
  c.querySelector('#sheetMore').innerHTML = '';
  c.querySelector('#sheetOverlay').classList.remove('hidden');
  // 每次打开重建实例（等价 reset）：本地余量与已展示集清空，防 following/followers 串台
  state.sheetList = createChunkedList({
    fetchChunk: async (page) => request(
      `follow/${state.listType}?userId=${state.profileUserId}&page=${page}`),
    chunkSize: SHEET_CHUNK_SIZE,
    batchSize: SHEET_BATCH_SIZE,
    keyOf: (u) => u.userId,
  });
  try {
    const batch = await state.sheetList.nextBatch();
    renderUserList(batch, false);
    renderSheetMore();
  } catch (e) {
    c.querySelector('#sheetList').innerHTML = '<div class="sheet-empty">加载失败</div>';
    c.querySelector('#sheetMore').innerHTML = '';
  }
}

// 「加载更多」：本地余量足够则不发请求；不足才由 helper 拉下一个 chunk
async function loadMoreUserList(btn) {
  btn.disabled = true;
  btn.textContent = '加载中...';
  try {
    const batch = await state.sheetList.nextBatch();
    renderUserList(batch, true);
    renderSheetMore();
  } catch (e) {
    btn.disabled = false;
    btn.textContent = '加载更多';
    showToast('加载失败，请重试');
  }
}

function closeUserList() {
  state.container.querySelector('#sheetOverlay').classList.add('hidden');
}

function renderSheetMore() {
  const box = state.container.querySelector('#sheetMore');
  box.innerHTML = '';
  if (!state.sheetList || !state.sheetList.hasMore()) return; // 本地余量与服务器均耗尽
  const btn = document.createElement('button');
  btn.className = 'load-more-btn';
  btn.textContent = '加载更多';
  btn.addEventListener('click', () => loadMoreUserList(btn));
  box.appendChild(btn);
}

function renderUserList(users, append) {
  const box = state.container.querySelector('#sheetList');
  if (!append) {
    if (!users.length) { box.innerHTML = '<div class="sheet-empty">暂无数据</div>'; return; }
    box.innerHTML = '';
  }
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
