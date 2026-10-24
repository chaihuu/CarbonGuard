/*
 * CarbonGuard
 * Copyright (C) 2026 chaihuu
 * SPDX-License-Identifier: GPL-3.0-only
 * See the LICENSE file for the full license text.
 */

'use strict';

const $ = (id) => document.getElementById(id);
const state = {
  page: 'bind',
  adminKey: sessionStorage.getItem('carbonguard.adminKey') || '',
  playerId: '',
  lookup: null,
  requestToken: '',
  cooldownTimer: 0
};

const escapeHtml = (value) => String(value ?? '')
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#39;');

const formatDate = (ms) => {
  if (!ms || ms <= 0) return '永久';
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit'
    }).format(new Date(ms));
  } catch {
    return new Date(ms).toLocaleString();
  }
};

function setStatus(element, message, kind = '') {
  if (!element) return;
  element.textContent = message || '';
  element.classList.remove('error', 'ok');
  if (kind) element.classList.add(kind);
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (state.adminKey && path.startsWith('/api/admin/')) {
    headers.set('X-CarbonGuard-Key', state.adminKey);
  }
  let body = options.body;
  if (body && typeof body !== 'string') {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(body);
  }
  const response = await fetch(path, {
    method: options.method || (body ? 'POST' : 'GET'),
    headers,
    body,
    cache: 'no-store',
    credentials: 'same-origin'
  });
  let data = null;
  try {
    data = await response.json();
  } catch {
    data = null;
  }
  if (!response.ok || !data || data.ok === false) {
    const error = new Error((data && data.message) || `请求失败（HTTP ${response.status}）`);
    error.status = response.status;
    error.code = data && data.error;
    if (response.status === 401) {
      clearAdminKey();
    }
    throw error;
  }
  return data;
}

function setPage(page, updateHash = true) {
  if (!['bind', 'bans', 'bindings', 'players', 'stats'].includes(page)) page = 'bind';
  state.page = page;
  document.querySelectorAll('.tab').forEach((tab) => {
    const active = tab.dataset.page === page;
    tab.classList.toggle('active', active);
    tab.setAttribute('aria-current', active ? 'page' : 'false');
  });
  document.querySelectorAll('.page').forEach((section) => {
    section.classList.toggle('hidden', section.id !== `page-${page}`);
  });
  const adminPage = page !== 'bind';
  $('admin-unlock').classList.toggle('hidden', !adminPage || Boolean(state.adminKey));
  document.querySelectorAll('.admin-content').forEach((element) => {
    element.classList.toggle('hidden', !adminPage || !state.adminKey);
  });
  $('key-button').textContent = state.adminKey ? '锁定管理' : '管理密钥';

  if (adminPage && state.adminKey) {
    if (page === 'bans') loadBans();
    if (page === 'bindings') loadBindings();
    if (page === 'players') loadPlayers();
    if (page === 'stats') loadStats();
  }
  if (updateHash) {
    history.replaceState(null, '', `${location.pathname}#${page}`);
  }
}

function clearAdminKey() {
  state.adminKey = '';
  sessionStorage.removeItem('carbonguard.adminKey');
  $('admin-key').value = '';
  if (state.page !== 'bind') {
    $('admin-unlock').classList.remove('hidden');
    document.querySelectorAll('.admin-content').forEach((el) => el.classList.add('hidden'));
  }
  setStatus($('unlock-status'), '');
  $('key-button').textContent = '管理密钥';
}

function saveAdminKey(key) {
  state.adminKey = key;
  sessionStorage.setItem('carbonguard.adminKey', key);
}

/* ----------------------------- binding page ----------------------------- */

const PLAYER_ID_PATTERN = /^[A-Za-z0-9_.-]{3,36}$/;

function setStep(step) {
  for (let i = 1; i <= 3; i += 1) {
    const element = $(`step-${i}`);
    if (!element) continue;
    element.classList.toggle('active', i === step);
    element.classList.toggle('done', i < step);
  }
}

