# Changelog

NibTeXa was built in numbered phases. Code comments such as "Phase 26" refer to this list.

| Phase | Date | What it added |
|---|---|---|
| 0 | 2026-10-26 | Audit, feasibility research and plan |
| 1 | 2026-10-26 | Toolchain, project skeleton and emulator ([ADR-0001](decisions/ADR-0001-toolchain-and-minsdk.md)) |
| 2 | 2026-10-26 | Stroke model, ink canvas and corpus capture ([ADR-0002](decisions/ADR-0002-ink-library-and-stroke-model.md)) |
| 3 | 2026-10-27 | Recognition evaluation, choosing TexTeller for maths ([ADR-0003](decisions/ADR-0003-recognizer.md)) |
| 4 | 2026-10-27 | Rendered math display and correction with MathLive ([ADR-0004](decisions/ADR-0004-math-editor.md)) |
| 5 | 2026-10-27 | First full path: ink to LaTeX, saved and exported ([ADR-0005](decisions/ADR-0005-notebook-storage-and-export.md)) |
| 6 | 2026-10-27 | Note-taking depth: booklets, role-specific pens, lasso and eraser |
| 7 | 2026-10-27 | Git sync on JGit ([ADR-0006](decisions/ADR-0006-git-sync.md)) |
| 8 | 2026-10-27 | Hardening: offline use, performance, accessibility and security |
| 9 | Open | Physical hardware validation; see the checklist in [Testing](development/TESTING.md) |
| 10 | 2026-10-27 | Signed release build for sideloading, in-app model loading and a licences screen |
| 11 | 2026-10-28 | Handwriting to text with ML Kit, and automatic regions ([ADR-0007](decisions/ADR-0007-text-recognition-and-auto-regions.md)) |
| 12 | 2026-10-28 | App flow: library home screen and on-device PDF export |
| 13 | 2026-10-28 | Visual redesign in light and dark, with a pen bar, selection bar, results panel and page overview |
| 14 | 2026-10-28 | Editing and export polish: cover page, authors, equation alignment and reading order |
| 15 | 2026-10-29 | Document structure: headings, lists, table of contents, and searchable scratch |
| 16 | 2026-10-29 | Text formatting, inline maths, theorems, numbered equations and footnotes |
| 17 | 2026-10-29 | Zoom and pan |
| 18 | 2026-10-29 | Importing LaTeX as typed regions |
| 19 | 2026-10-29 | Page sizes, zooming round the sheet, and a Move tool |
| 20 | 2026-10-30 | Markdown import and export, PDF pages to write on, and pictures |
| 21 | 2026-10-30 | Trash, backup and restore ([format](design/BACKUP_FORMAT.md)) |
| 22 | 2026-10-30 | Pen kinds: ballpoint, gel and felt |
| 23 | 2026-10-30 | Perfect shapes: hold the pen still at the end of a stroke |
| 24 | 2026-10-30 | Maths engine that reads an equation's LaTeX for graphing ([ADR-0008](decisions/ADR-0008-maths-engine.md)) |
| 25 | 2026-10-30 | Graphs on the page |
| 26 | 2026-10-31 | Saved clipboard, copying regions, and pasting with results kept |
| 27 | 2026-10-31 | One level of library folders |
| 28 | 2026-10-31 | Contents panel and booklet search |
| 29 | 2026-10-31 | Everyday cleanup: Paste, menus, Settings and the pen bar |
| 30 | 2026-10-31 | Columns, document styles, page margins, and keeping content inside them |
| 31 | 2026-10-31 | Diagrams made of clean, attached parts ([design](design/DIAGRAMS.md)) |
| 32 | 2026-10-31 | A split public build, replaced by Phase 33 |
| 33 | 2026-10-31 | Ready for GitHub and Google Play: ML Kit under a licence permission, model download, automatic backups, and keeping a damaged database |
| 34 | 2026-11-01 | Pen buttons, and a memory warning before the math model's download |
| 35 | 2026-11-01 | A new eraser: works as the pen moves, Precise and Stroke modes, four sizes and a ring showing its size. Scratch ink became a soft grey pencil |
| 36 | 2026-11-01 | Documentation reorganized for a public repository |
| 37 | 2026-11-01 | [Documentation website](https://iamthebarrow.github.io/nibtexa/), published to GitHub Pages |

Migrations keep notes from every earlier database schema readable (`storage/NotebookDatabase.kt`), and the backup reader keeps every earlier format version (`core/export/Backup.kt`).
