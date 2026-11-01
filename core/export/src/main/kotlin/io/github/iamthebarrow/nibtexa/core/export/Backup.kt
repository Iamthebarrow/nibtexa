package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Clip
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import io.github.iamthebarrow.nibtexa.core.model.notebook.SheetSize
import io.github.iamthebarrow.nibtexa.core.model.serialization.BookletJson
import io.github.iamthebarrow.nibtexa.core.model.notebook.DocumentStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin
import io.github.iamthebarrow.nibtexa.core.model.serialization.ClipJson
import io.github.iamthebarrow.nibtexa.core.model.serialization.InkFormatException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** What a booklet keeps beyond its pages: the library's details, as stored. */
data class BackupDetails(
    val cover: Int = 0,
    /** The booklet's own author; null uses the app's author name. */
    val author: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val mathAlign: MathAlign = MathAlign.CENTER,
    val sheet: Sheet = Sheet(),
    /** The folder it is in (Phase 27); null at the top level. */
    val folderId: String? = null,
    val style: DocumentStyle = DocumentStyle(),
)

/** A library folder in a backup (Phase 27). */
data class BackupFolder(val id: String, val name: String, val cover: Int = 0, val createdAt: Long = 0)

/** A booklet in a backup: its pages ([BookletJson]) and its details. */
data class BackupBooklet(val booklet: Booklet, val details: BackupDetails)

/** A clipboard entry in a backup (Phase 26). */
data class BackupClip(val id: String, val createdAt: Long, val pinned: Boolean, val clip: Clip)

/** Why a file can't be restored. */
enum class BackupProblem {
    /** Not a NibTeXa backup, or not a readable zip. */
    NOT_A_BACKUP,

    /** Made by a newer version of the app. */
    TOO_NEW,

    /** A file in it is missing, damaged or too large. */
    DAMAGED,

    /** It tries to reach outside itself, with a path or name no backup writes. */
    UNSAFE,
}

class BackupException(val problem: BackupProblem, message: String, cause: Throwable? = null) : IOException(message, cause)

/** A backup that passed every check. */
class CheckedBackup internal constructor(
    val booklets: List<BackupBooklet>,
    val clips: List<BackupClip>,
    val folders: List<BackupFolder>,
    val pictures: Set<String>,
    private val file: File,
) {
    /** Writes the picture [name] to [out]. */
    fun copyPicture(name: String, out: OutputStream) {
        require(name in pictures) { "No picture $name in the backup" }
        ZipFile(file).use { zip ->
            zip.getInputStream(zip.getEntry(Backup.picturePath(name))).use { it.copyTo(out) }
        }
    }
}

/**
 * The whole-library backup (Phase 21): one zip holding `manifest.json`, each booklet as
 * `booklets/<id>.json` ([BookletJson]) and the pictures and PDF pages they use as
 * `pictures/<name>`.
 *
 * [check] reads and verifies everything before anything can change: the format and version, every
 * path, every booklet document, that every picture a booklet uses is there, and each file's checksum
 * and size. So a damaged or hostile file is refused whole. Nothing in a backup is run.
 *
 * The version says what readers must understand: a later phase that adds data raises it, and [check]
 * keeps reading every earlier version. Version 2 (Phase 22): strokes may carry a pen kind.
 * Version 3 (Phase 25): regions may hold a graph. Version 4 (Phase 26): it may hold clipboard entries, as
 * `clipboard/<id>.json`, with the pictures they use. Version 5 (Phase 27): folders, listed in the manifest.
 * Version 8 (Phase 31): regions may hold a diagram.
 */
object Backup {
    const val FORMAT = "nibtexa-backup"
    const val VERSION = 8
    const val MANIFEST = "manifest.json"

    /** A suggested file name: a `.zip` any file manager can open. */
    const val EXTENSION = ".nibtexa.zip"

    private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    private const val MAX_BOOKLET_BYTES = 256L * 1024 * 1024
    private const val MAX_PICTURE_BYTES = 128L * 1024 * 1024
    private const val MAX_ENTRIES = 200_000
    const val MAX_FOLDER_NAME = 80

