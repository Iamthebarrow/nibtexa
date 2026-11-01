package io.github.iamthebarrow.nibtexa.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.iamthebarrow.nibtexa.core.export.Backup
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate

/** Where automatic backups go: a folder the user picked (Phase 33). */
interface BackupFolder {
    /** The file names in the folder. */
    fun names(): List<String>

    fun create(name: String): OutputStream

    fun delete(name: String)
}

/**
 * Keeps a few recent backups of the whole library in a folder, at most one a day, so that a lost tablet or an uninstall doesn't lose
 * everything. A failed backup leaves its partial file out, and the older ones stay.
 */
object AutoBackups {
    const val KEEP = 3
    const val PREFIX = "NibTeXa-auto-"
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    fun isDue(now: Long, last: Long): Boolean = now - last >= DAY_MILLIS

    /** Writes today's backup with [write], then removes all but the [KEEP] newest. Returns its name. */
    fun run(folder: BackupFolder, today: LocalDate, write: (OutputStream) -> Unit): String {
        val name = "$PREFIX$today${Backup.EXTENSION}"
        if (name in folder.names()) folder.delete(name)
        try {
            folder.create(name).use(write)
        } catch (e: Exception) {
            runCatching { folder.delete(name) }
            throw e
        }
        val ours = folder.names().filter { it.startsWith(PREFIX) && it.endsWith(Backup.EXTENSION) }.sortedDescending()
        ours.drop(KEEP).forEach(folder::delete)
        return name
    }
}

/** A folder from the system folder picker, which the app was given lasting access to. */
class TreeBackupFolder(private val resolver: ContentResolver, private val tree: Uri) : BackupFolder {
    private val root get() = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun children(): Map<String, Uri> {
        val listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return buildMap {
            resolver.query(listing, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) put(cursor.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
            } ?: throw IOException("The backup folder can't be read")
        }
    }

    override fun names(): List<String> = children().keys.toList()

    override fun create(name: String): OutputStream {
        val file = DocumentsContract.createDocument(resolver, root, "application/zip", name) ?: throw IOException("The backup folder can't be written to")
        return resolver.openOutputStream(file, "wt") ?: throw IOException("The backup folder can't be written to")
    }

    override fun delete(name: String) {
        children()[name]?.let { DocumentsContract.deleteDocument(resolver, it) }
    }
}
