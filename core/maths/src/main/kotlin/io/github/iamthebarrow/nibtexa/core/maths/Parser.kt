package io.github.iamthebarrow.nibtexa.core.maths

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atanh
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

internal sealed interface Token {
    /** [text] as written, since LaTeX gives a script or `\frac` only the first digit of `23`. */
    data class Number(val value: Double, val text: String) : Token

    /** A variable's name, with any mark and subscript: `x`, `\omega`, `R_{th}`, `\hat{n}`. */
    data class Name(val text: String) : Token

    /** A function or structure command, such as `\sin` or `\frac`. */
    data class Command(val name: String) : Token

    data class Symbol(val char: Char) : Token
    data class RootIndex(val value: Double) : Token
    data object Open : Token
    data object Close : Token
    data object BraceOpen : Token
    data object BraceClose : Token
    data object Bar : Token
    data object BarOpen : Token
    data object BarClose : Token
    data object FloorOpen : Token
    data object FloorClose : Token
    data object CeilOpen : Token
    data object CeilClose : Token
    data object Equals : Token
}

/**
 * Splits LaTeX into tokens, dropping what only affects how it looks: spacing, `\left`, `\right`, `\displaystyle`,
 * and the `\[ \]` or `$` that wrap an equation. Anything it doesn't know is a [MathsProblem.NotUnderstood].
 */
internal class Lexer(private val source: String) {
    private var i = 0
    private val out = mutableListOf<Token>()

    fun tokens(): List<Token> {
        if (source.length > MAX_CHARS) throw MathsException(MathsProblem.TooComplex)
        while (i < source.length) next()
        if (out.size > MAX_TOKENS) throw MathsException(MathsProblem.TooComplex)
        return out
    }

    private fun next() {
        val c = source[i]
        when {
            c.isWhitespace() || c == '$' -> i++
            c == '\\' -> command()
            c.isDigit() || (c == '.' && source.getOrNull(i + 1)?.isDigit() == true) -> number()
            c.isLetter() && c.code < 128 -> plainFunction() ?: run {
                i++
                out += Token.Name(c + subscript())
            }
            else -> symbol(c)
        }
    }

    /** `sin` written without its backslash, as a whole word: not three variables multiplied. */
    private fun plainFunction(): Unit? {
        var end = i
        while (end < source.length && source[end].isLetter()) end++
        val word = source.substring(i, end)
        if (word.length < 3 || word !in FUNCTIONS) return null
        i = end
        out += Token.Command(word)
        logBase(word)
        return Unit
    }

    /** `\log_{2}`: its base follows as a name starting with the subscript, for the parser to pick up. */
    private fun logBase(name: String) {
        if (name == "log") subscript().takeIf { it.isNotEmpty() }?.let { out += Token.Name(it) }
    }

    private fun number() {
        val start = i
        while (i < source.length && source[i].isDigit()) i++
        if (source.getOrNull(i) == '.') {
            i++
            while (i < source.length && source[i].isDigit()) i++
        }
        val value = source.substring(start, i).toDoubleOrNull() ?: throw MathsException(MathsProblem.Malformed("a number"))
        out += Token.Number(value, source.substring(start, i))
    }

    private fun symbol(c: Char) {
        i++
        out += when (c) {
            '(', '[' -> Token.Open
            ')', ']' -> Token.Close
            '{' -> Token.BraceOpen
            '}' -> Token.BraceClose
            '|' -> Token.Bar
            '=' -> Token.Equals
            ':' -> if (source.getOrNull(i) == '=') {
                i++
                Token.Equals
            } else {
                throw MathsException(MathsProblem.NotUnderstood(":"))
            }
            '+', '-', '*', '/', '^' -> Token.Symbol(c)
            '−' -> Token.Symbol('-')
            '×', '·', '⋅' -> Token.Symbol('*')
            '÷' -> Token.Symbol('/')
            'π' -> Token.Name("\\pi")
            else -> throw MathsException(MathsProblem.NotUnderstood(c.toString()))
        }
    }

