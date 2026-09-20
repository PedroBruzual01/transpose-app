# Transpose clone — design

Sustituye el enfoque anterior (captura de audio en vivo entre apps, ver
`FEASIBILITY.md`) por una app tipo "Transpose: Pitch, Speed Control": cambia
tono/velocidad de (A) archivos de audio locales y (B) vídeos de YouTube abiertos
en un navegador integrado. Uso personal, sin publicación en Play Store.

## Por qué este enfoque evita el muro de la Fase 1

El modo (B) no captura audio a nivel de Android (nada de `MediaProjection` /
`AudioPlaybackCaptureConfiguration`). Un `WebView` propio carga YouTube, y JS
inyectado engancha un `MediaElementAudioSourceNode` de la Web Audio API al
`<video>` — eso desconecta automáticamente su salida nativa — lo procesa y lo
vuelve a conectar a `AudioContext.destination`. Todo ocurre dentro de nuestra
propia app (el WebView), así que no hay ninguna otra app cuya política de
captura nos pueda bloquear, y sí logramos sustitución limpia (el problema que
documentamos en `FEASIBILITY.md` no aplica aquí).

## Alcance

Dos motores independientes, mismo shell de app (pestañas "Files" / "Browser").
Se implementan en orden: A primero (menor riesgo, controlamos toda la cadena),
B después (mayor riesgo técnico, JS/Web Audio dentro de WebView).

### A. Modo archivo local

```
Archivo (SAF picker)
   → MediaExtractor/MediaCodec (decode a PCM)
   → PitchShiftEngine nativo (C++/NDK, Rubber Band)
   → AudioTrack
```

- Selección de archivo vía Storage Access Framework (sin permiso de
  almacenamiento amplio).
- Motor nativo reutiliza el toolchain NDK/CMake ya validado compilando en esta
  máquina durante la Fase 1 anterior.
- Interfaz C++ tal como la especificaba el brief original:
  `initialize/setPitchSemitones/setTempoRatio/process/reset/destroy`.
- Controles: pitch (semitonos, -12..+12), velocidad (0.25x-4x), play/pause,
  seek, loop A-B.
- Rubber Band es GPLv2+ salvo licencia comercial — aceptable para uso personal
  no distribuido (el propio brief original ya excluye Play Store).

### B. Modo navegador (YouTube)

```
WebView (m.youtube.com)
   → JS inyectado: MediaElementAudioSourceNode sobre <video>
   → nodo de pitch-shift (WSOLA en JS, o librería tipo soundtouch.js)
   → AudioContext.destination
   → MutationObserver (re-engancha al cambiar de vídeo, YouTube es SPA)
   → puente JS↔Kotlin (JavascriptInterface) para sliders nativos
```

Diseño detallado de B se hace al llegar a esa fase (Fase 0 propia: investigar
qué librería JS de pitch-shift usar, probar el enganche a `<video>` en YouTube
real antes de construir la UI).

## Qué se retira del proyecto anterior

`AudioCaptureManager.kt`, `AudioCaptureService.kt`, la UI de captura en vivo, y
los permisos `RECORD_AUDIO`/`FOREGROUND_SERVICE_MEDIA_PROJECTION` del manifest
— ya no aplican a este enfoque. Se mantiene: estructura Gradle/Kotlin/Compose,
namespace `com.realtimetranspose`, NDK/CMake toolchain validado.

## Estado

