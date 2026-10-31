package com.ozet.app.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Mode { AUTO, EXAM, STORY }
enum class Length { SHORT, MEDIUM, LONG }
enum class SectionKind { BULLETS, PARAGRAPHS, NUMBERED }

/** Bölüm başlığı: CUSTOM ise `title` metindeki gerçek başlık / konu adıdır, değilse arayüz dilindeki sabit başlık kullanılır. */
enum class SecTitle { CUSTOM, SUMMARY, TURNING, NOTES, GENERAL }

data class Section(
    val title: String?,
    val items: List<String>,
    val kind: SectionKind,
    val key: SecTitle = SecTitle.CUSTOM
)

data class Result(
    val mode: Mode,
    val sections: List<Section>,
    val keywords: List<String>,
    val sentenceCount: Int,
    val wordCount: Int,
    val srcLang: String = "und",
    val translated: Boolean = false,
    val translateFailed: Boolean = false
)

private class Sent(
    val idx: Int,
    val text: String,
    val stems: List<String>,
    val stemSet: Set<String>,
    val heading: String?,
    val bullet: Boolean,
    val dialogue: Boolean,
    val paraStart: Boolean,
    val lower: String,
    val para: Int
) {
    var base = 0.0
    var rank = 0.0
    var score = 0.0
    var facts: Set<String> = emptySet()
    var isDef = false
    var isClass = false
    var isCause = false
    var isCompare = false
    var isExample = false
    var isConc = false
    var hasDate = false
    var hasPct = false
    var hasNum = false
    var ents = 0
    var member = false
    var head = -1
    var org = false
    var run = -1
    var seg = 0
    val words: Int = text.count { it == ' ' } + 1
}

private class Kw(
    val key: String,
    val surface: String,
    val stems: Set<String>,
    val tf: Int,
    val phrase: Boolean,
    var score: Double
)

/**
 * Yerel, yapay zekasız (API'siz) özetleyici. Belgenin dili otomatik algılanır (TR/EN/DE/ES/FR/AR).
 *
 * Akış: ayrıştırma -> özellik çıkarma (tarih, sayı, isim, tanım, sınıflandırma, neden-sonuç, karşılaştırma...)
 * -> puanlama (TF-IDF + TextRank + bu özellikler) -> yapı korumalı seçim (her başlık, her madde, her sınıf üyesi)
 * -> doğrulama/onarım (kaybolan başlık, tarih, sayı, tanım, sınıflandırma) -> anahtar kelime seçimi.
 * Özet çıkarımsaldır: metne cümle eklemez, yalnızca orijinal cümleleri seçer.
 */
object Summarizer {
    private val WORD = Regex("\\p{L}+")
    private val WS = Regex("\\s+")
    private val PAGE_NUM = Regex("\\d{1,4}")
    private val MULTI_SPACE = Regex(" {2,}")
    private val HYPHEN_BREAK = Regex("(\\p{L})-\\n(\\p{L})")
    private val BULLET = Regex("^([•●▪◦■□▫‣⁃*]|-|\\d{1,2}[.)]|[a-zA-Z][.)])\\s+\\S.*")
    private val BULLET_MARK = Regex("^([•●▪◦■□▫‣⁃*]|-|\\d{1,2}[.)]|[a-zA-Z][.)])\\s+")
    private val NUMBERED_HEAD = Regex("^\\d+(\\.\\d+)*[.)]?\\s+\\p{Lu}")
    private val NAME = Regex("(?<=[^.!?…\"“”«»—–:\\s]\\s)\\p{Lu}\\p{Ll}{2,}")
    private val NUM = Regex("\\d+(?:[.,]\\d+)*")
    private val YEAR = Regex("(?<!\\d)(1[0-9]{3}|20[0-9]{2})(?!\\d)")
    private val ACRO = Regex("(?<![\\p{L}\\d])\\p{Lu}{2,}[\\p{Lu}\\d]*(?![\\p{L}])")
    private val TR_COPULA = Regex("^(?:[^,]{2,45},[^.]{10,170}|\\p{Lu}[^.,]{8,140})\\p{L}+(d[ıiuü]r|t[ıiuü]r)[.!]?$")
    private val TR_COP_END = Regex("^\\p{L}{5,}(d[ıiuü]r|t[ıiuü]r)$")
    private val TR_CASE_END = Regex("^\\p{L}{4,}(de|da|den|dan|nin|nın|nun|nün|yle|yla)$")
    private val FR_PART = Regex("^\\p{L}{4,}(é|ée|és|ées)$")
    private val PCT = Regex("%|٪|yüzde|percent|prozent|por ciento|pour cent|بالمئة|في المائة|بالمائة")

    // ------------------------------------------------------------------ giris noktasi

    fun summarize(raw: String, requested: Mode, length: Length): Result {
        val wordCount = raw.split(WS).count { it.isNotEmpty() }
        val detected = Langs.detect(raw)
        val pack = detected ?: Langs.TR
        val srcLang = detected?.code ?: "und"
        val sents = parse(raw, pack)
        if (sents.isEmpty()) return Result(Mode.EXAM, emptyList(), emptyList(), 0, wordCount, srcLang)
        for (s in sents) analyze(s, pack)
        markMembers(sents, pack)
        val weights = computeBase(sents)
        val mode = if (requested == Mode.AUTO) detect(sents, pack) else requested
        val cands = keywordCandidates(sents, weights, pack)
        return if (mode == Mode.STORY) {
            val sec = storySections(sents, length, pack)
            val chosen = HashSet<Sent>(sec.second)
            Result(mode, sec.first, finalKeywords(cands, chosen, sents, pack), sents.size, wordCount, srcLang)
        } else {
            val ex = examSections(sents, length, pack, cands)
            Result(mode, ex.first, finalKeywords(cands, ex.second, sents, pack), sents.size, wordCount, srcLang)
        }
    }

