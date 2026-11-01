package io.github.iamthebarrow.nibtexa.feature.mathedit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.HostCommand
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.PageMessage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Debug-only test host from the Phase 4 spike: a MathLive editor and a list of
 * [SampleEquations.TWENTY]. Instrumented tests drive the bridge through it. It is in the
 * debug source set, so release builds do not contain it.
 */
class MathEditSpikeActivity : ComponentActivity() {

    private var page by mutableStateOf(MathPage.MathLiveEditor)
    private var latex by mutableStateOf("")
    private var status by mutableStateOf("")
    private var nextRequestId = 1L

    /** The host of the page on screen. */
    @VisibleForTesting
    var host: MathPageHost? = null
        private set

    /** Every accepted page message, in order, for tests to wait on. */
    @VisibleForTesting
    val received = LinkedBlockingQueue<PageMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent.getStringExtra(EXTRA_PAGE)?.let { name -> MathPage.entries.firstOrNull { it.name == name }?.let { page = it } }
        if (!MathPageHost.isSupported()) status = getString(R.string.mathedit_unsupported)
        setContent {
            MaterialTheme {
                Scaffold(Modifier.fillMaxSize()) { padding ->
                    SpikeScreen(Modifier.padding(padding))
                }
            }
        }
    }

    @VisibleForTesting
    fun newRequestId(): Long = nextRequestId++

    /** Waits off the main thread for the first received message matching [predicate]. */
    @VisibleForTesting
    fun <T : PageMessage> awaitMessage(timeoutMillis: Long, type: Class<T>, predicate: (T) -> Boolean = { true }): T {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val left = deadline - System.currentTimeMillis()
            check(left > 0) { "No matching ${type.simpleName} within $timeoutMillis ms" }
            val message = received.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (type.isInstance(message) && predicate(type.cast(message)!!)) return type.cast(message)!!
        }
    }

    private fun onPageMessage(message: PageMessage) {
        received += message
        when (message) {
            is PageMessage.Ready -> {
                when (page) {
                    MathPage.MathLiveEditor -> host?.send(HostCommand.SetValue(newRequestId(), SampleEquations.TWENTY.first()))
                    MathPage.MathLiveList -> host?.send(HostCommand.RenderList(newRequestId(), SampleEquations.TWENTY))
                    MathPage.MathLiveDocument -> host?.send(HostCommand.RenderDocument(newRequestId(), SampleEquations.DOCUMENT))
                }
            }
            is PageMessage.Changed -> latex = message.latex
            is PageMessage.Value -> latex = message.latex
            is PageMessage.Rendered ->
                status = getString(R.string.mathedit_rendered, message.count, message.millis, message.failed.size)
            is PageMessage.LaidOut ->
                status = getString(R.string.mathedit_laid_out, message.spans.size, message.height, message.failed.size)
            is PageMessage.Keyboard -> Unit
            is PageMessage.PageError -> status = message.message
        }
    }

    @Composable
    private fun SpikeScreen(modifier: Modifier) {
        Column(modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = TABS.indexOf(page)) {
                TABS.forEach { tab ->
                    Tab(
                        selected = page == tab,
                        onClick = { latex = ""; page = tab },
                        text = { Text(stringResource(tab.label())) },
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = latex.ifEmpty { stringResource(R.string.mathedit_no_latex) },
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            HorizontalDivider()
            if (MathPageHost.isSupported()) {
                key(page) {
                    AndroidView(
                        factory = { context -> MathPageHost(context, page, ::onPageMessage).also { host = it }.webView },
                        onRelease = { host?.destroy(); host = null },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }

    private fun MathPage.label() = when (this) {
        MathPage.MathLiveEditor -> R.string.mathedit_tab_mathlive
        MathPage.MathLiveList -> R.string.mathedit_tab_mathlive_list
        MathPage.MathLiveDocument -> R.string.mathedit_tab_document
    }

    companion object {
        const val EXTRA_PAGE = "page"
        private val TABS = listOf(MathPage.MathLiveEditor, MathPage.MathLiveList, MathPage.MathLiveDocument)

        fun intent(context: Context, page: MathPage = MathPage.MathLiveEditor): Intent =
            Intent(context, MathEditSpikeActivity::class.java).putExtra(EXTRA_PAGE, page.name)
    }
}
