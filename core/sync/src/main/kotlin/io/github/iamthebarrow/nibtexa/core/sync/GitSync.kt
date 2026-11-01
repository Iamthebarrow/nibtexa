package io.github.iamthebarrow.nibtexa.core.sync

import io.github.iamthebarrow.nibtexa.core.export.ExportFile
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** HTTPS credentials. The token is sent to the host and nowhere else. */
class Credentials(val username: String, val token: String) {
    override fun toString() = "Credentials($username, ***)"
}

/**
 * Exports a booklet to a Git remote, following the safety rules in ADR-0006:
 * - only `<destination>/<booklet-id>/` is ever changed;
 * - pushes are never forced;
 * - files edited outside the app are reported as conflicts, never overwritten silently;
 * - if the remote moved on, the commit stays local until the user rebases or discards it.
 *
 * The working copy in [workDir] is a bare repository private to the app. Commits are built
 * from the export's bytes directly, so the repository's `.gitattributes`, `.gitignore` and
 * the device's file system never change what is committed.
 *
 * Every method blocks: call it off the main thread. [cancel] stops a running network step.
 */
class GitSync(
    private val workDir: File,
    private val settings: SyncSettings,
    private val credentials: Credentials?,
    private val policy: RemotePolicy = RemotePolicy.HTTPS_ONLY,
    private val timeoutSeconds: Int = 30,
) {
    @Volatile
    private var canceled = false
    private val redactor = Redactor(credentials?.token)
    private val branch = settings.branch
    private val trackingRef = "refs/remotes/origin/$branch"
    private val localRef = "${Constants.R_HEADS}$branch"
    private val destination: String by lazy { SettingsValidator.destinationSegments(settings.destination)!!.joinToString("/") }

    init {
        isolateFromHostConfig
    }

    fun cancel() {
        canceled = true
    }

    /** A commit made earlier that has not reached the remote, if any. No network. */
    fun pending(): PendingCommit? = guarded { open().use(::pendingIn) }

    /**
     * Fetches the branch and works out what exporting [files] (relative to the booklet folder)
     * would change. Nothing is committed. [folder] is the booklet's library folder, if it has one ([SyncSettings.useFolders]).
     *
     * @throws SyncException with [SyncError.PendingCommit] if an earlier commit is unpushed.
     */
    fun prepare(bookletId: String, files: List<ExportFile>, folder: String? = null): SyncPlan = guarded {
        check(bookletId)
        open().use { git ->
            if (pendingIn(git) != null) throw SyncException(SyncError.PendingCommit)
            val before = git.repository.resolve(trackingRef)
            val base = fetchBase(git)
            val bookletPath = bookletPath(git.repository, base, bookletId, folder)
            val current = base?.let { readFolder(git.repository, it, bookletPath) }.orEmpty()
            val record = current["$bookletPath/${SyncRecord.FILE_NAME}"]?.let { SyncRecord.decode(it.toString(Charsets.UTF_8)) } ?: SyncRecord()
            Planner.plan(
                bookletPath = bookletPath,
                generated = files.associate { it.path to it.bytes },
                current = current,
                record = record,
                baseCommit = base?.name,
                remoteAdvanced = before != null && base != null && before != base,
            )
        }
    }

    /** Commits [plan] locally. Push it with [push]. */
    fun commit(plan: SyncPlan, resolution: Resolution, message: String, releaseKept: Boolean = false): CommitResult = guarded {
        require(message.isNotBlank()) { "A commit message is needed" }
        open().use { git ->
            val repo = git.repository
            if (pendingIn(git) != null) throw SyncException(SyncError.PendingCommit)
            val changes = plan.changes(resolution, releaseKept)
            if (changes.isEmpty()) return@use CommitResult.NothingToCommit
            val base = plan.baseCommit?.let(ObjectId::fromString)
            val tree = repo.newObjectInserter().use { inserter ->
                val index = indexOf(repo, base)
                index.editor().apply {
                    for (change in changes) {
                        val bytes = change.after
                        if (bytes == null) {
                            add(DirCacheEditor.DeletePath(change.path))
                        } else {
                            val blob = inserter.insert(Constants.OBJ_BLOB, bytes)
                            add(fileEdit(change.path, blob, FileMode.REGULAR_FILE))
                        }
                    }
                    finish()
                }
                index.writeTree(inserter).also { inserter.flush() }
            }
            assertOnlyChanged(repo, base, tree, plan.bookletPath)
            val ident = PersonIdent(settings.authorName.trim(), settings.authorEmail.trim())
            val id = writeCommit(repo, tree, base, ident, ident, message.trim())
            setLocalBranch(repo, id)
            CommitResult.Committed(PendingCommit(id.name, message.trim()))
        }
    }

    /** Pushes the pending commit, never forced. */
    fun push(): PushResult = guarded {
        open().use { git ->
            val pending = pendingIn(git) ?: throw SyncException(SyncError.Failed("Nothing to push"))
            pushPending(git, pending)
        }
    }

    /**
     * After [PushResult.RemoteAdvanced] with `canRebase`: replays the pending commit on top
     * of the remote branch and pushes it. Refuses if the new remote commits touch the
     * destination folder.
     */
    fun rebaseAndPush(): PushResult = guarded {
        open().use { git ->
            val repo = git.repository
            val pending = pendingIn(git) ?: throw SyncException(SyncError.Failed("Nothing to rebase"))
            val tip = fetchBranch(git) ?: throw SyncException(SyncError.Failed("The remote branch is gone"))
            RevWalk(repo).use { walk ->
                val commit = walk.parseCommit(ObjectId.fromString(pending.id))
                val parent = commit.parents.singleOrNull()?.let { walk.parseCommit(it) }
                if (!untouched(repo, parent, walk.parseCommit(tip))) return@use PushResult.RemoteAdvanced(canRebase = false)
                val tree = repo.newObjectInserter().use { inserter ->
                    val index = indexOf(repo, tip)
                    index.editor().apply {
                        TreeWalk(repo).use { tw ->
                            tw.isRecursive = true
                            tw.filter = TreeFilter.ANY_DIFF
                            if (parent == null) tw.addTree(org.eclipse.jgit.treewalk.EmptyTreeIterator()) else tw.addTree(parent.tree)
                            tw.addTree(commit.tree)
                            while (tw.next()) {
                                if (tw.getFileMode(1) == FileMode.MISSING) {
                                    add(DirCacheEditor.DeletePath(tw.pathString))
                                } else {
                                    add(fileEdit(tw.pathString, tw.getObjectId(1), tw.getFileMode(1)))
                                }
                            }
                        }
                        finish()
                    }
                    index.writeTree(inserter).also { inserter.flush() }
                }
                val committer = PersonIdent(settings.authorName.trim(), settings.authorEmail.trim())
                val id = writeCommit(repo, tree, tip, commit.authorIdent, committer, commit.fullMessage)
                setLocalBranch(repo, id)
                pushPending(git, PendingCommit(id.name, commit.fullMessage))
            }
        }
    }

    /** Drops the pending commit. The notebook is untouched; the next sync recreates it. */
    fun discardPending() = guarded {
        open().use { git ->
            val repo = git.repository
            val remote = repo.resolve(trackingRef)
            val update = repo.updateRef(localRef)
            update.isForceUpdate = true
            val result = if (remote == null) update.delete() else update.apply { setNewObjectId(remote) }.update()
            if (result !in OK_REF_UPDATES) throw SyncException(SyncError.Failed("Cannot discard: $result"))
        }
    }

    // ---- Repository ---------------------------------------------------------------------

    private fun open(): Git {
        val problems = SettingsValidator.problems(settings, policy)
        if (problems.isNotEmpty()) throw SyncException(SyncError.InvalidSettings(problems))
        val url = settings.remoteUrl.trim()
        if (File(workDir, "config").isFile) {
            val git = Git.open(workDir)
            if (git.repository.config.getString("remote", "origin", "url") == url) return git.also(::enforceTls)
            git.close()
        }
        // A new remote starts from a fresh working copy; nothing there is the only copy of
        // anything, because every commit can be made again from the notebook.
        workDir.deleteRecursively()
        if (!workDir.mkdirs()) throw IOException("Cannot create the working copy")
        val git = Git.init().setBare(true).setDirectory(workDir).setInitialBranch(branch).call()
        git.repository.config.apply {
            setString("remote", "origin", "url", url)
            save()
        }
        return git.also(::enforceTls)
    }

    /**
     * JGit only skips certificate checks when `http.sslVerify` is false. User and system
     * config are never read (see [isolateFromHostConfig]); this pins the working copy's own.
     */
    private fun enforceTls(git: Git) {
        val config = git.repository.config
        val before = config.toText()
        config.getSubsections("http").forEach { config.unsetSection("http", it) } // [http "<url>"] too
        config.unsetSection("http", null)
        config.setBoolean("http", null, "sslVerify", true)
        if (config.toText() != before) config.save()
    }

    private fun pendingIn(git: Git): PendingCommit? {
        val repo = git.repository
        val local = repo.resolve(localRef) ?: return null
        val remote = repo.resolve(trackingRef)
        RevWalk(repo).use { walk ->
            val commit = walk.parseCommit(local)
            if (remote != null && walk.isMergedInto(commit, walk.parseCommit(remote))) return null
            return PendingCommit(commit.name, commit.fullMessage.trimEnd())
        }
    }

    /**
     * Fetches the branch, or, if the remote doesn't have it yet, its default branch to start
     * from. Returns the commit to build on, or null for a new, empty history.
     */
    private fun fetchBase(git: Git): ObjectId? {
        fetchBranch(git)?.let { return it }
        val head = lsRemote(git)[Constants.HEAD]?.takeIf { it.isSymbolic }?.target?.name ?: return null
        val name = head.removePrefix(Constants.R_HEADS)
        val tracking = "refs/remotes/origin/$name"
        fetch(git, RefSpec("+$head:$tracking"))
        return git.repository.resolve(tracking)
    }

    /** The remote branch tip, after fetching it; null if the remote has no such branch. */
    private fun fetchBranch(git: Git): ObjectId? {
        if (lsRemote(git)[localRef] == null) {
            // The branch was deleted on the remote: forget the stale tracking ref, and the local
            // branch too if it only held what was pushed. An unpushed commit is never dropped.
            val repo = git.repository
            val tracked = repo.resolve(trackingRef)
            if (tracked != null) {
                if (repo.resolve(localRef) == tracked) repo.updateRef(localRef).apply { isForceUpdate = true }.delete()
                repo.updateRef(trackingRef).apply { isForceUpdate = true }.delete()
            }
            return null
        }
        fetch(git, RefSpec("+$localRef:$trackingRef"))
        return git.repository.resolve(trackingRef)
    }

    private fun lsRemote(git: Git) = network {
        git.lsRemote().setRemote("origin").setCredentialsProvider(provider()).setTimeout(timeoutSeconds).callAsMap()
    }

    private fun fetch(git: Git, spec: RefSpec) = network {
        git.fetch().setRemote("origin").setRefSpecs(spec).setCredentialsProvider(provider())
            .setTimeout(timeoutSeconds).setProgressMonitor(monitor).call()
    }

    private fun pushPending(git: Git, pending: PendingCommit): PushResult {
        val results = network {
            git.push().setRemote("origin")
                .setRefSpecs(RefSpec("$localRef:$localRef")) // no '+': never forced
                .setCredentialsProvider(provider()).setTimeout(timeoutSeconds).setProgressMonitor(monitor).call()
        }
        val update = results.firstNotNullOfOrNull { it.getRemoteUpdate(localRef) }
            ?: throw SyncException(SyncError.Failed("The push was not attempted"))
        return when (update.status) {
            RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> {
                // JGit updates the tracking ref after a successful push; make sure of it.
                git.repository.updateRef(trackingRef).apply {
                    setNewObjectId(ObjectId.fromString(pending.id))
                    isForceUpdate = true
                }.update()
                PushResult.Pushed(pending.id)
            }
            RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> {
                val repo = git.repository
                val tip = fetchBranch(git)
                val canRebase = tip != null && RevWalk(repo).use { walk ->
                    val parent = walk.parseCommit(ObjectId.fromString(pending.id)).parents.singleOrNull()?.let { walk.parseCommit(it) }
                    untouched(repo, parent, walk.parseCommit(tip))
                }
                PushResult.RemoteAdvanced(canRebase)
            }
            else -> throw SyncException(SyncError.Rejected(redactor.redact(update.message ?: update.status.name)))
        }
    }

    // ---- Trees and commits --------------------------------------------------------------

    /**
     * Where the booklet's folder is (Phase 27). One already on the remote stays there, so nothing ever moves: not when the
     * setting changes, and not when it moves to another library folder. A new one goes under its library folder if the
     * setting is on, unless that name is taken by another booklet's folder or a file.
     */
    private fun bookletPath(repo: Repository, base: ObjectId?, bookletId: String, folder: String?): String {
        val flat = "$destination/$bookletId"
        val segment = folder?.takeIf { settings.useFolders }?.let(SettingsValidator::folderSegment)
        if (base == null) return segment?.let { "$destination/$it/$bookletId" } ?: flat
        return RevWalk(repo).use { walk ->
            val tree = walk.parseCommit(base).tree
            fun at(path: String) = TreeWalk.forPath(repo, path, tree)?.use { it.getFileMode(0) }
            fun isBooklet(path: String) = at("$path/${SyncRecord.FILE_NAME}") != null
            when {
                isBooklet(flat) -> flat
                else -> subfolders(repo, tree, destination).firstOrNull { isBooklet("$destination/$it/$bookletId") }?.let { "$destination/$it/$bookletId" }
                    ?: segment?.takeUnless { isBooklet("$destination/$it") || at("$destination/$it").let { mode -> mode != null && mode != FileMode.TREE } }
                        ?.let { "$destination/$it/$bookletId" }
                    ?: flat
            }
        }
    }

    /** The names of the folders directly inside [path] in [tree]. */
    private fun subfolders(repo: Repository, tree: org.eclipse.jgit.revwalk.RevTree, path: String): List<String> {
        val parent = TreeWalk.forPath(repo, path, tree)?.use { if (it.getFileMode(0) == FileMode.TREE) it.getObjectId(0) else null } ?: return emptyList()
        return TreeWalk(repo).use { tw ->
            tw.addTree(parent)
            buildList { while (tw.next()) if (tw.getFileMode(0) == FileMode.TREE) add(tw.nameString) }
        }
    }

    /** The regular files under [folder] in [commit], keyed by repository path. */
    private fun readFolder(repo: Repository, commit: ObjectId, folder: String): Map<String, ByteArray> {
        val result = sortedMapOf<String, ByteArray>()
        RevWalk(repo).use { walk ->
            TreeWalk(repo).use { tw ->
                tw.addTree(walk.parseCommit(commit).tree)
                tw.isRecursive = true
                tw.filter = PathFilter.create(folder)
                while (tw.next()) {
                    val mode = tw.getFileMode(0)
                    if (mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) {
                        result[tw.pathString] = repo.open(tw.getObjectId(0)).bytes
                    }
                }
            }
        }
        return result
    }

    /** True if nothing under the destination folder changed from [from] to [to]. */
    private fun untouched(repo: Repository, from: RevCommit?, to: RevCommit): Boolean = TreeWalk(repo).use { tw ->
        tw.isRecursive = true
        tw.filter = org.eclipse.jgit.treewalk.filter.AndTreeFilter.create(PathFilter.create(destination), TreeFilter.ANY_DIFF)
        if (from == null) tw.addTree(org.eclipse.jgit.treewalk.EmptyTreeIterator()) else tw.addTree(from.tree)
        tw.addTree(to.tree)
        !tw.next()
    }

    /** The safety net for the planner: a commit may only change the booklet folder. */
    private fun assertOnlyChanged(repo: Repository, base: ObjectId?, tree: ObjectId, folder: String) {
        RevWalk(repo).use { walk ->
            TreeWalk(repo).use { tw ->
                tw.isRecursive = true
                tw.filter = TreeFilter.ANY_DIFF
                if (base == null) tw.addTree(org.eclipse.jgit.treewalk.EmptyTreeIterator()) else tw.addTree(walk.parseCommit(base).tree)
                tw.addTree(tree)
                while (tw.next()) {
                    check(tw.pathString.startsWith("$folder/")) { "Refusing to change ${tw.pathString} outside $folder" }
                }
            }
        }
    }

    private fun indexOf(repo: Repository, commit: ObjectId?): DirCache {
        val index = DirCache.newInCore()
        if (commit != null) {
            val builder = index.builder()
            repo.newObjectReader().use { reader ->
                RevWalk(reader).use { walk -> builder.addTree(ByteArray(0), DirCacheEntry.STAGE_0, reader, walk.parseCommit(commit).tree) }
            }
            builder.finish()
        }
        return index
    }

    private fun fileEdit(path: String, blob: ObjectId, mode: FileMode) = object : DirCacheEditor.PathEdit(path) {
        override fun apply(entry: DirCacheEntry) {
            entry.fileMode = mode
            entry.setObjectId(blob)
        }
    }

    private fun writeCommit(repo: Repository, tree: ObjectId, parent: ObjectId?, author: PersonIdent, committer: PersonIdent, message: String): ObjectId =
        repo.newObjectInserter().use { inserter ->
            val builder = CommitBuilder().apply {
                setTreeId(tree)
                parent?.let { setParentId(it) }
                this.author = author
                this.committer = committer
                this.message = if (message.endsWith("\n")) message else "$message\n"
            }
            inserter.insert(builder).also { inserter.flush() }
        }

    private fun setLocalBranch(repo: Repository, id: ObjectId) {
        val update = repo.updateRef(localRef)
        update.setNewObjectId(id)
        update.isForceUpdate = true // the local branch only ever holds the app's own commits
        val result = update.update()
        if (result !in OK_REF_UPDATES) throw SyncException(SyncError.Failed("Cannot update $localRef: $result"))
    }

    // ---- Errors and network --------------------------------------------------------------

    private fun check(bookletId: String) {
        if (!bookletId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) throw SyncException(SyncError.Failed("Unsafe booklet id"))
    }

    private fun provider() = credentials?.let { UsernamePasswordCredentialsProvider(it.username, it.token) }

    private val monitor = object : ProgressMonitor {
        override fun start(totalTasks: Int) = Unit
        override fun beginTask(title: String?, totalWork: Int) = Unit
        override fun update(completed: Int) = Unit
        override fun endTask() = Unit
        override fun isCancelled() = canceled
        override fun showDuration(enabled: Boolean) = Unit
    }

    private inline fun <T> network(block: () -> T): T {
        if (canceled) throw SyncException(SyncError.Canceled)
        return block().also { if (canceled) throw SyncException(SyncError.Canceled) }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SyncException) {
        throw e
    } catch (e: Exception) {
        throw SyncException(if (canceled) SyncError.Canceled else classify(e), e)
    }

    private fun classify(e: Throwable): SyncError {
        val chain = generateSequence(e) { it.cause }.toList()
        val text = chain.mapNotNull { it.message }.joinToString(": ")
        return when {
            chain.any { it is NoRemoteRepositoryException } -> SyncError.Rejected("Repository not found")
            AUTH_MESSAGES.any { text.contains(it, ignoreCase = true) } -> SyncError.AuthFailed
            chain.any {
                it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
                    it is SocketTimeoutException || it is InterruptedIOException
            } -> SyncError.Unreachable(redactor.redact(chain.last().message.orEmpty()))
            chain.any { it is org.eclipse.jgit.api.errors.TransportException || it is org.eclipse.jgit.errors.TransportException } ->
                SyncError.Unreachable(redactor.redact(e.message.orEmpty()))
            else -> SyncError.Failed(redactor.redact(e.message ?: e.javaClass.simpleName))
        }
    }

    private companion object {
        val OK_REF_UPDATES = setOf(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.NO_CHANGE)

        /** JGit's wording for a refused or missing login (JGitText.notAuthorized and friends). */
        val AUTH_MESSAGES = listOf("not authorized", "authentication is required", "authentication not supported")

        /**
         * JGit reads the user's and system's Git config by default (`~/.gitconfig`, which on a
         * JVM test host could set `url.insteadOf`, credential helpers or proxies). The app
         * reads only the repository's own config.
         */
        val isolateFromHostConfig: Unit by lazy {
            SystemReader.setInstance(object : SystemReader.Delegate(SystemReader.getInstance()) {
                override fun openUserConfig(parent: Config?, fs: FS) = EmptyConfig(parent, fs)
                override fun openSystemConfig(parent: Config?, fs: FS) = EmptyConfig(parent, fs)
                override fun openJGitConfig(parent: Config?, fs: FS) = EmptyConfig(parent, fs)
            })
        }
    }

    private class EmptyConfig(parent: Config?, fs: FS) : FileBasedConfig(parent, null, fs) {
        override fun load() = Unit
        override fun save() = Unit
        override fun isOutdated() = false
    }
}
