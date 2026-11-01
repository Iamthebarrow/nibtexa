package io.github.iamthebarrow.nibtexa.notebook

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.ColumnMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin
import io.github.iamthebarrow.nibtexa.ink.TYPED_TEXT_HEIGHT
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageView
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.export.Flow
import io.github.iamthebarrow.nibtexa.core.export.Lists
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.BookletInfo
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMarks
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import io.github.iamthebarrow.nibtexa.export.BookletPdf
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.ListAlign
import io.github.iamthebarrow.nibtexa.ink.InkColors
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.RegionMark
import io.github.iamthebarrow.nibtexa.ink.RegionOutline
import io.github.iamthebarrow.nibtexa.ink.SurfaceMode
import io.github.iamthebarrow.nibtexa.ink.typedContent
import io.github.iamthebarrow.nibtexa.ink.typedMaths
import io.github.iamthebarrow.nibtexa.NibTeXaApplication
import io.github.iamthebarrow.nibtexa.export.AnnotatedPdf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.iamthebarrow.nibtexa.ink.TypedMath
import io.github.iamthebarrow.nibtexa.recognition.HANDWRITING_PACK_SIZE_MB
import io.github.iamthebarrow.nibtexa.sync.SyncActivity
import io.github.iamthebarrow.nibtexa.ui.theme.Nib
import io.github.iamthebarrow.nibtexa.ui.theme.NibIcons
import io.github.iamthebarrow.nibtexa.ui.theme.NibPalette
import kotlinx.coroutines.delay

/**
 * The app's one screen: the library of booklets (the home screen, Phase 12), or the open
 * booklet. Back in a booklet returns to the library. The export "Save as" dialogs, the delete
 * confirmation, rename and cover colour live here, so both views share them.
 *
 * Phase 13: the open booklet is the page first, tools on demand. A slim top bar, a floating pen
 * bar, a selection bar only while ink is selected, and the results panel and page overview
 * only when asked for. Messages float at the bottom, and go by themselves (Phase 14).
 */
