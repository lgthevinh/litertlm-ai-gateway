// ═══════════════════════════════════════════════════════════════════════
// State
// ═══════════════════════════════════════════════════════════════════════
const state = {
  accessToken:  null,
  refreshToken: null,
  username:     null,
  activeConv:   null,     // currently selected conversation name
  ws:           null,     // WebSocket instance
  streaming:    false,    // true while model is generating
  messages:     {},       // { convName: [{role, text}] }
  revokeTarget: null,     // prefix of key being revoked
};

// ═══════════════════════════════════════════════════════════════════════
// Bootstrap
// ═══════════════════════════════════════════════════════════════════════
window.addEventListener('DOMContentLoaded', () => {
  const saved = sessionStorage.getItem('lm_session');
  if (saved) {
    try {
      const s = JSON.parse(saved);
      state.accessToken  = s.accessToken;
      state.refreshToken = s.refreshToken;
      state.username     = s.username;
      enterApp();
    } catch (_) { showLoginScreen(); }
  } else {
    showLoginScreen();
  }

  document.getElementById('login-user').addEventListener('keydown', e => {
    if (e.key === 'Enter') doLogin();
  });
  document.getElementById('login-pass').addEventListener('keydown', e => {
    if (e.key === 'Enter') doLogin();
  });

  const chatInput = document.getElementById('chat-input');
  chatInput.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  });
  chatInput.addEventListener('input', () => {
    chatInput.style.height = 'auto';
    chatInput.style.height = Math.min(chatInput.scrollHeight, 200) + 'px';
  });

  document.getElementById('nc-config').addEventListener('change', e => {
    document.getElementById('nc-system-field').style.display =
      e.target.value === 'custom' ? 'block' : 'none';
  });
});

// ═══════════════════════════════════════════════════════════════════════
// Auth
// ═══════════════════════════════════════════════════════════════════════
async function doLogin() {
  const username = document.getElementById('login-user').value.trim();
  const password = document.getElementById('login-pass').value;
  const errEl    = document.getElementById('login-err');
  setAlert(errEl, null);

  if (!username) { setAlert(errEl, 'Username is required.'); return; }
  if (!password) { setAlert(errEl, 'Password is required.'); return; }

  setBtnLoading('login-btn', true);
  try {
    const res = await api('/auth/login', 'POST', { username, password });
    if (!res.ok) { setAlert(errEl, res.error || 'Login failed.'); return; }
    state.accessToken  = res.accessToken;
    state.refreshToken = res.refreshToken;
    state.username     = username;
    sessionStorage.setItem('lm_session', JSON.stringify({
      accessToken: state.accessToken, refreshToken: state.refreshToken, username
    }));
    enterApp();
  } catch (e) { setAlert(errEl, 'Cannot reach server: ' + e.message); }
  finally { setBtnLoading('login-btn', false); }
}

async function doLogout() {
  try {
    if (state.refreshToken) {
      await api('/auth/logout', 'POST', { refreshToken: state.refreshToken });
    }
  } catch (_) {}
  clearSession();
}

function clearSession() {
  state.accessToken = state.refreshToken = state.username = null;
  state.activeConv  = null;
  state.messages    = {};
  wsDisconnect();
  sessionStorage.removeItem('lm_session');
  showLoginScreen();
}

// ═══════════════════════════════════════════════════════════════════════
// App shell
// ═══════════════════════════════════════════════════════════════════════
function showLoginScreen() {
  document.getElementById('login-screen').style.display = 'flex';
  document.getElementById('app').style.display = 'none';
}

function enterApp() {
  document.getElementById('login-screen').style.display = 'none';
  document.getElementById('app').style.display = 'flex';
  document.getElementById('nav-username').textContent = state.username;
  showPage('conversations');
}

function showPage(name) {
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('nav a[data-page]').forEach(a => a.classList.remove('active'));
  document.getElementById('page-' + name)?.classList.add('active');
  document.querySelector('nav a[data-page="' + name + '"]')?.classList.add('active');

  // Reset scroll on every page switch so every page starts at the top
  document.querySelector('main')?.scrollTo({ top: 0 });

  if (name === 'conversations') loadConversations();
  if (name === 'apikeys')       loadApiKeys();
}

// ═══════════════════════════════════════════════════════════════════════
// Conversations
// ═══════════════════════════════════════════════════════════════════════
async function loadConversations() {
  try {
    const res = await authApi('/conversations', 'GET');
    if (!res.ok) return;
    renderConvList(res.conversations || []);
  } catch (_) {}
}

