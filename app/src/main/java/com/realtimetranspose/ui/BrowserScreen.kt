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
 * Web Audio pitch/tempo-shift graph — confirmed viable in the Fase 0 spike
 * (docs/specs/2026-09-19-transpose-clone-design.md): YouTube's MSE-backed
 * video is not CORS-tainted inside our own WebView, so this is a clean
 * replacement of the audio, not an overlay (unlike the native-capture
 * approach that got blocked by Spotify in Phase 1).
 *
 * Pitch/tempo engine: @soundtouchjs/audio-worklet (LGPL-2.1), vendored as
 * assets/soundtouch-worklet.js — a real AudioWorkletProcessor, so shifting
 * happens on the audio rendering thread, not the page's main thread.
 * Base64-embedded into the injected script and loaded via a blob: URL,
 * since `audioWorklet.addModule()` needs a fetchable module URL and there's
 * no server to point it at inside a WebView.
 *
 * Graph per hooked video:
 *   MediaElementAudioSourceNode -> AudioWorkletNode('soundtouch-processor') -> AnalyserNode -> destination
 *
 * Native controls reach the page via `window.TransposeControl.set*()`,
 * called through WebView.evaluateJavascript() from [BrowserScreen]'s
 * `AndroidView` update block.
 */
private fun buildHookScript(processorSourceBase64: String): String = """
(function() {
  if (window.__transposeHooked) return;
  window.__transposeHooked = true;

  var processorBlobUrl = null;
  function getProcessorBlobUrl() {
    if (processorBlobUrl) return processorBlobUrl;
    var src = atob("$processorSourceBase64");
    var blob = new Blob([src], { type: 'application/javascript' });
    processorBlobUrl = URL.createObjectURL(blob);
    return processorBlobUrl;
  }

  window.TransposeControl = {
    setPitchSemitones: function(v) {
      if (window.__transposeNode) window.__transposeNode.parameters.get('pitchSemitones').value = v;
    },
    setTempo: function(v) {
      if (window.__transposeNode) window.__transposeNode.parameters.get('tempo').value = v;
    }
  };

  function hook(video) {
    if (video.__transposeConnected) return;
    video.__transposeConnected = true;
    try {
      var ctx = window.__transposeCtx || (window.__transposeCtx = new (window.AudioContext || window.webkitAudioContext)());
      var source = ctx.createMediaElementSource(video);

      ctx.audioWorklet.addModule(getProcessorBlobUrl()).then(function() {
        var node = new AudioWorkletNode(ctx, 'soundtouch-processor');
        window.__transposeNode = node;

        var analyser = ctx.createAnalyser();
        analyser.fftSize = 2048;

        source.connect(node);
        node.connect(analyser);
        node.connect(ctx.destination);

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
      }).catch(function(e) {
        window.TransposeBridge && window.TransposeBridge.onHookResult('WORKLET ERROR ' + e.message);
      });
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
    val context = LocalContext.current

    var pitchSemitones by remember { mutableFloatStateOf(0f) }
    var tempo by remember { mutableFloatStateOf(1f) }

    val processorSourceBase64 = remember {
        val bytes = context.assets.open("soundtouch-worklet.js").use { it.readBytes() }
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    val hookScript = remember(processorSourceBase64) { buildHookScript(processorSourceBase64) }

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
