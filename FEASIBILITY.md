# FEASIBILITY.md

Fase 0 (investigación) del spec de Real-Time Transpose. Basado en la documentación
oficial de Android (developer.android.com) consultada en septiembre de 2026.

## Resumen ejecutivo

| Pregunta | Respuesta |
|---|---|
| ¿Puede una app de terceros capturar el audio de reproducción de otra app en Android moderno, sin root? | **SUPPORTED** (API pública `AudioPlaybackCaptureConfiguration`, Android 10+) |
| ¿Puede la app fuente (Spotify, YouTube, etc.) bloquear esa captura? | **SÍ**, mediante `setAllowedCapturePolicy` / `android:allowAudioPlaybackCapture="false"` — decisión de cada app, no nuestra |
| ¿Spotify bloquea la captura? | **UNKNOWN — pendiente del POC** (hay reportes públicos de que sí, pero no confirmado en la versión/dispositivo actuales) |
| ¿YouTube (app de vídeo) bloquea la captura? | **UNKNOWN — pendiente del POC**, pero indicios indirectos (grabadores de pantalla nativos capturan su audio) sugieren que no |

## APIs investigadas

### `AudioPlaybackCaptureConfiguration` — SUPPORTED
Introducida en Android 10 (API 29). Es la API pública y soportada para este caso de
uso exacto (no es un hack ni una API privada).

- Requiere un objeto `MediaProjection` válido (ver más abajo) para construirse.
- Se filtra por `AudioAttributes.usage`: solo puede capturarse `USAGE_MEDIA`,
  `USAGE_GAME` y `USAGE_UNKNOWN`. Nunca `USAGE_VOICE_COMMUNICATION`, `USAGE_ALARM`,
  `USAGE_NOTIFICATION`, etc. — esto es una restricción de la plataforma, no
  configurable.
- Se puede filtrar además por UID (`addMatchingUid` / `excludeUid`) para limitar a
  o excluir apps concretas, aunque para el POC capturamos cualquier
  `USAGE_MEDIA`/`USAGE_GAME`/`USAGE_UNKNOWN` sin filtrar por app.
- **La app fuente controla si permite ser capturada.** Con `targetSdkVersion` ≥ 29
  (caso de Spotify y YouTube hoy), el permiso de captura es `ALLOW_CAPTURE_BY_ALL`
  **por defecto** — la app fuente tiene que optar explícitamente por bloquearlo
  (`setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)` en tiempo de ejecución, o
  `android:allowAudioPlaybackCapture="false"` en su manifest). Es una decisión que
  Spotify/YouTube pueden cambiar entre versiones sin avisar — de ahí que esto se
  trate como "unknown" y no como algo que se pueda dar por sentado.
- Requiere permiso `RECORD_AUDIO` en el manifest de nuestra app (aunque no se use
  el micrófono — `AudioPlaybackCaptureConfiguration` se consume vía `AudioRecord`).

### `MediaProjection` — SUPPORTED, con restricciones importantes en Android 14/15
Es el mecanismo de consentimiento del sistema: el usuario ve el diálogo estándar de
Android ("¿Empezar a grabar o transmitir?") y lo acepta o rechaza explícitamente.
No hay forma de evitar ni ocultar este diálogo (ni se debe intentar).

Restricciones relevantes de plataforma que afectan al diseño de la app:

- **Android 14 (API 34)+**: la app debe declarar el permiso
  `FOREGROUND_SERVICE_MEDIA_PROJECTION` y tener un `Service` en foreground con
  `foregroundServiceType="mediaProjection"` activo en el momento de construir la
  captura, o el sistema lanza `SecurityException`.
- **Android 14+**: un `BroadcastReceiver` de `BOOT_COMPLETED` no puede arrancar un
  foreground service de tipo `mediaProjection` (ni `microphone`). No es un problema
  para este POC (todo se arranca desde una `Activity` en primer plano), pero limita
  cualquier futura idea de "arrancar solo al encender el móvil".
- **Android 15 (API 35)+**: el token de `MediaProjection` **ya no se puede
  cachear entre reinicios de la app** — el usuario debe volver a dar el
  consentimiento cada vez que se inicia una nueva sesión de captura. Esto es
  intencional por privacidad y no hay forma de evitarlo; el diseño de la UI debe
  asumir que "Start Capture" siempre puede disparar el diálogo del sistema.

### `AudioRecord` — SUPPORTED
Consumidor estándar de la captura: se construye con
`AudioRecord.Builder().setAudioPlaybackCaptureConfig(...)` en vez de
`setAudioSource(MIC)`. El resto de la API (buffer, `read()`, `startRecording()`,
`release()`) es igual que para grabar del micrófono.

### `AudioTrack` — SUPPORTED (no usado todavía)
Necesario a partir de la Fase 2 (passthrough) para reproducir el audio capturado.
No forma parte de este POC de solo-captura.

### `AudioEffect` / `Visualizer` — NOT NEEDED
Se evaluaron como alternativa (Fase 35 del spec) pero no aplican: `Visualizer` solo
da una representación (FFT/waveform) del audio de sesión 0 (mezcla global), no un
stream PCM completo y no permite modificarlo — inútil para pitch-shifting real.
`AudioEffect` opera sobre efectos de audio (eco, ecualizador) insertados en una
sesión existente, no sobre captura entre apps.

### `AAudio` / `Oboe` / `OpenSL ES` — DEFERRED (Fase 3+)
Relevantes para la Fase 3 (motor de pitch-shifting en C++), no para este POC. Oboe
(wrapper de Google sobre AAudio) es la opción recomendada cuando lleguemos ahí por
su manejo de baja latencia y su compatibilidad hacia atrás con OpenSL ES en
dispositivos antiguos. No se ha investigado en profundidad todavía — corresponde a
la Fase 0 de esa fase futura.

### `MediaSession` — NOT NEEDED PARA CAPTURA
Permite leer metadata de reproducción (título, artista, estado play/pause) de apps
que exponen un `MediaSession`, pero **no da acceso al audio en sí**. No se necesita
para el objetivo de este proyecto (el spec es explícito: no necesitamos saber título
ni artista). Se descarta.

## Requisitos de manifest confirmados

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".audio.AudioCaptureService"
    android:foregroundServiceType="mediaProjection" />
```

`minSdk` = 29 (obligatorio: `AudioPlaybackCaptureConfiguration` no existe antes).

## Qué NO se ha investigado todavía (fuera de alcance de esta fase)

- Comportamiento exacto con Bluetooth y cambios de dispositivo de salida (Tests C–D–F
  del spec) — requiere el móvil físico en mano, se hace en la siguiente iteración
  del POC una vez confirmado Test A.
- Consumo de batería / CPU en sesiones largas (Fase 7) — irrelevante hasta que haya
  procesamiento real, no solo captura.
- Motor de pitch-shifting (Rubber Band vía NDK/JNI) — Fase 3, bloqueada por el
  resultado de este POC.

## Siguiente paso

Ejecutar la app mínima de `app/` (ya construida) en un dispositivo físico con
Spotify y con YouTube, y rellenar la sección "Resultados del POC" de este documento
con lo observado en cada test (A–F del spec).

## Resultados del POC

_Pendiente — se completa tras probar en dispositivo físico._

| Test | Spotify | YouTube |
|---|---|---|
| A — Audio received con reproducción normal | | |
| B — Pantalla bloqueada | | |
| C — Salida por Bluetooth | | |
| D — Salida por cable | | |
| E — Cambio de canción/vídeo | | |
| F — Pausa | | |
