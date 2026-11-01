package io.github.iamthebarrow.nibtexa.corpus

import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.canvas.CanvasViewModel
import io.github.iamthebarrow.nibtexa.core.model.serialization.InkJson
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class CorpusCaptureTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<CorpusCaptureActivity>()

    private val created = mutableListOf<File>()

    @After
    fun deleteCreatedFiles() {
        created.forEach { it.delete() }
    }

    private fun viewModel() = ViewModelProvider(composeRule.activity)[CanvasViewModel::class.java]

    @Test
    fun saveSampleWritesInkMlAndJsonThenClearsPageAndAdvancesPrompt() {
        composeRule.waitUntil(5_000) { viewModel().state.value.prompts.isNotEmpty() }
        val prompt = viewModel().state.value.prompt!!
        val dir = composeRule.activity.getExternalFilesDir(CanvasViewModel.CORPUS_DIR)!!
        val existing = dir.listFiles().orEmpty().toSet()

        composeRule.runOnUiThread {
            val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
            TestMotionEvents().line(TOOL_TYPE_STYLUS, 100f to 100f, 400f to 300f).forEach { view.dispatchTouchEvent(it) }
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.save_sample)).performClick()
        composeRule.waitUntil(5_000) { viewModel().state.value.savedCount == 1 }

        val newFiles = dir.listFiles().orEmpty().toSet() - existing
        created += newFiles
        assertEquals(setOf("inkml", "json"), newFiles.map { it.extension }.toSet())
        assertTrue(newFiles.all { it.name.startsWith("${prompt.id}_") })

        val inkMl = newFiles.single { it.extension == "inkml" }
        val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(inkMl)
        assertEquals(1, xml.getElementsByTagNameNS("http://www.w3.org/2003/InkML", "trace").length)
        assertEquals(1, InkJson.decode(newFiles.single { it.extension == "json" }.readText()).strokes.size)

        val state = viewModel().state.value
        assertTrue(state.document.strokes.isEmpty())
        assertEquals(1, state.promptIndex)
    }
}
