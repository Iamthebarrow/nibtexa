package io.github.iamthebarrow.nibtexa.storage

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "booklet")
data class BookletEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** When the booklet was last changed, in epoch milliseconds (0 before Phase 12). Sorts the library. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /** The cover colour, an index into the theme's cover palette (Phase 13). */
    @ColumnInfo(defaultValue = "0") val cover: Int = 0,
    /** When the booklet was created, in epoch milliseconds; 0 when unknown (Phase 14). */
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    /** The author on the cover page; null uses the app's author name (Phase 14). */
    val author: String? = null,
    /** How exports place equations: a `MathAlign` name (Phase 14). */
    @ColumnInfo(defaultValue = "CENTER") val mathAlign: String = "CENTER",
    /** The pages' paper (a `SheetSize` name) and whether it is on its side (Phase 19). */
    @ColumnInfo(defaultValue = "A4") val sheet: String = "A4",
    @ColumnInfo(defaultValue = "0") val landscape: Boolean = false,
    /** A custom sheet's size in inches (`SheetSize.CUSTOM`, Phase 20: an imported PDF's); 0 otherwise. */
    @ColumnInfo(defaultValue = "0") val customWidth: Float = 0f,
    @ColumnInfo(defaultValue = "0") val customHeight: Float = 0f,
    /** When the booklet was moved to the Trash, in epoch milliseconds (Phase 21); null while it is in the library. */
    val deletedAt: Long? = null,
    /** The library folder it is in (Phase 27); null at the top level, or if that folder is gone. */
    val folderId: String? = null,
    /** How it is set as a document (`DocumentStyle`, Phase 30). */
    @ColumnInfo(defaultValue = "0") val titleOnFirstPage: Boolean = false,
    @ColumnInfo(defaultValue = "0") val numberedSections: Boolean = false,
    @ColumnInfo(defaultValue = "0") val runningHeader: Boolean = false,
    /** A `PageMargin` name (Phase 30). */
    @ColumnInfo(defaultValue = "NORMAL") val pageMargin: String = "NORMAL",
)

/** A folder in the library (Phase 27). Only one level: booklets go in folders, folders don't. */
@Entity(tableName = "folder")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val cover: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "page",
    foreignKeys = [ForeignKey(entity = BookletEntity::class, parentColumns = ["id"], childColumns = ["bookletId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookletId")],
)
data class PageEntity(
    @PrimaryKey val id: String,
    val bookletId: String,
    val position: Int,
    val nextStrokeId: Long,
    val nextRegionNumber: Int,
    @ColumnInfo(defaultValue = "BLANK") val paper: String = "BLANK",
    /** The user arranged the reading order by hand (`Page.manualOrder`, Phase 14). */
    @ColumnInfo(defaultValue = "0") val manualOrder: Boolean = false,
    /** How the page was last shown on this device (`Page.view`, Phase 17); null until zoomed or moved. */
    val viewScale: Float? = null,
    val viewLeft: Float? = null,
    val viewTop: Float? = null,
    /** The picture under the ink (`Page.background`, Phase 20), a name in the picture store. */
    val background: String? = null,
    /** Whether the page is in columns (`Page.columns`, Phase 30). */
    @ColumnInfo(defaultValue = "AUTO") val columns: String = "AUTO",
    /** Its own margin (`Page.margin`, a `PageMargin` name, Phase 30); null uses the booklet's. */
    val margin: String? = null,
)

/** Strokes are immutable once written; rows are only inserted and deleted. */
@Entity(
    tableName = "stroke",
    primaryKeys = ["pageId", "id"],
    foreignKeys = [ForeignKey(entity = PageEntity::class, parentColumns = ["id"], childColumns = ["pageId"], onDelete = ForeignKey.CASCADE)],
)
data class StrokeEntity(
    val pageId: String,
    val id: Long,
    val tool: String,
    val pen: String,
    val startTimeMillis: Long,
    /** InkJson.encodePoints. */
    val points: String,
    /** The kind of pen (a `PenKind` name, Phase 22). */
    @ColumnInfo(defaultValue = "GEL") val kind: String = "GEL",
)

