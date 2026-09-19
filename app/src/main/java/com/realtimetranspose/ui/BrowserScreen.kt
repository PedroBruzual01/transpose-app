package com.realtimetranspose.ui

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.realtimetranspose.BuildConfig
import com.realtimetranspose.browser.AD_BLOCK_SCRIPT
import com.realtimetranspose.browser.BrowserProbeBus
import com.realtimetranspose.browser.TransposeJsBridge
import java.util.Locale

/**
 * Phase B (browser mode): hooks YouTube's own `<video>` element into a real
 * Web Audio pitch-shift graph — confirmed viable in the Fase 0 spike
 * (docs/specs/2026-09-19-transpose-clone-design.md): YouTube's MSE-backed
 * video is not CORS-tainted inside our own WebView, so this is a clean
 * replacement of the audio, not an overlay (unlike the native-capture
 * approach that got blocked by Spotify in Phase 1).
 *
 * Pitch engine: `@soundtouchjs/core` (MPL-2.0), bundled ourselves (esbuild,
 * see docs/specs) into a dependency-free IIFE at
 * assets/soundtouch-scriptprocessor.js, driven through a plain
 * `ScriptProcessorNode` rather than `@soundtouchjs/audio-worklet`'s
 * `AudioWorkletNode`. That's deliberate: YouTube's CSP
 * (`require-trusted-types-for 'script'`, `script-src ... 'strict-dynamic'`)
 * blocks `audioWorklet.addModule()` from loading a blob: URL module —
 * `ScriptProcessorNode` needs no separate module fetch at all, so it never
 * hits that wall. It's deprecated and runs on the main thread instead of a
 * dedicated audio-rendering thread, but every current browser still
 * supports it, which a CSP-blocked AudioWorkletNode does not help with.
 *
 * Tempo/speed is handled separately, natively: `video.playbackRate`, which
 * Chromium keeps pitch-preserving by default — no DSP needed for that half,
 * and it composes cleanly with our independent pitch shift on top.
 *
 * Graph per hooked video:
 *   MediaElementAudioSourceNode -> ScriptProcessorNode (pitch shift) -> AnalyserNode -> destination
 *
 * Native controls reach the page via `window.TransposeControl.set*()`,
 * called through WebView.evaluateJavascript() from [BrowserScreen]'s
 * `AndroidView` update block.
 */
