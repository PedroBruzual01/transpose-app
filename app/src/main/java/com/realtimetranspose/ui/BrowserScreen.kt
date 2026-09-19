package com.realtimetranspose.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.realtimetranspose.browser.BrowserProbeBus
import com.realtimetranspose.browser.TransposeJsBridge
import java.util.Locale

/**
 * Phase B / Fase 0 spike screen — NOT the real browser mode yet. Just proves
 * (or disproves) that a `MediaElementAudioSourceNode` hooked onto YouTube's
 * own `<video>` element, inside our own WebView, delivers real samples
 * instead of the CORS-taint silence documented in
 * docs/specs/2026-09-19-transpose-clone-design.md.
 *
 * Passthrough only (source -> destination unmodified) plus an AnalyserNode
 * tap reporting RMS once a second — same verification method as Phase 1's
 * native capture engine, so "it works" means a real non-zero, moving number,
 * not just "no JS exception was thrown" (a taint failure throws nothing; it
 * silently delivers zeroes).
 */
private val HOOK_SCRIPT = """
(function() {
  if (window.__transposeHooked) return;
  window.__transposeHooked = true;

  function hook(video) {
    if (video.__transposeConnected) return;
    video.__transposeConnected = true;
    try {
      var ctx = window.__transposeCtx || (window.__transposeCtx = new (window.AudioContext || window.webkitAudioContext)());
      var source = ctx.createMediaElementSource(video);
      var analyser = ctx.createAnalyser();
      analyser.fftSize = 2048;
      source.connect(analyser);
      source.connect(ctx.destination);
      window.TransposeBridge && window.TransposeBridge.onHookResult('OK ctxState=' + ctx.state);

      var data = new Uint8Array(analyser.frequencyBinCount);
      setInterval(function() {
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
      window.TransposeBridge && window.TransposeBridge.onHookResult('ERROR ' + e.message);
    }
  }

  function scan() {
    var v = document.querySelector('video');
    if (v) hook(v);
  }

  scan();
  new MutationObserver(scan).observe(document.documentElement, {childList: true, subtree: true});
})();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen() {
    val probeState by BrowserProbeBus.state.collectAsState()

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
        }
        HorizontalDivider()
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    addJavascriptInterface(TransposeJsBridge(), "TransposeBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript(HOOK_SCRIPT, null)
                        }
                    }
                    loadUrl("https://m.youtube.com")
                }
            },
        )
    }
}
