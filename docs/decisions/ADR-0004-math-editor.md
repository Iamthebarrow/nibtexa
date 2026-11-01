# ADR-0004: Rendered-math display and editor

- **Status:** Accepted
- **Date:** 2026-10-27
- **See also:** [the security note](../design/MATH_BRIDGE.md)

## Context

After recognition (ADR-0003), the user must see the equation as rendered math
and correct it element by element, fully offline, with a hardware keyboard,
touch or stylus. It has to use free, offline components compatible with
GPL-3.0.

## Options evaluated

| | **A. MathLive 0.111.1 in a local WebView** | **B. KaTeX 0.19.1 display plus LaTeX source editing** |
|---|---|---|
| Licence | MIT (code and KaTeX fonts) | MIT |
| Editing model | **Structural**: caret moves through bounds, scripts and fractions; on-screen math keyboard | Text only: the user edits LaTeX source, and the preview re-renders |
| Corpus (159 prompts) | Renders all of them; **0 lossy** after read-back (normalized tokens); **0 unstable** on a second round trip | Renders all of them; 0 parse errors |
| 20 equations on one screen (AVD, host GPU) | First render 118–161 ms; warm median 57–63 ms | First render 74–311 ms; warm median 29–33 ms |
| Keyboard | Hardware keys navigate and edit (needs real scan codes; see Consequences). Touch focus shows MathLive's own keyboard, and the Android IME stays hidden. | Ordinary text field with the Android IME |
| Accessibility (TalkBack smoke check) | The field exposes a spoken description ("the integral from minus 1 to 1 of X squared…") | MathML output, **not checked** under TalkBack (TalkBack's first-run screen blocked the check) |
| Size in APK | 1.2 MB of assets | 0.6 MB of assets |

Not evaluated, from the Phase 0 list: a native Compose structure editor and
JLaTeXMath ports. Both cost far more, and neither is needed given the result
for A.

## Decision

1. **MathLive in a locked-down local WebView is the math editor.** KaTeX is not
   needed as a second renderer: MathLive also renders read-only fields, and one
   library keeps output consistent.
2. The editor sits behind the typed message protocol in `:feature:mathedit`
   (`BridgeProtocol` version 1). The rest of the app only sees LaTeX in and
   LaTeX out.
3. The security controls in `docs/design/MATH_BRIDGE.md` are mandatory for
   any page in this WebView.
4. The confirmed LaTeX is MathLive's read-back value (for example `\int_1^1x\,dx`).
   It is stable: a second round trip doesn't change it. Comparisons use
   `LatexTokens.normalize`.

## Consequences

- **Hardware-keyboard navigation depends on `KeyboardEvent.code`.** MathLive
  maps keys by `code`, and WebView derives `code` from the Android scan code.
  Key events without a scan code (`adb shell input keyevent`,
  `Instrumentation.sendKeyDownUpSync`, and possibly some on-screen IMEs) type
  characters but **do not navigate**. Real keyboards send scan codes, but this
  is unverified on hardware and is added to the hardware matrix. Tests inject
  scan-coded events (`HardwareKeys`).
- Each editor is a WebView, at about 150 MB app PSS on the emulator with one
  page. A page of many equations should use one WebView with many fields, as
  the 20-equation list does, not one WebView per equation.
- Accessibility needs follow-up work in Phase 8 (**done in Phase 8**: our page
  code now hides the menu, hides the keyboard toggle from screen readers, makes
  the field a labelled group, and puts MathLive's spoken text on the field and
  its input sink. `MathEditorAccessibilityTest` checks this. TalkBack speaking
  remains HW-12):
  - the outer field node reads "Toggle Virtual Keyboard, Menu";
  - the math description sits on a zero-size inner node;
  - in one observation the description omitted a just-typed "+1".
- MathLive updates are taken deliberately: vendored, hash-pinned, and re-run
  through the corpus round trip.
