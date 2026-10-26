package com.troy.autotag.sources

import com.troy.autotag.lyrics.Lrc
import com.troy.autotag.net.Http
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

    /** AcoustID lookups go out as a POST form (the fingerprint is too long for a URL). */
    private fun acoustIdPost(
        body: String,
        code: Int = 200,
        capture: (String, Map<String, String>) -> Unit = { _, _ -> },
    ): (String, Map<String, String>) -> Http.Response? = { url, fields ->
        capture(url, fields)
        Http.Response(code, body.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun acoustIdLookupIsParsed() {
        var requestedUrl = ""
        var requestedFields: Map<String, String> = emptyMap()
        val result = AcoustIdSource.lookup(
            "key", "AQAA", 199, 0.6, { },
            acoustIdPost(acoustIdBody) { url, fields ->
                requestedUrl = url
                requestedFields = fields
            },
        )
        assertNotNull(result)
        assertEquals("https://api.acoustid.org/v2/lookup", requestedUrl)
        assertEquals("key", requestedFields["client"])
        assertEquals("199", requestedFields["duration"])
        assertEquals("AQAA", requestedFields["fingerprint"])
        assertTrue(requestedFields["meta"]!!.contains("recordings"))

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
        assertNull(AcoustIdSource.lookup("key", "AQAA", 199, 0.6, { }, acoustIdPost(body)))
    }

    @Test
    fun acoustIdReportsErrors() {
        assertNull(AcoustIdSource.lookup("key", "AQAA", 199, 0.6, { }, acoustIdPost("""{"status":"error"}""")))
        assertNull(AcoustIdSource.lookup("", "AQAA", 199, 0.6, { }, acoustIdPost(acoustIdBody)))
        assertNull(AcoustIdSource.lookup("key", "", 199, 0.6, { }, acoustIdPost(acoustIdBody)))
    }

    /** An HTTP error body carries the real reason and must reach the log. */
    @Test
    fun acoustIdSurfacesServerErrorMessage() {
        val logs = ArrayList<String>()
        val body = """{"status":"error","error":{"code":3,"message":"invalid fingerprint"}}"""
        val result = AcoustIdSource.lookup("key-err", "AQAA", 199, 0.6, { logs.add(it) }, acoustIdPost(body, code = 400))
        assertNull(result)
        assertTrue(logs.any { it.contains("invalid fingerprint") }, logs.toString())
    }

    /** A rejected API key is not retried for every file of a batch. */
    @Test
    fun acoustIdStopsAfterInvalidApiKey() {
        var calls = 0
        val body = """{"status":"error","error":{"code":4,"message":"invalid API key"}}"""
        val post = acoustIdPost(body, code = 400) { _, _ -> calls++ }
        assertNull(AcoustIdSource.lookup("bad-key-for-test", "AQAA", 199, 0.6, { }, post))
        assertNull(AcoustIdSource.lookup("bad-key-for-test", "AQAA", 199, 0.6, { }, post))
        assertEquals(1, calls)
    }

    // --------------------------------------------------------------------- 벅스(Bugs)

    private val bugsBody = """
        {"list":[
          {"track_no":1,"track_id":111,"track_title":"Dynamite","len":"03:22","disc_id":1,
           "title_yn":false,
           "album":{"album_id":247099,"title":"Rokstarr (Special Edition)","release_ymd":"20100101",
                    "image":{"path":"/2470/247099.jpg"}},
           "artists":[{"artist_id":1,"artist_nm":"Taio Cruz","genres":[{"svc_nm":"팝"}]}]},
          {"track_no":8,"track_id":32075142,"track_title":"Dynamite","len":"03:19","disc_id":1,
           "title_yn":false,
           "album":{"album_id":20361943,"title":"BE","release_ymd":"20201120",
                    "image":{"path":"/203619/20361943.jpg"}},
           "artists":[{"artist_id":80079394,"artist_nm":"방탄소년단","genres":[{"svc_nm":"댄스/팝"}]}]}
        ]}
    """.trimIndent()

    @Test
    fun bugsSearchIsParsed() {
        var requested = ""
        val meta = BugsSource.search("Dynamite", "방탄소년단", 199, { }) { url ->
            requested = url
            bugsBody
        }
        assertNotNull(meta)
        assertTrue(requested.startsWith("https://m.bugs.co.kr/api/getSearchList?type=track"), requested)
        assertEquals("Dynamite", meta.title)
        assertEquals("방탄소년단", meta.artist)
        assertEquals("BE", meta.album)
        assertEquals(8, meta.track)
        assertEquals(1, meta.disc)
        assertEquals("2020", meta.year)
        assertEquals("댄스/팝", meta.genre)
        assertEquals(199, meta.durationSec)
        assertEquals("https://image.bugsm.co.kr/album/images/1000/203619/20361943.jpg", meta.artworkUrl)
    }

    @Test
    fun bugsRejectsUnrelatedResults() {
        val body = """{"list":[{"track_no":1,"track_title":"Completely Different","len":"01:00",
            "album":{"album_id":1,"title":"X","release_ymd":"20200101","image":{"path":"/1/1.jpg"}},
            "artists":[{"artist_nm":"Other Band"}]}]}"""
        assertNull(BugsSource.search("Dynamite", "방탄소년단", 199, { }) { body })
    }

    @Test
    fun bugsAlbumTracksAreKeyedByTitle() {
        val tracks = BugsSource.albumTracks("BE", "방탄소년단") { bugsBody }
        assertEquals(1, tracks.size)
        val meta = tracks.values.first()
        assertEquals(8, meta.track)
        assertEquals("BE", meta.album)
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
