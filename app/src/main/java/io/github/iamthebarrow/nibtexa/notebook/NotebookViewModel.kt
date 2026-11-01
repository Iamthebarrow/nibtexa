package io.github.iamthebarrow.nibtexa.notebook

import io.github.iamthebarrow.nibtexa.storage.KeepDamagedDatabase
import io.github.iamthebarrow.nibtexa.export.TreeBackupFolder
import io.github.iamthebarrow.nibtexa.export.AutoBackups
import android.content.Intent
import io.github.iamthebarrow.nibtexa.core.export.GraphPlot
import io.github.iamthebarrow.nibtexa.core.maths.MathsException
import io.github.iamthebarrow.nibtexa.core.maths.MathsProblem
import io.github.iamthebarrow.nibtexa.core.model.notebook.Diagram
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramBuilder
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramLabel
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPart
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPoint
import io.github.iamthebarrow.nibtexa.core.model.notebook.Graph
import io.github.iamthebarrow.nibtexa.core.model.notebook.GraphCurve
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.graphics.pdf.PdfRenderer
import androidx.core.graphics.createBitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.iamthebarrow.nibtexa.NibTeXaApplication
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.export.Backup
import io.github.iamthebarrow.nibtexa.core.export.BackupException
import io.github.iamthebarrow.nibtexa.core.export.BackupProblem
import io.github.iamthebarrow.nibtexa.core.export.CheckedBackup
import io.github.iamthebarrow.nibtexa.core.export.ExportResult
import io.github.iamthebarrow.nibtexa.core.export.LatexExporter
import io.github.iamthebarrow.nibtexa.core.export.LatexImporter
import io.github.iamthebarrow.nibtexa.core.export.ImportResult
import io.github.iamthebarrow.nibtexa.core.export.MarkdownExporter
import io.github.iamthebarrow.nibtexa.core.export.MarkdownImporter
import io.github.iamthebarrow.nibtexa.export.TexImport
import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.PenKind
import io.github.iamthebarrow.nibtexa.core.model.notebook.AutoRegions
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.BookletInfo
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.ColumnMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.DocumentStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageFit
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin
import io.github.iamthebarrow.nibtexa.core.model.notebook.writingArea
import io.github.iamthebarrow.nibtexa.core.model.notebook.marginOf
import io.github.iamthebarrow.nibtexa.ink.TYPED_TEXT_HEIGHT
import io.github.iamthebarrow.nibtexa.core.model.notebook.Clip
import io.github.iamthebarrow.nibtexa.core.model.notebook.Clipboard
import io.github.iamthebarrow.nibtexa.core.model.notebook.Lasso
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageEditor
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageView
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.PastedClip
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionRequest
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import io.github.iamthebarrow.nibtexa.core.recognition.LanguagePackMissingException
import io.github.iamthebarrow.nibtexa.core.recognition.RecognitionCanceledException
import io.github.iamthebarrow.nibtexa.core.recognition.RecognitionException
import io.github.iamthebarrow.nibtexa.core.recognition.TextContext
import io.github.iamthebarrow.nibtexa.export.BookletPdf
import io.github.iamthebarrow.nibtexa.export.ExportMessages
import io.github.iamthebarrow.nibtexa.export.ExportZip
import io.github.iamthebarrow.nibtexa.export.InkFigureRenderer
import io.github.iamthebarrow.nibtexa.recognition.ModelImporter
import io.github.iamthebarrow.nibtexa.recognition.ModelNotInstalledException
import io.github.iamthebarrow.nibtexa.recognition.HANDWRITING_PACK_SIZE_MB
import io.github.iamthebarrow.nibtexa.core.model.notebook.EraserMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.EraserSettings
import io.github.iamthebarrow.nibtexa.core.model.notebook.EraserSize
import io.github.iamthebarrow.nibtexa.core.model.capture.PenButtonAction
import io.github.iamthebarrow.nibtexa.core.model.capture.PenButtons
import io.github.iamthebarrow.nibtexa.recognition.ModelDownloader
import io.github.iamthebarrow.nibtexa.recognition.OnnxTexTellerModel
import io.github.iamthebarrow.nibtexa.ink.InkColors
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.storage.BookletSummary
import io.github.iamthebarrow.nibtexa.storage.ClipSummary
import io.github.iamthebarrow.nibtexa.storage.FolderSummary
import io.github.iamthebarrow.nibtexa.storage.NotebookDatabase
import io.github.iamthebarrow.nibtexa.storage.NotebookRepository
import io.github.iamthebarrow.nibtexa.storage.PictureStore
import io.github.iamthebarrow.nibtexa.storage.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import java.io.File
import java.io.IOException

/** The tool in the stylus's hand. Pens decide what new ink becomes. */
enum class Tool(val pen: Pen?) {
    MATH_PEN(Pen.MATH),
    TEXT_PEN(Pen.TEXT),
    DRAWING_PEN(Pen.DRAWING),

    /** Draws clean diagrams: each stroke becomes a part of one (Phase 31). */
    DIAGRAM_PEN(Pen.DRAWING),
    SCRATCH_PEN(Pen.SCRATCH),
    LASSO(null),
    ERASER(null),

    /** Moves the page with one finger or the stylus (Phase 19). */
    MOVE(null),
}

/**
 * Where the pen bar sits over the page (the user drags it there). Along the top or bottom it is
 * a row; on the left or right edge, a column. The bottom right is kept for the Results and page
 * buttons.
 */
enum class PenDock(val vertical: Boolean = false) { TOP_START, TOP_CENTER, TOP_END, LEFT(vertical = true), RIGHT(vertical = true), BOTTOM_START, BOTTOM_CENTER }

/** What the app shows: the library of booklets (the home screen, Phase 12) or one open booklet. */
enum class Screen { LIBRARY, BOOKLET, TRASH }

/** A checked backup waiting for the user to restore it (Phase 21): the titles in it, and how many are already here. */
data class RestorePrompt(val titles: List<String>, val existing: Int)

/** A place on page [pageId], in page units, that Paste is offered at. */
data class PasteOffer(val pageId: String, val x: Float, val y: Float)

data class NotebookUiState(
    val screen: Screen = Screen.LIBRARY,
    /** A booklet is open (on [Screen.BOOKLET]). */
    val loaded: Boolean = false,
    val libraryLoaded: Boolean = false,
    val library: List<BookletSummary> = emptyList(),
    /** The library's folders (Phase 27), by name. */
    val folders: List<FolderSummary> = emptyList(),
    /** The folder being looked at in the library; null at the top level. */
    val openFolder: String? = null,
    /** Booklets in the Trash (Phase 21), most recently deleted first. */
    val trash: List<BookletSummary> = emptyList(),
    /** The Trash empties itself after 30 days. */
    val trashAutoEmpty: Boolean = true,
    /** A backup that passed its checks, waiting to be restored. */
    val restorePrompt: RestorePrompt? = null,
    /** Each booklet's first page, for its library thumbnail. */
    val thumbnails: Map<String, Page> = emptyMap(),
    /** The open booklet's pages, for the page strip. */
    val pages: List<Page> = emptyList(),
    /** A PDF or LaTeX export is being written. */
    val exporting: Boolean = false,
    /** The library's search text (Phase 13); empty shows every booklet. */
    val searchQuery: String = "",
    /** While searching: the booklets that match, and for each the handwriting it matched in. */
    val searchResults: List<BookletSummary>? = null,
    val searchSnippets: Map<String, SearchHit> = emptyMap(),
    /** The results panel of the open booklet is showing (Phase 13). */
    val resultsOpen: Boolean = false,
    val bookletId: String = "",
    val bookletTitle: String = "",
    val pageIndex: Int = 0,
    val pageCount: Int = 1,
    val page: Page = Page("loading"),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val tool: Tool = Tool.MATH_PEN,
    val fingerDraws: Boolean = false,
    /** Strokes picked by the lasso. */
    val selection: Set<Long> = emptySet(),
    /** Typed regions in the selection (Phase 18): they have no strokes, so they are selected by id. */
    val selectedTyped: Set<String> = emptySet(),
    /** The clipboard list (Phase 26): pinned first, then the most recent. */
    val clips: List<ClipSummary> = emptyList(),
    val clipboardOpen: Boolean = false,
    /** Where a tap with the lasso offered Paste (Phase 29). */
    val pasteOffer: PasteOffer? = null,
    /** The region just jumped to (Phase 28), marked for a moment. */
    val highlightRegion: String? = null,
    /** Copies of equations and words also go to Android's clipboard. Remembered across restarts. */
    val systemClipboard: Boolean = true,
    /** The region open in the side panel. */
    val editingRegionId: String? = null,
    /** The region whose recognition is running, if any (on any page). */
    val recognizingRegionId: String? = null,
    val readingMode: Boolean = false,
    /** The page's LaTeX, when the preview is open. */
    val preview: String? = null,
    val message: String? = null,
    /** What the last import made and left out (Phase 18), shown until dismissed. */
    val importReport: ImportReport? = null,
    /** Bumped on every page change; [savedVersion] catches up once it is on disk. */
    val version: Long = 0,
    val savedVersion: Long = 0,
    /** "Group ink into regions automatically" (Phase 11). */
    val autoRegions: Boolean = true,
    /** Whether the handwriting language pack is on the device; null until checked. */
    val handwritingReady: Boolean? = null,
    /** A math model is on the device, or being set up. */
    val modelInstalled: Boolean = false,
    /** How far the model download has come, in percent; null when none is running (Phase 33). */
    val modelDownload: Int? = null,
    /** The model download is waiting for the user to agree to use mobile data. */
    val askModelMobile: Boolean = false,
    /** Where automatic backups go; null when they are off (Phase 33). */
    val autoBackupFolder: String? = null,
    /** When the last automatic backup was made, in milliseconds; 0 if none has been. */
    val lastAutoBackup: Long = 0L,
    val downloadingHandwriting: Boolean = false,
    /** The connection is metered: the download waits for the user to agree. */
    val askMobileDownload: Boolean = false,
    /** The bundled math model is being copied out of the app, in percent (Phase 14); else null. */
    val modelSetup: Int? = null,
    /** The pen bar is folded into one button (Phase 14). Remembered across restarts. */
    val penBarHidden: Boolean = false,
    /** Where the pen bar is docked. Remembered across restarts. */
    val penDock: PenDock = PenDock.TOP_CENTER,
    /** The kind of pen chosen for each writing role (Phase 22). Remembered across restarts. */
    val penKinds: Map<Pen, PenKind> = emptyMap(),
    /** Converted shapes snap to 15-degree steps (Phase 23). Remembered across restarts. */
    val snapShapes: Boolean = true,
    /** What a pen's buttons do (Phase 34). */
    val penButtons: PenButtons = PenButtons(),
    /** How the eraser works (Phase 35). */
    val eraser: EraserSettings = EraserSettings(),
    /** The selection is being dragged, so the bars over the page are out of the way. */
    val draggingSelection: Boolean = false,
    /** The part of the open diagram chosen in its controls (Phase 31). */
    val diagramPart: DiagramPart? = null,
    /** A diagram's new writing is read as words, not maths. */
    val diagramWords: Boolean = false,
    /** The open booklet's own author (null: [appAuthor] is used) and equation alignment (Phase 14). */
    val bookletAuthor: String? = null,
    val mathAlign: MathAlign = MathAlign.CENTER,
    val documentStyle: DocumentStyle = DocumentStyle(),
    /** The open booklet's paper (Phase 19). */
    val sheet: Sheet = Sheet(),
    /** The author name on every cover page unless a booklet has its own. */
    val appAuthor: String = "",
) {
    val canPaste: Boolean get() = clips.isNotEmpty()

    /** The kind of pen [pen] writes with. Everything starts as gel, the one pen there used to be. */
    fun kindOf(pen: Pen): PenKind = penKinds[pen] ?: PenKind.GEL
}

/**
 * The notebook: booklets and pages, pens, lasso and eraser, conversion, correction, region
 * roles and reading order, autosave and export.
 *
 * Every page change goes through that page's [PageEditor] (with its own undo history) and is
 * saved in order by a single save worker. Recognition runs off the main thread, can be
 * canceled, and its result is applied only if the region's ink is unchanged.
 *
 * Automatic regions (Phase 11, ADR-0007): once the pen has been idle for [GROUPING_IDLE_MS],
 * loose ink on the page is grouped ([AutoRegions]) and its automatic math and text regions are
 * queued for recognition, one at a time, in reading order. Grouping also runs before export,
 * never after an undo.
 */
class NotebookViewModel(application: Application) : AndroidViewModel(application) {

    private val services = (application as NibTeXaApplication).services
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val figures = InkFigureRenderer((application as NibTeXaApplication).pictures)
    private var booklet: Booklet? = null
    private val pageIds = mutableListOf<String>()
    private val editors = mutableMapOf<String, PageEditor>()

