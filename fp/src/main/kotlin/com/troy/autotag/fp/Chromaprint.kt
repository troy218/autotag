package com.troy.autotag.fp

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin port of [Chromaprint](https://acoustid.org/chromaprint) 1.5.x,
 * the audio fingerprinting library used by AcoustID / `fpcalc`.
 *
 * Only the parts needed to produce AcoustID compatible fingerprints are ported
 * (algorithm "test 2", which is what the `fpcalc` command line tool uses by
 * default). No native code, no JNI, so it also runs on the JVM for unit tests.
 */
object Chromaprint {
    const val SAMPLE_RATE = 11025
    const val FRAME_SIZE = 4096
    const val FRAME_OVERLAP = FRAME_SIZE - FRAME_SIZE / 3
    const val MIN_FREQ = 28
    const val MAX_FREQ = 3520

    /** Chromaprint "algorithm" number that identifies the classifier set below. */
    const val ALGORITHM = 2

    private val CHROMA_FILTER_COEFFICIENTS = doubleArrayOf(0.25, 0.75, 1.0, 0.75, 0.25)

    /** Classifiers of FingerprinterConfigurationTest2 (fpcalc's default). */
    private val CLASSIFIERS: Array<Classifier> = arrayOf(
        Classifier(Filter(0, 4, 3, 15), Quantizer(1.98215, 2.35817, 2.63523)),
        Classifier(Filter(4, 4, 6, 15), Quantizer(-1.03809, -0.651211, -0.282167)),
        Classifier(Filter(1, 0, 4, 16), Quantizer(-0.298702, 0.119262, 0.558497)),
        Classifier(Filter(3, 8, 2, 12), Quantizer(-0.105439, 0.0153946, 0.135898)),
        Classifier(Filter(3, 4, 4, 8), Quantizer(-0.142891, 0.0258736, 0.200632)),
        Classifier(Filter(4, 0, 3, 5), Quantizer(-0.826319, -0.590612, -0.368214)),
        Classifier(Filter(1, 2, 2, 9), Quantizer(-0.557409, -0.233035, 0.0534525)),
        Classifier(Filter(2, 7, 3, 4), Quantizer(-0.0646826, 0.00620476, 0.0784847)),
        Classifier(Filter(2, 6, 2, 16), Quantizer(-0.192387, -0.029699, 0.215855)),
        Classifier(Filter(2, 1, 3, 2), Quantizer(-0.0397818, -0.00568076, 0.0292026)),
        Classifier(Filter(5, 10, 1, 15), Quantizer(-0.53823, -0.369934, -0.190235)),
        Classifier(Filter(3, 6, 2, 10), Quantizer(-0.124877, 0.0296483, 0.139239)),
        Classifier(Filter(2, 1, 1, 14), Quantizer(-0.101475, 0.0225617, 0.231971)),
        Classifier(Filter(3, 5, 6, 4), Quantizer(-0.0799915, -0.00729616, 0.063262)),
        Classifier(Filter(1, 9, 2, 12), Quantizer(-0.272556, 0.019424, 0.302559)),
        Classifier(Filter(3, 4, 2, 14), Quantizer(-0.164292, -0.0321188, 0.0846339)),
    )

    class Result(
        /** Raw (uncompressed) sub-fingerprints, as returned by `fpcalc -raw`. */
        val raw: IntArray,
        /** Compressed + base64 encoded fingerprint, as sent to the AcoustID API. */
        val encoded: String,
    )

    /**
     * Computes the Chromaprint fingerprint of the given PCM samples.
     *
     * @param pcm interleaved signed 16 bit PCM
     * @param sampleRate sample rate of [pcm]
     * @param channels number of interleaved channels (1, 2, ...)
     * @param maxSeconds how much audio to process (fpcalc defaults to 120s)
     */
    fun fingerprint(
        pcm: ShortArray,
        sampleRate: Int,
        channels: Int,
        maxSeconds: Int = 120,
    ): Result? {
        if (channels <= 0 || sampleRate <= 1000) return null
        val maxSamples = maxSeconds * sampleRate * channels
        val input = if (pcm.size > maxSamples) pcm.copyOf(maxSamples) else pcm
        if (input.isEmpty()) return null

        val mono = AudioProcessor(sampleRate, SAMPLE_RATE, channels).let {
            it.consume(input, input.size)
            it.flush()
            it.finish()
        }
        val image = buildImage(mono, applyChromaFilter = true)
        if (image.isEmpty()) return null

        val raw = fingerprintFromImage(image, CLASSIFIERS)
        if (raw.isEmpty()) return null
        val encoded = base64Encode(compress(raw, ALGORITHM))
        return Result(raw, encoded)
    }

    /**
     * Produces the chroma image (one 12 element row per STFT frame).
     * Exposed for the reference tests that compare against chromaprint's own
     * expected chromagram values.
     */
    fun buildImage(mono11025: ShortArray, applyChromaFilter: Boolean): Array<DoubleArray> {
        if (mono11025.size < FRAME_SIZE) return emptyArray()
        val fft = Fft(FRAME_SIZE)
        val chroma = Chroma(MIN_FREQ, MAX_FREQ, FRAME_SIZE, SAMPLE_RATE)
        val normalizer = ChromaNormalizer()
        val chromaFilter = if (applyChromaFilter) ChromaFilter(CHROMA_FILTER_COEFFICIENTS) else null

        val rows = ArrayList<DoubleArray>()
        val increment = FRAME_SIZE - FRAME_OVERLAP
        var start = 0
        while (start + FRAME_SIZE <= mono11025.size) {
            val spectrum = fft.powerSpectrum(mono11025, start)
            val features = chroma.consume(spectrum)
            normalizer.consume(features)
            val out = if (chromaFilter != null) chromaFilter.consume(features) else features.copyOf()
            if (out != null) rows.add(out)
            start += increment
        }
        return rows.toTypedArray()
    }

    private fun fingerprintFromImage(
        image: Array<DoubleArray>,
        classifiers: Array<Classifier>,
    ): IntArray {
        if (image.isEmpty()) return IntArray(0)
        var maxFilterWidth = 0
        for (c in classifiers) maxFilterWidth = max(maxFilterWidth, c.filter.width)
        if (maxFilterWidth <= 0) return IntArray(0)

        val integral = RollingIntegralImage(image.size + 1)
        val result = ArrayList<Int>()
        for (row in image) {
            integral.addRow(row)
            if (integral.numRows() >= maxFilterWidth) {
                result.add(calculateSubfingerprint(integral, integral.numRows() - maxFilterWidth, classifiers))
            }
        }
        return result.toIntArray()
    }

    private fun calculateSubfingerprint(
        image: RollingIntegralImage,
        offset: Int,
        classifiers: Array<Classifier>,
    ): Int {
        var bits = 0
        for (c in classifiers) {
            bits = (bits shl 2) or GRAY_CODE[c.classify(image, offset)]
        }
        return bits
    }

    private val GRAY_CODE = intArrayOf(0, 1, 3, 2)

    // ------------------------------------------------------------------
    // Compression / encoding (FingerprintCompressor + base64)
    // ------------------------------------------------------------------

    private const val kNormalBits = 3
    private const val kMaxNormalValue = (1 shl kNormalBits) - 1

    private fun compress(data: IntArray, algorithm: Int): ByteArray {
        val normalBits = ArrayList<Int>()
        val exceptionalBits = ArrayList<Int>()

        fun processSubfingerprint(xIn: Int) {
            var x = xIn
            var bit = 1
            var lastBit = 0
            while (x != 0) {
                if (x and 1 != 0) {
                    val value = bit - lastBit
                    if (value >= kMaxNormalValue) {
                        normalBits.add(kMaxNormalValue)
                        exceptionalBits.add(value - kMaxNormalValue)
                    } else {
                        normalBits.add(value)
                    }
                    lastBit = bit
                }
                x = x ushr 1
                bit++
            }
            normalBits.add(0)
        }

        if (data.isNotEmpty()) {
            processSubfingerprint(data[0])
            for (i in 1 until data.size) {
                processSubfingerprint(data[i] xor data[i - 1])
            }
        }

        val normalBytes = (normalBits.size * 3 + 7) / 8
        val exceptionalBytes = (exceptionalBits.size * 5 + 7) / 8
        val output = ByteArray(4 + normalBytes + exceptionalBytes)
        output[0] = (algorithm and 255).toByte()
        output[1] = ((data.size shr 16) and 255).toByte()
        output[2] = ((data.size shr 8) and 255).toByte()
        output[3] = (data.size and 255).toByte()

        var pos = 4
        pos = packInt3(normalBits, output, pos)
        packInt5(exceptionalBits, output, pos)
        return output
    }

    private fun packInt3(values: List<Int>, dest: ByteArray, start: Int): Int {
        var pos = start
        var i = 0
        val n = values.size
        while (i + 8 <= n) {
            val s0 = values[i] and 0x07
            val s1 = values[i + 1] and 0x07
            val s2 = values[i + 2] and 0x07
            val s3 = values[i + 3] and 0x07
            val s4 = values[i + 4] and 0x07
            val s5 = values[i + 5] and 0x07
            val s6 = values[i + 6] and 0x07
            val s7 = values[i + 7] and 0x07
            dest[pos++] = (s0 or (s1 shl 3) or ((s2 and 0x03) shl 6)).toByte()
            dest[pos++] = (((s2 and 0x04) shr 2) or (s3 shl 1) or (s4 shl 4) or ((s5 and 0x01) shl 7)).toByte()
            dest[pos++] = (((s5 and 0x06) shr 1) or (s6 shl 2) or (s7 shl 5)).toByte()
            i += 8
        }
        val rem = n - i
        if (rem == 7) {
            val s0 = values[i] and 0x07
            val s1 = values[i + 1] and 0x07
            val s2 = values[i + 2] and 0x07
            val s3 = values[i + 3] and 0x07
            val s4 = values[i + 4] and 0x07
            val s5 = values[i + 5] and 0x07
            val s6 = values[i + 6] and 0x07
            dest[pos++] = (s0 or (s1 shl 3) or ((s2 and 0x03) shl 6)).toByte()
            dest[pos++] = (((s2 and 0x04) shr 2) or (s3 shl 1) or (s4 shl 4) or ((s5 and 0x01) shl 7)).toByte()
            dest[pos++] = (((s5 and 0x06) shr 1) or (s6 shl 2)).toByte()
        } else if (rem == 6) {
            val s0 = values[i] and 0x07
            val s1 = values[i + 1] and 0x07
            val s2 = values[i + 2] and 0x07
            val s3 = values[i + 3] and 0x07
            val s4 = values[i + 4] and 0x07
            val s5 = values[i + 5] and 0x07
            dest[pos++] = (s0 or (s1 shl 3) or ((s2 and 0x03) shl 6)).toByte()
            dest[pos++] = (((s2 and 0x04) shr 2) or (s3 shl 1) or (s4 shl 4) or ((s5 and 0x01) shl 7)).toByte()
            dest[pos++] = (((s5 and 0x06) shr 1)).toByte()
        } else if (rem == 5) {
            val s0 = values[i] and 0x07
            val s1 = values[i + 1] and 0x07
            val s2 = values[i + 2] and 0x07
            val s3 = values[i + 3] and 0x07
            val s4 = values[i + 4] and 0x07
            dest[pos++] = (s0 or (s1 shl 3) or ((s2 and 0x03) shl 6)).toByte()
            dest[pos++] = (((s2 and 0x04) shr 2) or (s3 shl 1) or (s4 shl 4)).toByte()
        } else if (rem == 4) {
            val s0 = values[i] and 0x07
            val s1 = values[i + 1] and 0x07
            val s2 = values[i + 2] and 0x07
            val s3 = values[i + 3] and 0x07
            dest[pos++] = (s0 or (s1 shl 3) or ((s2 and 0x03) shl 6)).toByte()
            dest[pos++] = (((s2 and 0x04) shr 2) or (s3 shl 1)).toByte()
        } else if (rem == 3) {
            val s0 = values[i] and 0x07
            val s1 = values[i + 1] and 0x07
            val s2 = values[i + 2] and 0x07
            dest[pos++] = (s0 or (s1 shl 3) or ((s2 and 0x03) shl 6)).toByte()
            dest[pos++] = (((s2 and 0x04) shr 2)).toByte()
        } else if (rem == 2) {
            val s0 = values[i] and 0x07
            val s1 = values[i + 1] and 0x07
            dest[pos++] = (s0 or (s1 shl 3)).toByte()
        } else if (rem == 1) {
            dest[pos++] = (values[i] and 0x07).toByte()
        }
        return pos
    }

    private fun packInt5(values: List<Int>, dest: ByteArray, start: Int): Int {
        var pos = start
        var i = 0
        val n = values.size
        while (i + 8 <= n) {
            val s0 = values[i] and 0x1f
            val s1 = values[i + 1] and 0x1f
            val s2 = values[i + 2] and 0x1f
            val s3 = values[i + 3] and 0x1f
            val s4 = values[i + 4] and 0x1f
            val s5 = values[i + 5] and 0x1f
            val s6 = values[i + 6] and 0x1f
            val s7 = values[i + 7] and 0x1f
            dest[pos++] = (s0 or ((s1 and 0x07) shl 5)).toByte()
            dest[pos++] = (((s1 and 0x18) shr 3) or (s2 shl 2) or ((s3 and 0x01) shl 7)).toByte()
            dest[pos++] = (((s3 and 0x1e) shr 1) or ((s4 and 0x0f) shl 4)).toByte()
            dest[pos++] = (((s4 and 0x10) shr 4) or (s5 shl 1) or ((s6 and 0x03) shl 6)).toByte()
            dest[pos++] = (((s6 and 0x1c) shr 2) or (s7 shl 3)).toByte()
            i += 8
        }
        val rem = n - i
        if (rem == 7) {
            val s0 = values[i] and 0x1f
            val s1 = values[i + 1] and 0x1f
            val s2 = values[i + 2] and 0x1f
            val s3 = values[i + 3] and 0x1f
            val s4 = values[i + 4] and 0x1f
            val s5 = values[i + 5] and 0x1f
            val s6 = values[i + 6] and 0x1f
            dest[pos++] = (s0 or ((s1 and 0x07) shl 5)).toByte()
            dest[pos++] = (((s1 and 0x18) shr 3) or (s2 shl 2) or ((s3 and 0x01) shl 7)).toByte()
            dest[pos++] = (((s3 and 0x1e) shr 1) or ((s4 and 0x0f) shl 4)).toByte()
            dest[pos++] = (((s4 and 0x10) shr 4) or (s5 shl 1) or ((s6 and 0x03) shl 6)).toByte()
            dest[pos++] = (((s6 and 0x1c) shr 2)).toByte()
        } else if (rem == 6) {
            val s0 = values[i] and 0x1f
            val s1 = values[i + 1] and 0x1f
            val s2 = values[i + 2] and 0x1f
            val s3 = values[i + 3] and 0x1f
            val s4 = values[i + 4] and 0x1f
            val s5 = values[i + 5] and 0x1f
            dest[pos++] = (s0 or ((s1 and 0x07) shl 5)).toByte()
            dest[pos++] = (((s1 and 0x18) shr 3) or (s2 shl 2) or ((s3 and 0x01) shl 7)).toByte()
            dest[pos++] = (((s3 and 0x1e) shr 1) or ((s4 and 0x0f) shl 4)).toByte()
            dest[pos++] = (((s4 and 0x10) shr 4) or (s5 shl 1)).toByte()
        } else if (rem == 5) {
            val s0 = values[i] and 0x1f
            val s1 = values[i + 1] and 0x1f
            val s2 = values[i + 2] and 0x1f
            val s3 = values[i + 3] and 0x1f
            val s4 = values[i + 4] and 0x1f
            dest[pos++] = (s0 or ((s1 and 0x07) shl 5)).toByte()
            dest[pos++] = (((s1 and 0x18) shr 3) or (s2 shl 2) or ((s3 and 0x01) shl 7)).toByte()
            dest[pos++] = (((s3 and 0x1e) shr 1) or ((s4 and 0x0f) shl 4)).toByte()
            dest[pos++] = (((s4 and 0x10) shr 4)).toByte()
        } else if (rem == 4) {
            val s0 = values[i] and 0x1f
            val s1 = values[i + 1] and 0x1f
            val s2 = values[i + 2] and 0x1f
            val s3 = values[i + 3] and 0x1f
            dest[pos++] = (s0 or ((s1 and 0x07) shl 5)).toByte()
            dest[pos++] = (((s1 and 0x18) shr 3) or (s2 shl 2) or ((s3 and 0x01) shl 7)).toByte()
            dest[pos++] = (((s3 and 0x1e) shr 1)).toByte()
        } else if (rem == 3) {
            val s0 = values[i] and 0x1f
            val s1 = values[i + 1] and 0x1f
            val s2 = values[i + 2] and 0x1f
            dest[pos++] = (s0 or ((s1 and 0x07) shl 5)).toByte()
            dest[pos++] = (((s1 and 0x18) shr 3) or (s2 shl 2)).toByte()
        } else if (rem == 2) {
            val s0 = values[i] and 0x1f
            val s1 = values[i + 1] and 0x1f
            dest[pos++] = (s0 or ((s1 and 0x07) shl 5)).toByte()
            dest[pos++] = (((s1 and 0x18) shr 3)).toByte()
        } else if (rem == 1) {
            dest[pos++] = (values[i] and 0x1f).toByte()
        }
        return pos
    }

    /** Exposed for the reference tests (chromaprint tests/test_api.cpp vector). */
    internal fun compressForTest(raw: IntArray, algorithm: Int): ByteArray = compress(raw, algorithm)

    private const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun base64Encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        val n = data.size
        while (i + 3 <= n) {
            val s0 = data[i].toInt() and 0xff
            val s1 = data[i + 1].toInt() and 0xff
            val s2 = data[i + 2].toInt() and 0xff
            sb.append(BASE64_CHARS[(s0 shr 2) and 63])
            sb.append(BASE64_CHARS[((s0 shl 4) or (s1 shr 4)) and 63])
            sb.append(BASE64_CHARS[((s1 shl 2) or (s2 shr 6)) and 63])
            sb.append(BASE64_CHARS[s2 and 63])
            i += 3
        }
        val rem = n - i
        if (rem == 2) {
            val s0 = data[i].toInt() and 0xff
            val s1 = data[i + 1].toInt() and 0xff
            sb.append(BASE64_CHARS[(s0 shr 2) and 63])
            sb.append(BASE64_CHARS[((s0 shl 4) or (s1 shr 4)) and 63])
            sb.append(BASE64_CHARS[(s1 shl 2) and 63])
        } else if (rem == 1) {
            val s0 = data[i].toInt() and 0xff
            sb.append(BASE64_CHARS[(s0 shr 2) and 63])
            sb.append(BASE64_CHARS[(s0 shl 4) and 63])
        }
        return sb.toString()
    }
}

