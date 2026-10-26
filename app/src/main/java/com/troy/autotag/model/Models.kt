package com.troy.autotag.model

import org.json.JSONArray
import org.json.JSONObject

/** Metadata gathered from one online source. */
data class Meta(
    var title: String? = null,
    var artist: String? = null,
    var album: String? = null,
    var albumArtist: String? = null,
    var track: Int? = null,
    var trackTotal: Int? = null,
    var disc: Int? = null,
    var discTotal: Int? = null,
    var year: String? = null,
    var genre: String? = null,
    var isrc: String? = null,
    var label: String? = null,
    var bpm: String? = null,
    var mbTrackId: String? = null,
    var mbReleaseId: String? = null,
    var mbArtistId: String? = null,
    var mbReleaseGroupId: String? = null,
    var acoustIdId: String? = null,
    var artworkUrl: String? = null,
    var source: String = "",
    var score: Double = 0.0,
    var durationSec: Int? = null,
) {
    fun hasAnything(): Boolean = title != null || artist != null || album != null ||
        track != null || year != null || genre != null || artworkUrl != null

    /** Fills null fields of this object from [other]. */
    fun fillFrom(other: Meta?) {
        if (other == null) return
        if (title == null) title = other.title
        if (artist == null) artist = other.artist
        if (album == null) album = other.album
        if (albumArtist == null) albumArtist = other.albumArtist
        if (track == null) track = other.track
        if (trackTotal == null) trackTotal = other.trackTotal
        if (disc == null) disc = other.disc
        if (discTotal == null) discTotal = other.discTotal
        if (year == null) year = other.year
        if (genre == null) genre = other.genre
        if (isrc == null) isrc = other.isrc
        if (label == null) label = other.label
        if (bpm == null) bpm = other.bpm
        if (mbTrackId == null) mbTrackId = other.mbTrackId
        if (mbReleaseId == null) mbReleaseId = other.mbReleaseId
        if (mbArtistId == null) mbArtistId = other.mbArtistId
        if (mbReleaseGroupId == null) mbReleaseGroupId = other.mbReleaseGroupId
        if (acoustIdId == null) acoustIdId = other.acoustIdId
        if (artworkUrl == null) artworkUrl = other.artworkUrl
        if (durationSec == null) durationSec = other.durationSec
    }
}

/** Lyrics found for a track. */
data class LyricsResult(
    val synced: String?,
    val plain: String?,
    val source: String,
    val instrumental: Boolean = false,
    val durationSec: Int? = null,
) {
    fun isUsable(): Boolean = !synced.isNullOrBlank() || !plain.isNullOrBlank()
}

/** A single audio file that the user selected. */
data class Track(
    val file: java.io.File,
    val uri: android.net.Uri? = null,
    /** true when the file must be copied back to [uri] after editing. */
    val needsCopyBack: Boolean = false,
    var tags: ExistingTags = ExistingTags(),
    var meta: Meta = Meta(),
    var lyrics: LyricsResult? = null,
    var fingerprint: String? = null,
    var status: String = "",
    var changes: MutableList<String> = mutableListOf(),
    var error: String? = null,
)

/** Values currently stored in the file. */
data class ExistingTags(
    var title: String? = null,
    var artist: String? = null,
    var album: String? = null,
    var albumArtist: String? = null,
    var track: Int? = null,
    var trackTotal: Int? = null,
    var disc: Int? = null,
    var discTotal: Int? = null,
    var year: String? = null,
    var genre: String? = null,
    var comment: String? = null,
    var lyrics: String? = null,
    var hasSyncedLyrics: Boolean = false,
    var hasArtwork: Boolean = false,
    /** Size of the embedded front cover, used to judge upgrade candidates. */
    var artworkBytes: Int = 0,
    var artworkWidth: Int = 0,
    var artworkHeight: Int = 0,
    var artworkMime: String? = null,
    var isrc: String? = null,
    var mbTrackId: String? = null,
    var durationSec: Int = 0,
    var format: String = "",
    var hasAnyTag: Boolean = false,
) {
    fun summary(): String {
        val parts = ArrayList<String>()
        if (!title.isNullOrBlank()) parts.add("제목=$title")
        if (!artist.isNullOrBlank()) parts.add("가수=$artist")
        if (!album.isNullOrBlank()) parts.add("앨범=$album")
        if (track != null) parts.add("트랙=$track")
        if (year != null) parts.add("연도=$year")
        if (hasSyncedLyrics) parts.add("싱크가사")
        else if (!lyrics.isNullOrBlank()) parts.add("가사")
        if (hasArtwork) {
            parts.add(
                if (artworkWidth > 0 && artworkHeight > 0) "커버(${artworkWidth}x$artworkHeight)" else "커버"
            )
        }
        if (parts.isEmpty()) parts.add("태그없음")
        return parts.joinToString(" | ")
    }
}

// ---- small org.json helpers -------------------------------------------------

fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.takeIf { it.isNotBlank() && it != "null" }
}

fun JSONObject.int(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val v = optInt(key, Int.MIN_VALUE)
    return if (v == Int.MIN_VALUE) null else v
}

fun JSONObject.long(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val v = optLong(key, Long.MIN_VALUE)
    return if (v == Long.MIN_VALUE) null else v
}

fun JSONObject.obj(key: String): JSONObject? {
    if (!has(key) || isNull(key)) return null
    return optJSONObject(key)
}

fun JSONObject.arr(key: String): JSONArray? {
    if (!has(key) || isNull(key)) return null
    return optJSONArray(key)
}
