# Transpose — UI redesign brief

Documento funcional completo de la app, para usar como entrada de un
rediseño visual (Claude Design u otra herramienta). Describe **qué existe y
qué hace cada cosa**, no cómo debería verse — el rediseño tiene libertad
total de estilo visual mientras conserve cada función descrita aquí.

## Qué es la app

App personal Android (no publicada en Play Store) llamada **Transpose**.
Cambia el tono (pitch) y la velocidad (tempo) de audio de forma
**independiente** — puedes subir el tono sin acelerar la canción, o
ralentizarla sin que suene más grave, como hace un pedal de tono para
músicos. Dos fuentes de audio distintas, en dos pestañas:

- **Files**: un archivo de audio local (mp3, m4a, wav, flac...) elegido por
  el usuario.
- **Browser**: un vídeo de YouTube reproducido dentro de un navegador
  integrado en la propia app.

Usuario objetivo: músicos que quieren practicar una canción en otra
tonalidad, o más lenta, sin perder la naturalidad del sonido.

## Estado visual actual

Ahora mismo la app usa Jetpack Compose Material3 **sin ninguna
personalización** — colores, tipografía y componentes por defecto del
sistema, sin logo, sin paleta propia, sin iconografía. Es un lienzo en
blanco para el rediseño.

## Estructura de navegación

Una sola pantalla (`MainActivity`) con una barra de pestañas fija arriba
(`TabRow` de Material3) con dos pestañas: **"Files"** y **"Browser"**. Se
cambia de pestaña tocándola; el contenido de abajo cambia según la pestaña
activa. No hay más pantallas, ni menús, ni configuración, ni onboarding.

Detalle técnico relevante para el diseño: la pestaña seleccionada y el
estado de la pestaña Browser (el vídeo cargado, seguir sonando) **se
mantienen al girar la pantalla** — el diseño debe funcionar bien tanto en
vertical como en apaisado, sin asumir que un giro reinicia nada.

---

## Pestaña 1: Files (reproductor de archivo local)

### Propósito
Cargar un archivo de audio del propio dispositivo y reproducirlo con
control de tono y velocidad independientes, más un bucle A-B para repetir
un fragmento concreto en práctica.

### Estados de la pantalla

**Estado vacío (sin archivo cargado):**
- Título "Transpose"
- Un botón: **"Elegir archivo de audio"**
- Nada más visible — el resto de controles aparecen solo tras cargar un
  archivo

