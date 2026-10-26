package com.troy.autotag

import android.content.Context
import org.json.JSONObject

/** Every user facing knob of the app, persisted in SharedPreferences. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("autotag", Context.MODE_PRIVATE)

    var acoustIdKey: String
        get() = sp.getString("acoustid_key", DEFAULT_ACOUSTID_KEY) ?: DEFAULT_ACOUSTID_KEY
        set(value) = sp.edit().putString("acoustid_key", value.trim()).apply()

    /** AcoustID (audio fingerprint) lookup. Needs the fingerprint calculator. */
    var useAcoustId: Boolean
        get() = sp.getBoolean("use_acoustid", true)
        set(value) = sp.edit().putBoolean("use_acoustid", value).apply()

    var useMusicBrainz: Boolean
        get() = sp.getBoolean("use_mb", true)
        set(value) = sp.edit().putBoolean("use_mb", value).apply()

    var useItunes: Boolean
        get() = sp.getBoolean("use_itunes", true)
        set(value) = sp.edit().putBoolean("use_itunes", value).apply()

    var useDeezer: Boolean
        get() = sp.getBoolean("use_deezer", true)
        set(value) = sp.edit().putBoolean("use_deezer", value).apply()

    /** Look up (synced) lyrics on lrclib.net. */
    var useLyrics: Boolean
        get() = sp.getBoolean("use_lyrics", true)
        set(value) = sp.edit().putBoolean("use_lyrics", value).apply()

    /** Only fill fields that are empty / clearly wrong, never blindly overwrite. */
    var fillMissingOnly: Boolean
        get() = sp.getBoolean("fill_missing_only", true)
        set(value) = sp.edit().putBoolean("fill_missing_only", value).apply()

    /** Force a single canonical artist/album spelling across the whole batch. */
    var unifyArtistAlbum: Boolean
        get() = sp.getBoolean("unify_artist_album", true)
        set(value) = sp.edit().putBoolean("unify_artist_album", value).apply()

    /** "Artist feat. X" -> "Artist" for the artist tag (feature is kept in the title). */
    var stripFeaturing: Boolean
        get() = sp.getBoolean("strip_featuring", true)
        set(value) = sp.edit().putBoolean("strip_featuring", value).apply()

    /** Write ALBUMARTIST so albums are not split by player apps. */
    var writeAlbumArtist: Boolean
        get() = sp.getBoolean("write_album_artist", true)
        set(value) = sp.edit().putBoolean("write_album_artist", value).apply()

    /** Prefer synced lyrics over plain lyrics, and upgrade plain -> synced. */
    var preferSyncedLyrics: Boolean
        get() = sp.getBoolean("prefer_synced", true)
        set(value) = sp.edit().putBoolean("prefer_synced", value).apply()

    /** Write an additional "<song>.lrc" file next to the audio file. */
    var writeLrcSidecar: Boolean
        get() = sp.getBoolean("write_lrc", true)
        set(value) = sp.edit().putBoolean("write_lrc", value).apply()

    /** Embed a front cover if the file has none. */
    var embedArtwork: Boolean
        get() = sp.getBoolean("embed_artwork", true)
        set(value) = sp.edit().putBoolean("embed_artwork", value).apply()

    /** Keep a copy of the untouched file as "<name>.bak" before writing. */
    var backupOriginal: Boolean
        get() = sp.getBoolean("backup", false)
        set(value) = sp.edit().putBoolean("backup", value).apply()

    /** Write MusicBrainz / AcoustID ids into the tags (harmless, helps players). */
    var writeMbIds: Boolean
        get() = sp.getBoolean("write_mb_ids", true)
        set(value) = sp.edit().putBoolean("write_mb_ids", value).apply()

    /** Store the computed fingerprint in the tag (ACOUSTID_FINGERPRINT). */
    var writeFingerprint: Boolean
        get() = sp.getBoolean("write_fingerprint", false)
        set(value) = sp.edit().putBoolean("write_fingerprint", value).apply()

    /** iTunes storefront used for search (KR has good K-pop data). */
    var itunesCountry: String
        get() = sp.getString("itunes_country", "KR") ?: "KR"
        set(value) = sp.edit().putString("itunes_country", value.trim().uppercase()).apply()

    /** Minimum AcoustID score (0..1) accepted as authoritative. */
    var acoustIdThreshold: Float
        get() = sp.getFloat("acoustid_threshold", 0.70f)
        set(value) = sp.edit().putFloat("acoustid_threshold", value).apply()

    /** Downloaded cover art is shrunk to at most this many pixels (0 = keep original). */
    var artworkMaxSize: Int
        get() = sp.getInt("artwork_max_size", 1000)
        set(value) = sp.edit().putInt("artwork_max_size", value).apply()

    /** Learned "raw spelling -> canonical spelling" artist aliases. */
    var artistAliases: MutableMap<String, String>
        get() = readMap("artist_aliases")
        set(value) = sp.edit().putString("artist_aliases", mapToJson(value)).apply()

    var albumAliases: MutableMap<String, String>
        get() = readMap("album_aliases")
        set(value) = sp.edit().putString("album_aliases", mapToJson(value)).apply()

    fun rememberArtistAlias(raw: String, canonical: String) {
        if (raw.isBlank() || canonical.isBlank() || raw == canonical) return
        val map = artistAliases
        if (map[raw] == canonical) return
        map[raw] = canonical
        artistAliases = map
    }

    fun rememberAlbumAlias(raw: String, canonical: String) {
        if (raw.isBlank() || canonical.isBlank() || raw == canonical) return
        val map = albumAliases
        if (map[raw] == canonical) return
        map[raw] = canonical
        albumAliases = map
    }

    private fun readMap(key: String): MutableMap<String, String> {
        val raw = sp.getString(key, null) ?: return mutableMapOf()
        return try {
            val json = JSONObject(raw)
            val out = mutableMapOf<String, String>()
            for (k in json.keys()) out[k] = json.getString(k)
            out
        } catch (t: Throwable) {
            mutableMapOf()
        }
    }

    private fun mapToJson(map: Map<String, String>): String {
        val json = JSONObject()
        for ((k, v) in map) json.put(k, v)
        return json.toString()
    }

    fun reset() {
        val key = acoustIdKey
        sp.edit().clear().apply()
        acoustIdKey = key
    }

    companion object {
        const val DEFAULT_ACOUSTID_KEY = "uiTojZhLDe"
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "m4b", "mp4", "aac", "ogg", "oga", "opus", "wav", "wave",
            "aif", "aiff", "aifc", "wma", "asf", "dsf", "dff", "ape", "wv", "mpc", "spx",
        )
    }
}
