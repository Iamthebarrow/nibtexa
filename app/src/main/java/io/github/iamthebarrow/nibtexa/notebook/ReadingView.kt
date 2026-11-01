package io.github.iamthebarrow.nibtexa.notebook

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.export.FlowBlock
import io.github.iamthebarrow.nibtexa.core.export.Inline
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextColor
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import io.github.iamthebarrow.nibtexa.export.MathCapture
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.ListAlign
import io.github.iamthebarrow.nibtexa.ink.DiagramMath
import io.github.iamthebarrow.nibtexa.ink.DiagramRenderer
import io.github.iamthebarrow.nibtexa.ink.InkColors
import io.github.iamthebarrow.nibtexa.ui.theme.Nib
import io.github.iamthebarrow.nibtexa.ui.theme.NibPalette
import kotlin.math.max

/**
 * The page as it reads, on a sheet of paper, as the exports set it ([io.github.iamthebarrow.nibtexa.core.export.Flow]):
 * headings and paragraphs with their formatting and inline maths, lists by level, theorem-style
 * blocks with their labels, equations (runs of unnumbered ones rendered together by MathLive,
 * numbered ones with their number), figures, page breaks, and the page's footnotes at the end.
 * Each sits across the sheet where the export puts it (Phase 15). Numbers count through the
 * booklet, as in the exports (Phase 16).
 */
