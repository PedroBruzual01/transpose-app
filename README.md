# Real-Time Transpose — Audio Capture POC

Proyecto personal. Objetivo actual: responder si es viable capturar en tiempo real
el audio que Android reproduce desde otra app (Spotify / YouTube) para,
eventualmente, aplicarle pitch-shifting sin cambiar el tempo — sin tocar la API de
Spotify, sin root y sin modificar la app fuente.

Ver [FEASIBILITY.md](FEASIBILITY.md) para la investigación de las APIs de Android
implicadas y el resultado del POC.

## Estado actual: Fase 1 — POC de captura

Solo captura, sin pitch-shifting todavía. No construir el resto de la app hasta que
`FEASIBILITY.md` confirme que la captura es fiable con Spotify y/o YouTube.

## Cómo probarlo

Requiere un dispositivo Android físico (API 29+) con depuración USB activada y
Spotify (y opcionalmente YouTube) instalados.

```bash
./gradlew installDebug
```

1. Abre Spotify (o YouTube) y reproduce algo.
2. Abre "Audio Capture Test" en el móvil.
3. Toca **Start Capture** y acepta el diálogo del sistema ("Start recording or
   casting?") — es el diálogo estándar de Android, no lo ocultamos.
4. Observa en pantalla: `Capture state`, `Audio received`, `RMS level`,
   `Sample rate`, `Channels`.

## Estructura

```
app/src/main/java/com/realtimetranspose/
├── MainActivity.kt          — pide el permiso de MediaProjection, arranca el servicio
├── audio/
│   ├── AudioCaptureManager.kt  — AudioRecord + AudioPlaybackCaptureConfiguration, RMS
│   └── AudioCaptureService.kt  — foreground service (obligatorio para MediaProjection)
└── ui/
    └── MainScreen.kt         — UI Compose de estado
```
