// ============================================================================
// 创作中心 #/publish —— 两个 tab：「我的投稿」列表 + 「投稿」上传（视频/图文+裁剪）
// 我的投稿列表用 /profile?userId=<me> 获取本人内容（contentPage）。
// ============================================================================

import { request } from '../api.js';
import { isLoggedIn, getUserId } from '../auth.js';
import { showToast, formatSize, createVideoCard, emptyBox, skeletonCards } from '../utils.js';

const CATEGORIES = ['其他', '游戏', '音乐', '资讯', '动画', '娱乐', '动物', '体育', '鬼畜', '绘画'];

let state = null;
let crop = null;

export function mount(container) {
  if (!isLoggedIn()) {
    container.innerHTML = '<div class="empty"><div class="empty-icon">🔒</div><div class="empty-msg">请先登录后再进入创作中心</div><a class="btn-primary" href="#/login">去登录</a></div>';
    state = { container, locked: true };
    return;
  }
  state = {
    container, locked: false, tab: 'mine',
    type: 1, category: 0, videoFile: null, coverFile: null, imageFiles: [],
    myPage: 1, myTotalPages: 0,
  };
  crop = { scale: 1, offX: 0, offY: 0, natW: 0, natH: 0, originalName: 'cover.jpg' };
  render();
  loadMyContent(1);
}

export function unmount() {
  state = null;
  crop = null;
}

function render() {
  const c = state.container;
  c.innerHTML = `
    <div class="publish">
      <div class="type-tabs" id="ccTabs">
        <button class="type-tab active" data-tab="mine">我的投稿</button>
        <button class="type-tab" data-tab="upload">投稿</button>
      </div>

      <div id="minePane">
        <div class="home-content" id="myGrid"></div>
        <div class="load-more" id="myLoadMore"></div>
      </div>

      <div id="uploadPane" style="display:none">
        <div class="type-tabs" style="margin-bottom:14px">
          <button class="type-tab active" id="tabVideo" data-type="1">视频</button>
          <button class="type-tab" id="tabPost" data-type="2">图文</button>
        </div>
        <div class="publish-section">
          <div class="section-label">标题 <span style="color:#fb7299">*</span></div>
          <input class="input" id="title" placeholder="输入标题（最多50字）" maxlength="50">
        </div>
        <div class="publish-section">
          <div class="section-label">分区</div>
          <div class="cat-grid" id="catGrid"></div>
        </div>
        <div class="publish-section">
          <div class="section-label">描述</div>
          <textarea class="input" id="desc" placeholder="添加简介..." style="min-height:100px;resize:vertical"></textarea>
        </div>
        <div class="publish-section" id="fileSection"></div>
        <button class="submit-btn" id="submitBtn">发 布</button>
      </div>
    </div>

    <div class="crop-overlay hidden" id="cropOverlay">
      <div class="crop-hint">拖拽移动图片 · 滚轮缩放 · 框内为封面展示区域</div>
      <div class="crop-container" id="cropContainer"><img id="cropImg" alt=""><div class="crop-frame" id="cropFrame"></div></div>
      <div class="crop-actions">
        <button class="btn-cancel" id="cropCancel">取消</button>
        <button class="btn-confirm" id="cropConfirm">确认裁剪</button>
      </div>
    </div>`;

  c.querySelectorAll('#ccTabs .type-tab').forEach((b) => b.addEventListener('click', () => switchTab(b.dataset.tab)));
  c.querySelectorAll('#uploadPane .type-tab').forEach((b) => b.addEventListener('click', () => switchType(Number(b.dataset.type))));
  buildCategoryGrid();
  renderFileSection();
  c.querySelector('#submitBtn').addEventListener('click', doPublish);

  // 裁剪弹层事件
  c.querySelector('#cropCancel').addEventListener('click', cancelCrop);
  c.querySelector('#cropConfirm').addEventListener('click', confirmCrop);
  const cropContainer = c.querySelector('#cropContainer');
  cropContainer.addEventListener('mousedown', onCropDragStart);
  cropContainer.addEventListener('wheel', onCropWheel, { passive: false });
  cropContainer.addEventListener('touchstart', onCropTouchStart, { passive: false });
}

