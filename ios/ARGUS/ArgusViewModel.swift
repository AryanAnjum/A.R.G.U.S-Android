import AVFoundation
import Combine
import SwiftUI

/// The phone side of ARGUS: same WebSocket protocol as the Android app.
@MainActor
final class ArgusViewModel: ObservableObject {
    // Connection
    /// The PC's address that last worked, the other addresses of that PC, and its name.
    @AppStorage("ws_url") var serverURL = ""
    @AppStorage("ws_urls") private var savedURLs = ""
    @AppStorage("pc_name") var pcName = ""
    @Published private(set) var scanningQR = false
    private var connectedThisSession = false
    @Published var connection: ArgusSocket.State = .disconnected
    @Published var metrics = ServerMetrics()

    // Modes and features
    @Published private(set) var mode: ArgusMode = .live
    @Published var ocrMode = false
    @Published private(set) var guiding = false
    @Published private(set) var sltAvailable = true
    @Published private(set) var signLanguages: [SignLanguage] = []
    @Published private(set) var signLanguage = "de"
    @Published private(set) var signCaption = ""
    @Published private(set) var signCaptionEnglish = ""
    @Published private(set) var signStatus = "Show your hands to sign"

    // Conversation
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var thinking = false
    @Published var voiceOn = false
    @Published private(set) var awake = false

    let camera = CameraManager()
    let speech = SpeechListener()
    let audio = ReplyAudioPlayer()
    /// Lists ARGUS PCs on the same Wi-Fi or hotspot.
    let finder = ServerFinder()
    private let socket = ArgusSocket()
    private var bag = Set<AnyCancellable>()

    /// Read on the camera queue, so kept outside the main actor.
    private let frameGate = FrameGate()

    init() {
        socket.onState = { [weak self] s in
            guard let self else { return }
            self.connection = s
            self.frameGate.update(connected: s == .connected)
        }
        socket.onOpen = { [weak self] url in
            guard let self else { return }
            self.serverURL = url
            self.connectedThisSession = true
            self.sendHello()
        }
        camera.onQRCode = { [weak self] text in self?.scanned(text) }
        finder.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        finder.$servers.receive(on: DispatchQueue.main).sink { [weak self] s in self?.serversChanged(s) }.store(in: &bag)
        finder.start()
        socket.onEvent = { [weak self] e in self?.handle(e) }
        speech.onPhrase = { [weak self] p in self?.handlePhrase(p) }
        speech.onError = { [weak self] m in self?.system(m) }
        frameGate.update(mode: mode)

        let gate = frameGate
        let cam = camera
        let sock = socket
        camera.onFrame = { buffer in
            guard let spec = gate.shouldSend() else { return }
            guard let encoded = cam.encode(buffer, maxPx: spec.maxPx, quality: spec.quality) else { return }
            var event: [String: Any] = ["event": spec.sign ? "slt_frame" : "video_frame", "image_data": encoded]
            if spec.sign { event["ts"] = Int(Date().timeIntervalSince1970 * 1000) }
            sock.sendFrame(event)
        }
        camera.$isRunning.sink { [weak self] on in self?.frameGate.update(camera: on) }.store(in: &bag)
        // Forward child changes so views redraw when the mic or camera state flips.
        speech.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        camera.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        audio.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
    }

    // MARK: - Connection

    private var savedURLList: [String] {
        let list = savedURLs.split(separator: ",").map(String.init)
        return list.isEmpty && !serverURL.isEmpty ? [serverURL] : list
    }

    /// Connects to the saved PC unless already connected or connecting
    /// (the app calls this whenever it comes to the front).
    func connect() {
        finder.start()
        guard connection == .disconnected, !savedURLList.isEmpty else { return }
        // Try the address that worked last time first.
        let saved = savedURLList
        socket.connect(toAny: saved.contains(serverURL) ? [serverURL] + saved.filter { $0 != serverURL } : saved)
    }

