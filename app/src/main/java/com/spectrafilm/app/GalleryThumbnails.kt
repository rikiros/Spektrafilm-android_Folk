/*
 * Spektrafilm for Android — RAW gallery thumbnails. GPLv3.
 *
 * Fastest-first decode chain, all best-effort:
 *   1. the DNG's embedded preview via ExifInterface (no demosaic, ~ms);
 *   2. a LibRaw half-size proxy capped to the thumbnail edge (2x2 Bayer average, already
 *      upright: LibRaw applies the orientation itself, as the editor relies on);
 *   3. the platform decoder with inSampleSize.
 * Results are cached in memory (LRU) and on disk (cacheDir), keyed by Uri + mtime + size,
 * so a re-launch shows the grid instantly and a re-shot file regenerates.
 */
package com.spectrafilm.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.spectrafilm.libraw.RawDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

internal object GalleryThumbnails {
    private const val TARGET_EDGE = 384
    private const val MIN_EMBEDDED_EDGE = 160

    private val memory = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Native RAW decodes are memory-heavy; keep at most two in flight. */
    private val decodeSlots = Semaphore(2)

    fun cached(item: GalleryItem): Bitmap? = memory.get(key(item))

    suspend fun load(ctx: Context, item: GalleryItem): Bitmap? {
        val key = key(item)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val disk = File(dir(ctx), "$key.jpg")
            if (disk.isFile) {
                val bmp = runCatching { BitmapFactory.decodeFile(disk.path) }.getOrNull()
                if (bmp != null) {
                    memory.put(key, bmp)
                    return@withContext bmp
                }
            }
            val bmp = decodeSlots.withPermit { decode(ctx.contentResolver, item.uri) }
                ?: return@withContext null
            runCatching {
                val tmp = File(disk.path + ".tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                tmp.renameTo(disk)
            }
            memory.put(key, bmp)
            bmp
        }
    }

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "gallery_thumbs").apply { mkdirs() }

    private fun key(item: GalleryItem): String {
        val id = "${item.uri}|${item.lastModified}|${item.size}"
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    private fun decode(resolver: ContentResolver, uri: Uri): Bitmap? {
        var orientation = ExifInterface.ORIENTATION_NORMAL
        var embedded: Bitmap? = null
        runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
                if (exif.hasThumbnail()) embedded = exif.thumbnailBitmap
            }
        }
        embedded?.takeIf { max(it.width, it.height) >= MIN_EMBEDDED_EDGE }?.let {
            return orient(fit(it), orientation)
        }
        libRawProxy(resolver, uri)?.let { return fit(it) }
        platformDecode(resolver, uri)?.let { return orient(fit(it), orientation) }
        // Last resort: even a tiny embedded thumbnail beats a blank tile.
        return embedded?.let { orient(fit(it), orientation) }
    }

    private fun libRawProxy(resolver: ContentResolver, uri: Uri): Bitmap? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val settings = RawDecoder.Settings(halfSize = true, maxLongEdge = TARGET_EDGE)
            RawDecoder.decodeToLinear(pfd.fd, settings).use { linear ->
                linear.acquireDataLease().use { lease ->
                    linearProPhotoToBitmap(lease.data, linear.width, linear.height)
                }
            }
        }
    }.getOrNull()

    private fun platformDecode(resolver: ContentResolver, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return@runCatching null
        var sample = 1
        while (longEdge / (sample * 2) >= TARGET_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /**
     * Linear ProPhoto (D50) float RGB -> display sRGB, with a simple mean-luminance auto
     * exposure so dark/linear RAW proxies read like a normal preview. Thumbnail-grade only;
     * the editor does the real colour science.
     */
    private fun linearProPhotoToBitmap(data: ByteBuffer, width: Int, height: Int): Bitmap {
        val floats = data.asFloatBuffer()
        val n = width * height
        val rgb = FloatArray(n * 3)
        var lumSum = 0.0
        for (i in 0 until n) {
            val r = floats.get(i * 3)
            val g = floats.get(i * 3 + 1)
            val b = floats.get(i * 3 + 2)
            // ProPhoto (D50) -> linear sRGB (D65, Bradford).
            val sr = 2.0343697f * r - 0.7275347f * g - 0.3068350f * b
            val sg = -0.2288146f * r + 1.2317327f * g - 0.0029181f * b
            val sb = -0.0085558f * r - 0.1532651f * g + 1.1618209f * b
            rgb[i * 3] = sr
            rgb[i * 3 + 1] = sg
            rgb[i * 3 + 2] = sb
            lumSum += (0.2126f * sr + 0.7152f * sg + 0.0722f * sb).coerceAtLeast(0f)
        }
        val mean = (lumSum / n).toFloat()
        val gain = if (mean > 1e-6f) (0.18f / mean).coerceIn(0.25f, 16f) else 1f
        val pixels = IntArray(n)
        for (i in 0 until n) {
            val r = encode(rgb[i * 3] * gain)
            val g = encode(rgb[i * 3 + 1] * gain)
            val b = encode(rgb[i * 3 + 2] * gain)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun encode(linear: Float): Int {
        val v = linear.coerceIn(0f, 1f)
        val e = if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return (e * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun fit(src: Bitmap): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= TARGET_EDGE) return src
        val scale = TARGET_EDGE.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).roundToInt().coerceAtLeast(1),
            (src.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun orient(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}
