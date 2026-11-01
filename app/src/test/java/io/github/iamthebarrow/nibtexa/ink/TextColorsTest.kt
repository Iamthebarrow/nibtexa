package io.github.iamthebarrow.nibtexa.ink

import io.github.iamthebarrow.nibtexa.core.model.notebook.TextColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class TextColorsTest {
    private fun luminance(rgb: Int): Double {
        fun channel(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel(rgb shr 16 and 0xFF) + 0.7152 * channel(rgb shr 8 and 0xFF) + 0.0722 * channel(rgb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }

    /** The accessibility guideline for normal text is 4.5. */
    @Test
    fun everyColourCanBeReadOnBothPapers() {
        for (color in TextColor.entries) {
            color.onPaper(dark = true)?.let { assertTrue("$color on dark: ${contrast(it, InkColors.DARK.paper and 0xFFFFFF)}", contrast(it, InkColors.DARK.paper and 0xFFFFFF) >= 4.5) }
            color.onPaper(dark = false)?.let { assertTrue("$color on light: ${contrast(it, InkColors.LIGHT.paper and 0xFFFFFF)}", contrast(it, InkColors.LIGHT.paper and 0xFFFFFF) >= 4.5) }
        }
    }

    @Test
    fun aColourTheSameAsThePaperShowsAsTheInk() {
        assertNull(TextColor.BLACK.onPaper(dark = true))
        assertNull(TextColor.WHITE.onPaper(dark = false))
        assertEquals(TextColor.BLACK.rgb, TextColor.BLACK.onPaper(dark = false))
    }
}