@Composable
fun NotebookScreen(modifier: Modifier = Modifier, viewModel: NotebookViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val p = Nib.palette
    // The booklet an export or a dialog is for; null means the open one.
    var target by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var recovering by remember { mutableStateOf<String?>(null) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null && activity != null) viewModel.exportPdf(uri, { BookletPdf(activity).render(it) }, target)
    }
    val saveLatex = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportLatex(uri, target)
    }
    val saveMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportMarkdown(uri, target)
    }
    val saveAnnotated = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null && activity != null) viewModel.exportAnnotatedPdf(uri, { AnnotatedPdf(activity).render(it) }, target)
    }
    // Back up: choose what, then where. Restore: choose the file, then confirm once it is checked (Phase 21).
    var choosingBackup by remember { mutableStateOf(false) }
    var backupIds by remember { mutableStateOf<Set<String>?>(null) }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportBackup(uri, backupIds)
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.prepareRestore(uri)
    }
    // Import and export both ask for the format first, then the file (Phase 18).
    var choosingExport by remember { mutableStateOf(false) }
    var choosingImport by remember { mutableStateOf(false) }
    var importFormat by remember { mutableStateOf(ImportFormat.LATEX_FILE) }
    val openImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFile(uri, importFormat)
    }
    val exports = Exports(
        choose = { id -> target = id; choosingExport = true },
        save = { format, id ->
            target = id
            val launcher = when (format) {
                ExportFormat.PDF -> savePdf
                ExportFormat.LATEX -> saveLatex
                ExportFormat.MARKDOWN -> saveMarkdown
                ExportFormat.ANNOTATED_PDF -> saveAnnotated
            }
            launcher.launch(viewModel.exportFileName(format.extension, id, annotated = format == ExportFormat.ANNOTATED_PDF))
        },
    )

    Box(modifier.fillMaxSize().background(p.ground)) {
        // Opening a booklet grows it out of the library; going back fades the library in.
        AnimatedContent(
            state.screen,
            transitionSpec = {
                if (targetState == Screen.BOOKLET) {
                    (fadeIn(tween(SCREEN_MS)) + scaleIn(tween(SCREEN_MS), initialScale = 0.96f)).togetherWith(fadeOut(tween(SCREEN_MS / 2)))
                } else {
                    (fadeIn(tween(SCREEN_MS)) + scaleIn(tween(SCREEN_MS), initialScale = 1.03f)).togetherWith(fadeOut(tween(SCREEN_MS / 2)))
                }
            },
            label = "screen",
        ) { screen ->
        when (screen) {
            Screen.LIBRARY -> LibraryScreen(
                state,
                viewModel,
                onAction = { action ->
                    when (action) {
                        is BookletAction.Rename -> renaming = action.id
                        is BookletAction.Cover -> recovering = action.id
                        is BookletAction.Export -> exports.choose(action.id)
                        is BookletAction.Delete -> viewModel.moveToTrash(action.id)
                    }
                },
                menu = { onNewFolder ->
                    AppMenu(state, viewModel) { close ->
                        if (state.openFolder == null) DropdownMenuItem(text = { Text(stringResource(R.string.folder_new)) }, onClick = { close(); onNewFolder() })
                        // The Trash, and backing up the whole library (Phase 21).
                        DropdownMenuItem(
                            text = { Text(if (state.trash.isEmpty()) stringResource(R.string.trash) else stringResource(R.string.trash_with_count, state.trash.size)) },
                            onClick = { close(); viewModel.openTrash() },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.backup_menu)) }, onClick = { close(); choosingBackup = true }, enabled = !state.exporting)
                        DropdownMenuItem(text = { Text(stringResource(R.string.restore_menu)) }, onClick = { close(); openBackup.launch(ImportFormat.LATEX_PROJECT.mimeTypes) }, enabled = !state.exporting)
                    }
                },
                onImport = { choosingImport = true },
            )
            Screen.TRASH -> {
                BackHandler(onBack = viewModel::closeTrash)
                TrashScreen(state, viewModel)
            }
            Screen.BOOKLET -> {
                BackHandler(onBack = viewModel::closeBooklet)
                BookletView(state, viewModel, exports, onRename = { renaming = state.bookletId })
            }
        }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).widthIn(max = 720.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.modelSetup?.let { percent ->
                Toast(stringResource(R.string.model_setup_progress, percent), progress = true)
            }
            Messages(state, viewModel)
        }
    }
    if (choosingExport) {
        FormatDialog(
            title = stringResource(R.string.export_as),
            formats = ExportFormat.entries.map { stringResource(it.title) to stringResource(it.description) },
            onPick = { i ->
                choosingExport = false
                exports.save(ExportFormat.entries[i], target)
            },
            onDismiss = { choosingExport = false },
        )
    }
    if (choosingImport) {
        FormatDialog(
            title = stringResource(R.string.import_as),
            formats = ImportFormat.entries.map { stringResource(it.title) to stringResource(it.description) },
            onPick = { i ->
                choosingImport = false
                importFormat = ImportFormat.entries[i]
                openImport.launch(importFormat.mimeTypes)
            },
            onDismiss = { choosingImport = false },
        )
    }
    state.importReport?.let { ImportReportDialog(it, onDismiss = viewModel::dismissImportReport) }
    renaming?.let { id ->
        NameDialog(
            title = stringResource(R.string.rename_booklet),
            initial = state.library.firstOrNull { it.id == id }?.title ?: state.bookletTitle,
            onDone = { name -> renaming = null; viewModel.renameBooklet(id, name) },
            onDismiss = { renaming = null },
        )
    }
    recovering?.let { id ->
        var cover by remember(id) { mutableIntStateOf(state.library.firstOrNull { it.id == id }?.cover ?: 0) }
        AlertDialog(
            onDismissRequest = { recovering = null },
            title = { Text(stringResource(R.string.cover_colour)) },
            text = { CoverSwatches(cover) { cover = it } },
            confirmButton = { TextButton(onClick = { recovering = null; viewModel.setCover(id, cover) }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { recovering = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (choosingBackup) {
        BackupDialog(
            state.library,
            onSave = { ids ->
                choosingBackup = false
                backupIds = ids
                saveBackup.launch(viewModel.backupFileName())
            },
            onDismiss = { choosingBackup = false },
        )
    }
    state.restorePrompt?.let { RestoreDialog(it, onRestore = viewModel::confirmRestore, onDismiss = viewModel::cancelRestore) }
    if (state.askMobileDownload) {
        AlertDialog(
            onDismissRequest = viewModel::dismissMobileDownload,
            title = { Text(stringResource(R.string.handwriting_mobile_title)) },
            text = { Text(stringResource(R.string.handwriting_mobile_text, HANDWRITING_PACK_SIZE_MB)) },
            confirmButton = { TextButton(onClick = { viewModel.downloadHandwriting(allowMobileData = true) }) { Text(stringResource(R.string.download)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissMobileDownload) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The current message, sliding up and fading in, and going by itself after a few seconds (longer
 * for longer messages and failures, which say more). Tapping it sends it away sooner. While an
 * export runs, its message stays.
 */
@Composable
private fun Messages(state: NotebookUiState, viewModel: NotebookViewModel) {
    val message = state.message
    LaunchedEffect(message, state.exporting) {
        if (message == null || state.exporting) return@LaunchedEffect
        delay(messageMillis(message))
        viewModel.dismissMessage(message)
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        // Keeps showing the last text while it slides away.
        var shown by remember { mutableStateOf(message.orEmpty()) }
        if (message != null) shown = message
        AnimatedContent(shown, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "message") { text ->
            Toast(text, progress = state.exporting, onClick = { viewModel.dismissMessage() })
        }
    }
}

/** How long a message stays: about 4 s, up to 9 s for long ones. */
internal fun messageMillis(message: String): Long = (MESSAGE_MS + (message.length - 60).coerceAtLeast(0) * MESSAGE_MS_PER_CHAR).coerceAtMost(MESSAGE_MAX_MS)

@Composable
private fun Toast(text: String, progress: Boolean, onClick: (() -> Unit)? = null) {
    val p = Nib.palette
    Surface(
        color = p.inverse,
        contentColor = p.onInverse,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(24.dp)).clickable(onClickLabel = stringResource(R.string.dismiss), onClick = onClick) else Modifier)
            .testTag(if (onClick != null) MESSAGE_TAG else SETUP_TAG),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (progress) LinearProgressIndicator(Modifier.width(40.dp).padding(end = 14.dp), color = p.primary, trackColor = p.muted)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Starts an export of a booklet (null: the open one): [choose] asks for the format (Phase 18), then
 * [pdf] or [latex] opens the system "Save as" dialog.
 */
private class Exports(val choose: (String?) -> Unit, val save: (ExportFormat, String?) -> Unit)

@Composable
private fun BookletView(state: NotebookUiState, viewModel: NotebookViewModel, exports: Exports, onRename: () -> Unit) {
    val p = Nib.palette
    var pagesOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(state, viewModel, exports, onRename)
        // On a narrow screen (portrait) the results panel comes up from the bottom, so the page keeps its
        // width and the pen bar stays whole (Phase 16); on a wide one it slides in from the side.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val bottomPanel = maxWidth < NARROW_BOOKLET.dp
            val areaWidth = maxWidth
            val areaHeight = maxHeight
            val sheetHeight = maxHeight * SHEET_FRACTION
            val panel by animateDpAsState(if (state.resultsOpen && !state.readingMode) (if (bottomPanel) sheetHeight else RESULTS_WIDTH.dp) else 0.dp, spring(stiffness = Spring.StiffnessMediumLow), label = "panel room")
            // Where a paste can be seen (Phase 26).
            SideEffect { viewModel.setViewport((areaWidth - (if (bottomPanel) 0.dp else panel)).value, (areaHeight - (if (bottomPanel) panel else 0.dp)).value) }
            // The page and the reading view fade into each other.
            Crossfade(state.readingMode, animationSpec = tween(SCREEN_MS), label = "reading") { reading ->
            if (reading) {
                // Numbers count through the booklet, so the flow is built for every page (Phase 16).
                val blocks = remember(state.pages, state.page, state.pageIndex, state.mathAlign, state.documentStyle) {
                    val pages = state.pages.map { if (it.id == state.page.id) state.page else it }
                    Flow.of(Booklet("", "", pages, BookletInfo(mathAlign = state.mathAlign, style = state.documentStyle)), preview = true).getOrNull(state.pageIndex)
                        ?: Flow.page(state.page, state.mathAlign)
                }
                ReadingView(blocks, state.page, viewModel, Modifier.fillMaxSize())
            } else {
            Box(Modifier.fillMaxSize()) {
                val colors = if (p.dark) InkColors.DARK else InkColors.LIGHT
                val outlines = outlines(state.page, state.recognizingRegionId, state.editingRegionId, p)
                // Typed regions (Phase 18) are drawn on the page: their words, and their maths as captured by MathLive.
                val typed = remember(state.page.regions) { typedContent(state.page) }
                // Equations in display style, and the inline maths in typed text, by the LaTeX the page looks them up by.
                val wanted = remember(typed) { typedMaths(typed) }
                // Pictures and the page's imported PDF page (Phase 20), decoded off the main thread.
                val pictureStore = (LocalContext.current.applicationContext as NibTeXaApplication).pictures
                val pictureNames = remember(typed) { typed.mapNotNull { it.picture }.toSet() }
                val pictureBitmaps by produceState(emptyMap<String, android.graphics.Bitmap>(), pictureNames) {
                    value = withContext(Dispatchers.IO) { pictureNames.mapNotNull { n -> pictureStore.bitmap(n, PICTURE_PIXELS)?.let { n to it } }.toMap() }
                }
                val backdrop by produceState<android.graphics.Bitmap?>(null, state.page.background) {
                    value = state.page.background?.let { n -> withContext(Dispatchers.IO) { pictureStore.bitmap(n) } }
                }
                val handle = viewModel.selectedPicture(state)?.bounds
                // A region jumped to fades out where it is (Phase 28).
                val highlighted = state.highlightRegion?.let(state.page::region)?.bounds
                val highlightStrength by animateFloatAsState(if (highlighted != null) 1f else 0f, tween(HIGHLIGHT_FADE_MS), label = "highlight")
                var highlightBounds by remember { mutableStateOf<Bounds?>(null) }
                if (highlighted != null) highlightBounds = highlighted
                val captures = rememberInlineMath(wanted.map { it.second })
                val captured = captures.toMap()
                // Kept while nothing new is captured, so zooming does not lay the typed text out again.
                val pageMaths = remember(wanted, captured) { wanted.mapNotNull { (key, latex) -> captured[latex]?.let { key to TypedMath(it.bitmap, it.baseline) } }.toMap() }
                AndroidView(
                    factory = { context ->
                        InkSurfaceView(context).apply {
                            onStrokeCaptured = viewModel::onStrokeCaptured
                            onShape = viewModel::onShape
                            onLasso = viewModel::onLasso
                            onTap = viewModel::onTap
                            onMoveSelection = viewModel::moveSelection
                            onDragging = viewModel::setDraggingSelection
                            onResize = viewModel::resizePicture
                            onGraphView = viewModel::setGraphView
                            onDiagramEdit = viewModel::onDiagramEdit
                            onDiagramTap = viewModel::onDiagramTap
                            onErase = viewModel::onErase
                            onEraseEnd = viewModel::onEraseEnd
                            onEraseCancel = viewModel::onEraseCancel
                            onView = viewModel::onView
                        }
                    },
                    update = { view ->
                        view.setColors(colors)
                        view.fingerDraws = state.fingerDraws
                        view.penButtons = state.penButtons
                        view.eraserRadius = state.eraser.size.radius
                        // What a pen button selected can be moved or tapped away, and then the tool is back (Phase 34).
                        val selecting = state.tool == Tool.LASSO || state.selection.isNotEmpty() || state.selectedTyped.isNotEmpty()
                        view.mode = when {
                            selecting -> SurfaceMode.LASSO
                            state.tool == Tool.ERASER -> SurfaceMode.ERASER
                            state.tool == Tool.MOVE -> SurfaceMode.MOVE
                            else -> SurfaceMode.INK
                        }
                        state.tool.pen?.let { view.pen = it }
                        state.tool.pen?.let { view.kind = state.kindOf(it) }
                        view.convertsShapes = state.tool == Tool.DRAWING_PEN
                        view.keepsInk = state.tool != Tool.DIAGRAM_PEN
                        view.snapsShapes = state.snapShapes
                        view.setPaper(state.page.paper)
                        view.setDocument(state.page.ink)
                        view.setSheet(state.sheet)
                        view.setWritingArea(viewModel.writingArea(state.page, state))
                        val resizing = viewModel.selectedPicture(state)?.takeIf { it.picture == null && it.graph == null && it.diagram == null }
                        view.setTextHeight(resizing?.let { region -> { width: Float -> TYPED_TEXT_HEIGHT.height(region, width) } })
                        view.setView(state.page.view, state.page.id)
                        view.setRegions(outlines)
                        view.setTyped(typed, pageMaths)
                        view.setPictures(pictureBitmaps)
                        view.setBackdrop(backdrop)
                        view.setSelection(viewModel.selectionBounds(state))
                        view.setResizeHandle(if (selecting) handle else null)
                        view.setHighlight(highlightBounds, highlightStrength)
                        val openGraph = state.editingRegionId?.takeIf { state.resultsOpen }?.let { state.page.region(it) }?.takeIf { it.graph != null }
                        view.setOpenGraph(openGraph?.id, openGraph?.bounds, openGraph?.graph)
                        // With the lasso, an open diagram's parts can be chosen and moved (Phase 31).
                        val openDiagram = state.editingRegionId?.takeIf { state.resultsOpen && state.selectedTyped.isEmpty() && state.selection.isEmpty() }?.let { state.page.region(it) }?.takeIf { it.diagram != null }
                        val arranging = openDiagram?.takeIf { state.tool == Tool.LASSO }
                        view.setOpenDiagram(arranging?.id, arranging?.bounds, arranging?.diagram, state.diagramPart.takeIf { openDiagram != null })
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // In the space the results panel leaves, so the panel never covers it; it moves
                // aside (or up) as the panel slides in.
                // Out of the way while the selection is dragged, so that the whole page shows.
                val penBarShown by animateFloatAsState(if (state.draggingSelection) 0f else 1f, tween(DRAG_FADE_MS), label = "pen bar shown")
                PenBarDock(state, viewModel, Modifier.fillMaxSize().padding(end = if (bottomPanel) 0.dp else panel, bottom = if (bottomPanel) panel else 0.dp).graphicsLayer { alpha = penBarShown })
                SelectionBar(state, viewModel, Modifier.fillMaxSize().padding(end = if (bottomPanel) 0.dp else panel, bottom = if (bottomPanel) panel else 0.dp))
            }
            }
            }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(24.dp).padding(bottom = if (bottomPanel) panel else 0.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = !state.resultsOpen && !state.readingMode,
                    enter = fadeIn() + scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.7f),
                    exit = fadeOut() + scaleOut(targetScale = 0.7f),
                ) { ResultsButton(state, viewModel) }
                if (!state.readingMode) ZoomPill(state, viewModel, pageWidth = areaWidth - (if (!bottomPanel) panel else 0.dp), pageHeight = areaHeight - (if (bottomPanel) panel else 0.dp))
                FloatingPill(
                    icon = NibIcons.Pages,
                    label = stringResource(R.string.page_position, state.pageIndex + 1, state.pageCount),
                    onClick = { pagesOpen = true },
                    container = p.surface,
                    content = p.ink,
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = state.resultsOpen && !state.readingMode && bottomPanel,
                enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                exit = slideOutVertically(tween(SCREEN_MS)) { it } + fadeOut(tween(SCREEN_MS)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ResultsPanel(state, viewModel, Modifier.fillMaxWidth().height(sheetHeight))
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = state.resultsOpen && !state.readingMode && !bottomPanel,
                enter = slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                exit = slideOutHorizontally(tween(SCREEN_MS)) { it } + fadeOut(tween(SCREEN_MS)),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                ResultsPanel(state, viewModel, Modifier.width(RESULTS_WIDTH.dp).fillMaxHeight())
            }
        }
    }
    if (pagesOpen) PageOverview(state, viewModel, onDismiss = { pagesOpen = false })
    if (state.clipboardOpen) ClipboardPanel(state, viewModel)
}

// ---- Top bar -------------------------------------------------------------------------------

/** Back, the title (tap to rename), undo and redo, reading view, Export and More. */
@Composable
private fun TopBar(state: NotebookUiState, viewModel: NotebookViewModel, exports: Exports, onRename: () -> Unit) {
    val context = LocalContext.current
    val p = Nib.palette
    var coverDialog by remember { mutableStateOf(false) }
    var pageLayout by remember { mutableStateOf(false) }
    if (coverDialog) CoverDialog(state, viewModel, onDismiss = { coverDialog = false })
    if (pageLayout) PageLayoutDialog(state, viewModel) { pageLayout = false }
    // Pictures come from the system photo picker (Phase 20), which needs no storage permission.
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) viewModel.insertPicture(uri) }
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(p.surface).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = viewModel::closeBooklet) { Icon(NibIcons.Back, contentDescription = stringResource(R.string.back_to_library), tint = p.ink) }
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = state.loaded, onClickLabel = stringResource(R.string.rename_booklet), onClick = onRename)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(state.bookletTitle.ifEmpty { stringResource(R.string.app_name) }, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedContent(state.savedVersion >= state.version, transitionSpec = { fadeIn().togetherWith(fadeOut()) }, label = "saved") { saved ->
                Text(stringResource(if (saved) R.string.saved else R.string.saving), style = MaterialTheme.typography.labelSmall, color = p.muted)
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
            val tint by animateColorAsState(if (state.canUndo) p.ink else p.line, label = "undo")
            Icon(NibIcons.Undo, contentDescription = stringResource(R.string.undo), tint = tint)
        }
        IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
            val tint by animateColorAsState(if (state.canRedo) p.ink else p.line, label = "redo")
            Icon(NibIcons.Redo, contentDescription = stringResource(R.string.redo), tint = tint)
        }
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(28.dp).background(p.line))
        IconButton(
            onClick = { viewModel.setReadingMode(!state.readingMode) },
            modifier = Modifier.clip(CircleShape).background(if (state.readingMode) p.primarySoft else Color.Transparent).semantics { selected = state.readingMode },
        ) {
            Icon(NibIcons.Reading, contentDescription = stringResource(R.string.reading_mode), tint = if (state.readingMode) p.primaryInk else p.ink)
        }
        Box {
            Button(
                onClick = { exports.choose(null) },
                enabled = state.loaded && !state.exporting,
                colors = ButtonDefaults.buttonColors(containerColor = p.primarySoft, contentColor = p.primaryInk),
                contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
                modifier = Modifier.padding(horizontal = 6.dp).height(44.dp),
            ) {
                Icon(NibIcons.Export, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export))
            }
        }
        AppMenu(state, viewModel) { close ->
            // Export asks for the format (Phase 18); the cover and Git sync are here.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.insert_picture)) },
                onClick = { close(); pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = state.loaded,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.clipboard)) },
                leadingIcon = { Icon(NibIcons.Clipboard, contentDescription = null) },
                onClick = { close(); viewModel.setClipboardOpen(true) },
                enabled = state.loaded && !state.readingMode,
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.cover_layout)) }, onClick = { close(); coverDialog = true })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sync_open)) },
                leadingIcon = { Icon(NibIcons.Sync, contentDescription = null) },
                onClick = { close(); viewModel.whenSaved { id -> context.startActivity(SyncActivity.intent(context, id)) } },
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.page_layout)) }, onClick = { close(); pageLayout = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.clear_page)) }, onClick = { close(); viewModel.clearInk() }, enabled = state.page.ink.strokes.isNotEmpty())
            HorizontalDivider()
        }
    }
}

