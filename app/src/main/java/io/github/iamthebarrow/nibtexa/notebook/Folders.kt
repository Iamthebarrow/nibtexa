package io.github.iamthebarrow.nibtexa.notebook

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.export.Backup
import io.github.iamthebarrow.nibtexa.storage.FolderSummary
import io.github.iamthebarrow.nibtexa.storage.NotebookDatabase
import io.github.iamthebarrow.nibtexa.ui.theme.Nib
import io.github.iamthebarrow.nibtexa.ui.theme.NibIcons

/** A folder in the library (Phase 27), the size of a booklet's card. Tap to open; long-press to change or delete it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(folder: FolderSummary, viewModel: NotebookViewModel, modifier: Modifier = Modifier) {
    val p = Nib.palette
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val count = pluralStringResource(R.plurals.booklet_count, folder.bookletCount, folder.bookletCount)
    val description = stringResource(R.string.folder_card, folder.name, count)
    Box(modifier) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp))
                .combinedClickable(onClick = { viewModel.openFolder(folder.id) }, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.more))
                .semantics { contentDescription = description }
                .padding(4.dp)
                .testTag(folderTag(folder.id)),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT).clip(RoundedCornerShape(16.dp)).background(p.cover(folder.cover)), contentAlignment = Alignment.Center) {
                Icon(NibIcons.Folder, contentDescription = null, tint = p.ink, modifier = Modifier.size(72.dp))
            }
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(count, style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.folder_edit)) }, onClick = { menu = false; editing = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.folder_delete), color = p.error) }, onClick = { menu = false; deleting = true })
        }
    }
    if (editing) {
        FolderDialog(
            title = stringResource(R.string.folder_edit_title),
            initialName = folder.name,
            initialCover = folder.cover,
            onDone = { name, cover -> editing = false; viewModel.updateFolder(folder.id, name, cover) },
            onDismiss = { editing = false },
        )
    }
    if (deleting) DeleteFolderDialog(folder, viewModel, onDismiss = { deleting = false })
}

/** A folder's name and cover colour, for a new one or to change one. */
@Composable
fun FolderDialog(title: String, initialName: String, initialCover: Int, onDone: (String, Int) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var cover by remember { mutableIntStateOf(initialCover) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Backup.MAX_FOLDER_NAME) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.folder_name)) },
                    modifier = Modifier.testTag(FOLDER_NAME_TAG),
                )
                Text(stringResource(R.string.cover_colour), style = MaterialTheme.typography.labelLarge)
                CoverSwatches(cover) { cover = it }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(name, cover) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Deleting a folder asks what happens to its booklets. An empty one only asks to confirm. */
@Composable
private fun DeleteFolderDialog(folder: FolderSummary, viewModel: NotebookViewModel, onDismiss: () -> Unit) {
    val p = Nib.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_delete_title, folder.name)) },
        text = {
            Text(
                if (folder.bookletCount == 0) stringResource(R.string.folder_delete_empty)
                else pluralStringResource(R.plurals.folder_delete_text, folder.bookletCount, folder.bookletCount),
            )
        },
        confirmButton = {
            Row {
                if (folder.bookletCount > 0) TextButton(onClick = { onDismiss(); viewModel.deleteFolder(folder.id, trash = true) }) { Text(stringResource(R.string.folder_delete_trash), color = p.error) }
                TextButton(onClick = { onDismiss(); viewModel.deleteFolder(folder.id, trash = false) }) {
                    Text(if (folder.bookletCount > 0) stringResource(R.string.folder_delete_keep) else stringResource(R.string.delete))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Where to put a booklet: the top level, a folder, or a new folder. */
@Composable
fun MoveToFolderDialog(state: NotebookUiState, bookletId: String, viewModel: NotebookViewModel, onDismiss: () -> Unit) {
    val p = Nib.palette
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        FolderDialog(
            title = stringResource(R.string.folder_move_new),
            initialName = "",
            initialCover = state.folders.size % NotebookDatabase.COVER_COUNT,
            onDone = { name, cover -> creating = false; onDismiss(); viewModel.createFolder(name, cover, bookletId) },
            onDismiss = { creating = false },
        )
        return
    }
    val current = state.library.firstOrNull { it.id == bookletId }?.folderId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_move_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag(FOLDER_MOVE_TAG)) {
                val choose = { id: String? -> onDismiss(); viewModel.moveToFolder(bookletId, id) }
                TextButton(onClick = { choose(null) }, enabled = current != null) { Text(stringResource(R.string.folder_top_level)) }
                state.folders.forEach { folder ->
                    TextButton(onClick = { choose(folder.id) }, enabled = current != folder.id) {
                        Icon(NibIcons.Folder, contentDescription = null, tint = p.cover(folder.cover), modifier = Modifier.padding(end = 8.dp).size(20.dp))
                        Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = { creating = true }) { Text(stringResource(R.string.folder_new)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

fun folderTag(id: String) = "folder_$id"

const val FOLDER_NAME_TAG = "folder_name"
const val FOLDER_MOVE_TAG = "folder_move"
private const val COVER_ASPECT = 0.78f
