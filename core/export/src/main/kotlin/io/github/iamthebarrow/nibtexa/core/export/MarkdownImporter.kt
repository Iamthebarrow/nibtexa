package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.MarkStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMarks
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind

/**
 * Reads Markdown into pages of typed regions (Phase 20). It understands what [MarkdownExporter]
 * writes, so an export imports back, and the Markdown people commonly write in notes: front
 * matter, `#` headings, paragraphs with bold, italic, code and links, nested lists, footnotes,
 * `$…$` and `$$…$$` maths, pictures, and thematic breaks (`---`), which start a new page.
 *
 * Pictures come from the project only, through [import]'s `picture`: images on the web are never
 * downloaded (the app works offline); each becomes a line with its address, and is listed in
 * [ImportResult.warnings]. Code blocks, tables, quotes and HTML become plain text, with a warning.
 * Maths passes the export's checks ([LatexExporter.unsafe]); unsafe maths is left out.
 */
object MarkdownImporter {
    const val MAX_CHARS = 2_000_000
    private const val MAX_BLOCKS = 5_000

    private val HEADING = Regex("""^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val ITEM = Regex("""^( *)([-*+]|\d{1,9}[.)])(\s+|$)(.*)$""")
    private val BREAK = Regex("""^ {0,3}([-*_])(\s*\1){2,}\s*$""")
    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})""")
    // Closing brackets are escaped: Android's regular expressions (ICU) require it.
    private val NOTE_DEFINITION = Regex("""^ {0,3}\[\^([^\]]+)\]:\s*(.*)$""")
    private val IMAGE = Regex("""!\[([^\]]*)\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)""")
    private val TAG = Regex("""\\tag\*?\{([^}]*)\}""")
    private val THEOREM_LABEL = Regex("""^(?:\*\*|\*)(Definition|Theorem|Lemma|Proof|Example|Remark)(?: (\d+))?\.(?:\*\*|\*)\s+""")
    // A tag name ends at a space, a slash or the bracket, so <https://…> stays a link.
    private val HTML_TAG = Regex("""</?[A-Za-z][A-Za-z0-9-]*(?:\s[^>]*)?/?>""")

    /**
     * Reads [source]. [picture] stores an image of the project by its path as written (null if it
     * is not in the project or cannot be read); [newId] makes page ids.
     */
    fun import(source: String, picture: (String) -> ImportedPicture? = { null }, newId: () -> String): ImportResult {
        require(source.length <= MAX_CHARS) { "The file is too large to import" }
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val reader = Reader(picture)
        var start = 0
        // Front matter: the title and author.
        if (lines.firstOrNull()?.trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" || lines[it].trim() == "..." }
            if (end != null) {
                for (line in lines.subList(1, end)) {
                    val key = line.substringBefore(':').trim().lowercase()
                    val value = unquote(line.substringAfter(':', "").trim())
                    if (key == "title") reader.title = value.takeIf { it.isNotBlank() }
                    if (key == "author") reader.author = value.takeIf { it.isNotBlank() }
                }
                start = end + 1
            }
        }
        // Footnotes are defined anywhere and referenced before or after.
        val body = lines.drop(start).filter { line ->
            val m = NOTE_DEFINITION.matchEntire(line) ?: return@filter true
            reader.notes[m.groupValues[1]] = m.groupValues[2].trim()
            false
        }
        reader.read(body)
        reader.flush()
        // With no title in front matter, a single top heading at the start is the title.
        val blocks = reader.pages.flatten()
        val tops = blocks.filterIsInstance<ImportBlock.Heading>().filter { it.level == 1 }
        val pages = if (reader.title == null && tops.size == 1 && blocks.firstOrNull() === tops.single()) {
            reader.title = tops.single().text
            reader.pages.map { page ->
                page.filterNot { it === tops.single() }.map { if (it is ImportBlock.Heading) it.copy(level = (it.level - 1).coerceIn(1, 3)) else it }
            }
        } else {
            reader.pages
        }
        val result = pages.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) }.flatMap { ImportLayout.pages(newId, it) }
        return ImportResult(reader.title, reader.author, result, reader.warnings.toList())
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && (s.first() == '"' && s.last() == '"' || s.first() == '\'' && s.last() == '\'')) {
            s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        } else {
            s
        }

    private class Reader(private val store: (String) -> ImportedPicture?) {
        var title: String? = null
        var author: String? = null
        val notes = mutableMapOf<String, String>()
        val pages = mutableListOf(mutableListOf<ImportBlock>())
        val warnings = linkedSetOf<String>()

        private val paragraph = mutableListOf<String>()
        private var blocks = 0

        /** The list item being read: its depth, numbered or not, and its lines. */
        private var item: Triple<Int, Boolean, MutableList<String>>? = null

        /** The indents of the open lists, outermost first. */
        private val indents = ArrayDeque<Int>()

        fun warn(message: String) {
            if (warnings.size < 100) warnings += message
        }

        private fun add(block: ImportBlock) {
            if (++blocks > MAX_BLOCKS) {
                warn("The file has more than $MAX_BLOCKS blocks; the rest was left out.")
                return
            }
            pages.last() += block
        }

        fun read(lines: List<String>) {
            var i = 0
            while (i < lines.size) {
                val line = lines[i].replace("\t", "    ")
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> {
                        // A blank line ends the paragraph or item; the list goes on if an item follows.
                        flushParagraph()
                        flushItem()
                        i++
                        continue
                    }
                    FENCE.find(line) != null -> {
                        flush()
                        val fence = FENCE.find(line)!!.groupValues[1]
                        val end = (i + 1 until lines.size).firstOrNull { lines[it].trim().startsWith(fence) } ?: lines.size
                        val code = lines.subList(i + 1, end).joinToString("\n").trimEnd()
                        if (code.isNotBlank()) {
                            warnOnce("Code blocks became typewriter text.")
                            val escaped = code.replace("$", "\\$")
                            add(ImportBlock.Text(escaped, listOf(TextMark(0, escaped.length, MarkStyle.Typewriter)), null))
                        }
                        i = end + 1
                        continue
                    }
                    trimmed.startsWith("$$") -> {
                        flush()
                        val after = trimmed.removePrefix("$$")
                        if (after.trimEnd().endsWith("$$") && after.trimEnd().length >= 2) {
                            math(after.trimEnd().removeSuffix("$$"))
                            i++
                        } else {
                            val content = StringBuilder(after)
                            var j = i + 1
                            while (j < lines.size && !lines[j].trimEnd().endsWith("$$")) content.append('\n').append(lines[j++])
                            if (j < lines.size) content.append('\n').append(lines[j].trimEnd().removeSuffix("$$"))
                            math(content.toString())
                            i = j + 1
                        }
                        continue
                    }
                    BREAK.matches(line) && paragraph.isEmpty() -> {
                        flush()
                        if (pages.last().isNotEmpty()) pages += mutableListOf<ImportBlock>()
                    }
                    // Setext headings: a line underlined with = or -.
                    paragraph.isNotEmpty() && item == null && Regex("""^ {0,3}(=+|-+)\s*$""").matches(line) -> {
                        val level = if ('=' in trimmed) 1 else 2
                        val words = paragraph.joinToString(" ")
                        paragraph.clear()
                        heading(level, words)
                    }
                    HEADING.matches(line) -> {
                        flush()
                        val m = HEADING.matchEntire(line)!!
                        heading(m.groupValues[1].length, m.groupValues[2])
                    }
                    ITEM.matches(line) && !(paragraph.isNotEmpty() && item == null && line.startsWith("    ")) -> {
                        val m = ITEM.matchEntire(line)!!
                        flushParagraph()
                        flushItem()
                        val indent = m.groupValues[1].length
                        while (indents.isNotEmpty() && indents.last() > indent) indents.removeLast()
                        if (indents.isEmpty() || indents.last() < indent) indents.addLast(indent)
                        val numbered = m.groupValues[2].first().isDigit()
                        item = Triple((indents.size - 1).coerceIn(0, 2), numbered, mutableListOf(m.groupValues[4]))
                        if (indents.size > 3) warnOnce("Lists nested more than three deep became three deep.")
                    }
                    item != null && line.startsWith("  ") -> item!!.third += trimmed
                    trimmed.startsWith(">") -> {
                        warnOnce("Quotes became ordinary paragraphs.")
                        flushItem()
                        paragraph += trimmed.trimStart('>', ' ')
                    }
                    trimmed.startsWith("|") && trimmed.endsWith("|") -> {
                        flush()
                        warnOnce("Tables became lines of text.")
                        if (!Regex("""^\|[\s:|-]+\|$""").matches(trimmed)) {
                            text(trimmed.trim('|').split('|').joinToString("  ·  ") { it.trim() })
                        }
                    }
                    else -> {
                        if (item != null) {
                            // A lazy continuation of the item's text.
                            item!!.third += trimmed
                        } else {
                            // Text at the margin ends any list.
                            if (paragraph.isEmpty() && line.length - line.trimStart().length < 2) indents.clear()
                            paragraph += trimmed
                        }
                    }
                }
                i++
            }
        }

        fun flush() {
            flushParagraph()
            flushItem()
            indents.clear()
        }

        private fun flushParagraph() {
            if (paragraph.isEmpty()) return
            val words = paragraph.joinToString(" ")
            paragraph.clear()
            text(words)
        }

        private fun flushItem() {
            val (level, numbered, lines) = item ?: return
            item = null
            val (text, marks, pictures) = inline(lines.joinToString(" "))
            val prefix = if (numbered) "1. " else "- "
            if (text.isNotBlank() || pictures.isEmpty()) {
                add(ImportBlock.Item(prefix + text, marks.map { it.copy(start = it.start + prefix.length, end = it.end + prefix.length) }, level))
            }
            pictures.forEach(::add)
        }

        private fun heading(level: Int, words: String) {
            if (level > 3) warnOnce("Headings deeper than three levels became the third level.")
            val (text, marks, pictures) = inline(words)
            if (text.isNotBlank()) add(ImportBlock.Heading(level.coerceIn(1, 3), text, marks, null))
            pictures.forEach(::add)
        }

        /** A paragraph: theorem-style when it starts with the export's bold label. */
        private fun text(words: String) {
            var source = words
            var theorem: TheoremKind? = null
            THEOREM_LABEL.find(source)?.let { m ->
                theorem = TheoremKind.entries.first { it.label == m.groupValues[1] }
                source = source.substring(m.range.last + 1)
            }
            // A picture's caption is the italic line under it, as the export writes it.
            val last = pages.last().lastOrNull()
            val italic = Regex("""^\*([^*]+)\*$""").matchEntire(source.trim())
            if (theorem == null && last is ImportBlock.Picture && italic != null) {
                val caption = inline(italic.groupValues[1]).first
                if (last.caption == null || last.caption == caption) {
                    pages.last()[pages.last().lastIndex] = last.copy(caption = caption)
                    return
                }
            }
            val (text, marks, pictures) = inline(source)
            if (text.isNotBlank()) add(ImportBlock.Text(text, marks, null, theorem))
            pictures.forEach(::add)
        }

        private fun math(raw: String) {
            var latex = raw.trim()
            val numbered = TAG.containsMatchIn(latex)
            latex = TAG.replace(latex, "").trim()
            if (latex.isEmpty()) return
            LatexExporter.unsafe(latex)?.let {
                warnOnce("Some maths was left out: it $it.")
                return
            }
            add(ImportBlock.Math(latex, numbered, null))
        }

        /**
         * Inline Markdown as the app's text and marks, and the pictures found in it, placed after
         * it. Inline maths stays in the text as `$…$`; a literal dollar is `\$` ([InlineMath]).
         */
        fun inline(source: String): Triple<String, List<TextMark>, List<ImportBlock.Picture>> {
            val pictures = mutableListOf<ImportBlock.Picture>()
            val withoutImages = IMAGE.replace(source) { m ->
                val path = m.groupValues[2]
                val alt = m.groupValues[1].trim()
                val web = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(path)
                val stored = if (web) null else store(path.substringBefore('#').substringBefore('?'))
                when {
                    stored != null -> {
                        pictures += ImportBlock.Picture(stored, alt.takeIf { it.isNotEmpty() }, MathAlign.CENTER)
                        ""
                    }
                    web -> {
                        warn("The picture at $path is on the web and was not downloaded; its address was kept.")
                        " [${alt.ifEmpty { "Picture" }}: $path] "
                    }
                    else -> {
                        warn("The picture “$path” is not in the file's folder or could not be read, so it was left out.")
                        if (alt.isNotEmpty()) " [$alt] " else ""
                    }
                }
            }
            if (HTML_TAG.containsMatchIn(withoutImages)) warnOnce("HTML was left out; its words were kept.")
            val spans = Spans()
            spans.parse(HTML_TAG.replace(withoutImages, ""), this)
            val (text, marks) = spans.result()
            val lead = text.length - text.trimStart().length
            val trimmed = text.trim()
            val shifted = marks.map { it.copy(start = it.start - lead, end = it.end - lead) }
            return Triple(trimmed, TextMarks.normalize(shifted, trimmed.length), pictures)
        }

        fun warnOnce(message: String) = warn(message)
    }

    /** Inline text being read: the words and the marks over them. */
    private class Spans {
        private val out = StringBuilder()
        private val marks = mutableListOf<TextMark>()

        fun result(): Pair<String, List<TextMark>> = out.toString() to marks.toList()

        private fun append(s: String) {
            for (c in s) if (!(c == ' ' && out.endsWith(' '))) out.append(c)
        }

        fun parse(s: String, reader: Reader) {
            var i = 0
            while (i < s.length) i = next(s, i, reader)
        }

        private fun styled(content: String, style: MarkStyle?, reader: Reader) {
            val start = out.length
            parse(content, reader)
            if (style != null && out.length > start) marks += TextMark(start, out.length, style)
        }

        /** The index of the closing [delimiter] for one opened at [from], or -1. */
        private fun closing(s: String, from: Int, delimiter: String): Int {
            var j = from
            while (j < s.length) {
                when {
                    s[j] == '\\' -> j++
                    s[j] == '`' -> {
                        val end = s.indexOf('`', j + 1)
                        if (end > j) j = end
                    }
                    s.startsWith(delimiter, j) && j > from && s[j - 1] != ' ' -> {
                        // `**` inside `*…*` belongs to bold, not to the italic's end.
                        if (delimiter.length == 1 && s.startsWith(delimiter + delimiter, j)) {
                            val inner = closing(s, j + 2, delimiter + delimiter)
                            if (inner > 0) {
                                j = inner + 2
                                continue
                            }
                        }
                        return j
                    }
                }
                j++
            }
            return -1
        }

        fun next(s: String, i: Int, reader: Reader): Int {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    val e = s[i + 1]
                    if (e.isLetterOrDigit() || e.isWhitespace()) {
                        append("\\")
                        return i + 1
                    }
                    // An escaped dollar stays escaped: the app's text keeps literal dollars as `\$`.
                    append(if (e == '$') "\\$" else e.toString())
                    return i + 2
                }
                c == '`' -> {
                    val ticks = s.substring(i).takeWhile { it == '`' }
                    val end = s.indexOf(ticks, i + ticks.length)
                    if (end < 0) {
                        append(ticks)
                        return i + ticks.length
                    }
                    val code = s.substring(i + ticks.length, end).trim().replace("$", "\\$")
                    val start = out.length
                    out.append(code)
                    if (out.length > start) marks += TextMark(start, out.length, MarkStyle.Typewriter)
                    return end + ticks.length
                }
                c == '$' -> {
                    val display = s.startsWith("$$", i)
                    val open = if (display) 2 else 1
                    val end = if (display) s.indexOf("$$", i + 2) else s.indexOf('$', i + 1)
                    if (end < 0 || end == i + open || !display && s[i + 1] == ' ') {
                        append("\\$")
                        return i + 1
                    }
                    val latex = s.substring(i + open, end).trim()
                    val problem = LatexExporter.unsafe(latex)
                    if (problem != null) {
                        reader.warnOnce("Some maths was left out: it $problem.")
                    } else {
                        append("$")
                        out.append(latex)
                        append("$")
                    }
                    return end + open
                }
                s.startsWith("[^", i) -> {
                    val close = s.indexOf(']', i)
                    if (close > i) {
                        val note = reader.notes[s.substring(i + 2, close)]
                        // On the word just before it, as the LaTeX import reads `\footnote`.
                        var end = out.length
                        while (end > 0 && out[end - 1] == ' ') end--
                        var start = end
                        while (start > 0 && out[start - 1] != ' ') start--
                        if (note != null && start < end) {
                            val words = Spans().also { it.parse(note, reader) }.result().first.trim()
                            marks += TextMark(start, end, MarkStyle.Footnote(words))
                        }
                        return close + 1
                    }
                }
                c == '[' -> {
                    val close = matching(s, i, '[', ']')
                    if (close > i && close + 1 < s.length && s[close + 1] == '(') {
                        // `(<…>)` may hold brackets and spaces; a bare destination ends at its matching bracket.
                        val angled = s.startsWith("(<", close + 1)
                        val end = if (angled) s.indexOf('>', close + 2).let { if (it < 0) -1 else s.indexOf(')', it) } else matching(s, close + 1, '(', ')')
                        if (end > close) {
                            val inner = s.substring(close + 2, end).trim()
                            val url = if (angled) inner.removePrefix("<").substringBefore('>') else inner.substringBefore(' ')
                            val safe = TextMarks.isSafeUrl(url)
                            if (!safe) reader.warnOnce("A link that was not a web or mail address was kept as text.")
                            styled(s.substring(i + 1, close), MarkStyle.Link(url).takeIf { safe }, reader)
                            return end + 1
                        }
                    }
                }
                c == '<' -> {
                    val end = s.indexOf('>', i)
                    val url = if (end > i) s.substring(i + 1, end) else ""
                    if (TextMarks.isSafeUrl(url)) {
                        val start = out.length
                        out.append(url)
                        marks += TextMark(start, out.length, MarkStyle.Link(url))
                        return end + 1
                    }
                }
                c == '*' || c == '_' || c == '~' -> {
                    val run = s.substring(i).takeWhile { it == c }.length.coerceAtMost(2)
                    val delimiter = c.toString().repeat(run)
                    // Intraword underscores are letters, as in snake_case.
                    val intraword = c == '_' && i > 0 && s[i - 1].isLetterOrDigit()
                    val opens = i + run < s.length && s[i + run] != ' ' && !intraword
                    val end = if (opens) closing(s, i + run, delimiter) else -1
                    if (end > 0) {
                        val content = s.substring(i + run, end)
                        when {
                            c == '~' -> {
                                reader.warnOnce("Struck-through text was kept without the line.")
                                styled(content, null, reader)
                            }
                            run == 2 -> styled(content, MarkStyle.Bold, reader)
                            else -> styled(content, MarkStyle.Italic, reader)
                        }
                        return end + run
                    }
                }
            }
            append(c.toString())
            return i + 1
        }

        private fun matching(s: String, open: Int, a: Char, b: Char): Int {
            var depth = 0
            var j = open
            while (j < s.length) {
                when (s[j]) {
                    '\\' -> j++
                    a -> depth++
                    b -> if (--depth == 0) return j
                }
                j++
            }
            return -1
        }
    }
}
