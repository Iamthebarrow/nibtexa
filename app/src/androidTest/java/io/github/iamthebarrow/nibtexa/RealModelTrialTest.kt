package io.github.iamthebarrow.nibtexa

import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.core.model.latex.LatexTokens
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.recognition.ByteLevelBpeDecoder
import io.github.iamthebarrow.nibtexa.core.recognition.TexTellerRecognizer
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import io.github.iamthebarrow.nibtexa.notebook.NotebookUiState
import io.github.iamthebarrow.nibtexa.notebook.NotebookViewModel
import io.github.iamthebarrow.nibtexa.notebook.TestServicesRule
import io.github.iamthebarrow.nibtexa.notebook.Tool
import io.github.iamthebarrow.nibtexa.recognition.OnnxTexTellerModel
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The integrated trial's conversion step with the real TexTeller model (Phase 8, manual run).
 * Real handwriting comes from MathWriting test samples already on the AVD for
 * TexTellerDeviceTest (evaluation-only data; never in the repo): sums, fractions, recurrences
 * and matrices, each written onto the page as stylus input, lassoed and converted.
 * It measures; it only asserts that every conversion completes and the ink is untouched.
 * Skipped unless /data/local/tmp/nibtexa-spike and nibtexa-e2e are present.
 */
@RunWith(AndroidJUnit4::class)
class RealModelTrialTest {
    @get:Rule
    val services = TestServicesRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val density = instrumentation.targetContext.resources.displayMetrics.density
    private var scenario: ActivityScenario<MainActivity>? = null
    private var model: OnnxTexTellerModel? = null

    @After
    fun close() {
        scenario?.close()
        model?.close()
    }

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }

    private fun state(): NotebookUiState {
        lateinit var s: NotebookUiState
        scenario!!.onActivity { s = ViewModelProvider(it)[NotebookViewModel::class.java].state.value }
        return s
    }

    private fun onVm(block: NotebookViewModel.() -> Unit) = scenario!!.onActivity { ViewModelProvider(it)[NotebookViewModel::class.java].block() }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(100)
        }
    }

    @Test
    fun dspSamplesThroughTheNotebook() {
        assumeTrue("e2e samples not pushed", shell("ls $E2E/samples.json").contains("samples.json"))
        assumeTrue("model not pushed", shell("ls $SPIKE/${OnnxTexTellerModel.DECODER}").contains("onnx"))
        val all = JSONArray(File("$E2E/samples.json").readText())
        val samples = (0 until all.length()).map { all.getJSONObject(it) }
        val chosen = CATEGORIES.flatMap { (name, pattern) ->
            samples.filter { pattern.containsMatchIn(it.getString("truth")) }.take(2).map { name to it }
        }
        Log.i(TAG, "chosen ${chosen.size}: ${chosen.joinToString { it.first }}")

        // Closed in tearDown: the model holds about 0.8 GB of native memory, and later tests in
        // the same process must not inherit it.
        model = OnnxTexTellerModel(File(SPIKE))
        val recognizer = TexTellerRecognizer(model!!, ByteLevelBpeDecoder.fromVocabJson(File("$E2E/${OnnxTexTellerModel.VOCAB}").readText()))
        services.script = { strokes, canceled -> recognizer.recognize(strokes, canceled) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(15_000) { state().loaded }
        onVm { setTool(Tool.MATH_PEN) }
        instrumentation.waitForIdleSync()

        val rows = mutableListOf<Triple<String, String, String>>() // category, truth, region id
        for ((index, entry) in chosen.withIndex()) {
            val (category, sample) = entry
            // Fit the sample's ink into a 900 x 110 px box in its own row.
            val strokes = sample.getJSONArray("strokes").let { a -> List(a.length()) { s -> a.getJSONArray(s).let { p -> List(p.length()) { j -> p.getJSONArray(j).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } } } } }
            val xs = strokes.flatten().map { it.first }
            val ys = strokes.flatten().map { it.second }
            val scale = minOf(900f / (xs.max() - xs.min()).coerceAtLeast(1f), 110f / (ys.max() - ys.min()).coerceAtLeast(1f))
            val top = 80f + index * 135f
            val before = state().page.ink.strokes.size
            scenario!!.onActivity { activity ->
                val view = activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
                for (stroke in strokes) {
                    val points = stroke.map { (x, y) -> 150f + (x - xs.min()) * scale to top + (y - ys.min()) * scale }
                    val path = if (points.size >= 2) points else points + points.first().let { it.first + 1f to it.second }
                    TestMotionEvents().path(TOOL_TYPE_STYLUS, path).forEach { view.dispatchTouchEvent(it); it.recycle() }
                }
            }
            waitFor(10_000) { state().page.ink.strokes.size == before + strokes.size }
            val (l, t, r, b) = listOf(120f, top - 10f, 1100f, top + 125f).map { it / density }
            onVm { onLasso(listOf(l to t, r to t, r to b, l to b, l to t + 1f)) }
            waitFor(5_000) { state().selection.size == strokes.size }
            val known = state().page.regions.map { it.id }.toSet()
            onVm { makeRegion(RegionRole.MATH) }
            waitFor(5_000) { state().page.regions.any { it.id !in known } }
            val id = state().page.regions.single { it.id !in known }.id
            waitFor(120_000) { state().page.region(id)?.status.let { it == RecognitionStatus.RECOGNIZED || it == RecognitionStatus.FAILED } }
            rows += Triple(category, sample.getString("truth"), id)
        }

        val inkBefore = state().page.ink
        var exact = 0
        for ((category, truth, id) in rows) {
            val region = state().page.region(id)!!
            val got = region.recognizedLatex.orEmpty()
            val match = LatexTokens.normalize(got) == LatexTokens.normalize(truth)
            if (match) exact++
            Log.i(TAG, "ROW $category status=${region.status} exact=$match truth=$truth got=$got")
        }
        Log.i(TAG, "RESULT realModelTrial samples=${rows.size} exact=$exact")
        assertEquals("every conversion completed", rows.size, rows.count { state().page.region(it.third)?.status == RecognitionStatus.RECOGNIZED })
        assertEquals("conversion never changes the ink", inkBefore, state().page.ink)
    }

    private companion object {
        const val TAG = "RealModelTrial"
        const val SPIKE = "/data/local/tmp/nibtexa-spike"
        const val E2E = "/data/local/tmp/nibtexa-e2e"
        val CATEGORIES = listOf(
            "sum" to Regex("""\\sum"""),
            "fraction" to Regex("""\\frac"""),
            "recurrence" to Regex("""n\s*-\s*1"""),
            "matrix" to Regex("""matrix|\\begin\{array"""),
            "greek" to Regex("""\\(omega|zeta|alpha|theta|lambda)"""),
        )
    }
}