@Entity(
    tableName = "region",
    primaryKeys = ["pageId", "id"],
    foreignKeys = [ForeignKey(entity = PageEntity::class, parentColumns = ["id"], childColumns = ["pageId"], onDelete = ForeignKey.CASCADE)],
)
data class RegionEntity(
    val pageId: String,
    val id: String,
    val role: String,
    /** Comma-separated stroke ids. */
    val strokeIds: String,
    @ColumnInfo(name = "boundsLeft") val left: Float,
    @ColumnInfo(name = "boundsTop") val top: Float,
    @ColumnInfo(name = "boundsRight") val right: Float,
    @ColumnInfo(name = "boundsBottom") val bottom: Float,
    val readingOrder: Int,
    val status: String,
    val sourceStrokeIds: String,
    val recognizedLatex: String?,
    val confirmedLatex: String?,
    val error: String?,
    val text: String? = null,
    @ColumnInfo(defaultValue = "1") val headingLevel: Int = 1,
    val caption: String? = null,
    val recognizedText: String? = null,
    @ColumnInfo(defaultValue = "0") val auto: Boolean = false,
    /** Where the region sits across the exported page (a `MathAlign` name); null: its role's default (Phase 14). */
    val align: String? = null,
    // Phase 16: formatting (`TextMarks` JSON), a theorem kind (a `TheoremKind` name) and structure.
    val marks: String? = null,
    val theorem: String? = null,
    val listLevel: Int? = null,
    val newParagraph: Boolean? = null,
    @ColumnInfo(defaultValue = "0") val numbered: Boolean = false,
    val inlineMath: Boolean? = null,
    @ColumnInfo(defaultValue = "0") val pageBreak: Boolean = false,
    /** Typed, not written: imported text or maths with no ink (Phase 18). */
    @ColumnInfo(defaultValue = "0") val typed: Boolean = false,
    /** A picture region's image, a name in the picture store (Phase 20). */
    val picture: String? = null,
    /** A graph region's equations and view, as JSON (Phase 25). */
    val graph: String? = null,
    /** A diagram region's shapes, lines and labels, as JSON (Phase 31). */
    val diagram: String? = null,
)

/**
 * A clipboard entry (Phase 26). [content] is the clip's `ClipJson`, compressed; the other columns are what the panel
 * shows without reading it.
 */
@Entity(tableName = "clip")
class ClipEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val pinned: Boolean,
    /** Its confirmed equations, one per line. */
    val equations: String,
    /** The start of its confirmed words. */
    val words: String,
    /** Something in it can only be shown by drawing it. */
    val drawn: Boolean,
    /** A confirmed equation has its handwriting, so it can paste either way. */
    val handwritten: Boolean,
    /** The page it was copied on; null once restored from a backup. */
    val sourcePage: String?,
    /** Picture files it uses, comma-separated, so that they are kept. */
    val pictures: String,
    val content: ByteArray,
)

/** A clipboard entry as the panel lists it. */
data class ClipSummary(
    val id: String,
    val createdAt: Long,
    val pinned: Boolean = false,
    val equations: String = "",
    val words: String = "",
    val drawn: Boolean = false,
    val handwritten: Boolean = false,
    val sourcePage: String? = null,
) {
    val equationList: List<String> get() = if (equations.isEmpty()) emptyList() else equations.split('\n')

    companion object {
        private const val WORDS_SHOWN = 200

        fun of(id: String, createdAt: Long, pinned: Boolean, clip: io.github.iamthebarrow.nibtexa.core.model.notebook.Clip, sourcePage: String?) = ClipSummary(
            id,
            createdAt,
            pinned,
            clip.equations.joinToString("\n") { it.replace('\n', ' ') },
            clip.words.joinToString("\n").take(WORDS_SHOWN),
            clip.isDrawn,
            clip.hasHandwrittenEquations,
            sourcePage,
        )
    }
}

@Dao
interface NotebookDao {
    @Query("SELECT * FROM booklet WHERE deletedAt IS NULL ORDER BY rowid LIMIT 1")
    suspend fun firstBooklet(): BookletEntity?

    @Query("SELECT * FROM booklet WHERE id = :id")
    suspend fun booklet(id: String): BookletEntity?

