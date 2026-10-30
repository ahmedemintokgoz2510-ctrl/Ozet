package com.ozet.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdSize
import com.ozet.app.MainViewModel
import com.ozet.app.R
import com.ozet.app.UiState
import com.ozet.app.data.HistoryItem
import com.ozet.app.data.formatDate
import com.ozet.app.data.formatSize
import com.ozet.app.engine.Length
import com.ozet.app.engine.Mode
import com.ozet.app.engine.Result
import com.ozet.app.engine.SectionKind
import java.util.Locale

private val ALL_TYPES = arrayOf(
    "application/pdf",
    "text/plain",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
)
private val PDF_TYPES = arrayOf("application/pdf")
private val DOCX_TYPES = arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
private val TXT_TYPES = arrayOf("text/plain")

// ---------------------------------------------------------------- ana iskelet

@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val defLen by vm.defaultLength.collectAsStateWithLifecycle()
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
        containerColor = Pal.Bg,
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(Color.White)) {
                if (state is UiState.Home || state is UiState.History) {
                    NavBar(
                        homeSelected = state is UiState.Home,
                        onHome = vm::goHome,
                        onHistory = vm::showHistory
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.Line))
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                    AdBox(AdSize.BANNER, R.string.admob_banner_id)
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is UiState.Home -> HomeScreen(
                    history = history,
                    onPickAll = { picker.launch(ALL_TYPES) },
                    onPickPdf = { picker.launch(PDF_TYPES) },
                    onPickDocx = { picker.launch(DOCX_TYPES) },
                    onPickTxt = { picker.launch(TXT_TYPES) },
                    onPaste = vm::openPaste,
                    onSettings = vm::showSettings,
                    onOpen = vm::openHistory
                )
                is UiState.History -> HistoryScreen(history, vm::openHistory, vm::deleteHistory)
                is UiState.Settings -> SettingsScreen(
                    defLen = defLen,
                    historyCount = history.size,
                    onLength = vm::setDefaultLength,
                    onClear = vm::clearHistory,
                    onBack = vm::goHome
                )
                is UiState.Paste -> PasteScreen(onBack = vm::goHome, onSubmit = vm::submitText)
                is UiState.Loading -> LoadingScreen()
                is UiState.Working -> WorkingScreen(s)
                is UiState.Done -> ResultScreen(s, vm::setMode, vm::setLength, vm::goHome)
                is UiState.Error -> ErrorScreen(s.message, vm::goHome)
            }
        }
    }
}

// ---------------------------------------------------------------- ortak parcalar

