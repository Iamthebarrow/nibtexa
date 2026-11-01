package io.github.iamthebarrow.nibtexa

import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.sync.SyncSettings
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import io.github.iamthebarrow.nibtexa.notebook.NotebookUiState
import io.github.iamthebarrow.nibtexa.notebook.NotebookViewModel
import io.github.iamthebarrow.nibtexa.notebook.TestServicesRule
import io.github.iamthebarrow.nibtexa.notebook.Tool
import io.github.iamthebarrow.nibtexa.sync.SyncActivity
import io.github.iamthebarrow.nibtexa.sync.SyncUiState
import io.github.iamthebarrow.nibtexa.sync.SyncViewModel
import io.github.iamthebarrow.nibtexa.sync.TestGitRemote
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The integrated acceptance trial (Phase 8, from the v0.1 proposal) on the real notebook
 * screen, with a scripted recognizer standing in for handwriting the emulator can't produce:
 *
 * 1. a DSP/control page: heading, prose, a DFT sum, a transfer function with a damping ratio,
 *    a difference equation, a small state-space matrix and a hand-drawn block diagram, plus a
 *    math stroke that is never selected;
 * 2. only the selected regions are converted, and the diagram never reaches the recognizer;
 * 3. the diagram is moved and captioned;
 * 4. the app restarts;
 * 5. the booklet is exported (with `source/`) and pushed to the test remote; the export is
 *    copied to the app's external files for compiling on the host (see the Phase 8 outcome);
 * 6. the original strokes are intact and the diagram is in reading order.
 */
@RunWith(AndroidJUnit4::class)
class IntegratedTrialTest {
    @get:Rule
    val services = TestServicesRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as NibTeXaApplication
    private val density = app.resources.displayMetrics.density
    private val branch = "trial-${UUID.randomUUID().toString().take(8)}"
    private var notebook: ActivityScenario<MainActivity>? = null
    private var sync: ActivityScenario<SyncActivity>? = null

    /** Row of the page (in pixels) where each piece is written, and what it "says". */
    private val rows = listOf(
        Piece(150f, Pen.TEXT, RegionRole.HEADING, "Discrete-time control"),
        Piece(330f, Pen.TEXT, RegionRole.TEXT, "We sample at 1 kHz & keep 100% of the loop gain_x."),
        Piece(510f, Pen.MATH, RegionRole.MATH, """X[k]=\sum_{n=0}^{N-1}x[n]e^{-j2\pi kn/N}"""),
        Piece(690f, Pen.MATH, RegionRole.MATH, """H(s)=\frac{\omega_n^2}{s^2+2\zeta\omega_ns+\omega_n^2}"""),
        Piece(870f, Pen.MATH, RegionRole.MATH, """y[n]=a\,y[n-1]+b\,x[n]"""),
        Piece(1050f, Pen.MATH, RegionRole.MATH, """\dot{x}=\begin{bmatrix}0&1\\-\omega_n^2&-2\zeta\omega_n\end{bmatrix}x"""),
    )
    private val unselectedRow = 1230f
    private val diagramRow = 150f
    private val diagramX = 1500f

    private class Piece(val y: Float, val pen: Pen, val role: RegionRole, val content: String)

    @After
    fun tearDown() {
        notebook?.close()
        sync?.close()
        app.sync.settings.clear()
        app.sync.tokens.clear()
        app.sync.workDir.deleteRecursively()
    }

    private fun state(): NotebookUiState {
        lateinit var s: NotebookUiState
        notebook!!.onActivity { s = vm(it).state.value }
        return s
    }

    private fun vm(activity: MainActivity) = ViewModelProvider(activity)[NotebookViewModel::class.java]

