package io.github.iamthebarrow.nibtexa.storage

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.AutoRegions
import io.github.iamthebarrow.nibtexa.core.model.notebook.BookletInfo
import io.github.iamthebarrow.nibtexa.core.model.notebook.MarkStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageEditor
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageView
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import io.github.iamthebarrow.nibtexa.core.model.notebook.SheetSize
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class NotebookStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dbName = "storage-test-${System.nanoTime()}.db"

    @get:Rule
    val migrations = MigrationTestHelper(instrumentation, NotebookDatabase::class.java)

    @After
    fun deleteDatabase() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migratesVersionOneDataToVersionTwo() {
        migrations.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO booklet (id, title) VALUES ('b-1', 'Old')")
            execSQL("INSERT INTO page (id, bookletId, position, nextStrokeId, nextRegionNumber) VALUES ('p-1', 'b-1', 0, 2, 2)")
            execSQL("INSERT INTO stroke (pageId, id, tool, pen, startTimeMillis, points) VALUES ('p-1', 1, 'STYLUS', 'MATH', 0, '[{\"x\":1.0,\"y\":2.0,\"t\":0}]')")
            execSQL(
                "INSERT INTO region (pageId, id, role, strokeIds, boundsLeft, boundsTop, boundsRight, boundsBottom, readingOrder, status, " +
                    "sourceStrokeIds, recognizedLatex, confirmedLatex, error) VALUES ('p-1', 'r1', 'MATH', '1', 1, 2, 1, 2, 1, 'CONFIRMED', '1', 'x', 'x', NULL)",
            )
            close()
        }

        // Validates the migrated schema against the exported version 2 schema.
        migrations.runMigrationsAndValidate(dbName, 2, true, NotebookDatabase.MIGRATION_1_2).close()

        val db = NotebookDatabase.open(context, dbName)
        val booklet = runBlocking { NotebookRepository(db.dao()).loadOrCreate() }
        db.close()
        val page = booklet.pages.single()
        val region = page.regions.single()
        assertEquals("Old", booklet.title)
        assertEquals(Paper.BLANK, page.paper)
        assertEquals("x", region.confirmedLatex)
        assertNull(region.text)
        assertEquals(1, region.headingLevel)
        assertNull(region.caption)
        assertEquals(1, page.ink.strokes.single().points.size)
    }

    @Test
    fun migratesVersionTwoDataToVersionThree() {
        migrations.createDatabase(dbName, 2).apply {
            execSQL("INSERT INTO booklet (id, title) VALUES ('b-1', 'Old')")
            execSQL("INSERT INTO page (id, bookletId, position, nextStrokeId, nextRegionNumber, paper) VALUES ('p-1', 'b-1', 0, 2, 2, 'RULED')")
            execSQL("INSERT INTO stroke (pageId, id, tool, pen, startTimeMillis, points) VALUES ('p-1', 1, 'STYLUS', 'TEXT', 0, '[{\"x\":1.0,\"y\":2.0,\"t\":0}]')")
            execSQL(
                "INSERT INTO region (pageId, id, role, strokeIds, boundsLeft, boundsTop, boundsRight, boundsBottom, readingOrder, status, " +
                    "sourceStrokeIds, recognizedLatex, confirmedLatex, error, text, headingLevel, caption) " +
                    "VALUES ('p-1', 'r1', 'TEXT', '1', 1, 2, 1, 2, 1, 'CONFIRMED', '1', NULL, NULL, NULL, 'typed', 1, NULL)",
            )
            close()
        }

        // Validates the migrated schema against the exported version 3 schema.
        migrations.runMigrationsAndValidate(dbName, 3, true, NotebookDatabase.MIGRATION_2_3).close()

        val db = NotebookDatabase.open(context, dbName)
        val region = runBlocking { NotebookRepository(db.dao()).loadOrCreate() }.pages.single().regions.single()
        db.close()
        assertEquals("typed", region.text)
        assertNull(region.recognizedText)
        assertEquals(false, region.auto)
    }

    @Test
    fun autoRegionsAndRecognizedTextRoundTrip() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val booklet = repository.loadOrCreate()
        val editor = PageEditor(booklet.pages.single())
        editor.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0), InkPoint(12f, 24f, 8)), Pen.TEXT))
        val id = editor.autoGroup(AutoRegions.plan(editor.page)).single()
        editor.startRecognition(id)?.let { editor.applyResult(it, "the") }
        repository.savePage(booklet.id, 0, editor.page)

        val region = NotebookRepository(db.dao()).loadOrCreate().pages.single().regions.single()
        db.close()
        assertTrue(region.auto)
        assertEquals("the", region.recognizedText)
    }

    /**
     * A save that fails part-way (here: the last statement, after strokes were deleted and
     * inserted) leaves the previous page exactly as it was, and the next save still writes
     * the right difference.
     */
    @Test
    fun anInterruptedSaveLeavesThePreviousPageIntact() = runBlocking<Unit> {
        fun stroke(x: Float) = CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(x, 0f, 0), InkPoint(x + 10f, 10f, 8)), Pen.MATH)
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val booklet = repository.loadOrCreate()
        val editor = PageEditor(booklet.pages.first())
        editor.addStroke(stroke(0f))
        editor.addStroke(stroke(100f))
        editor.createRegion(setOf(1), RegionRole.MATH)
        repository.savePage(booklet.id, 0, editor.page)
        val saved = editor.page

        editor.removeStrokes(setOf(2))
        editor.addStroke(stroke(200f))
        val broken = editor.page.copy(regions = editor.page.regions + editor.page.regions.first()) // duplicate key
        val failure = runCatching { repository.savePage(booklet.id, 0, broken) }.exceptionOrNull()
        assertTrue("the save must fail, got $failure", failure is SQLiteConstraintException)

        val afterFailure = NotebookRepository(db.dao()).loadBooklet(booklet.id)!!.pages.first()
        assertEquals("rolled back", saved.ink, afterFailure.ink)
        assertEquals(saved.regions, afterFailure.regions)

        // The same repository saves the next good page correctly.
        repository.savePage(booklet.id, 0, editor.page)
        val afterRetry = NotebookRepository(db.dao()).loadBooklet(booklet.id)!!.pages.first()
        db.close()
        assertEquals(editor.page.ink, afterRetry.ink)
        assertEquals(editor.page.regions, afterRetry.regions)
    }

    @Test
    fun bookletsPagesMovesAndNewFieldsRoundTrip() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        var clock = 1_000L
        val repository = NotebookRepository(db.dao(), now = { clock++ })
        val first = repository.loadOrCreate()
        val second = repository.createBooklet("Signals")
        val added = repository.addPage(second.id, 1)

        val editor = PageEditor(second.pages.first())
        editor.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0), InkPoint(10f, 10f, 8)), Pen.TEXT))
        val heading = editor.createRegion(setOf(1), RegionRole.HEADING)!!
        editor.confirm(heading, "Week 1")
        editor.setHeadingLevel(heading, 2)
        editor.setPaper(Paper.GRID)
        repository.savePage(second.id, 0, editor.page)
        editor.moveStrokes(setOf(1), 50f, 0f)
        repository.savePage(second.id, 0, editor.page)
        repository.renameBooklet(second.id, "Signals & Systems")
        db.close()

        // A new repository over the same file, as after a restart.
        val reopened = NotebookDatabase.open(context, dbName)
        val fresh = NotebookRepository(reopened.dao())
        val library = fresh.library()
        val loaded = fresh.loadBooklet(second.id)!!
        reopened.close()

        // Most recently changed first (Phase 12).
        assertEquals(listOf(second.id to 2, first.id to 1), library.map { it.id to it.pageCount })
        assertEquals("Signals & Systems", loaded.title)
        assertEquals(listOf(second.pages.first().id, added.id), loaded.pages.map { it.id })
        val page = loaded.pages.first()
        assertEquals(Paper.GRID, page.paper)
        assertEquals(50f, page.ink.strokes.single().points.first().x) // the move was saved
        val region = page.region(heading)!!
        assertEquals("Week 1", region.text)
        assertEquals(2, region.headingLevel)
        assertEquals(editor.page.regions, page.regions)
    }

    @Test
    fun migratesVersionThreeDataToVersionFour() {
        migrations.createDatabase(dbName, 3).apply {
            execSQL("INSERT INTO booklet (id, title) VALUES ('b-1', 'Old')")
            execSQL("INSERT INTO booklet (id, title) VALUES ('b-2', 'Older')")
            close()
        }
        // Validates the migrated schema against the exported version 4 schema.
        migrations.runMigrationsAndValidate(dbName, 4, true, NotebookDatabase.MIGRATION_3_4).close()

        val db = NotebookDatabase.open(context, dbName)
        val library = runBlocking { NotebookRepository(db.dao()).library() }
        db.close()
        // Never edited since the migration: 0, in creation order.
        assertEquals(listOf("b-1" to 0L, "b-2" to 0L), library.map { it.id to it.updatedAt })
    }

    @Test
    fun migratesVersionFourDataToVersionFiveWithCoversInTurn() {
        migrations.createDatabase(dbName, 4).apply {
            for (i in 1..8) execSQL("INSERT INTO booklet (id, title, updatedAt) VALUES ('b-$i', 'B$i', 0)")
            close()
        }
        // Validates the migrated schema against the exported version 5 schema.
        migrations.runMigrationsAndValidate(dbName, 5, true, NotebookDatabase.MIGRATION_4_5).close()

        val db = NotebookDatabase.open(context, dbName)
        val library = runBlocking { NotebookRepository(db.dao()).library() }
        db.close()
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 0, 1), library.map { it.cover })
    }

    @Test
    fun migratesVersionFiveDataToVersionSixWithCreationDatesAndPositionOrder() {
        val updated = LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        migrations.createDatabase(dbName, 5).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover) VALUES ('b-1', 'Signals', $updated, 2)")
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover) VALUES ('b-2', 'Old', 0, 0)")
            execSQL("INSERT INTO page (id, bookletId, position, nextStrokeId, nextRegionNumber, paper) VALUES ('p-1', 'b-1', 0, 1, 1, 'RULED')")
            close()
        }
        // Validates the migrated schema against the exported version 6 schema.
        migrations.runMigrationsAndValidate(dbName, 6, true, NotebookDatabase.MIGRATION_5_6).close()

        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao(), defaultAuthor = { "Ada" }, zone = { ZoneOffset.UTC })
        val signals = runBlocking { repository.loadBooklet("b-1") }!!
        val old = runBlocking { repository.loadBooklet("b-2") }!!
        db.close()
        // The creation date was never stored: the last change is the closest known.
        assertEquals(BookletInfo("Ada", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), MathAlign.CENTER), signals.info)
        assertEquals(BookletInfo("Ada", null, null, MathAlign.CENTER), old.info)
        assertEquals(false, signals.pages.single().manualOrder)
    }

    @Test
    fun migratesVersionSixDataToVersionSevenWithDefaultPlacement() {
        migrations.createDatabase(dbName, 6).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign) VALUES ('b-1', 'Signals', 0, 0, 0, 'CENTER')")
            execSQL("INSERT INTO page (id, bookletId, position, nextStrokeId, nextRegionNumber, paper, manualOrder) VALUES ('p-1', 'b-1', 0, 2, 2, 'BLANK', 0)")
            execSQL("INSERT INTO region (pageId, id, role, strokeIds, boundsLeft, boundsTop, boundsRight, boundsBottom, readingOrder, status, sourceStrokeIds, headingLevel, auto) VALUES ('p-1', 'r1', 'TEXT', '1', 0, 0, 10, 10, 1, 'NOT_REQUESTED', '', 1, 0)")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 7, true, NotebookDatabase.MIGRATION_6_7).close()

        val db = NotebookDatabase.open(context, dbName)
        val region = runBlocking { NotebookRepository(db.dao()).loadBooklet("b-1") }!!.pages.single().regions.single()
        assertNull(region.align)
        val e = PageEditor(runBlocking { NotebookRepository(db.dao()).loadBooklet("b-1") }!!.pages.single())
        e.setAlign("r1", MathAlign.RIGHT)
        runBlocking { NotebookRepository(db.dao()).savePage("b-1", 0, e.page) }
        assertEquals(MathAlign.RIGHT, runBlocking { NotebookRepository(db.dao()).loadBooklet("b-1") }!!.pages.single().regions.single().align)
        db.close()
    }

    @Test
    fun migratesVersionSevenDataToVersionEightUnformatted() {
        migrations.createDatabase(dbName, 7).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign) VALUES ('b-1', 'Signals', 0, 0, 0, 'CENTER')")
            execSQL("INSERT INTO page (id, bookletId, position, nextStrokeId, nextRegionNumber, paper, manualOrder) VALUES ('p-1', 'b-1', 0, 2, 2, 'BLANK', 0)")
            execSQL("INSERT INTO region (pageId, id, role, strokeIds, boundsLeft, boundsTop, boundsRight, boundsBottom, readingOrder, status, sourceStrokeIds, headingLevel, auto, align) VALUES ('p-1', 'r1', 'TEXT', '1', 0, 0, 10, 10, 1, 'NOT_REQUESTED', '', 1, 0, 'RIGHT')")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 8, true, NotebookDatabase.MIGRATION_7_8).close()

        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val region = runBlocking { repository.loadBooklet("b-1") }!!.pages.single().regions.single()
        assertEquals(MathAlign.RIGHT, region.align)
        assertEquals(emptyList<TextMark>(), region.marks)
        assertEquals(null, region.theorem)
        assertEquals(false, region.pageBreak)
        db.close()
    }

    @Test
    fun migratesVersionEightDataToVersionNineWithDefaultViewsAndWrittenRegions() {
        migrations.createDatabase(dbName, 8).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign) VALUES ('b-1', 'Signals', 0, 0, 0, 'CENTER')")
            execSQL("INSERT INTO page (id, bookletId, position, nextStrokeId, nextRegionNumber, paper, manualOrder) VALUES ('p-1', 'b-1', 0, 2, 2, 'BLANK', 0)")
            execSQL("INSERT INTO region (pageId, id, role, strokeIds, boundsLeft, boundsTop, boundsRight, boundsBottom, readingOrder, status, sourceStrokeIds, headingLevel, auto, numbered, pageBreak) VALUES ('p-1', 'r1', 'TEXT', '1', 0, 0, 10, 10, 1, 'NOT_REQUESTED', '', 1, 0, 0, 0)")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 9, true, NotebookDatabase.MIGRATION_8_9).close()

        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val page = runBlocking { repository.loadBooklet("b-1") }!!.pages.single()
        assertNull(page.view)
        assertEquals(false, page.regions.single().typed)
        runBlocking { repository.setPageView("p-1", PageView(2f, 10f, 20f)) }
        assertEquals(PageView(2f, 10f, 20f), runBlocking { NotebookRepository(db.dao()).loadBooklet("b-1") }!!.pages.single().view)
        db.close()
    }

    @Test
    fun migratesVersionNineDataToVersionTenOnA4Portrait() {
        migrations.createDatabase(dbName, 9).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign) VALUES ('b-1', 'Signals', 0, 0, 0, 'CENTER')")
            execSQL("INSERT INTO page (id, bookletId, position, nextStrokeId, nextRegionNumber, paper, manualOrder) VALUES ('p-1', 'b-1', 0, 1, 1, 'BLANK', 0)")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 10, true, NotebookDatabase.MIGRATION_9_10).close()

        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        assertEquals(Sheet(), runBlocking { repository.info("b-1") }!!.sheet)
        runBlocking { repository.setBookletInfo("b-1", null, MathAlign.CENTER, Sheet(SheetSize.LETTER, landscape = true)) }
        assertEquals(Sheet(SheetSize.LETTER, landscape = true), runBlocking { NotebookRepository(db.dao()).loadBooklet("b-1") }!!.info.sheet)
        db.close()
    }

    @Test
    fun migratesVersionTenDataToVersionElevenWithoutPictures() {
        migrations.createDatabase(dbName, 10).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign, sheet, landscape) VALUES ('b-1', 'Signals', 0, 0, 0, 'CENTER', 'A5', 1)")
            execSQL("INSERT INTO page (id, bookletId, position, nextStrokeId, nextRegionNumber, paper, manualOrder) VALUES ('p-1', 'b-1', 0, 2, 2, 'BLANK', 0)")
            execSQL("INSERT INTO region (pageId, id, role, strokeIds, boundsLeft, boundsTop, boundsRight, boundsBottom, readingOrder, status, sourceStrokeIds, headingLevel, auto, numbered, pageBreak, typed) VALUES ('p-1', 'r1', 'FIGURE', '1', 0, 0, 10, 10, 1, 'NOT_REQUESTED', '', 1, 0, 0, 0, 0)")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 11, true, NotebookDatabase.MIGRATION_10_11).close()

        val db = NotebookDatabase.open(context, dbName)
        val booklet = runBlocking { NotebookRepository(db.dao()).loadBooklet("b-1") }!!
        assertEquals(Sheet(SheetSize.A5, landscape = true), booklet.info.sheet)
        assertNull(booklet.pages.single().background)
        assertNull(booklet.pages.single().regions.single().picture)
        db.close()
    }

    @Test
    fun migratesVersionThirteenRegionsWithNoGraphAndKeepsAGraphOnSaving() {
        migrations.createDatabase(dbName, 13).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign, sheet, landscape, customWidth, customHeight) VALUES ('b-1', 'B', 0, 0, 0, 'CENTER', 'A4', 0, 0, 0)")
            execSQL("INSERT INTO page (id, bookletId, position, nextStrokeId, nextRegionNumber, paper, manualOrder) VALUES ('p-1', 'b-1', 0, 1, 2, 'BLANK', 0)")
            execSQL("INSERT INTO region (pageId, id, role, strokeIds, boundsLeft, boundsTop, boundsRight, boundsBottom, readingOrder, status, sourceStrokeIds, typed) VALUES ('p-1', 'r1', 'TEXT', '', 0, 0, 10, 10, 1, 'CONFIRMED', '', 1)")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 14, true, NotebookDatabase.MIGRATION_13_14).close()
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val page = runBlocking { repository.loadBooklet("b-1") }!!.pages.single()
        assertEquals(null, page.region("r1")!!.graph)
        val graph = io.github.iamthebarrow.nibtexa.core.model.notebook.Graph(
            listOf(io.github.iamthebarrow.nibtexa.core.model.notebook.GraphCurve("y=kx", "r1", 2)),
            constants = mapOf("k" to 2.5),
        )
        val withGraph = page.copy(regions = page.regions.map { it.copy(graph = graph) })
        runBlocking { repository.savePage("b-1", 0, withGraph) }
        assertEquals(graph, runBlocking { repository.loadBooklet("b-1") }!!.pages.single().region("r1")!!.graph)
        db.close()
    }

    @Test
    fun migratesVersionFifteenWithEveryBookletAtTheTopLevel() {
        migrations.createDatabase(dbName, 15).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign, sheet, landscape, customWidth, customHeight) VALUES ('b-1', 'One', 5, 0, 0, 'CENTER', 'A4', 0, 0, 0)")
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign, sheet, landscape, customWidth, customHeight, deletedAt) VALUES ('b-2', 'Binned', 5, 0, 0, 'CENTER', 'A4', 0, 0, 0, 9)")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 16, true, NotebookDatabase.MIGRATION_15_16).close()
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        runBlocking {
            assertEquals(listOf("b-1" to null), repository.library().map { it.id to it.folderId })
            assertEquals(listOf("b-2" to null), repository.trash().map { it.id to it.folderId })
            assertEquals(emptyList<FolderSummary>(), repository.folders())
        }
        db.close()
    }

    @Test
    fun migratesVersionSixteenWithBookletsSetAsTheyWere() {
        migrations.createDatabase(dbName, 16).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign, sheet, landscape, customWidth, customHeight) VALUES ('b-1', 'One', 5, 0, 0, 'CENTER', 'A4', 0, 0, 0)")
            execSQL("INSERT INTO page (id, bookletId, position, nextStrokeId, nextRegionNumber, paper, manualOrder) VALUES ('p-1', 'b-1', 0, 1, 1, 'BLANK', 0)")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 17, true, NotebookDatabase.MIGRATION_16_17).close()
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        runBlocking {
            val booklet = repository.loadBooklet("b-1")!!
            assertEquals(io.github.iamthebarrow.nibtexa.core.model.notebook.ColumnMode.AUTO, booklet.pages.single().columns)
            assertEquals(io.github.iamthebarrow.nibtexa.core.model.notebook.DocumentStyle(), booklet.info.style)
            // And they are kept when set.
            val style = io.github.iamthebarrow.nibtexa.core.model.notebook.DocumentStyle(titleOnFirstPage = true, numberedSections = true, runningHeader = true)
            repository.setDocumentStyle("b-1", style)
            repository.savePage("b-1", 0, booklet.pages.single().copy(columns = io.github.iamthebarrow.nibtexa.core.model.notebook.ColumnMode.TWO))
            val again = repository.loadBooklet("b-1")!!
            assertEquals(style, again.info.style)
            assertEquals(io.github.iamthebarrow.nibtexa.core.model.notebook.ColumnMode.TWO, again.pages.single().columns)
        }
        db.close()
    }

    @Test
    fun migratesVersionSeventeenWithTheMarginThePdfAlwaysHad() {
        migrations.createDatabase(dbName, 17).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign, sheet, landscape, customWidth, customHeight, titleOnFirstPage, numberedSections, runningHeader) VALUES ('b-1', 'One', 5, 0, 0, 'CENTER', 'A4', 0, 0, 0, 1, 0, 0)")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 18, true, NotebookDatabase.MIGRATION_17_18).close()
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        runBlocking {
            val style = repository.loadBooklet("b-1")!!.info.style
            assertEquals(io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin.NORMAL, style.margin)
            assertEquals(true, style.titleOnFirstPage)
            repository.setDocumentStyle("b-1", style.copy(margin = io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin.CHEAT_SHEET))
            assertEquals(io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin.CHEAT_SHEET, repository.loadBooklet("b-1")!!.info.style.margin)
        }
        db.close()
    }

    @Test
    fun aDamagedDatabaseIsKeptNotLost() {
        val folder = io.github.iamthebarrow.nibtexa.storage.KeepDamagedDatabase.folder(context)
        val before = folder.listFiles().orEmpty().toSet()
        try {
            val first = NotebookDatabase.open(context, dbName)
            runBlocking { NotebookRepository(first.dao()).library() }
            first.close()
            val file = context.getDatabasePath(dbName)
            val garbage = ByteArray(file.length().toInt().coerceAtLeast(8192)) { 7 }
            file.writeBytes(garbage)
            java.io.File(file.path + "-wal").delete()
            java.io.File(file.path + "-shm").delete()

            val second = NotebookDatabase.open(context, dbName)
            // It starts again, as Android does; what matters is the copy.
            runBlocking { assertEquals(0, NotebookRepository(second.dao()).library().size) }
            second.close()
            val kept = folder.listFiles().orEmpty().toSet() - before
            assertEquals(1, kept.size)
            assertEquals(garbage.toList(), java.io.File(kept.single(), file.name).readBytes().toList())
            assertEquals(kept.single().name.toLong(), io.github.iamthebarrow.nibtexa.storage.KeepDamagedDatabase.latest(context).coerceAtLeast(kept.single().name.toLong()))
        } finally {
            // The user's own app reads this folder.
            (folder.listFiles().orEmpty().toSet() - before).forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun migratesVersionEighteenWithNoDiagrams() {
        migrations.createDatabase(dbName, 18).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign, sheet, landscape, customWidth, customHeight, titleOnFirstPage, numberedSections, runningHeader, pageMargin) VALUES ('b-1', 'One', 5, 0, 0, 'CENTER', 'A4', 0, 0, 0, 0, 0, 0, 'NORMAL')")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 19, true, NotebookDatabase.MIGRATION_18_19).close()
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        runBlocking { assertEquals("One", repository.loadBooklet("b-1")!!.title) }
        db.close()
    }

    @Test
    fun foldersKeepOrTrashTheirBookletsAsChosen() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val keep = repository.createFolder("  Zeta ", 1)
        val bin = repository.createFolder("alpha", 2)
        assertEquals(listOf("alpha", "Zeta"), repository.folders().map { it.name })
        val a = repository.createBooklet("A", folderId = keep).id
        val b = repository.createBooklet("B").id
        repository.moveToFolder(b, bin)
        val binned = repository.createBooklet("Already binned", folderId = bin).id
        repository.moveToTrash(binned)
        val stamp = repository.library().first { it.id == b }.updatedAt
        assertEquals(mapOf(keep to 1, bin to 1), repository.folders().associate { it.id to it.bookletCount })
        assertEquals("alpha", repository.folderNameOf(b))
        // Moving isn't an edit.
        assertEquals(stamp, repository.library().first { it.id == b }.updatedAt)

        repository.deleteFolder(keep, trash = false)
        assertEquals(null, repository.library().first { it.id == a }.folderId)

        val trashTime = repository.trash().single().deletedAt
        repository.deleteFolder(bin, trash = true)
        assertEquals(listOf(a), repository.library().map { it.id })
        assertEquals(setOf(b, binned), repository.trash().map { it.id }.toSet())
        assertTrue(repository.trash().all { it.folderId == null })
        // One already in the Trash keeps its own date there.
        assertEquals(trashTime, repository.trash().first { it.id == binned }.deletedAt)
        assertEquals(emptyList<FolderSummary>(), repository.folders())
        repository.restoreFromTrash(b)
        assertEquals(null, repository.library().first { it.id == b }.folderId)
        db.close()
    }

    @Test
    fun aDuplicateStaysInItsFolderAndAFolderComesBackFromARestore() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val folder = repository.createFolder("Term", 3)
        val source = repository.createBooklet("Source", folderId = folder).id
        val copy = repository.duplicateBooklet(source, "Copy")!!.id
        assertEquals(folder, repository.library().first { it.id == copy }.folderId)

        val booklets = repository.backupBooklets(null)
        val folders = repository.backupFolders(booklets, all = true)
        assertEquals(listOf("Term"), folders.map { it.name })
        repository.deleteBooklet(source)
        repository.deleteBooklet(copy)
        repository.deleteFolder(folder, trash = false)
        repository.restore(booklets, replace = false, folders = folders)
        assertEquals(listOf(folder), repository.folders().map { it.id })
        assertTrue(repository.library().all { it.folderId == folder })
        // A folder that is already here keeps its own name.
        repository.updateFolder(folder, "Renamed", 1)
        repository.restore(emptyList(), replace = false, folders = folders)
        assertEquals("Renamed", repository.folders().single().name)
        db.close()
    }

    @Test
    fun migratesVersionFourteenToAnEmptyClipboardThatKeepsEntries() {
        migrations.createDatabase(dbName, 14).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign, sheet, landscape, customWidth, customHeight) VALUES ('b-1', 'B', 0, 0, 0, 'CENTER', 'A4', 0, 0, 0)")
            execSQL("INSERT INTO page (id, bookletId, position, nextStrokeId, nextRegionNumber, paper, manualOrder) VALUES ('p-1', 'b-1', 0, 1, 1, 'BLANK', 0)")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 15, true, NotebookDatabase.MIGRATION_14_15).close()
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        runBlocking {
            assertEquals("B", repository.loadBooklet("b-1")!!.title)
            assertEquals(emptyList<ClipSummary>(), repository.clips())
            val clip = io.github.iamthebarrow.nibtexa.core.model.notebook.Clip(
                regions = listOf(
                    io.github.iamthebarrow.nibtexa.core.model.notebook.Region(
                        "r1", io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole.MATH, emptySet(),
                        io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds(0f, 0f, 9f, 9f), 1,
                        io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus.CONFIRMED, confirmedLatex = "x", typed = true,
                    ),
                ),
            )
            repository.addClip(ClipSummary.of("c-1", 1, false, clip, "p-1"), clip)
            assertEquals("x", repository.clips().single().equations)
            assertEquals(clip, repository.clip("c-1"))
        }
        db.close()
    }

    @Test
    fun theStoredClipboardFollowsTheLimitAndPins() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val clip = io.github.iamthebarrow.nibtexa.core.model.notebook.Clip()
        repository.addClip(ClipSummary.of("kept", 1, true, clip, null), clip)
        for (i in 2..60) repository.addClip(ClipSummary.of("c-$i", i.toLong(), false, clip, null), clip)
        val ids = repository.clips().map { it.id }
        assertEquals(51, ids.size)
        assertEquals("kept", ids.first())
        assertEquals("c-60", ids[1])
        assertEquals("c-11", ids.last())
        // Unpinned, the oldest is over the limit.
        repository.setClipPinned("kept", false)
        assertEquals(50, repository.clips().size)
        assertEquals(null, repository.clips().firstOrNull { it.id == "kept" })
        repository.clearClips()
        assertEquals(emptyList<ClipSummary>(), repository.clips())
        db.close()
    }

    @Test
    fun migratesVersionTwelveStrokesToGel() {
        migrations.createDatabase(dbName, 12).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign, sheet, landscape, customWidth, customHeight) VALUES ('b-1', 'Signals', 0, 0, 0, 'CENTER', 'A4', 0, 0, 0)")
            execSQL("INSERT INTO page (id, bookletId, position, nextStrokeId, nextRegionNumber, paper, manualOrder) VALUES ('p-1', 'b-1', 0, 2, 1, 'BLANK', 0)")
            execSQL("INSERT INTO stroke (pageId, id, tool, pen, startTimeMillis, points) VALUES ('p-1', 1, 'STYLUS', 'MATH', 0, '${io.github.iamthebarrow.nibtexa.core.model.serialization.InkJson.encodePoints(listOf(InkPoint(1f, 2f, 0)))}')")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 13, true, NotebookDatabase.MIGRATION_12_13).close()
        val db = NotebookDatabase.open(context, dbName)
        val stroke = runBlocking { NotebookRepository(db.dao()).loadBooklet("b-1") }!!.pages.single().ink.strokes.single()
        assertEquals(io.github.iamthebarrow.nibtexa.core.model.PenKind.GEL, stroke.kind)
        assertEquals(1f, stroke.points.single().x)
        db.close()
    }

    @Test
    fun migratesVersionElevenDataToVersionTwelveOutOfTheTrash() {
        migrations.createDatabase(dbName, 11).apply {
            execSQL("INSERT INTO booklet (id, title, updatedAt, cover, createdAt, mathAlign, sheet, landscape, customWidth, customHeight) VALUES ('b-1', 'Signals', 0, 0, 0, 'CENTER', 'A4', 0, 0, 0)")
            close()
        }
        migrations.runMigrationsAndValidate(dbName, 12, true, NotebookDatabase.MIGRATION_11_12).close()
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        assertEquals(listOf("Signals"), runBlocking { repository.library() }.map { it.title })
        assertEquals(0, runBlocking { repository.trash() }.size)
        db.close()
    }

    @Test
    fun theTrashHidesBookletsUntilRestoredOrPurgedAndKeepsTheirPictures() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        var clock = 1_000L
        val repository = NotebookRepository(db.dao(), now = { clock })
        val editor = PageEditor(io.github.iamthebarrow.nibtexa.core.model.notebook.Page("import", background = "bg.jpg"))
        editor.addPicture("photo.png", io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds(0f, 0f, 10f, 10f))
        val old = repository.importBooklet("Old", null, listOf(editor.page.copy()))
        val recent = repository.createBooklet("Recent")
        val kept = repository.createBooklet("Kept")
        // Findable by search until it is in the Trash.
        val firstPageId = old.pages.single().id
        repository.savePage(old.id, 0, old.pages.single().let { it.copy(regions = it.regions.map { r -> r.copy(caption = "whiteboard") }) })
        assertEquals(listOf("Old"), repository.search("whiteboard").map { it.first.title })

        repository.moveToTrash(old.id)
        assertEquals(setOf("Recent", "Kept"), repository.library().map { it.title }.toSet())
        assertEquals(listOf("Old"), repository.trash().map { it.title })
        assertEquals(emptyList<String>(), repository.search("whiteboard").map { it.first.title })
        // Its pictures still count as in use, so the clean-up at app start leaves them.
        assertEquals(setOf("bg.jpg", "photo.png"), repository.usedPictures())
        // And nothing is lost while it's there.
        assertEquals(firstPageId, repository.loadBooklet(old.id)!!.pages.single().id)

        // A booklet's updated time doesn't change when it comes back.
        val updated = db.dao().booklet(old.id)!!.updatedAt
        clock += 500
        repository.restoreFromTrash(old.id)
        assertEquals(setOf("Old", "Recent", "Kept"), repository.library().map { it.title }.toSet())
        assertEquals(updated, db.dao().booklet(old.id)!!.updatedAt)

        // Only what has been there the full time is purged.
        val day = 24L * 60 * 60 * 1000
        repository.moveToTrash(old.id)
        clock += 29 * day
        repository.moveToTrash(recent.id)
        repository.purgeTrash(30 * day)
        // Most recently deleted first.
        assertEquals(listOf("Recent", "Old"), repository.trash().map { it.title })
        clock += day
        repository.purgeTrash(30 * day)
        assertEquals(listOf("Recent"), repository.trash().map { it.title })
        assertNull(repository.loadBooklet(old.id))
        assertEquals(emptySet<String>(), repository.usedPictures())

        repository.emptyTrash()
        assertEquals(0, repository.trash().size)
        assertEquals(listOf("Kept"), repository.library().map { it.title })
        assertEquals(kept.id, repository.loadOrCreate().id)
        db.close()
    }

    @Test
    fun aTrashedBookletIsNotReopenedAsTheFirstBooklet() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val first = repository.createBooklet("First")
        repository.moveToTrash(first.id)
        val opened = repository.loadOrCreate()
        assertTrue(opened.id != first.id)
        assertEquals(NotebookRepository.DEFAULT_TITLE, opened.title)
        db.close()
    }

    @Test
    fun picturesBackgroundsAndCustomSizesAreStoredAndCopied() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val slide = Sheet.ofPoints(960f, 540f)
        val editor = PageEditor(io.github.iamthebarrow.nibtexa.core.model.notebook.Page("import", background = "bg.jpg"))
        editor.addPicture("photo.png", io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds(10f, 10f, 110f, 60f))
        val imported = repository.importBooklet("Slides", null, listOf(editor.page), sheet = slide)
        val stored = NotebookRepository(db.dao()).loadBooklet(imported.id)!!
        assertEquals(slide, stored.info.sheet)
        assertEquals("bg.jpg", stored.pages.single().background)
        assertEquals("photo.png", stored.pages.single().regions.single().picture)
        assertEquals(setOf("bg.jpg", "photo.png"), repository.usedPictures())
        assertEquals(slide, repository.library().single().paper())
        assertEquals("bg.jpg", repository.firstPage(imported.id)!!.background)

        val copy = repository.duplicateBooklet(imported.id, "Copy")!!
        assertEquals(slide, copy.info.sheet)
        assertEquals("bg.jpg", copy.pages.single().background)
        repository.setBookletInfo(copy.id, null, MathAlign.CENTER, Sheet.ofPoints(612f, 792f))
        assertEquals(Sheet.ofPoints(612f, 792f), repository.info(copy.id)!!.sheet)
        db.close()
    }

    @Test
    fun formattingAndStructureAreStored() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val b = repository.createBooklet("Signals")
        val e = PageEditor(b.pages.single())
        listOf(Pen.TEXT, Pen.MATH).forEachIndexed { i, pen -> e.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(0f, i * 100f, 0), InkPoint(10f, i * 100f + 10f, 8)), pen)) }
        val text = e.createRegion(setOf(1L), RegionRole.TEXT)!!
        val math = e.createRegion(setOf(2L), RegionRole.MATH)!!
        val marks = listOf(TextMark(0, 4, MarkStyle.Bold), TextMark(5, 9, MarkStyle.Link("https://example.org")), TextMark(5, 9, MarkStyle.Footnote("see")))
        e.confirm(text, "Pole zero", marks)
        e.setTheorem(text, TheoremKind.DEFINITION)
        e.setNewParagraph(text, true)
        e.setPageBreak(text, true)
        e.setListLevel(text, 1)
        e.confirm(math, "x^2")
        e.setNumbered(math, true)
        e.setInline(math, false)
        repository.savePage(b.id, 0, e.page)

        val loaded = repository.loadBooklet(b.id)!!.pages.single()
        assertEquals(e.page.region(text), loaded.region(text))
        assertEquals(e.page.region(math), loaded.region(math))
        db.close()
    }

    @Test
    fun coverDetailsAlignmentAndManualOrderAreStored() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        val day = LocalDate.of(2026, 10, 28).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        var clock = day
        val repository = NotebookRepository(db.dao(), now = { clock }, defaultAuthor = { "Ada" }, zone = { ZoneOffset.UTC })
        val b = repository.createBooklet("Signals")
        assertEquals(BookletInfo("Ada", LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 28)), repository.info(b.id))
        assertNull(repository.ownAuthor(b.id))

        clock = day + 3 * 86_400_000L
        repository.setBookletInfo(b.id, "  Grace Hopper ", MathAlign.RIGHT)
        assertEquals("Grace Hopper", repository.ownAuthor(b.id))
        assertEquals(BookletInfo("Grace Hopper", LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 31), MathAlign.RIGHT), repository.info(b.id))
        repository.setBookletInfo(b.id, " ", MathAlign.LEFT) // blank: back to the app's author
        assertEquals("Ada", NotebookRepository(db.dao(), defaultAuthor = { "Ada" }).loadBooklet(b.id)!!.info.author)

        val e = PageEditor(b.pages.single())
        listOf(0f, 100f).forEach { y -> e.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(0f, y, 0), InkPoint(10f, y + 10f, 8)), Pen.TEXT)) }
        e.createRegion(setOf(1), RegionRole.TEXT)
        e.createRegion(setOf(2), RegionRole.TEXT)
        e.moveInReadingOrder("r2", 0)
        repository.savePage(b.id, 0, e.page)
        val page = NotebookRepository(db.dao()).loadBooklet(b.id)!!.pages.single()
        assertTrue(page.manualOrder)
        assertEquals(listOf("r2", "r1"), page.regionsInReadingOrder.map { it.id })
        db.close()
    }

    @Test
    fun deletingAPageRemovesItsInkAndRenumbersTheRest() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val b = repository.createBooklet("Signals")
        val second = repository.addPage(b.id, 1)
        val third = repository.addPage(b.id, 2)
        val e = PageEditor(second)
        e.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0), InkPoint(10f, 10f, 8)), Pen.MATH))
        e.createRegion(setOf(1), RegionRole.MATH)
        repository.savePage(b.id, 1, e.page)

        repository.deletePage(b.id, second.id, listOf(b.pages.single().id, third.id))
        val pages = db.dao().pages(b.id)
        assertEquals(listOf(b.pages.single().id to 0, third.id to 1), pages.map { it.id to it.position })
        assertTrue(db.dao().strokes(second.id).isEmpty())
        assertTrue(db.dao().regions(second.id).isEmpty())
        db.close()
    }

    @Test
    fun coversPendingCountsAndSearchByHandwriting() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        var clock = 1_000L
        val repository = NotebookRepository(db.dao(), now = { clock++ })
        val signals = repository.createBooklet("Signals", cover = 3)
        repository.createBooklet("Control 100%_done")
        assertEquals(3, repository.library().single { it.id == signals.id }.cover)
        repository.setCover(signals.id, 5)
        assertEquals(5, repository.library().single { it.id == signals.id }.cover)

        // Two results waiting, one confirmed: the card's badge counts the two.
        val e = PageEditor(signals.pages.single())
        listOf(0f, 100f, 200f).forEach { y -> e.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(0f, y, 0), InkPoint(10f, y + 10f, 8)), Pen.TEXT)) }
        val ids = (1L..3L).map { e.createRegion(setOf(it), RegionRole.TEXT)!! }
        e.startRecognition(ids[0])?.let { e.applyResult(it, "the transfer function") }
        e.startRecognition(ids[1])?.let { e.applyResult(it, "pole at s = -2") }
        e.confirm(ids[2], "Bode plot")
        repository.savePage(signals.id, 0, e.page)
        assertEquals(2, repository.library().single { it.id == signals.id }.pending)

        // Search finds handwriting (recognized or confirmed, any case) and titles; % and _ are literal.
        assertEquals(listOf(signals.id to "the transfer function"), repository.search("TRANSFER").map { it.first.id to it.second?.snippet })
        assertEquals(listOf(signals.id to "Bode plot"), repository.search("bode").map { it.first.id to it.second?.snippet })
        assertEquals(listOf("Control 100%_done" to null), repository.search("100%_").map { it.first.title to it.second?.snippet })
        assertEquals(emptyList<String>(), repository.search("nothing like this").map { it.first.id })
        assertEquals(2, repository.search(" ").size) // blank: everything
        db.close()
    }

    @Test
    fun theLibraryIsNewestFirstAndDeleteAndDuplicateWork() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        var clock = 1_000L
        val repository = NotebookRepository(db.dao(), now = { clock++ })
        val a = repository.createBooklet("A", Paper.RULED)
        repository.createBooklet("B")
        assertEquals(listOf("B", "A"), repository.library().map { it.title })
        assertEquals(Paper.RULED, a.pages.single().paper)

        // Writing in A moves it to the top.
        val editor = PageEditor(a.pages.single())
        editor.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0), InkPoint(10f, 10f, 8)), Pen.MATH))
        editor.confirm(editor.createRegion(setOf(1), RegionRole.MATH)!!, "x")
        repository.savePage(a.id, 0, editor.page)
        repository.addPage(a.id, 1)
        assertEquals(listOf("A", "B"), repository.library().map { it.title })

        // A copy has new ids and the same content, and is newest.
        val copy = repository.duplicateBooklet(a.id, "A (copy)")!!
        assertEquals(listOf("A (copy)", "A", "B"), repository.library().map { it.title })
        assertTrue(copy.id != a.id && copy.pages.none { p -> p.id in setOf(a.pages.single().id) })
        assertEquals(2, copy.pages.size)
        assertEquals(editor.page.ink.strokes, copy.pages.first().ink.strokes)
        assertEquals(editor.page.regions, copy.pages.first().regions)
        assertEquals(Paper.RULED, copy.pages.first().paper)
        assertEquals(editor.page.ink.strokes, repository.firstPage(copy.id)!!.ink.strokes)

        // Deleting takes the pages, ink and regions with it; the copy is untouched.
        repository.deleteBooklet(a.id)
        assertEquals(listOf("A (copy)", "B"), repository.library().map { it.title })
        assertNull(repository.loadBooklet(a.id))
        assertTrue(db.dao().pages(a.id).isEmpty())
        assertTrue(db.dao().strokes(a.pages.single().id).isEmpty())
        assertTrue(db.dao().regions(a.pages.single().id).isEmpty())
        assertEquals(copy.pages.first().regions, NotebookRepository(db.dao()).loadBooklet(copy.id)!!.pages.first().regions)
        assertNull(repository.duplicateBooklet("b-missing", "x"))
        db.close()
    }

    @Test
    fun unknownBookletLoadsAsNull() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        assertNull(NotebookRepository(db.dao()).loadBooklet("b-missing"))
        db.close()
        assertEquals(true, File(context.getDatabasePath(dbName).path).exists())
    }
}