// ----------------------------------------------------------------------
// Audio -> mono / 11025 Hz (chromaprint's AudioProcessor)
// ----------------------------------------------------------------------

internal class AudioProcessor(
    private val inputSampleRate: Int,
    private val targetSampleRate: Int,
    private val numChannels: Int,
) {
    private val maxBufferSize = 1024 * 32
    private val buffer = ShortArray(maxBufferSize)
    private var bufferOffset = 0
    private val resampleBuffer = ShortArray(maxBufferSize)
    private val output = ShortArrayList()
    private val resampler: Resampler? =
        if (inputSampleRate != targetSampleRate) {
            Resampler(targetSampleRate, inputSampleRate, 16, 8, 0.8)
        } else {
            null
        }

    fun consume(input: ShortArray, lengthIn: Int) {
        var length = lengthIn / numChannels
        var offset = 0
        while (length > 0) {
            val consumed = load(input, offset, length)
            offset += consumed * numChannels
            length -= consumed
            if (bufferOffset == buffer.size) {
                resample()
            }
        }
    }

    fun flush() {
        if (bufferOffset > 0) {
            resample()
        }
    }

    fun finish(): ShortArray = output.toArray()

    private fun load(input: ShortArray, offset: Int, lengthIn: Int): Int {
        val length = min(lengthIn, buffer.size - bufferOffset)
        when (numChannels) {
            1 -> System.arraycopy(input, offset, buffer, bufferOffset, length)
            2 -> {
                var src = offset
                var dst = bufferOffset
                for (i in 0 until length) {
                    buffer[dst++] = ((input[src].toInt() + input[src + 1].toInt()) / 2).toShort()
                    src += 2
                }
            }
            else -> {
                var src = offset
                var dst = bufferOffset
                for (i in 0 until length) {
                    var sum = 0
                    for (c in 0 until numChannels) sum += input[src + c].toInt()
                    buffer[dst++] = (sum / numChannels).toShort()
                    src += numChannels
                }
            }
        }
        bufferOffset += length
        return length
    }

    private fun resample() {
        val r = resampler
        if (r == null) {
            output.add(buffer, 0, bufferOffset)
            bufferOffset = 0
            return
        }
        val consumed = IntArray(1)
        val produced = r.process(
            resampleBuffer,
            0,
            maxBufferSize,
            buffer,
            0,
            bufferOffset,
            consumed,
        )
        output.add(resampleBuffer, 0, produced)
        var remaining = bufferOffset - consumed[0]
        if (remaining < 0) remaining = 0
        if (remaining > 0) {
            System.arraycopy(buffer, consumed[0], buffer, 0, remaining)
        }
        bufferOffset = remaining
    }
}

