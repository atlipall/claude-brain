window.runComposerMenuTests = function () {
  const results = [];
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const receive = (message) => window.chat.receive(message);
  const test = (name, run) => {
    try { run(); results.push({ name, passed: true }); }
    catch (error) { results.push({ name, passed: false, error: error.message }); }
  };
  const agents = [{ id: 'claude', label: 'Claude' }, { id: 'acp', label: 'Hermes' }];
  const trigger = (id) => document.getElementById(id);
  const menu = () => document.querySelector('[role="menu"]');
  const entries = () => [...menu().querySelectorAll('[role="menuitemradio"]')];
  const key = (target, name) => target.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }));
  const sent = (type) => window.__sent.filter((message) => message.type === type);
  window.__sent.length = 0;
  receive({ type: 'reset', items: [] });
  receive({ type: 'agent', agents, selected: 'claude', locked: false });
  receive({ type: 'modes', modes: [
    { id: 'default', label: 'Ask before edits' },
    { id: 'acceptEdits', label: 'Accept edits' },
    { id: 'plan', label: 'Plan only' },
    { id: 'auto', label: 'Auto' },
  ] });
  receive({ type: 'state', state: 'IDLE', sessionId: null, mode: 'default' });

  test('agent choices open visibly above the composer control', () => {
    const trigger = document.getElementById('agent');
    trigger.click();
    const menu = document.querySelector('[role="menu"]');
    assert(menu && menu.getBoundingClientRect().height > 0, 'Agent menu must be visible');
    const bounds = menu.getBoundingClientRect();
    assert(bounds.bottom <= trigger.getBoundingClientRect().top, 'Menu must open above its trigger');
    assert(bounds.top >= 0 && bounds.left >= 0 && bounds.right <= innerWidth, 'Menu must stay inside the webview');
    assert(menu.querySelectorAll('[role="menuitemradio"]').length === 2, 'Both agents must be available');
  });

  test('choosing Hermes preserves the draft and attachment and sends one selection', () => {
    trigger('input').value = 'Keep this draft';
    receive({ type: 'attachment', id: 'menu-image', name: 'sample.png' });
    entries().find((entry) => entry.textContent.includes('Hermes')).click();
    assert(sent('setAgent').length === 1 && sent('setAgent')[0].agent === 'acp', 'Hermes must be selected exactly once');
    assert(!menu() && document.activeElement === trigger('agent'), 'Selection must close and return focus');
    assert(trigger('input').value === 'Keep this draft' && document.querySelectorAll('#attachments .tile').length === 1, 'Draft and image must survive selection');
    receive({ type: 'agent', agents, selected: 'acp', locked: false });
    receive({ type: 'modes', modes: [] });
    assert(trigger('input').placeholder.includes('Hermes') && trigger('mode').hidden, 'Controls must follow the selected agent');
    assert(trigger('usage').hidden, 'Choosing an agent must not invent context usage');
  });

  test('sending after selection sends the preserved draft and attachment once', () => {
    trigger('send').click();
    const messages = sent('send');
    assert(messages.length === 1 && messages[0].text === 'Keep this draft', 'One send must contain the draft');
    assert(messages[0].attachments.length === 1 && messages[0].attachments[0] === 'menu-image', 'The image must be sent');
    receive({ type: 'agent', agents, selected: 'acp', locked: true });
    trigger('agent').click();
    assert(trigger('agent').disabled && !menu(), 'An active chat must retain its agent');
  });

  test('permission choices open upward and send the agent-provided mode id', () => {
    receive({ type: 'modes', modes: [{ id: 'safe', label: 'Ask first' }, { id: 'fast', label: 'Accept edits' }] });
    receive({ type: 'state', state: 'RUNNING', sessionId: 'fixture-session', mode: 'safe' });
    trigger('mode').click();
    assert(menu().getBoundingClientRect().bottom <= trigger('mode').getBoundingClientRect().top, 'Permission menu must open upward');
    assert(entries()[0].getAttribute('aria-checked') === 'true', 'Current mode must be marked');
    entries()[1].click();
    assert(sent('setMode').length === 1 && sent('setMode')[0].mode === 'fast', 'Mode selection must send one exact id');
    assert(!menu() && trigger('mode').textContent.includes('Accept edits'), 'Selected mode must be displayed');
  });

  test('Escape closes without selecting or interrupting and returns focus', () => {
    trigger('mode').click();
    key(document.activeElement, 'Escape');
    assert(!menu() && document.activeElement === trigger('mode'), 'Escape must return focus to the trigger');
    assert(sent('setMode').length === 1 && sent('interrupt').length === 0, 'Dismissal must not change mode or interrupt');
    assert(trigger('mode').getAttribute('aria-expanded') === 'false', 'Expanded state must match dismissal');
  });

  test('arrow keys and Home and End move focus without selecting', () => {
    trigger('mode').focus();
    key(trigger('mode'), 'ArrowDown');
    key(document.activeElement, 'Home');
    assert(document.activeElement === entries()[0], 'Home must focus the first choice');
    key(document.activeElement, 'ArrowUp');
    assert(document.activeElement === entries()[1], 'ArrowUp must wrap to the last choice');
    key(document.activeElement, 'ArrowDown');
    assert(document.activeElement === entries()[0], 'ArrowDown must wrap to the first choice');
    key(document.activeElement, 'End');
    assert(document.activeElement === entries()[1], 'End must focus the last choice');
    key(document.activeElement, 'Tab');
    assert(!menu() && sent('setMode').length === 1, 'Tab must dismiss without selection');
  });

  test('outside click and resize dismiss without selecting', () => {
    trigger('mode').click();
    trigger('input').click();
    assert(!menu(), 'An outside click must dismiss');
    trigger('mode').click();
    window.dispatchEvent(new Event('resize'));
    assert(!menu() && sent('setMode').length === 1, 'Resize must dismiss without selection');
  });

  test('only one menu opens and incoming control updates close stale choices', () => {
    receive({ type: 'agent', agents, selected: 'acp', locked: false });
    trigger('agent').click();
    trigger('mode').click();
    assert(document.querySelectorAll('[role="menu"]').length === 1 && menu().getAttribute('aria-label') === 'Permission mode', 'Only the latest menu must remain');
    receive({ type: 'modes', modes: [] });
    assert(trigger('mode').hidden && !menu(), 'Unavailable modes must dismiss their menu');
    trigger('agent').click();
    receive({ type: 'agent', agents, selected: 'acp', locked: true });
    assert(trigger('agent').disabled && !menu(), 'Locking the agent must dismiss stale choices');
  });

  test('a long menu scrolls its keyboard-focused choice into view', () => {
    receive({ type: 'modes', modes: Array.from({ length: 40 }, (_, index) => ({ id: 'mode-' + index, label: 'Mode ' + index })) });
    receive({ type: 'state', state: 'IDLE', sessionId: null, mode: 'mode-0' });
    trigger('mode').click();
    const bounds = menu().getBoundingClientRect();
    assert(bounds.top >= 0 && bounds.bottom <= trigger('mode').getBoundingClientRect().top && bounds.right <= innerWidth, 'Long menu must remain above the trigger and inside the webview');
    assert(menu().scrollHeight > menu().clientHeight, 'Long choices must scroll rather than overflow');
    key(document.activeElement, 'End');
    const focused = document.activeElement.getBoundingClientRect();
    assert(focused.top >= bounds.top && focused.bottom <= bounds.bottom, 'Keyboard-focused last choice must be visible');
    key(document.activeElement, 'Escape');
  });
  return results;
};
