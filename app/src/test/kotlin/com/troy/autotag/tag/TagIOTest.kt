package com.troy.autotag.tag

import com.troy.autotag.lyrics.Lrc
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs on the JVM (no device needed) because the tagging layer is pure Java.
 * Every format we promise to support is exercised: write tags, re-read the
 * file and check that the values really ended up on disk.
 */
class TagIOTest {

    private fun copy(name: String): File {
        val bytes = javaClass.classLoader!!.getResourceAsStream("tag/$name")!!.use { it.readBytes() }
        val file = Files.createTempFile("autotag-", "-$name").toFile()
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    private fun roundTrip(format: String, name: String, synced: Boolean = false) {
        val file = copy(name)
        val lyrics = if (synced) {
            "[00:01.00]첫 번째 줄\n[00:03.50]second line\n[00:07.25]세 번째 줄\n"
        } else {
            null
        }
        val edit = TagEdit()
        edit.put(FieldKey.TITLE, "테스트 제목")
        edit.put(FieldKey.ARTIST, "테스트 가수")
        edit.put(FieldKey.ALBUM, "테스트 앨범")
        edit.put(FieldKey.ALBUM_ARTIST, "테스트 가수")
        edit.put(FieldKey.TRACK, "3")
        edit.put(FieldKey.TRACK_TOTAL, "11")
        edit.put(FieldKey.DISC_NO, "1")
        edit.put(FieldKey.YEAR, "2019")
        edit.put(FieldKey.GENRE, "Pop")
        if (synced) {
            edit.syncedLyrics = lyrics
        } else {
            edit.plainLyrics = "plain lyric line 1\nplain lyric line 2"
            edit.replacePlainLyrics = true
        }

        val ok = TagIO.write(file, edit, fullWrite = true) { println("[$format] $it") }
        assertTrue(ok, "$format: write() returned false")

        val after = TagIO.read(file)
        assertEquals("테스트 제목", after.title, "$format title")
        assertEquals("테스트 가수", after.artist, "$format artist")
        assertEquals("테스트 앨범", after.album, "$format album")
        assertEquals("2019", after.year, "$format year")
        assertEquals(3, after.track, "$format track")
        assertTrue(after.hasAnyTag, "$format hasAnyTag")
        assertNotNull(after.lyrics, "$format lyrics")
        if (synced) {
            assertTrue(Lrc.isSynced(after.lyrics), "$format lyrics should keep timestamps")
            assertEquals(3, Lrc.parse(after.lyrics).size, "$format parsed line count")
        } else {
            assertFalse(Lrc.isSynced(after.lyrics), "$format plain lyrics must not look synced")
        }
    }

    @Test
    fun mp3RoundTrip() = roundTrip("mp3", "testV2L3Stereo.mp3")

    @Test
    fun mp3WithSyncedLyrics() = roundTrip("mp3(synced)", "testV2L3Stereo.mp3", synced = true)

    @Test
    fun flacRoundTrip() = roundTrip("flac", "test3.flac")

    @Test
    fun flacWithSyncedLyrics() = roundTrip("flac(synced)", "test3.flac", synced = true)

    @Test
    fun oggRoundTrip() = roundTrip("ogg", "test.ogg")

    @Test
    fun wavRoundTrip() = roundTrip("wav", "bug153.wav")

    @Test
    fun m4aRoundTrip() = roundTrip("m4a", "issue-198.m4a")

    @Test
    fun m4aWithSyncedLyrics() = roundTrip("m4a(synced)", "issue-198.m4a", synced = true)

    /** Cover art goes through a different code path per container. */
    @Test
    fun artworkIsWrittenWhereSupported() {
        val jpeg = javaClass.classLoader!!.getResourceAsStream("tag/coverart.jpg")!!.use { it.readBytes() }
        for (name in listOf("testV2L3Stereo.mp3", "test3.flac", "issue-198.m4a")) {
            val file = copy(name)
            val edit = TagEdit()
            edit.put(FieldKey.TITLE, "cover test")
            edit.artworkBytes = jpeg
            edit.artworkMime = "image/jpeg"
            edit.replaceArtwork = true
            val ok = TagIO.write(file, edit, fullWrite = true) { println("[$name] $it") }
            assertTrue(ok, "$name: write() returned false")
        }
    }

    /** Existing values must survive a read/modify cycle (padding, other frames). */
    @Test
    fun existingValuesSurvive() {
        val file = copy("testV2L3Stereo.mp3")
        val first = TagEdit()
        first.put(FieldKey.TITLE, "keep me")
        first.put(FieldKey.COMMENT, "comment must survive")
        assertTrue(TagIO.write(file, first, fullWrite = true) { })

        val second = TagEdit()
        second.put(FieldKey.ARTIST, "added later")
        assertTrue(TagIO.write(file, second, fullWrite = false) { })

        val after = TagIO.read(file)
        assertEquals("keep me", after.title)
        assertEquals("added later", after.artist)
        assertEquals("comment must survive", after.comment)
    }

    /** A file with no writable tag should not crash the pipeline. */
    @Test
    fun unsupportedFileFailsGracefully() {
        val file = Files.createTempFile("autotag-", ".bin").toFile()
        file.writeBytes(ByteArray(64) { it.toByte() })
        val edit = TagEdit()
        edit.put(FieldKey.TITLE, "x")
        assertFalse(TagIO.write(file, edit, fullWrite = true) { println(it) })
    }
}