// ----------------------------------------------------------------------
// Small growable ShortArray
// ----------------------------------------------------------------------

internal class ShortArrayList(initialCapacity: Int = 1 shl 16) {
    private var data = ShortArray(initialCapacity)
    var size = 0
        private set

    fun add(src: ShortArray, offset: Int, length: Int) {
        ensure(size + length)
        System.arraycopy(src, offset, data, size, length)
        size += length
    }

    private fun ensure(capacity: Int) {
        if (capacity <= data.size) return
        var newSize = data.size
        while (newSize < capacity) newSize *= 2
        data = data.copyOf(newSize)
    }

    fun toArray(): ShortArray = data.copyOf(size)
}

// ----------------------------------------------------------------------
// FFT
// ----------------------------------------------------------------------

internal class Fft(private val frameSize: Int) {
    private val window = DoubleArray(frameSize).also { w ->
        val scale = 1.0 / Short.MAX_VALUE
        for (i in 0 until frameSize) {
            w[i] = scale * (0.54 - 0.46 * cos(i * 2.0 * Math.PI / (frameSize - 1)))
        }
    }
    private val re = DoubleArray(frameSize)
    private val im = DoubleArray(frameSize)
    private val cosTable = DoubleArray(frameSize / 2)
    private val sinTable = DoubleArray(frameSize / 2)
    private val spectrum = DoubleArray(frameSize / 2 + 1)

