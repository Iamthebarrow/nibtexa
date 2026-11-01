package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import io.github.iamthebarrow.nibtexa.core.model.notebook.SheetSize
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import io.github.iamthebarrow.nibtexa.core.model.notebook.marginOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** A generated file, with its path relative to the export root (always `/`-separated). */
class ExportFile(val path: String, val bytes: ByteArray) {
    constructor(path: String, text: String) : this(path, text.toByteArray(Charsets.UTF_8))

    /** The content as UTF-8 text (for .tex and .json files). */
    val content: String get() = bytes.toString(Charsets.UTF_8)

    override fun equals(other: Any?) = other is ExportFile && other.path == path && other.bytes.contentEquals(bytes)
    override fun hashCode() = 31 * path.hashCode() + bytes.contentHashCode()
    override fun toString() = "ExportFile($path, ${bytes.size} bytes)"
}

/** Renders a figure region's ink as a PNG, or gives a picture's file (Phase 20). Provided by the app (Android graphics). */
fun interface FigureRenderer {
    fun render(page: Page, region: Region): ByteArray
}

/** Why a booklet cannot be exported. Export is all-or-nothing. */
sealed interface ExportProblem {
    data class UnsafeId(val id: String) : ExportProblem
    data class DuplicateId(val id: String) : ExportProblem
    data class NotConfirmed(val pageId: String, val regionId: String) : ExportProblem
    data class Stale(val pageId: String, val regionId: String) : ExportProblem
    data class UnsafeLatex(val pageId: String, val regionId: String, val reason: String) : ExportProblem
}

sealed interface ExportResult {
    data class Success(val files: List<ExportFile>) : ExportResult
    data class Invalid(val problems: List<ExportProblem>) : ExportResult
}

/**
 * Generates a LaTeX project from a booklet. One document per booklet: `main.tex` inputs each
 * page's file in page order, and each page lists its regions in reading order.
 *
 * Layout:
 * - `main.tex`
 * - `booklets/<booklet-id>/pages/<page-id>.tex`
 * - `assets/<page-id>-<region-id>.png`: figures, placed inline in reading order
 * - `manifest.json`: schema version, ids, and the strokes each region was confirmed for.
 *
 * The title, author and dates go on an unnumbered title page ([Cover]); numbering starts at 1
 * on the first page of notes.
 *
 * Roles: math → display math, centred or flush left or right
 * ([io.github.iamthebarrow.nibtexa.core.model.notebook.BookletInfo.mathAlign]); heading → `\section` (level 1) to `\subsubsection` (3);
 * text → an escaped paragraph; figure → a centred image with its caption (not a float, so
 * reading order is kept); excluded → nothing.
 *
 * Structure (Phase 16) comes from [Flow]: lines joined into paragraphs, inline maths, nested
 * lists, theorem-style blocks (`amsthm`), numbered equations, page breaks, and formatting,
 * links and footnotes ([rich]).
 *
 * Output is a pure function of the booklet: no timestamps, stable ordering and LF line
 * endings, so unchanged notes export byte-identical files.
 */
object LatexExporter {
    const val MANIFEST_FORMAT = "nibtexa-export"
    const val MANIFEST_VERSION = 1

    /** Ids become path segments, so they are limited to a safe, portable character set. */
    private val SAFE_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")

    // Primitives that read or write files, run commands or redefine the document. Math from
    // the recognizer or the editor never needs them; refusing them keeps a compile of the
    // export from touching anything outside it.
    private val FORBIDDEN = Regex(
        """\\(input|include|includeonly|InputIfFileExists|openin|openout|read|write|immediate|newwrite|newread|""" +
            """special|catcode|def|edef|gdef|xdef|let|csname|usepackage|RequirePackage|documentclass|""" +
            """begin\s*\{\s*document|end\s*\{\s*document|directlua|ShellEscape)(?![a-zA-Z])""",
    )

    private val json = Json { prettyPrint = true }

