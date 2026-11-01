package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind

/**
 * Writes a booklet as Markdown (Phase 20): `main.md` and its pictures and ink figures in
 * `images/`. The text is CommonMark with the extensions GitHub, Obsidian and Pandoc share:
 * footnotes (`[^1]`) and maths as `$…$` and `$$…$$`. The title and author go in front matter.
 *
 * It is set from the same [Flow] as the LaTeX and the PDF. Markdown has nothing for underline,
 * colour, text size or alignment, so those are left out ([dropped] says which the booklet uses).
 * Theorem-style blocks start with their bold label, numbered equations carry `\tag`, and booklet
 * pages and page breaks are thematic breaks (`---`), which [MarkdownImporter] reads back as pages.
 *
 * The export checks are the LaTeX export's ([LatexExporter.validate]), so maths that may not be
 * exported as LaTeX is not exported here either.
 */
object MarkdownExporter {
    const val MAIN = "main.md"
    const val IMAGES = "images"

    fun export(booklet: Booklet, figures: FigureRenderer): ExportResult {
        val problems = LatexExporter.validate(booklet)
        if (problems.isNotEmpty()) return ExportResult.Invalid(problems)
        val flows = Flow.of(booklet)
        val files = mutableListOf(ExportFile(MAIN, markdown(booklet, flows)))
        for ((page, blocks) in booklet.pages.zip(flows)) {
            for (block in blocks.filterIsInstance<FlowBlock.Figure>()) {
                files += ExportFile("$IMAGES/${LatexExporter.figureName(page, block.region)}", figures.render(page, block.region))
            }
        }
        return ExportResult.Success(files)
    }

    /** What the booklet uses that Markdown cannot write, for the export's note: "underline", "colour", "size", "alignment". */
    fun dropped(booklet: Booklet): List<String> {
        val blocks = Flow.of(booklet).flatten()
        val styles = blocks.flatMap(::contentOf).mapNotNull { (it as? Inline.Text)?.style ?: (it as? Inline.Math)?.style }
        return buildList {
            if (styles.any { it.underline }) add("underline")
            if (styles.any { it.color != null }) add("colour")
            if (styles.any { it.size != null }) add("size")
        }
    }

    private fun contentOf(block: FlowBlock): List<Inline> = when (block) {
        is FlowBlock.Heading -> block.content
        is FlowBlock.Paragraph -> block.content
        is FlowBlock.ListItem -> block.content
        else -> emptyList()
    }

    private fun markdown(booklet: Booklet, flows: List<List<FlowBlock>>): String = buildString {
        val cover = Cover.of(booklet)
        appendLine("---")
        appendLine("title: ${yaml(cover.title)}")
        if (cover.author.isNotEmpty()) appendLine("author: ${yaml(cover.author)}")
        appendLine("---")
        val notes = mutableListOf<Inline.Note>()
        var first = true
        for ((page, blocks) in booklet.pages.zip(flows)) {
            if (!first) {
                appendLine()
                appendLine("---")
            }
            first = false
            var lastWasItem = false
            // Markdown has no columns: they read one after the other (Phase 30).
            for (block in blocks.filterNot { it is FlowBlock.ColumnsStart || it is FlowBlock.ColumnBreak || it is FlowBlock.ColumnsEnd }) {
                // Items of one list are written together; anything else is a paragraph apart.
                if (!(block is FlowBlock.ListItem && lastWasItem)) appendLine()
                lastWasItem = block is FlowBlock.ListItem
                when (block) {
                    is FlowBlock.Heading -> appendLine("#".repeat(block.level) + " " + inline(block.content, notes))
                    is FlowBlock.Paragraph -> {
                        val label = block.theorem?.let { t ->
                            val name = t.kind.label + (t.number?.let { " $it" } ?: "")
                            if (t.kind == TheoremKind.PROOF) "*$name.* " else "**$name.** "
                        }.orEmpty()
                        appendLine(label + lineStart(inline(block.content, notes), label.isEmpty()))
                    }
                    is FlowBlock.ListItem -> {
                        val marker = if (block.kind == Lists.Kind.BULLETED) "-" else "1."
                        appendLine("    ".repeat(block.level) + "$marker " + inline(block.content, notes))
                    }
                    is FlowBlock.Math -> {
                        appendLine("$$")
                        appendLine(block.latex.trim())
                        block.number?.let { appendLine("\\tag{$it}") }
                        appendLine("$$")
                    }
                    is FlowBlock.Figure -> {
                        val caption = block.region.caption?.trim().orEmpty()
                        appendLine("![${text(caption)}](${IMAGES}/${LatexExporter.figureName(page, block.region)})")
                        if (caption.isNotEmpty()) {
                            appendLine()
                            appendLine("*${text(caption)}*")
                        }
                    }
                    is FlowBlock.PageBreak -> appendLine("---")
                    is FlowBlock.NotReady, is FlowBlock.ColumnsStart, is FlowBlock.ColumnBreak, is FlowBlock.ColumnsEnd -> Unit
                }
            }
        }
        if (notes.isNotEmpty()) {
            appendLine()
            notes.forEach { appendLine("[^${it.number}]: ${text(it.text)}") }
        }
    }