    private val SAFE_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9-]{0,63}")

    /** As the picture store names its files. */
    private val PICTURE_NAME = Regex("[a-z0-9]{24}\\.(png|jpg)")

    private val json = Json { prettyPrint = true; prettyPrintIndent = " " }

    fun picturePath(name: String) = "pictures/$name"
    private fun bookletPath(id: String) = "booklets/$id.json"
    private fun clipPath(id: String) = "clipboard/$id.json"

    /** The pictures [booklet] uses, by name. */
    fun picturesOf(booklet: Booklet): Set<String> = booklet.pages.flatMapTo(sortedSetOf()) { page ->
        listOfNotNull(page.background) + page.regions.mapNotNull { it.picture }
    }

    /**
     * Writes [booklets] and clipboard entries [clips] to [out]. [picture] opens a stored picture by name, or gives null
     * if it is missing. Returns the names that were missing. Their booklets keep the reference, and restore
     * accepts a picture listed as missing, so a lost picture never stops the rest being saved.
     */
    fun write(out: OutputStream, booklets: List<BackupBooklet>, clips: List<BackupClip> = emptyList(), folders: List<BackupFolder> = emptyList(), picture: (String) -> InputStream?): Set<String> {
        val missing = sortedSetOf<String>()
        val written = sortedSetOf<String>()
        ZipOutputStream(out).use { zip ->
            for ((booklet, _) in booklets) {
                zip.putNextEntry(ZipEntry(bookletPath(booklet.id)))
                zip.write(BookletJson.encode(booklet).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for (item in clips) {
                zip.putNextEntry(ZipEntry(clipPath(item.id)))
                zip.write(ClipJson.encode(item.clip).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val used = booklets.flatMapTo(sortedSetOf()) { picturesOf(it.booklet) }
            for (item in clips) used += item.clip.pictures
            for (name in used) {
                val input = picture(name)
                if (input == null) {
                    missing += name
                    continue
                }
                zip.putNextEntry(ZipEntry(picturePath(name)))
                input.use { it.copyTo(zip) }
                zip.closeEntry()
                written += name
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest(booklets, clips, folders, written, missing).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return missing
    }

    private fun manifest(booklets: List<BackupBooklet>, clips: List<BackupClip>, folders: List<BackupFolder>, pictures: Set<String>, missing: Set<String>): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("booklets", buildJsonArray {
                for ((booklet, d) in booklets) {
                    add(buildJsonObject {
                        put("id", booklet.id)
                        put("cover", d.cover)
                        d.author?.let { put("author", it) }
                        put("createdAt", d.createdAt)
                        put("updatedAt", d.updatedAt)
                        put("mathAlign", d.mathAlign.name)
                        put("sheet", d.sheet.size.name)
                        put("landscape", d.sheet.landscape)
                        put("customWidth", d.sheet.customWidthInches)
                        put("customHeight", d.sheet.customHeightInches)
                        d.folderId?.let { put("folderId", it) }
                        put("titleOnFirstPage", d.style.titleOnFirstPage)
                        put("numberedSections", d.style.numberedSections)
                        put("runningHeader", d.style.runningHeader)
                        put("margin", d.style.margin.name)
                    })
                }
            })
            put("folders", buildJsonArray {
                for (f in folders) {
                    add(buildJsonObject {
                        put("id", f.id)
                        put("name", f.name)
                        put("cover", f.cover)
                        put("createdAt", f.createdAt)
                    })
                }
            })
            put("clipboard", buildJsonArray {
                for (item in clips) {
                    add(buildJsonObject {
                        put("id", item.id)
                        put("createdAt", item.createdAt)
                        put("pinned", item.pinned)
                    })
                }
            })
            put("pictures", JsonArray(pictures.map(::JsonPrimitive)))
            put("missingPictures", JsonArray(missing.map(::JsonPrimitive)))
        },
    ) + "\n"

    /**
     * Reads and verifies the whole backup at [file] without changing anything.
     *
     * @throws BackupException if any part of it can't be trusted.
     */
    fun check(file: File): CheckedBackup {
        val zip = try {
            ZipFile(file)
        } catch (e: IOException) {
            // Android refuses a zip with unsafe entry names when opening it (apps targeting API 34+).
            val unsafe = e.message.orEmpty().contains("path", ignoreCase = true)
            throw BackupException(if (unsafe) BackupProblem.UNSAFE else BackupProblem.NOT_A_BACKUP, "Not a readable backup file", e)
        }
        return zip.use { check(file, it) }
    }

    private fun check(file: File, zip: ZipFile): CheckedBackup {
        val entries = zip.entries().toList()
        if (entries.size > MAX_ENTRIES) throw BackupException(BackupProblem.DAMAGED, "The backup has too many files")
        for (entry in entries) {
            if (!safePath(entry.name)) throw BackupException(BackupProblem.UNSAFE, "The backup has an unsafe path")
        }
        val manifestEntry = zip.getEntry(MANIFEST) ?: throw BackupException(BackupProblem.NOT_A_BACKUP, "This is not a NibTeXa backup")
        val manifest = try {
            Json.parseToJsonElement(read(zip, manifestEntry, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)).jsonObject
        } catch (e: SerializationException) {
            throw BackupException(BackupProblem.NOT_A_BACKUP, "This is not a NibTeXa backup", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupProblem.NOT_A_BACKUP, "This is not a NibTeXa backup", e)
        }
        if (manifest["format"].asString() != FORMAT) throw BackupException(BackupProblem.NOT_A_BACKUP, "This is not a NibTeXa backup")
        val version = manifest["version"]?.jsonPrimitive?.intOrNull ?: throw BackupException(BackupProblem.DAMAGED, "The backup has no version")
        if (version > VERSION) throw BackupException(BackupProblem.TOO_NEW, "Made by a newer version of the app")
        if (version < 1) throw BackupException(BackupProblem.DAMAGED, "The backup has an unknown version")

        return try {
            val pictures = names(manifest["pictures"])
            val missing = names(manifest["missingPictures"])
            for (name in pictures + missing) {
                if (!PICTURE_NAME.matches(name)) throw BackupException(BackupProblem.UNSAFE, "The backup has an unsafe picture name")
            }
            val booklets = manifest["booklets"]?.jsonArray.orEmpty().map { item -> booklet(zip, item.jsonObject) }
            if (booklets.map { it.booklet.id }.toSet().size != booklets.size) throw BackupException(BackupProblem.DAMAGED, "A booklet is in the backup twice")
            for ((booklet, _) in booklets) {
                val unlisted = picturesOf(booklet) - pictures - missing
                if (unlisted.isNotEmpty()) throw BackupException(BackupProblem.DAMAGED, "A picture a booklet uses is not in the backup")
            }
            val clips = manifest["clipboard"]?.jsonArray.orEmpty().map { item -> clip(zip, item.jsonObject) }
            if (clips.map { it.id }.toSet().size != clips.size) throw BackupException(BackupProblem.DAMAGED, "A clipboard entry is in the backup twice")
            for (item in clips) {
                if ((item.clip.pictures - pictures - missing).isNotEmpty()) throw BackupException(BackupProblem.DAMAGED, "A picture a clipboard entry uses is not in the backup")
            }
            for (name in pictures) {
                val entry = zip.getEntry(picturePath(name)) ?: throw BackupException(BackupProblem.DAMAGED, "A picture is missing from the backup")
                verify(zip, entry, MAX_PICTURE_BYTES)
            }
            val folders = manifest["folders"]?.jsonArray.orEmpty().map { folder(it.jsonObject) }
            if (folders.map { it.id }.toSet().size != folders.size) throw BackupException(BackupProblem.DAMAGED, "A folder is in the backup twice")
            // A folder the backup doesn't list is no folder: the booklet comes back at the top level.
            val known = folders.mapTo(HashSet()) { it.id }
            val placed = booklets.map { b -> if (b.details.folderId in known) b else b.copy(details = b.details.copy(folderId = null)) }
            CheckedBackup(placed, clips, folders, pictures, file)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupProblem.DAMAGED, "The backup's list of contents is damaged", e)
        } catch (e: IOException) {
            if (e is BackupException) throw e
            throw BackupException(BackupProblem.DAMAGED, "A file in the backup is damaged", e)
        }
    }

    private fun folder(item: JsonObject): BackupFolder {
        val id = item["id"].asString().orEmpty()
        if (!SAFE_ID.matches(id)) throw BackupException(BackupProblem.UNSAFE, "The backup has an unsafe folder id")
        val name = item["name"].asString()?.trim()?.take(MAX_FOLDER_NAME)?.takeIf { it.isNotEmpty() } ?: throw BackupException(BackupProblem.DAMAGED, "A folder in the backup has no name")
        return BackupFolder(id, name, item["cover"]?.jsonPrimitive?.intOrNull ?: 0, item["createdAt"]?.jsonPrimitive?.longOrNull ?: 0)
    }

    private fun clip(zip: ZipFile, item: JsonObject): BackupClip {
        val id = item["id"].asString().orEmpty()
        if (!SAFE_ID.matches(id)) throw BackupException(BackupProblem.UNSAFE, "The backup has an unsafe clipboard entry id")
        val entry = zip.getEntry(clipPath(id)) ?: throw BackupException(BackupProblem.DAMAGED, "A clipboard entry is missing from the backup")
        val clip = try {
            ClipJson.decode(read(zip, entry, MAX_BOOKLET_BYTES).toString(Charsets.UTF_8))
        } catch (e: InkFormatException) {
            throw BackupException(BackupProblem.DAMAGED, "A clipboard entry in the backup is damaged", e)
        }
        if (clip.regions.map { it.id }.toSet().size != clip.regions.size) throw BackupException(BackupProblem.DAMAGED, "A clipboard entry in the backup is damaged")
        return BackupClip(id, item["createdAt"]?.jsonPrimitive?.longOrNull ?: 0, item["pinned"]?.jsonPrimitive?.booleanOrNull ?: false, clip)
    }

    private fun booklet(zip: ZipFile, item: JsonObject): BackupBooklet {
        val id = item["id"].asString().orEmpty()
        if (!SAFE_ID.matches(id)) throw BackupException(BackupProblem.UNSAFE, "The backup has an unsafe booklet id")
        val entry = zip.getEntry(bookletPath(id)) ?: throw BackupException(BackupProblem.DAMAGED, "A booklet is missing from the backup")
        val booklet = try {
            BookletJson.decode(read(zip, entry, MAX_BOOKLET_BYTES).toString(Charsets.UTF_8))
        } catch (e: InkFormatException) {
            throw BackupException(BackupProblem.DAMAGED, "A booklet in the backup is damaged", e)
        }
        if (booklet.id != id) throw BackupException(BackupProblem.DAMAGED, "A booklet in the backup is damaged")
        val pageIds = booklet.pages.map { it.id }
        if (pageIds.any { !SAFE_ID.matches(it) }) throw BackupException(BackupProblem.UNSAFE, "The backup has an unsafe page id")
        if (pageIds.toSet().size != pageIds.size) throw BackupException(BackupProblem.DAMAGED, "A booklet in the backup is damaged")
        if (booklet.pages.isEmpty()) throw BackupException(BackupProblem.DAMAGED, "A booklet in the backup has no pages")
        for (page in booklet.pages) {
            if (page.regions.map { it.id }.toSet().size != page.regions.size) throw BackupException(BackupProblem.DAMAGED, "A booklet in the backup is damaged")
        }
        return BackupBooklet(
            booklet,
            BackupDetails(
                cover = item["cover"]?.jsonPrimitive?.intOrNull ?: 0,
                author = item["author"].asString(),
                createdAt = item["createdAt"]?.jsonPrimitive?.longOrNull ?: 0,
                updatedAt = item["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0,
                mathAlign = MathAlign.entries.firstOrNull { it.name == item["mathAlign"].asString() } ?: MathAlign.CENTER,
                sheet = Sheet(
                    SheetSize.entries.firstOrNull { it.name == item["sheet"].asString() } ?: SheetSize.A4,
                    item["landscape"]?.jsonPrimitive?.booleanOrNull ?: false,
                    item["customWidth"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    item["customHeight"]?.jsonPrimitive?.floatOrNull ?: 0f,
                ),
                folderId = item["folderId"].asString(),
                style = DocumentStyle(
                    item["titleOnFirstPage"]?.jsonPrimitive?.booleanOrNull ?: false,
                    item["numberedSections"]?.jsonPrimitive?.booleanOrNull ?: false,
                    item["runningHeader"]?.jsonPrimitive?.booleanOrNull ?: false,
                    PageMargin.entries.firstOrNull { it.name == item["margin"].asString() } ?: PageMargin.NORMAL,
                ),
            ),
        )
    }

    private fun kotlinx.serialization.json.JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun names(element: kotlinx.serialization.json.JsonElement?): Set<String> =
        element?.jsonArray.orEmpty().mapTo(linkedSetOf()) { it.jsonPrimitive.content }

    /** A relative path with no way out: backups only ever write simple forward-slash paths. */
    private fun safePath(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("/") || '\\' in name || ':' in name) return false
        return name.split('/').none { it == ".." || it == "." || it.isEmpty() }
    }

    private fun read(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        consume(zip, entry, limit) { buffer, n -> out.write(buffer, 0, n) }
        return out.toByteArray()
    }

    private fun verify(zip: ZipFile, entry: ZipEntry, limit: Long) = consume(zip, entry, limit) { _, _ -> }

    /** Reads the entry within [limit], whatever size it claims, and checks its checksum. */
    private fun consume(zip: ZipFile, entry: ZipEntry, limit: Long, sink: (ByteArray, Int) -> Unit) {
        val crc = CRC32()
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            zip.getInputStream(entry).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw BackupException(BackupProblem.DAMAGED, "A file in the backup is too large")
                    crc.update(buffer, 0, n)
                    sink(buffer, n)
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: IOException) {
            throw BackupException(BackupProblem.DAMAGED, "A file in the backup is damaged", e)
        }
        if (entry.crc != -1L && crc.value != entry.crc) throw BackupException(BackupProblem.DAMAGED, "A file in the backup is damaged")
    }
}
