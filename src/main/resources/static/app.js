// ═══════════════════════════════════════════════════════════════════════
// State
// ═══════════════════════════════════════════════════════════════════════
const state = {
  accessToken:          null,
  refreshToken:         null,
  username:             null,
  activeConv:           null,
  ws:                   null,
  streaming:            false,
  messages:             {},
  revokeTarget:         null,
  editTarget:           null,
  thinkingMap:          {},     // { convName: boolean } — current thinkingEnabled per conv
  pendingAttachments:   [],     // [{ file, dataUrl, type }] — staged before send
  availableTools:       null,   // [{ set, name, description }] — cached from GET /api/tools
  perMessageThinking:   null,   // Boolean? — override for the next message only, then resets
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
  loadAvailableTools();
  showPage('conversations');
}

function showPage(name) {
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('nav a[data-page]').forEach(a => a.classList.remove('active'));
  document.getElementById('page-' + name)?.classList.add('active');
  document.querySelector('nav a[data-page="' + name + '"]')?.classList.add('active');

  // Reset scroll on every page switch so every page starts at the top
  document.querySelector('main')?.scrollTo({ top: 0 });

  // Stop queue polling when leaving the page
  stopQueuePolling();

  if (name === 'conversations') loadConversations();
  if (name === 'apikeys')       loadApiKeys();
  if (name === 'queue')         startQueuePolling();
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

function renderConvList(convs) {
  const el = document.getElementById('conv-list');
  if (!convs.length) {
    el.innerHTML = '<div class="text-dim" style="font-size:12px;padding:8px 4px;">No conversations yet</div>';
    return;
  }
  el.innerHTML = convs.map(c => {
    const n = c.name;
    return '<div class="conv-item ' + (n === state.activeConv ? 'active' : '') + '" onclick="selectConv(\'' + esc(n) + '\')">' +
      '<span>' + esc(n) + '</span>' +
      '<div class="conv-item-actions">' +
      '<span class="edit-conv" onclick="openEditConvModal(event,\'' + esc(n) + '\')" title="Edit">&#9998;</span>' +
      '<span class="del-conv"  onclick="deleteConv(event,\'' + esc(n) + '\')"        title="Delete">&times;</span>' +
      '</div>' +
      '</div>';
  }).join('');
  // Track thinking state per conversation
  state.thinkingMap = Object.fromEntries(convs.map(c => [c.name, c.thinkingEnabled !== false]));
}

function selectConv(name) {
  if (state.activeConv === name) return;

  state.streaming  = false;
  state.activeConv = name;

  // Clear pending attachments and per-message thinking override when switching
  state.pendingAttachments  = [];
  state.perMessageThinking  = null;
  renderAttachPreview();

  document.getElementById('conv-title').textContent = name;

  // Show toolbar thinking button and sync its state
  const toolbarBtn = document.getElementById('thinking-toolbar-btn');
  toolbarBtn.style.display = 'inline-flex';
  syncThinkingToolbarBtn(name);

  document.getElementById('send-btn').disabled   = true;
  document.getElementById('chat-input').disabled = true;
  document.getElementById('attach-btn').disabled = true;
  setWsStatus('disconnected');

  document.querySelectorAll('.conv-item').forEach(el => {
    el.classList.toggle('active', el.querySelector('span').textContent.trim().startsWith(name));
  });

  // Clear box immediately so no stale content or leftover streaming bubbles are visible
  const box = document.getElementById('chat-box');
  box.innerHTML = '<div class="chat-empty"><strong>LiteRTLM</strong>Loading\u2026</div>';

  // Always reload from server on switch — ensures history is always fresh
  loadMessages(name);
}

async function loadMessages(name) {
  try {
    const res = await authApi('/conversations/' + encodeURIComponent(name) + '/messages', 'GET');
    if (res.ok) {
      state.messages[name] = (res.messages || []).map(m => {
        // Map API attachment refs to renderable format
        const attachments = (m.attachments || []).map(att => ({
          type:     att.type,
          filename: att.filename,
          url:      '/api/attachments/' + encodeURIComponent(name) + '/' + encodeURIComponent(att.filename)
        }));
        return { role: m.role, text: m.text, attachments };
      });
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
      const attachHtml = renderAttachments(m.attachments);
      return '<div class="msg user">' +
             '<div class="msg-label">' + esc(state.username) + '</div>' +
             (attachHtml ? '<div class="msg-attachments">' + attachHtml + '</div>' : '') +
             (m.text ? '<div class="msg-bubble">' + esc(m.text) + '</div>' : '') +
             '</div>';
    }
    return '<div class="msg model"><div class="msg-label">Model</div>' +
           '<div class="msg-bubble markdown">' + renderMarkdown(m.text) + '</div></div>';
  }).join('');
  box.scrollTop = box.scrollHeight;
}

