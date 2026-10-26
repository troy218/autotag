package com.troy.autotag.sources

import com.troy.autotag.model.Meta
import com.troy.autotag.model.arr
import com.troy.autotag.model.int
import com.troy.autotag.model.long
import com.troy.autotag.model.obj
import com.troy.autotag.model.str
import com.troy.autotag.net.Http
import com.troy.autotag.util.TextTools
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** MusicBrainz text search + release details (no API key required). */
object MusicBrainzSource {

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun escapeLucene(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Searches for a recording and returns the best match, preferring results
     * whose title, artist and length all agree.
     */
    fun searchRecording(
        title: String,
        artist: String?,
        durationSec: Int?,
        log: (String) -> Unit,
    ): Meta? {
        if (title.isBlank()) return null
        val query = buildString {
            append("recording:\"")
            append(escapeLucene(title))
            append("\"")
            if (!artist.isNullOrBlank()) {
                append(" AND artist:\"")
                append(escapeLucene(TextTools.artistOnly(artist)))
                append("\"")
            }
        }
        val url = "https://musicbrainz.org/ws/2/recording?fmt=json&limit=8&query=" + encode(query)
        val body = Http.get(url) ?: return null
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return null
        }
        val recordings = json.arr("recordings") ?: return null
        var best: Meta? = null
        var bestScore = 0.0
        for (i in 0 until recordings.length()) {
            val rec = recordings.optJSONObject(i) ?: continue
            val recTitle = rec.str("title") ?: continue
            var score = TextTools.similarity(recTitle, title) * 60.0
            if (!artist.isNullOrBlank()) {
                val credit = artistCredit(rec).firstOrNull() ?: ""
                score += TextTools.artistSimilarity(credit, artist) * 30.0
            }
            val lengthMs = rec.long("length")
            if (lengthMs != null && durationSec != null && durationSec > 0) {
                val diff = kotlin.math.abs(lengthMs / 1000 - durationSec)
                score += when {
                    diff <= 2 -> 12.0
                    diff <= 5 -> 8.0
                    diff <= 10 -> 3.0
                    diff <= 20 -> 0.0
                    else -> -10.0
                }
            }
            val mbScore = rec.int("score") ?: 0
            score += mbScore / 20.0
            if (score > bestScore) {
                bestScore = score
                val meta = Meta(source = "MusicBrainz", score = score)
                meta.title = recTitle
                meta.mbTrackId = rec.str("id")
                val credits = artistCredit(rec)
                meta.artist = credits.firstOrNull()
                meta.mbArtistId = firstArtistId(rec)
                if (lengthMs != null) meta.durationSec = (lengthMs / 1000).toInt()
                val releases = rec.arr("releases")
                if (releases != null && releases.length() > 0) {
                    val release = chooseRelease(releases, recTitle)
                    if (release != null) {
                        meta.album = release.str("title")
                        meta.mbReleaseId = release.str("id")
                        meta.year = TextTools.parseYear(release.str("date"))
                        meta.mbReleaseGroupId = release.obj("release-group")?.str("id")
                        val medium = release.arr("media")?.let {
                            if (it.length() > 0) it.optJSONObject(0) else null
                        }
                        if (medium != null) {
                            meta.disc = medium.int("position")
                            meta.trackTotal = medium.int("track-count")
                            meta.track = trackPosition(medium, recTitle, meta.mbTrackId)
                        }
                    }
                }
                best = meta
            }
        }
        if (best != null) {
            log("  MusicBrainz: ${best.artist} - ${best.title} (${best.score.toInt()}점)")
        } else {
            log("  MusicBrainz: 결과 없음")
        }
        return best
    }