    /// Connects to a PC picked from the list, scanned from the QR code, or typed in.
    func connect(to server: ArgusServer) {
        pcName = server.name
        savedURLs = server.urls.joined(separator: ",")
        serverURL = server.urls.first ?? ""
        connectedThisSession = false
        socket.connect(toAny: server.urls)
    }

    func reconnect(to url: String) {
        guard let server = ArgusServer(link: url) else {
            system("That address doesn't look right. Use the PC's IP, for example 192.168.1.10.")
            return
        }
        connect(to: server)
    }

    /// PCs found on the network changed. Connects by itself when the choice is obvious:
    /// the saved PC got a new address, or there is exactly one PC and nothing has worked yet.
    private func serversChanged(_ servers: [ArgusServer]) {
        guard connection != .connected, !scanningQR, !servers.isEmpty else { return }
        if !pcName.isEmpty, let same = servers.first(where: { $0.name == pcName }) {
            if same.urls != savedURLList { connect(to: same) }
            return
        }
        if (savedURLList.isEmpty || !connectedThisSession) && servers.count == 1 {
            connect(to: servers[0])
        }
    }

    // MARK: - Pairing with the QR code in the PC app's Connect phone panel

    func startQRScan() {
        scanningQR = true
        camera.start()
        camera.scanForQRCodes(true)
    }

    func stopQRScan() {
        scanningQR = false
        camera.scanForQRCodes(false)
    }

    private func scanned(_ text: String) {
        guard scanningQR, let server = ArgusServer(link: text) else { return }
        stopQRScan()
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        system("Found \(server.name). Connecting…")
        connect(to: server)
    }

    /// The iPhone Camera app opened an `argus://connect?...` link from the PC's QR code.
    func open(link: URL) {
        guard let server = ArgusServer(link: link.absoluteString) else { return }
        system("Connecting to \(server.name)…")
        connect(to: server)
    }

    private func sendHello() {
        socket.send(["event": "hello", "client": "phone", "mode": mode.rawValue, "features": ["audio_queue"],
                     "device": UIDevice.current.name, "platform": "ios"])
        if mode == .slt { socket.send(["event": "set_mode", "mode": "slt", "language": signLanguage]) }
        system(pcName.isEmpty ? "Connected to ARGUS." : "Connected to ARGUS on \(pcName).")
    }

    // MARK: - Server events

