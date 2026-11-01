package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatic regions (Phase 11, ADR-0007 section 4). Handwriting here is 24 units tall, so the
 * page scale h is 24: words are 10 apart, lines 60 apart top to top.
 */
class AutoRegionsTest {

    private val editor = PageEditor(Page("p1"))

    /** Strokes are written 10 s apart unless a time is given, so the burst rule stays out of the way. */
    private var clock = 0L

    /** A diagonal stroke filling the box (x, y, w, h). Returns its id. */
    private fun write(x: Float, y: Float, w: Float = 12f, h: Float = 24f, pen: Pen = Pen.TEXT, at: Long? = null, durationMs: Long = 100): Long {
        val start = at ?: (clock + 10_000).also { clock = it }
        editor.addStroke(CapturedStroke(ToolType.STYLUS, start, listOf(InkPoint(x, y, 0), InkPoint(x + w, y + h, durationMs)), pen))
        return editor.page.ink.strokes.last().id
    }

    /** A word of [letters] strokes, 12 wide and 4 apart. Returns the ids. */
    private fun word(x: Float, y: Float, letters: Int = 3, pen: Pen = Pen.TEXT): Set<Long> =
        (0 until letters).mapTo(linkedSetOf()) { write(x + it * 16f, y, pen = pen) }

    private fun group(): List<String> = editor.autoGroup(AutoRegions.plan(editor.page))

    private fun region(id: String) = checkNotNull(editor.page.region(id))

    private fun ordered() = editor.page.regionsInReadingOrder

    // ---- Grouping ---------------------------------------------------------------------------

    @Test
    fun oneLineOfWordsIsOneTextRegion() {
        val ids = word(0f, 0f) + word(58f, 0f) + word(116f, 0f)
        val created = group()

        assertEquals(1, created.size)
        val r = region(created.single())
        assertEquals(RegionRole.TEXT, r.role)
        assertEquals(ids, r.strokeIds)
        assertTrue(r.auto)
        assertEquals(RecognitionStatus.NOT_REQUESTED, r.status)
    }

    @Test
    fun twoLinesAreTwoRegionsTopFirst() {
        val second = word(0f, 60f) + word(58f, 60f)
        val first = word(0f, 0f) + word(58f, 0f)
        group()

        assertEquals(listOf(first, second), ordered().map { it.strokeIds })
    }

    @Test
    fun twoColumnsAreReadOneColumnAfterTheOther() {
        val leftTop = word(0f, 0f)
        val rightTop = word(400f, 0f)
        val leftBottom = word(0f, 60f)
        val rightBottom = word(400f, 60f)
        group()

        assertEquals(listOf(leftTop, leftBottom, rightTop, rightBottom), ordered().map { it.strokeIds })
    }

    @Test
    fun textNextToMathIsTwoRegionsInLineOrder() {
        val text = word(0f, 0f) + word(58f, 0f)
        val math = word(140f, 0f, pen = Pen.MATH)
        group()

        assertEquals(listOf(RegionRole.TEXT to text, RegionRole.MATH to math), ordered().map { it.role to it.strokeIds })
    }

    @Test
    fun aDrawingNextToTextIsAFigureOfItsOwn() {
        val text = word(0f, 0f)
        val drawing = setOf(write(60f, -10f, w = 80f, h = 60f, pen = Pen.DRAWING), write(70f, 0f, w = 50f, h = 30f, pen = Pen.DRAWING))
        group()

        assertEquals(setOf(RegionRole.TEXT to text, RegionRole.FIGURE to drawing), editor.page.regions.map { it.role to it.strokeIds }.toSet())
    }

