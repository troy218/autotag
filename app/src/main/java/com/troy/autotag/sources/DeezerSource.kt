package com.troy.autotag.sources

import com.troy.autotag.model.Meta
import com.troy.autotag.model.arr
import com.troy.autotag.model.int
import com.troy.autotag.model.obj
import com.troy.autotag.model.str
import com.troy.autotag.net.Http
import com.troy.autotag.util.TextTools
import org.json.JSONObject
import java.net.URLEncoder

/** Deezer public API: good album art and track/disc numbers without a key. */
object DeezerSource {

    fun search(
        title: String,
        artist: String?,
        durationSec: Int?,
        log: (String) -> Unit,
    ): Meta? {
        val query = buildString {
            append("track:\"")
            append(title.replace("\"", ""))
            append("\"")
            if (!artist.isNullOrBlank()) {
                append(" artist:\"")
                append(TextTools.artistOnly(artist).replace("\"", ""))
                append("\"")
            }
        }
        val url = "https://api.deezer.com/search?limit=10&q=" + URLEncoder.encode(query, "UTF-8")
        val body = Http.get(url) ?: return null
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return null
        }
        val data = json.arr("data") ?: return null
        var best: JSONObject? = null
        var bestScore = 0.0
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val trackTitle = item.str("title") ?: continue
            var score = TextTools.similarity(trackTitle, title) * 70.0
            val artistName = item.obj("artist")?.str("name")
            if (!artist.isNullOrBlank() && artistName != null) {
                score += TextTools.artistSimilarity(artistName, artist) * 30.0
            }
            val itemDuration = item.int("duration")
            if (itemDuration != null && durationSec != null && durationSec > 0) {
                val diff = kotlin.math.abs(itemDuration - durationSec)
                score += when {
                    diff <= 2 -> 12.0
                    diff <= 5 -> 8.0
                    diff <= 12 -> 2.0
                    diff <= 25 -> -4.0
                    else -> -15.0
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        if (best == null || bestScore < 55.0) {
            log("  Deezer: 신뢰할 만한 결과 없음")
            return null
        }
        val meta = Meta(source = "Deezer", score = bestScore)
        meta.title = best.str("title")
        meta.artist = best.obj("artist")?.str("name")
        val album = best.obj("album")
        meta.album = album?.str("title")
        meta.artworkUrl = album?.str("cover_xl") ?: album?.str("cover_big")
        meta.durationSec = best.int("duration")

        val albumId = album?.str("id") ?: album?.int("id")?.toString()
        if (albumId != null) {
            fillFromAlbum(meta, albumId)
        }
        log("  Deezer: ${meta.artist} - ${meta.title} (${bestScore.toInt()}점)")
        return meta
    }

    /**
     * Fills track/disc numbers, genre, label and total counts from the album.
     */
    fun fillFromAlbum(meta: Meta, albumId: String): Meta {
        val body = Http.get("https://api.deezer.com/album/$albumId") ?: return meta
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return meta
        }
        if (meta.album == null) meta.album = json.str("title")
        if (meta.albumArtist == null) meta.albumArtist = json.str("artist")?.let { json.obj("artist")?.str("name") } ?: meta.artist
        if (meta.year == null) meta.year = TextTools.parseYear(json.str("release_date"))
        if (meta.label == null) meta.label = json.str("label")
        if (meta.artworkUrl == null) meta.artworkUrl = json.str("cover_xl")
        val genres = json.arr("genres")
        if (meta.genre == null && genres != null && genres.length() > 0) {
            meta.genre = genres.optJSONObject(0)?.str("name")
        }
        val tracks = json.obj("tracks")?.arr("data")
        if (tracks != null) {
            meta.trackTotal = tracks.length()
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                val trackTitle = track.str("title") ?: continue
                val matchesTitle = meta.title != null &&
                    TextTools.similarity(trackTitle, meta.title!!) >= 0.85
                if (matchesTitle || meta.track == null) {
                    if (matchesTitle) {
                        meta.track = track.int("track_position")
                        meta.disc = track.int("disk_number")
                        if (!track.str("isrc").isNullOrBlank()) meta.isrc = track.str("isrc")
                        break
                    }
                }
            }
            if (meta.track == null) {
                // fall back to the first track position (usually 1)
                meta.track = tracks.optJSONObject(0)?.int("track_position")
            }
        }
        val maxDisc = json.int("nb_discs")
        if (maxDisc != null && maxDisc > 0) meta.discTotal = maxDisc
        return meta
    }

    /** Returns the track list of an album keyed by normalized title. */
    fun albumTracks(album: String, artist: String?): Map<String, Meta> {
        val query = buildString {
            if (!artist.isNullOrBlank()) {
                append("artist:\"")
                append(TextTools.artistOnly(artist).replace("\"", ""))
                append("\" ")
            }
            append("album:\"")
            append(album.replace("\"", ""))
            append("\"")
        }
        val body = Http.get("https://api.deezer.com/search/album?limit=5&q=" + URLEncoder.encode(query, "UTF-8"))
            ?: return emptyMap()
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return emptyMap()
        }
        val data = json.arr("data") ?: return emptyMap()
        var albumId: String? = null
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val title = item.str("title") ?: continue
            if (TextTools.albumKey(title) == TextTools.albumKey(album)) {
                albumId = item.str("id")
                break
            }
        }
        if (albumId == null) return emptyMap()
        val albumBody = Http.get("https://api.deezer.com/album/$albumId") ?: return emptyMap()
        val albumJson = try {
            JSONObject(albumBody)
        } catch (t: Throwable) {
            return emptyMap()
        }
        val tracks = albumJson.obj("tracks")?.arr("data") ?: return emptyMap()
        val out = LinkedHashMap<String, Meta>()
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val title = track.str("title") ?: continue
            val meta = Meta(source = "Deezer(album)")
            meta.title = title
            meta.album = albumJson.str("title")
            meta.artist = albumJson.obj("artist")?.str("name")
            meta.albumArtist = meta.artist
            meta.track = track.int("track_position")
            meta.disc = track.int("disk_number")
            meta.year = TextTools.parseYear(albumJson.str("release_date"))
            meta.genre = albumJson.arr("genres")?.let {
                if (it.length() > 0) it.optJSONObject(0)?.str("name") else null
            }
            meta.trackTotal = tracks.length()
            meta.artworkUrl = albumJson.str("cover_xl")
            out[TextTools.matchKey(title)] = meta
        }
        return out
    }
}
