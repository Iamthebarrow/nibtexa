package io.github.iamthebarrow.nibtexa.core.model.latex

/**
 * LaTeX tokenization and normalization, the same rules as the Phase 3 evaluation harness
 * (tools/recognition-eval/nibtexa_eval/latex.py). Two expressions with equal normalized
 * tokens differ only in presentation: spacing, optional braces, `\left`/`\right`, synonyms.
 */
object LatexTokens {
    private val TOKEN = Regex("""\\[a-zA-Z]+|\\.|\S""")

    // Tokens that carry no handwritten content, including math-mode delimiters.
    private val DROP = setOf(
        "\\left", "\\right", "\\displaystyle", "\\,", "\\;", "\\:", "\\!", "\\ ", "\\limits",
        "\\[", "\\]", "\\(", "\\)", "$",
    )

    private val SYNONYMS = mapOf(
        "\\dfrac" to "\\frac",
        "\\tfrac" to "\\frac",
        "\\lt" to "<",
        "\\gt" to ">",
        "\\le" to "\\leq",
        "\\ge" to "\\geq",
        "\\ne" to "\\neq",
        "\\to" to "\\rightarrow",
        "\\dots" to "\\ldots",
        "\\cdots" to "\\ldots",
        "\\lbrace" to "\\{",
        "\\rbrace" to "\\}",
        "\\tg" to "\\tan",
        "\\overrightarrow" to "\\vec",
    )

    // Upright operator names are plain letters in handwriting, so both forms are spelled out.
    private val OPERATOR_NAMES = setOf("\\sin", "\\cos", "\\tan", "\\log", "\\ln", "\\exp", "\\lim", "\\max", "\\min", "\\det")

    fun tokenize(latex: String): List<String> = TOKEN.findAll(latex).map { it.value }.toList()

    fun normalize(latex: String): List<String> {
        val tokens = mutableListOf<String>()
        for (raw in tokenize(latex)) {
            if (raw in DROP) continue
            val token = SYNONYMS[raw] ?: raw
            if (token in OPERATOR_NAMES) token.drop(1).mapTo(tokens) { it.toString() } else tokens += token
        }
        return braceScripts(tokens)
    }

    /** Writes every super/subscript argument in braces: `x^2` becomes `x^{2}`. */
    private fun braceScripts(tokens: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            out += token
            if ((token == "^" || token == "_") && i + 1 < tokens.size && tokens[i + 1] != "{") {
                out += listOf("{", tokens[i + 1], "}")
                i += 2
                continue
            }
            i++
        }
        return out
    }

    fun editDistance(a: List<String>, b: List<String>): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val current = IntArray(b.size + 1)
            current[0] = i
            for (j in 1..b.size) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.size]
    }
}
