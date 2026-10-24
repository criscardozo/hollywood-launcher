import Foundation
import Network
import Observation

/// Finds TVs on the LAN: the Remote v2 service announces itself over Bonjour as _androidtvremote2._tcp.
@MainActor
@Observable
final class Discovery {
    struct TV: Identifiable, Hashable {
        let name: String
        let host: String
        var id: String { host }
    }

    private(set) var tvs: [TV] = []
    private(set) var searching = false
    private var browser: NWBrowser?

    func start() {
        guard browser == nil else { return }
        searching = true
        let browser = NWBrowser(for: .bonjour(type: "_androidtvremote2._tcp", domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            for result in results {
                guard case .service(let name, _, _, _) = result.endpoint else { continue }
                Self.resolve(result.endpoint) { [weak self] host in
                    guard let host else { return }
                    Task { @MainActor [weak self] in self?.add(TV(name: name, host: host)) }
                }
            }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    private func add(_ tv: TV) {
        if !tvs.contains(where: { $0.host == tv.host }) { tvs.append(tv) }
    }

    func stop() {
        browser?.cancel()
        browser = nil
        searching = false
    }

    /// A Bonjour endpoint to an IP address: connect, read where we ended up, hang up.
    nonisolated private static func resolve(_ endpoint: NWEndpoint, done: @escaping @Sendable (String?) -> Void) {
        // IPv4 only: over IPv6 the TV resolves to a link-local fe80:: address that is useless without
        // its interface scope.
        let parameters = NWParameters.tcp
        (parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = .v4
        let connection = NWConnection(to: endpoint, using: parameters)
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                if case .hostPort(let host, _) = connection.currentPath?.remoteEndpoint {
                    var text = "\(host)"
                    if let percent = text.firstIndex(of: "%") { text = String(text[..<percent]) }
                    done(text)
                } else {
                    done(nil)
                }
                connection.cancel()
            case .waiting, .failed:
                // Unreachable for now (no route, refused). A connection left waiting retries forever and
                // a failed one keeps its resources until cancelled; the next browse result tries again.
                connection.cancel()
            case .cancelled:
                done(nil)
            default:
                break
            }
        }
        connection.start(queue: .global())
        // Nor may it hang in preparing: give up after a few seconds. Cancelling a finished connection
        // does nothing.
        DispatchQueue.global().asyncAfter(deadline: .now() + 5) { connection.cancel() }
    }
}
