// ============================================================================
// 优惠券中心 #/coupon
// 字段照抄旧 coupon.html 消费：可用券 id/title/stock/beginTime/endTime；
// 我的券 couponId/status(1=已抢到)/couponCode/createTime/title。
// ============================================================================

import { request } from '../api.js';
import { isLoggedIn } from '../auth.js';
import { showToast, escapeHtml, formatDateTime } from '../utils.js';

let state = null;

export function mount(container) {
  state = { container, tab: 'available', myIds: new Set() };
  render();
  if (isLoggedIn()) load();
  else container.querySelector('#contentAvailable').innerHTML = '<div class="empty"><div class="empty-icon">🔒</div><div class="empty-msg">请先登录</div></div>';
}

export function unmount() {
  state = null;
}

function render() {
  const c = state.container;
  c.innerHTML = `
    <div class="coupon">
      <div class="coupon-tabs">
        <button class="coupon-tab active" id="tabAvailable" data-tab="available">可抢优惠券</button>
        <button class="coupon-tab" id="tabMy" data-tab="my">我的优惠券</button>
      </div>
      <div class="coupon-list" id="contentAvailable"></div>
      <div class="coupon-list" id="contentMy" style="display:none"></div>
    </div>`;

  c.querySelectorAll('.coupon-tab').forEach((btn) => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab));
  });
}

function switchTab(tab) {
  state.tab = tab;
  const c = state.container;
  c.querySelectorAll('.coupon-tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  c.querySelector('#contentAvailable').style.display = tab === 'available' ? '' : 'none';
  c.querySelector('#contentMy').style.display = tab === 'my' ? '' : 'none';
}

async function load() {
  try {
    const [available, mine] = await Promise.all([
      request('coupon/list'),
      request('coupon/my'),
    ]);
    state.myIds = new Set((mine || []).map((c) => c.couponId));
    renderAvailable(available || []);
    renderMy(mine || []);
  } catch (e) {
    if (e.code === 401 || e.code === 403) return;
    state.container.querySelector('#contentAvailable').innerHTML = '<div class="empty"><div class="empty-msg">加载失败，请刷新重试</div></div>';
  }
}

function renderAvailable(coupons) {
  const box = state.container.querySelector('#contentAvailable');
  if (!coupons.length) { box.innerHTML = '<div class="empty"><div class="empty-msg">暂无可用优惠券</div></div>'; return; }
  box.innerHTML = coupons.map((c) => {
    const grabbed = state.myIds.has(c.id);
    const stockZero = c.stock <= 0;
    let cls = 'available', txt = '立即抢购';
    if (grabbed) { cls = 'grabbed'; txt = '已抢'; }
    else if (stockZero) { cls = 'grabbed'; txt = '已抢光'; }
    return `<div class="coupon-card">
      <div class="c-title">${escapeHtml(c.title)}</div>
      <div class="c-stock${stockZero ? ' zero' : ''}">库存：${c.stock}</div>
      <div class="c-time">${formatDateTime(c.beginTime)} ~ ${formatDateTime(c.endTime)}</div>
      <button class="grab-btn ${cls}" data-id="${c.id}" ${(grabbed || stockZero) ? 'disabled' : ''}>${txt}</button>
    </div>`;
  }).join('');

  box.querySelectorAll('.grab-btn:not([disabled])').forEach((btn) => {
    btn.addEventListener('click', () => doGrab(Number(btn.dataset.id)));
  });
}

function renderMy(coupons) {
  const box = state.container.querySelector('#contentMy');
  if (!coupons.length) { box.innerHTML = '<div class="empty"><div class="empty-msg">还没有抢到优惠券</div></div>'; return; }
  box.innerHTML = coupons.map((c) => {
    const used = c.status !== 1;
    return `<div class="coupon-card">
      <span class="c-status ${used ? 'used' : 'success'}">${used ? '已使用' : '已抢到'}</span>
      <div class="c-title">${escapeHtml(c.title)}</div>
      <div class="c-info">抢购时间：${formatDateTime(c.createTime)}</div>
      <div class="code-box">
        <span class="code-text">${escapeHtml(c.couponCode)}</span>
        <button class="copy-btn" data-code="${escapeHtml(c.couponCode)}">复制</button>
      </div>
    </div>`;
  }).join('');

  box.querySelectorAll('.copy-btn').forEach((btn) => {
    btn.addEventListener('click', () => copyCode(btn.dataset.code, btn));
  });
}

async function doGrab(couponId) {
  try {
    const code = await request('coupon/grab', { jsonBody: { couponId } });
    showToast('🎉 抢购成功！券码：' + code);
    await load();
  } catch (e) {
    showToast(e.message || '抢购失败');
  }
}

function copyCode(code, btn) {
  navigator.clipboard.writeText(code).then(() => {
    btn.textContent = '✓ 已复制';
    btn.classList.add('copied');
    setTimeout(() => { btn.textContent = '复制'; btn.classList.remove('copied'); }, 1500);
  }).catch(() => showToast('复制失败，请手动复制'));
}