    fun export(booklet: Booklet, figures: FigureRenderer): ExportResult {
        val problems = validate(booklet)
        if (problems.isNotEmpty()) return ExportResult.Invalid(problems)

        val flows = Flow.of(booklet)
        val files = buildList {
            add(ExportFile("main.tex", mainTex(booklet, flows.flatten())))
            for ((page, blocks) in booklet.pages.zip(flows)) {
                add(ExportFile(pagePath(booklet, page) + ".tex", pageTex(page, blocks, textInches(booklet, page), ownGeometry(booklet, page), booklet.info.style.runningHeader)))
                // A graph is written into the page as pgfplots (Phase 25), and a diagram as TikZ (Phase 31), with no file of their own.
                for (region in page.regionsInReadingOrder.filter { it.role == RegionRole.FIGURE && it.graph == null && it.diagram == null }) {
                    add(ExportFile(figurePath(page, region), figures.render(page, region)))
                }
            }
            add(ExportFile("manifest.json", manifest(booklet)))
        }
        return ExportResult.Success(files)
    }

    fun validate(booklet: Booklet): List<ExportProblem> = buildList {
        val ids = listOf(booklet.id) + booklet.pages.map { it.id }
        ids.filterNot { SAFE_ID.matches(it) }.forEach { add(ExportProblem.UnsafeId(it)) }
        duplicates(booklet.pages.map { it.id }).forEach { add(ExportProblem.DuplicateId(it)) }
        for (page in booklet.pages) {
            duplicates(page.regions.map { it.id }).forEach { add(ExportProblem.DuplicateId("${page.id}/$it")) }
            for (region in page.regionsInReadingOrder) {
                when (region.role) {
                    RegionRole.EXCLUDED -> Unit
                    RegionRole.MATH -> checkMath(page, region)?.let(::add)
                    RegionRole.TEXT, RegionRole.HEADING -> checkText(page, region)?.let(::add)
                    RegionRole.FIGURE -> if (!page.isExportable(region)) add(ExportProblem.Stale(page.id, region.id))
                }
            }
        }
    }

    private fun checkMath(page: Page, region: Region): ExportProblem? {
        if (page.isStale(region)) return ExportProblem.Stale(page.id, region.id)
        if (!page.isExportable(region)) return ExportProblem.NotConfirmed(page.id, region.id)
        return unsafe(region.confirmedLatex.orEmpty())?.let { ExportProblem.UnsafeLatex(page.id, region.id, it) }
    }

    /** Why [latex] may not be exported, or null if it may. */
    fun unsafe(latex: String): String? {
        FORBIDDEN.find(latex)?.let { return "uses ${it.value}" }
        if (!bracesBalanced(latex)) return "unbalanced braces"
        return null
    }

    // Inline maths typed in text passes the same checks as equations (Phase 16).
    private fun checkText(page: Page, region: Region): ExportProblem? = when {
        page.isStale(region) -> ExportProblem.Stale(page.id, region.id)
        !page.isExportable(region) -> ExportProblem.NotConfirmed(page.id, region.id)
        else -> {
            val text = region.text.orEmpty()
            InlineMath.find(text).firstNotNullOfOrNull { unsafe(text.substring(it.first + 1, it.last)) }
                ?.let { ExportProblem.UnsafeLatex(page.id, region.id, it) }
        }
    }

    /** The booklet has a heading to list in a table of contents. */
    fun hasContents(booklet: Booklet): Boolean =
        booklet.pages.any { page -> page.regions.any { it.role == RegionRole.HEADING && page.isExportable(it) } }

