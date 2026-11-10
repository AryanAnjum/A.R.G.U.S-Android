import Foundation

/// WebSocket link to the ARGUS server (`ws://<pc-ip>:8000/ws`), with automatic reconnect.
final class ArgusSocket: NSObject, URLSessionWebSocketDelegate {
    enum State { case disconnected, connecting, connected }

    var onEvent: (([String: Any]) -> Void)?
    var onState: ((State) -> Void)?
    /// Called on every (re)connect, with the address that worked, so the app can send its hello.
    var onOpen: ((String) -> Void)?

    private var session: URLSession!
    private var task: URLSessionWebSocketTask?
    /// Addresses of the same PC (it may have Wi-Fi and Ethernet); tried in turn until one opens.
    private var urls: [URL] = []
    private var urlIndex = 0
    private var wantConnected = false
    /// True once the current connection has opened (false while an address is still being tried).
    private var opened = false
    private var generation = 0
    private let queue = DispatchQueue(label: "argus.socket")
    /// Frames in flight; camera frames are dropped while the link is busy so latency stays low.
    private var pendingFrames = 0
    private let lock = NSLock()

    override init() {
        super.init()
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = false
        config.timeoutIntervalForRequest = 10
        session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }

    func connect(to urlString: String) { connect(toAny: [urlString]) }

    /// Connects to the first address that answers and keeps retrying all of them until one does.
    func connect(toAny urlStrings: [String]) {
        queue.async {
            self.disconnectLocked()
            let urls = urlStrings.compactMap { URL(string: $0.trimmingCharacters(in: .whitespaces)) }
                .filter { $0.host != nil }
            guard !urls.isEmpty else { return }
            self.urls = urls
            self.urlIndex = 0
            self.wantConnected = true
            self.openLocked()
        }
    }

    func disconnect() {
        queue.async { self.disconnectLocked() }
    }

    private func disconnectLocked() {
        wantConnected = false
        opened = false
        generation += 1
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        emitState(.disconnected)
    }

    private func openLocked() {
        guard wantConnected, !urls.isEmpty else { return }
        generation += 1
        let gen = generation
        emitState(.connecting)
        var request = URLRequest(url: urls[urlIndex % urls.count])
        request.timeoutInterval = urls.count > 1 ? 4 : 8
        let t = session.webSocketTask(with: request)
        t.maximumMessageSize = 32 * 1024 * 1024  // reply audio clips can be a few MB
        task = t
        t.resume()
        receive(on: t, generation: gen)
    }

    private func scheduleReconnect(generation gen: Int, opened: Bool) {
        // An address that never opened: move on to the next one quickly. A dropped link: retry it.
        if !opened && urls.count > 1 { urlIndex = (urlIndex + 1) % urls.count }
        queue.asyncAfter(deadline: .now() + (opened || urls.count == 1 ? 2 : 0.3)) {
            guard self.wantConnected, gen == self.generation else { return }
            self.openLocked()
        }
    }

    private func receive(on t: URLSessionWebSocketTask, generation gen: Int) {
        t.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure:
                self.queue.async {
                    guard gen == self.generation, t === self.task else { return }
                    let opened = self.opened
                    self.opened = false
                    self.task = nil
                    self.emitState(opened || self.urls.count == 1 ? .disconnected : .connecting)
                    self.scheduleReconnect(generation: gen, opened: opened)
                }
            case .success(let message):
                var data: Data?
                switch message {
                case .string(let s): data = s.data(using: .utf8)
                case .data(let d): data = d
                @unknown default: break
                }
                if let data, let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    DispatchQueue.main.async { self.onEvent?(obj) }
                }
                self.receive(on: t, generation: gen)
            }
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didOpenWithProtocol protocol: String?) {
        queue.async {
            guard webSocketTask === self.task else { return }
            self.lock.lock(); self.pendingFrames = 0; self.lock.unlock()
            self.opened = true
            let url = self.urls[self.urlIndex % self.urls.count].absoluteString
            self.emitState(.connected)
            DispatchQueue.main.async { self.onOpen?(url) }
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        queue.async {
            guard webSocketTask === self.task else { return }
            let opened = self.opened
            self.opened = false
            self.task = nil
            self.emitState(.disconnected)
            self.scheduleReconnect(generation: self.generation, opened: opened)
        }
    }

    /// Sends an event. Every message is tagged as coming from the phone.
    func send(_ event: [String: Any]) {
        var payload = event
        payload["origin"] = "phone"
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else { return }
        queue.async { self.task?.send(.string(text)) { _ in } }
    }

    /// Sends a camera frame unless two are already queued (drops frames instead of building lag).
    func sendFrame(_ event: [String: Any]) {
        lock.lock()
        if pendingFrames >= 2 { lock.unlock(); return }
        pendingFrames += 1
        lock.unlock()
        var payload = event
        payload["origin"] = "phone"
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else {
            lock.lock(); pendingFrames -= 1; lock.unlock()
            return
        }
        queue.async {
            guard let t = self.task else {
                self.lock.lock(); self.pendingFrames = max(0, self.pendingFrames - 1); self.lock.unlock()
                return
            }
            t.send(.string(text)) { _ in
                self.lock.lock(); self.pendingFrames = max(0, self.pendingFrames - 1); self.lock.unlock()
            }
        }
    }

    private func emitState(_ s: State) {
        DispatchQueue.main.async { self.onState?(s) }
    }
}
