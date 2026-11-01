package io.github.iamthebarrow.nibtexa.storage

import android.util.Log
import io.github.iamthebarrow.nibtexa.core.export.BackupBooklet
import io.github.iamthebarrow.nibtexa.core.export.BackupClip
import io.github.iamthebarrow.nibtexa.core.export.BackupDetails
import io.github.iamthebarrow.nibtexa.core.export.BackupFolder
import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.PenKind
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.BookletInfo
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.ColumnMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.DocumentStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin
import io.github.iamthebarrow.nibtexa.core.model.notebook.Clip
import io.github.iamthebarrow.nibtexa.core.model.notebook.Clipboard
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageView
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import io.github.iamthebarrow.nibtexa.core.model.notebook.SheetSize
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMarks
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import io.github.iamthebarrow.nibtexa.core.model.serialization.ClipJson
import io.github.iamthebarrow.nibtexa.core.model.serialization.DiagramJson
import io.github.iamthebarrow.nibtexa.core.model.serialization.GraphJson
import io.github.iamthebarrow.nibtexa.core.model.serialization.InkFormatException
import io.github.iamthebarrow.nibtexa.core.model.serialization.InkJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Loads and saves the library: booklets, their pages, strokes and regions.
 *
 * Saves are incremental for strokes: added, removed and moved ones are written (a moved
 * stroke keeps its id, so it is detected as a different object). A page's regions are
 * replaced. Each save is one transaction.
 *
 * A loaded booklet carries its cover details ([BookletInfo]): its own author, or
 * [defaultAuthor] when it has none, and its dates as days in [zone].
 */