function switchTab(tab) {
  state.tab = tab;
  const c = state.container;
  c.querySelectorAll('#ccTabs .type-tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  c.querySelector('#minePane').style.display = tab === 'mine' ? '' : 'none';
  c.querySelector('#uploadPane').style.display = tab === 'upload' ? '' : 'none';
  if (tab === 'mine') loadMyContent(1);
}

// ---------- 我的投稿 ----------
async function loadMyContent(page) {
  const grid = state.container.querySelector('#myGrid');
  if (page === 1) grid.innerHTML = '<div class="grid">' + skeletonCards(6) + '</div>';
  try {
    const data = await request(`profile?userId=${getUserId()}&page=${page}&pageSize=12`);
    const cp = data.contentPage;
    const list = cp ? (cp.list || []) : [];
    state.myTotalPages = cp ? cp.totalPages : 0;
    state.myPage = page;
    if (page === 1) renderMyList(list);
    else appendMyList(list);
    renderMyLoadMore();
  } catch (e) {
    if (e.code === 401 || e.code === 403) return;
    if (page === 1) grid.innerHTML = emptyBox('加载失败，请刷新重试');
  }
}

function renderMyList(list) {
  const grid = state.container.querySelector('#myGrid');
  grid.innerHTML = '';
  if (!list.length) {
    grid.innerHTML = '<div class="empty"><div class="empty-icon">📝</div><div class="empty-msg">还没有投稿内容</div><button class="btn-primary" id="goUpload">去投稿</button></div>';
    grid.querySelector('#goUpload').addEventListener('click', () => switchTab('upload'));
    return;
  }
  const g = document.createElement('div');
  g.className = 'grid';
  list.forEach((item, i) => {
    const card = createVideoCard(item, { index: i });
    addCommentToggle(card, item);
    g.appendChild(card);
  });
  grid.appendChild(g);
}

function appendMyList(list) {
  const g = state.container.querySelector('#myGrid .grid');
  if (g) {
    list.forEach((item) => {
      const card = createVideoCard(item);
      addCommentToggle(card, item);
      g.appendChild(card);
    });
  }
}

/** 我的投稿卡片上的「关闭/开启评论区」按钮（仅作者本人可见的本页自己作品） */
function addCommentToggle(card, item) {
  const toggle = document.createElement('button');
  toggle.className = 'v-card-toggle';
  toggle.textContent = item.commentEnabled === false ? '开启评论区' : '关闭评论区';
  toggle.addEventListener('click', async (e) => {
    e.stopPropagation(); // 卡片本身点击跳详情，按钮不触发跳转
    const enabled = item.commentEnabled === false ? 1 : 0;
    try {
      await request(`content/commentEnabled?contentId=${item.id}&enabled=${enabled}`, { method: 'POST' });
      item.commentEnabled = enabled === 1;
      toggle.textContent = item.commentEnabled === false ? '开启评论区' : '关闭评论区';
      showToast(enabled === 1 ? '评论区已开启' : '评论区已关闭');
    } catch (err) {
      showToast(err.message || '操作失败');
    }
  });
  card.appendChild(toggle);
}

function renderMyLoadMore() {
  const box = state.container.querySelector('#myLoadMore');
  box.innerHTML = '';
  if (state.myPage < state.myTotalPages) {
    const btn = document.createElement('button');
    btn.className = 'load-more-btn';
    btn.textContent = '加载更多';
    btn.addEventListener('click', () => loadMyContent(state.myPage + 1));
    box.appendChild(btn);
  }
}

// ---------- 投稿 ----------
function switchType(type) {
  state.type = type;
  const c = state.container;
  c.querySelector('#tabVideo').classList.toggle('active', type === 1);
  c.querySelector('#tabPost').classList.toggle('active', type === 2);
  renderFileSection();
}

function buildCategoryGrid() {
  const grid = state.container.querySelector('#catGrid');
  grid.innerHTML = '';
  CATEGORIES.forEach((name, idx) => {
    const btn = document.createElement('button');
    btn.className = 'cat-btn' + (idx === state.category ? ' active' : '');
    btn.textContent = name;
    btn.addEventListener('click', () => {
      state.category = idx;
      grid.querySelectorAll('.cat-btn').forEach((b, i) => b.classList.toggle('active', i === idx));
    });
    grid.appendChild(btn);
  });
}

function renderFileSection() {
  const section = state.container.querySelector('#fileSection');
  if (state.type === 1) {
    section.innerHTML = `
      <div class="section-label">视频文件 <span style="color:#fb7299">*</span></div>
      <div class="file-row"><label for="videoInput">选择视频</label><input type="file" id="videoInput" accept="video/*"><span class="file-name" id="videoName"></span></div>
      <div class="section-label" style="margin-top:12px">封面图片 <span style="color:#fb7299">*</span></div>
      <div class="file-row"><label for="coverInput">选择封面</label><input type="file" id="coverInput" accept="image/*"><span class="file-name" id="coverName"></span></div>
      <div class="preview" id="coverPreview"></div>`;
    section.querySelector('#videoInput').addEventListener('change', onVideoChange);
    section.querySelector('#coverInput').addEventListener('change', onCoverChange);
  } else {
    section.innerHTML = `
      <div class="section-label">封面图片 <span style="font-weight:400;color:#9499a0">(可选)</span></div>
      <div class="file-row"><label for="coverInput">选择封面</label><input type="file" id="coverInput" accept="image/*"><span class="file-name" id="coverName"></span></div>
      <div class="preview" id="coverPreview"></div>
      <div class="section-label" style="margin-top:12px">图片</div>
      <div class="preview" id="imagePreview"></div>`;
    section.querySelector('#coverInput').addEventListener('change', onCoverChange);
    buildImagePreview();
  }
}

function onVideoChange(e) {
  const file = e.target.files[0];
  if (!file) return;
  state.videoFile = file;
  state.container.querySelector('#videoName').textContent = file.name + ' (' + formatSize(file.size) + ')';
}

function onCoverChange(e) {
  const file = e.target.files[0];
  if (!file) return;
  state.container.querySelector('#coverName').textContent = file.name;
  const url = URL.createObjectURL(file);
  const preload = new Image();
  preload.onload = () => {
    crop.natW = preload.naturalWidth;
    crop.natH = preload.naturalHeight;
    crop.originalName = file.name;
    openCropModal(url);
  };
  preload.src = url;
}

// ---------- 裁剪 ----------
function openCropModal(imgUrl) {
  const c = state.container;
  c.querySelector('#cropOverlay').classList.remove('hidden');
  const container = c.querySelector('#cropContainer');
  const img = c.querySelector('#cropImg');
  const frame = c.querySelector('#cropFrame');
  img.src = imgUrl;
  img.onload = () => {
    const cw = container.clientWidth;
    const fw = cw * 0.8;
    const fh = fw * 9 / 16;
    frame.style.width = fw + 'px';
    frame.style.height = fh + 'px';
    const sw = fw / crop.natW;
    const sh = fh / crop.natH;
    crop.scale = Math.max(sw, sh);
    const iw = crop.natW * crop.scale;
    const ih = crop.natH * crop.scale;
    img.style.width = iw + 'px';
    img.style.height = ih + 'px';
    crop.offX = (cw - iw) / 2;
    crop.offY = (container.clientHeight - ih) / 2;
    applyCropPos(img);
  };
}

function applyCropPos(img) {
  img.style.left = crop.offX + 'px';
  img.style.top = crop.offY + 'px';
  img.style.width = (crop.natW * crop.scale) + 'px';
  img.style.height = (crop.natH * crop.scale) + 'px';
}

function onCropDragStart(e) {
  if (state.container.querySelector('#cropOverlay').classList.contains('hidden')) return;
  e.preventDefault();
  let dragging = true;
  const startX = e.clientX - crop.offX;
  const startY = e.clientY - crop.offY;
  const img = state.container.querySelector('#cropImg');
  const onMove = (ev) => {
    if (!dragging) return;
    crop.offX = ev.clientX - startX;
    crop.offY = ev.clientY - startY;
    applyCropPos(img);
  };
  const onUp = () => {
    dragging = false;
    document.removeEventListener('mousemove', onMove);
    document.removeEventListener('mouseup', onUp);
  };
  document.addEventListener('mousemove', onMove);
  document.addEventListener('mouseup', onUp);
}

function onCropTouchStart(e) {
  if (state.container.querySelector('#cropOverlay').classList.contains('hidden')) return;
  if (e.touches.length !== 1) return;
  e.preventDefault();
  const t = e.touches[0];
  let dragging = true;
  const startX = t.clientX - crop.offX;
  const startY = t.clientY - crop.offY;
  const img = state.container.querySelector('#cropImg');
  const onMove = (ev) => {
    if (!dragging) return;
    const tt = ev.touches[0];
    crop.offX = tt.clientX - startX;
    crop.offY = tt.clientY - startY;
    applyCropPos(img);
  };
  const onUp = () => {
    dragging = false;
    document.removeEventListener('touchmove', onMove);
    document.removeEventListener('touchend', onUp);
  };
  document.addEventListener('touchmove', onMove, { passive: false });
  document.addEventListener('touchend', onUp);
}

function onCropWheel(e) {
  if (state.container.querySelector('#cropOverlay').classList.contains('hidden')) return;
  e.preventDefault();
  const container = state.container.querySelector('#cropContainer');
  const img = state.container.querySelector('#cropImg');
  const rect = container.getBoundingClientRect();
  const mx = e.clientX - rect.left;
  const my = e.clientY - rect.top;
  const old = crop.scale;
  crop.scale = Math.max(0.3, Math.min(3, crop.scale + (e.deltaY > 0 ? -0.1 : 0.1)));
  const ratio = crop.scale / old;
  crop.offX = mx - (mx - crop.offX) * ratio;
  crop.offY = my - (my - crop.offY) * ratio;
  applyCropPos(img);
}

function confirmCrop() {
  const c = state.container;
  const container = c.querySelector('#cropContainer');
  const img = c.querySelector('#cropImg');
  const frame = c.querySelector('#cropFrame');
  const containerRect = container.getBoundingClientRect();
  const frameRect = frame.getBoundingClientRect();
  const fx = frameRect.left - containerRect.left;
  const fy = frameRect.top - containerRect.top;
  const fw = frameRect.width;
  const fh = frameRect.height;
  const sx = (fx - crop.offX) / crop.scale;
  const sy = (fy - crop.offY) / crop.scale;
  const sw = fw / crop.scale;
  const sh = fh / crop.scale;
  const canvas = document.createElement('canvas');
  canvas.width = fw;
  canvas.height = fh;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, fw, fh);
  ctx.drawImage(img, sx, sy, sw, sh, 0, 0, fw, fh);
  canvas.toBlob((blob) => {
    state.coverFile = new File([blob], crop.originalName || 'cover.jpg', { type: 'image/jpeg' });
    showImagePreview(state.coverFile, 'coverPreview');
    c.querySelector('#cropOverlay').classList.add('hidden');
    URL.revokeObjectURL(img.src);
  }, 'image/jpeg', 0.9);
}

