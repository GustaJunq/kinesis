package dev.kinesis.core.tts

import java.text.Normalizer

/**
 * Brazilian Portuguese text to the IPA symbols the Kokoro model reads. Rule based and written for this app (the
 * reference phonemizer of the model, espeak-ng, is GPL and native code): it follows the conventions of espeak-ng's
 * `pt-br` voice, which Kokoro was trained on, and was tuned against it on a corpus of everyday sentences. Words that
 * stray from the spelling rules (loan words, open/closed `e` and `o` without accent) come out approximate.
 *
 * Numbers are read out ("25" as "vinte e cinco"). Punctuation that the model knows is kept, since it shapes pauses.
 */
object PortuguesePhonemizer {

    private const val V = "aeiouáàâãéêíóôõúü"
    private val STRESS_ACCENTS = "áéíóúâêôãõà"
    private val ONSET_CLUSTERS = setOf("bl", "br", "cl", "cr", "dr", "fl", "fr", "gl", "gr", "pl", "pr", "tr", "vr")

    /** Function words: pronounced without stress, or with a form the rules would get wrong. */
    private val EXCEPTIONS = mapOf(
        "de" to "dʤi", "da" to "da", "do" to "dʊ", "dos" to "dʊs", "das" to "das", "o" to "ʊ", "a" to "a", "os" to "ʊs", "as" to "as",
        "e" to "i", "que" to "ki", "um" to "ũŋ", "uma" to "umæ", "em" to "eɪŋ", "no" to "nʊ", "na" to "na", "nos" to "nʊs", "nas" to "nas",
        "para" to "paɾæ", "com" to "koŋ", "por" to "poɾ", "se" to "si", "ao" to "aʊ", "aos" to "aʊs", "pelo" to "pelʊ", "pela" to "pelæ",
        "pelos" to "pelʊs", "pelas" to "pelæs", "é" to "ˈɛ", "ou" to "ˈow", "ele" to "ˈeli", "eu" to "ˈeʊ", "não" to "nˈɐ̃ʊ̃",
        "você" to "vosˈe", "vocês" to "vosˈes", "mais" to "mˈaɪs", "como" to "kˈomʊ", "muito" to "mwˈĩŋtʊ", "muita" to "mwˈĩŋtæ",
        "muitos" to "mwˈĩŋtʊs", "muitas" to "mwˈĩŋtæs", "também" to "tɐ̃mbˈeɪŋ", "são" to "sˈɐ̃ʊ̃", "foi" to "fˈoɪ", "já" to "ʒˈa",
        "mas" to "mas", "nós" to "nˈɔs", "ela" to "ˈɛlæ", "seu" to "seʊ", "sua" to "suæ", "meu" to "meʊ", "minha" to "miɲæ",
        "dois" to "dˈoɪs", "três" to "tɾˈes", "mil" to "mˈiʊ", "cem" to "sˈeɪŋ", "tem" to "tˈeɪŋ", "têm" to "tˈeɪŋ", "há" to "ˈa",
        "quando" to "kwˈɐ̃ŋdʊ",
    )

    /** Phonemes of [text]: words separated by spaces, with the punctuation the model understands. */
    fun phonemize(text: String): String {
        val sb = StringBuilder()
        val src = Normalizer.normalize(expandNumbers(text), Normalizer.Form.NFC).lowercase()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isLetter() -> {
                    var j = i
                    while (j < src.length && (src[j].isLetter() || (src[j] == '-' && j + 1 < src.length && src[j + 1].isLetter()))) j++
                    val word = src.substring(i, j)
                    for ((k, part) in word.split('-').withIndex()) {
                        if (part.isEmpty()) continue
                        if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                        sb.append(phonemizeWord(part))
                    }
                    i = j
                }
                c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' || c == '…' || c == '—' || c == '(' || c == ')' -> {
                    if (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                    sb.append(c)
                    i++
                }
                c == '"' || c == '“' || c == '”' -> { i++ }
                else -> {
                    if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                    i++
                }
            }
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------------------------ words

