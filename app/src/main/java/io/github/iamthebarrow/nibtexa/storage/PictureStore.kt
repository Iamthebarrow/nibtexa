package io.github.iamthebarrow.nibtexa.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import io.github.iamthebarrow.nibtexa.core.export.ImportedPicture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * The pictures booklets use (Phase 20): images placed on pages, and imported PDF pages drawn under
 * the ink. Each is a file in [dir] with a random name; regions and pages refer to it by that name.
 * PNG and JPEG are kept as they are when they are small enough; anything else, or anything larger
 * than [MAX_SIDE] pixels on its long side, is decoded and stored again (JPEG stays JPEG).
 *
 * Files are only added here. [removeUnused] deletes the ones no booklet uses, when the app starts,
 * since undo can bring back a deleted picture while the app runs.
 */
class PictureStore(private val dir: File) {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** The file for [name], or null if the name is not one this store makes. */
    fun file(name: String): File? = if (NAME.matches(name)) File(dir, name).takeIf { it.isFile } else null

    /** The picture's bytes as stored (for the exports), or null if it is missing. */
    suspend fun bytes(name: String): ByteArray? = withContext(Dispatchers.IO) { file(name)?.readBytes() }

    /**
     * Stores an image from [bytes] (PNG, JPEG, or any type Android decodes) and returns its name and
     * size. Fails with [IOException] if it is not an image.
     */
    suspend fun add(bytes: ByteArray): ImportedPicture = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not a picture")
        val type = bounds.outMimeType
        val jpeg = type == "image/jpeg"
        if ((jpeg || type == "image/png") && max(bounds.outWidth, bounds.outHeight) <= MAX_SIDE) {
            return@withContext ImportedPicture(write(bytes, if (jpeg) "jpg" else "png"), bounds.outWidth, bounds.outHeight)
        }
        // Too large, or a type the exports can't use: decoded, scaled down, and stored again.
        val sample = Integer.highestOneBit((max(bounds.outWidth, bounds.outHeight) / MAX_SIDE).coerceAtLeast(1))
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Not a picture")
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
        add(bitmap, jpeg)
    }

    /** Stores [bitmap] (a rendered PDF page, or a scaled image) as JPEG or PNG. */
    suspend fun add(bitmap: Bitmap, jpeg: Boolean): ImportedPicture = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, JPEG_QUALITY, out)
        ImportedPicture(write(out.toByteArray(), if (jpeg) "jpg" else "png"), bitmap.width, bitmap.height)
    }

    private fun write(bytes: ByteArray, type: String): String {
        dir.mkdirs()
        val name = NotebookRepository.randomId() + NotebookRepository.randomId() + ".$type"
        val file = File(dir, name)
        val partial = File(dir, "$name.part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(file)) throw IOException("Could not store the picture")
        return name
    }

    /**
     * The picture as a bitmap at most [maxSide] pixels on its long side, for drawing, or null if it
     * is missing or cannot be decoded. Kept in a small cache. Call off the main thread.
     */
    fun bitmap(name: String, maxSide: Int = MAX_SIDE): Bitmap? {
        val key = "$name@$maxSide"
        cache.get(key)?.let { return it }
        val file = file(name) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /**
     * Stores a picture from a backup under its own [name] (Phase 21), written by [content]; one
     * already here is left as it is, since a name is only ever given to one picture.
     */
    fun restore(name: String, content: (java.io.OutputStream) -> Unit) {
        if (!NAME.matches(name)) throw IOException("Not a picture name")
        val file = File(dir, name)
        if (file.isFile) return
        dir.mkdirs()
        val partial = File(dir, "$name.part")
        try {
            partial.outputStream().use(content)
            if (!partial.renameTo(file)) throw IOException("Could not store the picture")
        } finally {
            partial.delete()
        }
    }

    /** Deletes the files [used] does not name, and any left half-written. */
    suspend fun removeUnused(used: Set<String>) = withContext(Dispatchers.IO) {
        val files = dir.listFiles() ?: return@withContext
        var removed = 0
        for (file in files) {
            if (file.name !in used && file.delete()) removed++
        }
        if (removed > 0) Log.i(TAG, "Removed $removed unused picture(s)")
    }

    companion object {
        const val DIR = "pictures"

        /** Pictures are kept at most this many pixels on their long side. */
        const val MAX_SIDE = 2400
        private const val JPEG_QUALITY = 90
        private const val CACHE_BYTES = 48 * 1024 * 1024
        private const val TAG = "PictureStore"
        private val NAME = Regex("[a-z0-9]{24}\\.(png|jpg)")
    }
}