    init {
        for (i in 0 until frameSize / 2) {
            cosTable[i] = cos(2.0 * Math.PI * i / frameSize)
            sinTable[i] = sin(2.0 * Math.PI * i / frameSize)
        }
    }

    /** Power spectrum (|X|^2) of the windowed frame starting at [offset]. */
    fun powerSpectrum(samples: ShortArray, offset: Int): DoubleArray {
        for (i in 0 until frameSize) {
            re[i] = samples[offset + i] * window[i]
            im[i] = 0.0
        }
        fft()
        for (i in 0..frameSize / 2) {
            spectrum[i] = re[i] * re[i] + im[i] * im[i]
        }
        return spectrum
    }

    private fun fft() {
        // Bit reversal permutation
        var j = 0
        for (i in 1 until frameSize) {
            var bit = frameSize shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= frameSize) {
            val half = len / 2
            val step = frameSize / len
            var i = 0
            while (i < frameSize) {
                for (k in 0 until half) {
                    val cosV = cosTable[k * step]
                    val sinV = sinTable[k * step]
                    val i0 = i + k
                    val i1 = i0 + half
                    val tre = re[i1] * cosV + im[i1] * sinV
                    val tim = -re[i1] * sinV + im[i1] * cosV
                    re[i1] = re[i0] - tre
                    im[i1] = im[i0] - tim
                    re[i0] += tre
                    im[i0] += tim
                }
                i += len
            }
            len *= 2
        }
    }
}

