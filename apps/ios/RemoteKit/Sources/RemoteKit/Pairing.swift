import CryptoKit
import Foundation

/// Pairs this client's certificate with the TV (port 6467). The TV shows a six-character hex code;
/// the secret proves both sides saw it: SHA-256 over both public keys and the last four characters,
/// whose first byte must equal the code's first two characters.
public enum Pairing {
    public static let port: UInt16 = 6467

    /// - Parameter code: asked once the TV is showing it; return the six characters the user typed.
    /// - Returns: the TV's key fingerprint, to pin on every later connection (RemoteSession).
    @discardableResult
    public static func pair(
        host: String,
        port: UInt16 = port,
        identity: ClientIdentity,
        clientName: String,
        code: @Sendable () async throws -> String
    ) async throws -> String {
        let channel = Channel(host: host, port: port, identity: identity)
        try await channel.open()
        defer { channel.close() }

        channel.send(Polo.pairingRequest(serviceName: "atvremote", clientName: clientName))
        var iterator = channel.messages.makeAsyncIterator()

        func expect(_ wanted: (Polo.Reply) -> Bool, _ step: String) async throws {
            guard let data = try await iterator.next() else { throw RemoteError.pairing("la tele cerró la conexión (\(step))") }
            let reply = try Polo.parse(data)
            if case .error(let status) = reply {
                throw status == 402 ? RemoteError.wrongCode : RemoteError.pairing("la tele respondió \(status) (\(step))")
            }
            guard wanted(reply) else { throw RemoteError.pairing("respuesta inesperada (\(step))") }
        }

        try await expect({ if case .pairingRequestAck = $0 { true } else { false } }, "pedido")
        channel.send(Polo.options())
        try await expect({ if case .options = $0 { true } else { false } }, "opciones")
        channel.send(Polo.configuration())
        try await expect({ if case .configurationAck = $0 { true } else { false } }, "configuración")

        // The TV is now showing the code.
        guard let server = channel.serverPublicNumbers else {
            throw RemoteError.pairing("no se pudo leer el certificado de la tele")
        }
        let secret = try secret(code: try await code(), client: identity.publicNumbers, server: server)
        channel.send(Polo.secret(secret))
        try await expect({ if case .secretAck = $0 { true } else { false } }, "código")
        return server.fingerprint
    }

    static func secret(code: String, client: RSAPublicNumbers, server: RSAPublicNumbers) throws -> Data {
        let hex = code.trimmingCharacters(in: .whitespaces).uppercased()
        guard hex.count == 6, let check = UInt8(hex.prefix(2), radix: 16),
              let tail = Data(hexString: String(hex.suffix(4))) else { throw RemoteError.wrongCode }
        var hash = SHA256()
        hash.update(data: client.modulus)
        hash.update(data: client.exponent)
        hash.update(data: server.modulus)
        hash.update(data: server.exponent)
        hash.update(data: tail)
        let digest = Data(hash.finalize())
        guard digest.first == check else { throw RemoteError.wrongCode }
        return digest
    }
}

extension Data {
    init?(hexString: String) {
        guard hexString.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }
}
