package io.github.iamthebarrow.nibtexa.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Text
import io.github.iamthebarrow.nibtexa.R

/**
 * The NibTeXa mark, the same drawing as the launcher icon (`res/drawable/ic_launcher_foreground.xml`):
 * a hand-drawn integral sign used as a Git branch line, with a commit dot in the middle and a
 * branch splitting off to the right. Drawn in a 108-unit square, like the launcher's layer.
 */
object NibLogo {
    private const val SIZE = 108f

    /**
     * The mark's size in its square. The icon pack drew it at 0.86, which reached the edge of
     * round launcher masks; at 0.64 it sits inside them with a margin, like other icons.
     */
    private const val SCALE = 0.64f

    /** The mark in [mark], its commit dot in [dot], ringed in [ring] (the colour behind it). */
    fun vector(mark: Color, dot: Color, ring: Color): ImageVector =
        ImageVector.Builder("nibtexa logo", SIZE.dp, SIZE.dp, SIZE, SIZE).apply {
            addGroup(pivotX = 54f, pivotY = 54f, scaleX = SCALE, scaleY = SCALE)
            addPath(addPathNodes("M54 54 C64 54 70 58 72 66"), stroke = SolidColor(mark), strokeLineWidth = 4f, strokeLineCap = StrokeCap.Round)
            addPath(addPathNodes("M67.2,69 a4.8,4.8 0 1,0 9.6,0 a4.8,4.8 0 1,0 -9.6,0 Z"), fill = SolidColor(mark))
            addPath(addPathNodes("M70 24 C62 14 47 20 50 36 L58 72 C61 88 46 94 38 84"), stroke = SolidColor(mark), strokeLineWidth = 8f, strokeLineCap = StrokeCap.Round)
            addPath(addPathNodes("M46.5,54 a7.5,7.5 0 1,0 15.0,0 a7.5,7.5 0 1,0 -15.0,0 Z"), fill = SolidColor(dot), stroke = SolidColor(ring), strokeLineWidth = 3.5f)
            clearGroup()
        }.build()

    // The icon's own colours (light and dark), as in the launcher icon.
    private val TILE_LIGHT = Color(0xFF2F7A74)
    private val MARK_LIGHT = Color(0xFFFBF7F1)
    private val MARK_DARK = Color(0xFFEEE9E1)
    private val DOT_LIGHT = Color(0xFFF4A58C)
    private val DOT_DARK = Color(0xFFF09A82)

    /**
     * The app name as the home screen sets it: "Nib" and "a" in Baloo 2, and TeX in a teal serif
     * with its E lowered and the letters drawn together, as LaTeX prints its own name
     * (`T\kern-.1667em\lower.5ex\hbox{E}\kern-.125em X`). Read out as "NibTeXa".
     */
    @Composable
    fun Wordmark(fontSize: TextUnit, modifier: Modifier = Modifier) {
        val p = Nib.palette
        val name = stringResource(R.string.app_name)
        val text = remember(p) {
            val word = SpanStyle(fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, color = p.ink)
            val tex = SpanStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = p.primary)
            buildAnnotatedString {
                withStyle(word.copy(letterSpacing = (-0.01).em)) { append("Nib") }
                withStyle(tex.copy(letterSpacing = (-0.06).em)) { append(" T") }
                withStyle(tex.copy(baselineShift = BaselineShift(-0.22f), letterSpacing = (-0.08).em)) { append("E") }
                withStyle(tex) { append("X") }
                withStyle(word) { append("a") }
            }
        }
        Text(text, fontSize = fontSize, lineHeight = fontSize * 1.15f, maxLines = 1, modifier = modifier.clearAndSetSemantics { contentDescription = name })
    }

    /** Baloo 2 (SIL OFL 1.1, third_party/baloo2), one variable font for every weight. */
    private val Baloo = FontFamily(
        listOf(400, 600, 700, 800).map { w ->
            Font(R.font.baloo2, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )

    /** The app icon as a rounded tile: the library header's logo, teal in both themes so it stands out on the header. */
    @Composable
    fun Tile(modifier: Modifier = Modifier) {
        val dark = Nib.palette.dark
        val tile = TILE_LIGHT
        val image = remember(dark) { vector(if (dark) MARK_DARK else MARK_LIGHT, if (dark) DOT_DARK else DOT_LIGHT, tile) }
        Box(modifier.clip(RoundedCornerShape(27)).background(tile)) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }

    /** The mark alone, in the brand colours, on a [background] of the caller's. */
    @Composable
    fun Mark(background: Color, modifier: Modifier = Modifier) {
        val p = Nib.palette
        val image = remember(p.primary, p.accent, background) { vector(p.primary, p.accent, background) }
        Image(image, contentDescription = null, modifier = modifier)
    }
}
