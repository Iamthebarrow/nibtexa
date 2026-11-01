// Page side of the NibTeXa math bridge. Mirrors BridgeProtocol.kt: every message is
// {"v": 1, "type": ..., ...}; commands from the app are validated before any handler runs.
'use strict';

const NibBridge = (() => {
  const VERSION = 1;
  const MAX_MESSAGE_CHARS = 64 * 1024;
  const MAX_LATEX_CHARS = 8 * 1024;
  const MAX_LIST_ITEMS = 200;

  // Injected by WebViewCompat.addWebMessageListener, only for the app's asset origin.
  const port = window.nibtexaBridge;
  const handlers = {};

  const MAX_BOX = 2000;
  const BLOCK_KINDS = new Set(['title', 'heading', 'paragraph', 'math', 'figure', 'inline-math']);
  const BLOCK_FIELDS = ['kind', 'text', 'level', 'width', 'height'];

  const ALIGNS = new Set(['left', 'center', 'right']);
  const isId = (x) => Number.isInteger(x) && x >= 0;
  const isLatex = (x) => typeof x === 'string' && x.length <= MAX_LATEX_CHARS;
  const isBox = (x) => typeof x === 'number' && Number.isFinite(x) && x >= 0 && x <= MAX_BOX;
  // A document block (Phase 12): exactly these fields, plain text only.
  const isBlock = (b) => b !== null && typeof b === 'object' && !Array.isArray(b) &&
    Object.keys(b).length === BLOCK_FIELDS.length && BLOCK_FIELDS.every((k) => Object.prototype.hasOwnProperty.call(b, k)) &&
    BLOCK_KINDS.has(b.kind) && isLatex(b.text) && Number.isInteger(b.level) && b.level >= 0 && b.level <= 3 &&
    isBox(b.width) && isBox(b.height);

  // Allowed fields and a validator per command type.
  const commands = {
    setValue: { fields: ['requestId', 'latex'], valid: (m) => isId(m.requestId) && isLatex(m.latex) },
    getValue: { fields: ['requestId'], valid: (m) => isId(m.requestId) },
    focus: { fields: [], valid: () => true },
    renderList: {
      fields: ['requestId', 'items', 'align'],
      valid: (m) => isId(m.requestId) && Array.isArray(m.items) && m.items.length <= MAX_LIST_ITEMS && m.items.every(isLatex) &&
        (m.align === undefined || ALIGNS.has(m.align)),
    },
    renderDocument: {
      fields: ['requestId', 'blocks'],
      valid: (m) => isId(m.requestId) && Array.isArray(m.blocks) && m.blocks.length <= MAX_LIST_ITEMS && m.blocks.every(isBlock),
    },
  };

  function send(message) {
    if (!port) return;
    const text = JSON.stringify(Object.assign({ v: VERSION }, message));
    if (text.length > MAX_MESSAGE_CHARS) {
      port.postMessage(JSON.stringify({ v: VERSION, type: 'error', requestId: message.requestId ?? null, message: 'reply too large' }));
      return;
    }
    port.postMessage(text);
  }

  function validate(data) {
    if (typeof data !== 'string' || data.length > MAX_MESSAGE_CHARS) return null;
    let m;
    try { m = JSON.parse(data); } catch (e) { return null; }
    if (m === null || typeof m !== 'object' || Array.isArray(m) || m.v !== VERSION) return null;
    const spec = Object.prototype.hasOwnProperty.call(commands, m.type) ? commands[m.type] : null;
    if (!spec) return null;
    const allowed = new Set(['v', 'type', ...spec.fields]);
    if (!Object.keys(m).every((k) => allowed.has(k)) || !spec.valid(m)) return null;
    return m;
  }

  if (port) {
    port.onmessage = (event) => {
      const m = validate(event.data);
      if (!m || !handlers[m.type]) {
        send({ type: 'error', requestId: null, message: 'rejected command' });
        return;
      }
      try {
        handlers[m.type](m);
      } catch (e) {
        send({ type: 'error', requestId: m.requestId ?? null, message: String(e && e.message || e).slice(0, 1000) });
      }
    };
  }

  // A CSP violation means the page tried something it must not; report it to the app.
  document.addEventListener('securitypolicyviolation', (e) => {
    send({ type: 'error', requestId: null, message: `CSP blocked ${e.violatedDirective}: ${e.blockedURI}`.slice(0, 1000) });
  });

  return {
    available: !!port,
    send,
    on(type, fn) { handlers[type] = fn; },
    ready(page) { send({ type: 'ready', page }); },
    // Resolves after fonts are loaded and two frames have been laid out.
    async settled() {
      await document.fonts.ready;
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    },
  };
})();
