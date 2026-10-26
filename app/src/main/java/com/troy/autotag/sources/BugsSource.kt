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
 * 벅스(Bugs, music.bugs.co.kr) — 한국 음악 스트리밍 서비스.
 *
 * 공식 키가 필요 없는 모바일 검색 JSON API(`m.bugs.co.kr/api/getSearchList`)를
 * 사용한다. 국내 음원은 벅스가 한글 제목/가수 표기와 앨범·커버를 가장 잘
 * 갖추고 있어서, 텍스트 검색에서는 다른 해외 소스보다 먼저 조회한다.
 * (비공식 API라 응답 형식이 없어지거나 바뀌면 이 소스만 조용히 실패하고
 *  MusicBrainz/iTunes/Deezer가 뒤를 받친다.)
 *
 * 커버는 `image.bugsm.co.kr/album/images/<크기><path>` 규칙으로 만들며,
 * 크기 자리에는 50/200/500/1000/original 이 들어간다. 여기서는 1000px를
 * 기본으로 쓰고, 다운로드 단계에서 필요하면 original로 다시 시도한다.
 */
object BugsSource {

    private val headers = mapOf(
        // 모바일 API가 브라우저 계열 UA를 기대하는 경우가 있어 명시한다.
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
        "Referer" to "https://music.bugs.co.kr/",
        "Accept" to "application/json",
    )

    fun search(
        title: String,
        artist: String?,
        durationSec: Int?,
        log: (String) -> Unit,
        fetch: (String) -> String? = { url -> Http.get(url, headers) },
    ): Meta? {
        if (title.isBlank()) return null
        val queries = ArrayList<String>()
        if (!artist.isNullOrBlank()) queries.add("${TextTools.artistOnly(artist)} $title")
        queries.add(title)

        for (query in queries) {
            val items = fetchItems(searchUrl(query, 20), fetch)
            if (items.isEmpty()) continue
            val best = bestMatch(items, title, artist, durationSec) ?: continue
            val meta = toMeta(best.first, best.second)
            log("  벅스: ${meta.artist} - ${meta.title} (${best.second.toInt()}점)")
            return meta
        }
        log("  벅스: 신뢰할 만한 결과 없음")
        return null
    }

    /** 같은 앨범의 곡 목록(트랙번호 보정용). 제목 키 -> 메타 맵. */
    fun albumTracks(
        album: String,
        artist: String?,
        fetch: (String) -> String? = { url -> Http.get(url, headers) },
    ): Map<String, Meta> {
        val queries = ArrayList<String>()
        if (!artist.isNullOrBlank()) queries.add("${TextTools.artistOnly(artist)} $album")
        queries.add(album)
        for (query in queries) {
            val items = fetchItems(searchUrl(query, 50), fetch)
            val out = LinkedHashMap<String, Meta>()
            for (item in items) {
                val albumObj = item.obj("album") ?: continue
                val albumTitle = albumObj.str("title") ?: continue
                if (TextTools.albumKey(albumTitle) != TextTools.albumKey(album)) continue
                val trackTitle = item.str("track_title") ?: continue
                val meta = toMeta(item, 0.0)
                out[TextTools.matchKey(trackTitle)] = meta
            }
            if (out.isNotEmpty()) return out
        }
        return emptyMap()
    }

    // ------------------------------------------------------------------ internals

    private fun searchUrl(query: String, size: Int): String =
        "https://m.bugs.co.kr/api/getSearchList?type=track&page=1&size=$size&query=" +
            URLEncoder.encode(query, "UTF-8")

    private fun fetchItems(url: String, fetch: (String) -> String?): List<JSONObject> {
        val body = fetch(url) ?: return emptyList()
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return emptyList()
        }
        val list = json.arr("list") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until list.length()) list.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    private fun bestMatch(
        items: List<JSONObject>,
        title: String,
        artist: String?,
        durationSec: Int?,
    ): Pair<JSONObject, Double>? {
        var best: JSONObject? = null
        var bestScore = 0.0
        for (item in items) {
            val trackTitle = item.str("track_title") ?: continue
            var score = TextTools.similarity(trackTitle, title) * 70.0
            val artistName = firstArtist(item)?.str("artist_nm")
            if (!artist.isNullOrBlank() && artistName != null) {
                score += TextTools.artistSimilarity(artistName, artist) * 30.0
            }
            val itemDuration = parseLen(item.str("len"))
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
            if (item.optBoolean("title_yn", false)) score += 2.0 // 타이틀곡 가산점
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        val chosen = best ?: return null
        if (bestScore < 55.0) return null
        return chosen to bestScore
    }

    private fun toMeta(item: JSONObject, score: Double): Meta {
        val meta = Meta(source = "벅스(Bugs)", score = score)
        meta.title = item.str("track_title")
        val artistObj = firstArtist(item)
        meta.artist = artistObj?.str("artist_nm")
        meta.albumArtist = meta.artist
        meta.track = item.int("track_no")
        meta.disc = item.int("disc_id")
        meta.durationSec = parseLen(item.str("len"))
        val genres = artistObj?.arr("genres")
        if (genres != null && genres.length() > 0) {
            meta.genre = genres.optJSONObject(0)?.str("svc_nm")
        }
        val album = item.obj("album")
        if (album != null) {
            meta.album = album.str("title")
            meta.year = TextTools.parseYear(album.str("release_ymd"))
            val path = album.obj("image")?.str("path")
            meta.artworkUrl = artworkUrl(path)
        }
        return meta
    }

    private fun firstArtist(item: JSONObject): JSONObject? {
        val artists = item.arr("artists") ?: return null
        return if (artists.length() > 0) artists.optJSONObject(0) else null
    }

    /** 벅스 이미지 경로("/203619/20361943.jpg") -> 커버 URL. */
    fun artworkUrl(path: String?, size: String = "1000"): String? {
        if (path.isNullOrBlank()) return null
        val clean = if (path.startsWith("/")) path else "/$path"
        return "https://image.bugsm.co.kr/album/images/$size$clean"
    }

    /** "03:19" / "3:19" -> 199초. */
    fun parseLen(len: String?): Int? {
        if (len.isNullOrBlank()) return null
        val parts = len.trim().split(":")
        return try {
            when (parts.size) {
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }
}