function resetBinding() {
  state.playerId = '';
  state.lookup = null;
  state.requestToken = '';
  clearInterval(state.cooldownTimer);
  $('bind-form').classList.remove('hidden');
  $('bind-identity').classList.add('hidden');
  $('code-request-form').classList.add('hidden');
  $('code-form').classList.add('hidden');
  $('bind-success').classList.add('hidden');
  $('bind-notice').classList.add('hidden');
  $('reset-bind').classList.add('hidden');
  $('resend-code').classList.add('hidden');
  $('bind-email').disabled = false;
  $('send-code').classList.remove('hidden');
  $('send-code').disabled = false;
  $('bind-player-id').disabled = false;
  $('bind-player-id').focus();
  $('bind-avatar').hidden = true;
  $('bind-avatar-fallback').textContent = '?';
  $('bind-avatar-fallback').classList.remove('hidden');
  setStatus($('bind-status'), '');
  setStep(1);
}

async function lookupPlayer(rawId) {
  const playerId = String(rawId || '').trim();
  if (!PLAYER_ID_PATTERN.test(playerId)) {
    setStatus($('bind-status'), '请输入 3-36 位账号 ID（UUID、XUID 或游戏名）。', 'error');
    return;
  }
  $('lookup-button').disabled = true;
  setStatus($('bind-status'), '正在匹配账号……');
  try {
    const data = await api(`/api/public/lookup?id=${encodeURIComponent(playerId)}`);
    state.playerId = data.playerId;
    state.lookup = data;
    $('bind-player-id').value = data.playerId;
    $('bind-player-id').disabled = true;
    $('bind-player').textContent = data.playerName || '未知玩家';
    // The mailbox is decided by the server: always <ID>@qq.com.
    $('bind-mailbox').textContent = data.email || `${data.playerId}@qq.com`;
    $('bind-state').textContent = data.bound ? '已绑定' : '未绑定';
    $('bind-state').className = data.bound ? 'ok' : '';
    renderAvatar(data);
    $('bind-identity').classList.remove('hidden');
    $('reset-bind').classList.remove('hidden');
    setStep(2);
    if (data.bound) {
      $('code-request-form').classList.add('hidden');
      $('code-form').classList.add('hidden');
      $('bind-notice-text').textContent =
        `该账号已绑定 ${data.boundEmail || '的邮箱'}，无需重复操作。如非本人绑定，请联系管理员解绑。`;
      $('bind-notice').classList.remove('hidden');
      setStatus($('bind-status'), '');
      return;
    }
    $('bind-notice').classList.add('hidden');
    $('bind-email').value = data.email || `${data.playerId}@qq.com`;
    $('email-hint').textContent =
      `验证码将发送至 ${$('bind-email').value}；该邮箱由 ID 自动生成，不能修改。`;
    $('code-request-form').classList.remove('hidden');
    setStatus($('bind-status'), '账号匹配成功，请获取验证码。', 'ok');
  } catch (error) {
    setStatus($('bind-status'), error.message, 'error');
  } finally {
    $('lookup-button').disabled = false;
  }
}

function renderAvatar(data) {
  const image = $('bind-avatar');
  const fallback = $('bind-avatar-fallback');
  const url = data && data.showAvatars !== false ? (data.avatarUrl || '') : '';
  const initial = ((data && data.playerName) || data && data.playerId || '?').charAt(0).toUpperCase();
  fallback.textContent = initial;
  fallback.classList.remove('hidden');
  image.hidden = true;
  if (!url) {
    return;
  }
  image.onload = () => {
    fallback.classList.add('hidden');
    image.hidden = false;
  };
  image.onerror = () => {
    image.hidden = true;
    fallback.classList.remove('hidden');
  };
  image.src = url;
}

async function requestCode() {
  if (!state.playerId) {
    setStatus($('bind-status'), '请先输入并匹配账号 ID。', 'error');
    return;
  }
  const email = $('bind-email').value.trim();
  if (!email) {
    setStatus($('bind-status'), '绑定邮箱缺失，请重新匹配账号。', 'error');
    return;
  }
  $('send-code').disabled = true;
  setStatus($('bind-status'), '正在发送验证码……');
  try {
    const data = await api('/api/public/request-code', {
      method: 'POST',
      body: { playerId: state.playerId, email }
    });
    state.requestToken = data.requestToken;
    const minutes = Math.max(1, Math.ceil(Number(data.ttlSeconds || 600) / 60));
    $('code-hint').textContent =
      `验证码已发送至 ${data.maskedEmail}，${minutes} 分钟内有效。`;
    setStatus($('bind-status'), '验证码已发送，请查收 QQ 邮箱。', 'ok');
    $('code-form').classList.remove('hidden');
    $('bind-email').disabled = true;
    $('send-code').classList.add('hidden');
    $('resend-code').classList.remove('hidden');
    setStep(3);
    startResendCooldown(Math.max(0, Number(data.cooldownSeconds) || 0));
    $('bind-code').focus();
  } catch (error) {
    $('send-code').disabled = false;
    setStatus($('bind-status'), error.message, 'error');
  }
}

