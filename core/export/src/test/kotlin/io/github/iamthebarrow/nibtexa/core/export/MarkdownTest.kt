package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.BookletInfo
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.MarkStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageEditor
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextColor
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markdown export and import (Phase 20): the app's own export comes back the same, and common Markdown reads. */
class MarkdownTest {
    private var ids = 0
    private fun import(source: String, pictures: Map<String, ImportedPicture> = emptyMap()) =
        MarkdownImporter.import(source, { pictures[it] }) { "p-${++ids}" }

    /** What must survive a round trip, block by block. */
    private fun document(pages: List<Page>): List<String> = Flow.of(Booklet("b-1", "T", pages)).flatten().mapNotNull { b ->
        when (b) {
            is FlowBlock.Heading -> "H${b.level} ${LatexExporter.rich(b.content)}"
            is FlowBlock.Paragraph -> "P${b.theorem?.kind?.let { " $it" } ?: ""} ${LatexExporter.rich(b.content)}"
            is FlowBlock.ListItem -> "L${b.level} ${b.marker} ${LatexExporter.rich(b.content)}"
            is FlowBlock.Math -> "M${b.number ?: ""} ${b.latex}"
            is FlowBlock.Figure -> "F ${b.region.caption}"
            else -> null
        }
    }

    private fun booklet(): Booklet {
        val e = PageEditor(Page("p-1"))
        var y = 0f
        fun region(value: String, role: RegionRole = RegionRole.TEXT, x: Float = 0f, marks: List<TextMark> = emptyList()): String {
            y += 80f
            e.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(x, y, 0), InkPoint(x + 100f, y + 24f, 10)), if (role == RegionRole.MATH) Pen.MATH else Pen.TEXT))
            val id = e.createRegion(setOf(e.page.ink.strokes.last().id), role)!!
            e.confirm(id, value, marks)
            return id
        }
        region("Transfer functions", RegionRole.HEADING, marks = listOf(TextMark(0, 8, MarkStyle.Bold)))
        val styled = "Bold italic mono link and note, with *stars* and [brackets]."
        fun at(word: String, style: MarkStyle) = styled.indexOf(word).let { TextMark(it, it + word.length, style) }
        region(styled, marks = listOf(
            at("Bold", MarkStyle.Bold), at("italic", MarkStyle.Italic), at("mono", MarkStyle.Typewriter),
            at("link", MarkStyle.Link("https://example.org/a_(b)")), at("note", MarkStyle.Footnote("100% sure")),
        ))
        region("The root \$s^2+1=0\$ costs \\\$5.")
        region("- poles")
        region("- stable", x = 40f)
        region("1. first", x = 80f)
        region("- zeros")
        e.setTheorem(region("A pole is a root."), TheoremKind.DEFINITION)
        e.setTheorem(region("By partial fractions."), TheoremKind.PROOF)
        e.setNumbered(region("H(s)=\\frac{1}{s+1}", RegionRole.MATH), true)
        region("y=x", RegionRole.MATH)
        e.setCaption(e.addPicture("photo.jpg", Bounds(0f, y + 100f, 300f, y + 300f)), "The board")
        e.setPageBreak(region("After the break."), true)
        return Booklet("b-1", "Signals & \"Systems\"", listOf(e.page), BookletInfo(author = "Ada"))
    }

    private fun exported(b: Booklet) = (MarkdownExporter.export(b) { _, r -> byteArrayOf(r.id.length.toByte()) } as ExportResult.Success).files

    @Test
    fun theExportIsMarkdownWithItsPictures() {
        val files = exported(booklet())
        assertEquals(listOf("main.md", "images/p-1-r12.jpg"), files.map { it.path })
        val md = files.first().content
        assertTrue(md, md.startsWith("---\ntitle: \"Signals & \\\"Systems\\\"\"\nauthor: \"Ada\"\n---\n"))
        assertTrue(md, "# **Transfer** functions" in md)
        assertTrue(md, "**Bold** *italic* `mono` [link](<https://example.org/a_(b)>) and note[^1], with \\*stars\\* and \\[brackets\\]." in md)
        assertTrue(md, "The root \$s^2+1=0\$ costs \\\$5." in md)
        assertTrue(md, "- poles\n    - stable\n        1. first\n- zeros" in md)
        assertTrue(md, "**Definition 1.** A pole is a root." in md)
        assertTrue(md, "*Proof.* By partial fractions." in md)
        assertTrue(md, "$$\nH(s)=\\frac{1}{s+1}\n\\tag{1}\n$$" in md)
        assertTrue(md, "![The board](images/p-1-r12.jpg)\n\n*The board*" in md)
        assertTrue(md, "\n---\n\nAfter the break." in md)
        assertTrue(md, md.endsWith("[^1]: 100% sure\n"))
    }

    @Test
    fun theAppsOwnExportImportsBackTheSame() {
        val booklet = booklet()
        val picture = ImportedPicture("new.jpg", 600, 400)
        val result = import(exported(booklet).first().content, mapOf("images/p-1-r12.jpg" to picture))
        assertEquals("Signals & \"Systems\"", result.title)
        assertEquals("Ada", result.author)
        assertEquals(document(booklet.pages), document(result.pages))
        assertEquals(2, result.pages.size)
        assertEquals("new.jpg", result.pages.flatMap { it.regions }.single { it.picture != null }.picture)
        assertTrue(result.pages.flatMap { it.regions }.all { it.typed && it.strokeIds.isEmpty() })
        assertEquals(emptyList<String>(), result.warnings)
    }

    @Test
    fun whatMarkdownCannotWriteIsNamed() {
        val e = PageEditor(Page("p-1"))
        e.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0), InkPoint(100f, 24f, 10)), Pen.TEXT))
        val id = e.createRegion(setOf(e.page.ink.strokes.last().id), RegionRole.TEXT)!!
        e.confirm(id, "red under", listOf(TextMark(0, 3, MarkStyle.Color(TextColor.RED)), TextMark(4, 9, MarkStyle.Underline)))
        assertEquals(listOf("underline", "colour"), MarkdownExporter.dropped(Booklet("b-1", "T", listOf(e.page))))
    }

    @Test
    fun commonMarkdownReads() {
        val result = import(
            """
            # Notes

            Intro with __bold__, _italic_, snake_case and a <https://example.org> link.
            Second line of the same paragraph.

            ## Part
            Setext
            ------

            * one
              continued
            * two
              1. nested

            ```kotlin
            val x = 1
            ```

            > A quote.

            | a | b |
            |---|---|
            | 1 | 2 |

            ![remote](https://example.org/x.png) ![missing](nowhere.png)

            ${'$'}${'$'}x^2${'$'}${'$'}
            """.trimIndent(),
        )
        // A single top heading at the start is the title, and the others move up.
        assertEquals("Notes", result.title)
        val doc = document(result.pages)
        assertEquals("P Intro with \\textbf{bold}, \\textit{italic}, snake\\_case and a \\href{https://example.org}{https://example.org} link. Second line of the same paragraph.", doc[0])
        assertEquals("H1 Part", doc[1])
        assertEquals("H1 Setext", doc[2])
        assertEquals("L0 • one continued", doc[3])
        assertEquals("L0 • two", doc[4])
        assertEquals("L1 1. nested", doc[5])
        assertTrue(doc[6], doc[6].startsWith("P \\texttt{val x = 1}"))
        assertTrue(doc.any { it == "P A quote." })
        assertTrue(doc.toString(), doc.any { it == "P a · b" } && doc.any { it == "P 1 · 2" })
        assertTrue(doc.any { "https://example.org/x.png" in it })
        assertEquals("M x^2", doc.last())
        assertTrue(result.warnings.any { "not downloaded" in it })
        assertTrue(result.warnings.any { "nowhere.png" in it })
        assertTrue(result.warnings.any { "Code blocks" in it })
        assertTrue(result.warnings.any { "Tables" in it })
    }

    @Test
    fun unsafeMathsIsLeftOut() {
        val result = import("Safe \$x\$ and unsafe \$\\input{x}\$.\n\n$$\\write18{rm}$$")
        assertEquals(listOf("P Safe \$x\$ and unsafe ."), document(result.pages))
        assertTrue(result.warnings.any { "maths was left out" in it })
    }
}
