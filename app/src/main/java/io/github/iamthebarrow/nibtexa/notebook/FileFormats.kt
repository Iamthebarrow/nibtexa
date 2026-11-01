package io.github.iamthebarrow.nibtexa.notebook

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.ui.theme.Nib

/**
 * The formats a booklet can be exported to (Phases 18 and 20): the Export button lists them, and the
 * chosen one opens the system "Save as" dialog. A new format is one more entry here.
 */
enum class ExportFormat(@StringRes val title: Int, @StringRes val description: Int, val extension: String, val mimeType: String) {
    PDF(R.string.format_pdf, R.string.format_pdf_description, "pdf", "application/pdf"),
    LATEX(R.string.format_latex_zip, R.string.format_latex_zip_export, "zip", "application/zip"),
    MARKDOWN(R.string.format_markdown_zip, R.string.format_markdown_zip_export, "zip", "application/zip"),
    ANNOTATED_PDF(R.string.format_annotated_pdf, R.string.format_annotated_pdf_description, "pdf", "application/pdf"),
}

/**
 * The formats a booklet can be imported from (Phases 18 and 20): the Import button lists them, and the
 * chosen one opens the system file picker with [mimeTypes]. A new format is one more entry here.
 */
enum class ImportFormat(@StringRes val title: Int, @StringRes val description: Int, val mimeTypes: Array<String>) {
    LATEX_FILE(R.string.format_tex, R.string.format_tex_import, arrayOf("text/*", "application/x-tex", "application/x-latex", "application/octet-stream")),
    LATEX_PROJECT(R.string.format_latex_zip, R.string.format_latex_zip_import, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")),
    MARKDOWN_FILE(R.string.format_markdown, R.string.format_markdown_import, arrayOf("text/markdown", "text/x-markdown", "text/*", "application/octet-stream")),
    MARKDOWN_PROJECT(R.string.format_markdown_zip, R.string.format_markdown_zip_import, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")),
    PDF_PAGES(R.string.format_pdf_pages, R.string.format_pdf_pages_import, arrayOf("application/pdf")),
}

/** What an import made: the new booklet's title, its pages, and what was dropped or simplified. */
data class ImportReport(val title: String, val pages: Int, val warnings: List<String>)

/** A list of formats to choose from, each with what it is for; choosing one closes the dialog. */
@Composable
fun FormatDialog(title: String, formats: List<Pair<String, String>>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val p = Nib.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(FORMAT_DIALOG_TAG)) {
                formats.forEachIndexed { i, (name, description) ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = p.card,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = name) { onPick(i) },
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(name, style = MaterialTheme.typography.titleSmall, color = p.ink)
                            Text(description, style = MaterialTheme.typography.bodySmall, color = p.muted)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** What the last import left out or simplified, after it opened the new booklet. */
@Composable
fun ImportReportDialog(report: ImportReport, onDismiss: () -> Unit) {
    val p = Nib.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_done_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.import_done, report.pages, report.title, report.pages), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                if (report.warnings.isEmpty()) {
                    Text(stringResource(R.string.import_nothing_dropped), style = MaterialTheme.typography.bodySmall, color = p.muted)
                } else {
                    Text(stringResource(R.string.import_dropped), style = MaterialTheme.typography.labelLarge, color = p.muted)
                    report.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = p.ink) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

const val FORMAT_DIALOG_TAG = "format_dialog"
