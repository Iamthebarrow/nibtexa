# ADR-0007: Text recognition and automatic regions

- **Status:** Accepted
- **Date:** 2026-10-28
- **Amended:** 2026-10-28, 2026-10-29, 2026-10-31

## Context

Since Phase 6, a Text or Heading region opens an empty box for a typed
transcription, and every region is made by hand from a lasso selection. The
user asked (2026-10-28) for text-pen handwriting to become text without
typing, and for ink to be grouped into regions automatically, so that text,
math and drawings reach the export without the lasso. The lasso stays as a
selection tool (move, delete, copy and paste), with Convert and Make region as
a secondary path.

## Decision

### 1. Text recognizer

1. **Text recognition sits behind a `TextRecognizer` interface** in
   `:core:recognition`, mirroring `MathRecognizer`:
   `recognize(strokes, context, isCanceled): String`. It throws
   `RecognitionException`, `RecognitionCanceledException`, or the new
   `LanguagePackMissingException`.
2. **Implementation: Google ML Kit Digital Ink Recognition**
   (`com.google.mlkit:digital-ink-recognition:19.0.0`), English (`en-US`),
   in `:app` as `MlKitTextRecognizer`, behind a `TextRecognizerSource` in
   `NibTeXaApplication.Services`.
   - **Proprietary.** It ships under the additional permission in `NOTICE.md` (see the 2026-10-31 amendment).
3. **No fallback ships.** TrOCR-small (ONNX) could sit behind the same interface, but its weights are trained on IAM, which is research-only.
4. **The language pack is downloaded explicitly** by the user ("Download
   handwriting language…"), with its size and progress shown, and Wi-Fi
   required unless the user agrees otherwise. Recognition is offline after
   that. Apart from Git sync, this is the only network use the user starts.
   ML Kit's own metrics traffic is a separate matter (section 2).
5. **Results are stored separately from confirmations**, as for math: a new
   `Region.recognizedText` column (Room migration 2→3). Reusing
   `recognizedLatex` was rejected, because a role change between math and
   text would then show LaTeX as prose or the reverse.
6. **Context:** the recognizer gets ML Kit's writing area (the region's
   bounds) and a pre-context of up to 20 characters: the end of the previous
   text region's value in reading order.

### 2. ML Kit facts (checked 2026-10-28)

