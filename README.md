# Transpose

Proyecto personal. Cambia el tono y la velocidad de reproducción de forma
independiente (tipo "Transpose: Pitch, Speed Control"), sobre (A) archivos de
audio locales y, más adelante, (B) vídeos de YouTube abiertos en un navegador
integrado.

Historia del proyecto: empezó como un intento de captura de audio en tiempo
real entre apps (ver [FEASIBILITY.md](FEASIBILITY.md)), pausado al confirmar
que Android no permite sustituir limpiamente el audio de otra app sin
permisos de sistema. Pivotado el 2026-09-19 — ver
[docs/specs/2026-09-19-transpose-clone-design.md](docs/specs/2026-09-19-transpose-clone-design.md)
para el diseño actual y por qué el modo navegador sí evita ese límite.

## Estado actual: Modo A (archivo local) implementado, sin probar en dispositivo

Motor de pitch/tempo: [Rubber Band Library](https://breakfastquay.com/rubberband/)
v3.3.0 (GPLv2+, vendorizada en `app/src/main/cpp/rubberband/`, uso personal —
sin publicación en Play Store) vía NDK/CMake, usando su propio bridge JNI
oficial sin código de puente propio.

## Cómo probarlo

```bash
./gradlew installDebug
```

Abre la app, elige un archivo de audio local, y prueba los sliders de pitch
(-12/+12 semitonos) y velocidad (0.25x-4x) mientras suena.

## Estructura

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt
│   └── rubberband/            — vendorizado, no editar (import de terceros)
└── java/
    ├── com/breakfastquay/rubberband/
    │   └── RubberBandStretcher.kt   — binding 1:1 con el bridge JNI oficial
    └── com/realtimetranspose/
        ├── MainActivity.kt          — selector de archivo (SAF)
        ├── audio/
        │   ├── AudioFileDecoder.kt  — MediaExtractor/MediaCodec → PCM float
        │   ├── PitchShiftEngine.kt  — semitonos/velocidad sobre RubberBandStretcher
        │   └── TransposePlayer.kt   — pipeline decode → shift → AudioTrack
        └── ui/
            └── FilePlayerScreen.kt  — Compose: picker, play/pause, sliders
```