class NotebookRepository(
    private val dao: NotebookDao,
    private val newId: () -> String = ::randomId,
    private val now: () -> Long = System::currentTimeMillis,
    private val defaultAuthor: () -> String = { "" },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val mutex = Mutex()

    /** The strokes stored per page, so a save writes only the difference. */
    private val storedStrokes = mutableMapOf<String, Map<Long, Stroke>>()

    /** Returns the first booklet, creating "Notebook" with one empty page if there is none. */
    suspend fun loadOrCreate(): Booklet = mutex.withLock {
        val booklet = dao.firstBooklet() ?: newBookletEntity(DEFAULT_TITLE)
        load(booklet)
    }

    /** Loads a booklet with all its pages, or null if it does not exist. */
    suspend fun loadBooklet(id: String): Booklet? = mutex.withLock { dao.booklet(id)?.let { load(it) } }

    suspend fun library(): List<BookletSummary> = mutex.withLock { dao.booklets() }

    /** Creates a booklet with one empty page on [paper], with cover colour [cover]. */
    suspend fun createBooklet(title: String, paper: Paper = Paper.BLANK, cover: Int = 0, sheet: Sheet = Sheet(), folderId: String? = null, margin: PageMargin = PageMargin.NORMAL): Booklet =
        mutex.withLock { load(newBookletEntity(title, paper, cover, sheet, folderId, margin)) }

    /**
     * Stores an imported booklet (Phase 18) called [title], by [author] (null: the app's author),
     * with [pages] of typed regions; page ids are replaced with new ones.
     */
    suspend fun importBooklet(title: String, author: String?, pages: List<Page>, cover: Int = 0, sheet: Sheet = Sheet(), folderId: String? = null): Booklet = mutex.withLock {
        val booklet = BookletEntity(
            "b-${newId()}", title, now(), cover, createdAt = now(), author = author?.trim()?.takeIf { it.isNotEmpty() },
            sheet = sheet.size.name, landscape = sheet.landscape, customWidth = sheet.customWidthInches, customHeight = sheet.customHeightInches, folderId = folderId,
        )
        val stored = pages.map { it.copy(id = "p-${newId()}") }
        dao.importBooklet(
            booklet,
            stored.mapIndexed { i, p -> PageEntity(p.id, booklet.id, i, p.ink.nextStrokeId, p.nextRegionNumber, p.paper.name, p.manualOrder, background = p.background, columns = p.columns.name, margin = p.margin?.name) },
            stored.flatMap { p -> p.regions.map { it.toEntity(p.id) } },
        )
        load(booklet)
    }

    suspend fun setCover(id: String, cover: Int) = mutex.withLock { dao.setCover(id, cover) }

    /** The booklet's cover details and alignment as the exports use them now, or null if it does not exist. */
    suspend fun info(id: String): BookletInfo? = mutex.withLock { dao.booklet(id)?.let(::infoOf) }

    /** The booklet's own author (null: the app's author name is used). */
    suspend fun ownAuthor(id: String): String? = mutex.withLock { dao.booklet(id)?.author }

    /** Sets the booklet's own author ([author] null or blank: use the app's) and equation alignment. */
    suspend fun setBookletInfo(id: String, author: String?, mathAlign: MathAlign, sheet: Sheet? = null) = mutex.withLock {
        val current = sheet ?: dao.booklet(id)?.let { infoOf(it).sheet } ?: Sheet()
        dao.setBookletInfo(id, author?.trim()?.takeIf { it.isNotEmpty() }, mathAlign.name, current.size.name, current.landscape, current.customWidthInches, current.customHeightInches, now())
    }

    /** How the booklet is set as a document (Phase 30). */
    suspend fun setDocumentStyle(id: String, style: DocumentStyle) = mutex.withLock {
        dao.setDocumentStyle(id, style.titleOnFirstPage, style.numberedSections, style.runningHeader, style.margin.name)
    }

    /**
     * Booklets whose title or handwriting (recognized or confirmed text, captions, LaTeX, and
     * scratch notes) contains [query], ignoring case, with the first matching text of each (null
     * when only the title matched; scratch only when nothing else matched). Most recently
     * changed first.
     */
    suspend fun search(query: String): List<Pair<BookletSummary, SearchHit?>> = mutex.withLock {
        val q = query.trim()
        if (q.isEmpty()) return@withLock dao.booklets().map { it to null }
        val pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val hits = dao.search(pattern).groupBy { it.bookletId }
        dao.booklets().mapNotNull { b ->
            val snippet = hits[b.id]?.firstOrNull { it.snippet != null }
            when {
                hits.containsKey(b.id) -> b to snippet
                b.title.contains(q, ignoreCase = true) -> b to null
                else -> null
            }
        }
    }

    suspend fun renameBooklet(id: String, title: String) = mutex.withLock { dao.renameBooklet(id, title, now()) }

    // ---- Trash (Phase 21) ---------------------------------------------------------------------

    /** Moves a booklet out of the library, keeping everything in it until the Trash is emptied. */
    suspend fun moveToTrash(id: String) = mutex.withLock { dao.setDeletedAt(id, now()) }

    /** Puts a booklet from the Trash back in the library. */
    suspend fun restoreFromTrash(id: String) = mutex.withLock { dao.setDeletedAt(id, null) }

    suspend fun trash(): List<BookletSummary> = mutex.withLock { dao.trash() }

    /** Deletes everything in the Trash for good. */
    suspend fun emptyTrash() = mutex.withLock {
        for (b in dao.trash()) deleteStored(b.id)
    }

    /** Deletes for good what has been in the Trash for [ageMillis] or more. */
    suspend fun purgeTrash(ageMillis: Long) = mutex.withLock {
        for (id in dao.trashedBefore(now() - ageMillis)) deleteStored(id)
    }

    // ---- Backup and restore (Phase 21) -----------------------------------------------------------

    /** Library booklets as a backup holds them: all of them, or those in [ids]. The Trash is left out. */
    suspend fun backupBooklets(ids: Set<String>? = null): List<BackupBooklet> = mutex.withLock {
        dao.booklets().filter { ids == null || it.id in ids }.mapNotNull { summary ->
            val entity = dao.booklet(summary.id) ?: return@mapNotNull null
            BackupBooklet(load(entity), detailsOf(entity))
        }
    }

    /** The ids of [booklets] already on this device, in the library or the Trash. */
    suspend fun existingBooklets(booklets: List<BackupBooklet>): Set<String> = mutex.withLock {
        val all = dao.allBookletIds().toSet()
        booklets.mapTo(linkedSetOf()) { it.booklet.id }.filterTo(linkedSetOf()) { it in all }
    }

    /**
     * Adds [booklets] from a checked backup, all or nothing, with their own ids where those are free.
     * One that already exists comes in as a copy under new ids, or, with [replace], takes the
     * existing one's place while a copy of that goes to the Trash, so nothing is lost either way.
     */
    suspend fun restore(booklets: List<BackupBooklet>, replace: Boolean, clips: List<BackupClip> = emptyList(), folders: List<BackupFolder> = emptyList(), copyTitle: (String) -> String = { it }): Unit = mutex.withLock {
        val existing = dao.allBookletIds().toSet()
        val items = booklets.map { (booklet, details) ->
            val exists = booklet.id in existing
            if (exists && replace) {
                val current = dao.booklet(booklet.id)!!
                val copy = load(current)
                val kept = stored(copy, detailsOf(current), keepIds = false, deletedAt = now())
                RestoreItem(stored(booklet, details, keepIds = true), replaces = booklet.id, trashCopy = kept)
            } else {
                // Page ids are unique across the library, so any already taken means new ones.
                val pageIds = booklet.pages.map { it.id }
                val taken = pageIds.chunked(MAX_QUERY_IDS).flatMap { dao.existingPages(it) }
                val keepIds = !exists && taken.isEmpty()
                RestoreItem(stored(if (exists) booklet.copy(title = copyTitle(booklet.title)) else booklet, details, keepIds))
            }
        }
        // Clipboard entries already here stay as they are; ones too large to keep are left out.
        val clipRows = clips.mapNotNull { item -> packed(item.clip)?.let { entity(ClipSummary.of(item.id, item.createdAt, item.pinned, item.clip, null), item.clip, it) } }
        dao.restoreBooklets(items, clipRows, Clipboard.MAX_UNPINNED, folders.map { FolderEntity(it.id, it.name, it.cover, it.createdAt) })
        for (item in items) {
            item.replaces?.let { id -> dao.pages(id).forEach { storedStrokes.remove(it.id) } }
        }
    }

    /** Rows for a whole booklet, with its ids or new ones. */
    private fun stored(booklet: Booklet, details: BackupDetails, keepIds: Boolean, deletedAt: Long? = null): StoredBooklet {
        val bookletId = if (keepIds) booklet.id else "b-${newId()}"
        val sheet = details.sheet
        val entity = BookletEntity(
            bookletId, booklet.title, details.updatedAt, details.cover, details.createdAt, details.author, details.mathAlign.name,
            sheet.size.name, sheet.landscape, sheet.customWidthInches, sheet.customHeightInches, deletedAt, details.folderId,
            details.style.titleOnFirstPage, details.style.numberedSections, details.style.runningHeader, details.style.margin.name,
        )
        val pages = booklet.pages.map { page -> (if (keepIds) page.id else "p-${newId()}") to page }
        return StoredBooklet(
            entity,
            pages.mapIndexed { i, (id, p) -> PageEntity(id, bookletId, i, p.ink.nextStrokeId, p.nextRegionNumber, p.paper.name, p.manualOrder, background = p.background, columns = p.columns.name, margin = p.margin?.name) },
            pages.flatMap { (id, p) -> p.ink.strokes.map { it.toEntity(id) } },
            pages.flatMap { (id, p) -> p.regions.map { it.toEntity(id) } },
        )
    }

    private fun detailsOf(booklet: BookletEntity) = BackupDetails(
        booklet.cover, booklet.author, booklet.createdAt, booklet.updatedAt,
        MathAlign.entries.firstOrNull { it.name == booklet.mathAlign } ?: MathAlign.CENTER,
        infoOf(booklet).sheet,
        booklet.folderId,
        infoOf(booklet).style,
    )

    /** Deletes the booklet with all its pages, ink and regions. */
    suspend fun deleteBooklet(id: String) = mutex.withLock { deleteStored(id) }

    private suspend fun deleteStored(id: String) {
        dao.pages(id).forEach { storedStrokes.remove(it.id) }
        dao.deleteBooklet(id)
    }

    /** Copies a booklet (pages, ink, regions and results) under new ids, as [title]. Null if it does not exist. */
    suspend fun duplicateBooklet(id: String, title: String): Booklet? = mutex.withLock {
        val source = dao.booklet(id)?.let { load(it) } ?: return@withLock null
        val original = dao.booklet(id)
        val copy = BookletEntity("b-${newId()}", title, now(), original?.cover ?: 0, now(), original?.author, original?.mathAlign ?: MathAlign.CENTER.name)
            .let { c -> original?.let { c.copy(sheet = it.sheet, landscape = it.landscape, customWidth = it.customWidth, customHeight = it.customHeight, folderId = it.folderId, titleOnFirstPage = it.titleOnFirstPage, numberedSections = it.numberedSections, runningHeader = it.runningHeader, pageMargin = it.pageMargin) } ?: c }
        dao.insertBooklet(copy)
        source.pages.forEachIndexed { position, page ->
            val pageId = "p-${newId()}"
            dao.savePage(
                PageEntity(pageId, copy.id, position, page.ink.nextStrokeId, page.nextRegionNumber, page.paper.name, page.manualOrder, page.view?.scale, page.view?.left, page.view?.top, page.background, page.columns.name, page.margin?.name),
                page.ink.strokes.map { it.toEntity(pageId) },
                emptyList(),
                page.regions.map { it.toEntity(pageId) },
                now(),
            )
        }
        load(copy)
    }

    /** The booklet's first page, for its library thumbnail. */
    suspend fun firstPage(bookletId: String): Page? = mutex.withLock {
        val page = dao.pages(bookletId).firstOrNull() ?: return@withLock null
        Page(
            id = page.id,
            ink = InkDocument(dao.strokes(page.id).map { it.toStroke() }, page.nextStrokeId),
            // Typed regions (Phase 18) have no ink: the thumbnail draws them from their regions.
            regions = dao.regions(page.id).map { it.toRegion() }.filter { it.typed },
            paper = Paper.entries.firstOrNull { it.name == page.paper } ?: Paper.BLANK,
            background = page.background,
        )
    }

    /** Every picture file a booklet (Phase 20) or a clipboard entry (Phase 26) uses. */
    suspend fun usedPictures(): Set<String> = mutex.withLock {
        dao.usedPictures().toSet() + dao.clipPictures().flatMap { it.split(',') }
    }

    // ---- Folders (Phase 27) -----------------------------------------------------------------

    suspend fun folders(): List<FolderSummary> = mutex.withLock { dao.folders() }

    /** Makes a folder and returns its id. */
    suspend fun createFolder(name: String, cover: Int): String = mutex.withLock {
        val id = "f-${newId()}"
        dao.insertFolder(FolderEntity(id, name.trim(), cover, now()))
        id
    }

    suspend fun updateFolder(id: String, name: String, cover: Int) = mutex.withLock { dao.updateFolder(id, name.trim(), cover) }

    /** Puts a booklet in a folder, or with null at the top level. */
    suspend fun moveToFolder(bookletId: String, folderId: String?) = mutex.withLock { dao.setBookletFolder(bookletId, folderId) }

    /** The library booklets in a folder. */
    suspend fun bookletsIn(folderId: String): List<String> = mutex.withLock { dao.bookletsIn(folderId) }

    /** Deletes a folder. Its booklets stay in the library at the top level, or with [trash] go to the Trash. */
    suspend fun deleteFolder(id: String, trash: Boolean) = mutex.withLock { dao.deleteFolder(id, if (trash) now() else null) }

    /** The name of the folder a booklet is in, if any. */
    suspend fun folderNameOf(bookletId: String): String? = mutex.withLock { dao.folderNameOf(bookletId) }

    /** The folders [booklets] are in, for a backup; with [all], every folder, empty ones too. */
    suspend fun backupFolders(booklets: List<BackupBooklet>, all: Boolean): List<BackupFolder> = mutex.withLock {
        val used = booklets.mapNotNullTo(HashSet()) { it.details.folderId }
        dao.folderRows().filter { all || it.id in used }.map { BackupFolder(it.id, it.name, it.cover, it.createdAt) }
    }

    // ---- Clipboard (Phase 26) ---------------------------------------------------------------

    /** The list as the panel shows it: pinned first, then the most recent. */
    suspend fun clips(): List<ClipSummary> = mutex.withLock { dao.clips() }

    /**
     * Keeps [clip] as the entry [summary], letting go of the oldest one not pinned beyond the limit. Returns false, keeping
     * nothing, if it is too large to store.
     */
    suspend fun addClip(summary: ClipSummary, clip: Clip): Boolean {
        val content = withContext(Dispatchers.Default) { packed(clip) } ?: return false
        mutex.withLock { dao.addClips(listOf(entity(summary, clip, content)), Clipboard.MAX_UNPINNED) }
        return true
    }

    /** The entry [id], or null if it is gone or can't be read. */
    suspend fun clip(id: String): Clip? {
        val content = mutex.withLock { dao.clipContent(id) } ?: return null
        return withContext(Dispatchers.Default) { unpacked(content) }
    }

    /** Pins or unpins an entry. An unpinned one may then be over the limit, and go. */
    suspend fun setClipPinned(id: String, pinned: Boolean) = mutex.withLock { dao.setClipPinned(id, pinned, Clipboard.MAX_UNPINNED) }

    suspend fun deleteClip(id: String) = mutex.withLock { dao.deleteClip(id) }

    suspend fun clearClips() = mutex.withLock { dao.clearClips() }

    /** Every entry that can be read, for a whole-library backup, oldest first. */
    suspend fun backupClips(): List<BackupClip> = clips().asReversed().mapNotNull { s -> clip(s.id)?.let { BackupClip(s.id, s.createdAt, s.pinned, it) } }

    private fun entity(summary: ClipSummary, clip: Clip, content: ByteArray) = ClipEntity(
        summary.id, summary.createdAt, summary.pinned, summary.equations, summary.words, summary.drawn, summary.handwritten, summary.sourcePage,
        clip.pictures.joinToString(","), content,
    )

    /** [clip] compressed; null if it would be too large for one row to read back, or to hold as text on the way there. */
    private fun packed(clip: Clip): ByteArray? {
        if (clip.strokes.sumOf { it.points.size } > MAX_CLIP_POINTS) return null
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(ClipJson.encode(clip).toByteArray(Charsets.UTF_8)) }
        return out.toByteArray().takeIf { it.size <= MAX_CLIP_BYTES }
    }

    private fun unpacked(content: ByteArray): Clip? = try {
        ClipJson.decode(GZIPInputStream(content.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8))
    } catch (e: IOException) {
        Log.w(TAG, "A clipboard entry can't be read", e)
        null
    } catch (e: InkFormatException) {
        Log.w(TAG, "A clipboard entry can't be read", e)
        null
    }

    /** Adds an empty page at the end of the booklet and returns it. */
    suspend fun addPage(bookletId: String, position: Int): Page = mutex.withLock {
        val page = PageEntity("p-${newId()}", bookletId, position, nextStrokeId = 1, nextRegionNumber = 1)
        dao.upsertPage(page)
        storedStrokes[page.id] = emptyMap()
        Page(page.id)
    }

    /**
     * Deletes the page with its ink and regions, and renumbers [remaining] (the booklet's other
     * page ids, in order) from 0. Pending saves must be written first.
     */
    suspend fun deletePage(bookletId: String, pageId: String, remaining: List<String>) = mutex.withLock {
        dao.deletePage(bookletId, pageId, remaining, now())
        storedStrokes.remove(pageId)
    }

    /** Stores how [pageId] is shown, without counting it as an edit (Phase 17). */
    suspend fun setPageView(pageId: String, view: PageView?) = mutex.withLock {
        dao.setPageView(pageId, view?.scale, view?.left, view?.top)
    }

    suspend fun savePage(bookletId: String, position: Int, page: Page) = mutex.withLock {
        val stored = storedStrokes[page.id].orEmpty()
        val current = page.ink.strokes.associateBy { it.id }
        val changed = current.values.filter { stored[it.id] !== it }.map { it.toEntity(page.id) }
        val removed = stored.keys.filter { it !in current }
        dao.savePage(
            PageEntity(page.id, bookletId, position, page.ink.nextStrokeId, page.nextRegionNumber, page.paper.name, page.manualOrder, page.view?.scale, page.view?.left, page.view?.top, page.background, page.columns.name, page.margin?.name),
            changed,
            removed,
            page.regions.map { it.toEntity(page.id) },
            now(),
        )
        storedStrokes[page.id] = current
    }

    private suspend fun newBookletEntity(title: String, paper: Paper = Paper.BLANK, cover: Int = 0, sheet: Sheet = Sheet(), folderId: String? = null, margin: PageMargin = PageMargin.NORMAL): BookletEntity =
        BookletEntity("b-${newId()}", title, now(), cover, createdAt = now(), pageMargin = margin.name, sheet = sheet.size.name, landscape = sheet.landscape, customWidth = sheet.customWidthInches, customHeight = sheet.customHeightInches, folderId = folderId).also {
            dao.createBooklet(it, PageEntity("p-${newId()}", it.id, 0, nextStrokeId = 1, nextRegionNumber = 1, paper = paper.name))
        }

    private suspend fun load(booklet: BookletEntity): Booklet {
        val pages = dao.pages(booklet.id).map { page ->
            val strokes = dao.strokes(page.id).map { it.toStroke() }
            storedStrokes[page.id] = strokes.associateBy { it.id }
            Page(
                id = page.id,
                ink = InkDocument(strokes, page.nextStrokeId),
                regions = dao.regions(page.id).map { it.toRegion() },
                nextRegionNumber = page.nextRegionNumber,
                paper = Paper.entries.firstOrNull { it.name == page.paper } ?: Paper.BLANK,
                manualOrder = page.manualOrder,
                columns = ColumnMode.entries.firstOrNull { it.name == page.columns } ?: ColumnMode.AUTO,
                margin = PageMargin.entries.firstOrNull { it.name == page.margin },
                view = page.viewScale?.let { PageView(it, page.viewLeft ?: 0f, page.viewTop ?: 0f).clamped() },
                background = page.background,
            )
        }
        return Booklet(booklet.id, booklet.title, pages, infoOf(booklet))
    }

    private fun infoOf(booklet: BookletEntity) = BookletInfo(
        author = booklet.author ?: defaultAuthor(),
        created = day(booklet.createdAt),
        updated = day(booklet.updatedAt),
        mathAlign = MathAlign.entries.firstOrNull { it.name == booklet.mathAlign } ?: MathAlign.CENTER,
        sheet = Sheet(SheetSize.entries.firstOrNull { it.name == booklet.sheet } ?: SheetSize.A4, booklet.landscape, booklet.customWidth, booklet.customHeight),
    style = DocumentStyle(booklet.titleOnFirstPage, booklet.numberedSections, booklet.runningHeader, PageMargin.entries.firstOrNull { it.name == booklet.pageMargin } ?: PageMargin.NORMAL),
    )

    private fun day(millis: Long): LocalDate? = millis.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone()).toLocalDate() }

    private fun Stroke.toEntity(pageId: String) =
        StrokeEntity(pageId, id, tool.name, pen.name, startTimeMillis, InkJson.encodePoints(points), kind.name)

    private fun StrokeEntity.toStroke() =
        Stroke(id, ToolType.valueOf(tool), startTimeMillis, InkJson.decodePoints(points), Pen.valueOf(pen), PenKind.entries.firstOrNull { it.name == kind } ?: PenKind.GEL)

    private fun Region.toEntity(pageId: String) = RegionEntity(
        pageId, id, role.name, strokeIds.joinToString(","), bounds.left, bounds.top, bounds.right, bounds.bottom,
        readingOrder, status.name, sourceStrokeIds.joinToString(","), recognizedLatex, confirmedLatex, error,
        text, headingLevel, caption, recognizedText, auto, align?.name,
        TextMarks.encode(marks), theorem?.name, listLevel, newParagraph, numbered, inline, pageBreak, typed, picture, graph?.let(GraphJson::encode), diagram?.let(DiagramJson::encode),
    )

    private fun RegionEntity.toRegion() = Region(
        id = id,
        role = RegionRole.valueOf(role),
        strokeIds = ids(strokeIds),
        bounds = Bounds(left, top, right, bottom),
        readingOrder = readingOrder,
        // A recognition that was running when the process ended will never report back.
        status = RecognitionStatus.valueOf(status).let { if (it == RecognitionStatus.RECOGNIZING) RecognitionStatus.FAILED else it },
        sourceStrokeIds = ids(sourceStrokeIds),
        recognizedLatex = recognizedLatex,
        confirmedLatex = confirmedLatex,
        error = if (RecognitionStatus.valueOf(status) == RecognitionStatus.RECOGNIZING) INTERRUPTED else error,
        text = text,
        headingLevel = headingLevel,
        caption = caption,
        recognizedText = recognizedText,
        auto = auto,
        align = MathAlign.entries.firstOrNull { it.name == align },
        marks = TextMarks.decode(marks),
        theorem = TheoremKind.entries.firstOrNull { it.name == theorem },
        listLevel = listLevel?.coerceIn(0, 2),
        newParagraph = newParagraph,
        numbered = numbered,
        inline = inlineMath,
        pageBreak = pageBreak,
        typed = typed,
        picture = picture,
        graph = graph?.let(GraphJson::decode),
        diagram = diagram?.let(DiagramJson::decode),
    )

    private fun ids(text: String): Set<Long> = if (text.isEmpty()) emptySet() else text.split(',').mapTo(linkedSetOf()) { it.toLong() }

    companion object {
        const val DEFAULT_TITLE = "Notebook"
        const val INTERRUPTED = "Interrupted: the app was closed during recognition"
        private const val MAX_QUERY_IDS = 500
        private const val TAG = "NotebookRepository"

        /** A row must fit Android's 2 MB cursor window to be read back. Handwriting compresses to a small part of this. */
        const val MAX_CLIP_BYTES = 1_500_000

        /** More ink than this is refused before it is written out as text, which would take a large part of the app's memory. */
        const val MAX_CLIP_POINTS = 100_000

        /** Short ids for path-safe filenames (see LatexExporter). */
        fun randomId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
