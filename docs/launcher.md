# Hollywood, el launcher (`apps/tv`)

*Qué hace el launcher propio, cómo está hecho, cómo se configura y qué falta para llevarlo a la tele.*

## Nombre e ícono

**Hollywood**, como la tele. Ícono «H▶» de la misma familia que el «G▶» de Ginebra (cuadrado
`#141a26`, H en Nunito Sans Black, play facetado rosa y violeta); se genera con
`design/icono/generar.py` y en la app está como PNG (`drawable-xhdpi/icon.png` y `banner.png`).

## Objetivo

Reemplazar la pantalla de inicio de Google TV por una propia: liviana (la tele tiene 1,8 GB), sin
publicidad, con las apps de la casa y con lo que se está viendo en Jellyfin y YouTube al frente.

## Pantalla de inicio (layout D)

Elegido el 2026-10-22 entre los mockups de `docs/mockups/launcher-2.html`: el "top shelf" del A con
las filas del B debajo.

```
┌──────────────────────────────────────────────────────────────────┐
│ [fondo: imagen de lo enfocado, o wallpaper de la biblioteca]     │
│ ● JELLYFIN                                  Entradas Ajustes 21:43│
│ Perfect Days                                                     │
│ 2023 · Drama · 2 h 4 min · PG                                    │
│ Sinopsis…                    ▬▬▬──── Quedan 1 h 51 min           │
│ ┌──────┐ ┌──────┐ ┌──────┐   ← contenido de la app enfocada      │
│ └──────┘ └──────┘ └──────┘                                       │
│ [YouTube] [Wholphin] [Prime] [Disney+] [Max] … [Todas las apps]  │
├───────────── al bajar, la página sube ───────────────────────────┤
│ Agregado recientemente (Jellyfin, pósters)                       │
│ Más de tus suscripciones (YouTube)                               │
│ Fijados (Prime Video · Disney+ · Max)                            │
└──────────────────────────────────────────────────────────────────┘
```

**Contenido de cada app** (fila de arriba, cambia según la app enfocada abajo):

| App enfocada | Fila | De dónde sale | OK |
|---|---|---|---|
| Wholphin / Jellyfin | En curso + próximo episodio | API de Jellyfin (`/UserItems/Resume`, `/Shows/NextUp`) | Abre el detalle |
| YouTube | Nuevo en tus suscripciones (15) | Lista de suscripciones (YouTube Data API) + feed RSS de cada canal | Abre el video en la app de YouTube |
| Prime Video, Disney+, Max | Fijados de ese servicio | Cargados desde la página de configuración; datos de TMDB vía Seerr | Abre el detalle |
| Las demás | — (se ve el wallpaper) | | |

**Arriba a la derecha:** Entradas (selector de HDMI de TCL, `com.android.tv.action.VIEW_INPUTS`),
Ajustes (los de la tele) y la hora.

**Fondo:** la imagen de lo enfocado; si no, fondos al azar de la biblioteca de Jellyfin, que cambian
cada 3 minutos. Si un video de YouTube no tiene miniatura grande, usa la de la tarjeta.

**Sin tocar el control:** a los 10 s el hero recorre solo la fila enfocada, uno cada 8 s, y la tarjeta
que muestra tiene una barra que se llena. El foco no se mueve: OK abre lo que elegiste y cualquier
tecla vuelve todo a su lugar.

## Detalle de películas y series

OK en una película o serie (Jellyfin o fijados) abre su página, como la de Jellyfin (diseño A de
`docs/mockups/detalle.html`): fondo, año, duración, clasificación, puntaje, a qué hora termina,
géneros, sinopsis, **Continuar** / **Desde el principio** (o **Reproducir**), y abajo el reparto o las
temporadas con sus episodios. Un episodio abre su serie, siguiendo desde ese episodio.

