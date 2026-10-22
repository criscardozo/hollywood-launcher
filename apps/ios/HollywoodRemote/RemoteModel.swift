import Foundation
import Observation
import RemoteKit
import UIKit

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

    func connectIfNeeded() async {
        guard !demo, let host, phase != .connected, phase != .pairing, phase != .enteringCode else { return }
        phase = .connecting
        do {
            let identity = try ClientIdentity.keychain()
            let session = RemoteSession(host: host, identity: identity, deviceName: UIDevice.current.name)
            try await session.connect()
            self.session = session
            state = session.current
            phase = .connected
            updates?.cancel()
            updates = Task { [weak self] in
                for await state in session.updates {
                    self?.state = state
                }
                // The stream ends when the TV closes the connection.
                guard let self, self.session === session else { return }
                self.session = nil
                self.phase = .failed("Se cortó la conexión con la tele")
            }
        } catch {
            phase = .failed(Self.explain(error))
        }
    }

    /// The TV closes the TLS handshake when it does not know our certificate; that is the common case.
    private static func explain(_ error: Error) -> String {
        let text = String(describing: error)
        if text.contains("handshake") || text.contains("EOF") {
            return "La tele no reconoce este iPhone (hay que emparejar de nuevo), o está apagada del todo."
        }
        if text.contains("connectTimeout") || text.contains("timed out") || text.contains("refused") {
            return "La tele no responde. ¿Está prendida y en la misma red?"
        }
        return error.localizedDescription
    }

    func disconnect() {
        updates?.cancel()
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
            try await Pairing.pair(host: host, identity: identity, clientName: UIDevice.current.name) { [weak self] in
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
        host = nil
        tvName = nil
        state = RemoteState()
        phase = .noTV
    }

    // MARK: Commands

    func press(_ key: KeyCode, _ direction: KeyDirection = .short) {
        guard let session else { Task { await connectIfNeeded() }; return }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        session.send(key, direction)
    }

    func launch(_ app: TVApp) {
        guard let session else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
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
