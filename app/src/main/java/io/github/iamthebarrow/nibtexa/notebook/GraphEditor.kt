package io.github.iamthebarrow.nibtexa.notebook

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.export.CurveProblem
import io.github.iamthebarrow.nibtexa.core.export.GraphPlot
import io.github.iamthebarrow.nibtexa.core.export.GraphText
import io.github.iamthebarrow.nibtexa.core.maths.MathsProblem
import io.github.iamthebarrow.nibtexa.core.model.notebook.Graph
import io.github.iamthebarrow.nibtexa.core.model.notebook.GraphColors
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.ink.GraphRenderer
import io.github.iamthebarrow.nibtexa.ui.theme.Nib
import io.github.iamthebarrow.nibtexa.ui.theme.NibIcons

const val GRAPH_EDITOR_TAG = "graph_editor"
const val GRAPH_ADD_TAG = "graph_add_curve"

/** Why the engine can't graph something, in the user's terms. */
fun mathsProblemText(resources: Resources, problem: MathsProblem): String = when (problem) {
    is MathsProblem.NotUnderstood -> resources.getString(R.string.graph_not_understood, problem.what)
    is MathsProblem.Malformed -> resources.getString(R.string.graph_malformed)
    MathsProblem.Implicit -> resources.getString(R.string.graph_implicit)
    is MathsProblem.AmbiguousInput -> resources.getString(R.string.graph_choose_axis)
    MathsProblem.SeveralEquals -> resources.getString(R.string.graph_several_equals)
    MathsProblem.TooComplex -> resources.getString(R.string.graph_too_complex)
}

/**
 * A graph's controls, in the results panel (Phase 25): its curves, and the values, axes and labels that go with them.
 * While this is open, dragging or pinching inside the graph moves or zooms it; touches anywhere else act on the page.
 */
@Composable
fun GraphEditor(state: NotebookUiState, region: Region, viewModel: NotebookViewModel) {
    val graph = region.graph ?: return
    val p = Nib.palette
    val resources = LocalResources.current
    val plotted = remember(graph) { GraphPlot.of(graph, points = 2) }
    Column(Modifier.testTag(GRAPH_EDITOR_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.graph_gestures), style = MaterialTheme.typography.bodySmall, color = p.muted)
        plotted.curves.forEach { curve ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val colorName = stringResource(R.string.graph_change_color)
                    Box(
                        Modifier.size(22.dp).background(Color(GraphRenderer.rgb(curve.curve.color)), CircleShape)
                            .clickable { viewModel.updateCurve(region.id, curve.index) { it.copy(color = (it.color + 1).mod(GraphColors.RGB.size)) } }
                            .semantics { contentDescription = colorName },
                    )
                    Text(curve.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    val showName = stringResource(R.string.graph_show_curve, curve.label)
                    Switch(
                        checked = !curve.curve.hidden,
                        onCheckedChange = { shown -> viewModel.updateCurve(region.id, curve.index) { it.copy(hidden = !shown) } },
                        modifier = Modifier.semantics { contentDescription = showName },
                    )
                    IconButton(onClick = { viewModel.removeCurve(region.id, curve.index) }) {
                        Icon(NibIcons.Delete, contentDescription = stringResource(R.string.graph_remove_curve, curve.label), tint = p.muted)
                    }
                }
                when (val problem = curve.problem) {
                    is CurveProblem.CantRead -> {
                        Text(mathsProblemText(resources, problem.problem), style = MaterialTheme.typography.bodySmall, color = p.error)
                        (problem.problem as? MathsProblem.AmbiguousInput)?.let { ambiguous ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ambiguous.names.forEach { name ->
                                    FilterChip(selected = false, onClick = { viewModel.updateCurve(region.id, curve.index) { it.copy(input = name) } }, label = { Text(GraphText.plain(name)) })
                                }
                            }
                        }
                    }
                    is CurveProblem.NeedsValues -> Text(stringResource(R.string.graph_needs_values, problem.names.joinToString { GraphText.plain(it) }), style = MaterialTheme.typography.bodySmall, color = p.error)
                    null -> Unit
                }
            }
        }
        AddCurve(state, region, graph, viewModel)

        // Every letter that needs a value, once, and those already given.
        val names = (plotted.curves.flatMap { it.equation?.parameters.orEmpty() } + graph.constants.keys).distinct()
        if (names.isNotEmpty()) {
            Text(stringResource(R.string.graph_values), style = MaterialTheme.typography.labelLarge, color = p.muted)
            names.forEach { name ->
                NumberField(GraphText.plain(name), graph.constants[name], Modifier.fillMaxWidth()) { value -> viewModel.setGraphConstant(region.id, name, value) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !graph.degrees, onClick = { viewModel.updateGraph(region.id) { it.copy(degrees = false) } }, label = { Text(stringResource(R.string.graph_radians)) })
            FilterChip(selected = graph.degrees, onClick = { viewModel.updateGraph(region.id) { it.copy(degrees = true) } }, label = { Text(stringResource(R.string.graph_degrees)) })
        }

        Text(stringResource(R.string.graph_range), style = MaterialTheme.typography.labelLarge, color = p.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.graph_x_min), graph.xMin, Modifier.weight(1f)) { v -> v?.let { x -> viewModel.updateGraph(region.id) { it.withRange(x, it.xMax, it.yMin, it.yMax) } } }
            NumberField(stringResource(R.string.graph_x_max), graph.xMax, Modifier.weight(1f)) { v -> v?.let { x -> viewModel.updateGraph(region.id) { it.withRange(it.xMin, x, it.yMin, it.yMax) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.graph_y_min), graph.yMin, Modifier.weight(1f)) { v -> v?.let { y -> viewModel.updateGraph(region.id) { it.withRange(it.xMin, it.xMax, y, it.yMax) } } }
            NumberField(stringResource(R.string.graph_y_max), graph.yMax, Modifier.weight(1f)) { v -> v?.let { y -> viewModel.updateGraph(region.id) { it.withRange(it.xMin, it.xMax, it.yMin, y) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.updateGraph(region.id, GraphPlot::fitted) }) { Text(stringResource(R.string.graph_fit)) }
            TextButton(onClick = {
                viewModel.updateGraph(region.id) { GraphPlot.fitted(it.withRange(-Graph.DEFAULT_RANGE, Graph.DEFAULT_RANGE, it.yMin, it.yMax)) }
            }) { Text(stringResource(R.string.graph_reset)) }
        }

        Text(stringResource(R.string.graph_labels), style = MaterialTheme.typography.labelLarge, color = p.muted)
        LabelField(stringResource(R.string.graph_title), graph.title) { v -> viewModel.updateGraph(region.id) { it.copy(title = v) } }
        LabelField(stringResource(R.string.graph_x_label), graph.xLabel) { v -> viewModel.updateGraph(region.id) { it.copy(xLabel = v) } }
        LabelField(stringResource(R.string.graph_y_label), graph.yLabel) { v -> viewModel.updateGraph(region.id) { it.copy(yLabel = v) } }
    }
}

