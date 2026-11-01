package io.github.iamthebarrow.nibtexa.core.model.notebook

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** LaTeX's ten font sizes, smallest first, with their size at 10 pt ([points]). */
enum class TextSize(val latex: String, val points: Float) {
    TINY("tiny", 5f),
    SCRIPT("scriptsize", 7f),
    FOOTNOTE("footnotesize", 8f),
    SMALL("small", 9f),
    NORMAL("normalsize", 10f),
    LARGE("large", 12f),
    LARGER("Large", 14.4f),
    LARGEST("LARGE", 17.28f),
    HUGE("huge", 20.74f),
    HUGEST("Huge", 24.88f),
    ;

    /** The size relative to the normal size. */
    val scale: Float get() = points / NORMAL.points
}

/** The named colours of LaTeX's `xcolor` base set the app offers, as RGB. */
enum class TextColor(val latex: String, val rgb: Int) {
    BLACK("black", 0x000000),
    RED("red", 0xFF0000),
    GREEN("green", 0x00FF00),
    BLUE("blue", 0x0000FF),
    CYAN("cyan", 0x00FFFF),
    MAGENTA("magenta", 0xFF00FF),
    YELLOW("yellow", 0xFFFF00),
    WHITE("white", 0xFFFFFF),
    ;

    /** Hard to read on white paper. */
    val faint: Boolean get() = this == YELLOW || this == WHITE
}

/** How a [TextMark] formats its words (Phase 16). */
sealed interface MarkStyle {
    data object Bold : MarkStyle
    data object Italic : MarkStyle
    data object Emphasis : MarkStyle
    data object Underline : MarkStyle
    data object Typewriter : MarkStyle
    data class Size(val size: TextSize) : MarkStyle
    data class Color(val color: TextColor) : MarkStyle

    /** A link to [url]: `http`, `https` or `mailto` only ([TextMarks.isSafeUrl]). */
    data class Link(val url: String) : MarkStyle

    /** A footnote, placed after the marked words. */
    data class Footnote(val note: String) : MarkStyle
}

/** Formatting over the characters [start] (inclusive) to [end] (exclusive) of a region's text. */
data class TextMark(val start: Int, val end: Int, val style: MarkStyle)

/**
 * Editing formatting (Phase 16). Marks are kept normalized: inside the text, not empty, sorted,
 * and with touching or overlapping marks of the same style merged. Sizes, colours and links
 * each have one value per character: setting one replaces the old one there. Footnotes are
 * never merged.
 */
object TextMarks {
    /** A slot holds one value per character; flags (bold, italic…) are their own slot. */
    private fun slot(style: MarkStyle): Any = when (style) {
        is MarkStyle.Size -> MarkStyle.Size::class
        is MarkStyle.Color -> MarkStyle.Color::class
        is MarkStyle.Link -> MarkStyle.Link::class
        is MarkStyle.Footnote -> style
        else -> style
    }

    private val SAFE_URL = Regex("""(?:https?://|mailto:)[^\s{}\\^`"<>|]+""")

    /** Only web and mail links, with no characters that LaTeX or a URL would treat specially. */
    fun isSafeUrl(url: String): Boolean = url.length <= 2000 && SAFE_URL.matches(url)

    fun normalize(marks: List<TextMark>, length: Int): List<TextMark> {
        val clamped = marks.mapNotNull { m ->
            val s = m.start.coerceIn(0, length)
            val e = m.end.coerceIn(0, length)
            if (s < e) m.copy(start = s, end = e) else null
        }
        val merged = mutableListOf<TextMark>()
        for ((style, group) in clamped.groupBy { it.style }) {
            if (style is MarkStyle.Footnote) {
                merged += group
                continue
            }
            var current: TextMark? = null
            for (m in group.sortedBy { it.start }) {
                current = when {
                    current == null -> m
                    m.start <= current.end -> current.copy(end = maxOf(current.end, m.end))
                    else -> current.also { merged += it }.let { m }
                }
            }
            current?.let { merged += it }
        }
        return merged.sortedWith(compareBy({ it.start }, { it.end }, { it.style.toString() }))
    }

    /** Whether every character in start..end has [style]. */
    fun covers(marks: List<TextMark>, start: Int, end: Int, style: MarkStyle): Boolean {
        if (start >= end) return false
        var at = start
        for (m in marks.filter { it.style == style }.sortedBy { it.start }) {
            if (m.start > at) return false
            if (m.end > at) at = m.end
            if (at >= end) return true
        }
        return false
    }

    /** Removes [style]'s slot from start..end, splitting marks that reach outside it. */
    private fun remove(marks: List<TextMark>, start: Int, end: Int, matches: (MarkStyle) -> Boolean): List<TextMark> = marks.flatMap { m ->
        if (!matches(m.style) || m.end <= start || m.start >= end) listOf(m) else listOfNotNull(
            m.copy(end = start).takeIf { it.start < it.end },
            m.copy(start = end).takeIf { it.start < it.end },
        )
    }