function renderConvList(names) {
  const el = document.getElementById('conv-list');
  if (!names.length) {
    el.innerHTML = '<div class="text-dim" style="font-size:12px;padding:8px 4px;">No conversations yet</div>';
    return;
  }
  el.innerHTML = names.map(n =>
    '<div class="conv-item ' + (n === state.activeConv ? 'active' : '') + '" onclick="selectConv(\'' + esc(n) + '\')">' +
    '<span>' + esc(n) + '</span>' +
    '<span class="del-conv" onclick="deleteConv(event,\'' + esc(n) + '\')" title="Delete">\u00d7</span>' +
    '</div>'
  ).join('');
}

function selectConv(name) {
  if (state.activeConv === name) return;  // already selected — no-op

  // Reset streaming state from previous conversation before switching
  state.streaming  = false;
  state.activeConv = name;

  document.getElementById('conv-title').textContent  = name;
  document.getElementById('send-btn').disabled       = true;   // re-enabled when WS connects
  document.getElementById('chat-input').disabled     = true;
  setWsStatus('disconnected');

  document.querySelectorAll('.conv-item').forEach(el => {
    el.classList.toggle('active', el.querySelector('span').textContent === name);
  });

  // Load history from server if not already in memory, then render and connect WS
  if (state.messages[name]) {
    renderMessages(name);
    connectWs(name);
  } else {
    loadMessages(name);
  }
}

async function loadMessages(name) {
  try {
    const res = await authApi('/conversations/' + encodeURIComponent(name) + '/messages', 'GET');
    if (res.ok) {
      state.messages[name] = (res.messages || []).map(m => ({ role: m.role, text: m.text }));
    } else {
      state.messages[name] = [];
    }
  } catch (_) {
    state.messages[name] = [];
  }
  // Only render and connect if the user hasn't switched away in the meantime
  if (state.activeConv === name) {
    renderMessages(name);
    connectWs(name);
  }
}

function renderMessages(name) {
  const box  = document.getElementById('chat-box');
  const msgs = state.messages[name] || [];
  if (!msgs.length) {
    box.innerHTML = '<div class="chat-empty"><strong>LiteRTLM</strong>Send a message to start the conversation</div>';
    return;
  }
  box.innerHTML = msgs.map(m => {
    if (m.role === 'user') {
      return '<div class="msg user"><div class="msg-label">' + esc(state.username) + '</div>' +
             '<div class="msg-bubble">' + esc(m.text) + '</div></div>';
    }
    return '<div class="msg model"><div class="msg-label">Model</div>' +
           '<div class="msg-bubble markdown">' + renderMarkdown(m.text) + '</div></div>';
  }).join('');
  box.scrollTop = box.scrollHeight;
}

function appendMessage(name, role, text) {
  if (!state.messages[name]) state.messages[name] = [];
  state.messages[name].push({ role, text });
  if (name === state.activeConv) renderMessages(name);
}

// ── Markdown ──────────────────────────────────────────────────────────
function renderMarkdown(raw) {
  if (typeof marked === 'undefined') return '<p>' + esc(raw) + '</p>';

  const html = marked.parse(raw, { gfm: true, breaks: true, mangle: false, headerIds: false });

  return html.replace(/<pre><code(?: class="language-([^"]*)")?>([\s\S]*?)<\/code><\/pre>/g,
    function(_, lang, code) {
      const id      = 'cb-' + Math.random().toString(36).slice(2, 8);
      const langTag = lang ? '<span class="code-lang">' + esc(lang) + '</span>' : '';
      return '<div class="code-block-wrap">' + langTag +
             '<pre id="' + id + '"><code>' + code + '</code></pre>' +
             '<button class="code-copy-btn" onclick="copyCode(\'' + id + '\')">Copy</button></div>';
    }
  );
}

function copyCode(preId) {
  const pre  = document.getElementById(preId);
  const text = pre ? pre.innerText : '';
  navigator.clipboard.writeText(text).then(() => {
    const btn = pre?.parentElement?.querySelector('.code-copy-btn');
    if (btn) { btn.textContent = 'Copied!'; setTimeout(() => { btn.textContent = 'Copy'; }, 2000); }
  });
}

// ── WebSocket ─────────────────────────────────────────────────────────

/**
 * Cleanly closes the current WebSocket without triggering the onclose handler side-effects.
 * Always resets streaming state.
 */
function wsDisconnect() {
  if (state.ws) {
    state.ws.onclose = null;
    state.ws.close();
    state.ws = null;
  }
  state.streaming = false;
  setWsStatus('disconnected');
}

