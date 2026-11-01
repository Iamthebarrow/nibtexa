# Security note: the math editor WebView and bridge

- **Applies to:** `:feature:mathedit`
  ([MathPageHost.kt](https://github.com/Iamthebarrow/nibtexa/blob/main/feature/mathedit/src/main/java/io/github/iamthebarrow/nibtexa/feature/mathedit/MathPageHost.kt),
  [BridgeProtocol.kt](https://github.com/Iamthebarrow/nibtexa/blob/main/feature/mathedit/src/main/java/io/github/iamthebarrow/nibtexa/feature/mathedit/bridge/BridgeProtocol.kt),
  [bridge.js](https://github.com/Iamthebarrow/nibtexa/blob/main/feature/mathedit/src/main/assets/mathedit/bridge.js)).

## What the WebView is for

It renders and edits LaTeX with MathLive. (KaTeX was compared in Phase 4 and
removed in Phase 5.) It runs
JavaScript, so it is treated as a component that could misbehave. It must never
reach the network, read app files, or drive the app beyond the narrow message
protocol below.

## Threats considered

1. Remote content loaded into the WebView: a link, a redirect, a script URL, or
   a resource URL inside LaTeX.
2. A page other than the app's own assets gaining access to the bridge.
3. A malformed, oversized or unexpected message crashing or confusing the app,
   or the page.
4. The WebView reading local files or content providers.
5. Data leaving the device. Notes may be private.

User-written LaTeX is the main untrusted input. Today it comes from the
recognizer or from typing, and later from imported notes.

Since Phase 12 a third page, `document.html`, lays out a booklet for the PDF
export. Its input is the same kind of user text: headings, paragraphs,
captions and LaTeX.
- Every text is set with `textContent`, and math as MathLive's value. **No
  host string is ever inserted as HTML.**
- A figure is an empty box of the requested size. The app draws the ink into
  it on the PDF canvas, so no image crosses the bridge.
- The page replies only with numbers: block extents and the document size.
- It is always light, and it uses the same CSP, asset loader and bridge as
  the other pages.

## Controls

| Layer | Control | Threats |
|---|---|---|
| Manifest | Since Phase 7 the app **has the `INTERNET` permission**, for Git sync only (ADR-0006). It is no longer a control for the WebView: the rows below must hold on their own, and the instrumented security tests are run with the permission granted. The network security config allows HTTPS only (debug builds add plain HTTP to `127.0.0.1`, forwarded by `adb reverse` to the test remote). | 5 |
| WebView settings | `blockNetworkLoads = true` (set explicitly; it no longer follows from a missing permission); `allowFileAccess` and `allowContentAccess` off; DOM storage off; no multiple windows or automatic window opening; geolocation off; Safe Browsing off, because nothing remote is loaded and lookups would need the network. | 1, 4, 5 |
| Request filter | `shouldInterceptRequest` serves only `https://appassets.androidplatform.net/assets/…` from the APK via `WebViewAssetLoader`. **Every other request**, including `data:` page loads, gets a 403 and is recorded in `blockedRequests`. `shouldOverrideUrlLoading` returns true, so pages never navigate. | 1, 2 |
| Content Security Policy | `default-src 'none'`; scripts, fonts and `connect` from `'self'` only; images `'self'` and `data:`; no media, objects, base URI or forms. `style-src` needs `'unsafe-inline'` because MathLive sets inline styles. CSP violations are reported to the app as error messages. | 1 |
| Bridge origin | `WebViewCompat.addWebMessageListener` with the single allowed origin `https://appassets.androidplatform.net`, so the `nibtexaBridge` object is not injected into any other origin. `MessageGate` then rejects any message whose origin is not exactly that string, or that comes from a subframe. | 2 |
| Protocol | Versioned JSON (`v: 1`). Fixed message types, required field types, **unexpected fields rejected**, non-negative integer ids, LaTeX at most 8 KiB, messages at most 64 KiB, lists at most 200 items. Decoding is all-or-nothing. The page applies the same rules to commands it receives (`bridge.js`). Document blocks (Phase 12) have exactly five fields: a kind from a fixed set, text (8 KiB at most), heading level 0–3, and figure box sizes 0–2000. Layout replies are four finite, non-negative numbers per block; since Phase 16 a fifth, the text baseline, for the new `inline-math` block kind (maths set inline at text size; its text is MathLive's value, like `math`). Since Phase 14 the editor page also reports its virtual keyboard's height (`keyboard`: one number, 0–2000), which only sizes the editor. Since Phase 15 the list command may carry an alignment, one of `left`, `center` or `right` (the page applies it as a CSS attribute value, never as markup). | 3 |
| No code execution over the bridge | Neither side sends script. The app never calls `evaluateJavascript` in production code, and there is no `addJavascriptInterface`. | 2, 3 |
| Renderer crash | `onRenderProcessGone` returns true and reports an error instead of crashing the app. | 3 |

Vendored libraries are pinned by hash in
[feature/mathedit/VENDORED.md](https://github.com/Iamthebarrow/nibtexa/blob/main/feature/mathedit/VENDORED.md). MathLive was
read for remote references before vendoring. The only remote URLs in it are an
error message about the Compute Engine and an opt-in AWS Polly speech path,
which needs an AWS SDK that is not shipped.

## Evidence

The JVM contract tests are in `BridgeProtocolTest`: 10 tests, plus 4 since
Phase 12 for document commands, layouts, malformed blocks and spans, and
limits, 1 since Phase 14 for the keyboard height, 1 since Phase 15 for list alignment, and Phase 16 adds the `inline-math` kind to the round trip and five-value spans (accepted) and six (rejected). They check that:

- every message type and command round-trips;
- LaTeX containing quotes, backslashes, `</script>` and non-BMP characters
  survives encoding;
- malformed JSON, the wrong version, an unknown type, extra fields, mistyped
  fields and oversized input are each rejected;
- commands over the page's limits are never encoded;
- `MessageGate` rejects foreign origins (including `http://` and look-alike
  hosts), subframes and garbage.

The instrumented tests are in `MathBridgeSecurityTest` (7 tests since Phase
12, AVD API 37, WebView 145):

- all three pages load and render with **zero blocked requests, zero
  rejected messages and zero page or CSP errors**;
- on the document page, a heading containing `<b>` and a paragraph
  containing `<script>` appear as text, no `b` or `script` element is
  created, and the figure box has exactly the requested size;
- the bridge object exists on the asset page. In a WebView registered with the
  same rule, it is `undefined` on `https://evil.example`;
- the host refuses a foreign `data:` page and navigation to
  `https://example.com/`, and both are recorded as blocked;
- `fetch('https://example.com/…')` from the page is stopped by CSP and
  reported;
- four malformed page messages are rejected by the app;
- four malformed commands are rejected by the page, and the field is unchanged.

All of these also passed with the emulator in airplane mode.

## Known gaps

- `style-src 'unsafe-inline'` is needed by both libraries. Scripts remain
  `'self'`-only.
- The CSP is a `<meta>` tag. Serving it as a response header through the asset
  loader would be stronger; that is a Phase 8 item.
- `connect-src 'self'` is needed because MathLive fetches its own script URL to
  resolve its font directory. Same-origin requests can only reach APK assets.
- **Untested:** WebView versions older than 145, and devices whose WebView lacks
  `WEB_MESSAGE_LISTENER`. On those the editor reports that it is unsupported.
  With `minSdk` 26 and an updatable WebView this should be rare, but it has not
  been measured.
- The LaTeX itself is not sanitized. MathLive parses it as math, and
  macros such as `\href` or `\url` render as text or links that can't navigate,
  because navigation is blocked. That is not proven for every macro.
  Re-examine when imported notes arrive.
- The exported `.tex` is a separate path: `LatexExporter` refuses LaTeX that
  uses file, shell or document primitives (ADR-0005), so a compile of an
  export does not read or write outside it.
