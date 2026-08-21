// ============================================================================
// 应用入口：渲染外壳（顶部导航 + 左抽屉）+ 注册路由 + 启动。
// 导航职责：
//   顶部导航 = 菜单按钮 + Logo + 分类下拉 + 搜索 + 头像
//   左侧抽屉 = 首页 / 动态 / 我的 / 创作中心 / 收藏 / 历史（默认隐藏）
//   分区（推荐/游戏/音乐…）收纳在顶部「分类」下拉，选中按分区过滤首页。
// ============================================================================

import { register, start, navigate, setOnNavigate } from './router.js';
import { isLoggedIn, getUsername, getUserId } from './auth.js';
import { initialChar, avatarColor, showToast } from './utils.js';
import * as home from './views/home.js';
import * as follow from './views/follow.js';
import * as detail from './views/detail.js';
import * as search from './views/search.js';
import * as user from './views/user.js';
import * as publish from './views/publish.js';
import * as login from './views/login.js';
import * as coupon from './views/coupon.js';
import * as admin from './views/admin.js';

const CATEGORIES = [
  { id: -1, name: '推荐' },
  { id: 0, name: '其他' },
  { id: 1, name: '游戏' },
  { id: 2, name: '音乐' },
  { id: 3, name: '资讯' },
  { id: 4, name: '动画' },
  { id: 5, name: '娱乐' },
  { id: 6, name: '动物' },
  { id: 7, name: '体育' },
  { id: 8, name: '鬼畜' },
  { id: 9, name: '绘画' },
];

// ---------- 内联图标（24×24 线性） ----------
const ICONS = {
  logo: '<svg viewBox="0 0 36 36"><rect x="2" y="3" width="32" height="30" rx="9" fill="currentColor"/><rect x="9" y="11" width="4" height="8" rx="2" fill="#fff"/><rect x="23" y="11" width="4" height="8" rx="2" fill="#fff"/><rect x="12.5" y="23" width="11" height="3" rx="1.5" fill="#fff"/></svg>',
  menu: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16M4 12h16M4 18h16"/></svg>',
  close: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>',
  chevron: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>',
  home: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/></svg>',
  feed: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 4 14h6l-1 8 9-12h-6l1-8z"/></svg>',
  user: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 4-6 8-6s8 2 8 6"/></svg>',
  publish: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="5"/><path d="M12 8v8M8 12h8"/></svg>',
  search: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg>',
  star: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M12 3.2l2.7 5.4 6 .9-4.3 4.2 1 6-5.4-2.8-5.4 2.8 1-6-4.3-4.2 6-.9L12 3.2z"/></svg>',
  history: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 3"/></svg>',
};

// ---------- 外壳渲染 ----------
function renderShell() {
  document.getElementById('topNav').innerHTML = `
    <button class="menu-btn" id="menuBtn" aria-label="菜单">${ICONS.menu}</button>
    <a class="logo" href="#/" aria-label="首页">
      <span class="logo-icon">${ICONS.logo}</span>
      <span class="logo-text">视频平台</span>
    </a>
    <div class="cat-menu" id="catMenu">
      <button class="cat-menu-btn" id="catMenuBtn">分类${ICONS.chevron}</button>
      <div class="cat-dropdown" id="catDropdown">
        ${CATEGORIES.map((c) => `<button class="cat-option" data-cat="${c.id}">${c.name}</button>`).join('')}
      </div>
    </div>
    <div class="search">
      ${ICONS.search}
      <input id="globalSearch" type="text" placeholder="搜索视频、图文" autocomplete="off">
    </div>
    <div class="top-actions">
      <div class="top-user" id="topUser"></div>
    </div>`;

  document.getElementById('sideDrawer').innerHTML = `
    <a class="drawer-item" href="#/" data-nav="home">${ICONS.home}<span class="drawer-label">首页</span></a>
    <a class="drawer-item" href="#/follow" data-nav="follow">${ICONS.feed}<span class="drawer-label">动态</span></a>
    <a class="drawer-item" href="#/user/me" data-nav="user">${ICONS.user}<span class="drawer-label">我的</span></a>
    <a class="drawer-item" href="#/publish" data-nav="publish">${ICONS.publish}<span class="drawer-label">创作中心</span></a>
    <a class="drawer-item disabled" data-nav="star">${ICONS.star}<span class="drawer-label">收藏</span></a>
    <a class="drawer-item disabled" data-nav="history">${ICONS.history}<span class="drawer-label">历史</span></a>`;

  refreshUserArea();
  bindShell();
}

