package io.github.iamthebarrow.nibtexa.recognition

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

class ModelDownloaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val contents = mutableMapOf<String, ByteArray>()
    private val ranges = mutableListOf<String?>()
    private var honourRange = true

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val name = exchange.requestURI.path.removePrefix("/")
            val body = contents[name]
            val range = exchange.requestHeaders.getFirst("Range")
            ranges += range
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else if (range != null && honourRange) {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                exchange.sendResponseHeaders(206, (body.size - from).toLong())
                exchange.responseBody.use { it.write(body, from, body.size - from) }
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** An encoder and decoder that look like ONNX, and a small vocabulary. */
    private fun model(): List<ModelDownloader.ModelFile> {
        fun network(seed: Int) = ByteArray((1 shl 20) + 1000) { (it * seed).toByte() }.also { it[0] = 0x08 }
        contents[OnnxTexTellerModel.ENCODER] = network(3)
        contents[OnnxTexTellerModel.DECODER] = network(7)
        contents[OnnxTexTellerModel.VOCAB] = """{"a":0,"b":1,"<s>":2}""".toByteArray()
        return contents.map { (name, body) -> ModelDownloader.ModelFile(name, body.size.toLong(), sha(body)) }
    }

    private fun url() = "http://127.0.0.1:${server.address.port}"

    private fun target() = File(temp.root, "models/texteller").also { it.parentFile!!.mkdirs() }

    private fun staging(target: File) = File(target.parentFile, ".${target.name}.downloading")

    @Test
    fun itComesDownCheckedAndInPlace() {
        val files = model()
        val target = target()
        val seen = mutableListOf<Long>()
        val result = ModelDownloader(target, url(), files).download(progress = { done, _ -> seen += done })
        assertTrue("$result", result is ModelDownloader.Result.Done)
        for (f in files) assertEquals(sha(contents[f.name]!!), sha(File(target, f.name).readBytes()))
        assertFalse("no leftovers", staging(target).exists())
        assertEquals(files.sumOf { it.size }, seen.max())
    }

    @Test
    fun anInterruptedDownloadGoesOnWhereItStopped() {
        val files = model()
        val target = target()
        val partial = contents[OnnxTexTellerModel.ENCODER]!!.copyOf(5000)
        File(staging(target).apply { mkdirs() }, OnnxTexTellerModel.ENCODER).writeBytes(partial)
        val result = ModelDownloader(target, url(), files).download()
        assertTrue("$result", result is ModelDownloader.Result.Done)
        assertTrue(ranges.toString(), "bytes=5000-" in ranges)
        assertEquals(sha(contents[OnnxTexTellerModel.ENCODER]!!), sha(File(target, OnnxTexTellerModel.ENCODER).readBytes()))
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsTheFileAgain() {
        val files = model()
        val target = target()
        honourRange = false
        File(staging(target).apply { mkdirs() }, OnnxTexTellerModel.ENCODER).writeBytes(ByteArray(5000) { 9 })
        val result = ModelDownloader(target, url(), files).download()
        assertTrue("$result", result is ModelDownloader.Result.Done)
        assertEquals(sha(contents[OnnxTexTellerModel.ENCODER]!!), sha(File(target, OnnxTexTellerModel.ENCODER).readBytes()))
    }

    @Test
    fun aFileThatFailsItsChecksumIsRefusedAndTheCurrentModelStays() {
        val files = model()
        val target = target().apply { mkdirs() }
        File(target, "current").writeText("the model in use")
        contents[OnnxTexTellerModel.DECODER] = contents[OnnxTexTellerModel.DECODER]!!.copyOf().also { it[100] = 1 }
        val result = ModelDownloader(target, url(), files).download()
        assertEquals(ModelDownloader.Result.Damaged(OnnxTexTellerModel.DECODER), result)
        assertEquals("the model in use", File(target, "current").readText())
        assertFalse("the bad file goes", File(staging(target), OnnxTexTellerModel.DECODER).exists())
    }

    @Test
    fun cancellingKeepsWhatCameDownForNextTime() {
        val files = model()
        val target = target()
        var stop = false
        val first = ModelDownloader(target, url(), files).download(progress = { done, _ -> if (done > 1000) stop = true }, canceled = { stop })
        assertEquals(ModelDownloader.Result.Canceled, first)
        assertFalse(target.exists())
        val second = ModelDownloader(target, url(), files).download()
        assertTrue("$second", second is ModelDownloader.Result.Done)
    }

    @Test
    fun aMissingFileOnTheServerIsAnErrorNotASilentFailure() {
        val files = model()
        contents.remove(OnnxTexTellerModel.VOCAB)
        val failed = runCatching { ModelDownloader(target(), url(), files).download() }
        assertTrue(failed.exceptionOrNull()?.message.orEmpty(), failed.exceptionOrNull()?.message.orEmpty().contains("404"))
    }

    @Test
    fun thePublishedFilesArePinned() {
        assertTrue(ModelDownloader.BASE_URL.startsWith("https://"))
        assertEquals(setOf(OnnxTexTellerModel.ENCODER, OnnxTexTellerModel.DECODER, OnnxTexTellerModel.VOCAB), ModelDownloader.FILES.map { it.name }.toSet())
        ModelDownloader.FILES.forEach { assertEquals(it.name, 64, it.sha256.length) }
    }
}
