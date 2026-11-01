package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.export.Backup
import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageEditor
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Phase 21 in the app: Delete goes to the Trash, the Trash restores and deletes for good, and a booklet
 * with ink, results, a picture and a PDF page comes back identical from a backup. Files that can't be
 * trusted change nothing. Screenshots of the Trash are kept.
 */
@RunWith(AndroidJUnit4::class)
class TrashAndBackupTest {
    private val services = TestServicesRule(startInLibrary = true)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value
    private val cache get() = composeRule.activity.cacheDir

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun png(color: Int): ByteArray {
        val bitmap = createBitmap(60, 40).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** A booklet with ink, a confirmed equation, a picture and a PDF page behind its second page, in the app's storage. */
    private fun richBooklet(title: String = "Signals"): String = runBlocking {
        val picture = services.pictures.add(png(Color.RED))
        val pdfPage = services.pictures.add(png(Color.BLUE))
        val repository = services.freshRepository()
        val editor = PageEditor(Page("import"))
        editor.addStroke(CapturedStroke(ToolType.STYLUS, 1000, listOf(InkPoint(40f, 40f, 0, pressure = 0.4f), InkPoint(140f, 90f, 12)), Pen.MATH))
        val equation = editor.createRegion(setOf(editor.page.ink.strokes.single().id), RegionRole.MATH)!!
        editor.confirm(equation, "H(s)=\\frac{1}{s+1}")
        editor.setCaption(editor.addPicture(picture.file, Bounds(200f, 200f, 500f, 400f)), "The board")
        val created = repository.importBooklet(title, "Ada", listOf(editor.page, Page("import2", background = pdfPage.file)), cover = 4, sheet = Sheet.ofPoints(960f, 540f))
        // importBooklet stores regions and page settings; strokes are saved like any edit.
        val first = created.pages.first().copy(ink = editor.page.ink)
        repository.savePage(created.id, 0, first)
        created.id
    }

    private fun refreshedLibrary() {
        composeRule.runOnUiThread { viewModel().openTrash(); viewModel().closeTrash() }
        // Any change that refreshes the library will do; a Trash restore of nothing is the simplest.
        composeRule.runOnUiThread { viewModel().restoreFromTrash("none") }
        composeRule.waitForIdle()
    }

    private fun stored(id: String): Booklet = runBlocking { services.freshRepository().loadBooklet(id)!! }

    /** The pages as they should survive a backup: everything but how they were last shown on this device. */
    private fun content(booklet: Booklet) = booklet.pages.map { it.copy(view = null) }

    private fun backupTo(name: String, ids: Set<String>? = null): File {
        val file = File(cache, name).apply { delete() }
        composeRule.runOnUiThread { viewModel().exportBackup(Uri.fromFile(file), ids) }
        waitFor { !state().exporting && file.length() > 0 }
        return file
    }

    private fun prepare(file: File) {
        composeRule.runOnUiThread { viewModel().prepareRestore(Uri.fromFile(file)) }
        waitFor { !state().exporting }
    }

    private fun screenshot(name: String) {
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theTrashScreenRestoresAndDeletesForGood() {
        waitFor { state().libraryLoaded }
        val keep = runBlocking { services.freshRepository().createBooklet("Comes back").id }
        val gone = runBlocking { services.freshRepository().createBooklet("Goes for good").id }
        val both = runBlocking { services.freshRepository().createBooklet("Emptied").id }
        refreshedLibrary()
        waitFor { state().library.size == 3 }
        composeRule.runOnUiThread { viewModel().moveToTrash(keep); viewModel().moveToTrash(gone); viewModel().moveToTrash(both) }
        waitFor { state().library.isEmpty() && state().trash.size == 3 }

        composeRule.runOnUiThread { viewModel().openTrash() }
        waitFor { state().screen == Screen.TRASH }
        composeRule.onNodeWithTag(TRASH_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Comes back").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trash_empty)).assertIsEnabled()
        screenshot("trash.png")

        // A row's Restore button brings it back.
        val keepRow = state().trash.indexOfFirst { it.id == keep }
        composeRule.onAllNodes(androidx.compose.ui.test.hasText(string(R.string.trash_restore)))[keepRow].performClick()
        waitFor { state().library.singleOrNull()?.id == keep && state().trash.size == 2 }

        // Delete for good asks first; Cancel keeps it.
        val goneRow = state().trash.indexOfFirst { it.id == gone }
        composeRule.onAllNodes(androidx.compose.ui.test.hasText(string(R.string.trash_delete_for_good)))[goneRow].performClick()
        composeRule.onNodeWithText(string(R.string.delete_booklet_title, "Goes for good")).assertIsDisplayed()
        screenshot("trash-delete.png")
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        assertEquals(2, state().trash.size)
        composeRule.onAllNodes(androidx.compose.ui.test.hasText(string(R.string.trash_delete_for_good)))[goneRow].performClick()
        composeRule.onNodeWithText(string(R.string.delete)).performClick()
        waitFor { state().trash.singleOrNull()?.id == both }
        assertNull(runBlocking { services.freshRepository().loadBooklet(gone) })

        composeRule.onNodeWithText(string(R.string.trash_empty)).performClick()
        composeRule.onNodeWithText(string(R.string.trash_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        assertEquals(1, state().trash.size)
        composeRule.onNodeWithText(string(R.string.trash_empty)).performClick()
        composeRule.onAllNodesAndClickLastEmpty()
        waitFor { state().trash.isEmpty() }
        composeRule.onNodeWithText(string(R.string.trash_empty_state)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trash_empty)).assertIsNotEnabled()
        screenshot("trash-empty.png")
        composeRule.onNodeWithContentDescription(string(R.string.back_to_library)).performClick()
        waitFor { state().screen == Screen.LIBRARY }
        assertEquals(keep, state().library.single().id)
    }

    /** The dialog's button has the same text as the screen's, and comes last. */
    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesAndClickLastEmpty() {
        onAllNodes(androidx.compose.ui.test.hasText(string(R.string.trash_empty)))[1].performClick()
    }

    @Test
    fun theLibraryMenuOffersTheTrashBackupAndRestore() {
        waitFor { state().libraryLoaded }
        runBlocking { services.freshRepository().createBooklet("Week 1"); services.freshRepository().createBooklet("Week 2") }
        refreshedLibrary()
        waitFor { state().library.size == 2 }
        fun menu() = composeRule.onNodeWithContentDescription(string(R.string.more)).performClick()

        menu()
        composeRule.onNodeWithText(string(R.string.restore_menu)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.backup_menu)).performClick()
        composeRule.onNodeWithTag(BACKUP_DIALOG_TAG).assertIsDisplayed()
        // Whole library by default. Chosen booklets need at least one chosen before saving.
        composeRule.onNodeWithText(string(R.string.backup_save)).assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.backup_chosen)).performClick()
        composeRule.onNodeWithText(string(R.string.backup_save)).assertIsNotEnabled()
        composeRule.onNode(androidx.compose.ui.test.hasText("Week 2") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(BACKUP_DIALOG_TAG))).performClick()
        composeRule.onNodeWithText(string(R.string.backup_save)).assertIsEnabled()
        Thread.sleep(400)
        screenshot("backup-dialog.png")
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()

        val id = state().library.first().id
        composeRule.runOnUiThread { viewModel().moveToTrash(id) }
        waitFor { state().trash.size == 1 }
        menu()
        composeRule.onNodeWithText(string(R.string.trash_with_count, 1)).performClick()
        waitFor { state().screen == Screen.TRASH }
        Thread.sleep(400)
        screenshot("trash-one.png")
        // Back returns to the library.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor { state().screen == Screen.LIBRARY }

        // The restore question, as it looks with a booklet already here.
        val file = backupTo("menu.nibtexa.zip")
        prepare(file)
        waitFor { state().restorePrompt != null }
        Thread.sleep(400)
        screenshot("restore-dialog.png")
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        waitFor { state().restorePrompt == null }
    }

