package io.github.iamthebarrow.nibtexa.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reading an import (Phase 18): the project's main file is found, and `\input` stays inside the project. */
class TexImportTest {
    private fun zip(vararg files: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, text) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test
    fun theMainFileIsMainTexNearestTheTopAndInputsResolveFromItsFolder() {
        val source = TexImport.project(zip(
            "notes/main.tex" to "\\documentclass{article}",
            "notes/pages/p1.tex" to "Page one.",
            "other/deep/main.tex" to "not this",
            "picture.png" to "ignored",
        ))
        assertEquals("\\documentclass{article}", source.main)
        assertEquals("Page one.", source.read("pages/p1.tex"))
        assertEquals("Page one.", source.read("./pages/p1.tex"))
    }

    @Test
    fun withoutMainTexTheFileWithADocumentClassIsMain() {
        val source = TexImport.project(zip("a.tex" to "part", "notes.tex" to "\\documentclass{article} x"))
        assertEquals("\\documentclass{article} x", source.main)
    }

    @Test
    fun inputsCannotLeaveTheProject() {
        val source = TexImport.project(zip("p/main.tex" to "\\documentclass{article}", "secret.tex" to "outside the folder"))
        assertNull(source.read("../secret.tex"))
        assertNull(source.read("/secret.tex"))
        assertNull(source.read("C:/secret.tex"))
        assertNull(source.read("pages/../../secret.tex"))
    }

    @Test
    fun aProjectWithoutTexFilesIsRefused() {
        assertThrows(IOException::class.java) { TexImport.project(zip("readme.txt" to "hello")) }
    }

    @Test
    fun aSingleFileIsReadAsUtf8() {
        val source = TexImport.tex(ByteArrayInputStream("Résumé — ok".toByteArray(Charsets.UTF_8)))
        assertEquals("Résumé — ok", source.main)
        assertNull(source.read("anything"))
    }

    @Test
    fun picturesComeFromInsideTheProjectWithOrWithoutTheirExtension() {
        val source = TexImport.project(zip("p/main.tex" to "\\documentclass{article}", "p/assets/plot.png" to "png", "outside.jpg" to "jpg"))
        assertEquals("png", source.picture("assets/plot.png")?.toString(Charsets.UTF_8))
        assertEquals("png", source.picture("assets/plot")?.toString(Charsets.UTF_8))
        assertNull(source.picture("../outside.jpg"))
        assertNull(source.picture("assets/missing.png"))
    }

    @Test
    fun aMarkdownProjectsMainFileIsMainMdThenReadme() {
        val withMain = TexImport.markdownProject(zip("notes/README.md" to "readme", "notes/main.md" to "main", "notes/images/a.jpg" to "jpg"))
        assertEquals("main", withMain.main)
        assertEquals("jpg", withMain.picture("images/a.jpg")?.toString(Charsets.UTF_8))
        assertEquals("readme", TexImport.markdownProject(zip("README.md" to "readme", "other.md" to "x")).main)
        assertThrows(IOException::class.java) { TexImport.markdownProject(zip("a.md" to "a", "b.md" to "b")) }
    }
}
