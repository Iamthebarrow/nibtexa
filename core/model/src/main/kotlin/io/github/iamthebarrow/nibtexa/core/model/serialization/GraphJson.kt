package io.github.iamthebarrow.nibtexa.core.model.serialization

import io.github.iamthebarrow.nibtexa.core.model.notebook.Graph
import io.github.iamthebarrow.nibtexa.core.model.notebook.GraphCurve
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A graph as stored in a region's column and in booklet documents (Phase 25). Constants are sorted, so the output is deterministic. */
object GraphJson {
    private val json = Json { explicitNulls = false }

    fun encode(graph: Graph): String = json.encodeToString(
        GraphFile.serializer(),
        GraphFile(
            curves = graph.curves.map { CurveJson(it.latex, it.source, it.color, it.hidden.takeIf { h -> h }, it.input) },
            x = listOf(graph.xMin, graph.xMax),
            y = listOf(graph.yMin, graph.yMax),
            degrees = graph.degrees.takeIf { it },
            constants = graph.constants.toSortedMap().takeIf { it.isNotEmpty() },
            title = graph.title,
            xLabel = graph.xLabel,
            yLabel = graph.yLabel,
        ),
    )

    /** The graph in [text], kept within limits, or null if it isn't one. */
    fun decode(text: String): Graph? {
        val file = try {
            json.decodeFromString(GraphFile.serializer(), text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (file.x.size != 2 || file.y.size != 2) return null
        return Graph(
            curves = file.curves.map { GraphCurve(it.latex, it.source, it.color, it.hidden == true, it.input) },
            xMin = file.x[0],
            xMax = file.x[1],
            yMin = file.y[0],
            yMax = file.y[1],
            degrees = file.degrees == true,
            constants = file.constants.orEmpty(),
            title = file.title,
            xLabel = file.xLabel,
            yLabel = file.yLabel,
        ).checked()
    }
}

@Serializable
private data class GraphFile(
    val curves: List<CurveJson>,
    val x: List<Double>,
    val y: List<Double>,
    val degrees: Boolean? = null,
    val constants: Map<String, Double>? = null,
    val title: String? = null,
    val xLabel: String? = null,
    val yLabel: String? = null,
)

@Serializable
private data class CurveJson(
    val latex: String,
    val source: String? = null,
    val color: Int = 0,
    val hidden: Boolean? = null,
    val input: String? = null,
)