    private fun command() {
        val start = i
        i++
        val first = source.getOrNull(i) ?: throw MathsException(MathsProblem.Malformed("a lone backslash"))
        if (!first.isLetter()) {
            i++
            when (first) {
                ',', ';', ':', '!', ' ', '[', ']', '(', ')' -> Unit // Spacing, and the marks round a display equation.
                '{' -> out += Token.Open
                '}' -> out += Token.Close
                '|' -> out += Token.Bar
                else -> throw MathsException(MathsProblem.NotUnderstood(source.substring(start, i)))
            }
            return
        }
        while (i < source.length && source[i].isLetter()) i++
        val name = source.substring(start + 1, i)
        when {
            name in IGNORED -> if (name == "left" || name == "right") skipInvisibleDelimiter()
            name in FONTS -> font()
            name in MARKS -> mark(name)
            name in LETTERS -> out += Token.Name("\\$name" + subscript())
            name == "operatorname" -> operatorName()
            else -> out += when (name) {
                "cdot", "times", "ast" -> Token.Symbol('*')
                "div" -> Token.Symbol('/')
                "lvert", "lVert" -> Token.BarOpen
                "rvert", "rVert" -> Token.BarClose
                "lfloor" -> Token.FloorOpen
                "rfloor" -> Token.FloorClose
                "lceil" -> Token.CeilOpen
                "rceil" -> Token.CeilClose
                "lbrack" -> Token.Open
                "rbrack" -> Token.Close
                else -> if (name in FUNCTIONS || name in STRUCTURE) Token.Command(name) else throw MathsException(MathsProblem.NotUnderstood("\\$name"))
            }
        }
        logBase(name)
        if (name == "sqrt") rootIndex()
    }

    /** The `[3]` of `\sqrt[3]{x}`, which is not a bracket, unlike the one in `\sqrt(x)`. */
    private fun rootIndex() {
        var j = i
        while (j < source.length && source[j].isWhitespace()) j++
        if (source.getOrNull(j) != '[') return
        val end = source.indexOf(']', j)
        if (end < 0) throw MathsException(MathsProblem.Malformed("an unmatched bracket"))
        val text = source.substring(j + 1, end).trim()
        val index = text.toIntOrNull()?.takeIf { it > 0 } ?: throw MathsException(MathsProblem.NotUnderstood("\\sqrt[$text]"))
        out += Token.RootIndex(index.toDouble())
        i = end + 1
    }

    /** `\left.` is an invisible bracket, which the source then doesn't balance. */
    private fun skipInvisibleDelimiter() {
        var j = i
        while (j < source.length && source[j].isWhitespace()) j++
        if (source.getOrNull(j) == '.') i = j + 1
    }

    /** `\mathrm{e}`, `\mathrm{x}`: a font on a name changes nothing, but `\mathrm{sin}` is a function. */
    private fun font() {
        val inner = braced() ?: return
        val word = inner.trim()
        when {
            word in FUNCTIONS -> out += Token.Command(word)
            word.length == 1 && word[0].isLetter() -> out += Token.Name(word + subscript())
            word.all { it.isLetter() } && word.isNotEmpty() -> out += Token.Name("\\mathrm{$word}" + subscript())
            else -> out += Lexer(inner).tokens()
        }
    }

    private fun operatorName() {
        val word = braced()?.trim().orEmpty()
        if (word in FUNCTIONS) out += Token.Command(word) else throw MathsException(MathsProblem.NotUnderstood("\\operatorname{$word}"))
    }

    /** `\hat{n}`: a variable of its own, named with its mark. */
    private fun mark(name: String) {
        val inner = braced() ?: run {
            // `\hat n`: the next letter, without braces.
            while (i < source.length && source[i].isWhitespace()) i++
            val c = source.getOrNull(i)?.takeIf { it.isLetter() } ?: throw MathsException(MathsProblem.NotUnderstood("\\$name"))
            i++
            c.toString()
        }
        out += Token.Name("\\$name{${inner.trim()}}" + subscript())
    }

