package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.export.Backup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** Folders in the library (Phase 27), with real taps: making one, moving into it, finding it, and deleting it. */
@RunWith(AndroidJUnit4::class)
class FoldersTest {
    private val services = TestServicesRule(startInLibrary = true)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value
    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(500)
        val shot = instrumentation.uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Booklets in the library, made directly, so that the test starts from a list. */
    private fun booklets(vararg titles: String) {
        runBlocking { titles.forEach { services.freshRepository().createBooklet(it) } }
        composeRule.runOnUiThread { viewModel().setSearch("x"); viewModel().setSearch("") }
        composeRule.runOnUiThread { viewModel().closeBooklet() }
        waitFor { state().library.size == titles.size }
    }

    private fun menuOf(title: String) {
        composeRule.onNodeWithText(title).performTouchInput { longClick() }
    }

    private fun newFolder(name: String) {
        composeRule.onNodeWithContentDescription(string(R.string.more)).performClick()
        composeRule.onNodeWithText(string(R.string.folder_new)).performClick()
        composeRule.onNodeWithTag(FOLDER_NAME_TAG).performTextInput(name)
        composeRule.onNodeWithText(string(R.string.ok)).performClick()
        waitFor { state().folders.any { it.name == name } }
    }

    private fun moveInto(title: String, folder: String) {
        val id = state().library.first { it.title == title }.id
        val before = state().library.first { it.id == id }.folderId
        menuOf(title)
        composeRule.onNodeWithText(string(R.string.folder_move)).performClick()
        composeRule.onNodeWithTag(FOLDER_MOVE_TAG).assertIsDisplayed()
        composeRule.onNode(hasText(folder) and hasAnyAncestor(hasTestTag(FOLDER_MOVE_TAG))).performClick()
        waitFor { state().library.first { it.id == id }.folderId != before }
    }

    @Test
    fun aLibraryWithNoFoldersShowsNoFolders() {
        booklets("Only")
        composeRule.onNodeWithText("Only").assertIsDisplayed()
        assertTrue(state().folders.isEmpty())
        assertEquals(null, state().openFolder)
        assertEquals(null, state().library.single().folderId)
        screenshot("folders-none.png")
    }

    @Test
    fun aFolderHoldsItsBookletsAndNewOnesMadeInsideIt() {
        booklets("Signals", "Algebra")
        newFolder("Term 1")
        val folder = state().folders.single()
        moveInto("Signals", "Term 1")
        composeRule.onNodeWithText("Algebra").assertIsDisplayed()
        composeRule.onNodeWithText("Signals").assertDoesNotExist()
        waitFor { state().folders.single().bookletCount == 1 }
        // The heading counts every booklet, those in folders too.
        composeRule.onNodeWithText(composeRule.activity.resources.getQuantityString(R.plurals.booklet_count, 2, 2), substring = true).assertIsDisplayed()
        screenshot("folders-top-level.png")

        composeRule.onNodeWithTag(folderTag(folder.id)).performClick()
        waitFor { state().openFolder == folder.id }
        composeRule.onNodeWithText("Signals").assertIsDisplayed()
        composeRule.onNodeWithText("Algebra").assertDoesNotExist()
        screenshot("folders-inside.png")

        // A booklet made here belongs here; going back from it returns here.
        composeRule.runOnUiThread { viewModel().createBooklet("Made inside") }
        waitFor { state().bookletTitle == "Made inside" }
        assertEquals(folder.id, runBlocking { services.freshRepository().library() }.first { it.title == "Made inside" }.folderId)
        composeRule.runOnUiThread { viewModel().closeBooklet() }
        waitFor { state().screen == Screen.LIBRARY && state().folders.single().bookletCount == 2 }
        assertEquals(folder.id, state().openFolder)

        // Back to the top with the arrow, and back into the library by moving out.
        composeRule.onNodeWithContentDescription(string(R.string.folder_back)).performClick()
        waitFor { state().openFolder == null }
        composeRule.onNodeWithTag(folderTag(folder.id)).performClick()
        waitFor { state().openFolder == folder.id }
        moveInto("Signals", string(R.string.folder_top_level))
        composeRule.onNodeWithText("Signals").assertDoesNotExist()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor { state().openFolder == null }
        composeRule.onNodeWithText("Signals").assertIsDisplayed()
    }

