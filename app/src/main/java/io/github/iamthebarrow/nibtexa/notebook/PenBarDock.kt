package io.github.iamthebarrow.nibtexa.notebook

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.EraserMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.EraserSize
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.PenKind
import io.github.iamthebarrow.nibtexa.ui.theme.Nib
import io.github.iamthebarrow.nibtexa.ui.theme.NibIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The pen bar over the page, open or folded, at one of the [PenDock] places (Phase 14). Drag it
 * by its grip (or the folded button) and let go: it springs to the nearest place, turning into a
 * column on the left and right edges. It never sits just anywhere: only at the docks, and never
 * over the Results and page buttons at the bottom right. A dock lifts the bar clear of them, and a
 * dock with no room left (a column on a short screen) is not offered. Screen readers get "Move the
 * pens to …" actions instead of the drag.
 *
 * [modifier] fills the area the bar may use (the page, less the results panel when it is open).
 */
@Composable
internal fun PenBarDock(state: NotebookUiState, viewModel: NotebookViewModel, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val area = IntSize(constraints.maxWidth, constraints.maxHeight)
        val margin = with(density) { DOCK_MARGIN.dp.toPx() }
        val reserve = with(density) { BOTTOM_END_RESERVE.dp.toPx() }
        val reserveHeight = with(density) { BOTTOM_END_HEIGHT.dp.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        // The open bar's size as a row and as a column, once each has been seen.
        var rowSize by remember { mutableStateOf<IntSize?>(null) }
        var columnSize by remember { mutableStateOf<IntSize?>(null) }
        val position = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        var dragging by remember { mutableStateOf(false) }
        var placed by remember { mutableStateOf(false) }
        // Bumped to settle the bar back at its dock (a drop on the same dock changes nothing else).
        var settle by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()

        /**
         * Where a bar of [s] goes at [d] (its top-left corner), inside the area and clear of the
         * buttons at the bottom right; null if it can't be clear of them there.
         */
        fun anchor(d: PenDock, s: IntSize): Offset? {
            val w = s.width.toFloat()
            val h = s.height.toFloat()
            val x = when (d) {
                PenDock.TOP_START, PenDock.LEFT, PenDock.BOTTOM_START -> margin
                PenDock.TOP_CENTER -> (area.width - w) / 2
                PenDock.BOTTOM_CENTER -> ((area.width - reserve - w) / 2).coerceAtLeast(margin)
                PenDock.TOP_END, PenDock.RIGHT -> area.width - margin - w
            }
            val y = when (d) {
                PenDock.TOP_START, PenDock.TOP_CENTER, PenDock.TOP_END -> margin
                PenDock.LEFT, PenDock.RIGHT -> (area.height - h) / 2
                PenDock.BOTTOM_START, PenDock.BOTTOM_CENTER -> area.height - margin - h
            }
            val left = x.coerceIn(0f, (area.width - w).coerceAtLeast(0f))
            var top = y.coerceIn(0f, (area.height - h).coerceAtLeast(0f))
            if (left + w > area.width - reserve && top + h > area.height - reserveHeight) {
                top = area.height - reserveHeight - margin - h
                if (top < 0f) return null
            }
            return Offset(left, top)
        }

        val folded = state.penBarHidden

        /**
         * The bar's size at [d]: a column on a side, a row elsewhere, each as last measured from the
         * bar itself (not while it animates), or estimated from the other. Folded, it is the button.
         */
        fun sizeAt(d: PenDock): IntSize = when {
            folded -> size
            d.vertical -> columnSize ?: rowSize?.let { IntSize((it.height * 1.1f).toInt(), (it.width * 0.8f).toInt()) } ?: size
            else -> rowSize ?: columnSize?.let { IntSize((it.height * 1.25f).toInt(), (it.width * 0.9f).toInt()) } ?: size
        }

        // The stored dock, or the first with room if it has none (a smaller window).
        // Where the finger is, in the page area: it moves on even where the bar stops at an edge.
        var finger by remember { mutableStateOf(Offset.Zero) }

        // Checked at the size the bar has there (a column on a side), so a dock that does not fit a
        // column cannot flip the bar between a row and a column for ever.
        val dock = state.penDock.takeIf { size == IntSize.Zero || anchor(it, sizeAt(it)) != null }
            ?: PenDock.entries.firstOrNull { anchor(it, sizeAt(it)) != null } ?: PenDock.TOP_CENTER

        LaunchedEffect(dock, folded, area, size, settle) {
            if (size == IntSize.Zero || dragging) return@LaunchedEffect
            val target = anchor(dock, size) ?: return@LaunchedEffect
            if (!placed) {
                position.snapTo(target)
                placed = true
            } else {
                position.animateTo(target, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
            }
        }

        /**
         * The dock where the finger let go (Phase 19): the one whose place is nearest the finger,
         * then nearest its middle. Going by the finger, not the bar's middle, lets a long bar reach a
         * side. A row turning into a column (or back) swaps its size.
         */
        fun nearest(): PenDock {
            val at = finger
            // Let go within its own place, it stays (long bars' places can overlap).
            anchor(dock, size)?.let { a -> if (at.x in a.x..a.x + size.width && at.y in a.y..a.y + size.height) return dock }
            return PenDock.entries.mapNotNull { d ->
                val s = sizeAt(d)
                anchor(d, s)?.let { a ->
                    val dx = maxOf(a.x - at.x, 0f, at.x - (a.x + s.width))
                    val dy = maxOf(a.y - at.y, 0f, at.y - (a.y + s.height))
                    val middle = (a + Offset(s.width / 2f, s.height / 2f) - at).getDistanceSquared()
                    Triple(d, dx * dx + dy * dy, middle)
                }
            }.minWithOrNull(compareBy({ it.second }, { it.third }))?.first ?: dock
        }

        // Restarted when what it measures against changes, which is only ever between drags.
        val drag = Modifier.pointerInput(dock, state.penDock, area, folded) {
            detectDragGestures(
                onDragStart = { start ->
                    dragging = true
                    // The grip is at the bar's start, so its local position is nearly the bar's.
                    finger = position.value + start
                },
                onDragEnd = {
                    dragging = false
                    val target = nearest()
                    if (target != dock) viewModel.setPenDock(target)
                    settle++
                },
                onDragCancel = {
                    dragging = false
                    settle++
                },
                onDrag = { change, amount ->
                    change.consume()
                    finger += amount
                    scope.launch {
                        val max = Offset((area.width - size.width).toFloat().coerceAtLeast(0f), (area.height - size.height).toFloat().coerceAtLeast(0f))
                        val next = position.value + amount
                        position.snapTo(Offset(next.x.coerceIn(0f, max.x), next.y.coerceIn(0f, max.y)))
                    }
                },
            )
        }

        val moveLabel = stringResource(R.string.pen_move)
        val moves = PenDock.entries.filter { it != dock && anchor(it, sizeAt(it)) != null }.map { d ->
            CustomAccessibilityAction(stringResource(R.string.pen_move_to, dockName(d))) { viewModel.setPenDock(d); true }
        }

        Box(
            Modifier
                .offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) }
                .alpha(if (placed) 1f else 0f)
                .onSizeChanged { size = it },
        ) {
            AnimatedContent(
                targetState = folded to dock.vertical,
                transitionSpec = {
                    (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f))
                        .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)) + scaleOut(targetScale = 0.6f))
                },
                label = "pen bar",
            ) { (isFolded, vertical) ->
                if (isFolded) {
                    FoldedPenButton(state, viewModel, drag.semantics { customActions = moves })
                } else {
                    Box(Modifier.onSizeChanged { if (vertical) columnSize = it else rowSize = it }) {
                        PenBarContent(state, viewModel, vertical, grip = drag.semantics { contentDescription = moveLabel; customActions = moves })
                    }
                }
            }
        }
    }
}

