package io.github.iamthebarrow.nibtexa

import io.github.iamthebarrow.nibtexa.notebook.SELECTION_BAR_TAG
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.notebook.TestServicesRule
import io.github.iamthebarrow.nibtexa.storage.NotebookRepository
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(TestServicesRule()).around(composeRule)

    @Test
    fun launchesIntoAnEmptyNotebook() {
        composeRule.waitUntil(10_000) {
            runCatching { composeRule.onNodeWithText(NotebookRepository.DEFAULT_TITLE).assertIsDisplayed() }.isSuccess
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.tool_math_pen)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.undo)).assertIsNotEnabled()
        // Convert is in the selection bar, which appears only with a selection (Phase 13).
        composeRule.onNodeWithTag(SELECTION_BAR_TAG).assertDoesNotExist()
        assertNotNull(composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG))
    }
}