    @Test
    fun searchSpansFoldersAndNamesThem() {
        booklets("Signals notes", "Signals exam", "Algebra")
        newFolder("Term 1")
        moveInto("Signals notes", "Term 1")
        composeRule.runOnUiThread { viewModel().setSearch("Signals") }
        waitFor { state().searchResults?.size == 2 }
        composeRule.onNodeWithText("Signals notes").assertIsDisplayed()
        composeRule.onNodeWithText("Signals exam").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.folder_in, "Term 1")).assertIsDisplayed()
        screenshot("folders-search.png")

        // From inside a folder too, it covers everything.
        val id = state().folders.single().id
        composeRule.runOnUiThread { viewModel().setSearch(""); viewModel().openFolder(id); viewModel().setSearch("Alge") }
        waitFor { state().searchResults?.size == 1 }
        composeRule.onNodeWithText("Algebra").assertIsDisplayed()
    }

    @Test
    fun deletingAFolderAsksAndKeepsOrTrashesItsBooklets() {
        booklets("Keep me", "Bin me")
        newFolder("Stays")
        newFolder("Goes")
        moveInto("Keep me", "Stays")
        moveInto("Bin me", "Goes")
        waitFor { state().folders.all { it.bookletCount == 1 } }

        fun deleteMenu(name: String) {
            menuOf(name)
            composeRule.onNodeWithText(string(R.string.folder_delete)).performClick()
            composeRule.onNodeWithText(string(R.string.folder_delete_title, name)).assertIsDisplayed()
        }
        // Cancelling changes nothing.
        deleteMenu("Stays")
        screenshot("folders-delete.png")
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        assertEquals(2, state().folders.size)

        deleteMenu("Stays")
        composeRule.onNodeWithText(string(R.string.folder_delete_keep)).performClick()
        waitFor { state().folders.size == 1 }
        composeRule.onNodeWithText("Keep me").assertIsDisplayed()

        deleteMenu("Goes")
        composeRule.onNodeWithText(string(R.string.folder_delete_trash)).performClick()
        waitFor { state().folders.isEmpty() && state().trash.size == 1 }
        assertEquals("Bin me", state().trash.single().title)
        assertEquals(listOf("Keep me"), state().library.map { it.title })
    }

    @Test
    fun aFolderCanBeRenamedAndRecolouredAndMadeWhileMoving() {
        booklets("Signals")
        menuOf("Signals")
        composeRule.onNodeWithText(string(R.string.folder_move)).performClick()
        composeRule.onNodeWithText(string(R.string.folder_new)).performClick()
        composeRule.onNodeWithTag(FOLDER_NAME_TAG).performTextInput("Fresh")
        composeRule.onNodeWithText(string(R.string.ok)).performClick()
        waitFor { state().folders.singleOrNull()?.bookletCount == 1 }
        assertEquals(state().folders.single().id, state().library.single().folderId)

        menuOf("Fresh")
        composeRule.onNodeWithText(string(R.string.folder_edit)).performClick()
        composeRule.onNodeWithTag(FOLDER_NAME_TAG).performTextInput("Older ")
        composeRule.onNodeWithContentDescription(string(R.string.cover_swatch, 5)).performClick()
        composeRule.onNodeWithText(string(R.string.ok)).performClick()
        waitFor { state().folders.single().cover == 4 }
        assertEquals("Older Fresh", state().folders.single().name)
    }

    @Test
    fun aBackupKeepsFoldersAndChosenBookletsBringOnlyTheirOwn() {
        booklets("In folder", "Loose")
        newFolder("Term 1")
        newFolder("Empty one")
        moveInto("In folder", "Term 1")
        val inFolder = state().library.first { it.title == "In folder" }.id

        fun backup(name: String, ids: Set<String>?): File {
            val file = File(composeRule.activity.cacheDir, name).apply { delete() }
            composeRule.runOnUiThread { viewModel().exportBackup(Uri.fromFile(file), ids) }
            waitFor(30_000) { !state().exporting && file.length() > 0 }
            return file
        }
        val whole = backup("folders-whole.nibtexa.zip", null)
        assertEquals(setOf("Term 1", "Empty one"), Backup.check(whole).folders.map { it.name }.toSet())
        val chosen = backup("folders-chosen.nibtexa.zip", setOf(inFolder))
        assertEquals(listOf("Term 1"), Backup.check(chosen).folders.map { it.name })

        // Everything gone, then restored: the folders and who is in them are back.
        runBlocking {
            val repository = services.freshRepository()
            repository.library().forEach { repository.deleteBooklet(it.id) }
            repository.folders().forEach { repository.deleteFolder(it.id, trash = false) }
        }
        composeRule.runOnUiThread { viewModel().prepareRestore(Uri.fromFile(whole)) }
        waitFor(30_000) { state().restorePrompt != null }
        composeRule.runOnUiThread { viewModel().confirmRestore(false) }
        waitFor(30_000) { !state().exporting && state().library.size == 2 }
        assertEquals(setOf("Term 1", "Empty one"), state().folders.map { it.name }.toSet())
        val term = state().folders.first { it.name == "Term 1" }
        assertEquals(term.id, state().library.first { it.title == "In folder" }.folderId)
        assertEquals(null, state().library.first { it.title == "Loose" }.folderId)
        whole.delete()
        chosen.delete()
    }
}
