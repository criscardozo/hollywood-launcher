# La tele: Hollywood

*Qué es la tele, qué tiene instalado y cómo se le habla. Datos leídos de la tele el 2026-10-22 (`baseline/2026-10-22/`).*

## Ficha

| | |
|---|---|
| Modelo | FFALCON 75" U65 Google TV (FF75U65), submarca de TCL |
| Placa / producto | TCL G10, `G10_4K_GB_NF_32BIT` |
| Sistema | Google TV sobre Android 12 (SDK 31), build `STT2.230203.001` (AS12), parche 2025-12-05 |
| Chip | Realtek `rtd6748`, solo 32 bits (`armeabi-v7a`) |
| Memoria | 1,8 GB; ~565 MB libres con el launcher de Google |
| Red | por cable, 192.168.0.4, reserva DHCP en el router |
| Pantalla | UI a 1920×1080 (panel 4K) |
| Sonido | barra JBL Cinema SB550 por eARC |
| Entradas | HDMI 1–3 (`com.tcl.tvinput/.TvPassThroughService/HW15..17`), sintonizador (`HW2`) |

## Cómo se le habla

- **adb:** desde Ginebra, que tiene la clave autorizada: `tools/tv shell …`. El adb de la Mac no tiene permiso de red local.
- **Remote v2** (el control de la app de Google TV): puertos 6466 (comandos) y 6467 (emparejamiento), servido por `com.google.android.tv.remote.service`. Es lo que usa `apps/ios`.
- **AirPlay:** puerto 7000 (`com.tcl.airplay2` + `com.realtek.airplay2`).
- **Chromecast:** 8008/8009/8443 (`com.google.android.apps.mediashell`); se va a desactivar.

## Apps que se quedan

Las que usa la casa (decidido el 2026-10-22), con lo que el launcher necesita para abrirlas.

| App | Paquete | Actividad | Links que acepta |
|---|---|---|---|
| YouTube | `com.google.android.youtube.tv` | `…youtube.tv.activity.ShellActivity` | `https://www.youtube.com/watch?v=…`, `vnd.youtube:` |
| Wholphin (Jellyfin) | `com.github.damontecres.wholphin` | `.MainActivity` | acción `com.github.damontecres.wholphin.PLAYBACK` con extras `itemId` y `position` (ms); `wholphin://view?itemId=<uuid>` abre la ficha |
| Jellyfin oficial | `org.jellyfin.androidtv` | `.ui.startup.StartupActivity` | `VIEW` sin esquema propio |
| Prime Video | `com.amazon.amazonvideo.livingroom` | `com.amazon.ignition.IgnitionActivity` | `https://` (links de primevideo.com) |
| Disney+ | `com.disney.disneyplus` | `com.bamtechmedia.dominguez.main.MainActivity` | `disneyplus://`, `https://` |
| HBO Max | `com.wbd.stream` | `com.wbd.beam.BeamActivity` | `https://` |
| Spotify | `com.spotify.tv.android` | `.SpotifyTVActivity` | `spotify:` |
| 9Now | `au.com.ninenow.ctv` | `.TvActivity` | `ninenow://`, `https://` |
| 7Plus | `com.swm.live` | `au.com.seven.inferno.ui.tv.setup.SetupTvActivity` | `seven://`, `https://` |
| 10Play | `au.com.tenplay` | `com.tenplay.MainActivity` | `tenplay://`, `https://` |
| SBS On Demand | `com.sbs.ondemand.tv` | `.MainActivity` | `https://` |
| ABC iview | `au.net.abc.iview` | `.ui.router.TvLauncherActivity` | `iview://`, `https://` |

El formato de Wholphin sale de su código (`services/IntentService.kt`) y se probó en el emulador:
Wholphin mira primero la acción del intent y solo si no hay usa el host de `wholphin://`, así que un
`VIEW` con `wholphin://play?…` abre la ficha, no reproduce. Para reproducir hay que mandar su acción
`PLAYBACK` con `itemId` y `position` como extras.

Instaladas por Cristian y todavía sin decidir: Flow, Mercado Play, Apple TV, Paramount+, Seerr TV y el Jellyfin oficial.

## Sistema: actividades útiles

| Para qué | Intent |
|---|---|
| Elegir entrada (HDMI) | acción `com.android.tv.action.VIEW_INPUTS` → `com.tcl.suspension/.ui.main.InputActivity` |
| TV en vivo | `com.tcl.tv/com.tcl.player.TVActivity` |
| Ajustes | acción `android.settings.SETTINGS` |
| Imagen / sonido | `android.settings.DISPLAY_SETTINGS`, `com.android.tv.action.VIEW_SOUND_SETTINGS` |

## El control

Control Bluetooth `BT_RC833A_B1` (vendor `0x0417`, product `0x0304`), además de infrarrojo
(`venus_IR_input`). Los archivos de mapeo de teclas (`/vendor/usr/keylayout/`) no se pueden leer sin
root, así que qué código manda cada botón se mide con `getevent -lt` apretando el control real.
Pendiente para cuando se pueda usar la tele.

Botones: mute, power, 0–9, Input, menú (☰), cuatro de colores, cruz y OK, atrás, inicio, micrófono,
ajustes, TV, VOL, CH, 123, info/subtítulos, y seis de apps: **Netflix, Prime Video, YouTube,
películas (seguramente Google TV), TCL Channel, Disney+**.

Teclas que hoy van al launcher de Google (`dumpsys window policy`) y quedan muertas sin él:
`KEYCODE_GUIDE`, `KEYCODE_PROFILE_SWITCH`, `KEYCODE_TCL_USB`, `KEYCODE_TCL_VGR_ACTIVE`.

## Memoria (lo que más consume)

Medido con el launcher de Google y el salvapantallas activos, 7 días sin reiniciar:

| Proceso | PSS |
|---|---|
| Wholphin (abierta) | 124 MB |
| `system` | 114 MB |
| `gms` | 104 MB |
| `dreamx` (salvapantallas de Google) | 103 MB |
| `launcherx` + `:coreservices` | 132 MB |
| Play Store (dos procesos) | 101 MB |
| `katniss` (Asistente) | 55 MB |
| `ttvs` (analítica TCL) | 42 MB |
| `mediashell` (Chromecast) | 37 MB |
| recomendaciones de YouTube | 29 MB |
| `setupwraith` | 23 MB |