    @Test
    fun theAutoEmptySettingIsRemembered() {
        waitFor { state().libraryLoaded }
        assertTrue(state().trashAutoEmpty)
        composeRule.runOnUiThread { viewModel().openTrash() }
        waitFor { state().screen == Screen.TRASH }
        composeRule.onNodeWithText(string(R.string.trash_auto_empty)).performClick()
        waitFor { !state().trashAutoEmpty }
        composeRule.runOnUiThread { viewModel().setTrashAutoEmpty(true) }
        waitFor { state().trashAutoEmpty }
    }

    @Test
    fun aDeletedOpenBookletClosesAndIsNotKeptInMemory() {
        waitFor { state().libraryLoaded }
        val id = runBlocking { services.freshRepository().createBooklet("Open").id }
        composeRule.runOnUiThread { viewModel().openBooklet(id) }
        waitFor { state().loaded && state().bookletId == id }
        composeRule.runOnUiThread { viewModel().closeBooklet() }
        waitFor { state().screen == Screen.LIBRARY }
        composeRule.runOnUiThread { viewModel().moveToTrash(id) }
        waitFor { state().trash.singleOrNull()?.id == id }
        assertEquals("", state().bookletId)
        assertEquals(false, state().loaded)
    }

    @Test
    fun aBookletComesBackIdenticalFromABackup() {
        waitFor { state().libraryLoaded }
        val id = richBooklet()
        val before = stored(id)
        assertEquals(1, before.pages.first().ink.strokes.size)
        val pictures = Backup.picturesOf(before)
        assertEquals(2, pictures.size)
        val pictureBytes = pictures.associateWith { services.pictures.file(it)!!.readBytes() }
        refreshedLibrary()
        val file = backupTo("whole.nibtexa.zip")
        assertTrue(state().message.orEmpty(), state().message == composeRule.activity.resources.getQuantityString(R.plurals.backup_done, 1, 1))

        // Lost entirely: the booklet and its picture files.
        composeRule.runOnUiThread { viewModel().deleteForGood(id) }
        waitFor { state().library.isEmpty() && state().trash.isEmpty() }
        runBlocking { services.pictures.removeUnused(services.freshRepository().usedPictures()) }
        pictures.forEach { assertNull(services.pictures.file(it)) }

        prepare(file)
        waitFor { state().restorePrompt != null }
        assertEquals(RestorePrompt(listOf("Signals"), 0), state().restorePrompt)
        composeRule.onNodeWithTag(RESTORE_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.restore_add)).performClick()
        waitFor { !state().exporting && state().library.size == 1 }

