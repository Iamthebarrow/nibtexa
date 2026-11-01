package io.github.iamthebarrow.nibtexa.feature.mathedit.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The typed message protocol between the app and the local math pages (see
 * docs/design/MATH_BRIDGE.md). Every message is a JSON object `{"v": 1, "type": ..., ...}`.
 *
 * Decoding is strict: an unknown type, a missing or mistyped field, an unexpected field, a
 * wrong version or an oversized message is rejected, never partially accepted. The page
 * applies the same rules to the commands it receives (assets/mathedit/bridge.js).
 */
object BridgeProtocol {
    const val VERSION = 1
    const val MAX_MESSAGE_CHARS = 64 * 1024
    const val MAX_LATEX_CHARS = 8 * 1024
    const val MAX_LIST_ITEMS = 200

    private val json = Json

    fun decode(raw: String?): Decoded<PageMessage> = decodeWith(raw) { type, fields ->
        when (type) {
            "ready" -> fields.only("page").let { PageMessage.Ready(it.string("page", 64)) }
            "changed" -> fields.only("latex").let { PageMessage.Changed(it.latex("latex")) }
            "value" -> fields.only("requestId", "latex").let { PageMessage.Value(it.id("requestId"), it.latex("latex")) }
            "rendered" -> fields.only("requestId", "count", "millis", "failed", "values").let {
                PageMessage.Rendered(
                    requestId = it.id("requestId"),
                    count = it.count("count"),
                    millis = it.number("millis"),
                    failed = it.list("failed") { e -> e.index() },
                    values = it.list("values") { e -> e.latexValue() },
                )
            }
            "laidOut" -> fields.only("requestId", "width", "height", "spans", "failed").let {
                PageMessage.LaidOut(
                    requestId = it.id("requestId"),
                    width = it.number("width"),
                    height = it.number("height"),
                    spans = it.list("spans") { e -> e.span() },
                    failed = it.list("failed") { e -> e.index() },
                )
            }
            "keyboard" -> fields.only("height").let {
                PageMessage.Keyboard(it.number("height").takeIf { h -> h <= MAX_BOX } ?: throw ProtocolException("'height' too large"))
            }
            "error" -> fields.only("requestId", "message").let {
                PageMessage.PageError(it.optionalId("requestId"), it.string("message", 1024))
            }
            else -> throw ProtocolException("unknown message type '$type'")
        }
    }

    /** Decodes a command as the page would; used to test that the two sides agree. */
    fun decodeCommand(raw: String?): Decoded<HostCommand> = decodeWith(raw) { type, fields ->
        when (type) {
            "setValue" -> fields.only("requestId", "latex").let { HostCommand.SetValue(it.id("requestId"), it.latex("latex")) }
            "getValue" -> fields.only("requestId").let { HostCommand.GetValue(it.id("requestId")) }
            "focus" -> fields.only().let { HostCommand.Focus }
            "renderList" -> fields.only("requestId", "items", "align").let {
                HostCommand.RenderList(it.id("requestId"), it.list("items") { e -> e.latexValue() }, it.listAlign("align"))
            }
            "renderDocument" -> fields.only("requestId", "blocks").let {
                HostCommand.RenderDocument(it.id("requestId"), it.list("blocks") { e -> e.documentBlock() })
            }
            else -> throw ProtocolException("unknown command type '$type'")
        }
    }

