package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.DocumentStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.ColumnMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Clip
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** The backup file (Phase 21): what goes in comes back identical, and anything untrustworthy is refused whole. */
class BackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val photo = "a".repeat(24) + ".jpg"
    private val pdfPage = "b".repeat(24) + ".png"
    private val store = mapOf(photo to byteArrayOf(1, 2, 3, 4), pdfPage to ByteArray(200_000) { (it % 7).toByte() })

    private fun booklet(id: String = "b-1") = Booklet(
        id,
        "Signals & \"Systems\"",
        listOf(
            Page(
                "p-1",
                InkDocument(listOf(Stroke(1, ToolType.STYLUS, 1000, listOf(InkPoint(1f, 2f, 0, pressure = 0.5f), InkPoint(3f, 4f, 8)), Pen.MATH)), nextStrokeId = 2),
                listOf(
                    Region("r1", RegionRole.MATH, setOf(1), Bounds(1f, 2f, 3f, 4f), 1, RecognitionStatus.CONFIRMED, setOf(1), "x^2", "x^{2}"),
                    Region("r2", RegionRole.FIGURE, emptySet(), Bounds(0f, 0f, 100f, 50f), 2, typed = true, picture = photo, caption = "Board"),
                ),
                nextRegionNumber = 3,
                paper = Paper.GRID,
                manualOrder = true,
            ),
            Page("p-2", background = pdfPage),
        ),
    )

    private val details = BackupDetails(3, "Ada", 1000, 2000, MathAlign.RIGHT, Sheet.ofPoints(960f, 540f))

    private fun written(vararg booklets: BackupBooklet = arrayOf(BackupBooklet(booklet(), details))): File {
        val file = folder.newFile()
        file.outputStream().use { out -> Backup.write(out, booklets.toList()) { store[it]?.inputStream() } }
        return file
    }

    /** A zip of exactly [entries], for making bad backups. */
    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = folder.newFile()
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return file
    }

    /** The real backup's entries, with some changed or added. */
    private fun altered(change: MutableMap<String, ByteArray>.() -> Unit): File {
        val contents = linkedMapOf<String, ByteArray>()
        ZipFile(written()).use { z -> z.entries().toList().forEach { contents[it.name] = z.getInputStream(it).readBytes() } }
        contents.change()
        return zip(*contents.map { it.key to it.value }.toTypedArray())
    }

    private fun manifest(text: String) = "manifest.json" to text.toByteArray()

    private fun problem(file: File) = assertThrows(BackupException::class.java) { Backup.check(file) }.problem

    @Test
    fun whatGoesInComesBackIdentical() {
        val checked = Backup.check(written())
        val back = checked.booklets.single()
        assertEquals(booklet(), back.booklet)
        assertEquals(details, back.details)
        assertEquals(setOf(photo, pdfPage), checked.pictures)
        for ((name, bytes) in store) {
            val out = ByteArrayOutputStream()
            checked.copyPicture(name, out)
            assertArrayEquals(bytes, out.toByteArray())
        }
    }

    // ---- The clipboard (Phase 26) ---------------------------------------------------------------

    private val clip = Clip(
        listOf(Stroke(1, ToolType.STYLUS, 0, listOf(InkPoint(1f, 2f, 0)), Pen.MATH)),
        listOf(
            Region("r1", RegionRole.MATH, setOf(1), Bounds(1f, 2f, 1f, 2f), 1, RecognitionStatus.CONFIRMED, setOf(1), "x", "x"),
            Region("r2", RegionRole.FIGURE, emptySet(), Bounds(0f, 0f, 10f, 10f), 2, typed = true, picture = photo),
        ),
    )

    private fun withClip(items: List<BackupClip>, booklets: List<BackupBooklet> = emptyList()): File {
        val file = folder.newFile()
        file.outputStream().use { out -> Backup.write(out, booklets, items) { store[it]?.inputStream() } }
        return file
    }

    /** A backup of one clip, with its zip entries changed. */
    private fun alteredClip(change: MutableMap<String, ByteArray>.() -> Unit): File {
        val contents = linkedMapOf<String, ByteArray>()
        ZipFile(withClip(listOf(BackupClip("c1", 5, true, clip)))).use { z -> z.entries().toList().forEach { contents[it.name] = z.getInputStream(it).readBytes() } }
        contents.change()
        return zip(*contents.map { it.key to it.value }.toTypedArray())
    }

    @Test
    fun clipboardEntriesAndTheirPicturesComeBack() {
        val items = listOf(BackupClip("c1", 5, true, clip), BackupClip("c2", 9, false, Clip(clip.strokes)))
        val checked = Backup.check(withClip(items))
        assertEquals(items, checked.clips)
        assertEquals(setOf(photo), checked.pictures)
        val out = ByteArrayOutputStream()
        checked.copyPicture(photo, out)
        assertArrayEquals(store.getValue(photo), out.toByteArray())
    }

    /** Phase 30: columns are in the booklet's document, and the document settings in its details. */
    @Test
    fun columnsAndTheDocumentSettingsComeBack() {
        val style = DocumentStyle(titleOnFirstPage = true, numberedSections = true, runningHeader = true, margin = io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin.CHEAT_SHEET)
        val original = booklet().let { b -> b.copy(pages = b.pages.map { it.copy(columns = ColumnMode.TWO, margin = io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin.WIDE) }) }
        val file = folder.newFile()
        file.outputStream().use { out -> Backup.write(out, listOf(BackupBooklet(original, details.copy(style = style)))) { store[it]?.inputStream() } }
        val back = Backup.check(file).booklets.single()
        assertEquals(original, back.booklet)
        assertEquals(style, back.details.style)
    }

    @Test
    fun aBackupFromBeforeTheDocumentSettingsHasTheOldLook() {
        val old = altered {
            val text = getValue("manifest.json").toString(Charsets.UTF_8).replace("\"version\": ${Backup.VERSION}", "\"version\": 5")
                .replace(Regex(",\\s*\"(titleOnFirstPage|numberedSections|runningHeader|margin)\": \"?\\w+\"?"), "")
            put("manifest.json", text.toByteArray())
        }
        assertEquals(DocumentStyle(), Backup.check(old).booklets.single().details.style)
    }

    @Test
    fun aBackupFromBeforeTheClipboardHasNone() {
        val old = altered {
            val text = getValue("manifest.json").toString(Charsets.UTF_8).replace("\"version\": ${Backup.VERSION}", "\"version\": 3").replace(Regex("\"clipboard\": \\[\\s*],?"), "")
            put("manifest.json", text.toByteArray())
        }
        val checked = Backup.check(old)
        assertEquals(emptyList<BackupClip>(), checked.clips)
        assertEquals(booklet(), checked.booklets.single().booklet)
    }

    @Test
    fun aBadClipboardEntryIsRefusedWhole() {
        assertEquals(BackupProblem.DAMAGED, problem(alteredClip { remove("clipboard/c1.json") }))
        assertEquals(BackupProblem.DAMAGED, problem(alteredClip { put("clipboard/c1.json", "{}".toByteArray()) }))
        assertEquals(BackupProblem.UNSAFE, problem(alteredClip { put("manifest.json", getValue("manifest.json").toString(Charsets.UTF_8).replace("\"id\": \"c1\"", "\"id\": \"../c1\"").toByteArray()) }))
        // A picture it uses must be accounted for.
        assertEquals(BackupProblem.DAMAGED, problem(alteredClip { put("manifest.json", getValue("manifest.json").toString(Charsets.UTF_8).replace("\"$photo\"", "").toByteArray()) }))
    }

    // ---- Folders (Phase 27) ----------------------------------------------------------------------

    private val folderList = listOf(BackupFolder("f-1", "Term 1 & \"more\"", 2, 77), BackupFolder("f-empty", "Empty", 1, 88))

    private fun withFolders(): File {
        val file = folder.newFile()
        file.outputStream().use { out -> Backup.write(out, listOf(BackupBooklet(booklet(), details.copy(folderId = "f-1"))), folders = folderList) { store[it]?.inputStream() } }
        return file
    }

    /** A backup with folders, with its manifest's text changed. */
    private fun alteredFolders(change: (String) -> String): File {
        val contents = linkedMapOf<String, ByteArray>()
        ZipFile(withFolders()).use { z -> z.entries().toList().forEach { contents[it.name] = z.getInputStream(it).readBytes() } }
        contents["manifest.json"] = change(contents.getValue("manifest.json").toString(Charsets.UTF_8)).toByteArray()
        return zip(*contents.map { it.key to it.value }.toTypedArray())
    }

    @Test
    fun foldersAndWhereABookletIsComeBack() {
        val checked = Backup.check(withFolders())
        assertEquals(folderList, checked.folders)
        assertEquals("f-1", checked.booklets.single().details.folderId)
    }

    @Test
    fun aBackupFromBeforeFoldersPutsEverythingAtTheTop() {
        val checked = Backup.check(alteredFolders { it.replace(Regex("\"folders\": \\[.*?],\\s*\"clipboard\"", RegexOption.DOT_MATCHES_ALL), "\"clipboard\"").replace(Regex(",\\s*\"folderId\": \"f-1\""), "").replace("\"version\": ${Backup.VERSION}", "\"version\": 4") })
        assertEquals(emptyList<BackupFolder>(), checked.folders)
        assertEquals(null, checked.booklets.single().details.folderId)
    }

    @Test
    fun aBookletInAFolderTheBackupDoesNotListIsAtTheTop() {
        val checked = Backup.check(alteredFolders { it.replace("\"folderId\": \"f-1\"", "\"folderId\": \"f-gone\"") })
        assertEquals(null, checked.booklets.single().details.folderId)
    }

    @Test
    fun aBadFolderIsRefusedWhole() {
        assertEquals(BackupProblem.UNSAFE, problem(alteredFolders { it.replace("\"id\": \"f-1\"", "\"id\": \"../f\"") }))
        assertEquals(BackupProblem.DAMAGED, problem(alteredFolders { it.replace("\"name\": \"Empty\"", "\"name\": \"  \"") }))
        assertEquals(BackupProblem.DAMAGED, problem(alteredFolders { it.replace("\"id\": \"f-empty\"", "\"id\": \"f-1\"") }))
    }

    @Test
    fun aVeryLongFolderNameIsCut() {
        val checked = Backup.check(alteredFolders { it.replace("\"name\": \"Empty\"", "\"name\": \"${"n".repeat(500)}\"") })
        assertEquals(Backup.MAX_FOLDER_NAME, checked.folders.last().name.length)
    }

    @Test
    fun severalBookletsShareAPictureOnce() {
        val file = written(BackupBooklet(booklet("b-1"), details), BackupBooklet(booklet("b-2"), BackupDetails()))
        val checked = Backup.check(file)
        assertEquals(listOf("b-1", "b-2"), checked.booklets.map { it.booklet.id })
        ZipFile(file).use { z -> assertEquals(1, z.entries().toList().count { it.name == Backup.picturePath(photo) }) }
    }

    @Test
    fun aMissingPictureIsReportedAndDoesNotStopTheBackup() {
        val file = folder.newFile()
        val missing = file.outputStream().use { out -> Backup.write(out, listOf(BackupBooklet(booklet(), details))) { name -> store[name]?.takeIf { name != photo }?.inputStream() } }
        assertEquals(setOf(photo), missing)
        val checked = Backup.check(file)
        assertEquals(setOf(pdfPage), checked.pictures)
        assertEquals(booklet(), checked.booklets.single().booklet)
    }

    @Test
    fun otherFilesAreNotBackups() {
        assertEquals(BackupProblem.NOT_A_BACKUP, problem(folder.newFile().apply { writeText("hello") }))
        assertEquals(BackupProblem.NOT_A_BACKUP, problem(zip("main.tex" to "x".toByteArray())))
        assertEquals(BackupProblem.NOT_A_BACKUP, problem(zip(manifest("not json"))))
        assertEquals(BackupProblem.NOT_A_BACKUP, problem(zip(manifest("""{"format":"other","version":1}"""))))
    }

    @Test
    fun anEarlierVersionStillRestoresAndItsStrokesAreGel() {
        val checked = Backup.check(altered { put("manifest.json", getValue("manifest.json").toString(Charsets.UTF_8).replace("\"version\": ${Backup.VERSION}", "\"version\": 1").toByteArray()) })
        assertEquals(booklet(), checked.booklets.single().booklet)
        assertEquals(io.github.iamthebarrow.nibtexa.core.model.PenKind.GEL, checked.booklets.single().booklet.pages.first().ink.strokes.single().kind)
    }

    @Test
    fun aNewerBackupIsRefused() {
        assertEquals(BackupProblem.TOO_NEW, problem(zip(manifest("""{"format":"${Backup.FORMAT}","version":${Backup.VERSION + 1}}"""))))
    }

    @Test
    fun aDamagedFileIsRefused() {
        // Cut short.
        val whole = written().readBytes()
        assertEquals(BackupProblem.NOT_A_BACKUP, problem(folder.newFile().apply { writeBytes(whole.copyOf(whole.size / 2)) }))
        // A missing picture or booklet, or a broken booklet document.
        assertEquals(BackupProblem.DAMAGED, problem(altered { remove(Backup.picturePath(photo)) }))
        assertEquals(BackupProblem.DAMAGED, problem(altered { remove("booklets/b-1.json") }))
        assertEquals(BackupProblem.DAMAGED, problem(altered { put("booklets/b-1.json", "{}".toByteArray()) }))
        // A booklet using a picture that isn't listed.
        assertEquals(
            BackupProblem.DAMAGED,
            problem(altered { put("manifest.json", getValue("manifest.json").toString(Charsets.UTF_8).replace("\"$photo\"", "").replace(",\n \n", "\n").replace("[\n  ,", "[").toByteArray()) }),
        )
    }

    @Test
    fun aFlippedByteIsCaught() {
        val bytes = written().readBytes()
        val file = folder.newFile()
        var refused = 0
        // Every byte of a small backup, or an even spread of a larger one.
        for (at in bytes.indices step maxOf(1, bytes.size / 2000)) {
            val copy = bytes.copyOf()
            copy[at] = (copy[at].toInt() xor 0x55).toByte()
            file.writeBytes(copy)
            // A damaged copy either still passes (a flip in unused zip metadata) or is refused cleanly: never a crash.
            val failure = runCatching { Backup.check(file) }.exceptionOrNull() ?: continue
            assertEquals("at byte $at: $failure", BackupException::class.java, failure.javaClass)
            refused++
        }
        assertEquals("most flips must be noticed", true, refused > 0)
    }

    @Test
    fun unsafePathsAndNamesAreRefusedWhole() {
        assertEquals(BackupProblem.UNSAFE, problem(altered { put("../evil.txt", byteArrayOf(1)) }))
        assertEquals(BackupProblem.UNSAFE, problem(altered { put("/etc/evil.txt", byteArrayOf(1)) }))
        assertEquals(BackupProblem.UNSAFE, problem(altered { put("pictures\\..\\evil.txt", byteArrayOf(1)) }))
        val base = """{"format":"${Backup.FORMAT}","version":1,"""
        assertEquals(BackupProblem.UNSAFE, problem(zip(manifest(base + """"booklets":[],"pictures":["../../evil.png"]}"""))))
        assertEquals(BackupProblem.UNSAFE, problem(zip(manifest(base + """"booklets":[{"id":"../x"}],"pictures":[]}"""))))
    }

    @Test
    fun anOverLongBookletIsRefusedWhateverItClaims() {
        // A file that unpacks far beyond its zip size still stops at the limit, rather than filling memory.
        val file = folder.newFile()
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(ByteArray(20 * 1024 * 1024) { ' '.code.toByte() })
            z.closeEntry()
        }
        assertEquals(BackupProblem.DAMAGED, problem(file))
    }

    @Test
    fun anEmptyLibraryBacksUp() {
        val checked = Backup.check(written(*emptyArray()))
        assertEquals(emptyList<BackupBooklet>(), checked.booklets)
    }

    @Test
    fun aGraphComesBackAsItWas() {
        val graph = io.github.iamthebarrow.nibtexa.core.model.notebook.Graph(
            listOf(io.github.iamthebarrow.nibtexa.core.model.notebook.GraphCurve("y=kx", "r1", 3, hidden = true, input = "x")),
            -1.0, 2.0, -3.0, 4.0, true, mapOf("k" to 2.5), "Title", "Time", "Speed",
        )
        val region = Region("r1", RegionRole.FIGURE, emptySet(), Bounds(0f, 0f, 400f, 300f), 1, typed = true, graph = graph)
        val booklet = Booklet("b-9", "Graphs", listOf(Page("p-9", InkDocument.EMPTY, listOf(region), nextRegionNumber = 2)))
        val file = written(BackupBooklet(booklet, BackupDetails()))
        val back = Backup.check(file).booklets.single().booklet
        assertEquals(graph, back.pages.single().region("r1")!!.graph)
    }
}
