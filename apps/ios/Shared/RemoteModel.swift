import Foundation
import Observation
import RemoteKit
#if canImport(UIKit)
import UIKit
#endif
#if os(macOS)
import SystemConfiguration
#endif

/// Everything the screens need: which TV, whether it is paired and connected, what it reports, and
/// the commands. One TV at a time; it is remembered between launches.
@MainActor
@Observable
final class RemoteModel {
    enum Phase: Equatable {
        /// No TV chosen yet (first launch, or forgotten).
        case noTV
        /// Talking to the TV to start pairing.
        case pairing
        /// The TV shows a code; waiting for the user to type it.
        case enteringCode
        case connecting
        case connected
        /// Could not connect: offline, or the TV no longer knows us (pair again).
        case failed(String)
    }

    private(set) var phase: Phase = .noTV
    private(set) var state = RemoteState()
    private(set) var tvName: String?
    private(set) var host: String?
    var pairingError: String?

    private var session: RemoteSession?
    private var updates: Task<Void, Never>?
    /// The connect in flight. Screens, scene changes, key presses and the Mac popover all ask to
    /// connect, often at once; they share this one instead of each opening a session.
    private var connecting: Task<Void, Never>?
    /// The pairing in flight, so Cancel can stop it at any step.
    private var pairing: Task<Void, Never>?
    private var codeContinuation: CheckedContinuation<String, Error>?
    private let defaults = UserDefaults.standard

    init() {
        host = defaults.string(forKey: "host")
        tvName = defaults.string(forKey: "tvName")
        if host != nil { phase = .connecting }
        // `-demo YES` as a launch argument: the connected screen with made-up state, for screenshots.
        if defaults.bool(forKey: "demo") {
            demo = true
            tvName = "Hollywood"
            phase = .connected
            state.connected = true
            state.isOn = true
            state.currentApp = "com.github.damontecres.wholphin"
            state.volumeLevel = 18
            state.volumeMax = 100
        }
    }

    private var demo = false

    // MARK: Connection

    /// Connects to the known TV unless already connected or pairing; .failed is retried. Joins the
    /// connect in flight when there is one, and returns when it is over.
    func connectIfNeeded() async {
        guard !demo, host != nil, phase != .connected, phase != .pairing, phase != .enteringCode else { return }
        let attempt = connecting ?? startConnecting()
        await attempt.value
    }

    private func startConnecting() -> Task<Void, Never> {
        let attempt = Task { await connect() }
        connecting = attempt
        return attempt
    }

    /// One attempt. Whoever supersedes it (disconnect, pairing, forgetting the TV) cancels it and moves
    /// on, so a cancelled attempt closes what it opened and leaves the state alone.
    private func connect() async {
        defer { if !Task.isCancelled { connecting = nil } }
        guard let host else { return }
        phase = .connecting
        guard let tvKey = defaults.string(forKey: "tvKey") else {
            phase = .failed("Falta emparejar con la tele.")
            return
        }
        do {
            // Without a stored identity there is nothing the TV knows: a new one would only be refused.
            guard let identity = try ClientIdentity.storedInKeychain() else {
                phase = .failed("Falta emparejar con la tele.")
                return
            }
            let session = RemoteSession(host: host, identity: identity, pinnedFingerprint: tvKey,
                                        deviceName: Device.name)
            try await session.connect()
            guard !Task.isCancelled else {
                session.close()
                return
            }
            adopt(session)
        } catch {
            guard !Task.isCancelled else { return }
            phase = .failed(Self.explain(error))
        }
    }

    private func adopt(_ session: RemoteSession) {
        self.session?.close()
        self.session = session
        state = session.current
        phase = .connected
        updates?.cancel()
        updates = Task { [weak self] in
            for await state in session.updates {
                self?.state = state
            }
            // The stream ends when the TV closes the connection, or when the session gives up on a
            // silent one (RemoteSession.silenceLimit).
            guard let self, self.session === session else { return }
            self.session = nil
            self.phase = .failed("Se cortó la conexión con la tele")
        }
    }

    /// The TV closes the TLS handshake when it does not know our certificate; that is the common case.
    private static func explain(_ error: Error) -> String {
        let text = String(describing: error)
        if text.contains("handshake") || text.contains("EOF") {
            return "La tele no reconoce \(Device.this) (hay que emparejar de nuevo), o está apagada del todo."
        }
        if text.contains("connectTimeout") || text.contains("timed out") || text.contains("refused") {
            return "La tele no responde. ¿Está prendida y en la misma red?"
        }
        return error.localizedDescription
    }

    /// Drops the connection, and any connect in flight.
    func disconnect() {
        connecting?.cancel()
        connecting = nil
        updates?.cancel()
        updates = nil
        session?.close()
        session = nil
        if phase == .connected { phase = .connecting }
    }

    // MARK: Pairing

    /// Starts pairing with a TV; the code arrives through [submitCode].
    func pair(host: String, name: String) async {
        disconnect()
        pairingError = nil
        phase = .pairing
        do {
            ClientIdentity.deleteKeychain()
            let identity = try ClientIdentity.keychain()
            let tvKey = try await Pairing.pair(host: host, identity: identity, clientName: Device.name) { [weak self] in
                try await withCheckedThrowingContinuation { continuation in
                    Task { @MainActor in
                        self?.codeContinuation = continuation
                        self?.phase = .enteringCode
                    }
                }
            }
            self.host = host
            tvName = name
            defaults.set(host, forKey: "host")
            defaults.set(name, forKey: "tvName")
            // Every later connection must present this same key (no impostor on the LAN).
            defaults.set(tvKey, forKey: "tvKey")
            phase = .connecting
            await connectIfNeeded()
        } catch {
            pairingError = error.localizedDescription
            phase = .noTV
        }
    }

    func submitCode(_ code: String) {
        codeContinuation?.resume(returning: code)
        codeContinuation = nil
        phase = .pairing
    }

    func cancelPairing() {
        codeContinuation?.resume(throwing: CancellationError())
        codeContinuation = nil
        phase = host == nil ? .noTV : .connecting
    }

    func forgetTV() {
        disconnect()
        ClientIdentity.deleteKeychain()
        defaults.removeObject(forKey: "host")
        defaults.removeObject(forKey: "tvName")
        defaults.removeObject(forKey: "tvKey")
        host = nil
        tvName = nil
        state = RemoteState()
        phase = .noTV
    }

    // MARK: Commands

    func press(_ key: KeyCode, _ direction: KeyDirection = .short) {
        guard let session else { Task { await connectIfNeeded() }; return }
        Device.tap()
        session.send(key, direction)
    }

    func launch(_ app: TVApp) {
        guard let session else { return }
        Device.tap(strong: true)
        session.launch(app.link)
    }

    func type(_ text: String) {
        session?.type(text)
    }

    /// The app in front, by its friendly name when it is one of the house apps.
    var currentAppName: String? {
        guard let package = state.currentApp else { return nil }
        return TVApp.house.first { $0.package == package }?.name ?? package
    }
}

/// What differs between the iPhone and the Mac: the name the TV lists us by, and the tap under a key.
enum Device {
    @MainActor static var name: String {
        #if canImport(UIKit)
        UIDevice.current.name
        #else
        Host.current().localizedName ?? "Mac"
        #endif
    }

    /// «este iPhone» or «esta Mac», for messages.
    static var this: String {
        #if os(macOS)
        "esta Mac"
        #else
        "este iPhone"
        #endif
    }

    @MainActor static func tap(strong: Bool = false) {
        #if canImport(UIKit)
        UIImpactFeedbackGenerator(style: strong ? .medium : .light).impactOccurred()
        #endif
    }
}
