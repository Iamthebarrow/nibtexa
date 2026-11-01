package io.github.iamthebarrow.nibtexa.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView

@Composable
fun CanvasScreen(modifier: Modifier = Modifier, viewModel: CanvasViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        Toolbar(
            state = state,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onClear = viewModel::clear,
            onFingerDrawsChange = viewModel::setFingerDraws,
        )
        CorpusBar(
            state = state,
            onPrevious = viewModel::previousPrompt,
            onNext = viewModel::nextPrompt,
            onSave = viewModel::saveSample,
        )
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context -> InkSurfaceView(context).apply { onStrokeCaptured = viewModel::onStrokeCaptured } },
                update = { view ->
                    view.fingerDraws = state.fingerDraws
                    view.setDocument(state.document)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Toolbar(
    state: CanvasUiState,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    onFingerDrawsChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(16.dp))
        TextButton(onClick = onUndo, enabled = state.canUndo) { Text(stringResource(R.string.undo)) }
        TextButton(onClick = onRedo, enabled = state.canRedo) { Text(stringResource(R.string.redo)) }
        TextButton(onClick = onClear, enabled = state.document.strokes.isNotEmpty()) { Text(stringResource(R.string.clear)) }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.toggleable(value = state.fingerDraws, role = Role.Switch, onValueChange = onFingerDrawsChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.finger_draws), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.fingerDraws, onCheckedChange = null)
        }
    }
}

@Composable
private fun CorpusBar(
    state: CanvasUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val prompt = state.prompt
        Column(Modifier.weight(1f)) {
            if (prompt == null) {
                Text(stringResource(R.string.no_prompts), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    stringResource(R.string.prompt_position, state.promptIndex + 1, state.prompts.size, prompt.category),
                    style = MaterialTheme.typography.labelMedium,
                )
                // The raw LaTeX is the prompt: write it as you naturally would.
                Text(prompt.latex, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedButton(onClick = onPrevious, enabled = state.prompts.isNotEmpty()) { Text(stringResource(R.string.previous)) }
        OutlinedButton(onClick = onNext, enabled = state.prompts.isNotEmpty()) { Text(stringResource(R.string.next)) }
        Button(onClick = onSave, enabled = state.canSave) { Text(stringResource(R.string.save_sample)) }
    }
}
