package io.github.iamthebarrow.nibtexa.notebook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.export.BookletNavigation
import io.github.iamthebarrow.nibtexa.core.export.ContentsEntry
import io.github.iamthebarrow.nibtexa.core.export.HitKind
import io.github.iamthebarrow.nibtexa.core.export.SearchMatch
import io.github.iamthebarrow.nibtexa.ui.theme.Nib
import io.github.iamthebarrow.nibtexa.ui.theme.NibIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The booklet's headings by level and page (Phase 28), as the exports list them. Tapping one goes to it. */
@Composable
fun ContentsTab(state: NotebookUiState, viewModel: NotebookViewModel, onDismiss: () -> Unit) {
    val p = Nib.palette
    val booklet = remember(state.pages, state.page, state.mathAlign) { viewModel.openBookletNow(state) }
    // Off the main thread: it sets the whole booklet's document.
    val entries by produceState<List<ContentsEntry>?>(null, booklet) { value = withContext(Dispatchers.Default) { BookletNavigation.contents(booklet) } }
    val shown = entries
    Box(Modifier.heightIn(min = PANEL_MIN_HEIGHT.dp).testTag(CONTENTS_TAG)) {
        when {
            shown == null -> Unit
            shown.isEmpty() -> Text(stringResource(R.string.contents_empty), style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(vertical = 24.dp))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(shown, key = { "${it.pageIndex}/${it.regionId}" }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { viewModel.goToRegion(entry.pageIndex, entry.regionId); onDismiss() }
                            .padding(start = 12.dp + INDENT.dp * (entry.level - 1), top = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            entry.title,
                            style = if (entry.level == 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                            color = p.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${entry.pageIndex + 1}", style = MaterialTheme.typography.labelMedium, color = p.muted)
                    }
                }
            }
        }
    }
}

/** Searches the open booklet as it is now, unsaved edits included (Phase 28). Tapping a result goes to it. */
@Composable
fun FindTab(state: NotebookUiState, viewModel: NotebookViewModel, onDismiss: () -> Unit) {
    val p = Nib.palette
    var query by remember { mutableStateOf("") }
    val pages by rememberUpdatedState(viewModel.openBookletNow(state).pages)
    val found by produceState<List<SearchMatch>?>(null, query) {
        value = null
        if (query.isBlank()) return@produceState
        delay(SEARCH_WAIT_MS)
        val now = pages
        value = withContext(Dispatchers.Default) { BookletNavigation.search(now, query) }
    }
    Column(Modifier.heightIn(min = PANEL_MIN_HEIGHT.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.find_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(NibIcons.Search, contentDescription = null, tint = p.muted) },
            modifier = Modifier.fillMaxWidth().testTag(FIND_FIELD_TAG),
        )
        val shown = found
        when {
            query.isBlank() -> Text(stringResource(R.string.find_prompt), style = MaterialTheme.typography.bodyMedium, color = p.muted)
            shown == null -> Unit
            shown.isEmpty() -> Text(stringResource(R.string.find_empty), style = MaterialTheme.typography.bodyMedium, color = p.muted)
            else -> {
                if (shown.size >= BookletNavigation.MAX_MATCHES) Text(stringResource(R.string.find_limit, BookletNavigation.MAX_MATCHES), style = MaterialTheme.typography.bodySmall, color = p.muted)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown, key = { "${it.pageIndex}/${it.regionId}" }) { match -> FindRow(match) { viewModel.goToRegion(match.pageIndex, match.regionId); onDismiss() } }
                }
            }
        }
    }
}

@Composable
private fun FindRow(match: SearchMatch, onClick: () -> Unit) {
    val p = Nib.palette
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            remember(match) {
                buildAnnotatedString {
                    append(match.snippet.substring(0, match.start))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.snippet.substring(match.start, match.start + match.length)) }
                    append(match.snippet.substring(match.start + match.length))
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = p.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val mark = when (match.kind) {
            HitKind.CONFIRMED -> null
            HitKind.UNCONFIRMED -> stringResource(R.string.find_unconfirmed)
            HitKind.SCRATCH -> stringResource(R.string.find_scratch)
        }
        val page = stringResource(R.string.find_page, match.pageIndex + 1)
        Text(if (mark != null) "$page · $mark" else page, style = MaterialTheme.typography.labelMedium, color = if (mark != null) p.primaryInk else p.muted)
    }
}

const val CONTENTS_TAG = "contents_tab"
const val FIND_FIELD_TAG = "find_field"
private const val PANEL_MIN_HEIGHT = 240
private const val INDENT = 20
private const val SEARCH_WAIT_MS = 200L