**Estado con archivo cargado:**
Todo lo del estado vacío, más (el botón cambia de texto a "Cambiar
archivo"):
1. Nombre del archivo cargado (texto, puede ser largo — nombres de archivo
   reales, sin truncar de forma inteligente actualmente)
2. Mensaje de error si algo falla (color de error, texto libre — ver
   "Casos de error" abajo)
3. **Controles de reproducción**
4. **Controles de bucle A-B**
5. **Controles de pitch y velocidad**

### 1. Controles de reproducción
- Botón **Play/Pause** (un solo botón que cambia de texto/icono según el
  estado — "Play" cuando está pausado, "Pause" cuando suena)
- **Barra de progreso** (slider): arrastradero para saltar a cualquier punto
  del archivo. Se puede arrastrar mientras suena.
- Debajo de la barra, a la izquierda **posición actual** (mm:ss) y a la
  derecha **duración total** (mm:ss)

### 2. Controles de bucle A-B
Pensado para repetir un fragmento concreto en bucle mientras se practica.
- Línea de estado con tres variantes posibles:
  - "Loop: desactivado" (ningún punto marcado)
  - "Loop: A=0:38, falta B" (solo el punto A marcado)
  - "Loop: 0:38 → 1:02" (bucle activo, ambos puntos marcados en orden
    correcto — empieza a repetirse automáticamente entre esos dos puntos)
- Tres botones en fila: **Set A** (marca el punto de inicio en la posición
  actual de reproducción), **Set B** (marca el punto final igual), **Clear**
  (borra ambos puntos y desactiva el bucle)
- Nota funcional: si B se marca antes que A, o en el mismo punto, el bucle
  simplemente no se activa (no hay mensaje de error explícito para este
  caso todavía — el rediseño podría mejorar esto)

### 3. Controles de pitch y velocidad
- **Pitch**: etiqueta "Pitch: <valor>" donde el valor es un entero con signo
  (ej. "+3", "-5", "0") en semitonos. Slider de -12 a +12 semitonos (24
  pasos discretos, uno por semitono). Botón **"Reset pitch"** que lo vuelve
  a 0.
- **Speed**: etiqueta "Speed: <valor>x" con dos decimales (ej. "1.00x",
  "0.75x"). Slider continuo de 0.25x a 4x. Botón **"Reset speed"** que lo
  vuelve a 1x.
- Pitch y velocidad son controles **completamente independientes** entre
  sí — cambiar uno no afecta al otro. Ambos se pueden mover mientras el
  audio suena, con efecto inmediato y audible.

### Casos de error (Files)
El mensaje de error es texto libre en color de error, puede incluir
mensajes técnicos crudos ("No se pudo abrir el archivo: ..."). Casos
conocidos: archivo sin pista de audio, formato de canales no soportado
(actualmente solo se soportan archivos mono o estéreo), fallo del
pipeline de reproducción. El rediseño puede tratar esto como un slot
genérico de "mensaje de error", sin necesidad de diseñar cada caso por
separado.

---

## Pestaña 2: Browser (reproductor de YouTube con pitch/tempo)

### Propósito
Abrir YouTube dentro de la propia app (no la app de YouTube, un navegador
integrado) y aplicar el mismo tipo de control de tono/velocidad
independientes sobre lo que se está viendo, más un bloqueador de anuncios.

### Estructura de la pantalla (de arriba a abajo)
1. **Panel de controles** (fijo, altura de contenido)
2. **Navegador** (WebView) que ocupa el resto del espacio disponible —
   carga automáticamente m.youtube.com (versión móvil de YouTube) al entrar
   en la pestaña, y el usuario navega dentro de él con normalidad (buscar,
   tocar vídeos, hacer scroll, todo el comportamiento normal de YouTube)

### Panel de controles — contenido actual

**Importante para el rediseño:** las tres primeras líneas ahora mismo son
**información de diagnóstico interna para desarrollo**, no pensada para el
usuario final. El rediseño debe decidir qué hacer con ellas — ocultarlas
detrás de un ajuste/gesto de "modo diagnóstico", moverlas a una pantalla
aparte, o eliminarlas de la vista principal:

- `Hook: <estado>` — si el enganche del audio del vídeo actual a nuestro
  motor de pitch ha funcionado o no (ej. "OK ctxState=running", "ERROR ...",
  "—" si no hay nada enganchado todavía)
- `Nivel: <número> (ctx=<estado>, muestras=<n>)` — nivel de audio detectado
  en tiempo real, para verificar que el pitch-shifting está recibiendo
  audio real
- `AdBlock: <lista de eventos>` — contador de cuántas veces se ha bloqueado
  o limpiado algo relacionado con anuncios (ej.
  "prune:adPlacements×3, network-blocked:doubleclick.net×2"), o "sin
  eventos todavía"

Controles reales, pensados para el usuario:
- **Pitch**: misma UI que en Files — etiqueta "Pitch: <valor>", slider
  -12/+12 semitonos, botón "Reset pitch"
- **Tempo**: etiqueta "Tempo: <valor>x" con dos decimales, slider de 0.5x a
  2x (rango más corto que en Files — a propósito, decisión ya tomada, no
  cambiar), botón "Reset tempo"

### Comportamiento funcional relevante para el diseño (no cambia con el rediseño)
- El pitch y tempo se aplican en tiempo real al vídeo que se esté
  reproduciendo dentro del WebView, sea cual sea
- Al cambiar de vídeo dentro de YouTube (tocar otro vídeo, autoplay,
  deslizar en Shorts), el pitch/tempo elegidos **se mantienen** — no hace
  falta volver a ajustarlos
- El bloqueador de anuncios actúa de forma automática y silenciosa —
  no hay ningún control manual para activarlo/desactivarlo ni configurarlo,
  solo el contador informativo de arriba

### Casos de error / estados (Browser)
No hay una pantalla de error dedicada distinta al texto de "Hook:" — si el
enganche de audio falla, el vídeo sigue siendo visible y reproducible
normalmente (solo sin efecto de pitch/tempo), y el motivo del fallo queda
en esa línea de diagnóstico.

---

## Qué NO debe cambiar (contratos funcionales)

El rediseño puede cambiar libremente layout, color, tipografía, iconos,
tipo de componente (p. ej. sustituir un botón de texto por un icono), y
agrupación visual — pero cada una de estas acciones debe seguir siendo
posible, con el mismo efecto:

- Elegir/cambiar archivo de audio
- Play/Pause, arrastrar la posición de reproducción
- Ver posición actual y duración total
- Marcar A, marcar B, y limpiar el bucle; ver qué puntos hay marcados
- Ajustar pitch (-12 a +12 semitonos) y velocidad, con reset para cada uno,
  en ambas pestañas (rangos de velocidad distintos entre pestañas: 0.25x-4x
  en Files, 0.5x-2x en Browser — mantener esa diferencia)
- Cambiar entre pestañas Files/Browser
- Navegar dentro del YouTube embebido con normalidad (el WebView en sí no
  se rediseña — es un navegador real, no un mockup)
- Ver un mensaje de error cuando algo falla en Files

## Libertad para el rediseño

Todo lo demás es negociable: nombres de botones, iconografía, si el bucle
A-B se representa con marcadores visuales sobre la barra de progreso en vez
de texto, si el panel de diagnóstico de Browser se convierte en un icono
expandible, paleta de color e identidad visual completas, tipografía,
densidad, animaciones de transición entre pestañas, etc. No hay guía de
marca previa que respetar — parte de cero.