async function verifyCode() {
  const code = $('bind-code').value.trim();
  if (!state.requestToken) {
    setStatus($('bind-status'), '验证会话不存在，请重新发送验证码。', 'error');
    return;
  }
  setStatus($('bind-status'), '正在验证……');
  try {
    await api('/api/public/verify', {
      method: 'POST',
      body: { requestToken: state.requestToken, code }
    });
    state.requestToken = '';
    $('code-request-form').classList.add('hidden');
    $('code-form').classList.add('hidden');
    $('reset-bind').classList.add('hidden');
    $('bind-success').classList.remove('hidden');
    setStatus($('bind-status'), '');
    setStep(3);
  } catch (error) {
    setStatus($('bind-status'), error.message, 'error');
  }
}

function startResendCooldown(seconds) {
  clearInterval(state.cooldownTimer);
  let remaining = seconds;
  const button = $('resend-code');
  button.disabled = true;
  button.textContent = `重新发送（${remaining}s）`;
  state.cooldownTimer = setInterval(() => {
    remaining -= 1;
    if (remaining <= 0) {
      clearInterval(state.cooldownTimer);
      button.disabled = false;
      button.textContent = '重新发送';
    } else {
      button.textContent = `重新发送（${remaining}s）`;
    }
  }, 1000);
}

/* -------------------------- administrative pages ------------------------ */

async function unlock(event) {
  event.preventDefault();
  const key = $('admin-key').value.trim();
  if (key.length < 24) {
    setStatus($('unlock-status'), '密钥长度至少 24 个字符。', 'error');
    return;
  }
  saveAdminKey(key);
  try {
    await api('/api/admin/stats');
    setStatus($('unlock-status'), '');
    setPage(state.page, false);
  } catch (error) {
    clearAdminKey();
    setStatus($('unlock-status'), error.message, 'error');
  }
}

async function loadBans() {
  if (!state.adminKey) return;
  const query = $('ban-query').value.trim();
  setStatus($('bans-status'), '正在查询……');
  try {
    const [bans, reviews] = await Promise.all([
      api(`/api/admin/bans?q=${encodeURIComponent(query)}&limit=50`),
      api('/api/admin/reviews?status=PENDING&limit=100')
    ]);
    renderBans(bans.bans || []);
    renderReviews(reviews.reviews || []);
    $('ban-count').textContent = String(bans.count || 0);
    $('review-count').textContent = String(reviews.count || 0);
    setStatus($('bans-status'), query ? `查询完成：${bans.count || 0} 条记录。` : '');
  } catch (error) {
    if (error.status === 401) {
      setStatus($('unlock-status'), error.message, 'error');
    } else {
      setStatus($('bans-status'), error.message, 'error');
    }
  }
}

async function loadBindings() {
  if (!state.adminKey) return;
  const query = $('binding-query').value.trim();
  setStatus($('bindings-status'), '正在查询……');
  try {
    const data = await api(`/api/admin/bindings?q=${encodeURIComponent(query)}&limit=50`);
    renderBindings(data.bindings || []);
    $('binding-count').textContent = String(data.count || 0);
    setStatus($('bindings-status'), query ? `查询完成：${data.count || 0} 条记录。` : '');
  } catch (error) {
    if (error.status === 401) {
      setStatus($('unlock-status'), error.message, 'error');
    } else {
      setStatus($('bindings-status'), error.message, 'error');
    }
  }
}