    /** The contents of `{...}` at the next non-space, if there is one. */
    private fun braced(): String? {
        var j = i
        while (j < source.length && source[j].isWhitespace()) j++
        if (source.getOrNull(j) != '{') return null
        var depth = 0
        val start = j + 1
        while (j < source.length) {
            when (source[j]) {
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    i = j + 1
                    return source.substring(start, j)
                }
            }
            j++
        }
        throw MathsException(MathsProblem.Malformed("an unclosed brace"))
    }

    /** `_1`, `_{th}`: part of a name, kept in the form `_{th}`. */
    private fun subscript(): String {
        var j = i
        while (j < source.length && source[j].isWhitespace()) j++
        if (source.getOrNull(j) != '_') return ""
        i = j + 1
        val inner = braced() ?: run {
            while (i < source.length && source[i].isWhitespace()) i++
            val c = source.getOrNull(i) ?: throw MathsException(MathsProblem.Malformed("an empty subscript"))
            i++
            if (c == '\\') {
                val start = i - 1
                while (i < source.length && source[i].isLetter()) i++
                source.substring(start, i)
            } else {
                c.toString()
            }
        }
        return "_{${inner.replace(" ", "")}}"
    }

    companion object {
        const val MAX_CHARS = 4_000
        const val MAX_TOKENS = 1_500

        val IGNORED = setOf("left", "right", "displaystyle", "textstyle", "scriptstyle", "quad", "qquad", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "enspace", "thinspace", "nonumber", "limits", "nolimits")
        val FONTS = setOf("mathrm", "mathit", "mathbf", "mathsf", "mathtt", "boldsymbol", "bm", "text", "textrm", "textit", "mbox")
        val MARKS = setOf("hat", "widehat", "bar", "overline", "vec", "tilde", "widetilde", "dot", "ddot", "overrightarrow")
        val LETTERS = setOf(
            "alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "zeta", "eta", "theta", "vartheta", "iota", "kappa", "lambda", "mu", "nu", "xi", "pi", "varpi",
            "rho", "varrho", "sigma", "varsigma", "tau", "upsilon", "phi", "varphi", "chi", "psi", "omega", "Gamma", "Delta", "Theta", "Lambda", "Xi", "Pi", "Sigma", "Upsilon", "Phi", "Psi", "Omega", "hbar", "ell",
        )
        val STRUCTURE = setOf("frac", "dfrac", "tfrac", "cfrac", "sqrt")

        /** What each function does; `\log` is base 10, as in engineering notes, and `\ln` is natural. */
        val FUNCTIONS: Map<String, Fn> = mapOf(
            "sin" to Fn.In(::sin), "cos" to Fn.In(::cos), "tan" to Fn.In(::tan),
            "cot" to Fn.In({ 1 / tan(it) }), "sec" to Fn.In({ 1 / cos(it) }), "csc" to Fn.In({ 1 / sin(it) }),
            "arcsin" to Fn.Out(::asin), "arccos" to Fn.Out(::acos), "arctan" to Fn.Out(::atan),
            "sinh" to Fn.Plain(::sinh), "cosh" to Fn.Plain(::cosh), "tanh" to Fn.Plain(::tanh),
            "arcsinh" to Fn.Plain(::asinh), "arccosh" to Fn.Plain(::acosh), "arctanh" to Fn.Plain(::atanh),
            "ln" to Fn.Plain(::ln), "log" to Fn.Plain(::log10), "lg" to Fn.Plain(::log10), "exp" to Fn.Plain(::exp),
            "abs" to Fn.Plain(::abs), "sgn" to Fn.Plain(::sign), "floor" to Fn.Plain(::floor), "ceil" to Fn.Plain(::ceil),
        )
    }
}

/** How a function treats angles. */
internal sealed interface Fn {
    val op: (Double) -> Double

    data class Plain(override val op: (Double) -> Double) : Fn
    data class In(override val op: (Double) -> Double) : Fn
    data class Out(override val op: (Double) -> Double) : Fn
}

/**
 * Builds an expression, with the rules a page of maths follows, stated so results are predictable:
 * - `*` and `/` go left to right, so `1/2x` is `(1/2)x`, as MATLAB reads it; `\frac` is the clear way to write it.
 * - Writing things side by side multiplies them: `2x`, `xy`, `(x+1)(x-1)`, `2\sin x`. Every letter is its own name.
 * - Powers bind tightest and go right to left: `-x^2` is `-(x^2)`, and `2^x^2` is `2^(x^2)`.
 * - A function without brackets takes what follows up to the next `+`, `-` or function: `\sin 2x + 1` is
 *   `\sin(2x) + 1`, and `\sin x \cos x` is `\sin(x)\cos(x)`. With brackets it takes only those: `\sin(x)y` is `\sin(x)y`.
 * - `\sin^2 x` is `(\sin x)^2`, and `\sin^{-1} x` is `\arcsin x`.
 * - A power with an odd root, such as `x^{1/3}`, is defined for negative `x`, as `\sqrt[3]{x}` is.
 */
internal class Parser(source: List<Token>) {
    private val tokens = source.toMutableList()
    private var at = 0
    private var depth = 0

    /** Bars met but not closed: inside them, a `|` where a product could go on closes instead of opening. */
    private var bars = 0

    fun parse(): Node {
        if (tokens.isEmpty()) throw MathsException(MathsProblem.Malformed("an empty side"))
        val node = sum()
        if (at < tokens.size) throw MathsException(MathsProblem.Malformed("something after the end"))
        return node
    }

    private fun peek() = tokens.getOrNull(at)

    private inline fun <T> nested(body: () -> T): T {
        if (++depth > MAX_DEPTH) throw MathsException(MathsProblem.TooComplex)
        return body().also { depth-- }
    }