    @Test
    fun overlappingDrawingsJoinAndDistantOnesDoNot() {
        val a = write(0f, 0f, w = 100f, h = 100f, pen = Pen.DRAWING)
        val b = write(90f, 90f, w = 50f, h = 50f, pen = Pen.DRAWING)
        val nearlyTouching = write(145f, 100f, w = 30f, h = 30f, pen = Pen.DRAWING) // 5 apart, under 0.5 h
        val far = write(400f, 0f, w = 50f, h = 50f, pen = Pen.DRAWING)
        group()

        assertEquals(setOf(setOf(a, b, nearlyTouching), setOf(far)), editor.page.regions.map { it.strokeIds }.toSet())
        assertTrue(editor.page.regions.all { it.role == RegionRole.FIGURE })
    }

    @Test
    fun aDotOrAccentJoinsItsWordNotTheLineBelow() {
        val stem = write(0f, 6f, w = 2f, h = 18f)
        val rest = word(16f, 0f, letters = 2)
        val dot = write(0f, 0f, w = 3f, h = 3f) // above the stem, no vertical overlap
        val below = word(0f, 60f)
        // A dot between two lines goes to the nearer one only.
        val stray = write(40f, 34f, w = 3f, h = 3f)
        group()

        assertEquals(setOf(setOf(stem, dot, stray) + rest, below), editor.page.regions.map { it.strokeIds }.toSet())
    }

    @Test
    fun mathStacksVerticallyIntoOneRegion() {
        val numerator = write(10f, 0f, pen = Pen.MATH)
        val bar = write(0f, 30f, w = 32f, h = 1f, pen = Pen.MATH)
        val denominator = write(10f, 38f, pen = Pen.MATH)
        val nextEquation = write(0f, 120f, pen = Pen.MATH)
        group()

        assertEquals(setOf(setOf(numerator, bar, denominator), setOf(nextEquation)), editor.page.regions.map { it.strokeIds }.toSet())
    }

    @Test
    fun strokesInOneBurstJoinAcrossALineBreak() {
        // A stroke just under a word, out of line with it, 0.5 h away.
        val first = write(0f, 0f)
        val quick = write(0f, 36f, at = clock + 300)
        // The same shapes written 5 s apart stay separate.
        val upper = write(200f, 0f)
        val lower = write(200f, 36f, at = clock + 5_000)
        group()

        val groups = editor.page.regions.map { it.strokeIds }.toSet()
        assertEquals(setOf(setOf(first, quick), setOf(upper), setOf(lower)), groups)
    }

    @Test
    fun scratchGroupsByLineOnItsOwnAndCanBeRecognizedForSearch() {
        val scratch = word(0f, 0f, pen = Pen.SCRATCH)
        val below = word(0f, 200f, pen = Pen.SCRATCH)
        val text = word(100f, 0f) // next to the scratch, in another pen
        group()

        val scratchRegions = editor.page.regions.filter { it.role == RegionRole.EXCLUDED }
        assertEquals(setOf(scratch, below), scratchRegions.map { it.strokeIds }.toSet())
        assertTrue(scratchRegions.all { it.auto })
        assertEquals(text, editor.page.regions.single { it.role == RegionRole.TEXT }.strokeIds)
        assertTrue(AutoRegions.plan(editor.page).isEmpty)

        // Recognized as text, for search; still nothing to confirm or export.
        val id = scratchRegions.first().id
        val request = checkNotNull(editor.startRecognition(id))
        assertTrue(editor.applyResult(request, "check the sign"))
        assertEquals("check the sign", region(id).recognizedText)
        assertEquals(null, region(id).recognizedValue)
        assertEquals(0, editor.confirmAll())
        assertTrue(!editor.page.isExportable(region(id)))
    }

    @Test
    fun thresholdsFollowTheHandwritingSize() {
        // Handwriting four times larger, with gaps four times larger: still one line.
        val big = (0 until 3).mapTo(linkedSetOf()) { write(it * 64f, 0f, w = 48f, h = 96f) } + write(232f, 0f, w = 48f, h = 96f)
        assertEquals(96f, AutoRegions.scale(editor.page))
        group()
        assertEquals(listOf(big), editor.page.regions.map { it.strokeIds })
    }

