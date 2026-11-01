package io.github.iamthebarrow.nibtexa.core.maths

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** Reading equations (Phase 24), each checked at many points against the same formula written directly in Kotlin. */
class EquationTest {
    private val xs = listOf(-3.7, -2.0, -1.0, -0.5, -0.1, 0.1, 0.5, 1.0, 1.5, 2.0, 3.3, 7.0)

    private fun check(latex: String, values: Map<String, Double> = emptyMap(), angle: AngleUnit = AngleUnit.RADIANS, expected: (Double) -> Double) {
        val f = Equation.parse(latex).function(values, angle)
        for (x in xs) {
            val want = expected(x)
            val got = f(x)
            if (want.isNaN()) {
                assertTrue("$latex at $x: expected undefined, got $got", got.isNaN())
            } else {
                assertEquals("$latex at $x", want, got, 1e-9 * maxOf(1.0, abs(want)))
            }
        }
    }

    @Test
    fun arithmeticAndPrecedence() {
        check("y=x+1") { it + 1 }
        check("y=2x+3") { 2 * it + 3 }
        check("y=x-2-1") { it - 3 }
        check("y=2*x/4") { it / 2 }
        check("y=1/2x") { 0.5 * it }
        check("y=x\\cdot 3") { it * 3 }
        check("y=x\\times 3") { it * 3 }
        check("y=x\\div 2") { it / 2 }
        check("y=-x^2") { -(it * it) }
        check("y=(-x)^2") { it * it }
        check("y=2^x") { 2.0.pow(it) }
        check("y=2^-x") { 2.0.pow(-it) }
        check("y=x^2+2x+1") { it * it + 2 * it + 1 }
        check("y=3-x^{2}") { 3 - it * it }
        check("y=2^{x^2}") { 2.0.pow(it * it) }
        check("y=+x") { it }
        check("y=--x") { it }
        check("y=x*-2") { -2 * it }
    }

    @Test
    fun whatIsWrittenSideBySideMultiplies() {
        check("y=2x") { 2 * it }
        check("y=x2") { 2 * it }
        check("y=(x+1)(x-1)") { (it + 1) * (it - 1) }
        check("y=2(x+1)") { 2 * (it + 1) }
        check("y=(x+1)2") { 2 * (it + 1) }
        check("y=x(x+1)") { it * (it + 1) }
        check("y=2\\pi x") { 2 * PI * it }
        check("y=x^2 x") { it * it * it }
        check("y=2x^2") { 2 * it * it }
        check("y=x\\sin x") { it * sin(it) }
        check("y=2\\sin x") { 2 * sin(it) }
        check("y=ax", mapOf("a" to 3.0)) { 3 * it }
        check("y=abx", mapOf("a" to 3.0, "b" to 2.0)) { 6 * it }
    }

    @Test
    fun fractionsRootsAndDigits() {
        check("y=\\frac{1}{x}") { 1 / it }
        check("y=\\dfrac{x+1}{x-5}") { (it + 1) / (it - 5) }
        check("y=\\frac12 x") { 0.5 * it }
        check("y=\\frac1x") { 1 / it }
        check("y=\\frac{1}{\\frac{1}{x}+1}") { 1 / (1 / it + 1) }
        check("y=\\sqrt{x}") { sqrt(it) }
        check("y=\\sqrt x") { sqrt(it) }
        check("y=\\sqrt2x") { sqrt(2.0) * it }
        check("y=\\sqrt{x^2+1}") { sqrt(it * it + 1) }
        check("y=\\sqrt(x)") { sqrt(it) }
        check("y=\\sqrt(x+1)2") { sqrt(it + 1) * 2 }
        check("y=\\sqrt[3]{x}") { cbrt(it) }
        check("y=\\sqrt[4]{x}") { it.pow(0.25) }
        check("y=x^{1/3}") { cbrt(it) }
        check("y=x^{2/3}") { cbrt(it * it) }
        check("y=x^{0.5}") { it.pow(0.5) }
        // As LaTeX shows them: only the first digit is raised.
        check("y=x^23") { it * it * 3 }
        check("y=x^{23}") { it.pow(23) }
        check("y=1.5x") { 1.5 * it }
        check("y=.5x") { 0.5 * it }
    }

