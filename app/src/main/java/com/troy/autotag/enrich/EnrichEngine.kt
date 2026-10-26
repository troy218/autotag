package com.troy.autotag.enrich

import com.troy.autotag.Prefs
import com.troy.autotag.audio.ArtworkFetcher
import com.troy.autotag.audio.AudioDecoder
import com.troy.autotag.lyrics.Lrc
import com.troy.autotag.model.LyricsResult
import com.troy.autotag.model.Meta
import com.troy.autotag.model.Track
import com.troy.autotag.sources.AcoustIdSource
import com.troy.autotag.sources.DeezerSource
import com.troy.autotag.sources.ItunesSource
import com.troy.autotag.sources.LrclibSource
import com.troy.autotag.sources.MusicBrainzSource
import com.troy.autotag.tag.TagEdit
import com.troy.autotag.tag.TagIO
import com.troy.autotag.util.TextTools
import com.troy.autotag.fp.Chromaprint
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two pass enrichment:
 *
 *  pass 1 (parallel): read tags, identify the recording online
 *                     (AcoustID -> MusicBrainz -> iTunes -> Deezer) and find lyrics.
 *  pass 2 (serial):   make artist / album / album artist spellings consistent
 *                     across the whole batch (this is what stops player apps from
 *                     showing one artist as two different ones).
 *  pass 3 (parallel): write the tags, cover art and synced lyrics.
 */
