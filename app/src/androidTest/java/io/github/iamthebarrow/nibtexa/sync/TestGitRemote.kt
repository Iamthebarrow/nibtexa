package io.github.iamthebarrow.nibtexa.sync

import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.NibTeXaApplication
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Docker test remote from tools/git-test-remote, seen from the emulator. Tests that need
 * it skip themselves when it isn't running. Credentials are the server's fixed test values.
 */
class TestGitRemote(private val scratch: File) {

    fun assumeRunning() {
        // The test remote speaks plain HTTP, which only debug builds accept (release: HTTPS only).
        assumeTrue("this build refuses plain HTTP remotes", allowsHttp())
        val problem = try {
            (URL("$URL_BASE/info/refs?service=git-upload-pack").openConnection() as HttpURLConnection).run {
                connectTimeout = 2_000
                readTimeout = 2_000
                try {
                    // Up, and asking for the login.
                    if (responseCode == 401) null else "HTTP $responseCode"
                } finally {
                    disconnect()
                }
            }
        } catch (e: Exception) {
            e.toString()
        }
        assumeTrue("Git test remote not reachable ($problem); see tools/git-test-remote/README.md", problem == null)
    }

    private val provider = UsernamePasswordCredentialsProvider(USERNAME, TOKEN)
    private var count = 0

    private fun mirror(): Git {
        val dir = File(scratch, "mirror-${count++}")
        val git = Git.init().setBare(true).setDirectory(dir).call()
        git.repository.config.apply { setString("remote", "origin", "url", URL_BASE) }.save()
        git.fetch().setRemote("origin").setRefSpecs(RefSpec("+refs/heads/*:refs/heads/*")).setCredentialsProvider(provider).call()
        return git
    }

    /** Every file on [branch], as text; empty if the branch doesn't exist. */
    fun files(branch: String): Map<String, String> = mirror().use { git ->
        val repo = git.repository
        val tip = repo.resolve("${Constants.R_HEADS}$branch") ?: return emptyMap()
        RevWalk(repo).use { walk ->
            TreeWalk(repo).use { tw ->
                tw.addTree(walk.parseCommit(tip).tree)
                tw.isRecursive = true
                buildMap { while (tw.next()) put(tw.pathString, String(repo.open(tw.getObjectId(0)).bytes, Charsets.UTF_8)) }
            }
        }
    }

    fun tip(branch: String): ObjectId? = mirror().use { it.repository.resolve("${Constants.R_HEADS}$branch") }

    /** Someone else adds [path] on [branch] and pushes it (not forced). */
    fun commitElsewhere(branch: String, path: String, text: String): ObjectId = mirror().use { git ->
        val repo = git.repository
        val parent = repo.resolve("${Constants.R_HEADS}$branch")
        val id = repo.newObjectInserter().use { inserter ->
            val index = org.eclipse.jgit.dircache.DirCache.newInCore()
            val builder = index.builder()
            if (parent != null) {
                repo.newObjectReader().use { reader ->
                    RevWalk(reader).use { builder.addTree(ByteArray(0), 0, reader, it.parseCommit(parent).tree) }
                }
            }
            builder.finish()
            val blob = inserter.insert(Constants.OBJ_BLOB, text.toByteArray())
            index.editor().apply {
                add(object : org.eclipse.jgit.dircache.DirCacheEditor.PathEdit(path) {
                    override fun apply(ent: org.eclipse.jgit.dircache.DirCacheEntry) {
                        ent.fileMode = org.eclipse.jgit.lib.FileMode.REGULAR_FILE
                        ent.setObjectId(blob)
                    }
                })
                finish()
            }
            val who = PersonIdent("Someone", "someone@example.org")
            inserter.insert(
                CommitBuilder().apply {
                    setTreeId(index.writeTree(inserter))
                    parent?.let { setParentId(it) }
                    author = who
                    committer = who
                    message = "Edited elsewhere\n"
                },
            ).also { inserter.flush() }
        }
        git.push().setRemote("origin").setRefSpecs(RefSpec("${id.name}:${Constants.R_HEADS}$branch")).setCredentialsProvider(provider).call()
        id
    }

    companion object {
        fun allowsHttp(): Boolean {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NibTeXaApplication
            return "http" in app.sync.policy.schemes
        }

        /** Forwarded to the host by `adb reverse tcp:18080 tcp:18080`. */
        const val URL_BASE = "http://127.0.0.1:18080/git/notes.git"
        const val USERNAME = "nibtexa"
        const val TOKEN = "nibtexa-test-token-not-a-secret"
    }
}