/**
 * "More": only what belongs to where it opens ([extra], the library's or the open booklet's), and then Settings, which holds
 * what applies to the whole app (Phase 29).
 */
@Composable
private fun AppMenu(state: NotebookUiState, viewModel: NotebookViewModel, extra: @Composable (close: () -> Unit) -> Unit = {}) {
    val p = Nib.palette
    var open by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    if (settingsOpen) SettingsPanel(state, viewModel) { settingsOpen = false }
    Box {
        IconButton(onClick = { open = true }) { Icon(NibIcons.More, contentDescription = stringResource(R.string.more), tint = p.ink) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            extra { open = false }
            DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { open = false; settingsOpen = true })
        }
    }
}

/** This page's paper, columns and margin (Phase 30), which apply as they are chosen. */
@Composable
private fun PageLayoutDialog(state: NotebookUiState, viewModel: NotebookViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.page_layout_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.paper), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Paper.entries.forEach { paper -> FilterChip(selected = state.page.paper == paper, onClick = { viewModel.setPaper(paper) }, label = { Text(paperName(paper)) }) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.columns), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ColumnMode.AUTO to R.string.columns_auto, ColumnMode.ONE to R.string.columns_one, ColumnMode.TWO to R.string.columns_two).forEach { (mode, name) ->
                            FilterChip(selected = state.page.columns == mode, onClick = { viewModel.setColumns(mode) }, label = { Text(stringResource(name)) })
                        }
                    }
                }
                MarginChoice(state.page.margin, sameAsBooklet = true) { viewModel.setPageMargin(it) }
                TextButton(onClick = viewModel::fitPageToMargins) { Text(stringResource(R.string.fit_to_margins)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** The open booklet's cover author (blank: the app's author name) and where its equations sit. */
@Composable
private fun CoverDialog(state: NotebookUiState, viewModel: NotebookViewModel, onDismiss: () -> Unit) {
    var author by remember { mutableStateOf(state.bookletAuthor.orEmpty()) }
    var align by remember { mutableStateOf(state.mathAlign) }
    var sheet by remember { mutableStateOf(state.sheet) }
    var style by remember { mutableStateOf(state.documentStyle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cover_layout_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.author_booklet)) },
                    placeholder = { Text(state.appAuthor.ifEmpty { stringResource(R.string.author_none) }) },
                    supportingText = { Text(stringResource(R.string.author_booklet_hint)) },
                )
                Text(stringResource(R.string.math_align), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MathAlign.entries.forEach { option ->
                        FilterChip(selected = align == option, onClick = { align = option }, label = { Text(alignName(option)) })
                    }
                }
                SheetChoice(sheet) { sheet = it }
                MarginChoice(style.margin) { style = style.copy(margin = it ?: PageMargin.NORMAL) }
                DocumentSwitch(R.string.style_title_on_page, style.titleOnFirstPage) { style = style.copy(titleOnFirstPage = it) }
                DocumentSwitch(R.string.style_numbered, style.numberedSections) { style = style.copy(numberedSections = it) }
                DocumentSwitch(R.string.style_header, style.runningHeader) { style = style.copy(runningHeader = it) }
                Text(stringResource(R.string.cover_layout_note), style = MaterialTheme.typography.bodySmall, color = Nib.palette.muted)
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); viewModel.setBookletInfo(author, align, sheet, style) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DocumentSwitch(name: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun alignName(align: MathAlign): String = stringResource(
    when (align) {
        MathAlign.LEFT -> R.string.align_left
        MathAlign.CENTER -> R.string.align_center
        MathAlign.RIGHT -> R.string.align_right
    },
)

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.booklet_title)) }) },
        confirmButton = { TextButton(onClick = { onDone(name) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---- Selection bar (the pen bar is in PenBarDock.kt) --------------------------------------

/**
 * Next to selected ink: Copy, Delete, Convert and Make region (with its roles), and once something
 * is copied, Paste and the clipboard. With nothing selected, only those two, where a tap with the lasso asked for them (Phase 29).
 */
@Composable
private fun SelectionBar(state: NotebookUiState, viewModel: NotebookViewModel, modifier: Modifier = Modifier) = BoxWithConstraints(modifier) {
    val p = Nib.palette
    val hasInk = state.selection.isNotEmpty()
    val hasSelection = hasInk || state.selectedTyped.isNotEmpty()
    val offer = state.pasteOffer?.takeIf { state.canPaste && state.tool == Tool.LASSO && it.pageId == state.page.id }
    // Not while the selection is dragged: it would cover where it is going.
    val visible = (hasSelection || offer != null) && !state.draggingSelection
    val page = viewModel.selectionBounds(state).reduceOrNull { a, b -> a.union(b) } ?: offer?.let { Bounds(it.x, it.y, it.x, it.y) }
    // On screen, at the page's zoom and position (Phase 17).
    val view = state.page.view ?: PageView()
    val bounds = page?.let { Bounds((it.left - view.left) * view.scale, (it.top - view.top) * view.scale, (it.right - view.left) * view.scale, (it.bottom - view.top) * view.scale) }
    // Above the selection or the tap, clear of the pen bar; below it if there is no room above, so that it doesn't cover what is
    // selected unless that fills the screen. It always stays whole on screen: near the right edge (or the results panel) it moves left (Phase 16, portrait).
    val density = androidx.compose.ui.platform.LocalDensity.current
    var barWidth by remember { mutableIntStateOf(0) }
    val target = with(density) {
        val edge = SELECTION_EDGE.dp.roundToPx()
        val right = (maxWidth.roundToPx() - barWidth - edge).coerceAtLeast(edge)
        val penBarAbove = state.penDock.name.startsWith("TOP")
        val penBarBelow = state.penDock.name.startsWith("BOTTOM")
        val highest = if (penBarAbove) PageView.PEN_BAR_ROOM else SELECTION_EDGE.toFloat()
        val lowest = maxHeight.value - SELECTION_BAR_HEIGHT - if (penBarBelow) PageView.PEN_BAR_ROOM else SELECTION_EDGE.toFloat()
        val above = (bounds?.top ?: 0f) - SELECTION_BAR_HEIGHT
        val below = (bounds?.bottom ?: 0f) + SELECTION_BAR_GAP
        val y = when {
            above >= highest -> above
            below <= lowest -> below
            else -> highest
        }
        IntOffset((bounds?.left ?: 24f).dp.roundToPx().coerceIn(edge, right), y.dp.roundToPx())
    }
    val place by animateIntOffsetAsState(target, spring(stiffness = Spring.StiffnessMediumLow), label = "selection bar")
    var roleMenu by remember { mutableStateOf(false) }
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = Modifier.offset { place }.onSizeChanged { barWidth = it.width },
    ) {
    Row(
        Modifier.shadow(10.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(p.inverse).padding(6.dp).animateContentSize()
            .testTag(SELECTION_BAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Typed regions (Phase 18) can be moved, deleted and copied (Phase 26); converting and regrouping are for ink.
        if (hasSelection) {
            SelectionAction(NibIcons.Delete, stringResource(R.string.delete), viewModel::deleteSelection)
            SelectionAction(NibIcons.Copy, stringResource(R.string.copy), viewModel::copySelection)
        }
        if (hasInk) {
            // A conversion requested while another runs waits its turn, so Convert stays available.
            SelectionAction(NibIcons.Convert, stringResource(R.string.convert)) { viewModel.makeRegion() }
            Box {
                SelectionAction(NibIcons.Region, stringResource(R.string.make_region)) { roleMenu = true }
                DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                    // Headings by level, so a title is one choice (Phase 15).
                    RegionRole.entries.forEach { role ->
                        if (role == RegionRole.HEADING) {
                            (1..3).forEach { level ->
                                DropdownMenuItem(text = { Text(stringResource(R.string.kind_heading, level)) }, onClick = { roleMenu = false; viewModel.makeRegion(role, level) })
                            }
                        } else {
                            DropdownMenuItem(text = { Text(roleName(role)) }, onClick = { roleMenu = false; viewModel.makeRegion(role) })
                        }
                    }
                }
            }
        }
        if (state.canPaste) {
            SelectionAction(NibIcons.Paste, stringResource(R.string.paste)) { viewModel.paste() }
            SelectionAction(NibIcons.Clipboard, stringResource(R.string.clipboard)) { viewModel.setClipboardOpen(true) }
        }
    }
    }
}

@Composable
private fun SelectionAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val p = Nib.palette
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = p.onInverse), contentPadding = PaddingValues(horizontal = 14.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

// ---- Results ---------------------------------------------------------------------------------

/** Regions whose result waits for the user (scratch never does: it is only for search). */
private fun waiting(page: Page) = page.regions.count { it.role != RegionRole.EXCLUDED && it.status == RecognitionStatus.RECOGNIZED && !page.isStale(it) }

@Composable
private fun ResultsButton(state: NotebookUiState, viewModel: NotebookViewModel) {
    val p = Nib.palette
    val count = waiting(state.page)
    FloatingPill(
        icon = NibIcons.Check,
        label = if (count > 0) pluralStringResource(R.plurals.to_confirm, count, count) else stringResource(R.string.results),
        onClick = { viewModel.setResultsOpen(true) },
        container = if (count > 0) p.primary else p.surface,
        content = if (count > 0) p.onPrimary else p.ink,
    )
}

/** A floating button. Its colour fades and its label rolls when they change (a new count, a new page). */
@Composable
private fun FloatingPill(icon: ImageVector, label: String, onClick: () -> Unit, container: Color, content: Color) {
    val fill by animateColorAsState(container, label = "pill")
    val ink by animateColorAsState(content, label = "pill text")
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = ink),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 20.dp),
        modifier = Modifier.height(50.dp).animateContentSize(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        AnimatedContent(
            label,
            transitionSpec = { (slideInVertically { it } + fadeIn()).togetherWith(slideOutVertically { -it } + fadeOut()) },
            label = "pill label",
        ) { Text(it) }
    }
}

/**
 * The results panel (Phase 13): the region being edited first, then a card per region in its
 * pen's colour with Confirm and Edit, then the reading order and the LaTeX preview.
 */
@Composable
private fun ResultsPanel(state: NotebookUiState, viewModel: NotebookViewModel, modifier: Modifier) {
    val p = Nib.palette
    val page = state.page
    val editing = state.editingRegionId?.let(page::region)
    val count = waiting(page)
    Column(
        modifier.shadow(16.dp).background(p.surface).padding(horizontal = 20.dp, vertical = 16.dp).testTag(RESULTS_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.results), style = MaterialTheme.typography.titleLarge, color = p.ink)
                Text(
                    if (count > 0) pluralStringResource(R.plurals.to_confirm, count, count) else stringResource(R.string.results_all_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                )
            }
            if (count > 0) {
                Button(onClick = viewModel::confirmAll, colors = ButtonDefaults.buttonColors(containerColor = p.primary, contentColor = p.onPrimary)) {
                    Text(stringResource(R.string.confirm_all))
                }
            }
            IconButton(onClick = { viewModel.setResultsOpen(false) }) { Icon(NibIcons.Close, contentDescription = stringResource(R.string.close_results), tint = p.ink) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editing != null) {
                PanelCard(editing.role) { RegionEditor(state, editing, viewModel) }
            }
            val others = page.regionsInReadingOrder.filter { it.id != editing?.id && it.role != RegionRole.EXCLUDED }
            if (page.regions.isEmpty()) {
                Text(stringResource(if (state.autoRegions) R.string.no_regions else R.string.no_regions_manual), style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
            others.forEach { region -> ResultCard(page, region, viewModel) }
            if (page.regions.size > 1 || state.preview != null) ReadingOrderSection(state, viewModel)
        }
    }
}

@Composable
private fun PanelCard(role: RegionRole, content: @Composable () -> Unit) {
    val p = Nib.palette
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).border(1.dp, p.line, RoundedCornerShape(16.dp)).animateContentSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RoleChip(role)
        content()
    }
}

