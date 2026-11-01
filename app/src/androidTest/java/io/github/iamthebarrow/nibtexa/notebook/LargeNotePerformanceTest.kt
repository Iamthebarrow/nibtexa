package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageEditor
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.ink.FinishedStrokesView
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.storage.NotebookDatabase
import io.github.iamthebarrow.nibtexa.storage.NotebookRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Phase 6 large-note baseline: one page with 2,000 strokes of 40 points (a dense page of
 * handwriting) and 100 regions. Records timings to logcat (tag LargeNotePerf); it asserts only
 * correctness. Emulator figures on the laptop's CPU, not tablet results.
 */
@RunWith(AndroidJUnit4::class)
class LargeNotePerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dbName = "perf-${System.nanoTime()}.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
    }

    private fun stroke(i: Int) = CapturedStroke(
        ToolType.STYLUS, i * 1000L,
        List(POINTS) { j ->
            val a = j / POINTS.toFloat() * 6f
            InkPoint(40f + (i % 50) * 24f + 8f * cos(a), 40f + (i / 50) * 28f + 8f * sin(a) + j * 0.3f, j * 8L, 0.5f)
        },
        if (i % 7 == 0) Pen.TEXT else Pen.MATH,
    )

    private inline fun millis(block: () -> Unit): Double {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        return (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    }

    @Test
    fun denseHandwrittenPage() = runBlocking<Unit> {
        val db = NotebookDatabase.open(context, dbName)
        val repository = NotebookRepository(db.dao())
        val booklet = repository.loadOrCreate()
        val editor = PageEditor(booklet.pages.first())

        val buildMs = millis { repeat(STROKES) { editor.addStroke(stroke(it)) } }
        repeat(REGIONS) { r -> editor.createRegion((r * 20L + 1..r * 20L + 20).toSet(), RegionRole.MATH) }
        val firstSaveMs = millis { repository.savePage(booklet.id, 0, editor.page) }
        db.close()

        // Reopen, as after a restart.
        val reopened = NotebookDatabase.open(context, dbName)
        lateinit var loaded: io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
        val loadMs = millis { loaded = NotebookRepository(reopened.dao()).loadBooklet(booklet.id)!! }
        val page = loaded.pages.first()
        assertEquals(STROKES, page.ink.strokes.size)
        assertEquals(REGIONS, page.regions.size)

        // One more stroke: the edit and its incremental save, as autosave does it.
        val repo2 = NotebookRepository(reopened.dao()).also { it.loadBooklet(booklet.id) }
        val live = PageEditor(page)
        val editMs = millis { live.addStroke(stroke(STROKES)) }
        val saveMs = millis { repo2.savePage(booklet.id, 0, live.page) }
        val undoMs = millis { live.undo() }
        reopened.close()

        // Opening the page: the conversion runs in the background; the main thread only starts it.
        lateinit var view: InkSurfaceView
        lateinit var finished: FinishedStrokesView
        val applied = CountDownLatch(1)
        var setDocumentMs = 0.0
        instrumentation.runOnMainSync {
            view = InkSurfaceView(context)
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(2560, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1500, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 2560, 1500)
            finished = (0 until view.childCount).map(view::getChildAt).filterIsInstance<FinishedStrokesView>().single()
            finished.onDocumentApplied = { applied.countDown() }
        }
        val openStart = SystemClock.elapsedRealtimeNanos()
        instrumentation.runOnMainSync { setDocumentMs = millis { view.setDocument(page.ink) } }
        assertTrue("page shown", applied.await(30, TimeUnit.SECONDS))
        val openMs = (SystemClock.elapsedRealtimeNanos() - openStart) / 1e6

        var softwareDrawMs = 0.0
        var recordAllMs = 0.0
        var recordAfterStrokeMs = 0.0
        var chunksForOneStroke = 0
        instrumentation.runOnMainSync {
            // Software canvas (API < 29, or no hardware acceleration): the whole page is drawn.
            val bitmap = Bitmap.createBitmap(2560, 1500, Bitmap.Config.ARGB_8888)
            softwareDrawMs = millis { finished.draw(Canvas(bitmap)) }
            bitmap.recycle()

            // Hardware canvas: the first frame records every chunk, later frames only changed ones.
            val frame = RenderNode("frame").apply { setPosition(0, 0, 2560, 1500) }
            recordAllMs = millis { frame.beginRecording().also { finished.draw(it) }.let { frame.endRecording() } }
            finished.onDocumentApplied = null
            view.setDocument(live.page.ink.withStroke(stroke(STROKES + 1)))
            val before = finished.recordedChunks
            recordAfterStrokeMs = millis { frame.beginRecording().also { finished.draw(it) }.let { frame.endRecording() } }
            chunksForOneStroke = finished.recordedChunks - before
            frame.discardDisplayList()
        }
        assertEquals("one new stroke re-records one chunk", 1, chunksForOneStroke)

        Log.i(
            TAG,
            "RESULT strokes=$STROKES points=${STROKES * POINTS} regions=$REGIONS build=${f(buildMs)} firstSave=${f(firstSaveMs)} " +
                "load=${f(loadMs)} edit=${f(editMs)} incrementalSave=${f(saveMs)} undo=${f(undoMs)} " +
                "setDocumentMain=${f(setDocumentMs)} openUntilShown=${f(openMs)} softwareDrawAll=${f(softwareDrawMs)} " +
                "hwRecordAll=${f(recordAllMs)} hwRecordAfterOneStroke=${f(recordAfterStrokeMs)} (ms)",
        )
    }

    private fun f(ms: Double) = "%.1f".format(java.util.Locale.ROOT, ms)

    private companion object {
        const val TAG = "LargeNotePerf"
        const val STROKES = 2000
        const val POINTS = 40
        const val REGIONS = 100
    }
}
