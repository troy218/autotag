package com.troy.autotag.sources

import com.troy.autotag.lyrics.Lrc
import com.troy.autotag.model.LyricsResult
import com.troy.autotag.model.arr
import com.troy.autotag.model.int
import com.troy.autotag.model.str
import com.troy.autotag.net.Http
import com.troy.autotag.util.TextTools
import org.json.JSONObject
import java.net.URLEncoder

/**
 * lrclib.net: free lyrics database with time synced lyrics (LRC format).
 */
object LrclibSource {

    fun find(
        title: String,
        artist: String?,
        album: String?,
        durationSec: Int?,
        preferSynced: Boolean,
        log: (String) -> Unit,
    ): LyricsResult? {
        if (title.isBlank()) return null

        // 1) exact "get" endpoint (artist + title + album + duration signature)
        if (!artist.isNullOrBlank()) {
            val exact = buildString {
                append("https://lrclib.net/api/get?track_name=")
                append(URLEncoder.encode(title, "UTF-8"))
                append("&artist_name=")
                append(URLEncoder.encode(TextTools.artistOnly(artist), "UTF-8"))
                if (!album.isNullOrBlank()) {
                    append("&album_name=")
                    append(URLEncoder.encode(album, "UTF-8"))
                }
                if (durationSec != null && durationSec > 0) {
                    append("&duration=")
                    append(durationSec)
                }
            }
            val body = Http.get(exact)
            if (body != null) {
                val json = runCatching { JSONObject(body) }.getOrNull()
                val result = json?.let { parse(it, "LRCLIB") }
                if (result != null && result.isUsable()) {
                    log("  LRCLIB: 정확 일치 ${if (result.synced != null) "(싱크가사)" else "(일반가사)"}")
                    return preferSynced.let { pick(result, preferSynced) }
                }
            }
        }

        // 2) search endpoint, pick the best matching entry
        val searchUrls = ArrayList<String>()
        val base = "https://lrclib.net/api/search?"
        if (!artist.isNullOrBlank()) {
            searchUrls.add(
                base + "track_name=" + URLEncoder.encode(title, "UTF-8") +
                    "&artist_name=" + URLEncoder.encode(TextTools.artistOnly(artist), "UTF-8")
            )
        }
        searchUrls.add(base + "q=" + URLEncoder.encode(
            listOfNotNull(artist?.let { TextTools.artistOnly(it) }, title).joinToString(" "),
            "UTF-8",
        ))
        for (url in searchUrls) {
            val body = Http.get(url) ?: continue
            val array = runCatching { org.json.JSONArray(body) }.getOrNull() ?: continue
            val candidates = ArrayList<Pair<Double, LyricsResult>>()
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                val parsed = parse(json, "LRCLIB") ?: continue
                if (!parsed.isUsable()) continue
                var score = 0.0
                val trackName = json.str("trackName") ?: ""
                score += TextTools.similarity(trackName, title) * 60.0
                val artistName = json.str("artistName")
                if (!artist.isNullOrBlank() && artistName != null) {
                    score += TextTools.artistSimilarity(artistName, artist) * 30.0
                }
                val itemDuration = json.int("duration")
                if (itemDuration != null && durationSec != null && durationSec > 0) {
                    val diff = kotlin.math.abs(itemDuration - durationSec)
                    score += when {
                        diff <= 2 -> 12.0
                        diff <= 5 -> 6.0
                        diff <= 12 -> 0.0
                        else -> -20.0
                    }
                }
                if (parsed.synced != null) score += 6.0
                candidates.add(score to parsed)
            }
            if (candidates.isNotEmpty()) {
                candidates.sortByDescending { it.first }
                val (score, result) = candidates.first()
                if (score < 45.0) continue
                log("  LRCLIB: 검색 일치 ${if (result.synced != null) "(싱크가사)" else "(일반가사)"} ${score.toInt()}점")
                return pick(result, preferSynced)
            }
        }
        log("  LRCLIB: 가사 없음")
        return null
    }

    private fun pick(result: LyricsResult, preferSynced: Boolean): LyricsResult {
        if (!preferSynced) return result
        return result
    }

    private fun parse(json: JSONObject, source: String): LyricsResult? {
        val synced = json.str("syncedLyrics")?.takeIf { Lrc.isSynced(it) }
        val plain = json.str("plainLyrics")
        val instrumental = json.optBoolean("instrumental", false)
        if (synced == null && plain == null && !instrumental) return null
        return LyricsResult(
            synced = synced,
            plain = plain ?: synced?.let { Lrc.toPlain(it) },
            source = source,
            instrumental = instrumental,
            durationSec = json.int("duration"),
        )
    }
}
