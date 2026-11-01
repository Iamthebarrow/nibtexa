package io.github.iamthebarrow.nibtexa.recognition

import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * The recognition model packed into the personal sideload APK (Phase 14, ADR-0003 amendment).
 * The model is read from files, so on first start it is copied out of the APK into the model
 * folder, through [ModelImporter] (staged, checked, then swapped in). A model already in the
 * folder, bundled or loaded by the user, is left alone.
 */
class BundledModel(private val assets: AssetManager, private val target: File) {

    private val _progress = MutableStateFlow<Int?>(null)

    /** The copy's progress in percent while it runs, else null. */
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    /** This APK carries the model. */
    val available: Boolean by lazy {
        val listed = try {
            assets.list(ASSET_FOLDER).orEmpty().toSet()
        } catch (e: IOException) {
            emptySet()
        }
        OnnxTexTellerModel.REQUIRED_FILES.all { it in listed }
    }

    /** The model folder has every file. */
    val installed: Boolean get() = OnnxTexTellerModel.REQUIRED_FILES.all { File(target, it).isFile }

    /**
     * Copies the bundled model into place if the folder lacks it. Returns true if it did. Blocks
     * for several seconds; call off the main thread.
     */
    @Synchronized
    fun installIfMissing(): Boolean {
        if (installed || !available) return false
        _progress.value = 0
        try {
            val files = OnnxTexTellerModel.REQUIRED_FILES.map { name ->
                val path = "$ASSET_FOLDER/$name"
                ImportFile(name, size(path)) { assets.open(path, AssetManager.ACCESS_STREAMING) }
            }
            var shown = -1
            val result = ModelImporter(target).import(files, progress = { copied, total ->
                val percent = if (total > 0) (copied * 100 / total).toInt().coerceAtMost(100) else 0
                if (percent != shown) {
                    shown = percent
                    _progress.value = percent
                }
            })
            if (result !is ModelImporter.Result.Imported) {
                Log.e(TAG, "The bundled model did not install: $result")
                return false
            }
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Copying the bundled model failed", e)
            return false
        } finally {
            _progress.value = null
        }
    }

    /** The asset's size; -1 if it is stored compressed (only the small vocabulary may be). */
    private fun size(path: String): Long = try {
        assets.openFd(path).use { it.length }
    } catch (e: IOException) {
        -1
    }

    companion object {
        private const val TAG = "BundledModel"

        /** Where the build puts the model in the APK's assets (app/build.gradle.kts, CopyModel). */
        const val ASSET_FOLDER = "texteller-int8"
    }
}
