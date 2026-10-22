import Foundation

// MARK: - Pairing (port 6467): polo.proto's OuterMessage

enum Polo {
    static let protocolVersion: UInt64 = 2
    static let statusOK: UInt64 = 200
    static let encodingHexadecimal: UInt64 = 3
    static let roleInput: UInt64 = 1
    static let symbolLength: UInt64 = 6

    private static func outer(_ body: (inout ProtoWriter) -> Void) -> Data {
        var w = ProtoWriter()
        w.varint(1, protocolVersion)
        w.varint(2, statusOK)
        body(&w)
        return w.data
    }

    static func pairingRequest(serviceName: String, clientName: String) -> Data {
        outer { w in
            w.message(10) { r in
                r.string(1, serviceName)
                r.string(2, clientName)
            }
        }
    }

    static func options() -> Data {
        outer { w in
            w.message(20) { o in
                o.message(1) { e in
                    e.varint(1, encodingHexadecimal)
                    e.varint(2, symbolLength)
                }
                o.varint(3, roleInput)
            }
        }
    }

    static func configuration() -> Data {
        outer { w in
            w.message(30) { c in
                c.message(1) { e in
                    e.varint(1, encodingHexadecimal)
                    e.varint(2, symbolLength)
                }
                c.varint(2, roleInput)
            }
        }
    }

    static func secret(_ secret: Data) -> Data {
        outer { w in w.message(40) { s in s.bytes(1, secret) } }
    }

    enum Reply {
        case pairingRequestAck, options, configurationAck, secretAck
        case error(status: UInt64)
        case other
    }

    static func parse(_ data: Data) throws -> Reply {
        let m = try ProtoMessage(data)
        let status = m.uint(2) ?? 0
        if status != statusOK { return .error(status: status) }
        if m.has(11) { return .pairingRequestAck }
        if m.has(20) { return .options }
        if m.has(31) { return .configurationAck }
        if m.has(41) { return .secretAck }
        return .other
    }
}

// MARK: - Remote (port 6466): remotemessage.proto's RemoteMessage

/// What the client says it supports (androidtvremote2's Feature flags).
public struct RemoteFeatures: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }

    public static let ping = RemoteFeatures(rawValue: 1 << 0)
    public static let key = RemoteFeatures(rawValue: 1 << 1)
    public static let ime = RemoteFeatures(rawValue: 1 << 2)
    public static let power = RemoteFeatures(rawValue: 1 << 5)
    public static let volume = RemoteFeatures(rawValue: 1 << 6)
    public static let appLink = RemoteFeatures(rawValue: 1 << 9)

    public static let standard: RemoteFeatures = [.ping, .key, .ime, .power, .volume, .appLink]
}

/// How long a key is held: a tap, or the start and end of a long press.
public enum KeyDirection: UInt64, Sendable {
    case startLong = 1
    case endLong = 2
    case short = 3
}

enum Remote {
    static func configure(features: RemoteFeatures, model: String, vendor: String, package: String, version: String) -> Data {
        var w = ProtoWriter()
        w.message(1) { c in
            c.int(1, features.rawValue)
            c.message(2) { d in
                d.string(1, model)
                d.string(2, vendor)
                d.int(3, 1)
                d.string(4, "1")
                d.string(5, package)
                d.string(6, version)
            }
        }
        return w.data
    }

    static func setActive(features: RemoteFeatures) -> Data {
        var w = ProtoWriter()
        w.message(2) { a in a.int(1, features.rawValue) }
        return w.data
    }

    static func pingResponse(_ value: Int) -> Data {
        var w = ProtoWriter()
        w.message(9) { p in p.int(1, value) }
        return w.data
    }

    static func key(_ code: KeyCode, _ direction: KeyDirection) -> Data {
        var w = ProtoWriter()
        w.message(10) { k in
            k.varint(1, UInt64(code.rawValue))
            k.varint(2, direction.rawValue)
        }
        return w.data
    }

    static func appLink(_ link: String) -> Data {
        var w = ProtoWriter()
        w.message(90) { a in a.string(1, link) }
        return w.data
    }

    /// Replaces the focused text field's content, as androidtvremote2's send_text does.
    static func text(_ text: String, imeCounter: Int, fieldCounter: Int) -> Data {
        let last = max(text.count - 1, 0)
        var w = ProtoWriter()
        w.message(21) { b in
            b.int(1, imeCounter)
            b.int(2, fieldCounter)
            b.message(3) { e in
                e.int(1, 1)
                e.message(2) { o in
                    o.int(1, last)
                    o.int(2, last)
                    o.string(3, text)
                }
            }
        }
        return w.data
    }

    enum Event {
        case configure(model: String?, vendor: String?, version: String?)
        case setActive
        case ping(Int)
        case currentApp(String)
        case imeCounters(ime: Int, field: Int)
        case started(Bool)
        case volume(level: Int, max: Int, muted: Bool)
        case error
        case other
    }

    static func parse(_ data: Data) throws -> Event {
        let m = try ProtoMessage(data)
        if let c = m.message(1) {
            let d = c.message(2)
            return .configure(model: d?.string(1), vendor: d?.string(2), version: d?.string(6))
        }
        if m.has(2) { return .setActive }
        if m.has(8) { return .ping(m.message(8)?.int(1) ?? 0) }
        if let ime = m.message(20) {
            if let app = ime.message(1)?.string(12), !app.isEmpty { return .currentApp(app) }
            return .other
        }
        if let batch = m.message(21) { return .imeCounters(ime: batch.int(1) ?? 0, field: batch.int(2) ?? 0) }
        if m.has(40) { return .started(m.message(40)?.bool(1) ?? false) }
        if let v = m.message(50) {
            return .volume(level: v.int(7) ?? 0, max: v.int(6) ?? 0, muted: v.bool(8) ?? false)
        }
        if m.has(3) { return .error }
        return .other
    }
}