@Composable
private fun dockName(dock: PenDock): String = stringResource(
    when (dock) {
        PenDock.TOP_START -> R.string.dock_top_start
        PenDock.TOP_CENTER -> R.string.dock_top_center
        PenDock.TOP_END -> R.string.dock_top_end
        PenDock.LEFT -> R.string.dock_left
        PenDock.RIGHT -> R.string.dock_right
        PenDock.BOTTOM_START -> R.string.dock_bottom_start
        PenDock.BOTTOM_CENTER -> R.string.dock_bottom_center
    },
)

/**
 * The grip, the four pens in their colours, the eraser and the lasso, finger drawing, and Fold.
 * [grip] goes on the grip only (it carries the drag), not on the bar.
 */
@SuppressLint("ModifierParameter")
@Composable
private fun PenBarContent(state: NotebookUiState, viewModel: NotebookViewModel, vertical: Boolean, grip: Modifier) {
    val p = Nib.palette
    val shape = RoundedCornerShape(32.dp)
    val frame = Modifier.shadow(10.dp, shape).clip(shape).background(p.surface).testTag(PEN_BAR_TAG)

    @Composable
    fun Divider() = Box(if (vertical) Modifier.padding(vertical = 6.dp).height(1.dp).width(36.dp).background(p.line) else Modifier.padding(horizontal = 6.dp).width(1.dp).height(36.dp).background(p.line))

    @Composable
    fun Items() {
        Box(grip.size(if (vertical) 56.dp else 28.dp, if (vertical) 28.dp else 56.dp), contentAlignment = Alignment.Center) {
            Icon(if (vertical) NibIcons.GripHorizontal else NibIcons.Grip, contentDescription = null, tint = p.muted, modifier = Modifier.size(20.dp))
        }
        Tool.entries.forEach { tool ->
            if (tool == Tool.ERASER) Divider()
            ToolButton(
                icon = toolIcon(tool),
                label = toolName(tool),
                tint = tool.pen?.let(p::penColor) ?: p.ink,
                on = state.tool == tool,
                onClick = { viewModel.setTool(tool) },
                compact = vertical,
            )
        }
        Divider()
        if (state.tool == Tool.ERASER) EraserButton(state, viewModel, compact = vertical) else PenKindButton(state, viewModel, compact = vertical)
        // On the emulator the mouse is a finger: nothing inks until this is on.
        ToolButton(
            icon = NibIcons.Finger,
            label = stringResource(R.string.tool_finger),
            tint = if (state.fingerDraws) p.primaryInk else p.muted,
            on = state.fingerDraws,
            toggle = viewModel::setFingerDraws,
            compact = vertical,
        )
        IconButton(onClick = { viewModel.setPenBarHidden(true) }) {
            Icon(NibIcons.ChevronUp, contentDescription = stringResource(R.string.pen_bar_hide), tint = p.muted)
        }
    }

    if (vertical) {
        Column(frame.padding(horizontal = 6.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) { Items() }
    } else {
        Row(frame.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Items() }
    }
}

/**
 * The pen bar folded away: one round button showing the pen in hand. A tap opens the bar again,
 * each long press moves on to the next tool along the bar (round to the first after the last),
 * and a drag moves it to another dock.
 */
@Composable
private fun FoldedPenButton(state: NotebookUiState, viewModel: NotebookViewModel, modifier: Modifier) {
    val p = Nib.palette
    val tool = state.tool
    val show = stringResource(R.string.pen_bar_show)
    val swap = stringResource(R.string.pen_swap, toolName(NotebookViewModel.nextTool(tool)))
    val description = stringResource(R.string.pen_bar_folded, toolName(tool))
    val tint by animateColorAsState(tool.pen?.let(p::penColor) ?: p.ink, label = "pen colour")
    Box(
        modifier.size(56.dp).shadow(10.dp, CircleShape).clip(CircleShape).background(p.surface)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = show,
                onLongClickLabel = swap,
                onLongClick = viewModel::nextTool,
                onClick = { viewModel.setPenBarHidden(false) },
            )
            .semantics { contentDescription = description }
            .testTag(FOLDED_PEN_BAR_TAG),
        contentAlignment = Alignment.Center,
    ) {
        // The pen in hand turns over when it changes.
        AnimatedContent(tool, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.5f)).togetherWith(fadeOut() + scaleOut(targetScale = 0.5f)) }, label = "pen") { t ->
            Icon(toolIcon(t), contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        }
    }
}

