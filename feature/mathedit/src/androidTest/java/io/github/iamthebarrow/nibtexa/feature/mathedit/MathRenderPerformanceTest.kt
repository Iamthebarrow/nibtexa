package io.github.iamthebarrow.nibtexa.feature.mathedit

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.PageMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Twenty equations on one screen, rendered by MathLive. Reports the time from
 * the command to a settled layout (fonts loaded, two frames). Emulator figures, not tablet.
 */
@RunWith(AndroidJUnit4::class)
class MathRenderPerformanceTest {

    @Test
    fun twentyEquationsRenderOnOneScreen() {
        for (page in listOf(MathPage.MathLiveList)) {
            val (scenario, activity) = launch(page)
            val first = activity.awaitMessage(60_000, PageMessage.Rendered::class.java)
            val warm = (1..RUNS).map { activity.renderList(SampleEquations.TWENTY) }

            for (result in warm + first) {
                assertEquals("$page count", SampleEquations.TWENTY.size, result.count)
                assertEquals("$page failures", emptyList<Int>(), result.failed)
            }
            val millis = warm.map { it.millis }.sorted()
            Log.i(
                TAG,
                "RESULT page=$page firstMs=${"%.0f".format(first.millis)} warmMedianMs=${"%.0f".format(millis[millis.size / 2])} " +
                    "warmMaxMs=${"%.0f".format(millis.last())} appPssMb=${Debug.getPss() / 1024}",
            )
            assertTrue("$page first render took ${first.millis} ms", first.millis < BUDGET_MS)
            scenario.close()
        }
    }

    companion object {
        private const val TAG = "MathRenderPerf"
        private const val RUNS = 5

        // Generous emulator budget; the measured values go in the report.
        private const val BUDGET_MS = 3_000.0
    }
}
