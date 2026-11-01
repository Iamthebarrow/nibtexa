package io.github.iamthebarrow.nibtexa.export

import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reading what the user chose to import (Phases 18 and 20): one `.tex` or `.md` file, or a project
 * as a `.zip` with its pictures. Only text and pictures are read, within limits, and a project's
 * files are reachable only by their place inside it: an `\input` or a picture path cannot leave the
 * project (no `..`, no absolute paths).
 */
object TexImport {
    /** A source to import: its main file, and the project's other text files and pictures by path. */
    class Source(
        val main: String,
        private val files: Map<String, String>,
        private val base: String,
        private val pictures: Map<String, ByteArray> = emptyMap(),
    ) {
        /** The project file [path] names, relative to the main file's folder, or null. */
        fun read(path: String): String? = resolve(path)?.let { files[it] }

        /**
         * The picture [path] names, relative to the main file's folder (Phase 20), or null. LaTeX may
         * leave out the extension, so `.png`, `.jpg` and `.jpeg` are tried after it.
         */
        fun picture(path: String): ByteArray? {
            val clean = resolve(path) ?: return null
            return sequenceOf("", ".png", ".jpg", ".jpeg").firstNotNullOfOrNull { pictures[clean + it] }
        }

        private fun resolve(path: String): String? {
            val clean = path.replace('\\', '/').trim()
            if (clean.isEmpty() || clean.startsWith("/") || clean.contains(':')) return null
            val parts = (base.split('/').filter { it.isNotEmpty() } + clean.split('/')).filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) return null
            return parts.joinToString("/")
        }
    }

    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    const val MAX_PROJECT_BYTES = 16 * 1024 * 1024
    const val MAX_FILES = 500

    /** Pictures in a project are read up to this much in all (Phase 20). */
    const val MAX_PICTURE_BYTES = 64 * 1024 * 1024
    private const val MAX_PICTURE_FILE_BYTES = 24 * 1024 * 1024
    private val PICTURE_TYPES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /** One `.tex` file. */
    fun tex(input: InputStream): Source = Source(text(input, MAX_FILE_BYTES), emptyMap(), "")

    /** One Markdown file (Phase 20); its pictures can only come with a `.zip`. */
    fun markdown(input: InputStream): Source = tex(input)

    /**
     * A LaTeX project `.zip`: its `.tex` files and pictures, and as the main file `main.tex`
     * (nearest the top), else the one with `\documentclass`, else the only one.
     */
    fun project(input: InputStream): Source = zip(input, "tex") { files ->
        files.keys.filter { it.substringAfterLast('/') == "main.tex" }.minByOrNull { it.count { c -> c == '/' } }
            ?: files.entries.firstOrNull { "\\documentclass" in it.value }?.key
            ?: files.keys.singleOrNull()
    }

    /**
     * A Markdown `.zip` (Phase 20), as the Markdown export writes it: its `.md` files and pictures,
     * and as the main file `main.md` (nearest the top), else `README.md`, else the only one.
     */
    fun markdownProject(input: InputStream): Source = zip(input, "md") { files ->
        fun named(name: String) = files.keys.filter { it.substringAfterLast('/').equals(name, ignoreCase = true) }.minByOrNull { it.count { c -> c == '/' } }
        named("main.md") ?: named("README.md") ?: files.keys.singleOrNull()
    }

    private fun zip(input: InputStream, extension: String, main: (Map<String, String>) -> String?): Source {
        val files = sortedMapOf<String, String>()
        val pictures = mutableMapOf<String, ByteArray>()
        var total = 0L
        var pictureTotal = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (entry.isDirectory || name.startsWith("/") || name.split('/').any { it == ".." }) continue
                val type = name.substringAfterLast('.', "").lowercase()
                when {
                    type == extension -> {
                        if (files.size >= MAX_FILES) throw IOException("The project has too many files")
                        val text = text(zip, MAX_FILE_BYTES)
                        total += text.length
                        if (total > MAX_PROJECT_BYTES) throw IOException("The project is too large")
                        files[name] = text
                    }
                    type in PICTURE_TYPES -> {
                        if (pictures.size >= MAX_FILES) continue
                        val bytes = bytes(zip, MAX_PICTURE_FILE_BYTES)
                        pictureTotal += bytes.size
                        if (pictureTotal > MAX_PICTURE_BYTES) throw IOException("The project's pictures are too large")
                        pictures[name] = bytes
                    }
                }
            }
        }
        val chosen = main(files) ?: throw IOException("The project has no main .$extension file")
        return Source(files.getValue(chosen), files, chosen.substringBeforeLast('/', ""), pictures)
    }

    /** UTF-8 text, at most [limit] bytes. */
    private fun text(input: InputStream, limit: Int): String = bytes(input, limit).toString(Charsets.UTF_8)

    private fun bytes(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException("The file is too large to import")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
