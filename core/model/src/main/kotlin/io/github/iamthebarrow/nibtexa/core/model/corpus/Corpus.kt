package io.github.iamthebarrow.nibtexa.core.model.corpus

import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.serialization.InkJson
import io.github.iamthebarrow.nibtexa.core.model.serialization.InkMlWriter

/** Something to write by hand, with its ground-truth LaTeX. */
data class CorpusPrompt(
    val id: String,
    val category: String,
    val latex: String,
)

object CorpusPrompts {
    private val ID = Regex("[a-z0-9][a-z0-9-]*")

    /**
     * Parses tab-separated `id<TAB>category<TAB>latex` lines. Blank lines and lines
     * starting with `#` are skipped.
     *
     * @throws IllegalArgumentException on a malformed line or a duplicate id.
     */
    fun parse(text: String): List<CorpusPrompt> {
        val prompts = text.lineSequence()
            .mapIndexedNotNull { index, raw ->
                val line = raw.trimEnd('\r')
                if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
                val parts = line.split('\t')
                require(parts.size == 3) { "Line ${index + 1}: expected 3 tab-separated fields, got ${parts.size}" }
                val (id, category, latex) = parts.map { it.trim() }
                require(ID.matches(id)) { "Line ${index + 1}: invalid id '$id'" }
                require(category.isNotEmpty() && latex.isNotEmpty()) { "Line ${index + 1}: empty field" }
                CorpusPrompt(id, category, latex)
            }
            .toList()
        val duplicate = prompts.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "Duplicate prompt id '${duplicate?.key}'" }
        return prompts
    }
}

/** The files that make up one corpus sample. */
data class CorpusSampleFiles(
    val baseName: String,
    val inkMl: String,
    val json: String,
)

object CorpusSamples {
    /**
     * Builds the files for one handwritten sample of [prompt].
     *
     * @param timestamp a filename-safe UTC timestamp, e.g. `20261026T223000Z`.
     * @param device a short description of the capture device, recorded for analysis.
     * @param mathTruth true for math prompts (truth recorded as `$latex$`), false for the text
     *   trial set (truth recorded as plain text).
     */
    fun build(prompt: CorpusPrompt, document: InkDocument, timestamp: String, device: String, mathTruth: Boolean = true): CorpusSampleFiles {
        require(document.strokes.isNotEmpty()) { "Cannot save an empty sample" }
        require(Regex("[0-9TZ]+").matches(timestamp)) { "Timestamp must be filename-safe" }
        val baseName = "${prompt.id}_$timestamp"
        val inkMl = InkMlWriter.write(
            document.strokes,
            listOf(
                "truth" to if (mathTruth) "$${prompt.latex}$" else prompt.latex,
                "UI" to baseName,
                "category" to prompt.category,
                "device" to device,
            ),
        )
        return CorpusSampleFiles(baseName, inkMl, InkJson.encode(document))
    }
}
