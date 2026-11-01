package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The contents and search of one booklet (Phase 28). */
class BookletNavigationTest {
    private fun typed(id: String, role: RegionRole, y: Float, text: String? = null, latex: String? = null, level: Int = 1, order: Int = 0) = Region(
        id, role, emptySet(), Bounds(0f, y, 300f, y + 30f), order, RecognitionStatus.CONFIRMED, text = text, confirmedLatex = latex, headingLevel = level, typed = true,
    )

    private fun page(id: String, vararg regions: Region) = Page(id, regions = regions.toList().mapIndexed { i, r -> r.copy(readingOrder = i + 1) }, nextRegionNumber = regions.size + 1)

    private val booklet = Booklet(
        "b1", "Notes",
        listOf(
            page("p1", typed("r1", RegionRole.HEADING, 0f, "Signals", level = 1), typed("r2", RegionRole.TEXT, 100f, "Some words"), typed("r3", RegionRole.HEADING, 200f, "Sampling", level = 2)),
            page("p2", typed("r1", RegionRole.TEXT, 0f, "Only words")),
            page("p3", typed("r1", RegionRole.HEADING, 0f, "Systems", level = 1), typed("r2", RegionRole.HEADING, 200f, "Deep", level = 3)),
        ),
    )

    @Test
    fun contentsAreTheHeadingsWithTheirLevelAndPage() {
        assertEquals(
            listOf(ContentsEntry(1, "Signals", 0, "r1"), ContentsEntry(2, "Sampling", 0, "r3"), ContentsEntry(1, "Systems", 2, "r1"), ContentsEntry(3, "Deep", 2, "r2")),
            BookletNavigation.contents(booklet),
        )
    }

    @Test
    fun contentsMatchTheOrderTheLatexExportSets() {
        val out = LatexExporter.export(booklet) { _, _ -> ByteArray(0) } as ExportResult.Success
        val tex = booklet.pages.joinToString("\n") { p -> out.files.first { it.path == "booklets/b1/pages/${p.id}.tex" }.bytes.decodeToString() }
        val sections = Regex("""\\(section|subsection|subsubsection)\{([^}]*)}""").findAll(tex).map { it.groupValues[2] }.toList()
        assertEquals(sections, BookletNavigation.contents(booklet).map { it.title })
    }

