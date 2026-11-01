package io.github.iamthebarrow.nibtexa.sync

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.NibTeXaApplication
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.export.ExportResult
import io.github.iamthebarrow.nibtexa.core.export.LatexExporter
import io.github.iamthebarrow.nibtexa.core.sync.CommitResult
import io.github.iamthebarrow.nibtexa.core.sync.Credentials
import io.github.iamthebarrow.nibtexa.core.sync.GitSync
import io.github.iamthebarrow.nibtexa.core.sync.Resolution
import io.github.iamthebarrow.nibtexa.core.sync.SyncSettings
import io.github.iamthebarrow.nibtexa.export.InkFigureRenderer
import io.github.iamthebarrow.nibtexa.notebook.TestServicesRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Git sync on the AVD against the Docker test remote (tools/git-test-remote). Each test uses
 * its own branch. Tests that need the remote skip when it isn't running.
 */
@RunWith(AndroidJUnit4::class)
class SyncTest {
    private val services = TestServicesRule()
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as NibTeXaApplication
    private val scratch = File(app.cacheDir, "sync-test-${System.nanoTime()}")
    private val remote = TestGitRemote(scratch)
    private val branch = "test-${UUID.randomUUID().toString().take(8)}"
    private lateinit var bookletId: String
    private var scenario: ActivityScenario<SyncActivity>? = null

    private val settings get() = SyncSettings(
        remoteUrl = TestGitRemote.URL_BASE,
        username = TestGitRemote.USERNAME,
        branch = branch,
        authorName = "AVD Tester",
        authorEmail = "tester@example.org",
    )

    @Before
    fun setUp() {
        scratch.mkdirs()
        app.sync.settings.clear()
        app.sync.tokens.clear()
        app.sync.workDir.deleteRecursively()
        bookletId = runBlocking { app.services.repository.loadOrCreate().id }
    }

    @After
    fun tearDown() {
        scenario?.close()
        app.sync.settings.clear()
        app.sync.tokens.clear()
        app.sync.workDir.deleteRecursively()
        scratch.deleteRecursively()
    }

    private fun launch(): ActivityScenario<SyncActivity> =
        ActivityScenario.launch<SyncActivity>(SyncActivity.intent(app, bookletId)).also { scenario = it }

    private fun state(): SyncUiState {
        lateinit var state: SyncUiState
        scenario!!.onActivity { state = ViewModelProvider(it)[SyncViewModel::class.java].state.value }
        return state
    }

    private fun waitForIdleStep(timeoutMs: Long = 30_000): SyncUiState {
        composeRule.waitUntil(timeoutMs) { state().step == null }
        return state()
    }

    private fun string(id: Int, vararg args: Any) = app.getString(id, *args)

    private fun fillSettings(token: String) {
        composeRule.onNodeWithTag(TAG_REMOTE).performTextReplacement(settings.remoteUrl)
        composeRule.onNodeWithTag(TAG_USERNAME).performTextReplacement(settings.username)
        composeRule.onNodeWithTag(TAG_TOKEN).performTextReplacement(token)
        composeRule.onNodeWithTag(TAG_BRANCH).performTextReplacement(branch)
        composeRule.onNodeWithTag(TAG_AUTHOR_NAME).performTextReplacement(settings.authorName)
        composeRule.onNodeWithTag(TAG_AUTHOR_EMAIL).performTextReplacement(settings.authorEmail)
        composeRule.onNodeWithText(string(R.string.sync_save_settings)).performClick()
        composeRule.waitForIdle()
    }

    private fun click(id: Int) {
        composeRule.onNodeWithText(string(id)).performScrollTo().performClick()
    }

    // ---- Token storage (no remote needed) -------------------------------------------------

    @Test
    fun theTokenIsStoredEncryptedAndCanBeForgotten() {
        val token = "tok-${UUID.randomUUID()}"
        val store = TokenStore(File(scratch, "token.bin"), alias = "nibtexa-test-key")
        store.save(token)
        assertEquals(token, store.load())
        val stored = File(scratch, "token.bin").readBytes()
        assertFalse("plaintext on disk", String(stored, Charsets.ISO_8859_1).contains(token))
        assertEquals("survives a new instance", token, TokenStore(File(scratch, "token.bin"), alias = "nibtexa-test-key").load())
        store.clear()
        assertNull(store.load())
        assertFalse(store.hasToken)
    }

