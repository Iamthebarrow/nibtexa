package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole

/** A heading in the contents panel (Phase 28): its level (1 to 3), its words, and where it is. */
data class ContentsEntry(val level: Int, val title: String, val pageIndex: Int, val regionId: String)

/** What a search matched in, which says how far to trust it. */
enum class HitKind {
    /** What the user confirmed, or typed, or a caption. */
    CONFIRMED,

    /** A result the user hasn't confirmed, or one that no longer matches its ink. */
    UNCONFIRMED,

    /** Scratch, which is recognized for search only and never exported. */
    SCRATCH,
}

/** A search match (Phase 28): the words round it, where the match is in those words, and where it is in the booklet. */
data class SearchMatch(val pageIndex: Int, val regionId: String, val snippet: String, val start: Int, val length: Int, val kind: HitKind)

/** Finding the way round one booklet (Phase 28). */
object BookletNavigation {
    /** Most matches shown: a search this wide needs narrowing, not reading. */
    const val MAX_MATCHES = 200
    private const val AROUND = 40

    /** The booklet's headings, from the document the exports set, so they are the same ones in the same order. */
    fun contents(booklet: Booklet): List<ContentsEntry> = Flow.of(booklet).flatMapIndexed { index, blocks ->
        blocks.filterIsInstance<FlowBlock.Heading>().map { ContentsEntry(it.level, Flow.plain(it.content).trim(), index, it.regionIds.first()) }
    }

    /**
     * Where [query] is in [pages], ignoring case, in page and reading order: at most [MAX_MATCHES], and one for each region.
     * The characters are taken as they are, so `x^2` or `50%` find themselves. What is confirmed wins over an old result.
     */
    fun search(pages: List<Page>, query: String): List<SearchMatch> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val found = mutableListOf<SearchMatch>()
        for ((index, page) in pages.withIndex()) {
            for (region in page.regionsInReadingOrder) {
                match(index, page, region, q)?.let { found += it }
                if (found.size >= MAX_MATCHES) return found
            }
        }
        return found
    }

    private fun match(index: Int, page: Page, region: Region, q: String): SearchMatch? {
        val current = region.status == RecognitionStatus.CONFIRMED && !page.isStale(region)
        val confirmedKind = when {
            region.role == RegionRole.EXCLUDED -> HitKind.SCRATCH
            current -> HitKind.CONFIRMED
            else -> HitKind.UNCONFIRMED
        }
        val unconfirmed = if (region.role == RegionRole.EXCLUDED) HitKind.SCRATCH else HitKind.UNCONFIRMED
        val candidates = listOf(
            region.text to confirmedKind,
            region.confirmedLatex to confirmedKind,
            region.caption to (if (region.role == RegionRole.EXCLUDED) HitKind.SCRATCH else HitKind.CONFIRMED),
            region.recognizedText to unconfirmed,
            region.recognizedLatex to unconfirmed,
        )
        for ((value, kind) in candidates) {
            val at = value?.indexOf(q, ignoreCase = true) ?: -1
            if (at >= 0) return snippet(index, region.id, value!!, at, q.length, kind)
        }
        return null
    }

    private fun snippet(index: Int, regionId: String, value: String, at: Int, length: Int, kind: HitKind): SearchMatch {
        val from = (at - AROUND).coerceAtLeast(0)
        val to = (at + length + AROUND).coerceAtMost(value.length)
        val before = if (from > 0) "…" else ""
        val after = if (to < value.length) "…" else ""
        // Line breaks would take the place of the words round the match; each is one character, so the match stays where it is.
        val text = value.substring(from, to).replace('\n', ' ').replace('\r', ' ')
        return SearchMatch(index, regionId, before + text + after, before.length + at - from, length, kind)
    }
}