/**
 * Opens a new WebSocket for [name], replacing any existing connection.
 * Input is disabled until onopen fires for THIS socket and THIS conversation.
 * Frames from stale sockets (after conversation switch) are silently ignored.
 */
function connectWs(name) {
  wsDisconnect();

  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const url   = proto + '://' + location.host + '/ws/conversations/' + encodeURIComponent(name) + '?token=' + state.accessToken;
  const ws    = new WebSocket(url);
  state.ws    = ws;

  ws.onopen = function() {
    // Only activate input if this socket is still the active one for this conversation
    if (state.ws === ws && state.activeConv === name) {
      setWsStatus('connected');
      document.getElementById('send-btn').disabled   = false;
      document.getElementById('chat-input').disabled = false;
      document.getElementById('chat-input').focus();
    }
  };

  ws.onclose = function() {
    if (state.ws === ws) {
      state.ws        = null;
      state.streaming = false;
      setWsStatus('disconnected');
      document.getElementById('send-btn').disabled   = true;
      document.getElementById('chat-input').disabled = true;
    }
  };

  ws.onerror = function() {
    if (state.ws === ws) setWsStatus('disconnected');
  };

  ws.onmessage = function(evt) {
    // Guard: discard frames from a stale socket or after conversation switch
    if (state.ws !== ws || state.activeConv !== name) return;
    try { handleWsFrame(name, JSON.parse(evt.data)); } catch (_) {}
  };
}

function handleWsFrame(convName, frame) {
  const box = document.getElementById('chat-box');

  if (frame.type === 'token') {
    let streamingBubble = box.querySelector('.msg-bubble.streaming');
    if (!streamingBubble) {
      const empty = box.querySelector('.chat-empty');
      if (empty) empty.remove();
      const wrapper = document.createElement('div');
      wrapper.className = 'msg model';
      wrapper.innerHTML = '<div class="msg-label">Model</div><div class="msg-bubble streaming"></div>';
      box.appendChild(wrapper);
      streamingBubble = wrapper.querySelector('.msg-bubble');
    }
    streamingBubble.textContent += frame.token;
    box.scrollTop = box.scrollHeight;
    state.streaming = true;
  }

  if (frame.type === 'done') {
    const streamingBubble = box.querySelector('.msg-bubble.streaming');
    if (streamingBubble) {
      const rawText = streamingBubble.textContent;
      streamingBubble.classList.remove('streaming');
      streamingBubble.classList.add('markdown');
      streamingBubble.innerHTML = renderMarkdown(rawText);
      appendMessage(convName, 'model', rawText);
      box.scrollTop = box.scrollHeight;
    }
    state.streaming = false;
    document.getElementById('send-btn').disabled   = false;
    document.getElementById('chat-input').disabled = false;
    document.getElementById('chat-input').focus();
  }

  if (frame.type === 'error') {
    state.streaming = false;
    document.getElementById('send-btn').disabled   = false;
    document.getElementById('chat-input').disabled = false;
    const errDiv = document.createElement('div');
    errDiv.style.cssText = 'color:var(--danger);font-size:13px;text-align:center;padding:12px 0;';
    errDiv.textContent = '\u26a0 ' + frame.error;
    box.appendChild(errDiv);
    box.scrollTop = box.scrollHeight;
  }
}

function setWsStatus(status) {
  const el = document.getElementById('ws-status');
  el.className   = 'ws-status ' + status;
  el.textContent = status === 'connected' ? 'Connected' : 'Disconnected';
}

// ── Send message ──────────────────────────────────────────────────────
function sendMessage() {
  if (!state.activeConv || !state.ws || state.ws.readyState !== WebSocket.OPEN || state.streaming) return;
  const input = document.getElementById('chat-input');
  const text  = input.value.trim();
  if (!text) return;

  input.value = '';
  input.style.height = 'auto';

  const box   = document.getElementById('chat-box');
  const empty = box.querySelector('.chat-empty');
  if (empty) empty.remove();

  appendMessage(state.activeConv, 'user', text);
  state.streaming = true;
  document.getElementById('send-btn').disabled   = true;
  document.getElementById('chat-input').disabled = true;
  state.ws.send(JSON.stringify({ message: text }));
}

// ── Docs section nav ─────────────────────────────────────────────────
/**
 * Wires the in-page anchor links in the docs section so they scroll the
 * <main> element (not window) to the target card.
 * Called once each time the docs page is shown.
 */
