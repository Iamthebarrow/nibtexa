package io.github.iamthebarrow.nibtexa.export

import android.content.Context
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.export.ExportProblem
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet

/** Why a booklet can't be exported yet, in the user's words. */
object ExportMessages {

    fun describe(context: Context, booklet: Booklet, problems: List<ExportProblem>): String =
        problems.joinToString("; ") { describe(context, booklet, it) }

    fun describe(context: Context, booklet: Booklet, problem: ExportProblem): String {
        fun where(pageId: String, regionId: String) =
            context.getString(R.string.region_on_page, regionId, booklet.pages.indexOfFirst { it.id == pageId } + 1)
        return when (problem) {
            is ExportProblem.NotConfirmed -> context.getString(R.string.problem_not_confirmed, where(problem.pageId, problem.regionId))
            is ExportProblem.Stale -> context.getString(R.string.problem_stale, where(problem.pageId, problem.regionId))
            is ExportProblem.UnsafeLatex -> context.getString(R.string.problem_unsafe_latex, where(problem.pageId, problem.regionId), problem.reason)
            is ExportProblem.UnsafeId -> context.getString(R.string.problem_unsafe_id, problem.id)
            is ExportProblem.DuplicateId -> context.getString(R.string.problem_duplicate_id, problem.id)
        }
    }
}