    @Test
    fun theSyncScreenPassesTheAccessibilityChecks() {
        composeRule.enableAccessibilityChecks()
        launch()
        waitForIdleStep()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun invalidSettingsAreShownAndNothingRuns() {
        launch()
        composeRule.onNodeWithTag(TAG_REMOTE).performTextReplacement("https://me:secret@git.example.org/notes.git")
        composeRule.onNodeWithText(string(R.string.sync_save_settings)).performClick()
        composeRule.onNodeWithText(string(R.string.sync_problem_credentials_in_url)).assertExists()
        composeRule.onNodeWithText(string(R.string.sync_problem_author)).assertExists()
        click(R.string.sync_check)
        composeRule.waitForIdle()
        assertNull(state().plan)
        assertFalse(app.sync.workDir.exists())
    }

    // ---- Against the test remote ----------------------------------------------------------
    // API 37 blocks the network for apps in the background (netpolicy APP_BACKGROUND), so the
    // remote is only reached while SyncActivity is in front.

    @Test
    fun reviewCommitAndPushToTheTestRemote() {
        launch()
        remote.assumeRunning()
        fillSettings(TestGitRemote.TOKEN)
        assertTrue(app.sync.tokens.hasToken)

        click(R.string.sync_check)
        val prepared = waitForIdleStep()
        assertNotNull(prepared.status, prepared.plan)
        assertTrue(prepared.plan!!.conflicts.isEmpty())
        val paths = prepared.changes.map { it.path }
        assertTrue(paths.toString(), "nibtexa/$bookletId/main.tex" in paths && "nibtexa/$bookletId/.nibtexa-sync.json" in paths)

        // The diff of a file opens under it.
        composeRule.onNodeWithText("nibtexa/$bookletId/main.tex").performScrollTo().performClick()
        composeRule.onNodeWithText("\\documentclass", substring = true).assertExists()

        click(R.string.sync_commit_and_push)
        val pushed = waitForIdleStep()
        assertNull(pushed.pending)
        assertTrue(pushed.status, pushed.status!!.startsWith(string(R.string.sync_pushed, "", branch).substringBefore(" ")))

        val files = remote.files(branch)
        assertTrue(files.keys.toString(), files.keys.all { it.startsWith("nibtexa/$bookletId/") })
        assertTrue(files.getValue("nibtexa/$bookletId/main.tex").contains("\\documentclass"))

        // Checking again finds nothing to do.
        click(R.string.sync_check)
        assertTrue(waitForIdleStep().changes.isEmpty())
        assertTokenNeverLeaked(TestGitRemote.TOKEN)
    }

    @Test
    fun aWrongTokenIsReportedAndNothingIsPushed() {
        launch()
        remote.assumeRunning()
        fillSettings("wrong-token-${UUID.randomUUID()}")
        click(R.string.sync_check)
        val failed = waitForIdleStep()
        assertEquals(string(R.string.sync_error_auth), failed.status)
        assertNull(failed.plan)
        assertNull(remote.tip(branch))
    }

    @Test
    fun aRejectedPushKeepsTheCommitAndRebaseRecovers() {
        launch() // in front, so the test itself may use the network
        remote.assumeRunning()
        remote.commitElsewhere(branch, "README.md", "first\n")
        app.sync.settings.save(settings)
        app.sync.tokens.save(TestGitRemote.TOKEN)

        // A commit is made, then someone pushes before it does (as if the app died before pushing).
        val git = GitSync(app.sync.workDir, settings, Credentials(TestGitRemote.USERNAME, TestGitRemote.TOKEN), app.sync.policy)
        val booklet = runBlocking { app.services.repository.loadBooklet(bookletId)!! }
        val files = (LatexExporter.export(booklet, InkFigureRenderer()) as ExportResult.Success).files
        val committed = git.commit(git.prepare(bookletId, files), Resolution.KEEP, "Export from the AVD")
        assertTrue(committed is CommitResult.Committed)
        val theirs = remote.commitElsewhere(branch, "README.md", "pushed elsewhere\n")

        // Reopen the screen, as after a restart.
        scenario!!.close()
        launch()
        val opened = waitForIdleStep()
        assertNotNull("the unpushed commit is shown after a restart", opened.pending)
        click(R.string.sync_push)
        val rejected = waitForIdleStep()
        assertEquals(string(R.string.sync_remote_advanced_rebase), rejected.status)
        assertEquals(true, rejected.canRebase)
        assertEquals("never forced", theirs, remote.tip(branch))

        click(R.string.sync_rebase_and_push)
        val pushed = waitForIdleStep()
        assertNull(pushed.pending)
        val remoteFiles = remote.files(branch)
        assertEquals("pushed elsewhere\n", remoteFiles["README.md"])
        assertTrue("nibtexa/$bookletId/main.tex" in remoteFiles)
        assertTokenNeverLeaked(TestGitRemote.TOKEN)
    }

    @Test
    fun anUnreachableHostIsReported() {
        // The emulator's loopback refuses at once (a closed port on the host may just drop).
        app.sync.settings.save(settings.copy(remoteUrl = "https://127.0.0.1:1/notes.git"))
        launch()
        waitForIdleStep()
        click(R.string.sync_check)
        val failed = waitForIdleStep()
        assertTrue(failed.status, failed.status!!.startsWith(string(R.string.sync_error_unreachable, "").trim()))
    }

    /** The token must not appear in the app's log output or anywhere in its private files. */
    private fun assertTokenNeverLeaked(token: String) {
        val log = shell("logcat -d --pid=${android.os.Process.myPid()}")
        assertTrue("logcat was read", log.isNotEmpty())
        assertFalse("token in logcat", log.contains(token))
        val roots = listOf(app.filesDir, app.noBackupFilesDir, File(app.applicationInfo.dataDir, "shared_prefs"), app.sync.workDir)
        for (file in roots.flatMap { it.walkTopDown().filter(File::isFile).toList() }) {
            assertFalse("token in ${file.name}", String(file.readBytes(), Charsets.ISO_8859_1).contains(token))
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }
}
