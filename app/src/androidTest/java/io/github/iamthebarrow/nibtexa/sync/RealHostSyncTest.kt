package io.github.iamthebarrow.nibtexa.sync

import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.NibTeXaApplication
import io.github.iamthebarrow.nibtexa.core.sync.Credentials
import io.github.iamthebarrow.nibtexa.core.sync.GitSync
import io.github.iamthebarrow.nibtexa.core.sync.PushResult
import io.github.iamthebarrow.nibtexa.core.sync.SyncError
import io.github.iamthebarrow.nibtexa.core.sync.SyncException
import io.github.iamthebarrow.nibtexa.notebook.TestServicesRule
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Git sync against a real host over HTTPS (Phase 8, run by hand). It uses the settings and
 * token the user entered in the app, on a throwaway repository, and leaves them in place. The
 * notebook is a temporary one. The token is never printed. Skipped unless an HTTPS remote and a
 * token are saved; never part of the normal suite's expectations.
 */
@RunWith(AndroidJUnit4::class)
class RealHostSyncTest {
    @get:Rule
    val services = TestServicesRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as NibTeXaApplication
    private val scratch = File(app.cacheDir, "real-host-${System.nanoTime()}")
    private var scenario: ActivityScenario<SyncActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        scratch.deleteRecursively()
    }

    private fun state(): SyncUiState {
        lateinit var s: SyncUiState
        scenario!!.onActivity { s = ViewModelProvider(it)[SyncViewModel::class.java].state.value }
        return s
    }

    private fun onVm(block: SyncViewModel.() -> Unit) = scenario!!.onActivity { ViewModelProvider(it)[SyncViewModel::class.java].block() }

    private fun idle(timeoutMs: Long = 60_000): SyncUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (state().step != null) {
            check(System.currentTimeMillis() < deadline) { "sync step timed out" }
            Thread.sleep(200)
        }
        return state()
    }

    /** A bare mirror of the host's branches, read with the same token. */
    private fun mirror(provider: UsernamePasswordCredentialsProvider, url: String): Git {
        val dir = File(scratch, "mirror-${System.nanoTime()}")
        val git = Git.init().setBare(true).setDirectory(dir).call()
        git.repository.config.apply { setString("remote", "origin", "url", url) }.save()
        git.fetch().setRemote("origin").setRefSpecs(RefSpec("+refs/heads/*:refs/heads/*")).setCredentialsProvider(provider).call()
        return git
    }

    private fun files(git: Git, branch: String): Map<String, String> {
        val repo = git.repository
        val tip = repo.resolve("${Constants.R_HEADS}$branch") ?: return emptyMap()
        return RevWalk(repo).use { walk ->
            TreeWalk(repo).use { tw ->
                tw.addTree(walk.parseCommit(tip).tree)
                tw.isRecursive = true
                buildMap { while (tw.next()) put(tw.pathString, String(repo.open(tw.getObjectId(0)).bytes)) }
            }
        }
    }

    @Test
    fun syncWithTheRealHost() {
        val settings = app.sync.settings.load()
        val token = app.sync.tokens.load()
        assumeTrue("no HTTPS remote saved", settings.remoteUrl.startsWith("https://"))
        assumeTrue("no token saved", token != null)
        val provider = UsernamePasswordCredentialsProvider(settings.username, token!!)
        val branch = settings.branch
        val url = settings.remoteUrl
        app.sync.workDir.deleteRecursively() // a fresh working copy; the settings stay

        val mainBefore = mirror(provider, url).use { it.repository.resolve("refs/heads/main") }
        val bookletId = runBlocking { app.services.repository.loadOrCreate().id }
        val folder = "${settings.destination}/$bookletId/"

        // 1. First push through the sync screen.
        scenario = ActivityScenario.launch(SyncActivity.intent(app, bookletId))
        idle()
        onVm { prepare() }
        val prepared = idle()
        assertTrue(prepared.status, prepared.plan != null && prepared.changes.isNotEmpty())
        onVm { commitAndPush() }
        val pushed = idle()
        assertTrue(pushed.status, pushed.pending == null && pushed.status!!.startsWith("Pushed"))
        val afterFirst = mirror(provider, url).use { files(it, branch) }
        assertTrue(afterFirst.keys.toString(), "${folder}main.tex" in afterFirst && "README.md" in afterFirst)
        assertTrue("only the booklet folder was added", afterFirst.keys.filterNot { it.startsWith(folder) } == listOf("README.md"))

        // 2. Nothing changed: nothing to commit.
        onVm { prepare() }
        assertTrue(idle().changes.isEmpty())

        // 3. A local commit, then someone pushes to the branch from elsewhere first.
        runBlocking { app.services.repository.renameBooklet(bookletId, "Renamed on the AVD") }
        scenario!!.close()
        val git = GitSync(app.sync.workDir, settings, Credentials(settings.username, token), app.sync.policy)
        val booklet = runBlocking { app.services.repository.loadBooklet(bookletId)!! }
        val export = (io.github.iamthebarrow.nibtexa.core.export.LatexExporter.export(booklet, io.github.iamthebarrow.nibtexa.export.InkFigureRenderer()) as io.github.iamthebarrow.nibtexa.core.export.ExportResult.Success).files
        scenario = ActivityScenario.launch(SyncActivity.intent(app, bookletId)) // in front, so the network is allowed
        idle()
        git.commit(git.prepare(bookletId, export), io.github.iamthebarrow.nibtexa.core.sync.Resolution.KEEP, "Rename from the AVD")
        val theirs = mirror(provider, url).use { m -> pushElsewhere(m, provider, branch, "elsewhere.txt", "pushed from elsewhere\n") }

        scenario!!.close()
        scenario = ActivityScenario.launch(SyncActivity.intent(app, bookletId))
        assertTrue("the unpushed commit is shown", idle().pending != null)
        onVm { push() }
        val rejected = idle()
        assertEquals(rejected.status, true, rejected.canRebase)
        assertEquals("never forced", theirs, mirror(provider, url).use { it.repository.resolve("${Constants.R_HEADS}$branch") })
        onVm { rebaseAndPush() }
        val rebased = idle()
        assertNull(rebased.status, rebased.pending)
        val afterRebase = mirror(provider, url).use { files(it, branch) }
        assertEquals("pushed from elsewhere\n", afterRebase["elsewhere.txt"])
        assertTrue(afterRebase.getValue("${folder}main.tex").contains("Renamed on the AVD"))

        // 4. A wrong token is refused, and the saved one is untouched.
        val wrong = GitSync(File(scratch, "wrong"), settings, Credentials(settings.username, "github_pat_wrong_${System.nanoTime()}"), app.sync.policy)
        val error = runCatching { wrong.prepare(bookletId, export) }.exceptionOrNull()
        assertTrue("got $error", (error as? SyncException)?.error == SyncError.AuthFailed)
        assertEquals(token, app.sync.tokens.load())

        // main is never touched.
        assertEquals(mainBefore, mirror(provider, url).use { it.repository.resolve("refs/heads/main") })

        // 5. The token is nowhere in logs or app files (compared, never printed).
        val log = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("logcat -d --pid=${android.os.Process.myPid()}")).use { String(it.readBytes()) }
        assertFalse("token in logcat", log.contains(token))
        val roots = listOf(app.filesDir, app.noBackupFilesDir, File(app.applicationInfo.dataDir, "shared_prefs"), app.sync.workDir)
        for (file in roots.flatMap { it.walkTopDown().filter(File::isFile).toList() }) {
            assertFalse("token in ${file.name}", String(file.readBytes(), Charsets.ISO_8859_1).contains(token))
        }
    }

    /** Commits [path] on top of [branch] and pushes it, not forced. */
    private fun pushElsewhere(git: Git, provider: UsernamePasswordCredentialsProvider, branch: String, path: String, text: String): ObjectId {
        val repo = git.repository
        val parent = repo.resolve("${Constants.R_HEADS}$branch")!!
        val id = repo.newObjectInserter().use { inserter ->
            val index = DirCache.newInCore()
            val builder = index.builder()
            repo.newObjectReader().use { reader -> RevWalk(reader).use { builder.addTree(ByteArray(0), 0, reader, it.parseCommit(parent).tree) } }
            builder.finish()
            val blob = inserter.insert(Constants.OBJ_BLOB, text.toByteArray())
            index.editor().apply {
                add(object : DirCacheEditor.PathEdit(path) {
                    override fun apply(ent: DirCacheEntry) {
                        ent.fileMode = FileMode.REGULAR_FILE
                        ent.setObjectId(blob)
                    }
                })
                finish()
            }
            val who = PersonIdent("Elsewhere", "elsewhere@example.org")
            inserter.insert(
                CommitBuilder().apply {
                    setTreeId(index.writeTree(inserter))
                    setParentId(parent)
                    author = who
                    committer = who
                    message = "Edited elsewhere\n"
                },
            ).also { inserter.flush() }
        }
        git.push().setRemote("origin").setRefSpecs(RefSpec("${id.name}:${Constants.R_HEADS}$branch")).setCredentialsProvider(provider).call()
        return id
    }
}