@Composable
private fun RoleChip(role: RegionRole, state: String? = null) {
    val p = Nib.palette
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            roleName(role),
            style = MaterialTheme.typography.labelMedium,
            color = p.roleColor(role),
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(p.roleChip(role)).padding(horizontal = 10.dp, vertical = 3.dp),
        )
        state?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = p.muted) }
    }
}

/** One region: its result (or what it needs), Confirm for a waiting result, and Edit. */
@Composable
private fun ResultCard(page: Page, region: Region, viewModel: NotebookViewModel) {
    val p = Nib.palette
    val value = when (region.role) {
        RegionRole.MATH -> if (region.status == RecognitionStatus.RECOGNIZED) region.recognizedLatex else region.confirmedLatex ?: region.recognizedLatex
        RegionRole.TEXT, RegionRole.HEADING -> if (region.status == RecognitionStatus.RECOGNIZED) region.recognizedText else region.text ?: region.recognizedText
        RegionRole.FIGURE -> region.caption
        RegionRole.EXCLUDED -> null
    }
    val waiting = region.status == RecognitionStatus.RECOGNIZED && !page.isStale(region)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).border(1.dp, p.line, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = stringResource(R.string.edit)) { viewModel.openRegion(region.id) }.animateContentSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoleChip(region.role, statusText(page, region, recognizing = false))
        if (!value.isNullOrBlank()) {
            if (region.role == RegionRole.MATH) {
                // Rendered, never raw LaTeX; the editor opens with Edit.
                EquationList(listOf(value), Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)), minHeight = MATH_CARD_HEIGHT.dp)
            } else {
                Text(
                    value,
                    style = if (region.role == RegionRole.HEADING) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = p.ink,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (waiting) {
                Button(
                    onClick = { viewModel.confirm(region.id, region.recognizedValue.orEmpty()) },
                    colors = ButtonDefaults.buttonColors(containerColor = p.primarySoft, contentColor = p.primaryInk),
                ) { Text(stringResource(R.string.confirm)) }
            }
            OutlinedButton(onClick = { viewModel.openRegion(region.id) }) { Text(stringResource(R.string.edit)) }
        }
    }
}

