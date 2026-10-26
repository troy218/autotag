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

/**
 * AcoustID lookup. A fingerprint identifies the exact recording, so when this
 * succeeds its metadata is treated as authoritative.
 */
object AcoustIdSource {

    data class Result(val meta: Meta, val releaseId: String?, val recordingId: String?)

    fun lookup(
        clientKey: String,
        fingerprint: String,
        durationSec: Int,
        threshold: Double,
        log: (String) -> Unit,
        fetch: (String) -> String? = Http::get,
    ): Result? {
        if (clientKey.isBlank() || fingerprint.isBlank() || durationSec <= 0) return null
        val url = "https://api.acoustid.org/v2/lookup?format=json" +
            "&client=" + URLEncoder.encode(clientKey, "UTF-8") +
            "&duration=" + durationSec +
            "&fingerprint=" + URLEncoder.encode(fingerprint, "UTF-8") +
            "&meta=recordings+releasegroups+releases+tracks+recordingids"
        val body = fetch(url) ?: run {
            log("  AcoustID: 요청 실패")
            return null
        }
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            log("  AcoustID: 응답 파싱 실패")
            return null
        }
        if (json.str("status") != "ok") {
            val error = json.obj("error")?.str("message") ?: ""
            log("  AcoustID: 오류 $error")
            return null
        }
        val results = json.arr("results") ?: return null
        var best: Result? = null
        var bestScore = 0.0
        for (i in 0 until results.length()) {
            val result = results.optJSONObject(i) ?: continue
            val score = result.optDouble("score", 0.0)
            if (score < threshold) continue
            val recording = pickRecording(result) ?: continue
            if (score > bestScore) {
                bestScore = score
                best = Result(recording.first, recording.second, recording.third)
            }
        }
        if (best == null) {
            log("  AcoustID: 일치하는 곡 없음 (기준 ${(threshold * 100).toInt()}%)")
            return null
        }
        log("  AcoustID: 일치 ${(bestScore * 100).toInt()}% - ${best.meta.artist} - ${best.meta.title}")
        return best
    }

    private fun pickRecording(result: JSONObject): Triple<Meta, String?, String?>? {
        val recordings = result.arr("recordings") ?: return null
        for (i in 0 until recordings.length()) {
            val recording = recordings.optJSONObject(i) ?: continue
            val meta = Meta(source = "AcoustID")
            meta.acoustIdId = result.str("id")
            meta.title = recording.str("title")
            meta.mbTrackId = recording.str("id")
            val artists = recording.arr("artists")
            if (artists != null && artists.length() > 0) {
                val first = artists.optJSONObject(0)
                meta.artist = first?.str("name")
                meta.mbArtistId = first?.str("id")
            }
            val acl = recording.arr("artist-credit")
            if (meta.artist == null && acl != null && acl.length() > 0) {
                meta.artist = acl.optJSONObject(0)?.str("name")
            }
            val duration = recording.int("duration")
            if (duration != null && duration > 0) meta.durationSec = duration

            val releases = recording.arr("releases")
            var releaseId: String? = null
            if (releases != null && releases.length() > 0) {
                val chosen = chooseRelease(releases, meta.title) ?: releases.optJSONObject(0)
                if (chosen != null) {
                    releaseId = chosen.str("id")
                    meta.mbReleaseId = releaseId
                    meta.album = chosen.str("title")
                    meta.year = TextTools.parseYear(chosen.obj("date")?.str("year"))
                    meta.mbReleaseGroupId = chosen.obj("release-group")?.str("id")
                    val medium = chosen.arr("mediums")?.let {
                        if (it.length() > 0) it.optJSONObject(0) else null
                    }
                    if (medium != null) {
                        meta.disc = medium.int("position")
                        val tracks = medium.arr("tracks")
                        if (tracks != null) {
                            for (t in 0 until tracks.length()) {
                                val track = tracks.optJSONObject(t) ?: continue
                                val title = track.str("title") ?: continue
                                val wantedTitle = meta.title
                                if (wantedTitle != null && TextTools.similarity(title, wantedTitle) >= 0.8) {
                                    meta.track = track.int("position")
                                    break
                                }
                            }
                        }
                        if (meta.track == null && medium.int("track-offset") != null) {
                            meta.track = medium.int("track-offset")
                        }
                    }
                }
            }
            return Triple(meta, releaseId, meta.mbTrackId)
        }
        return null
    }

    private fun chooseRelease(releases: org.json.JSONArray, recordingTitle: String?): JSONObject? {
        var best: JSONObject? = null
        var bestScore = -1.0
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            var score = 1.0
            val type = release.obj("release-group")?.str("primary-type")
            if (type == "Album") score += 3.0
            if (type == "Single" || type == "EP") score += 1.5
            if (type == "Compilation") score -= 1.0
            if (release.str("country") == "KR") score += 0.5
            val year = TextTools.parseYear(release.obj("date")?.str("year"))
            if (year != null && year <= "2000") score += 0.25
            val tracks = release.arr("mediums")?.let {
                if (it.length() > 0) it.optJSONObject(0)?.arr("tracks") else null
            }
            if (tracks != null && recordingTitle != null) {
                for (t in 0 until tracks.length()) {
                    val title = tracks.optJSONObject(t)?.str("title") ?: continue
                    if (TextTools.similarity(title, recordingTitle) >= 0.8) {
                        score += 2.0
                        break
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
