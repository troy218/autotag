package com.troy.autotag.sources

import com.troy.autotag.model.Meta
import com.troy.autotag.model.arr
import com.troy.autotag.model.int
import com.troy.autotag.model.obj
import com.troy.autotag.model.str
import com.troy.autotag.net.Http
import com.troy.autotag.util.TextTools
import org.json.JSONObject

/**
 * AcoustID lookup. A fingerprint identifies the exact recording, so when this
 * succeeds its metadata is treated as authoritative.
 */
object AcoustIdSource {

    data class Result(val meta: Meta, val releaseId: String?, val recordingId: String?)

    /**
     * API 키가 서버에서 거부된 경우 그 키로는 더 요청하지 않는다.
     * 배치 내내 파일마다 같은 실패를 반복(및 로그 도배)하지 않기 위함이며,
     * 키가 바뀌면(다른 값으로 호출되면) 자동으로 다시 시도한다.
     */
    @Volatile
    private var rejectedKey: String? = null

    fun lookup(
        clientKey: String,
        fingerprint: String,
        durationSec: Int,
        threshold: Double,
        log: (String) -> Unit,
        post: (String, Map<String, String>) -> Http.Response? = Http::postForm,
    ): Result? {
        if (clientKey.isBlank() || fingerprint.isBlank() || durationSec <= 0) return null
        if (rejectedKey == clientKey) return null // 이미 거부된 키: 조용히 생략
        // 지문은 길어서(120초 분량에 수천 자) GET URL에 실으면 서버/프록시가
        // 거부할 수 있다. AcoustID 공식 방식인 POST 폼 전송을 쓴다.
        val fields = linkedMapOf(
            "format" to "json",
            "client" to clientKey,
            "duration" to durationSec.toString(),
            "fingerprint" to fingerprint,
            "meta" to "recordings+releasegroups+releases+tracks+recordingids",
        )
        val response = post("https://api.acoustid.org/v2/lookup", fields) ?: run {
            log("  AcoustID: 요청 실패 (네트워크 오류 — 연결을 확인하세요)")
            return null
        }
        val body = response.text()
        val json = body?.let {
            try {
                JSONObject(it)
            } catch (t: Throwable) {
                null
            }
        }
        if (!response.isSuccess || json == null) {
            val error = json?.obj("error")
            val message = error?.str("message") ?: ""
            val code = error?.int("code")
            when {
                // 잘못된/비활성화된 API 키: 원인을 알리고 이 키로는 중단한다.
                code == 4 || message.contains("api key", ignoreCase = true) ||
                    response.code == 401 || response.code == 403 -> {
                    rejectedKey = clientKey
                    log("  AcoustID: API 키가 거부됨 (HTTP ${response.code}${if (message.isNotBlank()) ": $message" else ""}) — 설정에서 AcoustID API 키를 확인하세요. 이번 작업에서는 AcoustID를 건너뜁니다")
                }
                message.isNotBlank() ->
                    log("  AcoustID: 오류 (HTTP ${response.code}: $message)")
                json == null && body.isNullOrBlank() ->
                    log("  AcoustID: 요청 실패 (HTTP ${response.code})")
                json == null ->
                    log("  AcoustID: 응답 파싱 실패 (HTTP ${response.code})")
                else ->
                    log("  AcoustID: 요청 실패 (HTTP ${response.code})")
            }
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
