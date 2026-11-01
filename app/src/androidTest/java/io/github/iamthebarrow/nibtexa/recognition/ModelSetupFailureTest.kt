package io.github.iamthebarrow.nibtexa.recognition

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.core.recognition.RecognitionException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A missing or damaged model folder gives a typed error, never a crash (Phase 8). */
@RunWith(AndroidJUnit4::class)
class ModelSetupFailureTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "model-test-${System.nanoTime()}").apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun failure(): RecognitionException {
        try {
            TexTellerSource(dir).recognizer()
        } catch (e: RecognitionException) {
            return e
        }
        fail("expected a RecognitionException")
        throw AssertionError()
    }

    @Test
    fun aMissingModelNamesTheMissingFiles() {
        File(dir, OnnxTexTellerModel.VOCAB).writeText("{}")
        val e = failure()
        assertTrue(e is ModelNotInstalledException)
        assertEquals(OnnxTexTellerModel.REQUIRED_FILES - OnnxTexTellerModel.VOCAB, OnnxTexTellerModel.REQUIRED_FILES.filter { e.message!!.contains(it) })
    }

    @Test
    fun damagedModelFilesAreATypedError() {
        for (name in OnnxTexTellerModel.REQUIRED_FILES) File(dir, name).writeBytes(ByteArray(64) { it.toByte() })
        val e = failure()
        assertTrue(e.message, e !is ModelNotInstalledException)
    }

    @Test
    fun aValidVocabularyWithDamagedNetworksIsATypedError() {
        for (name in OnnxTexTellerModel.REQUIRED_FILES) File(dir, name).writeBytes(ByteArray(64))
        File(dir, OnnxTexTellerModel.VOCAB).writeText("""{"a": 0, "b": 1}""")
        val e = failure()
        assertTrue(e.message!!.startsWith("Could not load the recognition model"))
    }
}
