package com.ozet.app.engine

import java.util.Locale
import kotlin.math.abs
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

data class Section(val title: String?, val items: List<String>, val kind: SectionKind)

data class Result(
    val mode: Mode,
    val sections: List<Section>,
    val keywords: List<String>,
    val sentenceCount: Int,
    val wordCount: Int
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
    val lower: String
) {
    var base = 0.0
    var rank = 0.0
    var score = 0.0
}

/**
 * Yerel, yapay zekasiz ozetleyici.
 * Puanlama: TF-IDF + TextRank + konum + ipucu kelimeleri.
 * Sinav modu: tanim/ozellik/sayi iceren cumleler, basliklara gore gruplanir.
 * Hikaye modu: metin bolumlere ayrilir, her bolumden ozet cumlesi secilir;
 * olay ipuclari, yeni karakter girisi, tezat baglaclari ve hikaye egrisine gore donum noktalari bulunur.
 */
object Summarizer {
    private val TR = Locale("tr", "TR")
    private val WORD = Regex("\\p{L}+")
    private val WS = Regex("\\s+")
    private val PAGE_NUM = Regex("\\d{1,4}")
    private val MULTI_SPACE = Regex(" {2,}")
    private val HYPHEN_BREAK = Regex("(\\p{L})-\\n(\\p{L})")
    private val BULLET = Regex("^([•●▪◦■□▫‣⁃*]|-|\\d{1,2}[.)]|[a-zA-Z][.)])\\s+\\S.*")
    private val BULLET_MARK = Regex("^([•●▪◦■□▫‣⁃*]|-|\\d{1,2}[.)]|[a-zA-Z][.)])\\s+")
    private val NUMBERED_HEAD = Regex("^\\d+(\\.\\d+)*[.)]?\\s+\\p{Lu}")

    private val ABBR = setOf(
        "dr", "prof", "doç", "yrd", "av", "sn", "no", "bkz", "örn", "st", "mah", "cad", "sok",
        "apt", "tel", "müh", "uzm", "öğr", "gör", "alb", "gen", "bşk", "hz", "m.ö", "m.s", "a.ş",
        "ltd", "şti", "org", "çev", "ed", "yay", "krş", "mö", "ms"
    )

    private val STOP = setOf(
        "ve", "veya", "ile", "için", "ama", "fakat", "ancak", "gibi", "kadar", "daha", "çok", "bir", "bu", "şu",
        "da", "de", "ki", "mi", "mı", "mu", "mü", "ne", "ya", "ise", "her", "hem", "hiç", "bütün", "tüm",
        "olarak", "olan", "oldu", "olur", "olması", "olduğu", "olduğunu", "var", "yok", "ben", "sen", "biz", "siz",
        "onlar", "bunu", "buna", "bunun", "bunlar", "şunu", "onu", "ona", "onun", "kendi", "kendini", "sonra",
        "önce", "göre", "karşı", "dolayı", "üzere", "başka", "diye", "değil", "bile", "artık", "yani", "çünkü",
        "eğer", "hatta", "ayrıca", "oysa", "sadece", "yalnız", "iken", "idi", "imiş", "edilir", "edilen",
        "edilmiş", "yapılan", "yapılır", "yapar", "eder", "etti", "etmek", "yapmak", "olmak", "bulunan",
        "bulunur", "içinde", "üzerinde", "arasında", "tarafından", "sırasında", "nedeniyle", "ilgili", "bazı",
        "birçok", "herhangi", "hangi", "nasıl", "neden", "nerede", "kim", "şey", "zaman", "dedi", "diyor",
        "bana", "sana", "bize", "size", "beni", "seni", "bizi", "sizi", "onları", "benim", "senin", "bizim",
        "sizin", "onların", "the", "and", "for", "with", "that", "this", "are", "was", "were", "have"
    )

    private val DEF = Regex(
        "denir|denilir|adı verilir|adlandırılır|tanımlanır|tanımıdır|tanımı|demektir|anlamına gelir|anlamındadır|" +
            "olarak bilinir|ifade eder|özelliği|özellikleri|önemlidir|önem taşır|unutulmamalıdır|dikkat|" +
            "sonuç olarak|kısaca|özetle|başlıca|en önemli|amacı|görevi|nedeni|sebebi|formül|kural|ilke"
    )
    private val PAST = Regex("^\\p{L}{2,}([dt][ıiuü]|m[ıiuü]ş)(m|n|k|nız|niz|nuz|nüz|lar|ler)?$")

    private val STORY_CUE = Regex(
        "birdenbire|aniden|bir anda|o anda|tam o sırada|o sırada|sonunda|nihayet|meğer|işte o zaman|ilk kez|son kez|" +
            "kaçtı|kaçmak|öldü|öldür|ölüm|cinayet|kavga|savaş|saldır|yakalandı|yakaladı|itiraf|gerçek|sır|ihanet|" +
            "karar verdi|ayrıldı|evlen|doğdu|kayboldu|kayıp|buldu|bulundu|ortaya çık|anladı|fark etti|hapse|firar|" +
            "kurtul|vurdu|vuruldu|bağırdı|çığlık|yemin|veda|intikam"
    )
    private val CONTRAST_START =
        Regex("^(ancak|fakat|ama|birden|aniden|sonunda|nihayet|meğer|oysa|artık|ne var ki|tam o sırada)[\\s,]")
    private val NAME = Regex("(?<=[^.!?…\"“”«»—–:\\s]\\s)\\p{Lu}\\p{Ll}{2,}")

