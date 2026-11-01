package io.github.iamthebarrow.nibtexa.recognition

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.canvas.CanvasViewModel
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.corpus.CorpusPrompts
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.serialization.InkJson
import io.github.iamthebarrow.nibtexa.core.recognition.RecognitionCanceledException
import io.github.iamthebarrow.nibtexa.core.recognition.TextContext
import io.github.iamthebarrow.nibtexa.core.recognition.TextMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The ML Kit text trial (Phase 11, ADR-0007 section 3). Manual: it needs the English language
 * pack, which this test downloads only when run with `-e downloadPack true`, and otherwise
 * skips.
 *
 * - [userSamples] measures the user's own lines from the corpus capture screen's "Text
 *   samples" set (files `txt-*.json` in the app's corpus folder; never in the repo). It reports
 *   word accuracy, character error rate, latency and PSS, and asserts only that every line
 *   completes.
 * - [syntheticInkSmoke] checks the pipeline on drawn shapes: a result comes back, cancel works,
 *   and the strokes are not changed.
 *
 * Results are logged under [TAG] and written to the app's `trial/` folder.
 */
@RunWith(AndroidJUnit4::class)
class TextTrialTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val pack = TextLanguagePack()
    private val recognizer = MlKitTextSource().recognizer()

    @Before
    fun languagePack() {
        if (InstrumentationRegistry.getArguments().getString("downloadPack") == "true" && !pack.isDownloaded()) {
            val started = SystemClock.elapsedRealtime()
            pack.download(wifiOnly = false)
            Log.i(TAG, "language pack ${pack.languageTag} downloaded in ${SystemClock.elapsedRealtime() - started} ms")
        }
        assumeTrue("language pack ${pack.languageTag} not downloaded (run with -e downloadPack true)", pack.isDownloaded())
    }

    @Test
    fun syntheticInkSmoke() {
        // An "o": one closed loop, then an "l": one vertical line, side by side.
        val loop = Stroke(1, ToolType.STYLUS, 1_000, List(33) { i ->
            val a = 2 * PI * i / 32
            InkPoint(30f + 20f * sin(a).toFloat(), 60f - 20f * cos(a).toFloat(), i * 8L)
        }, Pen.TEXT)
        val line = Stroke(2, ToolType.STYLUS, 1_600, List(20) { i -> InkPoint(70f, 10f + i * 3.5f, i * 8L) }, Pen.TEXT)
        val strokes = listOf(loop, line)
        val copy = strokes.map { it.copy(points = it.points.toList()) }

        val started = SystemClock.elapsedRealtime()
        val text = recognizer.recognize(strokes, TextContext(100f, 90f))
        Log.i(TAG, "synthetic: \"$text\" in ${SystemClock.elapsedRealtime() - started} ms")

        assertTrue(text.isNotBlank())
        assertEquals(copy, strokes)
        assertThrows(RecognitionCanceledException::class.java) { recognizer.recognize(strokes) { true } }
    }

    @Test
    fun userSamples() {
        val truths = app.assets.open("corpus/text-prompts.tsv").bufferedReader().use { CorpusPrompts.parse(it.readText()) }
            .associate { it.id to it.latex }
        val corpus = app.getExternalFilesDir(CanvasViewModel.CORPUS_DIR) ?: File(app.filesDir, CanvasViewModel.CORPUS_DIR)
        val samples = corpus.listFiles { f -> f.name.startsWith("txt-") && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
            .mapNotNull { file -> truths[file.name.substringBefore('_')]?.let { truth -> Triple(file.nameWithoutExtension, truth, InkJson.decode(file.readText()).strokes) } }
        assumeTrue("no text samples in $corpus (use Text samples in the booklet menu)", samples.isNotEmpty())

        // The first call creates the recognizer and loads the pack; it is reported separately.
        val coldStart = SystemClock.elapsedRealtime()
        recognizer.recognize(samples.first().third, context(samples.first().third))
        val cold = SystemClock.elapsedRealtime() - coldStart

        val rows = samples.map { (name, truth, strokes) ->
            val started = SystemClock.elapsedRealtime()
            val text = recognizer.recognize(strokes, context(strokes))
            Triple(name, truth to text, SystemClock.elapsedRealtime() - started)
        }
        val pssMb = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss / 1024
        val summary = TextMetrics.summarize(rows.map { it.second })
        val latencies = rows.map { it.third }

        val report = buildString {
            appendLine("ML Kit text trial, ${Instant.now()}, ${android.os.Build.MODEL} API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("lines ${summary.lines}, exact lines ${summary.exactLines}")
            appendLine("word accuracy %.1f%% (%d/%d)".format(summary.wordAccuracy * 100, summary.matchedWords, summary.referenceWords))
            appendLine("character error rate %.1f%% (%d/%d)".format(summary.charErrorRate * 100, summary.charEdits, summary.referenceChars))
            appendLine("latency p50 ${TextMetrics.percentile(latencies, 0.5)} ms, p95 ${TextMetrics.percentile(latencies, 0.95)} ms, max ${latencies.max()} ms, cold first call $cold ms")
            appendLine("total PSS after recognition $pssMb MB")
            appendLine()
            for ((name, pair, ms) in rows) appendLine("$name\t${ms}ms\t${if (pair.first == pair.second) "OK" else "XX"}\t${pair.first}\t${pair.second}")
        }
        report.lineSequence().forEach { Log.i(TAG, it) }
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "trial").apply { mkdirs() }
        File(dir, "text-trial-${System.currentTimeMillis()}.txt").writeText(report)

        assertEquals(samples.size, rows.size)
    }

    /** The ink's own bounds as the writing area, as the notebook will pass a region's bounds. */
    private fun context(strokes: List<Stroke>): TextContext {
        val b = Bounds.of(strokes)!!
        return TextContext(b.right - b.left, b.bottom - b.top)
    }

    private companion object {
        const val TAG = "TextTrial"
    }
}