**Se reproduce en:** el «G▶» y el nombre del servidor (Ginebra) para Jellyfin, o el ícono de la app
instalada para Prime Video, Disney+ y Max. Los datos de los fijados salen de TMDB vía Seerr, con la
clasificación de Australia. Back vuelve a la misma tarjeta. Los videos de YouTube no tienen detalle:
OK los reproduce.

**Salvapantallas** (`ClockDream`): fondos de Jellyfin que cambian cada 45 s con un fundido, y el reloj
abajo a la derecha, que se corre unos píxeles cada minuto. Sin Jellyfin, solo el reloj sobre negro.

## Configuración

Todo lo que el launcher necesita saber de afuera se carga desde una **página web que sirve el propio
launcher** en la red de casa: **https://hollywood.merli.store** (Caddy en Ginebra hace de proxy a
`http://192.168.0.4:8765`, con certificado de Let's Encrypt; el nombre apunta a una IP privada, así que
fuera de casa no responde). Directo también anda: `http://192.168.0.4:8765`. En el emulador,
`http://localhost:8765` con `adb forward tcp:8765 tcp:8765`. Cada cambio pide el código de 6 dígitos que se ve en la tele en
**Todas las apps → Configuración del inicio** (generado con `SecureRandom`; después de 10 códigos
equivocados la API se bloquea 5 minutos). Ninguna clave queda en el código ni en git.

Desde la página:

- **Jellyfin:** servidor (lo busca en la red), usuario y contraseña. La contraseña no se guarda.
- **YouTube:** botón **Conectar YouTube**, que muestra el código de google.com/device en la página; y
  el client ID y secret de Google, una sola vez (ver abajo).
- **Seerr:** dirección (`http://192.168.0.2:5055`) y API key, para buscar series y películas.
- **Fijados:** buscar, elegir servicio (sugiere el que lo tiene en Australia) y pegar el link opcional.
- **Botones del control:** ver qué código manda un botón y asignarle una app.
- **Reproductor de Jellyfin:** Wholphin u oficial.

La misma API (`/api/…`, con el código en el header `X-Token`) es la que va a usar la app del iPhone.

## Jellyfin sin escribir nada en la tele

Lo más rápido es la página de configuración: usuario y contraseña desde el teléfono o la Mac. El
launcher inicia sesión, guarda solo el token y descarta la contraseña.

Desde la tele: el launcher busca el servidor en la red (UDP 7359) o se escribe la dirección, muestra un
código de **Quick Connect** y se aprueba desde Jellyfin (usuario → Quick Connect). Si el código vence,
pide otro solo.

## YouTube

Login con la cuenta de Google por el flujo para TVs: el launcher pide un código, se escribe en
google.com/device. El código aparece en la página de configuración (**Conectar YouTube**) o en la tele.
Google no permite el login web común (con redirección) hacia una IP de la red local, por eso es con
código. Solo pide `youtube.readonly`. Hace falta un cliente OAuth propio:

