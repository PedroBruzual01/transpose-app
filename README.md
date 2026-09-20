# Transpose

A personal Android app for musicians practicing by ear: change the **pitch**
and **playback speed** of audio independently and in real time, on **(A)
local audio files**, and **(B)** videos played inside a built-in browser.
Personal project, not published on the Play Store, not affiliated with,
endorsed by, or connected to YouTube, Google, or any other service it can
load.

<p align="center">
  <img src="docs/screenshots/files-loaded.png" width="260" alt="Files mode: pitch, fine tune and speed controls" />
  <img src="docs/screenshots/browser-playing.png" width="260" alt="Browser mode: YouTube video, dark theme, comments" />
  <img src="docs/screenshots/browser-controls-expanded.png" width="260" alt="Browser mode: pitch, cents and tempo controls expanded" />
</p>

## Features

### Files mode

- Pick any local audio file (`mp3`, `m4a`, `wav`, `flac`) via the system
  file picker — no broad storage permission needed.
- Real-time pitch shift, **−12 to +12 semitones**, decoupled from speed.
- Fine-tune pitch in **cents** (±50, i.e. up to half a semitone either way)
  on top of the coarse semitone control, for exact intonation matching.
- Independent speed control, **0.25× to 4×**, powered by the same
  time-stretching engine (so speed changes don't affect pitch unless you
  want them to).
- **A/B loop**: mark two points in the track and loop between them
  indefinitely — useful for drilling a specific section on repeat.

### Browser mode

- A built-in browser with a real address bar — type a URL, a bare domain,
  or a search phrase (falls back to a Google search).
- The same pitch (±12 semitones, ±50 cents fine-tune) and tempo (0.5×–2×)
  controls as Files mode, applied live to whatever video is playing, by
  hooking the page's own `<video>` element into a Web Audio pitch-shift
  graph — this works on YouTube specifically, since that's the ad-hoc test
  suite it was built and verified against, but the hook itself just looks
  for whatever `<video>` is active on the current page.
- Tempo uses the browser's native `video.playbackRate`, which is
  pitch-preserving by default — so tempo and pitch are fully independent,
  the same as in Files mode.
- **Full-screen video**, with the system status/navigation bars hidden to
  match, restored automatically on exit.
- The pitch/tempo/URL panel is **collapsible** — swipe it away to let the
  video take the full screen, tap the handle to bring the controls back.
- **Mutual exclusion**: playing a local file automatically pauses whatever
  is playing in the browser, and vice versa, so the two audio engines never
  overlap.
- On YouTube specifically: forces its dark theme regardless of system
  theme, and works around a WebView-detection quirk that otherwise hides
  comments (Android tags WebViews with a `; wv` user-agent marker that
  Google sites serve a reduced experience to — stripped here). It also
  strips known ad-related fields out of the page's own player-response data
  before the page reads it, plus a DOM-level fallback for ads stitched
  directly into the video stream — both purely client-side, the same class
  of thing a content-blocking browser extension does in its own tab.

## How it works

Two independent pipelines share one Compose UI shell (`Files` / `Browser`
tabs), each solving "pitch-shift this audio" in a different way because
they start from a different kind of source.

**Files.** `MediaExtractor`/`MediaCodec` decodes the file to raw PCM, which
is fed through the [Rubber Band Library](https://breakfastquay.com/rubberband/)
(vendored in `app/src/main/cpp/rubberband/`, built via NDK/CMake, driven
through its own official JNI bridge) for real time-domain pitch/time
shifting, and the result is streamed out through `AudioTrack`. The A/B loop
is just a position check on the same read loop that feeds the decoder.

**Browser.** A `WebView` loads the requested page. JavaScript is injected at
*document-start* (before any of the page's own scripts run, via
`WebViewCompat.addDocumentStartJavaScript`) that:

1. Finds the page's active `<video>` element and connects it to a Web Audio
   graph: `MediaElementAudioSourceNode → pitch-shift node → destination`.
   This is a genuine replacement of the video's audio path, not an overlay —
   confirmed the video is not CORS-tainted inside the app's own WebView, so
   real samples flow through, not silence.
2. Pitch-shifts through [`@soundtouchjs/core`](https://github.com/cutterbl/SoundTouchJS)
   (bundled with esbuild into a dependency-free script), driven by a plain
   `ScriptProcessorNode` rather than the modern `AudioWorkletNode` — YouTube's
   Content-Security-Policy blocks `audioWorklet.addModule()` from loading a
   worklet module, so the deprecated-but-still-supported API is what's left.
3. Re-hooks itself whenever YouTube swaps the active video (SPA navigation,
   autoplay, Shorts swipes) through several redundant triggers, since no
   single DOM event reliably fires for every case.
4. Strips ads from the player-response JSON before YouTube's own code reads
   it, and forces the dark-theme cookie.

Native (Kotlin) controls reach the page through
`window.TransposeControl.set*()`, called via `WebView.evaluateJavascript()`;
the page reports back (hook status, audio level, ad-block events, video
play/pause state) through a `window.TransposeBridge` JavaScript interface.

## Project structure

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt
│   └── rubberband/                       — vendored, do not edit (third-party import)
├── assets/
│   └── soundtouch-scriptprocessor.js     — Browser mode's pitch engine, bundled with esbuild
└── java/
    ├── com/breakfastquay/rubberband/
    │   └── RubberBandStretcher.kt        — 1:1 binding to the official JNI bridge (Files mode)
    └── com/realtimetranspose/
        ├── MainActivity.kt               — tab shell (Files / Browser), file picker (SAF)
        ├── audio/                        — Files mode
        │   ├── AudioFileDecoder.kt       — MediaExtractor/MediaCodec → PCM float
        │   ├── PitchShiftEngine.kt       — semitones/speed over RubberBandStretcher
        │   └── TransposePlayer.kt        — decode → shift → AudioTrack pipeline
        ├── browser/                      — Browser mode
        │   ├── AdBlockScript.kt          — strips ads from YouTube's player JSON + DOM fallback
        │   ├── BrowserProbeBus.kt        — observable state (hook/level/ad-block/playback)
        │   └── TransposeJsBridge.kt      — JS → Kotlin bridge
        └── ui/
            ├── FilePlayerScreen.kt       — Compose: picker, transport, pitch/cents/speed, loop
            ├── BrowserScreen.kt          — Compose: WebView + pitch/cents/tempo, URL bar, fullscreen
            ├── components/               — shared "Nocturne" design system pieces
            └── theme/                    — colors, type scale, spacing
```

## Building it

```bash
./gradlew installDebug
```

Requires the Android NDK (for the Rubber Band build) and a device or
emulator running Android 8.0 (API 26) or newer. No API keys or backend of
any kind — everything runs on-device.

## License

GPLv2 or later — see [LICENSE](LICENSE). This project statically links the
[Rubber Band Library](https://breakfastquay.com/rubberband/) (GPLv2+,
vendored in `app/src/main/cpp/rubberband/`), which is what determines that
choice: a GPLv2+ dependency compiled into the app means the whole app has to
be distributed under GPLv2+-compatible terms too, which is why the full
source is here rather than just an APK. `@soundtouchjs/core`
(`app/src/main/assets/`) is separately MPL-2.0 licensed.