@Composable
private fun ReadingOrderSection(state: NotebookUiState, viewModel: NotebookViewModel) {
    val p = Nib.palette
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(NibIcons.ReadingOrder, contentDescription = null, tint = p.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.page_contents), style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.weight(1f))
        }
        Row {
            // By position unless the user arranged it by hand (Phase 14); then this goes back.
            TextButton(onClick = viewModel::orderByPosition, enabled = state.page.manualOrder) { Text(stringResource(R.string.order_by_position)) }
            TextButton(onClick = { viewModel.showPreview(state.preview == null) }) {
                Text(stringResource(if (state.preview == null) R.string.show_latex else R.string.hide_latex))
            }
        }
        Text(
            stringResource(if (state.page.manualOrder) R.string.reading_order_manual else R.string.reading_order_auto),
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
        )
        val preview = state.preview
        if (preview != null) {
            Text(
                preview,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = p.ink,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.ground).padding(12.dp).testTag(PREVIEW_TAG),
            )
        } else {
            state.page.regionsInReadingOrder.forEach { r -> RegionRow(state.page, r, viewModel) }
        }
    }
}

@Composable
private fun RegionRow(page: Page, region: Region, viewModel: NotebookViewModel) {
    val p = Nib.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(p.roleColor(region.role)))
        TextButton(onClick = { viewModel.openRegion(region.id) }, modifier = Modifier.weight(1f)) {
            val ready = if (page.isExportable(region) || region.role == RegionRole.EXCLUDED) "" else " · " + stringResource(R.string.not_ready)
            Text(stringResource(R.string.region_row, region.id, roleName(region.role), ready), color = p.ink, modifier = Modifier.fillMaxWidth())
        }
        TextButton(onClick = { viewModel.moveRegion(region.id, -1) }) { Text("↑", color = p.ink) }
        TextButton(onClick = { viewModel.moveRegion(region.id, +1) }) { Text("↓", color = p.ink) }
    }
}

