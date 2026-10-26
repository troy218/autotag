package com.troy.autotag.sources

import com.troy.autotag.lyrics.Lrc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parses realistic API responses without touching the network: every source
 * takes a `fetch` lambda that the tests replace. This pins down the JSON field
 * names, the scoring and the "AcoustID wins" priority.
 */
class SourcesTest {

    private fun fetcher(vararg routes: Pair<String, String>): (String) -> String? = { url ->
        routes.firstOrNull { (needle, _) -> url.contains(needle) }?.second
    }

    // ------------------------------------------------------------------ AcoustID

    private val acoustIdBody = """
        {"status":"ok","results":[
          {"id":"acoustid-1","score":0.931,
           "recordings":[{
             "id":"rec-1","title":"Dynamite","duration":199,
             "artists":[{"id":"artist-1","name":"BTS"}],
             "releasegroups":[{"id":"rg-1","type":"Single","title":"Dynamite"}],
             "releases":[{
               "id":"rel-1","title":"Dynamite","country":"KR",
               "date":{"year":2020,"month":8,"day":21},
               "release-group":{"id":"rg-1","type":"Single","primary-type":"Single"},
               "mediums":[{"position":1,"track-count":2,
                           "tracks":[{"position":1,"title":"Instrumental"},
                                     {"position":2,"title":"Dynamite"}]}]
             }]
           }]}
        ]}
    """.trimIndent()

    @Test
    fun acoustIdLookupIsParsed() {
        var requested = ""
        val result = AcoustIdSource.lookup("key", "AQAA", 199, 0.6, { }) { url ->
            requested = url
            acoustIdBody
        }
        assertNotNull(result)
        assertTrue(requested.startsWith("https://api.acoustid.org/v2/lookup?"), requested)
        assertTrue(requested.contains("client=key"), requested)
        assertTrue(requested.contains("duration=199"), requested)
        assertTrue(requested.contains("meta=recordings"), requested)

        val meta = result.meta
        assertEquals("Dynamite", meta.title)
        assertEquals("BTS", meta.artist)
        assertEquals("Dynamite", meta.album)
        assertEquals(2, meta.track)
        assertEquals(1, meta.disc)
        assertEquals("2020", meta.year)
        assertEquals("rec-1", meta.mbTrackId)
        assertEquals("rel-1", meta.mbReleaseId)
        assertEquals("artist-1", meta.mbArtistId)
        assertEquals("rg-1", meta.mbReleaseGroupId)
        assertEquals("acoustid-1", meta.acoustIdId)
        assertNull(meta.genre, "release-group primary-type must not become a genre")
    }

    @Test
    fun acoustIdIgnoresLowScores() {
        val body = """{"status":"ok","results":[{"id":"x","score":0.31,"recordings":[
            {"id":"r","title":"Dynamite","artists":[{"name":"BTS"}]}]}]}"""
        assertNull(AcoustIdSource.lookup("key", "AQAA", 199, 0.6, { }) { body })
    }

    @Test
    fun acoustIdReportsErrors() {
        assertNull(AcoustIdSource.lookup("key", "AQAA", 199, 0.6, { }) { """{"status":"error"}""" })
        assertNull(AcoustIdSource.lookup("", "AQAA", 199, 0.6, { }) { acoustIdBody })
        assertNull(AcoustIdSource.lookup("key", "", 199, 0.6, { }) { acoustIdBody })
    }

    // ---------------------------------------------------------------- MusicBrainz

