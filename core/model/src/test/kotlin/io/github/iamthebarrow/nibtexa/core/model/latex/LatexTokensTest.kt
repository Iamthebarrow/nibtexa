package io.github.iamthebarrow.nibtexa.core.model.latex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

// Mirrors tools/recognition-eval/tests/test_harness.py so both normalizers keep the same rules.
class LatexTokensTest {
    private fun n(latex: String) = LatexTokens.normalize(latex)

    @Test
    fun tokenizesCommandsAndSymbols() {
        assertEquals(listOf("\\frac", "{", "a", "}", "{", "b", "}", "+", "\\alpha"), LatexTokens.tokenize("""\frac{a}{b}+\alpha"""))
    }

    @Test
    fun presentationalDifferencesNormalizeEqual() {
        assertEquals(n("x^{2}"), n("x^2"))
        assertEquals(n("(x)"), n("""\left( x \right)"""))
        assertEquals(n("""\frac{1}{2}"""), n("""\dfrac{1}{2}"""))
        assertEquals(n("(x+y)^2"), n("( x + y )^{2}"))
        assertEquals(n("2sin(x)"), n("""2\sin ( x )"""))
        assertEquals(n("""lim_{x\rightarrow 0}"""), n("""\lim_{x\to0}"""))
        assertEquals(n("""\hbar\omega_{p}"""), n("""\[\hbar \omega_p\]"""))
        assertEquals(n("""\vec{0}"""), n("""\overrightarrow{0}"""))
    }

    @Test
    fun contentDifferencesDoNotNormalizeEqual() {
        assertNotEquals(n("x_{2}"), n("x^{2}"))
        assertNotEquals(n("""\int_{1}^{1}"""), n("""\int_{-1}^{1}"""))
    }

    @Test
    fun editDistanceCountsTokenEdits() {
        assertEquals(0, LatexTokens.editDistance(listOf("a", "b", "c"), listOf("a", "b", "c")))
        assertEquals(1, LatexTokens.editDistance(listOf("a", "b", "c"), listOf("a", "b", "d")))
        assertEquals(2, LatexTokens.editDistance(emptyList(), listOf("a", "b")))
    }
}