function initDocsSectionNav() {
  document.querySelectorAll('.docs-section-link').forEach(link => {
    // Remove any existing listener by cloning the node
    const fresh = link.cloneNode(true);
    link.parentNode.replaceChild(fresh, link);
    fresh.addEventListener('click', e => {
      e.preventDefault();
      const targetId = fresh.getAttribute('href').replace('#', '');
      const target = document.getElementById(targetId);
      const main = document.querySelector('main');
      if (target && main) {
        const offset = target.getBoundingClientRect().top - main.getBoundingClientRect().top + main.scrollTop - 16;
        main.scrollTo({ top: offset, behavior: 'smooth' });
      }
    });
  });
}

// ── New conversation modal ────────────────────────────────────────────
function openNewConvModal() {
  document.getElementById('nc-name').value   = '';
  document.getElementById('nc-config').value = 'assistant';
  document.getElementById('nc-system').value = '';
  document.getElementById('nc-system-field').style.display = 'none';
  // Uncheck all tool checkboxes
  document.querySelectorAll('#nc-tools-row input[type="checkbox"]').forEach(cb => { cb.checked = false; });
  setAlert(document.getElementById('nc-err'), null);
  document.getElementById('new-conv-modal').classList.add('open');
  setTimeout(() => document.getElementById('nc-name').focus(), 50);
}
function closeNewConvModal() {
  document.getElementById('new-conv-modal').classList.remove('open');
}

async function createConversation() {
  const name   = document.getElementById('nc-name').value.trim();
  const config = document.getElementById('nc-config').value;
  const system = document.getElementById('nc-system').value.trim();
  const errEl  = document.getElementById('nc-err');
  setAlert(errEl, null);

  if (!name) { setAlert(errEl, 'Name is required.'); return; }

  // Collect checked tools
  const tools = Array.from(document.querySelectorAll('#nc-tools-row input[type="checkbox"]:checked'))
    .map(cb => cb.value);

  const body = config === 'custom'
    ? { name, systemInstruction: system || undefined, tools: tools.length ? tools : undefined }
    : { name, config, tools: tools.length ? tools : undefined };

  try {
    const res = await authApi('/conversations', 'POST', body);
    if (!res.ok) { setAlert(errEl, res.error || 'Failed to create.'); return; }
    closeNewConvModal();
    await loadConversations();
    selectConv(name);
  } catch (e) { setAlert(errEl, e.message); }
}

async function deleteConv(evt, name) {
  evt.stopPropagation();
  if (!confirm('Delete conversation "' + name + '"?')) return;
  try {
    await authApi('/conversations/' + encodeURIComponent(name), 'DELETE');
    if (state.activeConv === name) {
      wsDisconnect();
      state.activeConv = null;
      document.getElementById('conv-title').textContent = 'Select a conversation';
      document.getElementById('send-btn').disabled      = true;
      document.getElementById('chat-input').disabled    = true;
      document.getElementById('chat-box').innerHTML     =
        '<div class="chat-empty"><strong>LiteRTLM</strong>Select or create a conversation to start chatting</div>';
    }
    delete state.messages[name];
    await loadConversations();
  } catch (_) {}
}

// ═══════════════════════════════════════════════════════════════════════
// API Keys
// ═══════════════════════════════════════════════════════════════════════
async function loadApiKeys() {
  const tbody = document.getElementById('key-tbody');
  try {
    const res  = await authApi('/api-key/list', 'GET');
    if (!res.ok) { tbody.innerHTML = '<tr><td colspan="6" class="text-dim">Failed to load.</td></tr>'; return; }
    const keys = res.keys || [];
    if (!keys.length) {
      tbody.innerHTML = '<tr><td colspan="6" class="text-dim" style="padding:16px 12px;">No keys yet</td></tr>';
      return;
    }
    tbody.innerHTML = keys.map(k =>
      '<tr>' +
      '<td>' + esc(k.name) + '</td>' +
      '<td class="monospace">' + esc(k.prefix) + '</td>' +
      '<td><span class="badge ' + (k.active ? 'badge-active' : 'badge-revoked') + '">' + (k.active ? 'Active' : 'Revoked') + '</span></td>' +
      '<td class="text-dim">' + fmtDate(k.createdAt) + '</td>' +
      '<td class="text-dim">' + (k.lastUsedAt ? fmtDate(k.lastUsedAt) : '\u2014') + '</td>' +
      '<td>' + (k.active ? '<button class="btn btn-ghost btn-sm" onclick="openRevokeModal(\'' + esc(k.prefix) + '\')">Revoke</button>' : '') + '</td>' +
      '</tr>'
    ).join('');
  } catch (e) { tbody.innerHTML = '<tr><td colspan="6" class="text-dim">' + e.message + '</td></tr>'; }
}