function renderBindings(bindings) {
  const container = $('binding-list');
  if (!bindings.length) {
    container.innerHTML = '<p class="empty">没有匹配的绑定记录。</p>';
    return;
  }
  container.innerHTML = bindings.map((binding) => `
    <article class="record">
      <div class="record-head">
        <div>
          <strong>${escapeHtml(binding.player || '未知玩家')}</strong>
          <span class="state on">已绑定</span>
        </div>
        <button class="ghost small danger" type="button" data-unbind="${escapeHtml(binding.identity || '')}">解绑</button>
      </div>
      <dl class="meta">
        <div><dt>身份</dt><dd class="mono">${escapeHtml(binding.identity || '')}</dd></div>
        <div><dt>ID</dt><dd class="mono">${escapeHtml(binding.playerId || '-')}</dd></div>
        <div><dt>邮箱</dt><dd>${escapeHtml(binding.email || '')}</dd></div>
        <div><dt>验证时间</dt><dd>${formatDate(binding.verifiedAt)}</dd></div>
      </dl>
    </article>`).join('');
}

async function unbindIdentity(identity) {
  if (!identity || !window.confirm(`确认解除 ${identity} 的绑定？`)) return;
  try {
    await api('/api/admin/bindings/unbind', { method: 'POST', body: { identity } });
    await loadBindings();
  } catch (error) {
    setStatus($('bindings-status'), error.message, 'error');
  }
}

async function loadPlayers() {
  if (!state.adminKey) return;
  const query = $('player-query').value.trim();
  setStatus($('players-status'), '正在查询……');
  try {
    const data = await api(`/api/admin/players?q=${encodeURIComponent(query)}&limit=50`);
    renderPlayers(data.players || []);
    $('player-count').textContent = String(data.count || 0);
    setStatus($('players-status'), query ? `查询完成：${data.count || 0} 条记录。` : '');
  } catch (error) {
    if (error.status === 401) {
      setStatus($('unlock-status'), error.message, 'error');
    } else {
      setStatus($('players-status'), error.message, 'error');
    }
  }
}

function formatPlaytime(seconds) {
  const total = Math.max(0, Number(seconds) || 0);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  return hours ? `${hours} 小时 ${minutes} 分钟` : `${minutes} 分钟`;
}

function renderPlayers(players) {
  const container = $('player-list');
  if (!players.length) {
    container.innerHTML = '<p class="empty">暂时没有玩家记录。</p>';
    return;
  }
  container.innerHTML = players.map((player) => `
    <article class="record">
      <div class="record-head">
        <div>
          <strong>${escapeHtml(player.player || '未知玩家')}</strong>
          <span class="state ${player.boundAt ? 'on' : 'off'}">${player.boundAt ? '已绑定' : '未绑定'}</span>
        </div>
        <span class="mono">${escapeHtml(player.identity || '')}</span>
      </div>
      <dl class="meta">
        <div><dt>初次加入</dt><dd>${formatDate(player.firstJoin)}</dd></div>
        <div><dt>绑定时间</dt><dd>${player.boundAt ? formatDate(player.boundAt) : '未绑定'}</dd></div>
        <div><dt>邮箱</dt><dd>${escapeHtml(player.email || '-')}</dd></div>
        <div><dt>最近登录</dt><dd>${formatDate(player.lastLogin)}</dd></div>
        <div><dt>登录次数</dt><dd>${Number(player.loginCount || 0)}</dd></div>
        <div><dt>累计游玩</dt><dd>${formatPlaytime(player.playSeconds)}</dd></div>
        <div><dt>最近 IP</dt><dd class="mono">${escapeHtml(player.lastIp || '-')}</dd></div>
      </dl>
    </article>`).join('');
}

function renderBans(bans) {
  const container = $('ban-list');
  if (!bans.length) {
    container.innerHTML = '<p class="empty">没有匹配的封禁记录。</p>';
    return;
  }
  container.innerHTML = bans.map((ban) => {
    const active = ban.active;
    const ips = (ban.ips || []).map((ip) => `<span class="chip">${escapeHtml(ip)}</span>`).join('');
    return `
      <article class="record">
        <div class="record-head">
          <div>
            <strong>${escapeHtml(ban.player || '未知玩家')}</strong>
            <span class="mono">#${ban.id}</span>
            <span class="state ${active ? 'on' : 'off'}">${active ? '生效中' : '已失效'}</span>
          </div>
          <button class="ghost small" type="button" data-unban="${ban.id}" ${active ? '' : 'disabled'}>解封</button>
        </div>
        <p class="reason">${escapeHtml(ban.reason || '')}</p>
        <dl class="meta">
          <div><dt>身份</dt><dd class="mono">${escapeHtml(ban.identity || '')}</dd></div>
          <div><dt>创建</dt><dd>${formatDate(ban.createdAt)} · ${escapeHtml(ban.createdBy || '')}</dd></div>
          <div><dt>到期</dt><dd>${formatDate(ban.expiresAt)}</dd></div>
        </dl>
        <div class="chips">${ips || '<span class="muted">无关联 IP</span>'}</div>
      </article>`;
  }).join('');
}