    @Test
    fun aHeadingNotConfirmedIsNotInTheContentsAsItIsNotInTheExports() {
        val unconfirmed = Region("r1", RegionRole.HEADING, setOf(1), Bounds(0f, 0f, 10f, 10f), 1, RecognitionStatus.RECOGNIZED, setOf(1), recognizedText = "Maybe")
        val ink = InkDocument(listOf(Stroke(1, ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0)), Pen.TEXT)), 2)
        assertEquals(emptyList<ContentsEntry>(), BookletNavigation.contents(Booklet("b", "t", listOf(Page("p", ink, listOf(unconfirmed), 2)))))
        assertEquals(emptyList<ContentsEntry>(), BookletNavigation.contents(Booklet("b", "t", listOf(Page("p")))))
    }

    @Test
    fun aHeadingWithMathsKeepsItAndLosesItsFootnote() {
        val b = Booklet("b", "t", listOf(page("p", typed("r1", RegionRole.HEADING, 0f, "The ${'$'}x^2${'$'} case"))))
        assertEquals("The ${'$'}x^2${'$'} case", BookletNavigation.contents(b).single().title)
    }

    @Test
    fun searchIgnoresCaseAndGoesThroughThePagesInOrder() {
        val found = BookletNavigation.search(booklet.pages, "WORDS")
        assertEquals(listOf(0 to "r2", 1 to "r1"), found.map { it.pageIndex to it.regionId })
        assertEquals("Some words", found[0].snippet)
        assertEquals("words", found[0].snippet.substring(found[0].start, found[0].start + found[0].length))
        assertTrue(found.all { it.kind == HitKind.CONFIRMED })
    }

    @Test
    fun aBlankSearchFindsNothing() {
        assertEquals(emptyList<SearchMatch>(), BookletNavigation.search(booklet.pages, "   "))
        assertEquals(emptyList<SearchMatch>(), BookletNavigation.search(booklet.pages, "absent"))
    }

    @Test
    fun specialCharactersAreTakenAsTheyAre() {
        val pages = listOf(page("p", typed("r1", RegionRole.MATH, 0f, latex = "\\frac{x^2}{y}"), typed("r2", RegionRole.TEXT, 100f, "up 50% (a.b) [c] _d_"), typed("r3", RegionRole.TEXT, 200f, "plain axb text")))
        for ((query, region) in listOf("\\frac" to "r1", "x^2" to "r1", "{y}" to "r1", "50%" to "r2", "(a.b)" to "r2", "[c]" to "r2", "_d_" to "r2")) {
            assertEquals(query, listOf(region), BookletNavigation.search(pages, query).map { it.regionId })
        }
        // A dot is a dot, not any character.
        assertEquals(emptyList<SearchMatch>(), BookletNavigation.search(pages, "a.b t"))
        assertEquals(emptyList<SearchMatch>(), BookletNavigation.search(pages, ".*"))
    }

    @Test
    fun resultsSayHowFarToTrustThem() {
        val ink = InkDocument((1L..3L).map { Stroke(it, ToolType.STYLUS, 0, listOf(InkPoint(0f, 100f * it, 0)), Pen.TEXT) }, 4)
        val waiting = Region("r1", RegionRole.TEXT, setOf(1), Bounds(0f, 100f, 9f, 110f), 1, RecognitionStatus.RECOGNIZED, setOf(1), recognizedText = "pole")
        val scratch = Region("r2", RegionRole.EXCLUDED, setOf(2), Bounds(0f, 200f, 9f, 210f), 2, RecognitionStatus.RECOGNIZED, setOf(2), recognizedText = "pole to self", auto = true)
        // Confirmed once, but its ink has changed since.
        val stale = Region("r3", RegionRole.TEXT, setOf(3), Bounds(0f, 300f, 9f, 310f), 3, RecognitionStatus.CONFIRMED, emptySet(), text = "old pole")
        val done = typed("r4", RegionRole.TEXT, 400f, "pole confirmed", order = 4)
        val caption = Region("r5", RegionRole.FIGURE, emptySet(), Bounds(0f, 500f, 9f, 510f), 5, typed = true, picture = "a.png", caption = "Pole plot")
        val page = Page("p", ink, listOf(waiting, scratch, stale, done, caption), 6)
        assertEquals(
            listOf("r1" to HitKind.UNCONFIRMED, "r2" to HitKind.SCRATCH, "r3" to HitKind.UNCONFIRMED, "r4" to HitKind.CONFIRMED, "r5" to HitKind.CONFIRMED),
            BookletNavigation.search(listOf(page), "pole").map { it.regionId to it.kind },
        )
    }

    @Test
    fun whatIsConfirmedWinsOverTheResultItReplaced() {
        val r = typed("r1", RegionRole.TEXT, 0f, "pole here").copy(recognizedText = "pole there")
        assertEquals("pole here", BookletNavigation.search(listOf(page("p", r)), "pole").single().snippet)
    }

    @Test
    fun aLongValueShowsTheWordsRoundTheMatchOnOneLine() {
        val long = "a".repeat(100) + "\nneedle\n" + "b".repeat(100)
        val hit = BookletNavigation.search(listOf(page("p", typed("r1", RegionRole.TEXT, 0f, long))), "NEEDLE").single()
        assertTrue(hit.snippet, hit.snippet.startsWith("…") && hit.snippet.endsWith("…"))
        assertTrue('\n' !in hit.snippet)
        assertEquals("needle", hit.snippet.substring(hit.start, hit.start + hit.length))
        assertTrue(hit.snippet.length < 100)
    }

    @Test
    fun aVeryWideSearchStopsAtTheLimit() {
        val pages = List(50) { p -> page("p$p", *Array(10) { typed("r$it", RegionRole.TEXT, it * 100f, "common") }) }
        assertEquals(BookletNavigation.MAX_MATCHES, BookletNavigation.search(pages, "common").size)
    }

    @Test
    fun aLargeBookletStaysQuick() {
        val pages = List(500) { p ->
            page("p$p", typed("r0", RegionRole.HEADING, 0f, "Heading $p"), *Array(20) { typed("r${it + 1}", RegionRole.TEXT, 100f + it * 100f, "Words on page $p line $it of the notes") })
        }
        val large = Booklet("b", "t", pages)
        repeat(3) { BookletNavigation.contents(large); BookletNavigation.search(pages, "absent") }
        val start = System.nanoTime()
        assertEquals(500, BookletNavigation.contents(large).size)
        assertEquals(emptyList<SearchMatch>(), BookletNavigation.search(pages, "absent"))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$ms ms", ms < 1000)
    }
}
