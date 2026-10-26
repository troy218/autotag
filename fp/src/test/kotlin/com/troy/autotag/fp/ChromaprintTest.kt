package com.troy.autotag.fp

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reference tests against the test data and expected values shipped with
 * acoustid/chromaprint 1.5.1 (see fp/third_party_chromaprint_LICENSE.md).
 *
 * The chromagram comparison is the important one: it validates the whole
 * signal path (resampler -> window -> FFT -> chroma -> normalisation) against
 * the C++ reference implementation with a 1e-5 tolerance.
 */
class ChromaprintTest {

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("missing test resource: $name")
        return stream.use { it.readBytes() }
    }

    private fun toShortsLE(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) {
            out[i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    private fun mono11025(pcm: ShortArray, sampleRate: Int, channels: Int): ShortArray {
        val processor = AudioProcessor(sampleRate, Chromaprint.SAMPLE_RATE, channels)
        processor.consume(pcm, pcm.size)
        processor.flush()
        return processor.finish()
    }

    private fun repeat(pcm: ShortArray, times: Int): ShortArray {
        val out = ShortArray(pcm.size * times)
        for (i in 0 until times) System.arraycopy(pcm, 0, out, i * pcm.size, pcm.size)
        return out
    }

    @Test
    fun chromagramMatchesReferenceImplementation() {
        val stereo = toShortsLE(resource("test_stereo_44100.raw"))
        val mono = mono11025(stereo, 44100, 2)
        assertEquals(22040, mono.size, "resampled sample count")
        val image = Chromaprint.buildImage(mono, applyChromaFilter = false)
        assertEquals(14, image.size, "number of chroma rows")

        for (row in expected.indices) {
            for (col in 0 until 12) {
                assertTrue(
                    abs(image[row][col] - expected[row][col]) <= 1e-5,
                    "chroma mismatch at [$row][$col]: expected ${expected[row][col]}, got ${image[row][col]}",
                )
            }
        }
    }

    @Test
    fun compressionMatchesReferenceVectors() {
        // chromaprint tests/test_fingerprint_compressor.cpp
        val cases = listOf(
            intArrayOf(1) to byteArrayOf(0, 0, 0, 1, 1),
            intArrayOf(7) to byteArrayOf(0, 0, 0, 1, 73, 0),
            intArrayOf(1 shl 6) to byteArrayOf(0, 0, 0, 1, 7, 0),
            intArrayOf(1 shl 8) to byteArrayOf(0, 0, 0, 1, 7, 2),
            intArrayOf(1, 0) to byteArrayOf(0, 0, 0, 2, 65, 0),
            intArrayOf(1, 1) to byteArrayOf(0, 0, 0, 2, 1, 0),
        )
        for ((raw, want) in cases) {
            val actual = Chromaprint.compressForTest(raw, 0)
            assertEquals(want.toList(), actual.toList(), "compress(${raw.joinToString(",")})")
        }
        assertEquals(
            "NwAAAkEA",
            Chromaprint.base64Encode(Chromaprint.compressForTest(intArrayOf(1, 0), 55)),
        )
    }

    @Test
    fun integralImageAndFiltersMatchReference() {
        // chromaprint tests/test_filter.cpp
        val integral = RollingIntegralImage(4)
        integral.addRow(doubleArrayOf(0.0, 1.0))
        integral.addRow(doubleArrayOf(2.0, 3.0))
        val filter = Filter(0, 0, 1, 1)
        assertTrue(abs(filter.apply(integral, 0) - 0.0) < 1e-6)
        assertTrue(abs(filter.apply(integral, 1) - 1.0986123) < 1e-6)
    }

    @Test
    fun chromaFilterMatchesReference() {
        // chromaprint tests/test_chroma_filter.cpp
        fun row(a: Double, b: Double) = doubleArrayOf(a, b, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        val blur2 = ChromaFilter(doubleArrayOf(0.5, 0.5))
        val rows = ArrayList<DoubleArray>()
        blur2.consume(row(0.0, 5.0))?.let { rows.add(it) }
        blur2.consume(row(1.0, 6.0))?.let { rows.add(it) }
        blur2.consume(row(2.0, 7.0))?.let { rows.add(it) }
        assertEquals(2, rows.size)
        assertTrue(abs(rows[0][0] - 0.5) < 1e-9)
        assertTrue(abs(rows[1][0] - 1.5) < 1e-9)
        assertTrue(abs(rows[0][1] - 5.5) < 1e-9)
        assertTrue(abs(rows[1][1] - 6.5) < 1e-9)

        val blur3 = ChromaFilter(doubleArrayOf(0.5, 0.7, 0.5))
        val rows3 = ArrayList<DoubleArray>()
        for (i in 0 until 4) blur3.consume(row(i.toDouble(), 5.0 + i))?.let { rows3.add(it) }
        assertEquals(2, rows3.size)
        assertTrue(abs(rows3[0][0] - 1.7) < 1e-9)
        assertTrue(abs(rows3[1][0] - 3.4) < 1e-9)
        assertTrue(abs(rows3[0][1] - 10.2) < 1e-9)
        assertTrue(abs(rows3[1][1] - 11.9) < 1e-9)
    }

    @Test
    fun shortClipHasNoSubFingerprint() {
        // a sub-fingerprint needs 16 chroma frames, i.e. roughly 2.7 seconds
        val pcm = toShortsLE(resource("test_mono_11025.raw"))
        assertNull(Chromaprint.fingerprint(pcm, 11025, 1))
    }

    @Test
    fun longClipProducesExpectedNumberOfSubFingerprints() {
        val pcm = repeat(toShortsLE(resource("test_mono_11025.raw")), 6)
        val result = assertNotNull(Chromaprint.fingerprint(pcm, 11025, 1))
        val frames = (pcm.size - 4096) / 1365 + 1
        val expectedCount = frames - 4 - 16 + 1
        assertEquals(expectedCount, result.raw.size)
        assertTrue(result.encoded.isNotBlank())

        // Same audio sampled at 44.1kHz must produce an almost identical fingerprint.
        val pcm44 = repeat(toShortsLE(resource("test_mono_44100.raw")), 6)
        val result44 = assertNotNull(Chromaprint.fingerprint(pcm44, 44100, 1))
        val count = minOf(result.raw.size, result44.raw.size)
        assertTrue(count > 40)
        var bitErrors = 0L
        for (i in 0 until count) bitErrors += Integer.bitCount(result.raw[i] xor result44.raw[i])
        val averageBitErrors = bitErrors.toDouble() / count
        println("average bit errors per sub-fingerprint: $averageBitErrors")
        assertTrue(averageBitErrors < 3.0, "fingerprint too different across sample rates: $averageBitErrors")
    }

    @Test
    fun stereoInputProducesFingerprint() {
        val pcm = repeat(toShortsLE(resource("test_stereo_44100.raw")), 6)
        val result = assertNotNull(Chromaprint.fingerprint(pcm, 44100, 2))
        assertTrue(result.raw.size > 40)
    }

    /**
     * Optional cross-check against the real `fpcalc` (installed in CI):
     *   fpcalc -raw -algorithm 2 -length 120 long.raw > expected.txt   (long.raw = 6x test_mono_11025.raw)
     * and the path exported as FP_EXPECTED_RAW_FILE.
     */
    @Test
    fun fingerprintMatchesFpcalcWhenAvailable() {
        val expectedFile = System.getenv("FP_EXPECTED_RAW_FILE") ?: return
        if (!File(expectedFile).exists()) return
        val line = File(expectedFile).readText()
            .lineSequence()
            .firstOrNull { it.startsWith("FINGERPRINT=") }
            ?.removePrefix("FINGERPRINT=")
            ?.trim()
        if (line.isNullOrEmpty()) return

        val pcm = repeat(toShortsLE(resource("test_mono_11025.raw")), 6)
        val result = assertNotNull(Chromaprint.fingerprint(pcm, 11025, 1))
        val expectedRaw = line.split(",").map { it.trim().toLong().toInt() }.toIntArray()
        assertEquals(expectedRaw.size, result.raw.size, "sub-fingerprint count vs fpcalc")
        val matching = expectedRaw.indices.count { result.raw[it] == expectedRaw[it] }
        val ratio = matching.toDouble() / expectedRaw.size
        println("identical sub-fingerprints vs fpcalc: $matching / ${expectedRaw.size}")
        assertTrue(ratio >= 0.98, "fingerprint deviates from fpcalc: $matching/${expectedRaw.size}")
    }

    private companion object {
        /** Expected chromagram of data/test_stereo_44100.raw (chromaprint tests/test_chromaprint.cpp). */
        val expected = arrayOf(
            doubleArrayOf(0.155444, 0.268618, 0.474445, 0.159887, 0.1761, 0.423511, 0.178933, 0.34433, 0.360958, 0.30421, 0.200217, 0.17072),
            doubleArrayOf(0.159809, 0.238675, 0.286526, 0.166119, 0.225144, 0.449236, 0.162444, 0.371875, 0.259626, 0.483961, 0.24491, 0.17034),
            doubleArrayOf(0.156518, 0.271503, 0.256073, 0.152689, 0.174664, 0.52585, 0.141517, 0.253695, 0.293199, 0.332114, 0.442906, 0.170459),
            doubleArrayOf(0.154183, 0.38592, 0.497451, 0.203884, 0.362608, 0.355691, 0.125349, 0.146766, 0.315143, 0.318133, 0.172547, 0.112769),
            doubleArrayOf(0.201289, 0.42033, 0.509467, 0.259247, 0.322772, 0.325837, 0.140072, 0.177756, 0.320356, 0.228176, 0.148994, 0.132588),
            doubleArrayOf(0.187921, 0.302804, 0.46976, 0.302809, 0.183035, 0.228691, 0.206216, 0.35174, 0.308208, 0.233234, 0.316017, 0.243563),
            doubleArrayOf(0.213539, 0.240346, 0.308664, 0.250704, 0.204879, 0.365022, 0.241966, 0.312579, 0.361886, 0.277293, 0.338944, 0.290351),
            doubleArrayOf(0.227784, 0.252841, 0.295752, 0.265796, 0.227973, 0.451155, 0.219418, 0.272508, 0.376082, 0.312717, 0.285395, 0.165745),
            doubleArrayOf(0.168662, 0.180795, 0.264397, 0.225101, 0.562332, 0.33243, 0.236684, 0.199847, 0.409727, 0.247569, 0.21153, 0.147286),
            doubleArrayOf(0.0491864, 0.0503369, 0.130942, 0.0505802, 0.0694409, 0.0303877, 0.0389852, 0.674067, 0.712933, 0.05762, 0.0245158, 0.0389336),
            doubleArrayOf(0.0814379, 0.0312366, 0.240546, 0.134609, 0.063374, 0.0466124, 0.0752175, 0.657041, 0.680085, 0.0720311, 0.0249404, 0.0673359),
            doubleArrayOf(0.139331, 0.0173442, 0.49035, 0.287237, 0.0453947, 0.0873279, 0.15423, 0.447475, 0.621502, 0.127166, 0.0355933, 0.141163),
            doubleArrayOf(0.115417, 0.0132515, 0.356601, 0.245902, 0.0283943, 0.0588233, 0.117077, 0.499376, 0.715366, 0.100398, 0.0281382, 0.0943482),
            doubleArrayOf(0.047297, 0.0065354, 0.181074, 0.121455, 0.0135504, 0.030693, 0.0613105, 0.631705, 0.73548, 0.0550565, 0.0128093, 0.0460393),
        )
    }
}
