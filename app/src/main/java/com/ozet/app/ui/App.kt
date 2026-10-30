package com.ozet.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdSize
import com.ozet.app.MainViewModel
import com.ozet.app.R
import com.ozet.app.UiState
import com.ozet.app.engine.Length
import com.ozet.app.engine.Mode
import com.ozet.app.engine.Result
import com.ozet.app.engine.SectionKind
import java.util.Locale

private val DOC_TYPES = arrayOf(
    "application/pdf",
    "text/plain",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
)

@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    val working = state is UiState.Working

    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = state !is UiState.Home) { vm.goHome() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openUri(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                AdBox(AdSize.BANNER, R.string.admob_banner_id)
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is UiState.Home -> HomeScreen(onPick = { picker.launch(DOC_TYPES) }, onPaste = vm::openPaste)
                is UiState.Paste -> PasteScreen(onBack = vm::goHome, onSubmit = vm::submitText)
                is UiState.Working -> WorkingScreen(s)
                is UiState.Done -> ResultScreen(s, vm::setMode, vm::setLength, vm::goHome)
                is UiState.Error -> ErrorScreen(s.message, vm::goHome)
            }
        }
    }
}

@Composable
private fun HomeScreen(onPick: () -> Unit, onPaste: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Özet",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "PDF, Word veya metin dosyandaki en önemli yerleri çıkarır. Ders notlarında kavramları ve tanımları, " +
                "kitap ve hikayelerde olay örgüsünü ve dönüm noktalarını bulur.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Dosya seç") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Metin yapıştır")
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Özetleme telefonunda yapılır. Dosyan hiçbir yere gönderilmez.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PasteScreen(onBack: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val enough = text.trim().length >= 150
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Metni yapıştır", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = { Text("Metni buraya yapıştır") }
        )
        Spacer(Modifier.height(8.dp))
        if (text.isNotEmpty() && !enough) {
            Text(
                "Özetlemek için en az 150 karakter gerekli.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Geri") }
            Button(onClick = { onSubmit(text) }, enabled = enough, modifier = Modifier.weight(1f)) {
                Text("Özetle")
            }
        }
    }
}

@Composable
private fun WorkingScreen(s: UiState.Working) {
    val total = MainViewModel.WAIT
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Dosyan hazırlanıyor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (s.ready) "Özet hazır. ${s.secondsLeft} sn sonra açılacak."
            else "Metin okunuyor ve en önemli yerler seçiliyor. ${s.secondsLeft} sn",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            progress = { (total - s.secondsLeft + 1f) / total },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        AdBox(AdSize.MEDIUM_RECTANGLE, R.string.admob_banner_id)
    }
}

@Composable
private fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Özetlenemedi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Geri dön") }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

private fun plainText(r: Result, title: String): String = buildString {
    appendLine(title)
    appendLine()
    for (sec in r.sections) {
        if (sec.items.isEmpty()) continue
        sec.title?.let { appendLine(it.uppercase(Locale("tr", "TR"))) }
        sec.items.forEachIndexed { i, t ->
            when (sec.kind) {
                SectionKind.BULLETS -> appendLine("- $t")
                SectionKind.NUMBERED -> appendLine("${i + 1}. $t")
                SectionKind.PARAGRAPHS -> {
                    appendLine(t)
                    appendLine()
                }
            }
        }
        appendLine()
    }
    if (r.keywords.isNotEmpty()) appendLine("Anahtar kelimeler: " + r.keywords.joinToString(", "))
}

@Composable
private fun ResultScreen(
    s: UiState.Done,
    onMode: (Mode) -> Unit,
    onLength: (Length) -> Unit,
    onNew: () -> Unit
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val out = remember(s.result, s.fileName) { plainText(s.result, s.fileName) }
    val r = s.result

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(s.fileName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            val kind = when {
                s.mode != Mode.AUTO -> ""
                r.mode == Mode.STORY -> "Algılanan tür: hikaye / kitap. "
                else -> "Algılanan tür: ders / sınav notu. "
            }
            Text(
                kind + "${r.wordCount} kelime tarandı.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Otomatik", s.mode == Mode.AUTO, { onMode(Mode.AUTO) }, Modifier.weight(1f))
                Choice("Ders / sınav", s.mode == Mode.EXAM, { onMode(Mode.EXAM) }, Modifier.weight(1f))
                Choice("Hikaye / kitap", s.mode == Mode.STORY, { onMode(Mode.STORY) }, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Kısa", s.length == Length.SHORT, { onLength(Length.SHORT) }, Modifier.weight(1f))
                Choice("Orta", s.length == Length.MEDIUM, { onLength(Length.MEDIUM) }, Modifier.weight(1f))
                Choice("Uzun", s.length == Length.LONG, { onLength(Length.LONG) }, Modifier.weight(1f))
            }
        }
        items(r.sections.filter { it.items.isNotEmpty() }) { sec ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sec.title?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    sec.items.forEachIndexed { i, t ->
                        when (sec.kind) {
                            SectionKind.PARAGRAPHS -> Text(t, style = MaterialTheme.typography.bodyMedium)
                            else -> Row {
                                Text(
                                    if (sec.kind == SectionKind.NUMBERED) "${i + 1}." else "•",
                                    modifier = Modifier.width(26.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(t, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        if (r.keywords.isNotEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            "Anahtar kelimeler",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(r.keywords.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(out))
                        Toast.makeText(ctx, "Kopyalandı", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Kopyala") }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, out)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Paylaş"))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Paylaş") }
                Button(onClick = onNew, modifier = Modifier.weight(1f)) { Text("Yeni") }
            }
        }
    }
}
