package io.github.iamthebarrow.nibtexa.core.model.serialization

import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Clip
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page

/** A clipboard entry as text (Phase 26): one page of a [BookletJson] document, so it keeps whatever a booklet keeps. */
object ClipJson {
    fun encode(clip: Clip): String {
        val next = (clip.strokes.maxOfOrNull { it.id } ?: 0) + 1
        return BookletJson.encode(Booklet(ID, ID, listOf(Page(ID, InkDocument(clip.strokes, next), clip.regions))))
    }

    /** @throws InkFormatException if the text is not a clip. */
    fun decode(text: String): Clip {
        val page = BookletJson.decode(text).pages.singleOrNull() ?: throw InkFormatException("A clip is one page")
        return Clip(page.ink.strokes, page.regions)
    }

    private const val ID = "clip"
}