    private fun nuclei(w: String): List<IntRange> {
        val n = w.length
        val res = ArrayList<IntRange>()
        var i = 0
        while (i < n) {
            val c = w[i]
            if (c in V) {
                // The u of qu/gu before e/i is silent.
                if (c == 'u' && i > 0 && (w[i - 1] == 'q' || w[i - 1] == 'g') && i + 1 < n && w[i + 1] in "eiéêí") { i++; continue }
                var j = i + 1
                if ((c == 'ã' || c == 'õ') && j < n && w[j] in "oeiu") {
                    j++
                } else if (c in "aeéóo" && j < n && w[j] in "iu" && !(j + 1 < n && w[j + 1] in V)) {
                    j++
                } else if (c in "iu" && j < n && w[j] in "iu" && c != w[j]) {
                    j++
                } else if (c in "iu" && j < n && w[j] in "aeo" && j == n - 1 && !(i > 0 && w[i - 1] == 'q')) {
                    j++ // final glide + vowel (história, rua)
                } else if (c in "iu" && j < n && w[j] in "aeo" && j == n - 2 && w[j + 1] == 's') {
                    j++
                }
                res += i until j
                i = j
            } else i++
        }
        return res
    }

    private fun stressIndex(w: String, nu: List<IntRange>): Int {
        val k = nu.size
        for ((idx, r) in nu.withIndex()) if (r.any { w[it] in STRESS_ACCENTS }) return idx
        if (k <= 1) return 0
        if (Regex("(ão|ã|õe|ões|ãos|ãe)s?$").containsMatchIn(w)) return k - 1
        if (Regex("(a|e|o|as|es|os|am|em|ens|ns)$").containsMatchIn(w) && !Regex("(ins|uns|im|um)$").containsMatchIn(w)) return maxOf(0, k - 2)
        if (Regex("(i|u|l|r|z|x|im|um|is|us|ins|uns|ão)$").containsMatchIn(w)) return k - 1
        return maxOf(0, k - 2)
    }

    private fun vowelQuality(ch: Char, stressed: Boolean, finalPos: Boolean, nasal: Boolean): String {
        if (nasal) return when (ch) {
            'a', 'â', 'ã', 'á' -> "ɐ̃"
            'e', 'ê', 'é' -> "eɪ"
            'i', 'í' -> "i"
            'o', 'ô', 'ó' -> "o"
            'u', 'ú' -> "ũ"
            'õ' -> "õ"
            else -> ch.toString()
        }
        return when (ch) {
            'a', 'á', 'à' -> if (finalPos && !stressed && ch == 'a') "æ" else "a"
            'â' -> "ɐ"
            'ã' -> "ɐ̃"
            'é' -> "ɛ"
            'e', 'ê' -> if (finalPos && !stressed && ch == 'e') "y" else "e"
            'i', 'í' -> "i"
            'ó' -> "ɔ"
            'o', 'ô' -> if (finalPos && !stressed && ch == 'o') "ʊ" else "o"
            'õ' -> "õ"
            'u', 'ú', 'ü' -> "u"
            else -> ch.toString()
        }
    }

