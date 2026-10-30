package com.ozet.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ozet.app.data.HistoryItem
import com.ozet.app.data.HistoryStore
import com.ozet.app.data.sizeOf
import com.ozet.app.data.typeOf
import com.ozet.app.engine.Extractor
import com.ozet.app.engine.Length
import com.ozet.app.engine.Mode
import com.ozet.app.engine.Result
import com.ozet.app.engine.Summarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object Home : UiState
    data object History : UiState
    data object Settings : UiState
    data object Paste : UiState
    data object Loading : UiState
    data class Working(val secondsLeft: Int, val ready: Boolean) : UiState
    data class Done(val result: Result, val mode: Mode, val length: Length, val fileName: String) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val WAIT = 10
    }

    private val store = HistoryStore(app)
    private val settings = app.getSharedPreferences("ozet_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UiState>(UiState.Home)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _history = MutableStateFlow(store.load())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val _defaultLength = MutableStateFlow(readLength())
    val defaultLength: StateFlow<Length> = _defaultLength.asStateFlow()

    private var text = ""
    private var name = ""
    private var mode = Mode.AUTO
    private var length = _defaultLength.value
    private var job: Job? = null

    private fun readLength(): Length = try {
        Length.valueOf(settings.getString("length", "MEDIUM") ?: "MEDIUM")
    } catch (e: Exception) {
        Length.MEDIUM
    }

    fun goHome() {
        job?.cancel()
        _state.value = UiState.Home
    }

    fun showHistory() {
        job?.cancel()
        _state.value = UiState.History
    }

    fun showSettings() {
        _state.value = UiState.Settings
    }

    fun openPaste() {
        _state.value = UiState.Paste
    }

    fun setDefaultLength(l: Length) {
        settings.edit().putString("length", l.name).apply()
        _defaultLength.value = l
        length = l
    }

    fun deleteHistory(id: String) {
        _history.value = store.remove(id)
    }

    fun clearHistory() {
        _history.value = store.clear()
    }

    fun openUri(uri: Uri) {
        val ctx = getApplication<Application>()
        val n = Extractor.displayName(ctx, uri)
        start(n, typeOf(n), sizeOf(ctx, uri)) { Extractor.extract(ctx, uri) }
    }

    fun submitText(pasted: String) {
        val first = pasted.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        val label = if (first.length > 32) first.substring(0, 32) + "…" else first
        start(label.ifEmpty { "Yapıştırılan metin" }, "METIN", pasted.toByteArray().size.toLong()) { pasted }
    }

    fun openHistory(item: HistoryItem) {
        job?.cancel()
        name = item.name
        mode = Mode.AUTO
        _state.value = UiState.Loading
        job = viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val t = withContext(Dispatchers.IO) { store.readText(item.id) }
                        ?: error("Bu dosyanın kaydı bulunamadı.")
                    Pair(t, Summarizer.summarize(t, mode, length))
                }
            }
            r.onSuccess { p ->
                text = p.first
                _state.value = UiState.Done(p.second, mode, length, name)
            }.onFailure { e ->
                _state.value = UiState.Error(e.message ?: "Dosya açılamadı.")
            }
        }
    }

    fun setMode(m: Mode) = resummarize(m, length)

    fun setLength(l: Length) = resummarize(mode, l)

    private fun start(label: String, type: String, size: Long, load: suspend () -> String) {
        job?.cancel()
        name = label
        mode = Mode.AUTO
        _state.value = UiState.Working(WAIT, false)
        job = viewModelScope.launch {
            val work = async(Dispatchers.Default) {
                runCatching {
                    val t = load()
                    if (t.isBlank()) {
                        error("Dosyada okunabilir metin bulunamadı. Taranmış (resim) bir PDF olabilir.")
                    }
                    val res = Summarizer.summarize(t, mode, length)
                    if (res.sentenceCount == 0) error("Özetlenecek kadar metin bulunamadı.")
                    Pair(t, res)
                }
            }
            for (left in WAIT downTo 1) {
                _state.value = UiState.Working(left, work.isCompleted)
                if (work.isCompleted && work.await().isFailure) break
                delay(1000)
            }
            val outcome = work.await()
            outcome.onSuccess { pair ->
                text = pair.first
                val saved = withContext(Dispatchers.IO) { store.add(label, type, size, pair.first) }
                _history.value = saved
                _state.value = UiState.Done(pair.second, mode, length, name)
            }.onFailure { e ->
                _state.value = UiState.Error(e.message ?: "Dosya okunamadı.")
            }
        }
    }

    private fun resummarize(m: Mode, l: Length) {
        if (_state.value !is UiState.Done || text.isEmpty()) return
        mode = m
        length = l
        job?.cancel()
        job = viewModelScope.launch {
            val res = withContext(Dispatchers.Default) { Summarizer.summarize(text, m, l) }
            _state.value = UiState.Done(res, m, l, name)
        }
    }
}
