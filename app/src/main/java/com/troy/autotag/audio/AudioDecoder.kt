package com.troy.autotag.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the first [maxSeconds] of an audio file to mono 16 bit PCM using the
 * platform decoders (mp3, aac/m4a, flac, vorbis/opus, wav ...).
 *
 * The PCM is only needed to compute the AcoustID / Chromaprint fingerprint.
 */
object AudioDecoder {

    class Decoded(val samples: ShortArray, val sampleRate: Int)

    fun decode(file: File, maxSeconds: Int = 120): Decoded? {
        if (maxSeconds <= 0) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getIntOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = format.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmFloat = false

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val maxSamples = maxSeconds.toLong() * sampleRate
            val out = ShortGrower((maxSamples / 4).toInt().coerceAtLeast(65536))

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var producedFrames = 0L
            val startWallClock = System.currentTimeMillis()

            while (!outputDone) {
                if (System.currentTimeMillis() - startWallClock > 45_000) break
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        if (buffer == null) {
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        sampleRate = outFormat.getIntOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = outFormat.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmFloat = outFormat.getIntOr("pcm-encoding", 2) == 4
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // keep spinning; the wall clock guard protects us
                    }
                    else -> {
                        if (outIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outIndex)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                producedFrames += appendPcm(out, buffer, channels, pcmFloat)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                            if (producedFrames >= maxSeconds.toLong() * sampleRate) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
            if (out.size <= 0) return null
            return Decoded(out.toArray(), sampleRate)
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun appendPcm(
        out: ShortGrower,
        buffer: ByteBuffer,
        channels: Int,
        pcmFloat: Boolean,
    ): Long {
        val byteOrder = buffer.order(ByteOrder.nativeOrder())
        if (pcmFloat) {
            val floats = byteOrder.asFloatBuffer()
            val frames = floats.remaining() / channels
            for (i in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += floats.get()
                val value = (sum / channels * 32767f).coerceIn(-32768f, 32767f)
                out.add(value.toInt().toShort())
            }
            return frames.toLong()
        }
        val shorts = byteOrder.asShortBuffer()
        val frames = shorts.remaining() / channels
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += shorts.get().toInt()
            out.add((sum / channels).toShort())
        }
        return frames.toLong()
    }

    private fun MediaFormat.getIntOr(key: String, fallback: Int): Int {
        return try {
            if (containsKey(key)) getInteger(key) else fallback
        } catch (t: Throwable) {
            fallback
        }
    }

    internal class ShortGrower(initial: Int = 1 shl 16) {
        private var data = ShortArray(initial.coerceAtLeast(1024))
        var size = 0
            private set

        fun add(value: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }
}
