package dev.kinesis.core

import dev.kinesis.core.tts.KokoroVocab
import dev.kinesis.core.tts.PortuguesePhonemizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortuguesePhonemizerTest {
    private fun tsv(name: String) = javaClass.getResourceAsStream("/tts/$name")!!.bufferedReader().readLines().filter { it.isNotBlank() }.map { it.split('\t') }

    /** The Kotlin rules must give exactly what the Python prototype they were ported from gave (`gold.tsv`). */
    @Test
    fun matchesThePrototype() {
        val rows = tsv("gold.tsv")
        val bad = rows.filter { PortuguesePhonemizer.phonemizeWord(it[0]) != it[1] }
        assertTrue("differs on ${bad.size}/${rows.size}: " + bad.take(8).joinToString { "${it[0]}=${PortuguesePhonemizer.phonemizeWord(it[0])} (${it[1]})" }, bad.isEmpty())
    }

    private fun similarity(a: String, b: String): Double {
        fun norm(s: String) = s.replace("ˌ", "").replace("ˈ", "").replace("ŋ", "").replace("ə", "").replace("tʃ", "ʧ").replace("dʒ", "ʤ")
        val x = norm(a); val y = norm(b)
        val d = Array(x.length + 1) { IntArray(y.length + 1) }
        for (i in 0..x.length) d[i][0] = i
        for (j in 0..y.length) d[0][j] = j
        for (i in 1..x.length) for (j in 1..y.length) d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
        return 1.0 - d[x.length][y.length].toDouble() / maxOf(1, maxOf(x.length, y.length))
    }

    /** Closeness to the espeak-ng pt-br output (the model's reference) over everyday sentences. */
    @Test
    fun staysCloseToEspeak() {
        val rows = tsv("espeak.tsv")
        val avg = rows.map { similarity(PortuguesePhonemizer.phonemizeWord(it[0]), it[1]) }.average()
        assertTrue("average similarity $avg", avg > 0.8)
    }

    @Test
    fun everyPhonemeIsInTheModelVocabulary() {
        val rows = tsv("gold.tsv")
        for ((w, p) in rows) {
            val used = java.text.Normalizer.normalize(p.filter { it != 'ˈ' && it != 'ˌ' }, java.text.Normalizer.Form.NFD)
            assertEquals("$w -> $p", used.length, KokoroVocab.encode(used).size + used.count { it == '$' })
        }
    }

    @Test
    fun numbersAreRead() {
        assertEquals("vinte e cinco", PortuguesePhonemizer.numberToWords(25))
        assertEquals("cento e um", PortuguesePhonemizer.numberToWords(101))
        assertEquals("cem", PortuguesePhonemizer.numberToWords(100))
        assertEquals("mil e cem", PortuguesePhonemizer.numberToWords(1100))
        assertEquals("dois mil duzentos e trinta e quatro", PortuguesePhonemizer.numberToWords(2234))
        assertEquals("um milhão e quinhentos mil", PortuguesePhonemizer.numberToWords(1_500_000).let { it })
        assertTrue(PortuguesePhonemizer.expandNumbers("10% de 3,5").contains("dez por cento"))
    }

    @Test
    fun sentencesKeepPunctuationAndChunk() {
        val p = PortuguesePhonemizer.phonemize("Olá, mundo! Tudo bem?")
        assertTrue(p, p.contains(",") && p.contains("!") && p.endsWith("?"))
        val long = (1..200).joinToString(" ") { "casa bonita" } + "."
        val chunks = PortuguesePhonemizer.chunks(long)
        assertTrue(chunks.size > 1 && chunks.all { it.length <= 380 })
    }
}
