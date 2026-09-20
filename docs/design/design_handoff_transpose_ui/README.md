# Handoff: Transpose — rediseño de UI (Android / Jetpack Compose)

## Overview
Rediseño visual completo de **Transpose**, app Android personal que cambia pitch y tempo de audio de forma independiente, con dos fuentes: archivo local (**Files**) y YouTube en un WebView integrado (**Browser**). Ninguna función cambia — solo la capa visual y la agrupación de controles. La app actual usa Material3 sin personalizar; este rediseño introduce un tema oscuro propio ("Nocturne"), tipografía Inter, acento blurple `#9184D9` usado como línea y resplandor (nunca como relleno grande), y controles de pitch/velocidad como **stepper − / + + barra visual**.

## About the Design Files
Los archivos en `design/` son **referencias de diseño hechas en HTML** — prototipos que muestran aspecto y comportamiento previstos, **no código para copiar**. La tarea es **recrear estas pantallas en el codebase real de la app** (Kotlin + Jetpack Compose Material3), usando sus patrones existentes: `MainActivity`, el `TabRow`, los ViewModels/estado ya presentes y el pipeline de audio actual. Ningún contrato funcional del briefing cambia (ver `design/ui-redesign-brief.md`).

- `design/Transpose App.dc.html` — el prototipo funcional (ambas pestañas + estado vacío/error). Abrir en un navegador para ver e interactuar.
- `design/nocturne-styles.css` — hoja de tokens del sistema de diseño (colores, ramps, tipografía, espaciado, radios, sombras). Fuente de verdad de los valores.
- `design/android-frame.jsx` — solo el marco de dispositivo del prototipo; **no se implementa**.
- `design/ui-redesign-brief.md` — el briefing funcional original.

## Fidelity
**Alta fidelidad.** Colores, tipografía, tamaños y espaciados son finales; reprodúcelos con exactitud. El marco del dispositivo, la barra de estado y la barra de gestos del prototipo son del sistema operativo, no de la app.

---

## Design Tokens

Defínelos una vez en un `Theme.kt` (ColorScheme oscuro + Typography) y no uses literales sueltos en las pantallas.

### Colores
| Token | Hex | Uso |
| --- | --- | --- |
| `bg` | `#161826` | Fondo de toda la app |
| `surface` | `#232532` | Tarjetas (tarjeta de archivo, panel de diagnóstico) |
| `text` | `#E9E9ED` | Texto principal |
| `textMuted` | `#E9E9ED` al 55% alpha | Etiquetas secundarias, tiempos |
| `textFaint` | `#E9E9ED` al 45% alpha | Rótulos de sección en mayúsculas |
| `divider` | `#E9E9ED` al 16% alpha | Bordes, líneas de pestañas, tracks apagados |
| `accent` | `#9184D9` | Acento: bordes de botón, relleno de slider, thumb, resplandor |
| `accent300` | `#D2CEFD` | Texto de acento a tamaño pequeño (valores mono, estado del bucle) |
| `accent400` | `#B5ABFC` | Icono de la tarjeta de archivo, marcas A/B |
| `accent700` | `#5D5294` | Bordes de acento tenues |
| `accent900` | `#2B2741` | Relleno del cuadro de icono de archivo |
| `neutral900` | `#292B31` | Fondo del área del WebView |
| `error` | `#F0A1AE` (texto/icono), borde `#E2647A` al 35% | Mensaje de error |

Reglas del sistema: nunca negro ni blanco puros; el acento nunca se usa como relleno de área grande; elevación = borde de 1px + oscuridad ambiental, no sombras apiladas.

### Tipografía
- Familia única: **Inter** (400 cuerpo, 500 titulares — no subir de 500).
- Valores numéricos monoespaciados: **JetBrains Mono** (tiempos `mm:ss`, estado del bucle, valores de Browser, líneas de diagnóstico).
- Escala usada: 40sp (valor de pitch/velocidad en Files), 19sp (título de estado vacío), 14sp (botones, valor mono en Browser), 13sp (nombre de archivo, pestañas, texto de placeholder), 12.5sp (cuerpo secundario), 12sp (estado del bucle, botones pequeños), 11sp (rótulos en mayúsculas, metadatos, diagnóstico), 10.5sp (líneas de diagnóstico mono).
- Rótulos de sección: 11sp, MAYÚSCULAS, `letterSpacing` 0.12em, color `textFaint`.
- Wordmark de cabecera: "TRANSPOSE", 11sp, MAYÚSCULAS, `letterSpacing` 0.22em, `textMuted`.
- Números grandes: `fontFeatureSettings("tnum")` / tabular nums.

