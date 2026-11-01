// Read-only MathLive fields. renderList replies with the time to a settled layout and with
// each field's LaTeX as MathLive reads it back, which is what the round-trip test compares.
'use strict';

(() => {
  const list = document.getElementById('list');

  NibBridge.on('renderList', async (m) => {
    const started = performance.now();
    list.replaceChildren();
    // Each equation sits left (the default), centred or right on its line (Phase 15).
    list.dataset.align = m.align || 'left';
    const failed = [];
    const fields = m.items.map((latex, i) => {
      const field = new MathfieldElement();
      field.readOnly = true;
      try {
        field.setValue(latex, { silenceNotifications: true });
        // Each item is read as the equation, not as the field's toolbar (Phase 8).
        field.setAttribute('aria-label', field.getValue('spoken-text').trim() || 'Equation');
      } catch (e) {
        failed.push(i);
      }
      const item = document.createElement('div');
      item.className = 'item';
      item.setAttribute('role', 'listitem');
      item.append(field);
      list.append(item);
      return field;
    });
    await NibBridge.settled();
    NibBridge.send({
      type: 'rendered',
      requestId: m.requestId,
      count: fields.length,
      millis: performance.now() - started,
      failed,
      values: fields.map((f) => f.value),
    });
  });

  NibBridge.ready('mathlive-list');
})();
