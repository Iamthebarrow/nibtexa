package io.github.iamthebarrow.nibtexa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole

/**
 * NibTeXa's own colours (Phase 13, palette A: warm paper with teal and coral), light and dark.
 * The system's dynamic colour is not used: the app has one look everywhere.
 *
 * Each pen has a colour, used wherever its role appears: the pen button, the ink, the tint of
 * its regions, its result cards and chips.
 */
@Immutable
data class NibPalette(
    val dark: Boolean,
    val ground: Color,
    val header: Color,
    val surface: Color,
    val card: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val primaryInk: Color,
    val accent: Color,
    val onAccent: Color,
    val inverse: Color,
    val onInverse: Color,
    val paper: Color,
    val rule: Color,
    val math: Color,
    val text: Color,
    val drawing: Color,
    val scratch: Color,
    val mathTint: Color,
    val textTint: Color,
    val drawTint: Color,
    val mathChip: Color,
    val textChip: Color,
    val drawChip: Color,
    val error: Color,
    /** Booklet cover colours, by [io.github.iamthebarrow.nibtexa.storage.BookletSummary.cover]. */
    val covers: List<Color>,
) {
    fun penColor(pen: Pen): Color = when (pen) {
        Pen.MATH -> math
        Pen.TEXT -> text
        Pen.DRAWING -> drawing
        Pen.SCRATCH -> scratch
    }

    fun roleColor(role: RegionRole): Color = when (role) {
        RegionRole.MATH -> math
        RegionRole.TEXT, RegionRole.HEADING -> text
        RegionRole.FIGURE -> drawing
        RegionRole.EXCLUDED -> scratch
    }

    fun roleChip(role: RegionRole): Color = when (role) {
        RegionRole.MATH -> mathChip
        RegionRole.TEXT, RegionRole.HEADING -> textChip
        RegionRole.FIGURE -> drawChip
        RegionRole.EXCLUDED -> textChip
    }

    fun roleTint(role: RegionRole): Color = when (role) {
        RegionRole.MATH -> mathTint
        RegionRole.TEXT, RegionRole.HEADING -> textTint
        RegionRole.FIGURE -> drawTint
        RegionRole.EXCLUDED -> Color.Transparent
    }

    fun cover(index: Int): Color = covers[index.mod(covers.size)]
}

val LightPalette = NibPalette(
    dark = false,
    ground = Color(0xFFFBF7F1), header = Color(0xFFE6F0EC), surface = Color(0xFFFFFFFF), card = Color(0xFFFFFCF7),
    ink = Color(0xFF2B2A28), muted = Color(0xFF5F594F), line = Color(0xFFE4DCCF),
    primary = Color(0xFF2F7A74), onPrimary = Color(0xFFFFFFFF), primarySoft = Color(0xFFDCEFEC), primaryInk = Color(0xFF1F5A55),
    accent = Color(0xFFC2553B), onAccent = Color(0xFFFFFFFF), inverse = Color(0xFF2B2A28), onInverse = Color(0xFFFBF7F1),
    paper = Color(0xFFFFFDF9), rule = Color(0xFFE3EAF0),
    math = Color(0xFF2F63A8), text = Color(0xFF3B3A38), drawing = Color(0xFF2F7D4C), scratch = Color(0xFF8F897F),
    mathTint = Color(0x172F63A8), textTint = Color(0x0F3B3A38), drawTint = Color(0x172F7D4C),
    mathChip = Color(0xFFE4ECF8), textChip = Color(0xFFEFECE7), drawChip = Color(0xFFE2F0E6),
    error = Color(0xFFB3261E),
    covers = listOf(Color(0xFFEDB9A5), Color(0xFFA9D3CC), Color(0xFFF1D59E), Color(0xFFCBBCE5), Color(0xFFBBD1EA), Color(0xFFD9C6AA)),
)

