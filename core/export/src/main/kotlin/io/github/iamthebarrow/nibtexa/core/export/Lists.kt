package io.github.iamthebarrow.nibtexa.core.export

/**
 * Lists in handwriting (Phase 15): a text region that starts with a bullet (`-`, `•`, `*`, `–`,
 * `—`, `·`) or a number (`1.`, `1)`) is a list item. Consecutive items of one kind, in reading
 * order on a page, make one list: bulleted (`itemize`) or numbered (`enumerate`). Numbered lists
 * count from 1 in the export, whatever numbers were written, as LaTeX does. Items can nest
 * ([Flow], Phase 16).
 */
object Lists {
    enum class Kind { BULLETED, NUMBERED }

    /** An item: its [text] after the marker, which starts at [start] in the region's text. */
    data class Item(val kind: Kind, val text: String, val start: Int = 0)

    // A dash needs a space after it ("-5 dB" is a minus sign); a dot or star may touch the word,
    // as recognition often reads them.
    private val BULLET = Regex("""^\s*(?:[-–—]\s+|[•*·]\s*)(\S.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val NUMBER = Regex("""^\s*\d{1,3}[.)]\s+(\S.*)$""", RegexOption.DOT_MATCHES_ALL)

    /** The list item [text] is, or null if it is ordinary text. */
    fun item(text: String): Item? {
        BULLET.matchEntire(text)?.let { return item(Kind.BULLETED, it) }
        NUMBER.matchEntire(text)?.let { return item(Kind.NUMBERED, it) }
        return null
    }

    private fun item(kind: Kind, match: MatchResult): Item {
        val group = match.groups[1]!!
        return Item(kind, group.value.trim(), group.range.first)
    }
}
