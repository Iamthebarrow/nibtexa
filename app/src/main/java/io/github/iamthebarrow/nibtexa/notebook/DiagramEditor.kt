package io.github.iamthebarrow.nibtexa.notebook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.Diagram
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramArrow
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramFacing
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramLabel
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramLine
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPart
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramShape
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramShapeType
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramSymbol
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.ui.theme.Nib

const val DIAGRAM_EDITOR_TAG = "diagram_editor"
const val DIAGRAM_DELETE_TAG = "diagram_delete_part"
const val DIAGRAM_LABEL_FIELD_TAG = "diagram_label_field"

/**
 * A diagram's controls, in the results panel (Phase 31): what its writing is read as, and the part chosen on the page. With the lasso,
 * while this is open, tapping a part chooses it and dragging one moves it, and the lines it holds follow.
 */
@Composable
fun DiagramEditor(state: NotebookUiState, region: Region, viewModel: NotebookViewModel) {
    val diagram = region.diagram ?: return
    val p = Nib.palette
    Column(Modifier.testTag(DIAGRAM_EDITOR_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.diagram_gestures), style = MaterialTheme.typography.bodySmall, color = p.muted)
        Choices(stringResource(R.string.diagram_reads_as)) {
            FilterChip(selected = !state.diagramWords, onClick = { viewModel.setDiagramWords(false) }, label = { Text(stringResource(R.string.diagram_reads_maths)) })
            FilterChip(selected = state.diagramWords, onClick = { viewModel.setDiagramWords(true) }, label = { Text(stringResource(R.string.diagram_reads_words)) })
        }
        when (val part = state.diagramPart) {
            is DiagramPart.Shape -> diagram.shape(part.id)?.let { ShapeControls(region, it, viewModel) }
            is DiagramPart.Line -> diagram.line(part.id)?.let { LineControls(region, it, viewModel) }
            is DiagramPart.Label -> diagram.label(part.id)?.let { LabelControls(region, it, viewModel) }
            null -> Unit
        }
        val chosen = state.diagramPart
        if (chosen != null && partExists(diagram, chosen)) {
            OutlinedButton(onClick = { viewModel.deleteDiagramPart(region.id, chosen) }, modifier = Modifier.testTag(DIAGRAM_DELETE_TAG)) { Text(stringResource(R.string.diagram_delete_part)) }
        }
    }
}

