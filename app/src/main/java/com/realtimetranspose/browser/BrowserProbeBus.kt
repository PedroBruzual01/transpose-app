package com.realtimetranspose.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase B, Fase 0 spike: does hooking YouTube's `<video>` element into the Web
 * Audio API (inside our own WebView) actually deliver real samples, or does
 * the browser mute it for CORS/taint reasons the way native playback capture
 * got muted by Spotify in Phase 1? [TransposeJsBridge] is the only writer;
 * the UI just observes.
 */
data class BrowserProbeState(
    val hookResult: String? = null,
    val lastLevel: Float = 0f,
    val audioContextState: String? = null,
    val sampleCount: Int = 0,
)

object BrowserProbeBus {
    private val _state = MutableStateFlow(BrowserProbeState())
    val state: StateFlow<BrowserProbeState> = _state

    fun reportHookResult(result: String) {
        _state.value = _state.value.copy(hookResult = result)
    }

    fun reportLevel(level: Float, contextState: String) {
        val current = _state.value
        _state.value = current.copy(
            lastLevel = level,
            audioContextState = contextState,
            sampleCount = current.sampleCount + 1,
        )
    }

    fun reset() {
        _state.value = BrowserProbeState()
    }
}
