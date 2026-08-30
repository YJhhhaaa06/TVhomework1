// ============================================================================
// 编辑作品弹层（共享模块）：作者在创作中心/详情页直接编辑作品，无需重新上传
//   - 文案区：标题 + 简介，保存走 POST /content/update
//   - 媒体区：展示已上传媒体（缩略图），每项「替换」走 POST /api/upload/replace；
//     图文图片（type=2）额外支持「删除」走 POST /content/mediaDelete
// 依赖：api.js 的 request、utils.js 的 showToast/escapeHtml
// 调用：openEditWorkModal(content, { onDone })
//   content 需为全量内容（id/type/title/description/coverUrl/videoUrl/imageUrls/authorId）
// ============================================================================

import { request } from './api.js';
import { showToast, escapeHtml } from './utils.js';

const MEDIA_ACCEPT = { 1: 'video/*', 2: 'image/*', 3: 'image/*' };

export function openEditWorkModal(content, onDone) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.innerHTML = `
    <div class="modal edit-work-modal">
      <div class="edit-work-head">
        <span class="edit-work-title">编辑作品</span>
        <button class="sheet-close" type="button">✕</button>
      </div>
      <div class="edit-work-body">
        <div class="edit-work-section">
          <div class="section-label">标题</div>
          <input class="input ew-title" maxlength="50" value="">
          <div class="section-label">简介</div>
          <textarea class="input ew-desc" placeholder="输入简介..." style="min-height:90px;resize:vertical"></textarea>
          <button class="btn-confirm ew-save" type="button">保 存</button>
        </div>
        <div class="edit-work-section">
          <div class="section-label">媒体</div>
          <div class="ew-media-list"></div>
        </div>
      </div>
    </div>`;
  document.body.appendChild(overlay);

  const titleInput = overlay.querySelector('.ew-title');
  const descInput = overlay.querySelector('.ew-desc');
  const mediaList = overlay.querySelector('.ew-media-list');
  titleInput.value = content.title || '';
  descInput.value = content.description || '';

  overlay.querySelector('.sheet-close').addEventListener('click', close);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
  overlay.querySelector('.ew-save').addEventListener('click', saveText);

  renderMediaList(content);

  async function saveText() {
    const title = titleInput.value.trim();
    const description = descInput.value.trim();
    if (!title) { showToast('标题不能为空'); return; }
    if (title.length > 50) { showToast('标题不超过50字'); return; }
    const btn = overlay.querySelector('.ew-save');
    btn.disabled = true;
    try {
      await request('content/update', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ contentId: content.id, title, description }),
      });
      showToast('保存成功');
      content.title = title;
      content.description = description;
      if (typeof onDone === 'function') onDone();
    } catch (e) {
      showToast(e.message || '保存失败');
    } finally {
      btn.disabled = false;
    }
  }

  function renderMediaList(c) {
    const targets = buildTargets(c);
    mediaList.innerHTML = '';
    if (!targets.length) {
      mediaList.innerHTML = '<div class="ew-empty">暂无媒体</div>';
      return;
    }
    targets.forEach((t) => mediaList.appendChild(buildMediaRow(c, t)));
  }

  // 按内容类型推导可编辑媒体项：视频=视频文件+封面；图文=封面(若有)+每张图(type=2, sort=i+1)
  function buildTargets(c) {
    const list = [];
    if (c.type === 1) {
      list.push({ label: '视频文件', type: 1, sort: 1, thumb: c.coverUrl || null, isVideo: true });
      list.push({ label: '封面', type: 3, sort: 1, thumb: c.coverUrl || null });
    } else if (c.type === 2) {
      if (c.coverUrl) list.push({ label: '封面', type: 3, sort: 1, thumb: c.coverUrl });
      (c.imageUrls || []).forEach((u, i) => list.push({ label: '图片 ' + (i + 1), type: 2, sort: i + 1, thumb: u, deletable: true }));
    }
    return list;
  }

  function buildMediaRow(c, t) {
    const row = document.createElement('div');
    row.className = 'ew-media-row';
    row.innerHTML = `
      <div class="ew-media-thumb">${thumbHtml(t)}</div>
      <div class="ew-media-info">
        <div class="ew-media-label">${escapeHtml(t.label)}</div>
        <div class="ew-media-actions">
          <button class="ew-replace" type="button">替换</button>
          ${t.deletable ? '<button class="ew-delete" type="button">删除</button>' : ''}
        </div>
      </div>`;

    row.querySelector('.ew-replace').addEventListener('click', () => chooseFile(c, t, row.querySelector('.ew-replace')));
    if (t.deletable) {
      row.querySelector('.ew-delete').addEventListener('click', () => doDelete(c, t, row.querySelector('.ew-delete')));
    }
    return row;
  }

  function thumbHtml(t) {
    if (t.thumb) {
      return `<img src="${escapeHtml(t.thumb)}" alt="" loading="lazy">${t.isVideo ? '<span class="ew-play">▶</span>' : ''}`;
    }
    return `<span class="ew-no-thumb">${t.isVideo ? '视频' : '无预览'}</span>`;
  }

  // 替换：选文件即上传
  function chooseFile(c, t, btn) {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = MEDIA_ACCEPT[t.type] || '';
    input.addEventListener('change', () => {
      const file = input.files && input.files[0];
      if (!file) return;
      btn.disabled = true;
      btn.textContent = '处理中...';
      const fd = new FormData();
      fd.append('contentId', String(c.id));
      fd.append('type', String(t.type));
      fd.append('sort', String(t.sort));
      fd.append('file', file);
      request('api/upload/replace', { method: 'POST', body: fd })
        .then(() => { showToast('替换成功'); return refreshContent(); })
        .catch((e) => showToast(e.message || '替换失败'))
        .finally(() => { btn.disabled = false; btn.textContent = '替换'; });
    });
    input.click();
  }

  // 删除（仅图文图片）：确认后删除，删除后 sort 由后端重排
  function doDelete(c, t, btn) {
    if (!window.confirm(`确定删除「${t.label}」吗？删除后不可恢复`)) return;
    btn.disabled = true;
    btn.textContent = '处理中...';
    request(`content/mediaDelete?contentId=${c.id}&type=${t.type}&sort=${t.sort}`, { method: 'POST' })
      .then(() => { showToast('删除成功'); return refreshContent(); })
      .catch((e) => showToast(e.message || '删除失败'))
      .finally(() => { btn.disabled = false; btn.textContent = '删除'; });
  }

  // 换源/删除后重新拉取内容，重建媒体区（文案输入保持用户未保存的编辑）；同时通知调用方刷新
  async function refreshContent() {
    try {
      const fresh = await request(`search/IdSearch?contentId=${content.id}`);
      if (fresh) {
        content = fresh;
        renderMediaList(fresh);
      }
    } catch (e) { /* 拉取失败不阻塞，弹层仍可关闭 */ }
    if (typeof onDone === 'function') onDone();
  }

  function close() {
    overlay.remove();
  }
}
