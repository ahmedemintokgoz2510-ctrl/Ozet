package com.ozet.app.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Özet belgenin dilinden arayüz diline cihaz üzerinde (ML Kit, ücretsiz, API anahtarsız) çevrilir.
 * Dil paketi yalnızca Wi-Fi ile indirilir. Çeviri yapılamazsa özet belgenin kendi dilinde kalır (uygulama bozulmaz).
 */
object Translate {
    private val cache = HashMap<String, Translator>()
    private val SUPPORTED = setOf("tr", "en", "de", "es", "ar", "fr")

    private fun code(c: String): String = when (c) {
        "tr" -> TranslateLanguage.TURKISH
        "en" -> TranslateLanguage.ENGLISH
        "de" -> TranslateLanguage.GERMAN
        "es" -> TranslateLanguage.SPANISH
        "ar" -> TranslateLanguage.ARABIC
        else -> TranslateLanguage.FRENCH
    }

    private fun client(src: String, dst: String): Translator = synchronized(cache) {
        cache.getOrPut("$src>$dst") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(code(src)).setTargetLanguage(code(dst)).build()
            )
        }
    }

    private val wifi get() = DownloadConditions.Builder().requireWifi().build()

    /** Dil seçilince hedef dilin çeviri paketini (Wi-Fi varsa) arka planda indirmeye başlar. */
    fun prefetch(target: String) {
        if (target == "en") return
        try {
            client("en", target).downloadModelIfNeeded(wifi)
        } catch (e: Exception) {
        }
    }

    private suspend fun download(t: Translator): Boolean = suspendCancellableCoroutine<Boolean> { c ->
        t.downloadModelIfNeeded(wifi)
            .addOnSuccessListener { if (c.isActive) c.resume(true) }
            .addOnFailureListener { if (c.isActive) c.resume(false) }
    }

    private suspend fun one(t: Translator, s: String): String? = suspendCancellableCoroutine<String?> { c ->
        t.translate(s)
            .addOnSuccessListener { r -> if (c.isActive) c.resume(r) }
            .addOnFailureListener { if (c.isActive) c.resume(null) }
    }

    suspend fun translate(res: Result, target: String): Result {
        if (res.srcLang == target) return res
        if (res.srcLang !in SUPPORTED || target !in SUPPORTED || res.sections.isEmpty()) return res
        return try {
            val t = client(res.srcLang, target)
            val ready = withTimeoutOrNull(25_000L) { download(t) } ?: false
            if (!ready) return res.copy(translateFailed = true)
            val out = withTimeoutOrNull(30_000L) {
                var failed = false
                suspend fun tr(s: String): String {
                    val r = one(t, s)
                    if (r == null) failed = true
                    return r ?: s
                }
                val secs = res.sections.map { sec ->
                    Section(sec.title?.let { tr(it) }, sec.items.map { tr(it) }, sec.kind, sec.key)
                }
                val kws = res.keywords.map { k -> if (k.length > 1 && k == k.uppercase()) k else tr(k) }
                if (failed) null else Pair(secs, kws)
            }
            if (out == null) res.copy(translateFailed = true)
            else res.copy(sections = out.first, keywords = out.second, translated = true)
        } catch (e: Exception) {
            res.copy(translateFailed = true)
        }
    }
}
