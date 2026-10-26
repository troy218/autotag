package com.troy.autotag.audio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.troy.autotag.net.Http
import com.troy.autotag.util.ImageInfo
import java.io.ByteArrayOutputStream

/** Downloads cover art and keeps it small enough to embed. */
object ArtworkFetcher {

    // 여러 스레드(식별/쓰기 풀)가 동시에 부르므로 스레드 안전 맵을 쓴다.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    class Artwork(val bytes: ByteArray, val mime: String) {
        /** Pixel size of [bytes] (0 when it could not be determined). */
        val width: Int
        val height: Int

        init {
            val size = ImageInfo.dimensions(bytes)
            width = size?.width ?: 0
            height = size?.height ?: 0
        }

        fun sizeLabel(): String = if (width > 0 && height > 0) "${width}x$height" else "크기미상"
    }

    fun fetch(url: String, maxSize: Int, log: (String) -> Unit): Artwork? {
        val key = "$url@$maxSize"
        cache[key]?.let { return Artwork(it, mimeOf(it)) }
        var raw: ByteArray? = null
        for (candidate in candidateUrls(url)) {
            raw = Http.getBytes(candidate, mapOf("Accept" to "image/*"), 25000)
            if (raw != null && raw.size >= 512) break
            raw = null
        }
        raw ?: run {
            log("      커버 다운로드 실패")
            return null
        }
        if (raw.size < 512) {
            log("      커버 데이터가 너무 작음")
            return null
        }
        // 플레이어/태그가 제대로 못 읽는 WebP 등은 JPEG로 바꿔 넣는다.
        // (형식을 속여 넣으면 커버가 '없는 것처럼' 보이는 대표적인 원인)
        val needsJpeg = mimeOf(raw) == "image/webp"
        val shrunk = when {
            needsJpeg -> shrink(raw, if (maxSize > 0) maxSize else 4096, forceJpeg = true)
            maxSize > 0 -> shrink(raw, maxSize)
            else -> raw
        }
        if (cache.size > 40) cache.clear()
        cache[key] = shrunk
        return Artwork(shrunk, mimeOf(shrunk))
    }

    /**
     * 벅스 이미지 CDN은 같은 커버를 여러 크기로 제공한다
     * (`/album/images/<크기>/...`, 크기 = original/1000/500/200/50).
     * 큰 크기 요청이 실패하면 작은 크기로라도 받아온다.
     */
    fun candidateUrls(url: String): List<String> {
        val marker = "/album/images/"
        if (!url.contains("image.bugsm.co.kr") || !url.contains(marker)) return listOf(url)
        val out = ArrayList<String>()
        out.add(url)
        for (size in listOf("original", "1000", "500", "200")) {
            val replaced = url.replace(Regex("(/album/images/)[^/]+/")) { m ->
                m.groupValues[1] + size + "/"
            }
            if (replaced != url && !out.contains(replaced)) out.add(replaced)
        }
        return out
    }

    fun shrink(raw: ByteArray, maxSize: Int, forceJpeg: Boolean = false): ByteArray {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return raw
            if (!forceJpeg && width <= maxSize && height <= maxSize) return raw
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

    private fun mimeOf(bytes: ByteArray): String =
        ImageInfo.mimeOf(bytes) ?: "image/jpeg"
}
