(function () {
  'use strict';
  /** The element of every item shown, by item id. */
  const items = new Map();
  /** The scroller. */
  const log = document.getElementById('log');
  /** What [log] scrolls: the items. */
  const logContent = document.getElementById('log-content');
  const toBottom = document.getElementById('to-bottom');
  const input = document.getElementById('input');
  const sendBtn = document.getElementById('send');
  const modeSel = document.getElementById('mode');
  const modeLabel = document.getElementById('mode-label');
  const modelBtn = document.getElementById('model');
  const modelLabel = document.getElementById('model-label');
  const effortLabel = document.getElementById('effort-label');
  const modelDialog = document.getElementById('model-dialog');
  const modelOptions = document.getElementById('model-options');
  const effortSection = document.getElementById('effort-section');
  const effortOptions = document.getElementById('effort-options');
  const activityEl = document.getElementById('activity');
  const activityDots = document.getElementById('activity-dots');
  const activityLabel = document.getElementById('activity-label');
  const activitySecs = document.getElementById('activity-secs');
  const activityStop = document.getElementById('activity-stop');
  const restartBtn = document.getElementById('restart');
  const statusEl = document.getElementById('status');
  const checkoutEl = document.getElementById('checkout');
  const usageEl = document.getElementById('usage');
  const usageRing = document.getElementById('usage-ring');
  const usagePct = document.getElementById('usage-pct');
  const queueEl = document.getElementById('queue');
  const attachEl = document.getElementById('attachments');
  const attachBtn = document.getElementById('attach');
  /** The tiles in the attachment tray, by attachment id: { el, ready }. */
  const pending = new Map();
  let attachSeq = 0;
  const pendingResolve = new Map();
  let reqSeq = 0;
  /** Sent messages, oldest first: shared by every tab and kept by the IDE. */
  let history = [];
  /** Index into [history] while recalling, -1 otherwise. */
  let recall = -1;
  /** What the box held before recall started. */
  let draft = '';
  /** The predicted next prompt shown in the empty box; Tab takes it. */
  let suggestion = '';
  /** The last state was IDLE: a suggestion that lands later than a new turn's state is stale. */
  let idle = false;

  /** The header says nothing else; the strip above the input shows the turn. */
  const STATUS = { DEAD: 'Stopped' };
  const TOOL_STATUS = { running: 'running', awaiting_permission: 'needs permission', done: 'done', error: 'failed', denied: 'denied' };
  const PATH_RE = /^[\w@.\/~-]*[\w-]\.[A-Za-z0-9]{1,10}(?:(?::|#L)\d+(?:[:-]\d+)?)?$/;
  const MAX_PRE = 6000;
  /** Images per message; the IDE enforces it too. */
  const MAX_ATTACH = 10;
  /** Base64 characters per bridge message (a multiple of 4). */
  const CHUNK = 262144;
  /** The IDE refuses larger files without reading them. */
  const MAX_INPUT = 20 * 1024 * 1024;
  const DATA_IMG = /^data:image\/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+\/]+=*$/;
  const SANITIZE = {
    FORBID_TAGS: ['style', 'form', 'button', 'input', 'textarea', 'select', 'option', 'iframe', 'object', 'embed', 'link', 'meta', 'base', 'img', 'picture', 'source', 'video', 'audio', 'track', 'svg', 'math'],
    FORBID_ATTR: ['style', 'class', 'id'],
    ALLOW_DATA_ATTR: false,
  };
  /** Elements created by h(): the only ones the click handler acts on, as model markup is never trusted. */
  const trusted = new WeakSet();
  /**
   * Markdown images never become <img> in model markup: the renderer leaves a marker (unguessable per page load) that
   * survives sanitizing as text; the page swaps it for alt text, and for a trusted <img> once the IDE resolves the file.
   */
  const IMG_NONCE = Array.from(crypto.getRandomValues(new Uint32Array(2)), (n) => n.toString(36)).join('');
  const IMG_MARK = new RegExp('IMG' + IMG_NONCE + '_(\\d+)_', 'g');
  /** The image tokens of the message being parsed. */
  let imgSlots = null;
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
  /** A page-created image whose src is a data: URL the IDE built from validated bytes; anything else becomes alt text. */
  const imageOrAlt = (src, alt) => (typeof src === 'string' && DATA_IMG.test(src) ? h('img', { src, alt }) : h('span', { class: 'img-alt' }, alt));

  window.chat = {
    receive(msg) {
      switch (msg.type) {
        case 'reset':
          items.clear(); childBoxes.clear(); waitingFor.clear(); waitingOn.clear(); parentOf.clear(); reports.clear();
          logContent.textContent = '';
          follow();
          msg.items.forEach(upsert);
          break;
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
        case 'usage': setUsage(msg); break;
        case 'model': setModel(msg); break;
        case 'attachment': setAttachment(msg); break;
        case 'suggestion': setSuggestion(msg.text); break;
        case 'queue': setQueue(msg.items); break;
      }
    }
  };

  /**
   * Following: the log stays pinned to its latest line. It is a state, not re-measured on each update, because content
   * also grows outside updates (an image decoding, a markdown image resolving later) and that is not the user leaving.
   * Only the user scrolling up turns it off; scrolling back down to the bottom, or the arrow, turns it on again.
   */
  let following = true;
  let lastTop = 0;
  /** Pixels: this close to the bottom counts as at it. */
  const NEAR_BOTTOM = 40;
  const gap = () => log.scrollHeight - log.scrollTop - log.clientHeight;
  function pin() {
    log.scrollTop = log.scrollHeight;
    lastTop = log.scrollTop;
  }
  function showArrow() {
    const hide = following || gap() < NEAR_BOTTOM;
    if (toBottom.hidden !== hide) toBottom.hidden = hide;
  }
  function follow(smooth) {
    following = true;
    if (smooth && !matchMedia('(prefers-reduced-motion: reduce)').matches) log.scrollTo({ top: log.scrollHeight, behavior: 'smooth' }); else pin();
    showArrow();
  }

  /**
   * Moving up while not at the very bottom is the user leaving, so following stops; a shrink of the log that clamps
   * scrollTop also moves it up but leaves the gap at 0, so it does not count. Reaching the bottom again resumes it.
   */
  function trackFollowing() {
    const top = log.scrollTop, g = gap();
    if (top < lastTop - 1 && g > 1) following = false;
    else if (top >= lastTop && g < NEAR_BOTTOM) following = true;
    lastTop = top;
    showArrow();
  }
  log.addEventListener('scroll', trackFollowing, { passive: true });

  /**
   * The log's box shrinks when the activity strip, the image tray or a taller message box appears, and its content grows
   * with every update, image and expanded detail: while following, either keeps the latest line in view.
   */
  const resized = new ResizeObserver(() => {
    if (following) pin();
    showArrow();
  });
  resized.observe(log);
  resized.observe(logContent);

  toBottom.addEventListener('click', () => follow(true));

  /** The container of each subagent card's items, by card id: it survives the card's re-renders. */
  const childBoxes = new Map();
  /** Elements waiting for their card to be shown, by card id. */
  const waitingFor = new Map();
  /** The card id each waiting element waits for. */
  const waitingOn = new Map();
  /** The card id of each item that has one. */
  const parentOf = new Map();
  /** Each card's { text, node }: a report is rendered once, not on every progress update. */
  const reports = new Map();
  /** An Agent/Task call is shown as a card whose container holds its subagent's items. */
  const isAgentCall = (item) => item.kind === 'tool' && (item.name === 'Agent' || item.name === 'Task');

  function childBox(id) {
    let box = childBoxes.get(id);
    if (!box) { box = h('div', { class: 'agent-children' }); childBoxes.set(id, box); }
    return box;
  }

  /** Puts [el] in its card's container ([logContent] without a parent), or has it wait until the card is shown. */
  function place(el, parentId) {
    const waited = waitingOn.get(el);
    if (waited !== undefined && waited !== parentId) {
      waitingOn.delete(el);
      const list = (waitingFor.get(waited) || []).filter((x) => x !== el);
      if (list.length) waitingFor.set(waited, list); else waitingFor.delete(waited);
    }
    const box = parentId ? childBoxes.get(parentId) : logContent;
    if (!box) {
      el.remove();
      if (waitingOn.get(el) !== parentId) {
        waitingOn.set(el, parentId);
        waitingFor.set(parentId, (waitingFor.get(parentId) || []).concat(el));
      }
      return;
    }
    if (el.parentNode !== box) box.appendChild(el);
  }

  /** The <details> of [el] itself: a card's own are only its Prompt; those in its container belong to the items there. */
  const ownDetails = (el) => el.classList.contains('agent') ? el.querySelectorAll(':scope > .agent-body > details') : el.querySelectorAll('details');

  function upsert(item) {
    let el = items.get(item.id);
    if (!el) { el = document.createElement('div'); items.set(item.id, el); }
    const parentId = typeof item.parentId === 'string' && item.parentId ? item.parentId : null;
    if (parentId) parentOf.set(item.id, parentId); else parentOf.delete(item.id);
    place(el, parentId);
    const wasWaiting = el.classList.contains('status-awaiting_permission');
    const open = [...ownDetails(el)].map((d) => d.open);
    el.textContent = '';
    switch (item.kind) {
      case 'user': renderUser(el, item); break;
      case 'assistant': renderAssistant(el, item); break;
      case 'tool': if (isAgentCall(item)) renderAgent(el, item); else renderTool(el, item); break;
      case 'result': renderResult(el, item); break;
      case 'notice': el.className = 'msg notice' + (item.isError ? ' error' : ''); el.textContent = item.text; break;
    }
    ownDetails(el).forEach((d, i) => { if (open[i] !== undefined) d.open = open[i]; });
    const startedWaiting = item.kind === 'tool' && item.status === 'awaiting_permission' && !wasWaiting;
    if (startedWaiting) expandAgentsFrom(parentId);
  }

  /** Opens the card [id] and every card around it, so a subagent that starts waiting for an answer comes into view. */
  function expandAgentsFrom(id) {
    for (let p = id; p; p = parentOf.get(p)) expandAgent(p);
  }

  /** A user message: its text is plain, never markup. */
  function renderUser(el, item) {
    el.className = 'msg user';
    if (item.revertable) {
      el.append(h('button', { class: 'link revert', 'data-action': 'revertTo', 'data-id': item.id,
        title: 'Revert the files, the conversation or both to before this message (asks first)' }, 'Revert to here'));
    }
    const images = Array.isArray(item.images) ? item.images : [];
    if (images.length) el.append(h('div', { class: 'thumbs' }, ...images.map((src) => imageOrAlt(src, '[image]'))));
    if (item.text) el.append(document.createTextNode(item.text));
  }

  /**
   * Messages sent while Claude works wait here, under the log, until their turn starts; then they enter the log.
   * Only a text message can be edited: its images could not go back to the tray.
   */
  function setQueue(list) {
    const queued = Array.isArray(list) ? list : [];
    queueEl.textContent = '';
    for (const m of queued) {
      if (!m || typeof m.id !== 'string') continue;
      const editable = !(Array.isArray(m.images) && m.images.length);
      const el = h('div');
      renderUser(el, m);
      el.classList.add('queued');
      el.prepend(h('div', { class: 'queued-bar' },
          h('span', { class: 'queued-label' }, 'Queued'),
          h('button', { class: 'link', 'data-action': 'queueSendNow', 'data-id': m.id, title: 'Stop Claude and send this now' }, 'Send now'),
          editable ? h('button', { class: 'link', 'data-action': 'queueEdit', 'data-id': m.id, title: 'Move back into the message box' }, 'Edit') : null,
          h('button', { class: 'link', 'data-action': 'queueRemove', 'data-id': m.id, title: 'Don’t send this' }, 'Remove')));
      queueEl.append(el);
    }
    queueEl.hidden = !queueEl.childElementCount;
  }

  /** Model markdown into [el]: parsed, sanitized, with images as markers until the IDE resolves them. */
  function renderMarkdown(el, text, streaming) {
    imgSlots = [];
    let html, slots;
    try { html = marked.parse(text || ''); } finally { slots = imgSlots; imgSlots = null; }
    el.innerHTML = DOMPurify.sanitize(html, SANITIZE);
    const images = placeImages(el, slots);
    if (!streaming) { linkify(el); resolveImages(images); }
  }

  function renderAssistant(el, item) {
    el.className = 'msg assistant';
    renderMarkdown(el, item.text, item.streaming);
    el.toggleAttribute('aria-busy', !!item.streaming);
  }

  /** Replaces each image marker with its alt text in a trusted span; returns { span, href, alt } for each. */
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

  const decodedOrAsWritten = (s) => { try { return decodeURI(s); } catch (e) { return s; } };
  /** The path the IDE is asked for, from a markdown image's href. */
  const imageKey = (href) => decodedOrAsWritten(href.trim().replace(/^<(.*)>$/, '$1')).replace(/^\.\//, '');

  /** Only local paths are asked for; URLs (http:, https:, data:, file:, //host) stay alt text and are never fetched. */
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

  /**
   * Turns inline code that looks like a path, and markdown links whose target is not a web URL (those may point at
   * project files), into file links for the paths the IDE resolves.
   */
  function linkify(root) {
    const codes = [...root.querySelectorAll('code')].filter((c) => !c.closest('pre') && PATH_RE.test(c.textContent.trim()));
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

  const FILE_TOOLS = new Set(['Read', 'Edit', 'MultiEdit', 'Write', 'NotebookEdit']);
  const firstLine = (s) => String(s || '').split('\n').find((l) => l.trim() !== '') || '';

  /** One-line description of a tool call as plain text (file tools are shown as a file link instead; see renderToolRow). */
  function toolDescription(t) {
    const i = t.input || {};
    const str = (v) => (typeof v === 'string' ? v : '');
    switch (t.name) {
      case 'Bash': return firstLine(str(i.description)).trim() || firstLine(str(i.command)).trim();
      case 'Grep': return str(i.pattern) ? str(i.pattern) + (str(i.path) ? ' in ' + str(i.path) : '') : '';
      case 'Glob': return str(i.pattern);
      case 'WebFetch': {
        try { const u = new URL(str(i.url)); return u.host + (u.pathname === '/' ? '' : u.pathname); } catch (e) { return str(i.url); }
      }
      case 'WebSearch': return str(i.query);
      case 'Task': case 'Agent': return str(i.description);
      case 'TodoWrite': return 'Update todos';
      default:
        for (const v of Object.values(i)) if (typeof v === 'string' && v.trim() !== '') return firstLine(v).trim();
        return '';
    }
  }

  /** [label, nodes] for the input of a tool call, or null when there is none. */
  function inputBody(t) {
    const i = t.input || {};
    if (t.name === 'Edit') return ['Input', [h('pre', { class: 'del' }, clip(i.old_string)), h('pre', { class: 'add' }, clip(i.new_string))]];
    if (t.name === 'MultiEdit') return ['Input', (i.edits || []).flatMap((e) => [h('pre', { class: 'del' }, clip(e.old_string)), h('pre', { class: 'add' }, clip(e.new_string))])];
    if (t.name === 'Write') return ['Input', [h('pre', { class: 'add' }, clip(i.content))]];
    if (t.name === 'Bash') return ['Command', [h('pre', {}, clip(i.command))]];
    if (Object.keys(i).length) return ['Input', [h('pre', {}, clip(JSON.stringify(i, null, 2)))]];
    return null;
  }

  function inputDetails(t) {
    const body = inputBody(t);
    if (!body) return null;
    return h('details', { open: t.status === 'awaiting_permission' }, h('summary', {}, 'Input'), ...body[1]);
  }

  /** Tool rows and turn file lists the user has expanded, by item id, so a re-render of the same item keeps them open. */
  const expandedTools = new Set();
  const STATUS_ICON = { done: '\u2713', error: '\u2717', denied: '\u2298' };
  /** The icon cell of a tool or agent row (a spinner while running), named for screen readers by [TOOL_STATUS]. */
  const statusIcon = (status) => {
    const label = TOOL_STATUS[status] || null;
    const icon = status === 'running' ? h('span', { class: 'spin' }) : (STATUS_ICON[status] || '');
    return h('span', { class: 'tool-icon', role: label ? 'img' : null, 'aria-label': label }, icon);
  };

  /**
   * The disclosure button of an expandable row. The row itself stays a plain element (clicking anywhere on it still
   * toggles) because it can hold links and buttons, which may not sit inside a button.
   */
  const rowToggle = (name) => h('span', { class: 'tool-toggle', role: 'button', tabindex: '0', 'aria-label': 'Details of ' + name });

  /** Shows or hides the details right after [row]: a tool row's .tool-detail, or an agent card's .agent-body. */
  function setRowExpanded(row, open) {
    const toggle = row.querySelector('.tool-toggle');
    if (toggle) { toggle.setAttribute('aria-expanded', open ? 'true' : 'false'); toggle.textContent = open ? '\u25BE' : '\u25B8'; }
    const detail = row.nextElementSibling;
    if (detail && (detail.classList.contains('tool-detail') || detail.classList.contains('agent-body'))) detail.hidden = !open;
  }

  function toggleRow(row) {
    const id = row.dataset.id;
    const toggle = row.querySelector('.tool-toggle');
    const open = !toggle || toggle.getAttribute('aria-expanded') !== 'true';
    if (open) expandedTools.add(id); else expandedTools.delete(id);
    setRowExpanded(row, open);
  }

  function renderToolRow(el, t) {
    const body = inputBody(t);
    const hasDetails = !!body || !!t.resultPreview;
    const path = FILE_TOOLS.has(t.name) ? (t.filePath || (t.input && (t.input.file_path || t.input.notebook_path))) : null;
    const desc = h('span', { class: 'tool-desc' });
    if (typeof path === 'string' && path) desc.append(h('a', { class: 'file', href: '#', 'data-target': path, title: path }, shortPath(path)));
    else { const d = toolDescription(t); desc.textContent = d; if (d) desc.title = d; }
    const side = h('span', { class: 'tool-side' });
    if (t.status === 'error') side.append(h('span', { class: 'tool-failed' }, ' \u00b7 failed'));
    if (t.hasDiff) side.append(h('button', { class: 'link', 'data-action': 'diff', 'data-id': t.id }, 'View diff'));
    if (hasDetails) side.append(rowToggle(t.name));
    const row = h('div', { class: 'tool-row' + (hasDetails ? ' expandable' : ''), 'data-id': t.id },
      statusIcon(t.status), h('span', { class: 'tool-name' }, t.name), desc, side);
    el.append(row);
    if (hasDetails) {
      const detail = h('div', { class: 'tool-detail', hidden: true });
      if (body) detail.append(h('div', { class: 'tool-label' }, body[0]), ...body[1]);
      if (t.resultPreview) detail.append(h('div', { class: 'tool-label' }, 'Result'), h('pre', {}, clip(t.resultPreview)));
      el.append(detail);
      setRowExpanded(row, expandedTools.has(t.id));
    }
    const images = Array.isArray(t.images) ? t.images : [];
    if (images.length) el.append(h('div', { class: 'tool-images' }, ...images.map((src) => imageOrAlt(src, '[image]'))));
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

  /** A map keyed by model text: a question like "__proto__" stays an ordinary key. */
  const plainMap = () => Object.create(null);

  /**
   * Claude's question (AskUserQuestion). Every string here is model text and goes in through h() children, which are
   * text nodes; the controls are page-created, so only they can send an answer.
   */
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
      const answers = plainMap();
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
    if (!(t.question && t.question.questions) && t.status !== 'awaiting_permission') { el.classList.add('compact'); renderToolRow(el, t); return; }
    el.classList.remove('compact');
    const head = h('div', { class: 'tool-head' }, h('span', { class: 'tool-name' }, t.name));
    if (t.filePath) head.append(h('a', { class: 'file', href: '#', 'data-target': t.filePath, title: t.filePath }, shortPath(t.filePath)));
    else { const s = toolDescription(t); if (s) head.append(h('span', { class: 'tool-summary' }, s)); }
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

  /** running | done | failed: from the task events, else (an older CLI) from the call's own status. */
  function agentState(t) {
    const s = t.agent && t.agent.state;
    if (s === 'running' || s === 'done' || s === 'failed') return s;
    if (t.status === 'done') return 'done';
    return t.status === 'running' || t.status === 'awaiting_permission' ? 'running' : 'failed';
  }

  function agentSide(a, state) {
    if (!a) return '';
    const parts = [];
    if (state === 'running' && typeof a.lastTool === 'string' && a.lastTool) parts.push(a.lastTool.slice(0, 40));
    if (Number.isInteger(a.toolUses)) parts.push(a.toolUses + (a.toolUses === 1 ? ' tool' : ' tools'));
    if (Number.isFinite(a.durationMs)) parts.push(Math.round(a.durationMs / 1000) + 's');
    return parts.join(' \u00b7 ');
  }

  function reportNode(id, text) {
    const cached = reports.get(id);
    if (cached && cached.text === text) return cached.node;
    const node = h('div');
    renderMarkdown(node, text, false);
    reports.set(id, { text, node });
    return node;
  }

  /**
   * A subagent card: a head row like a tool row, and a body with the prompt, the container of the subagent's items and
   * its report. An older CLI sends no report; then the call's result is the best there is.
   */
  function renderAgent(el, t) {
    const a = t.agent && typeof t.agent === 'object' ? t.agent : null;
    const state = agentState(t);
    el.className = 'msg agent agent-' + state;
    const desc = toolDescription(t);
    const head = h('div', { class: 'tool-row agent-head expandable', 'data-id': t.id },
      statusIcon(state === 'failed' ? 'error' : state),
      h('span', { class: 'tool-name' }, t.name),
      h('span', { class: 'tool-desc', title: desc || null }, desc),
      a && typeof a.subagentType === 'string' && a.subagentType ? h('span', { class: 'agent-tag' }, a.subagentType.slice(0, 40)) : null,
      a && a.background === true ? h('span', { class: 'agent-tag' }, 'background') : null,
      h('span', { class: 'tool-side' }, agentSide(a, state), ' ', rowToggle(t.name)));
    const body = h('div', { class: 'agent-body' });
    if (a && typeof a.prompt === 'string' && a.prompt) body.append(h('details', {}, h('summary', {}, 'Prompt'), h('pre', {}, clip(a.prompt))));
    const box = childBox(t.id);
    body.append(box);
    if (a && typeof a.report === 'string' && a.report) {
      body.append(h('div', { class: 'agent-report' }, h('div', { class: 'tool-label' }, 'Report'), reportNode(t.id, a.report)));
    } else if (!a && t.resultPreview) {
      body.append(h('details', {}, h('summary', {}, 'Result'), h('pre', {}, clip(t.resultPreview))));
    }
    el.append(head, body);
    setRowExpanded(head, expandedTools.has(t.id));
    if (t.status === 'awaiting_permission' && t.permissionRequestId) el.append(permissionCard(t));
    const waiting = waitingFor.get(t.id);
    if (waiting) {
      waitingFor.delete(t.id);
      waiting.forEach((c) => { waitingOn.delete(c); box.appendChild(c); });
    }
  }

  function expandAgent(id) {
    expandedTools.add(id);
    const el = items.get(id);
    const head = el && el.querySelector(':scope > .agent-head');
    if (head) setRowExpanded(head, true);
  }

  /** A longer list shows this many of its files until "Show all" is clicked. */
  const TURN_FILES_SHOWN = 5;
  const turnFilesLabel = (all, count) => all ? 'Show fewer' : 'Show all ' + count + ' files';

  function renderResult(el, r) {
    el.className = 'msg result' + (r.isError ? ' error' : '');
    const files = Array.isArray(r.files) ? r.files : [];
    const line = h('div', { class: 'result-line' });
    if (r.isError) line.textContent = r.text || 'This turn failed.';
    else {
      const parts = [];
      if (r.durationMs != null) parts.push((r.durationMs / 1000).toFixed(1) + 's');
      line.textContent = 'Done' + (parts.length ? ' · ' + parts.join(' · ') : '');
    }
    el.append(line);
    if (!files.length) return;
    const sum = (key) => files.reduce((n, f) => n + (Number(f[key]) || 0), 0);
    line.append(' · ', files.length + (files.length === 1 ? ' file' : ' files') + ' changed ',
      lineCounts(sum('added'), sum('removed')), ' · ',
      h('button', { class: 'link', 'data-action': 'turnChanges', 'data-id': r.id }, 'View all changes'));
    const all = files.length <= TURN_FILES_SHOWN || expandedTools.has(r.id);
    const list = h('div', { class: 'tool-detail turn-files' + (all ? '' : ' clipped') });
    files.forEach((f, i) => {
      const path = String(f.path || '');
      const slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
      const dir = slash < 0 ? '' : path.slice(0, slash).split(/[\\/]/).pop();
      list.append(h('div', { class: 'tool-row' + (i >= TURN_FILES_SHOWN ? ' extra' : ''), role: 'button', tabindex: '0', title: path, 'data-action': 'turnDiff', 'data-id': r.id, 'data-index': String(i) },
        h('span', { class: 'tool-desc' }, h('span', { class: 'turn-file-name' }, path.slice(slash + 1)), dir ? ' ' + dir + '/' : '', f.isNew ? h('span', { class: 'turn-file-new' }, ' new') : ''),
        h('span', { class: 'tool-side' }, lineCounts(Number(f.added) || 0, Number(f.removed) || 0))));
    });
    if (files.length > TURN_FILES_SHOWN) {
      list.append(h('button', { class: 'link turn-files-more', 'data-action': 'turnFiles', 'data-id': r.id }, turnFilesLabel(all, files.length)));
    }
    el.append(list);
  }

  function lineCounts(added, removed) {
    return h('span', { class: 'line-counts' },
      added ? h('span', { class: 'added' }, '+' + added) : '', added && removed ? ' ' : '',
      removed ? h('span', { class: 'removed' }, '\u2212' + removed) : '');
  }

  function toggleTurnFiles(button) {
    const list = button.closest('.turn-files');
    const all = !list.classList.toggle('clipped');
    if (all) expandedTools.add(button.dataset.id); else expandedTools.delete(button.dataset.id);
    button.textContent = turnFilesLabel(all, list.querySelectorAll('.tool-row').length);
  }

  function openTurnDiff(target) {
    send({ type: 'openTurnDiff', id: target.dataset.id, index: Number(target.dataset.index) || 0 });
  }

  function setState(msg) {
    statusEl.textContent = STATUS[msg.state] || '';
    if (msg.state === 'DEAD') setActivity({ phase: null });
    restartBtn.hidden = msg.state !== 'DEAD';
    input.disabled = sendBtn.disabled = attachBtn.disabled = msg.state === 'DEAD';
    if (msg.mode && msg.mode !== modeSel.value) { modeSel.value = msg.mode; showMode(); }
    idle = msg.state === 'IDLE';
    if (!idle) setSuggestion('');
  }

  /**
   * The model a choice runs, short enough for the header: the CLI's descriptions lead with it ("Opus 5.5 · Most
   * capable…"), which says more than a name like "Default (recommended)"; the name when there is no description.
   */
  const shortModelName = (o) => (o.description ? o.description.split(' · ')[0].trim() : '') || o.name;

  /**
   * The header's model chip and the picker dialog show what the CLI says runs; the chip stays hidden until it says,
   * and the effort row shows only for a model that takes an effort. Every string is CLI text and goes in as text.
   */
  function setModel(msg) {
    const options = (Array.isArray(msg.options) ? msg.options : []).map((o) => ({
      value: String(o.value), name: String(o.name), description: o.description ? String(o.description) : '',
    }));
    const model = String(msg.model || '');
    const shown = options.find((o) => o.value === model);
    const efforts = (Array.isArray(msg.efforts) ? msg.efforts : []).map(String);
    const effort = msg.effort ? String(msg.effort) : '';
    modelLabel.textContent = shown ? shortModelName(shown) : (model || 'Model');
    effortLabel.textContent = effort && efforts.length ? capitalize(effort) : '';
    effortLabel.hidden = !effortLabel.textContent;
    modelBtn.hidden = !options.length;
    modelOptions.replaceChildren(...options.map((o) => h('button',
      { class: 'choice', 'data-action': 'pickModel', 'data-value': o.value, 'aria-pressed': String(o.value === model) },
      h('span', { class: 'choice-name' }, o.name),
      o.description ? h('span', { class: 'choice-desc' }, o.description) : null)));
    effortOptions.replaceChildren(...efforts.map((level) => h('button',
      { class: 'segment', 'data-action': 'pickEffort', 'data-value': level, 'aria-pressed': String(level === effort) },
      capitalize(level))));
    effortSection.hidden = !efforts.length;
    if (!options.length && modelDialog.open) modelDialog.close();
    if (modelDialog.open && !modelDialog.contains(document.activeElement)) focusCurrentChoice();
  }

  function openModelDialog() {
    if (modelDialog.open) return;
    modelDialog.showModal();
    focusCurrentChoice();
  }

  /** Focuses the picker's current model, so the keyboard starts (or, after the buttons are rebuilt, stays) there. */
  function focusCurrentChoice() {
    const current = modelDialog.querySelector('.choice[aria-pressed="true"]');
    if (current) current.focus();
  }

  /** A click on the backdrop lands on the dialog element itself, outside its content box: it closes the picker. */
  function closeOnBackdropClick(e) {
    if (e.target !== modelDialog) return;
    const r = modelDialog.getBoundingClientRect();
    if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) modelDialog.close();
  }

  /** Shows the selected permission mode beside the hand icon that opens the mode picker. */
  function showMode() {
    modeLabel.textContent = modeSel.selectedOptions[0]?.textContent || '';
  }

  /**
   * Grows the box to fit its text (capped by max-height in the CSS) so the log above gives way.
   * All reads come before any write, so each call lays the page out once.
   */
  function fitInput() {
    input.style.height = 'auto';
    const borders = input.offsetHeight - input.clientHeight;
    input.style.height = input.scrollHeight + borders + 'px';
  }
  let resizeFrame = 0;
  window.addEventListener('resize', () => { cancelAnimationFrame(resizeFrame); resizeFrame = requestAnimationFrame(fitInput); });

  /** Every programmatic change of the box's text goes through here, so its height follows. */
  function setInput(text, caret = text.length) {
    input.value = text;
    input.setSelectionRange(caret, caret);
    fitInput();
  }

  const PLACEHOLDER = input.placeholder;
  /** Only offered into an empty box while idle; typing, sending or a new turn takes it away. */
  function setSuggestion(text) {
    suggestion = idle && !input.value ? text : '';
    input.placeholder = suggestion ? suggestion + '   (Tab to use)' : PLACEHOLDER;
  }

  function acceptSuggestion() {
    const text = suggestion;
    setSuggestion('');
    setInput(text);
    recall = -1;
  }

  /** The strip above the input: phase and tool name come from the IDE, shown as text only; the seconds are counted here. */
  const PHASE_LABEL = {
    starting: () => 'Starting…',
    thinking: () => 'Thinking…',
    running: (msg) => 'Running ' + (typeof msg.tool === 'string' ? msg.tool.slice(0, 40) : 'tool') + '…',
    writing: () => 'Writing…',
    waiting: () => 'Waiting for your answer',
    agents: (msg) => {
      const n = Number.isInteger(msg.count) && msg.count > 0 ? msg.count : 1;
      return n === 1 ? '1 background agent working…' : n + ' background agents working…';
    },
  };
  let activityPhase = null;
  let activityLabelText = null;
  let activityStart = 0;
  let activityTimer = null;

  function tickActivity() {
    activitySecs.textContent = Math.max(0, Math.floor((Date.now() - activityStart) / 1000)) + 's';
  }

  function stopActivityTimer() {
    if (activityTimer !== null) { clearInterval(activityTimer); activityTimer = null; }
  }

  /**
   * Shows the phase from the IDE, or hides the strip without one. A changed label restarts the clock, except when one
   * of several background agents finishes: only the count changes and the clock keeps going. Stop is hidden while only
   * background agents work, as there is no turn to interrupt.
   */
  function setActivity(msg) {
    const phase = typeof msg.phase === 'string' && Object.prototype.hasOwnProperty.call(PHASE_LABEL, msg.phase) ? msg.phase : null;
    if (!phase) {
      activityPhase = activityLabelText = null;
      stopActivityTimer();
      activityEl.hidden = true;
      return;
    }
    const label = PHASE_LABEL[phase](msg);
    if (phase === activityPhase && label === activityLabelText) return;
    const keepTimer = phase === 'agents' && activityPhase === 'agents';
    activityPhase = phase;
    activityLabelText = label;
    activityLabel.textContent = label;
    activityStop.hidden = phase === 'agents';
    const waiting = phase === 'waiting';
    activityDots.hidden = waiting;
    activitySecs.hidden = waiting;
    activityEl.hidden = false;
    if (keepTimer) return;
    stopActivityTimer();
    if (!waiting) {
      activityStart = Date.now();
      tickActivity();
      activityTimer = setInterval(tickActivity, 1000);
    }
  }

  /** Plain text only: the IDE's checkout name and folder never become markup. */
  function setCheckout(text) {
    checkoutEl.textContent = typeof text === 'string' ? text : '';
    checkoutEl.hidden = !checkoutEl.textContent;
  }

  /** The meter turns amber this many points before autocompact. */
  const USAGE_WARN_POINTS = 10;
  function fmtTokens(n) {
    if (n >= 1e6) return +(n / 1e6).toFixed(1) + 'M';
    if (n >= 1000) return +(n / 1000).toFixed(n < 10000 ? 1 : 0) + 'k';
    return String(n);
  }
  const num = (v) => (typeof v === 'number' && isFinite(v) && v >= 0 ? v : null);

  function setUsage(msg) {
    const used = num(msg.used), max = num(msg.max), pct = num(msg.percent);
    if (used === null || !max || pct === null) { usageEl.hidden = true; return; }
    const p = Math.min(100, Math.round(pct));
    const at = num(msg.autocompactAt);
    const limit = at || 100;
    usageEl.classList.toggle('full', p >= limit);
    usageEl.classList.toggle('near', p < limit && p >= limit - USAGE_WARN_POINTS);
    usageRing.style.setProperty('--p', String(p));
    usagePct.textContent = p + '%';
    const parts = (Array.isArray(msg.categories) ? msg.categories : [])
      .filter((c) => c && typeof c.name === 'string' && num(c.tokens) !== null)
      .sort((a, b) => b.tokens - a.tokens)
      .map((c) => c.name + ' ' + fmtTokens(c.tokens));
    const lines = ['Context: ' + fmtTokens(used) + ' / ' + fmtTokens(max) + ' tokens (' + p + '%)'];
    if (parts.length) lines.push(parts.join(' · '));
    if (at !== null) lines.push('Autocompacts at ~' + at + '%');
    usageEl.title = lines.join('\n');
    usageEl.setAttribute('aria-valuenow', String(p));
    usageEl.setAttribute('aria-label', lines[0]);
    usageEl.hidden = false;
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

  /**
   * The IDE validated the image: show its thumbnail (or [image]); or it refused it (a notice in the log says why); or
   * it is still being prepared: a loading tile that blocks Send.
   */
  function setAttachment(msg) {
    if (typeof msg.id !== 'string') return;
    if (msg.failed === true) { removeTile(msg.id); return; }
    if (!pending.has(msg.id)) addTile(msg.id, typeof msg.name === 'string' ? msg.name : '');
    if (msg.loading === true) return;
    const p = pending.get(msg.id);
    p.ready = true;
    p.el.classList.remove('loading');
    p.el.firstChild.replaceWith(imageOrAlt(msg.thumb, '[image]'));
  }

  /**
   * Bytes go to the IDE as base64 in chunks; the IDE checks them (magic bytes, size) before anything else sees them.
   * A file over [MAX_INPUT], one past [MAX_ATTACH] (this tile included) or one whose reported type is not an image
   * sends no bytes: the IDE refuses it and says why (an empty file is "not an image"). A tile removed before its bytes
   * are read sends nothing more.
   */
  function upload(file) {
    const id = 'p' + (++attachSeq);
    const name = String(file.name || 'image').slice(0, 100);
    addTile(id, name);
    send({ type: 'attachStart', id, name, size: file.size });
    if (file.size > MAX_INPUT) return;
    if (pending.size > MAX_ATTACH) return;
    if (file.type && !/^image\//.test(file.type)) { send({ type: 'attachEnd', id }); return; }
    const reader = new FileReader();
    reader.onload = () => {
      if (!pending.has(id)) return;
      const url = String(reader.result);
      const data = url.slice(url.indexOf(',') + 1);
      for (let i = 0; i < data.length; i += CHUNK) send({ type: 'attachChunk', id, data: data.slice(i, i + CHUNK) });
      send({ type: 'attachEnd', id });
    };
    reader.onerror = () => { send({ type: 'detach', id }); removeTile(id); };
    reader.readAsDataURL(file);
  }

  function insertText(text) {
    recall = -1;
    const start = input.selectionStart, end = input.selectionEnd;
    const before = input.value.slice(0, start), after = input.value.slice(end);
    const sep = before && !/\s$/.test(before) ? ' ' : '';
    setInput(before + sep + text + ' ' + after, (before + sep + text + ' ').length);
    input.focus();
  }

  /** "High" for "high": how an effort level reads in the header and the picker. */
  const capitalize = (word) => word.charAt(0).toUpperCase() + word.slice(1);

  /**
   * The IDE's colours and font as CSS variables. The colour scheme is set too: native parts (a scrolling text box,
   * scrollbars) follow it, not the variables.
   */
  function applyTheme(t) {
    const s = document.documentElement.style;
    s.setProperty('--bg', t.bg); s.setProperty('--fg', t.fg); s.setProperty('--muted', t.muted);
    s.setProperty('--border', t.border); s.setProperty('--accent', t.accent); s.setProperty('--code-bg', t.codeBg);
    s.setProperty('--font', '"' + t.font + '", system-ui, sans-serif'); s.setProperty('--font-size', t.fontSize + 'px');
    s.setProperty('--add', t.dark ? '#2f5a3a' : '#d7f5dd'); s.setProperty('--del', t.dark ? '#5a2f2f' : '#f9d7d7');
    s.setProperty('--ok', t.dark ? '#5fb865' : '#2e7d32');
    s.setProperty('--warn', t.dark ? '#d29922' : '#9a6700'); s.setProperty('--danger', t.danger);
    s.setProperty('color-scheme', t.dark ? 'dark' : 'light');
    fitInput();
  }

  /** Sends the box's text with the attachments in the tray, once every image is ready. */
  function submit() {
    const text = input.value.trim();
    if (input.disabled) return;
    const tiles = [...pending.entries()];
    if (tiles.some(([, p]) => !p.ready)) return;
    const attachments = tiles.map(([id]) => id);
    if (!text && !attachments.length) return;
    send({ type: 'send', text, attachments });
    attachments.forEach(removeTile);
    setInput('');
    recall = -1;
    draft = '';
    setSuggestion('');
  }

  const caretOnFirstLine = () => !input.value.slice(0, input.selectionStart).includes('\n');
  const caretOnLastLine = () => !input.value.slice(input.selectionEnd).includes('\n');

  /**
   * Pure recall state machine. s = { recall, draft }; dir = 'up' | 'down'.
   * Returns { recall, draft, text } where text is the new box content, or null when the key is not handled.
   */
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

  /** Steps recall in [dir] and puts the entry in the box as its value, never parsed as HTML; false when not handled. */
  function recallKey(dir) {
    const step = recallStep({ recall, draft }, dir, history, input.value);
    if (!step) return false;
    recall = step.recall;
    draft = step.draft;
    setInput(step.text);
    return true;
  }

  input.addEventListener('keydown', (e) => {
    if (e.isComposing || e.keyCode === 229) return;
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); return; }
    if (e.shiftKey || e.altKey || e.ctrlKey || e.metaKey) return;
    if (e.key === 'Tab' && suggestion && !input.value) { e.preventDefault(); acceptSuggestion(); return; }
    if (e.key === 'ArrowUp' && caretOnFirstLine() && recallKey('up')) e.preventDefault();
    else if (e.key === 'ArrowDown' && caretOnLastLine() && recallKey('down')) e.preventDefault();
  });
  /** Typing ends recall (the edited text becomes the new draft) and dismisses the suggestion. */
  function onTyped() {
    recall = -1;
    if (suggestion) setSuggestion('');
    fitInput();
  }
  input.addEventListener('input', onTyped);
  fitInput();
  sendBtn.addEventListener('click', submit);
  activityStop.addEventListener('click', () => send({ type: 'interrupt' }));
  restartBtn.addEventListener('click', () => send({ type: 'restart' }));
  document.getElementById('show-changes').addEventListener('click', () => send({ type: 'showChanges' }));
  modeSel.addEventListener('change', () => { showMode(); send({ type: 'setMode', mode: modeSel.value }); });
  showMode();
  modelBtn.addEventListener('click', openModelDialog);
  modelDialog.addEventListener('click', closeOnBackdropClick);
  attachBtn.addEventListener('click', () => send({ type: 'pickImages' }));

  /**
   * The image files on a clipboard (Cmd+V of a screenshot): pasted anywhere on the page, they become attachments,
   * while text pastes are untouched. A clipboard with text too is left to the message box, which pastes it as text:
   * spreadsheets and Office put a picture next to the text.
   */
  const pastedImages = (clipboard) => [...((clipboard && clipboard.items) || [])]
    .filter((i) => i.kind === 'file' && /^image\//.test(i.type)).map((i) => i.getAsFile()).filter(Boolean);
  document.addEventListener('paste', (e) => {
    if (e.target === input && e.clipboardData && e.clipboardData.getData('text/plain')) return;
    const files = pastedImages(e.clipboardData);
    if (!files.length) return;
    e.preventDefault();
    if (!input.disabled) files.forEach(upload);
  });

  /** In the capture phase, so no anchor click or form submit reaches the browser's default navigation. */
  function blockDefaultNavigation() {
    document.addEventListener('auxclick', (e) => { if (e.target.closest('a')) e.preventDefault(); }, true);
    document.addEventListener('submit', (e) => e.preventDefault(), true);
  }
  blockDefaultNavigation();

  /**
   * Whether a drag or drop carries files. Dropped files become attachments and, as the dragover and drop listeners
   * run in the capture phase, never reach the browser's default (opening one would navigate the page); other drops are
   * allowed only into the message box, as text.
   */
  const hasFiles = (dt) => !!dt && [...(dt.types || [])].includes('Files');
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
    const fileRow = e.target.closest('.turn-files .tool-row');
    if (fileRow) { if (trusted.has(fileRow)) openTurnDiff(fileRow); return; }
    const b = e.target.closest('button[data-action]');
    if (!b) {
      const row = e.target.closest('.tool-row');
      if (row && trusted.has(row) && row.classList.contains('expandable') && !(window.getSelection && window.getSelection().toString())) toggleRow(row);
      return;
    }
    if (!trusted.has(b)) return;
    const action = b.dataset.action;
    if (action === 'diff') send({ type: 'openDiff', toolUseId: b.dataset.id });
    else if (action === 'turnFiles') toggleTurnFiles(b);
    else if (action === 'turnDiff') openTurnDiff(b);
    else if (action === 'turnChanges') send({ type: 'openTurnChanges', id: b.dataset.id });
    else if (action === 'detach') { send({ type: 'detach', id: b.dataset.id }); removeTile(b.dataset.id); }
    else if (action === 'allow' || action === 'allowSession') send({ type: 'permission', requestId: b.dataset.req, decision: action });
    else if (action === 'queueSendNow' || action === 'queueEdit' || action === 'queueRemove' || action === 'revertTo') send({ type: action, id: b.dataset.id });
    else if (action === 'pickModel') send({ type: 'setModel', model: b.dataset.value });
    else if (action === 'pickEffort') send({ type: 'setEffort', effort: b.dataset.value });
    else if (action === 'skipQuestion') send({ type: 'skipQuestion', requestId: b.dataset.req });
    else if (action === 'deny') {
      const reason = b.parentElement.querySelector('.deny-reason').value.trim();
      send({ type: 'permission', requestId: b.dataset.req, decision: 'deny', message: reason || 'The user denied this action.' });
    }
  });

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const fileRow = e.target.closest && e.target.closest('.turn-files .tool-row');
    if (fileRow && e.target === fileRow && trusted.has(fileRow)) { e.preventDefault(); openTurnDiff(fileRow); return; }
    const toggle = e.target.classList && e.target.classList.contains('tool-toggle') && trusted.has(e.target) ? e.target : null;
    const row = toggle && toggle.closest('.tool-row.expandable');
    if (row && trusted.has(row)) { e.preventDefault(); toggleRow(row); }
  });

  window.addEventListener('bridge-ready', () => send({ type: 'ready' }));
})();
