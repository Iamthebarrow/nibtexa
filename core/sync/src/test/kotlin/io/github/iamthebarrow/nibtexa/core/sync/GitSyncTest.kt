package io.github.iamthebarrow.nibtexa.core.sync

import io.github.iamthebarrow.nibtexa.core.export.ExportFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitSyncTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var remote: TestRemote
    private lateinit var work: File
    private val branch = SyncSettings.DEFAULT_BRANCH
    private val booklet = "nibtexa/b-1"

    private val export = listOf(
        ExportFile("main.tex", "main v1\n"),
        ExportFile("booklets/b-1/pages/p-1.tex", "page 1 v1\n"),
        ExportFile("booklets/b-1/pages/p-2.tex", "page 2 v1\n"),
        ExportFile("manifest.json", "{}\n"),
    )

    @Before
    fun setUp() {
        remote = TestRemote(temp.newFolder("host"))
        work = File(temp.root, "work")
    }

    private fun settings(url: String = remote.url) = SyncSettings(
        remoteUrl = url,
        authorName = "Writer",
        authorEmail = "writer@example.org",
    )

    private fun sync(url: String = remote.url, credentials: Credentials? = null) =
        GitSync(work, settings(url), credentials, RemotePolicy(setOf("file", "https")), timeoutSeconds = 5)

    private fun syncAll(files: List<ExportFile> = export, resolution: Resolution = Resolution.REGENERATE): PushResult {
        val git = sync()
        val plan = git.prepare("b-1", files)
        val committed = git.commit(plan, resolution, "Export")
        assertTrue("expected a commit, got $committed", committed is CommitResult.Committed)
        return git.push()
    }

    private fun error(block: () -> Unit): SyncError {
        try {
            block()
        } catch (e: SyncException) {
            return e.error
        }
        fail("expected a SyncException")
        throw AssertionError()
    }

    @Test
    fun firstSyncToAnEmptyRemoteCreatesTheBranchWithTheBookletFolderOnly() {
        val plan = sync().prepare("b-1", export)
        assertTrue(plan.conflicts.isEmpty())
        assertFalse(plan.remoteAdvanced)
        assertEquals(
            listOf("$booklet/.nibtexa-sync.json", "$booklet/booklets/b-1/pages/p-1.tex", "$booklet/booklets/b-1/pages/p-2.tex", "$booklet/main.tex", "$booklet/manifest.json"),
            plan.changes(Resolution.REGENERATE).map { it.path },
        )
        assertTrue(plan.changes(Resolution.REGENERATE).all { it.kind == ChangeKind.ADDED })

        val git = sync()
        val committed = git.commit(git.prepare("b-1", export), Resolution.REGENERATE, "Export") as CommitResult.Committed
        assertEquals(PushResult.Pushed(committed.commit.id), git.push())

        val files = remote.files(branch)
        assertEquals("page 1 v1\n", files["$booklet/booklets/b-1/pages/p-1.tex"])
        assertTrue(files.keys.all { it.startsWith("$booklet/") })
        val record = SyncRecord.decode(files.getValue("$booklet/.nibtexa-sync.json"))
        assertEquals(SyncRecord.hash("main v1\n".toByteArray()), record.files["main.tex"])
        assertNull(remote.tip("main"))
    }

    // ---- Library folders in the path (Phase 27) ------------------------------------------------

    private fun syncIn(folder: String?, useFolders: Boolean, files: List<ExportFile> = export, id: String = "b-1"): PushResult {
        val git = GitSync(work, settings().copy(useFolders = useFolders), null, RemotePolicy(setOf("file", "https")), timeoutSeconds = 5)
        val committed = git.commit(git.prepare(id, files, folder), Resolution.REGENERATE, "Export")
        assertTrue("expected a commit, got $committed", committed is CommitResult.Committed)
        return git.push()
    }

    private val changed = export.map { if (it.path == "main.tex") ExportFile("main.tex", "main v2\n") else it }

    @Test
    fun withTheSettingOffAFolderChangesNothing() {
        syncIn("Term 1", useFolders = false)
        assertTrue("nibtexa/b-1/main.tex" in remote.files(branch))
        assertFalse(remote.files(branch).keys.any { it.contains("Term") })
    }

    @Test
    fun withTheSettingOnANewBookletGoesUnderItsFolderAndOnlyThereChanges() {
        remote.commitElsewhere(branch, write = mapOf("README.md" to "mine\n", "nibtexa/other.txt" to "theirs\n"))
        val before = remote.tip(branch)
        assertTrue(syncIn("Term 1", useFolders = true) is PushResult.Pushed)
        val files = remote.files(branch)
        assertEquals("main v1\n", files["nibtexa/Term-1/b-1/main.tex"])
        assertEquals("mine\n", files["README.md"])
        assertEquals("theirs\n", files["nibtexa/other.txt"])
        assertTrue(files.keys.filter { it != "README.md" && it != "nibtexa/other.txt" }.all { it.startsWith("nibtexa/Term-1/b-1/") })
        // Never forced: the new commit sits on the old one.
        assertEquals(listOf(before), remote.parents(branch))
    }

    @Test
    fun aBookletAlreadySyncedStaysWhereItIs() {
        syncIn(null, useFolders = false)
        val first = remote.files(branch).keys
        // In a folder now, with the setting on: it doesn't move, and no copy appears.
        syncIn("Term 1", useFolders = true, files = changed)
        assertEquals(first, remote.files(branch).keys)
        assertEquals("main v2\n", remote.files(branch)["nibtexa/b-1/main.tex"])
    }

    @Test
    fun aBookletUnderAFolderStaysThereWhenTheLibraryOrTheSettingChanges() {
        syncIn("Term 1", useFolders = true)
        val first = remote.files(branch).keys
        syncIn("Term 2", useFolders = true, files = changed)
        assertEquals(first, remote.files(branch).keys)
        assertEquals("main v2\n", remote.files(branch)["nibtexa/Term-1/b-1/main.tex"])
        val third = changed.map { if (it.path == "main.tex") ExportFile("main.tex", "main v3\n") else it }
        syncIn(null, useFolders = false, files = third)
        assertEquals(first, remote.files(branch).keys)
        assertEquals("main v3\n", remote.files(branch)["nibtexa/Term-1/b-1/main.tex"])
    }

    @Test
    fun anOutsideEditIsStillAConflictUnderAFolder() {
        syncIn("Term 1", useFolders = true)
        remote.commitElsewhere(branch, write = mapOf("nibtexa/Term-1/b-1/main.tex" to "edited\n"))
        val git = GitSync(work, settings().copy(useFolders = true), null, RemotePolicy(setOf("file", "https")), timeoutSeconds = 5)
        val plan = git.prepare("b-1", changed, "Term 1")
        assertEquals("nibtexa/Term-1/b-1", plan.bookletPath)
        assertTrue(plan.conflicts.isNotEmpty())
        git.commit(plan, Resolution.KEEP, "Export")
        git.push()
        assertEquals("edited\n", remote.files(branch)["nibtexa/Term-1/b-1/main.tex"])
    }

    @Test
    fun aFolderNameTakenByABookletOrAFileIsNotUsed() {
        syncIn(null, useFolders = false, id = "b-1")
        // A folder called b-1 must not write inside booklet b-1's own folder.
        syncIn("b-1", useFolders = true, id = "b-2")
        assertTrue("nibtexa/b-2/main.tex" in remote.files(branch))
        assertFalse(remote.files(branch).keys.any { it.startsWith("nibtexa/b-1/b-2/") })

        remote.commitElsewhere(branch, write = mapOf("nibtexa/notes" to "a file\n"))
        syncIn("notes", useFolders = true, id = "b-3")
        assertTrue("nibtexa/b-3/main.tex" in remote.files(branch))
        assertEquals("a file\n", remote.files(branch)["nibtexa/notes"])
    }

    @Test
    fun aFolderNameWithNothingUsableGoesStraightUnderTheDestination() {
        syncIn("...", useFolders = true)
        assertTrue("nibtexa/b-1/main.tex" in remote.files(branch))
    }

    @Test
    fun anUnchangedExportCommitsNothing() {
        syncAll()
        val git = sync()
        val plan = git.prepare("b-1", export)
        assertTrue(plan.changes(Resolution.REGENERATE).isEmpty())
        assertEquals(CommitResult.NothingToCommit, git.commit(plan, Resolution.REGENERATE, "Export"))
        assertNull(git.pending())
    }

    @Test
    fun aNewBranchStartsFromTheDefaultBranchAndLeavesItAlone() {
        val main = remote.commitElsewhere("main", write = mapOf("README.md" to "hello\n"))
        syncAll()
        assertEquals(listOf(main), remote.parents(branch))
        assertEquals("hello\n", remote.files(branch)["README.md"])
        assertEquals(main, remote.tip("main"))
    }

    @Test
    fun onlyTheBookletFolderChanges() {
        remote.commitElsewhere(
            branch,
            write = mapOf(
                "README.md" to "mine\n",
                "nibtexa/other-booklet/main.tex" to "other\n",
                "$booklet/notes.txt" to "my own notes\n",
            ),
        )
        syncAll()
        syncAll(export.map { if (it.path == "main.tex") ExportFile("main.tex", "main v2\n") else it })

        val files = remote.files(branch)
        assertEquals("mine\n", files["README.md"])
        assertEquals("other\n", files["nibtexa/other-booklet/main.tex"])
        assertEquals("my own notes\n", files["$booklet/notes.txt"])
        assertEquals("main v2\n", files["$booklet/main.tex"])
    }

    @Test
    fun aDeletedPageRemovesOnlyTheAppsFile() {
        remote.commitElsewhere(branch, write = mapOf("$booklet/notes.txt" to "mine\n"))
        syncAll()
        val plan = sync().prepare("b-1", export.filterNot { it.path.endsWith("p-2.tex") })
        val changes = plan.changes(Resolution.REGENERATE).associate { it.path to it.kind }
        assertEquals(ChangeKind.DELETED, changes["$booklet/booklets/b-1/pages/p-2.tex"])
        assertFalse("$booklet/notes.txt" in changes)
    }

    @Test
    fun aFileEditedElsewhereIsAConflictAndRegenerateReplacesIt() {
        syncAll()
        remote.commitElsewhere(branch, write = mapOf("$booklet/main.tex" to "hand-edited\n"))
        val plan = sync().prepare("b-1", export)
        assertEquals(listOf(Conflict("$booklet/main.tex", ConflictKind.EDITED)), plan.conflicts)
        assertTrue(plan.remoteAdvanced)

        assertTrue(syncAll(resolution = Resolution.REGENERATE) is PushResult.Pushed)
        assertEquals("main v1\n", remote.files(branch)["$booklet/main.tex"])
    }

    @Test
    fun keepLeavesTheEditAndStopsGeneratingTheFileUntilReleased() {
        syncAll()
        remote.commitElsewhere(branch, write = mapOf("$booklet/main.tex" to "hand-edited\n"))
        syncAll(resolution = Resolution.KEEP)
        assertEquals("hand-edited\n", remote.files(branch)["$booklet/main.tex"])

        // Later exports neither ask again nor overwrite it.
        val changed = export.map { if (it.path == "main.tex") ExportFile("main.tex", "main v2\n") else it }
        val plan = sync().prepare("b-1", changed)
        assertTrue(plan.conflicts.isEmpty())
        assertEquals(listOf("$booklet/main.tex"), plan.kept)
        assertTrue(plan.changes(Resolution.REGENERATE).isEmpty())

        // Releasing hands it back.
        val git = sync()
        git.commit(git.prepare("b-1", changed), Resolution.REGENERATE, "Release", releaseKept = true)
        git.push()
        assertEquals("main v2\n", remote.files(branch)["$booklet/main.tex"])
        assertTrue(sync().prepare("b-1", changed).kept.isEmpty())
    }

    @Test
    fun copyKeepsTheEditAndWritesTheAppsVersionNextToIt() {
        syncAll()
        remote.commitElsewhere(branch, write = mapOf("$booklet/main.tex" to "hand-edited\n"))
        syncAll(resolution = Resolution.COPY)
        val files = remote.files(branch)
        assertEquals("hand-edited\n", files["$booklet/main.tex"])
        assertEquals("main v1\n", files["$booklet/main.nibtexa.tex"])
    }

    @Test
    fun aFileDeletedElsewhereIsAConflictAndKeepLeavesItDeleted() {
        syncAll()
        remote.commitElsewhere(branch, delete = setOf("$booklet/manifest.json"))
        val plan = sync().prepare("b-1", export)
        assertEquals(listOf(Conflict("$booklet/manifest.json", ConflictKind.DELETED)), plan.conflicts)
        syncAll(resolution = Resolution.KEEP)
        assertFalse("$booklet/manifest.json" in remote.files(branch))
        assertTrue(sync().prepare("b-1", export).changes(Resolution.REGENERATE).isEmpty())
    }

    @Test
    fun anExistingFileTheAppDidNotWriteIsAConflict() {
        remote.commitElsewhere(branch, write = mapOf("$booklet/main.tex" to "someone else's\n"))
        val plan = sync().prepare("b-1", export)
        assertEquals(listOf(Conflict("$booklet/main.tex", ConflictKind.NOT_OURS)), plan.conflicts)
    }

    @Test
    fun aRemoteThatMovedOnOutsideTheDestinationRejectsThePushThenRebaseKeepsBoth() {
        syncAll()
        val git = sync()
        git.commit(git.prepare("b-1", export.map { if (it.path == "main.tex") ExportFile("main.tex", "main v2\n") else it }), Resolution.REGENERATE, "Export 2")
        val theirs = remote.commitElsewhere(branch, write = mapOf("README.md" to "pushed elsewhere\n"))

        assertEquals(PushResult.RemoteAdvanced(canRebase = true), git.push())
        assertEquals("never forced", theirs, remote.tip(branch))
        assertNotNull("the commit is kept locally", git.pending())

        val pushed = git.rebaseAndPush()
        assertTrue(pushed is PushResult.Pushed)
        assertEquals(listOf(theirs), remote.parents(branch))
        val files = remote.files(branch)
        assertEquals("pushed elsewhere\n", files["README.md"])
        assertEquals("main v2\n", files["$booklet/main.tex"])
        assertNull(git.pending())
    }

    @Test
    fun aRemoteThatMovedOnInsideTheDestinationCannotBeRebased() {
        syncAll()
        val git = sync()
        git.commit(git.prepare("b-1", export.map { if (it.path == "main.tex") ExportFile("main.tex", "main v2\n") else it }), Resolution.REGENERATE, "Export 2")
        val theirs = remote.commitElsewhere(branch, write = mapOf("nibtexa/other/x.tex" to "theirs\n"))

        assertEquals(PushResult.RemoteAdvanced(canRebase = false), git.push())
        assertEquals(PushResult.RemoteAdvanced(canRebase = false), git.rebaseAndPush())
        assertEquals(theirs, remote.tip(branch))
        assertNotNull(git.pending())

        // Discarding drops only the local commit; the next sync starts from the remote.
        git.discardPending()
        assertNull(git.pending())
        val v2 = export.map { if (it.path == "main.tex") ExportFile("main.tex", "main v2\n") else it }
        assertEquals(listOf("$booklet/.nibtexa-sync.json", "$booklet/main.tex"), sync().prepare("b-1", v2).changes(Resolution.REGENERATE).map { it.path })
    }

    @Test
    fun anUnpushedCommitBlocksTheNextSyncAndSurvivesARestart() {
        val git = sync()
        val committed = git.commit(git.prepare("b-1", export), Resolution.REGENERATE, "Export") as CommitResult.Committed
        assertEquals(SyncError.PendingCommit, error { sync().prepare("b-1", export) })

        // A new instance over the same working copy, as after process death.
        val restarted = sync()
        assertEquals(committed.commit, restarted.pending())
        assertEquals(PushResult.Pushed(committed.commit.id), restarted.push())
    }

    @Test
    fun dyingAfterThePushReachedTheHostRecoversOnTheNextPush() {
        val git = sync()
        val committed = git.commit(git.prepare("b-1", export), Resolution.REGENERATE, "Export") as CommitResult.Committed
        // The push lands, but the app dies before it records that (no tracking ref update).
        org.eclipse.jgit.api.Git.open(work).use {
            it.push().setRemote("origin").setRefSpecs(org.eclipse.jgit.transport.RefSpec("refs/heads/$branch:refs/heads/$branch")).call()
        }
        assertNull("tracking ref not updated", org.eclipse.jgit.api.Git.open(work).use { it.repository.resolve("refs/remotes/origin/$branch") })

        val restarted = sync()
        assertEquals(committed.commit, restarted.pending())
        assertEquals(PushResult.Pushed(committed.commit.id), restarted.push())
        assertNull(restarted.pending())
        assertEquals(listOf<org.eclipse.jgit.lib.ObjectId>(), remote.parents(branch))
        assertTrue(sync().prepare("b-1", export).changes(Resolution.REGENERATE).isEmpty())
    }

    @Test
    fun aDeletedRemoteBranchIsCreatedAgain() {
        syncAll()
        org.eclipse.jgit.api.Git.open(remote.dir).use { it.branchDelete().setBranchNames(branch).setForce(true).call() }
        val plan = sync().prepare("b-1", export)
        assertTrue(plan.conflicts.isEmpty())
        assertTrue(syncAll() is PushResult.Pushed)
        assertNotNull(remote.tip(branch))
    }

    @Test
    fun aChangedRemoteUrlStartsAFreshWorkingCopy() {
        syncAll()
        val other = TestRemote(temp.newFolder("other-host"))
        val git = sync(other.url)
        git.commit(git.prepare("b-1", export), Resolution.REGENERATE, "Export")
        assertTrue(git.push() is PushResult.Pushed)
        assertEquals("main v1\n", other.files(branch)["$booklet/main.tex"])
    }

    @Test
    fun aCorruptRecordStopsTheSync() {
        remote.commitElsewhere(branch, write = mapOf("$booklet/.nibtexa-sync.json" to "not json"))
        assertEquals(SyncError.CorruptRecord, error { sync().prepare("b-1", export) })
    }

    @Test
    fun anUnreachableHostIsReportedWithoutTheToken() {
        val token = "tok-3f9c2a-secret" // gitleaks:allow (a made-up value; the test checks it never leaks)
        val git = sync("https://127.0.0.1:9/notes.git", Credentials("me", token))
        val error = error { git.prepare("b-1", export) }
        assertTrue("got $error", error is SyncError.Unreachable)
        assertFalse(error.toString().contains(token))
        for (file in work.walkTopDown().filter { it.isFile }) {
            assertFalse("token in ${file.name}", String(file.readBytes(), Charsets.ISO_8859_1).contains(token))
        }
    }

    @Test
    fun theTokenNeverReachesTheWorkingCopy() {
        val token = "tok-77aa01-secret"
        val git = sync(credentials = Credentials("me", token))
        git.commit(git.prepare("b-1", export), Resolution.REGENERATE, "Export")
        git.push()
        for (file in work.walkTopDown().filter { it.isFile }) {
            assertFalse("token in ${file.name}", String(file.readBytes(), Charsets.ISO_8859_1).contains(token))
        }
    }

    @Test
    fun certificateChecksCannotBeTurnedOffInTheWorkingCopy() {
        syncAll()
        org.eclipse.jgit.api.Git.open(work).use { git ->
            git.repository.config.apply {
                setBoolean("http", null, "sslVerify", false)
                setBoolean("http", "https://git.example.org/", "sslVerify", false)
            }.save()
        }
        sync().pending()
        org.eclipse.jgit.api.Git.open(work).use { git ->
            val config = org.eclipse.jgit.storage.file.FileBasedConfig(File(work, "config"), org.eclipse.jgit.util.FS.DETECTED).apply { load() }
            assertTrue(config.getBoolean("http", "sslVerify", false))
            assertTrue(config.getSubsections("http").isEmpty())
            assertEquals(remote.url, git.repository.config.getString("remote", "origin", "url"))
        }
    }

    @Test
    fun invalidSettingsAreRefusedBeforeAnyNetworkAccess() {
        val git = GitSync(work, settings("http://example.org/notes.git"), null, RemotePolicy.HTTPS_ONLY)
        val error = error { git.prepare("b-1", export) }
        assertEquals(SyncError.InvalidSettings(listOf(SettingsProblem.UnsupportedScheme("http"))), error)
        assertFalse(work.exists())
    }

    @Test
    fun aCanceledSyncStopsBeforeTheNetwork() {
        val git = sync()
        git.cancel()
        assertEquals(SyncError.Canceled, error { git.prepare("b-1", export) })
        assertNull(remote.tip(branch))
    }

    @Test
    fun commitsUseTheConfiguredAuthorAndTheExactExportBytes() {
        val crlf = listOf(ExportFile("main.tex", "line\r\nend\n"), ExportFile("assets/x.png", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
        remote.commitElsewhere(branch, write = mapOf(".gitattributes" to "* text=auto eol=lf\n*.png -diff\n", ".gitignore" to "*.png\n"))
        syncAll(crlf)
        org.eclipse.jgit.api.Git.open(remote.dir).use { git ->
            val repo = git.repository
            val commit = org.eclipse.jgit.revwalk.RevWalk(repo).use { it.parseCommit(remote.tip(branch)) }
            assertEquals("Writer", commit.authorIdent.name)
            assertEquals("writer@example.org", commit.authorIdent.emailAddress)
            assertEquals("Export\n", commit.fullMessage)
        }
        val files = remote.files(branch)
        assertEquals("line\r\nend\n", files["$booklet/main.tex"])
        assertTrue("ignored files are still exported", "$booklet/assets/x.png" in files)
    }
}
