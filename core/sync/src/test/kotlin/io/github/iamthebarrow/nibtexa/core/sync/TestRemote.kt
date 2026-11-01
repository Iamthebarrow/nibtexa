package io.github.iamthebarrow.nibtexa.core.sync

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File

/**
 * A bare repository standing in for the host, plus a person who edits it from elsewhere
 * through their own clone.
 */
class TestRemote(private val root: File) {
    val dir = File(root, "remote.git").also { Git.init().setBare(true).setDirectory(it).setInitialBranch("main").call().close() }
    val url: String = dir.absoluteFile.invariantSeparatorsPath.let { if (it.startsWith("/")) "file://$it" else "file:///$it" }
    private var clones = 0

    /** Commits [write] and [delete] to [branch] from another clone, and pushes (never forced). */
    fun commitElsewhere(branch: String, write: Map<String, String> = emptyMap(), delete: Set<String> = emptySet(), from: String? = null): ObjectId {
        val work = File(root, "elsewhere-${clones++}")
        // init + fetch rather than clone: clone fails when the remote's HEAD is unborn.
        Git.init().setDirectory(work).call().use { git ->
            val repo = git.repository
            repo.config.apply { setString("remote", "origin", "url", url) }.save()
            git.fetch().setRemote("origin").setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/origin/*")).call()
            val start = repo.resolve("refs/remotes/origin/$branch") ?: from?.let { repo.resolve("refs/remotes/origin/$it") }
            if (start != null) {
                git.checkout().setName(start.name).setForced(true).call()
            } else {
                git.checkout().setOrphan(true).setName(branch).call()
            }
            for ((path, text) in write) {
                File(work, path).apply { parentFile.mkdirs() }.writeText(text)
                git.add().addFilepattern(path).call()
            }
            for (path in delete) git.rm().addFilepattern(path).call()
            val commit = git.commit().setMessage("Edited elsewhere").setAuthor("Someone", "someone@example.org")
                .setCommitter("Someone", "someone@example.org").setSign(false).call()
            git.push().setRefSpecs(RefSpec("${commit.name}:${Constants.R_HEADS}$branch")).call()
            return commit.id
        }
    }

    fun tip(branch: String): ObjectId? = Git.open(dir).use { it.repository.resolve("${Constants.R_HEADS}$branch") }

    /** Every file on [branch], as text. */
    fun files(branch: String): Map<String, String> = Git.open(dir).use { git ->
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

    fun parents(branch: String): List<ObjectId> = Git.open(dir).use { git ->
        RevWalk(git.repository).use { walk -> walk.parseCommit(tip(branch)).parents.map { it.id } }
    }
}
