package io.github.iamthebarrow.nibtexa.notebook

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.Clip
import io.github.iamthebarrow.nibtexa.export.MathCapture
import io.github.iamthebarrow.nibtexa.storage.ClipSummary
import io.github.iamthebarrow.nibtexa.ui.theme.Nib
import io.github.iamthebarrow.nibtexa.ui.theme.NibIcons

/**
 * The clipboard list (Phase 26): what was copied, with equations typeset. Tapping an entry pastes it on the current page.
 * Entries can be pinned, removed or sent to a graph, and the whole list can be cleared.
 */
@Composable
fun ClipboardPanel(state: NotebookUiState, viewModel: NotebookViewModel) {
    val p = Nib.palette
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clipboard_clear_title)) },
            text = { Text(stringResource(R.string.clipboard_clear_text)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.clearClips() }) { Text(stringResource(R.string.clipboard_clear), color = p.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // All the panel's equations in one capture, kept for the session, so scrolling doesn't typeset anything again.
    val wanted = remember(state.clips) { state.clips.flatMap { it.equationList.take(EQUATIONS_SHOWN) }.distinct().map { "\\displaystyle $it" } }
    val maths = rememberInlineMath(wanted)
    Dialog(onDismissRequest = { viewModel.setClipboardOpen(false) }) {
        Column(
            Modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(24.dp)).background(p.surface).padding(20.dp).testTag(CLIPBOARD_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.clipboard), style = MaterialTheme.typography.titleLarge, color = p.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }, enabled = state.clips.isNotEmpty()) { Text(stringResource(R.string.clipboard_clear), color = if (state.clips.isNotEmpty()) p.error else p.line) }
                IconButton(onClick = { viewModel.setClipboardOpen(false) }) { Icon(NibIcons.Close, contentDescription = stringResource(R.string.close), tint = p.ink) }
            }
            if (state.clips.isEmpty()) {
                Text(stringResource(R.string.clipboard_empty), style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                Text(stringResource(R.string.clipboard_hint), style = MaterialTheme.typography.bodySmall, color = p.muted)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.clips, key = { it.id }) { summary ->
                        ClipRow(summary, maths, viewModel, Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipRow(summary: ClipSummary, maths: Map<String, MathCapture.Captured>, viewModel: NotebookViewModel, modifier: Modifier) {
    val p = Nib.palette
    val shape = RoundedCornerShape(14.dp)
    val paste = stringResource(R.string.paste)
    val equations = summary.equationList
    Column(modifier.fillMaxWidth().clip(shape).border(1.dp, if (summary.pinned) p.primary else p.line, shape)) {
        // The content pastes when tapped; the buttons are in a row of their own, so a tap can't land on the wrong one.
        Column(
            Modifier.fillMaxWidth().clickable(onClickLabel = paste) { viewModel.paste(summary.id) }.padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 6.dp).testTag(clipRowTag(summary.id)),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            equations.take(EQUATIONS_SHOWN).forEach { ClipEquation(it, maths["\\displaystyle $it"]) }
            if (equations.size > EQUATIONS_SHOWN) Text(stringResource(R.string.clipboard_more_equations, equations.size - EQUATIONS_SHOWN), style = MaterialTheme.typography.labelMedium, color = p.muted)
            if (summary.words.isNotEmpty()) Text(summary.words, style = MaterialTheme.typography.bodyMedium, color = p.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (summary.drawn) ClipPicture(summary, viewModel)
        }
        Row(Modifier.padding(start = 4.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.paste(summary.id) }) { Text(paste) }
            if (summary.handwritten) TextButton(onClick = { viewModel.paste(summary.id, handwriting = true) }) { Text(stringResource(R.string.clipboard_with_handwriting)) }
            if (equations.isNotEmpty()) TextButton(onClick = { viewModel.clipToGraph(summary.id) }) { Text(stringResource(R.string.graph_equation)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.setClipPinned(summary.id, !summary.pinned) }) {
                Icon(NibIcons.Pin, contentDescription = stringResource(if (summary.pinned) R.string.clipboard_unpin else R.string.clipboard_pin), tint = if (summary.pinned) p.primary else p.muted)
            }
            IconButton(onClick = { viewModel.deleteClip(summary.id) }) { Icon(NibIcons.Delete, contentDescription = stringResource(R.string.delete), tint = p.muted) }
        }
    }
}

/** An equation typeset, or its LaTeX until MathLive has done that. Wide ones are made smaller to fit. */
@Composable
private fun ClipEquation(latex: String, captured: MathCapture.Captured?) {
    val p = Nib.palette
    if (captured == null) {
        Text(latex, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = p.math, maxLines = 2, overflow = TextOverflow.Ellipsis)
        return
    }
    val image = remember(captured) { captured.bitmap.asImageBitmap() }
    val density = LocalDensity.current
    val aspect = image.width.toFloat() / image.height.coerceAtLeast(1)
    Image(
        image,
        contentDescription = latex,
        colorFilter = ColorFilter.tint(p.math),
        contentScale = ContentScale.Fit,
        modifier = Modifier.widthIn(max = with(density) { image.width.toDp() }).fillMaxWidth().aspectRatio(aspect),
    )
}

/** Ink, a picture or a graph, drawn small from the entry's content once it has been read. */
@Composable
private fun ClipPicture(summary: ClipSummary, viewModel: NotebookViewModel) {
    val clip by produceState<Clip?>(null, summary.id) { value = viewModel.clip(summary.id) }
    val shown = clip ?: return Spacer(Modifier.height(PICTURE_HEIGHT.dp))
    val (page, sheet) = remember(shown) { shown.preview() }
    val shape = RoundedCornerShape(8.dp)
    val label = stringResource(R.string.clipboard_picture)
    PageThumbnail(
        page,
        sheet,
        Modifier.heightIn(max = PICTURE_HEIGHT.dp).widthIn(max = PICTURE_WIDTH.dp).aspectRatio(sheet.aspect()).clip(shape).border(1.dp, Nib.palette.line, shape).semantics { contentDescription = label },
    )
}

fun clipRowTag(id: String) = "clip_$id"

const val CLIPBOARD_TAG = "clipboard"
private const val EQUATIONS_SHOWN = 3
private const val PICTURE_HEIGHT = 96
private const val PICTURE_WIDTH = 360
