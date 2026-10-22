import Foundation
import Security

/// Just enough ASN.1 DER to build a self-signed X.509 certificate and read an RSA public key.
enum DER {
    static func sequence(_ items: [Data]) -> Data { tlv(0x30, items.reduce(Data(), +)) }
    static func set(_ items: [Data]) -> Data { tlv(0x31, items.reduce(Data(), +)) }
    static func integer(_ bytes: Data) -> Data {
        var b = Data(bytes.drop(while: { $0 == 0 }))
        if b.isEmpty { b = Data([0]) }
        if b.first! & 0x80 != 0 { b.insert(0, at: 0) }
        return tlv(0x02, b)
    }
    static func integer(_ value: Int) -> Data {
        var v = value
        var bytes = Data()
        repeat { bytes.insert(UInt8(v & 0xFF), at: 0); v >>= 8 } while v > 0
        return integer(bytes)
    }
    static func bitString(_ bytes: Data) -> Data { tlv(0x03, Data([0]) + bytes) }
    static func null() -> Data { Data([0x05, 0x00]) }
    static func utf8String(_ s: String) -> Data { tlv(0x0C, Data(s.utf8)) }
    static func utcTime(_ date: Date) -> Data {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyMMddHHmmss'Z'"
        return tlv(0x17, Data(f.string(from: date).utf8))
    }
    static func generalizedTime(_ date: Date) -> Data {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyyMMddHHmmss'Z'"
        return tlv(0x18, Data(f.string(from: date).utf8))
    }
    static func explicit(_ tag: UInt8, _ content: Data) -> Data { tlv(0xA0 | tag, content) }

    static func oid(_ dotted: String) -> Data {
        let parts = dotted.split(separator: ".").compactMap { UInt64($0) }
        var body = Data([UInt8(parts[0] * 40 + parts[1])])
        for part in parts.dropFirst(2) {
            var chunk = [UInt8(part & 0x7F)]
            var v = part >> 7
            while v > 0 { chunk.insert(UInt8(v & 0x7F) | 0x80, at: 0); v >>= 7 }
            body.append(contentsOf: chunk)
        }
        return tlv(0x06, body)
    }

    static func tlv(_ tag: UInt8, _ value: Data) -> Data {
        var out = Data([tag])
        let n = value.count
        if n < 0x80 {
            out.append(UInt8(n))
        } else {
            var len = Data()
            var v = n
            while v > 0 { len.insert(UInt8(v & 0xFF), at: 0); v >>= 8 }
            out.append(0x80 | UInt8(len.count))
            out.append(len)
        }
        out.append(value)
        return out
    }

    /// Reads one TLV at `offset`: (tag, value, offset after it).
    static func read(_ data: Data, at offset: Int) -> (tag: UInt8, value: Data, next: Int)? {
        var i = offset
        guard i + 2 <= data.endIndex else { return nil }
        let tag = data[i]; i += 1
        var length = Int(data[i]); i += 1
        if length & 0x80 != 0 {
            let count = length & 0x7F
            guard i + count <= data.endIndex else { return nil }
            length = 0
            for _ in 0..<count { length = length << 8 | Int(data[i]); i += 1 }
        }
        guard i + length <= data.endIndex else { return nil }
        return (tag, data.subdata(in: i..<(i + length)), i + length)
    }
}

/// An RSA public key's modulus and exponent as unsigned big-endian bytes, no leading zeros.
struct RSAPublicNumbers: Equatable {
    let modulus: Data
    let exponent: Data

    /// From SecKeyCopyExternalRepresentation of an RSA public key: PKCS#1 SEQUENCE { n, e }.
    init?(pkcs1 der: Data) {
        guard let seq = DER.read(der, at: der.startIndex), seq.tag == 0x30,
              let n = DER.read(seq.value, at: seq.value.startIndex), n.tag == 0x02,
              let e = DER.read(seq.value, at: n.next), e.tag == 0x02 else { return nil }
        modulus = Data(n.value.drop(while: { $0 == 0 }))
        exponent = Data(e.value.drop(while: { $0 == 0 }))
    }

    /// From a SubjectPublicKeyInfo: SEQUENCE { algorithm, BIT STRING { PKCS#1 key } }.
    init?(spki der: Data) {
        guard let seq = DER.read(der, at: der.startIndex), seq.tag == 0x30,
              let algorithm = DER.read(seq.value, at: seq.value.startIndex),
              let bits = DER.read(seq.value, at: algorithm.next), bits.tag == 0x03, bits.value.count > 1 else { return nil }
        self.init(pkcs1: Data(bits.value.dropFirst()))
    }

    /// From an X.509 certificate: tbsCertificate's seventh field (sixth without the version) is the SPKI.
    init?(certificate der: Data) {
        guard let cert = DER.read(der, at: der.startIndex), cert.tag == 0x30,
              let tbs = DER.read(cert.value, at: cert.value.startIndex), tbs.tag == 0x30 else { return nil }
        var offset = tbs.value.startIndex
        var fields: [(tag: UInt8, value: Data, next: Int)] = []
        while let field = DER.read(tbs.value, at: offset) {
            fields.append(field)
            offset = field.next
        }
        let skipped = fields.first?.tag == 0xA0 ? 1 : 0
        guard fields.count > skipped + 5 else { return nil }
        let spki = fields[skipped + 5]
        self.init(spki: DER.tlv(spki.tag, spki.value))
    }
}

func rsaPublicNumbers(of key: SecKey) -> RSAPublicNumbers? {
    guard let data = SecKeyCopyExternalRepresentation(key, nil) as Data? else { return nil }
    return RSAPublicNumbers(pkcs1: data)
}
