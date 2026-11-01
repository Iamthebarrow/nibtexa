package io.github.iamthebarrow.nibtexa.mathedit

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.core.model.corpus.CorpusPrompt
import io.github.iamthebarrow.nibtexa.core.model.corpus.CorpusPrompts
import io.github.iamthebarrow.nibtexa.core.model.latex.LatexTokens
import io.github.iamthebarrow.nibtexa.feature.mathedit.MathEditSpikeActivity
import io.github.iamthebarrow.nibtexa.feature.mathedit.MathPage
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.HostCommand
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.PageMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every corpus prompt through the real MathLive page on the device: load the LaTeX, read it
 * back, and compare normalized tokens (no content lost), then load the read-back value again
 * and require the identical string (stable).
 */
@RunWith(AndroidJUnit4::class)
class CorpusRoundTripTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val prompts: List<CorpusPrompt> =
        instrumentation.targetContext.assets.open("corpus/prompts.tsv").bufferedReader().use { CorpusPrompts.parse(it.readText()) }

    @Test
    fun mathLiveRoundTripsEveryPromptWithoutLoss() {
        val first = render(MathPage.MathLiveList, prompts.map { it.latex })
        val second = render(MathPage.MathLiveList, first.values)

        val lossy = prompts.indices.filter { LatexTokens.normalize(prompts[it].latex) != LatexTokens.normalize(first.values[it]) }
        val unstable = prompts.indices.filter { first.values[it] != second.values[it] }
        lossy.forEach { Log.w(TAG, "LOSSY ${prompts[it].id}: ${prompts[it].latex}  ->  ${first.values[it]}") }
        unstable.forEach { Log.w(TAG, "UNSTABLE ${prompts[it].id}: ${first.values[it]}  ->  ${second.values[it]}") }
        Log.i(TAG, "RESULT mathlive prompts=${prompts.size} lossy=${lossy.size} unstable=${unstable.size} failed=${first.failed.size}")

        assertEquals(emptyList<Int>(), first.failed)
        assertEquals("prompts whose content changed", emptyList<String>(), lossy.map { prompts[it].id })
        assertEquals("prompts whose value is not stable", emptyList<String>(), unstable.map { prompts[it].id })
    }

    private fun render(page: MathPage, items: List<String>): PageMessage.Rendered {
        val scenario = ActivityScenario.launch<MathEditSpikeActivity>(MathEditSpikeActivity.intent(instrumentation.targetContext, page))
        try {
            lateinit var activity: MathEditSpikeActivity
            scenario.onActivity { activity = it }
            activity.awaitMessage(TIMEOUT_MS, PageMessage.Ready::class.java)
            val id = activity.newRequestId()
            instrumentation.runOnMainSync { check(activity.host?.send(HostCommand.RenderList(id, items)) == true) }
            return activity.awaitMessage(120_000, PageMessage.Rendered::class.java) { it.requestId == id }
        } finally {
            scenario.close()
        }
    }

    companion object {
        private const val TAG = "CorpusRoundTrip"
        private const val TIMEOUT_MS = 20_000L
    }
}
