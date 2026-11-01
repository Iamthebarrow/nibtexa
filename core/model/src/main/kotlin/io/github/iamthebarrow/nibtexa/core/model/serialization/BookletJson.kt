package io.github.iamthebarrow.nibtexa.core.model.serialization

import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.ColumnMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMarks
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A whole booklet as one editable document: pages, their ink (as [InkJson]) and regions with
 * every result and correction. This is the optional `source/` snapshot committed next to the
 * LaTeX (ADR-0006). The output is deterministic, so an unchanged booklet commits nothing.
 */
object BookletJson {
    const val FORMAT = "nibtexa-booklet"
    const val VERSION = 1

    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
        prettyPrint = true
        prettyPrintIndent = " "
    }

    fun encode(booklet: Booklet): String = json.encodeToString(BookletFile.serializer(), booklet.toFile()) + "\n"

    /** @throws InkFormatException if the text is not a supported booklet document. */
    fun decode(text: String): Booklet {
        val file = try {
            json.decodeFromString(BookletFile.serializer(), text)
        } catch (e: SerializationException) {
            throw InkFormatException("Malformed booklet document: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw InkFormatException("Malformed booklet document: ${e.message}", e)
        }
        if (file.format != FORMAT) throw InkFormatException("Unknown format '${file.format}'")
        if (file.version != VERSION) throw InkFormatException("Unsupported version ${file.version}")
        return try {
            file.toBooklet()
        } catch (e: IllegalArgumentException) {
            throw InkFormatException("Malformed booklet document: ${e.message}", e)
        }
    }

    private fun Booklet.toFile() = BookletFile(
        format = FORMAT,
        version = VERSION,
        id = id,
        title = title,
        pages = pages.map { page ->
            PageJson(
                id = page.id,
                paper = page.paper.name,
                background = page.background,
                manualOrder = page.manualOrder.takeIf { it },
                columns = page.columns.takeIf { it != ColumnMode.AUTO }?.name,
                margin = page.margin?.name,
                nextRegionNumber = page.nextRegionNumber,
                ink = Json.parseToJsonElement(InkJson.encode(page.ink)),
                regions = page.regions.map { r ->
                    RegionJson(
                        id = r.id,
                        role = r.role.name,
                        strokeIds = r.strokeIds.sorted(),
                        bounds = listOf(r.bounds.left, r.bounds.top, r.bounds.right, r.bounds.bottom),
                        readingOrder = r.readingOrder,
                        status = r.status.name,
                        sourceStrokeIds = r.sourceStrokeIds.sorted(),
                        recognizedLatex = r.recognizedLatex,
                        confirmedLatex = r.confirmedLatex,
                        error = r.error,
                        text = r.text,
                        headingLevel = r.headingLevel,
                        caption = r.caption,
                        recognizedText = r.recognizedText,
                        auto = r.auto,
                        align = r.align?.name,
                        marks = TextMarks.encode(r.marks)?.let(Json::parseToJsonElement),
                        theorem = r.theorem?.name,
                        listLevel = r.listLevel,
                        newParagraph = r.newParagraph,
                        numbered = r.numbered.takeIf { it },
                        inline = r.inline,
                        pageBreak = r.pageBreak.takeIf { it },
                        typed = r.typed.takeIf { it },
                        picture = r.picture,
                        diagram = r.diagram?.let { Json.parseToJsonElement(DiagramJson.encode(it)) },
                        graph = r.graph?.let { Json.parseToJsonElement(GraphJson.encode(it)) },
                    )
                },
            )
        },
    )

    private fun BookletFile.toBooklet() = Booklet(
        id = id,
        title = title,
        pages = pages.map { page ->
            Page(
                id = page.id,
                ink = InkJson.decode(page.ink.toString()),
                regions = page.regions.map { r ->
                    require(r.bounds.size == 4) { "Bounds need four values" }
                    Region(
                        id = r.id,
                        role = RegionRole.valueOf(r.role),
                        strokeIds = r.strokeIds.toCollection(linkedSetOf()),
                        bounds = Bounds(r.bounds[0], r.bounds[1], r.bounds[2], r.bounds[3]),
                        readingOrder = r.readingOrder,
                        status = RecognitionStatus.valueOf(r.status),
                        sourceStrokeIds = r.sourceStrokeIds.toCollection(linkedSetOf()),
                        recognizedLatex = r.recognizedLatex,
                        confirmedLatex = r.confirmedLatex,
                        error = r.error,
                        text = r.text,
                        headingLevel = r.headingLevel,
                        caption = r.caption,
                        recognizedText = r.recognizedText,
                        auto = r.auto,
                        align = r.align?.let(MathAlign::valueOf),
                        marks = TextMarks.decode(r.marks?.toString()),
                        theorem = r.theorem?.let(TheoremKind::valueOf),
                        listLevel = r.listLevel?.coerceIn(0, 2),
                        newParagraph = r.newParagraph,
                        numbered = r.numbered == true,
                        inline = r.inline,
                        pageBreak = r.pageBreak == true,
                        typed = r.typed == true,
                        picture = r.picture,
                        diagram = r.diagram?.let { DiagramJson.decode(it.toString()) },
                        graph = r.graph?.let { GraphJson.decode(it.toString()) },
                    )
                },
                nextRegionNumber = page.nextRegionNumber,
                paper = Paper.valueOf(page.paper),
                background = page.background,
                manualOrder = page.manualOrder == true,
                columns = ColumnMode.entries.firstOrNull { it.name == page.columns } ?: ColumnMode.AUTO,
                margin = PageMargin.entries.firstOrNull { it.name == page.margin },
            )
        },
    )
}

@Serializable
private data class BookletFile(
    val format: String,
    val version: Int,
    val id: String,
    val title: String,
    val pages: List<PageJson>,
)

@Serializable
private data class PageJson(
    val id: String,
    val paper: String,
    val nextRegionNumber: Int,
    val ink: JsonElement,
    val regions: List<RegionJson>,
    // Phase 20: the picture under the ink.
    val background: String? = null,
    val manualOrder: Boolean? = null,
    // Phase 30.
    val columns: String? = null,
    val margin: String? = null,
)

@Serializable
private data class RegionJson(
    val id: String,
    val role: String,
    val strokeIds: List<Long>,
    val bounds: List<Float>,
    val readingOrder: Int,
    val status: String,
    val sourceStrokeIds: List<Long>,
    val recognizedLatex: String? = null,
    val confirmedLatex: String? = null,
    val error: String? = null,
    val text: String? = null,
    val headingLevel: Int,
    val caption: String? = null,
    // Phase 11 (ADR-0007). Omitted when empty, so older snapshots are unchanged and still read.
    val recognizedText: String? = null,
    val auto: Boolean = false,
    // Phases 14 and 16, likewise omitted when unset.
    val align: String? = null,
    val marks: JsonElement? = null,
    val theorem: String? = null,
    val listLevel: Int? = null,
    val newParagraph: Boolean? = null,
    val numbered: Boolean? = null,
    val inline: Boolean? = null,
    val pageBreak: Boolean? = null,
    // Phases 18 and 20.
    val typed: Boolean? = null,
    val picture: String? = null,
    // Phase 25.
    val graph: JsonElement? = null,
    val diagram: JsonElement? = null,
)
