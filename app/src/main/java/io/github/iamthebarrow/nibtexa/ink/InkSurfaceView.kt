package io.github.iamthebarrow.nibtexa.ink

import io.github.iamthebarrow.nibtexa.core.model.notebook.Graph
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.PenKind
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.notebook.Diagram
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPart
import io.github.iamthebarrow.nibtexa.core.model.notebook.ShapeRecognizer
import io.github.iamthebarrow.nibtexa.core.model.capture.CaptureEvent
import io.github.iamthebarrow.nibtexa.core.model.capture.StrokeCapture
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.capture.PenButtonAction
import io.github.iamthebarrow.nibtexa.core.model.capture.PenButtons
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageFit
import io.github.iamthebarrow.nibtexa.core.model.notebook.clip
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageView
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import androidx.ink.strokes.Stroke as InkStroke

/** What input on the surface does. */
enum class SurfaceMode {
    /** Writing with the current [InkSurfaceView.pen]; a stylus eraser end still erases. */
    INK,

    /** Drawing a selection loop; dragging from inside the selection moves it. */
    LASSO,

    /** Erasing whole strokes along the path. */
    ERASER,

    /** Moving the page with one finger or the stylus (Phase 19). */
    MOVE,
}

/**
 * The writing surface.
 *
 * Input interpretation lives in [StrokeCapture] (pure Kotlin, unit-tested). This view adapts
 * [MotionEvent]s to it and drives the layers, bottom to top:
 * - [PaperView]: blank, ruled or grid background (display only);
 * - [FinishedStrokesView]: the document, rendered from the project's own model;
 * - [TypedRegionsView]: typed regions (Phase 18), imported text and maths with no ink;
 * - an androidx.ink [InProgressStrokesView] for low-latency rendering of the stroke being
 *   written (its finished strokes are discarded: the model is the source of truth);
 * - [SelectionOverlayView]: regions, selection, and lasso or eraser paths.
 *
  * Lasso, move and eraser gestures use one pointer of any type and are reported in page units
 * when they end.
 *
 * Zoom and pan (Phase 17): two fingers pinch to zoom and drag to move the page; with finger
 * drawing off, one finger moves it too. A stylus never moves the page. Every layer and every
 * touch goes through one [PageTransform], so the ink stays in page units. The view is reported
 * as it changes ([onView], `final` false) and when the gesture ends (`final` true).
 *
 * A stylus button held as the pen touches the page (Phase 34) stands in for the tool for that
 * touch, as [penButtons] says, except inside an open graph or diagram, which keep their touches.
 */
class InkSurfaceView(context: Context) : FrameLayout(context) {

    var onStrokeCaptured: (CapturedStroke) -> Unit = {}

    /** A finished lasso loop. */
    var onLasso: (List<Pair<Float, Float>>) -> Unit = {}

    /** A tap (a lasso gesture that barely moved). */
    var onTap: (Float, Float) -> Unit = { _, _ -> }

    /** The selection was dragged by (dx, dy). */
    var onMoveSelection: (Float, Float) -> Unit = { _, _ -> }

    /** A drag of the selection or its handle has started (true) or ended, so that what floats over the page can get out of the way. */
    var onDragging: (Boolean) -> Unit = {}

    private var dragging = false

    /** A selected picture was resized with its corner handle (Phase 20), to these bounds. */
    var onResize: (Bounds) -> Unit = {}

    /** More of the eraser's path (eraser mode, a stylus eraser end, or a pen button), as the pen moves; it joins on from the last. */
    var onErase: (List<Pair<Float, Float>>) -> Unit = {}

    /** The pen lifted from the page, ending that path. */
    var onEraseEnd: () -> Unit = {}

    /** That path wasn't an erase after all: a second finger made it a zoom, or Android took the touch back. */
    var onEraseCancel: () -> Unit = {}

    /** How far the eraser reaches, in page units; shown as a ring. */
    var eraserRadius = 8f

    /** The page view changed: while a zoom or move goes on (`final` false), and when it ends. */
    var onView: (PageView, Boolean) -> Unit = { _, _ -> }

    var mode: SurfaceMode = SurfaceMode.INK
        set(value) {
            if (field == value) return
            field = value
            settleInProgressStrokes()
            endGesture()
        }

    /** The pen new strokes are written with. */
    var pen: Pen = Pen.MATH
        set(value) {
            if (field == value) return
            field = value
            inProgressBrush = brush()
        }

    /** The kind of pen new strokes look written with (Phase 22). */
    var kind: PenKind = PenKind.GEL
        set(value) {
            if (field == value) return
            field = value
            inProgressBrush = brush()
        }

