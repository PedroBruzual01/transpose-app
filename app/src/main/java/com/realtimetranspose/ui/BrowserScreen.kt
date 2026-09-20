package com.realtimetranspose.ui

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.realtimetranspose.BuildConfig
import com.realtimetranspose.browser.AD_BLOCK_SCRIPT
import com.realtimetranspose.browser.BrowserProbeBus
import com.realtimetranspose.browser.BrowserProbeState
import com.realtimetranspose.browser.DOM_FALLBACK_SCRIPT
import com.realtimetranspose.browser.TransposeJsBridge
import com.realtimetranspose.ui.components.CenteredValueSlider
import com.realtimetranspose.ui.components.CircleIconButton
import com.realtimetranspose.ui.components.GhostIconButton
import com.realtimetranspose.ui.components.GhostTextButton
import com.realtimetranspose.ui.components.StatusDot
import com.realtimetranspose.ui.components.ValueMapping
import com.realtimetranspose.ui.theme.NocturneColors
import com.realtimetranspose.ui.theme.NocturneSpacing
import com.realtimetranspose.ui.theme.NocturneType
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.math.roundToInt

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

// Complementary to AdBlockScript.kt's JSON patching, NOT a substitute for it:
// domain/path blocking cannot stop YouTube's actual video ads (served from
// the same googlevideo.com host as real video, deliberately, to defeat
// exactly this). What this DOES catch: companion/display ad requests and
// tracking beacons. Telemetry endpoints (log_event, attestation) are
// deliberately left alone — suppressing them is itself a detectable signal
// of ad-blocking that YouTube has been reported to react to by degrading the
// page (missing comments/descriptions), and blocking them buys us nothing
// against actual video ads.
private val BLOCKED_HOSTS = listOf(
    "doubleclick.net",
    "googleadservices.com",
    "googlesyndication.com",
)
private val BLOCKED_PATH_SUBSTRINGS = listOf(
    "/pagead/",
    "/ptracking",
    "/api/stats/ads",
    "/api/stats/qoe",
)

private fun shouldBlockNetworkRequest(url: String): Boolean {
    if (BLOCKED_HOSTS.any { url.contains(it) }) return true
    if (BLOCKED_PATH_SUBSTRINGS.any { url.contains(it) }) return true
    // Ad-playback init ping, not a media segment — uBlock Origin blocks this
    // exact pattern for the same reason.
    if (url.contains("googlevideo.com/initplayback") && url.contains("oad=")) return true
    return false
}

