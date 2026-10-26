package com.troy.autotag.audio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.troy.autotag.net.Http
import java.io.ByteArrayOutputStream

/** Downloads cover art and keeps it small enough to embed. */
object ArtworkFetcher {

    private val cache = HashMap<String, ByteArray>()

    class Artwork(val bytes: ByteArray, val mime: String)

    fun fetch(url: String, maxSize: Int, log: (String) -> Unit): Artwork? {
        val key = "$url@$maxSize"
        cache[key]?.let { return Artwork(it, mimeOf(it)) }
        val raw = Http.getBytes(url, mapOf("Accept" to "image/*"), 25000) ?: run {
            log("      커버 다운로드 실패")
            return null
        }
        if (raw.size < 512) {
            log("      커버 데이터가 너무 작음")
            return null
        }
        val shrunk = if (maxSize > 0) shrink(raw, maxSize) else raw
        if (cache.size > 40) cache.clear()
        cache[key] = shrunk
        return Artwork(shrunk, mimeOf(shrunk))
    }

    fun shrink(raw: ByteArray, maxSize: Int): ByteArray {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0 || (width <= maxSize && height <= maxSize)) return raw
            var scale = 1
            while (width / (scale * 2) >= maxSize || height / (scale * 2) >= maxSize) scale *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = scale }
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return raw
            val scaled = if (bitmap.width > maxSize || bitmap.height > maxSize) {
                val ratio = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        } catch (t: Throwable) {
            raw
        }
    }

    private fun mimeOf(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) return "image/png"
        return "image/jpeg"
    }
}
