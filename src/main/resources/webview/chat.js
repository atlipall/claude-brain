(function () {
  'use strict';
  const items = new Map(); // id -> element
  const log = document.getElementById('log');
  const input = document.getElementById('input');
  const sendBtn = document.getElementById('send');
  const modeSel = document.getElementById('mode');
  const activityEl = document.getElementById('activity');
  const activityDots = document.getElementById('activity-dots');
  const activityLabel = document.getElementById('activity-label');
  const activitySecs = document.getElementById('activity-secs');
  const activityStop = document.getElementById('activity-stop');
  const restartBtn = document.getElementById('restart');
  const statusEl = document.getElementById('status');
  const checkoutEl = document.getElementById('checkout');
  const contextEl = document.getElementById('context');
  const contextText = document.getElementById('context-text');
  const contextOffBtn = document.getElementById('context-off');
  const attachEl = document.getElementById('attachments');
  const attachBtn = document.getElementById('attach');
  const pending = new Map(); // attachment id -> { el, ready }
  let attachSeq = 0;
  const pendingResolve = new Map();
  let reqSeq = 0;
  let history = [];  // sent messages, oldest first (shared by every tab, kept by the IDE)
  let recall = -1;   // index into history while recalling, -1 otherwise
  let draft = '';    // what the box held before recall started

  const STATUS = { DEAD: 'Stopped' }; // the header says nothing else; the strip above the input shows the turn
  const TOOL_STATUS = { running: 'running', awaiting_permission: 'needs permission', done: 'done', error: 'failed', denied: 'denied' };
  const PATH_RE = /^[\w@.\/~-]*[\w-]\.[A-Za-z0-9]{1,10}(?:(?::|#L)\d+(?:[:-]\d+)?)?$/;
  const MAX_PRE = 6000;
  const MAX_ATTACH = 10;                 // images per message; the IDE enforces it too
  const CHUNK = 262144;                  // base64 characters per bridge message (a multiple of 4)
  const MAX_INPUT = 20 * 1024 * 1024;    // the IDE refuses larger files without reading them
  const DATA_IMG = /^data:image\/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+\/]+=*$/;
  const SANITIZE = {
    FORBID_TAGS: ['style', 'form', 'button', 'input', 'textarea', 'select', 'option', 'iframe', 'object', 'embed', 'link', 'meta', 'base', 'img', 'picture', 'source', 'video', 'audio', 'track', 'svg', 'math'],
    FORBID_ATTR: ['style', 'class', 'id'],
    ALLOW_DATA_ATTR: false,
  };
  // Elements created by h() are the only ones the click handler acts on; model markup is never trusted.
  const trusted = new WeakSet();
  // Markdown images never become <img> in model markup: the renderer leaves a marker (unguessable per page load) that
  // survives sanitizing as text; the page swaps it for alt text, and for a trusted <img> once the IDE resolves the file.
  const IMG_NONCE = Array.from(crypto.getRandomValues(new Uint32Array(2)), (n) => n.toString(36)).join('');
  const IMG_MARK = new RegExp('IMG' + IMG_NONCE + '_(\\d+)_', 'g');
  let imgSlots = null; // the image tokens of the message being parsed
  marked.use({ renderer: { image(token) {
    if (!imgSlots) return '';
    imgSlots.push({ href: String(token.href || ''), alt: String(token.text || '') });
    return 'IMG' + IMG_NONCE + '_' + (imgSlots.length - 1) + '_';
  } } });

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
  // Images are page-created elements whose src is a data: URL the IDE built from validated bytes; anything else is alt text.
  const imageOrAlt = (src, alt) => (typeof src === 'string' && DATA_IMG.test(src) ? h('img', { src, alt }) : h('span', { class: 'img-alt' }, alt));

  window.chat = {
    receive(msg) {
      switch (msg.type) {
        case 'reset': items.clear(); log.textContent = ''; msg.items.forEach(upsert); break;
        case 'items': msg.items.forEach(upsert); break;
        case 'item': upsert(msg.item); break;
        case 'state': setState(msg); break;
        case 'activity': setActivity(msg); break;
        case 'resolved': { const cb = pendingResolve.get(msg.reqId); pendingResolve.delete(msg.reqId); if (cb) cb(msg.paths); break; }
        case 'images': { const cb = pendingResolve.get(msg.reqId); pendingResolve.delete(msg.reqId); if (cb) cb(msg.images || {}); break; }
        case 'insert': insertText(msg.text); break;
        case 'theme': applyTheme(msg); break;
        case 'history': history = Array.isArray(msg.entries) ? msg.entries : []; break;
        case 'checkout': setCheckout(msg.text); break;
        case 'context': setContext(msg.text, msg.off); break;
        case 'attachment': setAttachment(msg); break;
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
      case 'user': renderUser(el, item); break;
      case 'assistant': renderAssistant(el, item); break;
      case 'tool': renderTool(el, item); break;
      case 'result': renderResult(el, item); break;
      case 'notice': el.className = 'msg notice' + (item.isError ? ' error' : ''); el.textContent = item.text; break;
    }
    el.querySelectorAll('details').forEach((d, i) => { if (open[i] !== undefined) d.open = open[i]; });
    if (atBottom) log.scrollTop = log.scrollHeight;
  }

  function renderUser(el, item) {
    el.className = 'msg user';
    const images = Array.isArray(item.images) ? item.images : [];
    if (images.length) el.append(h('div', { class: 'thumbs' }, ...images.map((src) => imageOrAlt(src, '[image]'))));
    if (item.text) el.append(document.createTextNode(item.text)); // plain text only
  }

  function renderAssistant(el, item) {
    el.className = 'msg assistant';
    imgSlots = [];
    let html, slots;
    try { html = marked.parse(item.text || ''); } finally { slots = imgSlots; imgSlots = null; }
    el.innerHTML = DOMPurify.sanitize(html, SANITIZE);
    const images = placeImages(el, slots);
    if (!item.streaming) { linkify(el); resolveImages(images); }
  }

  // Replaces each image marker with its alt text in a trusted span; returns { span, href, alt } for each.
  function placeImages(root, slots) {
    const out = [];
    if (!slots.length) return out;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    for (const node of nodes) {
      const text = node.nodeValue;
      IMG_MARK.lastIndex = 0;
      if (!IMG_MARK.test(text)) continue;
      IMG_MARK.lastIndex = 0;
      const frag = document.createDocumentFragment();
      let last = 0, m;
      while ((m = IMG_MARK.exec(text))) {
        frag.append(text.slice(last, m.index));
        const slot = slots[Number(m[1])];
        if (slot) {
          const span = h('span', { class: 'img-alt' }, slot.alt);
          out.push({ span, href: slot.href, alt: slot.alt });
          frag.append(span);
        }
        last = m.index + m[0].length;
      }
      frag.append(text.slice(last));
      node.replaceWith(frag);
    }
    return out;
  }

  // Only local paths are asked for; URLs (http:, https:, data:, file:, //host) stay alt text and are never fetched.
  const imageKey = (href) => {
    let s = href.trim().replace(/^<(.*)>$/, '$1');
    try { s = decodeURI(s); } catch (e) { /* keep it as written */ }
    return s.replace(/^\.\//, '');
  };

  function resolveImages(images) {
    const wanted = images.filter((x) => x.href && !/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(x.href.trim()));
    if (!wanted.length) return;
    const reqId = ++reqSeq;
    pendingResolve.set(reqId, (found) => {
      for (const x of wanted) {
        const src = Object.prototype.hasOwnProperty.call(found, imageKey(x.href)) ? found[imageKey(x.href)] : null;
        if (!x.span.isConnected) continue;
        const img = imageOrAlt(src, x.alt);
        if (img.tagName !== 'IMG') continue;
        img.className = 'md-img';
        x.span.replaceWith(img);
      }
    });
    send({ type: 'resolveImages', reqId, paths: [...new Set(wanted.map((x) => imageKey(x.href)))] });
  }

  function linkify(root) {
    const codes = [...root.querySelectorAll('code')].filter((c) => !c.closest('pre') && PATH_RE.test(c.textContent.trim()));
    // Markdown links whose target is not a web URL may point at project files.
    const anchors = [...root.querySelectorAll('a[href]')]
      .filter((a) => !trusted.has(a) && !/^(?:https?:|mailto:)/i.test((a.getAttribute('href') || '').trim()))
      .map((a) => ({ a, key: (a.getAttribute('href') || '').trim().replace(/^\.\//, '') }))
      .filter((x) => x.key);
    if (!codes.length && !anchors.length) return;
    const reqId = ++reqSeq;
    pendingResolve.set(reqId, (paths) => {
      for (const c of codes) {
        const r = paths[c.textContent.trim()];
        if (!r || !c.isConnected) continue;
        const a = h('a', { class: 'file', href: '#', 'data-target': r.target, 'data-line': r.line });
        c.replaceWith(a);
        a.appendChild(c);
      }
      for (const { a: old, key } of anchors) {
        const r = paths[key];
        if (!r || !old.isConnected) continue;
        const a = h('a', { class: 'file', href: '#', 'data-target': r.target, 'data-line': r.line });
        while (old.firstChild) a.appendChild(old.firstChild);
        old.replaceWith(a);
      }
    });
    const candidates = [...codes.map((c) => c.textContent.trim()), ...anchors.map((x) => x.key)];
    send({ type: 'resolvePaths', reqId, candidates: [...new Set(candidates)] });
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

  // Claude's question (AskUserQuestion). Every string here is model text and goes in through h() children, which are
  // text nodes; the controls are page-created, so only they can send an answer.
  function questionCard(t) {
    const id = t.permissionRequestId;
    const qs = t.question.questions;
    const picks = qs.map(() => ({ labels: new Set(), other: '' }));
    const submitBtn = h('button', { class: 'q-submit', disabled: true }, 'Submit');
    const answered = (i) => picks[i].labels.size > 0 || picks[i].other.trim() !== '';
    const refresh = () => { if (picks.every((_, i) => answered(i))) submitBtn.removeAttribute('disabled'); else submitBtn.setAttribute('disabled', ''); };
    const blocks = qs.map((q, i) => {
      const multi = q.multiSelect === true;
      const preview = h('pre', { class: 'q-preview', hidden: true });
      const showPreview = (o) => { preview.textContent = o.preview ? clip(o.preview) : ''; preview.hidden = !o.preview; };
      const other = h('input', { class: 'q-other', placeholder: 'Other…', maxlength: 2000 });
      const buttons = [];
      const paint = () => buttons.forEach(({ b, label }) => b.setAttribute('aria-checked', picks[i].labels.has(label) ? 'true' : 'false'));
      for (const o of q.options) {
        const b = h('button', { type: 'button', class: 'q-opt', role: multi ? 'checkbox' : 'radio', 'aria-checked': 'false' },
          h('span', { class: 'q-label' }, o.label),
          o.description ? h('span', { class: 'q-desc' }, o.description) : null);
        buttons.push({ b, label: o.label });
        b.addEventListener('focus', () => showPreview(o));
        b.addEventListener('mouseenter', () => showPreview(o));
        b.addEventListener('click', () => {
          const set = picks[i].labels;
          if (multi) { if (set.has(o.label)) set.delete(o.label); else set.add(o.label); }
          else { set.clear(); set.add(o.label); picks[i].other = ''; other.value = ''; }
          showPreview(o);
          paint();
          refresh();
        });
      }
      other.addEventListener('input', () => {
        picks[i].other = other.value;
        if (!multi && other.value.trim() !== '') { picks[i].labels.clear(); paint(); }
        refresh();
      });
      return h('div', { class: 'q-block' },
        q.header ? h('div', { class: 'q-header' }, q.header) : null,
        h('div', { class: 'q-text' }, q.question),
        h('div', { class: 'q-options', role: multi ? 'group' : 'radiogroup' }, ...buttons.map((x) => x.b)),
        other, preview);
    });
    submitBtn.addEventListener('click', () => {
      if (submitBtn.hasAttribute('disabled') || !picks.every((_, i) => answered(i))) return;
      const answers = Object.create(null); // keeps a question text like __proto__ as an ordinary key
      qs.forEach((q, i) => {
        const other = picks[i].other.trim();
        answers[q.question] = { labels: q.options.map((o) => o.label).filter((l) => picks[i].labels.has(l)), other: other || null };
      });
      submitBtn.setAttribute('disabled', '');
      send({ type: 'answer', requestId: id, answers });
    });
    return h('div', { class: 'question' }, ...blocks,
      h('div', { class: 'perm' }, submitBtn, h('button', { 'data-action': 'skipQuestion', 'data-req': id }, 'Skip')));
  }

  function answerSummary(q) {
    const answers = q.answers || {};
    return h('div', { class: 'q-answers' }, ...q.questions.map((x) =>
      h('div', {}, (x.header || x.question) + ': ' + (answers[x.question] !== undefined ? answers[x.question] : ''))));
  }

  function renderTool(el, t) {
    el.className = 'msg tool status-' + t.status;
    const head = h('div', { class: 'tool-head' }, h('span', { class: 'tool-name' }, t.name));
    if (t.filePath) head.append(h('a', { class: 'file', href: '#', 'data-target': t.filePath, title: t.filePath }, shortPath(t.filePath)));
    else { const s = summary(t); if (s) head.append(h('span', { class: 'tool-summary' }, s)); }
    head.append(h('span', { class: 'badge' }, TOOL_STATUS[t.status] || t.status));
    if (t.hasDiff) head.append(h('button', { class: 'link', 'data-action': 'diff', 'data-id': t.id }, 'View diff'));
    el.append(head);
    const images = Array.isArray(t.images) ? t.images : [];
    if (images.length) el.append(h('div', { class: 'tool-images' }, ...images.map((src) => imageOrAlt(src, '[image]'))));
    if (t.question && t.question.questions) {
      if (t.status === 'awaiting_permission' && t.permissionRequestId) el.append(questionCard(t));
      else if (t.question.answers) el.append(answerSummary(t.question));
      else el.append(h('div', { class: 'q-answers' }, 'Skipped'));
      return;
    }
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
    statusEl.textContent = STATUS[msg.state] || '';
    if (msg.state === 'DEAD') setActivity({ phase: null });
    restartBtn.hidden = msg.state !== 'DEAD';
    input.disabled = sendBtn.disabled = attachBtn.disabled = msg.state === 'DEAD';
    if (msg.mode) modeSel.value = msg.mode;
  }

  // The strip above the input: phase and tool name come from the IDE, shown as text only; the seconds are counted here.
  const PHASE_LABEL = { starting: 'Starting…', thinking: 'Thinking…', writing: 'Writing…', waiting: 'Waiting for your answer' };
  let activityKey = null;
  let activityStart = 0;
  let activityTimer = null;

  function tickActivity() {
    activitySecs.textContent = Math.max(0, Math.floor((Date.now() - activityStart) / 1000)) + 's';
  }

  function stopActivityTimer() {
    if (activityTimer !== null) { clearInterval(activityTimer); activityTimer = null; }
  }

  function setActivity(msg) {
    const phase = typeof msg.phase === 'string' && (msg.phase === 'running' || PHASE_LABEL[msg.phase]) ? msg.phase : null;
    if (!phase) {
      activityKey = null;
      stopActivityTimer();
      activityEl.hidden = true;
      return;
    }
    const tool = phase === 'running' ? (typeof msg.tool === 'string' ? msg.tool.slice(0, 40) : 'tool') : '';
    const key = phase + ':' + tool;
    if (key === activityKey && !activityEl.hidden) return;
    activityKey = key;
    activityLabel.textContent = phase === 'running' ? 'Running ' + tool + '…' : PHASE_LABEL[phase];
    const waiting = phase === 'waiting';
    activityDots.hidden = waiting;
    activityLabel.classList.toggle('shimmer', !waiting);
    activitySecs.hidden = waiting;
    activityEl.hidden = false;
    stopActivityTimer();
    if (!waiting) {
      activityStart = Date.now();
      tickActivity();
      activityTimer = setInterval(tickActivity, 1000);
    }
  }

  // Plain text only: the IDE's checkout name and folder never become markup.
  function setCheckout(text) {
    checkoutEl.textContent = typeof text === 'string' ? text : '';
    checkoutEl.hidden = !checkoutEl.textContent;
  }

  // Plain text only: file and symbol names from the editor never become markup.
  function setContext(text, off) {
    const t = typeof text === 'string' ? text : '';
    const muted = off === true;
    contextEl.hidden = !t;
    contextEl.classList.toggle('off', muted);
    contextEl.title = muted ? '' : t;
    contextText.textContent = muted ? 'context off for next message — click to restore' : t;
    contextOffBtn.hidden = muted;
  }

  function addTile(id, name) {
    const tile = h('div', { class: 'tile loading', title: name },
      h('span', { class: 'img-alt' }, '…'),
      h('button', { class: 'tile-x', 'data-action': 'detach', 'data-id': id, title: 'Remove' }, '×'));
    pending.set(id, { el: tile, ready: false });
    attachEl.append(tile);
    attachEl.hidden = false;
  }

  function removeTile(id) {
    const p = pending.get(id);
    if (!p) return;
    p.el.remove();
    pending.delete(id);
    attachEl.hidden = pending.size === 0;
  }

  // The IDE validated the image: show its thumbnail (or [image]); or it refused it (a notice in the log says why).
  function setAttachment(msg) {
    if (typeof msg.id !== 'string') return;
    if (msg.failed === true) { removeTile(msg.id); return; }
    if (!pending.has(msg.id)) addTile(msg.id, typeof msg.name === 'string' ? msg.name : '');
    if (msg.loading === true) return; // still being prepared: a loading tile that blocks Send
    const p = pending.get(msg.id);
    p.ready = true;
    p.el.classList.remove('loading');
    p.el.firstChild.replaceWith(imageOrAlt(msg.thumb, '[image]'));
  }

  // Bytes go to the IDE as base64 in chunks; the IDE checks them (magic bytes, size) before anything else sees them.
  function upload(file) {
    const id = 'p' + (++attachSeq);
    const name = String(file.name || 'image').slice(0, 100);
    addTile(id, name);
    send({ type: 'attachStart', id, name, size: file.size });
    if (file.size > MAX_INPUT) return; // the IDE refuses it and says why
    if (pending.size > MAX_ATTACH) return; // already full (this tile included): the IDE refuses it and says why
    // Not an image by its reported type: send no bytes; the IDE sees an empty file and says it is not an image.
    if (file.type && !/^image\//.test(file.type)) { send({ type: 'attachEnd', id }); return; }
    const reader = new FileReader();
    reader.onload = () => {
      if (!pending.has(id)) return; // removed meanwhile
      const url = String(reader.result);
      const data = url.slice(url.indexOf(',') + 1);
      for (let i = 0; i < data.length; i += CHUNK) send({ type: 'attachChunk', id, data: data.slice(i, i + CHUNK) });
      send({ type: 'attachEnd', id });
    };
    reader.onerror = () => { send({ type: 'detach', id }); removeTile(id); };
    reader.readAsDataURL(file);
  }

  const hasFiles = (dt) => !!dt && [...(dt.types || [])].includes('Files');

  function insertText(text) {
    recall = -1;
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
    if (input.disabled) return;
    const tiles = [...pending.entries()];
    if (tiles.some(([, p]) => !p.ready)) return; // wait until every image is ready
    const attachments = tiles.map(([id]) => id);
    if (!text && !attachments.length) return;
    send({ type: 'send', text, attachments });
    attachments.forEach(removeTile);
    input.value = '';
    recall = -1;
    draft = '';
  }

  const caretOnFirstLine = () => !input.value.slice(0, input.selectionStart).includes('\n');
  const caretOnLastLine = () => !input.value.slice(input.selectionEnd).includes('\n');

  // Pure recall state machine. s = { recall, draft }; dir = 'up' | 'down'.
  // Returns { recall, draft, text } where text is the new box content, or null when the key is not handled.
  function recallStep(s, dir, entries, value) {
    if (dir === 'up') {
      let r = s.recall, d = s.draft;
      if (r === -1) {
        if (!entries.length) return null;
        d = value;
        r = entries.length;
      }
      r = Math.min(r, entries.length);
      if (r > 0) r--;
      return { recall: r, draft: d, text: entries[r] };
    }
    if (s.recall === -1) return null;
    const r = s.recall + 1;
    if (r >= entries.length) return { recall: -1, draft: s.draft, text: s.draft };
    return { recall: r, draft: s.draft, text: entries[r] };
  }

  function recallKey(dir) {
    const step = recallStep({ recall, draft }, dir, history, input.value);
    if (!step) return false;
    recall = step.recall;
    draft = step.draft;
    // Text goes into the textarea value only, never parsed as HTML.
    input.value = step.text;
    input.setSelectionRange(step.text.length, step.text.length);
    return true;
  }

  input.addEventListener('keydown', (e) => {
    if (e.isComposing || e.keyCode === 229) return;
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); return; }
    if (e.shiftKey || e.altKey || e.ctrlKey || e.metaKey) return;
    if (e.key === 'ArrowUp' && caretOnFirstLine() && recallKey('up')) e.preventDefault();
    else if (e.key === 'ArrowDown' && caretOnLastLine() && recallKey('down')) e.preventDefault();
  });
  // Typing ends recall: the edited text becomes the new draft.
  input.addEventListener('input', () => { recall = -1; });
  sendBtn.addEventListener('click', submit);
  document.getElementById('new-chat').addEventListener('click', () => send({ type: 'newChat' }));
  activityStop.addEventListener('click', () => send({ type: 'interrupt' }));
  restartBtn.addEventListener('click', () => send({ type: 'restart' }));
  modeSel.addEventListener('change', () => send({ type: 'setMode', mode: modeSel.value }));
  contextOffBtn.addEventListener('click', (e) => { e.stopPropagation(); send({ type: 'contextOff', off: true }); });
  contextEl.addEventListener('click', () => { if (contextEl.classList.contains('off')) send({ type: 'contextOff', off: false }); });
  attachBtn.addEventListener('click', () => send({ type: 'pickImages' }));

  // Pasted image data (Cmd+V of a screenshot) anywhere on the page becomes an attachment; text pastes are untouched.
  // A clipboard with text too (spreadsheets and Office put a picture next to the text) pastes into the message box as text.
  document.addEventListener('paste', (e) => {
    if (e.target === input && e.clipboardData && e.clipboardData.getData('text/plain')) return;
    const files = [...((e.clipboardData && e.clipboardData.items) || [])]
      .filter((i) => i.kind === 'file' && /^image\//.test(i.type)).map((i) => i.getAsFile()).filter(Boolean);
    if (!files.length) return;
    e.preventDefault();
    if (!input.disabled) files.forEach(upload);
  });

  // Capture phase: no anchor click or form submit reaches the browser's default navigation.
  document.addEventListener('auxclick', (e) => { if (e.target.closest('a')) e.preventDefault(); }, true);
  document.addEventListener('submit', (e) => e.preventDefault(), true);
  // Capture phase: a dropped file never reaches the browser's default (opening it would navigate the page). Dropped
  // files become attachments; other drops are allowed only into the message box (text).
  document.addEventListener('dragover', (e) => {
    if (hasFiles(e.dataTransfer) || e.target !== input) {
      e.preventDefault();
      if (e.dataTransfer) e.dataTransfer.dropEffect = hasFiles(e.dataTransfer) && !input.disabled ? 'copy' : 'none';
    }
  }, true);
  document.addEventListener('drop', (e) => {
    const files = hasFiles(e.dataTransfer);
    if (!files && e.target === input) return;
    e.preventDefault();
    e.stopPropagation();
    if (files && !input.disabled) [...e.dataTransfer.files].forEach(upload);
  }, true);

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
    else if (action === 'detach') { send({ type: 'detach', id: b.dataset.id }); removeTile(b.dataset.id); }
    else if (action === 'allow' || action === 'allowSession') send({ type: 'permission', requestId: b.dataset.req, decision: action });
    else if (action === 'skipQuestion') send({ type: 'skipQuestion', requestId: b.dataset.req });
    else if (action === 'deny') {
      const reason = b.parentElement.querySelector('.deny-reason').value.trim();
      send({ type: 'permission', requestId: b.dataset.req, decision: 'deny', message: reason || 'The user denied this action.' });
    }
  });

  window.addEventListener('bridge-ready', () => send({ type: 'ready' }));
})();
