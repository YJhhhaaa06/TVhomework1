// ============================================================================
// 详情 #/video/:id —— 左播放器 + 标题/UP主/简介 + 评论区 + 右侧相关推荐(用 /start 兜底)
// ============================================================================

import { request } from '../api.js';
import { isLoggedIn, getUserId } from '../auth.js';
import { showToast, formatTime, formatNumber, emptyBox, initialChar, avatarColor } from '../utils.js';
import { navigate } from '../router.js';
import { openEditWorkModal } from '../editWork.js';
import { createChunkedList } from '../chunkedList.js';

// T10-B：评论列表"大 chunk + 本地小批"——一次拉 CHUNK_SIZE 主楼，本地按 BATCH_SIZE 小批展示，
// 本地余量用尽才发下一次 chunk 请求（公共 helper createChunkedList，T11 复用）。
// T11-B：**请求只传 `page`**——信封大小由后端评论域常量（200）决定，CHUNK_SIZE 只作兜底值，
// 首次响应后用响应回显的 pageSize 自适应覆盖（见 chunkedList.js）。
const CHUNK_SIZE = 200;
const BATCH_SIZE = 10;

let state = null;

export function mount(container, params) {
  state = {
    container,
    contentId: params.id,
    content: null,
    commentList: null,      // T10-B：createChunkedList 实例（评论主楼大 chunk + 本地小批）
    displayedComments: 0,   // 已展示条数（本地小批累积）
    expandedRoots: {},      // T10-B：rootId → { replies, page, totalPages }（展开的楼中楼）
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
          <button class="edit-btn" id="editBtn" style="display:none">✏ 编辑</button>
        </div>
        <div class="detail-desc" id="desc"></div>
        <div class="comment-section">
          <h3 id="commentHeader">评论</h3>
          <div class="comment-input-row" id="commentInputRow" style="display:none">
            <input class="input" id="commentInput" placeholder="发一条友善的评论" maxlength="500">
            <button class="btn-primary" id="sendBtn">发送</button>
          </div>
          <div class="comment-list" id="commentList"></div>
          <div class="load-more" id="commentMore"></div>
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
    const [content] = await Promise.all([
      request(`search/IdSearch?contentId=${state.contentId}`),
      loadInitialComments(),
    ]);
    state.content = content;
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

  // 作者本人可编辑作品（改标题/简介 + 替换/删除媒体）；onclick 赋值避免重复渲染时监听叠加
  const editBtn = c.querySelector('#editBtn');
  if (item.authorId != null && item.authorId === getUserId()) {
    editBtn.style.display = '';
    editBtn.onclick = () => openEditWorkModal(item, reloadAfterEdit);
  } else {
    editBtn.style.display = 'none';
    editBtn.onclick = null;
  }

  c.querySelector('#desc').textContent = item.description || '';

  // 评论区：作者关闭后整体不可见/不可发（数据保留，重新开启即恢复）
  if (item.commentEnabled === false) {
    c.querySelector('#commentInputRow').style.display = 'none';
    c.querySelector('#commentHeader').textContent = '评论区已关闭';
    c.querySelector('#commentList').innerHTML = '<div class="empty-comments">作者已关闭评论区</div>';
    c.querySelector('#commentMore').innerHTML = '';
  } else {
    // 评论输入（登录后显示）
    if (isLoggedIn()) c.querySelector('#commentInputRow').style.display = '';
    renderComments();
  }
}

// 编辑作品完成后重新拉取详情并渲染
async function reloadAfterEdit() {
  try {
    state.content = await request(`search/IdSearch?contentId=${state.contentId}`);
    renderContent();
  } catch (e) {
    if (e.code === 401 || e.code === 403) return;
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

// ---------- 评论分页加载（T10-B：大 chunk + 本地小批，公共 chunkedList helper；N15 复用点） ----------
function ensureCommentList() {
  if (!state.commentList) {
    state.commentList = createChunkedList({
      // T11-B：只传 page（信封大小后端定，响应回显自适应）
      fetchChunk: async (page) => request(
        `comment/show?contentId=${state.contentId}&page=${page}`),
      chunkSize: CHUNK_SIZE,
      batchSize: BATCH_SIZE,
      // 评论 VO 同时含 userId（作者），必须显式按 commentId 去重（见 chunkedList.js keyOf 说明）
      keyOf: (c) => c.commentId,
    });
  }
  return state.commentList;
}

// 首次打开 / 发删评论后：重置列表并拉首批（本地小批展示）
async function loadInitialComments() {
  ensureCommentList().reset();
  state.comments = [];
  state.expandedRoots = {};
  await extendComments();
  renderComments();
}

// 拉下一小批：本地余量足够直接用，不足才发下一个 big chunk 请求
async function extendComments() {
  const list = ensureCommentList();
  const batch = await list.nextBatch();
  state.comments = state.comments.concat(batch);
  return batch;
}

async function loadMoreComments(btn) {
  btn.disabled = true;
  btn.textContent = '加载中...';
  try {
    await extendComments();
    renderComments();
  } catch (e) {
    btn.disabled = false;
    btn.textContent = '加载更多评论';
    showToast(e.message || '加载失败');
  }
}

function renderCommentLoadMore() {
  const box = state.container.querySelector('#commentMore');
  box.innerHTML = '';
  if (!ensureCommentList().hasMore()) return;
  const btn = document.createElement('button');
  btn.className = 'load-more-btn';
  btn.textContent = '加载更多评论';
  btn.addEventListener('click', () => loadMoreComments(btn));
  box.appendChild(btn);
}

// ---------- 评论渲染（T10-B：主楼 children 只带前 K 条 + replyCount 总数；展开时拉 /comment/replies） ----------
function renderComments() {
  const c = state.container;
  const list = c.querySelector('#commentList');
  // 计数用详情接口的 commentCount（含楼中楼的总评论数）：分页后只加载了部分主楼，
  // 不能再按"已加载条数"显示，否则数字会随加载页数增长、与详情统计不一致
  const count = (state.content && state.content.commentCount != null)
    ? state.content.commentCount : countTotal(state.comments);
  c.querySelector('#commentHeader').textContent = '评论 (' + count + ')';
  list.innerHTML = '';
  if (!state.comments.length) {
    list.innerHTML = '<div class="empty-comments">暂无评论，快来抢沙发吧</div>';
    renderCommentLoadMore();
    return;
  }
  state.comments.forEach((cm) => list.appendChild(createCommentItem(cm, false)));
  renderCommentLoadMore();
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
  if (isReply && comment.replyToUserId != null) {
    const at = document.createElement('span');
    at.className = 'comment-at';
    at.textContent = '回复 @' + (comment.replyToUsername || comment.replyToUserId) + '：';
    text.appendChild(at);
  }
  text.appendChild(document.createTextNode(comment.content || ''));

  const meta = document.createElement('div');
  meta.className = 'comment-meta';
  const likeBtn = document.createElement('button');
  likeBtn.className = 'comment-like' + (comment.isLiked ? ' liked' : '');
  likeBtn.innerHTML = '❤ ' + (comment.likeCount || 0);
  likeBtn.addEventListener('click', () => toggleCommentLike(comment, likeBtn));
  meta.appendChild(likeBtn);
  // 主楼与楼内回复均可回复（parentId 传该评论 id，后端自动上溯挂主楼）
  const replyBtn = document.createElement('button');
  replyBtn.className = 'comment-reply-btn';
  replyBtn.textContent = '回复';
  replyBtn.addEventListener('click', () => setReply(comment));
  meta.appendChild(replyBtn);
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

  // 主楼下方：楼内回复折叠列表（T10-B：children 只带前 K 条 + replyCount 总数；展开按需拉全）
  if (!isReply && (comment.children || []).length) {
    const total = Math.max(comment.replyCount || 0, (comment.children || []).length);
    const expanded = state.expandedRoots[comment.commentId]; // 已在展开：render 重建后按缓存数据重灌（L2）
    const toggle = document.createElement('div');
    toggle.className = 'comment-replies-toggle';
    toggle.textContent = '共 ' + total + ' 条回复 ' + (expanded ? '▾' : '▾');
    toggle.addEventListener('click', async () => {
      const box = wrapper.querySelector('.comment-replies');
      if (box.style.display !== 'none') { // 收起
        box.style.display = 'none';
        toggle.textContent = '共 ' + total + ' 条回复 ▸';
        return;
      }
      box.style.display = 'block';
      toggle.textContent = '共 ' + total + ' 条回复 ▾';
      // 首屏只带前 K 条：不足 total 且未拉过 → 点击展开拉取剩余（/comment/replies）
      if (state.expandedRoots[comment.commentId] || (comment.children || []).length >= total) return;
      toggle.textContent = '加载中...';
      try {
        await ensureRootExpanded(comment, wrapper);
        toggle.textContent = '共 ' + total + ' 条回复 ▾';
      } catch (e) {
        toggle.textContent = '共 ' + total + ' 条回复 ▾';
        showToast(e.message || '展开失败');
      }
    });

    const repliesBox = document.createElement('div');
    repliesBox.className = 'comment-replies';
    repliesBox.style.display = expanded ? 'block' : 'none';
    const seed = expanded ? (expanded.replies || []) : (comment.children || []);
    seed.forEach((child) => repliesBox.appendChild(createCommentItem(child, true)));

    wrapper.appendChild(toggle);
    wrapper.appendChild(repliesBox);
  }
  return wrapper;
}

// 展开主楼全部回复：分页拉 /comment/replies（页间与 children 前 K 重叠去重）并增量渲染；
// 结果存入 state.expandedRoots[rootId]={replies:[...]}，供 render 重建后重灌（review L2）
async function ensureRootExpanded(comment, wrapper) {
  const rootId = comment.commentId;
  const box = wrapper.querySelector('.comment-replies');
  const known = new Map();
  (comment.children || []).forEach((c) => known.set(c.commentId, c));
  let page = 1;
  for (;;) {
    // T11-B：只传 page（信封大小后端定 200，比原先硬传 50 少 4 倍往返，取齐结果不变）
    const data = await request(`comment/replies?rootId=${rootId}&page=${page}`);
    (data.list || []).forEach((r) => { known.set(r.commentId, r); });
    if (page >= (data.totalPages || 1)) break;
    page += 1;
  }
  if (!known.has(rootId) && known.size === 0) return; // 无任何行
  state.expandedRoots[rootId] = { replies: Array.from(known.values()) };
  box.innerHTML = '';
  state.expandedRoots[rootId].replies.forEach((c) => box.appendChild(createCommentItem(c, true)));
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
    // 删除后回到第 1 页重新累积（避免页码与已加载内容错位）
    await loadInitialComments();
    if (state.content) {
      state.content.commentCount = Math.max(0, (state.content.commentCount || 0) - removed);
      state.container.querySelector('#stats').textContent =
        `❤ ${formatNumber(state.content.likeCount || 0)} · 💬 ${formatNumber(state.content.commentCount || 0)} · ${formatTime(state.content.createTime)}`;
    }
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
    // 新评论进入主楼升序末位，回到第 1 页重新累积（与删除后同一处置）
    await loadInitialComments();
  } catch (e) {
    showToast(e.message || '评论失败');
  }
}