private val R16 = RoundedCornerShape(16.dp)

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Pal.Red,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFF1C9C9),
            disabledContentColor = Color.White
        )
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Pal.Red),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Pal.Red)
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun TypeBadge(type: String, size: Dp) {
    val bg: Color
    val label: String
    when (type) {
        "PDF" -> { bg = Pal.Red; label = "PDF" }
        "DOCX" -> { bg = Color(0xFF2B6CD4); label = "W" }
        "TXT" -> { bg = Color(0xFF7A7F87); label = "TXT" }
        else -> { bg = Pal.Red; label = "Aa" }
    }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.3f).sp)
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(R.drawable.ic_arrow_back), "Geri", tint = Pal.Ink, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Pal.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NavItem(label: String, iconRes: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = if (selected) Pal.Red else Pal.Sub
    Column(
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(painterResource(iconRes), null, tint = c, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = c, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun NavBar(homeSelected: Boolean, onHome: () -> Unit, onHistory: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.Line))
        Row(Modifier.fillMaxWidth()) {
            NavItem("Ana Sayfa", R.drawable.ic_home, homeSelected, onHome, Modifier.weight(1f))
            NavItem("Geçmiş", R.drawable.ic_description, !homeSelected, onHistory, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    val typeLabel = if (item.type == "METIN") "Metin" else item.type
    val meta = buildString {
        append(typeLabel)
        if (item.sizeBytes > 0) append("  •  ").append(formatSize(item.sizeBytes))
        append("  •  ").append(formatDate(item.dateMillis))
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(R16).background(Color.White)
            .border(1.dp, Pal.Line, R16).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Pal.Line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) { TypeBadge(item.type, 36.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name, fontSize = 16.sp, color = Pal.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(meta, fontSize = 13.sp, color = Pal.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Pal.Red else Color.White)
            .border(1.dp, if (selected) Pal.Red else Pal.Line, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.White else Pal.Ink,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ---------------------------------------------------------------- ana sayfa

@Composable
private fun HomeScreen(
    history: List<HistoryItem>,
    onPickAll: () -> Unit,
    onPickPdf: () -> Unit,
    onPickDocx: () -> Unit,
    onPickTxt: () -> Unit,
    onPaste: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (HistoryItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(62.dp).clip(RoundedCornerShape(18.dp)).background(Pal.Red),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painterResource(R.drawable.ic_description), null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Özetle", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                    Text("Uzun metinleri, kısa ve öz hale getir.", fontSize = 14.sp, color = Pal.Sub)
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painterResource(R.drawable.ic_settings), "Ayarlar", tint = Pal.Ink, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(Pal.RedLight, Pal.RedDark)))
                    .clickable(onClick = onPickAll)
            ) {
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.07f),
                        radius = size.height * 0.9f,
                        center = Offset(size.width * 0.97f, size.height * 1.15f)
                    )
                }
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(62.dp).border(3.dp, Color.White, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painterResource(R.drawable.ic_add), null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Dosya Seç", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "PDF, DOCX, TXT veya metin kopyala-yapıştır ile özet oluştur.",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painterResource(R.drawable.ic_arrow_forward), null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("PDF", "PDF", "Dosya seç", onPickPdf, Modifier.weight(1f))
                Tile("DOCX", "DOCX", "Dosya seç", onPickDocx, Modifier.weight(1f))
                Tile("TXT", "TXT", "Dosya seç", onPickTxt, Modifier.weight(1f))
                Tile("METIN", "Metin", "Yapıştır", onPaste, Modifier.weight(1f))
            }
            Spacer(Modifier.height(28.dp))
            Text("Son Dosyalar", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
            Spacer(Modifier.height(12.dp))
        }
        if (history.isEmpty()) {
            item {
                Text(
                    "Henüz dosya yok. İlk dosyanı seç, burada görünsün.",
                    color = Pal.Sub,
                    fontSize = 14.sp
                )
            }
        } else {
            items(history.take(5), key = { it.id }) { h ->
                HistoryRow(h, onClick = { onOpen(h) }) {
                    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = Pal.Sub, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun Tile(type: String, label: String, sub: String, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.clip(R16).background(Color.White).border(1.dp, Pal.Line, R16)
            .clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TypeBadge(type, 44.dp)
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Pal.Ink, maxLines = 1)
        Text(sub, fontSize = 11.sp, color = Pal.Sub, maxLines = 1)
    }
}

// ---------------------------------------------------------------- gecmis

@Composable
private fun HistoryScreen(history: List<HistoryItem>, onOpen: (HistoryItem) -> Unit, onDelete: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)
    ) {
        item {
            Text("Geçmiş", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
            Spacer(Modifier.height(16.dp))
        }
        if (history.isEmpty()) {
            item { Text("Geçmişte dosya yok.", color = Pal.Sub, fontSize = 14.sp) }
        } else {
            items(history, key = { it.id }) { h ->
                HistoryRow(h, onClick = { onOpen(h) }) {
                    Text(
                        "Sil",
                        color = Pal.Red,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { onDelete(h.id) }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- ayarlar

@Composable
private fun SettingsScreen(
    defLen: Length,
    historyCount: Int,
    onLength: (Length) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Ayarlar", onBack)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().clip(R16).background(Color.White).border(1.dp, Pal.Line, R16).padding(16.dp)) {
                Text("Varsayılan özet uzunluğu", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pal.Ink)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Kısa", defLen == Length.SHORT, { onLength(Length.SHORT) }, Modifier.weight(1f))
                    Choice("Orta", defLen == Length.MEDIUM, { onLength(Length.MEDIUM) }, Modifier.weight(1f))
                    Choice("Uzun", defLen == Length.LONG, { onLength(Length.LONG) }, Modifier.weight(1f))
                }
            }
            Column(Modifier.fillMaxWidth().clip(R16).background(Color.White).border(1.dp, Pal.Line, R16).padding(16.dp)) {
                Text("Geçmiş", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pal.Ink)
                Spacer(Modifier.height(4.dp))
                Text("Kayıtlı dosya sayısı: $historyCount", fontSize = 13.sp, color = Pal.Sub)
                Spacer(Modifier.height(12.dp))
                SecondaryButton("Geçmişi temizle", {
                    onClear()
                    Toast.makeText(ctx, "Geçmiş temizlendi", Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth())
            }
            Column(Modifier.fillMaxWidth().clip(R16).background(Color.White).border(1.dp, Pal.Line, R16).padding(16.dp)) {
                Text("Hakkında", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pal.Ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Özetle 1.0. Özetleme telefonunda yapılır, dosyaların hiçbir yere gönderilmez.",
                    fontSize = 13.sp,
                    color = Pal.Sub
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- yapistir, bekleme, hata

@Composable
private fun PasteScreen(onBack: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val enough = text.trim().length >= 150
    Column(Modifier.fillMaxSize()) {
        TopBar("Metni yapıştır", onBack)
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("Metni buraya yapıştır") }
            )
            Spacer(Modifier.height(8.dp))
            if (text.isNotEmpty() && !enough) {
                Text("Özetlemek için en az 150 karakter gerekli.", fontSize = 13.sp, color = Pal.Sub)
                Spacer(Modifier.height(8.dp))
            }
            PrimaryButton("Özetle", { onSubmit(text) }, Modifier.fillMaxWidth(), enabled = enough)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Pal.Red)
        Spacer(Modifier.height(16.dp))
        Text("Açılıyor", color = Pal.Sub)
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
        Text("Dosyan hazırlanıyor", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            if (s.ready) "Özet hazır. ${s.secondsLeft} sn sonra açılacak."
            else "Metin okunuyor ve en önemli yerler seçiliyor. ${s.secondsLeft} sn",
            textAlign = TextAlign.Center,
            color = Pal.Sub
        )
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            progress = { (total - s.secondsLeft + 1f) / total },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Pal.Red,
            trackColor = Pal.RedSoft
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
        Text("Özetlenemedi", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        Spacer(Modifier.height(8.dp))
        Text(message, textAlign = TextAlign.Center, color = Pal.Sub)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Geri dön", onBack, Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- sonuc

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

    Column(Modifier.fillMaxSize()) {
        TopBar(s.fileName, onNew)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val kind = when {
                    s.mode != Mode.AUTO -> ""
                    r.mode == Mode.STORY -> "Algılanan tür: hikaye / kitap. "
                    else -> "Algılanan tür: ders / sınav notu. "
                }
                Text(kind + "${r.wordCount} kelime tarandı.", fontSize = 13.sp, color = Pal.Sub)
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
                Column(
                    Modifier.fillMaxWidth().clip(R16).background(Color.White).border(1.dp, Pal.Line, R16).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    sec.title?.let {
                        Text(it, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Pal.Red)
                    }
                    sec.items.forEachIndexed { i, t ->
                        when (sec.kind) {
                            SectionKind.PARAGRAPHS -> Text(t, fontSize = 15.sp, color = Pal.Ink)
                            else -> Row {
                                Text(
                                    if (sec.kind == SectionKind.NUMBERED) "${i + 1}." else "•",
                                    modifier = Modifier.width(26.dp),
                                    color = Pal.Red,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(t, fontSize = 15.sp, color = Pal.Ink)
                            }
                        }
                    }
                }
            }
            if (r.keywords.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().clip(R16).background(Color.White).border(1.dp, Pal.Line, R16).padding(16.dp)) {
                        Text("Anahtar kelimeler", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Pal.Red)
                        Spacer(Modifier.height(6.dp))
                        Text(r.keywords.joinToString(", "), fontSize = 15.sp, color = Pal.Ink)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Kopyala", {
                        clipboard.setText(AnnotatedString(out))
                        Toast.makeText(ctx, "Kopyalandı", Toast.LENGTH_SHORT).show()
                    }, Modifier.weight(1f))
                    SecondaryButton("Paylaş", {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, out)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Paylaş"))
                    }, Modifier.weight(1f))
                    PrimaryButton("Yeni", onNew, Modifier.weight(1f))
                }
            }
        }
    }
}
