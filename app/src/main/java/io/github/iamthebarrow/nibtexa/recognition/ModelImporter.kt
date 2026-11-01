package io.github.iamthebarrow.nibtexa.recognition

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.iamthebarrow.nibtexa.core.recognition.ByteLevelBpeDecoder
import java.io.File
import java.io.IOException
import java.io.InputStream

/** A file offered for import, from any source (a folder the user picked, or a test). */
class ImportFile(val name: String, val size: Long, val open: () -> InputStream)

/**
 * Copies the recognition model into the app (Phase 10, for sideloaded installs without adb).
 *
 * The three files are copied to a staging folder next to [target], checked, and only then
 * swapped in, so a failed or canceled import leaves the current model as it was.
 */
class ModelImporter(private val target: File) {

    sealed interface Result {
        data class Imported(val bytes: Long) : Result

        /** The folder lacks some of the model files. */
        data class Missing(val names: List<String>) : Result

        /** A file is there but isn't usable. Nothing was changed. */
        data class Invalid(val reason: String) : Result

        data object Canceled : Result
    }

    /** @throws IOException if copying fails; the current model is kept. */
    fun import(files: List<ImportFile>, progress: (copied: Long, total: Long) -> Unit = { _, _ -> }, canceled: () -> Boolean = { false }): Result {
        val byName = files.associateBy { it.name }
        val missing = OnnxTexTellerModel.REQUIRED_FILES.filter { it !in byName }
        if (missing.isNotEmpty()) return Result.Missing(missing)
        val chosen = OnnxTexTellerModel.REQUIRED_FILES.map(byName::getValue)
        val total = chosen.sumOf { it.size.coerceAtLeast(0) }

        val parent = target.absoluteFile.parentFile ?: throw IOException("No folder for the model")
        val staging = File(parent, ".${target.name}.importing")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Cannot create $staging")
        try {
            var copied = 0L
            val buffer = ByteArray(1 shl 20)
            for (file in chosen) {
                file.open().use { input ->
                    File(staging, file.name).outputStream().use { output ->
                        while (true) {
                            if (canceled()) return Result.Canceled
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied += n
                            progress(copied, total)
                        }
                    }
                }
            }
            return when (val installed = install(staging)) {
                is Result.Imported -> Result.Imported(copied)
                else -> installed
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Checks the files in [staging], and moves them into place over any current model. */
    fun install(staging: File): Result {
        check(staging)?.let { return Result.Invalid(it) }
        val parent = target.absoluteFile.parentFile ?: throw IOException("No folder for the model")
        val previous = File(parent, ".${target.name}.previous")
        val bytes = staging.listFiles().orEmpty().sumOf { it.length() }
        previous.deleteRecursively()
        if (target.exists() && !target.renameTo(previous)) throw IOException("Cannot replace the current model")
        if (!staging.renameTo(target)) {
            previous.renameTo(target)
            throw IOException("Cannot move the model into place")
        }
        previous.deleteRecursively()
        return Result.Imported(bytes)
    }

    /** A reason the staged files can't be the model, or null if they look right. */
    private fun check(dir: File): String? {
        for (name in listOf(OnnxTexTellerModel.ENCODER, OnnxTexTellerModel.DECODER)) {
            val file = File(dir, name)
            // An ONNX model is a protobuf ModelProto; its first field is ir_version (tag 0x08).
            val first = file.inputStream().use { it.read() }
            if (file.length() < MIN_NETWORK_BYTES || first != 0x08) return "$name is not an ONNX model"
        }
        return try {
            ByteLevelBpeDecoder.fromVocabJson(File(dir, OnnxTexTellerModel.VOCAB).readText())
            null
        } catch (e: Exception) {
            "${OnnxTexTellerModel.VOCAB} can't be read"
        }
    }

    companion object {
        private const val MIN_NETWORK_BYTES = 1L shl 20

        /** The files directly inside a folder the user picked with the system folder picker. */
        fun filesInTree(resolver: ContentResolver, tree: Uri): List<ImportFile> {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            return buildList {
                resolver.query(children, columns, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                        add(ImportFile(cursor.getString(1), cursor.getLong(2)) { resolver.openInputStream(uri) ?: throw IOException("Cannot open ${cursor.getString(1)}") })
                    }
                }
            }
        }
    }
}