    /** Bold, italic and the other flags: on for the whole range, unless it already is, then off. */
    fun toggle(marks: List<TextMark>, start: Int, end: Int, style: MarkStyle, length: Int): List<TextMark> {
        if (start >= end) return marks
        val off = covers(marks, start, end, style)
        val without = remove(marks, start, end) { it == style }
        return normalize(if (off) without else without + TextMark(start, end, style), length)
    }

    /** Sets a size, colour or link over the range, replacing any other there; null removes it. */
    fun set(marks: List<TextMark>, start: Int, end: Int, kind: Any, style: MarkStyle?, length: Int): List<TextMark> {
        if (start >= end) return marks
        val without = remove(marks, start, end) { slot(it) == kind }
        return normalize(if (style == null) without else without + TextMark(start, end, style), length)
    }

    /** The slot of sizes, colours or links, for [set]. */
    val SIZE: Any = MarkStyle.Size::class
    val COLOR: Any = MarkStyle.Color::class
    val LINK: Any = MarkStyle.Link::class

    /** Adds a footnote after the range. */
    fun footnote(marks: List<TextMark>, start: Int, end: Int, note: String, length: Int): List<TextMark> =
        if (start >= end || note.isBlank()) marks else normalize(marks + TextMark(start, end, MarkStyle.Footnote(note.trim())), length)

    /** Removes all formatting from the range, footnotes that end inside it included. */
    fun clear(marks: List<TextMark>, start: Int, end: Int, length: Int): List<TextMark> {
        if (start >= end) return marks
        val kept = marks.filterNot { it.style is MarkStyle.Footnote && it.end in (start + 1)..end }
        return normalize(remove(kept, start, end) { it !is MarkStyle.Footnote }, length)
    }

    /**
     * Moves the marks of [old] onto [new], an edit of it: the changed part is the span between
     * their common prefix and suffix. Marks after it shift; a mark the change falls inside grows
     * or shrinks, and typing at the end of a formatted word continues the formatting (not for
     * links and footnotes). A mark whose text is all removed goes.
     */
    fun follow(marks: List<TextMark>, old: String, new: String): List<TextMark> {
        if (old == new) return marks
        var prefix = 0
        val shortest = minOf(old.length, new.length)
        while (prefix < shortest && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < shortest - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        // old[prefix, removed) became new[prefix, prefix + inserted).
        val removed = old.length - suffix
        val inserted = new.length - suffix - prefix
        val delta = new.length - old.length
        val moved = marks.map { m ->
            val extends = m.style !is MarkStyle.Link && m.style !is MarkStyle.Footnote
            // Typing just before a mark does not join it; a mark starting in the replaced part covers the replacement.
            val start = when {
                m.start < prefix -> m.start
                m.start >= removed -> m.start + delta
                else -> prefix
            }
            val end = when {
                m.end < prefix -> m.end
                m.end == prefix -> if (removed == prefix && extends) m.end + inserted else m.end
                m.end >= removed -> m.end + delta
                else -> prefix + inserted
            }
            m.copy(start = start, end = end)
        }
        return normalize(moved, new.length)
    }

    // ---- Storage ------------------------------------------------------------------------------

    @Serializable
    private data class MarkJson(val s: Int, val e: Int, val t: String, val v: String? = null)

    private val json = Json { explicitNulls = false }
    private val serializer = ListSerializer(MarkJson.serializer())

    /** As compact JSON for storage; null when there are none. */
    fun encode(marks: List<TextMark>): String? = if (marks.isEmpty()) null else json.encodeToString(serializer, marks.map { m ->
        val (t, v) = when (val s = m.style) {
            MarkStyle.Bold -> "b" to null
            MarkStyle.Italic -> "i" to null
            MarkStyle.Emphasis -> "em" to null
            MarkStyle.Underline -> "u" to null
            MarkStyle.Typewriter -> "tt" to null
            is MarkStyle.Size -> "size" to s.size.name
            is MarkStyle.Color -> "color" to s.color.name
            is MarkStyle.Link -> "link" to s.url
            is MarkStyle.Footnote -> "note" to s.note
        }
        MarkJson(m.start, m.end, t, v)
    })

    /** Reads [encode]'s output. Marks it cannot read (or unsafe links) are dropped. */
    fun decode(text: String?): List<TextMark> {
        if (text.isNullOrBlank()) return emptyList()
        val items = try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            return emptyList()
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        return items.mapNotNull { j ->
            val style = when (j.t) {
                "b" -> MarkStyle.Bold
                "i" -> MarkStyle.Italic
                "em" -> MarkStyle.Emphasis
                "u" -> MarkStyle.Underline
                "tt" -> MarkStyle.Typewriter
                "size" -> TextSize.entries.firstOrNull { it.name == j.v }?.let(MarkStyle::Size)
                "color" -> TextColor.entries.firstOrNull { it.name == j.v }?.let(MarkStyle::Color)
                "link" -> j.v?.takeIf(::isSafeUrl)?.let(MarkStyle::Link)
                "note" -> j.v?.takeIf { it.isNotBlank() }?.let(MarkStyle::Footnote)
                else -> null
            }
            style?.let { TextMark(j.s, j.e, it) }
        }
    }
}
