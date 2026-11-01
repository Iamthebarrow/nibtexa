package io.github.iamthebarrow.nibtexa.notebook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.capture.PenButtonAction
import io.github.iamthebarrow.nibtexa.core.model.capture.PenButtons
import io.github.iamthebarrow.nibtexa.licences.LicencesActivity
import io.github.iamthebarrow.nibtexa.ui.theme.Nib
import io.github.iamthebarrow.nibtexa.ui.theme.NibIcons

const val SETTINGS_TAG = "settings"

/**
 * What applies to the whole app, not to one booklet (Phase 29): the same from the library and from an open booklet, so that
 * neither menu has to hold it.
 */
@Composable
fun SettingsPanel(state: NotebookUiState, viewModel: NotebookViewModel, onDismiss: () -> Unit) {
    val p = Nib.palette
    val context = LocalContext.current
    var authorDialog by remember { mutableStateOf(false) }
    var modelTerms by remember { mutableStateOf(false) }
    if (modelTerms) {
        ModelTermsDialog(
            tightMemoryGb = viewModel.tightMemoryForModel(),
            onDownload = {
                modelTerms = false
                viewModel.downloadModel()
            },
            onDismiss = { modelTerms = false },
        )
    }
    var choosingButton by remember { mutableStateOf<PenButtonChoice?>(null) }
    choosingButton?.let { choice ->
        PenButtonDialog(
            title = stringResource(choice.title),
            current = choice.current(state.penButtons),
            onChoose = {
                viewModel.setPenButtons(choice.with(state.penButtons, it))
                choosingButton = null
            },
            onDismiss = { choosingButton = null },
        )
    }
    if (state.askModelMobile) {
        AlertDialog(
            onDismissRequest = viewModel::cancelModelMobile,
            title = { Text(stringResource(R.string.model_download)) },
            text = { Text(stringResource(R.string.model_download_mobile)) },
            confirmButton = { TextButton(onClick = { viewModel.downloadModel(allowMobileData = true) }) { Text(stringResource(R.string.model_download_go)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelModelMobile) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val pickBackupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) viewModel.setAutoBackupFolder(tree)
    }
    val pickModelFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let(viewModel::importModel)
    }
    if (authorDialog) {
        var name by remember { mutableStateOf(state.appAuthor) }
        AlertDialog(
            onDismissRequest = { authorDialog = false },
            title = { Text(stringResource(R.string.author_app_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.author_app_text), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.author_name)) })
                }
            },
            confirmButton = { TextButton(onClick = { authorDialog = false; viewModel.setAppAuthor(name) }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { authorDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(24.dp)).background(p.surface).padding(20.dp).testTag(SETTINGS_TAG)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, color = p.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(NibIcons.Close, contentDescription = stringResource(R.string.close), tint = p.ink) }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingRow(stringResource(R.string.author_name_menu), state.appAuthor.ifEmpty { null }) { authorDialog = true }
                SettingSwitch(stringResource(R.string.auto_regions), state.autoRegions, viewModel::setAutoRegions)
                SettingSwitch(stringResource(R.string.snap_shapes), state.snapShapes, viewModel::setSnapShapes)
                SettingSwitch(stringResource(R.string.clipboard_system), state.systemClipboard, viewModel::setSystemClipboard)
                PenButtonChoice.entries.forEach { choice ->
                    SettingRow(stringResource(choice.title), stringResource(penButtonLabel(choice.current(state.penButtons)))) { choosingButton = choice }
                }
                val downloading = state.modelDownload
                when {
                    downloading != null -> SettingRow(stringResource(R.string.model_download_running, downloading), stringResource(R.string.model_download_stop)) { viewModel.stopModelDownload() }
                    state.modelInstalled -> SettingRow(stringResource(R.string.model_download_installed), enabled = false) {}
                    else -> SettingRow(stringResource(R.string.model_download), stringResource(R.string.model_download_size)) { modelTerms = true }
                }
                SettingRow(stringResource(R.string.model_import)) { pickModelFolder.launch(null) }
                SettingRow(
                    stringResource(if (state.handwritingReady == true) R.string.handwriting_downloaded else R.string.handwriting_download),
                    enabled = state.handwritingReady != true && !state.downloadingHandwriting,
                ) { viewModel.downloadHandwriting() }
                val folder = state.autoBackupFolder
                if (folder == null) {
                    SettingRow(stringResource(R.string.auto_backup_on), stringResource(R.string.auto_backup_about)) { pickBackupFolder.launch(null) }
                } else {
                    val last = state.lastAutoBackup.takeIf { it > 0L }?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) }
                    SettingRow(stringResource(R.string.auto_backup_off), last?.let { stringResource(R.string.auto_backup_last, it) } ?: stringResource(R.string.auto_backup_pending)) { viewModel.setAutoBackupFolder(null) }
                }
                SettingRow(stringResource(R.string.licences_title)) { context.startActivity(LicencesActivity.intent(context)) }
            }
        }
    }
}

/** The source of the math model and its terms, with a warning first if it would be heavy for this tablet (Phase 34). */
@Composable
internal fun ModelTermsDialog(tightMemoryGb: Float?, onDownload: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_download)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tightMemoryGb != null) {
                    Text(stringResource(R.string.model_download_low_memory, tightMemoryGb), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.model_download_terms, io.github.iamthebarrow.nibtexa.recognition.ModelDownloader.SOURCE))
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text(stringResource(R.string.model_download_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The two rows for a pen's buttons (Phase 34). */
private enum class PenButtonChoice(val title: Int, val current: (PenButtons) -> PenButtonAction, val with: (PenButtons, PenButtonAction) -> PenButtons) {
    MAIN(R.string.pen_button_main, PenButtons::main, { buttons, action -> buttons.copy(main = action) }),
    SECOND(R.string.pen_button_second, PenButtons::second, { buttons, action -> buttons.copy(second = action) }),
}

private fun penButtonLabel(action: PenButtonAction) = when (action) {
    PenButtonAction.ERASER -> R.string.pen_button_eraser
    PenButtonAction.LASSO -> R.string.pen_button_lasso
    PenButtonAction.MOVE -> R.string.pen_button_move
    PenButtonAction.SCRATCH -> R.string.pen_button_scratch
    PenButtonAction.NOTHING -> R.string.pen_button_nothing
}

@Composable
private fun PenButtonDialog(title: String, current: PenButtonAction, onChoose: (PenButtonAction) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(stringResource(R.string.pen_button_about), style = MaterialTheme.typography.bodyMedium)
                PenButtonAction.entries.forEach { action ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChoose(action) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = action == current, onClick = null, modifier = Modifier.padding(end = 12.dp))
                        Text(stringResource(penButtonLabel(action)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SettingRow(title: String, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Nib.palette
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) p.ink else p.muted)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = p.muted)
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Nib.palette.ink, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
