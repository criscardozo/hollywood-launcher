# Limpieza del sistema

*Qué se desactiva en la tele, por qué, en qué orden y cómo se vuelve atrás.*

## Cómo funciona

Con adb (desde Ginebra) y `pm disable-user`: sin root, sin desinstalar nada. Cada paquete queda
apagado para el usuario y se vuelve a prender con `pm enable`. `tools/debloat.sh` anota el estado
de cada paquete antes de tocarlo (`debloat/state/`) y `restore` vuelve exactamente a ese estado, para
todo lo que anotó (también lo que después se comentó en la lista). `apply` se niega si una lista nombra
un paquete protegido (el bloque NEVER DISABLE de `hollywood.txt`, repetido en el script) y no aplica la
fase B mientras el launcher no esté instalado.

La lista sale de la de cleanyourtv para TCL G10 (`examples/tcl-g10-google-tv`), hecha sobre el mismo
producto y el mismo build que esta tele, ajustada a lo que decidió Cristian el 2026-10-22.

## Fases

**A: `debloat/hollywood.txt`**, sin tocar el launcher. Rastreo (Samba TV, analítica TCL, logs),
promos y canales de TCL, instalación automática de apps, modo demo y hotel, Alexa, Chromecast,
Asistente, y las apps que no se usan (Netflix, Stan, YouTube Music, Google TV). Se aplica en tandas
chicas, mirando que la tele siga bien entre una y otra.

**B: `debloat/hollywood-launcher.txt`**, solo cuando `apps/tv` esté instalado y probado: el launcher
de Google, el asistente de configuración inicial (que si no se queda con el botón Inicio) y el
salvapantallas de Google.

## Lo que se gana

Unos **300 MB** de 1,8 GB: launcher de Google (132 MB), salvapantallas (103 MB), Asistente (55 MB),
analítica TCL (42 MB), Chromecast (37 MB), recomendaciones de YouTube (29 MB), `setupwraith` (23 MB).
Es la memoria que hoy falta y manda todo a swap (ver `ginebra/docs/tele.md`: Spotify y YouTube).

## Lo que se pierde

- **Actualizaciones de firmware**: congeladas a propósito (`com.tcl.ttvs`). Google Play Services
  también puede traer actualizaciones de sistema y no se puede desactivar del todo; si aparece una,
  no aceptarla. Las apps se siguen actualizando por Play Store.
- **Botones del control**: Netflix, TCL Channel, películas (Google TV) y el micrófono quedan sin
  función. Se pueden reasignar más adelante desde el launcher (un servicio de accesibilidad), con el
  riesgo de que el firmware de TCL no lo deje arrancar solo (visto en otro TCL con Google TV 12).
- **Google TV**: recomendaciones, perfiles, lista de seguimiento, modo ambiental.
- **Chromecast**: desde el iPhone se sigue usando AirPlay. Ojo: YouTube en el iPhone transmite a la
  tele por Chromecast, no por AirPlay; si eso se usa, `mediashell` no debería apagarse.

## Riesgos y cómo se cubren

- **Una actualización de firmware** puede volver a prender paquetes o devolverle el inicio a Google.
  Con las actualizaciones congeladas es poco probable; si pasa, se vuelve a aplicar la lista.
- **Desactivar algo que la tele necesita**: la lista tiene una sección "NEVER DISABLE" y se aplica
  por tandas. `restore` está siempre a mano.
- **Reinicio inmediato**: esperar 10 s después de aplicar; si no, PackageManager puede no haber
  guardado los cambios y los paquetes vuelven.
- **Restablecer de fábrica**: antes, `restore` de las dos listas (sobre todo `setupwraith`).

## Pendiente de decidir

- `com.tcl.gamebar`: ¿la PlayStation está enchufada a esta tele?
- `com.tcl.ui_mediaCenter`: ¿se usa el USB para ver fotos o videos?
- `com.tcl.t_solo`: ¿se usan auriculares Bluetooth con la tele?
- Las apps que instaló Cristian y no nombró: Flow, Mercado Play, Apple TV, Paramount+, Seerr TV, Jellyfin oficial.