    @Test
    fun musicBrainzSearchAndRelease() {
        val search = """
            {"count":2,"recordings":[
              {"id":"mb-rec-2","score":80,"title":"Dynamite","length":240000,
               "artist-credit":[{"name":"Other Band","artist":{"id":"x","name":"Other Band"}}],
               "releases":[]},
              {"id":"mb-rec-1","score":100,"title":"Dynamite","length":199000,
               "artist-credit":[{"name":"BTS","artist":{"id":"mb-artist-1","name":"BTS"}}],
               "releases":[{"id":"mb-rel-1","title":"Dynamite","date":"2020-08-21",
                            "release-group":{"id":"mb-rg-1","primary-type":"Single"},
                            "media":[{"position":1,"track-count":2}]}]}
            ]}
        """.trimIndent()
        val release = """
            {"id":"mb-rel-1","title":"Dynamite","date":"2020-08-21",
             "artist-credit":[{"name":"BTS"}],
             "label-info":[{"label":{"name":"Big Hit"}}],
             "genres":[{"name":"K-pop"}],
             "media":[{"position":1,"track-count":2,"tracks":[
                {"id":"t1","position":1,"title":"Instrumental","recording":{"id":"other"}},
                {"id":"t2","position":2,"title":"Dynamite","recording":{"id":"mb-rec-1"}}]}]}
        """.trimIndent()

        val meta = MusicBrainzSource.searchRecording("Dynamite", "BTS", 199, { }) { url ->
            if (url.startsWith("https://musicbrainz.org/ws/2/recording?")) search else null
        }
        assertNotNull(meta)
        assertEquals("mb-rec-1", meta.mbTrackId, "the closer match must win")
        assertEquals("BTS", meta.artist)
        assertEquals("mb-artist-1", meta.mbArtistId)
        assertEquals("Dynamite", meta.album)
        assertEquals("mb-rel-1", meta.mbReleaseId)
        assertEquals("2020", meta.year)
        assertEquals(2, meta.trackTotal)

        val enriched = MusicBrainzSource.enrichFromRelease(meta, "Dynamite") { url ->
            if (url.startsWith("https://musicbrainz.org/ws/2/release/mb-rel-1")) release else null
        }
        assertEquals("Big Hit", enriched.label)
        assertEquals("K-pop", enriched.genre)
        assertEquals(2, enriched.track)
        assertEquals("BTS", enriched.albumArtist)
    }

    // --------------------------------------------------------------------- iTunes

    private val itunesBody = """
        {"resultCount":2,"results":[
          {"wrapperType":"track","artistName":"Other Band","trackName":"Dynamite",
           "collectionName":"Other Album","trackNumber":3,"trackCount":9,"discNumber":1,
           "discCount":1,"releaseDate":"2012-01-01T08:00:00Z","primaryGenreName":"Rock",
           "trackTimeMillis":241000,"artworkUrl100":"https://is1.mzstatic.com/a/100x100bb.jpg"},
          {"wrapperType":"track","artistName":"BTS","trackName":"Dynamite",
           "collectionName":"Dynamite - Single","trackNumber":1,"trackCount":2,"discNumber":1,
           "discCount":1,"releaseDate":"2020-08-21T07:00:00Z","primaryGenreName":"K-Pop",
           "isrc":"KRA402000001","trackTimeMillis":199000,
           "artworkUrl100":"https://is1.mzstatic.com/b/100x100bb.jpg"}
        ]}
    """.trimIndent()

    @Test
    fun itunesPicksTheRightTrack() {
        val meta = ItunesSource.search("Dynamite", "BTS", 199, "KR", { }) { itunesBody }
        assertNotNull(meta)
        assertEquals("BTS", meta.artist)
        assertEquals("Dynamite - Single", meta.album)
        assertEquals("BTS", meta.albumArtist)
        assertEquals(1, meta.track)
        assertEquals(2, meta.trackTotal)
        assertEquals("K-Pop", meta.genre)
        assertEquals("2020", meta.year)
        assertEquals("KRA402000001", meta.isrc)
        assertEquals("https://is1.mzstatic.com/b/1400x1400bb.jpg", meta.artworkUrl)
    }

    @Test
    fun itunesFallsBackToUsStore() {
        val urls = ArrayList<String>()
        val meta = ItunesSource.search("Dynamite", "BTS", 199, "KR", { }) { url ->
            urls.add(url)
            if (url.contains("country=US")) itunesBody else """{"resultCount":0,"results":[]}"""
        }
        assertNotNull(meta)
        assertEquals(2, urls.size)
        assertTrue(urls[1].contains("country=US"))
    }

    @Test
    fun itunesRejectsUnrelatedResults() {
        val body = """{"resultCount":1,"results":[{"artistName":"Other Band",
            "trackName":"Completely Different","collectionName":"X","trackNumber":1,
            "trackTimeMillis":300000}]}"""
        assertNull(ItunesSource.search("Dynamite", "BTS", 199, "KR", { }) { body })
    }

    // --------------------------------------------------------------------- Deezer

