import Foundation
import RemoteKit

// Mac command-line client for testing RemoteKit against a Google TV (or the emulator, with
// `adb forward tcp:6466 tcp:6466` and `adb forward tcp:6467 tcp:6467`).
//
//   atvremote pair  <host> [code]      pair; without a code it is read from stdin once the TV shows it
//   atvremote key   <host> <KEY>...    DPAD_UP, DPAD_CENTER, HOME, BACK, VOLUME_UP, …
//   atvremote launch <host> <link>     e.g. https://www.youtube.com or market://launch?id=<package>
//   atvremote text  <host> <text>      into the focused text field
//   atvremote watch <host>             print what the TV reports (app, volume, power) for 30 s
//
// The identity is a PEM certificate and key, .local/atvremote.crt and .local/atvremote.key by default
// (ATV_CERT, ATV_KEY). Create them once with openssl (see apps/ios/README.md).

let args = CommandLine.arguments.dropFirst()
guard let command = args.first, args.count >= 2 else {
    print("usage: atvremote pair|key|launch|text|watch <host> …")
    exit(2)
}
let host = args[args.startIndex + 1]
let rest = Array(args.dropFirst(2))
let env = ProcessInfo.processInfo.environment
/// The TV's key fingerprint saved when pairing; later connections must match it.
let pinPath = env["ATV_PIN"] ?? ".local/atvremote.pin"

func identity() throws -> ClientIdentity {
    try ClientIdentity.pem(
        certificate: URL(fileURLWithPath: env["ATV_CERT"] ?? ".local/atvremote.crt"),
        key: URL(fileURLWithPath: env["ATV_KEY"] ?? ".local/atvremote.key")
    )
}

func run() async throws {
    let id = try identity()
    switch command {
    case "pair":
        let given = rest.first
        let tvKey = try await Pairing.pair(host: host, identity: id, clientName: "atvremote (Mac)") {
            if let given { return given }
            print("Código que muestra la tele: ", terminator: "")
            return readLine() ?? ""
        }
        try tvKey.write(toFile: pinPath, atomically: true, encoding: .utf8)
        print("emparejado; clave de la tele en \(pinPath)")
    case "key", "launch", "text", "watch":
        let pin = try? String(contentsOfFile: pinPath, encoding: .utf8)
        let session = RemoteSession(host: host, identity: id, pinnedFingerprint: pin, deviceName: "atvremote (Mac)")
        try await session.connect()
        print("conectado: \(session.current)")
        switch command {
        case "key":
            for name in rest {
                guard let key = KeyCode(name: name) else { print("tecla desconocida: \(name)"); continue }
                session.send(key)
                try await Task.sleep(for: .milliseconds(300))
            }
        case "launch":
            session.launch(rest.joined(separator: " "))
        case "text":
            session.type(rest.joined(separator: " "))
        default:
            let deadline = Date().addingTimeInterval(30)
            for await state in session.updates {
                print(state)
                if Date() > deadline { break }
            }
        }
        try await Task.sleep(for: .seconds(1))
        print("final: \(session.current)")
        session.close()
    default:
        print("comando desconocido: \(command)")
        exit(2)
    }
}

do {
    try await run()
} catch {
    print("error: \(error.localizedDescription)")
    exit(1)
}