// ----------------------------------------------------------------------
// Chroma feature extraction
// ----------------------------------------------------------------------

internal class Chroma(
    minFreq: Int,
    maxFreq: Int,
    frameSize: Int,
    sampleRate: Int,
) {
    private val notes = IntArray(frameSize)
    private val minIndex: Int
    private val maxIndex: Int
    private val features = DoubleArray(NUM_BANDS)

    init {
        minIndex = max(1, freqToIndex(minFreq.toDouble(), frameSize, sampleRate))
        maxIndex = min(frameSize / 2, freqToIndex(maxFreq.toDouble(), frameSize, sampleRate))
        for (i in minIndex until maxIndex) {
            val freq = indexToFreq(i, frameSize, sampleRate)
            val octave = freqToOctave(freq)
            val note = NUM_BANDS * (octave - floor(octave))
            notes[i] = note.toInt()
        }
    }

    fun consume(spectrum: DoubleArray): DoubleArray {
        for (i in 0 until NUM_BANDS) features[i] = 0.0
        for (i in minIndex until maxIndex) {
            features[notes[i]] += spectrum[i]
        }
        return features
    }

    private fun freqToOctave(freq: Double, base: Double = 440.0 / 16.0): Double =
        ln(freq / base) / ln(2.0)

    private fun freqToIndex(freq: Double, frameSize: Int, sampleRate: Int): Int =
        (frameSize * freq / sampleRate).roundToInt()

    private fun indexToFreq(i: Int, frameSize: Int, sampleRate: Int): Double =
        i.toDouble() * sampleRate / frameSize

    companion object {
        private const val NUM_BANDS = 12
    }
}

