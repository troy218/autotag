package com.troy.autotag.sources

import com.troy.autotag.model.Meta
import com.troy.autotag.model.arr
import com.troy.autotag.model.int
import com.troy.autotag.model.long
import com.troy.autotag.model.obj
import com.troy.autotag.model.str
import com.troy.autotag.net.Http
import com.troy.autotag.util.TextTools
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Apple iTunes Search API. No key needed, excellent K-pop / pop coverage and
 * it also provides 1000x1000 cover art.
 */
object ItunesSource {

    fun search(
        title: String,
        artist: String?,
        durationSec: Int?,
        country: String,
        log: (String) -> Unit,
        fetch: (String) -> String? = Http::get,
    ): Meta? {
        val terms = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title)
        if (terms.isEmpty()) return null
        val term = terms.joinToString(" ")
        val url = buildUrl(term, country)
        var items = fetchItems(url, fetch)
        if (items.isEmpty() && country != "US") {
            items = fetchItems(buildUrl(term, "US"), fetch)
        }
        if (items.isEmpty()) {
            log("  iTunes: 결과 없음")
            return null
        }
        var best: JSONObject? = null
        var bestScore = 0.0
        for (item in items) {
            val trackName = item.str("trackName") ?: continue
            var score = TextTools.similarity(trackName, title) * 70.0
            val artistName = item.str("artistName")
            if (!artist.isNullOrBlank() && artistName != null) {
                score += TextTools.artistSimilarity(artistName, artist) * 30.0
            }
            val millis = item.long("trackTimeMillis")
            if (millis != null && durationSec != null && durationSec > 0) {
                val diff = kotlin.math.abs(millis / 1000 - durationSec)
                score += when {
                    diff <= 2 -> 12.0
                    diff <= 5 -> 8.0
                    diff <= 12 -> 2.0
                    diff <= 25 -> -4.0
                    else -> -15.0
                }
            } else {
                score += 4.0
            }
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        if (best == null || bestScore < 55.0) {
            log("  iTunes: 신뢰할 만한 결과 없음")
            return null
        }
        val meta = Meta(source = "iTunes", score = bestScore)
        meta.title = best.str("trackName")
        meta.artist = best.str("artistName")
        meta.album = best.str("collectionName")
        meta.albumArtist = best.str("artistName")
        meta.track = best.int("trackNumber")
        meta.trackTotal = best.int("trackCount")
        meta.disc = best.int("discNumber")
        meta.discTotal = best.int("discCount")
        meta.genre = best.str("primaryGenreName")
        meta.year = TextTools.parseYear(best.str("releaseDate"))
        meta.isrc = best.str("isrc")
        val millis = best.long("trackTimeMillis")
        if (millis != null) meta.durationSec = (millis / 1000).toInt()
        meta.artworkUrl = artworkUrl(best.str("artworkUrl100"))
        log("  iTunes: ${meta.artist} - ${meta.title} (${bestScore.toInt()}점)")
        return meta
    }

    /**
     * Looks up an album's track list, used to fill in missing track numbers of
     * the other tracks of the same album.
     */
    fun albumTracks(
        album: String,
        artist: String?,
        country: String,
        fetch: (String) -> String? = Http::get,
    ): Map<String, Meta> {
        val term = listOfNotNull(artist?.takeIf { it.isNotBlank() }, album).joinToString(" ")
        val url = "https://itunes.apple.com/search?media=music&entity=song&limit=50" +
            "&country=" + URLEncoder.encode(country, "UTF-8") +
            "&attribute=albumTerm&term=" + URLEncoder.encode(term, "UTF-8")
        val items = fetchItems(url, fetch)
        if (items.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Meta>()
        for (item in items) {
            val collection = item.str("collectionName") ?: continue
            if (TextTools.albumKey(collection) != TextTools.albumKey(album)) continue
            val trackName = item.str("trackName") ?: continue
            val meta = Meta(source = "iTunes(album)")
            meta.title = trackName
            meta.album = collection
            meta.artist = item.str("artistName")
            meta.albumArtist = item.str("artistName")
            meta.track = item.int("trackNumber")
            meta.trackTotal = item.int("trackCount")
            meta.disc = item.int("discNumber")
            meta.discTotal = item.int("discCount")
            meta.genre = item.str("primaryGenreName")
            meta.year = TextTools.parseYear(item.str("releaseDate"))
            meta.artworkUrl = artworkUrl(item.str("artworkUrl100"))
            val millis = item.long("trackTimeMillis")
            if (millis != null) meta.durationSec = (millis / 1000).toInt()
            out[TextTools.matchKey(trackName)] = meta
        }
        return out
    }

    private fun buildUrl(term: String, country: String): String =
        "https://itunes.apple.com/search?media=music&entity=song&limit=15" +
            "&country=" + URLEncoder.encode(country, "UTF-8") +
            "&term=" + URLEncoder.encode(term, "UTF-8")

    private fun fetchItems(url: String, fetch: (String) -> String?): List<JSONObject> {
        val body = fetch(url) ?: return emptyList()
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return emptyList()
        }
        val results = json.arr("results") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until results.length()) {
            results.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    /** Upgrades a 100x100 artwork url to the largest available size. */
    fun artworkUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.replace("100x100bb", "1400x1400bb")
            .replace("100x100", "1400x1400")
            .replace("60x60bb", "1400x1400bb")
    }
}