/**
 * The kind of pen for the writing pen in hand (Phase 22): shows the current one, and a tap lists
 * ballpoint, gel and felt. Off for tools that don't write in a kind: Scratch, the lasso, the eraser, Move.
 */
@Composable
private fun PenKindButton(state: NotebookUiState, viewModel: NotebookViewModel, compact: Boolean) {
    val p = Nib.palette
    val pen = state.tool.pen?.takeIf { it != Pen.SCRATCH && state.tool != Tool.DIAGRAM_PEN }
    var open by remember { mutableStateOf(false) }
    val kind = pen?.let(state::kindOf) ?: PenKind.GEL
    Box {
        ToolButton(
            icon = kindIcon(kind),
            label = stringResource(kindName(kind)),
            tint = if (pen != null) p.ink else p.line,
            on = false,
            onClick = { if (pen != null) open = true },
            compact = compact,
            description = stringResource(R.string.pen_kind_choose),
        )
        DropdownMenu(expanded = open && pen != null, onDismissRequest = { open = false }, modifier = Modifier.testTag(PEN_KIND_MENU_TAG)) {
            PenKind.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(kindName(option))) },
                    leadingIcon = { Icon(kindIcon(option), contentDescription = null, tint = p.ink) },
                    trailingIcon = { if (option == kind) Icon(NibIcons.Check, contentDescription = null, tint = p.primary) },
                    onClick = {
                        open = false
                        pen?.let { viewModel.setPenKind(it, option) }
                    },
                )
            }
        }
    }
}

