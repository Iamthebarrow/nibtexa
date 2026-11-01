package io.github.iamthebarrow.nibtexa.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** What the app does with a generated file that was changed outside it. */
enum class KeepMode {
    /** Leave the outside version alone and stop generating that file. */
    @SerialName("keep")
    KEEP,

    /** Leave the outside version alone and write the app's version next to it. */
    @SerialName("copy")
    COPY,
}

/**
 * The record committed next to an export (`.nibtexa-sync.json`): the hash of every file the
 * app wrote, and the files the user chose to keep after editing them elsewhere. Comparing the
 * repository with it is how edits made outside the app are found, on any device.
 *
 * Paths are relative to the booklet folder.
 */
data class SyncRecord(
    val files: Map<String, String> = emptyMap(),
    val kept: Map<String, KeepMode> = emptyMap(),
) {
    /** Deterministic: sorted keys, LF line ends, trailing newline. */
    fun encode(): String = json.encodeToString(RecordFile.serializer(), RecordFile(SCHEMA, files.toSortedMap(), kept.toSortedMap())) + "\n"

    companion object {
        const val FILE_NAME = ".nibtexa-sync.json"
        private const val SCHEMA = 1
        private val json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /** @throws SyncException with [SyncError.CorruptRecord] if [text] is not a record. */
        fun decode(text: String): SyncRecord {
            val file = try {
                json.decodeFromString(RecordFile.serializer(), text)
            } catch (e: SerializationException) {
                throw SyncException(SyncError.CorruptRecord, e)
            } catch (e: IllegalArgumentException) {
                throw SyncException(SyncError.CorruptRecord, e)
            }
            if (file.schema != SCHEMA) throw SyncException(SyncError.CorruptRecord)
            return SyncRecord(file.files, file.kept)
        }

        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
private data class RecordFile(
    val schema: Int,
    val files: Map<String, String>,
    val kept: Map<String, KeepMode> = emptyMap(),
)