private const val BROWSER_PITCH_MIN = -12f
private const val BROWSER_PITCH_MAX = 12f
private const val BROWSER_TEMPO_MIN = 0.5f
private const val BROWSER_TEMPO_MAX = 2f

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen() {
    val probeState by BrowserProbeBus.state.collectAsState()
    val context = LocalContext.current

    var pitchSemitones by remember { mutableFloatStateOf(0f) }
    var tempo by remember { mutableFloatStateOf(1f) }
    var currentUrl by remember { mutableStateOf("m.youtube.com") }
    var diagnosticsExpanded by remember { mutableStateOf(false) }

    val engineSourceBase64 = remember {
        val bytes = context.assets.open("soundtouch-scriptprocessor.js").use { it.readBytes() }
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    val hookScript = remember(engineSourceBase64) { buildHookScript(engineSourceBase64) }

    Column(modifier = Modifier.fillMaxSize().background(NocturneColors.bg)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrowserSliderRow(
                label = "PITCH",
                fraction = ValueMapping.semitonesToFraction(pitchSemitones, BROWSER_PITCH_MIN, BROWSER_PITCH_MAX),
                onFractionChange = { pitchSemitones = ValueMapping.fractionToSemitones(it, BROWSER_PITCH_MIN, BROWSER_PITCH_MAX).toFloat() },
                valueText = formatSemitones(pitchSemitones.roundToInt()),
                onDecrement = { pitchSemitones = (pitchSemitones.roundToInt() - 1).coerceAtLeast(BROWSER_PITCH_MIN.toInt()).toFloat() },
                onIncrement = { pitchSemitones = (pitchSemitones.roundToInt() + 1).coerceAtMost(BROWSER_PITCH_MAX.toInt()).toFloat() },
                onReset = { pitchSemitones = 0f },
                resetEnabled = pitchSemitones != 0f,
            )
            BrowserSliderRow(
                label = "TEMPO",
                fraction = ValueMapping.speedToFraction(tempo, BROWSER_TEMPO_MIN, BROWSER_TEMPO_MAX),
                onFractionChange = { tempo = ValueMapping.fractionToSpeed(it, BROWSER_TEMPO_MIN, BROWSER_TEMPO_MAX) },
                valueText = String.format(Locale.US, "%.2f×", tempo),
                onDecrement = { tempo = (tempo - 0.05f).coerceAtLeast(BROWSER_TEMPO_MIN) },
                onIncrement = { tempo = (tempo + 0.05f).coerceAtMost(BROWSER_TEMPO_MAX) },
                onReset = { tempo = 1f },
                resetEnabled = tempo != 1f,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(color = hookStatusColor(probeState.hookResult))
                    Text("Audio enganchado", style = NocturneType.diagnosticLabel, color = NocturneColors.textFaint)
                }
                GhostTextButton(
                    text = if (diagnosticsExpanded) "Ocultar" else "Diagnóstico",
                    onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                )
            }

            if (diagnosticsExpanded) {
                DiagnosticsPanel(probeState)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(NocturneColors.divider))

        UrlBar(currentUrl)

        AndroidView(
            modifier = Modifier.fillMaxSize().background(NocturneColors.neutral900),
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
                        WebViewCompat.addDocumentStartJavaScript(this, DOM_FALLBACK_SCRIPT, origins)
                        WebViewCompat.addDocumentStartJavaScript(this, hookScript, origins)
                    }

                    addJavascriptInterface(TransposeJsBridge(), "TransposeBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            currentUrl = url ?: currentUrl
                            // Fallback path for WebViews without document-start support
                            // (older WebView versions) — late, but better than nothing.
                            if (!supportsDocumentStart) {
                                view.evaluateJavascript(AD_BLOCK_SCRIPT, null)
                                view.evaluateJavascript(DOM_FALLBACK_SCRIPT, null)
                                view.evaluateJavascript(hookScript, null)
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            if (!shouldBlockNetworkRequest(url)) return null
                            // Called off the main thread; MutableStateFlow's setter is
                            // thread-safe, and Compose recomposes on the next frame.
                            BrowserProbeBus.reportAdBlockEvent(
                                "network-blocked:" + (request.url.host ?: "?"),
                            )
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
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

@Composable
private fun BrowserSliderRow(
    label: String,
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    valueText: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onReset: () -> Unit,
    resetEnabled: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = NocturneType.sectionLabel, color = NocturneColors.textFaint, modifier = Modifier.width(52.dp))
        CenteredValueSlider(
            fraction = fraction,
            onFractionChange = onFractionChange,
            modifier = Modifier.weight(1f),
            trackHeight = 24.dp,
            thumbDiameter = 12.dp,
            glow = false,
        )
        CircleIconButton(icon = Icons.Outlined.Remove, onClick = onDecrement, diameter = 28.dp, iconSize = 14.dp, contentDescription = "$label down")
        Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Text(
                valueText,
                style = NocturneType.browserValueMono,
                color = NocturneColors.accent300,
                softWrap = false,
                maxLines = 1,
            )
        }
        CircleIconButton(icon = Icons.Outlined.Add, onClick = onIncrement, diameter = 28.dp, iconSize = 14.dp, contentDescription = "$label up")
        GhostIconButton(icon = Icons.Outlined.Refresh, onClick = onReset, enabled = resetEnabled, contentDescription = "Reset $label")
    }
}

@Composable
private fun UrlBar(url: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NocturneColors.neutral900)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Public, contentDescription = null, tint = NocturneColors.accent, modifier = Modifier.size(13.dp))
        Text(
            url,
            style = NocturneType.metadata,
            color = NocturneColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(NocturneColors.divider))
}

@Composable
private fun DiagnosticsPanel(probeState: BrowserProbeState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NocturneColors.surface, RoundedCornerShape(NocturneSpacing.radiusMd))
            .border(1.dp, NocturneColors.divider, RoundedCornerShape(NocturneSpacing.radiusMd))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DiagnosticLine("Hook", probeState.hookResult ?: "—")
        DiagnosticLine(
            "Nivel",
            String.format(
                Locale.US,
                "%.4f (ctx=%s, muestras=%d)",
                probeState.lastLevel,
                probeState.audioContextState ?: "-",
                probeState.sampleCount,
            ),
        )
        DiagnosticLine(
            "AdBlock",
            if (probeState.adBlockEvents.isEmpty()) {
                "sin eventos todavía"
            } else {
                probeState.adBlockEvents.entries.joinToString(", ") { (tag, count) -> "$tag×$count" }
            },
        )
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = NocturneColors.textMuted)) { append("$label: ") }
            withStyle(SpanStyle(color = NocturneColors.accent300)) { append(value) }
        },
        style = NocturneType.diagnosticMono,
    )
}

private fun hookStatusColor(hookResult: String?): Color = when {
    hookResult == null -> NocturneColors.textMuted
    hookResult.startsWith("OK") -> NocturneColors.accent
    else -> NocturneColors.errorText
}

private fun formatSemitones(value: Int): String = if (value > 0) "+$value" else value.toString()