/** How the eraser works (Phase 35), in the place of the kind of pen while the eraser is in hand: what it takes, and how wide it is. */
@Composable
private fun EraserButton(state: NotebookUiState, viewModel: NotebookViewModel, compact: Boolean) {
    val p = Nib.palette
    var open by remember { mutableStateOf(false) }
    val settings = state.eraser
    Box {
        ToolButton(
            icon = NibIcons.Eraser,
            label = stringResource(eraserModeName(settings.mode)),
            tint = p.ink,
            on = false,
            onClick = { open = true },
            compact = compact,
            description = stringResource(R.string.eraser_choose),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag(ERASER_MENU_TAG)) {
            EraserMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(eraserModeName(mode)))
                            Text(stringResource(eraserModeAbout(mode)), style = MaterialTheme.typography.bodySmall, color = p.muted)
                        }
                    },
                    trailingIcon = { if (mode == settings.mode) Icon(NibIcons.Check, contentDescription = null, tint = p.primary) },
                    onClick = {
                        open = false
                        viewModel.setEraser(settings.copy(mode = mode))
                    },
                )
            }
            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(p.line))
            EraserSize.entries.forEach { size ->
                DropdownMenuItem(
                    text = { Text(stringResource(eraserSizeName(size))) },
                    leadingIcon = {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size((size.radius * 2f / 3f).coerceAtMost(24f).dp).clip(CircleShape).background(p.ink))
                        }
                    },
                    trailingIcon = { if (size == settings.size) Icon(NibIcons.Check, contentDescription = null, tint = p.primary) },
                    onClick = {
                        open = false
                        viewModel.setEraser(settings.copy(size = size))
                    },
                )
            }
        }
    }
}

private fun eraserModeName(mode: EraserMode): Int = when (mode) {
    EraserMode.PRECISE -> R.string.eraser_precise
    EraserMode.STROKE -> R.string.eraser_stroke
}

private fun eraserModeAbout(mode: EraserMode): Int = when (mode) {
    EraserMode.PRECISE -> R.string.eraser_precise_about
    EraserMode.STROKE -> R.string.eraser_stroke_about
}

private fun eraserSizeName(size: EraserSize): Int = when (size) {
    EraserSize.SMALL -> R.string.eraser_small
    EraserSize.MEDIUM -> R.string.eraser_medium
    EraserSize.LARGE -> R.string.eraser_large
    EraserSize.EXTRA_LARGE -> R.string.eraser_extra_large
}

private fun kindName(kind: PenKind): Int = when (kind) {
    PenKind.BALLPOINT -> R.string.pen_kind_ballpoint
    PenKind.GEL -> R.string.pen_kind_gel
    PenKind.FELT -> R.string.pen_kind_felt
}

private fun kindIcon(kind: PenKind): ImageVector = when (kind) {
    PenKind.BALLPOINT -> NibIcons.PenBallpoint
    PenKind.GEL -> NibIcons.PenGel
    PenKind.FELT -> NibIcons.PenFelt
}

/**
 * A pen-bar button: an icon over a short label. The selected one fills softly and its icon pops
 * up a little. A [toggle] makes it a switch.
 */
@Composable
private fun ToolButton(icon: ImageVector, label: String, tint: Color, on: Boolean, onClick: () -> Unit = {}, toggle: ((Boolean) -> Unit)? = null, compact: Boolean = false, description: String? = null) {
    val p = Nib.palette
    val fill by animateColorAsState(if (on) p.primarySoft else Color.Transparent, label = "tool fill")
    val text by animateColorAsState(if (on) p.primaryInk else p.muted, label = "tool label")
    val pop by animateFloatAsState(if (on) 1.12f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "tool pop")
    // In a column the buttons are a little shorter, so it fits beside the page.
    val base = Modifier.size(width = 58.dp, height = if (compact) 50.dp else 56.dp).clip(RoundedCornerShape(22.dp)).background(fill)
    Column(
        if (toggle != null) base.toggleable(value = on, role = Role.Switch, onValueChange = toggle) else base.clickable(role = Role.Button, onClickLabel = description, onClick = onClick).semantics { selected = on },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp).scale(pop))
        Text(label, style = MaterialTheme.typography.labelSmall, color = text, maxLines = 1)
    }
}

/** Space kept free at the bottom right for the Results, zoom (Phase 17) and page buttons. */
private const val BOTTOM_END_RESERVE = 260
private const val BOTTOM_END_HEIGHT = 240
private const val DOCK_MARGIN = 16

const val PEN_KIND_MENU_TAG = "pen_kind_menu"
const val ERASER_MENU_TAG = "eraser_menu"
