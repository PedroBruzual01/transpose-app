# Transpose

Proyecto personal. Cambia el tono y la velocidad de reproducción de forma
independiente (tipo "Transpose: Pitch, Speed Control"), sobre (A) archivos de
audio locales y (B) vídeos de YouTube abiertos en un navegador integrado (con
bloqueo de anuncios).

Historia del proyecto: empezó como un intento de captura de audio en tiempo
real entre apps (ver [FEASIBILITY.md](FEASIBILITY.md)), pausado al confirmar
que Android no permite sustituir limpiamente el audio de otra app sin
permisos de sistema. Pivotado el 2026-09-19 — ver
[docs/specs/2026-09-19-transpose-clone-design.md](docs/specs/2026-09-19-transpose-clone-design.md)
para el diseño actual, el cambio de motor de audio del Modo navegador
(AudioWorklet → ScriptProcessorNode, por la CSP de YouTube), y el diseño del
bloqueo de anuncios.

## Estado actual: Modo A y Modo B funcionando en dispositivo real

**Modo A — archivo local.** Motor: [Rubber Band Library](https://breakfastquay.com/rubberband/)
v3.3.0 (GPLv2+, vendorizada en `app/src/main/cpp/rubberband/`, uso personal —
sin publicación en Play Store) vía NDK/CMake, usando su propio bridge JNI
oficial sin código de puente propio. Probado con FLAC real, pitch/velocidad
en vivo funcionando.

**Modo B — navegador (YouTube).** WebView con JS inyectado en document-start:
engancha el `<video>` de YouTube a un pitch-shifter propio (`@soundtouchjs/core`
sobre `ScriptProcessorNode`, bundleado con esbuild — ver el spec para por qué
no es un `AudioWorkletNode`), más un bloqueador de anuncios que limpia la
respuesta JSON del reproductor antes de que YouTube la procese. Ambos
confirmados funcionando en dispositivo real: pitch audible y controlable, y
eventos de limpieza de anuncios contados en pantalla.

## Cómo probarlo

```bash
./gradlew installDebug
```

- **Files**: elige un archivo de audio local, prueba pitch (-12/+12 semitonos)
  y velocidad (0.25x-4x) mientras suena.
- **Browser**: reproduce un vídeo de YouTube gratuito (nada de pago/alquiler),
  prueba el slider de pitch (-12/+12) y tempo (0.5x-2x). La fila "AdBlock:"
  muestra cuántos campos de anuncio se han limpiado.

## Estructura

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt
│   └── rubberband/                    — vendorizado, no editar (import de terceros)
├── assets/
│   └── soundtouch-scriptprocessor.js  — motor de pitch del Modo B, bundleado con esbuild
└── java/
    ├── com/breakfastquay/rubberband/
    │   └── RubberBandStretcher.kt     — binding 1:1 con el bridge JNI oficial (Modo A)
    └── com/realtimetranspose/
        ├── MainActivity.kt            — shell de pestañas (Files / Browser), selector de archivo (SAF)
        ├── audio/                     — Modo A
        │   ├── AudioFileDecoder.kt    — MediaExtractor/MediaCodec → PCM float
        │   ├── PitchShiftEngine.kt    — semitonos/velocidad sobre RubberBandStretcher
        │   └── TransposePlayer.kt     — pipeline decode → shift → AudioTrack
        ├── browser/                   — Modo B
        │   ├── AdBlockScript.kt       — limpieza de JSON del reproductor de YouTube
        │   ├── BrowserProbeBus.kt     — estado observable (hook/nivel/eventos AdBlock)
        │   └── TransposeJsBridge.kt   — puente JS → Kotlin
        └── ui/
            ├── FilePlayerScreen.kt    — Compose: picker, play/pause, sliders (Modo A)
            └── BrowserScreen.kt       — Compose: WebView + sliders (Modo B)
```
