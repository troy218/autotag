package com.troy.autotag.lyrics

/** One line of a synced lyric file. */
data class LyricLine(val timeMs: Int, val text: String)

/**
 * LRC handling: parsing downloaded lyrics, building ID3 SYLT payloads and
 * creating ".lrc" side car files.
 */
object Lrc {

    private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val OFFSET_TAG = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
    private val METADATA_TAG = Regex("^\\[(ar|al|ti|by|re|ve|length|offset):.*]$", RegexOption.IGNORE_CASE)

    /** True if the text looks like it contains timestamps. */
    fun isSynced(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return TIME_TAG.containsMatchIn(text)
    }

    fun parse(text: String?): List<LyricLine> {
        if (text.isNullOrBlank()) return emptyList()
        var offset = 0
        OFFSET_TAG.find(text)?.let { offset = it.groupValues[1].toIntOrNull() ?: 0 }
        val out = ArrayList<LyricLine>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || METADATA_TAG.matches(line)) continue
            val matches = TIME_TAG.findAll(line).toList()
            if (matches.isEmpty()) continue
            val content = line.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val minutes = m.groupValues[1].toIntOrNull() ?: continue
                val seconds = m.groupValues[2].toIntOrNull() ?: continue
                val fractionRaw = m.groupValues[3]
                val millis = when {
                    fractionRaw.isEmpty() -> 0
                    fractionRaw.length == 1 -> fractionRaw.toInt() * 100
                    fractionRaw.length == 2 -> fractionRaw.toInt() * 10
                    else -> fractionRaw.substring(0, 3).toInt()
                }
                val time = minutes * 60_000 + seconds * 1000 + millis - offset
                out.add(LyricLine(time.coerceAtLeast(0), content))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Removes timestamps, used when only a text version is available. */
    fun toPlain(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val lines = parse(text)
        if (lines.isEmpty()) return text.trim().ifBlank { null }
        return lines.joinToString("\n") { it.text.trim() }.trim().ifBlank { null }
    }

    /** Re-serializes parsed lines (drops metadata, keeps ms precision). */
    fun toLrcText(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        for (line in lines) {
            val minutes = line.timeMs / 60_000
            val seconds = (line.timeMs % 60_000) / 1000
            val centis = (line.timeMs % 1000) / 10
            sb.append('[')
            sb.append(minutes.toString().padStart(2, '0'))
            sb.append(':')
            sb.append(seconds.toString().padStart(2, '0'))
            sb.append('.')
            sb.append(centis.toString().padStart(2, '0'))
            sb.append(']')
            sb.append(line.text)
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Builds the raw payload of an ID3 SYLT frame.
     *
     * Layout per entry (ID3v2 spec): <text><terminator><4 byte timestamp>
     * with UTF-16 (with BOM) text encoding, which is what desktop taggers write.
     */
    fun buildSyltPayload(lines: List<LyricLine>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        for (line in lines) {
            val text = line.text
            if (text.isEmpty()) continue
            out.write(bom)
            out.write(text.toByteArray(Charsets.UTF_16LE))
            out.write(0)
            out.write(0)
            val time = line.timeMs
            out.write((time ushr 24) and 0xFF)
            out.write((time ushr 16) and 0xFF)
            out.write((time ushr 8) and 0xFF)
            out.write(time and 0xFF)
        }
        return out.toByteArray()
    }
}
