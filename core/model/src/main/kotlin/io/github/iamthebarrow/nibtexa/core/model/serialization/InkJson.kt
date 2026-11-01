package io.github.iamthebarrow.nibtexa.core.model.serialization

import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.PenKind
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The project's own versioned ink format. This, not any rendering library's format, is
 * the persisted representation of strokes.
 *
 * Version history:
 * - 1: strokes with points (x, y, t) and optional pressure (p), tilt and orientation (o).
 * - 2: each stroke also records its [Pen]. Version 1 documents still decode; their strokes
 *   were all written with the math pen, the only pen that existed.
 */
object InkJson {
    const val FORMAT = "nibtexa-ink"
    const val VERSION = 2
    private const val OLDEST_READABLE_VERSION = 1

    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encode(document: InkDocument): String = json.encodeToString(InkFile.serializer(), document.toFile())

    /** @throws InkFormatException if the text is not a supported ink document. */
    fun decode(text: String): InkDocument {
        val file = try {
            json.decodeFromString(InkFile.serializer(), text)
        } catch (e: SerializationException) {
            throw InkFormatException("Malformed ink document: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw InkFormatException("Malformed ink document: ${e.message}", e)
        }
        if (file.format != FORMAT) throw InkFormatException("Unknown format '${file.format}'")
        if (file.version !in OLDEST_READABLE_VERSION..VERSION) throw InkFormatException("Unsupported version ${file.version}")
        if (file.version >= 2 && file.strokes.any { it.pen == null }) throw InkFormatException("Stroke without pen")
        return file.toDocument()
    }

    /** Encodes one stroke's points in the same per-point form documents use. */
    fun encodePoints(points: List<InkPoint>): String =
        json.encodeToString(POINTS, points.map { PointJson(it.x, it.y, it.elapsedMillis, it.pressure, it.tiltRadians, it.orientationRadians) })

    /** @throws InkFormatException if the text is not a non-empty point list. */
    fun decodePoints(text: String): List<InkPoint> {
        val points = try {
            json.decodeFromString(POINTS, text)
        } catch (e: SerializationException) {
            throw InkFormatException("Malformed points: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw InkFormatException("Malformed points: ${e.message}", e)
        }
        if (points.isEmpty()) throw InkFormatException("Stroke without points")
        return points.map { InkPoint(it.x, it.y, it.t, it.p, it.tilt, it.o) }
    }

    private val POINTS = kotlinx.serialization.builtins.ListSerializer(PointJson.serializer())

    private fun InkDocument.toFile() = InkFile(
        format = FORMAT,
        version = VERSION,
        nextStrokeId = nextStrokeId,
        strokes = strokes.map { stroke ->
            StrokeJson(
                id = stroke.id,
                tool = stroke.tool,
                pen = stroke.pen,
                kind = stroke.kind.takeIf { it != PenKind.GEL },
                startTimeMillis = stroke.startTimeMillis,
                points = stroke.points.map { PointJson(it.x, it.y, it.elapsedMillis, it.pressure, it.tiltRadians, it.orientationRadians) },
            )
        },
    )

    private fun InkFile.toDocument(): InkDocument {
        val ids = strokes.map { it.id }
        if (ids.toSet().size != ids.size) throw InkFormatException("Duplicate stroke ids")
        if (strokes.any { it.id >= nextStrokeId }) throw InkFormatException("Stroke id not below nextStrokeId")
        if (strokes.any { it.points.isEmpty() }) throw InkFormatException("Stroke without points")
        return InkDocument(
            strokes = strokes.map { s ->
                Stroke(s.id, s.tool, s.startTimeMillis, s.points.map { InkPoint(it.x, it.y, it.t, it.p, it.tilt, it.o) }, s.pen ?: Pen.MATH, s.kind ?: PenKind.GEL)
            },
            nextStrokeId = nextStrokeId,
        )
    }
}

class InkFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
private data class InkFile(
    val format: String,
    val version: Int,
    val nextStrokeId: Long,
    val strokes: List<StrokeJson>,
)

@Serializable
private data class StrokeJson(
    val id: Long,
    val tool: ToolType,
    /** Absent only in version 1 documents. */
    val pen: Pen? = null,
    /** The kind of pen (Phase 22); left out for gel, so older documents are unchanged and still read. */
    val kind: PenKind? = null,
    val startTimeMillis: Long,
    val points: List<PointJson>,
)

@Serializable
private data class PointJson(
    val x: Float,
    val y: Float,
    val t: Long,
    val p: Float? = null,
    val tilt: Float? = null,
    @SerialName("o") val o: Float? = null,
)
