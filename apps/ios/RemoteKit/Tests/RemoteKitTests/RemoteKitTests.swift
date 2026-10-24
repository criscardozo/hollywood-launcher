import Foundation
import Security
import Testing
@testable import RemoteKit

@Test func varintRoundTrip() {
    for value: UInt64 in [0, 1, 127, 128, 300, 16_384, UInt64(UInt32.max)] {
        let bytes = Data(encodeVarint(value))
        #expect(decodeVarint(bytes, at: 0)?.value == value)
    }
}

@Test func keyMessageMatchesProtobuf() throws {
    // RemoteMessage { remote_key_inject { key_code: DPAD_UP (19), direction: SHORT (3) } }
    #expect(Remote.key(.dpadUp, .short) == Data([0x52, 0x04, 0x08, 0x13, 0x10, 0x03]))
}

@Test func pingIsAnswered() throws {
    var w = ProtoWriter()
    w.message(8) { p in p.int(1, 42) }
    guard case .ping(let value) = try Remote.parse(w.data) else { Issue.record("not a ping"); return }
    #expect(value == 42)
}

@Test func selfSignedCertificateCarriesTheKey() throws {
    let key = try #require(SecKeyCreateRandomKey([
        kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits as String: 2048,
    ] as CFDictionary, nil))
    let pub = try #require(SecKeyCopyPublicKey(key))
    let der = try selfSignedCertificate(privateKey: key, publicKey: pub, commonName: "test")
    let cert = try #require(SecCertificateCreateWithData(nil, der as CFData))
    let certKey = try #require(SecCertificateCopyKey(cert))
    #expect(rsaPublicNumbers(of: certKey) == rsaPublicNumbers(of: pub))
    #expect(RSAPublicNumbers(certificate: der) == rsaPublicNumbers(of: pub))
    let generated = try ClientIdentity.generate()
    #expect(generated.publicNumbers.modulus.count == 256)
    #expect(rsaPublicNumbers(of: pub)?.exponent == Data([0x01, 0x00, 0x01]))
    #expect(rsaPublicNumbers(of: pub)?.modulus.count == 256)
}

@Test func secretChecksTheCodesFirstByte() throws {
    let client = RSAPublicNumbers(pkcs1: DER.sequence([DER.integer(Data(repeating: 0xAB, count: 256)), DER.integer(65_537)]))!
    let server = RSAPublicNumbers(pkcs1: DER.sequence([DER.integer(Data(repeating: 0xCD, count: 256)), DER.integer(65_537)]))!
    // Find the code the TV would show for tail "1234": its first byte is the hash's first byte.
    let good = try (0...255).compactMap { b -> String? in
        let code = String(format: "%02X1234", b)
        return (try? Pairing.secret(code: code, client: client, server: server)) != nil ? code : nil
    }
    #expect(good.count == 1)
    #expect(throws: RemoteError.self) { try Pairing.secret(code: "ZZ1234", client: client, server: server) }
}

@Test func hostileLengthsThrowInsteadOfTrapping() {
    // Field 1, length-delimited, claiming 2^64 - 1 bytes: must not reach Int(length).
    let huge = Data([0x0A] + encodeVarint(.max))
    #expect(throws: ProtoError.self) { try ProtoMessage(huge) }
    // A length one past the end, and a fixed64 cut short.
    #expect(throws: ProtoError.self) { try ProtoMessage(Data([0x0A, 0x02, 0x41])) }
    #expect(throws: ProtoError.self) { try ProtoMessage(Data([0x09, 0x01, 0x02])) }
}
