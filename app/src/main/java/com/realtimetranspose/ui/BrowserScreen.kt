package com.realtimetranspose.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContextWrapper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.realtimetranspose.BuildConfig
import com.realtimetranspose.audio.TransposePlayer
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
import java.net.URLEncoder
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

/**
 * Forces YouTube's own dark theme via its `PREF` cookie (`f6=400`) — a
 * well-documented, widely-used technique (e.g.
 * https://gist.github.com/RoguedBear/56758693577fa324b1556e94d68ac6d3).
 * Needed because YouTube's dark theme is NOT purely CSS `prefers-color-scheme`
 * driven — it's gated by this stored preference — and neither
 * `WebSettingsCompat.setForceDark()` nor an app-level Configuration/night-mode
 * override changed `window.matchMedia('(prefers-color-scheme: dark)')` at all
 * in on-device testing (confirmed false in both cases, even with the phone's
 * own system dark theme on), so those Android-side dark-mode APIs aren't
 * reliably wired to this WebView's rendering on this OS version. Setting the
 * cookie directly sidesteps all of that.
 */
private val YOUTUBE_DARK_MODE_SCRIPT = """
(function() {
  if (window.__transposeDarkModeSet) return;
  window.__transposeDarkModeSet = true;
  try {
    document.cookie = 'PREF=tz=UTC&f6=400; path=/; domain=.youtube.com; max-age=31536000';
  } catch (e) {}
})();
""".trimIndent()

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
    },
    // Called from Kotlin when file playback starts, so only one of the two
    // audio sources is ever audible at once.
    pauseVideo: function() {
      if (window.__transposeVideo) window.__transposeVideo.pause();
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

    // Report play/pause of whichever video is active, once per element —
    // this is what lets the Kotlin side pause the local-file player the
    // instant a YouTube video starts, so the two never play at once.
    if (!video.__transposePlaybackListenersAdded) {
      video.__transposePlaybackListenersAdded = true;
      video.addEventListener('play', function() {
        window.TransposeBridge && window.TransposeBridge.onVideoPlaybackState && window.TransposeBridge.onVideoPlaybackState(true);
      });
      video.addEventListener('pause', function() {
        window.TransposeBridge && window.TransposeBridge.onVideoPlaybackState && window.TransposeBridge.onVideoPlaybackState(false);
      });
      if (!video.paused) {
        window.TransposeBridge && window.TransposeBridge.onVideoPlaybackState && window.TransposeBridge.onVideoPlaybackState(true);
      }
    }

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

private fun isYouTubeHost(url: String?): Boolean {
    val host = url?.let { android.net.Uri.parse(it).host } ?: return false
    return host == "youtube.com" || host.endsWith(".youtube.com")
}

/** Compose's LocalContext is usually a ContextWrapper around the Activity, not the Activity itself. */
private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Cinema-mode for fullscreen video — restored when the custom view is hidden. */
private fun setSystemBarsHidden(activity: Activity?, hidden: Boolean) {
    val window = activity?.window ?: return
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    if (hidden) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen() {
    val probeState by BrowserProbeBus.state.collectAsState()
    val fileState by TransposePlayer.state.collectAsState()
    val context = LocalContext.current

    var pitchSemitones by remember { mutableFloatStateOf(0f) }
    var fineCents by remember { mutableFloatStateOf(0f) }
    var tempo by remember { mutableFloatStateOf(1f) }
    var currentUrl by remember { mutableStateOf("m.youtube.com") }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    // Collapsible so YouTube can take the full screen when the user isn't
    // actively adjusting pitch/tempo — starts expanded to match prior
    // behavior, user collapses it on demand via the grabber handle below.
    var controlsExpanded by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Editable copy of currentUrl — kept separate so typing doesn't fight
    // with onPageFinished updates, and only resynced from the real URL while
    // the field isn't focused (otherwise every navigation would blow away
    // whatever the user is mid-typing).
    var urlInputText by remember { mutableStateOf(currentUrl) }
    var urlFieldFocused by remember { mutableStateOf(false) }
    LaunchedEffect(currentUrl) {
        if (!urlFieldFocused) urlInputText = currentUrl
    }
    val focusManager = LocalFocusManager.current
    val navigate: (String) -> Unit = { input ->
        webViewRef?.loadUrl(resolveNavigationTarget(input))
        focusManager.clearFocus()
    }

    // Mutual exclusion between the two audio sources — both screens stay alive
    // at once now (tab switching no longer disposes either), so without this
    // a file and a YouTube video could both play audio simultaneously.
    // Whichever one starts playing pauses the other; already-paused doesn't
    // re-trigger, since both effects key off the *other* source's isPlaying
    // and only act on true, so there's no feedback loop between them.
    LaunchedEffect(fileState.isPlaying) {
        if (fileState.isPlaying) {
            webViewRef?.evaluateJavascript(
                "window.TransposeControl && window.TransposeControl.pauseVideo && window.TransposeControl.pauseVideo();",
                null,
            )
        }
    }
    LaunchedEffect(probeState.videoPlaying) {
        if (probeState.videoPlaying) {
            TransposePlayer.pause()
        }
    }

    val engineSourceBase64 = remember {
        val bytes = context.assets.open("soundtouch-scriptprocessor.js").use { it.readBytes() }
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    val hookScript = remember(engineSourceBase64) { buildHookScript(engineSourceBase64) }

    Column(modifier = Modifier.fillMaxSize().background(NocturneColors.bg)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, start = 16.dp, end = 16.dp, bottom = if (controlsExpanded) 14.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Minimal always-visible handle: a plain grabber bar, no label —
            // tap anywhere on it to expand/collapse the panel below, so
            // YouTube can take the freed-up space when collapsed.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { controlsExpanded = !controlsExpanded }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 3.dp)
                        .background(NocturneColors.divider, RoundedCornerShape(2.dp)),
                )
            }

            AnimatedVisibility(
                visible = controlsExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    UrlSearchBar(
                        value = urlInputText,
                        onValueChange = { urlInputText = it },
                        onSubmit = { navigate(urlInputText) },
                        onFocusChanged = { urlFieldFocused = it },
                    )
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
                        label = "CENTS",
                        fraction = ValueMapping.centsToFraction(fineCents),
                        onFractionChange = { fineCents = ValueMapping.fractionToCents(it).toFloat() },
                        valueText = formatSemitones(fineCents.roundToInt()),
                        onDecrement = { fineCents = (fineCents.roundToInt() - 1).coerceAtLeast(-50).toFloat() },
                        onIncrement = { fineCents = (fineCents.roundToInt() + 1).coerceAtMost(50).toFloat() },
                        onReset = { fineCents = 0f },
                        resetEnabled = fineCents != 0f,
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
                            Text("Audio hooked", style = NocturneType.diagnosticLabel, color = NocturneColors.textFaint)
                        }
                        GhostTextButton(
                            text = if (diagnosticsExpanded) "Hide" else "Diagnostics",
                            onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                        )
                    }

                    if (diagnosticsExpanded) {
                        DiagnosticsPanel(probeState)
                    }
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(NocturneColors.divider))

        val activity = context.findActivity()

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().background(NocturneColors.neutral900),
            factory = { ctx ->
                if (BuildConfig.DEBUG) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                val webView = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true

                    // Strips the "; wv" WebView marker Android appends by default.
                    // YouTube (and other Google sites) detect that token and serve a
                    // deliberately reduced experience to embedded WebViews — comments
                    // in particular don't render at all with it present. The rest of
                    // the UA (Chrome/WebView version) is left untouched.
                    settings.userAgentString = settings.userAgentString?.replace("; wv", "")

                    // Kept as a defensive fallback for algorithmic darkening of any
                    // unstyled fragment — NOT what actually makes YouTube render dark.
                    // Measured empirically: window.matchMedia('(prefers-color-scheme:
                    // dark)').matches stayed false with these set, with a per-instance
                    // ConfigurationContext, and with an app-level night-mode override —
                    // this WebView isn't wired to any of those for that media query on
                    // this OS version. What actually works is YOUTUBE_DARK_MODE_SCRIPT
                    // below: YouTube's dark theme is gated by its own stored `PREF`
                    // cookie preference, not purely by prefers-color-scheme.
                    @Suppress("DEPRECATION")
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                        WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
                    }
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                        // YouTube has a real dark theme behind prefers-color-scheme —
                        // prefer that over Chromium's own simulated/inverted darkening.
                        WebSettingsCompat.setForceDarkStrategy(
                            settings,
                            WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING,
                        )
                    }
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                    }

                    // YouTube-specific behavior is scoped to YouTube's own origins only —
                    // AD_BLOCK_SCRIPT and DOM_FALLBACK_SCRIPT have real global side effects
                    // (JSON.parse/Response.json monkey-patched with no URL filter, a
                    // setInterval DOM-poll that never stops) that have no business running
                    // on unrelated sites the URL bar can now take the user to, and the
                    // dark-mode cookie is meaningless anywhere else. The pitch-shift hook
                    // stays on "*": it's a genuine feature (pitch-shift whatever <video> is
                    // on the current page) and is inert on pages with no <video> element.
                    val youtubeOrigins = setOf("https://*.youtube.com", "https://youtube.com")
                    val allOrigins = setOf("*")
                    val supportsDocumentStart =
                        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    if (supportsDocumentStart) {
                        // Dark-mode cookie first: it must be set before YouTube's own
                        // bootstrap script reads it to decide which theme to render.
                        // Ad-block after that (also before YouTube's bootstrap, for the
                        // same reason), hook script last.
                        WebViewCompat.addDocumentStartJavaScript(this, YOUTUBE_DARK_MODE_SCRIPT, youtubeOrigins)
                        WebViewCompat.addDocumentStartJavaScript(this, AD_BLOCK_SCRIPT, youtubeOrigins)
                        WebViewCompat.addDocumentStartJavaScript(this, DOM_FALLBACK_SCRIPT, youtubeOrigins)
                        WebViewCompat.addDocumentStartJavaScript(this, hookScript, allOrigins)
                    }

                    addJavascriptInterface(TransposeJsBridge(), "TransposeBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            currentUrl = url ?: currentUrl
                            // Fallback path for WebViews without document-start support
                            // (older WebView versions) — late, but better than nothing.
                            // The dark-mode cookie in particular only takes effect on the
                            // *next* navigation here, since this page already rendered.
                            // evaluateJavascript has no origin scoping at all, so the
                            // YouTube-only scripts are gated by hand here to match.
                            if (!supportsDocumentStart) {
                                if (isYouTubeHost(url)) {
                                    view.evaluateJavascript(YOUTUBE_DARK_MODE_SCRIPT, null)
                                    view.evaluateJavascript(AD_BLOCK_SCRIPT, null)
                                    view.evaluateJavascript(DOM_FALLBACK_SCRIPT, null)
                                }
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

                    // YouTube's own fullscreen button requests the standard HTML5
                    // Fullscreen API on the <video>; WebView doesn't do anything with
                    // that on its own; it hands the enlarged view to onShowCustomView
                    // for the app to place wherever makes sense — here, straight onto
                    // the Activity's decor view so it covers the whole screen (tabs,
                    // controls panel, everything), with system bars hidden to match.
                    webChromeClient = object : WebChromeClient() {
                        private var customView: View? = null
                        private var customViewCallback: CustomViewCallback? = null

                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                            val decorView = activity?.window?.decorView as? ViewGroup ?: return
                            if (customView != null) {
                                callback.onCustomViewHidden()
                                return
                            }
                            customView = view
                            customViewCallback = callback
                            decorView.addView(
                                view,
                                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                            )
                            setSystemBarsHidden(activity, true)
                        }

                        override fun onHideCustomView() {
                            val decorView = activity?.window?.decorView as? ViewGroup ?: return
                            customView?.let { decorView.removeView(it) }
                            customView = null
                            customViewCallback?.onCustomViewHidden()
                            customViewCallback = null
                            setSystemBarsHidden(activity, false)
                        }
                    }

                    loadUrl("https://m.youtube.com")
                }.also { webViewRef = it }

                FrameLayout(ctx).apply {
                    addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            },
            update = {
                val totalPitch = pitchSemitones + fineCents / 100f
                webViewRef?.evaluateJavascript(
                    "window.TransposeControl && window.TransposeControl.setPitchSemitones($totalPitch);",
                    null,
                )
                webViewRef?.evaluateJavascript(
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

/**
 * Editable, inside the collapsible controls panel (not fixed above the
 * WebView) — lets the user both see and change the current page: type a
 * full URL, a bare domain, or a search phrase, then hit the keyboard's Go
 * action. [resolveNavigationTarget] decides which of those it is.
 */
@Composable
private fun UrlSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NocturneColors.neutral900, RoundedCornerShape(NocturneSpacing.radiusMd))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = NocturneColors.textFaint, modifier = Modifier.size(13.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onFocusChanged(it.isFocused) },
            textStyle = NocturneType.metadata.copy(color = NocturneColors.text),
            singleLine = true,
            cursorBrush = SolidColor(NocturneColors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        )
    }
}

/**
 * Regular-browser-address-bar semantics, not a YouTube-only search box: a
 * full URL is used as-is, a bare domain-looking string (no spaces, has a
 * dot) gets `https://` prepended, and anything else is a general Google
 * search — so this can navigate to any site, the same as Chrome's omnibox,
 * not just YouTube. (Pitch/tempo and the ad-block/dark-mode scripts still
 * only activate on youtube.com pages, wherever the user ends up.)
 */
private fun resolveNavigationTarget(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "https://m.youtube.com"
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    val looksLikeDomain = !trimmed.contains(" ") && trimmed.contains(".")
    return if (looksLikeDomain) {
        "https://$trimmed"
    } else {
        "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
    }
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
            "Level",
            String.format(
                Locale.US,
                "%.4f (ctx=%s, samples=%d)",
                probeState.lastLevel,
                probeState.audioContextState ?: "-",
                probeState.sampleCount,
            ),
        )
        DiagnosticLine(
            "AdBlock",
            if (probeState.adBlockEvents.isEmpty()) {
                "no events yet"
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
