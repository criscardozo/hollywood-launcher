import Foundation

/// The small part of protobuf's wire format the protocol needs: varints and length-delimited fields.
/// Field numbers come from androidtvremote2's polo.proto and remotemessage.proto (Apache-2.0).
struct ProtoWriter {
    private(set) var data = Data()

    mutating func varint(_ field: Int, _ value: UInt64) {
        tag(field, wireType: 0)
        appendVarint(value)
    }

    mutating func int(_ field: Int, _ value: Int) { varint(field, UInt64(bitPattern: Int64(value))) }

    mutating func bool(_ field: Int, _ value: Bool) { varint(field, value ? 1 : 0) }

    mutating func bytes(_ field: Int, _ value: Data) {
        tag(field, wireType: 2)
        appendVarint(UInt64(value.count))
        data.append(value)
    }

    mutating func string(_ field: Int, _ value: String) { bytes(field, Data(value.utf8)) }

    mutating func message(_ field: Int, _ build: (inout ProtoWriter) -> Void) {
        var inner = ProtoWriter()
        build(&inner)
        bytes(field, inner.data)
    }

    private mutating func tag(_ field: Int, wireType: UInt64) { appendVarint(UInt64(field) << 3 | wireType) }

    private mutating func appendVarint(_ value: UInt64) { data.append(contentsOf: encodeVarint(value)) }
}

func encodeVarint(_ value: UInt64) -> [UInt8] {
    var v = value
    var out: [UInt8] = []
    repeat {
        var byte = UInt8(v & 0x7F)
        v >>= 7
        if v != 0 { byte |= 0x80 }
        out.append(byte)
    } while v != 0
    return out
}

/// Reads varint at `offset`; nil when the buffer ends first (a partial message on the wire).
func decodeVarint(_ data: Data, at offset: Int) -> (value: UInt64, next: Int)? {
    var result: UInt64 = 0
    var shift: UInt64 = 0
    var i = offset
    while i < data.endIndex {
        let byte = data[i]
        result |= UInt64(byte & 0x7F) << shift
        i += 1
        if byte & 0x80 == 0 { return (result, i) }
        shift += 7
        if shift > 63 { return nil }
    }
    return nil
}

/// A decoded message: field number → values (varints as UInt64, length-delimited as Data).
struct ProtoMessage {
    private var varints: [Int: [UInt64]] = [:]
    private var blobs: [Int: [Data]] = [:]

    init(_ data: Data) throws {
        var i = data.startIndex
        while i < data.endIndex {
            guard let (key, afterKey) = decodeVarint(data, at: i) else { throw ProtoError.truncated }
            let field = Int(key >> 3)
            switch key & 7 {
            case 0:
                guard let (value, next) = decodeVarint(data, at: afterKey) else { throw ProtoError.truncated }
                varints[field, default: []].append(value)
                i = next
            case 2:
                // Compare against what is left before converting: a hostile length (up to 2^64) would
                // trap in Int(length) or overflow start + length.
                guard let (length, start) = decodeVarint(data, at: afterKey),
                      length <= UInt64(data.endIndex - start) else { throw ProtoError.truncated }
                let end = start + Int(length)
                blobs[field, default: []].append(data.subdata(in: start..<end))
                i = end
            case 1:
                guard data.endIndex - afterKey >= 8 else { throw ProtoError.truncated }
                i = afterKey + 8
            case 5:
                guard data.endIndex - afterKey >= 4 else { throw ProtoError.truncated }
                i = afterKey + 4
            default: throw ProtoError.unsupportedWireType
            }
        }
    }

    func has(_ field: Int) -> Bool { varints[field] != nil || blobs[field] != nil }
    func uint(_ field: Int) -> UInt64? { varints[field]?.first }
    func int(_ field: Int) -> Int? { uint(field).map { Int(Int64(bitPattern: $0)) } }
    func bool(_ field: Int) -> Bool? { uint(field).map { $0 != 0 } }
    func data(_ field: Int) -> Data? { blobs[field]?.first }
    func string(_ field: Int) -> String? { data(field).map { String(decoding: $0, as: UTF8.self) } }
    func message(_ field: Int) -> ProtoMessage? { data(field).flatMap { try? ProtoMessage($0) } }
}

enum ProtoError: Error {
    case truncated
    case unsupportedWireType
}