    /** The pen's brush at the current zoom: the stroke being written is drawn in screen pixels. */
    private fun brush() = InkStrokes.penBrush(pen, density * transform.view.scale, colors, kind)

    private var colors = InkColors.LIGHT

    /** The page's colours: paper, ruling, ink and marks (light or dark, Phase 13). */
    fun setColors(value: InkColors) {
        if (value == colors) return
        colors = value
        // The desk; the sheet is drawn on it (Phase 19).
        setBackgroundColor(value.desk)
        paperView.setColors(value)
        finishedView.setColors(value)
        typedView.setColors(value)
        overlay.setColors(value)
        inProgressBrush = brush()
    }

    var fingerDraws: Boolean
        get() = capture.policy.fingerDraws
        set(value) {
            capture.policy = capture.policy.copy(fingerDraws = value)
        }

    private val density = resources.displayMetrics.density
    private val transform = PageTransform(density)
    private val capture = StrokeCapture()
    private val paperView = PaperView(context)
    private val finishedView = FinishedStrokesView(context)
    private val typedView = TypedRegionsView(context)
    private val inProgressView = InProgressStrokesView(context)
    private var inProgressBrush = InkStrokes.penBrush(pen, density)
    private val inProgressIds = mutableMapOf<Int, InProgressStrokeId>()
    private val overlay = SelectionOverlayView(context)

    private enum class Gesture { NONE, LOOP, MOVE, ERASE, RESIZE }

    private var gesture = Gesture.NONE
    private val path = mutableListOf<Pair<Float, Float>>()
    private var gesturePointerId = MotionEvent.INVALID_POINTER_ID
    private var selectionBounds: Bounds? = null

