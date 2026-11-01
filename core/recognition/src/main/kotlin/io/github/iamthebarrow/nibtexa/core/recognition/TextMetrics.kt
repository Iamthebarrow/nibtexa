package io.github.iamthebarrow.nibtexa.core.recognition

/** Accuracy measures for the text recognizer trial (ADR-0007, section 3). */
object TextMetrics {
    /** Whitespace-separated words. */
    fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * Reference words found, in order, in the hypothesis (longest common subsequence of
     * words). Exact comparison: case and punctuation count.
     */
    fun matchedWords(reference: String, hypothesis: String): Int = lcs(words(reference), words(hypothesis))

    /** Character edit distance (insertions, deletions, substitutions). */
    fun charEdits(reference: String, hypothesis: String): Int = levenshtein(reference.toList(), hypothesis.toList())

    /** Totals over a set of (reference, hypothesis) lines. */
    data class Summary(val lines: Int, val referenceWords: Int, val matchedWords: Int, val referenceChars: Int, val charEdits: Int, val exactLines: Int) {
        val wordAccuracy: Double get() = if (referenceWords == 0) 0.0 else matchedWords.toDouble() / referenceWords
        val charErrorRate: Double get() = if (referenceChars == 0) 0.0 else charEdits.toDouble() / referenceChars
    }

    fun summarize(pairs: List<Pair<String, String>>): Summary = Summary(
        lines = pairs.size,
        referenceWords = pairs.sumOf { words(it.first).size },
        matchedWords = pairs.sumOf { matchedWords(it.first, it.second) },
        referenceChars = pairs.sumOf { it.first.length },
        charEdits = pairs.sumOf { charEdits(it.first, it.second) },
        exactLines = pairs.count { it.first == it.second },
    )

    /** The value at quantile [q] (0..1) by the nearest-rank method. */
    fun percentile(values: List<Long>, q: Double): Long {
        require(values.isNotEmpty()) { "No values" }
        require(q in 0.0..1.0) { "Quantile out of range" }
        val sorted = values.sorted()
        val rank = kotlin.math.ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun <T> lcs(a: List<T>, b: List<T>): Int {
        var previous = IntArray(b.size + 1)
        for (x in a) {
            val row = IntArray(b.size + 1)
            for (j in b.indices) row[j + 1] = if (x == b[j]) previous[j] + 1 else maxOf(previous[j + 1], row[j])
            previous = row
        }
        return previous[b.size]
    }

    private fun <T> levenshtein(a: List<T>, b: List<T>): Int {
        var previous = IntArray(b.size + 1) { it }
        for ((i, x) in a.withIndex()) {
            val row = IntArray(b.size + 1)
            row[0] = i + 1
            for (j in b.indices) row[j + 1] = minOf(previous[j + 1] + 1, row[j] + 1, previous[j] + if (x == b[j]) 0 else 1)
            previous = row
        }
        return previous[b.size]
    }
}