- [x] Diseño aprobado por el usuario (2026-09-19)
- [x] Limpieza del código de captura en vivo
- [x] NDK/CMake + Rubber Band vendorizado (compila para arm64-v8a/armeabi-v7a/x86_64)
- [x] JNI bridge (usa el bridge oficial de Rubber Band, sin código propio)
- [x] Pipeline de decodificación de archivo + AudioTrack (`TransposePlayer`)
- [x] UI modo archivo (`FilePlayerScreen`) — **probado en Poco F7 Pro real, funciona** (FLAC, pitch -3, sin errores)
- [x] Loop A-B (Modo A) — probado en dispositivo real, funciona
- [x] Rediseño visual "Nocturne" (ambas pantallas) — ver `docs/design/design_handoff_transpose_ui/`, probado en dispositivo real
- [x] Fase 0 de investigación del modo navegador — **CONFIRMADO: funciona** (ver más abajo)
- [x] Motor de pitch real en el navegador — **probado en dispositivo real, funciona.** Ver "Cambio de motor" más abajo: `@soundtouchjs/audio-worklet` (AudioWorkletNode) quedó descartado por CSP; sustituido por `@soundtouchjs/core` sobre `ScriptProcessorNode`, bundleado a mano con esbuild
- [x] Velocidad/tempo en el navegador vía `video.playbackRate` nativo (preserva tono por defecto) — independiente del pitch
- [x] Bloqueo de anuncios de YouTube (investigado con un agente de planning en Opus) — eventos de limpieza confirmados en dispositivo real (`prune:adBreakHeartbeatParams×2`, `fetch-pruned×1`, `prune:playerAds×1`, `prune:adSlots×1`)
- [ ] Re-enganche robusto al cambiar de vídeo (SPA) — cubierto en parte por el `MutationObserver` existente, sin probar a fondo todavía

## Cambio de motor: de AudioWorklet a ScriptProcessorNode (2026-09-20)

`@soundtouchjs/audio-worklet` (la primera opción, un `AudioWorkletNode` real)
quedó bloqueada por la CSP de YouTube: `require-trusted-types-for 'script'`
+ `script-src ... 'strict-dynamic'` impide que `audioWorklet.addModule()`
cargue un módulo desde un blob URL (`AbortError: unable to load a worklet's
module`), incluso envolviendo la URL con una política Trusted Types — el
bloqueo real está en `script-src`/`worker-src`, no solo en Trusted Types.

Sustituido por `@soundtouchjs/core` (el motor DSP puro, sin capa de
Worklet) conducido a mano mediante un `ScriptProcessorNode` — no necesita
cargar ningún módulo aparte, así que nunca choca con esa CSP. Es una API
obsoleta (corre en el hilo principal, no en el hilo de audio dedicado) pero
sigue soportada en todos los navegadores actuales. Bundleado nosotros mismos
con esbuild (`@soundtouchjs/core` solo publica ESM con una dependencia
externa, `@soundtouchjs/interpolation-strategy-lanczos`) en un único IIFE de
~26 KB sin dependencias, en `assets/soundtouch-scriptprocessor.js`. El motor
de tempo/velocidad ya no pasa por SoundTouch en absoluto — se delegó a
`video.playbackRate` nativo (Chromium preserva el tono por defecto), con lo
que pitch y velocidad quedan totalmente independientes con mucho menos
código.

Además, el propio `eval()` usado para cargar el motor bundleado tropezó con
la misma CSP: aunque `'unsafe-eval'` está permitido, `require-trusted-types-for
'script'` exige que el argumento de `eval()` sea un `TrustedScript`, no una
cadena suelta — mismo patrón de política permisiva que ya se usaba para el
blob URL del worklet, aplicado aquí a `trustedTypes.createPolicy(...).createScript(...)`.

## Bloqueo de anuncios de YouTube

El usuario pidió investigar alternativas a "usar Brave" (no es viable —
Android no permite embeber la UI de otra app, y usar un navegador externo vía
Custom Tabs perdería la capacidad de inyectar JS que hace posible todo el
Modo navegador). Investigación delegada a un agente de planning con Opus.

**Hallazgo clave:** bloquear por dominio (`shouldInterceptRequest`) no para
los anuncios de vídeo — salen del mismo `googlevideo.com` que el vídeo real,
a propósito, para burlar justo ese tipo de bloqueo. Lo que sí funciona es
**interceptar y limpiar la respuesta JSON del reproductor** (quitar
`adPlacements`, `playerAds`, `adSlots`, etc.) antes de que el propio código
de YouTube la procese — la misma técnica que usa uBlock Origin internamente.

