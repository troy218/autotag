package com.troy.autotag.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextToolsTest {

    @Test
    fun artistKeysCollapseSpellingVariants() {
        val variants = listOf(
            "BTS",
            "bts",
            "BTS ",
            "BTS feat. Halsey",
            "BTS (방탄소년단)",
        )
        val keys = variants.map { TextTools.artistKey(it) }
        assertEquals(1, keys.distinct().size, "expected one artist key, got $keys")
    }

    @Test
    fun albumKeysIgnoreEditionNoise() {
        val variants = listOf(
            "Love Yourself: Answer",
            "love yourself answer",
            "Love Yourself - Answer",
            "Love Yourself: Answer (Deluxe Edition)",
            "Love Yourself: Answer [Repackage]",
        )
        val keys = variants.map { TextTools.albumKey(it) }
        assertEquals(1, keys.distinct().size, "expected one album key, got $keys")

        // A CJK subtitle cannot be folded to the same key, but stays similar
        // enough for the near-duplicate clustering in the enrich engine.
        assertTrue(
            TextTools.similarity("Love Yourself 結 'Answer'", "Love Yourself: Answer") >= 0.9,
        )
    }

    @Test
    fun artistOnlyStripsFeaturing() {
        assertEquals("IU", TextTools.artistOnly("IU ft. SUGA"))
        assertEquals("BTS", TextTools.artistOnly("BTS (feat. Halsey)"))
        assertEquals("아이유", TextTools.artistOnly("아이유"))
    }

    @Test
    fun guessesFromFileName() {
        val g1 = TextTools.guessFromFileName("01 - BTS - Dynamite.flac")
        assertEquals("BTS", g1.artist)
        assertEquals("Dynamite", g1.title)

        val g2 = TextTools.guessFromFileName("아이유 - 좋은 날.mp3")
        assertEquals("아이유", g2.artist)
        assertEquals("좋은 날", g2.title)

        val g3 = TextTools.guessFromFileName("Dynamite.mp3")
        assertEquals("Dynamite", g3.title)

        val g4 = TextTools.guessFromFileName("03. Dynamite (Official MV).flac")
        assertEquals("Dynamite", g4.title)
    }

    @Test
    fun similarityIsForgiving() {
        assertTrue(TextTools.similarity("Dynamite", "Dynamite (Official MV)") > 0.9)
        assertTrue(TextTools.artistSimilarity("BTS", "BTS feat. Halsey") > 0.9)
        assertTrue(TextTools.similarity("Dynamite", "Butter") < 0.6)
    }
}
