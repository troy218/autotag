package com.troy.autotag.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageInfoTest {

    @Test
    fun jpegDimensionsFromFixture() {
        val jpeg = javaClass.classLoader!!.getResourceAsStream("tag/coverart.jpg")!!.use { it.readBytes() }
        assertEquals("image/jpeg", ImageInfo.mimeOf(jpeg))
        val size = ImageInfo.dimensions(jpeg)
        assertEquals(200, size?.width)
        assertEquals(200, size?.height)
    }

    @Test
    fun pngAndWebpDimensions() {
        // minimal PNG: signature + IHDR with width=1000, height=500
        val png = ByteArray(24)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
            .copyInto(png, 0)
        png[16] = 0; png[17] = 0; png[18] = 0x03; png[19] = 0xE8.toByte() // 1000 (BE)
        png[20] = 0; png[21] = 0; png[22] = 0x01; png[23] = 0xF4.toByte() // 500
        assertEquals("image/png", ImageInfo.mimeOf(png))
        assertEquals(ImageInfo.Size(1000, 500), ImageInfo.dimensions(png))

        // WebP VP8X: canvas 1400x1400 stored minus one
        val webp = ByteArray(30)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(webp, 0)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(webp, 8)
        "VP8X".toByteArray(Charsets.US_ASCII).copyInto(webp, 12)
        val stored = 1399
        webp[24] = (stored and 0xFF).toByte()
        webp[25] = ((stored shr 8) and 0xFF).toByte()
        webp[26] = ((stored shr 16) and 0xFF).toByte()
        webp[27] = webp[24]; webp[28] = webp[25]; webp[29] = webp[26]
        assertEquals("image/webp", ImageInfo.mimeOf(webp))
        assertEquals(ImageInfo.Size(1400, 1400), ImageInfo.dimensions(webp))

        assertNull(ImageInfo.dimensions(byteArrayOf(1, 2, 3)))
        assertNull(ImageInfo.mimeOf(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun clearUpgradeRules() {
        // clearly larger picture wins
        assertTrue(ImageInfo.isClearUpgrade(300, 300, 20_000, 1000, 1000, 90_000))
        // smaller or same-ish picture does not replace the existing cover
        assertFalse(ImageInfo.isClearUpgrade(1000, 1000, 90_000, 500, 500, 200_000))
        assertFalse(ImageInfo.isClearUpgrade(1000, 1000, 90_000, 1050, 1050, 95_000))
        // wider but shorter is not an upgrade
        assertFalse(ImageInfo.isClearUpgrade(800, 800, 50_000, 1600, 400, 60_000))
        // same pixels: only a much heavier (less compressed) file counts
        assertTrue(ImageInfo.isClearUpgrade(1000, 1000, 40_000, 1000, 1000, 100_000))
        assertFalse(ImageInfo.isClearUpgrade(1000, 1000, 90_000, 1000, 1000, 100_000))
        // unknown existing size: a big candidate heavier than the old file wins
        assertTrue(ImageInfo.isClearUpgrade(0, 0, 10_000, 1200, 1200, 80_000))
        assertFalse(ImageInfo.isClearUpgrade(0, 0, 10_000, 400, 400, 80_000))
        // unknown candidate size never replaces anything
        assertFalse(ImageInfo.isClearUpgrade(300, 300, 20_000, 0, 0, 500_000))
    }
}
