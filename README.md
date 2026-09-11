# Real-Time Transpose — Audio Capture POC

Proyecto personal. Objetivo: capturar en tiempo real el audio que reproduce otra
app de Android y aplicarle pitch-shifting sin cambiar el tempo — sin tocar ninguna
API de la app fuente, sin root y sin modificarla.

**Cambio de alcance (2026-09-11):** la app se pensó originalmente optimizada para
Spotify, pero el POC de captura confirmó que Spotify bloquea
`AudioPlaybackCaptureConfiguration` a nivel de plataforma (`ALLOW_CAPTURE_BY_NONE`)
— no es sorteable sin root/ingeniería inversa, ambos fuera de alcance. **YouTube es
ahora el objetivo principal** (funciona sin bloqueo, confirmado en dispositivo
real). Ver [FEASIBILITY.md](FEASIBILITY.md) para la investigación completa y la
evidencia del POC.

## Estado actual: Fase 1 completada — pasando a Fase 2 (Passthrough)

Fase 1 (solo captura, sin procesar) confirmada y funcionando. Siguiente paso:
reproducir el audio capturado sin modificarlo, para validar el enrutado completo
antes de meter el motor de pitch-shifting.

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
