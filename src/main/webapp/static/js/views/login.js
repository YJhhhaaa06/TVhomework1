// ============================================================================
// 登录 / 注册 #/login
// ============================================================================

import { request } from '../api.js';
import { setAuth } from '../auth.js';
import { showToast } from '../utils.js';
import { navigate } from '../router.js';

let state = null;

export function mount(container) {
  state = { container, tab: 'login' };
  render();
}

export function unmount() {
  state = null;
}

function render() {
  const c = state.container;
  c.innerHTML = `
    <div class="auth">
      <div class="auth-card">
        <div class="auth-logo">视频平台</div>
        <div class="auth-tabs">
          <button class="auth-tab active" data-tab="login">登录</button>
          <button class="auth-tab" data-tab="register">注册</button>
        </div>
        <form class="auth-form" id="loginForm">
          <input class="input" id="loginAccount" placeholder="手机号 或 用户ID" autocomplete="username">
          <input class="input" id="loginPwd" type="password" placeholder="密码" autocomplete="current-password">
          <button class="btn-primary btn-block" id="loginBtn" type="submit">登录</button>
        </form>
        <form class="auth-form" id="regForm" style="display:none">
          <input class="input" id="regUsername" placeholder="用户名" autocomplete="off">
          <input class="input" id="regPhone" placeholder="手机号" autocomplete="off">
          <input class="input" id="regPwd" type="password" placeholder="密码（至少6位）" autocomplete="new-password">
          <input class="input" id="regConfirmPwd" type="password" placeholder="确认密码" autocomplete="new-password">
          <button class="btn-primary btn-block" id="regBtn" type="submit">注册</button>
        </form>
      </div>
    </div>`;

  c.querySelectorAll('.auth-tab').forEach((btn) => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab));
  });
  c.querySelector('#loginForm').addEventListener('submit', (e) => { e.preventDefault(); doLogin(); });
  c.querySelector('#regForm').addEventListener('submit', (e) => { e.preventDefault(); doRegister(); });
}

function switchTab(tab) {
  state.tab = tab;
  const c = state.container;
  c.querySelectorAll('.auth-tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  c.querySelector('#loginForm').style.display = tab === 'login' ? '' : 'none';
  c.querySelector('#regForm').style.display = tab === 'register' ? '' : 'none';
}

async function doLogin() {
  const c = state.container;
  const account = c.querySelector('#loginAccount').value.trim();
  const password = c.querySelector('#loginPwd').value;
  if (!account || !password) { showToast('账号和密码不能为空'); return; }

  const btn = c.querySelector('#loginBtn');
  btn.disabled = true; btn.textContent = '登录中...';
  try {
    const data = await request('user/login', { jsonBody: { account, password } });
    setAuth(data.token, data.username, data.id);
    showToast('登录成功');
    navigate('/');
  } catch (e) {
    showToast(e.message || '登录失败');
  } finally {
    btn.disabled = false; btn.textContent = '登录';
  }
}

async function doRegister() {
  const c = state.container;
  const username = c.querySelector('#regUsername').value.trim();
  const phone = c.querySelector('#regPhone').value.trim();
  const password = c.querySelector('#regPwd').value;
  const confirm = c.querySelector('#regConfirmPwd').value;

  if (!username || !phone || !password || !confirm) { showToast('所有字段都必须填写'); return; }
  if (!/^1[3-9]\d{9}$/.test(phone)) { showToast('请输入有效的11位手机号'); return; }
  if (password.length < 6) { showToast('密码长度不能少于6位'); return; }
  if (password !== confirm) { showToast('两次输入的密码不一致'); return; }

  const btn = c.querySelector('#regBtn');
  btn.disabled = true; btn.textContent = '注册中...';
  try {
    const data = await request('user/register', { jsonBody: { username, phone, password } });
    setAuth(data.token, data.username, data.id);
    showToast('注册成功');
    navigate('/');
  } catch (e) {
    showToast(e.message || '注册失败');
  } finally {
    btn.disabled = false; btn.textContent = '注册';
  }
}
