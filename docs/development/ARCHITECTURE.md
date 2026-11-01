# Architecture

NibTeXa is an Android app in Kotlin and Jetpack Compose. Most logic is in pure Kotlin modules, tested on the JVM, with the Android parts kept thin.

## Modules

| Module | What it holds |
|---|---|
| `:core:model` | The stroke model with pens, input rules (`StrokeCapture`), undo and redo, JSON and InkML, LaTeX token normalization, and the notebook model: `Page`, `Region`, `PageEditor`, `Lasso`, `Eraser`, `AutoRegions`, `Diagram`, `Graph` |
| `:core:export` | LaTeX, Markdown and import, with golden-file tests. `Flow` builds the document once for every output. Backup files |
| `:core:recognition` | The recognition pipeline: stroke renderer, TexTeller preprocessing, decoding, tokenizer, checked against Python fixtures. The `TextRecognizer` contract |
| `:core:sync` | Git sync on JGit, tested against local bare repositories |
| `:core:maths` | Reads an equation's LaTeX into a function and samples it into a curve. No dependencies |
| `:feature:mathedit` | MathLive in a [locked-down local WebView](../design/MATH_BRIDGE.md), with a typed message bridge |
| `:app` | The notebook UI, Room storage and autosave, the Git sync screen, ONNX Runtime and ML Kit |
| `:spike:recognition` | An evaluation-only device test for ONNX Runtime. Never a dependency of `:app` |

`tools/` holds the recognition evaluation harness, a Docker Git test remote and the secrets scan.

## Ideas that hold it together

**The project's model is the source of truth for ink,** not androidx.ink. The ink library draws; `core/model` stores. Coordinates are canvas units, and `ink/PageTransform` maps every layer and touch to the screen.

**A page is strokes plus regions.** A stroke belongs to at most one region. A region's role comes from the pen (math, text, figure, excluded scratch). **Typed regions** hold no ink: imported text, pictures, graphs and diagrams.

**Every edit goes through `PageEditor`,** as a command with its own undo. Recognition results aren't undoable, and undo never rolls them back.

**A result belongs to exact strokes.** A region remembers which stroke ids its result was made from (`sourceStrokeIds`), and is stale if its strokes differ. That is why strokes cut by the eraser get new ids. A late recognition result for older ink is discarded. Confirmations are stored separately from what the recognizer returned.

**Recognition sits behind interfaces** (`MathRecognizer`, `TextRecognizer`), so a model can be replaced without touching the UI or storage. Heavy work stays off the main thread.

**One document for all outputs.** `core/export/Flow.kt` joins paragraphs and marks inline maths, lists, theorems, columns and page breaks, then LaTeX, PDF and the reading view all use it. See [Exports](../design/EXPORTS.md).

**Git sync only touches its own folder.** It uses a bare working copy, builds commits from objects, changes only `<folder>/<booklet>/`, never force-pushes, and tracks outside edits in `.nibtexa-sync.json`.

## Storage

Room holds notes, with migrations for every schema change. Pictures are files in `storage/PictureStore` (`filesDir/pictures`), and unused ones are removed when the app opens. A change that adds stored data must also extend [the backup format](../design/BACKUP_FORMAT.md) and raise its version, keeping older versions readable.

## Layout

`NotebookViewModel` holds the open booklet's state. The canvas is `ink/InkSurfaceView`, a stack of layers: paper, finished strokes, typed regions, androidx.ink's low-latency layer for the stroke being written, and the selection overlay.

Layout adapts to width: the library header stacks below 960 dp, and the results panel becomes a bottom sheet below 900 dp. Config changes don't recreate the activities, which keeps the ink surface alive.

## Decisions

Why each major choice was made is in the [decision records](../decisions/README.md).
