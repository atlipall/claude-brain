(function () {
  'use strict';
  const items = new Map(); // id -> element
  const log = document.getElementById('log');
  const input = document.getElementById('input');
  const sendBtn = document.getElementById('send');
  const modeSel = document.getElementById('mode');
  const sessionsSel = document.getElementById('sessions');
  const stopBtn = document.getElementById('stop');
  const restartBtn = document.getElementById('restart');
  const statusEl = document.getElementById('status');
  const pendingResolve = new Map();
  let reqSeq = 0;

  const STATUS = { IDLE: 'Ready', RUNNING: 'Working…', AWAITING_PERMISSION: 'Waiting for your permission', DEAD: 'Stopped' };
  const TOOL_STATUS = { running: 'running', awaiting_permission: 'needs permission', done: 'done', error: 'failed', denied: 'denied' };
  const PATH_RE = /^[\w@.\/~-]*[\w-]\.[A-Za-z0-9]{1,10}(?:(?::|#L)\d+(?:[:-]\d+)?)?$/;
  const MAX_PRE = 6000;
  const SANITIZE = {
    FORBID_TAGS: ['style', 'form', 'button', 'input', 'textarea', 'select', 'option', 'iframe', 'object', 'embed', 'link', 'meta', 'base', 'img', 'picture', 'source', 'video', 'audio', 'track', 'svg', 'math'],
    FORBID_ATTR: ['style', 'class', 'id'],
    ALLOW_DATA_ATTR: false,
  };
  // Elements created by h() are the only ones the click handler acts on; model markup is never trusted.
  const trusted = new WeakSet();

  function send(msg) { if (window.__send) window.__send(JSON.stringify(msg)); }

  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    trusted.add(el);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v; else el.setAttribute(k, v === true ? '' : String(v));
    }
    for (const c of children) if (c !== null && c !== undefined) el.append(c instanceof Node ? c : String(c));
    return el;
  }
  const clip = (s) => (s && s.length > MAX_PRE ? s.slice(0, MAX_PRE) + '\n…' : s || '');
  const shortPath = (p) => p.split('/').slice(-2).join('/');

  window.chat = {
    receive(msg) {
      switch (msg.type) {
        case 'reset': items.clear(); log.textContent = ''; msg.items.forEach(upsert); break;
        case 'item': upsert(msg.item); break;
        case 'state': setState(msg); break;
        case 'sessions': fillSessions(msg.sessions); break;
        case 'resolved': { const cb = pendingResolve.get(msg.reqId); pendingResolve.delete(msg.reqId); if (cb) cb(msg.paths); break; }
        case 'insert': insertText(msg.text); break;
        case 'theme': applyTheme(msg); break;
      }
    }
  };

  function upsert(item) {
    const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
    let el = items.get(item.id);
    if (!el) { el = document.createElement('div'); items.set(item.id, el); log.appendChild(el); }
    const open = [...el.querySelectorAll('details')].map((d) => d.open);
    el.textContent = '';
    switch (item.kind) {
      case 'user': el.className = 'msg user'; el.textContent = item.text; break;
      case 'assistant': renderAssistant(el, item); break;
      case 'tool': renderTool(el, item); break;
      case 'result': renderResult(el, item); break;
      case 'notice': el.className = 'msg notice' + (item.isError ? ' error' : ''); el.textContent = item.text; break;
    }
    el.querySelectorAll('details').forEach((d, i) => { if (open[i] !== undefined) d.open = open[i]; });
    if (atBottom) log.scrollTop = log.scrollHeight;
  }

  function renderAssistant(el, item) {
    el.className = 'msg assistant';
    el.innerHTML = DOMPurify.sanitize(marked.parse(item.text || ''), SANITIZE);
    if (!item.streaming) linkify(el);
  }

  function linkify(root) {
    const codes = [...root.querySelectorAll('code')].filter((c) => !c.closest('pre') && PATH_RE.test(c.textContent.trim()));
    if (!codes.length) return;
    const reqId = ++reqSeq;
    pendingResolve.set(reqId, (paths) => {
      for (const c of codes) {
        const r = paths[c.textContent.trim()];
        if (!r || !c.isConnected) continue;
        const a = h('a', { class: 'file', href: '#', 'data-target': r.target, 'data-line': r.line });
        c.replaceWith(a);
        a.appendChild(c);
      }
    });
    send({ type: 'resolvePaths', reqId, candidates: [...new Set(codes.map((c) => c.textContent.trim()))] });
  }

  function summary(t) {
    const i = t.input || {};
    if (t.name === 'Bash') return (i.command || '').split('\n')[0];
    return i.pattern || i.url || i.description || i.query || '';
  }

  function inputDetails(t) {
    const i = t.input || {};
    const open = t.status === 'awaiting_permission';
    let body;
    if (t.name === 'Edit') body = [h('pre', { class: 'del' }, clip(i.old_string)), h('pre', { class: 'add' }, clip(i.new_string))];
    else if (t.name === 'MultiEdit') body = (i.edits || []).flatMap((e) => [h('pre', { class: 'del' }, clip(e.old_string)), h('pre', { class: 'add' }, clip(e.new_string))]);
    else if (t.name === 'Write') body = [h('pre', { class: 'add' }, clip(i.content))];
    else if (t.name === 'Bash') body = [h('pre', {}, clip(i.command))];
    else if (Object.keys(i).length) body = [h('pre', {}, clip(JSON.stringify(i, null, 2)))];
    else return null;
    return h('details', { open }, h('summary', {}, 'Input'), ...body);
  }

  function permissionCard(t) {
    const id = t.permissionRequestId;
    return h('div', { class: 'perm' },
      h('span', {}, 'Allow ' + t.name + '?'),
      h('button', { 'data-action': 'allow', 'data-req': id }, 'Allow'),
      h('button', { 'data-action': 'allowSession', 'data-req': id }, 'Allow for session'),
      h('input', { class: 'deny-reason', placeholder: 'Reason (optional)' }),
      h('button', { 'data-action': 'deny', 'data-req': id }, 'Deny'));
  }

  function renderTool(el, t) {
    el.className = 'msg tool status-' + t.status;
    const head = h('div', { class: 'tool-head' }, h('span', { class: 'tool-name' }, t.name));
    if (t.filePath) head.append(h('a', { class: 'file', href: '#', 'data-target': t.filePath, title: t.filePath }, shortPath(t.filePath)));
    else { const s = summary(t); if (s) head.append(h('span', { class: 'tool-summary' }, s)); }
    head.append(h('span', { class: 'badge' }, TOOL_STATUS[t.status] || t.status));
    if (t.hasDiff) head.append(h('button', { class: 'link', 'data-action': 'diff', 'data-id': t.id }, 'View diff'));
    el.append(head);
    const details = inputDetails(t);
    if (details) el.append(details);
    if (t.status === 'awaiting_permission' && t.permissionRequestId) el.append(permissionCard(t));
    if (t.resultPreview) el.append(h('details', {}, h('summary', {}, 'Result'), h('pre', {}, clip(t.resultPreview))));
  }

  function renderResult(el, r) {
    el.className = 'msg result' + (r.isError ? ' error' : '');
    if (r.isError) { el.textContent = r.text || 'Error'; return; }
    const parts = [];
    if (r.durationMs != null) parts.push((r.durationMs / 1000).toFixed(1) + 's');
    if (r.costUsd != null) parts.push('$' + r.costUsd.toFixed(2));
    el.textContent = 'Done' + (parts.length ? ' · ' + parts.join(' · ') : '');
  }

  function setState(msg) {
    statusEl.textContent = STATUS[msg.state] || msg.state;
    const busy = msg.state === 'RUNNING' || msg.state === 'AWAITING_PERMISSION';
    stopBtn.hidden = !busy;
    restartBtn.hidden = msg.state !== 'DEAD';
    input.disabled = sendBtn.disabled = msg.state === 'DEAD';
    if (msg.mode) modeSel.value = msg.mode;
  }

  function fillSessions(sessions) {
    sessionsSel.textContent = '';
    sessionsSel.append(h('option', { value: '' }, sessions.length ? 'History…' : 'No past sessions'));
    for (const s of sessions) {
      const when = new Date(s.modified).toLocaleString();
      sessionsSel.append(h('option', { value: s.id, title: when }, s.title));
    }
  }

  function insertText(text) {
    const start = input.selectionStart, end = input.selectionEnd;
    const before = input.value.slice(0, start), after = input.value.slice(end);
    const sep = before && !/\s$/.test(before) ? ' ' : '';
    input.value = before + sep + text + ' ' + after;
    const pos = (before + sep + text + ' ').length;
    input.setSelectionRange(pos, pos);
    input.focus();
  }

  function applyTheme(t) {
    const s = document.documentElement.style;
    s.setProperty('--bg', t.bg); s.setProperty('--fg', t.fg); s.setProperty('--muted', t.muted);
    s.setProperty('--border', t.border); s.setProperty('--accent', t.accent); s.setProperty('--code-bg', t.codeBg);
    s.setProperty('--font', '"' + t.font + '", system-ui, sans-serif'); s.setProperty('--font-size', t.fontSize + 'px');
    s.setProperty('--add', t.dark ? '#2f5a3a' : '#d7f5dd'); s.setProperty('--del', t.dark ? '#5a2f2f' : '#f9d7d7');
  }

  function submit() {
    const text = input.value.trim();
    if (!text || input.disabled) return;
    send({ type: 'send', text });
    input.value = '';
  }

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); submit(); }
  });
  sendBtn.addEventListener('click', submit);
  document.getElementById('new-chat').addEventListener('click', () => send({ type: 'newChat' }));
  stopBtn.addEventListener('click', () => send({ type: 'interrupt' }));
  restartBtn.addEventListener('click', () => send({ type: 'restart' }));
  modeSel.addEventListener('change', () => send({ type: 'setMode', mode: modeSel.value }));
  sessionsSel.addEventListener('mousedown', () => send({ type: 'listSessions' }));
  sessionsSel.addEventListener('focus', () => send({ type: 'listSessions' }));
  sessionsSel.addEventListener('change', () => {
    const id = sessionsSel.value;
    sessionsSel.value = '';
    if (id) send({ type: 'resume', sessionId: id });
  });

  // Capture phase: no anchor click or form submit reaches the browser's default navigation.
  document.addEventListener('auxclick', (e) => { if (e.target.closest('a')) e.preventDefault(); }, true);
  document.addEventListener('submit', (e) => e.preventDefault(), true);

  document.addEventListener('click', (e) => {
    const a = e.target.closest('a');
    if (a) {
      e.preventDefault();
      if (a.classList.contains('file')) {
        if (trusted.has(a)) send({ type: 'openFile', target: a.dataset.target, line: a.dataset.line ? Number(a.dataset.line) : null });
      } else if (/^https?:/i.test(a.getAttribute('href') || '')) send({ type: 'openUrl', url: a.href });
      return;
    }
    const b = e.target.closest('button[data-action]');
    if (!b || !trusted.has(b)) return;
    const action = b.dataset.action;
    if (action === 'diff') send({ type: 'openDiff', toolUseId: b.dataset.id });
    else if (action === 'allow' || action === 'allowSession') send({ type: 'permission', requestId: b.dataset.req, decision: action });
    else if (action === 'deny') {
      const reason = b.parentElement.querySelector('.deny-reason').value.trim();
      send({ type: 'permission', requestId: b.dataset.req, decision: 'deny', message: reason || 'The user denied this action.' });
    }
  });

  window.addEventListener('bridge-ready', () => send({ type: 'ready' }));
})();