/** Adds a confirmed equation from anywhere in the booklet that isn't on the graph yet. */
@Composable
private fun AddCurve(state: NotebookUiState, region: Region, graph: Graph, viewModel: NotebookViewModel) {
    var open by remember { mutableStateOf(false) }
    val offered = viewModel.equationsToGraph(state, graph)
    Box {
        TextButton(
            onClick = { open = true },
            enabled = offered.isNotEmpty() && graph.curves.size < Graph.MAX_CURVES,
            modifier = Modifier.testTag(GRAPH_ADD_TAG),
        ) { Text(stringResource(R.string.graph_add_curve)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            offered.forEach { equation ->
                DropdownMenuItem(
                    text = { Text(GraphText.plain(equation.latex)) },
                    onClick = {
                        open = false
                        viewModel.addCurve(region.id, equation)
                    },
                )
            }
        }
    }
}

/** A value kept while being typed, and given when it's finished: with Done, or on leaving the field. */
@Composable
private fun NumberField(label: String, value: Double?, modifier: Modifier = Modifier, onValue: (Double?) -> Unit) {
    val shown = value?.let(::numberText).orEmpty()
    var text by remember(shown) { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        if (text != shown) onValue(text.trim().toDoubleOrNull()?.takeIf { it.isFinite() })
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        isError = text.isNotBlank() && text.trim().toDoubleOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused) commit()
            focused = it.isFocused
        }.testTag("graph_number_$label"),
    )
}

@Composable
private fun LabelField(label: String, value: String?, onValue: (String?) -> Unit) {
    val shown = value.orEmpty()
    var text by remember(shown) { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        if (text != shown) onValue(text.trim().take(MAX_LABEL).takeIf { it.isNotEmpty() })
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).onFocusChanged {
            if (focused && !it.isFocused) commit()
            focused = it.isFocused
        },
    )
}

/** A number as short as it can be written: `2`, not `2.0`. */
private fun numberText(value: Double): String = if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

private const val MAX_LABEL = 80