Implementado en `AdBlockScript.kt`: hooks sobre `JSON.parse`,
`Response.prototype.json`, `fetch` y `XMLHttpRequest`, más una trampa sobre
`window.ytInitialPlayerResponse`. Requiere inyección en **document-start**
(antes de que corra cualquier script de la página) vía
`WebViewCompat.addDocumentStartJavaScript` (librería `androidx.webkit`) — el
punto de inyección anterior (`onPageFinished`) llegaba demasiado tarde tanto
para esto como, en rigor, para el propio hook de pitch-shifting.

Regla dura compartida con el hook de audio: el bloqueador **nunca** debe
reemplazar ni clonar el `<video>` (algunos bloqueadores lo hacen vía
iframe-swap) — rompería `createMediaElementSource()`, que solo funciona una
vez por elemento.

Cada limpieza reporta un evento contable a `BrowserProbeBus` (visible en la
UI como "AdBlock: prune:adPlacements×3, ...") — es la señal de aceptación
real ("de verdad quitó algo"), no "no vi ningún anuncio" (la mayoría de
vídeos no tienen anuncios de por sí).

Completado en una pasada posterior (2026-09-20), también probado en
dispositivo real y funcionando:
- **Bloqueo de red complementario** (`shouldInterceptRequest` en
  `BrowserScreen.kt`): doubleclick.net, googleadservices.com,
  googlesyndication.com, `/pagead/`, `/ptracking`, `/api/stats/ads`,
  `/api/stats/qoe`, y el ping de init de anuncio de `googlevideo.com`. Deja
  aparte a propósito los endpoints de telemetría/attestation
  (`log_event`, `att`) — bloquearlos no para anuncios de vídeo y es
  precisamente el tipo de señal que YouTube usa para detectar bloqueadores.
- **Fallback por DOM** (`DOM_FALLBACK_SCRIPT` en `AdBlockScript.kt`) para
  anuncios insertados directamente en el stream (server-side ad insertion),
  que el bloqueo JSON no puede tocar por definición: clic automático al
  botón de saltar si existe, salto forzado de `currentTime` a los 8s si no.
  Nunca toca `playbackRate` (lo controla el slider de tempo) ni silencia
  (comportamiento indefinido tras enrutar por Web Audio) — solo oculta por
  CSS y hace clic/salta. Selectores no verificados contra un anuncio real en
  pruebas (ninguno de los vídeos probados traía anuncios); a revisar si deja
  de funcionar.

Queda pendiente, sin implementar: vigilancia de la API de integridad de
WebView de Google, que podría inutilizar este enfoque en el futuro sin
previo aviso (no hay nada que hacer al respecto de antemano, solo estar
atentos si el bloqueo deja de funcionar de golpe).

## Fase 0 del Modo navegador — resultado (2026-09-19)

**Pregunta:** ¿el `<video>` de YouTube, enganchado a un `MediaElementAudioSourceNode`
dentro de nuestro propio WebView, entrega audio real o silencio por CORS-taint?

**Respuesta: entrega audio real.** Probado en el Poco F7 Pro con YouTube de
verdad (m.youtube.com, canción "Fine Again" de Seether, contenido gratuito sin
DRM). El script inyectado (`BrowserScreen.kt`) engancha el `<video>`, lo
conecta a un `AnalyserNode` + `ctx.destination`, y mide RMS cada segundo igual
que hicimos con la captura nativa en la Fase 1:

```
Hook: OK ctxState=running
Nivel: 0.0992 (ctx=running, muestras=156)
```

Nivel sostenido y no-cero durante 156 muestras (~2.5 minutos), audio
perfectamente audible en todo momento — confirma la hipótesis: YouTube sirve
vídeo vía MSE (el propio JS de la página inyecta los bytes con
`appendBuffer()`), lo que aparentemente no dispara la misma comprobación
CORS/origin-clean que un `<video src="https://otro-dominio">` directo sí
dispararía. A diferencia de Spotify (Fase 1), aquí no hay bloqueo — vía libre
para construir el pitch-shifting real sobre esta base.

Captura de pantalla: `docs/poc-results/browser-probe.png`.
