package io.github.iamthebarrow.nibtexa.core.sync

import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import java.io.ByteArrayOutputStream

object Diffs {

    /** A unified diff of [change] for review, or null for a binary file such as a PNG. */
    fun unified(change: FileChange, context: Int = 3): String? {
        val before = change.before ?: ByteArray(0)
        val after = change.after ?: ByteArray(0)
        if (RawText.isBinary(before) || RawText.isBinary(after)) return null
        val a = RawText(before)
        val b = RawText(after)
        val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM).diff(RawTextComparator.DEFAULT, a, b)
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { formatter ->
            formatter.setContext(context)
            formatter.format(edits, a, b)
        }
        return out.toString(Charsets.UTF_8)
    }
}
