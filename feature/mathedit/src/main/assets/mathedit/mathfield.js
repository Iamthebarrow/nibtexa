// One editable MathLive field. Reports every edit to the app as LaTeX.
'use strict';

(() => {
  const field = document.getElementById('field');

  // Accessibility (Phase 8). Screen readers focus MathLive's input sink, a zero-size element
  // labelled only "Equation", and read the field's toolbar buttons as its text. So:
  // - the menu, unused, is hidden;
  // - the virtual-keyboard toggle stays visible but is hidden from screen readers: it is not
  //   focusable anyway, and the keyboard opens when the field gets focus;
  // - the field and the sink are labelled with the equation as MathLive speaks it, updated on
  //   every change.
  field.menuItems = [];
  field.setAttribute('role', 'group');
  const describe = () => {
    const toggle = field.shadowRoot && field.shadowRoot.querySelector('[part="virtual-keyboard-toggle"]');
    if (toggle) toggle.setAttribute('aria-hidden', 'true');
    let spoken = '';
    try {
      spoken = field.getValue('spoken-text').trim();
    } catch (_) {
      // Unparseable input: keep the plain label.
    }
    const label = spoken ? `Equation: ${spoken}` : 'Equation, empty';
    field.setAttribute('aria-label', label);
    const sink = field.shadowRoot && field.shadowRoot.querySelector('[part="keyboard-sink"]');
    if (sink) sink.setAttribute('aria-label', label);
  };
  describe();

  field.addEventListener('input', () => {
    describe();
    NibBridge.send({ type: 'changed', latex: field.value });
  });

  // The virtual keyboard covers the bottom of the page. The app makes the editor tall enough
  // for the field and the keyboard, so the field is never hidden behind it (Phase 14).
  const keyboard = window.mathVirtualKeyboard;
  let reported = -1;
  const reportKeyboard = () => {
    const height = keyboard && keyboard.visible ? Math.ceil(keyboard.boundingRect.height) : 0;
    if (height === reported) return;
    reported = height;
    NibBridge.send({ type: 'keyboard', height: Math.min(height, 2000) });
  };
  if (keyboard) {
    keyboard.addEventListener('geometrychange', reportKeyboard);
    keyboard.addEventListener('virtual-keyboard-toggle', reportKeyboard);
  }

  NibBridge.on('setValue', (m) => {
    field.setValue(m.latex, { silenceNotifications: true });
    describe();
    NibBridge.send({ type: 'value', requestId: m.requestId, latex: field.value });
  });
  NibBridge.on('getValue', (m) => NibBridge.send({ type: 'value', requestId: m.requestId, latex: field.value }));
  NibBridge.on('focus', () => field.focus());

  NibBridge.ready('mathlive-editor');
})();