1. [console.cloud.google.com](https://console.cloud.google.com) → proyecto nuevo (sin facturación).
2. APIs y servicios → Biblioteca → **YouTube Data API v3** → Habilitar.
3. Google Auth Platform → Branding: nombre y mail. Audience: **External** y luego **Publicar la app**
   (en "Testing" Google vence el login cada 7 días; publicada sin verificar muestra un aviso al
   aprobar, pero no vence).
4. Clients → Create client → tipo **TVs and Limited Input devices** → copiar client ID y secret.
5. Cargarlos en la página de configuración y tocar **Conectar YouTube** (o en la tele: enfocar
   YouTube → **Conectar YouTube**).

Cuota: la lista de suscripciones cuesta 1 unidad cada 50 canales (se renueva cada 6 h); los videos
salen del feed RSS público de cada canal (sin cuota); duraciones y vistas, 1 unidad cada 50 videos.
Muy por debajo de las 10.000 diarias. Los Shorts se descartan.

## Fijados (Prime Video, Disney+, Max)

Esas apps no dejan leer su "seguir viendo": un launcher que no es de sistema no puede, porque Android
lo protege con `ACCESS_ALL_EPG_DATA` (`signature|privileged`) y adb no lo otorga sin root. Lo que sí
se puede es **fijar** a mano: la imagen y la sinopsis salen de TMDB (vía Seerr, en español) y el link
es el que da el botón Compartir de cada app. Sin link, OK abre la app.

## Botones del control

Servicio de accesibilidad "Botones del control" (`RemoteKeysService`): intercepta los códigos de
tecla asignados y abre la app elegida. Sirve para los botones que quedan muertos con la limpieza
(Netflix, TCL Channel, películas). Se activa por adb (fase B de `docs/limpieza.md`) y se configura
desde la página: se aprieta el botón, la página muestra su código, se le asigna una app.

**Sin probar con un control real:** en el emulador ni `input keyevent` ni las teclas de la consola
llegan a los servicios de accesibilidad (cleanyourtv vio lo mismo en TCL con teclas inyectadas). En
algunos TCL el firmware además no deja arrancar servicios instalados a mano (`OP_AUTO_START`). Se
verifica en la tele con el control.

## Reproductor

Wholphin por defecto. Para reproducir hay que usar su acción `com.github.damontecres.wholphin.PLAYBACK`
con `itemId` y `position` como extras: un `VIEW` con `wholphin://play?…` abre la ficha (lo lee de la
acción antes que del link; probado en el emulador). La app oficial de Jellyfin solo abre la ficha
(extra `ItemId` en `StartupActivity`). Lo visto vive en el servidor, así que cambiar de app no pierde
nada.

## Técnica

- Kotlin + Jetpack Compose con `androidx.tv:tv-material`. Paquete `dev.cardozo.hollywood.launcher`.
- Jellyfin con el SDK oficial; HTTP con OkHttp; JSON con kotlinx.serialization; imágenes con Coil;
  configuración en DataStore; página web con NanoHTTPD (vive en el proceso del launcher, que como
  inicio siempre está corriendo; sin servicios en segundo plano).
- `minSdk 31` (Android 12), sin código nativo. APK release de 5,3 MB.
- Memoria medida en el emulador (release, inicio con fondos y filas): unos 75 MB de PSS, la mayor
  parte imágenes. Los fondos se piden a 1280 px. Falta medir en la tele, que es de 32 bits.

## Cómo se desarrolla

Emulador de Google TV con Android 12 en la Mac: `make tv-emulator`, `make tv-run`,
`make tv-screenshot`. En el emulador el launcher de Google está desactivado para que Inicio caiga en
el nuestro; en el emulador `setupwraith` no se quedó con el inicio, en la tele hay que verificarlo.

## Estado

Hecho y probado en el emulador: pantalla de inicio D, Jellyfin (Quick Connect, filas, reproducción en
Wholphin desde la posición), YouTube (login con código, 40 canales, 39 videos con duración), fijados
(búsqueda en Seerr, servicio sugerido, fila), página de configuración, salvapantallas, compilación
release, nombre e ícono Hollywood.

Falta probar en la tele: abrir un video en la app de YouTube (el emulador no la tiene) y los botones
del control (necesitan el control real).

`GET /api/youtube` (con el código) dice cuántos videos tendría la fila y, si está vacía, por qué.

El cliente OAuth de Google está en modo Testing con Cristian como tester: el login vence cada 7 días.
El launcher guarda la lista de canales, así que la fila sigue con los videos nuevos (salen del RSS);
solo dejan de verse duraciones y vistas, y los canales nuevos, hasta reconectar.

Para llevarlo a la tele (con el OK de Cristian, y alguien frente a la tele): instalar con
`tools/tv install -r`, abrirlo a mano desde el launcher de Google, cargar la configuración, aplicar la
fase A de la limpieza, y recién después la fase B.