// ---- Page overview ---------------------------------------------------------------------------

/**
 * Every page as a thumbnail (the current one marked), with Add page and Delete page (the current
 * one, after asking: undo does not cover pages). The last page can't be deleted.
 */
@Composable
private fun PageOverview(state: NotebookUiState, viewModel: NotebookViewModel, onDismiss: () -> Unit) {
    val p = Nib.palette
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_page_title, state.pageIndex + 1)) },
            text = { Text(stringResource(R.string.delete_page_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deletePage(state.pageIndex) }) { Text(stringResource(R.string.delete), color = p.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    var tab by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(24.dp)).background(p.surface).padding(20.dp).testTag(PAGES_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Pages, and the two ways of finding something in them (Phase 28).
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = p.ink, modifier = Modifier.weight(1f)) {
                    listOf(R.string.pages_title, R.string.contents_title, R.string.find_title).forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(title), maxLines = 1) })
                    }
                }
                IconButton(onClick = onDismiss) { Icon(NibIcons.Close, contentDescription = stringResource(R.string.close), tint = p.ink) }
            }
            when (tab) {
                1 -> ContentsTab(state, viewModel, onDismiss)
                2 -> FindTab(state, viewModel, onDismiss)
                else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { confirmDelete = true }, enabled = state.loaded && state.pageCount > 1, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(NibIcons.Delete, contentDescription = null, tint = if (state.pageCount > 1) p.error else p.line, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.delete_page))
                }
                Button(onClick = { viewModel.addPage(); onDismiss() }, enabled = state.loaded, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent)) {
                    Text(stringResource(R.string.add_page))
                }
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(132.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
                    val current = index == state.pageIndex
                    val label = stringResource(R.string.page_thumbnail, index + 1)
                    val shape = RoundedCornerShape(8.dp)
                    Column(Modifier.animateItem(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PageThumbnail(
                            page,
                            state.sheet,
                            Modifier.fillMaxWidth().aspectRatio(state.sheet.aspect()).clip(shape)
                                .border(if (current) 3.dp else 1.dp, if (current) p.primary else p.line, shape)
                                .clickable(onClickLabel = label) { viewModel.goToPage(index); onDismiss() }
                                .semantics { contentDescription = label; selected = current },
                        )
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = if (current) p.primary else p.muted)
                    }
                }
            }
                }
            }
        }
    }
}

// ---- Region editors (in the results panel) ---------------------------------------------------

@Composable
private fun RegionEditor(state: NotebookUiState, region: Region, viewModel: NotebookViewModel) {
    val page = state.page
    val stale = page.isStale(region)
    val recognizing = state.recognizingRegionId == region.id
    var roleMenu by remember { mutableStateOf(false) }

    Column(Modifier.testTag(EDITOR_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.region_title, region.id, statusText(page, region, recognizing)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { roleMenu = true }, enabled = !recognizing) { Text(roleName(region.role)) }
                DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                    RegionRole.entries.forEach { role ->
                        DropdownMenuItem(text = { Text(roleName(role)) }, onClick = { roleMenu = false; viewModel.changeRole(region.id, role) })
                    }
                }
            }
        }
        if (recognizing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = viewModel::cancelRecognition) { Text(stringResource(R.string.cancel)) }
            return@Column
        }
        when (region.role) {
            RegionRole.MATH -> MathRegionEditor(state, region, viewModel)
            RegionRole.TEXT, RegionRole.HEADING -> TextRegionEditor(state, region, stale, viewModel)
            RegionRole.FIGURE -> {
                GraphEditor(state, region, viewModel)
                DiagramEditor(state, region, viewModel)
                FigureRegionEditor(region, viewModel)
            }
            RegionRole.EXCLUDED -> Text(stringResource(R.string.excluded_hint), style = MaterialTheme.typography.bodyMedium)
        }
        if (region.role != RegionRole.EXCLUDED) {
            PlacementRow(page, region, state.mathAlign, viewModel)
            LayoutRows(region, viewModel)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { viewModel.deleteRegion(region.id) }) { Text(stringResource(R.string.delete_region), color = Nib.palette.error) }
            TextButton(onClick = { viewModel.openRegion(null) }) { Text(stringResource(R.string.close)) }
        }
    }
}

/**
 * Where the region sits across the exported page (Phase 14): left, centre or right, changeable
 * at any time. The selected chip is where it goes now: its own choice, or its role's default (the
 * booklet's setting for equations); "Use default" drops its own choice.
 */
@Composable
private fun PlacementRow(page: Page, region: Region, bookletMath: MathAlign, viewModel: NotebookViewModel) {
    val placed = page.alignOf(region, bookletMath)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.placement), style = MaterialTheme.typography.labelLarge, color = Nib.palette.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MathAlign.entries.forEach { option ->
                FilterChip(selected = placed == option, onClick = { viewModel.setAlign(region.id, option) }, label = { Text(alignName(option)) })
            }
            if (region.align != null) TextButton(onClick = { viewModel.setAlign(region.id, null) }) { Text(stringResource(R.string.placement_default)) }
        }
    }
}

