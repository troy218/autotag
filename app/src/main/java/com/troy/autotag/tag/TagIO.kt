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
        val artwork = runCatching { tag.firstArtwork }.getOrNull()
        existing.hasArtwork = artwork != null
        if (artwork != null) {
            val data = runCatching { artwork.binaryData }.getOrNull()
            if (data != null && data.isNotEmpty()) {
                existing.artworkBytes = data.size
                // jaudiotagger often leaves width/height at 0 (esp. ID3 APIC),
                // so fall back to parsing the image header ourselves.
                val declaredW = runCatching { artwork.width }.getOrDefault(0)
                val declaredH = runCatching { artwork.height }.getOrDefault(0)
                if (declaredW > 0 && declaredH > 0) {
                    existing.artworkWidth = declaredW
                    existing.artworkHeight = declaredH
                } else {
                    val parsed = com.troy.autotag.util.ImageInfo.dimensions(data)
                    if (parsed != null) {
                        existing.artworkWidth = parsed.width
                        existing.artworkHeight = parsed.height
                    }
                }
                existing.artworkMime = runCatching { artwork.mimeType }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: com.troy.autotag.util.ImageInfo.mimeOf(data)
            }
        }
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

        // Snapshot the existing cover *before* touching anything. Some
        // containers/formats lose their picture when the tag is rewritten,
        // and an artwork write that fails halfway must never cost the user
        // the cover they already had — we restore from this snapshot.
        val originalArtworks = snapshotArtwork(target)

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
        var artworkApplied = false
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
                artworkApplied = true
            }.onFailure {
                log("      커버 저장 실패: ${it.message}")
                // 삭제까지만 성공하고 새 커버 적용에 실패한 경우 기존 커버 복원
                if (edit.replaceArtwork && originalArtworks.isNotEmpty() &&
                    snapshotArtwork(target).isEmpty()
                ) {
                    restoreArtwork(target, originalArtworks)
                    log("      기존 커버를 유지합니다")
                }
            }
        }
        if (edit.artworkBytes == null && originalArtworks.isNotEmpty() &&
            snapshotArtwork(target).isEmpty()
        ) {
            // 텍스트/가사만 고쳤는데 태그 객체에서 커버가 사라진 경우
            restoreArtwork(target, originalArtworks)
            log("      기존 커버 복원(쓰기 중 유실 방지)")
        }

        if (!changed) return false
        audioFile.setTag(target)
        runCatching { AudioFileIO.write(audioFile) }
            .onFailure {
                log("      파일 저장 실패: ${it.message}")
                return false
            }

        // ---- verify: the cover must still be there after the rewrite ------
        val shouldHaveArtwork = artworkApplied || originalArtworks.isNotEmpty()
        if (shouldHaveArtwork && !fileHasArtwork(file)) {
            val restoreWith = when {
                artworkApplied && edit.artworkBytes != null ->
                    listOf(ArtworkSnapshot(edit.artworkBytes!!, edit.artworkMime, 3))
                else -> originalArtworks
            }
            log("      ! 저장 후 커버가 확인되지 않아 복원을 시도합니다")
            if (restoreWith.isNotEmpty() && repairArtwork(file, restoreWith)) {
                log("      ✓ 커버 복원 완료")
            } else {
                log("      ! 커버 복원 실패 — 원본 백업(.bak)으로 되돌릴 수 있습니다")
            }
        }
        return true
    }

    // ------------------------------------------------------------- artwork care

    private class ArtworkSnapshot(
        val bytes: ByteArray,
        val mime: String?,
        val pictureType: Int,
    )

    private fun snapshotArtwork(tag: Tag): List<ArtworkSnapshot> {
        val list = runCatching { tag.artworkList }.getOrNull() ?: return emptyList()
        val out = ArrayList<ArtworkSnapshot>()
        for (artwork in list) {
            val data = runCatching { artwork.binaryData }.getOrNull()
            if (data == null || data.isEmpty()) continue
            out.add(
                ArtworkSnapshot(
                    bytes = data,
                    mime = runCatching { artwork.mimeType }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: com.troy.autotag.util.ImageInfo.mimeOf(data),
                    pictureType = runCatching { artwork.pictureType }.getOrDefault(3)
                        .takeIf { it in 0..20 } ?: 3, // 3 = Cover (front)
                )
            )
        }
        return out
    }

    private fun restoreArtwork(tag: Tag, snapshots: List<ArtworkSnapshot>): Boolean {
        // setField() replaces the front cover in every format we support, so
        // restoring more than the first picture would just overwrite it again.
        val snapshot = snapshots.firstOrNull() ?: return false
        val artwork = AndroidArtwork()
        artwork.binaryData = snapshot.bytes
        artwork.mimeType = snapshot.mime ?: "image/jpeg"
        artwork.pictureType = snapshot.pictureType
        runCatching { artwork.setImageFromData() }
        return runCatching { tag.setField(artwork) }.isSuccess
    }

    private fun fileHasArtwork(file: File): Boolean {
        val reopened = runCatching { AudioFileIO.read(file) }.getOrNull() ?: return false
        val tag = reopened.tag ?: return false
        return runCatching { tag.firstArtwork != null }.getOrDefault(false)
    }

    /** Re-opens a written file and writes nothing but the cover back into it. */
    private fun repairArtwork(file: File, snapshots: List<ArtworkSnapshot>): Boolean {
        val audioFile = runCatching { AudioFileIO.read(file) }.getOrNull()
            ?: runCatching { AudioFileIO.readMagic(file) }.getOrNull()
            ?: return false
        var tag: Tag? = audioFile.tag
        if (tag == null || tag is org.jaudiotagger.tag.id3.ID3v1Tag) {
            tag = runCatching { audioFile.createDefaultTag() }.getOrNull() ?: return false
            audioFile.setTag(tag)
        }
        val target = tag ?: return false
        if (!restoreArtwork(target, snapshots)) return false
        audioFile.setTag(target)
        return runCatching { AudioFileIO.write(audioFile) }.isSuccess && fileHasArtwork(file)
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
