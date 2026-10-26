package com.troy.autotag.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LrcTest {

    @Test
    fun parsesLrcTimestamps() {
        val text = """
            [ar:아이유]
            [ti:좋은 날]
            [offset:0]
            [00:12.34]첫 줄
            [00:15.5]둘째 줄
            [01:02]third line
        """.trimIndent()
        val lines = Lrc.parse(text)
        assertEquals(3, lines.size)
        assertEquals(12340, lines[0].timeMs)
        assertEquals("첫 줄", lines[0].text)
        assertEquals(15500, lines[1].timeMs)
        assertEquals(62000, lines[2].timeMs)
        assertEquals("third line", lines[2].text)
    }

    @Test
    fun supportsMultipleTimestampsOnOneLine() {
        val lines = Lrc.parse("[00:01.00][00:05.00]반복 후렴")
        assertEquals(2, lines.size)
        assertEquals(1000, lines[0].timeMs)
        assertEquals(5000, lines[1].timeMs)
        assertEquals("반복 후렴", lines[1].text)
    }

    @Test
    fun appliesOffset() {
        // positive offset means "show earlier", so it is subtracted from the timestamp
        val earlier = Lrc.parse("[offset:500]\n[00:01.00]shifted")
        assertEquals(500, earlier[0].timeMs)
        val later = Lrc.parse("[offset:-500]\n[00:01.00]shifted")
        assertEquals(1500, later[0].timeMs)
        val early = Lrc.parse("[00:00.10]clamped")
        assertEquals(100, early[0].timeMs)
        val negative = Lrc.parse("[offset:1000]\n[00:00.10]clamped at zero")
        assertEquals(0, negative[0].timeMs)
    }

    @Test
    fun detectsSyncedText() {
        assertTrue(Lrc.isSynced("[00:12.34]hello"))
        assertFalse(Lrc.isSynced("just plain lyrics\nsecond line"))
        assertFalse(Lrc.isSynced(null))
        assertFalse(Lrc.isSynced(""))
    }

    @Test
    fun plainTextDropsTimestamps() {
        val plain = Lrc.toPlain("[00:01.00]one\n[00:02.00]two")
        assertEquals("one\ntwo", plain)
    }

    @Test
    fun lrcTextRoundTrip() {
        val lines = listOf(LyricLine(1000, "a"), LyricLine(62500, "b"))
        val text = Lrc.toLrcText(lines)
        assertEquals(listOf(LyricLine(1000, "a"), LyricLine(62500, "b")), Lrc.parse(text))
    }

    /**
     * SYLT payload layout: BOM + UTF-16LE text + 0x0000 + 4 byte big endian ms.
     */
    @Test
    fun syltPayloadLayout() {
        val payload = Lrc.buildSyltPayload(listOf(LyricLine(0x01020304, "hi")))
        // 2 (BOM) + 2*2 (text) + 2 (terminator) + 4 (timestamp)
        assertEquals(12, payload.size)
        assertEquals(0xFF.toByte(), payload[0])
        assertEquals(0xFE.toByte(), payload[1])
        assertEquals('h'.code.toByte(), payload[2])
        assertEquals(0, payload[3])
        assertEquals('i'.code.toByte(), payload[4])
        assertEquals(0, payload[5])
        assertEquals(0, payload[6])
        assertEquals(0, payload[7])
        assertEquals(0x01, payload[8])
        assertEquals(0x02, payload[9])
        assertEquals(0x03, payload[10])
        assertEquals(0x04, payload[11])
    }

    @Test
    fun syltPayloadSkipsEmptyLines() {
        val payload = Lrc.buildSyltPayload(
            listOf(LyricLine(0, ""), LyricLine(10, "x"), LyricLine(20, "  ")),
        )
        // only the non-blank line survives: BOM + 1 char + terminator + timestamp
        assertEquals(2 + 2 + 2 + 4, payload.size)
    }
}
