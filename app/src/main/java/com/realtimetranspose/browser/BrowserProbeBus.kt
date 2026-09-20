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
    // Ad-block event tag -> occurrence count, e.g. "prune:adPlacements" -> 3.
    // The acceptance signal for the ad-block script: counts rising means it's
    // actually stripping ad data, not just "I didn't happen to see an ad".
    val adBlockEvents: Map<String, Int> = emptyMap(),
    // Play/pause state of the currently hooked <video> — lets BrowserScreen
    // pause TransposePlayer's file playback the instant YouTube starts, so
    // both audio sources are never audible at once.
    val videoPlaying: Boolean = false,
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

    fun reportAdBlockEvent(tag: String) {
        val current = _state.value
        val counts = current.adBlockEvents.toMutableMap()
        counts[tag] = (counts[tag] ?: 0) + 1
        _state.value = current.copy(adBlockEvents = counts)
    }

    fun reportVideoPlaybackState(isPlaying: Boolean) {
        _state.value = _state.value.copy(videoPlaying = isPlaying)
    }

    fun reset() {
        _state.value = BrowserProbeState()
    }
}
