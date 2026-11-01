package io.github.iamthebarrow.nibtexa.feature.mathedit.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BridgeProtocolTest {

    private fun accepted(raw: String?): PageMessage {
        val decoded = BridgeProtocol.decode(raw)
        if (decoded !is Decoded.Accepted) fail("expected $raw to be accepted, was $decoded")
        return (decoded as Decoded.Accepted).value
    }

    private fun assertRejected(raw: String?) {
        val decoded = BridgeProtocol.decode(raw)
        assertTrue("expected $raw to be rejected, was $decoded", decoded is Decoded.Rejected)
    }

    @Test
    fun everyPageMessageRoundTrips() {
        val messages = listOf(
            PageMessage.Ready("mathlive-editor"),
            PageMessage.Changed("""\int_{-1}^{1} x\,dx"""),
            PageMessage.Value(7, """\frac{a}{b}"""),
            PageMessage.Rendered(3, 2, 12.5, listOf(1), listOf("x", """\notacommand""")),
            PageMessage.PageError(null, "renderer gone"),
            PageMessage.PageError(4, "rejected command"),
        )
        for (message in messages) assertEquals(message, accepted(BridgeProtocol.encode(message)))
    }

    @Test
    fun keyboardHeightsRoundTripAndBadOnesAreRejected() {
        assertEquals(PageMessage.Keyboard(236.5), accepted(BridgeProtocol.encode(PageMessage.Keyboard(236.5))))
        assertEquals(PageMessage.Keyboard(0.0), accepted("""{"v":1,"type":"keyboard","height":0}"""))
        assertRejected("""{"v":1,"type":"keyboard","height":-1}""")
        assertRejected("""{"v":1,"type":"keyboard","height":"200"}""")
        assertRejected("""{"v":1,"type":"keyboard","height":2001}""")
        assertRejected("""{"v":1,"type":"keyboard"}""")
        assertRejected("""{"v":1,"type":"keyboard","height":10,"top":0}""")
    }

    @Test
    fun listAlignmentsRoundTripAndBadOnesAreRejected() {
        for (align in ListAlign.entries) {
            val command = HostCommand.RenderList(4, listOf("x"), align)
            assertEquals(Decoded.Accepted(command), BridgeProtocol.decodeCommand(BridgeProtocol.encode(command)))
        }
        // Left is the default and is not sent, so older pages read the same message.
        assertTrue("align" !in BridgeProtocol.encode(HostCommand.RenderList(4, listOf("x"))))
        assertTrue(BridgeProtocol.decodeCommand("""{"v":1,"type":"renderList","requestId":1,"items":[],"align":"justify"}""") is Decoded.Rejected)
        assertTrue(BridgeProtocol.decodeCommand("""{"v":1,"type":"renderList","requestId":1,"items":[],"align":1}""") is Decoded.Rejected)
    }

    @Test
    fun everyCommandRoundTripsThroughThePageRules() {
        val commands = listOf(
            HostCommand.SetValue(1, """x=\frac{-b\pm\sqrt{b^{2}-4ac}}{2a}"""),
            HostCommand.GetValue(2),
            HostCommand.Focus,
            HostCommand.RenderList(3, listOf("a", """\sum_{n}""")),
        )
        for (command in commands) {
            assertEquals(Decoded.Accepted(command), BridgeProtocol.decodeCommand(BridgeProtocol.encode(command)))
        }
    }

    @Test
    fun latexWithQuotesBackslashesAndUnicodeSurvivesEncoding() {
        val latex = "\\text{\"quoted\" </script> \u00e9 \u2200 \uD835\uDC65}\\\\ \n\t"
        assertEquals(PageMessage.Changed(latex), accepted(BridgeProtocol.encode(PageMessage.Changed(latex))))
    }

    @Test
    fun malformedInputIsRejected() {
        assertRejected(null)
        assertRejected("")
        assertRejected("not json")
        assertRejected("""{"v":1,"type":"changed","latex":"x"""")
        assertRejected("[1,2]")
        assertRejected("\"changed\"")
    }

    @Test
    fun wrongOrMissingVersionIsRejected() {
        assertRejected("""{"type":"changed","latex":"x"}""")
        assertRejected("""{"v":2,"type":"changed","latex":"x"}""")
        assertRejected("""{"v":"1","type":"changed","latex":"x"}""")
        assertRejected("""{"v":1.5,"type":"changed","latex":"x"}""")
    }

    @Test
    fun unknownTypesAndFieldsAreRejected() {
        assertRejected("""{"v":1,"type":"evaluate","script":"1+1"}""")
        assertRejected("""{"v":1,"type":"changed","latex":"x","extra":true}""")
        assertRejected("""{"v":1,"latex":"x"}""")
        assertRejected("""{"v":1,"type":5}""")
    }

    @Test
    fun mistypedFieldsAreRejected() {
        assertRejected("""{"v":1,"type":"changed","latex":5}""")
        assertRejected("""{"v":1,"type":"changed"}""")
        assertRejected("""{"v":1,"type":"value","requestId":"1","latex":"x"}""")
        assertRejected("""{"v":1,"type":"value","requestId":-1,"latex":"x"}""")
        assertRejected("""{"v":1,"type":"value","requestId":true,"latex":"x"}""")
        assertRejected("""{"v":1,"type":"rendered","requestId":1,"count":1,"millis":"fast","failed":[],"values":[]}""")
        assertRejected("""{"v":1,"type":"rendered","requestId":1,"count":1,"millis":1,"failed":[-1],"values":[]}""")
        assertRejected("""{"v":1,"type":"rendered","requestId":1,"count":1,"millis":1,"failed":[],"values":[1]}""")
    }

    @Test
    fun oversizedInputIsRejected() {
        val longLatex = "x".repeat(BridgeProtocol.MAX_LATEX_CHARS + 1)
        assertRejected("""{"v":1,"type":"changed","latex":"$longLatex"}""")
        assertRejected("x".repeat(BridgeProtocol.MAX_MESSAGE_CHARS + 1))
        val tooMany = (0..BridgeProtocol.MAX_LIST_ITEMS).joinToString(",") { "\"x\"" }
        assertRejected("""{"v":1,"type":"rendered","requestId":1,"count":1,"millis":1,"failed":[],"values":[$tooMany]}""")
    }

    @Test
    fun commandsBeyondThePageLimitsAreNotSent() {
        assertThrows { BridgeProtocol.encode(HostCommand.SetValue(1, "x".repeat(BridgeProtocol.MAX_LATEX_CHARS + 1))) }
        assertThrows { BridgeProtocol.encode(HostCommand.RenderList(1, List(BridgeProtocol.MAX_LIST_ITEMS + 1) { "x" })) }
        assertThrows { BridgeProtocol.encode(HostCommand.RenderList(1, List(20) { "x".repeat(BridgeProtocol.MAX_LATEX_CHARS) })) }
    }

    // ---- The document page (Phase 12, the PDF export) --------------------------------------

    private val blocks = listOf(
        DocumentBlock(DocumentBlock.Kind.TITLE, "Signals & Systems"),
        DocumentBlock(DocumentBlock.Kind.HEADING, "Week 1", level = 2),
        DocumentBlock(DocumentBlock.Kind.PARAGRAPH, "<b>not markup</b>"),
        DocumentBlock(DocumentBlock.Kind.MATH, """\frac{a}{b}"""),
        DocumentBlock(DocumentBlock.Kind.FIGURE, "Caption", width = 144.0, height = 45.5),
        DocumentBlock(DocumentBlock.Kind.INLINE_MATH, """\textstyle x^2"""),
    )

    @Test
    fun documentCommandsAndLayoutsRoundTrip() {
        val command = HostCommand.RenderDocument(9, blocks)
        assertEquals(Decoded.Accepted(command), BridgeProtocol.decodeCommand(BridgeProtocol.encode(command)))
        val laidOut = PageMessage.LaidOut(9, 483.0, 900.5, listOf(BlockSpan(0.0, 40.0), BlockSpan(40.0, 140.0, 169.5, 48.0), BlockSpan(140.0, 170.0, baseline = 162.0)), listOf(3))
        assertEquals(laidOut, accepted(BridgeProtocol.encode(laidOut)))
    }

    @Test
    fun malformedDocumentBlocksAreRejected() {
        fun command(block: String) = BridgeProtocol.decodeCommand("""{"v":1,"type":"renderDocument","requestId":1,"blocks":[$block]}""")
        val good = """{"kind":"paragraph","text":"x","level":0,"width":0,"height":0}"""
        assertTrue(command(good) is Decoded.Accepted)
        for (bad in listOf(
            """{"kind":"html","text":"x","level":0,"width":0,"height":0}""",
            """{"kind":"paragraph","text":"x","level":0,"width":0,"height":0,"html":"<b>"}""",
            """{"kind":"paragraph","text":"x","level":0,"width":0}""",
            """{"kind":"heading","text":"x","level":4,"width":0,"height":0}""",
            """{"kind":"figure","text":"x","level":0,"width":-1,"height":0}""",
            """{"kind":"figure","text":"x","level":0,"width":2001,"height":0}""",
            """{"kind":"paragraph","text":5,"level":0,"width":0,"height":0}""",
            """"just text"""",
        )) assertTrue("expected $bad to be rejected", command(bad) is Decoded.Rejected)
    }

    @Test
    fun malformedLayoutsAreRejected() {
        fun laidOut(spans: String) = """{"v":1,"type":"laidOut","requestId":1,"width":1,"height":1,"spans":$spans,"failed":[]}"""
        assertTrue(BridgeProtocol.decode(laidOut("[[0,1,0,0]]")) is Decoded.Accepted)
        // Inline maths reports its baseline as a fifth value (Phase 16).
        val withBaseline = BridgeProtocol.decode(laidOut("[[0,30,0,0,22.5]]")) as Decoded.Accepted
        assertEquals(22.5, (withBaseline.value as PageMessage.LaidOut).spans.single().baseline, 0.0)
        assertRejected(laidOut("[[0,30,0,0,22.5,1]]"))
        assertRejected(laidOut("[[0,1,0]]"))
        assertRejected(laidOut("[[5,1,0,0]]")) // ends before it starts
        assertRejected(laidOut("[[-1,1,0,0]]"))
        assertRejected(laidOut("""[["0",1,0,0]]"""))
        assertRejected(laidOut("[[0,1e999,0,0]]"))
    }

    @Test
    fun documentCommandsBeyondTheLimitsAreNotSent() {
        assertThrows { BridgeProtocol.encode(HostCommand.RenderDocument(1, List(BridgeProtocol.MAX_LIST_ITEMS + 1) { blocks[2] })) }
        assertThrows { BridgeProtocol.encode(HostCommand.RenderDocument(1, listOf(DocumentBlock(DocumentBlock.Kind.HEADING, "x", level = 7)))) }
        assertThrows { BridgeProtocol.encode(HostCommand.RenderDocument(1, listOf(DocumentBlock(DocumentBlock.Kind.FIGURE, "", width = 5000.0)))) }
        assertThrows { BridgeProtocol.encode(HostCommand.RenderDocument(1, List(10) { DocumentBlock(DocumentBlock.Kind.PARAGRAPH, "x".repeat(BridgeProtocol.MAX_LATEX_CHARS)) })) }
    }

    @Test
    fun gateRejectsForeignOriginsAndSubframes() {
        val reasons = mutableListOf<String>()
        val gate = MessageGate { reasons += it }
        val valid = BridgeProtocol.encode(PageMessage.Changed("x"))

        assertNull(gate.receive("https://evil.example", true, valid))
        assertNull(gate.receive("http://appassets.androidplatform.net", true, valid))
        assertNull(gate.receive("https://appassets.androidplatform.net.evil.example", true, valid))
        assertNull(gate.receive("null", true, valid))
        assertNull(gate.receive(MessageGate.ASSET_ORIGIN, false, valid))
        assertNull(gate.receive(MessageGate.ASSET_ORIGIN, true, "garbage"))
        assertEquals(6, gate.rejectedCount)
        assertEquals(6, reasons.size)

        assertEquals(PageMessage.Changed("x"), gate.receive(MessageGate.ASSET_ORIGIN, true, valid))
        assertEquals(6, gate.rejectedCount)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: ProtocolException) {
            return
        }
        fail("expected ProtocolException")
    }
}
