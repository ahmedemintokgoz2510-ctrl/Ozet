package com.ozet.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    data object Paste : UiState
    data class Working(val secondsLeft: Int, val ready: Boolean) : UiState
    data class Done(val result: Result, val mode: Mode, val length: Length, val fileName: String) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val WAIT = 10
    }

    private val _state = MutableStateFlow<UiState>(UiState.Home)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var text = ""
    private var name = ""
    private var mode = Mode.AUTO
    private var length = Length.MEDIUM
    private var job: Job? = null

    fun openPaste() {
        _state.value = UiState.Paste
    }

    fun goHome() {
        job?.cancel()
        _state.value = UiState.Home
    }

    fun openUri(uri: Uri) {
        val ctx = getApplication<Application>()
        start(Extractor.displayName(ctx, uri)) { Extractor.extract(ctx, uri) }
    }

    fun submitText(pasted: String) {
        start("Yapıştırılan metin") { pasted }
    }

    fun setMode(m: Mode) = resummarize(m, length)

    fun setLength(l: Length) = resummarize(mode, l)

    private fun start(label: String, load: suspend () -> String) {
        job?.cancel()
        name = label
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
