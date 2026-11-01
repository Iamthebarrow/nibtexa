package io.github.iamthebarrow.nibtexa.core.maths

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real recognized LaTeX, read from the evaluation data outside the repository (skipped without it). It isn't an accuracy
 * check, as most of those samples aren't functions. It shows that nothing crashes, and what the engine can't read yet,
 * written to `build/reports/recognized-latex.txt`.
 */
class RecognizedLatexTest {
    private val file = File("../../../nibtexa-eval-data/results/texteller_test_n500_s0.jsonl")

    @Test
    fun recognizedLatexNeverCrashesAndTheGapsAreKnown() {
        assumeTrue("no evaluation data", file.isFile)
        val predicted = Regex("\"predicted\": \"((?:[^\"\\\\]|\\\\.)*)\"")
        val samples = file.readLines().mapNotNull { predicted.find(it)?.groupValues?.get(1)?.replace("\\\\", "\\") }
        var read = 0
        val problems = sortedMapOf<String, Int>()
        for (latex in samples) {
            try {
                val e = Equation.parse(latex)
                e.function(e.parameters.associateWith { 1.0 })(0.5)
                read++
            } catch (e: MathsException) {
                val key = when (val p = e.problem) {
                    is MathsProblem.NotUnderstood -> "not understood: ${p.what}"
                    is MathsProblem.Malformed -> "malformed: ${p.detail}"
                    is MathsProblem.AmbiguousInput -> "no clear input variable"
                    else -> p.toString()
                }
                problems.merge(key, 1, Int::plus)
            }
        }
        val report = buildString {
            appendLine("Samples: ${samples.size}; read as functions: $read")
            problems.entries.sortedByDescending { it.value }.forEach { (k, v) -> appendLine("$v\t$k") }
        }
        File("build/reports").mkdirs()
        File("build/reports/recognized-latex.txt").writeText(report)
        println(report)
    }
}