private fun partExists(diagram: Diagram, part: DiagramPart) = when (part) {
    is DiagramPart.Shape -> diagram.shape(part.id) != null
    is DiagramPart.Line -> diagram.line(part.id) != null
    is DiagramPart.Label -> diagram.label(part.id) != null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun ShapeControls(region: Region, shape: DiagramShape, viewModel: NotebookViewModel) {
    fun change(to: (DiagramShape) -> DiagramShape) = viewModel.updateDiagram(region.id) { d -> d.copy(shapes = d.shapes.map { if (it.id == shape.id) to(it) else it }) }
    Choices(stringResource(R.string.diagram_shape)) {
        DiagramShapeType.entries.forEach { type ->
            FilterChip(selected = shape.type == type, onClick = { change { it.copy(type = type) } }, label = { Text(stringResource(shapeName(type))) })
        }
    }
    if (shape.type == DiagramShapeType.TRIANGLE || shape.type == DiagramShapeType.OP_AMP) {
        Choices(stringResource(R.string.diagram_facing)) {
            DiagramFacing.entries.forEach { facing ->
                FilterChip(selected = shape.facing == facing, onClick = { change { it.copy(facing = facing) } }, label = { Text(stringResource(facingName(facing))) })
            }
        }
    }
}

@Composable
private fun LineControls(region: Region, line: DiagramLine, viewModel: NotebookViewModel) {
    fun change(to: (DiagramLine) -> DiagramLine) = viewModel.updateDiagram(region.id) { d -> d.copy(lines = d.lines.map { if (it.id == line.id) to(it) else it }) }
    Choices(stringResource(R.string.diagram_line)) {
        FilterChip(selected = line.arrowStart, onClick = { change { it.copy(arrowStart = !it.arrowStart) } }, label = { Text(stringResource(R.string.diagram_arrow_start)) })
        FilterChip(selected = line.arrows.isNotEmpty(), onClick = { change { it.copy(arrows = if (it.arrows.isEmpty()) listOf(DiagramArrow(0.5f)) else emptyList()) } }, label = { Text(stringResource(R.string.diagram_arrow_middle)) })
        FilterChip(selected = line.arrowEnd, onClick = { change { it.copy(arrowEnd = !it.arrowEnd) } }, label = { Text(stringResource(R.string.diagram_arrow_end)) })
        FilterChip(selected = line.dashed, onClick = { change { it.copy(dashed = !it.dashed) } }, label = { Text(stringResource(R.string.diagram_dashed)) })
    }
    Choices(stringResource(R.string.diagram_symbol)) {
        DiagramSymbol.entries.forEach { symbol ->
            FilterChip(selected = line.symbol == symbol, onClick = { change { it.copy(symbol = symbol) } }, label = { Text(stringResource(symbolName(symbol))) })
        }
    }
}

@Composable
private fun LabelControls(region: Region, label: DiagramLabel, viewModel: NotebookViewModel) {
    val latex = label.latex
    val text = label.text
    if (latex != null) {
        var current by remember(region.id, label.id, latex) { mutableStateOf(latex) }
        MathEditor("diagram-${region.id}-${label.id}", latex, onLatexChange = { current = it }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
        Button(onClick = { viewModel.setDiagramLabel(region.id, label.id, latex = current.trim(), text = null) }, enabled = current.isNotBlank()) { Text(stringResource(R.string.confirm)) }
    } else if (text != null) {
        var current by remember(region.id, label.id, text) { mutableStateOf(text) }
        OutlinedTextField(value = current, onValueChange = { current = it }, singleLine = true, label = { Text(stringResource(R.string.diagram_label)) }, modifier = Modifier.fillMaxWidth().testTag(DIAGRAM_LABEL_FIELD_TAG))
        Button(onClick = { viewModel.setDiagramLabel(region.id, label.id, latex = null, text = current.trim()) }, enabled = current.isNotBlank()) { Text(stringResource(R.string.confirm)) }
    } else {
        Text(stringResource(if (label.unread) R.string.diagram_label_reading else R.string.diagram_label_handwritten), style = MaterialTheme.typography.bodySmall)
    }
    if (label.ink.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.readDiagramLabel(region.id, label.id, words = false) }) { Text(stringResource(R.string.diagram_read_maths)) }
            OutlinedButton(onClick = { viewModel.readDiagramLabel(region.id, label.id, words = true) }) { Text(stringResource(R.string.diagram_read_words)) }
        }
        if (!label.handwritten) {
            OutlinedButton(onClick = { viewModel.setDiagramLabel(region.id, label.id, latex = null, text = null) }) { Text(stringResource(R.string.diagram_use_handwriting)) }
        }
    }
}

private fun shapeName(type: DiagramShapeType) = when (type) {
    DiagramShapeType.BOX -> R.string.diagram_shape_box
    DiagramShapeType.CIRCLE -> R.string.diagram_shape_circle
    DiagramShapeType.CROSS -> R.string.diagram_shape_cross
    DiagramShapeType.PLUS -> R.string.diagram_shape_plus
    DiagramShapeType.ELLIPSE -> R.string.diagram_shape_ellipse
    DiagramShapeType.TRIANGLE -> R.string.diagram_shape_triangle
    DiagramShapeType.DOT -> R.string.diagram_shape_dot
    DiagramShapeType.GROUND -> R.string.diagram_shape_ground
    DiagramShapeType.OP_AMP -> R.string.diagram_shape_op_amp
}

private fun facingName(facing: DiagramFacing) = when (facing) {
    DiagramFacing.RIGHT -> R.string.diagram_facing_right
    DiagramFacing.LEFT -> R.string.diagram_facing_left
    DiagramFacing.UP -> R.string.diagram_facing_up
    DiagramFacing.DOWN -> R.string.diagram_facing_down
}

private fun symbolName(symbol: DiagramSymbol) = when (symbol) {
    DiagramSymbol.NONE -> R.string.diagram_symbol_none
    DiagramSymbol.RESISTOR -> R.string.diagram_symbol_resistor
    DiagramSymbol.CAPACITOR -> R.string.diagram_symbol_capacitor
    DiagramSymbol.INDUCTOR -> R.string.diagram_symbol_inductor
    DiagramSymbol.VOLTAGE_SOURCE -> R.string.diagram_symbol_voltage
    DiagramSymbol.CURRENT_SOURCE -> R.string.diagram_symbol_current
    DiagramSymbol.DIODE -> R.string.diagram_symbol_diode
    DiagramSymbol.SWITCH -> R.string.diagram_symbol_switch
}
