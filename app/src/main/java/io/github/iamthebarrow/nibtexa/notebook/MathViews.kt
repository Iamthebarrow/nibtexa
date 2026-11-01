package io.github.iamthebarrow.nibtexa.notebook

import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.feature.mathedit.MathPage
import io.github.iamthebarrow.nibtexa.feature.mathedit.MathPageHost
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.HostCommand
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.ListAlign
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.PageMessage
import io.github.iamthebarrow.nibtexa.ui.theme.Nib

/**
 * A page host in the app's light or dark look: the page's prefers-color-scheme follows the
 * context theme, which the activity's own (always light) theme would not. The WebView is
 * transparent, so the card behind it shows through.
 */
private fun themedHost(context: Context, dark: Boolean, page: MathPage, listener: (PageMessage) -> Unit): MathPageHost {
    val theme = if (dark) R.style.Theme_NibTeXa_MathDark else R.style.Theme_NibTeXa_MathLight
    return MathPageHost(ContextThemeWrapper(context, theme), page, listener).also { it.webView.setBackgroundColor(Color.TRANSPARENT) }
}

/** Holds the page host so callbacks created before it exists can reach it. */
private class HostRef {
    var host: MathPageHost? = null
    var ready by mutableStateOf(false)
    private var nextId = 1L
    fun id() = nextId++
}

/**
 * A MathLive editor loaded with [initialLatex]. Every edit, and the initial value as MathLive
 * reads it back, is reported through [onLatexChange]. Changing [editorKey] or [initialLatex]
 * reloads the editor.
 *
 * The editor is [fieldHeight] tall, and grows by the height of MathLive's virtual keyboard while
 * it shows, so the keyboard sits under the equation instead of over it (Phase 14).
 */
@Composable
fun MathEditor(editorKey: String, initialLatex: String, onLatexChange: (String) -> Unit, modifier: Modifier = Modifier, fieldHeight: Dp = 104.dp) {
    val latestOnChange by rememberUpdatedState(onLatexChange)
    val dark = Nib.palette.dark
    key(editorKey, initialLatex, dark) {
        val ref = remember { HostRef() }
        // CSS pixels in the page are dp (its viewport is the device width at scale 1).
        var keyboard by remember { mutableDoubleStateOf(0.0) }
        AndroidView(
            factory = { context ->
                themedHost(context, dark, MathPage.MathLiveEditor) { message ->
                    when (message) {
                        is PageMessage.Ready -> ref.host?.send(HostCommand.SetValue(ref.id(), initialLatex))
                        is PageMessage.Value -> latestOnChange(message.latex)
                        is PageMessage.Changed -> latestOnChange(message.latex)
                        is PageMessage.Keyboard -> keyboard = message.height
                        else -> Unit
                    }
                }.also { ref.host = it }.webView
            },
            onRelease = { ref.host?.destroy(); ref.host = null },
            modifier = modifier.height(fieldHeight + keyboard.dp).testTag(MATH_EDITOR_TAG),
        )
    }
}

const val MATH_EDITOR_TAG = "math_editor"

/** Read-only rendered equations, one per item, in the given order, each placed [align] on its line. */
@Composable
fun EquationList(items: List<String>, modifier: Modifier = Modifier, align: ListAlign = ListAlign.LEFT, minHeight: Dp = 72.dp) {
    val dark = Nib.palette.dark
    key(dark) {
        val ref = remember { HostRef() }
        // As tall as the rendered equations (a sum with limits is taller than a line), at least [minHeight].
        // CSS pixels in the page are dp, as for the editor.
        var height by remember { mutableStateOf(minHeight) }
        AndroidView(
            factory = { context ->
                themedHost(context, dark, MathPage.MathLiveList) { message ->
                    when (message) {
                        is PageMessage.Ready -> ref.ready = true
                        is PageMessage.Rendered -> ref.host?.webView?.let { view ->
                            view.postDelayed({ if (ref.host != null) height = maxOf(minHeight, (view.contentHeight + 8).dp) }, LAYOUT_SETTLE_MS)
                        }
                        else -> Unit
                    }
                }.also { ref.host = it }.webView
            },
            onRelease = { ref.host?.destroy(); ref.host = null },
            modifier = modifier.height(height),
        )
        LaunchedEffect(items, align, ref.ready) {
            if (ref.ready) ref.host?.send(HostCommand.RenderList(ref.id(), items, align))
        }
    }
}

/** How long after rendering the list page has laid out, before its height is read. */
private const val LAYOUT_SETTLE_MS = 100L
