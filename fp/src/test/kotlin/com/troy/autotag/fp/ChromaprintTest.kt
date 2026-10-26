package com.troy.autotag.fp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reference tests against the test data and expected values shipped with
 * acoustid/chromaprint 1.5.1 (see fp/third_party_chromaprint_LICENSE.md).
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
            out[i] = (((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8))).toShort()
        }
        return out
    }

    private fun mono11025(pcm: ShortArray, sampleRate: Int, channels: Int): ShortArray {
        val processor = AudioProcessor(sampleRate, Chromaprint.SAMPLE_RATE, channels)
        processor.consume(pcm, pcm.size)
        processor.flush()
        return processor.finish()
    }

    @Test
    fun chromagramMatchesReferenceImplementation() {
        val stereo = toShortsLE(resource("test_stereo_44100.raw"))
        val mono = mono11025(stereo, 44100, 2)
        val image = Chromaprint.buildImage(mono, applyChromaFilter = false)

        assertEquals(14, image.size, "number of chroma rows")

        for (row in expected.indices) {
            for (col in 0 until 12) {
                val actual = image[row][col]
                val want = expected[row][col]
                assertTrue(
                    kotlin.math.abs(actual - want) <= 1e-5,
                    "chroma mismatch at [$row][$col]: expected $want, got $actual",
                )
            }
        }
    }

    @Test
    fun compressionMatchesReferenceVector() {
        // chromaprint tests/test_api.cpp: TestEncodeFingerprint / TestEncodeFingerprintBase64
        val compressed = Chromaprint.compressForTest(intArrayOf(1, 0), 55)
        val expectedBytes = byteArrayOf(55, 0, 0, 2, 65, 0)
        assertEquals(expectedBytes.toList(), compressed.toList())
        assertEquals("NwAAAkEA", Chromaprint.base64Encode(compressed))
    }

    @Test
    fun fingerprintIsStableForMono11025() {
        val pcm = toShortsLE(resource("test_mono_11025.raw"))
        val result = assertNotNull(Chromaprint.fingerprint(pcm, 11025, 1))
        assertTrue(result.raw.size > 3, "fingerprint should have a few subfingerprints")
        println("mono11025 fingerprint = ${result.encoded}")

        // Optional verification against the real fpcalc, generated in CI:
        //   fpcalc -raw -algorithm 2 -length 120 test_mono_11025.raw > expected.txt
        val expectedFile = System.getenv("FP_EXPECTED_RAW_FILE")
        if (expectedFile != null && File(expectedFile).exists()) {
            val line = File(expectedFile).readText()
                .lineSequence()
                .firstOrNull { it.startsWith("FINGERPRINT=") }
                ?.removePrefix("FINGERPRINT=")
                ?.trim()
            if (line != null && line.isNotEmpty()) {
                val expectedRaw = line.split(",").map { it.trim().toLong().toInt() }.toIntArray()
                val matching = expectedRaw.indices.count {
                    it < result.raw.size && result.raw[it] == expectedRaw[it]
                }
                println("subfingerprints matching fpcalc: $matching / ${expectedRaw.size}")
                assertTrue(
                    matching * 100 >= expectedRaw.size * 98,
                    "fingerprint deviates too much from fpcalc: $matching/${expectedRaw.size}",
                )
            }
        }
    }

    @Test
    fun fingerprintOfMono8000IsProduced() {
        val pcm = toShortsLE(resource("test_mono_8000.raw"))
        val result = assertNotNull(Chromaprint.fingerprint(pcm, 8000, 1))
        assertTrue(result.raw.size > 3)
        println("mono8000 fingerprint = ${result.encoded}")
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
