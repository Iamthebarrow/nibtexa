package io.github.iamthebarrow.nibtexa

import android.app.Application
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import io.github.iamthebarrow.nibtexa.core.sync.RemotePolicy
import io.github.iamthebarrow.nibtexa.recognition.BundledModel
import io.github.iamthebarrow.nibtexa.recognition.HandwritingPack
import io.github.iamthebarrow.nibtexa.recognition.MlKitTextSource
import io.github.iamthebarrow.nibtexa.recognition.RecognizerSource
import io.github.iamthebarrow.nibtexa.recognition.TextRecognizerSource
import io.github.iamthebarrow.nibtexa.recognition.TexTellerSource
import io.github.iamthebarrow.nibtexa.storage.NotebookDatabase
import io.github.iamthebarrow.nibtexa.storage.NotebookRepository
import io.github.iamthebarrow.nibtexa.storage.PictureStore
import io.github.iamthebarrow.nibtexa.sync.SyncSettingsStore
import io.github.iamthebarrow.nibtexa.sync.TokenStore
import java.io.File

/** App-wide services. Tests replace them with [replaceServices]. */
class NibTeXaApplication : Application() {

    class Services(
        val database: NotebookDatabase,
        val repository: NotebookRepository,
        val recognizerSource: RecognizerSource,
        val textRecognizerSource: TextRecognizerSource,
        val handwritingPack: HandwritingPack,
        /** The app opens on the library (Phase 12); tests that write straight away start in a booklet. */
        val startInLibrary: Boolean = true,
        /** The model packed into the APK, copied out on first start (Phase 14). */
        val bundledModel: BundledModel? = null,
        /**
         * When the app opens, load the math model and fetch a missing handwriting language
         * (Phase 14). Off in tests, which script both.
         */
        val prepareRecognition: Boolean = false,
        /** Where pictures are kept (Phase 20); null: the app's own store. Tests keep theirs apart. */
        val pictures: PictureStore? = null,
        /** Delete pictures no booklet uses when the app opens. Only with the real notes. */
        val cleanPictures: Boolean = false,
    )

    private var current: Services? = null

    val services: Services
        @Synchronized get() = current ?: defaultServices().also { current = it }

    @VisibleForTesting
    @Synchronized
    fun replaceServices(services: Services) {
        current = services
    }

    /** Drops replaced services; the next access creates the real ones again. */
    @VisibleForTesting
    @Synchronized
    fun resetServices() {
        current = null
    }

    private val ownPictures by lazy { PictureStore(File(filesDir, PictureStore.DIR)) }

    /** The pictures booklets use (Phase 20). */
    val pictures: PictureStore get() = services.pictures ?: ownPictures

    /** The folder the recognition model is read from (outside the APK; see docs/guide/MODEL_SETUP.md). */
    fun modelDirectory(): File = File(getExternalFilesDir(MODELS_DIR) ?: File(filesDir, MODELS_DIR), TexTellerSource.MODEL_FOLDER)

    private val settings by lazy { getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE) }

    /** The name on every booklet's cover page, unless the booklet has its own (Phase 14). */
    var authorName: String
        get() = settings.getString(KEY_AUTHOR, null).orEmpty()
        set(value) {
            settings.edit { putString(KEY_AUTHOR, value.trim()) }
        }

    /** Git sync: settings, the encrypted token and the app-private working copy (ADR-0006). */
    class SyncServices(
        val settings: SyncSettingsStore,
        val tokens: TokenStore,
        val workDir: File,
        val policy: RemotePolicy,
    )

    val sync: SyncServices by lazy {
        SyncServices(
            settings = SyncSettingsStore(getSharedPreferences(SYNC_PREFS, MODE_PRIVATE)),
            tokens = TokenStore(File(noBackupFilesDir, TOKEN_FILE)),
            workDir = File(noBackupFilesDir, SYNC_DIR),
            // Debug builds also accept plain HTTP, for the test remote on the developer machine
            // (tools/git-test-remote, through adb reverse); network_security_config limits it to 127.0.0.1.
            policy = if (resources.getBoolean(R.bool.allow_cleartext_git)) RemotePolicy(setOf("https", "http")) else RemotePolicy.HTTPS_ONLY,
        )
    }

    private fun defaultServices(): Services {
        val database = NotebookDatabase.open(this)
        val text = MlKitTextSource()
        val bundled = BundledModel(assets, modelDirectory())
        return Services(
            database = database,
            repository = NotebookRepository(database.dao(), defaultAuthor = { authorName }),
            recognizerSource = TexTellerSource(modelDirectory(), bundled),
            textRecognizerSource = text,
            handwritingPack = text.pack,
            bundledModel = bundled,
            prepareRecognition = true,
            cleanPictures = true,
        )
    }

    companion object {
        const val MODELS_DIR = "models"
        const val SYNC_PREFS = "sync"
        const val SETTINGS_PREFS = "settings"
        const val KEY_AUTHOR = "author"
        const val TOKEN_FILE = "git-token.bin"
        const val SYNC_DIR = "git"
    }
}