### Espaciado y forma
- Escala (densidad 0.7×): 2.8 / 5.6 / 8.4 / 11.2 / 16.8 / 22.4 dp. En la práctica: padding de pantalla **16dp**, separación entre bloques **18dp**, gap interno de bloque **10dp**.
- Radios: `sm 4dp`, `md 8dp` (tarjetas, botones), `lg 14dp`. Botones circulares: 50%.
- Sombra/elevación: borde `1dp` `divider`; el único "glow" es un resplandor de acento al 20–25% detrás del botón de play y de los thumbs de slider.

---

## Screens / Views

Pantalla única (`MainActivity`), cabecera fija + `TabRow` de dos pestañas. El estado de pestaña y el de Browser deben sobrevivir a rotación (ya lo hacen hoy — mantenerlo). Todo el layout debe funcionar en vertical y apaisado: en apaisado, coloca el panel de controles y el contenido en dos columnas en lugar de apilar.

### Cabecera (común)
- Fila: padding `10dp` arriba, `16dp` lados. Wordmark "TRANSPOSE" a la izquierda; resto vacío.
- Debajo, pestañas: `Row` de dos pesos iguales, margen lateral `16dp`, borde inferior `1dp divider`.
  - Pestaña: alto ~44dp, texto 13sp centrado. Activa: color `accent` + indicador inferior sólido de `2dp` en `accent` con el ancho de la pestaña. Inactiva: `textMuted`.
  - Hover/press: tinte `text` al 6%.

### Pantalla A — Files, con archivo cargado
Columna scrollable, padding 16dp, separación 18dp entre bloques.

1. **Tarjeta de archivo** — `surface`, borde `1dp divider`, radio `md`, padding `10dp/11dp`, gap 11dp.
   - Cuadro 34×34dp, radio `sm`, fondo `accent900`, icono de nota musical 16dp trazo 1.8 en `accent400`.
   - Columna: nombre de archivo 13sp, una línea, elipsis al final; debajo metadatos 11sp `textMuted` (`"m4a · 4:14 · 48 kHz estéreo"`).
   - Botón texto "Cambiar" 12sp en `accent` (ghost). Acción: abre el selector de archivos (`ACTION_OPEN_DOCUMENT`). En estado vacío el equivalente es "Elegir archivo".
2. **Barra de progreso** — alto táctil 44dp, arrastrable en cualquier punto (tap = seek inmediato).
   - Track: 3dp, radio 2dp, `text` al 14%.
   - Región del bucle A→B: rectángulo desde A hasta B, de borde a borde vertical 13dp/13dp, `accent` al 20%.
   - Relleno de progreso: 3dp `accent` desde el origen hasta la posición.
   - Thumb: círculo 13dp `accent`, resplandor `0 0 12dp accent 70%`.
   - Marcas A y B: línea vertical 1dp × 12dp en `accent400`, ancladas arriba del track; solo visibles si el punto está marcado.
   - Debajo: fila mono 11sp `textMuted`, posición a la izquierda, duración a la derecha (`m:ss`).
3. **Transporte** — un único botón circular centrado, 66dp, fondo transparente, borde `1dp accent`, icono play/pause 24dp en `accent`, resplandor `0 0 28dp accent 22%`. Hover 12% / press 22% de tinte de acento.
4. **Fila A-B** — `Row` con `SpaceBetween`:
   - Izquierda: botones "A" y "B" (38dp de ancho, alto ~30dp, radio `md`, 12sp). Borde+texto `accent` si el punto está marcado; borde `divider` + texto normal si no.
   - Icono papelera 16dp en botón circular ghost 34dp — limpia A y B. **Deshabilitado (opacidad 0.35, sin eventos) cuando no hay ningún punto marcado.**
   - Derecha: estado del bucle, mono 12sp, `nowrap`, nunca elipsado:
     - sin puntos → `"desactivado"`, color `textMuted`
     - solo A → `"A=0:38 · falta B"`
     - solo B → `"B=1:02 · falta A"`
     - bucle válido (B > A) → `"0:38 → 1:02"`, color `accent300`
     - Si B ≤ A el bucle no se activa: muestra el texto de "falta" correspondiente en lugar de fallar en silencio.
