package io.github.iamthebarrow.nibtexa.notebook

import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a restart after process death sees: the activity is finished (its ViewModel cleared),
 * the services are recreated over the same database file, and the activity starts again.
 * A real `am kill` is checked manually on the AVD (see the Phase 5 outcome).
 */
@RunWith(AndroidJUnit4::class)
class NotebookRestartTest {
    @get:Rule
    val services = TestServicesRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun state(scenario: ActivityScenario<MainActivity>): NotebookUiState {
        lateinit var state: NotebookUiState
        scenario.onActivity { state = ViewModelProvider(it)[NotebookViewModel::class.java].state.value }
        return state
    }

    private fun waitFor(scenario: ActivityScenario<MainActivity>, condition: (NotebookUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition(state(scenario))) {
            check(System.currentTimeMillis() < deadline) { "timed out; state ${state(scenario)}" }
            Thread.sleep(50)
        }
    }

    @Test
    fun strokesSurviveARestart() {
        val first = ActivityScenario.launch(MainActivity::class.java)
        waitFor(first) { it.loaded }
        first.onActivity { activity ->
            val view = activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
            TestMotionEvents().line(TOOL_TYPE_STYLUS, 200f to 200f, 300f to 300f).forEach { view.dispatchTouchEvent(it); it.recycle() }
        }
        waitFor(first) { it.page.ink.strokes.size == 1 && it.savedVersion == it.version }
        val before = state(first).page
        first.close()
        instrumentation.waitForIdleSync()

        services.install() // new process: new repository, nothing cached
        val second = ActivityScenario.launch(MainActivity::class.java)
        waitFor(second) { it.loaded }

        val after = state(second).page
        assertEquals(before.id, after.id)
        assertEquals(before.ink, after.ink)
        assertTrue(state(second).canUndo.not()) // history is per session
        second.close()
    }

    @Test
    fun aGraphSurvivesARestart() {
        val first = ActivityScenario.launch(MainActivity::class.java)
        waitFor(first) { it.loaded }
        first.onActivity { activity ->
            val view = activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
            TestMotionEvents().line(TOOL_TYPE_STYLUS, 200f to 200f, 300f to 300f).forEach { view.dispatchTouchEvent(it); it.recycle() }
        }
        waitFor(first) { it.page.ink.strokes.size == 1 }
        first.onActivity { activity ->
            val viewModel = ViewModelProvider(activity)[NotebookViewModel::class.java]
            viewModel.setAutoRegions(false)
            viewModel.onLasso(listOf(0f to 0f, 2000f to 0f, 2000f to 2000f, 0f to 2000f, 0f to 0f))
            viewModel.makeRegion(io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole.MATH)
        }
        waitFor(first) { s -> s.page.regions.singleOrNull()?.let { it.status != io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus.RECOGNIZING } == true }
        first.onActivity { activity ->
            val viewModel = ViewModelProvider(activity)[NotebookViewModel::class.java]
            val id = viewModel.state.value.page.regions.single().id
            viewModel.confirm(id, "y=kx")
            viewModel.graphEquation(id)
            val graph = viewModel.state.value.page.regions.first { it.graph != null }
            viewModel.setGraphConstant(graph.id, "k", 2.5)
            viewModel.updateGraph(graph.id) { it.withRange(-1.0, 1.0, -2.0, 2.0) }
        }
        waitFor(first) { it.page.regions.any { r -> r.graph?.xMax == 1.0 } && it.savedVersion == it.version }
        val before = state(first).page.regions.first { it.graph != null }
        first.close()
        instrumentation.waitForIdleSync()

        services.install()
        val second = ActivityScenario.launch(MainActivity::class.java)
        waitFor(second) { it.loaded }
        assertEquals(before, state(second).page.region(before.id))
        second.close()
    }