        val after = stored(id)
        assertEquals(before.title, after.title)
        assertEquals(content(before), content(after))
        assertEquals(before.info, after.info)
        assertEquals(4, state().library.single().cover)
        pictureBytes.forEach { (name, bytes) -> assertArrayEquals(bytes, services.pictures.file(name)!!.readBytes()) }
        // And it can be edited, as any booklet can.
        composeRule.runOnUiThread { viewModel().openBooklet(id) }
        waitFor { state().loaded && state().bookletId == id }
        assertEquals("H(s)=\\frac{1}{s+1}", state().page.regions.first { it.role == RegionRole.MATH }.confirmedLatex)
    }

    @Test
    fun aTrashedBookletsPicturesSurviveTheCleanUpAndChosenBookletsBackUpAlone() {
        waitFor { state().libraryLoaded }
        val trashed = richBooklet("Trashed")
        val kept = runBlocking { services.freshRepository().createBooklet("Kept").id }
        refreshedLibrary()
        waitFor { state().library.size == 2 }
        val pictures = Backup.picturesOf(stored(trashed))
        composeRule.runOnUiThread { viewModel().moveToTrash(trashed) }
        waitFor { state().trash.size == 1 }
        runBlocking { services.pictures.removeUnused(services.freshRepository().usedPictures()) }
        pictures.forEach { assertNotNull(services.pictures.file(it)) }

        // The Trash stays out of a whole-library backup, and a chosen booklet comes alone.
        assertEquals(listOf("Kept"), Backup.check(backupTo("whole.nibtexa.zip")).booklets.map { it.booklet.title })
        composeRule.runOnUiThread { viewModel().dismissMessage(); viewModel().restoreFromTrash(trashed) }
        waitFor { state().library.size == 2 }
        val chosen = Backup.check(backupTo("chosen.nibtexa.zip", setOf(trashed)))
        assertEquals(listOf(trashed), chosen.booklets.map { it.booklet.id })
        assertEquals(pictures, chosen.pictures)
        assertNotEquals(kept, trashed)
    }

    @Test
    fun restoringWhatIsAlreadyHereAddsACopyOrReplacesKeepingTheOldInTheTrash() {
        waitFor { state().libraryLoaded }
        val id = richBooklet()
        refreshedLibrary()
        waitFor { state().library.size == 1 }
        val backup = backupTo("existing.nibtexa.zip")
        val inBackup = stored(id)

        // Changed since the backup.
        runBlocking {
            val repository = services.freshRepository()
            val page = repository.loadBooklet(id)!!.pages.first()
            repository.savePage(id, 0, page.copy(ink = InkDocument(emptyList(), page.ink.nextStrokeId)))
        }
        val changed = stored(id)

        // As a copy: the existing one is untouched.
        prepare(backup)
        waitFor { state().restorePrompt?.existing == 1 }
        composeRule.onNodeWithText(string(R.string.restore_add_copies)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.restore_replace)).assertIsDisplayed()
        composeRule.runOnUiThread { viewModel().confirmRestore(replace = false) }
        waitFor { !state().exporting && state().library.size == 2 }
        val copy = state().library.single { it.id != id }
        assertEquals(string(R.string.booklet_restored_title, "Signals"), copy.title)
        assertEquals(content(changed), content(stored(id)))
        assertEquals(inBackup.pages.first().ink, stored(copy.id).pages.first().ink)
        assertTrue(stored(copy.id).pages.none { p -> changed.pages.any { it.id == p.id } })

        // Replacing: the backup takes its place, and the old one waits in the Trash.
        composeRule.runOnUiThread { viewModel().moveToTrash(copy.id); viewModel().emptyTrash() }
        waitFor { state().trash.isEmpty() && state().library.size == 1 }
        prepare(backup)
        waitFor { state().restorePrompt?.existing == 1 }
        composeRule.runOnUiThread { viewModel().confirmRestore(replace = true) }
        waitFor { !state().exporting && state().trash.size == 1 }
        assertEquals(content(inBackup), content(stored(id)))
        val old = state().trash.single()
        assertEquals("Signals", old.title)
        assertEquals(0, stored(old.id).pages.first().ink.strokes.size)
        assertEquals(id, state().library.single().id)
    }

    @Test
    fun filesThatCantBeTrustedChangeNothing() {
        waitFor { state().libraryLoaded }
        val id = richBooklet()
        refreshedLibrary()
        waitFor { state().library.size == 1 }
        val good = backupTo("good.nibtexa.zip").readBytes()
        val before = stored(id)

        fun refused(name: String, bytes: ByteArray, message: Int) {
            composeRule.runOnUiThread { viewModel().dismissMessage() }
            prepare(File(cache, name).apply { writeBytes(bytes) })
            assertEquals(string(message), state().message)
            assertNull(state().restorePrompt)
            assertEquals(listOf(id), state().library.map { it.id })
            assertEquals(content(before), content(stored(id)))
        }
        refused("text.zip", "hello".toByteArray(), R.string.backup_not_a_backup)
        refused("cut.zip", good.copyOf(good.size / 2), R.string.backup_not_a_backup)

        // Cancelling a good one changes nothing either.
        prepare(File(cache, "good2.zip").apply { writeBytes(good) })
        waitFor { state().restorePrompt != null }
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        waitFor { state().restorePrompt == null }
        assertEquals(listOf(id), state().library.map { it.id })
    }
}
