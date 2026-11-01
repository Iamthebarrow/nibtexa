package io.github.iamthebarrow.nibtexa.core.sync

/**
 * The rules for what an export may change in the booklet folder. Pure: it compares the
 * folder's current content (from the remote branch) with the app's record of what it wrote.
 *
 * - A file the app wrote and nobody changed is replaced or deleted freely.
 * - A file the app wrote that was edited or deleted elsewhere is a [Conflict].
 * - A file the app is about to write that it never wrote before, and that differs, is a
 *   conflict too.
 * - Any other file in the folder is left alone.
 * - Files the user kept stay as they are until the user releases them.
 */
internal object Planner {

    /**
     * @param generated the export, keyed by path relative to the booklet folder.
     * @param current the booklet folder on the remote branch, keyed by repository path.
     */
    fun plan(
        bookletPath: String,
        generated: Map<String, ByteArray>,
        current: Map<String, ByteArray>,
        record: SyncRecord,
        baseCommit: String?,
        remoteAdvanced: Boolean,
    ): SyncPlan {
        val files = generated.mapKeys { (path, _) -> "$bookletPath/$path" }
        val conflicts = buildList {
            for ((relative, hash) in record.files) {
                if (relative in record.kept) continue
                val path = "$bookletPath/$relative"
                val now = current[path]
                when {
                    now == null && path in files -> add(Conflict(path, ConflictKind.DELETED))
                    now != null && SyncRecord.hash(now) != hash -> add(Conflict(path, ConflictKind.EDITED))
                }
            }
            for ((path, bytes) in files) {
                val relative = path.removePrefix("$bookletPath/")
                if (relative in record.files || relative in record.kept) continue
                val now = current[path] ?: continue
                if (!now.contentEquals(bytes)) add(Conflict(path, ConflictKind.NOT_OURS))
            }
        }.sortedBy { it.path }
        return SyncPlan(bookletPath, conflicts, remoteAdvanced, baseCommit, files, current, record)
    }

    /**
     * The booklet folder's full content after the commit, including the new record.
     *
     * @param releaseKept hand every kept file back to the app: generated ones are written
     *   again, the others are left in place as the user's own files.
     */
    fun target(plan: SyncPlan, resolution: Resolution, releaseKept: Boolean): Map<String, ByteArray> {
        val prefix = "${plan.bookletPath}/"
        val recordPath = prefix + SyncRecord.FILE_NAME
        val conflicts = plan.conflicts.associateBy { it.path }
        val kept = if (releaseKept) mutableMapOf() else plan.record.kept.toMutableMap()
        val released = if (releaseKept) plan.record.kept.keys else emptySet()
        val target = plan.current.toMutableMap()
        val written = mutableSetOf<String>()

        fun write(path: String, bytes: ByteArray) {
            // Never write over a file the app does not own, such as a user's own `x.nibtexa.tex`.
            val relative = path.removePrefix(prefix)
            val owned = relative in plan.record.files || relative in released || path in plan.files
            if (path in plan.current && !owned) return
            target[path] = bytes
            written += path
        }

        for ((path, bytes) in plan.files) {
            val relative = path.removePrefix(prefix)
            val mode = kept[relative] ?: if (path in conflicts) {
                when (resolution) {
                    Resolution.REGENERATE -> null
                    Resolution.KEEP -> KeepMode.KEEP
                    Resolution.COPY -> KeepMode.COPY
                }
            } else {
                null
            }
            when (mode) {
                null -> write(path, bytes)
                KeepMode.KEEP -> kept[relative] = KeepMode.KEEP
                KeepMode.COPY -> {
                    kept[relative] = KeepMode.COPY
                    write(copyPath(path), bytes)
                }
            }
        }
        // Files the app wrote before but no longer generates (deleted pages, old copies).
        for (relative in plan.record.files.keys) {
            val path = prefix + relative
            if (path in written || path in plan.files || relative in kept) continue
            if (path in conflicts && resolution != Resolution.REGENERATE) {
                kept[relative] = KeepMode.KEEP
            } else {
                target.remove(path)
            }
        }
        target.remove(recordPath)
        val record = SyncRecord(
            files = written.associate { it.removePrefix(prefix) to SyncRecord.hash(target.getValue(it)) },
            kept = kept,
        )
        target[recordPath] = record.encode().toByteArray(Charsets.UTF_8)
        return target
    }
}
