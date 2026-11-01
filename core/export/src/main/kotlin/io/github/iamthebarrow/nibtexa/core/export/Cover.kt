package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the exports print on the cover page (Phase 14): the title, the author, and the dates the
 * booklet was created and last updated. The PDF and the LaTeX title page use the same lines, so
 * both say the same thing. Lines without a value are left out. Each line but the title is
 * labelled ("Author: …", "Created: …", "Last updated: …").
 */
data class Cover(val title: String, val author: String, val dates: List<String>) {
    /** The author as printed, or null when there is none. */
    val authorLine: String? get() = author.takeIf { it.isNotEmpty() }?.let { "Author: $it" }

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)

        fun of(booklet: Booklet): Cover {
            val info = booklet.info
            val dates = listOfNotNull(
                info.created?.let { "Created: ${format(it)}" },
                info.updated?.let { "Last updated: ${format(it)}" },
            )
            return Cover(booklet.title.trim(), info.author.trim(), dates)
        }

        fun format(date: LocalDate): String = DATE.format(date)
    }
}