    private fun mainTex(booklet: Booklet, blocks: List<FlowBlock>): String = buildString {
        appendLine("% Generated by NibTeXa. Regenerated on every export: edits here are overwritten.")
        val cover = Cover.of(booklet)
        val style = booklet.info.style
        appendLine(if (style.titleOnFirstPage) "\\documentclass{article}" else "\\documentclass[titlepage]{article}")
        appendLine("\\usepackage{amsmath}")
        appendLine("\\usepackage{amssymb}")
        appendLine("\\usepackage{graphicx}")
        if (blocks.any { it is FlowBlock.ColumnsStart }) appendLine("\\usepackage{multicol}")
        // An equation wider than its column or page is made smaller, as nothing may run outside the margins (Phase 30).
        if (blocks.any { it is FlowBlock.Math }) {
            appendLine("\\newsavebox{\\nibbox}")
            appendLine("\\newcommand{\\nibfit}[1]{\\sbox{\\nibbox}{$\\displaystyle #1$}\\ifdim\\wd\\nibbox>\\linewidth\\resizebox{\\linewidth}{!}{\\usebox{\\nibbox}}\\else\\usebox{\\nibbox}\\fi}")
        }
        if (blocks.any { it is FlowBlock.Figure && it.region.graph != null }) {
            appendLine("\\usepackage{pgfplots}")
            appendLine("\\pgfplotsset{compat=1.18}")
        }
        if (blocks.any { it is FlowBlock.Figure && it.region.diagram != null }) DiagramLatex.PREAMBLE.forEach(::appendLine)
        // The booklet's paper (Phase 19); geometry also sets the PDF's page size under pdflatex.
        val sheet = booklet.info.sheet
        appendLine("\\usepackage[${paperOption(sheet)},${marginOption(style)}]{geometry}")
        // Packages for formatting only when the booklet uses it (Phase 16), so booklets without it
        // export as before. hyperref goes last, as it asks.
        val styles = blocks.flatMap(::contentOf).mapNotNull { (it as? Inline.Text)?.style ?: (it as? Inline.Math)?.style }
        if (styles.any { it.underline }) appendLine("\\usepackage[normalem]{ulem}")
        if (styles.any { it.color != null }) appendLine("\\usepackage{xcolor}")
        val theorems = blocks.any { it is FlowBlock.Paragraph && it.theorem != null }
        if (theorems) appendLine("\\usepackage{amsthm}")
        if (styles.any { it.link != null }) appendLine("\\usepackage[colorlinks=true,linkcolor=black,urlcolor=blue]{hyperref}")
        if (theorems) {
            for ((style, kinds) in TheoremKind.entries.filter { it.style != null }.groupBy { it.style!! }) {
                appendLine("\\theoremstyle{$style}")
                kinds.forEach { appendLine("\\newtheorem{${it.environment}}{${it.label}}") }
            }
        }
        // LaTeX numbers sections unless told not to, and the PDF and reading view show the same (Phase 30).
        if (!style.numberedSections && blocks.any { it is FlowBlock.Heading }) appendLine("\\setcounter{secnumdepth}{0}")
        if (style.runningHeader) {
            appendLine("\\usepackage{fancyhdr}")
            appendLine("\\pagestyle{fancy}")
            appendLine("\\fancyhf{}")
            appendLine("\\fancyhead[L]{\\nouppercase{${escapeText(cover.title)}}}")
            appendLine("\\fancyhead[R]{\\nouppercase{\\leftmark}}")
            appendLine("\\fancyfoot[C]{\\thepage}")
        }
        appendLine("\\title{${escapeText(cover.title)}}")
        appendLine("\\author{${escapeText(cover.authorLine.orEmpty())}}")
        appendLine("\\date{${cover.dates.joinToString("\\\\") { escapeText(it) }}}")
        appendLine("\\begin{document}")
        appendLine("\\maketitle")
        // With headings, an unnumbered table of contents, then the notes on a new page from 1
        // (Phase 15). LaTeX fills it in on a second run.
        if (hasContents(booklet) && !style.titleOnFirstPage) {
            appendLine("\\pagenumbering{gobble}")
            appendLine("\\tableofcontents")
            appendLine("\\clearpage")
            appendLine("\\pagenumbering{arabic}")
        }
        booklet.pages.forEach { appendLine("\\input{${pagePath(booklet, it)}}") }
        appendLine("\\end{document}")
    }

    /**
     * The page's LaTeX as it would export now, for the on-tablet preview. Regions that are not
     * ready (unconfirmed, stale, or without ink) appear as a comment saying so instead of
     * content; nothing is validated or refused.
     */
    fun previewPage(page: Page, align: MathAlign = MathAlign.CENTER): String = pageTex(page, Flow.page(page, align))

    private fun contentOf(block: FlowBlock): List<Inline> = when (block) {
        is FlowBlock.Heading -> block.content
        is FlowBlock.Paragraph -> block.content
        is FlowBlock.ListItem -> block.content
        else -> emptyList()
    }

