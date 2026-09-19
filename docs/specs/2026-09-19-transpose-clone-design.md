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
- [x] UI modo archivo (`FilePlayerScreen`) — pendiente de probar en dispositivo real
- [ ] Loop A-B (diferido, no bloqueante)
- [ ] Fase 0 de investigación del modo navegador
- [ ] Modo navegador