    /** Inline content as Markdown: emphasis, code, links, inline maths and footnote references. */
    private fun inline(content: List<Inline>, notes: MutableList<Inline.Note>): String = content.joinToString("") { piece ->
        when (piece) {
            is Inline.Text -> styled(piece.text, piece.style)
            is Inline.Math -> styled("$${piece.latex.trim()}$", piece.style, raw = true)
            is Inline.Note -> {
                notes += piece
                "[^${piece.number}]"
            }
        }
    }

    private fun styled(words: String, style: Style, raw: Boolean = false): String {
        if (words.isBlank()) return words
        // Emphasis must touch its words, so surrounding spaces stay outside it.
        val lead = words.takeWhile { it == ' ' }
        val trail = words.drop(lead.length).takeLastWhile { it == ' ' }
        val core = words.substring(lead.length, words.length - trail.length)
        var s = when {
            style.typewriter && !raw -> code(core)
            raw -> core
            else -> text(core)
        }
        if (style.italic || style.emphasis) s = "*$s*"
        if (style.bold) s = "**$s**"
        style.link?.let { s = "[$s](${url(it)})" }
        return lead + s + trail
    }

    /** Characters Markdown would read as syntax are escaped; a literal `$` is `\$`, as the app stores it. */
    fun text(s: String): String = buildString {
        for (c in s) {
            if (c in SPECIAL) append('\\')
            append(c)
        }
    }

    private const val SPECIAL = "\\`*_[]<>$~|"

    /** A paragraph's first characters may not read as a heading, list or quote. */
    private fun lineStart(s: String, check: Boolean): String = when {
        !check -> s
        s.startsWith("#") || s.startsWith("+") || s.startsWith("- ") || s.startsWith("-") && s.length == 1 -> "\\$s"
        Regex("""^\d{1,9}[.)]\s""").containsMatchIn(s) -> s.replaceFirst(Regex("""^(\d{1,9})([.)])"""), "$1\\\\$2")
        else -> s
    }

    private fun code(s: String): String {
        val fence = if ('`' in s) "``" else "`"
        val pad = if (s.startsWith("`") || s.endsWith("`")) " " else ""
        return "$fence$pad$s$pad$fence"
    }

    /**
     * A safe URL ([io.github.iamthebarrow.nibtexa.core.model.notebook.TextMarks.isSafeUrl]) as a link
     * destination: in angle brackets when it has brackets or spaces, so it reads back unchanged.
     */
    private fun url(u: String) = if (u.any { it in "() " }) "<${u.replace("<", "%3C").replace(">", "%3E")}>" else u

    /** A front-matter value, quoted. */
    private fun yaml(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