    private fun sum(): Node = nested {
        var left = term()
        while (true) {
            val t = peek() as? Token.Symbol ?: break
            left = when (t.char) {
                '+' -> {
                    at++
                    Node.Binary(Double::plus, left, term())
                }
                '-' -> {
                    at++
                    Node.Binary(Double::minus, left, term())
                }
                else -> break
            }
        }
        left
    }

    private fun term(): Node {
        var left = signed()
        while (true) {
            val t = peek()
            left = when {
                t is Token.Symbol && t.char == '*' -> {
                    at++
                    Node.Binary(Double::times, left, signed())
                }
                t is Token.Symbol && t.char == '/' -> {
                    at++
                    Node.Binary(DIVIDE, left, signed())
                }
                startsFactor(t) -> Node.Binary(Double::times, left, power())
                else -> return left
            }
        }
    }

    private fun signed(): Node = nested {
        val t = peek()
        when {
            t is Token.Symbol && t.char == '-' -> {
                at++
                Node.Unary({ -it }, signed())
            }
            t is Token.Symbol && t.char == '+' -> {
                at++
                signed()
            }
            else -> power()
        }
    }

    /** Whether a product can go on with [t]. A bar closes the one that's open, if any. */
    private fun startsFactor(t: Token?): Boolean = when (t) {
        is Token.Number, is Token.Name, is Token.Command, Token.Open, Token.BraceOpen, Token.BarOpen, Token.FloorOpen, Token.CeilOpen -> true
        Token.Bar -> bars == 0
        else -> false
    }

    private fun power(): Node = nested {
        val base = atom()
        val t = peek()
        if (t is Token.Symbol && t.char == '^') {
            at++
            raised(base, exponent())
        } else {
            base
        }
    }

    private fun raised(base: Node, exponent: Node): Node {
        val odd = oddRoot(exponent)
        return if (odd != null) Node.Unary({ signedPower(it, odd) }, base) else Node.Binary(Double::pow, base, exponent)
    }

    /** The value of a constant exponent `p/q` with odd `q`, which has a real answer for negative bases. */
    private fun oddRoot(exponent: Node): Pair<Double, Double>? {
        val (top, bottom) = (exponent as? Node.Binary)?.takeIf { it.op == DIVIDE }?.let { it.a to it.b } ?: return null
        val p = (top as? Node.Number)?.value ?: return null
        val q = (bottom as? Node.Number)?.value ?: return null
        if (p % 1.0 != 0.0 || q % 1.0 != 0.0 || q % 2.0 == 0.0) return null
        return p to q
    }

    private fun signedPower(x: Double, power: Pair<Double, Double>): Double {
        val (p, q) = power
        val value = abs(x).pow(p / q)
        return if (x < 0 && p % 2.0 != 0.0) -value else value
    }

    /** `^2` or `^{...}`: a single item, or a whole group. */
    private fun exponent(): Node = nested {
        val t = peek()
        when {
            t == Token.BraceOpen -> group()
            t is Token.Symbol && t.char == '-' -> {
                at++
                Node.Unary({ -it }, exponent())
            }
            t is Token.Number -> oneDigit(t)
            else -> atom()
        }
    }

    /**
     * What LaTeX takes as a script or `\frac` argument with no braces: one digit of a run, as `x^23` shows `x²3`
     * and `\frac12` shows one half. The rest of the run stays for what follows.
     */
    private fun oneDigit(t: Token.Number): Node {
        if (t.text.length == 1 || !t.text.all(Char::isDigit)) {
            at++
            return Node.Number(t.value)
        }
        val rest = t.text.substring(1)
        tokens[at] = Token.Number(rest.toDouble(), rest)
        return Node.Number(t.text.substring(0, 1).toDouble())
    }

    private fun group(): Node = nested {
        expect(Token.BraceOpen)
        val inner = withOwnBars { sum() }
        expect(Token.BraceClose)
        inner
    }

    private inline fun <T> withOwnBars(body: () -> T): T {
        val saved = bars
        bars = 0
        return body().also { bars = saved }
    }

    private fun expect(token: Token) {
        if (peek() != token) throw MathsException(MathsProblem.Malformed("an unmatched bracket"))
        at++
    }