    private fun onVm(block: NotebookViewModel.() -> Unit) = notebook!!.onActivity { vm(it).block() }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(50)
        }
    }

    private fun write(pen: Pen, x: Float, y: Float) {
        val tool = Tool.entries.first { it.pen == pen }
        onVm { setTool(tool) }
        instrumentation.waitForIdleSync()
        val before = state().page.ink.strokes.size
        notebook!!.onActivity { activity ->
            val view = activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
            TestMotionEvents().line(TOOL_TYPE_STYLUS, x to y, x + 160f to y + 50f).forEach { view.dispatchTouchEvent(it); it.recycle() }
        }
        waitFor { state().page.ink.strokes.size == before + 1 }
    }

    /** Lasso (in canvas units) around a pixel row band, then make a region of [role]. */
    private fun region(top: Float, bottom: Float, left: Float, right: Float, role: RegionRole): String {
        val (l, t, r, b) = listOf(left, top, right, bottom).map { it / density }
        onVm { onLasso(listOf(l to t, r to t, r to b, l to b, l to t + 1f)) }
        waitFor { state().selection.isNotEmpty() }
        val before = state().page.regions.map { it.id }.toSet()
        onVm { makeRegion(role) }
        waitFor { state().page.regions.any { it.id !in before } }
        return state().page.regions.single { it.id !in before }.id
    }

    @Test
    fun theDspControlTrial() {
        // The recognizer answers by the row the strokes were written in.
        services.script = { strokes, _ ->
            val y = strokes.first().points.first().y * density
            rows.single { kotlin.math.abs(it.y - y) < 40f && it.pen == Pen.MATH }.content
        }
        notebook = ActivityScenario.launch(MainActivity::class.java)
        waitFor { state().loaded }

        // 1. Write the page.
        for (piece in rows) write(piece.pen, 200f, piece.y)
        write(Pen.MATH, 200f, unselectedRow)
        write(Pen.DRAWING, diagramX, diagramRow)
        write(Pen.DRAWING, diagramX, diagramRow + 80f)
        val original: List<Stroke> = state().page.ink.strokes

        // 2. Convert only the selected regions; text and headings are typed and confirmed.
        val ids = rows.map { piece -> piece to region(piece.y - 40f, piece.y + 90f, 100f, 600f, piece.role) }
        for ((piece, id) in ids) {
            if (piece.role == RegionRole.MATH) {
                waitFor { state().page.region(id)?.status == RecognitionStatus.RECOGNIZED }
                onVm { confirm(id, state().page.region(id)!!.recognizedLatex!!) }
            } else {
                onVm { confirm(id, piece.content) }
            }
            waitFor { state().page.region(id)?.status == RecognitionStatus.CONFIRMED }
        }
        val figure = region(diagramRow - 40f, diagramRow + 170f, diagramX - 50f, diagramX + 300f, RegionRole.FIGURE)
        assertEquals("only the four selected math regions were recognized", 4, services.requests.size)
        val recognized = services.requests.flatten().map { it.id }.toSet()
        assertTrue("no drawing ink reached the recognizer", original.filter { it.pen == Pen.DRAWING }.none { it.id in recognized })
        val unselected = original.single { it.pen == Pen.MATH && kotlin.math.abs(it.points.first().y * density - unselectedRow) < 40f }
        assertTrue("the unselected stroke was not recognized", unselected.id !in recognized)

        // 3. Move the diagram between the difference equation and the matrix, and caption it.
        val dyPx = 960f - diagramRow
        val dxPx = 200f - diagramX
        onVm { onLasso(listOf((diagramX - 50f) / density to (diagramRow - 40f) / density, (diagramX + 300f) / density to (diagramRow - 40f) / density, (diagramX + 300f) / density to (diagramRow + 170f) / density, (diagramX - 50f) / density to (diagramRow + 170f) / density)) }
        waitFor { state().selection.size == 2 }
        onVm { moveSelection(dxPx / density, dyPx / density) }
        onVm { setCaption(figure, "Closed loop with plant G(s) and controller C(z)") }
        onVm { orderByPosition() }
        waitFor { state().let { it.savedVersion == it.version } }
        val order = state().page.regionsInReadingOrder.map { it.id }
        val expected = ids.take(5).map { it.second } + figure + ids[5].second
        assertEquals("the diagram is read between the difference equation and the matrix", expected, order)
        val beforeRestart = state().page

        // 4. Restart.
        notebook!!.close()
        instrumentation.waitForIdleSync()
        services.install()
        notebook = ActivityScenario.launch(MainActivity::class.java)
        waitFor { state().loaded }
        val page = state().page
        assertEquals(beforeRestart.ink, page.ink)
        assertEquals(beforeRestart.regions, page.regions)

        // 6. The original strokes are intact: all but the diagram's exactly, the diagram's moved.
        val byId = page.ink.strokes.associateBy { it.id }
        for (stroke in original) {
            val now = byId.getValue(stroke.id)
            if (stroke.pen == Pen.DRAWING) {
                assertEquals(stroke.points.size, now.points.size)
                assertEquals(stroke.points.first().x + dxPx / density, now.points.first().x, 0.5f)
            } else {
                assertEquals(stroke, now)
            }
        }

        // 5. Export (with the editable source) and push.
        app.sync.settings.save(
            SyncSettings(
                remoteUrl = TestGitRemote.URL_BASE, username = TestGitRemote.USERNAME, branch = branch,
                authorName = "Trial", authorEmail = "trial@example.org", includeSource = true,
            ),
        )
        app.sync.tokens.save(TestGitRemote.TOKEN)
        onVm { exportLatex(services.exportZipUri) }
        waitFor { state().message?.startsWith("LaTeX project saved") == true }
        val exported = services.unpackExport()
        val keep = File(app.getExternalFilesDir(null), "trial").apply { deleteRecursively() }
        exported.copyRecursively(keep)
        val pageTex = File(exported, "booklets/${state().bookletId}/pages/${page.id}.tex").readText()
        for (piece in rows) assertTrue(piece.content, pageTex.contains(piece.content.take(12)) || piece.role != RegionRole.MATH)
        assertTrue(pageTex, pageTex.contains("""\section{Discrete-time control}"""))
        assertTrue(pageTex, pageTex.contains("""1 kHz \& keep 100\% of the loop gain\_x."""))
        assertTrue(pageTex, pageTex.indexOf("includegraphics") in pageTex.indexOf("y[n]")..pageTex.indexOf("dot{x}"))

        val bookletId = state().bookletId
        // The push goes to the plain-HTTP test remote, which release builds refuse by design;
        // there the trial ends after the export (Git on release is covered by RealHostSyncTest).
        if (!TestGitRemote.allowsHttp()) {
            android.util.Log.i("IntegratedTrial", "push step skipped: this build refuses plain HTTP remotes")
            return
        }
        notebook!!.close()
        notebook = null
        sync = ActivityScenario.launch(SyncActivity.intent(app, bookletId))
        TestGitRemote(File(app.cacheDir, "trial-remote")).assumeRunning()
        fun syncState(): SyncUiState {
            lateinit var s: SyncUiState
            sync!!.onActivity { s = ViewModelProvider(it)[SyncViewModel::class.java].state.value }
            return s
        }
        fun onSync(block: SyncViewModel.() -> Unit) = sync!!.onActivity { ViewModelProvider(it)[SyncViewModel::class.java].block() }
        waitFor(30_000) { syncState().step == null }
        onSync { prepare() }
        waitFor(30_000) { syncState().step == null && syncState().plan != null }
        onSync { commitAndPush() }
        waitFor(30_000) { syncState().step == null && syncState().pending == null && syncState().status?.startsWith("Pushed") == true }
        val remote = TestGitRemote(File(app.cacheDir, "trial-remote")).files(branch)
        val prefix = "nibtexa/$bookletId/"
        assertTrue(remote.keys.toString(), "${prefix}main.tex" in remote && "${prefix}source/booklet.json" in remote && remote.keys.any { it.startsWith("${prefix}assets/") })
    }
}
