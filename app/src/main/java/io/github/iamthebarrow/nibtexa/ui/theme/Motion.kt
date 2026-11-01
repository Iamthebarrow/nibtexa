package io.github.iamthebarrow.nibtexa.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Small, shared bits of motion (Phase 14), so the app moves the same way everywhere: short,
 * soft and quick to settle. System "remove animations" settings still apply, through Compose.
 */
object Motion {
    /** Each item of a list starts this much after the one before, up to [MAX_STAGGER] items. */
    const val STAGGER_MS = 45L
    const val MAX_STAGGER = 8
    const val ENTER_MS = 320
}

/** Rises a little and fades in the first time it is shown, [index] steps after the first item. */
fun Modifier.entrance(index: Int): Modifier = composed {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(minOf(index, Motion.MAX_STAGGER) * Motion.STAGGER_MS)
        shown.animateTo(1f, tween(Motion.ENTER_MS, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * 24.dp.toPx()
    }
}

/** Sinks a little while pressed and springs back on release. */
fun Modifier.pressScale(interaction: InteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "press")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