    @Test
    fun thePlanIsStable() {
        word(0f, 0f)
        word(0f, 60f, pen = Pen.MATH)
        write(200f, 0f, w = 60f, h = 60f, pen = Pen.DRAWING)

        assertEquals(AutoRegions.plan(editor.page), AutoRegions.plan(editor.page))
        val other = PageEditor(editor.page)
        assertEquals(group(), other.autoGroup(AutoRegions.plan(other.page)))
        assertEquals(editor.page, other.page)
        // Grouping again finds nothing new.
        assertTrue(AutoRegions.plan(editor.page).isEmpty)
        assertEquals(emptyList<String>(), group())
    }

    // ---- What grouping may touch ----------------------------------------------------------

    @Test
    fun userRegionsAreNeverChangedOrGrown() {
        val mine = word(0f, 0f)
        val id = checkNotNull(editor.createRegion(mine, RegionRole.TEXT))
        val before = region(id)
        val next = word(58f, 0f) // the same line, close by
        group()

        assertEquals(before, region(id))
        assertFalse(before.auto)
        assertEquals(next, editor.page.regions.single { it.id != id }.strokeIds)
    }

    @Test
    fun anUnconfirmedAutoRegionGrowsAndGoesStale() {
        word(0f, 0f)
        val id = group().single()
        editor.startRecognition(id)?.let { editor.applyResult(it, "the") }
        assertFalse(editor.page.isStale(region(id)))

        val more = word(58f, 0f)
        assertEquals(emptyList<String>(), group())

        assertTrue(region(id).strokeIds.containsAll(more))
        assertTrue(editor.page.isStale(region(id)))
        assertEquals(1, editor.page.regions.size)
    }

    @Test
    fun inkNearAConfirmedRegionStartsANewOneAndKeepsTheConfirmation() {
        word(0f, 0f)
        val id = group().single()
        editor.confirm(id, "the")
        val confirmed = region(id)
        assertFalse(confirmed.auto)

        val more = word(58f, 0f)
        val created = group().single()

        assertEquals(confirmed, region(id))
        assertFalse(editor.page.isStale(region(id)))
        assertEquals(more, region(created).strokeIds)
    }

    @Test
    fun changingTheRoleOrCaptionMakesARegionTheUsers() {
        word(0f, 0f)
        write(200f, 0f, w = 60f, h = 60f, pen = Pen.DRAWING)
        group()
        val (text, figure) = ordered().map { it.id }
        editor.changeRole(text, RegionRole.HEADING)
        editor.setCaption(figure, "A sketch")

        assertFalse(region(text).auto)
        assertFalse(region(figure).auto)
        // Neither grows any more.
        word(58f, 0f)
        write(255f, 0f, w = 20f, h = 20f, pen = Pen.DRAWING)
        group()
        assertEquals(4, editor.page.regions.size)
    }

    @Test
    fun anAlreadyClaimedStrokeIsNotTakenByAStalePlan() {
        val ids = word(0f, 0f)
        val plan = AutoRegions.plan(editor.page)
        editor.createRegion(ids, RegionRole.MATH)

        assertEquals(emptyList<String>(), editor.autoGroup(plan))
        assertEquals(1, editor.page.regions.size)
    }

    // ---- Undo -----------------------------------------------------------------------------

    @Test
    fun oneUndoTakesBackTheStrokeAndTheGroupingItCaused() {
        val first = word(0f, 0f, letters = 2)
        val last = write(32f, 0f)
        group()
        assertEquals(1, editor.page.regions.size)

        assertTrue(editor.undo())
        assertTrue(editor.page.regions.isEmpty())
        assertEquals(first, editor.page.ink.strokes.map { it.id }.toSet())

        assertTrue(editor.redo())
        assertEquals(first + last, editor.page.regions.single().strokeIds)
        assertTrue(editor.page.regions.single().auto)
    }

    @Test
    fun groupingAfterAnUndoIsItsOwnStep() {
        word(0f, 0f)
        editor.undo()
        val created = group()
        assertEquals(1, created.size)

        editor.undo()
        assertTrue(editor.page.regions.isEmpty())
        assertEquals(2, editor.page.ink.strokes.size)
    }