    private func handle(_ e: [String: Any]) {
        switch e.string("event") {
        case "hello":
            sltAvailable = e.bool("slt_available")
            if let langs = e["slt_languages"] as? [[String: Any]] { signLanguages = langs.map(Self.language) }
            if let m = e["metrics"] as? [String: Any] { updateMetrics(m) }
            metrics.gpuName = e.string("gpu_name", metrics.gpuName)
            metrics.vramTotal = e.string("vram_total", metrics.vramTotal)
            let serverName = e.string("server_name")
            if !serverName.isEmpty { pcName = serverName }
        case "mode_changed":
            if let m = ArgusMode(rawValue: e.string("mode")) { applyMode(m, tellServer: false) }
        case "argus_reply_start":
            thinking = true
            messages.append(ChatMessage(sender: .argus, text: "", replyId: e.string("id")))
        case "argus_reply_delta":
            appendDelta(id: e.string("id"), text: e.string("text"))
        case "argus_reply":
            thinking = false
            finishReply(id: e.string("id"), text: e.string("text"))
        case "speech_recognized":
            if !e.bool("typed") { add(.you, e.string("text")) }
        case "perception_log":
            let t = e.string("text")
            if !t.isEmpty { system(t) }
        case "system_metrics":
            updateMetrics(e)
        case "tts_audio":
            audio.enqueue(base64: e.string("audio_base64"), requestId: e.string("request_id"))
        case "guide_status":
            guiding = e.bool("on")
        case "guide_update":
            var m = ChatMessage(sender: .argus, text: e.string("text"))
            m.isWarning = e.bool("warning")
            messages.append(m)
            if m.isWarning { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
            trim()
        case "slt_languages":
            if let langs = e["languages"] as? [[String: Any]] { signLanguages = langs.map(Self.language) }
            let code = e.string("language")
            if !code.isEmpty { signLanguage = code }
        case "slt_caption":
            signCaption = e.bool("unsure") ? "Not sure, please sign again" : e.string("text")
            signCaptionEnglish = ""
        case "slt_caption_english":
            signCaptionEnglish = e.string("text")
        case "slt_status":
            switch e.string("state") {
            case "signing": signStatus = "Signing… \(e.string("seconds"))s"
            case "translating": signStatus = "Translating…"
            default:
                let note = e.string("note")
                signStatus = note.isEmpty ? "Show your hands to sign" : note
            }
        case "slt_error":
            system(e.string("text"))
        default:
            break
        }
    }

    nonisolated private static func language(_ d: [String: Any]) -> SignLanguage {
        SignLanguage(code: d.string("code"), label: d.string("label"),
                     signLanguage: d.string("sign_language"), ready: d.bool("ready"))
    }

    private func updateMetrics(_ d: [String: Any]) {
        metrics.gpuName = d.string("gpu_name", metrics.gpuName)
        metrics.vramUsed = d.string("vram_used", "--")
        metrics.vramTotal = d.string("vram_total", metrics.vramTotal)
        metrics.temperature = d.string("temperature", "--")
        metrics.latency = d.string("latency", "--")
    }

    private func appendDelta(id: String, text: String) {
        if let i = messages.lastIndex(where: { $0.replyId == id && $0.sender == .argus }) {
            messages[i].text += text
        } else {
            messages.append(ChatMessage(sender: .argus, text: text, replyId: id))
        }
    }

    private func finishReply(id: String, text: String) {
        if let i = messages.lastIndex(where: { $0.replyId == id && $0.sender == .argus }) {
            messages[i].text = text
        } else {
            messages.append(ChatMessage(sender: .argus, text: text, replyId: id))
        }
        trim()
    }

    // MARK: - Messages

    func system(_ text: String) { add(.system, text) }

    private func add(_ sender: ChatMessage.Sender, _ text: String) {
        guard !text.isEmpty else { return }
        messages.append(ChatMessage(sender: sender, text: text))
        trim()
    }

    private func trim() {
        if messages.count > 60 { messages.removeFirst(messages.count - 60) }
    }

    func clearChat() { messages.removeAll() }

    // MARK: - Modes

    func selectMode(_ m: ArgusMode) { applyMode(m, tellServer: true) }

    private func applyMode(_ m: ArgusMode, tellServer: Bool) {
        let changed = m != mode
        mode = m
        frameGate.update(mode: m)
        if m != .slt {
            signCaption = ""
            signCaptionEnglish = ""
        } else if !camera.isRunning {
            camera.start()
            system("Camera on: sign in front of it.")
        }
        if m != .assistive { guiding = false }
        if tellServer {
            var event: [String: Any] = ["event": "set_mode", "mode": m.rawValue]
            if m == .slt { event["language"] = signLanguage }
            socket.send(event)
        }
        if changed { UISelectionFeedbackGenerator().selectionChanged() }
    }

    func selectSignLanguage(_ code: String) {
        socket.send(["event": "slt_language", "language": code])
    }

    func signControl(_ action: String) {
        socket.send(["event": "slt_control", "action": action])
        if action == "clear" {
            signCaption = ""
            signCaptionEnglish = ""
        }
    }

    func toggleGuide() {
        if !guiding && mode != .assistive { applyMode(.assistive, tellServer: true) }
        socket.send(["event": "guide", "on": !guiding])
    }

    // MARK: - Camera and voice

    func toggleCamera() {
        camera.isRunning ? camera.stop() : camera.start()
    }

    func toggleVoice() {
        if voiceOn {
            voiceOn = false
            speech.stop()
            return
        }
        SpeechListener.requestPermissions { [weak self] ok in
            guard let self else { return }
            guard ok else {
                self.system("Allow Microphone and Speech Recognition for ARGUS in Settings.")
                return
            }
            self.voiceOn = true
            self.speech.start()
            self.system("Listening. Say \"Hey Argus\" and your question.")
        }
    }

    /// Same flow as the Android app: stop and sleep are handled on the phone,
    /// "Hey Argus" wakes it, and once awake every phrase is a question until "goodbye".
    private func handlePhrase(_ phrase: String) {
        let normalized = Commands.normalize(phrase)
        guard !normalized.isEmpty else { return }

        if Commands.isStop(normalized) {
            add(.you, phrase)
            stopTalking()
            awake = true
            system("Stopped. Still listening.")
            return
        }
        if Commands.isSleep(normalized) {
            add(.you, phrase)
            stopTalking()
            awake = false
            system("Going to sleep. Say Hey Argus to wake me.")
            return
        }
        // While ARGUS is talking, ignore anything but stop so it doesn't answer its own voice.
        if audio.isPlaying { return }

        add(.you, phrase)
        if !awake {
            guard let rest = Commands.wakeRemainder(phrase) else {
                system("Heard you, but I'm asleep. Say Hey Argus.")
                return
            }
            awake = true
            if rest.isEmpty {
                system("Listening.")
                return
            }
            sendPrompt(rest)
            return
        }
        sendPrompt(Commands.wakeRemainder(phrase).flatMap { $0.isEmpty ? nil : $0 } ?? phrase)
    }

    /// Typed question from the text box.
    func sendTyped(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        add(.you, t)
        sendPrompt(t)
    }

    /// "Read this" shortcut: a sharp photo plus a reading question.
    func readThis() {
        add(.you, "Read this")
        sendPrompt("Read this for me.", forceOcr: true)
    }

    private func sendPrompt(_ text: String, forceOcr: Bool = false) {
        audio.stop()
        let wantsOcr = forceOcr || ocrMode || Commands.wantsOcr(text)
        var prompt: [String: Any] = ["event": "user_prompt", "text": text, "mode": mode.rawValue, "ocr": wantsOcr]
        guard wantsOcr, camera.isRunning, mode != .screen else {
            if wantsOcr && !camera.isRunning && mode != .screen {
                system("Camera is off, so ARGUS will use the last frame it has.")
            }
            socket.send(prompt)
            return
        }
        system("Taking a sharp photo to read…")
        camera.capturePhoto(maxPx: FrameConfig.ocrPhotoPx) { [weak self] jpeg in
            if let jpeg { prompt["image_data"] = jpeg }
            self?.socket.send(prompt)
        }
    }

    func stopTalking() {
        audio.stop()
        thinking = false
        socket.send(["event": "stop"])
    }
}

/// Decides, on the camera queue, whether a frame should go to the server and at what size.
final class FrameGate: @unchecked Sendable {
    private let lock = NSLock()
    private var mode: ArgusMode = .live
    private var connected = false
    private var cameraOn = false
    private var lastSent: TimeInterval = 0

    func update(mode: ArgusMode) { lock.lock(); self.mode = mode; lastSent = 0; lock.unlock() }
    func update(connected: Bool) { lock.lock(); self.connected = connected; lock.unlock() }
    func update(camera: Bool) { lock.lock(); cameraOn = camera; lock.unlock() }

    /// (isSignFrame, maxPx, jpegQuality) when a frame is due, else nil.
    func shouldSend() -> (sign: Bool, maxPx: CGFloat, quality: CGFloat)? {
        lock.lock(); defer { lock.unlock() }
        // Screen mode looks at the PC screen, so the phone camera isn't needed.
        guard connected, cameraOn, mode != .screen else { return nil }
        let sign = mode == .slt
        let interval = sign ? FrameConfig.signIntervalMs
            : (mode == .assistive ? FrameConfig.assistIntervalMs : FrameConfig.liveIntervalMs)
        let now = Date().timeIntervalSince1970 * 1000
        guard now - lastSent >= interval else { return nil }
        lastSent = now
        return sign ? (true, FrameConfig.signPx, 0.7) : (false, FrameConfig.livePx, 0.8)
    }
}
