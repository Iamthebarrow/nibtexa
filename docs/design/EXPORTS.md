# Exports

## One document

`core/export/Flow.kt` builds the document once: paragraph joining, inline maths, list levels, theorems, numbers, footnotes, columns and page breaks. LaTeX, `PdfLayout`/`BookletPdf` and the reading view all use it, so they agree. Contents and booklet search take their headings from it too.

A region is exported only when it's ready: math, text and headings need a confirmation that still matches their ink. A stale region is reported as a problem, never exported silently.

## LaTeX

- **Packages are added only when used:** `ulem`, `xcolor`, `amsthm`, `hyperref`, `multicol`, `fancyhdr`, `pgfplots`, `tikz`.
- **Sheet and margins:** `geometry` states the margin, so the text width matches the PDF, and `\newgeometry` handles a page's own. A custom sheet uses `paperwidth` and `paperheight`.
- **Fitting:** an equation wider than its column or page is made smaller with `\nibfit`. Figures are limited to the text width.
- **Sections** are unnumbered unless **Number the sections** is on, as the PDF and reading view never number them.
- **Safety:** equations pass `LatexExporter.unsafe`, labels are escaped, and exports compile with `-no-shell-escape`.

The app's own export imports back to the same document (a JVM round-trip test). Importing leaves graphs and diagrams out, with a warning.

## PDF

`export/BookletPdf.kt` typesets on the device: `StaticLayout` for text, and equations captured from MathLive's document page with their baseline (`export/MathCapture.kt`). It lays out twice to fill in the table of contents. In columns, each column runs down the pages by itself, and drawing waits until both are laid out, as a PDF page can't be gone back to.

`export/AnnotatedPdf` writes pages as they look: paper lines or the imported PDF page, pictures and typed text, then ink.

## Markdown

`MarkdownExporter` writes front matter, headings, emphasis, code, nested lists, footnotes, `$` and `$$` maths with `\tag`, theorem labels, and `---` between pages. It lists underline, colour and size as left out, and graphs and diagrams become PNGs. Columns read one after the other. `MarkdownImporter` reads that back, and common Markdown.

## Graphs

`core/export/GraphPlot` goes from what's stored to curves, ticks and any problem per curve, and the screen, PDFs and LaTeX all use it.

- **PDF:** vector lines and text.
- **LaTeX:** `pgfplots`, plotting 300 computed points per curve (`GraphLatex`). TeX evaluates nothing.
- **Markdown:** a PNG.

A curve that can't be read is left out, and the panel says why. Poles and jumps are narrowed in on by bisection, so `1/x` and `\tan x` have no false vertical line.

## Diagrams

`DiagramGeometry.plot` makes every part plain paths, and everything draws those. See [Diagrams](DIAGRAMS.md).
