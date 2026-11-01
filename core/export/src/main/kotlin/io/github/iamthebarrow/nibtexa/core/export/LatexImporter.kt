package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.MarkStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextColor
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMarks
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextSize
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind

/** What an import read: the booklet's title and author, its pages of typed regions, and what was dropped or simplified. */
class ImportResult(val title: String?, val author: String?, val pages: List<Page>, val warnings: List<String>)

/**
 * Reads LaTeX into pages of typed regions (Phase 18). It understands what the LaTeX export
 * writes, so an export imports back, and the LaTeX people commonly write in notes: sections,
 * paragraphs with formatting, inline and display maths, nested lists, theorem-style blocks,
 * footnotes, links, page breaks and alignment environments.
 *
 * It only reads: nothing in the source is run, and other files are read only through [read],
 * which the caller limits to the chosen project. Maths passes the export's checks
 * ([LatexExporter.unsafe]); unsafe maths is left out. Anything it does not understand is dropped
 * with its words kept, and listed in [ImportResult.warnings].
 */
object LatexImporter {
    /** Larger sources are refused. */
    const val MAX_CHARS = 2_000_000
    private const val MAX_DEPTH = 8
    private const val MAX_BLOCKS = 5_000

    /**
     * Reads [main]. [read] returns a file of the project by its path as written in `\input`
     * (null if there is none or it may not be read); [picture] stores a project image by its path
     * as written in `\includegraphics` (Phase 20; null if it is not in the project or cannot be
     * read); [newId] makes page ids.
     */
    fun import(
        main: String,
        read: (String) -> String? = { null },
        picture: (String) -> ImportedPicture? = { null },
        newId: () -> String,
    ): ImportResult {
        require(main.length <= MAX_CHARS) { "The file is too large to import" }
        val parser = Parser(read, picture)
        val source = stripComments(main)
        val begin = source.indexOf("\\begin{document}")
        val preamble = if (begin >= 0) source.substring(0, begin) else ""
        val body = if (begin >= 0) source.substring(begin + "\\begin{document}".length).substringBefore("\\end{document}") else source
        val title = command(preamble, "title")?.let { parser.plain(it) }?.takeIf { it.isNotBlank() }
        val author = command(preamble, "author")?.let { parser.plain(it) }?.removePrefix("Author:")?.trim()?.takeIf { it.isNotBlank() }
        parser.body(body, depth = 0)
        parser.flush()
        val pages = parser.pages.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList<ImportBlock>()) }.flatMap { blocks -> ImportLayout.pages(newId, blocks) }
        return ImportResult(title, author, pages, parser.warnings.toList())
    }

    // ---- Source helpers --------------------------------------------------------------------

    /** The source without `%` comments (an escaped `\%` stays). */
    fun stripComments(source: String): String = source.lineSequence().joinToString("\n") { line ->
        var i = 0
        var cut = line.length
        while (i < line.length) {
            if (line[i] == '\\') {
                i += 2
                continue
            }
            if (line[i] == '%') {
                cut = i
                break
            }
            i++
        }
        line.substring(0, cut)
    }

    /** The argument of the first `\name{…}` in [source], or null. */
    private fun command(source: String, name: String): String? {
        val at = Regex("""\\$name\s*\{""").find(source) ?: return null
        return group(source, at.range.last)?.first
    }

    /** The braced group opening at [open]: its content and the index after its closing brace. */
    private fun group(source: String, open: Int): Pair<String, Int>? {
        if (open >= source.length || source[open] != '{') return null
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i++
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, i) to i + 1
            }
            i++
        }
        return source.substring(open + 1) to source.length
    }

    private val THEOREMS = mapOf(
        "definition" to TheoremKind.DEFINITION, "defn" to TheoremKind.DEFINITION,
        "theorem" to TheoremKind.THEOREM, "thm" to TheoremKind.THEOREM,
        "lemma" to TheoremKind.LEMMA, "lem" to TheoremKind.LEMMA,
        "proof" to TheoremKind.PROOF,
        "example" to TheoremKind.EXAMPLE,
        "remark" to TheoremKind.REMARK, "note" to TheoremKind.REMARK,
        "abstract" to TheoremKind.ABSTRACT,
    )

    // corollary and proposition have no kind of their own: they read as theorems.
    private val AS_THEOREM = setOf("corollary", "proposition", "prop", "cor", "claim")

    private val MATH_ENVIRONMENTS = mapOf(
        "equation" to null, "equation*" to null, "displaymath" to null,
        "align" to "aligned", "align*" to "aligned", "eqnarray" to "aligned", "eqnarray*" to "aligned", "flalign" to "aligned", "flalign*" to "aligned",
        "gather" to "gathered", "gather*" to "gathered", "multline" to "gathered", "multline*" to "gathered",
    )

    private val DISPLAYED = Regex("""\$\\displaystyle\s*(.+)\$\s*(\(\s*\))?""")

    private val CAPTION_AFTER = Regex("""\s*\\par\s*\\small\s+([^\n]*)""")

    private val SIZES = TextSize.entries.associateBy { it.latex }
    private val COLORS = TextColor.entries.associateBy { it.latex }

    /** Commands that only lay out the page, with how many arguments they take: dropped quietly. */
    private val LAYOUT = mapOf(
        "restoregeometry" to 0, "newgeometry" to 1, "raggedcolumns" to 0, "maketitle" to 0, "tableofcontents" to 0, "noindent" to 0, "indent" to 0, "centering" to 0, "raggedright" to 0, "raggedleft" to 0,
        "medskip" to 0, "bigskip" to 0, "smallskip" to 0, "hfill" to 0, "vfill" to 0, "null" to 0, "protect" to 0, "relax" to 0,
        "pagenumbering" to 1, "thispagestyle" to 1, "pagestyle" to 1, "vspace" to 1, "hspace" to 1, "label" to 1, "linebreak" to 0,
        "title" to 1, "author" to 1, "date" to 1, "usepackage" to 1, "documentclass" to 1, "theoremstyle" to 1,
        "fancyhf" to 0, "fancyhead" to 1, "fancyfoot" to 1, "setcounter" to 2,
        "newtheorem" to 2, "setlength" to 2, "addtolength" to 2, "refstepcounter" to 1, "qedhere" to 0, "theequation" to 0,
    )

    /** Walks the source, making blocks. */
    private class Parser(private val read: (String) -> String?, private val store: (String) -> ImportedPicture?) {
        val pages = mutableListOf(mutableListOf<ImportBlock>())
        val warnings = linkedSetOf<String>()

        private var text = Inline()
        private val aligns = ArrayDeque<MathAlign>()
        private val lists = ArrayDeque<Boolean>() // numbered?
        private var item = false
        private var theorem: TheoremKind? = null
        private var blocks = 0

        private fun warn(message: String) {
            if (warnings.size < 100) warnings += message
        }

        private fun add(block: ImportBlock) {
            if (++blocks > MAX_BLOCKS) {
                warn("The file has more than $MAX_BLOCKS blocks; the rest was left out.")
                return
            }
            pages.last() += block
        }

        /** Ends the paragraph or list item being read. */
        fun flush() {
            val (raw, marks) = text.result()
            text = Inline()
            if (raw.isBlank()) {
                item = false
                return
            }
            val lead = raw.length - raw.trimStart().length
            val trimmed = raw.trim()
            val shifted = marks.map { it.copy(start = it.start - lead, end = it.end - lead) }
            // An equation placed left or right, as the export writes it: `$displaystyle …$`, numbered
            // when followed by its number.
            DISPLAYED.matchEntire(trimmed)?.takeIf { !item }?.let { m ->
                math(m.groupValues[1], numbered = m.groupValues[2].isNotEmpty())
                return
            }
            if (item) {
                // A list item is text starting with a bullet or number, as handwriting writes it.
                val prefix = if (lists.lastOrNull() == true) "1. " else "- "
                add(ImportBlock.Item(prefix + trimmed, shifted.map { it.copy(start = it.start + prefix.length, end = it.end + prefix.length) }, (lists.size - 1).coerceIn(0, 2)))
                item = false
            } else {
                add(ImportBlock.Text(trimmed, TextMarks.normalize(shifted, trimmed.length), aligns.lastOrNull(), theorem))
                theorem = null
            }
        }

        /** Stores the project's picture at [path], or warns that it could not be. */
        fun picture(path: String): ImportedPicture? = store(path.trim()) ?: run {
            warn("The picture “${path.trim()}” is not in the project or could not be read, so it was left out.")
            null
        }

        /** `\includegraphics` in the text, as the export writes a figure: a picture of its own. */
        fun placePicture(from: Any, path: String, caption: String?) {
            if (from !== text) {
                warnOnce("A picture inside a heading or footnote was left out.")
                return
            }
            flush()
            picture(path)?.let { add(ImportBlock.Picture(it, caption, aligns.lastOrNull())) }
        }

        private fun pageBreak() {
            flush()
            if (pages.last().isNotEmpty()) pages += mutableListOf<ImportBlock>()
        }

        /** The block-level content of [s], until its end or `\end{[until]}` (returning the index after it). */
        fun body(s: String, depth: Int, start: Int = 0, until: String? = null): Int {
            var i = start
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' -> {
                        val (name, next) = name(s, i)
                        when {
                            name == "end" -> {
                                val (env, after) = group(s, skipSpace(s, next)) ?: ("" to next)
                                if (env == until) return after
                                i = after
                            }
                            name == "begin" -> {
                                val (env, after) = group(s, skipSpace(s, next)) ?: ("" to next)
                                i = environment(s, env.trim(), after, depth)
                            }
                            name in setOf("section", "subsection", "subsubsection", "section*", "subsection*", "subsubsection*", "chapter", "chapter*") -> {
                                flush()
                                val j = optional(s, next)
                                val (arg, after) = group(s, skipSpace(s, j)) ?: ("" to j)
                                val level = when (name.removeSuffix("*")) { "chapter", "section" -> 1; "subsection" -> 2; else -> 3 }
                                if (name.startsWith("chapter")) warn("Chapters became sections.")
                                val heading = Inline().also { it.parse(arg, this) }.result()
                                val trimmed = heading.first.trim()
                                val lead = heading.first.length - heading.first.trimStart().length
                                if (trimmed.isNotEmpty()) add(ImportBlock.Heading(level, trimmed, heading.second.map { it.copy(start = it.start - lead, end = it.end - lead) }, aligns.lastOrNull()))
                                i = after
                            }
                            name == "item" -> {
                                flush()
                                item = lists.isNotEmpty()
                                var j = skipSpace(s, next)
                                if (j < s.length && s[j] == '[') {
                                    val close = s.indexOf(']', j)
                                    if (close > j) {
                                        text.parse(s.substring(j + 1, close), this)
                                        text.append(" ")
                                        j = close + 1
                                    }
                                }
                                i = j
                            }
                            name == "[" -> {
                                flush()
                                val end = s.indexOf("\\]", next).let { if (it < 0) s.length else it }
                                math(s.substring(next, end), numbered = false)
                                i = minOf(s.length, end + 2)
                            }
                            // An export's equation set flush left or right.
                            name == "nibfit" -> {
                                flush()
                                val arg = group(s, skipSpace(s, next))
                                if (arg != null) {
                                    math(arg.first, numbered = false)
                                    i = arg.second
                                } else {
                                    i = next
                                }
                            }
                            name == "columnbreak" -> {
                                flush()
                                add(ImportBlock.ColumnBreak)
                                i = next
                            }
                            name in setOf("clearpage", "newpage", "pagebreak", "cleardoublepage") -> {
                                pageBreak()
                                i = next
                            }
                            name == "par" -> {
                                flush()
                                i = next
                            }
                            name == "input" || name == "include" || name == "subfile" -> {
                                val (path, after) = group(s, skipSpace(s, next)) ?: ("" to next)
                                include(path.trim(), depth)
                                i = after
                            }
                            else -> i = text.command(s, i, this)
                        }
                    }
                    c == '$' && s.startsWith("$$", i) -> {
                        flush()
                        val end = s.indexOf("$$", i + 2).let { if (it < 0) s.length else it }
                        math(s.substring(i + 2, end), numbered = false)
                        i = minOf(s.length, end + 2)
                    }
                    c == '\n' && blankLine(s, i) -> {
                        // A blank line ends a paragraph; inside a theorem it only spaces the words.
                        if (theorem != null) text.append(" ") else flush()
                        i++
                    }
                    else -> i = text.next(s, i, this)
                }
            }
            return i
        }

        private fun blankLine(s: String, i: Int): Boolean {
            var j = i + 1
            while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\r')) j++
            return j < s.length && s[j] == '\n' || j >= s.length
        }

        /** `\begin{env}`: its content, and the index after its `\end`. */
        private fun environment(s: String, env: String, start: Int, depth: Int): Int {
            MATH_ENVIRONMENTS[env]?.let { inner -> return mathEnvironment(s, env, start, inner) }
            if (env in MATH_ENVIRONMENTS) return mathEnvironment(s, env, start, null)
            return when (env) {
                "itemize", "enumerate", "description" -> {
                    flush()
                    if (lists.size >= 3) warn("Lists nested more than three deep became three deep.")
                    lists.addLast(env == "enumerate")
                    val end = body(s, depth, start, env)
                    flush()
                    lists.removeLast()
                    end
                }
                "multicols" -> {
                    flush()
                    add(ImportBlock.ColumnsStart)
                    // Its number of columns; any other than two come in as two.
                    val open = skipSpace(s, start)
                    val inner = if (open < s.length && s[open] == '{') s.indexOf('}', open) + 1 else start
                    val end = body(s, depth, inner, env)
                    flush()
                    add(ImportBlock.ColumnsEnd)
                    end
                }
                "center", "flushleft", "flushright" -> {
                    flush()
                    aligns.addLast(when (env) { "center" -> MathAlign.CENTER; "flushleft" -> MathAlign.LEFT; else -> MathAlign.RIGHT })
                    val end = body(s, depth, start, env)
                    flush()
                    aligns.removeLast()
                    end
                }
                in THEOREMS.keys, in AS_THEOREM -> {
                    flush()
                    if (env in AS_THEOREM) warn("A $env became a theorem.")
                    theorem = THEOREMS[env] ?: TheoremKind.THEOREM
                    // A title in brackets, as `\begin{theorem}[Pythagoras]`, starts the text.
                    var j = start
                    if (j < s.length && s[j] == '[') {
                        val close = s.indexOf(']', j)
                        if (close > j) {
                            text.append("(")
                            text.parse(s.substring(j + 1, close), this)
                            text.append(") ")
                            j = close + 1
                        }
                    }
                    val end = body(s, depth, j, env)
                    flush()
                    theorem = null
                    end
                }
                "verbatim", "lstlisting" -> {
                    flush()
                    val close = "\\end{$env}"
                    val end = s.indexOf(close, start).let { if (it < 0) s.length else it }
                    val raw = s.substring(start, end).trim('\n')
                    if (raw.isNotBlank()) {
                        val escaped = raw.replace("$", "\\$")
                        add(ImportBlock.Text(escaped, listOf(TextMark(0, escaped.length, MarkStyle.Typewriter)), aligns.lastOrNull()))
                    }
                    minOf(s.length, end + close.length)
                }
                "tabular", "tabular*", "array", "table", "tikzpicture", "picture" -> {
                    flush()
                    warn("A table or drawing was left out.")
                    val close = "\\end{$env}"
                    val end = s.indexOf(close, start).let { if (it < 0) s.length else it }
                    minOf(s.length, end + close.length)
                }
                "figure", "figure*" -> {
                    flush()
                    val close = "\\end{$env}"
                    val end = s.indexOf(close, start).let { if (it < 0) s.length else it }
                    val inner = s.substring(start, end)
                    val caption = command(inner, "caption")?.let(::plain)
                    val stored = Regex("""\\includegraphics\s*(?:\[[^\]]*\])?\s*\{([^}]*)\}""").findAll(inner).mapNotNull { picture(it.groupValues[1]) }.toList()
                    val align = if ("\\centering" in inner) MathAlign.CENTER else aligns.lastOrNull()
                    stored.forEachIndexed { i, p -> add(ImportBlock.Picture(p, caption.takeIf { i == stored.lastIndex }, align)) }
                    if (stored.isEmpty() && !caption.isNullOrBlank()) add(ImportBlock.Text("Figure: $caption", emptyList(), aligns.lastOrNull()))
                    minOf(s.length, end + close.length)
                }
                "document", "quote", "quotation", "minipage", "small", "footnotesize" -> body(s, depth, start, env)
                else -> {
                    warn("The environment “$env” was read as plain text.")
                    body(s, depth, start, env)
                }
            }
        }

        private fun mathEnvironment(s: String, env: String, start: Int, inner: String?): Int {
            flush()
            val close = "\\end{$env}"
            val end = s.indexOf(close, start).let { if (it < 0) s.length else it }
            val content = s.substring(start, end).trim()
            val latex = if (inner != null) "\\begin{$inner}$content\\end{$inner}" else content
            math(latex, numbered = !env.endsWith("*") && env != "displaymath")
            return minOf(s.length, end + close.length)
        }

        /** Our export's fitting command round an equation (Phase 30) isn't part of it. */
        private fun unfitted(latex: String): String =
            if (latex.startsWith("\\nibfit{") && latex.endsWith("}")) latex.substring("\\nibfit{".length, latex.length - 1).trim() else latex

        private fun math(raw: String, numbered: Boolean) {
            val latex = raw.trim().replace(Regex("""\\label\{[^}]*\}"""), "").trim().let { unfitted(it) }
            if (latex.isEmpty()) return
            LatexExporter.unsafe(latex)?.let {
                warn("Some maths was left out: it $it.")
                return
            }
            add(ImportBlock.Math(latex, numbered, aligns.lastOrNull()))
        }

        private fun include(path: String, depth: Int) {
            if (depth >= MAX_DEPTH) {
                warn("Files included too deeply were left out.")
                return
            }
            val source = read(path) ?: read("$path.tex")
            if (source == null) {
                warn("“$path” is not in the project, so it was left out.")
                return
            }
            // Each included file is a page of its own, as the export writes them.
            pageBreak()
            body(stripComments(source), depth + 1)
            pageBreak()
        }

        /** [s] as plain words, for titles and footnotes. */
        fun plain(s: String): String = Inline().also { it.parse(s, this) }.result().first.replace(Regex("\\s+"), " ").trim()

        fun warnUnknown(name: String) = warn("“\\$name” is not understood; its words were kept.")
    }

    /** The name of the command at [i] (a backslash): letters (with a trailing star), or one other character. */
    private fun name(s: String, i: Int): Pair<String, Int> {
        var j = i + 1
        if (j >= s.length) return "" to j
        if (!s[j].isLetter()) return s[j].toString() to j + 1
        while (j < s.length && s[j].isLetter()) j++
        if (j < s.length && s[j] == '*') j++
        return s.substring(i + 1, j) to j
    }

    private fun skipSpace(s: String, i: Int): Int {
        var j = i
        while (j < s.length && s[j].isWhitespace()) j++
        return j
    }

    /** Past an optional `[…]` argument at [i]. */
    private fun optional(s: String, i: Int): Int {
        val j = skipSpace(s, i)
        if (j < s.length && s[j] == '[') {
            val close = s.indexOf(']', j)
            if (close > j) return close + 1
        }
        return i
    }

    /** Text with its formatting being read: the words and the marks over them. */
    private class Inline {
        private val out = StringBuilder()
        private val marks = mutableListOf<TextMark>()

        fun result(): Pair<String, List<TextMark>> = out.toString() to marks.toList()

        fun append(s: String) {
            for (c in s) {
                // Runs of space are one space, as in LaTeX.
                if (c.isWhitespace()) {
                    if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                } else {
                    out.append(c)
                }
            }
        }

        fun parse(s: String, parser: Parser) {
            var i = 0
            while (i < s.length) i = next(s, i, parser)
        }

        /** Reads one piece of text at [i] and returns the index after it. */
        fun next(s: String, i: Int, parser: Parser): Int {
            val c = s[i]
            return when {
                c == '\\' -> command(s, i, parser)
                c == '{' -> {
                    val (content, after) = group(s, i)!!
                    styledGroup(content, parser)
                    after
                }
                c == '}' -> i + 1
                c == '~' -> {
                    append(" ")
                    i + 1
                }
                c == '$' -> {
                    val end = s.indexOf('$', i + 1).let { if (it < 0) s.length else it }
                    inlineMath(s.substring(i + 1, end), parser)
                    minOf(s.length, end + 1)
                }
                s.startsWith("---", i) -> { append("—"); i + 3 }
                s.startsWith("--", i) -> { append("–"); i + 2 }
                s.startsWith("``", i) -> { append("“"); i + 2 }
                s.startsWith("''", i) -> { append("”"); i + 2 }
                c == '`' -> { append("‘"); i + 1 }
                c == '\'' -> { append("’"); i + 1 }
                else -> {
                    append(c.toString())
                    i + 1
                }
            }
        }

        private fun inlineMath(raw: String, parser: Parser) {
            val latex = raw.trim()
            if (latex.isEmpty()) return
            val problem = LatexExporter.unsafe(latex)
            if (problem != null) {
                parser.warnUnknownMath(problem)
                return
            }
            append("$")
            out.append(latex)
            append("$")
        }

        /** A `{…}` group: size, style and colour switches inside it last to its end. */
        private fun styledGroup(content: String, parser: Parser) {
            val switches = mutableListOf<Pair<Int, MarkStyle>>()
            var i = 0
            while (i < content.length) {
                if (content[i] == '\\') {
                    val (name, after) = name(content, i)
                    val switch: MarkStyle? = SIZES[name]?.let(MarkStyle::Size) ?: when (name) {
                        "bfseries", "bf" -> MarkStyle.Bold
                        "itshape", "it", "em", "slshape" -> MarkStyle.Italic
                        "ttfamily", "tt" -> MarkStyle.Typewriter
                        else -> null
                    }
                    if (switch != null) {
                        switches += out.length to switch
                        i = after
                        continue
                    }
                    if (name == "color") {
                        val (color, end) = group(content, skipSpace(content, after)) ?: ("" to after)
                        COLORS[color.trim()]?.let { switches += out.length to MarkStyle.Color(it) } ?: parser.warnColor(color.trim())
                        i = end
                        continue
                    }
                }
                i = next(content, i, parser)
            }
            for ((at, style) in switches) if (at < out.length) marks += TextMark(at, out.length, style)
        }

        /** A command at [i] inside text; returns the index after it and its arguments. */
        fun command(s: String, i: Int, parser: Parser): Int {
            val (name, after) = name(s, i)
            fun arg(at: Int = after): Pair<String, Int> = group(s, skipSpace(s, at)) ?: ("" to at)
            fun styled(style: MarkStyle?, at: Int = after): Int {
                val (content, end) = arg(at)
                val start = out.length
                parse(content, parser)
                if (style != null && out.length > start) marks += TextMark(start, out.length, style)
                return end
            }
            when (name) {
                "textbf" -> return styled(MarkStyle.Bold)
                "textit", "textsl" -> return styled(MarkStyle.Italic)
                "emph" -> return styled(MarkStyle.Emphasis)
                "uline", "underline" -> return styled(MarkStyle.Underline)
                "texttt" -> return styled(MarkStyle.Typewriter)
                "textrm", "textsf", "textnormal", "mbox", "text", "textup", "textmd" -> return styled(null)
                "textsc" -> {
                    parser.warnOnce("Small capitals became ordinary letters.")
                    return styled(null)
                }
                "textcolor" -> {
                    val (color, next) = arg()
                    val known = COLORS[color.trim()]
                    if (known == null) parser.warnColor(color.trim())
                    return styled(known?.let(MarkStyle::Color), next)
                }
                "href" -> {
                    val (url, next) = arg()
                    val clean = url.trim().replace("\\#", "#").replace("\\%", "%").replace("\\&", "&").replace("\\_", "_")
                    if (!TextMarks.isSafeUrl(clean)) parser.warnOnce("A link that was not a web or mail address was kept as text.")
                    return styled(MarkStyle.Link(clean).takeIf { TextMarks.isSafeUrl(clean) }, next)
                }
                "url" -> {
                    val (url, next) = arg()
                    val start = out.length
                    append(url.trim())
                    if (TextMarks.isSafeUrl(url.trim()) && out.length > start) marks += TextMark(start, out.length, MarkStyle.Link(url.trim()))
                    return next
                }
                "footnote" -> {
                    val (note, next) = arg(optional(s, after))
                    val words = parser.plain(note)
                    // On the word just before it, as the export writes it after its words.
                    var end = out.length
                    while (end > 0 && out[end - 1] == ' ') end--
                    var start = end
                    while (start > 0 && out[start - 1] != ' ') start--
                    if (words.isNotEmpty() && start < end) marks += TextMark(start, end, MarkStyle.Footnote(words))
                    return next
                }
                "\\", "newline" -> {
                    append(" ")
                    return if (name == "\\") optional(s, after) else after
                }
                "&", "%", "#", "_", "{", "}" -> {
                    append(name)
                    return after
                }
                "$" -> {
                    // A dollar in the text is a literal one ([InlineMath]).
                    out.append("\\$")
                    return after
                }
                " ", ",", ";", ":", "!", "quad", "qquad", "enspace", "thinspace" -> {
                    append(" ")
                    return after
                }
                "-", "/", "@" -> return after
                "textbackslash" -> { out.append("\\"); return skipBraces(s, after) }
                "textasciicircum" -> { append("^"); return skipBraces(s, after) }
                "textasciitilde" -> { append("~"); return skipBraces(s, after) }
                "ldots", "dots", "textellipsis" -> { append("…"); return skipBraces(s, after) }
                "LaTeX" -> { append("LaTeX"); return skipBraces(s, after) }
                "TeX" -> { append("TeX"); return skipBraces(s, after) }
                "today" -> return skipBraces(s, after)
                "(" -> {
                    val end = s.indexOf("\\)", after).let { if (it < 0) s.length else it }
                    inlineMath(s.substring(after, end), parser)
                    return minOf(s.length, end + 2)
                }
                "ref", "eqref", "cite", "pageref" -> {
                    val (key, next) = arg(optional(s, after))
                    parser.warnOnce("References and citations were kept as their labels.")
                    append("[${key.trim()}]")
                    return next
                }
                "includegraphics" -> {
                    val (path, next) = arg(optional(s, after))
                    // The export writes a figure's caption as a small line under it.
                    val caption = CAPTION_AFTER.find(s, next)?.takeIf { it.range.first == next }
                    parser.placePicture(this, path, caption?.let { parser.plain(it.groupValues[1]) })
                    return caption?.range?.last?.plus(1) ?: next
                }
            }
            LAYOUT[name]?.let { count ->
                var at = optional(s, after)
                repeat(count) { at = arg(at).second }
                return at
            }
            // Something else: its words are kept.
            if (name.isNotEmpty()) parser.warnUnknown(name.removeSuffix("*"))
            var at = optional(s, after)
            val j = skipSpace(s, at)
            if (j < s.length && s[j] == '{') at = styled(null, at)
            return at
        }

        private fun skipBraces(s: String, i: Int): Int = if (s.startsWith("{}", i)) i + 2 else i
    }

    private fun Parser.warnOnce(message: String) = warnings.let { if (it.size < 100) it += message }
    private fun Parser.warnColor(color: String) = warnOnce("The colour “$color” is not one of the eight named colours, so it was left out.")
    private fun Parser.warnUnknownMath(problem: String) = warnOnce("Some maths was left out: it $problem.")
}
