package app.convoyage.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.convoyage.api.ApiResult
import app.convoyage.api.ConvoyApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File

/** Where a picture comes from: a file on this phone, or a server media path that needs the bearer token. */
sealed interface ImageSource {
    val key: String

    data class Local(val file: File) : ImageSource {
        override val key get() = "file:${file.path}:${file.lastModified()}"
    }

    /** `/v1/media/<id>` or `/v1/media/<id>/thumb`. */
    data class Remote(val path: String) : ImageSource {
        override val key get() = "remote:$path"
    }
}

/**
 * Loads and decodes pictures, sampled down to what the screen needs, with a memory cache.
 *
 * Media on the server needs `Authorization: Bearer`, which rules out the usual image libraries without extra
 * plumbing, and this app has very few pictures: ride photos, thumbnails in posts.
 */
class ImageLoader(private val scope: CoroutineScope, private val api: ConvoyApi) {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()

    fun cached(source: ImageSource, maxEdgePx: Int): ImageBitmap? = cache.get(cacheKey(source, maxEdgePx))

    suspend fun load(source: ImageSource, maxEdgePx: Int): ImageBitmap? {
        val k = cacheKey(source, maxEdgePx)
        cache.get(k)?.let { return it }
        val job = synchronized(inFlight) {
            inFlight.getOrPut(k) {
                scope.async(Dispatchers.IO) {
                    val bmp = try {
                        when (source) {
                            is ImageSource.Local -> decodeFile(source.file, maxEdgePx)
                            is ImageSource.Remote -> (api.fetchBytes(source.path) as? ApiResult.Ok)?.value?.let { decodeBytes(it, maxEdgePx) }
                        }
                    } catch (_: Exception) {
                        null
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                    val image = bmp?.asImageBitmap()
                    if (image != null) cache.put(k, image)
                    synchronized(inFlight) { inFlight.remove(k) }
                    image
                }
            }
        }
        return job.await()
    }

    fun clear() = cache.evictAll()

    private fun cacheKey(s: ImageSource, edge: Int) = "${s.key}@$edge"

    companion object {
        /** Largest power of two that keeps the long edge at or above [target]. */
        fun sampleSize(width: Int, height: Int, target: Int): Int {
            var sample = 1
            val longEdge = maxOf(width, height)
            while (longEdge / (sample * 2) >= target) sample *= 2
            return sample
        }

        fun decodeBytes(bytes: ByteArray, maxEdgePx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx) }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }

        /** A camera file: sampled, then turned upright by its EXIF orientation. */
        fun decodeFile(file: File, maxEdgePx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx) }
            val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
            return upright(bmp, file)
        }

        fun upright(bmp: Bitmap, file: File): Bitmap {
            val degrees = try {
                degreesOf(ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
            } catch (_: Exception) {
                0f
            }
            return rotated(bmp, degrees)
        }

        fun degreesOf(exifOrientation: Int): Float = when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        fun rotated(bmp: Bitmap, degrees: Float): Bitmap =
            if (degrees == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)

        /**
         * A picture chosen from the gallery, as a JPEG file the server will take: any format Android can decode (HEIC
         * included, which the server refuses), turned upright, at most 2560 px on the long edge.
         */
        fun importPicked(resolver: android.content.ContentResolver, uri: android.net.Uri, outDir: File): File? = try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val degrees = degreesOf(ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
            val bmp = decodeBytes(bytes, 2560)?.let { rotated(it, degrees) } ?: return null
            outDir.mkdirs()
            File(outDir, "pick-${System.currentTimeMillis()}.jpg").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        } catch (_: Exception) {
            null
        }

        /**
         * Keeps a camera photo under the server's 15 MB: the original if it is small enough, else a JPEG sampled
         * to at most 2560 px on the long edge (the server's own cap), turned upright because the re-encode drops EXIF.
         */
        fun prepareForUpload(file: File, cacheDir: File): File {
            if (file.length() <= 12L * 1024 * 1024) return file
            val bmp = decodeFile(file, 2560) ?: return file
            val out = File(cacheDir, "upload-${file.nameWithoutExtension}.jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            return out
        }
    }
}
