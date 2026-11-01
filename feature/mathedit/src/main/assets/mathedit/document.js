// The PDF export's page (Phase 12). renderDocument lays out blocks of plain text and math and
// replies with each block's extent, so the app can paginate and draw figures into their boxes.
// Text is always set with textContent, never as HTML.
'use strict';

(() => {
  const doc = document.getElementById('doc');

  function block(b) {
    const el = document.createElement('div');
    el.classList.add('block');
    switch (b.kind) {
      case 'title':
        el.classList.add('title');
        el.textContent = b.text;
        break;
      case 'heading':
        el.classList.add(`heading-${Math.min(Math.max(b.level, 1), 3)}`);
        el.setAttribute('role', 'heading');
        el.textContent = b.text;
        break;
      case 'paragraph':
        el.classList.add('paragraph');
        el.textContent = b.text;
        break;
      case 'math': {
        el.classList.add('math');
        const field = new MathfieldElement();
        field.readOnly = true;
        field.setValue(b.text, { silenceNotifications: true });
        el.append(field);
        break;
      }
      // Inline maths (Phase 16): set on a line at text size, next to an empty probe whose bottom
      // is the line's baseline, so the app can sit the captured maths on its own lines.
      case 'inline-math': {
        el.classList.add('inline-math');
        const field = new MathfieldElement();
        field.readOnly = true;
        field.setValue(b.text, { silenceNotifications: true });
        const probe = document.createElement('span');
        probe.classList.add('baseline');
        el.append(field, probe);
        break;
      }
      case 'figure': {
        el.classList.add('figure');
        const box = document.createElement('div');
        box.classList.add('figure-box');
        box.style.width = `${b.width}px`;
        box.style.height = `${b.height}px`;
        el.append(box);
        if (b.text) {
          const caption = document.createElement('div');
          caption.classList.add('caption');
          caption.textContent = b.text;
          el.append(caption);
        }
        break;
      }
    }
    return el;
  }

  NibBridge.on('renderDocument', async (m) => {
    doc.replaceChildren();
    const failed = [];
    const elements = m.blocks.map((b, i) => {
      try {
        return block(b);
      } catch (e) {
        failed.push(i);
        return document.createElement('div');
      }
    });
    doc.append(...elements);
    await NibBridge.settled();
    const y = window.scrollY;
    const spans = elements.map((el) => {
      const r = el.getBoundingClientRect();
      const box = el.querySelector('.figure-box');
      const b = box ? box.getBoundingClientRect() : null;
      const span = [r.top + y, r.bottom + y, b ? b.left : 0, b ? b.top + y : 0];
      const probe = el.querySelector('.baseline');
      if (probe) span.push(probe.getBoundingClientRect().bottom + y);
      return span.map((v) => Math.max(0, v));
    });
    NibBridge.send({
      type: 'laidOut',
      requestId: m.requestId,
      width: doc.getBoundingClientRect().width,
      height: document.documentElement.scrollHeight,
      spans,
      failed,
    });
  });

  NibBridge.ready('mathlive-document');
})();
