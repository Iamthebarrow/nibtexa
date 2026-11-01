package io.github.iamthebarrow.nibtexa.feature.mathedit

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.HostCommand
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.PageMessage
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What an accessibility service (TalkBack) gets from the math editor: the WebView's virtual
 * node tree as the instrumentation's UiAutomation sees it. Covers the ADR-0004 findings fixed
 * in Phase 8. TalkBack itself, speaking, is still checked by hand (HW-12).
 */
@RunWith(AndroidJUnit4::class)
class MathEditorAccessibilityTest {
    private lateinit var scenario: ActivityScenario<MathEditSpikeActivity>
    private lateinit var activity: MathEditSpikeActivity
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Before
    fun open() {
        // Connect as an accessibility service before the WebView exists, as TalkBack would be,
        // so Chromium builds its accessibility tree.
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        launch(MathPage.MathLiveEditor).let { (s, a) -> scenario = s; activity = a }
    }

    @After
    fun close() = scenario.close()

    private fun nodes(): List<Pair<Int, AccessibilityNodeInfo>> {
        val out = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            node ?: return
            out += depth to node
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(automation.rootInActiveWindow, 0)
        return out
    }

    /** The nodes inside the WebView, once Chromium has built them. */
    private fun webNodes(): List<AccessibilityNodeInfo> {
        val all = nodes()
        val web = all.indexOfFirst { it.second.className == "android.webkit.WebView" }
        if (web < 0) return emptyList()
        val depth = all[web].first
        return all.drop(web + 1).takeWhile { it.first > depth }.map { it.second }
    }

    private fun label(node: AccessibilityNodeInfo) = listOfNotNull(node.text, node.contentDescription, node.hintText).joinToString(" ")

    private fun describe(node: AccessibilityNodeInfo): String {
        val r = Rect().also(node::getBoundsInScreen)
        return "${node.className} text=${node.text} desc=${node.contentDescription} hint=${node.hintText} focusable=${node.isFocusable} bounds=${r.toShortString()}"
    }

    /** Waits for the focusable equation node whose label satisfies [matches]. */
    private fun equationNode(matches: (String) -> Boolean): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            val found = webNodes().firstOrNull { it.isFocusable && it.className == "android.widget.EditText" && matches(label(it)) }
            if (found != null) return found
            if (System.currentTimeMillis() > deadline) {
                webNodes().forEach { Log.i(TAG, describe(it)) }
                throw AssertionError("no focusable equation node matched; tree logged under $TAG")
            }
            Thread.sleep(200)
        }
    }

    @Test
    fun theEquationIsReadAsMathAndNotAsTheToolbar() {
        activity.setValue("""\int_{-1}^{1} x^2\,dx""")
        // The focusable input (MathLive's zero-size sink, where editing happens) reads the equation...
        equationNode { it.contains("integral from minus 1 to 1") }
        // ...and so does the field itself, which has a size, so touch exploration finds it.
        val nodes = webNodes()
        val field = nodes.firstOrNull { label(it).contains("integral from minus 1 to 1") && Rect().also(it::getBoundsInScreen).let { r -> r.width() > 100 && r.height() > 20 } }
        assertTrue("no full-size node reads the equation: ${nodes.map(::describe)}", field != null)

        // Only the keyboard toggle button itself says "Toggle Virtual Keyboard"; no node says "Menu".
        assertTrue("toolbar text leaks: ${nodes.map(::describe)}", nodes.none { it.className != "android.widget.Button" && label(it).contains("Toggle Virtual Keyboard") })
        assertTrue("menu still exposed: ${nodes.map(::describe)}", nodes.none { label(it).contains("Menu") })
    }

    @Test
    fun theDescriptionFollowsTyping() {
        activity.setValue("x")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { checkNotNull(activity.host).webView.requestFocus() }
        activity.send { HostCommand.Focus }
        activity.received.clear()
        // An edit through MathLive's own insert command, which fires the same input event as
        // typing. Key delivery itself is MathEditorKeyboardTest's job: injected keys are
        // sometimes dropped by the WebView under load, which made this test flaky.
        activity.evaluate("(() => { const f = document.getElementById('field'); f.position = f.lastOffset; f.executeCommand(['insert', '+1']); return f.value; })()")
        activity.awaitMessage(TIMEOUT_MS, PageMessage.Changed::class.java) { it.latex == "x+1" }
        equationNode { it.contains("plus 1") }
    }

    private companion object {
        const val TAG = "MathA11y"
    }
}