5. **Pitch** — rótulo "PITCH" + botón texto "Reset" a la derecha (12sp, ghost; atenuado a 0.35 y sin eventos cuando pitch = 0).
   - Fila: botón circular 38dp "−", valor centrado 40sp tabular con signo (`+3`, `0`, `-5`) y subtítulo 11sp `textMuted` "semitonos", botón circular 38dp "+".
   - Botones: borde `1dp accent`, icono 16dp trazo 2. Al alcanzar el límite del rango (−12 / +12) el botón se atenúa a 0.4 con borde `divider` y deja de responder.
   - Debajo, **barra visual también arrastrable**: alto 26dp, track 3dp `text 14%`, marca de centro 1dp × 14dp `text 30%` al 50%, relleno de acento que crece **desde el centro** hacia el valor, thumb 13dp fondo `bg` + borde `1dp accent` + resplandor 50%.
   - Rango −12…+12 semitonos, paso entero. Arrastre = redondeo al semitono más cercano.
6. **Velocidad** — misma estructura. Valor `1.00×` a 40sp; subtítulo "0.25× – 4×". Paso de los botones **±0.05×**. La barra mapea el valor en **escala logarítmica** entre 0.25× y 4×, de modo que 1.00× cae exactamente en el centro; el arrastre redondea a múltiplos de 0.05. Reset → 1.00×.

Pitch y velocidad son independientes; ambos aplican en caliente mientras suena.

### Pantalla B — Files, estado vacío (+ slot de error)
- Mismas cabecera y pestañas.
- Bloque centrado: círculo 78dp con borde `1dp accent700` y resplandor `0 0 40dp accent 16%`, icono de nota 28dp en `accent`; título 19sp "Sin archivo cargado"; subtítulo 12.5sp `textMuted`, máx ~26 caracteres por línea: "Elige un mp3, m4a, wav o flac del dispositivo para empezar a practicar."; botón outline primario "Elegir archivo" (borde `1dp accent`, texto `accent`, radio `md`).
- **Slot de error genérico** (visible solo cuando hay error, en cualquiera de los dos estados de Files): caja con borde `1dp` `#E2647A` al 35%, radio `md`, padding 10/11dp, icono de aviso 16dp `#F0A1AE`; título 12.5sp en `#F0A1AE` y detalle técnico 11sp mono `textMuted` con `word-break`. Un solo componente reutilizable que acepta título + detalle crudo del pipeline.

### Pantalla C — Browser
Columna: panel de control (altura de contenido) + WebView ocupando el resto.

1. **Panel de control** — padding `12dp/16dp/14dp`, gap 12dp, borde inferior `1dp divider`.
   - Fila **Pitch**: rótulo de 52dp de ancho ("PITCH", 11sp mayúsculas `textFaint`) · barra deslizante fina (alto 24dp, mismas reglas que arriba, thumb 12dp sin resplandor) · botón circular 28dp "−" · valor mono 14sp `accent300` en caja de 40dp centrada · botón circular 28dp "+" · botón ghost con icono de reset circular 14dp (atenuado cuando el valor es 0).
   - Fila **Tempo**: idéntica, rango **0.5×–2×** (no cambiar), paso ±0.05×, barra logarítmica con 1.00× en el centro, reset → 1.00×.
   - Fila de estado: punto de 5dp en `accent` + texto 11sp `textFaint` "Audio enganchado" (nowrap) a la izquierda; a la derecha botón ghost 11sp "Diagnóstico" / "Ocultar".
     - El punto refleja el estado real del hook: acento si OK, `textMuted` si no hay enganche, `#E2647A` si error.
   - **Diagnóstico plegado por defecto.** Al abrirlo: caja `surface`, borde `1dp divider`, radio `md`, padding 9/10dp, tres líneas mono 10.5sp `textMuted` con el valor en `accent300`:
     - `Hook: OK ctxState=running`
     - `Nivel: 0.42 (ctx=running, muestras=8192)`
     - `AdBlock: prune:adPlacements×3, network-blocked:doubleclick.net×2`
   - Esta información deja de ser visible de entrada: es diagnóstico interno tras un toggle.
