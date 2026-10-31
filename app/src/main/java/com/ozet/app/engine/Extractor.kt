package com.ozet.app.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

enum class Err { NO_TEXT, TOO_SHORT, ENCRYPTED, NOT_FOUND, OPEN, READ }

class AppError(val kind: Err) : Exception()

object Extractor {
    private const val MAX_CHARS = 4_000_000

    fun displayName(context: Context, uri: Uri, fallback: String = "File"): String {
        var name: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (e: Exception) {
        }
        return name ?: uri.lastPathSegment ?: fallback
    }

    suspend fun extract(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val name = displayName(context, uri).lowercase()
        val mime = context.contentResolver.getType(uri) ?: ""
        val text = when {
            mime == "application/pdf" || name.endsWith(".pdf") -> readPdf(context, uri)
            name.endsWith(".docx") || mime.contains("wordprocessingml") -> readDocx(context, uri)
            else -> readTxt(context, uri)
        }
        if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
    }

    private fun readTxt(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        var s = String(bytes, Charsets.UTF_8)
        if (s.count { it == '\uFFFD' } > 3) s = String(bytes, Charset.forName("windows-1254"))
        return s.removePrefix("\uFEFF")
    }

    private fun readPdf(context: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        return input.use { ins ->
            PDDocument.load(ins).use { doc ->
                if (doc.isEncrypted) throw AppError(Err.ENCRYPTED)
                PDFTextStripper().getText(doc)
            }
        }
    }

    private fun readDocx(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        var result = ""
        input.use { raw ->
            ZipInputStream(raw.buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (e.name == "word/document.xml") {
                        result = parseDocx(zis)
                        break
                    }
                    e = zis.nextEntry
                }
            }
        }
        return result
    }

    private fun parseDocx(input: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val out = StringBuilder()
        val para = StringBuilder()
        var inT = false
        var inPPr = false
        var isHead = false
        var isList = false
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "p" -> { para.setLength(0); isHead = false; isList = false }
                    "pPr" -> inPPr = true
                    "pStyle" -> {
                        val v = (parser.getAttributeValue(null, "w:val") ?: parser.getAttributeValue(null, "val") ?: "")
                            .lowercase()
                        if (v.contains("heading") || v.contains("title") || v.contains("başlık") || v.contains("baslik")) {
                            isHead = true
                        }
                    }
                    "numPr" -> isList = true
                    "t" -> inT = true
                    "tab", "br" -> if (!inPPr) para.append(' ')
                }
                XmlPullParser.TEXT -> if (inT) para.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "t" -> inT = false
                    "pPr" -> inPPr = false
                    "p" -> {
                        val t = para.toString().trim()
                        if (t.isNotEmpty()) {
                            out.append(if (isHead) "# " else if (isList) "• " else "")
                            out.append(t).append("\n\n")
                        }
                        para.setLength(0)
                    }
                }
            }
            ev = parser.next()
        }
        return out.toString()
    }
}