@Composable
fun ReadingView(blocks: List<FlowBlock>, page: Page, viewModel: NotebookViewModel, modifier: Modifier) {
    val p = Nib.palette
    val pieces = remember(blocks) { readingPieces(blocks) }
    val maths = remember(blocks) { blocks.flatMap(::contentOf).filterIsInstance<Inline.Math>() }
    val images = rememberInlineMath(maths.map { it.latex })
    val notes = remember(blocks) { blocks.flatMap(::contentOf).filterIsInstance<Inline.Note>() }
    Box(modifier.background(p.ground), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 820.dp).fillMaxHeight().padding(vertical = 24.dp).shadow(4.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp)).background(p.paper).testTag(READING_TAG),
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (pieces.isEmpty()) item { Text(stringResource(R.string.nothing_to_read), style = MaterialTheme.typography.bodyLarge, color = p.muted) }
            items(pieces) { (piece, firstMath) ->
                Piece(piece, firstMath, maths, images, page, viewModel)
            }
            if (notes.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider(Modifier.width(120.dp), color = p.line)
                    notes.forEach { note ->
                        Text("${note.number}. ${note.text}", style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun Piece(piece: ReadingPiece, firstMath: Int, maths: List<Inline.Math>, images: Map<String, MathCapture.Captured>, page: Page, viewModel: NotebookViewModel) {
    when (piece) {
        is ReadingPiece.Block -> Block(piece.block, firstMath, maths, images, page, viewModel)
        is ReadingPiece.Equations -> EquationList(piece.latex, Modifier.fillMaxWidth(), piece.align.list)
        is ReadingPiece.Columns -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth().testTag(READING_COLUMNS_TAG)) {
            for (column in listOf(piece.left, piece.right)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    column.forEach { (inner, first) -> Piece(inner, first, maths, images, page, viewModel) }
                }
            }
        }
    }
}

/** A block of the page, a run of unnumbered equations with one place, rendered together, or two columns of these (Phase 30). */
private sealed interface ReadingPiece {
    data class Block(val block: FlowBlock) : ReadingPiece
    data class Equations(val latex: List<String>, val align: MathAlign) : ReadingPiece
    data class Columns(val left: List<Pair<ReadingPiece, Int>>, val right: List<Pair<ReadingPiece, Int>>) : ReadingPiece
}

/** The page's pieces, each with the index of its first inline maths on the page. */
private fun readingPieces(blocks: List<FlowBlock>): List<Pair<ReadingPiece, Int>> {
    val top = mutableListOf<Pair<ReadingPiece, Int>>()
    var result = top
    var firstColumn = mutableListOf<Pair<ReadingPiece, Int>>()
    var columnsMath = 0
    var math = 0
    for (block in blocks) {
        when (block) {
            FlowBlock.ColumnsStart -> {
                columnsMath = math
                firstColumn = mutableListOf()
                result = firstColumn
                continue
            }
            FlowBlock.ColumnBreak -> {
                result = mutableListOf()
                continue
            }
            FlowBlock.ColumnsEnd -> {
                top += ReadingPiece.Columns(firstColumn, result) to columnsMath
                result = top
                continue
            }
            else -> Unit
        }
        val last = result.lastOrNull()?.first
        if (block is FlowBlock.Math && block.number == null) {
            if (last is ReadingPiece.Equations && last.align == block.align) {
                result[result.lastIndex] = last.copy(latex = last.latex + block.latex) to result.last().second
            } else {
                result += ReadingPiece.Equations(listOf(block.latex), block.align) to math
            }
            continue
        }
        result += ReadingPiece.Block(block) to math
        math += contentOf(block).count { it is Inline.Math }
    }
    return top
}

private fun contentOf(block: FlowBlock): List<Inline> = when (block) {
    is FlowBlock.Heading -> block.content
    is FlowBlock.Paragraph -> block.content
    is FlowBlock.ListItem -> block.content
    else -> emptyList()
}

@Composable
private fun Block(block: FlowBlock, firstMath: Int, maths: List<Inline.Math>, images: Map<String, MathCapture.Captured>, page: Page, viewModel: NotebookViewModel) {
    val p = Nib.palette
    val body = MaterialTheme.typography.bodyLarge
    when (block) {
        is FlowBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.headlineSmall
                else -> MaterialTheme.typography.titleLarge
            }
            RichText(block.content, style, block.align, firstMath, maths, images, prefix = block.number?.let { AnnotatedString("$it  ") })
        }
        is FlowBlock.Paragraph -> {
            val theorem = block.theorem
            if (theorem == null) {
                RichText(block.content, body, block.align, firstMath, maths, images)
            } else {
                // The label as amsthm sets it: bold, or italic for remarks and proofs; a proof ends with □.
                val italicLabel = theorem.kind == TheoremKind.REMARK || theorem.kind == TheoremKind.PROOF
                val label = AnnotatedString(
                    theorem.kind.label + (theorem.number?.let { " $it" } ?: "") + ". ",
                    SpanStyle(fontWeight = if (italicLabel) null else FontWeight.Bold, fontStyle = if (italicLabel) FontStyle.Italic else null),
                )
                val content = if (theorem.kind == TheoremKind.PROOF) block.content + Inline.Text(" □") else block.content
                RichText(content, body, MathAlign.LEFT, firstMath, maths, images, prefix = label, baseItalic = theorem.kind.italicBody)
            }
        }
        is FlowBlock.ListItem -> Row(Modifier.fillMaxWidth().padding(start = (block.level * 28).dp)) {
            Text(block.marker, style = body, color = p.ink, textAlign = TextAlign.End, modifier = Modifier.width(36.dp).padding(end = 8.dp))
            Box(Modifier.weight(1f)) { RichText(block.content, body, MathAlign.LEFT, firstMath, maths, images) }
        }
        is FlowBlock.Math -> Row(verticalAlignment = Alignment.CenterVertically) {
            EquationList(listOf(block.latex), Modifier.weight(1f), block.align.list)
            block.number?.let { Text("($it)", style = body, color = p.ink) }
        }
        is FlowBlock.Figure -> Column(horizontalAlignment = block.align.horizontal, modifier = Modifier.fillMaxWidth()) {
            // In the theme's pen colours on the sheet, not on white.
            // A diagram's labels are typeset as in the exports (Phase 31), and it is drawn again once they are.
            val labels = remember(block.region.diagram) { block.region.diagram?.labels.orEmpty().mapNotNull { it.latex } }
            val labelImages = rememberInlineMath(labels).toMap()
            val density = LocalDensity.current.density
            val bitmap = remember(page, block.region.id, p.dark, labelImages.keys.toSet()) {
                val units = DiagramRenderer.MATH_SIZE / (io.github.iamthebarrow.nibtexa.ink.DIAGRAM_MATH_PX_PER_EM * density)
                viewModel.figureBitmap(page, block.region.id, if (p.dark) InkColors.DARK else InkColors.LIGHT) { latex -> labelImages[latex]?.let { DiagramMath(it.bitmap, it.bitmap.width * units, it.bitmap.height * units) } }
            }
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = block.region.caption ?: stringResource(R.string.figure)) }
            block.region.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.muted) }
        }
        is FlowBlock.PageBreak -> PageBreakMark(p)
        is FlowBlock.NotReady -> Text(stringResource(R.string.region_not_ready, block.regionId), style = MaterialTheme.typography.bodySmall, color = p.muted)
        FlowBlock.ColumnsStart, FlowBlock.ColumnBreak, FlowBlock.ColumnsEnd -> Unit
    }
}

