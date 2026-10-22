import Foundation

/// What the TV reports about itself while connected.
public struct RemoteState: Sendable, Equatable {
    public var connected = false
    public var isOn: Bool?
    /// Package of the app in front, e.g. com.google.android.youtube.tv.
    public var currentApp: String?
    public var volumeLevel: Int?
    public var volumeMax: Int?
    public var muted: Bool?
    public var model: String?
    public var vendor: String?

    public init() {}
}

/// A remote-control session on port 6466, once the client certificate is paired. Answers the TV's
/// handshake and pings by itself; the TV drops clients that stop answering pings.
public final class RemoteSession: @unchecked Sendable {
    public static let port: UInt16 = 6466

    private let channel: Channel
    private let features: RemoteFeatures
    private let deviceName: String
    private let lock = NSLock()
    private var state = RemoteState()
    private var imeCounter = 0
    private var fieldCounter = 0
    private var reader: Task<Void, Never>?
    private let stateContinuation: AsyncStream<RemoteState>.Continuation

    /// Every change of [RemoteState], starting with the first one after connecting.
    public let updates: AsyncStream<RemoteState>

    public init(host: String, port: UInt16 = port, identity: ClientIdentity, deviceName: String = "Hollywood Remote",
                features: RemoteFeatures = .standard) {
        channel = Channel(host: host, port: port, identity: identity)
        self.features = features
        self.deviceName = deviceName
        var cont: AsyncStream<RemoteState>.Continuation!
        updates = AsyncStream(bufferingPolicy: .bufferingNewest(1)) { cont = $0 }
        stateContinuation = cont
    }

    /// Opens the connection and waits until the TV is ready for commands. Throws when the TV refuses
    /// the certificate: pair first.
    public func connect(timeout: TimeInterval = 8) async throws {
        try await channel.open()
        let ready = AsyncStream<Void>.makeStream()
        reader = Task { [weak self] in
            guard let self else { return }
            do {
                for try await data in channel.messages {
                    if handle(data) { ready.continuation.yield() }
                }
            } catch {}
            update { $0.connected = false }
            ready.continuation.finish()
            stateContinuation.finish()
        }
        let started = await withTaskGroup(of: Bool.self) { group in
            group.addTask { for await _ in ready.stream { return true }; return false }
            group.addTask { try? await Task.sleep(for: .seconds(timeout)); return false }
            let first = await group.next() ?? false
            group.cancelAll()
            return first
        }
        guard started else {
            close()
            throw RemoteError.connection("la tele no respondió (¿falta emparejar?)")
        }
    }

    public func send(_ key: KeyCode, _ direction: KeyDirection = .short) {
        channel.send(Remote.key(key, direction))
    }

    /// Opens a link on the TV: an app's deep link, or market://launch?id=<package> to open an app.
    public func launch(_ link: String) {
        channel.send(Remote.appLink(link))
    }

    /// Replaces the text of the TV's focused text field (needs the TV keyboard to be open).
    public func type(_ text: String) {
        guard !text.isEmpty else { return }
        let (ime, field) = lock.withLock { (imeCounter, fieldCounter) }
        channel.send(Remote.text(text, imeCounter: ime, fieldCounter: field))
    }

    public var current: RemoteState { lock.withLock { state } }

    public func close() {
        reader?.cancel()
        channel.close()
    }

    /// Returns true once the TV says it is ready (remote_start).
    private func handle(_ data: Data) -> Bool {
        guard let event = try? Remote.parse(data) else { return false }
        switch event {
        case .configure(let model, let vendor, _):
            update { $0.model = model; $0.vendor = vendor }
            channel.send(Remote.configure(features: features, model: deviceName, vendor: "Hollywood",
                                          package: "dev.cristian.hollywood.remote", version: "1.0"))
        case .setActive:
            channel.send(Remote.setActive(features: features))
        case .ping(let value):
            channel.send(Remote.pingResponse(value))
        case .currentApp(let app):
            update { $0.currentApp = app }
        case .imeCounters(let ime, let field):
            lock.withLock { imeCounter = ime; fieldCounter = field }
        case .started(let on):
            update { $0.connected = true; $0.isOn = on }
            return true
        case .volume(let level, let max, let muted):
            update { $0.volumeLevel = level; $0.volumeMax = max; $0.muted = muted }
        case .error, .other:
            break
        }
        return false
    }

    private func update(_ change: (inout RemoteState) -> Void) {
        let snapshot = lock.withLock { () -> RemoteState in
            change(&state)
            return state
        }
        stateContinuation.yield(snapshot)
    }
}