    private fun pageTex(page: Page, blocks: List<FlowBlock>, textInches: Float = MAX_FIGURE_INCHES, geometry: String? = null, header: Boolean = false): String = buildString {
        appendLine("% NibTeXa page ${page.id}")
        // A page with its own margin (Phase 30), on sheets of its own, as LaTeX can only change margins between them.
        geometry?.let {
            appendLine("\\newgeometry{$it}")
            // The header keeps the width it had unless told, and would run off the page.
            if (header) appendLine("\\setlength{\\headwidth}{\\textwidth}")
        }
        // Open list environments, outermost first: consecutive items make one list, nested by
        // level (Phases 15 and 16); anything else closes them.
        val lists = mutableListOf<Lists.Kind>()
        fun closeLists(depth: Int) {
            while (lists.size > depth) appendLine(if (lists.removeAt(lists.lastIndex) == Lists.Kind.BULLETED) "\\end{itemize}" else "\\end{enumerate}")
        }
        var inColumns = false
        for (block in blocks) {
            // Lists end before the next block's comment, and start after it.
            if (block is FlowBlock.ListItem) {
                closeLists(block.level + 1)
                if (lists.size == block.level + 1 && lists.last() != block.kind) closeLists(block.level)
            } else {
                closeLists(0)
            }
            appendLine()
            if (block.regionIds.isNotEmpty()) appendLine(if (block.regionIds.size == 1) "% region ${block.regionIds.single()}" else "% regions ${block.regionIds.joinToString(", ")}")
            when (block) {
                // The break stays where the writing's column ends; columns aren't stretched to the same length (Phase 30).
                is FlowBlock.ColumnsStart -> {
                    inColumns = true
                    appendLine("\\begin{multicols}{2}")
                    appendLine("\\raggedcolumns")
                }
                is FlowBlock.ColumnBreak -> appendLine("\\columnbreak")
                is FlowBlock.ColumnsEnd -> {
                    inColumns = false
                    appendLine("\\end{multicols}")
                }
                is FlowBlock.NotReady -> appendLine("% (${if (block.stale) "ink changed since it was confirmed" else "not confirmed yet"})")
                is FlowBlock.PageBreak -> appendLine("\\clearpage")
                is FlowBlock.ListItem -> {
                    while (lists.size < block.level + 1) {
                        appendLine(if (block.kind == Lists.Kind.BULLETED) "\\begin{itemize}" else "\\begin{enumerate}")
                        lists += block.kind
                    }
                    appendLine("\\item ${rich(block.content)}")
                }
                is FlowBlock.Math -> {
                    val latex = block.latex
                    // Where it sits across the page: its own alignment, or its role's default (Phase 14).
                    when {
                        block.number != null && block.align == MathAlign.CENTER -> {
                            appendLine("\\begin{equation}")
                            appendLine(fitted(latex))
                            appendLine("\\end{equation}")
                        }
                        // Numbered and flush left or right: the number on the right margin, as in LaTeX.
                        block.number != null -> {
                            val number = "\\refstepcounter{equation}(\\theequation)"
                            appendLine("\\par\\medskip\\noindent")
                            appendLine(if (block.align == MathAlign.LEFT) "${inText(latex)}\\hfill$number" else "\\hfill${inText(latex)}\\qquad$number")
                            appendLine("\\par\\medskip")
                        }
                        block.align == MathAlign.CENTER -> {
                            appendLine("\\[")
                            appendLine(fitted(latex))
                            appendLine("\\]")
                        }
                        else -> placedIn(block.align) { appendLine(inText(latex)) }
                    }
                }
                is FlowBlock.Heading -> {
                    val command = SECTIONS[block.level - 1]
                    val text = rich(block.content)
                    val plain = plainTex(block.content)
                    // Formatting stays out of the table of contents: it gets the plain words.
                    val line = if (text == plain) "\\$command{$text}" else "\\$command[$plain]{$text}"
                    if (block.align == MathAlign.LEFT) appendLine(line) else placedIn(block.align) { appendLine(line) }
                }
                is FlowBlock.Paragraph -> {
                    val text = rich(block.content)
                    val theorem = block.theorem
                    when {
                        theorem != null -> {
                            appendLine("\\begin{${theorem.kind.environment}}")
                            appendLine(text)
                            appendLine("\\end{${theorem.kind.environment}}")
                        }
                        block.align == MathAlign.LEFT -> appendLine(text)
                        else -> placedIn(block.align) { appendLine(text) }
                    }
                }
                is FlowBlock.Figure -> {
                    val region = block.region
                    val inches = minOf((region.bounds.right - region.bounds.left) / DP_PER_INCH, if (inColumns) (textInches - COLUMN_GAP_INCHES) / 2 else minOf(MAX_FIGURE_INCHES, textInches))
                    placedIn(block.align) {
                        val graph = region.graph
                        val diagram = region.diagram
                        if (diagram != null) {
                            append(DiagramLatex.picture(diagram, inches))
                        } else if (graph != null) {
                            val shape = (region.bounds.bottom - region.bounds.top) / (region.bounds.right - region.bounds.left).coerceAtLeast(1f)
                            append(GraphLatex.axis(graph, inches, inches * shape))
                        } else {
                            appendLine("\\includegraphics[width=${"%.2f".format(Locale.ROOT, inches)}in]{${figurePath(page, region)}}")
                        }
                        region.caption?.let { appendLine("\\par\\small ${escapeText(it)}") }
                    }
                }
            }
        }
        closeLists(0)
        if (geometry != null) {
            appendLine("\\restoregeometry")
            if (header) appendLine("\\setlength{\\headwidth}{\\textwidth}")
        }
    }