| Fact | Finding | Status | Source |
|---|---|---|---|
| Artifact | `com.google.mlkit:digital-ink-recognition`, latest **19.0.0** (2025-08-29, "updated to support 16 KB page sizes"). | Verified | Maven metadata; release notes |
| Licence | The POM's licence is "ML Kit Terms of Service". Those terms "incorporate and are subject to" the Google APIs Terms of Service, and forbid reverse engineering or extracting ML Kit software, which includes its models. Not open source, not GPL-compatible for distribution. | Verified | POM; [terms](https://developers.google.com/ml-kit/terms) |
| minSdk | "requires Android API level 23 or above". The app's minSdk is 26. | Verified | [Android guide](https://developers.google.com/ml-kit/vision/digital-ink-recognition/android) |
| Google Play services | The documentation does not say. The POM depends on the `play-services-base`, `-basement` and `-tasks` client libraries. Digital Ink uses the "dynamically downloaded" model path: models are stored in app-specific storage and managed through `RemoteModelManager`, not delivered by Play services. Whether it runs on a device without Play services is **not confirmed**. The reference AVD (`google_apis`) has Play services. | Reported | POM; [installation paths](https://developers.google.com/ml-kit/tips/installation-paths) |
| Pack size | "Around 20MB of storage is required per language." | Reported (to be measured) | Android guide |
| Download | `RemoteModelManager.download(model, DownloadConditions)`, `isModelDownloaded`, `deleteDownloadedModel`. `DownloadConditions` can require Wi-Fi. | Verified (API) | Android guide |
| Offline | "works fully offline"; recognition "is performed without any network connection". | Reported (to be tested in airplane mode) | [overview](https://developers.google.com/ml-kit/vision/digital-ink-recognition) |
| Context | `WritingArea(width, height)` and `setPreContext` ("up to 20 characters"). The recognizer assumes a single line of text. | Verified (API) | Android guide |
| Ink | `Ink.Stroke.builder()` with `Ink.Point.create(x, y, t)`; `t` is optional. In 19.0.0 the classes are in `com.google.mlkit.vision.digitalink.recognition` (the guide shows `…digitalink`). | Verified (API, `javap` on the AAR) | Android guide; AAR |
| Measured on the AVD (2026-10-28) | Pack download 4.0 s. The first availability check after install took more than 10 s. Recognizing two synthetic strokes took 48 ms, and 50 ms in airplane mode. | Emulator-measured | `TextTrialTest` |
| Data sent to Google | "The user's handwriting stays on the device." ML Kit does contact Google servers for "bug fixes, updated models, and hardware accelerator compatibility information", and sends performance and usage metrics: device manufacturer, model, OS version and build, package name and app version, API configuration, input/output size, event type (including model downloads), error codes, and per-installation identifiers. For Digital Ink, also the configured languages. Sent over HTTPS, not passed to third parties. No opt-out is documented. | Reported | [data disclosure](https://developers.google.com/ml-kit/android-data-disclosure); terms |
| Transitive dependencies | WorkManager 2.7.0, Google datatransport (transport-api, -backend-cct, -runtime), Play services base/basement/tasks, Firebase components and encoders, ML Kit common 18.11.0 and digital-ink-common 16.0.0, OkHttp **3.12.1**, Okio 2.0.0, Kotlin stdlib-jdk7. Licences are recorded in docs/THIRD_PARTY.md once integrated. | Verified (POM) | POM |

The privacy page must describe the metrics traffic and the extra dependencies.

### 3. Measuring it (optional)

ML Kit can be measured on your own handwriting: about
30 short lines from a text prompt set in the corpus capture screen, including
technical prose. Measured on the AVD:

- word accuracy (reference words matched in a minimal word-level alignment,
  over reference words);
- character error rate (edit distance over reference characters);
- p50 and p95 latency;
- total PSS after recognition.

A reasonable target is at least 85% word accuracy, and p95 under 1 s on the emulator. Mouse input on an emulator gives poorer ink than a stylus, so the numbers understate a tablet. ML Kit was adopted without this trial.

### 4. Automatic regions

1. **Grouping is pure Kotlin** in `:core:model` (`notebook/AutoRegions.kt`),
   JVM-tested. The ViewModel only schedules it and applies the result through
   `PageEditor`.
2. **Only ink outside any region is grouped**, by pen (role from
   `RegionRole.forPen`), by line, by proximity and by time. Thresholds scale
   with the median stroke height on the page.
3. **Auto regions are marked** (`Region.auto`, in the same migration). A
   region stops being automatic when the user confirms it, changes its role,
   or makes it with the lasso. Grouping never alters a region that is not
   automatic.
4. Figure groups become Figure regions at once. Math and text groups become
   regions, and recognition starts automatically, one at a time, in reading
   order. Results wait for the user's confirmation; "Confirm all" accepts
   them as they are.
5. **Undo (user decision 2, 2026-10-28):** the grouping is folded into the
   undo entry of the most recent stroke, so a single undo removes both the
   stroke and the grouping it caused. Grouping runs only after new pen
   strokes (1.5 s idle) and before export, never after an undo, so undone
   ink is not regrouped behind the user's back.
6. **Growing (user decision 3, 2026-10-28):**
   - A stroke whose centre is **inside** any region of its role joins it, as
     today. Writing inside a confirmed region is usually a correction, so
     that region goes stale.
   - A stroke **near** (outside but within the proximity threshold) an
     unconfirmed auto region joins it, which makes its result stale and
     re-runs recognition.
   - A stroke near a **confirmed** region starts a new region instead.
7. **Reading order:** new auto regions are inserted by position, top to
   bottom and left to right, without renumbering the user's existing order.
8. A setting, "Group ink into regions automatically", on by default.
9. **Telemetry:** ML Kit's metrics traffic and its added dependencies are accepted, and disclosed on the privacy page.
10. **Order of work:** the pure-Kotlin grouping
    is built while the user writes the trial lines, because it does not
    depend on the go/no-go. The user then said to go ahead with everything
    ("Just do it"), so the integration was built before the trial. A no-go
    replaces only the engine behind `TextRecognizer`.
11. **Queue rules (found in testing):**
    - Recognition runs one at a time.
    - Automatic regions are queued in reading order.
    - A manual Convert waits at the front of the queue instead of being
      refused.
    - A missing model or language pack stops automatic recognition for that
      role until the model is imported or the pack is downloaded.
    - Pasted ink is grouped like new ink.

## Alternatives considered

- **TrOCR-small (ONNX):** open runtime, but the usable weights are IAM-trained
  (research-only), it needs a line image rather than ink, and it is larger
  and slower than an ink recognizer. Kept as the fallback.
- **Typed transcription only:** rejected, as the point of the app is to write by hand.
- **Cloud recognition:** out of scope; the core workflow must stay offline.
- **Grouping only on export:** simpler, but recognition would then all run at
  export time, and the user could not confirm results while writing.

## Consequences

- The app depends on a proprietary library and on Google's metrics traffic, both disclosed.
- Room schema v3 adds `recognizedText` and `auto` to `region`.
- `PageEditor.startRecognition` accepts text and heading regions as well as
  math.
- docs/THIRD_PARTY.md, the licences screen, the installing guide and a privacy
  note gain ML Kit entries.
- Devices without Play services may not run text recognition. That is
  untested until a device without them is tried.

## Amendment (Phase 14, 2026-10-28): the language pack downloads by itself

- Nobody should have to fetch a model by hand for this. When the app opens without the English language pack, it
  downloads it straight away. On a metered connection it asks first, as
  before. *Download handwriting language…* stays in the menu.
- docs/PRIVACY.md is updated to match.

## Amendment (Phase 15, 2026-10-29): scratch is grouped and recognized for search

- Scratch notes should be searchable.
  Scratch ink now groups by line into automatic excluded regions, which are
  recognized as text with the same on-device recognizer, after all other
  work. The result is used only for library search: it is never confirmed,
  counted as waiting, or exported.
- Automatic grouping also suggests headings by writing size (Phase 15).

## Amendment (Phase 33, 2026-10-31): ML Kit in the published app

The app is published on GitHub and Google Play with ML Kit, under an additional permission to the GPL (section 7), in `NOTICE.md`. The copyright holder granted it. Usage metrics to Google are disclosed in `docs/PRIVACY.md`, and must be in Play's Data safety form.
