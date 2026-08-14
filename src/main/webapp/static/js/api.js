// ============================================================================
// 请求封装：token 头 + {code,msg,data} 解包 + 401 跳转
//
// 后端约定（务必照抄）：
//   - 鉴权头是自定义 `token`，不是 Authorization。
//   - 所有业务响应均为 HTTP 200 + JSON `{code, msg, data}`；
//     错误码 code ∈ {400,401,403,404,409,500}，401/403 也走 JSON，而非 HTTP 状态。
//   - request() 直接返回 data 载荷，业务错误抛 Error（携带 .code / .msg）。
// ============================================================================

import { getToken, clearAuth } from './auth.js';
import { showToast } from './utils.js';

export async function request(path, options = {}) {
  const opts = { ...options };
  opts.headers = { ...(options.headers || {}) };

  if (options.jsonBody !== undefined) {
    opts.method = opts.method || 'POST';
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(options.jsonBody);
  }

  const token = getToken();
  if (token) opts.headers['token'] = token;

  let res;
  try {
    res = await fetch(path, opts);
  } catch (e) {
    const err = new Error('网络错误，请重试');
    err.network = true;
    throw err;
  }

  // 真·HTTP 错误（404 页面、容器 5xx 等），非常规业务通道
  if (!res.ok) {
    const err = new Error(`请求失败（${res.status}）`);
    err.status = res.status;
    throw err;
  }

  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) {
    const body = await res.json();
    if (body.code === 200) return body.data;

    if (body.code === 401) {
      clearAuth();
      showToast('登录已过期，请重新登录');
      location.hash = '#/login';
    }
    const err = new Error(body.msg || '请求失败');
    err.code = body.code;
    throw err;
  }

  return await res.text();
}

export const api = { request };
