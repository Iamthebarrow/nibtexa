package io.github.iamthebarrow.nibtexa.core.model.serialization

import io.github.iamthebarrow.nibtexa.core.model.notebook.Diagram
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramArrow
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramFacing
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramLabel
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramLine
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramLineType
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPoint
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramShape
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramShapeType
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramSymbol
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A diagram as stored in a region's column and in booklet documents (Phase 31). Points are flat lists of x, y. */
object DiagramJson {
    private val json = Json { explicitNulls = false; ignoreUnknownKeys = true }

    fun encode(diagram: Diagram): String = json.encodeToString(
        DiagramFile.serializer(),
        DiagramFile(
            width = diagram.width,
            height = diagram.height,
            nextId = diagram.nextId,
            shapes = diagram.shapes.map { ShapeJson(it.id, it.type.name, it.x, it.y, it.width, it.height, it.facing.name, it.onLine, it.along) },
            lines = diagram.lines.map { l ->
                LineJson(
                    l.id, flat(l.points), l.type.name, l.from, l.to, l.arrowStart.takeIf { it }, l.arrowEnd.takeIf { it },
                    l.arrows.takeIf { it.isNotEmpty() }?.map { ArrowJson(it.at, it.reversed.takeIf { r -> r }) },
                    l.symbol.takeIf { it != DiagramSymbol.NONE }?.name, l.dashed.takeIf { it },
                )
            },
            labels = diagram.labels.map { l ->
                LabelJson(l.id, l.x, l.y, l.latex, l.text, l.shape, l.ink.takeIf { it.isNotEmpty() }?.map(::flat), l.unread.takeIf { it }, l.words.takeIf { it })
            },
        ),
    )

    /** The diagram in [text], kept within limits, or null if it isn't one. */
    fun decode(text: String): Diagram? {
        val file = try {
            json.decodeFromString(DiagramFile.serializer(), text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return Diagram(
            width = file.width,
            height = file.height,
            nextId = file.nextId,
            shapes = file.shapes.map { s ->
                DiagramShape(
                    s.id, DiagramShapeType.entries.firstOrNull { it.name == s.type } ?: DiagramShapeType.BOX, s.x, s.y, s.width, s.height,
                    DiagramFacing.entries.firstOrNull { it.name == s.facing } ?: DiagramFacing.RIGHT, s.onLine, s.along,
                )
            },
            lines = file.lines.map { l ->
                val points = points(l.points) ?: return null
                DiagramLine(
                    l.id, points, DiagramLineType.entries.firstOrNull { it.name == l.type } ?: DiagramLineType.STRAIGHT, l.from, l.to, l.arrowStart == true, l.arrowEnd == true,
                    l.arrows.orEmpty().map { DiagramArrow(it.at, it.reversed == true) }, DiagramSymbol.entries.firstOrNull { it.name == l.symbol } ?: DiagramSymbol.NONE, l.dashed == true,
                )
            },
            labels = file.labels.map { l ->
                DiagramLabel(l.id, l.x, l.y, l.latex, l.text, l.shape, l.ink.orEmpty().map { points(it) ?: return null }, l.unread == true, l.words == true)
            },
        ).checked()
    }

    private fun flat(points: List<DiagramPoint>) = points.flatMap { listOf(it.x, it.y) }

    private fun points(flat: List<Float>): List<DiagramPoint>? =
        if (flat.size % 2 != 0) null else flat.chunked(2) { DiagramPoint(it[0], it[1]) }
}

@Serializable
private data class DiagramFile(
    val width: Float,
    val height: Float,
    val nextId: Int,
    val shapes: List<ShapeJson> = emptyList(),
    val lines: List<LineJson> = emptyList(),
    val labels: List<LabelJson> = emptyList(),
)

@Serializable
private data class ShapeJson(
    val id: Int,
    val type: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val facing: String? = null,
    val onLine: Int? = null,
    val along: Float = 0f,
)

@Serializable
private data class LineJson(
    val id: Int,
    val points: List<Float>,
    val type: String? = null,
    val from: Int? = null,
    val to: Int? = null,
    val arrowStart: Boolean? = null,
    val arrowEnd: Boolean? = null,
    val arrows: List<ArrowJson>? = null,
    val symbol: String? = null,
    val dashed: Boolean? = null,
)

@Serializable
private data class ArrowJson(val at: Float, val reversed: Boolean? = null)

@Serializable
private data class LabelJson(
    val id: Int,
    val x: Float,
    val y: Float,
    val latex: String? = null,
    val text: String? = null,
    val shape: Int? = null,
    val ink: List<List<Float>>? = null,
    val unread: Boolean? = null,
    val words: Boolean? = null,
)
