package com.troy.autotag.util

import java.text.Normalizer

/** String helpers used for matching metadata and for making spellings consistent. */
object TextTools {

    private val BRACKETED = Regex("[\\[(\uFF08\u3010][^\\])\uFF09\u3011]*[\\])\uFF09\u3011]")
    private val FEAT = Regex(
        "(?i)\\s*[\\[(]?\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s+[^\\])]*[\\])]?\\s*$"
    )
    private val VERSION_NOISE = Regex(
        "(?i)\\b(remaster(ed)?|remastered\\s+\\d{4}|deluxe|expanded|edition|version|ver\\.?|" +
            "mono|stereo|single|album version|radio edit|clean|explicit|inst\\.?|instrumental|" +
            "acoustic|live|repackage|repackaged|bonus track|digital remaster)\\b"
    )
    private val TRACK_PREFIX = Regex("^\\s*(\\d{1,3})\\s*[.\\-)\\]_]+\\s*")
    private val MULTI_SPACE = Regex("\\s+")
    private val PUNCT = Regex("[\\p{Punct}\\p{IsPunctuation}\\u00B7\\u2027\\u30FB]")

    private val NOISE_TITLE = setOf(
        "official", "official video", "mv", "m/v", "m v", "music video", "lyrics", "lyric video",
        "audio", "official audio", "hd", "hq", "4k", "performance", "live clip", "teaser",
        "full album", "eng sub", "가사", "뮤직비디오", "티저",
    )

    fun normalizeSpace(s: String): String = MULTI_SPACE.replace(s.trim(), " ")

