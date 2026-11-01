package io.github.iamthebarrow.nibtexa.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * NibTeXa's icons (Phase 13): simple 24 × 24 stroke drawings made for this app, so no icon
 * library is bundled. They are drawn in black and tinted by `Icon`; every icon button still has
 * a text label for screen readers.
 */
object NibIcons {
    private fun stroke(name: String, vararg paths: String, width: Float = 2f, filled: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            for (d in filled) addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        }.build()

    private fun dot(cx: Float, cy: Float, r: Float = 1.8f) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    // The kinds of pen (Phase 22): the same squiggle in the kind's own line weight.
    val PenBallpoint = stroke("pen_ballpoint", "M4 16c3-7 5-7 7-3s4 4 9-5", width = 1.2f)
    val PenGel = stroke("pen_gel", "M4 16c3-7 5-7 7-3s4 4 9-5", width = 2.2f)
    val PenFelt = stroke("pen_felt", "M4 16c3-7 5-7 7-3s4 4 9-5", width = 3.8f)

    val Back = stroke("back", "M15 6l-6 6 6 6", width = 2.2f)
    val Undo = stroke("undo", "M9 14L4 9l5-5", "M4 9h11a5 5 0 0 1 0 10h-3")
    val Redo = stroke("redo", "M15 14l5-5-5-5", "M20 9H9a5 5 0 0 0 0 10h3")
    val Reading = stroke("reading", "M3 5.5C5.5 4 8.5 4 11 5.5V20c-2.5-1.5-5.5-1.5-8 0z", "M21 5.5C18.5 4 15.5 4 13 5.5V20c2.5-1.5 5.5-1.5 8 0z")
    val Export = stroke("export", "M12 15V3", "M7 8l5-5 5 5", "M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6", width = 2.2f)
    val More = stroke("more", filled = listOf(dot(12f, 5f), dot(12f, 12f), dot(12f, 19f)))
    val Close = stroke("close", "M6 6l12 12M18 6L6 18", width = 2.2f)
    val Check = stroke("check", "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z", "M8 12.5l2.6 2.6L16 9.6", width = 2.2f)
    val Pages = stroke("pages", "M9 3h10a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M4 7v12a2 2 0 0 0 2 2h9")
    val Plus = stroke("plus", "M12 5v14M5 12h14", width = 2.4f)
    val Search = stroke("search", "M11 4a7 7 0 1 1 0 14a7 7 0 1 1 0-14z", "M20 20l-4-4")
    val Sync = stroke("sync", "M6 3v12", "M18 9a3 3 0 1 0 0-6a3 3 0 0 0 0 6z", "M6 21a3 3 0 1 0 0-6a3 3 0 0 0 0 6z", "M18 9a9 9 0 0 1-9 9")
    val ReadingOrder = stroke("reading order", "M8 6h12M8 12h12M8 18h12", filled = listOf(dot(4f, 6f, 1.2f), dot(4f, 12f, 1.2f), dot(4f, 18f, 1.2f)))
    val ChevronRight = stroke("chevron right", "M9 6l6 6-6 6", width = 2.2f)
    val ChevronDown = stroke("chevron down", "M6 9l6 6 6-6", width = 2.2f)
    val ChevronUp = stroke("chevron up", "M6 15l6-6 6 6", width = 2.2f)
    val Grip = stroke("grip", filled = listOf(dot(9f, 6f, 1.6f), dot(15f, 6f, 1.6f), dot(9f, 12f, 1.6f), dot(15f, 12f, 1.6f), dot(9f, 18f, 1.6f), dot(15f, 18f, 1.6f)))
    val GripHorizontal = stroke("grip horizontal", filled = listOf(dot(6f, 9f, 1.6f), dot(12f, 9f, 1.6f), dot(18f, 9f, 1.6f), dot(6f, 15f, 1.6f), dot(12f, 15f, 1.6f), dot(18f, 15f, 1.6f)))
    val Edit = stroke("edit", "M4 20l4-1 11-11-3-3L5 16z", "M14 6l3 3")

    // The pens and tools, as in the floating pen bar.
    val MathPen = stroke("math pen", "M17 5H6l6.5 7L6 19h11", width = 2.2f)
    val TextPen = stroke("text pen", "M5 6h14M12 6v13M9 19h6", width = 2.2f)
    val DiagramPen = stroke("diagram pen", "M3 8h6v8H3zM15 8h6v8h-6zM9 12h6", width = 2.2f)
    val DrawPen = stroke("drawing pen", "M4 20c3 0 5-1.5 5-4.5L17.5 7a2.1 2.1 0 0 0-3-3L6 12.5C3 12.5 4 20 4 20z", width = 2.2f)
    val ScratchPen = stroke("scratch pen", "M3 15c3-7 5 7 8 0s5 7 8 0", width = 2.2f)
    val Eraser = stroke("eraser", "M7 20h11M5 15l8.5-8.5 5.5 5.5L13 18H8z")
    val Lasso = stroke("lasso", "M12 4c5 0 9 2.7 9 6s-4 6-9 6-9-2.7-9-6 4-6 9-6z", "M7 15.5c-1.5 2-0.5 4.5 2 4.5")
    val Move = stroke("move", "M12 3v18M3 12h18", "M9 6l3-3 3 3M9 18l3 3 3-3M6 9l-3 3 3 3M18 9l3 3-3 3")
    val Finger = stroke(
        "finger",
        "M9 13V6.5a1.5 1.5 0 0 1 3 0V11m0-5.5a1.5 1.5 0 0 1 3 0V11m0-3.5a1.5 1.5 0 0 1 3 0V14c0 4-2.8 7-6.5 7-3 0-4.6-1.6-5.8-4l-1.6-3.2a1.5 1.5 0 0 1 2.6-1.5L9 14",
    )

    // Selection actions.
    val Copy = stroke("copy", "M9 9h10v10H9z", "M5 15V5h10")
    val Paste = stroke("paste", "M9 4h6v3H9z", "M9 5H6v15h12V5h-3")
    val Clipboard = stroke("clipboard", "M9 4h6v3H9z", "M9 5H6v15h12V5h-3", "M9 11h6M9 15h6")
    val Folder = stroke("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Pin = stroke("pin", "M9 4h6l-1 7 3 3v1H7v-1l3-3z", "M12 15v6")
    val Delete = stroke("delete", "M5 7h14", "M10 11v6M14 11v6", "M7 7l1 12h8l1-12", "M10 7V4h4v3")
    val Convert = stroke("convert", "M4 12h12", "M12 6l6 6-6 6", width = 2.2f)
    val Region = stroke("region", "M4 4h6M14 4h6M4 20h6M14 20h6M4 4v6M4 14v6M20 4v6M20 14v6")
}
