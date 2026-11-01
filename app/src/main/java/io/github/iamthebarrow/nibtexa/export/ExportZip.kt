package io.github.iamthebarrow.nibtexa.export

import io.github.iamthebarrow.nibtexa.core.export.ExportFile
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The LaTeX project as one `.zip` (Phase 12), saved wherever the user picks. Entries keep the
 * export's paths under one folder named after the booklet, in the export's order, with a fixed
 * timestamp, so the same booklet always zips to the same bytes.
 */
object ExportZip {
    /** 1980-01-01, the earliest time a zip entry can hold. */
    private const val FIXED_TIME = 315_532_800_000L

    fun write(folder: String, files: List<ExportFile>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry("$folder/${file.path}").apply { time = FIXED_TIME })
                zip.write(file.bytes)
                zip.closeEntry()
            }
        }
    }

    /** A file name from a booklet title: letters, digits, spaces, dashes and underscores only. */
    fun safeName(title: String): String = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(Regex("\\s+"), " ").take(60).ifEmpty { "NibTeXa" }
}