    /**
     * Most recently changed first; booklets never changed since Phase 12 keep their creation
     * order. [BookletSummary.pending] counts results waiting for the user to confirm.
     */
    @Query(
        "SELECT b.id AS id, b.title AS title, b.updatedAt AS updatedAt, b.cover AS cover, b.deletedAt AS deletedAt, b.folderId AS folderId, " +
            "b.sheet AS sheet, b.landscape AS landscape, b.customWidth AS customWidth, b.customHeight AS customHeight, " +
            "(SELECT COUNT(*) FROM page p WHERE p.bookletId = b.id) AS pageCount, " +
            "(SELECT COUNT(*) FROM region r JOIN page p ON r.pageId = p.id WHERE p.bookletId = b.id AND r.status = 'RECOGNIZED' AND r.role != 'EXCLUDED') AS pending " +
            "FROM booklet b WHERE b.deletedAt IS NULL ORDER BY b.updatedAt DESC, b.rowid",
    )
    suspend fun booklets(): List<BookletSummary>

    /**
     * Regions whose recognized or confirmed text, caption or LaTeX contains [pattern] (a LIKE
     * pattern with `\` as the escape), with their booklet: library search (Phase 13).
     */
    @Query(
        "SELECT p.bookletId AS bookletId, COALESCE(r.text, r.recognizedText, r.caption, r.confirmedLatex, r.recognizedLatex) AS snippet, " +
            "r.role = 'EXCLUDED' AS scratch, r.pageId AS pageId, r.id AS regionId " +
            "FROM region r JOIN page p ON r.pageId = p.id " +
            "WHERE r.text LIKE :pattern ESCAPE '\\' OR r.recognizedText LIKE :pattern ESCAPE '\\' OR r.caption LIKE :pattern ESCAPE '\\' " +
            "OR r.confirmedLatex LIKE :pattern ESCAPE '\\' OR r.recognizedLatex LIKE :pattern ESCAPE '\\' " +
            // Notes before scratch, so a card shows a scratch match only when nothing else matched.
            "ORDER BY r.role = 'EXCLUDED', p.position, r.readingOrder",
    )
    suspend fun search(pattern: String): List<SearchHit>

    /** The Trash (Phase 21), most recently deleted first. */
    @Query(
        "SELECT b.id AS id, b.title AS title, b.updatedAt AS updatedAt, b.cover AS cover, b.deletedAt AS deletedAt, b.folderId AS folderId, 0 AS pending, " +
            "b.sheet AS sheet, b.landscape AS landscape, b.customWidth AS customWidth, b.customHeight AS customHeight, " +
            "(SELECT COUNT(*) FROM page p WHERE p.bookletId = b.id) AS pageCount " +
            "FROM booklet b WHERE b.deletedAt IS NOT NULL ORDER BY b.deletedAt DESC, b.rowid",
    )
    suspend fun trash(): List<BookletSummary>

