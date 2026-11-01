package io.github.iamthebarrow.nibtexa.feature.mathedit

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.DocumentBlock
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.HostCommand
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.PageMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val TIMEOUT_MS = 20_000L

internal fun launch(page: MathPage): Pair<ActivityScenario<MathEditSpikeActivity>, MathEditSpikeActivity> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val scenario = ActivityScenario.launch<MathEditSpikeActivity>(MathEditSpikeActivity.intent(context, page))
    lateinit var activity: MathEditSpikeActivity
    scenario.onActivity { activity = it }
    activity.awaitMessage(TIMEOUT_MS, PageMessage.Ready::class.java)
    return scenario to activity
}

internal fun MathEditSpikeActivity.host(): MathPageHost {
    var host: MathPageHost? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { host = this.host }
    return checkNotNull(host)
}

/** Sends a command on the main thread and returns its request id. */
internal fun MathEditSpikeActivity.send(make: (Long) -> HostCommand): Long {
    val id = newRequestId()
    var sent = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync { sent = host?.send(make(id)) == true }
    check(sent) { "page not ready" }
    return id
}

internal fun MathEditSpikeActivity.setValue(latex: String): String {
    val id = send { HostCommand.SetValue(it, latex) }
    return awaitMessage(TIMEOUT_MS, PageMessage.Value::class.java) { it.requestId == id }.latex
}

internal fun MathEditSpikeActivity.value(): String {
    val id = send { HostCommand.GetValue(it) }
    return awaitMessage(TIMEOUT_MS, PageMessage.Value::class.java) { it.requestId == id }.latex
}

internal fun MathEditSpikeActivity.renderList(items: List<String>): PageMessage.Rendered {
    val id = send { HostCommand.RenderList(it, items) }
    return awaitMessage(60_000, PageMessage.Rendered::class.java) { it.requestId == id }
}

internal fun MathEditSpikeActivity.renderDocument(blocks: List<DocumentBlock>): PageMessage.LaidOut {
    val id = send { HostCommand.RenderDocument(it, blocks) }
    return awaitMessage(60_000, PageMessage.LaidOut::class.java) { it.requestId == id }
}

/** Runs JavaScript in the page and returns its JSON-encoded result. */
internal fun MathEditSpikeActivity.evaluate(script: String): String {
    val latch = CountDownLatch(1)
    var result = ""
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        checkNotNull(host).webView.evaluateJavascript(script) { result = it; latch.countDown() }
    }
    check(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "script timed out" }
    return result
}