    @Test
    fun functionsTakeWhatFollowsAsWrittenOnThePage() {
        check("y=\\sin x") { sin(it) }
        check("y=\\sin(x)") { sin(it) }
        check("y=\\sin 2x") { sin(2 * it) }
        check("y=\\sin x+1") { sin(it) + 1 }
        check("y=\\sin(x)+1") { sin(it) + 1 }
        check("y=\\sin(x)x") { sin(it) * it }
        check("y=\\sin x\\cos x") { sin(it) * cos(it) }
        check("y=\\sin x/2") { sin(it) / 2 }
        check("y=\\sin\\frac{x}{2}") { sin(it / 2) }
        check("y=\\sin -x") { sin(-it) }
        check("y=\\sin^2 x") { sin(it).pow(2) }
        check("y=\\sin^{2}(x)") { sin(it).pow(2) }
        check("y=\\sin(x)^2") { sin(it).pow(2) }
        check("y=\\sin^{-1}x") { asin(it) }
        check("y=\\tan^{-1}x") { atan(it) }
        check("y=\\cos(2\\pi x)") { cos(2 * PI * it) }
        check("y=\\tan x") { tan(it) }
        check("y=\\cot x") { 1 / tan(it) }
        check("y=\\sec x") { 1 / cos(it) }
        check("y=\\csc x") { 1 / sin(it) }
        check("y=\\arctan x") { atan(it) }
        check("y=\\sinh x") { sinh(it) }
        check("y=\\cosh x") { cosh(it) }
        check("y=\\operatorname{sin}x") { sin(it) }
        check("y=\\mathrm{sin}(x)") { sin(it) }
    }

    @Test
    fun logsAndExponentials() {
        check("y=e^x") { E.pow(it) }
        check("y=e^{-x}") { E.pow(-it) }
        check("y=\\mathrm{e}^{-x^2}") { E.pow(-it * it) }
        check("y=\\exp(x)") { exp(it) }
        check("y=\\ln x") { ln(it) }
        check("y=\\ln(x^2+1)") { ln(it * it + 1) }
        check("y=\\log x") { log10(it) }
        check("y=\\log_{2} x") { log2(it) }
        check("y=\\log_2(x)") { log2(it) }
        check("y=\\log_{10}x") { log10(it) }
        check("y=\\pi") { PI }
        check("y=π x") { PI * it }
    }

    @Test
    fun absoluteValuesAndRounding() {
        check("y=|x|") { abs(it) }
        check("y=2|x|") { 2 * abs(it) }
        check("y=|x|+1") { abs(it) + 1 }
        check("y=|x||x-1|") { abs(it) * abs(it - 1) }
        check("y=||x|-1|") { abs(abs(it) - 1) }
        check("y=\\left|x\\right|") { abs(it) }
        check("y=\\lvert x\\rvert") { abs(it) }
        check("y=\\lfloor x\\rfloor") { floor(it) }
        check("y=\\lceil x\\rceil") { ceil(it) }
    }

    @Test
    fun lookIsIgnoredAndBracketsAreBrackets() {
        check("\\[y=x^2\\]") { it * it }
        check("$$ y = x ^ { 2 } $$") { it * it }
        check("y=\\left(x+1\\right)^{2}") { (it + 1).pow(2) }
        check("y=\\left[x+1\\right]") { it + 1 }
        check("y=\\{x+1\\}") { it + 1 }
        check("y=\\displaystyle\\frac{1}{x}") { 1 / it }
        check("y=x\\,x\\;x\\!x\\quad x") { it.pow(5) }
        check("y={x+1}^2") { (it + 1).pow(2) }
        check("y=\\bigl(x\\bigr)") { it }
        check("y=x−1") { it - 1 }
    }