    // ------------------------------------------------------------------ giris noktasi

    fun summarize(raw: String, requested: Mode, length: Length): Result {
        val wordCount = raw.split(WS).count { it.isNotEmpty() }
        val sents = parse(raw)
        if (sents.isEmpty()) return Result(Mode.EXAM, emptyList(), emptyList(), 0, wordCount)
        val weights = computeBase(sents)
        val mode = if (requested == Mode.AUTO) detect(sents) else requested
        val sections = if (mode == Mode.STORY) storySections(sents, length) else examSections(sents, length)
        return Result(mode, sections, keywords(sents, weights), sents.size, wordCount)
    }

    // ------------------------------------------------------------------ ayristirma

    private fun parse(raw: String): List<Sent> {
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
        val buf = StringBuilder()

        fun addUnit(unit: String, bullet: Boolean, dialogue: Boolean) {
            val parts = if (bullet) listOf(unit) else splitSentences(unit)
            var first = true
            for (p in parts) {
                val t = p.trim()
                val words = t.count { it == ' ' } + 1
                if (t.length < (if (bullet) 12 else 25) || words < (if (bullet) 2 else 4)) continue
                val st = stems(t)
                if (st.size < 2) continue
                out.add(Sent(out.size, t, st, st.toHashSet(), heading, bullet, dialogue, first, t.lowercase(TR)))
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
                flush(); prevBlank = true; continue
            }
            if (line.startsWith("# ")) {
                flush(); heading = cleanHeading(line.substring(2)); prevBlank = false; continue
            }
            val canStart = prevBlank || buf.isEmpty() || buf.last() in ".!?…:\"”"
            when {
                isDialogueStart(line) -> { flush(); addUnit(line, false, true) }
                isBullet(line) -> { flush(); addUnit(line.replaceFirst(BULLET_MARK, ""), true, false) }
                isHeading(line, canStart) -> { flush(); heading = cleanHeading(line) }
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

    private fun isHeading(line: String, canStart: Boolean): Boolean {
        if (line.length > 80) return false
        val words = line.split(' ').size
        val last = line.last()
        if (NUMBERED_HEAD.containsMatchIn(line) && words <= 10 && last !in ".!?") return true
        val letters = line.filter { it.isLetter() }
        if (letters.length >= 4 && words <= 10 && letters.all { it.isUpperCase() }) return true
        if (last == ':' && words <= 8) return true
        if (canStart && words <= 6 && line[0].isUpperCase() && last !in ".!?,;:" && !line.contains(',')) return true
        return false
    }

    private fun cleanHeading(h: String): String {
        var t = h.trim().trimEnd(':').trim()
        val letters = t.filter { it.isLetter() }
        if (letters.length >= 4 && letters.all { it.isUpperCase() }) {
            t = t.lowercase(TR).split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> c.titlecase(TR) } }
        }
        return if (t.length > 80) t.substring(0, 80) else t
    }

    private fun splitSentences(p: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        val n = p.length
        while (i < n) {
            val c = p[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var j = i + 1
                while (j < n && (p[j] == '.' || p[j] == '!' || p[j] == '?' || p[j] == '”' || p[j] == '"' ||
                        p[j] == '\'' || p[j] == ')' || p[j] == '»' || p[j] == '’')
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
                            nx == '(' || nx == '—' || nx == '–' || nx == '-'
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
                        val word = p.substring(w + 1, i).lowercase(TR)
                        if (word.length == 1 || word in ABBR) ok = false
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

    private fun stems(text: String): List<String> {
        val res = ArrayList<String>()
        for (m in WORD.findAll(text.lowercase(TR))) {
            val w = m.value
            if (w.length < 3 || w in STOP) continue
            res.add(if (w.length > 5) w.substring(0, 5) else w)
        }
        return res
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
            val words = s.text.count { it == ' ' } + 1
            if (words > 40) v *= 0.7
            if (words < 6) v *= 0.5
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

    private fun redundant(s: Sent, chosen: List<Sent>): Boolean =
        chosen.any { jaccard(it.stemSet, s.stemSet) > 0.6 }

    private fun display(s: Sent): String {
        val t = s.text.replace(WS, " ").trim()
        if (t.length <= 360) return t
        val cut = t.lastIndexOf(' ', 357)
        return t.substring(0, if (cut > 200) cut else 357) + "…"
    }

    // ------------------------------------------------------------------ tur algilama

    private fun detect(sents: List<Sent>): Mode {
        val n = sents.size.toDouble()
        val bullet = sents.count { it.bullet } / n
        val dialog = sents.count { it.dialogue } / n
        val head = sents.mapNotNull { it.heading }.toSet().size / n
        val def = sents.count { DEF.containsMatchIn(it.lower) } / n
        val digit = sents.count { s -> s.text.any { ch -> ch.isDigit() } } / n
        var words = 0
        var past = 0
        for (s in sents.take(3000)) {
            for (m in WORD.findAll(s.lower)) {
                words++
                if (m.value.length >= 4 && PAST.matches(m.value)) past++
            }
        }
        val pastRate = if (words == 0) 0.0 else past.toDouble() / words
        val exam = 5 * def + 2.5 * bullet + 3 * head + 1.5 * digit
        val story = 4 * dialog + 7 * pastRate
        return if (story >= exam && story > 0.6) Mode.STORY else Mode.EXAM
    }

    // ------------------------------------------------------------------ sinav / ders notu

    private fun examSections(sents: List<Sent>, length: Length): List<Section> {
        for (s in sents) {
            var v = 0.5 * s.base + 0.5 * s.rank
            if (DEF.containsMatchIn(s.lower)) v += 0.25
            if (s.text.any { it.isDigit() }) v += 0.08
            if (s.bullet) v += 0.15
            if (s.paraStart) v += 0.08
            if (s.text.contains(':')) v += 0.05
            if (s.idx == 0) v += 0.1
            if (s.dialogue) v -= 0.1
            s.score = v
        }
        val n = sents.size
        val cfg = when (length) {
            Length.SHORT -> Triple(0.08, 6, 20)
            Length.MEDIUM -> Triple(0.15, 10, 40)
            Length.LONG -> Triple(0.25, 16, 80)
        }
        val target = min(n, (n * cfg.first).roundToInt().coerceIn(cfg.second, cfg.third))
        val chosen = ArrayList<Sent>()

        val groups = sents.filter { it.heading != null }.groupBy { it.heading }
        if (groups.size > 1) {
            val ordered = groups.values.filter { it.size >= 3 }
                .sortedByDescending { g -> g.sumOf { it.score } }
                .take(target / 2)
            for (g in ordered) {
                val best = g.maxByOrNull { it.score }
                if (best != null && !redundant(best, chosen)) chosen.add(best)
            }
        }
        for (s in sents.sortedByDescending { it.score }) {
            if (chosen.size >= target) break
            if (s in chosen || redundant(s, chosen)) continue
            chosen.add(s)
        }

        val fallback = if (groups.isEmpty()) "Önemli notlar" else "Genel notlar"
        return chosen.sortedBy { it.idx }
            .groupBy { it.heading }
            .map { (h, list) -> Section(h ?: fallback, list.map { display(it) }, SectionKind.BULLETS) }
    }

    // ------------------------------------------------------------------ hikaye / kitap

    private fun bump(pos: Double, center: Double, width: Double): Double {
        val x = (pos - center) / width
        return exp(-x * x)
    }

    private fun storySections(sents: List<Sent>, length: Length): List<Section> {
        val n = sents.size

        val nameFreq = HashMap<String, Int>()
        val namesOf = ArrayList<List<String>>(n)
        for (s in sents) {
            val ns = NAME.findAll(s.text).map { it.value.lowercase(TR) }.toList()
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
            if (STORY_CUE.containsMatchIn(s.lower)) v += 0.15
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
            for (c in STORY_CUE.findAll(s.lower)) {
                cues++
                if (cues >= 3) break
            }
            v += 0.25 * cues
            if (s.text.contains('!')) v += 0.1
            if (newChar[i]) v += 0.35
            if (CONTRAST_START.containsMatchIn(s.lower)) v += 0.25
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

        return listOf(
            Section("Özet", picks.sortedBy { it.idx }.map { display(it) }.distinct(), SectionKind.PARAGRAPHS),
            Section("Dönüm noktaları", tp.map { display(sents[it]) }, SectionKind.NUMBERED)
        )
    }

    // ------------------------------------------------------------------ anahtar kelimeler

    private fun keywords(sents: List<Sent>, weights: Map<String, Double>): List<String> {
        val surf = HashMap<String, HashMap<String, Int>>()
        for (s in sents.take(5000)) {
            for (m in WORD.findAll(s.lower)) {
                val w = m.value
                if (w.length < 4 || w in STOP) continue
                val st = if (w.length > 5) w.substring(0, 5) else w
                if ((weights[st] ?: 0.0) <= 0.0) continue
                val mp = surf.getOrPut(st) { HashMap() }
                mp[w] = (mp[w] ?: 0) + 1
            }
        }
        return surf.entries
            .filter { e -> e.value.values.sum() >= 2 }
            .sortedByDescending { e -> weights[e.key] ?: 0.0 }
            .take(8)
            .map { e ->
                val mx = e.value.values.maxOrNull() ?: 1
                val best = e.value.filter { it.value * 2 >= mx }.keys.minByOrNull { it.length } ?: e.key
                best.replaceFirstChar { c -> c.titlecase(TR) }
            }
            .distinct()
    }
}
