package io.github.iamthebarrow.nibtexa.ink

import io.github.iamthebarrow.nibtexa.core.model.notebook.TextColor

/**
 * A text colour as it shows on the app's paper, as RGB, or null for the ink itself. The colours are LaTeX's, for white paper,
 * and some are hard to read on the app's: blue on dark, yellow on light. So those show lighter or darker here. Exports keep
 * the real colour.
 */
fun TextColor.onPaper(dark: Boolean): Int? = when (this) {
    TextColor.BLACK -> if (dark) null else rgb
    TextColor.WHITE -> if (dark) rgb else null
    TextColor.RED -> if (dark) 0xFF7B72 else 0xD32F2F
    TextColor.GREEN -> if (dark) rgb else 0x1B7F37
    TextColor.BLUE -> if (dark) 0x8AB4F8 else rgb
    TextColor.CYAN -> if (dark) rgb else 0x00798C
    TextColor.MAGENTA -> if (dark) 0xF27CF2 else 0xB100B1
    TextColor.YELLOW -> if (dark) rgb else 0x8A6D00
}
