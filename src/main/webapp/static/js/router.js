// ============================================================================
// 原生 hash 路由：#/video/123、#/search?kw=、#/user/:id
// 视图模块需提供 mount(container, params) / unmount() 生命周期；
// 切换时先调旧视图 unmount()，再 mount 新视图，容器 innerHTML 一并清空。
// ============================================================================

const routes = [];
let current = null;
let onNavigate = null;

export function register(pattern, view) {
  routes.push({ pattern, view, tokens: tokenize(pattern) });
}

export function navigate(path) {
  if (path[0] !== '#') path = '#' + path;
  if (location.hash === path) {
    resolve();
  } else {
    location.hash = path;
  }
}

export function setOnNavigate(fn) {
  onNavigate = fn;
}

export function start() {
  window.addEventListener('hashchange', resolve);
  resolve();
}

export function currentRoute() {
  return current;
}

function tokenize(pattern) {
  return pattern.split('/').filter(Boolean)
    .map((seg) => (seg.startsWith(':') ? { name: seg.slice(1) } : { lit: seg }));
}

function resolve() {
  const raw = location.hash || '#/';
  const full = raw.slice(1);
  const qIndex = full.indexOf('?');
  const pathPart = qIndex >= 0 ? full.slice(0, qIndex) : full;
  const queryPart = qIndex >= 0 ? full.slice(qIndex + 1) : '';
  const segs = pathPart.split('/').filter(Boolean);
  const query = parseQuery(queryPart);

  let matched = null;
  let params = {};
  for (const r of routes) {
    const m = match(segs, r.tokens);
    if (m) { matched = r; params = m; break; }
  }
  if (!matched) {
    matched = routes.find((r) => r.pattern === '/') || routes[0];
    params = {};
  }
  if (!matched) return;

  // 卸载旧视图
  if (current && current.view && typeof current.view.unmount === 'function') {
    try { current.view.unmount(); } catch (e) { console.error('[router] unmount 失败', e); }
  }

  const container = document.getElementById('app');
  container.innerHTML = '';
  current = { view: matched.view, pattern: matched.pattern, params, query };

  if (matched.view && typeof matched.view.mount === 'function') {
    matched.view.mount(container, { ...params, query });
  }

  if (onNavigate) onNavigate({ pattern: matched.pattern, params, query });
}

function match(segs, tokens) {
  if (segs.length !== tokens.length) return null;
  const params = {};
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (t.lit !== undefined) {
      if (t.lit !== segs[i]) return null;
    } else {
      params[t.name] = decodeURIComponent(segs[i]);
    }
  }
  return params;
}

function parseQuery(qs) {
  const out = {};
  if (!qs) return out;
  for (const pair of qs.split('&')) {
    if (!pair) continue;
    const idx = pair.indexOf('=');
    const k = idx >= 0 ? pair.slice(0, idx) : pair;
    const v = idx >= 0 ? pair.slice(idx + 1) : '';
    out[decodeURIComponent(k)] = decodeURIComponent(v);
  }
  return out;
}
