package io.github.iamthebarrow.nibtexa.core.sync

/** A failed sync step. Messages never contain the token (see [Redactor]). */
sealed interface SyncError {
    data class InvalidSettings(val problems: List<SettingsProblem>) : SyncError

    /** The host refused the credentials, or asked for some and none were given. */
    data object AuthFailed : SyncError

    /** The host could not be reached: offline, wrong address, timeout. */
    data class Unreachable(val detail: String) : SyncError

    /** The repository or branch was refused for another reason (missing, protected, ...). */
    data class Rejected(val detail: String) : SyncError

    /** A commit made earlier has not been pushed yet; push or discard it first. */
    data object PendingCommit : SyncError

    /** The repository's `.nibtexa-sync.json` could not be read. Nothing was changed. */
    data object CorruptRecord : SyncError

    data object Canceled : SyncError

    data class Failed(val detail: String) : SyncError
}

class SyncException(val error: SyncError, cause: Throwable? = null) : Exception(error.toString(), cause)

enum class ChangeKind { ADDED, MODIFIED, DELETED }

/** A file the commit will change. Paths are repository paths, `/`-separated. */
class FileChange(val path: String, val kind: ChangeKind, val before: ByteArray?, val after: ByteArray?) {
    override fun toString() = "FileChange($path, $kind)"
}

enum class ConflictKind {
    /** A file the app wrote was edited outside it. */
    EDITED,

    /** A file the app wrote was deleted outside it. */
    DELETED,

    /** A file the app is about to write already exists, and the app did not write it. */
    NOT_OURS,
}

/** A generated file that was changed outside the app. Paths are repository paths. */
data class Conflict(val path: String, val kind: ConflictKind)

/** How to treat every [Conflict] in a plan. */
enum class Resolution {
    /** Keep the outside version and stop generating the file. */
    KEEP,

    /** Replace the outside version with the app's. */
    REGENERATE,

    /** Keep the outside version and write the app's version next to it ([copyPath]). */
    COPY,
}

/** The app's version of `dir/name.ext` is written to `dir/name.nibtexa.ext`. */
fun copyPath(path: String): String {
    val slash = path.lastIndexOf('/')
    val dot = path.lastIndexOf('.').takeIf { it > slash + 1 } ?: return "$path.nibtexa"
    return path.substring(0, dot) + ".nibtexa" + path.substring(dot)
}

/**
 * What a sync would commit, worked out against the remote branch as fetched. Nothing has been
 * written yet; [GitSync.commit] applies it.
 *
 * @property remoteAdvanced the remote branch has commits since the app's last push, outside
 *   the booklet folder or not. Shown to the user; it never blocks a sync on its own.
 */
class SyncPlan internal constructor(
    val bookletPath: String,
    val conflicts: List<Conflict>,
    val remoteAdvanced: Boolean,
    internal val baseCommit: String?,
    internal val files: Map<String, ByteArray>,
    internal val current: Map<String, ByteArray>,
    internal val record: SyncRecord,
) {
    /** Files the user kept after editing them elsewhere (repository paths). */
    val kept: List<String> get() = record.kept.keys.map { "$bookletPath/$it" }.sorted()

    /**
     * What the commit changes when the conflicts are resolved with [resolution], and
     * [kept] files are handed back to the app if [releaseKept].
     */
    fun changes(resolution: Resolution, releaseKept: Boolean = false): List<FileChange> = Planner.target(this, resolution, releaseKept).let { target ->
        (target.keys + current.keys).toSortedSet().mapNotNull { path ->
            val before = current[path]
            val after = target[path]
            when {
                before == null && after != null -> FileChange(path, ChangeKind.ADDED, null, after)
                before != null && after == null -> FileChange(path, ChangeKind.DELETED, before, null)
                before != null && after != null && !before.contentEquals(after) -> FileChange(path, ChangeKind.MODIFIED, before, after)
                else -> null
            }
        }
    }
}

/** A commit that exists locally but not on the remote. */
data class PendingCommit(val id: String, val message: String)

sealed interface CommitResult {
    data class Committed(val commit: PendingCommit) : CommitResult

    /** The repository already holds exactly this export. */
    data object NothingToCommit : CommitResult
}

sealed interface PushResult {
    data class Pushed(val commitId: String) : PushResult

    /**
     * The remote branch moved on since the commit was made. The commit is kept locally.
     *
     * @property canRebase none of the new remote commits touch the destination folder, so
     *   the commit can be replayed on top of them without overwriting anyone's work.
     */
    data class RemoteAdvanced(val canRebase: Boolean) : PushResult
}

/** Replaces the token in text that might be shown or logged. */
class Redactor(private val secret: String?) {
    fun redact(text: String): String = if (secret.isNullOrEmpty()) text else text.replace(secret, "***")
}
