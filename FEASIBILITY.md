# FEASIBILITY.md

Fase 0 (investigación) del spec de Real-Time Transpose. Basado en la documentación
oficial de Android (developer.android.com) consultada en septiembre de 2026.

## Resumen ejecutivo

| Pregunta | Respuesta |
|---|---|
| ¿Puede una app de terceros capturar el audio de reproducción de otra app en Android moderno, sin root? | **SUPPORTED** (API pública `AudioPlaybackCaptureConfiguration`, Android 10+) |
| ¿Puede la app fuente (Spotify, YouTube, etc.) bloquear esa captura? | **SÍ**, mediante `setAllowedCapturePolicy` / `android:allowAudioPlaybackCapture="false"` — decisión de cada app, no nuestra |
| ¿Spotify bloquea la captura? | **CONFIRMADO: SÍ la bloquea** (Test A, dispositivo real, 2026-09-11) |
| ¿YouTube (app de vídeo) bloquea la captura? | **CONFIRMADO: NO la bloquea** (Test A, mismo dispositivo/sesión) |

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

Dispositivo: Poco F7 Pro (HyperOS), Android con `targetSdk`/plataforma actual.
Fecha: 2026-09-11.

| Test | Spotify | YouTube |
|---|---|---|
| A — Audio received con reproducción normal | ❌ **BLOQUEADO** | ✅ **FUNCIONA** |
| B — Pantalla bloqueada | pendiente | pendiente |
| C — Salida por Bluetooth | pendiente | pendiente |
| D — Salida por cable | pendiente | pendiente |
| E — Cambio de canción/vídeo | pendiente | pendiente |
| F — Pausa | pendiente | pendiente |

### Test A — Spotify: BLOQUEADO

`AudioRecord` se construye e inicia sin errores (`REMOTE_SUBMIX`, 44100 Hz, estéreo),
y `read()` no deja de recibir buffers — pero el contenido es silencio. Confirmado
por dos vías independientes:

1. **Logcat del sistema** (`AudioRecordImpl`, no es nuestro código):
   ```
   [audioRecordData][mute] 19s(f:0 m:19021 s:0)
   ```
   `f` (frames "fine"/reales) se queda en 0 mientras `m` (frames silenciados por
   política de captura) crece sin parar, con Spotify sonando de fondo.
2. **UI de la app**: `Capture state = Waiting for audio`, `Audio received = FALSE`,
   `RMS level = 0,0000` de forma sostenida durante >7 millones de frames leídos.

Esto es exactamente el comportamiento documentado para una app fuente con
`ALLOW_CAPTURE_BY_NONE`: el sistema no lanza ningún error, simplemente entrega
silencio en su lugar. Confirma los reportes públicos — Spotify bloquea
`AudioPlaybackCaptureConfiguration` en este dispositivo/versión.

### Test A — YouTube: FUNCIONA

Al reproducir un vídeo en la app oficial de YouTube (sin detener la sesión de
captura activa — la configuración no está filtrada a una app concreta), el mismo
log cambia de categoría:

```
[audioRecordData][mute] 14s(f:0 m:14004 s:0)
[audioRecordData][fine] 5s(f:5155 m:14329 s:0)     ← YouTube empieza a sonar aquí
[audioRecordData][fine] 20s(f:20132 m:15142 s:0)
```

`f` crece con el tiempo real transcurrido mientras `m` se congela — el audio ya no
se marca como silenciado. La UI de la app lo confirma: `Capture state = Processing`,
`Audio received = TRUE`, `RMS level = 0,2645` (nivel real, no cero).

### Implicación para el alcance del proyecto

El spec original describe la app como "especialmente optimizada para Spotify".
Con Spotify bloqueando la captura a nivel de plataforma (no hay forma legítima de
sortearlo sin root/ingeniería inversa, ambas explícitamente prohibidas en el
spec), **YouTube pasa a ser el objetivo viable real**, y Spotify queda descartado
salvo que una versión futura de la app cambie su política de captura (fuera de
nuestro control).

## Límite de plataforma adicional: no se puede sustituir el audio original (solo superponerlo)

Investigado el 2026-09-11, sin tocar código del motor de audio, a petición del
usuario, antes de construir la Fase 2 (Passthrough).

**Pregunta:** ¿puede la app silenciar la reproducción original de la app fuente
mientras reproduce la copia con pitch-shifting, para lograr una sustitución limpia
(el pipeline del punto 1 del spec: original → shift → auriculares, sin oír las dos
a la vez)?

**Respuesta: NO, con las APIs públicas disponibles para una app de terceros sin
privilegios de sistema.**

`AudioMix` (la clase interna sobre la que se construye `AudioPlaybackCaptureConfiguration`)
define dos modos de enrutado:

- `ROUTE_FLAG_RENDER` — el audio se redirige y **deja de sonar** por su destino
  original. Esto es lo que se necesitaría para una sustitución limpia.
- `ROUTE_FLAG_LOOP_BACK_RENDER` — el audio **sigue sonando normalmente** en su
  destino original, y además se entrega una copia a quien la pide. Esto es lo
  único a lo que da acceso `AudioPlaybackCaptureConfiguration`.

Redirigir de verdad (`ROUTE_FLAG_RENDER`) solo es posible usando la API de sistema
`android.media.audiopolicy.AudioPolicy` con el permiso `MODIFY_AUDIO_ROUTING`, que
es un permiso de firma/sistema — no obtenible por una app de terceros instalada
normalmente (requiere ser app de sistema o tener el dispositivo rooteado). Lo
confirma la propia discusión de los mantenedores de scrcpy evaluando este mismo
problema para forwarding de audio:
[Genymobile/scrcpy#4380](https://github.com/Genymobile/scrcpy/issues/4380) — cita
literal: *"If `AudioMix.ROUTE_FLAG_RENDER` is used instead of
`AudioMix.ROUTE_FLAG_LOOP_BACK_RENDER`, the audio no longer plays on the device
itself"*, señalando que ese modo requiere la API de sistema, no la pública.

**Consecuencia práctica:** con las restricciones del proyecto (sin root, sin APIs
privadas, sin modificar la app fuente), el original **siempre sonará superpuesto**
a la copia transportada. No se pudo confirmar (sin implementar y medir en
dispositivo) si bajar el volumen general de música del sistema atenúa también lo
que se captura, o si el punto de captura está antes de esa atenuación — quedaría
pendiente de verificación empírica si el proyecto se retoma.

## Estado del proyecto: PAUSADO (2026-09-11)

A petición del usuario, tras confirmar esta limitación. La Fase 1 (POC de
captura) quedó completa y funcionando para YouTube. No se ha empezado la Fase 2
(Passthrough) ni ninguna posterior. Para retomarlo, el primer paso sería la
verificación empírica de volumen mencionada arriba, ya que determina si el
resultado final es mínimamente usable o no.
