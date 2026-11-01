package io.github.iamthebarrow.nibtexa.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.sync.ChangeKind
import io.github.iamthebarrow.nibtexa.core.sync.ConflictKind
import io.github.iamthebarrow.nibtexa.core.sync.Diffs
import io.github.iamthebarrow.nibtexa.core.sync.Resolution
import io.github.iamthebarrow.nibtexa.core.sync.SettingsProblem

@Composable
fun SyncScreen(viewModel: SyncViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.sync_heading, state.bookletTitle), style = MaterialTheme.typography.headlineSmall)
        SettingsCard(state, viewModel)
        ReviewCard(state, viewModel)
        state.status?.let { Text(it, Modifier.testTag(TAG_STATUS), style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun SettingsCard(state: SyncUiState, viewModel: SyncViewModel) {
    // Edited locally; saved together. The token field is never filled from storage.
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    var token by remember { mutableStateOf("") }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sync_repository), style = MaterialTheme.typography.titleMedium)
            Field(R.string.sync_remote_url, draft.remoteUrl, TAG_REMOTE, Modifier.fillMaxWidth(), KeyboardType.Uri) { draft = draft.copy(remoteUrl = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(R.string.sync_username, draft.username, TAG_USERNAME, modifier = Modifier.weight(1f)) { draft = draft.copy(username = it) }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(if (state.hasToken) R.string.sync_token_saved else R.string.sync_token)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.weight(1f).testTag(TAG_TOKEN),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(R.string.sync_branch, draft.branch, TAG_BRANCH, modifier = Modifier.weight(1f)) { draft = draft.copy(branch = it) }
                Field(R.string.sync_destination, draft.destination, TAG_DESTINATION, modifier = Modifier.weight(1f)) { draft = draft.copy(destination = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(R.string.sync_author_name, draft.authorName, TAG_AUTHOR_NAME, modifier = Modifier.weight(1f)) { draft = draft.copy(authorName = it) }
                Field(R.string.sync_author_email, draft.authorEmail, TAG_AUTHOR_EMAIL, Modifier.weight(1f), KeyboardType.Email) { draft = draft.copy(authorEmail = it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = draft.includeSource, onCheckedChange = { draft = draft.copy(includeSource = it) })
                Text(stringResource(R.string.sync_include_source))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = draft.useFolders, onCheckedChange = { draft = draft.copy(useFolders = it) })
                Text(stringResource(R.string.sync_use_folders))
            }
            state.settingsProblems.forEach { Text(problemText(it), color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveSettings(draft, token); token = "" }, enabled = state.step == null) {
                    Text(stringResource(R.string.sync_save_settings))
                }
                if (state.hasToken) TextButton(onClick = viewModel::forgetToken, enabled = state.step == null) { Text(stringResource(R.string.sync_forget_token)) }
            }
        }
    }
}

@Composable
private fun Field(
    label: Int,
    value: String,
    tag: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(stringResource(label)) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    modifier = modifier.testTag(tag),
)