function toggleDrawer(force) {
  const open = force != null ? force : !document.body.classList.contains('drawer-open');
  document.body.classList.toggle('drawer-open', open);
  document.getElementById('menuBtn').innerHTML = open ? ICONS.close : ICONS.menu;
}

function refreshUserArea() {
  const box = document.getElementById('topUser');
  if (!box) return;
  if (isLoggedIn()) {
    const name = getUsername() || '用户';
    const id = getUserId();
    const a = document.createElement('a');
    a.className = 'avatar';
    a.href = '#/user/' + (id != null ? id : 'me');
    a.title = name;
    a.style.background = avatarColor(name);
    a.textContent = initialChar(name);
    box.innerHTML = '';
    box.appendChild(a);
  } else {
    box.innerHTML = '<a class="login-link" href="#/login">登录</a>';
  }
}

function bindShell() {
  const catMenu = document.getElementById('catMenu');

  // 抽屉
  document.getElementById('menuBtn').addEventListener('click', () => toggleDrawer());
  document.getElementById('drawerBackdrop').addEventListener('click', () => toggleDrawer(false));
  document.getElementById('sideDrawer').addEventListener('click', (e) => {
    if (e.target.closest('.drawer-item')) toggleDrawer(false);
  });

  // 分类下拉
  document.getElementById('catMenuBtn').addEventListener('click', (e) => {
    e.stopPropagation();
    catMenu.classList.toggle('open');
  });
  document.getElementById('catDropdown').addEventListener('click', (e) => {
    const opt = e.target.closest('.cat-option');
    if (!opt) return;
    catMenu.classList.remove('open');
    const cat = opt.dataset.cat;
    navigate(cat === '-1' ? '/' : '/?cat=' + cat);
  });

  // 点击外部 / Esc 关闭浮层
  document.addEventListener('click', (e) => {
    if (!catMenu.contains(e.target)) catMenu.classList.remove('open');
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      toggleDrawer(false);
      catMenu.classList.remove('open');
    }
  });

  // 全局搜索
  document.getElementById('globalSearch').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      const kw = e.target.value.trim();
      if (kw) navigate('/search?kw=' + encodeURIComponent(kw));
    }
  });

  document.querySelectorAll('.drawer-item.disabled').forEach((el) => {
    el.addEventListener('click', () => showToast('功能开发中'));
  });
  window.addEventListener('auth:changed', refreshUserArea);
}

// ---------- 路由 ----------
function registerRoutes() {
  register('/', home);
  register('/follow', follow);
  register('/video/:id', detail);
  register('/search', search);
  register('/user/:id', user);
  register('/publish', publish);
  register('/login', login);
  register('/coupon', coupon);
  register('/admin', admin);
}

// ---------- 导航高亮 ----------
function onRouteChange({ pattern, query }) {
  window.scrollTo(0, 0);
  document.querySelectorAll('.drawer-item[data-nav]').forEach((el) => {
    const nav = el.dataset.nav;
    let active = false;
    if (nav === 'home') active = pattern === '/';
    else if (nav === 'follow') active = pattern === '/follow';
    else if (nav === 'user') active = pattern === '/user/:id';
    else if (nav === 'publish') active = pattern === '/publish';
    el.classList.toggle('active', active);
  });
  // 分类下拉高亮当前分区
  const cat = query.cat != null ? Number(query.cat) : -1;
  document.querySelectorAll('.cat-option').forEach((opt) => {
    opt.classList.toggle('active', Number(opt.dataset.cat) === cat);
  });
}

// ---------- 启动 ----------
renderShell();
registerRoutes();
setOnNavigate(onRouteChange);
start();