/** A dashed line across the sheet, labelled: what follows starts a new page in the exports. */
@Composable
private fun PageBreakMark(p: NibPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.weight(1f).height(1.dp)) {
            drawLine(p.muted, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        Text(stringResource(R.string.page_break_marker), style = MaterialTheme.typography.labelSmall, color = p.muted)
        Canvas(Modifier.weight(1f).height(1.dp)) {
            drawLine(p.muted, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
    }
}

/**
 * Formatted text with its inline maths drawn in the line, from the captured images once they are
 * ready (the LaTeX until then).
 */
@Composable
private fun RichText(
    content: List<Inline>,
    style: TextStyle,
    align: MathAlign,
    firstMath: Int,
    maths: List<Inline.Math>,
    images: Map<String, MathCapture.Captured>,
    prefix: AnnotatedString? = null,
    baseItalic: Boolean = false,
) {
    val p = Nib.palette
    val density = LocalDensity.current
    val text = readingText(content, p, baseItalic, firstMath, prefix)
    val count = content.count { it is Inline.Math }
    val inline = (firstMath until firstMath + count).associate { i ->
        val piece = maths[i]
        "math-$i" to inlineMath(piece, images[piece.latex], style, p, density)
    }
    Text(text, style = style, color = p.ink, textAlign = align.text, inlineContent = inline, modifier = Modifier.fillMaxWidth())
}

/**
 * Inline maths in a line of text: the captured image scaled to the text, its baseline on the
 * text's baseline. Placeholders can only centre on the text, so the image is padded until the
 * point a third of an em above its baseline is its middle, which is where the text's middle is.
 */
private fun inlineMath(piece: Inline.Math, captured: MathCapture.Captured?, style: TextStyle, p: NibPalette, density: androidx.compose.ui.unit.Density): InlineTextContent {
    val color = piece.style.color?.let { shownColor(it, p) } ?: p.ink
    if (captured == null) {
        return InlineTextContent(Placeholder((0.55f * piece.latex.length + 0.5f).em, 1.2.em, PlaceholderVerticalAlign.TextCenter)) {
            Text(piece.latex, fontFamily = FontFamily.Monospace, color = p.math, style = style.copy(fontSize = style.fontSize * 0.8f))
        }
    }
    with(density) {
        val em = style.fontSize.toPx() * (piece.style.size?.scale ?: 1f)
        val f = em / (MathCapture.INLINE_CSS_PX * density.density)
        val width = captured.bitmap.width * f
        val height = captured.bitmap.height * f
        val axis = captured.baseline * f - AXIS * em
        val half = max(axis, height - axis)
        val top = half - axis
        val image = captured.bitmap.asImageBitmap()
        return InlineTextContent(Placeholder(width.toSp(), (2 * half).toSp(), PlaceholderVerticalAlign.TextCenter)) {
            Canvas(Modifier.fillMaxSize().testTag(INLINE_MATH_TAG)) {
                drawImage(image, dstOffset = IntOffset(0, top.toInt()), dstSize = IntSize(width.toInt(), height.toInt()), colorFilter = ColorFilter.tint(color))
            }
        }
    }
}

const val INLINE_MATH_TAG = "inline_math"

/** How far above the baseline the middle of a line of text is, in ems (Nunito). */
private const val AXIS = 0.33f

/**
 * Inline maths for the reading view, captured by MathLive as the PDF captures it; filled in as it
 * is ready. Captures are kept for the session ([InlineMathCache]), so reopening the reading view or
 * turning the page back is immediate.
 */
@Composable
internal fun rememberInlineMath(latex: List<String>): Map<String, MathCapture.Captured> {
    val activity = LocalActivity.current
    val images = remember { mutableStateMapOf<String, MathCapture.Captured>() }
    LaunchedEffect(latex, activity) {
        latex.forEach { l -> InlineMathCache[l]?.let { images[l] = it } }
        val missing = latex.distinct().filterNot(images::containsKey)
        if (activity == null || missing.isEmpty()) return@LaunchedEffect
        runCatching { MathCapture(activity).capture(missing, inline = true) }.onSuccess { captured ->
            missing.zip(captured).forEach { (l, c) -> InlineMathCache[l] = c; images[l] = c }
        }.onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) android.util.Log.w("InlineMath", "Could not typeset ${missing.size} piece(s) of maths", e) }
    }
    return images
}

/** The most recently used inline maths captures, by LaTeX: at most [MAX], about 50 kB each. */
private object InlineMathCache {
    private const val MAX = 200
    private val entries = object : LinkedHashMap<String, MathCapture.Captured>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MathCapture.Captured>) = size > MAX
    }

    operator fun get(latex: String): MathCapture.Captured? = entries[latex]
    operator fun set(latex: String, captured: MathCapture.Captured) {
        entries[latex] = captured
    }
}

val MathAlign.text: TextAlign
    get() = when (this) {
        MathAlign.LEFT -> TextAlign.Start
        MathAlign.CENTER -> TextAlign.Center
        MathAlign.RIGHT -> TextAlign.End
    }

val MathAlign.horizontal: Alignment.Horizontal
    get() = when (this) {
        MathAlign.LEFT -> Alignment.Start
        MathAlign.CENTER -> Alignment.CenterHorizontally
        MathAlign.RIGHT -> Alignment.End
    }

val MathAlign.list: ListAlign
    get() = when (this) {
        MathAlign.LEFT -> ListAlign.LEFT
        MathAlign.CENTER -> ListAlign.CENTER
        MathAlign.RIGHT -> ListAlign.RIGHT
    }
