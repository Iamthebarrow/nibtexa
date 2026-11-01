package io.github.iamthebarrow.nibtexa.notebook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 34: the math model's download warns first on a tablet it would be heavy for, and only there. */
@RunWith(AndroidJUnit4::class)
class ModelTermsDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val warning get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.model_download_low_memory, 3.6f)

    @Test
    fun aTabletWithLittleMemoryIsWarned() {
        composeRule.setContent { ModelTermsDialog(tightMemoryGb = 3.6f, onDownload = {}, onDismiss = {}) }
        composeRule.onNodeWithText(warning).assertIsDisplayed()
    }

    @Test
    fun aTabletWithPlentyIsNot() {
        composeRule.setContent { ModelTermsDialog(tightMemoryGb = null, onDownload = {}, onDismiss = {}) }
        assertEquals(0, composeRule.onAllNodesWithText(warning).fetchSemanticsNodes().size)
    }
}
