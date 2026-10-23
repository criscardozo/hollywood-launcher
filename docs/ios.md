# Control remoto para iPhone (`apps/ios`)

*Qué hace la app de iOS, cómo le habla a la tele y en qué orden se construye.*

## Objetivo

Un control remoto propio en el iPhone, más cómodo que el físico: cruz táctil, volumen, apps de un
toque y teclado de verdad para buscar.

## Cómo le habla a la tele

**Remote v2**, el mismo protocolo que usa la app de Google TV. No necesita adb ni tocar la tele:

- Busca la tele por Bonjour (`_androidtvremote2._tcp`).
- **Emparejamiento**, una sola vez: la tele muestra un código de 6 caracteres y se escribe en el
  iPhone (puerto 6467). La app genera un certificado propio y lo guarda en el Llavero; desde ahí la
  tele lo reconoce siempre.
- **Comandos** (puerto 6466, TLS con ese certificado): teclas (cruz, OK, atrás, inicio, volumen,
  mute, power, reproducir/pausa), abrir apps por link, texto para el teclado de la tele.
- **La tele avisa**: qué app está abierta, si está prendida y el volumen.

Funciona igual con el launcher de Google o con el nuestro, así que se puede desarrollar en paralelo.
Requisito: no desactivar `com.google.android.tv.remote.service` (la lista de limpieza ya lo protege).

Lo que Remote v2 no hace (lista de apps con íconos, "reproducir en la tele" desde Jellyfin) llega
después, con la API local del launcher (`docs/launcher.md`).

## Pantallas

1. **Control:** superficie táctil para la cruz (deslizar = mover, tocar = OK), atrás, inicio,
   volumen ±, mute, power, reproducir/pausa, y la app que está abierta arriba.
2. **Apps:** las 11 de la casa como botones; abren la app en la tele.
3. **Teclado:** campo de texto que se escribe directo en la tele.
4. **Emparejar:** buscar la tele, pedir el código, escribirlo.

Extras posibles, después: control en el Centro de Control (iOS 18+), atajos de Siri ("poné
Jellyfin en la tele"), widget y Live Activity con lo que se está reproduciendo.

## Técnica

- SwiftUI, iOS 17+, proyecto generado con XcodeGen (`apps/ios/project.yml`); el `.xcodeproj` no va a git.
- El protocolo está en **`RemoteKit`** (`apps/ios/RemoteKit`), un paquete Swift propio:
  - protobuf mínimo escrito a mano, con los números de campo de `androidtvremote2` (Home Assistant);
  - identidad del cliente: clave RSA 2048 y certificado X.509 autofirmado armado a mano en DER,
    guardados en el Llavero (solo este dispositivo);
  - **TLS con SwiftNIO SSL, no con Network.framework.** El certificado del servicio de control remoto
    (en el emulador y en la tele real) tiene clave RSA pero dice estar firmado con
    `ecdsa-with-SHA256`, y la pila TLS de Apple no lo puede leer: falla con -9808 ("bad certificate
    format") antes de llegar a cualquier verificación propia. BoringSSL (el de NIO) lo acepta con la
    verificación de cadena apagada (el certificado es autofirmado). En su lugar, al emparejar se guarda
    la huella SHA-256 de la clave pública de la tele y cada conexión posterior tiene que presentar la
    misma; si no coincide, la app corta y pide emparejar de nuevo. El emparejamiento en sí confía en la
    primera tele que responde: el código en pantalla es lo que confirma que es la correcta.
- Búsqueda de la tele por Bonjour (`_androidtvremote2._tcp`), resuelta a IPv4 (por IPv6 da una
  dirección `fe80::` que no sirve sin la interfaz).
- `atvremote`: cliente de línea de comandos para la Mac (`swift run atvremote …` en `apps/ios/RemoteKit`)
  para probar el protocolo sin el iPhone. Usa una identidad en `.local/atvremote.crt` y `.key`:

  ```bash
  openssl req -x509 -newkey rsa:2048 -nodes -keyout .local/atvremote.key \
    -out .local/atvremote.crt -days 7300 -subj "/CN=atvremote"
  ```

## Estado

- Probado contra el emulador de Google TV: TLS con el certificado raro y **emparejamiento completo**
  (el servicio acepta el secreto).
- El puerto de comandos (6466) del emulador no acepta conexiones ni siquiera a `androidtvremote2`
  después de emparejar: es una limitación del emulador. Teclas, apps, texto y estado (app en frente,
  volumen, encendido) se prueban con la tele.
- La app compila sin advertencias y corre en el simulador; encuentra a la tele real por Bonjour.
- `-demo YES` como argumento de arranque muestra el control conectado con datos inventados.
- Abrir apps usa `market://launch?id=<paquete>` (YouTube, su URL): falta confirmarlo en la tele.

## Firma

Cuenta gratuita de Apple: se instala desde la Mac y hay que volver a firmar cada 7 días. Puede que
algunas capacidades no estén disponibles con cuenta gratuita (por ejemplo App Groups, para compartir
datos con un widget); se verifica cuando se llegue a los extras. Nada de esto pasa por CI.

## Orden

1. ~~Esqueleto y pantallas.~~
2. ~~Remote v2: descubrimiento y emparejamiento~~ (probado en el emulador); teclas y estado (falta la tele). ← acá
3. Abrir apps y teclado: falta confirmar en la tele.
4. API del launcher: apps con íconos, fijados y Jellyfin desde el celular.
5. Extras de iOS: Centro de Control, atajos de Siri, widget.

## App de Mac (`HollywoodMenu`)

El mismo control en la barra de menú de la Mac (diseño A de `docs/mockups/mac.html`): un «H▶» arriba a
la derecha que abre un panel con la cruz y OK, Atrás, Inicio, volumen, silencio, encendido, las apps de
la casa y un campo para escribir en la tele. Sin ícono en el Dock.

- **Teclado:** con el panel abierto, las flechas mueven, ↩ es OK, esc o ⌫ Atrás, `h` Inicio, espacio
  pausa, `+`/`-` volumen y `m` silencio. Mientras se escribe en el campo de texto, las teclas son texto.
  **⌥⌘H** abre y cierra el panel desde cualquier app (atajo de Carbon: no pide permiso de Accesibilidad).
- **Comparte** con el iPhone el modelo, el descubrimiento, las apps y el tema (`apps/ios/Shared/`), y
  RemoteKit. Es otro target del mismo `project.yml`: bundle `dev.cardozo.hollywood-menu`, equipo
  `FA737M2U79`.
- **Emparejamiento:** igual que en el iPhone; la tele la lista con el nombre de la Mac. La primera vez
  macOS pide permiso de **Red local**.
- `make mac-build` / `make mac-run`. Menú «⋯» → **Abrir al iniciar sesión**. Para revisar el panel sin
  abrirlo: `HollywoodMenu -demo YES -snapshot /tmp/panel.png` lo dibuja en un PNG y sale.
- Falta probarla contra la tele (emparejar muestra un código en pantalla).