@Composable
private fun ReviewCard(state: SyncUiState, viewModel: SyncViewModel) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sync_changes), style = MaterialTheme.typography.titleMedium)
            val busy = state.step != null
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stepText(state.step!!))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.cancel)) }
                }
            }
            val pending = state.pending
            if (pending != null) {
                Text(stringResource(R.string.sync_pending, pending.id.take(7), pending.message))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.canRebase == true) {
                        Button(onClick = viewModel::rebaseAndPush, enabled = !busy) { Text(stringResource(R.string.sync_rebase_and_push)) }
                    } else if (state.canRebase == null) {
                        Button(onClick = viewModel::push, enabled = !busy) { Text(stringResource(R.string.sync_push)) }
                    }
                    OutlinedButton(onClick = viewModel::discardPending, enabled = !busy) { Text(stringResource(R.string.sync_discard)) }
                }
                return@Column
            }
            Button(onClick = viewModel::prepare, enabled = !busy) { Text(stringResource(R.string.sync_check)) }
            val plan = state.plan ?: return@Column
            if (plan.remoteAdvanced) Text(stringResource(R.string.sync_remote_moved))
            if (plan.conflicts.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.sync_conflicts), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                plan.conflicts.forEach { Text(stringResource(conflictText(it.kind), it.path)) }
                Resolution.entries.forEach { resolution ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = state.resolution == resolution, role = Role.RadioButton) { viewModel.setResolution(resolution) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.resolution == resolution, onClick = null)
                        Text(stringResource(resolutionText(resolution)))
                    }
                }
            }
            if (plan.kept.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.sync_kept, plan.kept.joinToString(", ")))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.releaseKept, onCheckedChange = viewModel::setReleaseKept)
                    Text(stringResource(R.string.sync_release_kept))
                }
            }
            HorizontalDivider()
            if (state.changes.isEmpty()) {
                Text(stringResource(R.string.sync_no_changes))
                return@Column
            }
            state.changes.forEach { change ->
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.showDiff(change.path.takeIf { it != state.diffPath }) }.padding(vertical = 4.dp),
                ) {
                    Text(kindMark(change.kind), Modifier.width(24.dp), fontFamily = FontFamily.Monospace)
                    Text(change.path, fontFamily = FontFamily.Monospace)
                }
                if (change.path == state.diffPath) {
                    val diff = remember(change) { Diffs.unified(change) }
                    Text(
                        diff ?: stringResource(R.string.sync_binary),
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 24.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedTextField(
                value = state.commitMessage,
                onValueChange = viewModel::setCommitMessage,
                label = { Text(stringResource(R.string.sync_commit_message)) },
                modifier = Modifier.fillMaxWidth().testTag(TAG_MESSAGE),
            )
            Button(onClick = viewModel::commitAndPush, enabled = !busy && state.commitMessage.isNotBlank()) {
                Text(stringResource(R.string.sync_commit_and_push))
            }
        }
    }
}

private fun kindMark(kind: ChangeKind) = when (kind) {
    ChangeKind.ADDED -> "A"
    ChangeKind.MODIFIED -> "M"
    ChangeKind.DELETED -> "D"
}

private fun conflictText(kind: ConflictKind) = when (kind) {
    ConflictKind.EDITED -> R.string.sync_conflict_edited
    ConflictKind.DELETED -> R.string.sync_conflict_deleted
    ConflictKind.NOT_OURS -> R.string.sync_conflict_not_ours
}

private fun resolutionText(resolution: Resolution) = when (resolution) {
    Resolution.KEEP -> R.string.sync_resolution_keep
    Resolution.REGENERATE -> R.string.sync_resolution_regenerate
    Resolution.COPY -> R.string.sync_resolution_copy
}

@Composable
private fun stepText(step: SyncStep) = stringResource(
    when (step) {
        SyncStep.CHECKING -> R.string.sync_step_checking
        SyncStep.PREPARING -> R.string.sync_step_preparing
        SyncStep.COMMITTING -> R.string.sync_step_committing
        SyncStep.PUSHING -> R.string.sync_step_pushing
        SyncStep.REBASING -> R.string.sync_step_rebasing
        SyncStep.DISCARDING -> R.string.sync_step_discarding
    },
)

@Composable
private fun problemText(problem: SettingsProblem) = when (problem) {
    SettingsProblem.MissingRemote -> stringResource(R.string.sync_problem_missing_remote)
    is SettingsProblem.UnsupportedScheme -> stringResource(R.string.sync_problem_scheme, problem.scheme)
    SettingsProblem.CredentialsInUrl -> stringResource(R.string.sync_problem_credentials_in_url)
    SettingsProblem.MalformedRemote -> stringResource(R.string.sync_problem_malformed_remote)
    SettingsProblem.InvalidBranch -> stringResource(R.string.sync_problem_branch)
    SettingsProblem.InvalidDestination -> stringResource(R.string.sync_problem_destination)
    SettingsProblem.MissingAuthor -> stringResource(R.string.sync_problem_author)
    SettingsProblem.InvalidEmail -> stringResource(R.string.sync_problem_email)
}


const val TAG_REMOTE = "sync_remote"
const val TAG_USERNAME = "sync_username"
const val TAG_TOKEN = "sync_token"
const val TAG_BRANCH = "sync_branch"
const val TAG_DESTINATION = "sync_destination"
const val TAG_AUTHOR_NAME = "sync_author_name"
const val TAG_AUTHOR_EMAIL = "sync_author_email"
const val TAG_MESSAGE = "sync_message"
const val TAG_STATUS = "sync_status"