/**
 * How the region fits into the document (Phase 16). Each choice is the region's own; tapping a
 * selected chip goes back to following the page ("By position").
 * - equations: numbered, and in the text or on their own line;
 * - text: a new paragraph or joined to the line before; a list item's level;
 * - any region: a new page before it.
 */
@Composable
private fun LayoutRows(region: Region, viewModel: NotebookViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.layout), style = MaterialTheme.typography.labelLarge, color = Nib.palette.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            @Composable
            fun choice(selected: Boolean, label: String, onClick: () -> Unit) = FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
            when (region.role) {
                RegionRole.MATH -> {
                    choice(region.inline == true, stringResource(R.string.math_inline)) { viewModel.setInline(region.id, if (region.inline == true) null else true) }
                    choice(region.inline == false, stringResource(R.string.math_own_line)) { viewModel.setInline(region.id, if (region.inline == false) null else false) }
                }
                RegionRole.TEXT -> {
                    choice(region.newParagraph == true, stringResource(R.string.new_paragraph)) { viewModel.setNewParagraph(region.id, if (region.newParagraph == true) null else true) }
                    choice(region.newParagraph == false, stringResource(R.string.join_paragraph)) { viewModel.setNewParagraph(region.id, if (region.newParagraph == false) null else false) }
                    if (Lists.item(region.text.orEmpty().trim()) != null) {
                        (0..Flow.MAX_LEVEL).forEach { level ->
                            choice(region.listLevel == level, stringResource(R.string.list_level, level + 1)) {
                                viewModel.setListLevel(region.id, if (region.listLevel == level) null else level)
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
        if (region.role == RegionRole.MATH) SwitchRow(stringResource(R.string.number_equation), region.numbered) { viewModel.setNumbered(region.id, it) }
        SwitchRow(stringResource(R.string.page_break), region.pageBreak) { viewModel.setPageBreak(region.id, it) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun theoremName(kind: TheoremKind): String = stringResource(
    when (kind) {
        TheoremKind.DEFINITION -> R.string.theorem_definition
        TheoremKind.THEOREM -> R.string.theorem_theorem
        TheoremKind.LEMMA -> R.string.theorem_lemma
        TheoremKind.PROOF -> R.string.theorem_proof
        TheoremKind.EXAMPLE -> R.string.theorem_example
        TheoremKind.REMARK -> R.string.theorem_remark
        TheoremKind.ABSTRACT -> R.string.theorem_abstract
    },
)

@Composable
private fun MathRegionEditor(state: NotebookUiState, region: Region, viewModel: NotebookViewModel) {
    val page = state.page
    // What the editor shows: the recognizer's result if it is newer than the confirmation.
    val initial = when (region.status) {
        RecognitionStatus.RECOGNIZED -> region.recognizedLatex
        else -> region.confirmedLatex ?: region.recognizedLatex
    }.orEmpty()
    var editorInitial by remember(region.id, initial) { mutableStateOf(initial) }
    var current by remember(region.id, initial) { mutableStateOf(initial) }

    region.error?.takeIf { region.status == RecognitionStatus.FAILED }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    val previous = region.confirmedLatex
    if (region.status == RecognitionStatus.RECOGNIZED && previous != null && previous != region.recognizedLatex) {
        Text(stringResource(R.string.previous_correction, previous), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        TextButton(onClick = { editorInitial = previous }) { Text(stringResource(R.string.use_previous)) }
    }
    MathEditor(region.id, editorInitial, onLatexChange = { current = it }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.confirm(region.id, current) }, enabled = current.isNotBlank() && (page.membersOf(region).isNotEmpty() || region.typed)) {
            Text(stringResource(R.string.confirm))
        }
        OutlinedButton(onClick = { viewModel.convert(region.id) }, enabled = state.recognizingRegionId == null && page.membersOf(region).isNotEmpty()) {
            Text(stringResource(if (region.status == RecognitionStatus.NOT_REQUESTED) R.string.convert else R.string.reconvert))
        }
        if (region.status == RecognitionStatus.CONFIRMED && !region.confirmedLatex.isNullOrBlank()) {
            OutlinedButton(onClick = { viewModel.graphEquation(region.id) }, modifier = Modifier.testTag(GRAPH_EQUATION_TAG)) { Text(stringResource(R.string.graph_equation)) }
        }
    }
}

@Composable
private fun TextRegionEditor(state: NotebookUiState, region: Region, stale: Boolean, viewModel: NotebookViewModel) {
    // As for math: the recognizer's result if it is newer than the confirmation, ready to check.
    val initial = when (region.status) {
        RecognitionStatus.RECOGNIZED -> region.recognizedText
        else -> region.text ?: region.recognizedText
    }.orEmpty()
    // Formatting (Phase 16) follows the words onto a newer result.
    val initialMarks = if (region.text != null && region.text != initial) TextMarks.follow(region.marks, region.text!!, initial) else region.marks
    var value by remember(region.id, region.role, initial) { mutableStateOf(endOf(initial)) }
    var marks by remember(region.id, region.role, initial) { mutableStateOf(initialMarks) }
    region.error?.takeIf { region.status == RecognitionStatus.FAILED }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    // Plain text or a heading level, one tap each (Phase 15); the confirmed words stay.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val plain = stringResource(R.string.kind_text_description)
        FilterChip(
            selected = region.role == RegionRole.TEXT,
            onClick = { viewModel.setTextKind(region.id, null) },
            label = { Text(stringResource(R.string.kind_text)) },
            modifier = Modifier.semantics { contentDescription = plain },
        )
        (1..3).forEach { level ->
            val on = region.role == RegionRole.HEADING && region.headingLevel == level
            val name = stringResource(R.string.kind_heading, level)
            FilterChip(
                selected = on,
                onClick = { viewModel.setTextKind(region.id, level) },
                label = { Text(stringResource(R.string.kind_heading_short, level)) },
                modifier = Modifier.semantics { contentDescription = name },
            )
        }
    }
    // Theorem-style blocks (Phase 16): tapping the chosen one makes the region plain text again.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TheoremKind.entries.forEach { kind ->
            val on = region.role == RegionRole.TEXT && region.theorem == kind
            FilterChip(selected = on, onClick = { viewModel.setTheorem(region.id, if (on) null else kind) }, label = { Text(theoremName(kind)) })
        }
    }
    if (stale && region.status == RecognitionStatus.CONFIRMED) {
        Text(stringResource(R.string.status_stale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    FormatBar(value, marks) { marks = it }
    FormattedTextField(
        value = value,
        marks = marks,
        onChange = { v, m -> value = v; marks = m },
        label = stringResource(R.string.transcription),
        minLines = if (region.role == RegionRole.TEXT) 3 else 1,
        modifier = Modifier.fillMaxWidth().testTag(TEXT_FIELD_TAG),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.confirm(region.id, value.text, marks) }, enabled = value.text.isNotBlank()) { Text(stringResource(R.string.confirm)) }
        OutlinedButton(onClick = { viewModel.convert(region.id) }, enabled = state.page.membersOf(region).isNotEmpty()) {
            Text(stringResource(if (region.status == RecognitionStatus.NOT_REQUESTED) R.string.convert else R.string.reconvert))
        }
    }
}

@Composable
private fun FigureRegionEditor(region: Region, viewModel: NotebookViewModel) {
    var caption by remember(region.id, region.caption) { mutableStateOf(region.caption.orEmpty()) }
    if (region.graph == null && region.diagram == null) Text(stringResource(R.string.figure_hint), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = caption,
        onValueChange = { caption = it },
        label = { Text(stringResource(R.string.caption)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(CAPTION_FIELD_TAG),
    )
    Button(onClick = { viewModel.setCaption(region.id, caption) }) { Text(stringResource(R.string.save_caption)) }
}

// ---- Names and marks ---------------------------------------------------------------------------

@Composable
private fun statusText(page: Page, region: Region, recognizing: Boolean): String = stringResource(
    when {
        recognizing -> R.string.status_recognizing
        region.graph != null -> R.string.status_graph
        region.diagram != null -> R.string.status_diagram
        region.role == RegionRole.FIGURE -> R.string.status_figure
        region.role == RegionRole.EXCLUDED -> R.string.status_excluded
        page.isStale(region) && region.status != RecognitionStatus.NOT_REQUESTED -> R.string.status_stale
        region.status == RecognitionStatus.CONFIRMED -> R.string.status_confirmed
        region.status == RecognitionStatus.RECOGNIZED -> R.string.status_recognized
        region.status == RecognitionStatus.FAILED -> R.string.status_failed
        region.role == RegionRole.MATH -> R.string.status_not_converted
        else -> R.string.status_needs_text
    },
)

@Composable
internal fun toolName(tool: Tool): String = stringResource(
    when (tool) {
        Tool.MATH_PEN -> R.string.tool_math_pen
        Tool.TEXT_PEN -> R.string.tool_text_pen
        Tool.DRAWING_PEN -> R.string.tool_drawing_pen
        Tool.DIAGRAM_PEN -> R.string.tool_diagram_pen
        Tool.SCRATCH_PEN -> R.string.tool_scratch_pen
        Tool.LASSO -> R.string.tool_lasso
        Tool.ERASER -> R.string.tool_eraser
        Tool.MOVE -> R.string.tool_move
    },
)

internal fun toolIcon(tool: Tool): ImageVector = when (tool) {
    Tool.MATH_PEN -> NibIcons.MathPen
    Tool.DIAGRAM_PEN -> NibIcons.DiagramPen
    Tool.TEXT_PEN -> NibIcons.TextPen
    Tool.DRAWING_PEN -> NibIcons.DrawPen
    Tool.SCRATCH_PEN -> NibIcons.ScratchPen
    Tool.LASSO -> NibIcons.Lasso
    Tool.ERASER -> NibIcons.Eraser
    Tool.MOVE -> NibIcons.Move
}

@Composable
private fun roleName(role: RegionRole): String = stringResource(
    when (role) {
        RegionRole.MATH -> R.string.role_math
        RegionRole.TEXT -> R.string.role_text
        RegionRole.HEADING -> R.string.role_heading
        RegionRole.FIGURE -> R.string.role_figure
        RegionRole.EXCLUDED -> R.string.role_excluded
    },
)

@Composable
internal fun paperName(paper: Paper): String = stringResource(
    when (paper) {
        Paper.BLANK -> R.string.paper_blank
        Paper.RULED -> R.string.paper_ruled
        Paper.GRID -> R.string.paper_grid
    },
)

/** Each region's tint in its pen's colour, and its state mark (Phase 13). */
private fun outlines(page: Page, recognizingRegionId: String?, editingRegionId: String?, p: NibPalette): List<RegionOutline> = page.regions.filterNot { region ->
    // Scratch grouped only for search stays as it was written: no tint, no mark.
    region.role == RegionRole.EXCLUDED && region.auto && region.id != editingRegionId
}.map { region ->
    val mark = when {
        region.id == recognizingRegionId -> RegionMark.RECOGNIZING
        region.role == RegionRole.EXCLUDED -> RegionMark.NEEDS_CONVERSION
        page.isExportable(region) -> RegionMark.CONFIRMED
        page.isStale(region) || region.status == RecognitionStatus.FAILED -> RegionMark.NEEDS_ATTENTION
        region.status == RecognitionStatus.RECOGNIZED -> RegionMark.NEEDS_CONFIRMATION
        else -> RegionMark.NEEDS_CONVERSION
    }
    RegionOutline(region.id, region.bounds, mark, p.roleTint(region.role).toArgb(), p.roleColor(region.role).toArgb(), editing = region.id == editingRegionId)
}

private const val HIGHLIGHT_FADE_MS = 300

const val MESSAGE_TAG = "notebook_message"
const val EDITOR_TAG = "region_editor"
const val GRAPH_EQUATION_TAG = "graph_equation"
const val PREVIEW_TAG = "latex_preview"
const val READING_COLUMNS_TAG = "reading_columns"
const val READING_TAG = "reading_view"
const val TEXT_FIELD_TAG = "region_text"
const val CAPTION_FIELD_TAG = "region_caption"
const val RESULTS_TAG = "results_panel"
const val PAGES_TAG = "page_overview"
const val SELECTION_BAR_TAG = "selection_bar"
const val PEN_BAR_TAG = "pen_bar"
const val FOLDED_PEN_BAR_TAG = "pen_bar_folded"
const val SETUP_TAG = "model_setup"
private const val SCREEN_MS = 280
private const val MESSAGE_MS = 4_000L
private const val MESSAGE_MS_PER_CHAR = 40L
private const val MESSAGE_MAX_MS = 9_000L
private const val RESULTS_WIDTH = 420

/** The selection bar keeps at least this far from the edges of the page area, in dp. */
private const val SELECTION_EDGE = 16
private const val SELECTION_BAR_HEIGHT = 72f
private const val SELECTION_BAR_GAP = 16f
private const val DRAG_FADE_MS = 120

/** Narrower than this, the open booklet puts the results panel at the bottom, [SHEET_FRACTION] of its height. */
private const val NARROW_BOOKLET = 900
private const val SHEET_FRACTION = 0.45f

/** Pictures on the page are decoded at most this many pixels on their long side (Phase 20). */
private const val PICTURE_PIXELS = 1600
private const val MATH_CARD_HEIGHT = 96

/**
 * The page's zoom (Phases 17 and 19): its level, and a menu with 100%, Fit width and Whole page for
 * the sheet in the page area, [pageWidth] x [pageHeight].
 */
@Composable
private fun ZoomPill(state: NotebookUiState, viewModel: NotebookViewModel, pageWidth: androidx.compose.ui.unit.Dp, pageHeight: androidx.compose.ui.unit.Dp) {
    val p = Nib.palette
    val view = state.page.view ?: PageView()
    val percent = kotlin.math.round(view.scale * 100).toInt()
    var menu by remember { mutableStateOf(false) }
    Box {
        FloatingPill(icon = NibIcons.Search, label = stringResource(R.string.zoom_level, percent), onClick = { menu = true }, container = p.surface, content = p.ink)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.zoom_actual)) }, onClick = {
                menu = false
                viewModel.onView(view.copy(scale = 1f).within(state.sheet, pageWidth.value, pageHeight.value), final = true)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.zoom_fit)) }, onClick = {
                menu = false
                viewModel.onView(PageView.fitWidth(state.sheet, pageWidth.value, pageHeight.value), final = true)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.zoom_whole)) }, onClick = {
                menu = false
                viewModel.onView(PageView.wholePage(state.sheet, pageWidth.value, pageHeight.value), final = true)
            })
        }
    }
}

/** A typed equation as it is captured for the page: in display style, though captured as inline maths. */
