package io.github.iamthebarrow.nibtexa

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the JVM unit-test pipeline runs. It tests no app behavior; real
 * unit tests arrive with the stroke model in Phase 2.
 */
class ToolchainSmokeTest {
    @Test
    fun jvmUnitTestsRun() {
        val specVersion = System.getProperty("java.specification.version").orEmpty()
        assertTrue(specVersion.toIntOrNull() ?: 0 >= 17)
    }
}