    @Query("UPDATE booklet SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?)

    @Query("SELECT id FROM booklet WHERE deletedAt IS NOT NULL AND deletedAt <= :before")
    suspend fun trashedBefore(before: Long): List<String>

    @Query("SELECT id FROM page WHERE id IN (:ids)")
    suspend fun existingPages(ids: List<String>): List<String>

    @Query("SELECT id FROM booklet")
    suspend fun allBookletIds(): List<String>

    @Query("UPDATE booklet SET cover = :cover WHERE id = :id")
    suspend fun setCover(id: String, cover: Int)

    @Query("UPDATE booklet SET titleOnFirstPage = :titleOnFirstPage, numberedSections = :numberedSections, runningHeader = :runningHeader, pageMargin = :pageMargin WHERE id = :id")
    suspend fun setDocumentStyle(id: String, titleOnFirstPage: Boolean, numberedSections: Boolean, runningHeader: Boolean, pageMargin: String)

    @Query("UPDATE booklet SET author = :author, mathAlign = :mathAlign, sheet = :sheet, landscape = :landscape, customWidth = :customWidth, customHeight = :customHeight, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setBookletInfo(id: String, author: String?, mathAlign: String, sheet: String, landscape: Boolean, customWidth: Float, customHeight: Float, updatedAt: Long)

    @Query("UPDATE booklet SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameBooklet(id: String, title: String, updatedAt: Long)

    @Query("UPDATE booklet SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchBooklet(id: String, updatedAt: Long)

    /** Deletes the booklet; its pages, strokes and regions go with it (foreign-key cascade). */
    @Query("DELETE FROM booklet WHERE id = :id")
    suspend fun deleteBooklet(id: String)

    /** Every picture file a region or page uses (Phase 20), so the others can be removed. */
    @Query("SELECT picture FROM region WHERE picture IS NOT NULL UNION SELECT background FROM page WHERE background IS NOT NULL")
    suspend fun usedPictures(): List<String>

    // ---- Folders (Phase 27) -----------------------------------------------------------------

    /** By name, with how many library booklets each holds. */
    @Query(
        "SELECT f.id AS id, f.name AS name, f.cover AS cover, " +
            "(SELECT COUNT(*) FROM booklet b WHERE b.folderId = f.id AND b.deletedAt IS NULL) AS bookletCount " +
            "FROM folder f ORDER BY f.name COLLATE NOCASE, f.createdAt",
    )
    suspend fun folders(): List<FolderSummary>

    @Query("SELECT * FROM folder")
    suspend fun folderRows(): List<FolderEntity>

    @Insert
    suspend fun insertFolder(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFoldersIfNew(folders: List<FolderEntity>)

    @Query("UPDATE folder SET name = :name, cover = :cover WHERE id = :id")
    suspend fun updateFolder(id: String, name: String, cover: Int)

    /** Moving a booklet isn't an edit of it, so its date stays. */
    @Query("UPDATE booklet SET folderId = :folderId WHERE id = :id")
    suspend fun setBookletFolder(id: String, folderId: String?)

    @Query("SELECT id FROM booklet WHERE folderId = :folderId AND deletedAt IS NULL")
    suspend fun bookletsIn(folderId: String): List<String>

    @Query("SELECT f.name FROM folder f JOIN booklet b ON b.folderId = f.id WHERE b.id = :bookletId")
    suspend fun folderNameOf(bookletId: String): String?

    @Query("UPDATE booklet SET deletedAt = :deletedAt WHERE folderId = :folderId AND deletedAt IS NULL")
    suspend fun trashBookletsIn(folderId: String, deletedAt: Long)

    @Query("UPDATE booklet SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)

    @Query("DELETE FROM folder WHERE id = :id")
    suspend fun deleteFolderRow(id: String)

    /** Deletes a folder; its booklets stay in the library, or with [trashedAt] go to the Trash, and belong to no folder. */
    @Transaction
    suspend fun deleteFolder(id: String, trashedAt: Long?) {
        trashedAt?.let { trashBookletsIn(id, it) }
        clearFolder(id)
        deleteFolderRow(id)
    }

    // ---- Clipboard (Phase 26) ---------------------------------------------------------------

    @Query("SELECT id, createdAt, pinned, equations, words, drawn, handwritten, sourcePage FROM clip ORDER BY pinned DESC, createdAt DESC")
    suspend fun clips(): List<ClipSummary>

    @Query("SELECT content FROM clip WHERE id = :id")
    suspend fun clipContent(id: String): ByteArray?

    /** The picture lists of entries that use pictures. */
    @Query("SELECT pictures FROM clip WHERE pictures != ''")
    suspend fun clipPictures(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClips(clips: List<ClipEntity>)

    @Query("DELETE FROM clip WHERE pinned = 0 AND id NOT IN (SELECT id FROM clip WHERE pinned = 0 ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trimClips(keep: Int)

    @Transaction
    suspend fun addClips(clips: List<ClipEntity>, keep: Int) {
        insertClips(clips)
        trimClips(keep)
    }

    @Transaction
    suspend fun setClipPinned(id: String, pinned: Boolean, keep: Int) {
        setPinned(id, pinned)
        trimClips(keep)
    }

    @Query("UPDATE clip SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM clip WHERE id = :id")
    suspend fun deleteClip(id: String)

    @Query("DELETE FROM clip")
    suspend fun clearClips()

    @Query("SELECT * FROM page WHERE bookletId = :bookletId ORDER BY position")
    suspend fun pages(bookletId: String): List<PageEntity>

    @Query("DELETE FROM page WHERE id = :id")
    suspend fun deletePageRow(id: String)

    /** The page's view only: zooming or moving the page is not an edit, so the booklet's date stays (Phase 17). */
    @Query("UPDATE page SET viewScale = :scale, viewLeft = :left, viewTop = :top WHERE id = :id")
    suspend fun setPageView(id: String, scale: Float?, left: Float?, top: Float?)

    @Query("UPDATE page SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Int)

    /** Deletes a page (its strokes and regions cascade) and numbers [remaining] 0, 1, 2… in that order. */
    @Transaction
    suspend fun deletePage(bookletId: String, pageId: String, remaining: List<String>, updatedAt: Long) {
        deletePageRow(pageId)
        remaining.forEachIndexed { i, id -> setPosition(id, i) }
        touchBooklet(bookletId, updatedAt)
    }

    @Query("SELECT * FROM stroke WHERE pageId = :pageId ORDER BY id")
    suspend fun strokes(pageId: String): List<StrokeEntity>

    @Query("SELECT * FROM region WHERE pageId = :pageId")
    suspend fun regions(pageId: String): List<RegionEntity>

    @Insert
    suspend fun insertBooklet(booklet: BookletEntity)

    @Upsert
    suspend fun upsertPage(page: PageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStrokes(strokes: List<StrokeEntity>)

    @Query("DELETE FROM stroke WHERE pageId = :pageId AND id IN (:ids)")
    suspend fun deleteStrokes(pageId: String, ids: List<Long>)

    @Query("DELETE FROM region WHERE pageId = :pageId")
    suspend fun deleteRegions(pageId: String)

    @Insert
    suspend fun insertRegions(regions: List<RegionEntity>)

    /** Writes one page change atomically: either all of it is stored or none. */
    @Transaction
    suspend fun savePage(page: PageEntity, addedStrokes: List<StrokeEntity>, removedStrokeIds: List<Long>, regions: List<RegionEntity>, updatedAt: Long) {
        touchBooklet(page.bookletId, updatedAt)
        upsertPage(page)
        if (removedStrokeIds.isNotEmpty()) removedStrokeIds.chunked(MAX_SQL_ARGS).forEach { deleteStrokes(page.id, it) }
        if (addedStrokes.isNotEmpty()) insertStrokes(addedStrokes)
        deleteRegions(page.id)
        if (regions.isNotEmpty()) insertRegions(regions)
    }

    @Transaction
    suspend fun createBooklet(booklet: BookletEntity, page: PageEntity) {
        insertBooklet(booklet)
        upsertPage(page)
    }

    /** An imported booklet (Phase 18): its pages and their typed regions, all at once. */
    @Transaction
    suspend fun importBooklet(booklet: BookletEntity, pages: List<PageEntity>, regions: List<RegionEntity>) {
        insertBooklet(booklet)
        pages.forEach { upsertPage(it) }
        regions.chunked(MAX_SQL_ARGS).forEach { insertRegions(it) }
    }

    /**
     * A restore (Phase 21), all or nothing. Each item is a booklet to add; [RestoreItem.replaces]
     * is one to remove first, once its copy is in the Trash.
     */
    @Transaction
    suspend fun restoreBooklets(items: List<RestoreItem>, clips: List<ClipEntity>, keepClips: Int, folders: List<FolderEntity>) {
        if (folders.isNotEmpty()) insertFoldersIfNew(folders)
        for (item in items) {
            item.trashCopy?.let { insertAll(it) }
            item.replaces?.let { deleteBooklet(it) }
            insertAll(item.booklet)
        }
        if (clips.isNotEmpty()) addClips(clips, keepClips)
    }

    private suspend fun insertAll(stored: StoredBooklet) {
        insertBooklet(stored.booklet)
        stored.pages.forEach { upsertPage(it) }
        stored.strokes.chunked(MAX_SQL_ARGS).forEach { insertStrokes(it) }
        stored.regions.chunked(MAX_SQL_ARGS).forEach { insertRegions(it) }
    }

    private companion object {
        const val MAX_SQL_ARGS = 500
    }
}

/** A whole booklet's rows, ready to insert (Phase 21). */
class StoredBooklet(val booklet: BookletEntity, val pages: List<PageEntity>, val strokes: List<StrokeEntity>, val regions: List<RegionEntity>)

/** One booklet of a restore: [booklet] goes in; if it replaces another, that one is removed, and [trashCopy] keeps it in the Trash. */
class RestoreItem(val booklet: StoredBooklet, val replaces: String? = null, val trashCopy: StoredBooklet? = null)

/** A booklet in the library list. */
data class BookletSummary(
    val id: String,
    val title: String,
    val pageCount: Int,
    val updatedAt: Long = 0,
    val cover: Int = 0,
    /** Results waiting for the user to confirm. */
    val pending: Int = 0,
    /** The pages' paper, for the thumbnail's shape (Phase 20); as [BookletEntity]'s. */
    val sheet: String = "A4",
    val landscape: Boolean = false,
    val customWidth: Float = 0f,
    val customHeight: Float = 0f,
    /** When it went to the Trash (Phase 21); null in the library. */
    val deletedAt: Long? = null,
    /** Its folder (Phase 27); null at the top level. */
    val folderId: String? = null,
) {
    fun paper(): io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet = io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet(
        io.github.iamthebarrow.nibtexa.core.model.notebook.SheetSize.entries.firstOrNull { it.name == sheet } ?: io.github.iamthebarrow.nibtexa.core.model.notebook.SheetSize.A4,
        landscape,
        customWidth,
        customHeight,
    )
}

/** A folder as the library shows it (Phase 27). */
data class FolderSummary(val id: String, val name: String, val cover: Int = 0, val bookletCount: Int = 0)

/** A region matching a library search, the text it matched in, whether that is scratch (Phase 15), and where it is (Phase 28). */
data class SearchHit(val bookletId: String, val snippet: String?, val scratch: Boolean = false, val pageId: String? = null, val regionId: String? = null)

@Database(
    entities = [BookletEntity::class, PageEntity::class, StrokeEntity::class, RegionEntity::class, ClipEntity::class, FolderEntity::class],
    version = NotebookDatabase.VERSION,
    exportSchema = true,
)
abstract class NotebookDatabase : RoomDatabase() {
    abstract fun dao(): NotebookDao

    companion object {
        /**
         * Schema history:
         * - 1 (Phase 5): booklet, page, stroke, region.
         * - 2 (Phase 6): page.paper; region.text, region.headingLevel, region.caption.
         * - 3 (Phase 11, ADR-0007): region.recognizedText, region.auto.
         * - 4 (Phase 12): booklet.updatedAt, for the library's "recent first" order.
         * - 5 (Phase 13): booklet.cover, the cover colour.
         * - 6 (Phase 14): booklet.createdAt, booklet.author, booklet.mathAlign (the cover page and
         *   equation alignment); page.manualOrder (reading order follows position by default).
         * - 7 (Phase 14): region.align, a region's own place across the exported page.
         * - 8 (Phase 16): region.marks, theorem, listLevel, newParagraph, numbered, inlineMath and
         *   pageBreak: formatting and document structure.
         * - 9 (Phases 17 and 18): page.viewScale, viewLeft, viewTop (zoom and position on this
         *   device); region.typed (imported regions with no ink).
         * - 10 (Phase 19): booklet.sheet and booklet.landscape, the pages' paper size.
         * - 11 (Phase 20): region.picture (pictures), page.background (PDF pages to write on),
         *   booklet.customWidth and customHeight (a custom paper size).
         * - 12 (Phase 21): booklet.deletedAt, for the Trash.
         * - 13 (Phase 22): stroke.kind, the kind of pen.
         * - 14 (Phase 25): region.graph, a graph on the page.
         * - 19 (Phase 31): region.diagram, a clean figure on the page.
         * - 15 (Phase 26): the clip table, the clipboard.
         * - 16 (Phase 27): the folder table and booklet.folderId.
         */
        const val VERSION = 19
        const val NAME = "notebook.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE page ADD COLUMN paper TEXT NOT NULL DEFAULT 'BLANK'")
                db.execSQL("ALTER TABLE region ADD COLUMN text TEXT")
                db.execSQL("ALTER TABLE region ADD COLUMN headingLevel INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE region ADD COLUMN caption TEXT")
            }
        }

        /** Existing regions were all made by the user, so none is automatic. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE region ADD COLUMN recognizedText TEXT")
                db.execSQL("ALTER TABLE region ADD COLUMN auto INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Existing booklets get 0: unknown, sorted after everything edited since, in creation order. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE booklet ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Existing booklets get the cover colours in turn, in creation order, so the library isn't all one colour. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE booklet ADD COLUMN cover INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE booklet SET cover = (rowid - 1) % $COVER_COUNT")
            }
        }

        /**
         * The creation date was never stored, so existing booklets take their last change as
         * the closest known. Existing pages follow position from their next edit: that is
         * the default order now, and an older order cannot be told apart from a hand-made one.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE booklet ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE booklet ADD COLUMN author TEXT")
                db.execSQL("ALTER TABLE booklet ADD COLUMN mathAlign TEXT NOT NULL DEFAULT 'CENTER'")
                db.execSQL("UPDATE booklet SET createdAt = updatedAt")
                db.execSQL("ALTER TABLE page ADD COLUMN manualOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Existing regions keep their role's default place. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE region ADD COLUMN align TEXT")
            }
        }

        /** Existing regions are unformatted, and their structure follows position. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE region ADD COLUMN marks TEXT")
                db.execSQL("ALTER TABLE region ADD COLUMN theorem TEXT")
                db.execSQL("ALTER TABLE region ADD COLUMN listLevel INTEGER")
                db.execSQL("ALTER TABLE region ADD COLUMN newParagraph INTEGER")
                db.execSQL("ALTER TABLE region ADD COLUMN numbered INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE region ADD COLUMN inlineMath INTEGER")
                db.execSQL("ALTER TABLE region ADD COLUMN pageBreak INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Pages open at their default view; every existing region is written. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE page ADD COLUMN viewScale REAL")
                db.execSQL("ALTER TABLE page ADD COLUMN viewLeft REAL")
                db.execSQL("ALTER TABLE page ADD COLUMN viewTop REAL")
                db.execSQL("ALTER TABLE region ADD COLUMN typed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Existing booklets are A4 portrait, which all their ink fits on. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE booklet ADD COLUMN sheet TEXT NOT NULL DEFAULT 'A4'")
                db.execSQL("ALTER TABLE booklet ADD COLUMN landscape INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Existing regions have no picture, pages no background, and booklets a named paper size. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE region ADD COLUMN picture TEXT")
                db.execSQL("ALTER TABLE page ADD COLUMN background TEXT")
                db.execSQL("ALTER TABLE booklet ADD COLUMN customWidth REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE booklet ADD COLUMN customHeight REAL NOT NULL DEFAULT 0")
            }
        }

        /** Nothing is in the Trash yet. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE booklet ADD COLUMN deletedAt INTEGER")
            }
        }

        /** Existing strokes count as gel, which looks the same as the one pen there was. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stroke ADD COLUMN kind TEXT NOT NULL DEFAULT 'GEL'")
            }
        }

        /** Existing regions have no graph. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE region ADD COLUMN graph TEXT")
            }
        }

        /** The clipboard starts empty. */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `clip` (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, " +
                        "`equations` TEXT NOT NULL, `words` TEXT NOT NULL, `drawn` INTEGER NOT NULL, `handwritten` INTEGER NOT NULL, " +
                        "`sourcePage` TEXT, `pictures` TEXT NOT NULL, `content` BLOB NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** Every booklet stays at the top level, so the library looks as it did. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `folder` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `cover` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("ALTER TABLE booklet ADD COLUMN folderId TEXT")
            }
        }

        /** Pages go on finding their columns by themselves, and booklets are set as they were. */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE page ADD COLUMN columns TEXT NOT NULL DEFAULT 'AUTO'")
                db.execSQL("ALTER TABLE booklet ADD COLUMN titleOnFirstPage INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE booklet ADD COLUMN numberedSections INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE booklet ADD COLUMN runningHeader INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** The margin the PDF always had. */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE booklet ADD COLUMN pageMargin TEXT NOT NULL DEFAULT 'NORMAL'")
                db.execSQL("ALTER TABLE page ADD COLUMN margin TEXT")
            }
        }

        /** Existing regions have no diagram. */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE region ADD COLUMN diagram TEXT")
            }
        }

        /** Cover colours in the theme (`NibPalette.covers`). */
        const val COVER_COUNT = 6

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19)

        fun open(context: Context, name: String? = NAME): NotebookDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context, NotebookDatabase::class.java)
            } else {
                Room.databaseBuilder(context, NotebookDatabase::class.java, name)
            }
            // No destructive fallback: a missing migration must fail loudly, never drop notes.
            // Android deletes a damaged database by default: a copy is kept first (Phase 33).
            return builder.addMigrations(*MIGRATIONS).openHelperFactory(KeepDamagedDatabase(KeepDamagedDatabase.folder(context))).build()
        }
    }
}
