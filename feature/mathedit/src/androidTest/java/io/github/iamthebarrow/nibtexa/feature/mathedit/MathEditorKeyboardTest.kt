package io.github.iamthebarrow.nibtexa.feature.mathedit

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.HostCommand
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.PageMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MathEditorKeyboardTest {
    private lateinit var scenario: ActivityScenario<MathEditSpikeActivity>
    private lateinit var activity: MathEditSpikeActivity

    @Before
    fun open() {
        launch(MathPage.MathLiveEditor).let { (s, a) -> scenario = s; activity = a }
    }

    @After
    fun close() = scenario.close()

    private fun focusField() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { checkNotNull(activity.host).webView.requestFocus() }
        activity.send { HostCommand.Focus }
        val focused = activity.evaluate("document.getElementById('field').hasFocus()")
        assertEquals("true", focused)
    }

    @Test
    fun editsTheIntegralLowerBoundFromMinusOneToOne() {
        activity.setValue("""\int_{-1}^{1} x\,dx""")
        focusField()
        activity.received.clear()

        // Home: start of the field. Right: into the lower bound. Right: past the minus sign.
        HardwareKeys.press(KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DEL)

        val changed = activity.awaitMessage(TIMEOUT_MS, PageMessage.Changed::class.java)
        assertEquals("""\int_1^1x\,dx""", changed.latex)
        assertEquals("""\int_1^1x\,dx""", activity.value())
    }

    @Test
    fun keyboardNavigationReachesTheUpperBoundAndTypesIntoIt() {
        activity.setValue("""\int_{-1}^{1} x\,dx""")
        focusField()
        activity.received.clear()

        // Home, then Right four times: past "-" and "1", out of the lower bound, and into the
        // upper bound before its "1" (caret position 4). Each key waits for the caret to move:
        // under load the WebView can drop an injected key (Phase 8: 1 failure in 4 full runs when
        // the keys were sent in one burst), and a dropped key is sent again.
        fun position() = activity.evaluate("document.getElementById('field').position").toIntOrNull() ?: -1
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (position() != 0) {
            HardwareKeys.press(KeyEvent.KEYCODE_MOVE_HOME)
            val step = System.currentTimeMillis() + 1_000
            while (position() != 0 && System.currentTimeMillis() < step) Thread.sleep(20)
            check(System.currentTimeMillis() < deadline) { "caret never reached the start" }
        }
        while (position() < 4) {
            val before = position()
            HardwareKeys.press(KeyEvent.KEYCODE_DPAD_RIGHT)
            val step = System.currentTimeMillis() + 1_000
            while (position() == before && System.currentTimeMillis() < step) Thread.sleep(20)
            check(System.currentTimeMillis() < deadline) { "caret never reached the upper bound" }
        }
        assertEquals(4, position())
        HardwareKeys.press(KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_2)

        // Wait for the edit to be reported rather than reading the value immediately.
        val changed = activity.awaitMessage(TIMEOUT_MS, PageMessage.Changed::class.java) { it.latex.startsWith("""\int_{-1}^2""") }
        assertEquals("""\int_{-1}^2x\,dx""", changed.latex)
        assertEquals(changed.latex, activity.value())
    }
}