    /** @throws ProtocolException if the command breaks a limit the page would enforce. */
    fun encode(command: HostCommand): String {
        val obj = buildJsonObject {
            put("v", VERSION)
            when (command) {
                is HostCommand.SetValue -> {
                    put("type", "setValue")
                    put("requestId", command.requestId)
                    put("latex", checkLatex(command.latex))
                }
                is HostCommand.GetValue -> {
                    put("type", "getValue")
                    put("requestId", command.requestId)
                }
                HostCommand.Focus -> put("type", "focus")
                is HostCommand.RenderList -> {
                    if (command.items.size > MAX_LIST_ITEMS) throw ProtocolException("too many items: ${command.items.size}")
                    put("type", "renderList")
                    put("requestId", command.requestId)
                    putJsonArray("items") { command.items.forEach { add(JsonPrimitive(checkLatex(it))) } }
                    // Left is the page's default, so the field is only sent otherwise.
                    if (command.align != ListAlign.LEFT) put("align", command.align.wire)
                }
                is HostCommand.RenderDocument -> {
                    if (command.blocks.size > MAX_LIST_ITEMS) throw ProtocolException("too many blocks: ${command.blocks.size}")
                    put("type", "renderDocument")
                    put("requestId", command.requestId)
                    putJsonArray("blocks") {
                        command.blocks.forEach { b ->
                            if (b.level !in 0..3) throw ProtocolException("heading level out of range: ${b.level}")
                            if (b.width !in 0.0..MAX_BOX || b.height !in 0.0..MAX_BOX) throw ProtocolException("figure box out of range")
                            add(buildJsonObject {
                                put("kind", b.kind.wire)
                                put("text", checkLatex(b.text))
                                put("level", b.level)
                                put("width", b.width)
                                put("height", b.height)
                            })
                        }
                    }
                }
            }
        }
        val text = obj.toString()
        if (text.length > MAX_MESSAGE_CHARS) throw ProtocolException("message too large: ${text.length} chars")
        return text
    }

