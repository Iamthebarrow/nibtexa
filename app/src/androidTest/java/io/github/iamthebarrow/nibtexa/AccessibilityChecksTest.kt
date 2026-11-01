package io.github.iamthebarrow.nibtexa

import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.notebook.NotebookViewModel
import io.github.iamthebarrow.nibtexa.notebook.TestServicesRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Google's Accessibility Test Framework over the notebook screens (Phase 8): touch targets,
 * labels, contrast and duplicate descriptions. It fails the test on any error-level result.
 * TalkBack speaking is still checked by hand (HW-12).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityChecksTest {
    private val services = TestServicesRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]

    /** The control: the checks must catch a tiny unlabelled button, or the passes mean nothing. */
    @Test
    fun theChecksCatchATinyUnlabelledButton() {
        composeRule.enableAccessibilityChecks()
        composeRule.waitUntil(10_000) { viewModel().state.value.loaded }
        composeRule.runOnUiThread {
            composeRule.activity.setContent { Box(Modifier.size(8.dp).clickable {}) }
        }
        composeRule.waitForIdle()
        val failure = runCatching { composeRule.onRoot().tryPerformAccessibilityChecks() }.exceptionOrNull()
        assertNotNull("the Accessibility Test Framework did not run", failure)
        assertTrue("unexpected failure: $failure", failure.toString().contains("touch target", ignoreCase = true))
    }

    @Test
    fun theNotebookAndReadingModePassTheChecks() {
        composeRule.enableAccessibilityChecks()
        composeRule.waitUntil(10_000) { viewModel().state.value.loaded }
        composeRule.onRoot().tryPerformAccessibilityChecks()

        composeRule.runOnUiThread { viewModel().setReadingMode(true) }
        composeRule.waitForIdle()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }
}