function renderReviews(reviews) {
  const container = $('review-list');
  if (!reviews.length) {
    container.innerHTML = '<p class="empty">当前没有待审记录。</p>';
    return;
  }
  container.innerHTML = reviews.map((review) => `
    <article class="record review">
      <div class="record-head">
        <div>
          <span class="type">${review.type === 'IP_LIMIT' ? '单 IP 超限' : 'IP 关联'}</span>
          <strong>${escapeHtml(review.player || '未知玩家')}</strong>
          <span class="state pending">待审</span>
        </div>
        <div class="record-actions">
          <button class="ghost small" type="button" data-review="${review.id}" data-action="approve">通过</button>
          <button class="ghost small danger" type="button" data-review="${review.id}" data-action="reject">拒绝</button>
          ${review.banId ? `<button class="ghost small" type="button" data-unban="${review.banId}">解封</button>` : ''}
        </div>
      </div>
      <p class="reason">${escapeHtml(review.reason || '')}</p>
      <dl class="meta">
        <div><dt>IP</dt><dd class="mono">${escapeHtml(review.ip || '-')}</dd></div>
        <div><dt>身份</dt><dd class="mono">${escapeHtml(review.identity || '')}</dd></div>
        <div><dt>时间</dt><dd>${formatDate(review.createdAt)}</dd></div>
      </dl>
    </article>`).join('');
}

async function decideReview(id, action) {
  if (action === 'reject' && !window.confirm('拒绝会踢出该玩家的当前在线会话，确认继续？')) return;
  try {
    await api(`/api/admin/reviews/${id}`, { method: 'POST', body: { action } });
    await loadBans();
  } catch (error) {
    setStatus($('bans-status'), error.message, 'error');
  }
}

async function unban(id) {
  if (!window.confirm(`确认解封 #${id}？该封禁的 IP 关联也会被清除。`)) return;
  try {
    await api(`/api/admin/bans/${id}/unban`, { method: 'POST', body: {} });
    await loadBans();
    if (state.page === 'stats') loadStats();
  } catch (error) {
    setStatus($('bans-status'), error.message, 'error');
  }
}

async function loadStats() {
  if (!state.adminKey) return;
  setStatus($('stats-status'), '');
  try {
    const data = await api('/api/admin/stats');
    renderStats(data);
  } catch (error) {
    if (error.status === 401) {
      setStatus($('unlock-status'), error.message, 'error');
    } else {
      setStatus($('stats-status'), error.message, 'error');
    }
  }
}

