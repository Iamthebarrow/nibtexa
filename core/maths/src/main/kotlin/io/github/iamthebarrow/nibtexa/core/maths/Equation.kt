package io.github.iamthebarrow.nibtexa.core.maths

import kotlin.math.PI

/** Why an equation can't be graphed, in terms the app can explain to the user. */
sealed interface MathsProblem {
    /** It uses something this engine doesn't read: [what] is the command or character, as written. */
    data class NotUnderstood(val what: String) : MathsProblem

    /** The source doesn't form an expression, such as an unmatched bracket or `2+`. */
    data class Malformed(val detail: String) : MathsProblem

    /** More than one side uses variables, as in `x^2+y^2=1`, which isn't y as a function of x. */
    data object Implicit : MathsProblem

    /** There is nothing to choose as the horizontal axis among [names]. */
    data class AmbiguousInput(val names: List<String>) : MathsProblem

    /** More than one `=`. */
    data object SeveralEquals : MathsProblem

    /** Too long or too deeply nested to read safely. */
    data object TooComplex : MathsProblem
}

class MathsException(val problem: MathsProblem) : Exception(problem.toString())

enum class AngleUnit { RADIANS, DEGREES }

/**
 * An equation ready to graph: [output] as a function of [input], with [parameters] needing values from the
 * user. Names are as written, such as `x`, `\omega` or `R_{th}`.
 */
class Equation internal constructor(
    val output: String,
    val input: String,
    val parameters: List<String>,
    private val body: Node,
) {
    /**
     * The function for these [values] of its [parameters] and [angle] unit. Each call gives a function that can
     * be called many times; undefined results are NaN or infinite.
     *
     * @throws IllegalArgumentException if a parameter has no value: check [missing] first.
     */
    fun function(values: Map<String, Double> = emptyMap(), angle: AngleUnit = AngleUnit.RADIANS): (Double) -> Double {
        val absent = missing(values)
        require(absent.isEmpty()) { "No value for ${absent.joinToString()}" }
        val compiled = body.compile(Scope(input, values, angle))
        val cell = DoubleArray(1)
        return { x ->
            cell[0] = x
            compiled(cell)
        }
    }

    /** The parameters [values] doesn't give, in the order they appear. */
    fun missing(values: Map<String, Double>): List<String> = parameters.filterNot(values::containsKey)

    companion object {
        /**
         * Reads an equation from LaTeX: `y=...`, `f(x)=...`, `...=y`, or an expression alone. The horizontal axis
         * is the named argument, or else `x`, `t`, `s`, `\theta`, `\omega`, `n` or `u`, or the only variable.
         *
         * Nothing in the source is run. A command this doesn't know is a [MathsProblem], not ignored.
         *
         * @param input the horizontal axis, when the user has chosen it after a [MathsProblem.AmbiguousInput].
         * @throws MathsException if it can't be graphed.
         */
        fun parse(latex: String, input: String? = null): Equation {
            val tokens = Lexer(latex).tokens()
            val sides = split(tokens)
            if (sides.size > 2) throw MathsException(MathsProblem.SeveralEquals)
            if (sides.size == 1) return fromBody("y", input, Parser(sides[0]).parse())

            functionForm(sides[0])?.let { (name, argument) -> return fromBody(name, argument, Parser(sides[1]).parse()) }
            val left = Parser(sides[0]).parse()
            val right = Parser(sides[1]).parse()
            val leftName = (left as? Node.Variable)?.name
            val rightName = (right as? Node.Variable)?.name
            return when {
                leftName != null && !right.variables().contains(leftName) -> {
                    if (right.variables().isEmpty() && leftName in VERTICAL) throw MathsException(MathsProblem.Implicit)
                    fromBody(leftName, input, right)
                }
                rightName != null && !left.variables().contains(rightName) -> fromBody(rightName, input, left)
                else -> throw MathsException(MathsProblem.Implicit)
            }
        }

        private val VERTICAL = setOf("x")
        private val INPUTS = listOf("x", "t", "s", "\\theta", "\\omega", "n", "u")

        private fun split(tokens: List<Token>): List<List<Token>> {
            val sides = mutableListOf(mutableListOf<Token>())
            for (t in tokens) if (t is Token.Equals) sides += mutableListOf<Token>() else sides.last() += t
            return sides
        }

        /** `f(x)`: a name, a bracket, one name, a bracket; with nothing else on that side. */
        private fun functionForm(side: List<Token>): Pair<String, String>? {
            val name = side.getOrNull(0) as? Token.Name ?: return null
            if (side.size != 4 || side[1] !is Token.Open || side[3] !is Token.Close) return null
            val argument = side[2] as? Token.Name ?: return null
            return name.text to argument.text
        }

        private fun fromBody(output: String, named: String?, body: Node): Equation {
            val variables = body.variables()
            val input = named ?: INPUTS.firstOrNull { it in variables } ?: when {
                variables.isEmpty() -> "x"
                variables.size == 1 -> variables.single()
                else -> throw MathsException(MathsProblem.AmbiguousInput(variables))
            }
            return Equation(output, input, variables.filter { it != input }, body)
        }
    }
}

// ---- Expressions ---------------------------------------------------------------------------------

internal class Scope(val input: String, val values: Map<String, Double>, val angle: AngleUnit)

private typealias Compiled = (DoubleArray) -> Double

internal sealed interface Node {
    /** The variables used, in the order they first appear. Named constants aren't variables. */
    fun collect(into: MutableSet<String>)
    fun compile(scope: Scope): Compiled
    fun variables(): List<String> = linkedSetOf<String>().also(::collect).toList()

    data class Number(val value: Double) : Node {
        override fun collect(into: MutableSet<String>) = Unit
        override fun compile(scope: Scope): Compiled = { value }
    }

    data class Variable(val name: String) : Node {
        override fun collect(into: MutableSet<String>) {
            into += name
        }

        override fun compile(scope: Scope): Compiled {
            if (name == scope.input) return { it[0] }
            val value = scope.values.getValue(name)
            return { value }
        }
    }

    data class Unary(val op: (Double) -> Double, val a: Node) : Node {
        override fun collect(into: MutableSet<String>) = a.collect(into)
        override fun compile(scope: Scope): Compiled {
            val f = a.compile(scope)
            return { op(f(it)) }
        }
    }

    /** A trigonometric function, which takes an angle in the chosen unit. */
    data class AngleIn(val op: (Double) -> Double, val a: Node) : Node {
        override fun collect(into: MutableSet<String>) = a.collect(into)
        override fun compile(scope: Scope): Compiled {
            val f = a.compile(scope)
            val factor = if (scope.angle == AngleUnit.DEGREES) PI / 180 else 1.0
            return { op(f(it) * factor) }
        }
    }

    /** An inverse trigonometric function, which gives an angle in the chosen unit. */
    data class AngleOut(val op: (Double) -> Double, val a: Node) : Node {
        override fun collect(into: MutableSet<String>) = a.collect(into)
        override fun compile(scope: Scope): Compiled {
            val f = a.compile(scope)
            val factor = if (scope.angle == AngleUnit.DEGREES) 180 / PI else 1.0
            return { op(f(it)) * factor }
        }
    }

    data class Binary(val op: (Double, Double) -> Double, val a: Node, val b: Node) : Node {
        override fun collect(into: MutableSet<String>) {
            a.collect(into)
            b.collect(into)
        }

        override fun compile(scope: Scope): Compiled {
            val f = a.compile(scope)
            val g = b.compile(scope)
            return { op(f(it), g(it)) }
        }
    }
}