    /** Unicode-normalized lower case form, used as a comparison key. */
    fun fold(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC)
        return n.lowercase()
            .replace('\u2019', '\'').replace('\u2018', '\'')
            .replace('\u201c', '"').replace('\u201d', '"')
            .replace('\u2013', '-').replace('\u2014', '-')
    }

    /** Aggressive key: no spaces, punctuation or bracketed suffixes. */
    fun matchKey(s: String): String {
        var v = fold(s)
        v = BRACKETED.replace(v, " ")
        v = PUNCT.replace(v, "")
        v = v.replace("&", "and")
        v = MULTI_SPACE.replace(v, "")
        return v
    }

    /** Key used to group albums that only differ in edition/casing/spacing. */
    fun albumKey(s: String): String {
        var v = fold(s)
        v = BRACKETED.replace(v, " ")
        v = VERSION_NOISE.replace(v, " ")
        v = PUNCT.replace(v, "")
        v = MULTI_SPACE.replace(v, "")
        return v
    }

    fun artistKey(s: String): String {
        var v = fold(artistOnly(s))
        v = BRACKETED.replace(v, " ")
        v = PUNCT.replace(v, "")
        v = v.replace("&", "and")
        v = MULTI_SPACE.replace(v, "")
        return v
    }

    /** Removes "feat. x", "ft. x", "with x" from an artist string. */
    fun artistOnly(s: String): String {
        var v = normalizeSpace(s)
        v = FEAT.replace(v, "")
        v = v.split(" / ").firstOrNull() ?: v
        return normalizeSpace(v)
    }

    fun cleanTitle(raw: String): String {
        var v = normalizeSpace(raw)
        v = v.replace(Regex("(?i)\\s*[\\[(]\\s*(official\\s+)?(mv|m/v|music video|lyric video|audio|hd)\\s*[\\])]\\s*$"), "")
        v = normalizeSpace(v)
        return v.trim(' ', '-', '_')
    }

    fun stripTrackPrefix(raw: String): String {
        val m = TRACK_PREFIX.find(raw)
        return if (m != null) raw.substring(m.range.last + 1).trim() else raw
    }

    fun isNoiseWord(word: String): Boolean = NOISE_TITLE.contains(fold(word).trim())

    /** Levenshtein based similarity in [0,1]. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val ka = matchKey(a)
        val kb = matchKey(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0.0
        if (ka == kb) return 0.98
        val maxLen = maxOf(ka.length, kb.length)
        val distance = levenshtein(ka, kb)
        return (1.0 - distance.toDouble() / maxLen).coerceIn(0.0, 1.0)
    }

    /**
     * Similarity of two already normalized keys (0..1). Used to cluster items
     * whose keys differ only by a subtitle in another script etc.
     */
    fun keySimilarity(keyA: String, keyB: String): Double {
        if (keyA == keyB) return 1.0
        if (keyA.isEmpty() || keyB.isEmpty()) return 0.0
        val maxLen = maxOf(keyA.length, keyB.length)
        return (1.0 - levenshtein(keyA, keyB).toDouble() / maxLen).coerceIn(0.0, 1.0)
    }

    /** Similarity of two artist strings, tolerant to featuring credits and separators. */
    fun artistSimilarity(a: String, b: String): Double {
        val ka = artistKey(a)
        val kb = artistKey(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0.0
        if (ka == kb) return 1.0
        if (ka.contains(kb) || kb.contains(ka)) return 0.92
        val tokensA = tokens(artistOnly(a))
        val tokensB = tokens(artistOnly(b))
        if (tokensA.isEmpty() || tokensB.isEmpty()) return similarity(a, b)
        val common = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        val jaccard = common.toDouble() / union
        val lev = similarity(a, b)
        return maxOf(lev, jaccard * 0.95)
    }

    private fun tokens(s: String): Set<String> =
        fold(s).split(Regex("[\\s,;&/+]+")).filter { it.isNotBlank() }.toSet()

    fun levenshtein(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        var previous = IntArray(m + 1) { it }
        var current = IntArray(m + 1)
        for (i in 1..n) {
            current[0] = i
            val ca = a[i - 1]
            for (j in 1..m) {
                val cost = if (ca == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val tmp = previous
            previous = current
            current = tmp
        }
        return previous[m]
    }

    fun parseYear(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("(\\d{4})").find(raw) ?: return null
        val year = m.groupValues[1].toIntOrNull() ?: return null
        return if (year in 1900..2100) year.toString() else null
    }

    fun parseIntSafe(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return Regex("\\d{1,4}").find(raw)?.value?.toIntOrNull()
    }

    data class GuessedNames(val title: String?, val artist: String?, val album: String?)

    /**
     * Best effort guess of artist/title from a file name, used when the file
     * has no usable tags. Handles patterns like:
     *   "01. Artist - Title", "Artist - Title (Album)", "Title - Artist"
     */
    fun guessFromFileName(fileName: String): GuessedNames {
        var name = fileName.substringBeforeLast('.')
        name = name.replace('_', ' ')
        name = normalizeSpace(name)
        name = stripTrackPrefix(name)
        var album: String? = null
        val bracket = BRACKETED.findAll(name).toList()
        if (bracket.isNotEmpty()) {
            val candidate = bracket.last().value.trim('[', ']', '(', ')', '\uFF08', '\uFF09')
            if (candidate.length > 2 && !VERSION_NOISE.matches(candidate) && !looksLikeQuality(candidate)) {
                album = candidate
            }
        }
        val dashParts = name.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        var artist: String? = null
        var title: String? = null
        if (dashParts.size >= 2) {
            artist = dashParts[0]
            title = dashParts.drop(1).joinToString(" - ")
        } else {
            title = name
        }
        if (title != null) {
            title = BRACKETED.replace(title, " ").let { normalizeSpace(it) }
        }
        return GuessedNames(
            title = title?.takeIf { it.isNotBlank() },
            artist = artist?.takeIf { it.isNotBlank() },
            album = album,
        )
    }

    private fun looksLikeQuality(s: String): Boolean =
        Regex("(?i)^(\\d{2,3}\\s*kbps|kbps|flac|mp3|320|192|128|lossless|hires)$").matches(s.trim())
}