    /**
     * Inline content as LaTeX (Phase 16). Every command comes from the app: text is escaped, maths
     * was checked ([unsafe]), links are web or mail addresses only.
     */
    fun rich(content: List<Inline>): String = content.joinToString("") { piece ->
        when (piece) {
            is Inline.Text -> styled(escapeText(piece.text), piece.style)
            is Inline.Math -> styled("\$${piece.latex}\$", piece.style)
            is Inline.Note -> "\\footnote{${escapeText(piece.text)}}"
        }
    }

    /** The words only, for a heading in the table of contents: escaped text and its maths. */
    private fun plainTex(content: List<Inline>): String = content.joinToString("") { piece ->
        when (piece) {
            is Inline.Text -> escapeText(piece.text)
            is Inline.Math -> "\$${piece.latex}\$"
            is Inline.Note -> ""
        }
    }

    private fun styled(text: String, style: Style): String {
        var s = text
        if (style.underline) s = "\\uline{$s}"
        if (style.emphasis) s = "\\emph{$s}"
        if (style.italic) s = "\\textit{$s}"
        if (style.bold) s = "\\textbf{$s}"
        if (style.typewriter) s = "\\texttt{$s}"
        style.size?.let { s = "{\\${it.latex} $s}" }
        style.color?.let { s = "\\textcolor{${it.latex}}{$s}" }
        // A safe URL has no braces or backslashes; % and # still need escaping in \href.
        style.link?.let { s = "\\href{${it.replace("%", "\\%").replace("#", "\\#")}}{$s}" }
        return s
    }

    /** [body] in a `flushleft`, `center` or `flushright` environment. */
    private inline fun StringBuilder.placedIn(align: MathAlign, body: () -> Unit) {
        val environment = when (align) {
            MathAlign.LEFT -> "flushleft"
            MathAlign.CENTER -> "center"
            MathAlign.RIGHT -> "flushright"
        }
        appendLine("\\begin{$environment}")
        body()
        appendLine("\\end{$environment}")
    }

    /** geometry's paper: a named size, or a custom one (Phase 20: an imported PDF's) by its measures. */
    private fun paperOption(sheet: Sheet): String = if (sheet.size == SheetSize.CUSTOM) {
        "paperwidth=${"%.3f".format(Locale.ROOT, sheet.width / DP_PER_INCH)}in,paperheight=${"%.3f".format(Locale.ROOT, sheet.height / DP_PER_INCH)}in"
    } else {
        sheet.size.latex + if (sheet.landscape) ",landscape" else ""
    }

    /**
     * geometry's margins (Phase 30). The default leaves room in them for the page number and any header; where a margin is too small to,
     * they come out of the text area, so that they stay on the page.
     */
    private fun marginOption(style: io.github.iamthebarrow.nibtexa.core.model.notebook.DocumentStyle): String {
        val margin = "margin=${"%.2f".format(Locale.ROOT, style.margin.inches)}in"
        val small = style.margin.inches < SMALL_MARGIN_INCHES
        return when {
            small && style.runningHeader -> "$margin,includeheadfoot"
            small -> "$margin,includefoot"
            else -> margin
        }
    }

    private const val SMALL_MARGIN_INCHES = 0.7f

    private fun figurePath(page: Page, region: Region) = "assets/${figureName(page, region)}"

    /**
     * A figure's file name in the exports: ink is drawn as PNG; a picture (Phase 20) keeps its own
     * type, PNG or JPEG.
     */
    fun figureName(page: Page, region: Region): String {
        val type = region.picture?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in PICTURE_TYPES } ?: "png"
        return "${page.id}-${region.id}.$type"
    }

