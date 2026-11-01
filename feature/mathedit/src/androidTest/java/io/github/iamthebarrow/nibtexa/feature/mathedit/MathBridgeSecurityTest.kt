package io.github.iamthebarrow.nibtexa.feature.mathedit

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.MessageGate
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.PageMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The bridge security note's claims, checked against a real WebView. */
@RunWith(AndroidJUnit4::class)
class MathBridgeSecurityTest {

    @Test
    fun everyPageLoadsFromAssetsWithoutBlockedRequestsOrErrors() {
        for (page in MathPage.entries) {
            val (scenario, activity) = launch(page)
            // The spike screen fills list and document pages on Ready; wait for that render to settle.
            when (page) {
                MathPage.MathLiveEditor -> Unit
                MathPage.MathLiveList -> activity.awaitMessage(60_000, PageMessage.Rendered::class.java)
                MathPage.MathLiveDocument -> activity.awaitMessage(60_000, PageMessage.LaidOut::class.java)
            }
            activity.renderOrSet()
            val host = activity.host()
            assertEquals("$page blocked requests", emptyList<String>(), host.blockedRequests)
            assertEquals("$page rejected messages", emptyList<String>(), host.rejectedMessages)
            val errors = activity.received.filterIsInstance<PageMessage.PageError>()
            assertEquals("$page page errors", emptyList<PageMessage.PageError>(), errors)
            scenario.close()
        }
    }

    /** Phase 12: document blocks are text. Markup in them is shown, never parsed or run. */
    @Test
    fun documentTextIsNeverParsedAsMarkup() {
        val (scenario, activity) = launch(MathPage.MathLiveDocument)
        activity.awaitMessage(60_000, PageMessage.LaidOut::class.java)
        val laidOut = activity.renderDocument(SampleEquations.DOCUMENT)
        assertEquals(SampleEquations.DOCUMENT.size, laidOut.spans.size)
        assertEquals(emptyList<Int>(), laidOut.failed)
        // Compared in the page: evaluateJavascript's JSON escapes '<' as <.
        assertEquals("true", activity.evaluate("document.querySelector('.heading-1').textContent === 'Week 1 <b>not bold</b>'"))
        assertEquals("0", activity.evaluate("document.querySelectorAll('#doc b, #doc script').length"))
        // The figure is an empty box of the requested size; the app draws the ink into it.
        val figure = laidOut.spans.last()
        assertTrue(figure.imageTop > figure.top && figure.imageTop < figure.bottom)
        assertEquals("\"200px 80px\"", activity.evaluate("(() => { const b = document.querySelector('.figure-box'); return b.style.width + ' ' + b.style.height })()"))
        assertEquals(emptyList<String>(), activity.host().blockedRequests)
        scenario.close()
    }

    @Test
    fun bridgeObjectIsInjectedForTheAssetOriginOnly() {
        val (scenario, activity) = launch(MathPage.MathLiveEditor)
        assertEquals("\"object\"", activity.evaluate("typeof nibtexaBridge"))
        scenario.close()

        // The host refuses foreign pages outright (next test), so the WebView's own origin rule
        // is checked in a plain WebView registered exactly as MathPageHost registers it.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            WebViewCompat.addWebMessageListener(webView, MathPageHost.JS_OBJECT, setOf(MessageGate.ASSET_ORIGIN)) { _, _, _, _, _ -> }
            webView.loadDataWithBaseURL("https://evil.example/", "<!doctype html><p>foreign</p>", "text/html", "utf-8", null)
        }
        assertTrue(waitFor { webView.evaluate("location.origin + ' ' + document.readyState") == "\"https://evil.example complete\"" })
        assertEquals("\"undefined\"", webView.evaluate("typeof nibtexaBridge"))
        instrumentation.runOnMainSync { webView.destroy() }
    }

    @Test
    fun hostRefusesForeignContentAndRemoteNavigation() {
        val (scenario, activity) = launch(MathPage.MathLiveEditor)
        val webView = activity.host().webView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.loadDataWithBaseURL("https://evil.example/", "<!doctype html><p>foreign</p>", "text/html", "utf-8", null)
        }
        assertTrue(waitFor { activity.host().blockedRequests.any { it.startsWith("data:") } })
        InstrumentationRegistry.getInstrumentation().runOnMainSync { webView.loadUrl("https://example.com/") }
        assertTrue(waitFor { "https://example.com/" in activity.host().blockedRequests })
        scenario.close()
    }

    @Test
    fun contentSecurityPolicyStopsRemoteFetchesFromThePage() {
        val (scenario, activity) = launch(MathPage.MathLiveEditor)
        activity.received.clear()
        activity.evaluate("fetch('https://example.com/data').catch(() => {}); 1")
        val error = activity.awaitMessage(TIMEOUT_MS, PageMessage.PageError::class.java)
        assertTrue(error.message, error.message.startsWith("CSP blocked connect-src"))
        assertEquals(emptyList<String>(), activity.host().blockedRequests)
        scenario.close()
    }

    @Test
    fun malformedMessagesFromThePageAreRejected() {
        val (scenario, activity) = launch(MathPage.MathLiveEditor)
        activity.evaluate(
            """
            nibtexaBridge.postMessage('not json');
            nibtexaBridge.postMessage('{"v":1,"type":"evaluate","script":"x"}');
            nibtexaBridge.postMessage('{"v":2,"type":"changed","latex":"x"}');
            nibtexaBridge.postMessage('{"v":1,"type":"changed","latex":"x","extra":1}');
            1
            """.trimIndent(),
        )
        assertTrue(waitFor { activity.host().rejectedMessages.size == 4 })
        assertTrue(activity.received.none { it is PageMessage.Changed })
        scenario.close()
    }

    @Test
    fun malformedCommandsToThePageAreRejectedThere() {
        val (scenario, activity) = launch(MathPage.MathLiveEditor)
        val before = activity.setValue("y")
        activity.received.clear()
        // Feed commands straight into the page's handler, as if the app had sent them.
        activity.evaluate(
            """
            const deliver = (data) => nibtexaBridge.onmessage({ data });
            deliver('{"v":1,"type":"setValue","requestId":1,"latex":"x","script":"alert(1)"}');
            deliver('{"v":1,"type":"eval","requestId":2}');
            deliver('{"v":1,"type":"setValue","requestId":-3,"latex":"x"}');
            deliver('{"v":1,"type":"setValue","requestId":4,"latex":"' + 'x'.repeat(9000) + '"}');
            1
            """.trimIndent(),
        )
        repeat(4) {
            val error = activity.awaitMessage(TIMEOUT_MS, PageMessage.PageError::class.java)
            assertEquals("rejected command", error.message)
        }
        assertEquals("rejected commands must not change the field", before, activity.value())
        scenario.close()
    }

    private fun MathEditSpikeActivity.renderOrSet() {
        when (host().page) {
            MathPage.MathLiveEditor -> setValue("x")
            MathPage.MathLiveList -> renderList(SampleEquations.TWENTY)
            MathPage.MathLiveDocument -> renderDocument(SampleEquations.DOCUMENT)
        }
    }

    private fun WebView.evaluate(script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync { evaluateJavascript(script) { result = it; latch.countDown() } }
        check(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "script timed out" }
        return result
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return false
    }
}
