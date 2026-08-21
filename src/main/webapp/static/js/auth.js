// ============================================================================
// 鉴权：token / username / userId 存取、登录态判断、JWT 解码
// 后端约定：登录返回 { token, username, id }；userId 亦可从 JWT 的 sub 解码。
// ============================================================================

const TOKEN_KEY = 'userToken';
const USERNAME_KEY = 'username';
const USERID_KEY = 'userId';

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

export function getUsername() {
  return localStorage.getItem(USERNAME_KEY) || '';
}

export function getUserId() {
  const stored = localStorage.getItem(USERID_KEY);
  if (stored) {
    const n = parseInt(stored, 10);
    if (!Number.isNaN(n)) return n;
  }
  // 兼容旧数据（仅存 token/username，未存 id）
  return parseUserId(getToken());
}

export function isLoggedIn() {
  return !!getToken();
}

/** 从 JWT 的 sub 字段解码 userId；失败返回 null */
export function parseUserId(token) {
  if (!token) return null;
  try {
    const seg = token.split('.')[1];
    if (!seg) return null;
    const obj = JSON.parse(base64UrlDecode(seg));
    return obj && obj.sub != null ? parseInt(obj.sub, 10) : null;
  } catch (e) {
    return null;
  }
}

function base64UrlDecode(str) {
  let s = str.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4) s += '=';
  return atob(s);
}

export function setAuth(token, username, id) {
  localStorage.setItem(TOKEN_KEY, token || '');
  localStorage.setItem(USERNAME_KEY, username || '');
  if (id != null) localStorage.setItem(USERID_KEY, String(id));
  else localStorage.removeItem(USERID_KEY);
  window.dispatchEvent(new CustomEvent('auth:changed'));
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USERNAME_KEY);
  localStorage.removeItem(USERID_KEY);
  window.dispatchEvent(new CustomEvent('auth:changed'));
}