function renderAttachments(attachments) {
  if (!attachments || !attachments.length) return '';
  return attachments.map(att => {
    if (att.type === 'image') {
      // dataUrl (live session) or API url (from history)
      const src = att.dataUrl || att.url || '';
      return src ? '<img class="msg-attach-img" src="' + src + '" alt="' + esc(att.name || 'image') + '">' : '';
    }
    if (att.type === 'audio') {
      if (att.dataUrl) {
        return '<audio class="msg-attach-audio" controls src="' + att.dataUrl + '"></audio>';
      }
      if (att.url) {
        return '<audio class="msg-attach-audio" controls src="' + att.url + '"></audio>';
      }
      const label = att.name || att.filename || 'audio';
      return '<div class="msg-attach-file">🎵 ' + esc(label) + '</div>';
    }
    return '';
  }).join('');
}

function appendMessage(name, role, text) {
  if (!state.messages[name]) state.messages[name] = [];
  state.messages[name].push({ role, text, attachments: [] });
  if (name === state.activeConv) renderMessages(name);
}

function appendMessageWithAttachments(name, role, text, attachments) {
  if (!state.messages[name]) state.messages[name] = [];
  // Store lightweight refs for rendering (dataUrl for images, type+name for audio)
  const refs = attachments.map(a => ({ type: a.type, dataUrl: a.dataUrl, name: a.file.name }));
  state.messages[name].push({ role, text, attachments: refs });
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
    if (state.ws === ws && state.activeConv === name) {
      setWsStatus('connected');
      if (!state.streaming) {
        setInputEnabled(true);
        syncThinkBtn();
        document.getElementById('chat-input').focus();
      }
    }
  };

  ws.onclose = function() {
    if (state.ws === ws) {
      state.ws        = null;
      state.streaming = false;
      setWsStatus('disconnected');
      setInputEnabled(false);
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

  if (frame.type === 'queued') {
    state.streaming = true;
    setInputEnabled(false);
    showQueuedBubble(box, frame.position);
    return;
  }

  if (frame.type === 'busy') {
    state.streaming = true;
    setInputEnabled(false);
    removeQueuedBubble(box);
    showThinkingBubble(box);
    return;
  }

  // ── Agent frames ─────────────────────────────────────────────────────
  if (frame.type === 'agent_thinking') {
    updateThinkingBubble(box, 'Step ' + (frame.stepIndex + 1) + ': Thinking\u2026');
    return;
  }

  if (frame.type === 'agent_tool_call') {
    updateThinkingBubble(box, 'Calling tool: ' + frame.toolName + '\u2026');
    appendAgentStep(box, 'tool-call',
      '\uD83D\uDD27 ' + frame.toolName,
      frame.params
    );
    return;
  }

  if (frame.type === 'agent_tool_result') {
    updateThinkingBubble(box, 'Got result, reasoning\u2026');
    appendAgentStep(box, 'tool-result',
      '\u2714 ' + frame.toolName,
      frame.result
    );
    return;
  }

  if (frame.type === 'agent_max_steps') {
    updateThinkingBubble(box, 'Max steps reached');
    return;
  }
  // ─────────────────────────────────────────────────────────────────────

  if (frame.type === 'token') {
    // First token — swap thinking bubble for streaming bubble
    const thinking = box.querySelector('.thinking-bubble');
    if (thinking) {
      thinking.classList.remove('thinking-bubble');
      thinking.querySelector('.msg-bubble').classList.remove('thinking');
      thinking.querySelector('.msg-bubble').textContent = '';
      thinking.classList.add('streaming-bubble');
    }
    const streaming = box.querySelector('.streaming-bubble .msg-bubble');
    if (streaming) {
      streaming.textContent += frame.token;
      box.scrollTop = box.scrollHeight;
    }
    return;
  }

  if (frame.type === 'done') {
    const inProgress = box.querySelector('.streaming-bubble') || box.querySelector('.thinking-bubble');
    if (inProgress) inProgress.remove();

    state.streaming = false;
    setInputEnabled(true);
    syncThinkBtn();

    const reply = frame.reply || '';
    if (reply) appendMessage(convName, 'model', reply);
    document.getElementById('chat-input').focus();
    box.scrollTop = box.scrollHeight;
    return;
  }

  if (frame.type === 'error') {
    const inProgress = box.querySelector('.streaming-bubble') || box.querySelector('.thinking-bubble') || box.querySelector('.queued-bubble');
    if (inProgress) inProgress.remove();
    state.streaming = false;
    setInputEnabled(true);
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

function setInputEnabled(enabled) {
  document.getElementById('send-btn').disabled    = !enabled;
  document.getElementById('chat-input').disabled  = !enabled;
  document.getElementById('attach-btn').disabled  = !enabled;
  document.getElementById('think-btn').disabled   = !enabled;
}

// ── File attachment ───────────────────────────────────────────────────
function onFilesSelected(input) {
  const files = Array.from(input.files || []);
  input.value = ''; // reset so same file can be re-selected
  files.forEach(file => {
    const isImage = file.type.startsWith('image/');
    const isAudio = file.type.startsWith('audio/');
    if (!isImage && !isAudio) return;
    if (state.pendingAttachments.length >= 6) return; // max 6 attachments
    const reader = new FileReader();
    reader.onload = e => {
      state.pendingAttachments.push({ file, dataUrl: e.target.result, type: isImage ? 'image' : 'audio' });
      renderAttachPreview();
    };
    reader.readAsDataURL(file);
  });
}

function renderAttachPreview() {
  const strip = document.getElementById('attach-preview');
  if (!state.pendingAttachments.length) { strip.innerHTML = ''; return; }
  strip.innerHTML = state.pendingAttachments.map((att, i) => {
    if (att.type === 'image') {
      return '<div class="attach-thumb" title="' + esc(att.file.name) + '">' +
             '<img src="' + att.dataUrl + '">' +
             '<button class="attach-remove" onclick="removeAttachment(' + i + ')">✕</button></div>';
    }
    // audio
    const ext = att.file.name.split('.').pop().toUpperCase();
    return '<div class="attach-thumb attach-audio" title="' + esc(att.file.name) + '">' +
           '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>' +
           '<span>' + esc(ext) + '</span>' +
           '<button class="attach-remove" onclick="removeAttachment(' + i + ')">✕</button></div>';
  }).join('');
}

function removeAttachment(index) {
  state.pendingAttachments.splice(index, 1);
  renderAttachPreview();
}
function sendMessage() {
  if (!state.activeConv || !state.ws || state.ws.readyState !== WebSocket.OPEN || state.streaming) return;
  const input = document.getElementById('chat-input');
  const text  = input.value.trim();
  if (!text && !state.pendingAttachments.length) return;

  input.value = '';
  input.style.height = 'auto';

  const box   = document.getElementById('chat-box');
  const empty = box.querySelector('.chat-empty');
  if (empty) empty.remove();

  // Snapshot and clear pending attachments
  const attachments = state.pendingAttachments.splice(0);
  renderAttachPreview();

  // Build WS payload
  const payload = { message: text || '' };
  const images = attachments.filter(a => a.type === 'image').map(a => a.dataUrl);
  const audio  = attachments.filter(a => a.type === 'audio').map(a => a.dataUrl);
  if (images.length) payload.images = images;
  if (audio.length)  payload.audio  = audio;

  // Include per-message thinking override if set, then reset it
  if (state.perMessageThinking !== null) {
    payload.thinking = state.perMessageThinking;
    state.perMessageThinking = null;
    syncThinkBtn();  // reset button visual back to conv default
  }

  // Render user message with attachment previews
  appendMessageWithAttachments(state.activeConv, 'user', text, attachments);
  state.streaming = true;
  setInputEnabled(false);
  state.ws.send(JSON.stringify(payload));
}

// ── Queued bubble helpers ────────────────────────────────────────────
function showQueuedBubble(box, position) {
  removeQueuedBubble(box);
  const empty = box.querySelector('.chat-empty');
  if (empty) empty.remove();
  const wrapper = document.createElement('div');
  wrapper.className = 'msg model queued-bubble';
  wrapper.innerHTML = '<div class="msg-label">Model</div><div class="msg-bubble queued">Queued (#' + position + ')\u2026</div>';
  box.appendChild(wrapper);
  box.scrollTop = box.scrollHeight;
}

function removeQueuedBubble(box) {
  const bubble = box.querySelector('.queued-bubble');
  if (bubble) bubble.remove();
}

// ── Thinking bubble helpers ───────────────────────────────────────────
function showThinkingBubble(box) {
  if (box.querySelector('.thinking-bubble')) return;
  const empty = box.querySelector('.chat-empty');
  if (empty) empty.remove();
  const wrapper = document.createElement('div');
  wrapper.className = 'msg model thinking-bubble';
  wrapper.innerHTML = '<div class="msg-label">Model</div><div class="msg-bubble thinking">Thinking\u2026</div>';
  box.appendChild(wrapper);
  box.scrollTop = box.scrollHeight;
}

function removeThinkingBubble(box) {
  const bubble = box.querySelector('.thinking-bubble');
  if (bubble) bubble.remove();
}

/**
 * Updates the status text inside the thinking bubble.
 * Used to show agent step progress (e.g. "Calling tool: datetime…").
 */
function updateThinkingBubble(box, text) {
  const bubble = box.querySelector('.thinking-bubble .msg-bubble');
  if (bubble) {
    bubble.textContent = text;
    box.scrollTop = box.scrollHeight;
  }
}

/**
 * Appends a collapsible agent step row (tool call or tool result) inside
 * the thinking bubble so the user can inspect what the agent did.
 */
function appendAgentStep(box, type, label, detail) {
  const thinking = box.querySelector('.thinking-bubble');
  if (!thinking) return;

  // Ensure the steps container exists inside the thinking bubble
  let steps = thinking.querySelector('.agent-steps');
  if (!steps) {
    steps = document.createElement('div');
    steps.className = 'agent-steps';
    thinking.appendChild(steps);
  }

  const step = document.createElement('div');
  step.className = 'agent-step agent-step-' + type;

  const header = document.createElement('div');
  header.className = 'agent-step-header';
  header.textContent = label;
  header.onclick = () => step.classList.toggle('expanded');

  const body = document.createElement('div');
  body.className = 'agent-step-body';
  // Pretty-print JSON if possible, otherwise raw string
  try {
    body.textContent = JSON.stringify(JSON.parse(detail), null, 2);
  } catch (_) {
    body.textContent = detail;
  }

  step.appendChild(header);
  step.appendChild(body);
  steps.appendChild(step);
  box.scrollTop = box.scrollHeight;
}
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

// ── Tools ─────────────────────────────────────────────────────────────

async function loadAvailableTools() {
  try {
    const res = await fetch('/api/tools');
    const data = await res.json();
    state.availableTools = (data.tools || []);
  } catch (_) {
    state.availableTools = [];
  }
}

/**
 * Renders tool checkboxes into `containerId`.
 * Groups individual tools under their set name — one checkbox per set.
 * `checkedSets` is an array of set names that should start checked.
 */
function renderToolCheckboxes(containerId, checkedSets = []) {
  const container = document.getElementById(containerId);
  if (!container) return;

  const tools = state.availableTools;
  if (!tools || !tools.length) {
    container.innerHTML = '<span class="tools-loading">No tools available.</span>';
    return;
  }

  // Group by set name, collect tool names for the label
  const sets = {};
  tools.forEach(t => {
    if (!sets[t.set]) sets[t.set] = [];
    sets[t.set].push(t.name);
  });

  container.innerHTML = Object.entries(sets).map(([setName, toolNames]) => {
    const checked  = checkedSets.includes(setName) ? ' checked' : '';
    const subtitle = toolNames.join(', ');
    return `<label class="nc-tool-check" title="${esc(subtitle)}">` +
           `<input type="checkbox" value="${esc(setName)}"${checked}> ` +
           `<span class="tool-set-name">${esc(setName)}</span>` +
           `<span class="tool-set-tools">${esc(subtitle)}</span>` +
           `</label>`;
  }).join('');
}

// ── New conversation modal ────────────────────────────────────────────
function openNewConvModal() {
  document.getElementById('nc-name').value = '';
  // Reset thinking toggle to ON
  const btn = document.getElementById('nc-thinking-btn');
  btn.textContent = 'ON';
  btn.classList.add('active');
  btn.dataset.thinking = 'true';
  renderToolCheckboxes('nc-tools-row');
  setAlert(document.getElementById('nc-err'), null);
  document.getElementById('new-conv-modal').classList.add('open');
  setTimeout(() => document.getElementById('nc-name').focus(), 50);
}
function closeNewConvModal() {
  document.getElementById('new-conv-modal').classList.remove('open');
}

async function createConversation() {
  const name  = document.getElementById('nc-name').value.trim();
  const errEl = document.getElementById('nc-err');
  setAlert(errEl, null);

  if (!name) { setAlert(errEl, 'Name is required.'); return; }

  const tools = Array.from(document.querySelectorAll('#nc-tools-row input[type="checkbox"]:checked'))
    .map(cb => cb.value);

  const thinkingBtn = document.getElementById('nc-thinking-btn');
  const thinkingEnabled = thinkingBtn.dataset.thinking !== 'false';

  const body = {
    name,
    config: 'agent',
    tools: tools.length ? tools : undefined,
    thinkingEnabled
  };

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
      setInputEnabled(false);
      document.getElementById('chat-box').innerHTML     =
        '<div class="chat-empty"><strong>LiteRTLM</strong>Select or create a conversation to start chatting</div>';
    }
    delete state.messages[name];
    await loadConversations();
  } catch (_) {}
}

// ── Edit conversation modal ───────────────────────────────────────────
function openEditConvModal(evt, name) {
  evt.stopPropagation();
  state.editTarget = name;
  renderToolCheckboxes('ec-tools-row');
  setAlert(document.getElementById('ec-err'), null);
  document.getElementById('edit-conv-modal').classList.add('open');
}
function closeEditConvModal() {
  document.getElementById('edit-conv-modal').classList.remove('open');
  state.editTarget = null;
}

async function saveEditConv() {
  const errEl = document.getElementById('ec-err');
  setAlert(errEl, null);

  const name = state.editTarget;
  if (!name) return;

  const checkedTools = Array.from(document.querySelectorAll('#ec-tools-row input[type="checkbox"]'));
  const anyChecked   = checkedTools.some(cb => cb.checked);
  const tools        = anyChecked ? checkedTools.filter(cb => cb.checked).map(cb => cb.value) : undefined;

  if (tools === undefined) {
    setAlert(errEl, 'Select at least one tool to update, or cancel.');
    return;
  }

  try {
    const res = await authApi('/conversations/' + encodeURIComponent(name), 'PATCH', { tools });
    if (!res.ok) { setAlert(errEl, res.error || 'Update failed.'); return; }
    closeEditConvModal();
    await loadConversations();
    if (state.activeConv === name) connectWs(name);
  } catch (e) { setAlert(errEl, e.message); }
}


// ── Thinking controls ─────────────────────────────────────────────────

/** Syncs the toolbar thinking button label/style to the stored conv state. */
function syncThinkingToolbarBtn(name) {
  const enabled = state.thinkingMap[name] !== false;
  const btn     = document.getElementById('thinking-toolbar-btn');
  const label   = document.getElementById('thinking-toolbar-label');
  if (!btn || !label) return;
  label.textContent = enabled ? '⚡ Thinking ON' : '⚡ Thinking OFF';
  btn.classList.toggle('thinking-on',  enabled);
  btn.classList.toggle('thinking-off', !enabled);
}

/** Syncs the per-message ⚡ button in the input bar. */
function syncThinkBtn() {
  const name    = state.activeConv;
  const convVal = name ? (state.thinkingMap[name] !== false) : true;
  // If override is set, show that; otherwise show conv default
  const effective = state.perMessageThinking !== null ? state.perMessageThinking : convVal;
  const btn = document.getElementById('think-btn');
  if (!btn) return;
  btn.classList.toggle('think-active',   effective);
  btn.classList.toggle('think-inactive', !effective);
  btn.title = state.perMessageThinking !== null
    ? 'Thinking override: ' + (effective ? 'ON' : 'OFF') + ' for next message (click to reset)'
    : 'Click to override thinking for next message (currently ' + (effective ? 'ON' : 'OFF') + ')';
}

/** Toggle in new-conv modal. */
function toggleNewConvThinking() {
  const btn     = document.getElementById('nc-thinking-btn');
  const current = btn.dataset.thinking !== 'false';
  const next    = !current;
  btn.dataset.thinking = String(next);
  btn.textContent = next ? 'ON' : 'OFF';
  btn.classList.toggle('active', next);
}

/** Permanently toggle thinking for the active conversation. */
async function togglePermanentThinking() {
  const name = state.activeConv;
  if (!name) return;
  const current = state.thinkingMap[name] !== false;
  const next    = !current;
  try {
    const res = await authApi('/conversations/' + encodeURIComponent(name), 'PATCH', { thinkingEnabled: next });
    if (!res.ok) return;
    state.thinkingMap[name] = next;
    // Reset per-message override — permanent change takes priority
    state.perMessageThinking = null;
    syncThinkingToolbarBtn(name);
    syncThinkBtn();
  } catch (_) {}
}

/** Toggle thinking for the next message only. */
function togglePerMessageThinking() {
  const name    = state.activeConv;
  if (!name) return;
  const convVal = state.thinkingMap[name] !== false;

  if (state.perMessageThinking === null) {
    // First click — flip from conv default
    state.perMessageThinking = !convVal;
  } else {
    // Second click — reset override (back to conv default)
    state.perMessageThinking = null;
  }
  syncThinkBtn();
}


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
// Queue
// ═══════════════════════════════════════════════════════════════════════
var queuePollTimer = null;

function startQueuePolling() {
  loadQueue();
  queuePollTimer = setInterval(loadQueue, 2000);
}

function stopQueuePolling() {
  if (queuePollTimer) { clearInterval(queuePollTimer); queuePollTimer = null; }
}

async function loadQueue() {
  try {
    const res = await api('/queue', 'GET');
    if (!res.ok) return;
    var size  = res.size || 0;
    var queue = res.queue || [];

    document.getElementById('queue-size').textContent = size;
    document.getElementById('queue-engine-status').textContent =
      size === 0 ? 'Engine idle' : size === 1 ? 'Processing 1 task' : 'Processing 1 task, ' + (size - 1) + ' queued';

    // Update nav badge
    var badge = document.getElementById('nav-queue-badge');
    if (size > 0) {
      badge.textContent = size;
      badge.style.display = 'inline-flex';
    } else {
      badge.style.display = 'none';
    }

    // Update queue table
    var tableCard = document.getElementById('queue-table-card');
    var tbody     = document.getElementById('queue-tbody');
    if (queue.length > 0) {
      tableCard.style.display = '';
      tbody.innerHTML = queue.map(function(entry) {
        var statusClass = entry.status === 'processing' ? 'queue-status-processing' : 'queue-status-waiting';
        var statusLabel = entry.status === 'processing' ? 'Processing' : 'Waiting';
        return '<tr>' +
          '<td>' + esc(String(entry.position)) + '</td>' +
          '<td>' + esc(entry.name) + '</td>' +
          '<td><span class="queue-status-badge ' + statusClass + '">' + statusLabel + '</span></td>' +
          '</tr>';
      }).join('');
    } else {
      tableCard.style.display = 'none';
      tbody.innerHTML = '';
    }
  } catch (_) {}
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