internal class ChromaNormalizer {
    fun consume(features: DoubleArray) {
        var squares = 0.0
        for (v in features) squares += v * v
        val norm = if (squares > 0.0) sqrt(squares) else 0.0
        if (norm < 0.01) {
            for (i in features.indices) features[i] = 0.0
        } else {
            for (i in features.indices) features[i] /= norm
        }
    }
}

internal class ChromaFilter(private val coefficients: DoubleArray) {
    private val buffer = arrayOfNulls<DoubleArray>(8)
    private var bufferOffset = 0
    private var bufferSize = 1
    private val result = DoubleArray(12)

    fun consume(features: DoubleArray): DoubleArray? {
        buffer[bufferOffset] = features.copyOf()
        bufferOffset = (bufferOffset + 1) % 8
        if (bufferSize >= coefficients.size) {
            val offset = (bufferOffset + 8 - coefficients.size) % 8
            for (i in 0 until 12) {
                var sum = 0.0
                for (j in coefficients.indices) {
                    sum += (buffer[(offset + j) % 8] ?: continue)[i] * coefficients[j]
                }
                result[i] = sum
            }
            return result.copyOf()
        } else {
            bufferSize++
            return null
        }
    }
}

// ----------------------------------------------------------------------
// Integral image + classifiers
// ----------------------------------------------------------------------

