package com.troy.autotag.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Tiny HTTP helper (no third party dependencies) with per-host rate limiting,
 * retries and gzip support.
 */
object Http {

    private class HostState(val minIntervalMs: Long) {
        var lastRequest = 0L
    }

    private val hosts = ConcurrentHashMap<String, HostState>()

    /** Minimum delay between two requests to the same host. */
    fun registerHost(host: String, minIntervalMs: Long) {
        hosts[host] = HostState(minIntervalMs)
    }

    init {
        registerHost("musicbrainz.org", 1100) // 1 request/second, required by MB
        registerHost("api.acoustid.org", 350)
        registerHost("itunes.apple.com", 3200) // Apple: ~20 calls/minute
        registerHost("api.deezer.com", 250)
        registerHost("lrclib.net", 320)
        registerHost("m.bugs.co.kr", 350) // 벅스(Bugs) 모바일 검색 API
        registerHost("music.bugs.co.kr", 350)
        registerHost("image.bugsm.co.kr", 150)
    }

    /** Result of a request that must surface HTTP errors (e.g. AcoustID). */
    class Response(val code: Int, val body: ByteArray?) {
        fun text(): String? = body?.let { String(it, Charsets.UTF_8) }
        val isSuccess: Boolean get() = code in 200..299
    }

    /**
     * POSTs `application/x-www-form-urlencoded` data. Unlike [get], an HTTP
     * error status is *returned* (with its body) instead of being swallowed,
     * because APIs like AcoustID put the real reason ("invalid API key",
     * "invalid fingerprint", ...) in the error body. Returns null only for
     * network level failures after retries.
     */
    fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000,
        retries: Int = 2,
    ): Response? {
        val payload = fields.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }.toByteArray(Charsets.UTF_8)
        var attempt = 0
        while (attempt <= retries) {
            try {
                val parsed = URL(url)
                throttle(parsed.host ?: "")
                val connection = (parsed.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    doOutput = true
                    setRequestProperty("Accept-Encoding", "gzip")
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    for ((k, v) in headers) setRequestProperty(k, v)
                }
                connection.outputStream.use { it.write(payload) }
                val code = connection.responseCode
                if (code == 429 || code >= 500) {
                    connection.disconnect()
                    attempt++
                    if (attempt > retries) return Response(code, null)
                    Thread.sleep(1500L * attempt)
                    continue
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.let { raw ->
                    val input = if (connection.contentEncoding?.contains("gzip", true) == true) {
                        GZIPInputStream(raw)
                    } else {
                        raw
                    }
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    input.use {
                        while (true) {
                            val read = it.read(chunk)
                            if (read <= 0) break
                            buffer.write(chunk, 0, read)
                        }
                    }
                    buffer.toByteArray()
                }
                connection.disconnect()
                return Response(code, bytes)
            } catch (e: IOException) {
                attempt++
                if (attempt > retries) return null
                try {
                    Thread.sleep(800L * attempt)
                } catch (ignored: InterruptedException) {
                }
            } catch (t: Throwable) {
                return null
            }
        }
        return null
    }

    private fun throttle(host: String) {
        val state = hosts[host] ?: return
        synchronized(state) {
            val now = System.currentTimeMillis()
            val wait = state.minIntervalMs - (now - state.lastRequest)
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (ignored: InterruptedException) {
                }
            }
            state.lastRequest = System.currentTimeMillis()
        }
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000,
        retries: Int = 2,
    ): String? {
        val bytes = getBytes(url, headers, timeoutMs, retries) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000,
        retries: Int = 2,
    ): ByteArray? {
        var attempt = 0
        while (attempt <= retries) {
            try {
                val parsed = URL(url)
                throttle(parsed.host ?: "")
                val connection = (parsed.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty("Accept-Encoding", "gzip")
                    setRequestProperty("User-Agent", USER_AGENT)
                    for ((k, v) in headers) setRequestProperty(k, v)
                }
                val code = connection.responseCode
                if (code == 404) {
                    connection.disconnect()
                    return null
                }
                if (code == 429 || code >= 500) {
                    connection.disconnect()
                    attempt++
                    if (attempt > retries) return null
                    Thread.sleep(1500L * attempt)
                    continue
                }
                if (code !in 200..299) {
                    connection.disconnect()
                    return null
                }
                val encoding = connection.contentEncoding
                val stream = connection.inputStream
                val input = if (encoding != null && encoding.contains("gzip", true)) {
                    GZIPInputStream(stream)
                } else {
                    stream
                }
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                input.use {
                    while (true) {
                        val read = it.read(chunk)
                        if (read <= 0) break
                        buffer.write(chunk, 0, read)
                    }
                }
                connection.disconnect()
                return buffer.toByteArray()
            } catch (e: IOException) {
                attempt++
                if (attempt > retries) return null
                try {
                    Thread.sleep(800L * attempt)
                } catch (ignored: InterruptedException) {
                }
            } catch (t: Throwable) {
                return null
            }
        }
        return null
    }

    private const val USER_AGENT = "AutoTag/1.5 ( personal Android tagger; https://github.com/troy218/autotag )"
}