val DarkPalette = NibPalette(
    dark = true,
    ground = Color(0xFF1C1B19), header = Color(0xFF22302D), surface = Color(0xFF2A2926), card = Color(0xFF302E2B),
    ink = Color(0xFFEEE9E1), muted = Color(0xFFB5AEA2), line = Color(0xFF45423C),
    primary = Color(0xFF73C4BA), onPrimary = Color(0xFF10211F), primarySoft = Color(0xFF2C4542), primaryInk = Color(0xFFA7E0D8),
    accent = Color(0xFFF09A82), onAccent = Color(0xFF2A140D), inverse = Color(0xFFEEE9E1), onInverse = Color(0xFF1C1B19),
    paper = Color(0xFF232220), rule = Color(0xFF33312D),
    math = Color(0xFF93B7EE), text = Color(0xFFDAD5CD), drawing = Color(0xFF8BCB9F), scratch = Color(0xFF9C958A),
    mathTint = Color(0x1A93B7EE), textTint = Color(0x0FDAD5CD), drawTint = Color(0x1A8BCB9F),
    mathChip = Color(0xFF33405A), textChip = Color(0xFF3A3834), drawChip = Color(0xFF2F4537),
    error = Color(0xFFF2B8B5),
    covers = listOf(Color(0xFF7A5245), Color(0xFF3E6560), Color(0xFF74613A), Color(0xFF584B70), Color(0xFF40577A), Color(0xFF655645)),
)

val LocalNibPalette = staticCompositionLocalOf { LightPalette }

/** The current [NibPalette]: `Nib.palette.math` and so on. */
object Nib {
    val palette: NibPalette
        @Composable get() = LocalNibPalette.current
}

/** Nunito (SIL Open Font License 1.1, third_party/nunito), one variable font for every weight. */
private val Nunito = FontFamily(
    listOf(400, 600, 700, 800).map { w ->
        Font(R.font.nunito, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private fun TextStyle.nunito(weight: Int? = null) = copy(fontFamily = Nunito, fontWeight = weight?.let(::FontWeight) ?: fontWeight)

private val NibTypography = Typography().run {
    copy(
        displayLarge = displayLarge.nunito(800), displayMedium = displayMedium.nunito(800), displaySmall = displaySmall.nunito(800),
        headlineLarge = headlineLarge.nunito(800), headlineMedium = headlineMedium.nunito(800), headlineSmall = headlineSmall.nunito(800),
        titleLarge = titleLarge.nunito(800), titleMedium = titleMedium.nunito(800), titleSmall = titleSmall.nunito(700),
        bodyLarge = bodyLarge.nunito(600), bodyMedium = bodyMedium.nunito(600), bodySmall = bodySmall.nunito(600),
        labelLarge = labelLarge.nunito(800), labelMedium = labelMedium.nunito(800), labelSmall = labelSmall.nunito(800),
    )
}

private val NibShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun NibTeXaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (darkTheme) DarkPalette else LightPalette
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = p.primary, onPrimary = p.onPrimary, primaryContainer = p.primarySoft, onPrimaryContainer = p.primaryInk,
            secondary = p.accent, onSecondary = p.onAccent, secondaryContainer = p.primarySoft, onSecondaryContainer = p.primaryInk,
            tertiary = p.math, background = p.ground, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
            surfaceVariant = p.card, onSurfaceVariant = p.muted, surfaceContainer = p.surface, surfaceContainerHigh = p.card,
            surfaceContainerLow = p.ground, surfaceContainerHighest = p.card, outline = p.muted, outlineVariant = p.line,
            inverseSurface = p.inverse, inverseOnSurface = p.onInverse, error = p.error,
        )
    } else {
        lightColorScheme(
            primary = p.primary, onPrimary = p.onPrimary, primaryContainer = p.primarySoft, onPrimaryContainer = p.primaryInk,
            secondary = p.accent, onSecondary = p.onAccent, secondaryContainer = p.primarySoft, onSecondaryContainer = p.primaryInk,
            tertiary = p.math, background = p.ground, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
            surfaceVariant = p.card, onSurfaceVariant = p.muted, surfaceContainer = p.surface, surfaceContainerHigh = p.card,
            surfaceContainerLow = p.ground, surfaceContainerHighest = p.card, outline = p.muted, outlineVariant = p.line,
            inverseSurface = p.inverse, inverseOnSurface = p.onInverse, error = p.error,
        )
    }
    CompositionLocalProvider(LocalNibPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = NibTypography, shapes = NibShapes, content = content)
    }
}