function cancelCrop() {
  const c = state.container;
  const img = c.querySelector('#cropImg');
  c.querySelector('#cropOverlay').classList.add('hidden');
  URL.revokeObjectURL(img.src);
  const coverInput = c.querySelector('#coverInput');
  coverInput.value = '';
  state.coverFile = null;
  c.querySelector('#coverName').textContent = '';
  c.querySelector('#coverPreview').innerHTML = '';
}

// ---------- 图文多图 ----------
function buildImagePreview() {
  const container = state.container.querySelector('#imagePreview');
  if (!container) return;
  container.innerHTML = '';
  state.imageFiles.forEach((file, idx) => {
    const item = document.createElement('div');
    item.className = 'preview-item';
    const img = document.createElement('img');
    const url = URL.createObjectURL(file);
    img.src = url;
    img.alt = '';
    const rm = document.createElement('button');
    rm.className = 'remove';
    rm.textContent = '✕';
    rm.addEventListener('click', () => {
      state.imageFiles.splice(idx, 1);
      URL.revokeObjectURL(url);
      buildImagePreview();
    });
    item.appendChild(img);
    item.appendChild(rm);
    container.appendChild(item);
  });

  const add = document.createElement('div');
  add.className = 'add-img-btn';
  add.textContent = '+';
  add.addEventListener('click', () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.multiple = true;
    input.addEventListener('change', (e) => {
      state.imageFiles.push(...Array.from(e.target.files));
      buildImagePreview();
    });
    input.click();
  });
  container.appendChild(add);
}

