package io.github.iamthebarrow.nibtexa.recognition

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.licences.LICENCES
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Loading the model from a picked folder (Phase 10), and the licence texts it ships with. */
@RunWith(AndroidJUnit4::class)
class ModelImporterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "import-test-${System.nanoTime()}").apply { mkdirs() }
    private val target = File(root, "models/texteller-int8")
    private val importer = ModelImporter(target)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** Looks like an ONNX model to the importer's checks: protobuf tag 0x08, over 1 MB. */
    private fun network(marker: Int) = ByteArray(1_100_000).also { it[0] = 0x08; it[1] = marker.toByte() }

    private val vocab = """{"a": 0, "b": 1}""".toByteArray()

    private fun files(encoder: ByteArray = network(1), decoder: ByteArray = network(2), vocabulary: ByteArray? = vocab) = buildList {
        add(ImportFile(OnnxTexTellerModel.ENCODER, encoder.size.toLong()) { encoder.inputStream() })
        add(ImportFile(OnnxTexTellerModel.DECODER, decoder.size.toLong()) { decoder.inputStream() })
        vocabulary?.let { add(ImportFile(OnnxTexTellerModel.VOCAB, it.size.toLong()) { it.inputStream() }) }
        add(ImportFile("README.txt", 3) { "hi\n".byteInputStream() }) // other files are ignored
    }

    private fun installed(name: String) = File(target, name).readBytes()

    @Test
    fun aCompleteFolderIsCopiedWithProgress() {
        val reports = mutableListOf<Pair<Long, Long>>()
        val result = importer.import(files(), progress = { c, t -> reports += c to t })
        assertTrue(result.toString(), result is ModelImporter.Result.Imported)
        assertEquals(1, installed(OnnxTexTellerModel.ENCODER)[1].toInt())
        assertEquals(2, installed(OnnxTexTellerModel.DECODER)[1].toInt())
        assertFalse(File(target, "README.txt").exists())
        assertEquals(reports.last().second, reports.last().first)
        assertEquals(listOf("texteller-int8"), target.parentFile!!.list()!!.toList()) // no staging left
    }

    @Test
    fun aMissingFileIsNamedAndNothingChanges() {
        importer.import(files())
        val result = importer.import(files(vocabulary = null))
        assertEquals(ModelImporter.Result.Missing(listOf(OnnxTexTellerModel.VOCAB)), result)
        assertEquals(1, installed(OnnxTexTellerModel.ENCODER)[1].toInt())
    }

    @Test
    fun aFileThatIsNotAModelKeepsTheCurrentOne() {
        importer.import(files())
        val result = importer.import(files(encoder = ByteArray(2_000_000) { 0x41 }, decoder = network(9)))
        assertTrue(result.toString(), result is ModelImporter.Result.Invalid)
        assertEquals("the old decoder stays", 2, installed(OnnxTexTellerModel.DECODER)[1].toInt())
    }

    @Test
    fun anUnreadableVocabularyKeepsTheCurrentOne() {
        importer.import(files())
        val result = importer.import(files(decoder = network(9), vocabulary = "{".toByteArray()))
        assertTrue(result.toString(), result is ModelImporter.Result.Invalid)
        assertEquals(2, installed(OnnxTexTellerModel.DECODER)[1].toInt())
    }

    @Test
    fun aCanceledImportKeepsTheCurrentOne() {
        importer.import(files())
        var calls = 0
        val result = importer.import(files(decoder = network(9)), canceled = { ++calls > 1 })
        assertEquals(ModelImporter.Result.Canceled, result)
        assertEquals(2, installed(OnnxTexTellerModel.DECODER)[1].toInt())
        assertEquals(listOf("texteller-int8"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun everyLicenceTextIsInTheApp() {
        for (licence in LICENCES) {
            val text = context.assets.open(licence.asset).bufferedReader().use { it.readText() }
            assertTrue("${licence.asset} is empty", text.length > 500)
        }
    }
}
