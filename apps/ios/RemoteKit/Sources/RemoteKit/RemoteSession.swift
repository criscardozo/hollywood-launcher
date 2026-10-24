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
///
/// It also notices when the TV goes quiet: the TV pings about every 5 s, so [silenceLimit] without any
/// message means the connection is dead (the Mac slept, the network changed) even though no error ever
/// arrives. The session then closes and [updates] ends, as when the TV hangs up.
public final class RemoteSession: @unchecked Sendable {
    public static let port: UInt16 = 6466
    public static let silenceLimit: Duration = .seconds(15)

    private let channel: Channel
    private let features: RemoteFeatures
    private let deviceName: String
    private let lock = NSLock()
    private var state = RemoteState()
    private var imeCounter = 0
    private var fieldCounter = 0
    private var reader: Task<Void, Never>?
    private var watchdog: Task<Void, Never>?
    private var lastHeard = ContinuousClock.now
    private let stateContinuation: AsyncStream<RemoteState>.Continuation

    /// Every change of [RemoteState], starting with the first one after connecting.
    public let updates: AsyncStream<RemoteState>

    /// - Parameter pinnedFingerprint: what Pairing.pair returned; the TV's key must still match it.
    public init(host: String, port: UInt16 = port, identity: ClientIdentity, pinnedFingerprint: String?,
                deviceName: String = "Hollywood Remote", features: RemoteFeatures = .standard) {
        channel = Channel(host: host, port: port, identity: identity, pinnedFingerprint: pinnedFingerprint)
        self.features = features
        self.deviceName = deviceName
        var cont: AsyncStream<RemoteState>.Continuation!
        updates = AsyncStream(bufferingPolicy: .bufferingNewest(1)) { cont = $0 }
        stateContinuation = cont
    }

    /// Opens the connection and waits until the TV is ready for commands. Throws when the TV refuses
    /// the certificate: pair first. Cancelling the calling task closes the session and throws
    /// CancellationError.
    public func connect(timeout: TimeInterval = 8) async throws {
        do {
            try await withTaskCancellationHandler {
                try await start(timeout: timeout)
            } onCancel: {
                close()
            }
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw error
        }
    }

    private func start(timeout: TimeInterval) async throws {
        try await channel.open()
        let ready = AsyncStream<Void>.makeStream()
        lock.withLock { lastHeard = .now }
        let reader = Task { [weak self] in
            guard let self else { return }
            do {
                for try await data in channel.messages {
                    lock.withLock { lastHeard = .now }
                    if handle(data) { ready.continuation.yield() }
                }
            } catch {}
            lock.withLock { watchdog }?.cancel()
            update { $0.connected = false }
            ready.continuation.finish()
            stateContinuation.finish()
        }
        let watchdog = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3))
                guard let self else { return }
                let silence = lock.withLock { ContinuousClock.now - lastHeard }
                if silence > Self.silenceLimit {
                    channel.close()
                    return
                }
            }
        }
        lock.withLock {
            self.reader = reader
            self.watchdog = watchdog
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

    /// Replaces the text of the TV's focused text field (needs the TV keyboard to be open). An empty
    /// string clears it.
    ///
    /// Clearing is the same IME batch edit with an empty value, not DEL key presses: the edit names the
    /// field (its counters), so once the TV keyboard has closed it goes nowhere, while a DEL would land
    /// on whatever has focus (some apps take it as Back). androidtvremote2 refuses empty text, so this
    /// one is ours; shrinking the text already works the same way, by sending the shorter value.
    public func type(_ text: String) {
        let (ime, field) = lock.withLock { (imeCounter, fieldCounter) }
        channel.send(Remote.text(text, imeCounter: ime, fieldCounter: field))
    }

    public var current: RemoteState { lock.withLock { state } }

    public func close() {
        let (reader, watchdog) = lock.withLock { (reader, watchdog) }
        reader?.cancel()
        watchdog?.cancel()
        channel.close()
    }

    /// Returns true once the TV says it is ready (remote_start).
    private func handle(_ data: Data) -> Bool {
        guard let event = try? Remote.parse(data) else { return false }
        switch event {
        case .configure(let model, let vendor, _):
            update { $0.model = model; $0.vendor = vendor }
            channel.send(Remote.configure(features: features, model: deviceName, vendor: "Hollywood",
                                          package: "dev.cardozo.hollywood.remote", version: "1.0"))
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
