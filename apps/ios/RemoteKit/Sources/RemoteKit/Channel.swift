import Foundation
import NIOCore
import NIOPosix
import NIOSSL
import NIOTLS

/// A TLS connection to the TV carrying varint-length-prefixed protobuf messages, both on the pairing
/// port (6467) and the remote port (6466).
///
/// TLS goes through SwiftNIO SSL, not Network.framework: the TV's certificate has an RSA key but claims
/// an ecdsa-with-SHA256 signature, and Apple's stack refuses to parse it (-9808, "bad certificate
/// format") before any verify block runs. Seen on the emulator and on the real TV. The certificate is not
/// chain-verified (it is self-signed); instead, after pairing its key is pinned: [pinnedFingerprint] must
/// match, or the connection is dropped. The TV checks ours on every connection too.
final class Channel: @unchecked Sendable {
    private let host: String
    private let port: UInt16
    private let identity: ClientIdentity
    private let pinnedFingerprint: String?
    /// Guards [channel] and [closed]: close() can come from any thread, also while open() is running.
    private let lock = NSLock()
    private var channel: NIOCore.Channel?
    private var closed = false
    private let continuation: AsyncThrowingStream<Data, Error>.Continuation
    private(set) var serverPublicNumbers: RSAPublicNumbers?

    /// Incoming messages, one Data per protobuf message, until the connection closes.
    let messages: AsyncThrowingStream<Data, Error>

    init(host: String, port: UInt16, identity: ClientIdentity, pinnedFingerprint: String? = nil) {
        self.host = host
        self.port = port
        self.identity = identity
        self.pinnedFingerprint = pinnedFingerprint
        (messages, continuation) = AsyncThrowingStream.makeStream()
    }

    /// TCP connect and TLS handshake each get this long. A TV that accepts the connection but never
    /// answers the handshake (asleep, or a half-dead socket after a network change) would otherwise
    /// hang open() forever.
    static let timeout: TimeAmount = .seconds(8)

    /// Connects and completes the TLS handshake; throws if the TV refuses (e.g. we are not paired), if it
    /// takes longer than [timeout], or if close() is called meanwhile.
    func open() async throws {
        var config = TLSConfiguration.makeClientConfiguration()
        config.certificateVerification = .none
        config.certificateChain = [.certificate(try NIOSSLCertificate(bytes: Array(identity.certificateDER), format: .der))]
        config.privateKey = .privateKey(try NIOSSLPrivateKey(bytes: Array(identity.privateKeyDER), format: .der))
        let context = try NIOSSLContext(configuration: config)

        let group = MultiThreadedEventLoopGroup.singleton
        let handshake = group.any().makePromise(of: Void.self)
        let continuation = self.continuation
        do {
            guard !lock.withLock({ closed }) else { throw ClosedWhileOpening() }
            let channel = try await ClientBootstrap(group: group)
                .connectTimeout(Self.timeout)
                .channelInitializer { channel in
                    channel.eventLoop.makeCompletedFuture {
                        let ops = channel.pipeline.syncOperations
                        try ops.addHandler(NIOSSLClientHandler(context: context, serverHostname: nil))
                        try ops.addHandler(ByteToMessageHandler(VarintFrameDecoder()))
                        try ops.addHandler(MessageHandler(continuation: continuation, handshake: handshake))
                    }
                }
                .connect(host: host, port: Int(port))
                .get()
            // close() may have run while connecting; it found no channel to close, so close this one.
            let adopted = lock.withLock { () -> Bool in
                if closed { return false }
                self.channel = channel
                return true
            }
            guard adopted else {
                channel.close(promise: nil)
                throw ClosedWhileOpening()
            }
            let deadline = channel.eventLoop.scheduleTask(in: Self.timeout) {
                handshake.fail(HandshakeTimeout())
            }
            handshake.futureResult.whenComplete { _ in deadline.cancel() }
            try await handshake.futureResult.get()
            let spki = try await channel.eventLoop.submit { () -> [UInt8]? in
                let ssl = try channel.pipeline.syncOperations.handler(type: NIOSSLClientHandler.self)
                return try ssl.peerCertificate?.extractPublicKey().toSPKIBytes()
            }.get()
            serverPublicNumbers = spki.flatMap { RSAPublicNumbers(spki: Data($0)) }
            if let pinnedFingerprint, serverPublicNumbers?.fingerprint != pinnedFingerprint {
                throw PinMismatch()
            }
        } catch is PinMismatch {
            close()
            throw RemoteError.connection("la tele no coincide con la emparejada: emparejá de nuevo")
        } catch {
            handshake.fail(error)
            close()
            throw RemoteError.connection(String(describing: error))
        }
    }