    /** Clips read or just copied, by entry id (Phase 26); the newest is here at once, before it is on disk. */
    private val clipCache = object : LinkedHashMap<String, Clip>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Clip>) = size > CLIP_CACHE
    }

    /** Changes to the stored clipboard, written in order. */
    private val clipWrites = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var lastClipTime = 0L

    /** The entry and page last pasted on, and how many times in a row, so that pastes don't stack. */
    private var lastPaste: Pair<String, String>? = null
    private var pasteRepeat = 0

    /** The page area on screen in dp, for placing a paste in view. */
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** A region to bring into view once the page area's size is known (Phase 28). */
    private var pendingReveal: String? = null
    private var highlighting: Job? = null

    private var recognition: Job? = null
    private var activeRequest: Pair<String, RecognitionRequest>? = null
    private var activeIsAuto = false

    /** Automatic recognitions waiting their turn: page id to region id, in reading order. */
    private val queue = ArrayDeque<Pair<String, String>>()

    /** Queued entries the user asked for with Convert; they run first and open the side panel. */
    private val manualRequests = mutableSetOf<Pair<String, String>>()
    private var grouping: Job? = null

    /** No automatic recognition while the math model or the language pack is missing. */
    @Volatile private var mathBlocked = false
    @Volatile private var textBlocked = false
    private val saves = Channel<SaveRequest>(Channel.UNLIMITED)

    private class SaveRequest(val version: Long, val bookletId: String, val position: Int, val page: Page)

    private val _state = MutableStateFlow(
        NotebookUiState(
            fingerDraws = prefs.getBoolean(KEY_FINGER_DRAWS, false),
            autoRegions = prefs.getBoolean(KEY_AUTO_REGIONS, true),
            penBarHidden = prefs.getBoolean(KEY_PEN_BAR_HIDDEN, false),
            penDock = PenDock.entries.firstOrNull { it.name == prefs.getString(KEY_PEN_DOCK, null) } ?: PenDock.TOP_CENTER,
            appAuthor = (application as NibTeXaApplication).authorName,
            trashAutoEmpty = prefs.getBoolean(KEY_TRASH_AUTO_EMPTY, true),
            snapShapes = prefs.getBoolean(KEY_SNAP_SHAPES, true),
            penButtons = PenButtons(
                PenButtonAction.named(prefs.getString(KEY_PEN_BUTTON_MAIN, null), PenButtonAction.ERASER),
                PenButtonAction.named(prefs.getString(KEY_PEN_BUTTON_SECOND, null), PenButtonAction.LASSO),
            ),
            eraser = EraserSettings(
                EraserMode.entries.firstOrNull { it.name == prefs.getString(KEY_ERASER_MODE, null) } ?: EraserMode.PRECISE,
                EraserSize.entries.firstOrNull { it.name == prefs.getString(KEY_ERASER_SIZE, null) } ?: EraserSize.MEDIUM,
            ),
            diagramWords = prefs.getBoolean(KEY_DIAGRAM_WORDS, false),
            systemClipboard = prefs.getBoolean(KEY_SYSTEM_CLIPBOARD, true),
            penKinds = Pen.entries.mapNotNull { pen -> PenKind.entries.firstOrNull { it.name == prefs.getString(KEY_PEN_KIND + pen.name, null) }?.let { pen to it } }.toMap(),
        ),
    )
    val state: StateFlow<NotebookUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val ready = try {
                services.handwritingPack.isDownloaded()
            } catch (e: Exception) {
                Log.w(TAG, "Checking the handwriting language failed", e)
                false
            }
            _state.update { it.copy(handwritingReady = ready) }
            if (!ready && services.prepareRecognition) withContext(Dispatchers.Main) { downloadHandwriting() }
        }
        _state.update { it.copy(modelInstalled = modelIsOnDevice(), autoBackupFolder = prefs.getString(KEY_BACKUP_FOLDER, null), lastAutoBackup = prefs.getLong(KEY_LAST_AUTO_BACKUP, 0L)) }
        if (services.prepareRecognition) {
            prepareMathModel()
            checkOnNotes()
        }
        // When the app opens: the Trash lets go of what has been there too long (Phase 21), then pictures
        // no booklet uses any more go too (Phase 20). Pictures of booklets in the Trash are still in use.
        if (services.cleanPictures) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    if (_state.value.trashAutoEmpty) services.repository.purgeTrash(TRASH_DAYS * MILLIS_PER_DAY)
                    pictures.removeUnused(services.repository.usedPictures())
                }.onFailure { Log.w(TAG, "Removing unused pictures failed", it) }
            }
        }
        viewModelScope.launch {
            // The app opens on the library (Phase 12). Tests can start in the last booklet instead.
            if (services.startInLibrary) {
                refreshLibrary()
                return@launch
            }
            val opened = try {
                withContext(Dispatchers.IO) {
                    prefs.getString(KEY_BOOKLET, null)?.let { services.repository.loadBooklet(it) } ?: services.repository.loadOrCreate()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Loading the notebook failed", e)
                _state.update { it.copy(message = text(R.string.load_failed, e.message.orEmpty())) }
                return@launch
            }
            show(opened, pageIndex = 0)
        }
        viewModelScope.launch(Dispatchers.IO) {
            reloadClips()
            for (write in clipWrites) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Saving the clipboard failed", e)
                    _state.update { it.copy(message = text(R.string.save_failed, e.message.orEmpty())) }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Pages are saved in order; each request holds a complete page.
            for (request in saves) {
                try {
                    services.repository.savePage(request.bookletId, request.position, request.page)
                    _state.update { it.copy(savedVersion = maxOf(it.savedVersion, request.version)) }
                } catch (e: Exception) {
                    Log.e(TAG, "Autosave failed", e)
                    _state.update { it.copy(message = text(R.string.save_failed, e.message.orEmpty())) }
                }
            }
        }
    }

    /**
     * Loads the math model as soon as the app opens (Phase 14), copying the bundled one into
     * place first if needed, so the user never has to load it and the first conversion is quick.
     * A conversion asked for meanwhile waits for it ([TexTellerSource]).
     */
    private fun prepareMathModel() {
        val bundled = services.bundledModel
        if (bundled != null) {
            viewModelScope.launch { bundled.progress.collect { p -> _state.update { it.copy(modelSetup = p) } } }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // The full model takes about 1.7 GB: with little memory, it waits until it is first needed.
            if (!roomToKeepModelLoaded()) return@launch
            val firstInstall = bundled?.let { !it.installed && it.available } == true
            val ready = services.recognizerSource.warmUp()
            if (ready) {
                mathBlocked = false
                if (firstInstall) {
                    _state.update { it.copy(message = text(R.string.model_setup_done)) }
                    withContext(Dispatchers.Main) { pageIds.toList().forEach(::queueRecognition) }
                }
            }
        }
    }

    private suspend fun show(opened: Booklet, pageIndex: Int) {
        booklet = opened
        pageIds.clear()
        pageIds += opened.pages.map { it.id }
        opened.pages.forEach { page -> editors.getOrPut(page.id) { PageEditor(page) } }
        prefs.edit().putString(KEY_BOOKLET, opened.id).apply()
        val library = withContext(Dispatchers.IO) { services.repository.library() }
        _state.update {
            it.copy(
                screen = Screen.BOOKLET,
                resultsOpen = false,
                loaded = true,
                library = library,
                bookletId = opened.id,
                bookletTitle = opened.title,
                mathAlign = opened.info.mathAlign,
                documentStyle = opened.info.style,
                sheet = opened.info.sheet,
                pageIndex = pageIndex.coerceIn(0, pageIds.lastIndex),
                pageCount = pageIds.size,
                selection = emptySet(), selectedTyped = emptySet(),
                editingRegionId = null,
                preview = null,
            )
        }
        publish(save = false)
        fitTypedContent()
        val own = withContext(Dispatchers.IO) { services.repository.ownAuthor(opened.id) }
        _state.update { if (it.bookletId == opened.id) it.copy(bookletAuthor = own) else it }
    }

    private val currentPageId: String? get() = pageIds.getOrNull(_state.value.pageIndex)

    private val editor: PageEditor? get() = currentPageId?.let(editors::get)

    // ---- Tools and ink --------------------------------------------------------------------

    fun setTool(tool: Tool) = _state.update { it.copy(tool = tool, selection = emptySet(), selectedTyped = emptySet(), pasteOffer = null) }

    /**
     * The next tool along the pen bar, after the last the first again: the folded pen bar's long
     * press (Phase 14), so holding it again and again goes round them all.
     */
    fun nextTool() = setTool(nextTool(_state.value.tool))

    fun setAlign(regionId: String, align: MathAlign?) = edit { setAlign(regionId, align) }

    /** Docks the pen bar at [dock]. */
    fun setPenDock(dock: PenDock) {
        prefs.edit().putString(KEY_PEN_DOCK, dock.name).apply()
        _state.update { it.copy(penDock = dock) }
    }

    /** Folds the pen bar away so it is out of the way while writing, or brings it back. */
    /** Chooses the kind of pen for a writing role (Phase 22). Existing strokes keep the kind they have. */
    fun setPenKind(pen: Pen, kind: PenKind) {
        prefs.edit().putString(KEY_PEN_KIND + pen.name, kind.name).apply()
        _state.update { it.copy(penKinds = it.penKinds + (pen to kind)) }
    }

    fun setPenBarHidden(hidden: Boolean) {
        prefs.edit().putBoolean(KEY_PEN_BAR_HIDDEN, hidden).apply()
        _state.update { it.copy(penBarHidden = hidden) }
    }

    /** Remembered across restarts: a tablet without a stylus (or the emulator) always needs it. */
    fun setFingerDraws(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FINGER_DRAWS, enabled).apply()
        _state.update { it.copy(fingerDraws = enabled) }
    }

    /** Recognition waits until the pen is idle and the group is stable ([scheduleGrouping]). */
    fun onStrokeCaptured(stroke: CapturedStroke) {
        // A pen button can write scratch whatever the tool is (Phase 34).
        val scratch = stroke.pen == Pen.SCRATCH
        if (_state.value.tool == Tool.DIAGRAM_PEN && !scratch) return diagramStroke(stroke)
        val pen = if (scratch) Pen.SCRATCH else _state.value.tool.pen ?: return
        edit(recognize = false) { addStroke(stroke.copy(pen = pen, kind = _state.value.kindOf(pen))) }
        scheduleGrouping()
    }

    /**
     * A held stroke became a perfect shape (Phase 23). The stroke goes in as drawn, then the shape as its
     * own undo step, so one undo brings the hand-drawn stroke back.
     */
    fun onShape(drawn: CapturedStroke, points: List<InkPoint>) {
        val pen = _state.value.tool.pen ?: return
        edit(recognize = false) {
            addStroke(drawn.copy(pen = pen, kind = _state.value.kindOf(pen)))
            convertStroke(page.ink.strokes.last().id, points)
        }
        scheduleGrouping()
    }

    /** What a pen's buttons do (Phase 34). Remembered across restarts. */
    fun setPenButtons(buttons: PenButtons) {
        prefs.edit().putString(KEY_PEN_BUTTON_MAIN, buttons.main.name).putString(KEY_PEN_BUTTON_SECOND, buttons.second.name).apply()
        _state.update { it.copy(penButtons = buttons) }
    }

    /** Whether converted shapes snap to 15-degree steps (Phase 23). Remembered across restarts. */
    fun setSnapShapes(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SNAP_SHAPES, enabled).apply()
        _state.update { it.copy(snapShapes = enabled) }
    }

    // ---- The eraser (Phase 35) -------------------------------------------------------------

    /** The editor of the pass going on, which is one undo. */
    private var erasing: PageEditor? = null
    private var erasedInk = false

    /**
     * More of the eraser's path: the ink goes as the pen moves. The page is shown each time and saved when the pass ends;
     * the page's waiting grouping and recognition wait for it too.
     */
    fun onErase(path: List<Pair<Float, Float>>) {
        val editor = editor ?: return
        if (erasing == null) {
            erasing = editor
            grouping?.cancel()
            editor.beginPass()
        }
        val settings = _state.value.eraser
        if (editor.erase(path, settings.size.radius, settings.mode)) {
            erasedInk = true
            publish(save = false)
        }
        eraseDiagramParts(path, settings.size.radius)
    }

    /** The pen lifted: what changed is saved, and anything left out of date is read again. */
    fun onEraseEnd() {
        val editor = erasing ?: return
        erasing = null
        editor.endPass()
        if (erasedInk) publish()
        erasedInk = false
        if (_state.value.autoRegions) scheduleGrouping() else currentPageId?.let(::queueRecognition)
    }

    /** The touch turned out to be a zoom, or Android took it back: nothing was erased after all. */
    fun onEraseCancel() {
        val editor = erasing ?: return
        erasing = null
        erasedInk = false
        editor.cancelPass()
        publish()
        if (_state.value.autoRegions) scheduleGrouping()
    }

    /** Remembered across restarts. */
    fun setEraser(settings: EraserSettings) {
        prefs.edit().putString(KEY_ERASER_MODE, settings.mode.name).putString(KEY_ERASER_SIZE, settings.size.name).apply()
        _state.update { it.copy(eraser = settings) }
    }

    /** Undo never regroups: a grouping still waiting for the idle pen is dropped. */
    fun undo() {
        grouping?.cancel()
        edit { undo() }
    }

    fun redo() {
        grouping?.cancel()
        edit { redo() }
    }

    fun clearInk() = edit { clearInk() }

    // ---- Lasso ----------------------------------------------------------------------------

    fun onLasso(loop: List<Pair<Float, Float>>) {
        val page = editor?.page ?: return
        // Typed regions (Phase 18) are in when their middle is.
        val typed = page.regions.filter { it.typed && Lasso.contains(loop, it.bounds.centerX, it.bounds.centerY) }.mapTo(linkedSetOf()) { it.id }
        _state.update { it.copy(selection = Lasso.select(loop, page.ink.strokes), selectedTyped = typed, pasteOffer = null, message = null) }
    }

    /** The parts of diagrams an eraser path passes over go, each diagram as one undo. */
    private fun eraseDiagramParts(path: List<Pair<Float, Float>>, radius: Float) {
        val page = editor?.page ?: return
        for (region in page.regions.filter { it.diagram != null }) {
            val start = region.diagram!!
            val scale = diagramScale(region)
            var diagram = start
            for ((x, y) in path) {
                if (!region.bounds.contains(x, y)) continue
                diagram.partAt((x - region.bounds.left) / scale, (y - region.bounds.top) / scale, radius / scale)?.let { diagram = diagram.without(it) }
            }
            if (diagram != start) storeDiagram(region.id, diagram, region.bounds.left, region.bounds.top, scale)
        }
    }

    /**
     * A tap: opens the region under it, or clears the selection. With the lasso, a tap on an empty part of the page with
     * nothing to clear offers Paste there (Phase 29), and the next tap takes that away.
     */
    fun onTap(x: Float, y: Float) {
        val page = editor?.page ?: return
        val region = page.regionsInReadingOrder.lastOrNull { it.bounds.contains(x, y) }
        _state.update {
            val free = region == null && it.tool == Tool.LASSO && it.canPaste && it.selection.isEmpty() && it.selectedTyped.isEmpty() && it.pasteOffer == null
            it.copy(
                selection = emptySet(), selectedTyped = emptySet(), editingRegionId = region?.id ?: it.editingRegionId, resultsOpen = it.resultsOpen || region != null,
                pasteOffer = if (free) PasteOffer(page.id, x, y) else null,
                diagramPart = region?.diagram?.partAt((x - region.bounds.left) / diagramScale(region), (y - region.bounds.top) / diagramScale(region)),
            )
        }
    }

    fun setDraggingSelection(dragging: Boolean) = _state.update { it.copy(draggingSelection = dragging) }

    fun moveSelection(dx: Float, dy: Float) {
        val state = _state.value
        val selection = state.selection
        val typed = state.selectedTyped
        if (selection.isEmpty() && typed.isEmpty()) return
        // All of it stops at the margin together, so it keeps its shape (Phase 30).
        val bounds = selectionBounds(state).reduceOrNull { a, b -> a.union(b) }
        val (x, y) = if (bounds != null) PageFit.moveWithin(bounds, writingArea(state.page), dx, dy) else dx to dy
        edit { moveSelection(selection, typed, x, y) }
    }

    /** Where writing goes on [page] (Phase 30). */
    fun writingArea(page: Page, state: NotebookUiState = _state.value): Bounds =
        if (marginsApply) state.sheet.writingArea(page.paper, state.documentStyle.marginOf(page), page.background != null) else Bounds(0f, 0f, state.sheet.width, state.sheet.height)

    /** This page's own margin, or null for the booklet's (Phase 30). What is on the page comes inside it. */
    fun setPageMargin(margin: PageMargin?) {
        val page = editor?.page ?: return
        if (page.margin == margin) return
        edit(recognize = false) { setMargin(margin) }
        fitPageToMargins()
    }

    /** Brings everything on this page inside its margin (Phase 30), as one undo. */
    fun fitPageToMargins() {
        val page = editor?.page ?: return
        val area = writingArea(page)
        edit(recognize = false) { fitTo(area, TYPED_TEXT_HEIGHT) }
    }

    /**
     * When a page is shown: typed content over its margin, or taller than its box, is put right, with what is next to it. Handwriting is left
     * where it was written unless typed content has to make room. One undo puts it back.
     */
    private fun fitTypedContent() {
        if (!marginsApply) return
        val page = editor?.page ?: return
        val area = writingArea(page)
        if (PageFit.typedSpills(page, area, TYPED_TEXT_HEIGHT)) edit(recognize = false) { fitTo(area, TYPED_TEXT_HEIGHT) }
    }

    /** After the paper or margin changed: every page of the booklet, each as one undo, and saved. */
    private fun fitAllPages(state: NotebookUiState) {
        val bookletId = booklet?.id ?: return
        val current = currentPageId
        val others = pageIds.withIndex().filter { it.value != current }.mapNotNull { (i, id) ->
            val other = editors[id] ?: return@mapNotNull null
            if (other.fitTo(writingArea(other.page, state), TYPED_TEXT_HEIGHT)) i to other.page else null
        }
        editor?.let { e -> val area = writingArea(e.page, state); edit(recognize = false) { fitTo(area, TYPED_TEXT_HEIGHT) } }
        if (others.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { others.forEach { (i, p) -> services.repository.savePage(bookletId, i, p) } }
    }

    // ---- Pictures (Phase 20) ----------------------------------------------------------------

    private val pictures get() = getApplication<NibTeXaApplication>().pictures

    /**
     * Places the picture at [source] on the page, near the top-left of the view, at most half the
     * sheet wide, and selects it with the lasso so it can be moved or resized straight away.
     */
    fun insertPicture(source: Uri) {
        val pageId = currentPageId ?: return
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(source)?.use { readLimited(it, MAX_PICTURE_BYTES) }
                } ?: throw IOException("The file could not be opened")
                val stored = pictures.add(bytes)
                if (currentPageId != pageId) return@launch
                val page = editor?.page ?: return@launch
                val area = writingArea(page)
                val view = page.view ?: PageView()
                val (fullWidth, fullHeight) = stored.sizeOnPage(maxWidth = minOf(_state.value.sheet.width / 2, area.right - area.left))
                val fit = minOf(1f, (area.bottom - area.top) / fullHeight.coerceAtLeast(1f))
                val width = fullWidth * fit
                val height = fullHeight * fit
                val left = (maxOf(view.left, area.left) + PICTURE_MARGIN).coerceIn(area.left, maxOf(area.left, area.right - width))
                val top = (maxOf(view.top, area.top) + PICTURE_MARGIN).coerceIn(area.top, maxOf(area.top, area.bottom - height))
                var id = ""
                edit { id = addPicture(stored.file, Bounds(left, top, left + width, top + height)) }
                _state.update { it.copy(tool = Tool.LASSO, selection = emptySet(), selectedTyped = setOf(id)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Inserting a picture failed", e)
                _state.update { it.copy(message = text(R.string.picture_failed, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    /** The selected picture or words, resized with the corner handle, to [bounds]. */
    fun resizePicture(bounds: Bounds) {
        val id = _state.value.selectedTyped.singleOrNull() ?: return
        edit { setBounds(id, bounds) }
    }

    /** The picture, graph or typed words alone in the selection, which have a resize handle; null otherwise. */
    fun selectedPicture(state: NotebookUiState): Region? =
        if (state.selection.isNotEmpty()) null else state.selectedTyped.singleOrNull()?.let { state.page.region(it) }?.takeIf { it.picture != null || it.graph != null || it.diagram != null || it.role == RegionRole.TEXT || it.role == RegionRole.HEADING }

    // ---- Graphs (Phase 25) ------------------------------------------------------------------

    /**
     * Graphs the confirmed equation on a new graph under it, and opens the graph's controls. An equation the engine
     * can't read says why instead; one that only needs an axis or values chosen goes ahead, and the controls ask.
     */
    fun graphEquation(regionId: String) {
        val page = editor?.page ?: return
        val region = page.region(regionId) ?: return
        val latex = region.confirmedLatex?.takeIf { it.isNotBlank() } ?: return
        val curve = GraphCurve(latex, source = regionId)
        val problem = graphProblem(curve)
        if (problem != null) {
            sayCantGraph(problem)
            return
        }
        val area = writingArea(page)
        val width = minOf(GRAPH_WIDTH, area.right - area.left)
        val height = minOf(GRAPH_HEIGHT, area.bottom - area.top)
        val left = region.bounds.left.coerceIn(area.left, maxOf(area.left, area.right - width))
        val top = (region.bounds.bottom + GRAPH_GAP).coerceIn(area.top, maxOf(area.top, area.bottom - height))
        var id = ""
        edit(recognize = false) { id = addGraph(GraphPlot.fitted(Graph(listOf(curve))), Bounds(left, top, left + width, top + height)) }
        _state.update { it.copy(selection = emptySet(), selectedTyped = emptySet(), editingRegionId = id, resultsOpen = true, message = null) }
    }

    /** Why [curve] can't go on a graph; null also for one that only needs an axis chosen, which the controls ask. */
    private fun graphProblem(curve: GraphCurve): MathsProblem? =
        (GraphPlot.read(curve).exceptionOrNull() as? MathsException)?.problem?.takeIf { it !is MathsProblem.AmbiguousInput }

    private fun sayCantGraph(problem: MathsProblem) =
        _state.update { it.copy(message = text(R.string.graph_cant, mathsProblemText(getApplication<Application>().resources, problem))) }

    /** A confirmed equation that could go on a graph. */
    class GraphableEquation(val latex: String, val source: String?)

    /** The booklet's confirmed equations not on [graph] yet. Those on this page stay linked; others are added as they are. */
    fun equationsToGraph(state: NotebookUiState, graph: Graph): List<GraphableEquation> {
        val pages = state.pages.map { if (it.id == state.page.id) state.page else it }
        return pages.flatMap { page ->
            page.regionsInReadingOrder.filter { it.role == RegionRole.MATH && it.status == RecognitionStatus.CONFIRMED && !it.confirmedLatex.isNullOrBlank() }
                .map { GraphableEquation(it.confirmedLatex!!, it.id.takeIf { _ -> page.id == state.page.id }) }
        }.filter { e -> graph.curves.none { it.latex == e.latex } }.distinctBy { it.latex }
    }

    /** Changes a graph, as one undo. */
    fun updateGraph(regionId: String, change: (Graph) -> Graph) {
        val graph = editor?.page?.region(regionId)?.graph ?: return
        edit(recognize = false) { setGraph(regionId, change(graph)) }
    }

    fun addCurve(regionId: String, equation: GraphableEquation) = updateGraph(regionId) {
        if (it.curves.size >= Graph.MAX_CURVES) it else it.copy(curves = it.curves + GraphCurve(equation.latex, equation.source, it.nextColor()))
    }

    fun removeCurve(regionId: String, index: Int) = updateGraph(regionId) { it.copy(curves = it.curves.filterIndexed { i, _ -> i != index }) }

    fun updateCurve(regionId: String, index: Int, change: (GraphCurve) -> GraphCurve) = updateGraph(regionId) { graph ->
        graph.copy(curves = graph.curves.mapIndexed { i, c -> if (i == index) change(c) else c })
    }

    /** A letter's value on the graph; null clears it. */
    fun setGraphConstant(regionId: String, name: String, value: Double?) = updateGraph(regionId) {
        it.copy(constants = if (value == null) it.constants - name else it.constants + (name to value))
    }

    /** The view a drag or pinch inside the graph ended on. */
    fun setGraphView(regionId: String, graph: Graph) = updateGraph(regionId) { graph }

    // ---- Diagrams (Phase 31) -----------------------------------------------------------------

    private var diagramReading: Job? = null

    private fun diagramScale(region: Region): Float = (region.bounds.right - region.bounds.left) / (region.diagram?.width ?: 1f).coerceAtLeast(1f)

    /** A stroke of the Diagram pen: it joins the diagram it is on or near, or starts one, and becomes a part of it at once. */
    private fun diagramStroke(stroke: CapturedStroke) {
        val page = editor?.page ?: return
        val left = stroke.points.minOf { it.x }
        val top = stroke.points.minOf { it.y }
        val right = stroke.points.maxOf { it.x }
        val bottom = stroke.points.maxOf { it.y }
        val near = page.regionsInReadingOrder.filter { r ->
            val b = r.bounds
            r.diagram != null && left <= b.right + DIAGRAM_JOIN && right >= b.left - DIAGRAM_JOIN && top <= b.bottom + DIAGRAM_JOIN && bottom >= b.top - DIAGRAM_JOIN
        }
        val target = near.lastOrNull()
        val scale = target?.let(::diagramScale) ?: 1f
        val originX = target?.bounds?.left ?: 0f
        val originY = target?.bounds?.top ?: 0f
        // A stroke within reach of more than one diagram goes between them, and makes them one.
        val others = near.dropLast(1)
        val diagram = others.fold(target?.diagram ?: Diagram()) { d, other ->
            val otherScale = diagramScale(other)
            d.merged(other.diagram!!, otherScale / scale, (other.bounds.left - originX) / scale, (other.bounds.top - originY) / scale)
        }
        val step = DiagramBuilder.add(diagram, stroke.points.map { DiagramPoint((it.x - originX) / scale, (it.y - originY) / scale) }, _state.value.diagramWords)
        val tapped = step.tapped
        if (tapped != null && target != null) {
            _state.update { it.copy(editingRegionId = target.id, resultsOpen = true, diagramPart = DiagramPart.Label(tapped)) }
            return
        }
        if (step.diagram != diagram || others.isNotEmpty()) storeDiagram(target?.id, step.diagram, originX, originY, scale, others.mapTo(HashSet()) { it.id })
        readDiagramWriting()
    }

    /** [diagram] stored, its region round it and inside the margin, as one undo. Nothing left in it removes the region. */
    private fun storeDiagram(regionId: String?, diagram: Diagram, originX: Float, originY: Float, scale: Float, merged: Set<String> = emptySet()) {
        val page = editor?.page ?: return
        if (diagram.isEmpty) {
            if (regionId != null) edit(recognize = false) { deleteRegion(regionId) }
            _state.update { it.copy(diagramPart = null) }
            return
        }
        val (settled, by) = diagram.normalized()
        val x = originX - by.x * scale
        val y = originY - by.y * scale
        val bounds = Bounds(x, y, x + settled.width * scale, y + settled.height * scale)
        val fitted = PageFit.typedBox(Region("", RegionRole.FIGURE, emptySet(), bounds, 0, typed = true, diagram = settled), writingArea(page))
        edit(recognize = false) {
            when {
                regionId == null -> addDiagram(settled, fitted)
                merged.isNotEmpty() -> mergeDiagrams(regionId, settled, fitted, merged)
                else -> setDiagram(regionId, settled, fitted)
            }
        }
    }

    /** Changes a diagram from its controls, as one undo. */
    fun updateDiagram(regionId: String, change: (Diagram) -> Diagram) {
        val region = editor?.page?.region(regionId) ?: return
        val diagram = region.diagram ?: return
        val next = change(diagram)
        if (next != diagram) storeDiagram(regionId, next, region.bounds.left, region.bounds.top, diagramScale(region))
    }

    /** A part was dragged on the page. It lines up with others nearby, as new ones do, and is left chosen. */
    fun onDiagramEdit(regionId: String, diagram: Diagram, part: DiagramPart) {
        updateDiagram(regionId) { DiagramBuilder.aligned(diagram, part) }
        _state.update { it.copy(diagramPart = part) }
    }

    fun onDiagramTap(regionId: String, part: DiagramPart?) = _state.update { it.copy(editingRegionId = regionId, resultsOpen = true, diagramPart = part) }

    fun deleteDiagramPart(regionId: String, part: DiagramPart) {
        updateDiagram(regionId) { it.without(part) }
        _state.update { it.copy(diagramPart = null) }
    }

    /** What a diagram's new writing is read as. Remembered across restarts. */
    fun setDiagramWords(words: Boolean) {
        prefs.edit().putBoolean(KEY_DIAGRAM_WORDS, words).apply()
        _state.update { it.copy(diagramWords = words) }
    }

    /** A label as corrected, or with neither [latex] nor [text], shown in the handwriting it came from. */
    fun setDiagramLabel(regionId: String, labelId: Int, latex: String?, text: String?) = updateDiagram(regionId) { d ->
        d.copy(labels = d.labels.map { if (it.id == labelId) it.copy(latex = latex, text = text, unread = false) else it })
    }

    /** Reads a label's handwriting again, as maths or words. */
    fun readDiagramLabel(regionId: String, labelId: Int, words: Boolean) {
        updateDiagram(regionId) { d ->
            d.copy(labels = d.labels.map { if (it.id == labelId) it.copy(latex = null, text = null, unread = true, words = words) else it })
        }
        readDiagramWriting()
    }

    /**
     * Once the pen has rested, reads the page's unread diagram writing, one label at a time. New strokes start the wait again, so writing
     * that goes on is read whole. Writing that can't be read stays as handwriting, which is part of the figure as it is.
     */
    private fun readDiagramWriting() {
        diagramReading?.cancel()
        val pageId = currentPageId ?: return
        if (editors[pageId]?.page?.regions?.none { r -> r.diagram?.labels?.any { it.unread } == true } != false) return
        diagramReading = viewModelScope.launch {
            delay(DIAGRAM_READ_DELAY_MS)
            while (true) {
                val region = editors[pageId]?.page?.regions?.firstOrNull { r -> r.diagram?.labels?.any { it.unread } == true } ?: return@launch
                val label = region.diagram!!.labels.first { it.unread }
                val read = readLabel(label)
                val now = editors[pageId]?.page?.region(region.id) ?: return@launch
                val diagram = now.diagram ?: return@launch
                if (diagram.label(label.id)?.ink != label.ink) continue
                val done = diagram.copy(labels = diagram.labels.map { l ->
                    if (l.id != label.id) l else if (label.words) l.copy(text = read, unread = false) else l.copy(latex = read, unread = false)
                })
                if (pageId == currentPageId) {
                    edit(recognize = false) { setDiagram(region.id, done, now.bounds) }
                } else {
                    editors[pageId]?.let { e ->
                        e.setDiagram(region.id, done, now.bounds)
                        save(pageId, e.page)
                    }
                }
            }
        }
    }

    /** What the label says, or null if it can't be read now. */
    private suspend fun readLabel(label: DiagramLabel): String? {
        val strokes = label.ink.mapIndexed { i, points ->
            Stroke(i + 1L, ToolType.STYLUS, i * 1000L, points.mapIndexed { n, p -> InkPoint(p.x, p.y, n * 8L) }, if (label.words) Pen.TEXT else Pen.MATH)
        }
        val box = label.ink.flatten()
        return try {
            withContext(Dispatchers.Default) {
                if (label.words) {
                    val context = TextContext(box.maxOf { it.x } - box.minOf { it.x }, box.maxOf { it.y } - box.minOf { it.y })
                    val recognizer = runInterruptible { services.textRecognizerSource.recognizer() }
                    runInterruptible { recognizer.recognize(strokes, context) { !isActive } }
                } else {
                    val recognizer = runInterruptible { services.recognizerSource.recognizer() }
                    recognizer.recognize(strokes) { !isActive }
                }
            }.trim().takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "A diagram's writing could not be read", e)
            null
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException("The file is too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    // ---- Clipboard (Phase 26) ---------------------------------------------------------------

    private suspend fun reloadClips() {
        val clips = services.repository.clips()
        lastClipTime = maxOf(lastClipTime, clips.maxOfOrNull { it.createdAt } ?: 0)
        _state.update { it.copy(clips = clips) }
    }

    private fun keptClips(clips: List<ClipSummary>) = Clipboard.kept(clips, ClipSummary::pinned, ClipSummary::createdAt)

    /** Adds the selection to the clipboard list at once; it reaches the disk in order behind that. */
    fun copySelection() {
        val page = editor?.page ?: return
        val clip = Clip.of(page, _state.value.selection, _state.value.selectedTyped)
        if (clip.isEmpty) return
        val id = NotebookRepository.randomId()
        lastClipTime = maxOf(System.currentTimeMillis(), lastClipTime + 1)
        val summary = ClipSummary.of(id, lastClipTime, false, clip, page.id)
        clipCache[id] = clip
        lastPaste = null
        _state.update { it.copy(clips = keptClips(listOf(summary) + it.clips), message = text(R.string.clip_copied)) }
        if (_state.value.systemClipboard) clip.plain.takeIf { it.isNotBlank() }?.let(::copyToSystem)
        clipWrites.trySend {
            if (!services.repository.addClip(summary, clip)) {
                clipCache.remove(id)
                _state.update { s -> s.copy(clips = s.clips.filterNot { it.id == id }, message = text(R.string.clip_too_large)) }
            }
        }
    }

    private fun copyToSystem(plain: String) {
        val manager = getApplication<Application>().getSystemService(ClipboardManager::class.java) ?: return
        // A very large selection can be refused; the list in the app has it anyway.
        runCatching { manager.setPrimaryClip(ClipData.newPlainText(text(R.string.app_name), plain)) }.onFailure { Log.w(TAG, "Android's clipboard refused the copy", it) }
    }

    /** Whether copies also go to Android's clipboard. */
    fun setSystemClipboard(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYSTEM_CLIPBOARD, enabled).apply()
        _state.update { it.copy(systemClipboard = enabled) }
    }

    fun setClipboardOpen(open: Boolean) = _state.update { it.copy(clipboardOpen = open) }

    /** The page area on screen, in dp. */
    fun setViewport(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        revealPending()
    }

    /** The content of entry [id], for its picture in the panel and for pasting. */
    suspend fun clip(id: String): Clip? {
        clipCache[id]?.let { return it }
        val clip = withContext(Dispatchers.IO) { services.repository.clip(id) } ?: return null
        clipCache[id] = clip
        return clip
    }

    /**
     * Pastes entry [id] (with null, the one copied last) on the current page and selects it, keeping its results, so
     * nothing is recognized again. Confirmed equations come typed unless [handwriting] is asked for. Only loose ink is
     * grouped, as new ink is.
     */
    fun paste(id: String? = null, handwriting: Boolean = false) {
        val clips = _state.value.clips
        val summary = (if (id != null) clips.firstOrNull { it.id == id } else clips.maxByOrNull { it.createdAt }) ?: return
        val ready = clipCache[summary.id]
        if (ready != null) {
            place(summary, ready, handwriting)
            return
        }
        viewModelScope.launch {
            val clip = runCatching { clip(summary.id) }.onFailure { Log.w(TAG, "Reading a clipboard entry failed", it) }.getOrNull()
            if (clip == null) _state.update { it.copy(message = text(R.string.clip_unreadable)) } else place(summary, clip, handwriting)
        }
    }

    private fun place(summary: ClipSummary, clip: Clip, handwriting: Boolean) {
        val page = editor?.page ?: return
        val spot = summary.id to page.id
        pasteRepeat = if (lastPaste == spot) pasteRepeat + 1 else 0
        lastPaste = spot
        val content = if (handwriting) clip else clip.typedEquations()
        val at = _state.value.pasteOffer?.takeIf { it.pageId == page.id }?.let { it.x to it.y }
        val place = content.placement(writingArea(page), page.view ?: PageView(), viewWidth, viewHeight, summary.sourcePage == page.id, pasteRepeat, at) ?: return
        var pasted = PastedClip()
        edit(recognize = false) { pasted = paste(content, place.left, place.top, place.scale) }
        _state.update { it.copy(selection = pasted.strokes, selectedTyped = pasted.typedRegions, tool = Tool.LASSO, clipboardOpen = false, pasteOffer = null, message = null) }
        scheduleGrouping()
    }

    /** Pins or unpins an entry. Unpinned, it may be over the limit, and go. */
    fun setClipPinned(id: String, pinned: Boolean) {
        _state.update { s -> s.copy(clips = keptClips(s.clips.map { if (it.id == id) it.copy(pinned = pinned) else it })) }
        clipWrites.trySend { services.repository.setClipPinned(id, pinned) }
    }

    fun deleteClip(id: String) {
        clipCache.remove(id)
        _state.update { s -> s.copy(clips = s.clips.filterNot { it.id == id }) }
        clipWrites.trySend { services.repository.deleteClip(id) }
    }

    /** Empties the list, pinned entries too. Confirmed by the user in the UI. */
    fun clearClips() {
        clipCache.clear()
        lastPaste = null
        _state.update { it.copy(clips = emptyList()) }
        clipWrites.trySend { services.repository.clearClips() }
    }

    /**
     * Sends entry [id]'s equations to a graph: the graph open now, or a new one in view. What can't be graphed is left
     * out and says why. They are copies, not linked to anything.
     */
    fun clipToGraph(id: String) {
        val latex = _state.value.clips.firstOrNull { it.id == id }?.equationList.orEmpty()
        val curves = latex.map { GraphCurve(it) }
        val problem = curves.firstNotNullOfOrNull(::graphProblem)
        val usable = curves.filter { graphProblem(it) == null }
        if (usable.isEmpty()) {
            problem?.let(::sayCantGraph)
            return
        }
        val page = editor?.page ?: return
        val open = _state.value.editingRegionId?.takeIf { _state.value.resultsOpen }?.let(page::region)?.takeIf { it.graph != null }
        var target = open?.id
        if (open != null) {
            updateGraph(open.id) { withCurves(it, usable) }
        } else {
            val area = writingArea(page)
            val view = page.view ?: PageView()
            val width = minOf(GRAPH_WIDTH, area.right - area.left)
            val height = minOf(GRAPH_HEIGHT, area.bottom - area.top)
            val left = (maxOf(view.left, area.left) + Clipboard.PASTE_MARGIN).coerceIn(area.left, maxOf(area.left, area.right - width))
            val top = (maxOf(view.top, area.top) + Clipboard.PASTE_MARGIN).coerceIn(area.top, maxOf(area.top, area.bottom - height))
            edit(recognize = false) { target = addGraph(GraphPlot.fitted(withCurves(Graph(emptyList()), usable)), Bounds(left, top, left + width, top + height)) }
        }
        _state.update {
            it.copy(
                selection = emptySet(), selectedTyped = emptySet(), editingRegionId = target, resultsOpen = true, clipboardOpen = false,
                message = problem?.let { p -> text(R.string.graph_cant, mathsProblemText(getApplication<Application>().resources, p)) },
            )
        }
    }

    /** [graph] with [curves] not on it yet, each in the next colour, up to the most a graph holds. */
    private fun withCurves(graph: Graph, curves: List<GraphCurve>): Graph = curves.fold(graph) { g, c ->
        if (g.curves.size >= Graph.MAX_CURVES || g.curves.any { it.latex == c.latex }) g else g.copy(curves = g.curves + c.copy(color = g.nextColor()))
    }

    fun deleteSelection() {
        val selection = _state.value.selection
        val typed = _state.value.selectedTyped
        if (selection.isEmpty() && typed.isEmpty()) return
        edit {
            if (selection.isNotEmpty()) removeStrokes(selection)
            if (typed.isNotEmpty()) deleteRegions(typed)
        }
        _state.update { it.copy(selection = emptySet(), selectedTyped = emptySet()) }
    }

    /**
     * Makes a region of the selection: the secondary path, for regrouping ink or giving it a
     * role (automatic regions are the main one). The role is the one given, or that of the pen
     * most of the selected ink was written with. Math, text and headings are recognized straight
     * away; figures open the side panel for a caption.
     */
    fun makeRegion(role: RegionRole? = null, headingLevel: Int = 1) {
        val page = editor?.page ?: return
        val selection = _state.value.selection
        if (selection.isEmpty()) return
        val chosen = role ?: page.ink.strokes.filter { it.id in selection }
            .groupingBy { it.pen }.eachCount().maxByOrNull { it.value }?.key?.let(RegionRole::forPen) ?: RegionRole.MATH
        var regionId: String? = null
        edit {
            regionId = createRegion(selection, chosen)
            if (chosen == RegionRole.HEADING && headingLevel != 1) regionId?.let { setHeadingLevel(it, headingLevel) }
        }
        _state.update { it.copy(selection = emptySet(), selectedTyped = emptySet(), editingRegionId = regionId, resultsOpen = it.resultsOpen || regionId != null) }
        if (chosen != RegionRole.FIGURE && chosen != RegionRole.EXCLUDED) regionId?.let(::convert)
    }

    // ---- Automatic regions ----------------------------------------------------------------

    /** "Group ink into regions automatically". Off, the lasso is the only way to make regions. */
    fun setAutoRegions(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_REGIONS, enabled).apply()
        _state.update { it.copy(autoRegions = enabled) }
        if (enabled) {
            currentPageId?.let(::groupPage)
        } else {
            grouping?.cancel()
            queue.retainAll(manualRequests::contains)
        }
    }

    /** Groups the page once the pen has been idle; every new stroke restarts the wait. */
    private fun scheduleGrouping() {
        if (!_state.value.autoRegions) return
        val pageId = currentPageId ?: return
        grouping?.cancel()
        grouping = viewModelScope.launch {
            delay(GROUPING_IDLE_MS)
            // The wait is over: clear it first, or queueRecognition would think the pen is still busy.
            grouping = null
            groupPage(pageId)
        }
    }

    /** Groups the page's loose ink, then queues its automatic regions for recognition. */
    private fun groupPage(pageId: String) {
        val editor = editors[pageId] ?: return
        val plan = AutoRegions.plan(editor.page)
        if (!plan.isEmpty) {
            editor.autoGroup(plan)
            if (pageId == currentPageId) publish() else save(pageId, editor.page)
        }
        queueRecognition(pageId)
    }

    /**
     * Queues the page's automatic math and text regions that have no current result, in
     * reading order. A failure is not retried unless the ink changed, or [retryFailedText] (the
     * language pack has just been downloaded).
     */
    private fun queueRecognition(pageId: String, retryFailedText: Boolean = false) {
        val page = editors[pageId]?.page ?: return
        // While the pen is still busy on this page, the pending grouping queues it instead.
        val settling = grouping?.isActive == true && pageId == currentPageId
        // With automatic regions off, only conversions the user asked for are queued.
        if (_state.value.autoRegions && !settling) {
            for (region in page.regionsInReadingOrder) {
                val entry = pageId to region.id
                if (entry in queue || activeRequest?.let { it.first == pageId && it.second.regionId == region.id } == true) continue
                if (if (region.role == RegionRole.MATH) mathBlocked else textBlocked) continue
                val retry = retryFailedText && region.status == RecognitionStatus.FAILED && region.role != RegionRole.MATH
                if (region.auto && (needsRecognition(page, region) || retry) && page.membersOf(region).isNotEmpty()) queue += entry
            }
        }
        startNext()
    }

    /** Math, text and headings to confirm; scratch only to be searchable (Phase 15). */
    private fun needsRecognition(page: Page, region: Region) = (region.needsConfirmation || region.role == RegionRole.EXCLUDED) && when (region.status) {
        RecognitionStatus.NOT_REQUESTED -> true
        RecognitionStatus.RECOGNIZED, RecognitionStatus.FAILED -> page.isStale(region)
        RecognitionStatus.RECOGNIZING, RecognitionStatus.CONFIRMED -> false
    }

    private fun startNext() {
        while (recognition?.isActive != true) {
            // Scratch waits until nothing that needs the user is queued: it is only for search.
            val entry = (queue.firstOrNull { !isScratch(it) } ?: queue.firstOrNull())?.also(queue::remove) ?: return
            val manual = manualRequests.remove(entry)
            val (pageId, regionId) = entry
            val page = editors[pageId]?.page ?: continue
            val region = page.region(regionId) ?: continue
            // Checked when queued; here only what may have changed since (undo, confirm, delete).
            // A conversion the user asked for runs whatever its status (Reconvert).
            val pending = manual || (region.status != RecognitionStatus.RECOGNIZING && region.status != RecognitionStatus.CONFIRMED)
            if (pending && page.membersOf(region).isNotEmpty()) start(pageId, regionId, auto = !manual)
        }
    }

    private fun isScratch(entry: Pair<String, String>) = editors[entry.first]?.page?.region(entry.second)?.role == RegionRole.EXCLUDED

    /** Accepts every current result on the page as it is ("Confirm all"). */
    fun confirmAll() {
        var count = 0
        edit { count = confirmAll() }
        _state.update { it.copy(message = plural(R.plurals.confirmed_all, count)) }
    }

    // ---- Handwriting language -------------------------------------------------------------

    /**
     * Downloads the handwriting language pack (ADR-0007): only when the user asks, and on a
     * metered connection only once they agree ([allowMobileData]). Offline afterwards.
     */
    fun downloadHandwriting(allowMobileData: Boolean = false) {
        if (_state.value.downloadingHandwriting) return
        if (!allowMobileData && isMetered()) {
            _state.update { it.copy(askMobileDownload = true) }
            return
        }
        _state.update {
            it.copy(downloadingHandwriting = true, askMobileDownload = false, message = text(R.string.handwriting_downloading, HANDWRITING_PACK_SIZE_MB))
        }
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    services.handwritingPack.download(wifiOnly = false)
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Handwriting language download failed", e)
                    e
                }
            }
            val message = if (failure == null) text(R.string.handwriting_ready) else text(R.string.handwriting_failed, failure.message.orEmpty())
            _state.update { it.copy(downloadingHandwriting = false, handwritingReady = it.handwritingReady == true || failure == null, message = message) }
            if (failure == null) {
                textBlocked = false
                pageIds.forEach { queueRecognition(it, retryFailedText = true) }
            }
        }
    }

    fun dismissMobileDownload() = _state.update { it.copy(askMobileDownload = false) }

    private fun isMetered(): Boolean =
        getApplication<Application>().getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    // ---- Recognition ----------------------------------------------------------------------

    /**
     * Recognizes a region's current ink, at the user's request. Only one recognition runs at a
     * time: while another runs (often an automatic one), this one waits at the front of the queue.
     */
    fun convert(regionId: String) {
        val entry = (currentPageId ?: return) to regionId
        if (recognition?.isActive == true) {
            queue.remove(entry)
            queue.addFirst(entry)
            manualRequests += entry
            _state.update { it.copy(editingRegionId = regionId, resultsOpen = true, message = text(R.string.recognition_queued)) }
            return
        }
        start(entry.first, regionId, auto = false)
    }

    private fun start(pageId: String, regionId: String, auto: Boolean) {
        val editor = editors[pageId] ?: return
        val request = editor.startRecognition(regionId) ?: return
        val page = editor.page
        val region = page.region(regionId) ?: return
        val strokes = editor.strokesOf(regionId)
        activeRequest = pageId to request
        activeIsAuto = auto
        if (pageId == currentPageId) publish() else save(pageId, page)
        // An automatic recognition never takes over the side panel while the user writes.
        _state.update { it.copy(recognizingRegionId = regionId, editingRegionId = if (auto) it.editingRegionId else regionId, resultsOpen = it.resultsOpen || !auto, message = if (auto) it.message else null) }

        val context = if (region.role == RegionRole.MATH) null else textContext(page, region)
        // Noted as the running job before it starts: an answer that comes at once finishes it, and starts the next, inside start().
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val outcome = try {
                Result.success(
                    withContext(Dispatchers.Default) {
                        if (context == null) {
                            val recognizer = runInterruptible { services.recognizerSource.recognizer() }
                            recognizer.recognize(strokes) { !isActive }
                        } else {
                            val recognizer = runInterruptible { services.textRecognizerSource.recognizer() }
                            runInterruptible { recognizer.recognize(strokes, context) { !isActive } }
                        }
                    },
                )
            } catch (e: CancellationException) {
                Result.failure(RecognitionCanceledException())
            } catch (e: ModelNotInstalledException) {
                // Say what to do, not which files are missing where (Phase 10, user feedback).
                mathBlocked = true
                dropQueued { it == RegionRole.MATH }
                Result.failure(RecognitionException(text(R.string.model_not_loaded), e))
            } catch (e: LanguagePackMissingException) {
                textBlocked = true
                dropQueued { it != RegionRole.MATH }
                _state.update { it.copy(handwritingReady = false) }
                Result.failure(RecognitionException(text(R.string.handwriting_missing), e))
            } catch (e: RecognitionException) {
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed", e)
                Result.failure(RecognitionException(e.message ?: e.javaClass.simpleName, e))
            }
            finish(pageId, request, outcome)
        }
        recognition = job
        job.start()
    }

    /** The region's size, and the end of the text before it in reading order. */
    private fun textContext(page: Page, region: Region): TextContext {
        val before = page.regionsInReadingOrder.takeWhile { it.id != region.id }
            .lastOrNull { it.role == RegionRole.TEXT || it.role == RegionRole.HEADING }
        val b = region.bounds
        return TextContext(b.right - b.left, b.bottom - b.top, TextContext.preContextFrom(before?.text ?: before?.recognizedText))
    }

    /** Drops queued recognitions that would fail the same way (no model, no language pack). */
    private fun dropQueued(role: (RegionRole) -> Boolean) {
        queue.removeAll { (pageId, regionId) -> editors[pageId]?.page?.region(regionId)?.role?.let(role) ?: true }
        manualRequests.retainAll(queue::contains)
    }

    /** Cancels the running recognition and anything queued after it. */
    fun cancelRecognition() {
        queue.clear()
        manualRequests.clear()
        val job = recognition ?: return
        val (pageId, request) = activeRequest ?: return
        job.cancel()
        finish(pageId, request, Result.failure(RecognitionCanceledException()))
    }

    private fun finish(pageId: String, request: RecognitionRequest, outcome: Result<String>) {
        if (activeRequest != pageId to request) return
        activeRequest = null
        // finish runs inside the job it ends; clear it so the next one can start.
        recognition = null
        val editor = editors[pageId]
        val role = editor?.page?.region(request.regionId)?.role
        val applied = outcome.fold(
            onSuccess = { value -> editor?.applyResult(request, value) == true },
            onFailure = { e -> editor?.applyFailure(request, e.message ?: "Recognition failed") == true },
        )
        if (outcome.isSuccess) if (role == RegionRole.MATH) mathBlocked = false else textBlocked = false
        // A discarded automatic result is expected (the user kept writing); it is queued again.
        val message = outcome.exceptionOrNull()?.message ?: if (!applied && !activeIsAuto) text(R.string.result_discarded) else null
        _state.update { it.copy(recognizingRegionId = null, message = message ?: if (activeIsAuto) it.message else null) }
        // The result is saved with its page, which may not be the one on screen.
        editor?.let { save(pageId, it.page) }
        publish(save = false)
        // Ink may have changed while this ran: look again, unless the user canceled.
        if (outcome.exceptionOrNull() is RecognitionCanceledException) startNext() else queueRecognition(pageId)
    }

    // ---- Regions --------------------------------------------------------------------------

    /** Opens a region in the results panel (opening the panel), or with null closes the region. */
    fun openRegion(regionId: String?) = _state.update { it.copy(editingRegionId = regionId, resultsOpen = it.resultsOpen || regionId != null) }

    /** Confirms LaTeX (math) or the transcription (text, heading) with its formatting ([marks], Phase 16). */
    fun confirm(regionId: String, value: String, marks: List<TextMark> = emptyList()) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(message = text(R.string.empty_value)) }
            return
        }
        val lead = value.length - value.trimStart().length
        edit { confirm(regionId, trimmed, marks.map { it.copy(start = it.start - lead, end = it.end - lead) }) }
        // Words made longer take the room they need, and what is below moves down.
        if (editor?.page?.region(regionId)?.typed == true) fitTypedContent()
        _state.update { it.copy(editingRegionId = null) }
    }

    // ---- Structure (Phase 16) ----------------------------------------------------------------

    fun setTheorem(regionId: String, kind: TheoremKind?) = edit { setTheorem(regionId, kind) }

    fun setListLevel(regionId: String, level: Int?) = edit { setListLevel(regionId, level) }

    fun setNewParagraph(regionId: String, value: Boolean?) = edit { setNewParagraph(regionId, value) }

    fun setNumbered(regionId: String, value: Boolean) = edit { setNumbered(regionId, value) }

    fun setInline(regionId: String, value: Boolean?) = edit { setInline(regionId, value) }

    fun setPageBreak(regionId: String, value: Boolean) = edit { setPageBreak(regionId, value) }

    fun changeRole(regionId: String, role: RegionRole) = edit { changeRole(regionId, role) }

    fun setHeadingLevel(regionId: String, level: Int) = edit { setHeadingLevel(regionId, level) }

    /** Plain text ([level] null) or a heading of [level], keeping the confirmed words. */
    fun setTextKind(regionId: String, level: Int?) = edit { setTextKind(regionId, level) }

    fun setCaption(regionId: String, caption: String) = edit { setCaption(regionId, caption) }

    fun moveRegion(regionId: String, delta: Int) {
        val page = editor?.page ?: return
        val index = page.regionsInReadingOrder.indexOfFirst { it.id == regionId }
        if (index >= 0) edit { moveInReadingOrder(regionId, index + delta) }
    }

    fun orderByPosition() = edit { orderByPosition() }

    fun deleteRegion(regionId: String) {
        if (_state.value.recognizingRegionId == regionId) cancelRecognition()
        edit { deleteRegion(regionId) }
        _state.update { it.copy(editingRegionId = null) }
    }

    // ---- Pages and booklets ---------------------------------------------------------------

    fun setPaper(paper: Paper) {
        val page = editor?.page ?: return
        if (page.paper == paper) return
        edit { setPaper(paper) }
        // Ruled paper has no margin at the top: one that does may need things moved in (Phase 30).
        val area = writingArea(editor?.page ?: return)
        edit(recognize = false) { fitTo(area, TYPED_TEXT_HEIGHT) }
    }

    /** Whether this page is in columns (Phase 30). */
    fun setColumns(mode: ColumnMode) {
        if (editor?.page?.columns != mode) edit { setColumns(mode) }
    }

    /**
     * The page's view changed (Phase 17): shown at once; stored when a zoom or move ends
     * ([final]), without counting as an edit of the booklet.
     */
    fun onView(view: PageView, final: Boolean) {
        val editor = editor ?: return
        val pageId = currentPageId ?: return
        if (editor.page.view == view.clamped() && !final) return
        editor.setView(view)
        publish(save = false)
        if (final) viewModelScope.launch(Dispatchers.IO) { services.repository.setPageView(pageId, editor.page.view) }
    }

    /**
     * Goes to a region (Phase 28): its page, at that page's zoom, with the region in view, marked for a moment. A view that
     * isn't known yet (the booklet has only just opened) waits for the screen to report its size.
     */
    fun goToRegion(pageIndex: Int, regionId: String) {
        if (pageIndex !in pageIds.indices) return
        _state.update { it.copy(pageIndex = pageIndex, selection = emptySet(), selectedTyped = emptySet(), editingRegionId = null, preview = null, highlightRegion = regionId) }
        fitTypedContent()
        pendingReveal = regionId
        revealPending()
        publish(save = false)
        highlighting?.cancel()
        highlighting = viewModelScope.launch {
            delay(HIGHLIGHT_MS)
            _state.update { it.copy(highlightRegion = null) }
        }
    }

    private fun revealPending() {
        val id = pendingReveal ?: return
        if (viewWidth <= 0f || viewHeight <= 0f) return
        pendingReveal = null
        val pageId = currentPageId ?: return
        val editor = editor ?: return
        val region = editor.page.region(id) ?: return
        val sheet = _state.value.sheet
        val shown = (editor.page.view ?: InkSurfaceView.openAt(sheet, viewWidth, viewHeight)).showing(region.bounds, sheet, viewWidth, viewHeight)
        if (shown == editor.page.view) return
        editor.setView(shown)
        publish(save = false)
        viewModelScope.launch(Dispatchers.IO) { services.repository.setPageView(pageId, editor.page.view) }
    }

    /** The open booklet's pages as they are now, for the contents and search (Phase 28). */
    fun openBookletNow(state: NotebookUiState) = Booklet(state.bookletId, state.bookletTitle, state.pages.map { if (it.id == state.page.id) state.page else it }, BookletInfo(mathAlign = state.mathAlign, style = state.documentStyle))

    fun goToPage(index: Int) {
        if (index !in pageIds.indices || index == _state.value.pageIndex) return
        _state.update { it.copy(pageIndex = index, selection = emptySet(), selectedTyped = emptySet(), editingRegionId = null, preview = null) }
        publish(save = false)
        fitTypedContent()
    }

    fun addPage() {
        val b = booklet ?: return
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) { services.repository.addPage(b.id, pageIds.size) }
            pageIds += page.id
            editors[page.id] = PageEditor(page)
            _state.update { it.copy(pageCount = pageIds.size) }
            goToPage(pageIds.lastIndex)
            refreshLibrary()
        }
    }

    /**
     * Deletes the page at [index] with its ink and regions, once its pending saves are written.
     * Confirmed by the user in the UI: undo does not cover pages. The last page stays.
     */
    fun deletePage(index: Int) {
        val b = booklet ?: return
        if (pageIds.size <= 1 || index !in pageIds.indices) return
        val pageId = pageIds[index]
        if (activeRequest?.first == pageId) cancelRecognition()
        queue.removeAll { it.first == pageId }
        manualRequests.removeAll { it.first == pageId }
        if (index == _state.value.pageIndex) grouping?.cancel()
        pageIds.removeAt(index)
        editors.remove(pageId)
        val current = _state.value.pageIndex
        val next = when {
            index < current -> current - 1
            index == current -> index.coerceAtMost(pageIds.lastIndex)
            else -> current
        }
        _state.update { it.copy(pageIndex = next, pageCount = pageIds.size, selection = emptySet(), selectedTyped = emptySet(), editingRegionId = null, preview = null) }
        publish(save = false)
        val remaining = pageIds.toList()
        viewModelScope.launch {
            // Saves already queued carry the old page numbers; they go first.
            val version = _state.value.version
            _state.first { it.savedVersion >= version }
            withContext(Dispatchers.IO) { services.repository.deletePage(b.id, pageId, remaining) }
            booklet = booklet?.takeIf { it.id == b.id }?.let { it.copy(pages = it.pages.filterNot { p -> p.id == pageId }) }
            _state.update { it.copy(message = text(R.string.page_deleted, index + 1)) }
            refreshLibrary()
        }
    }

    /** Creates a booklet with one page on [paper] and cover colour [cover] (default: the next one), and opens it. */
    fun createBooklet(title: String, paper: Paper = Paper.BLANK, cover: Int? = null, sheet: Sheet = Sheet(), margin: PageMargin = PageMargin.NORMAL) {
        val name = title.trim().ifEmpty { text(R.string.untitled_booklet) }
        val colour = cover ?: (_state.value.library.size % NotebookDatabase.COVER_COUNT)
        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) { services.repository.createBooklet(name, paper, colour, sheet, _state.value.openFolder, margin) }
            show(created, pageIndex = 0)
        }
    }

    fun setCover(id: String, cover: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { services.repository.setCover(id, cover) }
            refreshLibrary()
        }
    }

    private var searching: Job? = null

    /** Filters the library by title and handwriting (Phase 13), shortly after typing stops. */
    fun setSearch(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searching?.cancel()
        searching = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(query)
        }
    }

    private suspend fun runSearch(query: String) {
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = null, searchSnippets = emptyMap()) }
            return
        }
        val found = withContext(Dispatchers.IO) { services.repository.search(query) }
        _state.update { s ->
            if (s.searchQuery != query) s else s.copy(
                searchResults = found.map { it.first },
                searchSnippets = found.mapNotNull { (b, snippet) -> snippet?.let { b.id to it } }.toMap(),
            )
        }
    }

    /** Opens or closes the results panel of the open booklet. Closing it also closes the region being edited. */
    fun setResultsOpen(open: Boolean) = _state.update { it.copy(resultsOpen = open, editingRegionId = if (open) it.editingRegionId else null) }

    /**
     * Opens a booklet from the library. The one already open is shown as it was left. From a search result ([hit], Phase 28)
     * it opens at the page and region that matched.
     */
    fun openBooklet(id: String, hit: SearchHit? = null) {
        fun land() {
            val index = pageIds.indexOf(hit?.pageId)
            val region = hit?.regionId
            if (index >= 0 && region != null) goToRegion(index, region)
        }
        if (id == booklet?.id) {
            _state.update { it.copy(screen = Screen.BOOKLET) }
            land()
            return
        }
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { services.repository.loadBooklet(id) } ?: return@launch
            show(opened, pageIndex = 0)
            land()
        }
    }

    /** Back to the library. The booklet stays loaded (and any recognition keeps running). */
    fun closeBooklet() {
        _state.update { it.copy(screen = Screen.LIBRARY, selection = emptySet(), selectedTyped = emptySet(), editingRegionId = null, preview = null, readingMode = false) }
        viewModelScope.launch { refreshLibrary() }
    }

    /** Renames the open booklet. */
    fun renameBooklet(title: String) {
        renameBooklet(booklet?.id ?: return, title)
    }

    fun renameBooklet(id: String, title: String) {
        val name = title.trim().takeIf { it.isNotEmpty() } ?: return
        booklet?.takeIf { it.id == id }?.let { b ->
            booklet = b.copy(title = name)
            _state.update { it.copy(bookletTitle = name) }
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { services.repository.renameBooklet(id, name) }
            refreshLibrary()
        }
    }

    /** The author on every cover page unless a booklet has its own (Phase 14). */
    fun setAppAuthor(name: String) {
        getApplication<NibTeXaApplication>().authorName = name
        _state.update { it.copy(appAuthor = name.trim()) }
    }

    /** The open booklet's own author ([author] blank: use the app's) and its equation alignment. */
    fun setBookletInfo(author: String?, align: MathAlign, sheet: Sheet = _state.value.sheet, style: DocumentStyle = _state.value.documentStyle) {
        val id = booklet?.id ?: return
        val own = author?.trim()?.takeIf { it.isNotEmpty() }
        val before = _state.value
        val reflow = before.sheet != sheet || before.documentStyle.margin != style.margin
        _state.update { it.copy(bookletAuthor = own, mathAlign = align, sheet = sheet, documentStyle = style) }
        booklet = booklet?.let { it.copy(info = it.info.copy(mathAlign = align, sheet = sheet, style = style)) }
        // A smaller sheet or wider margin brings what is on the pages inside it (Phase 30).
        if (reflow) fitAllPages(_state.value)
        if (_state.value.preview != null) showPreview(true)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                services.repository.setBookletInfo(id, own, align, sheet)
                services.repository.setDocumentStyle(id, style)
            }
            refreshLibrary()
        }
    }

    /** Copies a booklet as "<title> (copy)". */
    fun duplicateBooklet(id: String) {
        viewModelScope.launch {
            _state.first { it.savedVersion >= it.version } // copy what is on disk, including the latest edits
            val title = _state.value.library.firstOrNull { it.id == id }?.title ?: return@launch
            withContext(Dispatchers.IO) { services.repository.duplicateBooklet(id, text(R.string.booklet_copy_title, title)) }
            refreshLibrary()
        }
    }

    /** Lets go of [id] if it is the booklet held open, once its pending saves are written. */
    private suspend fun closeIfOpen(id: String) {
        _state.first { it.savedVersion >= it.version }
        booklet?.takeIf { it.id == id }?.let { open ->
            val pages = open.pages.mapTo(HashSet()) { it.id }
            if (activeRequest?.first in pages) cancelRecognition()
            queue.removeAll { it.first in pages }
            pages.forEach(editors::remove)
            booklet = null
            pageIds.clear()
            _state.update { it.copy(loaded = false, bookletId = "", bookletTitle = "", pages = emptyList()) }
        }
        if (prefs.getString(KEY_BOOKLET, null) == id) prefs.edit().remove(KEY_BOOKLET).apply()
    }

    // ---- Folders (Phase 27) -----------------------------------------------------------------

    fun openFolder(id: String?) = _state.update { it.copy(openFolder = id) }

    /** Makes a folder, and puts [bookletId] in it if given. */
    fun createFolder(name: String, cover: Int, bookletId: String? = null) {
        val clean = folderName(name) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val id = services.repository.createFolder(clean, cover)
                bookletId?.let { services.repository.moveToFolder(it, id) }
            }
            refreshLibrary()
        }
    }

    fun updateFolder(id: String, name: String, cover: Int) {
        val clean = folderName(name) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { services.repository.updateFolder(id, clean, cover) }
            refreshLibrary()
        }
    }

    private fun folderName(name: String) = name.trim().take(Backup.MAX_FOLDER_NAME).takeIf { it.isNotEmpty() }

    /** Puts a booklet in a folder, or with null at the top level. */
    fun moveToFolder(bookletId: String, folderId: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { services.repository.moveToFolder(bookletId, folderId) }
            refreshLibrary()
        }
    }

    /** Deletes a folder. Its booklets stay in the library, or with [trash] go to the Trash. Confirmed by the user in the UI. */
    fun deleteFolder(id: String, trash: Boolean) {
        viewModelScope.launch {
            if (trash) withContext(Dispatchers.IO) { services.repository.bookletsIn(id) }.forEach { closeIfOpen(it) }
            withContext(Dispatchers.IO) { services.repository.deleteFolder(id, trash) }
            _state.update { if (it.openFolder == id) it.copy(openFolder = null) else it }
            refreshLibrary()
        }
    }

    // ---- Trash (Phase 21) ---------------------------------------------------------------------

    /** Delete from the library: the booklet goes to the Trash, and can come back. */
    fun moveToTrash(id: String) {
        viewModelScope.launch {
            closeIfOpen(id)
            withContext(Dispatchers.IO) { services.repository.moveToTrash(id) }
            refreshLibrary()
            _state.update { it.copy(message = text(R.string.moved_to_trash)) }
        }
    }

    fun restoreFromTrash(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { services.repository.restoreFromTrash(id) }
            refreshLibrary()
        }
    }

    /** Deletes a booklet from the Trash for good. Confirmed by the user in the UI. */
    fun deleteForGood(id: String) {
        viewModelScope.launch {
            closeIfOpen(id)
            withContext(Dispatchers.IO) { services.repository.deleteBooklet(id) }
            refreshLibrary()
        }
    }

    /** Deletes everything in the Trash for good. Confirmed by the user in the UI. */
    fun emptyTrash() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { services.repository.emptyTrash() }
            refreshLibrary()
        }
    }

    fun openTrash() = _state.update { it.copy(screen = Screen.TRASH) }

    fun closeTrash() = _state.update { it.copy(screen = Screen.LIBRARY) }

    /** Whether the Trash empties itself after [TRASH_DAYS] days. Remembered across restarts. */
    fun setTrashAutoEmpty(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRASH_AUTO_EMPTY, enabled).apply()
        _state.update { it.copy(trashAutoEmpty = enabled) }
    }

    // ---- Backup and restore (Phase 21) -----------------------------------------------------------

    /** A file name for a backup, with today's date. */
    fun backupFileName(): String = "NibTeXa-backup-${java.time.LocalDate.now()}${Backup.EXTENSION}"

    /** Saves the whole library, or the booklets in [ids], as one backup file at [target]. The Trash is left out. */
    fun exportBackup(target: Uri, ids: Set<String>? = null) = runExport {
        _state.first { it.savedVersion >= it.version }
        withContext(Dispatchers.IO) {
            val booklets = services.repository.backupBooklets(ids)
            val folders = services.repository.backupFolders(booklets, all = ids == null)
            // The clipboard goes with the whole library, not with chosen booklets (Phase 26).
            val clips = if (ids == null) { clipWritten(); services.repository.backupClips() } else emptyList()
            var missing = emptySet<String>()
            write(target) { out -> missing = Backup.write(out, booklets, clips, folders) { name -> pictures.file(name)?.inputStream() } }
            prefs.edit().putLong(KEY_LAST_BACKUP, System.currentTimeMillis()).apply()
            val done = getApplication<Application>().resources.getQuantityString(R.plurals.backup_done, booklets.size, booklets.size)
            if (missing.isEmpty()) done else text(R.string.backup_missing_pictures, done, missing.size)
        }
    }

    /** Waits until every clipboard change so far is on disk. */
    private suspend fun clipWritten() {
        val done = CompletableDeferred<Unit>()
        clipWrites.trySend { done.complete(Unit) }
        done.await()
    }

    private var pendingRestore: CheckedBackup? = null

    /**
     * Reads and checks the backup at [source] without changing anything. If it can be trusted, asks
     * the user ([NotebookUiState.restorePrompt]); if not, says why.
     */
    fun prepareRestore(source: Uri) {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true, message = text(R.string.backup_checking)) }
        viewModelScope.launch {
            val message = try {
                val prompt = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val file = File(app.cacheDir, RESTORE_FILE)
                    (app.contentResolver.openInputStream(source) ?: throw IOException("The file could not be opened")).use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    // Restoring needs room for what is in it, unpacked, as well as the file.
                    if (app.filesDir.usableSpace < file.length() * 2 + LOW_STORAGE_BYTES) throw IOException(text(R.string.storage_no_room_restore))
                    val checked = Backup.check(file)
                    pendingRestore = checked
                    RestorePrompt(checked.booklets.map { it.booklet.title }, services.repository.existingBooklets(checked.booklets).size)
                }
                _state.update { it.copy(restorePrompt = prompt) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                Log.w(TAG, "Backup refused: ${e.problem}", e)
                text(
                    when (e.problem) {
                        BackupProblem.NOT_A_BACKUP -> R.string.backup_not_a_backup
                        BackupProblem.TOO_NEW -> R.string.backup_too_new
                        BackupProblem.DAMAGED -> R.string.backup_damaged
                        BackupProblem.UNSAFE -> R.string.backup_unsafe
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "Reading a backup failed", e)
                text(R.string.backup_read_failed, e.message ?: e.javaClass.simpleName)
            }
            _state.update { it.copy(exporting = false, message = message) }
        }
    }

    fun cancelRestore() {
        pendingRestore = null
        _state.update { it.copy(restorePrompt = null) }
        File(getApplication<Application>().cacheDir, RESTORE_FILE).delete()
    }

    /**
     * Restores the checked backup. Its pictures go in first, then all its booklets in one step, so a
     * failure leaves the library as it was. Booklets already here come in as copies, or, with
     * [replace], take the existing one's place with a copy of that in the Trash.
     */
    fun confirmRestore(replace: Boolean) {
        val checked = pendingRestore ?: return
        _state.update { it.copy(exporting = true, restorePrompt = null, message = text(R.string.backup_restoring)) }
        viewModelScope.launch {
            val message = try {
                if (replace) services.repository.existingBooklets(checked.booklets).forEach { closeIfOpen(it) }
                withContext(Dispatchers.IO) {
                    for (name in checked.pictures) pictures.restore(name) { checked.copyPicture(name, it) }
                    clipWritten()
                    services.repository.restore(checked.booklets, replace, checked.clips, checked.folders) { text(R.string.booklet_restored_title, it) }
                    reloadClips()
                }
                refreshLibrary()
                getApplication<Application>().resources.getQuantityString(R.plurals.backup_restored, checked.booklets.size, checked.booklets.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Restoring a backup failed", e)
                text(R.string.backup_restore_failed, e.message ?: e.javaClass.simpleName)
            }
            pendingRestore = null
            File(getApplication<Application>().cacheDir, RESTORE_FILE).delete()
            _state.update { it.copy(exporting = false, message = message) }
        }
    }

    private suspend fun refreshLibrary() {
        val (library, thumbnails) = withContext(Dispatchers.IO) {
            val library = services.repository.library()
            library to library.mapNotNull { b -> services.repository.firstPage(b.id)?.let { b.id to it } }.toMap()
        }
        val trash = withContext(Dispatchers.IO) { services.repository.trash() }
        val folders = withContext(Dispatchers.IO) { services.repository.folders() }
        // The open booklet's first page may have edits not on disk yet.
        val open = booklet?.id?.let { id -> pageIds.firstOrNull()?.let(editors::get)?.page?.let { id to it } }
        _state.update { it.copy(library = library, trash = trash, folders = folders, openFolder = it.openFolder?.takeIf { id -> folders.any { f -> f.id == id } }, thumbnails = thumbnails + listOfNotNull(open), libraryLoaded = true) }
        _state.value.searchQuery.takeIf { it.isNotBlank() }?.let { runSearch(it) }
    }

    // ---- Reading, preview and export ------------------------------------------------------

    fun setReadingMode(enabled: Boolean) = _state.update { it.copy(readingMode = enabled, selection = emptySet(), selectedTyped = emptySet()) }

    fun showPreview(show: Boolean) {
        val page = editor?.page ?: return
        _state.update { it.copy(preview = if (show) LatexExporter.previewPage(page, it.mathAlign) else null) }
    }

    /** Renders a figure for the reading view. */
    /** A figure for the reading view: in [colors], on transparency. */
    fun figureBitmap(page: Page, regionId: String, colors: InkColors = InkColors.LIGHT, maths: (String) -> io.github.iamthebarrow.nibtexa.ink.DiagramMath? = { null }) =
        page.region(regionId)?.let { figures.bitmap(page, it, colors, android.graphics.Color.TRANSPARENT, maths) }

    /**
     * The booklet to export: the open one as it is now (its loose ink grouped first), or
     * [bookletId] from storage. Null if there is none.
     */
    private suspend fun exportSnapshot(bookletId: String?): Booklet? {
        val open = booklet
        if (bookletId == null || bookletId == open?.id) {
            open ?: return null
            if (_state.value.autoRegions) {
                grouping?.cancel()
                pageIds.forEach(::groupPage)
            }
            // The cover's "last updated" and author come from storage, as they are now.
            val info = withContext(Dispatchers.IO) { services.repository.info(open.id) } ?: open.info
            return open.copy(title = _state.value.bookletTitle, pages = pageIds.mapNotNull { editors[it]?.page }, info = info)
        }
        return withContext(Dispatchers.IO) { services.repository.loadBooklet(bookletId) }
    }

    /** Saves the booklet's LaTeX project (`.tex` files and figures) as a `.zip` at [target] (Phase 12). */
    fun exportLatex(target: Uri, bookletId: String? = null) = runExport {
        val current = exportSnapshot(bookletId) ?: return@runExport null
        when (val result = withContext(Dispatchers.Default) { LatexExporter.export(current, figures) }) {
            is ExportResult.Invalid -> text(R.string.export_blocked, ExportMessages.describe(getApplication(), current, result.problems))
            is ExportResult.Success -> {
                withContext(Dispatchers.IO) { write(target) { ExportZip.write(ExportZip.safeName(current.title), result.files, it) } }
                plural(R.plurals.export_latex_done, result.files.size)
            }
        }
    }

    /**
     * Saves the booklet as a PDF at [target] (Phase 12). [render] typesets it; it needs an
     * activity's window ([BookletPdf]), so the screen passes it in.
     */
    fun exportPdf(target: Uri, render: suspend (Booklet) -> BookletPdf.Result, bookletId: String? = null) = runExport {
        val current = exportSnapshot(bookletId) ?: return@runExport null
        when (val result = render(current)) {
            is BookletPdf.Result.Invalid -> text(R.string.export_blocked, ExportMessages.describe(getApplication(), current, result.problems))
            is BookletPdf.Result.Written -> {
                withContext(Dispatchers.IO) { write(target) { it.write(result.bytes) } }
                plural(R.plurals.export_pdf_done, result.pages)
            }
        }
    }

    /**
     * Saves the booklet as Markdown at [target] (Phase 20): a `.zip` with `main.md` and its pictures
     * and figures. The message names what Markdown could not keep.
     */
    fun exportMarkdown(target: Uri, bookletId: String? = null) = runExport {
        val current = exportSnapshot(bookletId) ?: return@runExport null
        when (val result = withContext(Dispatchers.Default) { MarkdownExporter.export(current, figures) }) {
            is ExportResult.Invalid -> text(R.string.export_blocked, ExportMessages.describe(getApplication(), current, result.problems))
            is ExportResult.Success -> {
                withContext(Dispatchers.IO) { write(target) { ExportZip.write(ExportZip.safeName(current.title), result.files, it) } }
                val done = plural(R.plurals.export_markdown_done, result.files.size)
                val dropped = MarkdownExporter.dropped(current).map {
                    when (it) {
                        "underline" -> text(R.string.dropped_underline)
                        "colour" -> text(R.string.dropped_colour)
                        else -> text(R.string.dropped_size)
                    }
                }
                if (dropped.isEmpty()) done else text(R.string.export_markdown_dropped, done, dropped.joinToString(", "))
            }
        }
    }

    /**
     * Saves the booklet's pages as they look at [target] (Phase 20): each page's paper or imported
     * PDF page with its ink, pictures and typed text on top. [render] needs an activity's window
     * for the typed maths, so the screen passes it in.
     */
    fun exportAnnotatedPdf(target: Uri, render: suspend (Booklet) -> ByteArray, bookletId: String? = null) = runExport {
        val current = exportSnapshot(bookletId) ?: return@runExport null
        val bytes = render(current)
        withContext(Dispatchers.IO) { write(target) { it.write(bytes) } }
        plural(R.plurals.export_pdf_done, current.pages.size)
    }

    // ---- Import (Phase 18) ------------------------------------------------------------------

    /**
     * Imports [source] (a `.tex` file or a LaTeX project `.zip`, by [format]) as a new booklet and
     * opens it, then reports what was dropped or simplified ([NotebookUiState.importReport]).
     */
    fun importFile(source: Uri, format: ImportFormat) {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true, message = text(R.string.importing)) }
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val name = withContext(Dispatchers.IO) { displayName(source) }?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                val (result, sheet) = if (format == ImportFormat.PDF_PAGES) {
                    importPdf(source)
                } else {
                    val read = withContext(Dispatchers.IO) {
                        app.contentResolver.openInputStream(source)?.use {
                            when (format) {
                                ImportFormat.LATEX_PROJECT -> TexImport.project(it)
                                ImportFormat.MARKDOWN_PROJECT -> TexImport.markdownProject(it)
                                ImportFormat.MARKDOWN_FILE -> TexImport.markdown(it)
                                else -> TexImport.tex(it)
                            }
                        }
                    } ?: throw IOException("The file could not be opened")
                    // Pictures in a project are stored as they are found (Phase 20); one that isn't a picture is left out.
                    val store = { path: String -> read.picture(path)?.let { bytes -> runCatching { kotlinx.coroutines.runBlocking { pictures.add(bytes) } }.getOrNull() } }
                    val imported = withContext(Dispatchers.Default) {
                        if (format == ImportFormat.MARKDOWN_FILE || format == ImportFormat.MARKDOWN_PROJECT) {
                            MarkdownImporter.import(read.main, store) { "import" }
                        } else {
                            LatexImporter.import(read.main, read::read, store) { "import" }
                        }
                    }
                    imported to Sheet()
                }
                val title = result.title ?: name ?: text(R.string.untitled_booklet)
                val cover = _state.value.library.size % NotebookDatabase.COVER_COUNT
                val created = withContext(Dispatchers.IO) { services.repository.importBooklet(title, result.author, result.pages, cover, sheet, _state.value.openFolder) }
                _state.update { it.copy(exporting = false, message = null, importReport = ImportReport(created.title, created.pages.size, result.warnings)) }
                show(created, pageIndex = 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                _state.update { it.copy(exporting = false, message = text(R.string.import_failed, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    /**
     * A PDF as pages to write on (Phase 20): each PDF page drawn at [PDF_DPI] into the picture store
     * as its page's background, on a sheet the size of the first page. Android's own [PdfRenderer]
     * reads it; nothing in it is run.
     */
    private suspend fun importPdf(source: Uri): Pair<ImportResult, Sheet> = withContext(Dispatchers.IO) {
        val descriptor = getApplication<Application>().contentResolver.openFileDescriptor(source, "r") ?: throw IOException("The file could not be opened")
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: SecurityException) {
            descriptor.close()
            throw IOException(text(R.string.pdf_import_locked), e)
        }
        try {
            if (renderer.pageCount == 0) throw IOException("The PDF has no pages")
            val warnings = mutableListOf<String>()
            if (renderer.pageCount > MAX_PDF_PAGES) warnings += plural(R.plurals.pdf_import_too_long, MAX_PDF_PAGES)
            var sheet: Sheet? = null
            var mixed = false
            val pages = (0 until minOf(renderer.pageCount, MAX_PDF_PAGES)).map { i ->
                val page = renderer.openPage(i)
                try {
                    val size = Sheet.ofPoints(page.width.toFloat(), page.height.toFloat())
                    if (sheet == null) sheet = size else if (size != sheet) mixed = true
                    // At PDF_DPI, but never more than the store keeps.
                    val scale = minOf(PDF_DPI / 72f, PictureStore.MAX_SIDE.toFloat() / maxOf(page.width, page.height))
                    val bitmap = createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1))
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val stored = pictures.add(bitmap, jpeg = true)
                    bitmap.recycle()
                    Page("import", background = stored.file)
                } finally {
                    page.close()
                }
            }
            if (mixed) warnings += "Pages of other sizes were fitted to the first page's size."
            ImportResult(null, null, pages, warnings) to sheet!!
        } finally {
            renderer.close()
            descriptor.close()
        }
    }

    fun dismissImportReport() = _state.update { it.copy(importReport = null) }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun runExport(work: suspend () -> String?) {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true, message = text(R.string.exporting)) }
        viewModelScope.launch {
            val message = try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                text(R.string.export_failed, e.message ?: e.javaClass.simpleName)
            }
            _state.update { it.copy(exporting = false, message = message) }
        }
    }

    private fun write(target: Uri, body: (java.io.OutputStream) -> Unit) {
        val out = getApplication<Application>().contentResolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write to the chosen file")
        out.use(body)
    }

    /** A file name for the open booklet's export, e.g. "Signals.pdf". */
    fun exportFileName(extension: String, bookletId: String? = null, annotated: Boolean = false): String {
        val title = bookletId?.let { id -> _state.value.library.firstOrNull { it.id == id }?.title } ?: _state.value.bookletTitle
        return "${ExportZip.safeName(title)}${if (annotated) "-annotated" else ""}.$extension"
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Clears [shown] if it is still the message on screen (a newer one stays for its own time). */
    fun dismissMessage(shown: String) = _state.update { if (it.message == shown) it.copy(message = null) else it }

    /**
     * Copies the recognition model from a folder the user picked (Phase 10). The current model
     * stays in place until the new one is copied and checked.
     */
    fun importModel(tree: Uri) {
        if (recognition?.isActive == true) {
            _state.update { it.copy(message = text(R.string.recognition_busy)) }
            return
        }
        val app = getApplication<NibTeXaApplication>()
        viewModelScope.launch {
            _state.update { it.copy(message = text(R.string.model_import_progress, 0)) }
            val message = try {
                withContext(Dispatchers.IO) {
                    val files = ModelImporter.filesInTree(app.contentResolver, tree)
                    var shown = -1
                    val result = ModelImporter(app.modelDirectory()).import(files, progress = { copied, total ->
                        val percent = if (total > 0) (copied * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            _state.update { it.copy(message = text(R.string.model_import_progress, percent)) }
                        }
                    })
                    if (result is ModelImporter.Result.Imported) {
                        services.recognizerSource.invalidate()
                        mathBlocked = false
                    }
                    when (result) {
                        is ModelImporter.Result.Imported -> text(R.string.model_import_done, result.bytes / (1024 * 1024))
                        is ModelImporter.Result.Missing -> text(R.string.model_import_missing, result.names.joinToString())
                        is ModelImporter.Result.Invalid -> text(R.string.model_import_invalid, result.reason)
                        ModelImporter.Result.Canceled -> text(R.string.model_import_failed, "canceled")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Model import failed", e)
                text(R.string.model_import_failed, e.message.orEmpty())
            } catch (e: SecurityException) {
                Log.e(TAG, "Model import failed", e)
                text(R.string.model_import_failed, e.message.orEmpty())
            }
            _state.update { it.copy(message = message) }
        }
    }

    // ---- Downloading the model (Phase 33) ------------------------------------------------------

    private var modelDownload: Job? = null

    private fun roomToKeepModelLoaded(): Boolean {
        val decoder = File(getApplication<NibTeXaApplication>().modelDirectory(), OnnxTexTellerModel.DECODER)
        return decoder.length() < FULL_MODEL_BYTES || tabletMemory() >= LOAD_FULL_MODEL_MEMORY
    }

    private fun tabletMemory(): Long {
        val memory = android.app.ActivityManager.MemoryInfo()
        getApplication<Application>().getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(memory)
        return memory.totalMem
    }

    /** This tablet's memory in GB if the math model would be heavy for it, as the download warns (Phase 34); null if it has plenty. */
    fun tightMemoryForModel(): Float? = tabletMemory().takeIf { it < LOAD_FULL_MODEL_MEMORY }?.let { it / (1024f * 1024f * 1024f) }

    private fun modelIsOnDevice(): Boolean {
        val dir = getApplication<NibTeXaApplication>().modelDirectory()
        return services.bundledModel?.available == true || OnnxTexTellerModel.REQUIRED_FILES.all { File(dir, it).isFile }
    }

    /** Fetches the math model from its authors. The user has seen where it comes from and on what terms. On mobile data it asks first. */
    fun downloadModel(allowMobileData: Boolean = false) {
        if (modelDownload?.isActive == true) return
        if (recognition?.isActive == true) {
            _state.update { it.copy(message = text(R.string.recognition_busy)) }
            return
        }
        if (!allowMobileData && isMetered()) {
            _state.update { it.copy(askModelMobile = true) }
            return
        }
        val app = getApplication<NibTeXaApplication>()
        _state.update { it.copy(askModelMobile = false, modelDownload = 0, message = null) }
        modelDownload = viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    var shown = -1
                    val result = ModelDownloader(app.modelDirectory()).download(
                        progress = { done, total ->
                            val percent = (done * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                _state.update { it.copy(modelDownload = percent) }
                            }
                        },
                        canceled = { !isActive },
                    )
                    if (result is ModelDownloader.Result.Done) {
                        services.recognizerSource.invalidate()
                        mathBlocked = false
                        _state.update { it.copy(modelInstalled = true) }
                    }
                    when (result) {
                        is ModelDownloader.Result.Done -> text(R.string.model_download_done)
                        ModelDownloader.Result.Canceled -> text(R.string.model_download_stopped)
                        is ModelDownloader.Result.NoRoom -> text(R.string.model_download_no_room, result.needed / (1024 * 1024))
                        is ModelDownloader.Result.Damaged -> text(R.string.model_download_damaged)
                        is ModelDownloader.Result.Invalid -> text(R.string.model_import_invalid, result.reason)
                    }
                }
            } catch (e: CancellationException) {
                text(R.string.model_download_stopped)
            } catch (e: IOException) {
                Log.w(TAG, "The model download failed", e)
                text(R.string.model_download_failed)
            }
            _state.update { it.copy(modelDownload = null, message = message) }
        }
    }

    fun stopModelDownload() {
        modelDownload?.cancel()
    }

    fun cancelModelMobile() = _state.update { it.copy(askModelMobile = false) }

    // ---- Keeping notes safe (Phase 33) --------------------------------------------------------

    /** When the app opens: says if notes were damaged or room is short, or if nothing guards them, and makes any backup that is due. */
    private fun checkOnNotes() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val damaged = KeepDamagedDatabase.latest(app)
            val seen = prefs.getLong(KEY_DAMAGED_SEEN, 0L)
            val message = when {
                damaged > seen -> {
                    prefs.edit().putLong(KEY_DAMAGED_SEEN, damaged).apply()
                    text(R.string.notes_damaged)
                }
                app.filesDir.usableSpace < LOW_STORAGE_BYTES -> text(R.string.storage_low)
                else -> null
            }
            if (message != null) {
                _state.update { it.copy(message = message) }
            } else if (shouldRemindAboutBackups()) {
                prefs.edit().putLong(KEY_BACKUP_REMINDED, System.currentTimeMillis()).apply()
                _state.update { it.copy(message = text(R.string.backup_reminder)) }
            }
            runAutoBackup()
        }
    }

    private suspend fun shouldRemindAboutBackups(): Boolean {
        if (prefs.getString(KEY_BACKUP_FOLDER, null) != null) return false
        val now = System.currentTimeMillis()
        val since = maxOf(prefs.getLong(KEY_LAST_BACKUP, 0L), prefs.getLong(KEY_BACKUP_REMINDED, 0L))
        return now - since >= BACKUP_REMINDER_MILLIS && services.repository.library().isNotEmpty()
    }

    /** Turns automatic backups on, into [tree], which the app keeps access to; or off, with null. */
    fun setAutoBackupFolder(tree: Uri?) {
        val resolver = getApplication<Application>().contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val old = prefs.getString(KEY_BACKUP_FOLDER, null)
        if (old != null && old != tree?.toString()) runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        if (tree == null) {
            prefs.edit().remove(KEY_BACKUP_FOLDER).apply()
            _state.update { it.copy(autoBackupFolder = null) }
            return
        }
        try {
            resolver.takePersistableUriPermission(tree, flags)
        } catch (e: SecurityException) {
            Log.w(TAG, "No lasting access to the backup folder", e)
            _state.update { it.copy(message = text(R.string.auto_backup_failed)) }
            return
        }
        prefs.edit().putString(KEY_BACKUP_FOLDER, tree.toString()).remove(KEY_LAST_AUTO_BACKUP).apply()
        _state.update { it.copy(autoBackupFolder = tree.toString(), lastAutoBackup = 0L) }
        viewModelScope.launch(Dispatchers.IO) { runAutoBackup(saySo = true) }
    }

    /** Makes today's automatic backup, if there is a folder and one is due. A failure is said, and tried again next time. */
    private suspend fun runAutoBackup(saySo: Boolean = false) {
        val folder = prefs.getString(KEY_BACKUP_FOLDER, null)?.let(Uri::parse) ?: return
        val now = System.currentTimeMillis()
        if (!AutoBackups.isDue(now, prefs.getLong(KEY_LAST_AUTO_BACKUP, 0L))) return
        val app = getApplication<Application>()
        try {
            val booklets = services.repository.backupBooklets(null)
            if (booklets.isEmpty()) return
            val folders = services.repository.backupFolders(booklets, all = true)
            clipWritten()
            val clips = services.repository.backupClips()
            AutoBackups.run(TreeBackupFolder(app.contentResolver, folder), java.time.LocalDate.now()) { out ->
                Backup.write(out, booklets, clips, folders) { name -> pictures.file(name)?.inputStream() }
            }
            prefs.edit().putLong(KEY_LAST_AUTO_BACKUP, now).apply()
            _state.update { it.copy(lastAutoBackup = now, message = if (saySo) text(R.string.auto_backup_done) else it.message) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "An automatic backup failed", e)
            _state.update { it.copy(message = text(R.string.auto_backup_failed)) }
        }
    }

    /** Runs [action] with the booklet id once every edit so far is on disk (sync reads storage). */
    fun whenSaved(action: (bookletId: String) -> Unit) {
        val id = booklet?.id ?: return
        viewModelScope.launch {
            _state.first { it.savedVersion >= it.version }
            action(id)
        }
    }

    // ---- Plumbing -------------------------------------------------------------------------

    /** Applies a page change, saves it, and (unless [recognize] is false) queues what it made stale. */
    private inline fun edit(recognize: Boolean = true, change: PageEditor.() -> Unit) {
        val editor = editor ?: return
        editor.change()
        publish()
        if (recognize) currentPageId?.let(::queueRecognition)
    }

    private fun publish(save: Boolean = true) {
        val pageId = pageIds.getOrNull(_state.value.pageIndex) ?: return
        val editor = editors[pageId] ?: return
        val page = editor.page
        _state.update {
            val editing = it.editingRegionId?.takeIf { id -> page.region(id) != null }
            val selection = it.selection.filterTo(linkedSetOf()) { id -> page.ink.strokes.any { s -> s.id == id } }
            val selectedTyped = it.selectedTyped.filterTo(linkedSetOf()) { id -> page.region(id)?.typed == true }
            it.copy(
                page = page,
                pages = pageIds.mapNotNull(editors::get).map(PageEditor::page),
                canUndo = editor.canUndo,
                canRedo = editor.canRedo,
                editingRegionId = editing,
                selection = selection,
                selectedTyped = selectedTyped,
                preview = it.preview?.let { _ -> LatexExporter.previewPage(page, it.mathAlign) },
            )
        }
        if (save) save(pageId, page)
    }

    private fun save(pageId: String, page: Page) {
        val b = booklet ?: return
        val version = _state.value.version + 1
        _state.update { it.copy(version = version) }
        saves.trySend(SaveRequest(version, b.id, pageIds.indexOf(pageId), page))
    }

    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun plural(id: Int, count: Int): String = getApplication<Application>().resources.getQuantityString(id, count, count)

    /** Bounds of the selection, for the overlay. */
    fun selectionBounds(state: NotebookUiState): List<Bounds> =
        state.page.ink.strokes.filter { it.id in state.selection }.map(Bounds::of) + state.page.regions.filter { it.id in state.selectedTyped }.map { it.bounds }

    companion object {
        /** Tests that write at screen positions write anywhere on the sheet, as they did before margins. */
        @androidx.annotation.VisibleForTesting
        var marginsApply = true

        /** The tool after [tool] on the pen bar, wrapping round. */
        fun nextTool(tool: Tool): Tool = Tool.entries[(tool.ordinal + 1) % Tool.entries.size]

        private const val TAG = "NotebookViewModel"
        private const val PREFS = "notebook"
        private const val KEY_BOOKLET = "booklet"
        private const val KEY_FINGER_DRAWS = "finger_draws"
        private const val KEY_AUTO_REGIONS = "auto_regions"
        private const val KEY_PEN_BAR_HIDDEN = "pen_bar_hidden"
        private const val KEY_PEN_DOCK = "pen_dock"
        private const val KEY_PEN_KIND = "pen_kind_"
        private const val KEY_SNAP_SHAPES = "snap_shapes"
        private const val KEY_ERASER_MODE = "eraser_mode"
        private const val KEY_ERASER_SIZE = "eraser_size"
        private const val KEY_PEN_BUTTON_MAIN = "pen_button_main"
        private const val KEY_PEN_BUTTON_SECOND = "pen_button_second"
        private const val KEY_DIAGRAM_WORDS = "diagram_words"
        private const val FULL_MODEL_BYTES = 500L * 1024 * 1024
        private const val LOAD_FULL_MODEL_MEMORY = 6L * 1024 * 1024 * 1024 - 256L * 1024 * 1024
        private const val KEY_BACKUP_FOLDER = "auto_backup_folder"
        private const val KEY_LAST_AUTO_BACKUP = "last_auto_backup"
        private const val KEY_LAST_BACKUP = "last_backup"
        private const val KEY_BACKUP_REMINDED = "backup_reminded"
        private const val KEY_DAMAGED_SEEN = "damaged_seen"
        private const val LOW_STORAGE_BYTES = 200L * 1024 * 1024
        private const val BACKUP_REMINDER_MILLIS = 14L * 24 * 60 * 60 * 1000

        /** How long the pen rests before a diagram's writing is read: long enough to lift it between letters. */
        private const val DIAGRAM_READ_DELAY_MS = 1200L

        /** A stroke this near a diagram, in page units (about an inch), is part of it. */
        private const val DIAGRAM_JOIN = 150f
        private const val KEY_TRASH_AUTO_EMPTY = "trash_auto_empty"
        private const val KEY_SYSTEM_CLIPBOARD = "system_clipboard"

        /** How long the Trash keeps a booklet (Phase 21). */
        const val TRASH_DAYS = 30L
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        private const val RESTORE_FILE = "restore.nibtexa.zip"

        /** How long the pen rests before its ink is grouped (spec: about 1.5 s). */
        private const val GROUPING_IDLE_MS = 1_500L
        private const val SEARCH_DEBOUNCE_MS = 250L

        /** How long a region jumped to stays marked (Phase 28). */
        private const val HIGHLIGHT_MS = 2_500L

        /** Clipboard entries kept read in memory. */
        private const val CLIP_CACHE = 8

        /** Pictures and PDFs are read up to this size (Phase 20). */
        private const val MAX_PICTURE_BYTES = 40 * 1024 * 1024
        private const val PICTURE_MARGIN = 40f

        /** A new graph's size in page units, and its distance below its equation (Phase 25). */
        private const val GRAPH_WIDTH = 440f
        private const val GRAPH_HEIGHT = 320f
        private const val GRAPH_GAP = 16f

        /** PDF pages are drawn at this many dots per inch under the ink, and at most this many are imported. */
        private const val PDF_DPI = 200f
        private const val MAX_PDF_PAGES = 200
    }
}