internal class RollingIntegralImage(maxRows: Int) {
    private val rows = ArrayList<DoubleArray>()
    private var columns = 0

    fun numRows(): Int = rows.size

    fun addRow(values: DoubleArray) {
        if (columns == 0) columns = values.size
        val row = DoubleArray(columns)
        var running = 0.0
        val previous = if (rows.isEmpty()) null else rows[rows.size - 1]
        for (i in 0 until columns) {
            running += values[i]
            row[i] = running + (previous?.get(i) ?: 0.0)
        }
        rows.add(row)
    }

    fun area(r1: Int, c1: Int, r2: Int, c2: Int): Double {
        if (r1 == r2 || c1 == c2) return 0.0
        val row1 = if (r1 == 0) null else rows[r1 - 1]
        val row2 = rows[r2 - 1]
        return if (r1 == 0) {
            if (c1 == 0) row2[c2 - 1] else row2[c2 - 1] - row2[c1 - 1]
        } else {
            val r1v = row1!!
            if (c1 == 0) {
                row2[c2 - 1] - r1v[c2 - 1]
            } else {
                row2[c2 - 1] - r1v[c2 - 1] - row2[c1 - 1] + r1v[c1 - 1]
            }
        }
    }
}

internal class Filter(
    val type: Int,
    val y: Int,
    val height: Int,
    val width: Int,
) {
    fun apply(image: RollingIntegralImage, x: Int): Double {
        return when (type) {
            0 -> {
                val a = image.area(x, y, x + width, y + height)
                subtractLog(a, 0.0)
            }
            1 -> {
                val h2 = height / 2
                val a = image.area(x, y + h2, x + width, y + height)
                val b = image.area(x, y, x + width, y + h2)
                subtractLog(a, b)
            }
            2 -> {
                val w2 = width / 2
                val a = image.area(x + w2, y, x + width, y + height)
                val b = image.area(x, y, x + w2, y + height)
                subtractLog(a, b)
            }
            3 -> {
                val w2 = width / 2
                val h2 = height / 2
                val a = image.area(x, y + h2, x + w2, y + height) +
                    image.area(x + w2, y, x + width, y + h2)
                val b = image.area(x, y, x + w2, y + h2) +
                    image.area(x + w2, y + h2, x + width, y + height)
                subtractLog(a, b)
            }
            4 -> {
                val h3 = height / 3
                val a = image.area(x, y + h3, x + width, y + 2 * h3)
                val b = image.area(x, y, x + width, y + h3) +
                    image.area(x, y + 2 * h3, x + width, y + height)
                subtractLog(a, b)
            }
            else -> {
                val w3 = width / 3
                val a = image.area(x + w3, y, x + 2 * w3, y + height)
                val b = image.area(x, y, x + w3, y + height) +
                    image.area(x + 2 * w3, y, x + width, y + height)
                subtractLog(a, b)
            }
        }
    }

    private fun subtractLog(a: Double, b: Double): Double =
        ln((1.0 + a) / (1.0 + b))
}

internal class Quantizer(private val t0: Double, private val t1: Double, private val t2: Double) {
    fun quantize(value: Double): Int = when {
        value < t0 -> 0
        value < t1 -> 1
        value < t2 -> 2
        else -> 3
    }
}

internal class Classifier(val filter: Filter, private val quantizer: Quantizer) {
    fun classify(image: RollingIntegralImage, offset: Int): Int =
        quantizer.quantize(filter.apply(image, offset))
}
