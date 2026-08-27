// ============================================================================
// 详情 #/video/:id —— 左播放器 + 标题/UP主/简介 + 评论区 + 右侧相关推荐(用 /start 兜底)
// ============================================================================

import { request } from '../api.js';
import { isLoggedIn, getUserId } from '../auth.js';
import { showToast, formatTime, formatNumber, emptyBox, initialChar, avatarColor } from '../utils.js';
import { navigate } from '../router.js';

let state = null;

export function mount(container, params) {
  state = {
    container,
    contentId: params.id,
    content: null,
    comments: [],
    related: [],
    parentId: null,
  };
  if (!state.contentId) {
    container.innerHTML = emptyBox('缺少内容 ID');
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
    <div class="detail">
      <div class="detail-main">
        <div class="player" id="player"></div>
        <div class="detail-gallery" id="gallery" style="display:none"></div>
        <div class="detail-title" id="title"></div>
        <div class="detail-stats" id="stats"></div>
        <div class="author-row">
          <div class="author-avatar" id="authorAvatar"></div>
          <span class="author-name" id="authorName"></span>
          <span class="author-spacer"></span>
          <button class="follow-btn unfollowed" id="followBtn" style="display:none">+ 关注</button>
        </div>
        <div class="detail-actions">
          <button class="like-btn" id="likeBtn">❤ 点赞 <span id="likeCount">0</span></button>
        </div>
        <div class="detail-desc" id="desc"></div>
        <div class="comment-section">
          <h3 id="commentHeader">评论</h3>
          <div class="comment-input-row" id="commentInputRow" style="display:none">
            <input class="input" id="commentInput" placeholder="发一条友善的评论" maxlength="500">
            <button class="btn-primary" id="sendBtn">发送</button>
          </div>
          <div class="comment-list" id="commentList"></div>
        </div>
      </div>
      <div class="detail-side">
        <div class="side-card">
          <div class="side-title">相关推荐</div>
          <div id="relatedList"><div class="empty"><div class="empty-msg">加载中...</div></div></div>
        </div>
      </div>
    </div>`;

  const input = state.container.querySelector('#commentInput');
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendComment(); }
  });
  state.container.querySelector('#sendBtn').addEventListener('click', sendComment);
}

async function init() {
  try {
    const [content, comments] = await Promise.all([
      request(`search/IdSearch?contentId=${state.contentId}`),
      request(`comment/show?contentId=${state.contentId}`),
    ]);
    state.content = content;
    state.comments = comments || [];
    renderContent();
    loadRelated();
  } catch (e) {
    if (e.code === 401 || e.code === 403) return;
    state.container.querySelector('#player').innerHTML = emptyBox(e.message || '加载失败，请刷新重试');
  }
}

async function loadRelated() {
  try {
    const list = await request('start');
    state.related = list || [];
  } catch (e) {
    state.related = [];
  }
  renderRelated();
}

function renderContent() {
  const c = state.container;
  const item = state.content;
  if (!item) return;

  // 媒体区
  const player = c.querySelector('#player');
  player.innerHTML = '';
  if (item.type === 1) {
    const video = document.createElement('video');
    video.src = item.videoUrl || '';
    video.controls = true;
    if (item.coverUrl) video.poster = item.coverUrl;
    video.addEventListener('error', () => {
      if (!item.coverUrl) player.innerHTML = emptyBox('视频加载失败');
    });
    player.appendChild(video);
  } else {
    // 图文：封面为主图，其余图进画廊（点缩略图切换主图）
    const images = [];
    if (item.coverUrl) images.push(item.coverUrl);
    if (item.imageUrls) images.push(...item.imageUrls);
    if (images.length) {
      const main = document.createElement('img');
      main.className = 'player-cover';
      main.src = images[0];
      main.alt = item.title || '';
      player.appendChild(main);
      if (images.length > 1) {
        const gallery = c.querySelector('#gallery');
        gallery.style.display = 'flex';
        gallery.innerHTML = '';
        images.forEach((url) => {
          const img = document.createElement('img');
          img.src = url;
          img.alt = '';
          img.loading = 'lazy';
          img.addEventListener('click', () => { main.src = url; });
          gallery.appendChild(img);
        });
      }
    }
  }

  c.querySelector('#title').textContent = item.title || '';
  c.querySelector('#stats').textContent = `❤ ${formatNumber(item.likeCount || 0)} · 💬 ${formatNumber(item.commentCount || 0)} · ${formatTime(item.createTime)}`;

  const authorName = item.authorName || '未知作者';
  const avatar = c.querySelector('#authorAvatar');
  avatar.textContent = initialChar(authorName);
  avatar.style.background = avatarColor(authorName);
  avatar.addEventListener('click', () => { if (item.authorId != null) navigate('/user/' + item.authorId); });
  const nameEl = c.querySelector('#authorName');
  nameEl.textContent = authorName;
  nameEl.addEventListener('click', () => { if (item.authorId != null) navigate('/user/' + item.authorId); });

  const followBtn = c.querySelector('#followBtn');
  if (item.authorId != null) {
    followBtn.style.display = '';
    updateFollowBtn();
    followBtn.addEventListener('click', toggleFollow);
  }

  const likeBtn = c.querySelector('#likeBtn');
  updateLikeBtn();
  c.querySelector('#likeCount').textContent = item.likeCount || 0;
  likeBtn.addEventListener('click', toggleContentLike);

  c.querySelector('#desc').textContent = item.description || '';

  // 评论区：作者关闭后整体不可见/不可发（数据保留，重新开启即恢复）
  if (item.commentEnabled === false) {
    c.querySelector('#commentInputRow').style.display = 'none';
    c.querySelector('#commentHeader').textContent = '评论区已关闭';
    c.querySelector('#commentList').innerHTML = '<div class="empty-comments">作者已关闭评论区</div>';
  } else {
    // 评论输入（登录后显示）
    if (isLoggedIn()) c.querySelector('#commentInputRow').style.display = '';
    renderComments();
  }
}

function updateFollowBtn() {
  const btn = state.container.querySelector('#followBtn');
  const followed = state.content.isFollowed === true;
  btn.textContent = followed ? '已关注' : '+ 关注';
  btn.className = 'follow-btn ' + (followed ? 'followed' : 'unfollowed');
}

function updateLikeBtn() {
  const btn = state.container.querySelector('#likeBtn');
  btn.classList.toggle('liked', state.content.isLiked === true);
}

function renderRelated() {
  const box = state.container.querySelector('#relatedList');
  if (!state.related.length) {
    box.innerHTML = '<div class="empty"><div class="empty-msg">暂无推荐</div></div>';
    return;
  }
  box.innerHTML = '';
  state.related.forEach((item) => box.appendChild(createSideItem(item)));
}

function createSideItem(item) {
  const el = document.createElement('div');
  el.className = 'side-item';
  el.addEventListener('click', () => navigate('/video/' + item.id));

  const thumb = document.createElement('div');
  thumb.className = 'thumb';
  const fb = document.createElement('div');
  fb.className = 'cover-fallback';
  fb.textContent = initialChar(item.title || item.authorName);
  fb.style.background = avatarColor(item.title || item.authorName);
  thumb.appendChild(fb);
  if (item.coverUrl) {
    const img = document.createElement('img');
    img.src = item.coverUrl; img.alt = ''; img.loading = 'lazy';
    img.addEventListener('error', () => img.remove());
    thumb.appendChild(img);
  }

  const info = document.createElement('div');
  info.className = 'side-info';
  const t = document.createElement('div');
  t.className = 't';
  t.textContent = item.title || '';
  const m = document.createElement('div');
  m.className = 'm';
  m.textContent = `${item.authorName || ''} · ${formatNumber(item.likeCount || 0)}赞`;

  info.appendChild(t); info.appendChild(m);
  el.appendChild(thumb); el.appendChild(info);
  return el;
}

// ---------- 评论渲染（楼中楼：主楼 + 楼内回复平铺，回复仅一级） ----------
function renderComments() {
  const c = state.container;
  const list = c.querySelector('#commentList');
  const count = countTotal(state.comments);
  c.querySelector('#commentHeader').textContent = '评论 (' + count + ')';
  list.innerHTML = '';
  if (!state.comments.length) {
    list.innerHTML = '<div class="empty-comments">暂无评论，快来抢沙发吧</div>';
    return;
  }
  state.comments.forEach((cm) => list.appendChild(createCommentItem(cm, false)));
}

function countTotal(list) {
  let n = list.length;
  list.forEach((c) => { if (c.children && c.children.length) n += countTotal(c.children); });
  return n;
}

function createCommentItem(comment, isReply) {
  const wrapper = document.createElement('div');
  wrapper.className = 'comment-item' + (isReply ? ' reply-indent' : '');

  const body = document.createElement('div');
  body.className = 'comment-body ' + (isReply ? 'level-1' : 'level-0');

  const nameWrap = document.createElement('span');
  nameWrap.style.cursor = 'pointer';
  nameWrap.addEventListener('click', () => { if (comment.userId != null) navigate('/user/' + comment.userId); });

  const avatar = document.createElement('span');
  avatar.className = 'comment-avatar';
  avatar.textContent = initialChar(comment.username);
  avatar.style.background = avatarColor(comment.username);

  const name = document.createElement('span');
  name.className = 'comment-username';
  name.textContent = comment.username || '匿名';
  nameWrap.appendChild(avatar);
  nameWrap.appendChild(name);

  const text = document.createElement('span');
  text.className = 'comment-text';
  text.textContent = comment.content || '';

  const meta = document.createElement('div');
  meta.className = 'comment-meta';
  const likeBtn = document.createElement('button');
  likeBtn.className = 'comment-like' + (comment.isLiked ? ' liked' : '');
  likeBtn.innerHTML = '❤ ' + (comment.likeCount || 0);
  likeBtn.addEventListener('click', () => toggleCommentLike(comment, likeBtn));
  meta.appendChild(likeBtn);
  if (!isReply) {
    const replyBtn = document.createElement('button');
    replyBtn.className = 'comment-reply-btn';
    replyBtn.textContent = '回复';
    replyBtn.addEventListener('click', () => setReply(comment));
    meta.appendChild(replyBtn);
  }
  if (comment.userId === getUserId()) {
    const delBtn = document.createElement('button');
    delBtn.className = 'comment-delete-btn';
    delBtn.textContent = '删除';
    delBtn.addEventListener('click', () => deleteComment(comment));
    meta.appendChild(delBtn);
  }

  body.appendChild(nameWrap);
  body.appendChild(text);
  body.appendChild(meta);
  wrapper.appendChild(body);

  // 主楼下方：楼内回复折叠列表
  if (!isReply && comment.children && comment.children.length) {
    const toggle = document.createElement('div');
    toggle.className = 'comment-replies-toggle';
    toggle.textContent = '共 ' + comment.children.length + ' 条回复 ▾';
    toggle.addEventListener('click', () => {
      const box = wrapper.querySelector('.comment-replies');
      const expanded = box.style.display !== 'none';
      box.style.display = expanded ? 'none' : 'block';
      toggle.textContent = '共 ' + comment.children.length + ' 条回复 ' + (expanded ? '▸' : '▾');
    });

    const repliesBox = document.createElement('div');
    repliesBox.className = 'comment-replies';
    repliesBox.style.display = 'none';
    comment.children.forEach((child) => repliesBox.appendChild(createCommentItem(child, true)));

    wrapper.appendChild(toggle);
    wrapper.appendChild(repliesBox);
  }
  return wrapper;
}

// ---------- 交互 ----------
function requireLogin() {
  if (!isLoggedIn()) { showToast('请先登录'); return false; }
  return true;
}

async function deleteComment(comment) {
  if (!requireLogin()) return;
  const removed = comment.parentId ? 1 : 1 + (comment.children || []).length;
  try {
    await request(`comment/delete?commentId=${comment.commentId}`, { method: 'POST' });
    showToast('删除成功');
    state.comments = await request(`comment/show?contentId=${state.contentId}`);
    if (state.content) {
      state.content.commentCount = Math.max(0, (state.content.commentCount || 0) - removed);
      state.container.querySelector('#stats').textContent =
        `❤ ${formatNumber(state.content.likeCount || 0)} · 💬 ${formatNumber(state.content.commentCount || 0)} · ${formatTime(state.content.createTime)}`;
    }
    renderComments();
  } catch (e) {
    showToast(e.message || '删除失败');
  }
}

async function toggleContentLike() {
  if (!requireLogin()) return;
  const item = state.content;
  const liked = item.isLiked;
  const action = liked ? 'remove' : 'add';
  item.isLiked = !liked;
  item.likeCount += liked ? -1 : 1;
  updateLikeBtn();
  state.container.querySelector('#likeCount').textContent = item.likeCount;
  state.container.querySelector('#stats').textContent = `❤ ${formatNumber(item.likeCount || 0)} · 💬 ${formatNumber(item.commentCount || 0)} · ${formatTime(item.createTime)}`;
  try {
    await request(`like/content/${action}?contentId=${state.contentId}`, { method: 'POST' });
  } catch (e) {
    item.isLiked = liked;
    item.likeCount += liked ? 1 : -1;
    updateLikeBtn();
    state.container.querySelector('#likeCount').textContent = item.likeCount;
    showToast(e.message || '操作失败');
  }
}

async function toggleCommentLike(comment, btn) {
  if (!requireLogin()) return;
  const liked = comment.isLiked;
  const action = liked ? 'remove' : 'add';
  comment.isLiked = !liked;
  comment.likeCount += liked ? -1 : 1;
  btn.className = 'comment-like' + (comment.isLiked ? ' liked' : '');
  btn.innerHTML = '❤ ' + comment.likeCount;
  try {
    await request(`like/comment/${action}?commentId=${comment.commentId}`, { method: 'POST' });
  } catch (e) {
    comment.isLiked = liked;
    comment.likeCount += liked ? 1 : -1;
    btn.className = 'comment-like' + (comment.isLiked ? ' liked' : '');
    btn.innerHTML = '❤ ' + comment.likeCount;
    showToast(e.message || '操作失败');
  }
}

async function toggleFollow() {
  if (!requireLogin()) return;
  const item = state.content;
  const followed = item.isFollowed;
  const action = followed ? 'remove' : 'add';
  item.isFollowed = !followed;
  updateFollowBtn();
  try {
    await request(`follow/${action}?followedUserId=${item.authorId}`, { method: 'POST' });
    showToast(followed ? '已取消关注' : '关注成功');
  } catch (e) {
    item.isFollowed = followed;
    updateFollowBtn();
    showToast(e.message || '操作失败');
  }
}

function setReply(comment) {
  if (!requireLogin()) return;
  state.parentId = comment.commentId;
  const input = state.container.querySelector('#commentInput');
  input.placeholder = '回复 @' + (comment.username || '匿名') + '：';
  input.focus();
}

async function sendComment() {
  if (!requireLogin()) return;
  const input = state.container.querySelector('#commentInput');
  const message = input.value.trim();
  if (!message) { showToast('评论内容不能为空'); return; }

  const body = { contentId: parseInt(state.contentId, 10), message };
  if (state.parentId) body.parentId = state.parentId;

  try {
    await request('comment/add', { jsonBody: body });
    input.value = '';
    input.placeholder = '发一条友善的评论';
    state.parentId = null;
    showToast('评论成功');
    state.comments = await request(`comment/show?contentId=${state.contentId}`);
    renderComments();
  } catch (e) {
    showToast(e.message || '评论失败');
  }
}
