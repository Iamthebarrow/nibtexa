package io.github.iamthebarrow.nibtexa.recognition

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches the math recognition model from its authors when the user asks (Phase 33, ADR-0003). This project doesn't host or ship it: the
 * device gets it straight from upstream, at a pinned commit, and each file is checked against its known SHA-256 before it is used. An
 * interrupted download goes on where it stopped. The current model, if any, stays until the new one has been checked.
 */
class ModelDownloader(
    private val target: File,
    private val baseUrl: String = BASE_URL,
    private val files: List<ModelFile> = FILES,
) {
    class ModelFile(val name: String, val size: Long, val sha256: String)

    sealed interface Result {
        data class Done(val bytes: Long) : Result

        /** Stopped by the user. What came down is kept, for the next try. */
        data object Canceled : Result

        data class NoRoom(val needed: Long) : Result

        /** A file didn't match its checksum, and has been removed. */
        data class Damaged(val name: String) : Result

        data class Invalid(val reason: String) : Result
    }

    /** @throws IOException if the network or storage fails; what came down is kept. */
    fun download(progress: (done: Long, total: Long) -> Unit = { _, _ -> }, canceled: () -> Boolean = { false }): Result {
        val parent = target.absoluteFile.parentFile ?: throw IOException("No folder for the model")
        val staging = File(parent, ".${target.name}.downloading")
        if (!staging.isDirectory && !staging.mkdirs()) throw IOException("Cannot create $staging")
        val total = files.sumOf { it.size }
        val have = files.sumOf { File(staging, it.name).length().coerceAtMost(it.size) }
        val needed = total - have + SPARE_BYTES
        if (parent.usableSpace < needed) return Result.NoRoom(needed)

        var done = have
        for (file in files) {
            val local = File(staging, file.name)
            if (local.length() > file.size) local.delete()
            if (local.length() < file.size) {
                done -= local.length()
                val fetched = fetch(file, local, { n -> progress(done + n, total) }, canceled)
                if (!fetched) return Result.Canceled
                done += file.size
            }
        }
        for (file in files) {
            if (canceled()) return Result.Canceled
            if (sha256(File(staging, file.name)) != file.sha256) {
                File(staging, file.name).delete()
                return Result.Damaged(file.name)
            }
        }
        return when (val installed = ModelImporter(target).install(staging)) {
            is ModelImporter.Result.Imported -> {
                staging.deleteRecursively()
                Result.Done(installed.bytes)
            }
            is ModelImporter.Result.Invalid -> {
                staging.deleteRecursively()
                Result.Invalid(installed.reason)
            }
            else -> Result.Invalid("the model could not be installed")
        }
    }

    /** Fills [local] up to the file's size, from where it stopped. False if [canceled]. */
    private fun fetch(file: ModelFile, local: File, written: (Long) -> Unit, canceled: () -> Boolean): Boolean {
        val start = local.length()
        val connection = URL("$baseUrl/${file.name}").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        if (start > 0) connection.setRequestProperty("Range", "bytes=$start-")
        try {
            val code = connection.responseCode
            // A server that ignores the range sends it all, so it starts again.
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (!append && code != HttpURLConnection.HTTP_OK) throw IOException("The server answered $code for ${file.name}")
            var count = if (append) start else 0L
            written(count)
            connection.inputStream.use { input ->
                FileOutputStream(local, append).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        if (canceled()) return false
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        count += n
                        written(count)
                    }
                }
            }
            if (count != file.size) throw IOException("${file.name} stopped at $count of ${file.size} bytes")
            return true
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SOURCE = "https://huggingface.co/OleehyO/TexTeller"
        private const val COMMIT = "7b96df06b9d81cdb129c3bef68b7250bc3e2b0ea"
        const val BASE_URL = "$SOURCE/resolve/$COMMIT"

        val FILES = listOf(
            ModelFile(OnnxTexTellerModel.ENCODER, 343_553_824, "071afc0a0c92b2ee3847612127eb9e82b1c39413725e692f4405e5ea9e265bf0"),
            ModelFile(OnnxTexTellerModel.DECODER, 909_186_959, "8b78f10f67da98528fb507977f6d041fe0a8fb486a56b2132156f93b2d5de575"),
            ModelFile(OnnxTexTellerModel.VOCAB, VOCAB_SIZE, VOCAB_SHA256),
        )

        val TOTAL_BYTES: Long get() = FILES.sumOf { it.size }

        private const val SPARE_BYTES = 200L * 1024 * 1024
        private const val TIMEOUT_MS = 60_000
    }
}

private const val VOCAB_SIZE = 146_663L
private const val VOCAB_SHA256 = "6c5fdc7c688b7da8ca9f6abca73ff2c12c08e14386461daf9af1f0716b31e359"
