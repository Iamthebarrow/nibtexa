# ADR-0002: Ink rendering library and stroke data model

- **Status:** Accepted
- **Date:** 2026-10-26

## Context

The app needs low-latency stylus rendering and a stroke representation that
recognition, export, storage and migrations can rely on for years. androidx.ink
reached 1.0.0 stable on 2025-12-17. Its 1.1.0 line is still alpha (1.1.0-alpha10,
2026-10-21) and includes experimental partial erasing.

Verified on 2026-10-26 from the published artifacts:

- License: Apache-2.0, from the `LICENSE.txt` in the AAR. It is compatible with
  GPL-3.0.
- `ink-authoring` 1.0.0 minSdk: 23, from the AAR manifest.
- `ink-authoring` depends on `fragment-ktx`, `graphics-core`,
  `espresso-idling-resource` and the other ink modules. The native code is
  bundled.

## Decision

1. **Rendering:**
   - In-progress strokes use androidx.ink (`InProgressStrokesView`,
     front-buffered and low latency). This was **1.0.0 stable** until Phase 8
     and is now **1.1.0-alpha10**; see the Phase 8 amendment below.
   - Finished strokes are drawn with `CanvasStrokeRenderer` and
     `StockBrushes.pressurePen()`.
   - No alpha-only APIs are used. The code is unchanged between 1.0.0 and the
     alpha.
2. **Source of truth:** the project's own model in `:core:model`, not androidx.ink
   objects or serialization.
   - The model is `Stroke`, `InkPoint` and `InkDocument`, stored in canvas units
     (dp).
   - Optional channels (pressure, tilt, orientation) are `null` when not
     measured, and only stylus and eraser record them.
   - It is persisted as `nibtexa-ink` JSON, version 1.
   - androidx.ink's own finished strokes are discarded. Finished strokes are
     rebuilt from the model, so what is stored is exactly what is drawn.
3. **Input interpretation** sits in a pure-Kotlin state machine (`StrokeCapture`)
   that is separate from the Android adapter (`MotionEventSamples`) and from
   rendering (`InkSurfaceView`), so it can be unit-tested on the JVM. Rules:
   - stylus and mouse ink;
   - fingers ink only when the user enables it;
   - only one stroke is active at a time;
   - while writing with the stylus, other contacts are ignored;
   - a stylus cancels a finger stroke in progress;
   - a second finger cancels a finger stroke and locks finger inking until all
     fingers lift;
   - `FLAG_CANCELED` (API 33+) and `ACTION_CANCEL` drop the stroke;
   - `TOOL_TYPE_PALM`, eraser and unknown tools never ink.
4. **Undo/redo:** bounded snapshots of immutable documents, limited to 100.
   Stroke ids are never reused, even across undo.
5. **Corpus export:** InkML in the CROHME subset, plus native JSON. Files are
   written atomically (a temporary file, then a rename) to app-specific external
   storage, so no storage permission is needed.

## Consequences

- Moving to androidx.ink 1.1 (partial erase, newer brushes) is a library upgrade,
  not a data migration.
- In-progress strokes are drawn by androidx.ink from raw `MotionEvent`s, and
  finished strokes are drawn from the model. Both come from the same samples, but
  smoothing could differ slightly at handoff. No difference was visible on the
  emulator; check on hardware (HW-4).
- Snapshot undo copies the stroke list on every edit (O(n)). That's fine for single
  pages; Phase 6 moves to command-based history.
- The page is held in a ViewModel. It survives rotation, but **process death
  discards unsaved ink** until persistence arrives in Phase 5.
- Finger inking is opt-in (the "Draw with finger" switch). That makes it usable
  without a stylus, and it was needed on the emulator anyway.

## Alternatives considered

- **Custom Compose `Canvas` with `androidx.graphics` front-buffered rendering:** full
  control, but it reimplements latency work that androidx.ink already does.
- **Persisting androidx.ink's `StrokeInputBatch` serialization:** this would tie the
  storage format to a library's encoding. Rejected.
- **androidx.ink 1.1.0 alpha:** the eraser isn't needed until Phase 6, and alpha
  APIs can change between releases. Revisit in Phase 6.

## Amendment (Phase 8, 2026-10-27): androidx.ink 1.1.0-alpha10

- **Why:** with 1.0.0, a cancelled stroke followed by an activity recreation
  crashed in `libhwui` (Phase 6). On the API 37 AVD, the cancel-then-recreate
  pair crashed **8/20** with 1.0.0 and **0/20**, then **0/30**, with alpha10.
- **Checks on alpha10:**
  - full app suite passes;
  - lint: no issues;
  - no source changes were needed.
- **Risk:** this is an alpha. APIs and behaviour can change, and the release
  needs a stable version. Move to the 1.1.0 stable release when it ships, and
  re-run the crash loop (`InkSurfaceTest` pair) then. Hardware check: HW-13.