    fun phonemizeWord(w: String): String {
        EXCEPTIONS[w]?.let { return it }
        val nu = nuclei(w)
        val n = w.length
        if (nu.isEmpty()) return w
        val st = stressIndex(w, nu)
        val vstart = HashMap<Int, Int>()
        for ((idx, r) in nu.withIndex()) vstart[r.first] = idx
        val k = nu.size
        val out = StringBuilder()
        fun at(i: Int, off: Int = 0): Char = (i + off).let { if (it in 0 until n) w[it] else '#' }
        fun vowelAfter(j: Int) = j < n && w[j] in V
        var i = 0
        while (i < n) {
            val c = w[i]
            val idx = vstart[i]
            if (idx != null) {
                val r = nu[idx]
                val a = r.first
                val b = r.last + 1
                val seg = w.substring(a, b)
                val stressed = idx == st
                val finalNucleus = idx == k - 1
                val mark = when {
                    stressed -> "ˈ"
                    idx == 0 && k >= 3 && st >= 1 -> "ˌ"
                    else -> ""
                }
                val nxt = if (b < n) w[b] else '#'
                val nxt2 = if (b + 1 < n) w[b + 1] else '#'
                val isNasal = (nxt == 'm' || nxt == 'n') && (nxt2 == '#' || nxt2 !in V || (nxt2 == 's' && b + 2 >= n)) && !(nxt == 'n' && nxt2 == 'h')
                val coda = if (isNasal) (if (nxt2 == 'p' || nxt2 == 'b') "m" else "ŋ") else ""
                val fp = b >= n || w.substring(b) == "s"
                var ph: String
                if (seg.length == 1) {
                    ph = vowelQuality(seg[0], stressed, fp, isNasal)
                } else {
                    val a1 = seg[0]
                    val a2 = seg[1]
                    ph = when {
                        seg == "ão" -> "ɐ̃ʊ̃"
                        seg == "ãe" || seg == "ãi" -> "ɐ̃ɪ̃"
                        seg == "õe" -> "õɪ̃"
                        a2 in "iu" && a1 in "aeéóoíu" && a1 != a2 -> {
                            val g = if (a2 == 'i') "ɪ" else "ʊ"
                            when {
                                a1 == 'o' && a2 == 'u' -> "ow"
                                a1 in "iu" -> vowelQuality(a1, stressed, false, false) + (if (a2 == 'u') "ʊ" else "ɪ")
                                else -> vowelQuality(a1, stressed, false, false) + g
                            }
                        }
                        a1 in "iu" && a2 in "aeéo" -> vowelQuality(a1, true, false, false) + vowelQuality(a2, false, fp, false)
                        else -> seg.map { vowelQuality(it, stressed, fp, false) }.joinToString("")
                    }
                }
                // a before a nasal consonant that starts a syllable (canal, janela, chegamos): nasal a
                if (!isNasal && (nxt == 'm' || nxt == 'n') && nxt2 in V && (seg == "a" || seg == "â")) ph = "ɐ̃"
                out.append(mark).append(ph).append(coda)
                i = b
                if (isNasal) i = b + 1
                continue
            }
            val nxt = at(i, 1)
            val prv = at(i, -1)
            when (c) {
                'c' -> {
                    if (nxt == 'h') { out.append('ʃ'); i += 2; continue }
                    out.append(if (nxt in "eiéêí") 's' else 'k'); i++; continue
                }
                'ç' -> { out.append('s'); i++; continue }
                'q' -> {
                    out.append('k'); i++
                    if (at(i) == 'u') {
                        if (at(i, 1) in "eiéêí") i++ else { out.append('w'); i++ }
                    }
                    continue
                }
                'g' -> {
                    if (nxt == 'u' && at(i, 2) in "eiéêí") { out.append('ɡ'); i += 2; continue }
                    if (nxt == 'ü') { out.append("ɡw"); i += 2; continue }
                    out.append(if (nxt in "eiéêí") 'ʒ' else 'ɡ'); i++; continue
                }
                's' -> {
                    if (nxt == 's') { out.append('s'); i += 2; continue }
                    if (nxt == 'c' && at(i, 2) in "eiéêí") { out.append('s'); i += 2; continue }
                    if (prv in V && vowelAfter(i + 1)) out.append('z')
                    else if (prv in V && nxt in "bdgvzmnlr") out.append('z')
                    else out.append('s')
                    i++; continue
                }
                'x' -> {
                    out.append(if (prv == 'e' && i == 1 && nxt in V) 'z' else 'ʃ'); i++; continue
                }
                'r' -> {
                    if (nxt == 'r') { out.append('x'); i += 2; continue }
                    if (i == 0 || prv in "nls") { out.append('x'); i++; continue }
                    out.append(if (prv in V && nxt in V) 'ɾ' else 'r'); i++; continue
                }
                'l' -> {
                    if (nxt == 'h') { out.append('ʎ'); i += 2; continue }
                    when {
                        vowelAfter(i + 1) -> out.append('l')
                        prv == 'o' || prv == 'u' -> out.append('w')
                        else -> out.append('ʊ')
                    }
                    i++; continue
                }
                'n' -> {
                    if (nxt == 'h') { out.append('ɲ'); i += 2; continue }
                    out.append('n'); i++; continue
                }
                't', 'd' -> {
                    val aff = nxt in "iíĩ" || (nxt == 'e' && i + 2 == n) || (nxt == 'e' && i + 3 == n && at(i, 2) == 's')
                    out.append(if (aff) (if (c == 't') 'ʧ' else 'ʤ') else c); i++; continue
                }
                'h' -> { i++; continue }
                'j' -> { out.append('ʒ'); i++; continue }
                'y' -> { out.append('i'); i++; continue }
                'z' -> { out.append(if (i == n - 1) 's' else 'z'); i++; continue }
                else -> { out.append(c); i++; continue }
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ numbers

    private val UNITS = arrayOf(
        "zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze", "doze", "treze", "catorze",
        "quinze", "dezesseis", "dezessete", "dezoito", "dezenove",
    )
    private val TENS = arrayOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
    private val HUNDREDS = arrayOf("", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos", "oitocentos", "novecentos")

    private fun below1000(n: Int): String {
        if (n == 0) return ""
        if (n == 100) return "cem"
        val parts = ArrayList<String>()
        if (n >= 100) parts += HUNDREDS[n / 100]
        val r = n % 100
        if (r > 0) parts += if (r < 20) UNITS[r] else TENS[r / 10] + (if (r % 10 > 0) " e " + UNITS[r % 10] else "")
        return parts.joinToString(" e ")
    }

    /** [n] (0 up to 999 999 999 999) as Portuguese words. */
    fun numberToWords(n: Long): String {
        if (n == 0L) return "zero"
        if (n < 0 || n > 999_999_999_999L) return n.toString().map { UNITS[it - '0'] }.joinToString(" ")
        val groups = ArrayList<String>()
        val bi = (n / 1_000_000_000).toInt(); val mi = ((n / 1_000_000) % 1000).toInt(); val th = ((n / 1000) % 1000).toInt(); val rest = (n % 1000).toInt()
        if (bi > 0) groups += below1000(bi) + (if (bi == 1) " bilhão" else " bilhões")
        if (mi > 0) groups += below1000(mi) + (if (mi == 1) " milhão" else " milhões")
        if (th > 0) groups += (if (th == 1) "mil" else below1000(th) + " mil")
        if (rest > 0) groups += below1000(rest)
        // "e" joins the last group when it is round (mil e cem) or below 100.
        val sb = StringBuilder(groups[0])
        for (g in 1 until groups.size) sb.append(if (g == groups.size - 1 && (rest < 100 || rest % 100 == 0)) " e " else " ").append(groups[g])
        return sb.toString()
    }

    /** Replaces the numbers of [text] by words ("3,5" becomes "três vírgula cinco", "10%" "dez por cento"). */
    fun expandNumbers(text: String): String {
        val re = Regex("(\\d+)(?:[.,](\\d+))?\\s*(%)?")
        return re.replace(text) { m ->
            val whole = m.groupValues[1].replace(".", "")
            val frac = m.groupValues[2]
            val sb = StringBuilder(" ")
            sb.append(whole.toLongOrNull()?.let { numberToWords(it) } ?: whole.map { UNITS[it - '0'] }.joinToString(" "))
            if (frac.isNotEmpty()) sb.append(" vírgula ").append(frac.map { UNITS[it - '0'] }.joinToString(" "))
            if (m.groupValues[3].isNotEmpty()) sb.append(" por cento")
            sb.append(' ')
            sb.toString()
        }
    }

    /**
     * Splits [text] into pieces whose phonemes fit the model (at most [maxTokens]), cutting at sentence ends and then at
     * commas or spaces. Returns the phoneme strings.
     */
    fun chunks(text: String, maxTokens: Int = 380): List<String> {
        val out = ArrayList<String>()
        val sentences = Regex("[^.!?…;:\\n]+[.!?…;:]*").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }
        for (s in sentences) {
            val p = phonemize(s)
            if (p.isBlank()) continue
            if (p.length <= maxTokens) { out += p; continue }
            var cur = StringBuilder()
            for (w in p.split(' ')) {
                if (cur.isNotEmpty() && cur.length + 1 + w.length > maxTokens) { out += cur.toString(); cur = StringBuilder() }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(w)
            }
            if (cur.isNotEmpty()) out += cur.toString()
        }
        return out
    }
}