    /** Encodes a page message; the real page builds these in JavaScript. For tests. */
    fun encode(message: PageMessage): String = buildJsonObject {
        put("v", VERSION)
        when (message) {
            is PageMessage.Ready -> { put("type", "ready"); put("page", message.page) }
            is PageMessage.Changed -> { put("type", "changed"); put("latex", message.latex) }
            is PageMessage.Value -> { put("type", "value"); put("requestId", message.requestId); put("latex", message.latex) }
            is PageMessage.Rendered -> {
                put("type", "rendered")
                put("requestId", message.requestId)
                put("count", message.count)
                put("millis", message.millis)
                putJsonArray("failed") { message.failed.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("values") { message.values.forEach { add(JsonPrimitive(it)) } }
            }
            is PageMessage.LaidOut -> {
                put("type", "laidOut")
                put("requestId", message.requestId)
                put("width", message.width)
                put("height", message.height)
                putJsonArray("spans") {
                    message.spans.forEach { s ->
                        val values = listOf(s.top, s.bottom, s.imageLeft, s.imageTop) + listOfNotNull(s.baseline.takeIf { it != 0.0 })
                        add(JsonArray(values.map(::JsonPrimitive)))
                    }
                }
                putJsonArray("failed") { message.failed.forEach { add(JsonPrimitive(it)) } }
            }
            is PageMessage.Keyboard -> { put("type", "keyboard"); put("height", message.height) }
            is PageMessage.PageError -> {
                put("type", "error")
                message.requestId?.let { put("requestId", it) }
                put("message", message.message)
            }
        }
    }.toString()

    private fun <T> decodeWith(raw: String?, body: (String, Fields) -> T): Decoded<T> {
        if (raw == null) return Decoded.Rejected("not a text message")
        if (raw.length > MAX_MESSAGE_CHARS) return Decoded.Rejected("message too large: ${raw.length} chars")
        val obj = try {
            json.parseToJsonElement(raw) as? JsonObject ?: return Decoded.Rejected("not a JSON object")
        } catch (e: IllegalArgumentException) {
            return Decoded.Rejected("malformed JSON")
        }
        val version = (obj["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (version != VERSION.toLong()) return Decoded.Rejected("unsupported version")
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Decoded.Rejected("missing type")
        return try {
            Decoded.Accepted(body(type, Fields(obj - "v" - "type")))
        } catch (e: ProtocolException) {
            Decoded.Rejected(e.message ?: "invalid message")
        }
    }

    private fun checkLatex(latex: String): String {
        if (latex.length > MAX_LATEX_CHARS) throw ProtocolException("LaTeX too long: ${latex.length} chars")
        return latex
    }

    private class Fields(private val values: Map<String, JsonElement>) {
        /** Rejects any field not listed; optional fields may still be absent. */
        fun only(vararg allowed: String): Fields {
            val unexpected = values.keys - allowed.toSet()
            if (unexpected.isNotEmpty()) throw ProtocolException("unexpected field(s) ${unexpected.sorted()}")
            return this
        }

        fun string(name: String, maxChars: Int): String {
            val p = values[name] as? JsonPrimitive
            if (p == null || !p.isString) throw ProtocolException("'$name' must be a string")
            if (p.content.length > maxChars) throw ProtocolException("'$name' too long")
            return p.content
        }

        fun latex(name: String): String = string(name, MAX_LATEX_CHARS)

        /** An optional list alignment: absent is left. */
        fun listAlign(name: String): ListAlign {
            if (name !in values) return ListAlign.LEFT
            val wire = string(name, 8)
            return ListAlign.entries.firstOrNull { it.wire == wire } ?: throw ProtocolException("'$name' must be left, center or right")
        }

        fun id(name: String): Long = optionalId(name) ?: throw ProtocolException("'$name' is required")

        fun optionalId(name: String): Long? {
            val element = values[name] ?: return null
            if (element is JsonNull) return null
            return (element as? JsonPrimitive)?.integer()?.takeIf { it >= 0 }
                ?: throw ProtocolException("'$name' must be a non-negative integer")
        }

        fun count(name: String): Int =
            (values[name] as? JsonPrimitive)?.integer()?.takeIf { it in 0..MAX_LIST_ITEMS }?.toInt()
                ?: throw ProtocolException("'$name' must be an integer in 0..$MAX_LIST_ITEMS")

        fun number(name: String): Double {
            val p = values[name] as? JsonPrimitive
            val d = p?.takeUnless { it.isString }?.doubleOrNull
            if (d == null || d.isNaN() || d < 0) throw ProtocolException("'$name' must be a non-negative number")
            return d
        }

        fun <T> list(name: String, item: (JsonElement) -> T): List<T> {
            val array = values[name] as? JsonArray ?: throw ProtocolException("'$name' must be an array")
            if (array.size > MAX_LIST_ITEMS) throw ProtocolException("'$name' has too many items")
            return array.map(item)
        }
    }

    private fun JsonPrimitive.integer(): Long? =
        takeUnless { it.isString || it.booleanOrNull != null }?.longOrNull

    private fun JsonElement.index(): Int =
        (this as? JsonPrimitive)?.integer()?.takeIf { it in 0 until MAX_LIST_ITEMS }?.toInt()
            ?: throw ProtocolException("list index out of range")

    /**
     * `[top, bottom, imageLeft, imageTop]`, and for inline maths a fifth, its baseline (Phase 16),
     * in CSS pixels; each a finite number in 0..[MAX_EXTENT].
     */
    private fun JsonElement.span(): BlockSpan {
        val values = (this as? JsonArray)?.takeIf { it.size == 4 || it.size == 5 }?.map { e ->
            (e as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..MAX_EXTENT }
                ?: throw ProtocolException("span values must be numbers in 0..$MAX_EXTENT")
        } ?: throw ProtocolException("a span must be four or five numbers")
        if (values[1] < values[0]) throw ProtocolException("span ends before it starts")
        return BlockSpan(values[0], values[1], values[2], values[3], values.getOrElse(4) { 0.0 })
    }

    private fun JsonElement.documentBlock(): DocumentBlock {
        val obj = this as? JsonObject ?: throw ProtocolException("a block must be an object")
        val fields = Fields(obj).only("kind", "text", "level", "width", "height")
        val kind = fields.string("kind", 16).let { wire -> DocumentBlock.Kind.entries.firstOrNull { it.wire == wire } }
            ?: throw ProtocolException("unknown block kind")
        val level = (obj["level"] as? JsonPrimitive)?.integer()?.takeIf { it in 0..3 }?.toInt()
            ?: throw ProtocolException("'level' must be an integer in 0..3")
        val width = fields.number("width").takeIf { it <= MAX_BOX } ?: throw ProtocolException("'width' too large")
        val height = fields.number("height").takeIf { it <= MAX_BOX } ?: throw ProtocolException("'height' too large")
        return DocumentBlock(kind, fields.latex("text"), level, width, height)
    }

    /** Largest figure box, in CSS pixels (an A4 page is 595 wide). */
    const val MAX_BOX = 2000.0

    /** Largest coordinate a laid-out document may report, in CSS pixels. */
    const val MAX_EXTENT = 1_000_000.0

    private fun JsonElement.latexValue(): String {
        val p = this as? JsonPrimitive
        if (p == null || !p.isString || p.content.length > MAX_LATEX_CHARS) throw ProtocolException("list item must be LaTeX text")
        return p.content
    }
}

/** Messages the page sends to the app. */
sealed interface PageMessage {
    data class Ready(val page: String) : PageMessage
    data class Changed(val latex: String) : PageMessage
    data class Value(val requestId: Long, val latex: String) : PageMessage

    /** Result of [HostCommand.RenderList]: indices that failed to render and each item's value. */
    data class Rendered(
        val requestId: Long,
        val count: Int,
        val millis: Double,
        val failed: List<Int>,
        val values: List<String>,
    ) : PageMessage

    /**
     * Result of [HostCommand.RenderDocument]: the laid-out document's size and each block's
     * extent, in CSS pixels from the document's top-left; blocks at [failed] could not render.
     */
    data class LaidOut(
        val requestId: Long,
        val width: Double,
        val height: Double,
        val spans: List<BlockSpan>,
        val failed: List<Int>,
    ) : PageMessage

    /**
     * The editor's virtual keyboard is showing at [height] CSS pixels, or hidden (0), so the app
     * can size the editor to fit both the field and the keyboard (Phase 14).
     */
    data class Keyboard(val height: Double) : PageMessage

    data class PageError(val requestId: Long?, val message: String) : PageMessage
}

/** A block's vertical extent; for a figure, where its empty image box starts; for inline maths, its baseline. */
data class BlockSpan(val top: Double, val bottom: Double, val imageLeft: Double = 0.0, val imageTop: Double = 0.0, val baseline: Double = 0.0)

/**
 * One block of a document page (Phase 12, the PDF export). The page builds it from plain text
 * only: [text] is set as text content (or as MathLive's value for [Kind.MATH]), never as HTML.
 * A figure is an empty [width] x [height] box with [text] as its caption; the app draws the ink.
 */
data class DocumentBlock(val kind: Kind, val text: String, val level: Int = 0, val width: Double = 0.0, val height: Double = 0.0) {
    enum class Kind(val wire: String) { TITLE("title"), HEADING("heading"), PARAGRAPH("paragraph"), MATH("math"), FIGURE("figure"),

        /** Maths set inline, at body text size, so its baseline can be reported (Phase 16). */
        INLINE_MATH("inline-math"),
    }
}

/** Commands the app sends to the page. */
sealed interface HostCommand {
    data class SetValue(val requestId: Long, val latex: String) : HostCommand
    data class GetValue(val requestId: Long) : HostCommand
    data object Focus : HostCommand
    /** Renders [items] read-only, one per line, each placed [align] across the page (Phase 15). */
    data class RenderList(val requestId: Long, val items: List<String>, val align: ListAlign = ListAlign.LEFT) : HostCommand
    data class RenderDocument(val requestId: Long, val blocks: List<DocumentBlock>) : HostCommand
}

sealed interface Decoded<out T> {
    data class Accepted<T>(val value: T) : Decoded<T>
    data class Rejected(val reason: String) : Decoded<Nothing>
}

class ProtocolException(message: String) : Exception(message)

/** Where a read-only list places its equations across the page. */
enum class ListAlign(val wire: String) { LEFT("left"), CENTER("center"), RIGHT("right") }