async function generateKey() {
  const name   = document.getElementById('key-name').value.trim() || 'default';
  const errEl  = document.getElementById('key-gen-err');
  const newBox = document.getElementById('new-key-box');
  setAlert(errEl, null);
  newBox.style.display = 'none';

  try {
    const res = await authApi('/api-key/generate', 'POST', { name });
    if (!res.ok) { setAlert(errEl, res.error || 'Failed.'); return; }
    document.getElementById('new-key-text').textContent = res.key;
    newBox.style.display = 'block';
    document.getElementById('key-name').value = '';
    await loadApiKeys();
  } catch (e) { setAlert(errEl, e.message); }
}

function copyKey() {
  const text = document.getElementById('new-key-text').textContent;
  navigator.clipboard.writeText(text).then(() => {
    const btn = document.querySelector('#new-key-value button');
    btn.textContent = 'Copied!';
    setTimeout(() => { btn.textContent = 'Copy'; }, 2000);
  });
}

function openRevokeModal(prefix) {
  state.revokeTarget = prefix;
  document.getElementById('revoke-key-prefix').textContent = prefix;
  document.getElementById('revoke-raw-key').value = '';
  setAlert(document.getElementById('revoke-err'), null);
  document.getElementById('revoke-modal').classList.add('open');
  setTimeout(() => document.getElementById('revoke-raw-key').focus(), 50);
}
function closeRevokeModal() {
  document.getElementById('revoke-modal').classList.remove('open');
  state.revokeTarget = null;
}

async function confirmRevoke() {
  const rawKey = document.getElementById('revoke-raw-key').value.trim();
  const errEl  = document.getElementById('revoke-err');
  setAlert(errEl, null);
  if (!rawKey) { setAlert(errEl, 'Paste the raw key to revoke.'); return; }
  if (!rawKey.startsWith('lrtlm_')) { setAlert(errEl, 'Key must start with lrtlm_'); return; }
  try {
    const res = await authApi('/api-key/revoke', 'DELETE', { key: rawKey });
    if (!res.ok) { setAlert(errEl, res.error || 'Revoke failed.'); return; }
    closeRevokeModal();
    await loadApiKeys();
  } catch (e) { setAlert(errEl, e.message); }
}

// ═══════════════════════════════════════════════════════════════════════
// HTTP helpers
// ═══════════════════════════════════════════════════════════════════════
async function api(path, method, body) {
  method = method || 'GET';
  const opts = { method: method, headers: { 'Content-Type': 'application/json' } };
  if (body) opts.body = JSON.stringify(body);
  return (await fetch('/api' + path, opts)).json();
}

async function authApi(path, method, body) {
  method = method || 'GET';
  const opts = {
    method: method,
    headers: {
      'Content-Type':  'application/json',
      'Authorization': 'Bearer ' + state.accessToken,
    },
  };
  if (body) opts.body = JSON.stringify(body);

  let res = await fetch('/api' + path, opts);

  if (res.status === 401 && state.refreshToken) {
    if (await tryRefresh()) {
      opts.headers['Authorization'] = 'Bearer ' + state.accessToken;
      res = await fetch('/api' + path, opts);
    } else {
      clearSession();
      throw new Error('Session expired. Please sign in again.');
    }
  }

  return res.json();
}

async function tryRefresh() {
  try {
    const res = await api('/auth/refresh', 'POST', { refreshToken: state.refreshToken });
    if (!res.ok) return false;
    state.accessToken  = res.accessToken;
    state.refreshToken = res.refreshToken;
    sessionStorage.setItem('lm_session', JSON.stringify({
      accessToken: state.accessToken, refreshToken: state.refreshToken, username: state.username
    }));
    return true;
  } catch (_) { return false; }
}

// ═══════════════════════════════════════════════════════════════════════
// UI helpers
// ═══════════════════════════════════════════════════════════════════════
function setAlert(el, msg) {
  if (!msg) { el.textContent = ''; el.classList.remove('show'); return; }
  el.textContent = msg;
  el.classList.add('show');
}

function setBtnLoading(id, loading) {
  const btn    = document.getElementById(id);
  btn.disabled = loading;
  btn.innerHTML = loading ? '<span class="loading"></span> Signing in\u2026' : 'Sign in';
}

function esc(str) {
  return String(str)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function fmtDate(ms) {
  if (!ms) return '\u2014';
  return new Date(ms).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}