    // ------------------------------------------------------------------ ayristirma

    private fun parse(raw: String, pack: LangPack): List<Sent> {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
            .replace("\u00AD", "").replace('\u00A0', ' ').replace('\t', ' ')
        text = text.replace(HYPHEN_BREAK, "\$1\$2")
        val rawLines = text.split('\n').map { it.trim().replace(MULTI_SPACE, " ") }

        val freq = HashMap<String, Int>()
        for (l in rawLines) if (l.length in 6..80) freq[l] = (freq[l] ?: 0) + 1
        val lines = rawLines.filter { l ->
            val pageNum = PAGE_NUM.matches(l)
            val repeated = l.length in 6..80 && (freq[l] ?: 0) >= 4 && !isDialogueStart(l) && !isBullet(l)
            !(pageNum || repeated)
        }

        val out = ArrayList<Sent>()
        var heading: String? = null
        var para = 0
        val buf = StringBuilder()

        fun addUnit(unit: String, bullet: Boolean, dialogue: Boolean) {
            val parts = if (bullet) listOf(unit) else splitSentences(unit, pack)
            var first = true
            for (p in parts) {
                val t = p.trim()
                val words = t.count { it == ' ' } + 1
                if (t.length < (if (bullet) 12 else 25) || words < (if (bullet) 2 else 4)) continue
                val st = stems(t, pack)
                if (st.size < 2) continue
                out.add(Sent(out.size, t, st, st.toHashSet(), heading, bullet, dialogue, first, pack.lower(t), para))
                first = false
            }
        }

        fun flush() {
            if (buf.isNotEmpty()) {
                addUnit(buf.toString(), false, false)
                buf.setLength(0)
            }
        }

        var prevBlank = true
        for (line in lines) {
            if (line.isEmpty()) {
                flush(); prevBlank = true; para++; continue
            }
            if (line.startsWith("# ")) {
                flush(); heading = cleanHeading(line.substring(2), pack); para++; prevBlank = false; continue
            }
            val canStart = prevBlank || buf.isEmpty() || buf.last() in ".!?…:\"”؟"
            when {
                isDialogueStart(line) -> { flush(); addUnit(line, false, true) }
                isBullet(line) -> { flush(); addUnit(line.replaceFirst(BULLET_MARK, ""), true, false) }
                isHeading(line, canStart, pack) -> { flush(); heading = cleanHeading(line, pack); para++ }
                else -> {
                    if (buf.isNotEmpty()) buf.append(' ')
                    buf.append(line)
                }
            }
            prevBlank = false
        }
        flush()
        return out
    }

    private fun isDialogueStart(line: String): Boolean {
        if (line.startsWith("—") || line.startsWith("–") || line.startsWith("“") ||
            line.startsWith("\"") || line.startsWith("«")
        ) return true
        if (line.startsWith("- ") && (line.contains('?') || line.contains('!'))) return true
        return false
    }

    private fun isBullet(line: String): Boolean {
        if (!BULLET.matches(line)) return false
        val rest = line.replaceFirst(BULLET_MARK, "")
        val marker = line.substring(0, line.length - rest.length).trim()
        val enumerator = marker.isNotEmpty() && (marker[0].isLetterOrDigit())
        if (enumerator) {
            return rest.length >= 30 || (rest.isNotEmpty() && rest.last() in ".!?;")
        }
        return true
    }

    private fun isHeading(line: String, canStart: Boolean, pack: LangPack): Boolean {
        if (line.length > 80) return false
        val words = line.split(' ').size
        val last = line.last()
        if (NUMBERED_HEAD.containsMatchIn(line) && words <= 10 && last !in ".!?") return true
        val letters = line.filter { it.isLetter() }
        if (letters.length >= 4 && words <= 10 && letters.all { it.isUpperCase() }) return true
        if (last == ':' && words <= 8) return true
        val startsOk = line[0].isUpperCase() || (!pack.hasCase && line[0].isLetter())
        if (canStart && words <= 6 && startsOk && last !in ".!?,;:؟،" && !line.contains(',') && !line.contains('،')) return true
        return false
    }