    @Test
    fun deezerSearchFillsAlbumDetails() {
        val search = """
            {"data":[{"id":1,"title":"Dynamite","duration":199,
              "artist":{"id":9,"name":"BTS"},
              "album":{"id":555,"title":"Dynamite",
                       "cover_xl":"https://cdn.deezer.com/cover/1000x1000.jpg"}}]}
        """.trimIndent()
        val album = """
            {"id":555,"title":"Dynamite","release_date":"2020-08-21","label":"Big Hit",
             "nb_discs":1,"cover_xl":"https://cdn.deezer.com/cover/1000x1000.jpg",
             "artist":{"name":"BTS"},"genres":[{"name":"K-Pop"}],
             "tracks":{"data":[
               {"title":"Dynamite","track_position":1,"disk_number":1,"isrc":"KRA402000001"},
               {"title":"Instrumental","track_position":2,"disk_number":1}]}}
        """.trimIndent()
        val meta = DeezerSource.search("Dynamite", "BTS", 199, { }) { url ->
            when {
                url.contains("/album/555") -> album
                url.contains("/search") -> search
                else -> null
            }
        }
        assertNotNull(meta)
        assertEquals("BTS", meta.artist)
        assertEquals("Dynamite", meta.album)
        assertEquals(1, meta.track)
        assertEquals(2, meta.trackTotal)
        assertEquals(1, meta.disc)
        assertEquals("K-Pop", meta.genre)
        assertEquals("Big Hit", meta.label)
        assertEquals("KRA402000001", meta.isrc)
        assertEquals("2020", meta.year)
    }

    // --------------------------------------------------------------------- LRCLIB

    // JSON string bodies need escaped newlines, so keep the raw text and escape when embedding
    private val syncedLrc = "[00:12.34]cause I'm in the stars tonight\n[00:17.10]so watch me bring the fire"
    private val plainLyrics = "cause I'm in the stars tonight\nso watch me bring the fire"
    private val syncedJson = syncedLrc.replace("\n", "\\n")
    private val plainJson = plainLyrics.replace("\n", "\\n")

    @Test
    fun lrclibPrefersSyncedLyrics() {
        val exactPlain = """
            {"id":1,"trackName":"Dynamite","artistName":"BTS","albumName":"Dynamite",
             "duration":199,"instrumental":false,"plainLyrics":"$plainJson","syncedLyrics":null}
        """.trimIndent()
        val searchWithSynced = """
            [{"id":1,"trackName":"Dynamite","artistName":"BTS","albumName":"Dynamite",
              "duration":199,"instrumental":false,"plainLyrics":"$plainJson","syncedLyrics":"$syncedJson"},
             {"id":2,"trackName":"Dynamite (Live)","artistName":"BTS","albumName":"Live",
              "duration":205,"instrumental":false,"plainLyrics":"x","syncedLyrics":null}]
        """.trimIndent()

        val result = LrclibSource.find("Dynamite", "BTS", null, 199, true, { }) { url ->
            when {
                url.contains("/api/get?") -> exactPlain
                url.contains("/api/search?") -> searchWithSynced
                else -> null
            }
        }
        assertNotNull(result)
        assertNotNull(result.synced)
        assertTrue(Lrc.isSynced(result.synced), "synced lyrics expected, got ${result.synced}")
    }

    @Test
    fun lrclibFallsBackToPlainLyrics() {
        val exactPlain = """
            {"id":1,"trackName":"Dynamite","artistName":"BTS","albumName":"Dynamite",
             "duration":199,"instrumental":false,"plainLyrics":"$plainJson","syncedLyrics":null}
        """.trimIndent()
        val result = LrclibSource.find("Dynamite", "BTS", null, 199, true, { }) { url ->
            if (url.contains("/api/get?")) exactPlain else null
        }
        assertNotNull(result)
        assertNull(result.synced)
        assertEquals(plainLyrics, result.plain)
    }

    /** Instrumental entries carry no lyrics, so they must not be reported as lyrics. */
    @Test
    fun lrclibSkipsInstrumentalOnlyEntries() {
        val instrumental = """[{"id":3,"trackName":"Dynamite","artistName":"BTS",
            "duration":199,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}]"""
        val result = LrclibSource.find("Dynamite", "BTS", null, 199, true, { }) { url ->
            if (url.contains("/api/search?")) instrumental else null
        }
        assertNull(result)
    }
}