2. **WebView** — fondo `neutral900`. Encima, barra fina de 8/12dp con borde inferior `divider`: icono de globo 13dp en `accent`, URL actual mono 11sp `textMuted` con elipsis. El WebView real carga `m.youtube.com` y se comporta con normalidad; no se rediseña su contenido. El bloqueador de anuncios sigue actuando en silencio (sin control manual).
3. Pitch/tempo persisten al cambiar de vídeo dentro del WebView.

---

## Interactions & Behavior
- **Pestañas**: cambio inmediato, sin animación de deslizamiento; el indicador de 2dp puede animar su posición (200ms, easing estándar).
- **Play/Pause**: un solo botón que alterna icono; sin cambio de tamaño.
- **Seek**: arrastre continuo sobre la barra; la reproducción sigue sonando mientras se arrastra.
- **Bucle**: "A"/"B" marcan el punto en la posición actual redondeada al segundo. Con bucle válido, al llegar a B la reproducción salta a A. La papelera borra ambos.
- **Steppers**: pulsación única = un paso. Recomendado añadir auto-repetición en pulsación larga (retardo 400ms, repetición cada 80ms) — mejora clara sobre el slider para ajuste fino.
- **Estados de control**: todo botón fuera de rango o sin efecto pasa a opacidad 0.4 (límites de rango) / 0.35 (resets y papelera) y deja de recibir toques, en vez de desaparecer.
- **Foco de teclado/mando**: anillo de 2dp `accent` con offset 2dp. Nunca el foco por defecto del sistema.
- **Press**: tinte de acento al 22% en botones outline; 6–14% de `text` en secundarios.
- **Rotación**: nada se reinicia; en apaisado el panel de Browser va a la izquierda y el WebView a la derecha, y en Files las secciones pitch/velocidad pueden ir en dos columnas.
- **Objetivos táctiles**: mínimo 44dp — los botones de 28dp y 34dp deben llevar área táctil ampliada.

## State Management
Estado que la UI necesita (el motor de audio ya existe):
- `selectedTab: Files | Browser`
- Files: `fileUri`, `fileName`, `fileMeta`, `isPlaying`, `positionMs`, `durationMs`, `loopA: Int?`, `loopB: Int?`, `pitchSemitones: Int (-12..12)`, `speed: Float (0.25..4)`, `errorTitle: String?`, `errorDetail: String?`
- Browser: `browserPitchSemitones: Int (-12..12)`, `tempo: Float (0.5..2)`, `hookStatus`, `levelInfo`, `adBlockEvents`, `diagnosticsExpanded: Boolean` (por defecto `false`), `currentUrl`
- Derivados: `loopActive = loopA != null && loopB != null && loopB > loopA`; etiqueta del bucle; porcentajes de las barras.
- Todo en `rememberSaveable` / ViewModel para sobrevivir a rotación.

## Assets
Ningún bitmap. Todos los iconos son vectoriales de trazo 1.5–2 sobre grid de 24: nota musical, play, pause, menos, más, papelera, reset circular, aviso, globo, pantalla de vídeo. Usar **Phosphor Icons** (o el equivalente Material Symbols con peso ligero) manteniendo el grosor de trazo. Fuentes: Inter y JetBrains Mono — empaquetar como recursos locales, no descargar en runtime.

## Files
- `design/Transpose App.dc.html` — prototipo interactivo (abrir en navegador).
- `design/nocturne-styles.css` — tokens exactos.
- `design/ui-redesign-brief.md` — briefing funcional (contratos que no cambian).
- `design/android-frame.jsx` — marco del dispositivo del prototipo; ignorar en la implementación.
