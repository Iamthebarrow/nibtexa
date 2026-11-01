package io.github.iamthebarrow.nibtexa.core.model.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PageColumnsTest {
    private val h = 20f

    private fun box(id: String, left: Float, top: Float, right: Float, bottom: Float) = id to Bounds(left, top, right, bottom)

    /** The page from the user's testing: two columns that touch, with a wide equation between two parts of them. */
    private val userPage = listOf(
        box("heading", 40f, 0f, 460f, 40f),
        box("system", 40f, 50f, 460f, 100f),
        box("pole", 460f, 55f, 880f, 95f),
        box("equation", 250f, 110f, 670f, 170f),
        box("poles", 40f, 180f, 460f, 220f),
        box("expand", 460f, 180f, 880f, 240f),
        box("left", 40f, 230f, 460f, 270f),
        box("imaginary", 40f, 280f, 460f, 320f),
        box("zeta", 460f, 250f, 880f, 330f),
        box("single", 60f, 330f, 460f, 370f),
        box("see", 460f, 340f, 880f, 380f),
        box("pair", 60f, 380f, 460f, 420f),
    )

    @Test
    fun theUsersPageIsTwoColumnsEitherSideOfItsEquation() {
        val sections = PageColumns.sections(userPage, h, ColumnMode.AUTO)
        assertNotNull(sections)
        assertEquals(
            listOf(
                PageSection(listOf(listOf("heading", "system"), listOf("pole"))),
                PageSection(listOf(listOf("equation"))),
                PageSection(listOf(listOf("poles", "left", "imaginary", "single", "pair"), listOf("expand", "zeta", "see"))),
            ),
            sections,
        )
    }

    /** The bug: the columns were read across, which also split a list. */
    @Test
    fun theReadingOrderFollowsTheColumns() {
        val order = AutoRegions.byPosition(userPage, h, ColumnMode.AUTO)
        assertEquals(listOf("heading", "system", "pole", "equation", "poles", "left", "imaginary", "single", "pair", "expand", "zeta", "see"), order)
    }

    /** Every box has one beside it, but a tall paragraph is beside two lines, so these aren't rows of a table. */
    @Test
    fun aParagraphBesideTwoLinesIsColumns() {
        val page = listOf(
            box("heading", 40f, 330f, 640f, 370f), box("one", 40f, 380f, 640f, 430f), box("two", 40f, 440f, 640f, 490f), box("three", 40f, 500f, 640f, 550f),
            box("paragraph", 640f, 330f, 1240f, 430f), box("zeta", 640f, 445f, 1240f, 495f), box("see", 640f, 510f, 1240f, 550f),
        )
        assertEquals(
            listOf(PageSection(listOf(listOf("heading", "one", "two", "three"), listOf("paragraph", "zeta", "see")))),
            PageColumns.sections(page, h, ColumnMode.AUTO),
        )
    }

    @Test
    fun wordsAndAnEquationOnEachLineStayLines() {
        val lines = listOf(
            box("title", 40f, 0f, 300f, 50f),
            box("words1", 40f, 70f, 400f, 110f), box("math1", 500f, 70f, 800f, 110f),
            box("words2", 40f, 130f, 400f, 170f), box("math2", 500f, 130f, 800f, 170f),
            box("words3", 40f, 190f, 400f, 230f), box("math3", 500f, 190f, 800f, 230f),
        )
        assertNull(PageColumns.sections(lines, h, ColumnMode.AUTO))
    }

    @Test
    fun anOrdinaryColumnOfNotesStaysOne() {
        val notes = listOf(box("a", 40f, 0f, 800f, 40f), box("b", 40f, 60f, 300f, 100f), box("c", 40f, 120f, 800f, 160f), box("d", 40f, 180f, 500f, 220f))
        assertNull(PageColumns.sections(notes, h, ColumnMode.AUTO))
    }

    @Test
    fun oneNoteInTheMarginDoesntMakeColumns() {
        val notes = listOf(box("a", 40f, 0f, 300f, 40f), box("b", 40f, 60f, 300f, 100f), box("c", 40f, 120f, 300f, 160f), box("margin", 500f, 60f, 800f, 100f))
        assertNull(PageColumns.sections(notes, h, ColumnMode.AUTO))
    }

    @Test
    fun theUserCanSayOtherwise() {
        assertNull(PageColumns.sections(userPage, h, ColumnMode.ONE))
        // A page that wouldn't be found as columns, but is asked to be.
        val notes = listOf(box("a", 40f, 0f, 300f, 40f), box("b", 40f, 60f, 300f, 100f), box("margin", 500f, 60f, 800f, 100f))
        assertEquals(listOf(PageSection(listOf(listOf("a", "b"), listOf("margin")))), PageColumns.sections(notes, h, ColumnMode.TWO))
    }

    @Test
    fun whatCrossesTheLineGoesAcross() {
        val page = listOf(
            box("title", 40f, 0f, 800f, 40f),
            box("a", 40f, 60f, 400f, 100f), box("b", 40f, 120f, 400f, 160f), box("c", 40f, 180f, 400f, 220f),
            box("d", 440f, 70f, 800f, 110f), box("e", 440f, 200f, 800f, 240f),
            box("end", 40f, 300f, 800f, 340f),
        )
        assertEquals(
            listOf(PageSection(listOf(listOf("title"))), PageSection(listOf(listOf("a", "b", "c"), listOf("d", "e"))), PageSection(listOf(listOf("end")))),
            PageColumns.sections(page, h, ColumnMode.AUTO),
        )
    }

    @Test
    fun aPageWithoutColumnsReadsAsItDid() {
        val page = listOf(box("a", 40f, 0f, 300f, 40f), box("b", 40f, 60f, 300f, 100f))
        assertEquals(AutoRegions.byPosition(page, h), AutoRegions.byPosition(page, h, ColumnMode.AUTO))
        assertEquals(listOf("a", "b"), AutoRegions.byPosition(page, h, ColumnMode.ONE))
    }

    @Test
    fun theEditorKeepsTheChoiceAndOrdersByIt() {
        val editor = PageEditor(Page("p"))
        assertEquals(ColumnMode.AUTO, editor.page.columns)
        editor.setColumns(ColumnMode.ONE)
        assertEquals(ColumnMode.ONE, editor.page.columns)
    }
}
