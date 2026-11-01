package io.github.iamthebarrow.nibtexa.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.storage.BookletSummary
import io.github.iamthebarrow.nibtexa.ui.theme.Nib
import io.github.iamthebarrow.nibtexa.ui.theme.NibIcons

/**
 * The Trash (Phase 21): booklets deleted from the library, until they are restored, deleted for good,
 * or have been here for [NotebookViewModel.TRASH_DAYS] days.
 */
@Composable
fun TrashScreen(state: NotebookUiState, viewModel: NotebookViewModel, modifier: Modifier = Modifier) {
    val p = Nib.palette
    var deleting by remember { mutableStateOf<BookletSummary?>(null) }
    var emptying by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(p.ground).testTag(TRASH_TAG)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::closeTrash) { Icon(NibIcons.Back, contentDescription = stringResource(R.string.back_to_library), tint = p.ink) }
            Text(stringResource(R.string.trash), style = MaterialTheme.typography.titleLarge, color = p.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = { emptying = true }, enabled = state.trash.isNotEmpty()) {
                Text(stringResource(R.string.trash_empty), color = if (state.trash.isNotEmpty()) p.error else p.muted)
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = state.trashAutoEmpty, role = Role.Switch, onValueChange = viewModel::setTrashAutoEmpty)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.trash_auto_empty), style = MaterialTheme.typography.bodyMedium, color = p.ink, modifier = Modifier.weight(1f))
            Switch(checked = state.trashAutoEmpty, onCheckedChange = null)
        }
        if (state.trash.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.trash_empty_state), style = MaterialTheme.typography.bodyLarge, color = p.muted)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.trash, key = { it.id }) { booklet ->
                    TrashRow(booklet, state.trashAutoEmpty, onRestore = { viewModel.restoreFromTrash(booklet.id) }, onDelete = { deleting = booklet })
                }
            }
        }
    }
    deleting?.let { booklet ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_booklet_title, booklet.title)) },
            text = { Text(stringResource(R.string.delete_booklet_text)) },
            confirmButton = { TextButton(onClick = { deleting = null; viewModel.deleteForGood(booklet.id) }) { Text(stringResource(R.string.delete), color = p.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (emptying) {
        AlertDialog(
            onDismissRequest = { emptying = false },
            title = { Text(stringResource(R.string.trash_empty_title)) },
            text = { Text(stringResource(R.string.trash_empty_text)) },
            confirmButton = { TextButton(onClick = { emptying = false; viewModel.emptyTrash() }) { Text(stringResource(R.string.trash_empty), color = p.error) } },
            dismissButton = { TextButton(onClick = { emptying = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun TrashRow(booklet: BookletSummary, autoEmpty: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    val p = Nib.palette
    val days = ((System.currentTimeMillis() - (booklet.deletedAt ?: 0L)) / NotebookViewModel.MILLIS_PER_DAY).toInt().coerceAtLeast(0)
    val deleted = if (days == 0) stringResource(R.string.trash_deleted_today) else pluralStringResource(R.plurals.trash_deleted_days, days, days)
    val left = (NotebookViewModel.TRASH_DAYS.toInt() - days).coerceAtLeast(0)
    Surface(shape = RoundedCornerShape(14.dp), color = p.card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(booklet.title, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = deleted + if (autoEmpty) " · " + pluralStringResource(R.plurals.trash_days_left, left, left) else ""
                Text(detail, style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
            TextButton(onClick = onRestore) { Text(stringResource(R.string.trash_restore)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.trash_delete_for_good), color = p.error) }
        }
    }
}

/** What to back up (Phase 21): the whole library, or chosen booklets. [onSave] gets null for the whole library. */
@Composable
fun BackupDialog(library: List<BookletSummary>, onSave: (Set<String>?) -> Unit, onDismiss: () -> Unit) {
    val p = Nib.palette
    var whole by remember { mutableStateOf(true) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_title)) },
        text = {
            Column(Modifier.testTag(BACKUP_DIALOG_TAG), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.backup_text), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                ChoiceRow(stringResource(R.string.backup_whole_library), whole) { whole = true }
                ChoiceRow(stringResource(R.string.backup_chosen), !whole) { whole = false }
                if (!whole) {
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        for (booklet in library) {
                            val on = booklet.id in chosen
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(value = on, role = Role.Checkbox) { chosen = if (it) chosen + booklet.id else chosen - booklet.id }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = null)
                                Spacer(Modifier.padding(start = 8.dp))
                                Text(booklet.title, style = MaterialTheme.typography.bodyMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(if (whole) null else chosen) }, enabled = whole || chosen.isNotEmpty()) { Text(stringResource(R.string.backup_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.padding(start = 8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Nib.palette.ink)
    }
}

/** Asks before restoring a checked backup (Phase 21), and how to treat booklets already here. */
@Composable
fun RestoreDialog(prompt: RestorePrompt, onRestore: (replace: Boolean) -> Unit, onDismiss: () -> Unit) {
    val p = Nib.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_title)) },
        text = {
            Column(Modifier.testTag(RESTORE_DIALOG_TAG), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pluralStringResource(R.plurals.restore_text, prompt.titles.size, prompt.titles.size), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    prompt.titles.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (prompt.existing > 0) {
                    Text(pluralStringResource(R.plurals.restore_existing, prompt.existing, prompt.existing), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                }
            }
        },
        confirmButton = {
            Row {
                if (prompt.existing > 0) TextButton(onClick = { onRestore(true) }) { Text(stringResource(R.string.restore_replace), color = p.error) }
                TextButton(onClick = { onRestore(false) }) { Text(stringResource(if (prompt.existing > 0) R.string.restore_add_copies else R.string.restore_add)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

const val TRASH_TAG = "trash"
const val BACKUP_DIALOG_TAG = "backup_dialog"
const val RESTORE_DIALOG_TAG = "restore_dialog"