    /**
     * Phase 26's aim: three equations copied on one page paste in another booklet, in any order, after restarting the
     * app, without recognition running.
     */
    @Test
    fun threeEquationsPasteInAnotherBookletAfterARestartWithoutRecognition() {
        val first = ActivityScenario.launch(MainActivity::class.java)
        waitFor(first) { it.loaded }
        fun viewModelOf(activity: MainActivity) = ViewModelProvider(activity)[NotebookViewModel::class.java]
        val confirmed = io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus.CONFIRMED
        for ((i, latex) in listOf("a=1", "b=2", "c=3").withIndex()) {
            val y = 200f + 300f * i
            // The surface takes its tool when the screen next updates: the pen must be in hand before it writes.
            first.onActivity { activity -> viewModelOf(activity).setTool(Tool.MATH_PEN) }
            instrumentation.waitForIdleSync()
            Thread.sleep(100)
            first.onActivity { activity ->
                val view = activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
                TestMotionEvents().line(TOOL_TYPE_STYLUS, 200f to y, 500f to y + 20f).forEach { view.dispatchTouchEvent(it); it.recycle() }
            }
            waitFor(first) { it.page.ink.strokes.size == i + 1 }
            val points = state(first).page.ink.strokes.last().points
            val loop = listOf(0f to points.minOf { it.y } - 5f, 3000f to points.minOf { it.y } - 5f, 3000f to points.maxOf { it.y } + 5f, 0f to points.maxOf { it.y } + 5f, 0f to points.minOf { it.y } - 5f)
            first.onActivity { activity ->
                viewModelOf(activity).setTool(Tool.LASSO)
                viewModelOf(activity).onLasso(loop)
                viewModelOf(activity).makeRegion(io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole.MATH)
            }
            waitFor(first) { s -> s.page.regions.size == i + 1 && s.page.regions.all { it.status != io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus.RECOGNIZING } }
            first.onActivity { activity ->
                val vm = viewModelOf(activity)
                vm.confirm(vm.state.value.page.regions.first { it.confirmedLatex == null }.id, latex)
                vm.onLasso(loop)
                vm.copySelection()
            }
            waitFor(first) { it.clips.size == i + 1 && it.savedVersion == it.version }
        }
        assertEquals(listOf("c=3", "b=2", "a=1"), state(first).clips.map { it.equations })
        // Copies reach the disk in order behind the screen; a real restart has them once this is true.
        val deadline = System.currentTimeMillis() + 10_000
        while (kotlinx.coroutines.runBlocking { services.freshRepository().clips() }.size < 3) {
            check(System.currentTimeMillis() < deadline) { "the clipboard never reached the disk" }
            Thread.sleep(50)
        }
        first.close()
        instrumentation.waitForIdleSync()

        services.install() // new process: new repository, nothing cached
        val second = ActivityScenario.launch(MainActivity::class.java)
        waitFor(second) { it.loaded && it.clips.size == 3 }
        assertEquals(listOf("c=3", "b=2", "a=1"), state(second).clips.map { it.equations })
        second.onActivity { activity ->
            viewModelOf(activity).setAutoRegions(true)
            viewModelOf(activity).createBooklet("Elsewhere")
        }
        waitFor(second) { it.bookletTitle == "Elsewhere" }
        val elsewhere = state(second).bookletId
        val asked = synchronized(services.requests) { services.requests.size }
        // In another order than they were copied.
        val ids = state(second).clips.associate { it.equations to it.id }
        for ((i, latex) in listOf("b=2", "c=3", "a=1").withIndex()) {
            second.onActivity { activity -> viewModelOf(activity).paste(ids.getValue(latex)) }
            waitFor(second) { it.page.regions.size == i + 1 }
        }
        assertEquals(setOf("a=1", "b=2", "c=3"), state(second).page.regions.map { it.confirmedLatex }.toSet())
        assertTrue(state(second).page.regions.all { it.typed && it.status == confirmed })
        assertTrue(state(second).page.ink.strokes.isEmpty())
        assertTrue(state(second).page.regions.all(state(second).page::isExportable))

        // Past the wait for the pen, nothing has gone to the recognizer.
        Thread.sleep(2_500)
        assertEquals(asked, synchronized(services.requests) { services.requests.size })
        waitFor(second) { it.savedVersion == it.version }
        assertEquals(3, kotlinx.coroutines.runBlocking { services.freshRepository().loadBooklet(elsewhere) }!!.pages.single().regions.size)
        second.close()
    }
}
