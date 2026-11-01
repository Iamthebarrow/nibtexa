# ADR-0005: Notebook model, storage and LaTeX export

- **Status:** Accepted
- **Date:** 2026-10-27
- **Builds on:** ADR-0002 (stroke model), ADR-0003 (recognizer), ADR-0004 (math editor)

## Context

Phase 5 is the first end-to-end flow: write, select, convert, correct,
confirm, autosave, reopen and export `.tex`. It needs:

- a page model where conversion never destroys ink;
- durable local storage;
- a predictable export.

## Decisions

### 1. Model (`:core:model`, pure Kotlin)

- **Library → Booklet → Page → Region.** Phase 5 shows one booklet and one
  page, but the schema already has the full hierarchy.
- **Strokes record their pen** (math, text, drawing, scratch). This is ink
  format v2; v1 documents still decode, with every stroke as math.
- **Regions refer to strokes by id and never change ink.**
  - A region keeps the stroke ids it has claimed. Its members are the claimed
    strokes still on the page. Ids are never reused, so undo and redo move
    strokes in and out of regions correctly.
  - A stroke belongs to at most one region, so export regions never overlap.
  - A new stroke whose centre falls inside a region of the same role joins it.
- **Recognized and confirmed LaTeX are stored separately.**
  - A result applies only to the strokes it was produced for. If the ink
    changed meanwhile, the result is discarded.
  - The region is *stale* when its members differ from the strokes it was
    recognized or confirmed for.
  - A confirmed correction survives reconversion until the user confirms
    something else.
- **Region edits are not undoable yet.** Undo and redo cover ink. Phase 6
  brings command-based history for both.

### 2. Storage: Room 2.8.5 (KSP 2.3.12), schema exported to `app/schemas/`

- Tables: `booklet`, `page`, `stroke` (points in the `InkJson` point format)
  and `region`.
- Every page change is **one transaction**. Strokes are written
  incrementally: only added and removed ones.
- **Autosave on every change**, through one sequential save worker. The UI
  tracks `version` and `savedVersion`.
- **No destructive migration fallback.** A missing migration fails loudly
  instead of dropping notes.
- A region left *recognizing* when the process died loads as failed
  ("Interrupted"); its ink is unaffected.

*Alternative considered:* a JSON file per page with atomic rename, which is
simpler and already used for corpus samples. Rejected: Room gives
transactions across pages and regions, and tested migrations as the schema
grows (Phase 6 adds pages and roles).

### 3. Export (`:core:export`, pure Kotlin): one document per booklet (user decision)

- Layout:
  - `main.tex`, which inputs each page in order;
  - `booklets/<booklet-id>/pages/<page-id>.tex`, with regions in reading
    order and each equation in `\[ … \]`;
  - `manifest.json`: format, version, ids, and the strokes each region was
    confirmed for.
- **Deterministic:** no timestamps, stable order, LF line endings. Unchanged
  notes give byte-identical files (golden-file tested).
- **All-or-nothing validation.** Export refuses:
  - unconfirmed or stale regions;
  - unsafe or duplicate ids (ids become path segments);
  - LaTeX with unbalanced braces;
  - primitives that touch files or the document (`\input`, `\write18`,
    `\def`, `\end{document}`, …).
- Preamble: `article` with `amsmath` and `amssymb`. All 159 corpus prompts
  compile with it.
- Written to the app's external files folder, `exports/<booklet-id>/`. The
  write goes to a staging folder that is swapped in, so a failed export
  leaves the previous one intact.

### 4. Recognition in the app (ADR-0003, option C)

- **`:core:recognition` (pure Kotlin):** the recognizer interface, the stroke
  renderer, TexTeller preprocessing, greedy decoding with cancellation, the
  byte-level BPE decoder, and LaTeX clean-up.
- **`OnnxTexTellerModel` (app):** ONNX Runtime 1.31.1 with the KV cache.
  Cross-attention caches come from the first step and are then reused.
- **The model is not in the APK.** It is loaded from the app's model folder
  ([MODEL_SETUP.md](../guide/MODEL_SETUP.md)). Without it, Convert reports that the
  model is missing, and the user can still type the LaTeX.

## Consequences

- **ONNX Runtime adds about 34 MB (arm64) to 41 MB (x86_64) of native code
  per ABI.** Debug builds keep arm64 and x86_64 only. Release builds keep all
  ABIs; an App Bundle delivers one per device. A reduced-operator ONNX
  Runtime build is a Phase 8/10 option.
- **Corpus capture moved to its own screen**, so samples never touch notes.
- **The Phase 4 spike screen is debug-only**, and KaTeX was removed
  (ADR-0004).

## Addendum (Phase 6, 2026-10-27)

- **History.** `PageEditor` keeps a command-based undo/redo per page (up to
  200 steps). Each command records only what it changed:
  - strokes added, removed or replaced;
  - regions added or removed;
  - named region fields.
  So undo never rolls back a recognition result that arrived later.
- **Moving keeps stroke ids**, so a moved, confirmed region stays current.
  Because of that:
  - the canvas cache and the storage diff compare stroke objects by
    identity;
  - storage upserts moved strokes.
- **Roles in export:**
  - math → display math;
  - heading → `\section`, `\subsection` or `\subsubsection`;
  - text → escaped paragraph;
  - figure → `assets/<page-id>-<region-id>.png` placed inline, centred, with
    its caption under it (not a float, so reading order holds);
  - excluded → nothing.
  - The preamble adds `graphicx`. The exporter takes a `FigureRenderer`, and
    the app renders ink to PNG with Android graphics.
- **Confirmation for every role.** Text and heading regions are confirmed
  like math: the typed transcription is stored with the strokes it was
  confirmed for, and goes stale when the ink changes. Figures export their
  current ink without confirmation.
- **Schema v2:** `page.paper`, plus `region.text`, `region.headingLevel` and
  `region.caption`. Migration 1→2 is tested against the exported schemas.
- **Preview:** `LatexExporter.previewPage` shows a page's LaTeX as it would
  export now. Regions that aren't ready appear as comments.
