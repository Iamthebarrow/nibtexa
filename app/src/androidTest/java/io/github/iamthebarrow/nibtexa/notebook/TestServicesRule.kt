package io.github.iamthebarrow.nibtexa.notebook

import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageView
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.NibTeXaApplication
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.recognition.MathRecognizer
import io.github.iamthebarrow.nibtexa.core.recognition.TextContext
import io.github.iamthebarrow.nibtexa.core.recognition.TextRecognizer
import io.github.iamthebarrow.nibtexa.recognition.HandwritingPack
import io.github.iamthebarrow.nibtexa.recognition.RecognizerSource
import io.github.iamthebarrow.nibtexa.recognition.TextRecognizerSource
import io.github.iamthebarrow.nibtexa.storage.NotebookDatabase
import io.github.iamthebarrow.nibtexa.storage.NotebookRepository
import org.junit.rules.ExternalResource
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Installs isolated services before the activity starts: a database file and an export folder
 * under the test cache, and a scripted recognizer. Never touches the user's notebook.
 */
class TestServicesRule(
    var script: (List<Stroke>, () -> Boolean) -> String = { _, _ -> "x^2+1" },
    /** Automatic regions (Phase 11) are off unless a test asks, so the lasso tests stay deterministic. */
    private val autoRegions: Boolean = false,
    /** Tests write straight away, so they start in a booklet unless they test the library (Phase 12). */
    private val startInLibrary: Boolean = false,
    /** Pages open at Fit width (Phase 19); tests that write at screen positions open them at 100% from the corner. */
    private val openAtFitWidth: Boolean = false,
    /** Page margins hold (Phase 30); other tests write at screen positions anywhere on the sheet. */
    private val margins: Boolean = false,
) : ExternalResource() {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NibTeXaApplication
    private val dir = File(app.cacheDir, "notebook-test-${System.nanoTime()}")

    /** The test's own pictures (Phase 20), deleted with [dir]. */
    val pictures = io.github.iamthebarrow.nibtexa.storage.PictureStore(File(dir, "pictures"))

    /** Where [unpackExport] unpacks the LaTeX project. */
    val exportRoot = File(dir, "exports")

    /** The file the tests export the LaTeX project to (as the system "Save as" would give a URI). */
    val exportZip = File(dir, "export.zip")
    val exportZipUri: Uri get() = Uri.fromFile(exportZip)

    /** Unpacks [exportZip] into [exportRoot] and returns the project folder inside it. */
    fun unpackExport(): File {
        exportRoot.deleteRecursively()
        ZipInputStream(exportZip.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val out = File(exportRoot, entry.name).canonicalFile
                check(out.path.startsWith(exportRoot.canonicalPath)) { "zip entry outside the folder: ${entry.name}" }
                out.parentFile!!.mkdirs()
                out.outputStream().use(zip::copyTo)
            }
        }
        return exportRoot.listFiles().orEmpty().single()
    }
    private val dbName = File(dir, "notebook.db").path
    lateinit var database: NotebookDatabase
        private set

    /** Strokes each recognition call received. */
    val requests = mutableListOf<List<Stroke>>()

    private val source = RecognizerSource {
        object : MathRecognizer {
            override fun recognize(strokes: List<Stroke>, isCanceled: () -> Boolean): String {
                synchronized(requests) { requests += strokes }
                return script(strokes, isCanceled)
            }
        }
    }

    /** The scripted text recognizer's answer. */
    var textScript: (List<Stroke>, TextContext, () -> Boolean) -> String = { _, _, _ -> "the transfer function" }

    /** Strokes and context each text recognition call received. */
    val textRequests = mutableListOf<Pair<List<Stroke>, TextContext>>()

    private val textSource = TextRecognizerSource {
        object : TextRecognizer {
            override fun recognize(strokes: List<Stroke>, context: TextContext, isCanceled: () -> Boolean): String {
                synchronized(textRequests) { textRequests += strokes to context }
                return textScript(strokes, context, isCanceled)
            }
        }
    }

    /** A fake language pack: downloaded unless a test says otherwise; downloads are counted. */
    class FakePack : HandwritingPack {
        @Volatile var downloaded = true
        @Volatile var downloads = 0
        override val languageTag = "en-US"
        override fun isDownloaded() = downloaded
        override fun download(wifiOnly: Boolean) {
            downloads++
            downloaded = true
        }
    }

    val pack = FakePack()

    private val prefs = app.getSharedPreferences("notebook", android.content.Context.MODE_PRIVATE)

    /** The user's own pen-bar and finger-drawing settings, put back after the test (the app may be the user's install). */
    private var penBarHidden: Boolean? = null
    private var penDock: String? = null
    private var fingerDraws: Boolean? = null
    private var penKinds: Map<String, String> = emptyMap()
    private var snapShapes: Boolean? = null
    private var systemClipboard: Boolean? = null
    private var penButtons: Pair<String?, String?> = null to null
    private var eraser: Pair<String?, String?> = null to null

    override fun before() {
        dir.mkdirs()
        systemClipboard = if (prefs.contains(SYSTEM_CLIPBOARD)) prefs.getBoolean(SYSTEM_CLIPBOARD, true) else null
        // Tests leave Android's clipboard alone unless they turn this on.
        prefs.edit().putBoolean(SYSTEM_CLIPBOARD, false).commit()
        if (!openAtFitWidth) InkSurfaceView.openAt = { _, _, _ -> PageView() }
        NotebookViewModel.marginsApply = margins
        penBarHidden = if (prefs.contains(PEN_BAR_HIDDEN)) prefs.getBoolean(PEN_BAR_HIDDEN, false) else null
        penDock = prefs.getString(PEN_DOCK, null)
        fingerDraws = if (prefs.contains(FINGER_DRAWS)) prefs.getBoolean(FINGER_DRAWS, false) else null
        snapShapes = if (prefs.contains(SNAP_SHAPES)) prefs.getBoolean(SNAP_SHAPES, true) else null
        prefs.edit().remove(SNAP_SHAPES).commit()
        penButtons = prefs.getString(PEN_BUTTON_MAIN, null) to prefs.getString(PEN_BUTTON_SECOND, null)
        prefs.edit().remove(PEN_BUTTON_MAIN).remove(PEN_BUTTON_SECOND).commit()
        eraser = prefs.getString(ERASER_MODE, null) to prefs.getString(ERASER_SIZE, null)
        prefs.edit().remove(ERASER_MODE).remove(ERASER_SIZE).commit()
        penKinds = prefs.all.filterKeys { it.startsWith(PEN_KIND) }.mapValues { it.value.toString() }
        prefs.edit().apply { penKinds.keys.forEach { remove(it) } }.commit()
        prefs.edit().putBoolean("auto_regions", autoRegions).putBoolean(PEN_BAR_HIDDEN, false).remove(PEN_DOCK).commit()
        install()
    }

    /** Fresh services over the same files: what a new process would see. */
    fun install() {
        database = NotebookDatabase.open(app, dbName)
        app.replaceServices(NibTeXaApplication.Services(database, NotebookRepository(database.dao()), source, textSource, pack, startInLibrary, pictures = pictures))
    }

    /** A repository with no in-memory state, reading what is on disk. */
    fun freshRepository() = NotebookRepository(NotebookDatabase.open(app, dbName).dao())

    override fun after() {
        InkSurfaceView.openAt = PageView::fitWidth
        NotebookViewModel.marginsApply = true
        prefs.edit().remove("auto_regions").apply {
            penBarHidden?.let { putBoolean(PEN_BAR_HIDDEN, it) } ?: remove(PEN_BAR_HIDDEN)
            penDock?.let { putString(PEN_DOCK, it) } ?: remove(PEN_DOCK)
            fingerDraws?.let { putBoolean(FINGER_DRAWS, it) } ?: remove(FINGER_DRAWS)
            prefs.all.keys.filter { it.startsWith(PEN_KIND) }.forEach { remove(it) }
            penKinds.forEach { (key, value) -> putString(key, value) }
            snapShapes?.let { putBoolean(SNAP_SHAPES, it) } ?: remove(SNAP_SHAPES)
            putString(PEN_BUTTON_MAIN, penButtons.first)
            putString(PEN_BUTTON_SECOND, penButtons.second)
            putString(ERASER_MODE, eraser.first)
            putString(ERASER_SIZE, eraser.second)
            systemClipboard?.let { putBoolean(SYSTEM_CLIPBOARD, it) } ?: remove(SYSTEM_CLIPBOARD)
        }.commit()
        database.close()
        app.resetServices()
        dir.deleteRecursively()
    }

    private companion object {
        const val PEN_BAR_HIDDEN = "pen_bar_hidden"
        const val PEN_DOCK = "pen_dock"
        const val FINGER_DRAWS = "finger_draws"
        const val PEN_KIND = "pen_kind_"
        const val SNAP_SHAPES = "snap_shapes"
        const val PEN_BUTTON_MAIN = "pen_button_main"
        const val PEN_BUTTON_SECOND = "pen_button_second"
        const val ERASER_MODE = "eraser_mode"
        const val ERASER_SIZE = "eraser_size"
        const val SYSTEM_CLIPBOARD = "system_clipboard"
    }
}
