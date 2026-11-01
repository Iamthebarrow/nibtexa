# Features

How NibTeXa works, and what it doesn't do yet.

## The library

The app opens on your booklets, newest first, each with a picture of its first page. You can create, rename, duplicate and delete them, sort them into one level of folders, and search everything that has been recognized.

Deleted booklets stay in the **Trash** for 30 days, unless you turn that off in Settings.

## Pages

- **Paper:** blank, ruled or grid, in A4, A5, US Letter and US Legal, portrait or landscape.
- **Margins:** Cheat sheet, Narrow, Normal or Wide, for a booklet or a single page. Ink, pictures and moved content stay inside them. A stroke that crosses the margin is cut there.
- **Zoom and pan:** pinch with two fingers. One finger moves the page unless finger drawing is on. The **Move** tool moves it with any pointer.
- **Columns:** detected from your writing, or set in **Page layout…**.

## Pens and tools

| Tool | What it does |
|---|---|
| **Math** | Writing that becomes LaTeX |
| **Text** | Writing that becomes words |
| **Draw** | Freehand figures. Hold the pen still at the end of a stroke to make it a perfect line, circle, box or other shape |
| **Diagram** | Each stroke becomes a clean part of a figure |
| **Scratch** | Pencil-grey working that is never exported, but can be found by search |
| **Select** | The lasso: select to move, resize, copy, paste or delete |
| **Erase** | Removes ink as the pen moves; one undo restores the whole pass |
| **Move** | Moves the page |

Math, Text and Draw come in ballpoint, gel and felt, which only change how the ink looks.

### The eraser

With the eraser in hand, the button beside the tools sets how it works:

- **Precise** removes only the ink it passes over, and **Stroke** removes each whole stroke it touches.
- **Small, Medium, Large and Extra large** are measured on the paper, so a size stays the same on the page at any zoom. A ring shows the size.

If erasing changes handwriting that hasn't been confirmed, it's read again. Confirmed work stays as you confirmed it, and is marked out of date.

### Pen buttons

Settings has **Pen button** (Eraser by default) and **Second pen button** (Lasso by default). Each can be Eraser, Lasso, Move the page, Scratch pen or Nothing. Hold the button as the pen touches the page, and let go to get your tool back. Something selected this way can be moved, and tapping away brings your tool back.

## From handwriting to a document

Ink groups into **regions** by itself when you pause. Math and text regions are then recognized on the tablet, and drawings become figures with captions. Check each result in the results panel, or tap **Confirm all**.

- Maths is corrected in a rendered editor, and text in a text box with formatting.
- Your ink is always kept. A result that no longer matches its ink is marked out of date, and export reports it.
- Writing larger suggests a heading. Lines starting with a dash, bullet or number become lists.
- Text can also be a theorem-style block or an abstract, and equations can be inline, aligned or numbered.

Only recognized ink can be found by search.

## Diagrams

Draw with the Diagram pen, and each stroke becomes a box, circle, line with arrowheads, summing point or label as soon as it ends. Lines stay attached to shapes when those move. With the lasso, a tap opens a part in the panel, and a drag moves it. Circuit symbols are chosen in the panel, on a line or shape.

Limits: a small circle with a straight stroke inside it becomes a summing point, and writing that can't be read stays as handwriting. Undo takes back any one stroke. Lines don't route around things.

## Graphs

Graph an equation you've written, and it follows later corrections. While a graph's controls are open, touches inside it pan and zoom it.

**It reads** `y=x^2`, `f(x)=…` and an expression alone, with arithmetic, `\frac`, roots, trigonometric and hyperbolic functions, logs, Greek letters and constants you give values to.

**It can't read yet** implicit curves such as `x^2+y^2=1`, vertical lines, sums, integrals, limits, derivatives, matrices or inequalities. It says why, and makes no graph. Polar and parametric curves aren't supported either.

The exact reading rules, such as `1/2x` meaning `(1/2)x`, are in [ADR-0008](../decisions/ADR-0008-maths-engine.md).

## Clipboard

Copy keeps your selection, with results, in a list that survives closing the app: the latest 50 entries, plus any you pin. Confirmed equations paste typed, or **With handwriting** to keep the ink. An entry can also be sent to a graph. Confirmed words and equations also go to Android's clipboard unless you turn that off.

Pasting from other apps' clipboards isn't supported.

## Import and export

| Format | Export | Import |
|---|---|---|
| **PDF** | Typeset on the tablet, or annotated pages as they look | Pages to write on, at most 200; no text is read from them |
| **LaTeX** | A project `.zip`, or `.tex` | `.tex` or a project `.zip`, as typed regions, with a report of what was dropped |
| **Markdown** | With pictures | Common Markdown; web images aren't downloaded |

Importing a LaTeX export leaves graphs and diagrams out, with a warning, and importing Markdown gives them as pictures. A backup keeps them whole.

LaTeX exports with a graph need `pgfplots`, which full TeX distributions and Overleaf include.

## Keeping notes safe

- Everything saves automatically and reopens as you left it.
- **Automatic backups** go to a folder you choose, at most one a day when the app opens, keeping the newest 3. A tablet where the app isn't opened makes none.
- **Back up** and **Restore from backup** are in the library's menu. A backup holds booklets, ink, results and pictures, and isn't encrypted.
- If the notes database is damaged, a copy is kept, and the app says so.
- Uninstalling the app deletes everything in it, so back up first.

## Git sync

**Sync with Git…** commits and pushes each booklet's LaTeX project and PDF to a repository you set up, on any Git server. It only changes that booklet's folder, never force-pushes, and asks about generated files you edited elsewhere. Your access token is encrypted with the Android Keystore. See [ADR-0006](../decisions/ADR-0006-git-sync.md).

GitHub is the only host tested so far.

## Known limits

- Large libraries and very long booklets haven't been measured on a real tablet.
- Tablets without Google Play services may not run text recognition.
- Only English handwriting is set up.
- A page that crashes the app on opening keeps doing so. A backup can still move the notes.