    private fun atom(): Node = nested {
        when (val t = peek()) {
            is Token.Number -> {
                at++
                Node.Number(t.value)
            }
            is Token.Name -> {
                at++
                named(t.text)
            }
            is Token.Command -> {
                at++
                command(t.name)
            }
            Token.Open -> {
                at++
                val inner = withOwnBars { sum() }
                expect(Token.Close)
                inner
            }
            Token.BraceOpen -> group()
            Token.Bar -> bracketed(Token.Bar, ::abs, counted = true)
            Token.BarOpen -> bracketed(Token.BarClose, ::abs)
            Token.FloorOpen -> bracketed(Token.FloorClose, ::floor)
            Token.CeilOpen -> bracketed(Token.CeilClose, ::ceil)
            null -> throw MathsException(MathsProblem.Malformed("something missing at the end"))
            else -> throw MathsException(MathsProblem.Malformed("something out of place"))
        }
    }

    private fun bracketed(close: Token, op: (Double) -> Double, counted: Boolean = false): Node {
        at++
        if (counted) bars++
        val inner = sum()
        if (counted) bars--
        expect(close)
        return Node.Unary(op, inner)
    }

    private fun named(name: String): Node = when (name) {
        "\\pi" -> Node.Number(PI)
        "e", "\\mathrm{e}" -> Node.Number(E)
        else -> Node.Variable(name)
    }

    private fun command(name: String): Node = when (name) {
        "frac", "dfrac", "tfrac", "cfrac" -> {
            val top = argument()
            val bottom = argument()
            Node.Binary(DIVIDE, top, bottom)
        }
        "sqrt" -> root()
        else -> function(name)
    }

    /** `{...}`, or one token, as in `\frac12`. */
    private fun argument(): Node = when (val t = peek()) {
        Token.BraceOpen -> group()
        is Token.Number -> oneDigit(t)
        else -> atom()
    }

    private fun root(): Node {
        val t = peek() as? Token.RootIndex ?: return Node.Unary(::sqrt, argument())
        at++
        val index = t.value
        val inner = argument()
        return if (index % 2.0 == 1.0) Node.Unary({ signedPower(it, 1.0 to index) }, inner) else Node.Binary(Double::pow, inner, Node.Number(1 / index))
    }

    private fun function(name: String): Node {
        val base = logBase(name)
        var fn = Lexer.FUNCTIONS.getValue(name)
        var outerPower: Node? = null
        val t = peek()
        if (t is Token.Symbol && t.char == '^') {
            at++
            val power = exponent()
            val inverse = INVERSES[name]
            if (isMinusOne(power) && inverse != null) fn = Lexer.FUNCTIONS.getValue(inverse) else outerPower = power
        }
        val inner = functionArgument()
        val applied: Node = when {
            base != null -> Node.Binary({ a, b -> ln(a) / ln(b) }, inner, base)
            fn is Fn.In -> Node.AngleIn(fn.op, inner)
            fn is Fn.Out -> Node.AngleOut(fn.op, inner)
            else -> Node.Unary(fn.op, inner)
        }
        return if (outerPower != null) raised(applied, outerPower) else applied
    }

    /** `\log_{2} x`. The lexer keeps a subscript on names only, so one on `\log` arrives as its own marks. */
    private fun logBase(name: String): Node? {
        if (name != "log") return null
        val t = peek() as? Token.Name ?: return null
        if (!t.text.startsWith("_")) return null
        at++
        val base = t.text.removePrefix("_{").removeSuffix("}")
        return Node.Number(base.toDoubleOrNull() ?: throw MathsException(MathsProblem.NotUnderstood("\\log_{$base}")))
    }

    private fun isMinusOne(node: Node): Boolean = node is Node.Unary && (node.a as? Node.Number)?.value == 1.0 && node.op(1.0) == -1.0

    /** Brackets straight after the name are all of it; otherwise a product, up to the next `+`, `-` or function. */
    private fun functionArgument(): Node = nested {
        val t = peek()
        when {
            t == Token.Open -> atom()
            t == Token.BraceOpen -> group()
            t == null || (t is Token.Symbol && t.char != '-') -> throw MathsException(MathsProblem.Malformed("a function with nothing to apply to"))
            else -> {
                var product = signed()
                while (true) {
                    val next = peek()
                    if (next is Token.Command && next.name in Lexer.FUNCTIONS) break
                    if (!startsFactor(next)) break
                    product = Node.Binary(Double::times, product, power())
                }
                product
            }
        }
    }

    companion object {
        const val MAX_DEPTH = 120
        private val DIVIDE: (Double, Double) -> Double = { a, b -> a / b }
        private val INVERSES = mapOf("sin" to "arcsin", "cos" to "arccos", "tan" to "arctan", "sinh" to "arcsinh", "cosh" to "arccosh", "tanh" to "arctanh")
    }
}