    // ---- Headings (Phase 15) ----------------------------------------------------------------

    @Test
    fun largeLinesAreSuggestedAsHeadingsByTheirSize() {
        // Ordinary writing is 24 high: five lines of it set the page's scale.
        (0 until 5).forEach { word(0f, 400f + it * 60f) }
        val big = (0 until 3).mapTo(linkedSetOf()) { write(it * 40f, 0f, w = 30f, h = 60f) } // 2.5 h
        val medium = (0 until 3).mapTo(linkedSetOf()) { write(it * 30f, 150f, w = 24f, h = 42f) } // 1.75 h
        val created = group().map(::region)

        val title = created.single { it.strokeIds == big }
        assertEquals(RegionRole.HEADING, title.role)
        assertEquals(1, title.headingLevel)
        assertTrue(title.auto)
        val section = created.single { it.strokeIds == medium }
        assertEquals(RegionRole.HEADING, section.role)
        assertEquals(2, section.headingLevel)
        assertEquals(5, created.count { it.role == RegionRole.TEXT })
    }

    @Test
    fun aSuggestedHeadingStillGrowsAlongItsLine() {
        (0 until 5).forEach { word(0f, 400f + it * 60f) }
        (0 until 3).forEach { write(it * 40f, 0f, w = 30f, h = 60f) }
        val heading = group().map(::region).single { it.role == RegionRole.HEADING }
        val more = write(130f, 0f, w = 30f, h = 60f)
        assertTrue(group().isEmpty())
        assertTrue(more in region(heading.id).strokeIds)
    }

    @Test
    fun aSingleLargeStrokeIsNotAHeading() {
        (0 until 5).forEach { word(0f, 400f + it * 60f) }
        write(0f, 0f, w = 30f, h = 70f)
        assertTrue(group().map(::region).none { it.role == RegionRole.HEADING })
    }

    // ---- Reading order --------------------------------------------------------------------

    @Test
    fun newRegionsKeepTheUsersOrder() {
        val bottom = checkNotNull(editor.createRegion(word(0f, 120f), RegionRole.TEXT))
        val top = checkNotNull(editor.createRegion(word(0f, 0f), RegionRole.TEXT))
        assertEquals(listOf(top, bottom), ordered().map { it.id }) // by position by default
        editor.moveInReadingOrder(bottom, 0)
        assertEquals(listOf(bottom, top), ordered().map { it.id }) // the user's order, against position

        val middle = word(0f, 60f)
        val created = group().single()

        assertEquals(listOf(bottom, top, created), ordered().map { it.id })
        assertEquals(middle, region(created).strokeIds)
        assertEquals(listOf(1, 2, 3), ordered().map { it.readingOrder })
    }

    @Test
    fun aNewRegionGoesWhereItsPositionPutsIt() {
        val top = checkNotNull(editor.createRegion(word(0f, 0f), RegionRole.TEXT))
        val bottom = checkNotNull(editor.createRegion(word(0f, 120f), RegionRole.TEXT))
        word(0f, 60f)
        val created = group().single()

        assertEquals(listOf(top, created, bottom), ordered().map { it.id })
    }

    // ---- The lasso on auto regions ----------------------------------------------------------

    @Test
    fun movingLassoedStrokesKeepsTheirRegion() {
        val ids = word(0f, 0f)
        val id = group().single()
        editor.moveStrokes(ids, 0f, 300f)

        assertEquals(ids, region(id).strokeIds)
        assertEquals(300f, region(id).bounds.top)
    }

    @Test
    fun deletingSomeStrokesShrinksTheRegionAndAllRemovesIt() {
        val ids = word(0f, 0f).toList()
        val id = group().single()
        editor.startRecognition(id)?.let { editor.applyResult(it, "the") }

        editor.removeStrokes(setOf(ids[0]))
        assertEquals(setOf(ids[1], ids[2]), editor.page.membersOf(region(id)))
        assertTrue(editor.page.isStale(region(id)))

        editor.removeStrokes(setOf(ids[1], ids[2]))
        assertNull(editor.page.region(id))

        editor.undo()
        assertEquals(setOf(ids[1], ids[2]), editor.page.membersOf(region(id)))
    }

