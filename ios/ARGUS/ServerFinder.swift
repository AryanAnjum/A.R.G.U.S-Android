import Foundation
import Network

/// A PC running ARGUS that the phone can connect to.
struct ArgusServer: Identifiable, Equatable {
    var name: String
    /// WebSocket addresses of that PC, best first (it may have both Wi-Fi and Ethernet).
    var urls: [String]
    var id: String { name }

    /// From a pairing link: `argus://connect?h=192.168.1.10,10.0.0.5&p=8000&n=MY-PC`
    /// (the QR code in the PC app's Connect phone panel). Also accepts a plain `ws://` address.
    init?(link: String) {
        let text = link.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.lowercased().hasPrefix("ws://") || text.lowercased().hasPrefix("wss://") {
            guard let host = URL(string: text)?.host else { return nil }
            name = host
            urls = [text]
            return
        }
        guard let parts = URLComponents(string: text), parts.scheme?.lowercased() == "argus" else { return nil }
        var query: [String: String] = [:]
        for item in parts.queryItems ?? [] { query[item.name] = item.value ?? "" }
        let hosts = (query["h"] ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard !hosts.isEmpty else { return nil }
        let port = Int(query["p"] ?? "") ?? 8000
        name = (query["n"] ?? "").isEmpty ? hosts[0] : query["n"]!
        urls = hosts.map { "ws://\($0):\(port)/ws" }
    }

    init(name: String, urls: [String]) {
        self.name = name
        self.urls = urls
    }
}

/// Finds ARGUS PCs on the same Wi-Fi or hotspot. The PC announces itself with Bonjour
/// (`_argus._tcp`, see `src/argus_connect.py` in the backend), so nothing has to be typed.
final class ServerFinder: ObservableObject {
    @Published private(set) var servers: [ArgusServer] = []
    @Published private(set) var searching = false

    private var browser: NWBrowser?

    func start() {
        guard browser == nil else { return }
        let params = NWParameters()
        params.includePeerToPeer = false
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_argus._tcp", domain: nil), using: params)
        b.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                switch state {
                case .ready: self?.searching = true
                case .failed, .cancelled: self?.searching = false
                default: break
                }
            }
        }
        b.browseResultsChangedHandler = { [weak self] results, _ in
            let found = results.compactMap(Self.server).sorted { $0.name < $1.name }
            DispatchQueue.main.async { self?.servers = found }
        }
        browser = b
        b.start(queue: .global(qos: .utility))
    }

    func stop() {
        browser?.cancel()
        browser = nil
        searching = false
    }

    private static func server(from result: NWBrowser.Result) -> ArgusServer? {
        guard case let .service(serviceName, _, _, _) = result.endpoint,
              case let .bonjour(txt) = result.metadata else { return nil }
        let hosts = (txt["hosts"] ?? "").split(separator: ",").map(String.init).filter { !$0.isEmpty }
        guard !hosts.isEmpty else { return nil }
        let path = txt["path"] ?? "/ws"
        let port = Int(txt["port"] ?? "") ?? 8000
        let name = txt["name"].flatMap { $0.isEmpty ? nil : $0 } ?? serviceName
        return ArgusServer(name: name, urls: hosts.map { "ws://\($0):\(port)\(path)" })
    }
}