    @Test
    fun formsOfEquation() {
        fun parts(latex: String) = Equation.parse(latex).let { Triple(it.output, it.input, it.parameters) }
        assertEquals(Triple("y", "x", emptyList<String>()), parts("y=x^2"))
        assertEquals(Triple("y", "x", emptyList<String>()), parts("x^2"))
        assertEquals(Triple("f", "x", emptyList<String>()), parts("f(x)=x^2"))
        assertEquals(Triple("g", "t", emptyList<String>()), parts("g(t)=\\sin t"))
        assertEquals(Triple("H", "s", emptyList<String>()), parts("H(s)=\\frac{1}{s^2+2s+5}"))
        assertEquals(Triple("y", "x", emptyList<String>()), parts("x^2=y"))
        assertEquals(Triple("y", "x", emptyList<String>()), parts("y:=x"))
        assertEquals(Triple("y", "x", emptyList<String>()), parts("y=5"))
        assertEquals(Triple("v", "t", listOf("v_{0}", "a")), parts("v=v_0+at"))
        assertEquals(Triple("y", "x", listOf("k")), parts("y=kx"))
        assertEquals(Triple("V", "\\omega", listOf("R", "C")), parts("V(\\omega)=\\frac{1}{1+\\omega RC}"))
        assertEquals(Triple("y", "\\theta", emptyList<String>()), parts("y=\\sin\\theta"))
        assertEquals(Triple("A", "r", emptyList<String>()), parts("A=\\pi r^2"))
        assertEquals(Triple("y", "x", listOf("\\hat{n}", "R_{th}")), parts("y=\\hat{n}R_{th}x"))
        // The named argument is the axis even if x is also there.
        assertEquals(Triple("f", "t", listOf("x")), parts("f(t)=xt"))
    }

    @Test
    fun theUserCanChooseTheAxisWhenItIsntClear() {
        val e = Equation.parse("y=a+2b", input = "b")
        assertEquals("b", e.input)
        assertEquals(listOf("a"), e.parameters)
        assertEquals(7.0, e.function(mapOf("a" to 1.0))(3.0), 1e-9)
        assertEquals("a", Equation.parse("a^2+b", input = "a").input)
    }

    @Test
    fun functionNamesWithoutTheBackslashAreStillFunctions() {
        check("y=sin(x)") { sin(it) }
        check("y=2cos x") { 2 * cos(it) }
        check("y=log_2 x") { log2(it) }
        // A letter or two stays as variables, since `ln` could be l times n.
        assertEquals(listOf("l"), Equation.parse("y=lnx").parameters.take(1))
    }

    @Test
    fun theAngleUnitAppliesToTrigonometry() {
        check("y=\\sin x", angle = AngleUnit.DEGREES) { sin(it * PI / 180) }
        check("y=\\arctan x", angle = AngleUnit.DEGREES) { atan(it) * 180 / PI }
        check("y=\\sin^{-1}x", angle = AngleUnit.DEGREES) { asin(it) * 180 / PI }
        // Not to anything else.
        check("y=\\sinh x", angle = AngleUnit.DEGREES) { sinh(it) }
        check("y=\\ln x", angle = AngleUnit.DEGREES) { ln(it) }
    }

    @Test
    fun constantsNeedValuesAndSayWhich() {
        val e = Equation.parse("y=kx+b")
        assertEquals(listOf("k", "b"), e.parameters)
        assertEquals(listOf("k", "b"), e.missing(emptyMap()))
        assertEquals(listOf("b"), e.missing(mapOf("k" to 1.0)))
        assertThrows(IllegalArgumentException::class.java) { e.function(mapOf("k" to 1.0)) }
        check("y=kx+b", mapOf("k" to 2.0, "b" to -1.0)) { 2 * it - 1 }
    }

    private fun problem(latex: String) = assertThrows(MathsException::class.java) { Equation.parse(latex) }.problem