    private val PICTURE_TYPES = setOf("png", "jpg")

    private val SECTIONS = listOf("section", "subsection", "subsubsection")

    /** Android's density-independent pixel is 1/160 inch. */
    private const val DP_PER_INCH = 160f

    /** Wider figures are scaled to fit article's default text width. */
    private const val MAX_FIGURE_INCHES = 4.5f

    /** LaTeX's \`\\columnsep\`, 10 pt. */
    private const val COLUMN_GAP_INCHES = 0.14f

    /** Fits the export's margins: paper width less them (Phase 30). */
    private fun textInches(booklet: Booklet, page: Page): Float = booklet.info.sheet.width / DP_PER_INCH - 2 * booklet.info.style.marginOf(page).inches

    private fun ownGeometry(booklet: Booklet, page: Page): String? {
        val style = booklet.info.style
        return page.margin?.takeIf { it != style.margin }?.let { marginOption(style.copy(margin = it)) }
    }

    /**
     * An equation made to fit (\`\\nibfit\`, Phase 30). Lines broken with \`\\\\\\\\\` outside an environment can't go in a box, so
     * they are left as they are.
     */
    private fun fitted(latex: String): String = if ("\\\\" in latex && "\\begin" !in latex) latex else "\\nibfit{$latex}"

    private fun inText(latex: String): String = if ("\\\\" in latex && "\\begin" !in latex) "\$\\displaystyle $latex\$" else "\\nibfit{$latex}"

    private fun manifest(booklet: Booklet): String {
        val obj = buildJsonObject {
            put("format", MANIFEST_FORMAT)
            put("version", MANIFEST_VERSION)
            put("booklet", buildJsonObject {
                put("id", booklet.id)
                put("title", booklet.title)
            })
            put("pages", buildJsonArray {
                for (page in booklet.pages) {
                    add(buildJsonObject {
                        put("id", page.id)
                        put("file", pagePath(booklet, page) + ".tex")
                        put("regions", buildJsonArray {
                            for (region in page.regionsInReadingOrder) {
                                add(buildJsonObject {
                                    put("id", region.id)
                                    put("role", region.role.name.lowercase())
                                    put("readingOrder", region.readingOrder)
                                    if (region.role == RegionRole.FIGURE && region.graph == null && region.diagram == null) put("file", figurePath(page, region))
                                    put("sourceStrokeIds", JsonArray(region.sourceStrokeIds.sorted().map(::JsonPrimitive)))
                                })
                            }
                        })
                    })
                }
            })
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj) + "\n"
    }

    private fun pagePath(booklet: Booklet, page: Page) = "booklets/${booklet.id}/pages/${page.id}"

    /** Escapes prose for LaTeX text mode. */
    fun escapeText(text: String): String = buildString {
        for (c in text) {
            append(
                when (c) {
                    '\\' -> "\\textbackslash{}"
                    '{', '}', '$', '&', '#', '_', '%' -> "\\$c"
                    '^' -> "\\textasciicircum{}"
                    '~' -> "\\textasciitilde{}"
                    '\n', '\r' -> " "
                    else -> c.toString()
                },
            )
        }
    }

    private fun bracesBalanced(latex: String): Boolean {
        var depth = 0
        var i = 0
        while (i < latex.length) {
            when (latex[i]) {
                '\\' -> i++ // skip the escaped character, e.g. \{ or \}
                '{' -> depth++
                '}' -> if (--depth < 0) return false
            }
            i++
        }
        return depth == 0
    }

    private fun duplicates(ids: List<String>) = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
