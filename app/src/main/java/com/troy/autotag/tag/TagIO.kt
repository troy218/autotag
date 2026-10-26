package com.troy.autotag.tag

import com.troy.autotag.lyrics.Lrc
import com.troy.autotag.model.ExistingTags
import com.troy.autotag.util.TextTools
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v23Frame
import org.jaudiotagger.tag.id3.ID3v23Frames
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Frames
import org.jaudiotagger.tag.id3.framebody.FrameBodySYLT
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Reads and writes audio tags with jaudiotagger.
 *
 * Supported containers: mp3 (ID3v2.3/2.4), flac, ogg/oga/opus (Vorbis comments),
 * m4a/mp4 (iTunes atoms), wav/aiff (ID3 chunk), dsf (ID3), wma (ASF).
 */
object TagIO {

    private var initialised = false

    fun init() {
        if (initialised) return
        initialised = true
        runCatching {
            Logger.getLogger("org.jaudiotagger").level = Level.OFF
            val singleton = org.jaudiotagger.tag.TagOptionSingleton.getInstance()
            singleton.isAndroid = true
        }
    }

    // ------------------------------------------------------------------ read

    fun read(file: File): ExistingTags {
        init()
        val existing = ExistingTags(format = file.extension.lowercase())
        val audioFile = runCatching { AudioFileIO.read(file) }.getOrNull() ?: return existing
        runCatching {
            val header = audioFile.audioHeader
            if (header != null) {
                existing.durationSec = runCatching { header.trackLength }.getOrDefault(0)
                existing.format = runCatching { header.format }.getOrDefault(existing.format)
            }
        }
        val tag = audioFile.tag ?: return existing
        existing.hasAnyTag = true
        existing.title = first(tag, FieldKey.TITLE)
        existing.artist = first(tag, FieldKey.ARTIST)
        existing.album = first(tag, FieldKey.ALBUM)
        existing.albumArtist = first(tag, FieldKey.ALBUM_ARTIST)
        existing.genre = first(tag, FieldKey.GENRE)
        existing.comment = first(tag, FieldKey.COMMENT)
        existing.isrc = first(tag, FieldKey.ISRC)
        existing.mbTrackId = first(tag, FieldKey.MUSICBRAINZ_TRACK_ID)
        existing.year = TextTools.parseYear(first(tag, FieldKey.YEAR))
        val trackRaw = first(tag, FieldKey.TRACK)
        if (trackRaw != null) {
            existing.track = TextTools.parseIntSafe(trackRaw.substringBefore('/'))
            existing.trackTotal = TextTools.parseIntSafe(trackRaw.substringAfter('/', ""))
        }
        if (existing.trackTotal == null) {
            existing.trackTotal = TextTools.parseIntSafe(first(tag, FieldKey.TRACK_TOTAL))
        }
        val discRaw = first(tag, FieldKey.DISC_NO)
        if (discRaw != null) {
            existing.disc = TextTools.parseIntSafe(discRaw.substringBefore('/'))
            existing.discTotal = TextTools.parseIntSafe(discRaw.substringAfter('/', ""))
        }
        if (existing.discTotal == null) {
            existing.discTotal = TextTools.parseIntSafe(first(tag, FieldKey.DISC_TOTAL))
        }
        existing.lyrics = runCatching { tag.getFirst(FieldKey.LYRICS) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        existing.hasSyncedLyrics = runCatching {
            (tag is AbstractID3v2Tag && tag.hasField("SYLT")) ||
                Lrc.isSynced(existing.lyrics)
        }.getOrDefault(false)
        existing.hasArtwork = runCatching { tag.firstArtwork != null }.getOrDefault(false)
        return existing
    }

    private fun first(tag: Tag, key: FieldKey): String? = runCatching {
        tag.getFirst(key)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    // ----------------------------------------------------------------- write

    /**
     * Applies [edit] to [file]. Returns true when the file was modified.
     */
    fun write(
        file: File,
        edit: TagEdit,
        fullWrite: Boolean,
        log: (String) -> Unit,
    ): Boolean {
        init()
        val audioFile = runCatching { AudioFileIO.read(file) }.getOrNull()
            ?: runCatching { AudioFileIO.readMagic(file) }.getOrNull()
            ?: run {
                log("      태그를 열 수 없음")
                return false
            }
        var tag: Tag? = audioFile.tag
        val hasUsableTag = tag != null && tag !is org.jaudiotagger.tag.id3.ID3v1Tag
        if (!hasUsableTag) {
            val created = runCatching { audioFile.createDefaultTag() }.getOrNull()
            if (created != null) {
                audioFile.setTag(created)
                tag = created
            }
        }
        val target = tag ?: run {
            log("      이 형식은 태그 쓰기를 지원하지 않음")
            return false
        }

        var changed = false
        for ((key, value) in edit.textFields) {
            if (value.isBlank()) continue
            if (!fullWrite && isSame(target, key, value)) continue
            runCatching { target.setField(key, value) }
                .onSuccess { changed = true }
                .onFailure { log("      필드 저장 실패($key): ${it.message}") }
        }

        // ---- lyrics ------------------------------------------------------
        val synced = edit.syncedLyrics
        val plain = edit.plainLyrics
        if (!synced.isNullOrBlank()) {
            runCatching { target.setField(FieldKey.LYRICS, synced) }
                .onSuccess { changed = true }
            if (target is AbstractID3v2Tag) {
                val payload = Lrc.buildSyltPayload(Lrc.parse(synced))
                if (payload.isNotEmpty()) {
                    if (writeSylt(target, payload)) changed = true
                }
            }
        } else if (!plain.isNullOrBlank() && edit.replacePlainLyrics) {
            runCatching { target.setField(FieldKey.LYRICS, plain) }
                .onSuccess { changed = true }
        }

        // ---- artwork -----------------------------------------------------
        edit.artworkBytes?.let { bytes ->
            val artwork = AndroidArtwork()
            artwork.binaryData = bytes
            artwork.mimeType = edit.artworkMime ?: "image/jpeg"
            artwork.pictureType = PictureTypes.DEFAULT_ID
            runCatching { artwork.setImageFromData() }
            runCatching {
                if (edit.replaceArtwork) target.deleteArtworkField()
                target.setField(artwork)
            }.onSuccess {
                changed = true
            }.onFailure {
                log("      커버 저장 실패: ${it.message}")
            }
        }

        if (!changed) return false
        audioFile.setTag(target)
        runCatching { AudioFileIO.write(audioFile) }
            .onFailure {
                log("      파일 저장 실패: ${it.message}")
                return false
            }
        return true
    }

    private fun isSame(tag: Tag, key: FieldKey, value: String): Boolean {
        val current = runCatching { tag.getFirst(key) }.getOrNull() ?: return false
        return current.trim() == value.trim()
    }

    private fun writeSylt(tag: AbstractID3v2Tag, payload: ByteArray): Boolean {
        val language = if (payload.containsHangul()) "kor" else "eng"
        val body = FrameBodySYLT(
            1, // UTF-16 with BOM
            language,
            2, // timestamps in milliseconds
            1, // content type: lyrics
            "",
            payload,
        )
        return when (tag) {
            is org.jaudiotagger.tag.id3.ID3v24Tag -> {
                val frame = ID3v24Frame(ID3v24Frames.FRAME_ID_SYNC_LYRIC)
                frame.body = body
                tag.setFrame(frame)
                true
            }
            is org.jaudiotagger.tag.id3.ID3v23Tag -> {
                val frame = ID3v23Frame(ID3v23Frames.FRAME_ID_V3_SYNC_LYRIC)
                frame.body = body
                tag.setFrame(frame)
                true
            }
            else -> false
        }
    }

    private fun ByteArray.containsHangul(): Boolean {
        var i = 0
        while (i + 1 < size) {
            val code = ((this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8))
            if (code in 0xAC00..0xD7A3) return true
            i += 2
        }
        return false
    }
}

/** Everything that should be written into a file. */
class TagEdit(
    val textFields: LinkedHashMap<FieldKey, String> = LinkedHashMap(),
    var syncedLyrics: String? = null,
    var plainLyrics: String? = null,
    var artworkBytes: ByteArray? = null,
    var artworkMime: String? = null,
    var replacePlainLyrics: Boolean = true,
    var replaceArtwork: Boolean = false,
) {
    fun put(key: FieldKey, value: String?) {
        if (!value.isNullOrBlank()) textFields[key] = value.trim()
    }

    fun needsId3v2(): Boolean = syncedLyrics != null || artworkBytes != null || textFields.isNotEmpty()
}