private fun buildHookScript(engineSourceBase64: String): String = """
(function() {
  if (window.__transposeHooked) return;
  window.__transposeHooked = true;

  if (!window.TransposeSoundTouch) {
    try {
      // YouTube's CSP includes 'unsafe-eval', but also
      // `require-trusted-types-for 'script'`, which still requires eval's
      // argument to be a TrustedScript (not a raw string) — same story as
      // the worklet blob: URL needing a TrustedScriptURL before. Same
      // permissive policy pattern: no `trusted-types <allowed names>`
      // directive restricts which policy names may be created.
      var src = atob("$engineSourceBase64");
      if (window.trustedTypes && window.trustedTypes.createPolicy) {
        var evalPolicy = window.trustedTypes.createPolicy('transpose-eval', {
          createScript: function(s) { return s; }
        });
        (0, eval)(evalPolicy.createScript(src));
      } else {
        (0, eval)(src);
      }
    } catch (e) {
      window.TransposeBridge && window.TransposeBridge.onHookResult('EVAL ERROR ' + e.name + ': ' + e.message);
    }
  }

  // Last known slider values, applied automatically to whatever video becomes
  // active next (autoplay/Shorts-swipe switches videos without the user
  // touching a slider, so the new video must not silently reset to 0/1x).
  window.__transposeLastPitch = window.__transposeLastPitch || 0;
  window.__transposeLastTempo = window.__transposeLastTempo || 1;

  window.TransposeControl = {
    setPitchSemitones: function(v) {
      window.__transposeLastPitch = v;
      if (window.__transposePitchNode) window.__transposePitchNode.setPitchSemitones(v);
    },
    setTempo: function(v) {
      window.__transposeLastTempo = v;
      if (window.__transposeVideo) window.__transposeVideo.playbackRate = v;
    }
  };

  // YouTube is a SPA: video-to-video navigation, autoplay, and Shorts swipes
  // don't necessarily reload the page or even replace the <video> element —
  // sometimes it's the same element with a new source, sometimes (Shorts
  // especially, which preloads several <video> elements at once for
  // adjacent reels) it's a different element entirely. `activateVideo`
  // handles both: no-op if it's the element we're already hooked to, cheap
  // reconnect (no new MediaElementAudioSourceNode — only one is ever allowed
  // per element) if we've seen this exact element before, full hook if not.
  function activateVideo(video) {
    if (!video || video === window.__transposeVideo) return;

    var previous = window.__transposePitchNode;
    if (previous) previous.node.disconnect();

    video.preservesPitch = true;
    window.__transposeVideo = video;
    video.playbackRate = window.__transposeLastTempo;

    try {
      var ctx = window.__transposeCtx || (window.__transposeCtx = new (window.AudioContext || window.webkitAudioContext)());

      if (video.__transposePitchShift) {
        // Re-activating an element we hooked before (e.g. swiped back to a
        // previous Short) — just reconnect its existing chain.
        var existing = video.__transposePitchShift;
        existing.node.connect(existing.analyser);
        existing.node.connect(ctx.destination);
        window.__transposePitchNode = existing;
        existing.setPitchSemitones(window.__transposeLastPitch);
        window.TransposeBridge && window.TransposeBridge.onHookResult('OK (reattached) ctxState=' + ctx.state);
        return;
      }

      var source = ctx.createMediaElementSource(video);
      var pitchShift = window.TransposeSoundTouch.createPitchShiftNode(ctx, 4096);
      var analyser = ctx.createAnalyser();
      analyser.fftSize = 2048;

      source.connect(pitchShift.node);
      pitchShift.node.connect(analyser);
      pitchShift.node.connect(ctx.destination);
      pitchShift.analyser = analyser;
      pitchShift.setPitchSemitones(window.__transposeLastPitch);

      video.__transposePitchShift = pitchShift;
      window.__transposePitchNode = pitchShift;

      window.TransposeBridge && window.TransposeBridge.onHookResult('OK ctxState=' + ctx.state);

      var data = new Uint8Array(analyser.frequencyBinCount);
      setInterval(function() {
        if (window.__transposeVideo !== video) return; // this video is no longer active; stop reporting for it
        analyser.getByteTimeDomainData(data);
        var sum = 0;
        for (var i = 0; i < data.length; i++) {
          var v = (data[i] - 128) / 128;
          sum += v * v;
        }
        var rms = Math.sqrt(sum / data.length);
        if (window.TransposeBridge) window.TransposeBridge.onLevel(rms.toFixed(4), ctx.state);
      }, 1000);
    } catch (e) {
      window.TransposeBridge && window.TransposeBridge.onHookResult('ERROR ' + e.name + ': ' + e.message);
    }
  }

  // Prefer a video that's actually playing (the "active" one when several
  // are present, as YouTube does for Shorts preloading); fall back to the
  // first video with any metadata loaded so we still hook something before
  // playback starts.
  function findActiveVideo() {
    var videos = document.querySelectorAll('video');
    var fallback = null;
    for (var i = 0; i < videos.length; i++) {
      var v = videos[i];
      if (!v.paused) return v;
      if (!fallback && v.readyState > 0) fallback = v;
    }
    return fallback || videos[0] || null;
  }

  function scan() {
    activateVideo(findActiveVideo());
  }

  // Three independent triggers, since no single one is reliably fired for
  // every way YouTube can switch videos:
  // 1. yt-navigate-finish — YouTube's own SPA-navigation-complete event,
  //    fired on document; precise and immediate when it fires.
  var scanDebounceTimer = null;
  function debouncedScan() {
    if (scanDebounceTimer) clearTimeout(scanDebounceTimer);
    scanDebounceTimer = setTimeout(scan, 200);
  }
  document.addEventListener('yt-navigate-finish', scan, true);
  // 2. 'play' events (capture phase, so it fires for any <video>, not just
  //    ones already known to us) — catches Shorts swipes and similar cases
  //    that may not dispatch yt-navigate-finish.
  document.addEventListener('play', function(e) {
    if (e.target && e.target.tagName === 'VIDEO') scan();
  }, true);
  // 3. MutationObserver, debounced — a fallback net for anything the two
  //    event-based triggers above miss.
  new MutationObserver(debouncedScan).observe(document.documentElement, {childList: true, subtree: true});
  // 4. Periodic safety-net poll, in case all of the above miss a transition.
  setInterval(scan, 1500);

  scan();
})();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen() {
    val probeState by BrowserProbeBus.state.collectAsState()
    val context = LocalContext.current

    var pitchSemitones by remember { mutableFloatStateOf(0f) }
    var tempo by remember { mutableFloatStateOf(1f) }

    val engineSourceBase64 = remember {
        val bytes = context.assets.open("soundtouch-scriptprocessor.js").use { it.readBytes() }
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    val hookScript = remember(engineSourceBase64) { buildHookScript(engineSourceBase64) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Hook: ${probeState.hookResult ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text(
                String.format(
                    Locale.US,
                    "Nivel: %.4f (ctx=%s, muestras=%d)",
                    probeState.lastLevel,
                    probeState.audioContextState ?: "-",
                    probeState.sampleCount,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (probeState.adBlockEvents.isEmpty()) {
                    "AdBlock: sin eventos todavía"
                } else {
                    "AdBlock: " + probeState.adBlockEvents.entries.joinToString(", ") { (tag, count) -> "$tag×$count" }
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Pitch: ${formatSemitones(pitchSemitones)}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = pitchSemitones,
                valueRange = -12f..12f,
                steps = 23,
                onValueChange = { pitchSemitones = it },
            )

            Text(
                String.format(Locale.US, "Tempo: %.2fx", tempo),
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = tempo,
                valueRange = 0.5f..2f,
                onValueChange = { tempo = it },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { pitchSemitones = 0f }) { Text("Reset pitch") }
                Button(onClick = { tempo = 1f }) { Text("Reset tempo") }
            }
        }
        HorizontalDivider()
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                if (BuildConfig.DEBUG) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true

                    val origins = setOf("https://m.youtube.com")
                    val supportsDocumentStart =
                        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    if (supportsDocumentStart) {
                        // Ad-block must run before the hook script: both patch the
                        // page, but the ad-block hooks (JSON.parse etc.) need to be
                        // in place before YouTube's own bootstrap script runs, and
                        // registration order is injection order.
                        WebViewCompat.addDocumentStartJavaScript(this, AD_BLOCK_SCRIPT, origins)
                        WebViewCompat.addDocumentStartJavaScript(this, hookScript, origins)
                    }

                    addJavascriptInterface(TransposeJsBridge(), "TransposeBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            // Fallback path for WebViews without document-start support
                            // (older WebView versions) — late, but better than nothing.
                            if (!supportsDocumentStart) {
                                view.evaluateJavascript(AD_BLOCK_SCRIPT, null)
                                view.evaluateJavascript(hookScript, null)
                            }
                        }
                    }
                    loadUrl("https://m.youtube.com")
                }
            },
            update = { webView ->
                webView.evaluateJavascript(
                    "window.TransposeControl && window.TransposeControl.setPitchSemitones($pitchSemitones);",
                    null,
                )
                webView.evaluateJavascript(
                    "window.TransposeControl && window.TransposeControl.setTempo($tempo);",
                    null,
                )
            },
        )
    }
}

private fun formatSemitones(value: Float): String {
    val rounded = Math.round(value)
    return if (rounded > 0) "+$rounded" else rounded.toString()
}