class EnrichEngine(
    private val prefs: Prefs,
    private val log: (String) -> Unit,
    private val cancelled: () -> Boolean,
) {

    fun run(tracks: List<Track>, progress: (done: Int, total: Int, current: String) -> Unit) {
        if (tracks.isEmpty()) return
        val done = AtomicInteger(0)
        val total = tracks.size

        // ---------------- pass 1: identify -----------------------------------
        val pool = Executors.newFixedThreadPool(2)
        try {
            for (track in tracks) {
                pool.submit {
                    if (cancelled()) return@submit
                    try {
                        identify(track)
                    } catch (t: Throwable) {
                        track.error = t.message ?: t.javaClass.simpleName
                        log("  ! ${track.file.name}: ${track.error}")
                    }
                    val d = done.incrementAndGet()
                    progress(d, total, track.file.name)
                }
            }
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.MINUTES)
        } finally {
            pool.shutdownNow()
        }

        // ---------------- pass 2: batch consistency --------------------------
        unifyArtists(tracks)
        unifyAlbums(tracks)
        fillMissingTrackNumbers(tracks)

        // ---------------- pass 3: write --------------------------------------
        done.set(0)
        val writer = Executors.newFixedThreadPool(2)
        try {
            for (track in tracks) {
                writer.submit {
                    if (cancelled()) return@submit
                    try {
                        applyToFile(track)
                    } catch (t: Throwable) {
                        track.error = t.message ?: t.javaClass.simpleName
                        log("  ! ${track.file.name}: ${track.error}")
                    }
                    val d = done.incrementAndGet()
                    progress(d, total, track.file.name)
                }
            }
            writer.shutdown()
            writer.awaitTermination(60, TimeUnit.MINUTES)
        } finally {
            writer.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ pass 1

    private fun identify(track: Track) {
        val existing = TagIO.read(track.file)
        track.tags = existing
        val guess = TextTools.guessFromFileName(track.file.name)

        val knownTitle = existing.title?.takeIf { it.isNotBlank() } ?: guess.title
        val knownArtist = existing.artist?.takeIf { it.isNotBlank() } ?: guess.artist
        val knownAlbum = existing.album?.takeIf { it.isNotBlank() }
        val duration = existing.durationSec.takeIf { it > 0 }

        log("▶ ${track.file.name}")
        if (!knownTitle.isNullOrBlank()) {
            log("  현재: ${knownArtist ?: "?"} - $knownTitle" + (knownAlbum?.let { " / $it" } ?: ""))
        }

        val found = Meta()
        var authoritative = false

        // --- 1) AcoustID (audio fingerprint) -----------------------------
        if (prefs.useAcoustId && prefs.acoustIdKey.isNotBlank()) {
            val decoded = AudioDecoder.decode(track.file)
            if (decoded != null && decoded.samples.isNotEmpty()) {
                val fp = Chromaprint.fingerprint(decoded.samples, decoded.sampleRate, 1)
                if (fp != null && fp.encoded.isNotBlank()) {
                    track.fingerprint = fp.encoded
                    val fingerprintDuration = if (duration != null && duration > 0) duration
                    else decoded.samples.size / decoded.sampleRate
                    val result = AcoustIdSource.lookup(
                        prefs.acoustIdKey,
                        fp.encoded,
                        fingerprintDuration,
                        prefs.acoustIdThreshold.toDouble(),
                        log,
                    )
                    if (result != null) {
                        found.fillFrom(result.meta)
                        if (result.meta.mbTrackId != null) authoritative = true
                        if (prefs.useMusicBrainz && (result.meta.album == null || result.meta.track == null)) {
                            MusicBrainzSource.enrichFromRelease(result.meta, result.meta.title ?: knownTitle)
                            found.fillFrom(result.meta)
                        }
                    }
                } else {
                    log("  지문 생성 실패")
                }
            } else {
                log("  오디오 디코딩 실패 (AcoustID 생략)")
            }
        }

        // --- 2) MusicBrainz text search ----------------------------------
        val searchTitle = knownTitle ?: found.title
        val searchArtist = knownArtist ?: found.artist
        if (!authoritative && prefs.useMusicBrainz && !searchTitle.isNullOrBlank()) {
            val mb = MusicBrainzSource.searchRecording(searchTitle, searchArtist, duration, log)
            if (mb != null) {
                found.fillFrom(mb)
                if (prefs.useMusicBrainz && (found.album == null || found.track == null)) {
                    MusicBrainzSource.enrichFromRelease(mb, searchTitle)
                    found.fillFrom(mb)
                }
            }
        }

        // --- 3) iTunes (cover art, genre, track numbers) ------------------
        val itunesTitle = searchTitle ?: found.title
        val itunesArtist = searchArtist ?: found.artist
        if (prefs.useItunes && !itunesTitle.isNullOrBlank()) {
            val itunes = ItunesSource.search(itunesTitle, itunesArtist, duration, prefs.itunesCountry, log)
            if (itunes != null) found.fillFrom(itunes)
        }

        // --- 4) Deezer (fallback for art / album data) -------------------
        if (prefs.useDeezer && (found.artworkUrl == null || found.album == null || found.track == null)) {
            val deezer = DeezerSource.search(itunesTitle ?: found.title ?: "", itunesArtist ?: found.artist, duration, log)
            if (deezer != null) found.fillFrom(deezer)
        }

        // --- merge into the final values (never lose existing data) -------
        val finalMeta = merge(existing, found, knownTitle, knownArtist, knownAlbum, guess)
        track.meta = finalMeta

        // --- lyrics -------------------------------------------------------
        if (prefs.useLyrics) {
            val wantLyrics = when {
                existing.hasSyncedLyrics -> false
                !existing.lyrics.isNullOrBlank() && !prefs.preferSyncedLyrics -> false
                else -> true
            }
            if (wantLyrics) {
                val lyrics = LrclibSource.find(
                    title = finalMeta.title ?: knownTitle ?: "",
                    artist = finalMeta.artist ?: knownArtist,
                    album = finalMeta.album ?: knownAlbum,
                    durationSec = finalMeta.durationSec ?: duration,
                    preferSynced = prefs.preferSyncedLyrics,
                    log = log,
                )
                track.lyrics = lyrics
                if (lyrics == null && !existing.lyrics.isNullOrBlank()) {
                    log("  기존 가사 유지")
                }
            } else if (existing.hasSyncedLyrics) {
                log("  이미 싱크가사 보유")
            }
        }
    }

    /**
     * Decides the final values of every field.
     *
     * Existing values win unless they are empty, obviously partial (e.g. no
     * album) or only differ in formatting from the online data.
     */
    private fun merge(
        existing: com.troy.autotag.model.ExistingTags,
        found: Meta,
        knownTitle: String?,
        knownArtist: String?,
        knownAlbum: String?,
        guess: TextTools.GuessedNames,
    ): Meta {
        val out = Meta()

        // title
        val titleCandidate = found.title ?: knownTitle ?: guess.title
        out.title = chooseString(
            existingValue = existing.title,
            candidateValue = titleCandidate,
            similar = { a, b -> TextTools.similarity(a, b) >= 0.9 },
        )

        // artist
        val artistCandidate = found.artist?.let {
            if (prefs.stripFeaturing) TextTools.artistOnly(it) else it
        }
        out.artist = chooseString(
            existingValue = existing.artist,
            candidateValue = artistCandidate,
            similar = { a, b -> TextTools.artistSimilarity(a, b) >= 0.9 },
        )

        // album
        out.album = chooseString(
            existingValue = existing.album,
            candidateValue = found.album ?: knownAlbum,
            similar = { a, b -> TextTools.albumKey(a) == TextTools.albumKey(b) },
        )

        // album artist
        out.albumArtist = chooseString(
            existingValue = existing.albumArtist,
            candidateValue = found.albumArtist,
            similar = { a, b -> TextTools.artistSimilarity(a, b) >= 0.9 },
        )
        if (out.albumArtist.isNullOrBlank()) out.albumArtist = out.artist

        // simple "fill if empty" fields
        out.track = existing.track ?: found.track
        out.trackTotal = existing.trackTotal ?: found.trackTotal
        out.disc = existing.disc ?: found.disc
        out.discTotal = existing.discTotal ?: found.discTotal
        out.year = existing.year ?: TextTools.parseYear(found.year)
        out.genre = existing.genre ?: found.genre
        out.isrc = existing.isrc ?: found.isrc
        out.label = found.label
        out.mbTrackId = existing.mbTrackId ?: found.mbTrackId
        out.mbReleaseId = found.mbReleaseId
        out.mbArtistId = found.mbArtistId
        out.mbReleaseGroupId = found.mbReleaseGroupId
        out.acoustIdId = found.acoustIdId
        out.artworkUrl = found.artworkUrl
        out.source = found.source
        out.durationSec = found.durationSec ?: existing.durationSec.takeIf { it > 0 }
        out.score = found.score
        return out
    }

    private fun chooseString(
        existingValue: String?,
        candidateValue: String?,
        similar: (String, String) -> Boolean,
    ): String? {
        if (candidateValue.isNullOrBlank()) return existingValue?.takeIf { it.isNotBlank() }
        if (existingValue.isNullOrBlank()) return candidateValue
        if (existingValue.trim() == candidateValue.trim()) return existingValue.trim()
        if (!prefs.fillMissingOnly) return candidateValue
        if (prefs.unifyArtistAlbum && similar(existingValue, candidateValue)) return candidateValue
        return existingValue
    }

    // ------------------------------------------------------------------ pass 2

    private fun unifyArtists(tracks: List<Track>) {
        val groups = tracks
            .filter { !it.meta.artist.isNullOrBlank() }
            .groupBy { TextTools.artistKey(it.meta.artist!!) }
            .filterKeys { it.isNotBlank() }

        for ((_, group) in groups) {
            if (group.size < 1) continue
            val canonical = pickCanonical(group) { it.meta.artist } ?: continue
            val alias = prefs.artistAliases[canonical]
            val finalName = if (!alias.isNullOrBlank() && prefs.unifyArtistAlbum) alias else canonical
            for (track in group) {
                val previous = track.meta.artist
                track.meta.artist = finalName
                if (prefs.unifyArtistAlbum) {
                    if (track.meta.albumArtist.isNullOrBlank() ||
                        TextTools.artistKey(track.meta.albumArtist!!) == TextTools.artistKey(previous ?: "")
                    ) {
                        track.meta.albumArtist = finalName
                    }
                }
                if (!previous.isNullOrBlank() && previous != finalName) {
                    prefs.rememberArtistAlias(previous, finalName)
                }
                val rawExisting = track.tags.artist
                if (!rawExisting.isNullOrBlank()) prefs.rememberArtistAlias(rawExisting, finalName)
            }
        }
    }

    private fun unifyAlbums(tracks: List<Track>) {
        val groups = tracks
            .filter { !it.meta.album.isNullOrBlank() }
            .groupBy {
                val artistKey = TextTools.artistKey(it.meta.albumArtist ?: it.meta.artist ?: "")
                "$artistKey|${TextTools.albumKey(it.meta.album!!)}"
            }

        for ((_, group) in groups) {
            val canonical = pickCanonical(group) { it.meta.album } ?: continue
            val alias = prefs.albumAliases[canonical]
            val finalAlbum = if (!alias.isNullOrBlank() && prefs.unifyArtistAlbum) alias else canonical
            val years = group.mapNotNull { it.meta.year }.filter { it.length == 4 }
            val canonicalYear = years.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            for (track in group) {
                val previous = track.meta.album
                track.meta.album = finalAlbum
                if (canonicalYear != null && (track.meta.year.isNullOrBlank() || prefs.unifyArtistAlbum)) {
                    track.meta.year = canonicalYear
                }
                if (!previous.isNullOrBlank() && previous != finalAlbum) {
                    prefs.rememberAlbumAlias(previous, finalAlbum)
                }
                val rawExisting = track.tags.album
                if (!rawExisting.isNullOrBlank()) prefs.rememberAlbumAlias(rawExisting, finalAlbum)
            }
        }
    }

    /**
     * Picks the spelling that should be used for a group: prefer values that
     * came from an online database, then the most frequent one.
     */
    private fun pickCanonical(group: List<Track>, selector: (Track) -> String?): String? {
        val dbCounts = HashMap<String, Int>()
        val allCounts = HashMap<String, Int>()
        for (track in group) {
            val value = selector(track)?.takeIf { it.isNotBlank() } ?: continue
            allCounts[value] = (allCounts[value] ?: 0) + 1
            val source = track.meta.source
            if (source.contains("AcoustID") || source.contains("MusicBrainz") ||
                source.contains("iTunes") || source.contains("Deezer")
            ) {
                dbCounts[value] = (dbCounts[value] ?: 0) + 1
            }
        }
        if (allCounts.isEmpty()) return null
        val pool = if (dbCounts.isNotEmpty() && prefs.unifyArtistAlbum) dbCounts else allCounts
        val best = pool.entries.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }
                .thenBy { if (it.key == it.key.trim()) 1 else 0 }
                .thenBy { -it.key.length },
        )
        if (best != null && best.key.isNotBlank()) return best.key.trim()
        return allCounts.entries.maxByOrNull { it.value }?.key?.trim()
    }

    /**
     * Files that are missing a track number get one from the album track list.
     */
    private fun fillMissingTrackNumbers(tracks: List<Track>) {
        val albumGroups = tracks
            .filter { !it.meta.album.isNullOrBlank() && it.meta.track == null }
            .groupBy { TextTools.albumKey(it.meta.album!!) }
        if (albumGroups.isEmpty()) return

        for ((_, group) in albumGroups) {
            if (cancelled()) return
            val album = group.first().meta.album ?: continue
            val artist = group.first().meta.albumArtist ?: group.first().meta.artist
            var lookup: Map<String, Meta> = emptyMap()
            if (prefs.useItunes) {
                lookup = ItunesSource.albumTracks(album, artist, prefs.itunesCountry)
            }
            if (lookup.isEmpty() && prefs.useDeezer) {
                lookup = DeezerSource.albumTracks(album, artist)
            }
            if (lookup.isEmpty()) continue
            for (track in group) {
                val title = track.meta.title ?: continue
                val match = lookup[TextTools.matchKey(title)]
                    ?: lookup.entries.firstOrNull {
                        TextTools.similarity(it.key, TextTools.matchKey(title)) >= 0.85
                    }?.value
                if (match != null) {
                    track.meta.track = match.track
                    if (track.meta.trackTotal == null) track.meta.trackTotal = match.trackTotal
                    if (track.meta.disc == null) track.meta.disc = match.disc
                    if (track.meta.discTotal == null) track.meta.discTotal = match.discTotal
                    if (track.meta.genre == null) track.meta.genre = match.genre
                    if (track.meta.year == null) track.meta.year = match.year
                    log("  트랙번호 보정: ${track.file.name} -> ${match.track}")
                }
            }
        }
    }

    // ------------------------------------------------------------------ pass 3

    private fun applyToFile(track: Track) {
        if (cancelled()) return
        val existing = track.tags
        val meta = track.meta
        val edit = TagEdit()

        val changes = track.changes

        fun put(key: FieldKey, label: String, value: String?, current: String?, overwrite: Boolean) {
            if (value.isNullOrBlank()) return
            if (!current.isNullOrBlank() && current.trim() == value.trim()) return
            if (!overwrite && !current.isNullOrBlank()) return
            edit.put(key, value)
            changes.add("$label: ${current ?: "-"} → $value")
        }

        val overwriteAll = !prefs.fillMissingOnly
        put(FieldKey.TITLE, "제목", meta.title, existing.title, overwriteAll || prefs.unifyArtistAlbum)
        put(FieldKey.ARTIST, "가수", meta.artist, existing.artist, overwriteAll || prefs.unifyArtistAlbum)
        put(FieldKey.ALBUM, "앨범", meta.album, existing.album, overwriteAll || prefs.unifyArtistAlbum)
        if (prefs.writeAlbumArtist) {
            put(
                FieldKey.ALBUM_ARTIST, "앨범가수", meta.albumArtist ?: meta.artist, existing.albumArtist,
                overwriteAll || prefs.unifyArtistAlbum,
            )
        }
        put(FieldKey.YEAR, "연도", meta.year, existing.year, overwriteAll || prefs.unifyArtistAlbum)
        put(FieldKey.GENRE, "장르", meta.genre, existing.genre, overwriteAll)
        put(FieldKey.TRACK, "트랙", meta.track?.toString(), existing.track?.toString(), overwriteAll)
        put(FieldKey.TRACK_TOTAL, "총트랙", meta.trackTotal?.toString(), existing.trackTotal?.toString(), overwriteAll)
        put(FieldKey.DISC_NO, "디스크", meta.disc?.toString(), existing.disc?.toString(), overwriteAll)
        put(FieldKey.DISC_TOTAL, "총디스크", meta.discTotal?.toString(), existing.discTotal?.toString(), overwriteAll)
        put(FieldKey.ISRC, "ISRC", meta.isrc, existing.isrc, overwriteAll)
        if (prefs.writeMbIds) {
            put(FieldKey.MUSICBRAINZ_TRACK_ID, "MB recording", meta.mbTrackId, existing.mbTrackId, false)
            put(FieldKey.MUSICBRAINZ_RELEASEID, "MB release", meta.mbReleaseId, null, false)
            put(FieldKey.MUSICBRAINZ_ARTISTID, "MB artist", meta.mbArtistId, null, false)
            put(FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID, "MB release group", meta.mbReleaseGroupId, null, false)
            put(FieldKey.ACOUSTID_ID, "AcoustID", meta.acoustIdId, null, false)
        }
        if (prefs.writeFingerprint && !track.fingerprint.isNullOrBlank()) {
            edit.put(FieldKey.ACOUSTID_FINGERPRINT, track.fingerprint)
        }

        // ---- lyrics ------------------------------------------------------
        val lyrics = track.lyrics
        if (lyrics != null && lyrics.isUsable() && prefs.useLyrics) {
            val syncedAvailable = lyrics.synced != null && Lrc.isSynced(lyrics.synced)
            when {
                existing.hasSyncedLyrics -> Unit
                syncedAvailable && prefs.preferSyncedLyrics -> {
                    edit.syncedLyrics = lyrics.synced
                    changes.add("싱크가사: ${lyrics.source}")
                }
                !lyrics.plain.isNullOrBlank() -> {
                    edit.plainLyrics = lyrics.plain
                    changes.add("가사: ${lyrics.source}")
                }
                lyrics.synced != null -> {
                    edit.syncedLyrics = lyrics.synced
                    changes.add("싱크가사: ${lyrics.source}")
                }
            }
        }

        // ---- artwork -----------------------------------------------------
        val wantsArtwork = prefs.embedArtwork && meta.artworkUrl != null && !existing.hasArtwork
        if (wantsArtwork) {
            val art = ArtworkFetcher.fetch(meta.artworkUrl!!, prefs.artworkMaxSize, log)
            if (art != null) {
                edit.artworkBytes = art.bytes
                edit.artworkMime = art.mime
                edit.replaceArtwork = false
                changes.add("커버 삽입 (${art.bytes.size / 1024}KB)")
            }
        }

        if (edit.textFields.isEmpty() && edit.syncedLyrics == null &&
            edit.plainLyrics == null && edit.artworkBytes == null
        ) {
            track.status = "변경 없음"
            log("  = 변경 없음")
            writeLrcSidecar(track)
            return
        }

        if (prefs.backupOriginal && !track.needsCopyBack) {
            val backup = File(track.file.parentFile, track.file.name + ".bak")
            if (!backup.exists()) {
                runCatching { track.file.copyTo(backup, overwrite = false) }
            }
        }

        val fullWrite = !existing.hasAnyTag
        val ok = TagIO.write(track.file, edit, fullWrite, log)
        if (ok) {
            track.status = "${changes.size}개 변경"
            log("  ✓ ${changes.size}개 항목 저장")
            writeLrcSidecar(track)
        } else {
            track.status = "저장 실패"
        }

        // re-read to verify
        val after = TagIO.read(track.file)
        if (ok && after.title.isNullOrBlank() && !meta.title.isNullOrBlank()) {
            log("  ! 저장 후 확인 실패")
        }
    }

    private fun writeLrcSidecar(track: Track) {
        if (!prefs.writeLrcSidecar || track.needsCopyBack) return
        val synced = track.lyrics?.synced
        val lrcText = when {
            !synced.isNullOrBlank() && Lrc.isSynced(synced) -> synced
            track.tags.hasSyncedLyrics -> track.tags.lyrics
            else -> null
        }
        if (lrcText.isNullOrBlank() || !Lrc.isSynced(lrcText)) return
        val base = track.file.nameWithoutExtension
        val lrcFile = File(track.file.parentFile, "$base.lrc")
        runCatching {
            if (!lrcFile.exists() || lrcFile.readText() != lrcText) {
                lrcFile.writeText(Lrc.toLrcText(Lrc.parse(lrcText)), Charsets.UTF_8)
                log("  ✓ ${lrcFile.name} 생성 (싱크가사)")
            }
        }
    }
}