    @Test
    fun aLassoRegionOverSeveralAutoRegionsReplacesThem() {
        val a = word(0f, 0f)
        val b = word(0f, 60f)
        val (first, second) = group()
        val mine = checkNotNull(editor.createRegion(a + b, RegionRole.MATH))

        assertNull(editor.page.region(first))
        assertNull(editor.page.region(second))
        assertEquals(listOf(mine), editor.page.regions.map { it.id })
        assertFalse(region(mine).auto)
        // A partial lasso only takes the strokes it covers.
        val c = word(0f, 200f).toList()
        val third = group().single()
        editor.createRegion(setOf(c[0]), RegionRole.TEXT)
        assertEquals(setOf(c[1], c[2]), region(third).strokeIds)
    }

    // ---- Text recognition and Confirm all --------------------------------------------------

    @Test
    fun textRegionsAreRecognizedIntoTheirOwnField() {
        word(0f, 0f)
        val id = group().single()
        val request = checkNotNull(editor.startRecognition(id))
        assertTrue(editor.applyResult(request, "the"))

        assertEquals("the", region(id).recognizedText)
        assertEquals("the", region(id).recognizedValue)
        assertNull(region(id).recognizedLatex)
        assertEquals(RecognitionStatus.RECOGNIZED, region(id).status)
        // Figures and excluded ink are never sent to a recognizer.
        val figure = checkNotNull(editor.createRegion(setOf(write(300f, 0f, pen = Pen.DRAWING)), RegionRole.FIGURE))
        assertNull(editor.startRecognition(figure))
    }

    @Test
    fun confirmAllAcceptsCurrentResultsInOneStep() {
        word(0f, 0f)
        word(0f, 60f, pen = Pen.MATH)
        word(0f, 120f)
        val (text, math, stale) = group()
        editor.startRecognition(text)?.let { editor.applyResult(it, "the") }
        editor.startRecognition(math)?.let { editor.applyResult(it, "x+1") }
        editor.startRecognition(stale)?.let { editor.applyResult(it, "old") }
        editor.removeStrokes(setOf(region(stale).strokeIds.first()))

        assertEquals(2, editor.confirmAll())
        assertEquals("the", region(text).text)
        assertEquals("x+1", region(math).confirmedLatex)
        assertTrue(editor.page.isExportable(region(text)) && editor.page.isExportable(region(math)))
        assertEquals(RecognitionStatus.RECOGNIZED, region(stale).status)

        editor.undo()
        assertEquals(RecognitionStatus.RECOGNIZED, region(text).status)
        assertEquals(RecognitionStatus.RECOGNIZED, region(math).status)
        assertTrue(region(text).auto)
        assertEquals(0, PageEditor(Page("empty")).confirmAll())
    }

    // ---- A formula in a sentence (Phase 16) ----------------------------------------------------

    @Test
    fun aFormulaInASentenceSplitsTheWordsAroundIt() {
        // "the root" x "is", the gap across the formula within the text gap (2 h = 48).
        val before = word(0f, 0f)
        val formula = setOf(write(56f, 0f, w = 10f, pen = Pen.MATH))
        val after = word(74f, 0f)
        group()

        val regions = ordered()
        assertEquals(listOf(RegionRole.TEXT, RegionRole.MATH, RegionRole.TEXT), regions.map { it.role })
        assertEquals(listOf(before, formula, after), regions.map { it.strokeIds })
    }

    @Test
    fun wordsOnOneLineStillJoinWithAFormulaOnAnotherLine() {
        val words = word(0f, 0f) + word(58f, 0f)
        write(40f, 60f, pen = Pen.MATH)
        group()

        assertEquals(words, ordered().first { it.role == RegionRole.TEXT }.strokeIds)
    }
}