function showImagePreview(file, containerId) {
  const container = state.container.querySelector('#' + containerId);
  if (!container) return;
  container.innerHTML = '';
  const item = document.createElement('div');
  item.className = 'preview-item';
  const img = document.createElement('img');
  img.src = URL.createObjectURL(file);
  img.alt = '';
  item.appendChild(img);
  container.appendChild(item);
}

// ---------- 发布 ----------
async function doPublish() {
  const c = state.container;
  const title = c.querySelector('#title').value.trim();
  const desc = c.querySelector('#desc').value.trim();

  if (!title) { showToast('请输入标题'); return; }
  if (title.length > 50) { showToast('标题不超过50字'); return; }
  if (state.type === 1) {
    if (!state.videoFile) { showToast('请选择视频文件'); return; }
    if (!state.coverFile) { showToast('请选择封面图片'); return; }
  }

  const fd = new FormData();
  fd.append('title', title);
  fd.append('description', desc || '-');
  fd.append('categoryId', String(state.category));

  let url;
  if (state.type === 1) {
    fd.append('video', state.videoFile);
    fd.append('cover', state.coverFile);
    url = 'api/upload/video';
  } else {
    if (state.coverFile) fd.append('cover', state.coverFile);
    state.imageFiles.forEach((f) => fd.append('image', f));
    url = 'api/upload/post';
  }

  const btn = c.querySelector('#submitBtn');
  btn.disabled = true;
  btn.textContent = '发布中...';
  try {
    await request(url, { method: 'POST', body: fd });
    showToast('发布成功');
    resetUploadForm();
    switchTab('mine');
  } catch (e) {
    showToast(e.message || '发布失败');
  } finally {
    btn.disabled = false;
    btn.textContent = '发 布';
  }
}

function resetUploadForm() {
  state.type = 1;
  state.category = 0;
  state.videoFile = null;
  state.coverFile = null;
  state.imageFiles = [];
  const c = state.container;
  c.querySelector('#title').value = '';
  c.querySelector('#desc').value = '';
  c.querySelector('#tabVideo').classList.add('active');
  c.querySelector('#tabPost').classList.remove('active');
  buildCategoryGrid();
  renderFileSection();
}