    init {
        tag = TAG
        contentDescription = context.getString(R.string.canvas_description)
        setBackgroundColor(InkColors.LIGHT.desk)
        addView(paperView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(finishedView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(typedView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inProgressView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        paperView.setTransform(transform)
        finishedView.setTransform(transform)
        typedView.setTransform(transform)
        overlay.setTransform(transform)
        inProgressView.addFinishedStrokesListener(
            object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, InkStroke>) {
                    inProgressView.removeFinishedStrokes(strokes.keys)
                }
            },
        )
    }

    fun setDocument(document: InkDocument) {
        this.document = document
        finishedView.setDocument(document)
    }

    private var document = InkDocument.EMPTY

    /** The page shown, so a page with no view of its own gets its default view once. */
    private var viewPageId: String? = null
    private var pendingDefault = false

    /**
     * Shows the page [pageId] at [view]. A page that has none yet opens at 100%, or at Fit ink when
     * its ink is wider than the screen; that default is reported (not as final, so it is not stored).
     */
    fun setView(view: PageView?, pageId: String) {
        if (navigation != null) return
        val newPage = pageId != viewPageId
        viewPageId = pageId
        when {
            // Kept round the sheet; a view that had to move is reported, so the app shows the same one.
            view != null -> view.within(sheet, width / density, height / density).let {
                if (it != transform.view) apply(it)
                if (it != view && width > 0) onView(it, false)
            }
            newPage -> {
                pendingDefault = true
                showDefault()
            }
        }
    }

    private fun showDefault() {
        if (!pendingDefault || width == 0) return
        pendingDefault = false
        // A page opens with its sheet across the screen (Phase 19).
        val view = openAt(sheet, width / density, height / density)
        apply(view)
        onView(view, false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        showDefault()
        // A new size (turning the tablet, opening the results panel) keeps the sheet in reach.
        if (!pendingDefault) transform.view.within(sheet, w / density, h / density).let { if (it != transform.view) { apply(it); onView(it, false) } }
    }

    private var sheet = Sheet()

    /** Where ink goes (Phase 30): inside the page's margin. */
    private var area = Bounds(0f, 0f, sheet.width, sheet.height)

    /** Words in a text box being resized: how tall they are at a width, which it can't be shorter than (Phase 30); null for a picture. */
    private var textHeight: ((Float) -> Float)? = null

    /** The page's writing area, shown on the paper when it has a margin. */
    fun setWritingArea(value: Bounds) {
        if (value == area && value.right > 0f) return
        area = value
        paperView.setArea(value.takeIf { it != Bounds(0f, 0f, sheet.width, sheet.height) })
    }

    /** [measure] when the selection with the resize handle is words, which wrap instead of keeping their shape. */
    fun setTextHeight(measure: ((Float) -> Float)?) {
        textHeight = measure
    }

    /** The touch being drawn started on the desk, so it writes nothing. */
    private var onDesk = false

    /** The booklet's paper (Phase 19): drawn as a sheet, and the view kept round it. */
    fun setSheet(value: Sheet) {
        if (value == sheet) return
        sheet = value
        paperView.setSheet(value)
        if (width > 0) transform.view.within(sheet, width / density, height / density).let { if (it != transform.view) { apply(it); onView(it, false) } }
    }

    private fun apply(view: PageView) {
        transform.set(view)
        inProgressBrush = brush()
        paperView.invalidate()
        finishedView.invalidate()
        typedView.invalidate()
        overlay.invalidate()
    }

    fun setPaper(paper: Paper) = paperView.setPaper(paper)

    /** The page's background picture (Phase 20: an imported PDF page), decoded; null for plain paper. */
    fun setBackdrop(bitmap: android.graphics.Bitmap?) = paperView.setBackdrop(bitmap)

    /** Decoded pictures for the page's picture regions, by name (Phase 20). */
    fun setPictures(pictures: Map<String, android.graphics.Bitmap>) = typedView.setPictures(pictures)

    /** Typed regions (Phase 18), and the captured maths they show. */
    fun setTyped(typed: List<TypedContent>, maths: Map<String, TypedMath>) {
        typedView.setTyped(typed)
        typedView.setMaths(maths)
    }

    fun setRegions(regions: List<RegionOutline>) = overlay.setRegions(regions)

    fun setHighlight(bounds: Bounds?, strength: Float) = overlay.setHighlight(bounds, strength)

    fun setSelection(bounds: List<Bounds>) {
        selectionBounds = bounds.reduceOrNull(Bounds::union)
        overlay.setSelection(bounds)
    }

    /**
     * Releases androidx.ink's in-progress layer before the window goes away. Tearing the
     * surface down with a handoff in flight (for example, recreating the activity right after a
     * stroke) crashed in libhwui on API 37 (Phase 4 open issue). Only public API is used.
     * Nothing is lost: finished strokes are already in the model, and a stroke still being
     * written is canceled, as the system does with its gesture when the window is hidden.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != VISIBLE) settleInProgressStrokes()
        super.onWindowVisibilityChanged(visibility)
    }

    override fun onDetachedFromWindow() {
        settleInProgressStrokes()
        super.onDetachedFromWindow()
    }

    private fun settleInProgressStrokes() {
        inProgressIds.clear()
        if (inProgressView.hasUnfinishedStrokes()) inProgressView.cancelUnfinishedStrokes()
        // Strokes finished but not yet handed off are still drawn by androidx.ink. They are
        // already in the model (and drawn by FinishedStrokesView), so release them now rather
        // than leave a handoff in flight when the surface is destroyed.
        val finished = inProgressView.getFinishedStrokes().keys
        if (finished.isNotEmpty()) inProgressView.removeFinishedStrokes(finished)
    }

    /** All touches belong to the surface, not to its rendering layers. */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility") // Drawing has no click equivalent; see canvas_description.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestUnbufferedDispatch(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) readButtons(event)
        if (graphGesture(event)) return true
        if (diagramGesture(event)) return true
        if (navigate(event)) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gesture = when {
                event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER -> Gesture.ERASE
                held == PenButtonAction.ERASER -> Gesture.ERASE
                held == PenButtonAction.LASSO -> lassoGesture(event)
                held == PenButtonAction.SCRATCH -> Gesture.NONE
                mode == SurfaceMode.ERASER -> Gesture.ERASE
                mode == SurfaceMode.LASSO -> lassoGesture(event)
                else -> Gesture.NONE
            }
        }
        if (gesture != Gesture.NONE) {
            trackGesture(event)
            return true
        }
        // Ink is written inside the margin only (Phases 20 and 30): a touch that starts outside it writes nothing.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDesk = !area.contains(transform.pageX(event.x), transform.pageY(event.y))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (onDesk) {
                onDesk = false
                return true
            }
        }
        if (onDesk) return true
        for (captureEvent in MotionEventSamples.dispatch(event, capture, transform)) {
            render(captureEvent, event)
        }
        watchForHold(event)
        return true
    }

    private fun lassoGesture(event: MotionEvent) = when {
        onHandle(event.x, event.y) -> Gesture.RESIZE
        selectionBounds?.contains(transform.pageX(event.x), transform.pageY(event.y)) == true -> Gesture.MOVE
        else -> Gesture.LOOP
    }

    // ---- A pen's buttons (Phase 34) -------------------------------------------------------------

    /** What a pen's buttons do. */
    var penButtons = PenButtons()

    /** What the buttons asked for as this touch began. It lasts for the touch, so a press in the middle of a stroke doesn't break it. */
    private var held: PenButtonAction? = null

    private fun readButtons(event: MotionEvent) {
        held = if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            null
        } else {
            penButtons.held(
                event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY),
                event.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY),
            )
        }
    }

    // ---- A graph's view (Phase 25) --------------------------------------------------------------

    /** A drag or pinch inside the open graph ended on this view. */
    var onGraphView: (String, Graph) -> Unit = { _, _ -> }

    private var graphId: String? = null
    private var graphBounds: Bounds? = null
    private var graph: Graph? = null

    /** What a drag or pinch started from: the graph then, the place in its plot under the fingers, and their spread. */
    private class GraphTouch(val start: Graph, val x: Double, val y: Double, val span: Float)

    private var graphTouch: GraphTouch? = null
    private var graphNow: Graph? = null

    /**
     * The graph whose controls are open, or null. Only it takes touches, so a graph is otherwise part of the page and
     * can't get in the way of writing on it or of moving the page.
     */
    fun setOpenGraph(id: String?, bounds: Bounds?, value: Graph?) {
        if (id != graphId || value != graph) {
            // What's stored has caught up with the gesture, or changed by other means.
            if (graphTouch == null) typedView.setGraphPreview(null, null)
        }
        graphId = id
        graphBounds = bounds
        graph = value
    }

    /** Follows a touch that began inside the open graph, with any pointers, and returns whether [event] was part of one. */
    private fun graphGesture(event: MotionEvent): Boolean {
        val id = graphId ?: return false
        val current = graph ?: return false
        val bounds = graphBounds ?: return false
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            if (!bounds.contains(transform.pageX(event.x), transform.pageY(event.y))) return false
            graphNow = current
            startGraphTouch(event, skip = -1, id, bounds)
            return true
        }
        if (graphTouch == null) return false
        when (action) {
            MotionEvent.ACTION_POINTER_DOWN -> startGraphTouch(event, skip = -1, id, bounds)
            MotionEvent.ACTION_POINTER_UP -> startGraphTouch(event, skip = event.actionIndex, id, bounds)
            MotionEvent.ACTION_MOVE -> followGraphTouch(event, id, bounds)
            MotionEvent.ACTION_UP -> {
                followGraphTouch(event, id, bounds)
                graphTouch = null
                val end = graphNow
                if (end != null && end != current) onGraphView(id, end) else typedView.setGraphPreview(null, null)
            }
            MotionEvent.ACTION_CANCEL -> {
                graphTouch = null
                typedView.setGraphPreview(null, null)
            }
        }
        return true
    }

    private fun startGraphTouch(event: MotionEvent, skip: Int, id: String, bounds: Bounds) {
        val start = graphNow ?: return
        val (x, y, span) = fingers(event, skip)
        val (fx, fy) = plotShare(start, bounds, x, y)
        graphTouch = GraphTouch(start, fx, fy, span)
        typedView.setGraphPreview(id, start)
    }

    private fun followGraphTouch(event: MotionEvent, id: String, bounds: Bounds) {
        val touch = graphTouch ?: return
        val (x, y, span) = fingers(event, skip = -1)
        val (fx, fy) = plotShare(touch.start, bounds, x, y)
        val zoom = if (touch.span > 0f && span > 0f) (span / touch.span).toDouble() else 1.0
        val next = touch.start.moved(touch.x, touch.y, fx, fy, zoom)
        if (next != graphNow) {
            graphNow = next
            typedView.setGraphPreview(id, next)
        }
    }

    /** Where a screen point is across the plot, as shares of its width and height from the top-left. */
    private fun plotShare(graph: Graph, bounds: Bounds, x: Float, y: Float): Pair<Double, Double> {
        val area = GraphRenderer.plotArea(graph, bounds)
        return ((transform.pageX(x) - area.left) / area.width()).toDouble() to ((transform.pageY(y) - area.top) / area.height()).toDouble()
    }

    // ---- A diagram's parts (Phase 31) -----------------------------------------------------------

    /** Strokes stay as ink. Off for the Diagram pen, as they become parts of a diagram. */
    var keepsInk = true

    /** A part of the open diagram was dragged, and the diagram is now this. */
    var onDiagramEdit: (String, Diagram, DiagramPart) -> Unit = { _, _, _ -> }

    /** The open diagram was tapped, on this part or none. */
    var onDiagramTap: (String, DiagramPart?) -> Unit = { _, _ -> }

    private var diagramId: String? = null
    private var diagramBounds: Bounds? = null
    private var diagram: Diagram? = null

    private class DiagramTouch(val part: DiagramPart?, val x: Float, val y: Float) {
        var moved = false
        var now: Diagram? = null
    }

    private var diagramTouch: DiagramTouch? = null

    /** The diagram open for arranging with the lasso, or null. Only it takes these touches. */
    fun setOpenDiagram(id: String?, bounds: Bounds?, value: Diagram?, chosen: DiagramPart?) {
        // What's stored has caught up with the drag, or changed by other means.
        if ((id != diagramId || value != diagram) && diagramTouch == null) typedView.setDiagramPreview(null, null)
        diagramId = id
        diagramBounds = bounds
        diagram = value
        typedView.setDiagramMark(id, chosen)
    }

    /** Follows a touch that began inside the open diagram, and returns whether [event] was part of one. A second finger leaves it to zooming. */
    private fun diagramGesture(event: MotionEvent): Boolean {
        val id = diagramId ?: return false
        val current = diagram ?: return false
        val bounds = diagramBounds ?: return false
        val scale = (bounds.right - bounds.left) / current.width.coerceAtLeast(1f)
        val x = transform.pageX(event.x)
        val y = transform.pageY(event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!bounds.contains(x, y)) return false
                diagramTouch = DiagramTouch(current.partAt((x - bounds.left) / scale, (y - bounds.top) / scale), x, y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                diagramTouch?.let { typedView.setDiagramPreview(null, null) }
                diagramTouch = null
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val touch = diagramTouch ?: return false
                val dx = x - touch.x
                val dy = y - touch.y
                val part = touch.part ?: return true
                if (!touch.moved && kotlin.math.hypot(dx, dy) * transform.view.scale < TAP_SLOP_DP) return true
                touch.moved = true
                touch.now = current.moved(part, dx / scale, dy / scale)
                typedView.setDiagramPreview(id, touch.now)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val touch = diagramTouch ?: return false
                diagramTouch = null
                val end = touch.now
                when {
                    !touch.moved -> onDiagramTap(id, touch.part)
                    end != null && end != current && touch.part != null -> onDiagramEdit(id, end, touch.part)
                    else -> typedView.setDiagramPreview(null, null)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (diagramTouch == null) return false
                diagramTouch = null
                typedView.setDiagramPreview(null, null)
                return true
            }
        }
        return diagramTouch != null
    }

    // ---- Perfect shapes (Phase 23) --------------------------------------------------------------

    /** Hold the pen still at the end of a stroke to make it a perfect shape. Off for pens that write words. */
    var convertsShapes = false

    /** A converted shape near a multiple of 15 degrees is turned to it. */
    var snapsShapes = true

    /** A stroke became a shape: the stroke as drawn, and the points to replace it with. */
    var onShape: (CapturedStroke, List<InkPoint>) -> Unit = { _, _ -> }

    private var holdX = 0f
    private var holdY = 0f
    private var holdEvent: MotionEvent? = null
    private val onHold = Runnable { convertHeldStroke() }

    /** Restarts the hold timer whenever the pen really moves; ends it when the stroke does. */
    private fun watchForHold(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!convertsShapes || held != null || !capture.hasActiveStroke) return stopWatchingForHold()
                val moved = event.actionMasked == MotionEvent.ACTION_DOWN ||
                    kotlin.math.hypot(event.x - holdX, event.y - holdY) > HOLD_SLOP_DP * density
                if (moved) {
                    holdX = event.x
                    holdY = event.y
                    removeCallbacks(onHold)
                    postDelayed(onHold, HOLD_MILLIS)
                }
                holdEvent?.recycle()
                holdEvent = MotionEvent.obtain(event)
            }
            else -> stopWatchingForHold()
        }
    }

    private fun stopWatchingForHold() {
        removeCallbacks(onHold)
        holdEvent?.recycle()
        holdEvent = null
    }

    /**
     * The pen has rested: if the stroke so far is a clear shape, it becomes one now, and what follows of
     * this touch writes nothing. If it isn't, nothing happens, and the stroke goes on as drawn.
     */
    private fun convertHeldStroke() {
        val drawn = capture.activeStroke ?: return
        val points = area.clip(drawn.points)
        if (points.size != drawn.points.size) return
        val shape = ShapeRecognizer.recognize(points, snapsShapes) ?: return
        // A shape running past the margin would have to be cut, so that stroke stays as drawn (Phases 20 and 30).
        if (shape.points.any { !area.contains(it.x, it.y) }) return
        val event = holdEvent ?: return
        val stroke = capture.takeActive() ?: return
        inProgressIds.remove(event.getPointerId(0))?.let { inProgressView.cancelStroke(it, event) }
        finishedView.addPending(stroke.copy(points = shape.points, pen = pen, kind = kind))
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onShape(stroke.copy(pen = pen, kind = kind), shape.points)
        stopWatchingForHold()
    }

    // ---- Zoom and pan (Phase 17) ------------------------------------------------------------

    /** A zoom or move in progress: the view, the fingers' middle and their spread when it (re)started. */
    private class Navigation(val view: PageView, val x: Float, val y: Float, val span: Float)

    private var navigation: Navigation? = null

    /** Fingers (not a stylus) on the surface. */
    private fun fingersOnly(event: MotionEvent) = (0 until event.pointerCount).all {
        val tool = event.getToolType(it)
        tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER
    }

    /**
     * Starts, follows or ends a zoom or move, and returns whether [event] was one. Two fingers
     * always navigate, cancelling a stroke or lasso just begun; one finger does in ink mode when
     * fingers do not draw.
     */
    private fun navigate(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (navigation == null) {
            // The Move tool moves the page with any one pointer (Phase 19), and a pen button set to it does too (Phase 34).
            val oneFingerPans = action == MotionEvent.ACTION_DOWN && (held == PenButtonAction.MOVE || held == null && (mode == SurfaceMode.MOVE ||
                (mode == SurfaceMode.INK && !fingerDraws && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER)))
            val twoFingers = action == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2 && fingersOnly(event)
            if (!oneFingerPans && !twoFingers) return false
            if (twoFingers) {
                // What the first finger began is not ink, a selection or an erase after all.
                cancelErase()
                if (gesture != Gesture.NONE) endGesture() else capture.onCancel().forEach { render(it, event) }
            }
            startNavigation(event, skip = -1)
            return true
        }
        when (action) {
            MotionEvent.ACTION_POINTER_DOWN -> startNavigation(event, skip = -1)
            MotionEvent.ACTION_POINTER_UP -> startNavigation(event, skip = event.actionIndex)
            MotionEvent.ACTION_MOVE -> follow(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Where the finger lifts counts too.
                if (action == MotionEvent.ACTION_UP) follow(event)
                navigation = null
                onView(transform.view, true)
            }
        }
        return true
    }

    /** The view for the fingers' positions now. */
    private fun follow(event: MotionEvent) {
        val nav = navigation ?: return
        val (x, y, span) = fingers(event, skip = -1)
        val scale = if (nav.span > 0f && span > 0f) nav.view.scale * span / nav.span else nav.view.scale
        // The page point under the fingers' middle when they went down stays under it.
        val start = nav.view
        val pageX = start.left + nav.x / density / start.scale
        val pageY = start.top + nav.y / density / start.scale
        val s = scale.coerceIn(PageView.MIN_SCALE, PageView.MAX_SCALE)
        val next = PageView(s, pageX - x / density / s, pageY - y / density / s).within(sheet, width / density, height / density)
        if (next != transform.view) {
            apply(next)
            onView(next, false)
        }
    }

    private fun startNavigation(event: MotionEvent, skip: Int) {
        val (x, y, span) = fingers(event, skip)
        navigation = Navigation(transform.view, x, y, span)
    }

    /** The middle of the pointers (but [skip], one lifting) and their mean distance from it, in pixels. */
    private fun fingers(event: MotionEvent, skip: Int): Triple<Float, Float, Float> {
        val indices = (0 until event.pointerCount).filter { it != skip }
        if (indices.isEmpty()) return Triple(0f, 0f, 0f)
        val x = indices.map { event.getX(it) }.average().toFloat()
        val y = indices.map { event.getY(it) }.average().toFloat()
        val span = if (indices.size < 2) 0f else indices.map { kotlin.math.hypot(event.getX(it) - x, event.getY(it) - y) }.average().toFloat()
        return Triple(x, y, span)
    }

    /** One pointer drives the gesture; other pointers are ignored. */
    private fun trackGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                path.clear()
                gesturePointerId = event.getPointerId(0)
                path += transform.pageX(event.x) to transform.pageY(event.y)
                if (gesture == Gesture.ERASE) {
                    erasing = true
                    erasedTo = 0
                    sendErase(event.eventTime, now = true)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(gesturePointerId)
                if (index < 0) return
                for (h in 0 until event.historySize) path += transform.pageX(event.getHistoricalX(index, h)) to transform.pageY(event.getHistoricalY(index, h))
                path += transform.pageX(event.getX(index)) to transform.pageY(event.getY(index))
                if (gesture == Gesture.ERASE) sendErase(event.eventTime, now = false)
            }

            MotionEvent.ACTION_UP -> {
                // The lift position ends the path; without it a quick swipe is a single point.
                val index = event.findPointerIndex(gesturePointerId)
                if (index >= 0) path += transform.pageX(event.getX(index)) to transform.pageY(event.getY(index))
                if (gesture == Gesture.ERASE) sendErase(event.eventTime, now = true)
                val points = path.toList()
                val kind = gesture
                endGesture()
                finish(kind, points)
                return
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelErase()
                endGesture()
                return
            }
        }
        if ((gesture == Gesture.MOVE || gesture == Gesture.RESIZE) && !dragging &&
            kotlin.math.hypot(path.last().first - path.first().first, path.last().second - path.first().second) * transform.view.scale >= TAP_SLOP_DP
        ) {
            dragging = true
            onDragging(true)
        }
        when (gesture) {
            Gesture.LOOP -> overlay.setLoop(path.toList(), closed = true)
            Gesture.ERASE -> overlay.setEraser(path.last().first, path.last().second, eraserRadius)
            Gesture.MOVE -> overlay.setOffset(path.last().first - path.first().first, path.last().second - path.first().second)
            Gesture.RESIZE -> overlay.setHandle(handle, resized(path.last().first - path.first().first, path.last().second - path.first().second))
            Gesture.NONE -> Unit
        }
    }

    private fun finish(kind: Gesture, points: List<Pair<Float, Float>>) {
        if (points.isEmpty()) return
        val width = points.maxOf { it.first } - points.minOf { it.first }
        val height = points.maxOf { it.second } - points.minOf { it.second }
        // A tap is small on screen, whatever the zoom.
        val tap = maxOf(width, height) * transform.view.scale < TAP_SLOP_DP
        when (kind) {
            Gesture.LOOP -> if (tap) onTap(points.first().first, points.first().second) else onLasso(points)
            Gesture.MOVE -> if (tap) onTap(points.first().first, points.first().second) else onMoveSelection(points.last().first - points.first().first, points.last().second - points.first().second)
            // Done as the pen moved.
            Gesture.ERASE -> Unit
            Gesture.RESIZE -> if (!tap) resized(points.last().first - points.first().first, points.last().second - points.first().second)?.let(onResize)
            Gesture.NONE -> Unit
        }
    }

    // ---- Erasing as the pen moves (Phase 35) ----------------------------------------------------

    private var erasing = false

    /** How much of [path] has been sent, and when it last was. */
    private var erasedTo = 0
    private var erasedAt = 0L

    /** Hands on the path so far, [now] or at most every [ERASE_EVERY_MS]: a whole page's update for each touch sample would slow the pen. */
    private fun sendErase(time: Long, now: Boolean) {
        if (!now && time - erasedAt < ERASE_EVERY_MS) return
        if (erasedTo >= path.size) return
        // From the last point sent, so that the path has no gaps.
        onErase(path.subList((erasedTo - 1).coerceAtLeast(0), path.size).toList())
        erasedTo = path.size
        erasedAt = time
    }

    private fun cancelErase() {
        if (!erasing) return
        erasing = false
        onEraseCancel()
    }

    /** Shows the eraser's ring as a pen hovers over the page, where it will erase. Pens that report hovering only. */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val stylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val button = stylus && penButtons.held(event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY), event.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY)) == PenButtonAction.ERASER
        val eraser = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER || button || mode == SurfaceMode.ERASER
        if (!eraser || event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            overlay.clearEraser()
        } else {
            overlay.setEraser(transform.pageX(event.x), transform.pageY(event.y), eraserRadius)
        }
        return super.onHoverEvent(event)
    }

    private fun endGesture() {
        if (dragging) {
            dragging = false
            onDragging(false)
        }
        if (erasing) {
            erasing = false
            onEraseEnd()
        }
        gesture = Gesture.NONE
        gesturePointerId = MotionEvent.INVALID_POINTER_ID
        path.clear()
        overlay.clearEraser()
        overlay.setLoop(emptyList(), closed = true)
        overlay.setOffset(0f, 0f)
        overlay.setHandle(handle)
    }

    // ---- Resizing a picture (Phase 20) --------------------------------------------------------

    private var handle: Bounds? = null

    /** The selected picture that can be resized (its bounds), or null. */
    fun setResizeHandle(bounds: Bounds?) {
        if (bounds == handle) return
        handle = bounds
        overlay.setHandle(bounds)
    }

    /** The screen point is on the handle at the picture's bottom-right corner. */
    private fun onHandle(x: Float, y: Float): Boolean {
        val b = handle ?: return false
        val reach = HANDLE_REACH_DP * density
        return kotlin.math.abs(x - transform.screenX(b.right)) < reach && kotlin.math.abs(y - transform.screenY(b.bottom)) < reach
    }

    /**
     * The box dragged by ([dx], [dy]), inside the margin. A picture keeps its shape, and is at least [MIN_PICTURE] wide. Words (Phase 30)
     * wrap to any width down to [PageFit.MIN_TEXT_WIDTH], and can be as short as they are, so that none are cut off.
     */
    private fun resized(dx: Float, dy: Float): Bounds? {
        val b = handle ?: return null
        val w = b.right - b.left
        val h = b.bottom - b.top
        if (w <= 0f || h <= 0f) return null
        textHeight?.let { measure ->
            val width = (w + dx).coerceIn(minOf(PageFit.MIN_TEXT_WIDTH, area.right - b.left), maxOf(area.right - b.left, PageFit.MIN_TEXT_WIDTH))
            val least = measure(width)
            val most = maxOf(area.bottom - b.top, least)
            return Bounds(b.left, b.top, b.left + width, b.top + (h + dy).coerceIn(least, most))
        }
        val most = minOf(area.right - b.left, (area.bottom - b.top) * w / h).coerceAtLeast(MIN_PICTURE)
        val width = (w + dx).coerceIn(MIN_PICTURE, most)
        return Bounds(b.left, b.top, b.left + width, b.top + width * h / w)
    }

    private fun render(captureEvent: CaptureEvent, event: MotionEvent) {
        val pointerId = captureEvent.pointerId
        when (captureEvent) {
            is CaptureEvent.Started ->
                inProgressIds[pointerId] = inProgressView.startStroke(event, pointerId, if (held == PenButtonAction.SCRATCH) InkStrokes.penBrush(Pen.SCRATCH, density * transform.view.scale, colors, PenKind.GEL) else inProgressBrush)

            is CaptureEvent.Extended ->
                inProgressIds[pointerId]?.let { inProgressView.addToStroke(event, pointerId, it) }

            is CaptureEvent.Finished -> {
                // A stroke that runs past the margin ends there (Phases 20 and 30).
                val points = area.clip(captureEvent.stroke.points)
                if (points.isEmpty()) {
                    inProgressIds.remove(pointerId)?.let { inProgressView.cancelStroke(it, event) }
                    return
                }
                inProgressIds.remove(pointerId)?.let { inProgressView.finishStroke(event, pointerId, it) }
                val scratch = held == PenButtonAction.SCRATCH
                val stroke = captureEvent.stroke.copy(points = points, pen = if (scratch) Pen.SCRATCH else pen, kind = if (scratch) PenKind.GEL else kind)
                if (keepsInk || scratch) finishedView.addPending(stroke)
                onStrokeCaptured(stroke)
            }

            // With androidx.ink 1.0.0, a stroke that ended in a cancel left state that crashed libhwui
            // when an activity in the same process was later recreated (Phase 6). 1.1.0-alpha10 does
            // not (0/30 vs 8/20 on the API 37 AVD, Phase 8); see ADR-0002.
            is CaptureEvent.Canceled ->
                inProgressIds.remove(pointerId)?.let { inProgressView.cancelStroke(it, event) }
        }
    }

    companion object {
        const val TAG = "ink_surface"

        /** How a page with no view of its own opens: Fit width. Tests that write at screen positions open at 100%. */
        @androidx.annotation.VisibleForTesting
        var openAt: (Sheet, Float, Float) -> PageView = PageView::fitWidth
        private const val TAP_SLOP_DP = 8f
        private const val ERASE_EVERY_MS = 32L

        /** The eraser's ring as it stands, for tests: its radius in page units, or 0 with none showing. */
        @androidx.annotation.VisibleForTesting
        internal fun eraserRingOf(view: InkSurfaceView) = view.overlay.eraserShown
        private const val HANDLE_REACH_DP = 28f
        private const val MIN_PICTURE = 40f

        /** How long the pen rests, and how little it may move in dp, to convert a shape (Phase 23). */
        private const val HOLD_MILLIS = 500L
        private const val HOLD_SLOP_DP = 5f
    }
}