    /** Fills genre / label / total counts / track number from the release. */
    fun enrichFromRelease(meta: Meta, recordingTitle: String?): Meta {
        val releaseId = meta.mbReleaseId ?: return meta
        val url =
            "https://musicbrainz.org/ws/2/release/$releaseId?inc=recordings+artist-credits+genres+labels&fmt=json"
        val body = Http.get(url) ?: return meta
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return meta
        }
        if (meta.album == null) meta.album = json.str("title")
        if (meta.year == null) meta.year = TextTools.parseYear(json.str("date"))
        if (meta.albumArtist == null) {
            val credit = json.arr("artist-credit")?.let {
                if (it.length() > 0) it.optJSONObject(0)?.str("name") else null
            }
            meta.albumArtist = credit
        }
        if (meta.label == null) {
            val labels = json.arr("label-info")
            if (labels != null && labels.length() > 0) {
                meta.label = labels.optJSONObject(0)?.obj("label")?.str("name")
            }
        }
        if (meta.genre == null) {
            val genres = json.arr("genres")
            if (genres != null && genres.length() > 0) {
                meta.genre = genres.optJSONObject(0)?.str("name")
            }
        }
        val media = json.arr("media")
        if (media != null && media.length() > 0) {
            val medium = media.optJSONObject(0)
            if (medium != null) {
                if (meta.disc == null) meta.disc = medium.int("position")
                if (meta.trackTotal == null) meta.trackTotal = medium.int("track-count")
                if (meta.track == null) meta.track = trackPosition(medium, recordingTitle, meta.mbTrackId)
            }
        }
        return meta
    }

    private fun trackPosition(medium: JSONObject, title: String?, recordingId: String?): Int? {
        val tracks = medium.arr("tracks") ?: return null
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val id = track.str("id")
            val recId = track.obj("recording")?.str("id")
            if (recordingId != null && recId == recordingId) return track.int("position")
            val trackTitle = track.str("title")
            if (title != null && trackTitle != null && TextTools.similarity(trackTitle, title) >= 0.85) {
                return track.int("position")
            }
            if (id == null && recId == null && trackTitle == null) continue
        }
        return null
    }

    private fun artistCredit(recording: JSONObject): List<String> {
        val out = ArrayList<String>()
        val credit = recording.arr("artist-credit")
        if (credit != null) {
            val sb = StringBuilder()
            for (i in 0 until credit.length()) {
                val item = credit.optJSONObject(i) ?: continue
                val name = item.str("name") ?: item.obj("artist")?.str("name") ?: continue
                if (sb.isNotEmpty()) sb.append(" & ")
                sb.append(name)
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
        }
        return out
    }

    private fun firstArtistId(recording: JSONObject): String? {
        val credit = recording.arr("artist-credit") ?: return null
        if (credit.length() == 0) return null
        return credit.optJSONObject(0)?.obj("artist")?.str("id")
    }

    private fun chooseRelease(releases: JSONArray, recordingTitle: String?): JSONObject? {
        var best: JSONObject? = null
        var bestScore = -100.0
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            var score = 0.0
            val group = release.obj("release-group")
            when (group?.str("primary-type")) {
                "Album" -> score += 4.0
                "EP" -> score += 2.0
                "Single" -> score += 1.0
                "Compilation" -> score -= 2.0
            }
            val country = release.str("country")
            if (country == "KR" || country == "JP") score += 0.5
            val date = release.str("date")
            if (date != null) {
                val year = TextTools.parseYear(date)?.toIntOrNull()
                if (year != null) score += (year - 1990) / 100.0
            }
            val media = release.arr("media")
            if (media != null && media.length() > 0) {
                val medium = media.optJSONObject(0)
                if (medium != null && recordingTitle != null) {
                    val tracks = medium.arr("tracks")
                    if (tracks != null) {
                        for (t in 0 until tracks.length()) {
                            val title = tracks.optJSONObject(t)?.str("title") ?: continue
                            if (TextTools.similarity(title, recordingTitle) >= 0.85) {
                                score += 1.5
                                break
                            }
                        }
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = release
            }
        }
        return best
    }
}
