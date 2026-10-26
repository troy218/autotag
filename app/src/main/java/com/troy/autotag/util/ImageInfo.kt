package com.troy.autotag.util

/**
 * Pure byte-level image inspection (no Android Bitmap needed, so it also runs
 * in JVM unit tests). Used to judge how good an existing cover is and whether
 * a downloaded candidate is actually an upgrade worth embedding.
 */
object ImageInfo {

    data class Size(val width: Int, val height: Int) {
        val area: Long get() = width.toLong() * height.toLong()
        val minSide: Int get() = minOf(width, height)
    }

    /** MIME type from the magic bytes, or null when it is not a known image. */
    fun mimeOf(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) return "image/jpeg"
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) return "image/png"
        if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) return "image/webp"
        if (bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) return "image/gif"
        return null
    }

    /** Pixel dimensions parsed from the image header, or null if unknown. */
    fun dimensions(bytes: ByteArray): Size? {
        return when (mimeOf(bytes)) {
            "image/png" -> pngSize(bytes)
            "image/gif" -> gifSize(bytes)
            "image/jpeg" -> jpegSize(bytes)
            "image/webp" -> webpSize(bytes)
            else -> null
        }
    }

    /**
     * True only when [candidate] is *clearly* better than [existing]:
     * a meaningfully larger picture, not just a re-encode of the same pixels.
     * Byte size is only a tie-breaker/ fallback because a fat low-res file is
     * not better than a lean high-res one.
     */
    fun isClearUpgrade(
        existingWidth: Int,
        existingHeight: Int,
        existingBytes: Int,
        candidateWidth: Int,
        candidateHeight: Int,
        candidateBytes: Int,
    ): Boolean {
        if (candidateWidth <= 0 || candidateHeight <= 0) return false
        val candidateArea = candidateWidth.toLong() * candidateHeight.toLong()
        if (existingWidth > 0 && existingHeight > 0) {
            val existingArea = existingWidth.toLong() * existingHeight.toLong()
            if (candidateWidth == existingWidth && candidateHeight == existingHeight) {
                // Same pixels: only worth it for a much less compressed copy.
                return existingBytes > 0 && candidateBytes > existingBytes * 2
            }
            return candidateWidth >= existingWidth && candidateHeight >= existingHeight &&
                candidateArea * 4 >= existingArea * 5 // >= 1.25x the pixels
        }
        // Existing dimensions unknown: demand a genuinely large candidate
        // that is also heavier than what is embedded now.
        return minOf(candidateWidth, candidateHeight) >= 800 &&
            (existingBytes <= 0 || candidateBytes > existingBytes)
    }

    // ------------------------------------------------------------------ parsers

    private fun pngSize(bytes: ByteArray): Size? {
        if (bytes.size < 24) return null
        val width = readIntBE(bytes, 16)
        val height = readIntBE(bytes, 20)
        return if (width > 0 && height > 0) Size(width, height) else null
    }

    private fun gifSize(bytes: ByteArray): Size? {
        if (bytes.size < 10) return null
        val width = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val height = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        return if (width > 0 && height > 0) Size(width, height) else null
    }

    private fun jpegSize(bytes: ByteArray): Size? {
        var i = 2 // skip SOI
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) {
                i++
                continue
            }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7 || marker == 0x01) {
                i += 2
                continue
            }
            if (i + 4 > bytes.size) return null
            val length = readShortBE(bytes, i + 2)
            if (length < 2) return null
            // SOF0..SOF15 except DHT(0xC4), JPG(0xC8), DAC(0xCC)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val height = readShortBE(bytes, i + 5)
                val width = readShortBE(bytes, i + 7)
                return if (width > 0 && height > 0) Size(width, height) else null
            }
            i += 2 + length
        }
        return null
    }

    private fun webpSize(bytes: ByteArray): Size? {
        if (bytes.size < 30) return null
        val chunk = String(bytes, 12, 4, Charsets.US_ASCII)
        return when (chunk) {
            "VP8X" -> {
                val width = 1 + readInt24LE(bytes, 24)
                val height = 1 + readInt24LE(bytes, 27)
                Size(width, height)
            }
            "VP8 " -> {
                if (bytes.size < 30) return null
                val width = readShortLE(bytes, 26) and 0x3FFF
                val height = readShortLE(bytes, 28) and 0x3FFF
                if (width > 0 && height > 0) Size(width, height) else null
            }
            "VP8L" -> {
                if (bytes.size < 25) return null
                val b0 = bytes[21].toInt() and 0xFF
                val b1 = bytes[22].toInt() and 0xFF
                val b2 = bytes[23].toInt() and 0xFF
                val b3 = bytes[24].toInt() and 0xFF
                val width = 1 + (b0 or ((b1 and 0x3F) shl 8))
                val height = 1 + ((b1 shr 6) or (b2 shl 2) or ((b3 and 0x0F) shl 10))
                Size(width, height)
            }
            else -> null
        }
    }

    private fun readIntBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)

    private fun readShortBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readShortLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readInt24LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}