function renderStats(data) {
  const cards = [
    ['在线会话', data.online],
    ['身份总数', data.identities],
    ['已绑定', data.bindings],
    ['生效封禁', data.activeBans],
    ['24h 登录', data.logins24h],
    ['24h 拒绝', data.denied24h],
    ['24h 独立 IP', data.uniqueIps24h],
    ['待审记录', data.pendingReviews]
  ];
  $('stat-cards').innerHTML = cards.map(([label, value]) => `
    <div class="stat"><span>${escapeHtml(label)}</span><strong>${Number(value || 0)}</strong></div>`).join('');

  const series = data.loginSeries || [];
  const max = Math.max(1, ...series.map((item) => Number(item.allowed) + Number(item.denied)));
  $('login-chart').innerHTML = series.length ? series.map((item) => {
    const allowed = Number(item.allowed) || 0;
    const denied = Number(item.denied) || 0;
    return `
      <div class="chart-row">
        <span class="chart-day">${escapeHtml(item.day.slice(5))}</span>
        <div class="chart-bars" title="允许 ${allowed} / 拒绝 ${denied}">
          <progress class="allowed" max="${max}" value="${allowed}" aria-label="允许 ${allowed}"></progress>
          <progress class="denied" max="${max}" value="${denied}" aria-label="拒绝 ${denied}"></progress>
        </div>
        <span class="chart-value">${allowed}/${denied}</span>
      </div>`;
  }).join('') : '<p class="empty">暂无登录事件。</p>';

  const modules = data.modules || {};
  const labels = [
    ['ipLimit', '单 IP 限制', modules.ipLimitMode ? `模式 ${modules.ipLimitMode}，硬上限 ${modules.ipLimitHardLimit}` : ''],
    ['ban', '封禁模块', modules.ipAssociationAction ? `关联动作 ${modules.ipAssociationAction}` : ''],
    ['bindingGateOperational', '入服拦截', modules.requireBinding ? '已启用 require-binding' : '未要求绑定'],
    ['bindingPage', '绑定页面', modules.bindingUrl ? `${modules.bindingUrl}（邮箱 ${modules.bindingMailboxDomain || '@qq.com'}）` : '已关闭'],
    ['smtpOperational', 'SMTP', '发送 ID@qq.com 绑定验证码'],
    ['bstats', 'bStats', '匿名统计'],
    ['updateCheck', '自动更新', '异步检查 GitHub Releases'],
    ['floodgate', 'Floodgate', 'XUID 主键'],
    ['geyser', 'Geyser', '基岩版检测 / 真实 IP 依赖代理协议'],
    ['web', 'Web 面板', '管理密钥 + 速率限制']
  ];
  $('module-grid').innerHTML = labels.map(([key, name, detail]) => {
    const on = Boolean(modules[key]);
    return `
      <div class="module">
        <span class="dot ${on ? 'on' : 'off'}"></span>
        <div><strong>${escapeHtml(name)}</strong><small>${escapeHtml(detail || '')}</small></div>
        <span class="state ${on ? 'on' : 'off'}">${on ? '开启' : '关闭'}</span>
      </div>`;
  }).join('');
}

/* -------------------------------- wiring -------------------------------- */

document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('.tab').forEach((tab) => {
    tab.addEventListener('click', () => setPage(tab.dataset.page));
  });
  $('key-button').addEventListener('click', () => {
    if (state.adminKey) {
      clearAdminKey();
      setPage('bans');
      $('admin-key').focus();
    } else {
      setPage('bans');
      $('admin-key').focus();
    }
  });
  $('unlock-form').addEventListener('submit', unlock);

  $('bind-form').addEventListener('submit', (event) => {
    event.preventDefault();
    lookupPlayer($('bind-player-id').value);
  });
  $('reset-bind').addEventListener('click', resetBinding);
  $('code-request-form').addEventListener('submit', (event) => {
    event.preventDefault();
    requestCode();
  });
  $('resend-code').addEventListener('click', () => requestCode());
  $('code-form').addEventListener('submit', (event) => {
    event.preventDefault();
    verifyCode();
  });

  $('ban-search-form').addEventListener('submit', (event) => {
    event.preventDefault();
    loadBans();
  });
  $('ban-refresh').addEventListener('click', loadBans);
  $('binding-search-form').addEventListener('submit', (event) => {
    event.preventDefault();
    loadBindings();
  });
  $('binding-list').addEventListener('click', (event) => {
    const button = event.target.closest('button[data-unbind]');
    if (button) unbindIdentity(button.dataset.unbind);
  });
  $('player-search-form').addEventListener('submit', (event) => {
    event.preventDefault();
    loadPlayers();
  });
  $('review-list').addEventListener('click', (event) => {
    const review = event.target.closest('button[data-review]');
    if (review) {
      decideReview(review.dataset.review, review.dataset.action);
      return;
    }
    const unbanButton = event.target.closest('button[data-unban]');
    if (unbanButton) unban(unbanButton.dataset.unban);
  });
  $('ban-list').addEventListener('click', (event) => {
    const button = event.target.closest('button[data-unban]');
    if (button) unban(button.dataset.unban);
  });

  const hash = location.hash.replace('#', '');
  const page = ['bans', 'bindings', 'players', 'stats'].includes(hash) ? hash : 'bind';
  setPage(page, false);
  if (page === 'bind') {
    resetBinding();
  }
  $('admin-key').value = state.adminKey;
});
