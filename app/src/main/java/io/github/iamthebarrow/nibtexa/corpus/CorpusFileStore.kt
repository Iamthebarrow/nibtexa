package io.github.iamthebarrow.nibtexa.corpus

import io.github.iamthebarrow.nibtexa.core.model.corpus.CorpusSampleFiles
import java.io.File
import java.io.IOException

/**
 * Writes corpus samples to [directory], typically the app-specific external files
 * directory, so the developer can collect them with
 * `adb pull /sdcard/Android/data/<package>/files/corpus`.
 *
 * Each file is written to a temporary name and then renamed, so a crash or process death
 * never leaves a half-written sample behind under its final name.
 */
class CorpusFileStore(private val directory: File) {

    /** @return the written files. @throws IOException if writing fails or the sample exists. */
    fun save(sample: CorpusSampleFiles): List<File> {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
        val targets = listOf(
            File(directory, "${sample.baseName}.inkml") to sample.inkMl,
            File(directory, "${sample.baseName}.json") to sample.json,
        )
        targets.firstOrNull { it.first.exists() }?.let { throw IOException("${it.first.name} already exists") }

        val written = mutableListOf<File>()
        try {
            for ((target, content) in targets) {
                val temp = File(directory, "${target.name}.tmp")
                temp.writeText(content, Charsets.UTF_8)
                if (!temp.renameTo(target)) {
                    temp.delete()
                    throw IOException("Cannot rename ${temp.name}")
                }
                written += target
            }
        } catch (e: IOException) {
            written.forEach { it.delete() }
            throw e
        }
        return written
    }
}
