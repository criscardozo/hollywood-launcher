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

- SwiftUI, iOS 17+, proyecto generado con XcodeGen (`apps/ios/project.yml`), así el `.xcodeproj`
  no va a git.
- Red: Network.framework (Bonjour con `NWBrowser`, TLS con identidad de cliente).
- Mensajes: protobuf (`SwiftProtobuf`), con las definiciones del protocolo que usan las librerías
  abiertas (`androidtvremote2` de Home Assistant, `androidtv-remote` de Node).
- Certificado de cliente: clave RSA 2048 en el Llavero y certificado autofirmado con
  `swift-certificates`. Es RSA porque el código de emparejamiento se valida con el módulo y el
  exponente de las dos claves.
- Alternativa evaluada: el paquete `AndroidTVRemoteControl` (MIT). Su documentación solo cubre
  emparejar y mandar teclas; hacerlo propio es más trabajo pero da control sobre lo que la tele avisa.
- `Info.plist`: `NSLocalNetworkUsageDescription` y `NSBonjourServices` (`_androidtvremote2._tcp`).

## Firma

Cuenta gratuita de Apple: se instala desde la Mac y hay que volver a firmar cada 7 días. Puede que
algunas capacidades no estén disponibles con cuenta gratuita (por ejemplo App Groups, para compartir
datos con un widget); se verifica cuando se llegue a los extras. Nada de esto pasa por CI.

## Orden

1. Esqueleto: pantallas con un cliente simulado. ← acá
2. Remote v2: descubrimiento, emparejamiento, teclas. Se prueba primero contra el emulador de
   Google TV, si trae el servicio de Remote v2; si no, contra la tele.
3. Abrir apps y teclado.
4. API del launcher: apps con íconos, Jellyfin.
5. Extras de iOS.