    private fun cleanHeading(h: String, pack: LangPack): String {
        var t = h.trim().trimEnd(':').trim()
        val letters = t.filter { it.isLetter() }
        if (letters.length >= 4 && letters.all { it.isUpperCase() }) {
            t = t.lowercase(pack.locale).split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> c.titlecase(pack.locale) } }
        }
        return if (t.length > 80) t.substring(0, 80) else t
    }

    private fun splitSentences(p: String, pack: LangPack): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        val n = p.length
        while (i < n) {
            val c = p[i]
            if (c == '.' || c == '!' || c == '?' || c == '…' || c == '؟') {
                var j = i + 1
                while (j < n && (p[j] == '.' || p[j] == '!' || p[j] == '?' || p[j] == '”' || p[j] == '"' ||
                        p[j] == '\'' || p[j] == ')' || p[j] == '»' || p[j] == '’' || p[j] == '؟')
                ) j++
                var ok = false
                if (j >= n) {
                    ok = true
                } else if (p[j].isWhitespace()) {
                    var k = j
                    while (k < n && p[k].isWhitespace()) k++
                    if (k >= n) {
                        ok = true
                    } else {
                        val nx = p[k]
                        ok = nx.isUpperCase() || nx.isDigit() || nx == '"' || nx == '“' || nx == '«' ||
                            nx == '(' || nx == '—' || nx == '–' || nx == '-' || (!pack.hasCase && nx.isLetter())
                    }
                }
                if (ok && c == '.') {
                    if (i > 0 && p[i - 1].isDigit()) {
                        var d = i - 1
                        while (d >= 0 && p[d].isDigit()) d--
                        if (i - 1 - d <= 2 && (d < 0 || p[d].isWhitespace())) ok = false
                    } else {
                        var w = i - 1
                        while (w >= 0 && (p[w].isLetter() || p[w] == '.')) w--
                        val word = p.substring(w + 1, i).lowercase(pack.locale)
                        if ((word.length == 1 && pack.hasCase) || word in pack.abbr) ok = false
                    }
                }
                if (ok) {
                    val s = p.substring(start, j).trim()
                    if (s.isNotEmpty()) out.add(s)
                    start = j
                    i = j
                    continue
                }
            }
            i++
        }
        if (start < n) {
            val s = p.substring(start).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun stems(text: String, pack: LangPack): List<String> {
        val res = ArrayList<String>()
        for (m in WORD.findAll(pack.lower(text))) {
            val w = m.value
            if (w.length < 3 || w in pack.stop) continue
            res.add(pack.stem(w))
        }
        return res
    }

    // ------------------------------------------------------------------ ozellik cikarma

    /** Cümledeki tarih, sayı, yüzde, kurum/kişi adı, kısaltma ve tanım/sınıflandırma/neden-sonuç/karşılaştırma ipuçları. */
    private fun analyze(s: Sent, pack: LangPack) {
        val f = HashSet<String>()
        val pct = PCT.containsMatchIn(s.lower)
        var anyNum = false
        for (m in NUM.findAll(s.text)) {
            val d = m.value.replace(".", "").replace(",", "")
            if (d.length >= 2 || pct) {
                f.add("n:$d")
                anyNum = true
            }
        }
        val year = YEAR.containsMatchIn(s.text)
        s.hasNum = anyNum
        s.hasPct = pct && anyNum
        s.hasDate = year || (anyNum && pack.months.containsMatchIn(s.lower))
        var acr = 0
        for (m in ACRO.findAll(s.text)) {
            if (m.value.length <= 8) { f.add("a:" + m.value); acr++ }
        }
        var ents = 0
        if (pack.hasCase && pack.code != "de") {
            val toks = s.text.split(' ')
            var prevEnd = true
            for ((i, t0) in toks.withIndex()) {
                val t = t0.trim { !it.isLetterOrDigit() }
                if (i > 0 && !prevEnd && t.length >= 3 && t[0].isUpperCase() && t.drop(1).any { it.isLowerCase() } &&
                    pack.lower(t) !in pack.stop
                ) {
                    f.add("e:" + pack.lower(t))
                    ents++
                }
                prevEnd = t0.isNotEmpty() && t0.last() in ".!?…:\"”»"
            }
        }
        s.ents = ents + acr
        s.org = ents > 0 && pack.inst.containsMatchIn(s.lower)
        s.facts = f
        s.isDef = pack.def.containsMatchIn(s.lower) || (pack.code == "tr" && TR_COPULA.matches(s.text))
        s.isClass = pack.classCue.containsMatchIn(s.lower)
        s.isCause = pack.cause.containsMatchIn(s.lower)
        s.isCompare = pack.compare.containsMatchIn(s.lower)
        s.isExample = pack.example.containsMatchIn(s.lower)
        s.isConc = pack.conclusion.containsMatchIn(s.lower)
    }

    private fun announced(s: Sent, pack: LangPack): Int {
        for (m in WORD.findAll(s.lower)) pack.numWords[m.value]?.let { return it }
        val d = Regex("(?<![\\d.,])([2-8])(?![\\d.,%])").find(s.text)?.value?.toIntOrNull()
        return d ?: 0
    }

    /** Sınıflandırma duyuran cümlelerin (ör. "üç türü vardır") üyelerini ve madde listelerini işaretler. */
    private fun markMembers(sents: List<Sent>, pack: LangPack) {
        val n = sents.size
        var runId = -1
        var prev: Sent? = null
        for (s in sents) {
            if (s.bullet) {
                if (prev == null || !prev.bullet || prev.heading != s.heading) runId++
                s.run = runId
                s.member = true
            }
            prev = s
        }
        for (i in 0 until n) {
            val h = sents[i]
            if (!h.isClass || h.bullet) continue
            val ann = announced(h, pack)
            val cap = if (ann in 2..8) ann + 1 else 8
            var found = 0
            var j = i + 1
            while (j < n && j - i <= 14 && found < cap && sents[j].heading == h.heading) {
                val t = sents[j]
                if (t.bullet) {
                    t.member = true; found++
                } else if (!pack.anaphora.containsMatchIn(t.lower)) {
                    val first = t.stems.firstOrNull()
                    var shared = 0
                    for (x in t.stemSet) if (x in h.stemSet) shared++
                    val near = j - i <= 4 && t.para == h.para
                    if (!t.isClass && ((first != null && first in h.stemSet) || (near && shared >= 2))) {
                        t.member = true; t.head = h.idx; found++
                    }
                }
                j++
            }
        }
    }

    // ------------------------------------------------------------------ puanlama

    private fun computeBase(sents: List<Sent>): Map<String, Double> {
        val n = sents.size
        val tf = HashMap<String, Int>()
        val df = HashMap<String, Int>()
        for (s in sents) {
            for (t in s.stems) tf[t] = (tf[t] ?: 0) + 1
            for (t in s.stemSet) df[t] = (df[t] ?: 0) + 1
        }
        val w = HashMap<String, Double>()
        for ((t, c) in tf) {
            val d = df[t] ?: 1
            w[t] = ln(1.0 + c) * ln(1.0 + n.toDouble() / d) * (min(c, 3) / 3.0)
        }
        var mx = 1e-9
        for (s in sents) {
            var sum = 0.0
            for (t in s.stemSet) sum += w[t] ?: 0.0
            val size = max(1, s.stemSet.size)
            var v = sum / size.toDouble().pow(0.75)
            if (s.words > 40) v *= 0.7
            if (s.words < 6) v *= 0.5
            s.base = v
            if (v > mx) mx = v
        }
        for (s in sents) s.base /= mx
        textRank(sents)
        return w
    }

    private fun textRank(sents: List<Sent>) {
        val cand = sents.sortedByDescending { it.base }.take(400)
        val m = cand.size
        if (m < 3) {
            for (s in cand) s.rank = s.base
            return
        }
        val sim = Array(m) { DoubleArray(m) }
        for (i in 0 until m) {
            for (j in i + 1 until m) {
                val a = cand[i].stemSet
                val b = cand[j].stemSet
                if (a.isEmpty() || b.isEmpty()) continue
                val small = if (a.size <= b.size) a else b
                val large = if (a.size <= b.size) b else a
                var common = 0
                for (t in small) if (t in large) common++
                if (common == 0) continue
                val v = common / (ln(a.size + 1.0) + ln(b.size + 1.0))
                sim[i][j] = v
                sim[j][i] = v
            }
        }
        val rowSum = DoubleArray(m) { i -> sim[i].sum() }
        var r = DoubleArray(m) { 1.0 / m }
        repeat(30) {
            val nr = DoubleArray(m)
            for (i in 0 until m) {
                var acc = 0.0
                for (j in 0 until m) {
                    if (sim[j][i] > 0 && rowSum[j] > 0) acc += sim[j][i] / rowSum[j] * r[j]
                }
                nr[i] = 0.15 / m + 0.85 * acc
            }
            r = nr
        }
        val mx = max(r.maxOrNull() ?: 1e-9, 1e-9)
        for (i in 0 until m) cand[i].rank = r[i] / mx
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var c = 0
        for (t in a) if (t in b) c++
        return c.toDouble() / (a.size + b.size - c)
    }

    /** Tekrar: çok benzer cümleyi eler; ama farklı bir tarih/sayı/isim/madde taşıyan cümleyi ASLA silmez. */
    private fun redundant(s: Sent, chosen: Collection<Sent>): Boolean {
        for (c in chosen) {
            val j = jaccard(c.stemSet, s.stemSet)
            if (j > 0.85) return true
            if (j > 0.6 && !s.member && s.facts.all { it in c.facts }) return true
        }
        return false
    }

    private fun display(s: Sent): String {
        val t = s.text.replace(WS, " ").trim()
        val limit = if (s.member || s.isClass || s.facts.size >= 3) 560 else 400
        if (t.length <= limit) return t
        var cut = max(t.lastIndexOf("; ", limit), t.lastIndexOf(", ", limit))
        if (cut < limit * 0.6) cut = t.lastIndexOf(' ', limit - 3)
        if (cut < limit * 0.5) cut = limit - 3
        return t.substring(0, cut).trimEnd(',', ';', ' ') + "…"
    }

    // ------------------------------------------------------------------ tur algilama

    private fun detect(sents: List<Sent>, pack: LangPack): Mode {
        val n = sents.size.toDouble()
        val bullet = sents.count { it.bullet } / n
        val dialog = sents.count { it.dialogue } / n
        val head = sents.mapNotNull { it.heading }.toSet().size / n
        val def = sents.count { it.isDef } / n
        val digit = sents.count { s -> s.text.any { ch -> ch.isDigit() } } / n
        var words = 0
        var past = 0
        for (s in sents.take(3000)) {
            for (m in WORD.findAll(s.lower)) {
                words++
                if (m.value.length >= 3 && pack.isPast(m.value)) past++
            }
        }
        val pastRate = if (words == 0) 0.0 else past.toDouble() / words
        val exam = 5 * def + 2.5 * bullet + 3 * head + 1.5 * digit
        val story = 4 * dialog + 7 * pastRate
        return if (story >= exam && story > 0.6) Mode.STORY else Mode.EXAM
    }

    // ------------------------------------------------------------------ hedef uzunluk

    /** Metnin uzunluğuna ve bilgi yoğunluğuna göre özet cümle sayısı: kısa metni uzatmaz, uzun metni aşırı kısaltmaz. */
    private fun targetCount(sents: List<Sent>, length: Length): Int {
        val n = sents.size
        val dense = sents.count { it.facts.isNotEmpty() || it.isDef || it.isClass || it.isCause || it.isCompare }.toDouble() / n
        val cfg = when (length) {
            Length.SHORT -> doubleArrayOf(0.07, 4.0, 18.0, 0.25)
            Length.MEDIUM -> doubleArrayOf(0.13, 6.0, 36.0, 0.40)
            Length.LONG -> doubleArrayOf(0.22, 12.0, 70.0, 0.55)
        }
        // Kısa/orta notlarda (öğrenci notu) oran yüksek, çok uzun metinlerde düşük; bilgi yoğunsa daha fazla cümle korunur.
        val small = 1.0 + 1.2 * max(0.0, (60.0 - n) / 60.0)
        var t = n * cfg[0] * small * (0.7 + 1.6 * dense)
        // Tarih ağırlıklı (zaman çizelgesi gibi) metinlerde olayların önemli kısmı korunur.
        val dated = sents.count { it.hasDate }
        if (n >= 8 && dated.toDouble() / n >= 0.35) {
            val f = when (length) { Length.SHORT -> 0.30; Length.MEDIUM -> 0.45; Length.LONG -> 0.65 }
            t = max(t, dated * f)
        }
        val lo = min(cfg[1].toInt(), ceil(n * cfg[3]).toInt()).coerceAtLeast(1)
        val capBase = (cfg[2] * (1 + 0.5 * max(0.0, ln(n / 300.0)))).roundToInt()
        val hi = max(lo, min(capBase, ceil(n * 0.7).toInt()))
        return t.roundToInt().coerceIn(lo, hi).coerceAtMost(n)
    }

    private fun wordRatio(length: Length): Double = when (length) {
        Length.SHORT -> 0.12
        Length.MEDIUM -> 0.22
        Length.LONG -> 0.35
    }

    // ------------------------------------------------------------------ bolum / konu gruplari

    private class Grp(val title: String?, val skey: SecTitle) {
        val members = ArrayList<Sent>()
    }

    private fun cosine(a: Map<String, Int>, b: Map<String, Int>): Double {
        var dot = 0.0
        for ((k, v) in a) { val o = b[k]; if (o != null) dot += v.toDouble() * o }
        var na = 0.0
        for (v in a.values) na += v.toDouble() * v
        var nb = 0.0
        for (v in b.values) nb += v.toDouble() * v
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
    }

    /** Başlığı olmayan uzun metinlerde konu geçişlerini (kelime örtüşmesinin düştüğü, paragraf başı olan yerleri) bulur. */
    private fun segmentStarts(sents: List<Sent>): List<Int> {
        val n = sents.size
        val w = (n / 20).coerceIn(4, 10)
        if (n < 2 * w + 2) return emptyList()
        val sims = DoubleArray(n) { Double.NaN }
        fun block(from: Int, to: Int): Map<String, Int> {
            val m = HashMap<String, Int>()
            for (i in from until to) for (t in sents[i].stemSet) m[t] = (m[t] ?: 0) + 1
            return m
        }
        for (b in w..(n - w)) sims[b] = cosine(block(b - w, b), block(b, b + w))
        val valid = (w..(n - w)).map { sims[it] }
        val mean = valid.average()
        val sd = sqrt(valid.sumOf { (it - mean) * (it - mean) } / valid.size)
        val cands = ArrayList<Int>()
        for (b in w..(n - w)) {
            val v = sims[b]
            val l = if (b - 1 >= w) sims[b - 1] else Double.MAX_VALUE
            val r = if (b + 1 <= n - w) sims[b + 1] else Double.MAX_VALUE
            if (v < mean - 0.5 * sd && v <= l && v <= r && sents[b].paraStart && sents[b].para != sents[b - 1].para) cands.add(b)
        }
        val maxSeg = (n / 25).coerceIn(2, 6)
        val picked = ArrayList<Int>()
        for (b in cands.sortedBy { sims[it] }) {
            if (picked.size >= maxSeg - 1) break
            if (picked.any { abs(it - b) < w * 1.5 }) continue
            picked.add(b)
        }
        picked.sort()
        return picked
    }

    private fun buildGroups(sents: List<Sent>, cands: List<Kw>): List<Grp> {
        val heads = sents.mapNotNull { it.heading }.toSet()
        val groups = ArrayList<Grp>()
        if (heads.size >= 2) {
            val byHead = LinkedHashMap<String?, Grp>()
            for (s in sents) {
                val g = byHead.getOrPut(s.heading) {
                    if (s.heading == null) Grp(null, SecTitle.GENERAL) else Grp(s.heading, SecTitle.CUSTOM)
                }
                g.members.add(s)
            }
            groups.addAll(byHead.values)
        } else {
            val starts = if (sents.size >= 24) segmentStarts(sents) else emptyList()
            if (starts.isEmpty()) {
                val g = Grp(null, SecTitle.NOTES)
                g.members.addAll(sents)
                groups.add(g)
            } else {
                val bounds = listOf(0) + starts + listOf(sents.size)
                val used = HashSet<String>()
                for (k in 0 until bounds.size - 1) {
                    val part = sents.subList(bounds[k], bounds[k + 1])
                    var bestKw: Kw? = null
                    var bestV = 0.0
                    for (kw in cands) {
                        if (kw.surface in used) continue
                        val cnt = part.count { s -> kw.stems.all { it in s.stemSet } }
                        val v = cnt * kw.score * (if (kw.phrase) 1.3 else 1.0)
                        if (cnt >= 2 && v > bestV) { bestV = v; bestKw = kw }
                    }
                    val g = if (bestKw != null) { used.add(bestKw.surface); Grp(bestKw.surface, SecTitle.CUSTOM) } else Grp(null, SecTitle.NOTES)
                    g.members.addAll(part)
                    groups.add(g)
                }
            }
        }
        return groups
    }

    // ------------------------------------------------------------------ sinav / ders notu

    private fun examSections(sents: List<Sent>, length: Length, pack: LangPack, cands: List<Kw>): Pair<List<Section>, Set<Sent>> {
        val timeline = sents.size >= 8 && sents.count { it.hasDate }.toDouble() / sents.size >= 0.35
        for (s in sents) {
            var v = 0.45 * s.base + 0.45 * s.rank
            if (s.isDef) v += 0.28
            if (s.isClass) v += 0.22
            if (s.member) v += 0.12
            if (s.isCause) v += 0.12
            if (s.isCompare) v += 0.12
            if (s.isConc) v += 0.22
            if (s.isExample && s.facts.isNotEmpty()) v += 0.05
            v += 0.05 * min(s.facts.size, 4)
            if (s.hasDate) v += if (timeline) 0.24 else 0.08
            if (timeline && s.hasDate && s.ents > 0) v += 0.06
            if (s.hasPct) v += 0.08
            if (s.org) v += 0.05
            if (s.bullet) v += 0.12
            if (s.paraStart) v += 0.06
            if (s.text.contains(':')) v += 0.04
            if (s.idx == 0) v += 0.1
            if (s.dialogue) v -= 0.1
            s.score = v
        }
        val n = sents.size
        val target = targetCount(sents, length)
        val avgWords = sents.sumOf { it.words }.toDouble() / n
        val wordBudget = max(80, max((sents.sumOf { it.words } * wordRatio(length)).toInt(), (target * avgWords * 1.2).toInt()))
        val groups = buildGroups(sents, cands)
        for ((gi, g) in groups.withIndex()) for (s in g.members) s.seg = gi
        val chosen = LinkedHashSet<Sent>()
        var words = 0
        fun add(s: Sent): Boolean {
            if (chosen.add(s)) { words += s.words; return true }
            return false
        }

        // 1) Her başlığın / konunun altında en az bir açıklayıcı cümle
        val maxGroups = max(2, (target * 1.3).toInt())
        val orderedGroups = groups.sortedByDescending { g -> g.members.sumOf { it.score } }.take(maxGroups)
        if (groups.size > 1) {
            for (g in orderedGroups) {
                val best = g.members.maxByOrNull { it.score + (if (it.paraStart) 0.03 else 0.0) } ?: continue
                if (!redundant(best, chosen)) add(best)
            }
        }

        // 2) Ana kavramların tanımları
        var defAdded = 0
        for (kw in cands.take(6)) {
            if (defAdded >= max(2, target / 4)) break
            val d = sents.filter { it.isDef && !it.dialogue && kw.stems.all { st -> st in it.stemSet } }.maxByOrNull { it.score } ?: continue
            if (d !in chosen && !redundant(d, chosen)) { add(d); defAdded++ }
        }

        // 3) Puana göre doldurma
        for (s in sents.sortedByDescending { it.score }) {
            if (chosen.size >= target || words >= wordBudget) break
            if (s in chosen || redundant(s, chosen)) continue
            add(s)
        }

        // 3b) Sınıflandırmayı duyuran cümleler ("... iki gruba ayrılır") mutlaka özette olsun
        var headAdded = 0
        for (h in sents.filter { it.isClass && !it.bullet && !it.dialogue }.sortedByDescending { it.score }) {
            if (headAdded >= max(2, target / 4)) break
            if (h !in chosen && (announced(h, pack) > 0 || h.text.contains(':'))) { add(h); headAdded++ }
        }

        // 4) Bütünlük: sınıflandırma üyeleri ve madde listeleri eksik kalmasın
        val runCap = when (length) { Length.SHORT -> 4; Length.MEDIUM -> 6; Length.LONG -> 10 }
        for (round in 0 until 3) {
            var changed = false
            val snapshot = chosen.toList()
            for (s in snapshot) {
                if (s.isClass && !s.bullet) {
                    var cnt = 0
                    for (t in sents) {
                        if (t.head == s.idx && cnt < 8) { cnt++; if (add(t)) changed = true }
                    }
                }
                if (s.head >= 0 && add(sents[s.head])) changed = true
                if (s.run >= 0) {
                    val mates = sents.filter { it.run == s.run }
                    val keep = if (mates.size <= runCap) mates else (mates.filter { it in chosen } + mates.sortedByDescending { it.score }).distinct().take(runCap)
                    for (t in keep) if (add(t)) changed = true
                }
            }
            if (!changed) break
        }

        // 5) Doğrulama: önemli tarih / sayı / yüzde / isim kaybolmasın
        val sorted = sents.map { it.score }.sorted()
        val thr = sorted[(sorted.size * 0.6).toInt().coerceIn(0, sorted.size - 1)]
        val keyFacts = HashSet<String>()
        for (s in sents) if (s.hasDate || s.hasPct || s.score >= thr) keyFacts.addAll(s.facts)
        if (keyFacts.isNotEmpty()) {
            val covered = HashSet<String>()
            for (s in chosen) covered.addAll(s.facts)
            val extraCap = max(1, target / 4)
            var extra = 0
            while (extra < extraCap) {
                var best: Sent? = null
                var bestV = 0.0
                for (s in sents) {
                    if (s in chosen || s.dialogue) continue
                    val gain = s.facts.count { it in keyFacts && it !in covered }
                    if (gain == 0) continue
                    val v = gain + s.score
                    if (v > bestV) { bestV = v; best = s }
                }
                if (best == null) break
                add(best); covered.addAll(best.facts); extra++
                if (keyFacts.count { it in covered }.toDouble() / keyFacts.size >= 0.85) break
            }
        }

        // 6) Kendi başına anlaşılabilirlik: "Bu / Ayrıca / However" ile başlayan cümlenin önceki cümlesi
        val ctxCap = max(1, target / 5)
        var ctx = 0
        for (s in chosen.toList().sortedBy { it.idx }) {
            if (ctx >= ctxCap) break
            if (s.idx > 0 && pack.anaphora.containsMatchIn(s.lower)) {
                val p = sents[s.idx - 1]
                if (p !in chosen && p.para == s.para && p.heading == s.heading && !p.dialogue) { add(p); ctx++ }
            }
        }

        // 7) Hiçbir başlık / konu içeriksiz kalmasın
        if (groups.size > 1) {
            for (g in orderedGroups) {
                if (g.members.none { it in chosen }) {
                    val best = g.members.maxByOrNull { it.score } ?: continue
                    add(best)
                }
            }
        }

        val byGroup = LinkedHashMap<Int, MutableList<Sent>>()
        for (s in chosen.sortedBy { it.idx }) byGroup.getOrPut(s.seg) { ArrayList() }.add(s)
        val sections = byGroup.map { (gi, list) ->
            val g = groups[gi]
            Section(g.title, list.map { display(it) }, SectionKind.BULLETS, g.skey)
        }
        return Pair(sections, chosen)
    }

    // ------------------------------------------------------------------ hikaye / kitap

    private fun bump(pos: Double, center: Double, width: Double): Double {
        val x = (pos - center) / width
        return exp(-x * x)
    }

    private fun storySections(sents: List<Sent>, length: Length, pack: LangPack): Pair<List<Section>, List<Sent>> {
        val n = sents.size

        val nameFreq = HashMap<String, Int>()
        val namesOf = ArrayList<List<String>>(n)
        for (s in sents) {
            val ns = NAME.findAll(s.text).map { it.value.lowercase(pack.locale) }.toList()
            namesOf.add(ns)
            for (x in ns) nameFreq[x] = (nameFreq[x] ?: 0) + 1
        }
        val seen = HashSet<String>()
        val newChar = BooleanArray(n)
        for (i in 0 until n) {
            for (x in namesOf[i]) {
                if ((nameFreq[x] ?: 0) >= 4 && seen.add(x)) newChar[i] = true
            }
        }

        for (i in 0 until n) {
            val s = sents[i]
            var v = 0.5 * s.base + 0.5 * s.rank
            v += 0.08 * min(namesOf[i].size, 3)
            if (pack.storyCue.containsMatchIn(s.lower)) v += 0.15
            if (s.dialogue) v *= 0.6
            if (s.text.length > 300) v *= 0.8
            s.score = v
        }

        val cfg = when (length) {
            Length.SHORT -> Pair(6, 4)
            Length.MEDIUM -> Pair(10, 7)
            Length.LONG -> Pair(16, 12)
        }
        val m = cfg.second
        val k = max(1, min(cfg.first, n / 3))
        val picks = ArrayList<Sent>()
        for (seg in 0 until k) {
            val from = seg * n / k
            val to = (seg + 1) * n / k
            if (to <= from) continue
            var best: Sent? = null
            for (i in from until to) {
                if (best == null || sents[i].score > best.score) best = sents[i]
            }
            if (best != null) picks.add(best)
        }

        val ev = DoubleArray(n)
        for (i in 0 until n) {
            val s = sents[i]
            var v = 0.3 * s.base
            var cues = 0
            for (c in pack.storyCue.findAll(s.lower)) {
                cues++
                if (cues >= 3) break
            }
            v += 0.25 * cues
            if (s.text.contains('!')) v += 0.1
            if (newChar[i]) v += 0.35
            if (pack.contrastStart.containsMatchIn(s.lower)) v += 0.25
            val pos = i.toDouble() / max(1, n - 1)
            v += 0.12 * bump(pos, 0.12, 0.07) + 0.12 * bump(pos, 0.5, 0.08) +
                0.3 * bump(pos, 0.78, 0.07) + 0.1 * bump(pos, 0.96, 0.04)
            if (s.dialogue) v *= 0.8
            ev[i] = v
        }
        val gap = max(1, n / (m * 2))
        val order = (0 until n).sortedByDescending { ev[it] }
        val tp = ArrayList<Int>()
        for (i in order) {
            if (tp.size >= m) break
            if (tp.any { abs(it - i) < gap }) continue
            tp.add(i)
        }
        tp.sort()

        val sections = listOf(
            Section(null, picks.sortedBy { it.idx }.map { display(it) }.distinct(), SectionKind.PARAGRAPHS, SecTitle.SUMMARY),
            Section(null, tp.map { display(sents[it]) }, SectionKind.NUMBERED, SecTitle.TURNING)
        )
        return Pair(sections, picks + tp.map { sents[it] })
    }

    // ------------------------------------------------------------------ anahtar kelimeler

    private fun pickSurface(m: Map<String, Int>): String {
        val mx = m.values.maxOrNull() ?: 1
        return m.filter { it.value * 2 >= mx }.keys.minByOrNull { it.length } ?: m.keys.first()
    }

    private fun titleWord(low: String, acro: Boolean, pack: LangPack): String =
        if (acro) low.uppercase(Locale.ROOT) else low.replaceFirstChar { c -> c.titlecase(pack.locale) }

    /**
     * Aday anahtar kelimeler: tek kelime ve iki kelimelik kavramlar ("Makine Öğrenmesi").
     * Puan: TF-IDF + başlıkta geçme + tanımın öznesi olma + kısaltma / özel isim + tekrar eden kavram bonusu.
     * Genel kelimeler ("yeni", "çalışan", "belirli"...), fiiller ve yalnızca diyaloglarda geçenler elenir.
     */
    private fun keywordCandidates(sents: List<Sent>, weights: Map<String, Double>, pack: LangPack): List<Kw> {
        val maxW = max(weights.values.maxOrNull() ?: 1.0, 1e-9)
        val headSets = ArrayList<Set<String>>()
        val headStems = HashSet<String>()
        val seenHeads = HashSet<String>()
        for (s in sents) {
            val h = s.heading ?: continue
            if (seenHeads.add(h)) {
                val st = stems(h, pack).toHashSet()
                headSets.add(st); headStems.addAll(st)
            }
        }
        val defStems = HashSet<String>()
        val defPhrases = HashSet<String>()
        for (s in sents) {
            if (!s.isDef || s.bullet || s.words > 40) continue
            var prev: String? = null
            var cnt = 0
            for (m in WORD.findAll(s.text)) {
                if (cnt >= 4) break
                cnt++
                val low = pack.lower(m.value)
                if (low.length < 3 || low in pack.stop || low in pack.generic) { prev = null; continue }
                val st = pack.stem(low)
                defStems.add(st)
                if (prev != null) defPhrases.add("$prev $st")
                prev = st
            }
        }

        val uTf = HashMap<String, Int>()
        val uSurf = HashMap<String, HashMap<String, Int>>()
        val uAcro = HashMap<String, Int>()
        val uCap = HashMap<String, Int>()
        val uDlg = HashMap<String, Int>()
        val bTf = HashMap<String, Int>()
        val bSurf = HashMap<String, HashMap<String, Int>>()
        val bProper = HashMap<String, Int>()
        val bAcro = HashMap<String, Int>()
        class Txt(val text: String, val dialogue: Boolean)
        val texts = ArrayList<Txt>()
        for (s in sents.take(6000)) texts.add(Txt(s.text, s.dialogue))
        for (h in seenHeads) texts.add(Txt(h, false))
        for (s in texts) {
            var prevStem: String? = null
            var prevLow = ""
            var prevEnd = -5
            var prevCap = false
            var prevAcro = false
            var wi = 0
            for (m in WORD.findAll(s.text)) {
                val orig = m.value
                val low = pack.lower(orig)
                val first = wi == 0
                wi++
                val cand = low.length >= 3 && low !in pack.stop && low !in pack.generic && !pack.months.matches(low)
                if (!cand) { prevStem = null; continue }
                val st = pack.stem(low)
                val acro = orig.length >= 2 && orig.all { it.isUpperCase() }
                val cap = pack.hasCase && pack.code != "de" && !first && orig[0].isUpperCase() && orig.drop(1).any { it.isLowerCase() }
                uTf[st] = (uTf[st] ?: 0) + 1
                val sm = uSurf.getOrPut(st) { HashMap() }
                sm[low] = (sm[low] ?: 0) + 1
                if (acro) uAcro[st] = (uAcro[st] ?: 0) + 1
                if (cap) uCap[st] = (uCap[st] ?: 0) + 1
                if (s.dialogue) uDlg[st] = (uDlg[st] ?: 0) + 1
                if (prevStem != null && m.range.first == prevEnd + 2 && s.text[prevEnd + 1] == ' ') {
                    val key = "$prevStem $st"
                    bTf[key] = (bTf[key] ?: 0) + 1
                    val bm = bSurf.getOrPut(key) { HashMap() }
                    val sf = "$prevLow $low"
                    bm[sf] = (bm[sf] ?: 0) + 1
                    if ((cap || acro) && (prevCap || prevAcro)) bProper[key] = (bProper[key] ?: 0) + 1
                    if (acro || prevAcro) bAcro[key] = (bAcro[key] ?: 0) + 1
                }
                prevStem = st; prevLow = low; prevEnd = m.range.last; prevCap = cap; prevAcro = acro
            }
        }

        val out = ArrayList<Kw>()
        for ((st, tf) in uTf) {
            val inHead = st in headStems
            if (tf < 2 && !inHead) continue
            val acro = (uAcro[st] ?: 0) * 2 >= tf
            val proper = tf >= 2 && (uCap[st] ?: 0).toDouble() / tf >= 0.5
            val inDef = st in defStems
            val best = pickSurface(uSurf[st]!!)
            var sc = 0.55 * ((weights[st] ?: 0.0) / maxW) + 0.1 * min(1.0, ln(1.0 + tf) / ln(6.0))
            if (inHead) sc += 0.8
            if (acro) sc += 0.6
            if (proper) sc += 0.45
            if (inDef) sc += 0.5
            if (best.length >= 8) sc += 0.12
            if (pack.isPast(best)) sc *= 0.3
            if (pack.code == "tr" && (best.endsWith("mak") || best.endsWith("mek"))) sc *= 0.3
            if (pack.code == "tr" && best.length >= 8 && TR_COP_END.matches(best)) sc *= 0.2
            if (pack.code == "fr" && FR_PART.matches(best) && !proper) sc *= 0.3
            if ((uDlg[st] ?: 0) * 2 > tf) sc *= 0.5
            if (sc < 0.3) continue
            out.add(Kw(st, titleWord(best, acro, pack), setOf(st), tf, false, sc))
        }
        for ((key, c) in bTf) {
            if (c < 2) continue
            val parts = key.split(' ')
            val t1 = uTf[parts[0]] ?: continue
            val t2 = uTf[parts[1]] ?: continue
            if (c.toDouble() / min(t1, t2) < 0.3) continue
            val w1 = (weights[parts[0]] ?: 0.0) / maxW
            val w2 = (weights[parts[1]] ?: 0.0) / maxW
            var sc = 0.15 + 0.45 * sqrt(w1 * w2) + 0.25 * min(1.0, ln(1.0 + c) / ln(6.0))
            if (headSets.any { parts[0] in it && parts[1] in it }) sc += 0.9
            if ((bProper[key] ?: 0) * 2 >= c) sc += 0.35
            if ((bAcro[key] ?: 0) > 0) sc += 0.3
            if (key in defPhrases) sc += 0.5
            val surfMap = bSurf[key]!!
            val okMap = if (pack.code == "tr") surfMap.filter { !TR_CASE_END.matches(it.key.substringAfter(' ')) } else surfMap
            if (okMap.isEmpty()) continue
            val sf = pickSurface(okMap).split(' ')
            val disp = sf.joinToString(" ") { w ->
                titleWord(w, (uAcro[pack.stem(w)] ?: 0) * 2 >= (uTf[pack.stem(w)] ?: 1), pack)
            }
            out.add(Kw(key, disp, parts.toSet(), c, true, sc))
        }
        out.sortByDescending { it.score }
        return out.take(40)
    }

    /** Özet seçildikten sonra: özette / başlıkta geçmeyen ve nadir kelimeleri ele, yinelenen kavramları birleştir. */
    private fun finalKeywords(cands: List<Kw>, chosen: Collection<Sent>, sents: List<Sent>, pack: LangPack): List<String> {
        val chosenStems = HashSet<String>()
        for (s in chosen) chosenStems.addAll(s.stemSet)
        val headStems = HashSet<String>()
        for (h in sents.mapNotNull { it.heading }.toSet()) headStems.addAll(stems(h, pack))
        val n = sents.size
        val limit = when {
            n < 10 -> 4
            n < 30 -> 6
            n < 80 -> 8
            n < 300 -> 9
            else -> 10
        }
        val ranked = ArrayList<Kw>()
        for (k in cands) {
            val inSum = k.stems.all { it in chosenStems }
            val inHead = k.stems.all { it in headStems }
            if (!(inSum || inHead || k.tf >= 3)) continue
            ranked.add(Kw(k.key, k.surface, k.stems, k.tf, k.phrase, k.score * (if (inSum || inHead) 1.2 else 0.8)))
        }
        ranked.sortByDescending { it.score }
        val floor = if (ranked.isEmpty()) 0.0 else ranked[0].score * 0.3
        val picked = ArrayList<Kw>()
        for (k in ranked) {
            if (picked.size >= limit) break
            if (k.score < floor) break
            if (picked.any { it.stems.containsAll(k.stems) }) continue
            if (k.phrase) picked.removeAll { !it.phrase && k.stems.containsAll(it.stems) }
            picked.add(k)
        }
        return picked.map { it.surface }.distinct()
    }
}