    func send(_ message: Data) {
        guard let channel = lock.withLock({ channel }) else { return }
        var buffer = channel.allocator.buffer(capacity: message.count + 5)
        buffer.writeBytes(encodeVarint(UInt64(message.count)))
        buffer.writeBytes(message)
        channel.writeAndFlush(buffer, promise: nil)
    }

    /// Closes the connection, or makes a running open() give up. [messages] ends either way.
    func close() {
        let channel = lock.withLock { () -> NIOCore.Channel? in
            closed = true
            return self.channel
        }
        channel?.close(promise: nil)
        continuation.finish()
    }
}

private struct PinMismatch: Error {}

private struct ClosedWhileOpening: Error, CustomStringConvertible {
    var description: String { "closed while connecting" }
}

/// Worded so RemoteModel.explain reads it as "the TV does not answer", not as a refused certificate.
private struct HandshakeTimeout: Error, CustomStringConvertible {
    var description: String { "TLS timed out" }
}

/// A frame header the decoder will not wait on; the channel closes (MessageHandler.errorCaught).
enum FrameError: Error {
    case badLength
    case tooLarge(UInt64)
}

/// Splits the TLS stream into messages: each is preceded by its length as a varint, and either may
/// arrive in pieces.
private struct VarintFrameDecoder: ByteToMessageDecoder {
    typealias InboundOut = ByteBuffer

    /// The TV's messages are a few hundred bytes. Anything near this is garbage, and waiting for it
    /// would let the buffer grow without bound.
    static let maxFrame: UInt64 = 64 * 1024

    func decode(context: ChannelHandlerContext, buffer: inout ByteBuffer) throws -> DecodingState {
        guard let bytes = buffer.getBytes(at: buffer.readerIndex, length: min(buffer.readableBytes, 10)),
              let (length, headerSize) = decodeVarint(Data(bytes), at: 0) else {
            // Ten bytes without the varint ending is not a length at all.
            if buffer.readableBytes >= 10 { throw FrameError.badLength }
            return .needMoreData
        }
        guard length <= Self.maxFrame else { throw FrameError.tooLarge(length) }
        guard buffer.readableBytes >= headerSize + Int(length) else { return .needMoreData }
        buffer.moveReaderIndex(forwardBy: headerSize)
        context.fireChannelRead(wrapInboundOut(buffer.readSlice(length: Int(length))!))
        return .continue
    }
}

private final class MessageHandler: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = ByteBuffer

    private let continuation: AsyncThrowingStream<Data, Error>.Continuation
    private let handshake: EventLoopPromise<Void>

    init(continuation: AsyncThrowingStream<Data, Error>.Continuation, handshake: EventLoopPromise<Void>) {
        self.continuation = continuation
        self.handshake = handshake
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        if case .handshakeCompleted = event as? TLSUserEvent { handshake.succeed(()) }
        context.fireUserInboundEventTriggered(event)
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        var buffer = unwrapInboundIn(data)
        continuation.yield(Data(buffer.readBytes(length: buffer.readableBytes) ?? []))
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        handshake.fail(error)
        continuation.finish(throwing: RemoteError.connection(String(describing: error)))
        context.close(promise: nil)
    }

    func channelInactive(context: ChannelHandlerContext) {
        handshake.fail(RemoteError.connection("la tele cerró la conexión"))
        continuation.finish()
    }
}
