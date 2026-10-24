import Foundation
import Security

/// The client's TLS identity: an RSA key and a self-signed certificate. The TV remembers the
/// certificate after pairing, so it has to survive app restarts.
public struct ClientIdentity: Sendable {
    /// X.509 certificate, DER.
    public let certificateDER: Data
    /// RSA private key, DER (PKCS#1 or PKCS#8).
    public let privateKeyDER: Data
    let publicNumbers: RSAPublicNumbers

    public init(certificateDER: Data, privateKeyDER: Data) throws {
        guard let numbers = RSAPublicNumbers(certificate: certificateDER) else {
            throw RemoteError.identity("el certificado no tiene una clave RSA")
        }
        self.certificateDER = certificateDER
        self.privateKeyDER = privateKeyDER
        publicNumbers = numbers
    }

    /// A new key and certificate, valid for 20 years.
    public static func generate(commonName: String = "Hollywood Remote") throws -> ClientIdentity {
        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey([
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
        ] as CFDictionary, &error),
            let publicKey = SecKeyCopyPublicKey(privateKey),
            let keyDER = SecKeyCopyExternalRepresentation(privateKey, &error) as Data? else {
            throw RemoteError.identity("no se pudo crear la clave: \(String(describing: error?.takeRetainedValue()))")
        }
        let certificate = try selfSignedCertificate(privateKey: privateKey, publicKey: publicKey, commonName: commonName)
        return try ClientIdentity(certificateDER: certificate, privateKeyDER: keyDER)
    }

    /// The identity kept in the Keychain, created the first time. Used by the iPhone and Mac apps.
    ///
    /// Only a missing item means "first time". Any other Keychain error (locked, denied) throws: replacing
    /// the identity then would silently unpair us from the TV.
    public static func keychain(account: String = "dev.cardozo.hollywood.remote") throws -> ClientIdentity {
        if let stored = try storedInKeychain(account: account) { return stored }
        let identity = try generate()
        try identity.saveToKeychain(account: account)
        return identity
    }

    /// The identity in the Keychain, or nil when there is none (never created, or forgotten).
    public static func storedInKeychain(account: String = "dev.cardozo.hollywood.remote") throws -> ClientIdentity? {
        guard let cert = try Keychain.read(account: account, kind: "cert"),
              let key = try Keychain.read(account: account, kind: "key") else { return nil }
        return try ClientIdentity(certificateDER: cert, privateKeyDER: key)
    }

    /// Keeps this identity in the Keychain, replacing the one there. Pairing calls it only once the TV
    /// has accepted the identity, so a cancelled or failed re-pair keeps the working one.
    public func saveToKeychain(account: String = "dev.cardozo.hollywood.remote") throws {
        try Keychain.write(certificateDER, account: account, kind: "cert")
        try Keychain.write(privateKeyDER, account: account, kind: "key")
    }

    /// Forgets the identity: the TV will ask to pair again.
    public static func deleteKeychain(account: String = "dev.cardozo.hollywood.remote") {
        Keychain.delete(account: account, kind: "cert")
        Keychain.delete(account: account, kind: "key")
    }

    /// PEM files as openssl writes them (certificate and PKCS#8 key). Used by the Mac command line.
    public static func pem(certificate: URL, key: URL) throws -> ClientIdentity {
        try ClientIdentity(certificateDER: try pemBody(certificate), privateKeyDER: try pemBody(key))
    }

    private static func pemBody(_ url: URL) throws -> Data {
        let text = try String(contentsOf: url, encoding: .utf8)
        let base64 = text.split(separator: "\n").filter { !$0.hasPrefix("-----") }.joined()
        guard let data = Data(base64Encoded: base64) else { throw RemoteError.identity("PEM inválido: \(url.lastPathComponent)") }
        return data
    }
}

/// Generic-password items: the key and certificate are just bytes to keep, this device only.
private enum Keychain {
    /// nil only when the item does not exist; other failures throw.
    static func read(account: String, kind: String) throws -> Data? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: account,
            kSecAttrAccount as String: kind,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ] as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data else { throw RemoteError.identity("el Llavero devolvió algo que no son datos") }
            return data
        case errSecItemNotFound:
            return nil
        default:
            throw RemoteError.identity("no se pudo leer el Llavero (\(status))")
        }
    }

    static func write(_ data: Data, account: String, kind: String) throws {
        delete(account: account, kind: kind)
        let status = SecItemAdd([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: account,
            kSecAttrAccount as String: kind,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ] as CFDictionary, nil)
        guard status == errSecSuccess else { throw RemoteError.identity("no se pudo guardar en el Llavero (\(status))") }
    }

    static func delete(account: String, kind: String) {
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: account,
            kSecAttrAccount as String: kind,
        ] as CFDictionary)
    }
}

/// A minimal X.509 v3 certificate: RSA key, SHA-256 signature, CN only, valid for 20 years.
func selfSignedCertificate(privateKey: SecKey, publicKey: SecKey, commonName: String) throws -> Data {
    guard let pkcs1 = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
        throw RemoteError.identity("no se pudo exportar la clave pública")
    }
    let sha256WithRSA = DER.sequence([DER.oid("1.2.840.113549.1.1.11"), DER.null()])
    let name = DER.sequence([DER.set([DER.sequence([DER.oid("2.5.4.3"), DER.utf8String(commonName)])])])
    let now = Date()
    let validity = DER.sequence([DER.utcTime(now.addingTimeInterval(-86_400)), DER.generalizedTime(now.addingTimeInterval(20 * 365 * 86_400))])
    let spki = DER.sequence([
        DER.sequence([DER.oid("1.2.840.113549.1.1.1"), DER.null()]),
        DER.bitString(pkcs1),
    ])
    var serial = Data(count: 16)
    _ = serial.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
    serial[0] &= 0x7F
    let tbs = DER.sequence([
        DER.explicit(0, DER.integer(2)),
        DER.integer(serial),
        sha256WithRSA,
        name,
        validity,
        name,
        spki,
    ])
    var error: Unmanaged<CFError>?
    guard let signature = SecKeyCreateSignature(privateKey, .rsaSignatureMessagePKCS1v15SHA256, tbs as CFData, &error) as Data? else {
        throw RemoteError.identity("no se pudo firmar el certificado: \(String(describing: error?.takeRetainedValue()))")
    }
    return DER.sequence([tbs, sha256WithRSA, DER.bitString(signature)])
}

public enum RemoteError: Error, LocalizedError {
    case identity(String)
    case connection(String)
    case pairing(String)
    case wrongCode

    public var errorDescription: String? {
        switch self {
        case .identity(let s): "Identidad: \(s)"
        case .connection(let s): "Conexión: \(s)"
        case .pairing(let s): "Emparejamiento: \(s)"
        case .wrongCode: "El código no coincide"
        }
    }
}