    @Test
    fun whatItCantReadItSays() {
        assertEquals(MathsProblem.Implicit, problem("x^2+y^2=1"))
        assertEquals(MathsProblem.Implicit, problem("y=x+y"))
        assertEquals(MathsProblem.Implicit, problem("x=5"))
        assertEquals(MathsProblem.SeveralEquals, problem("y=x=2"))
        assertEquals(MathsProblem.AmbiguousInput(listOf("a", "b")), problem("y=a+b"))
        assertEquals(MathsProblem.NotUnderstood("\\int"), problem("y=\\int x"))
        assertEquals(MathsProblem.NotUnderstood("\\sum"), problem("y=\\sum_{n=1}^{5} n"))
        assertEquals(MathsProblem.NotUnderstood("\\infty"), problem("y=\\infty"))
        assertEquals(MathsProblem.NotUnderstood("\\begin"), problem("\\begin{matrix}1\\end{matrix}"))
        assertEquals(MathsProblem.NotUnderstood("\\input"), problem("y=\\input{x}"))
        assertEquals(MathsProblem.NotUnderstood("!"), problem("y=x!"))
        assertEquals(MathsProblem.NotUnderstood(","), problem("y=x,2"))
        assertTrue(problem("y=(x+1") is MathsProblem.Malformed)
        assertTrue(problem("y=x+1)") is MathsProblem.Malformed)
        assertTrue(problem("y=2+") is MathsProblem.Malformed)
        assertTrue(problem("y=") is MathsProblem.Malformed)
        assertTrue(problem("") is MathsProblem.Malformed)
        assertTrue(problem("y=\\frac{1") is MathsProblem.Malformed)
        assertTrue(problem("y=\\sin") is MathsProblem.Malformed)
        assertTrue(problem("y=|x") is MathsProblem.Malformed)
        assertTrue(problem("\\") is MathsProblem.Malformed)
    }

    @Test
    fun hostileInputFailsCleanlyAndQuickly() {
        val started = System.nanoTime()
        assertEquals(MathsProblem.TooComplex, problem("y=" + "(".repeat(500) + "x" + ")".repeat(500)))
        assertEquals(MathsProblem.TooComplex, problem("y=" + "\\sqrt{".repeat(300) + "x" + "}".repeat(300)))
        assertEquals(MathsProblem.TooComplex, problem("y=" + "-".repeat(3000) + "x"))
        assertEquals(MathsProblem.TooComplex, problem("y=" + "x+".repeat(3000) + "x"))
        assertEquals(MathsProblem.TooComplex, problem("y=" + "1".repeat(100_000)))
        assertEquals(MathsProblem.TooComplex, problem("y=" + "x^".repeat(800) + "x"))
        // Within the limits, a long sum still works.
        val long = Equation.parse("y=" + "x+".repeat(600) + "x").function()
        assertEquals(601.0, long(1.0), 1e-9)
        // Random bytes never get past the checks as anything but a problem.
        val random = java.util.Random(7)
        val alphabet = "xy+-*/^(){}[]|\\=_0123456789.,sin frac sqrt left right ^{ _{ \\frac \\sin \\sqrt \\left \\right "
        repeat(5000) {
            val source = String(CharArray(random.nextInt(60)) { alphabet[random.nextInt(alphabet.length)] })
            try {
                val e = Equation.parse(source)
                val values = e.parameters.associateWith { 1.0 }
                e.function(values)(0.5)
            } catch (_: MathsException) {
                // Expected for most of them.
            }
        }
        assertTrue("took too long", (System.nanoTime() - started) / 1_000_000 < 15_000)
    }

    @Test
    fun aThousandPointsAreFastEnoughToRedrawWhileZooming() {
        val f = Equation.parse("y=\\frac{\\sin(x)e^{-x^2}+\\sqrt{x^2+1}}{\\ln(x^2+2)}").function()
        val started = System.nanoTime()
        repeat(50) { Plot.sample(f, Range(-10.0, 10.0), Range(-5.0, 5.0)) }
        val each = (System.nanoTime() - started) / 50 / 1_000_000.0
        assertTrue("$each ms each", each < 16)
    }
}
