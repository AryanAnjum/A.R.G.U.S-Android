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

    // Cameras
    /// Back, front, or the head-mount camera that is plugged into the PC.
    @AppStorage("camera") private var cameraRaw = CameraSource.back.rawValue
    var cameraSource: CameraSource { CameraSource(rawValue: cameraRaw) ?? .back }
    /// The Camera button: whether ARGUS should be looking at all.
    @Published private(set) var cameraEnabled = false
    /// Latest picture from the head-mount camera, shown while it is the chosen camera.
    @Published private(set) var headImage: UIImage?
    /// What the PC says about the head-mount camera when something is wrong with it.
    @Published private(set) var headNote = ""
    private var serverHasCameras = false
    /// "", "live" or "sign": the PC app is using this iPhone's camera.
    private var pcWantsPhone = ""
    private var headTask: Task<Void, Never>?

    // Screen mode: this iPhone's screen, shared by the ARGUS Screen broadcast (ReplayKit)
    @Published private(set) var screenSharing = false
    private var serverHasPhoneScreen = false

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

    /// How long after ARGUS's voice stops the mic keeps ignoring speech (room echo, recognizer lag).
    private static let echoTail: TimeInterval = 1.2
    /// Until when a reply's voice is still expected (between its text and its first audio clip).
    private var replyAudioDue = Date.distantPast

    /// True while ARGUS is speaking, between two sentences of a reply, or just after it finished.
    private var isArgusTalking: Bool {
        let now = Date()
        return audio.isPlaying || now < replyAudioDue
            || now.timeIntervalSince(audio.lastSoundEnd) < Self.echoTail
    }

    init() {
        socket.onState = { [weak self] s in
            guard let self else { return }
            self.connection = s
            self.frameGate.update(connected: s == .connected)
            self.applyCamera()
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
        // Echo guard: when ARGUS starts or stops talking, drop what the mic heard so far, because
        // on the loudspeaker that is ARGUS's own voice, not the user.
        audio.onTalkingChanged = { [weak self] talking in
            guard let self else { return }
            self.speech.discardCurrentPhrase()
            if !talking {
                DispatchQueue.main.asyncAfter(deadline: .now() + Self.echoTail) { [weak self] in
                    guard let self, !self.audio.isPlaying else { return }
                    self.speech.discardCurrentPhrase()
                }
            }
        }
        speech.onError = { [weak self] m in self?.system(m) }
        frameGate.update(mode: mode)
        frameGate.update(ownCamera: cameraSource != .head)
        camera.setFront(cameraSource == .front)

        let gate = frameGate
        let cam = camera
        let sock = socket
        camera.onFrame = { buffer in
            guard let spec = gate.shouldSend() else { return }
            guard let encoded = cam.encode(buffer, maxPx: spec.maxPx, quality: spec.quality) else { return }
            var event: [String: Any] = ["event": spec.signEvent ? "slt_frame" : "video_frame", "image_data": encoded]
            if spec.timed { event["ts"] = Int(Date().timeIntervalSince1970 * 1000) }
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
        applyCamera()
        camera.scanForQRCodes(true)
    }

    func stopQRScan() {
        scanningQR = false
        camera.scanForQRCodes(false)
        applyCamera()
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
        socket.send(["event": "hello", "client": "phone", "mode": mode.rawValue,
                     "features": ["audio_queue", "camera_source", "screen_share"],
                     "device": UIDevice.current.name, "platform": "ios"])
        if mode == .slt { socket.send(["event": "set_mode", "mode": "slt", "language": signLanguage]) }
        sendCameraChoice()
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
            let features = e["features"] as? [String] ?? []
            serverHasCameras = features.contains("camera_source")
            serverHasPhoneScreen = features.contains("phone_screen")
            if cameraSource == .head && !serverHasCameras {
                system("Update ARGUS on the PC to use the head-mount camera. Using this iPhone's camera for now.")
                selectCamera(.back)
            }
            if let c = e["camera"] as? [String: Any] { cameraState(c) }
        case "camera_state":
            cameraState(e)
        case "mode_changed":
            if let m = ArgusMode(rawValue: e.string("mode")) { applyMode(m, tellServer: false) }
        case "argus_reply_start":
            thinking = true
            messages.append(ChatMessage(sender: .argus, text: "", replyId: e.string("id")))
        case "argus_reply_delta":
            appendDelta(id: e.string("id"), text: e.string("text"))
        case "argus_reply":
            replyAudioDue = Date().addingTimeInterval(4)  // its voice usually starts within a few seconds
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
            replyAudioDue = .distantPast  // playback (and the tail after it) now guards the mic
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

    /// Which camera the PC uses for this iPhone, whether the PC app wants this iPhone's camera,
    /// and whether the screen broadcast is reaching the PC.
    private func cameraState(_ e: [String: Any]) {
        let wanted = e.string("phone_wanted")
        if wanted != pcWantsPhone {
            pcWantsPhone = wanted
            frameGate.update(pcWants: wanted)
            if !wanted.isEmpty { system("The PC app is using this iPhone's camera.") }
            applyCamera()
        }
        let note = (e["head"] as? [String: Any])?.string("error") ?? ""
        if note != headNote {
            headNote = note
            if !note.isEmpty && cameraSource == .head { system(note) }
        }
        let sharing = e.bool("screen_sharing")
        if sharing != screenSharing {
            screenSharing = sharing
            system(sharing ? "ARGUS can see this iPhone's screen. Open any app and ask about it."
                           : "Screen sharing stopped.")
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
        } else if !cameraEnabled {
            cameraEnabled = true
            system("Camera on: sign in front of it.")
        }
        if m != .assistive { guiding = false }
        applyCamera()
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

    /// The Camera button.
    func toggleCamera() {
        if mode == .screen {
            system("Screen mode looks at this iPhone's screen, not the camera. Pick Live, Assistive or Sign to use a camera.")
            return
        }
        cameraEnabled.toggle()
        applyCamera()
    }

    /// Back camera, front camera, or the head-mount camera on the PC. Choosing one also turns the view on.
    func selectCamera(_ source: CameraSource) {
        if source == .head && connection == .connected && !serverHasCameras {
            system("Update ARGUS on the PC to use the head-mount camera.")
            return
        }
        cameraRaw = source.rawValue
        frameGate.update(ownCamera: source != .head)
        cameraEnabled = true
        sendCameraChoice()
        applyCamera()
        UISelectionFeedbackGenerator().selectionChanged()
    }

    private func sendCameraChoice() {
        socket.send(["event": "set_camera", "source": cameraSource == .head ? "head" : "phone",
                     "facing": cameraSource == .front ? "front" : "back"])
    }

    /// Turns the iPhone's camera and the head-mount view on or off to match the current choices.
    private func applyCamera() {
        let own = cameraEnabled && cameraSource != .head && mode != .screen
        if scanningQR || !pcWantsPhone.isEmpty || own {
            camera.setFront(!scanningQR && cameraSource == .front)  // QR codes are read with the back camera
            camera.start()
        } else {
            camera.stop()
        }
        let wantHead = cameraEnabled && cameraSource == .head && mode != .screen && !scanningQR && connection == .connected
        if wantHead { startHeadView() } else { stopHeadView() }
    }

    /// Shows what the head-mount camera sees by fetching its picture from the PC a few times a second.
    private func startHeadView() {
        guard headTask == nil, let url = headURL() else { return }
        headTask = Task { [weak self] in
            let session = URLSession(configuration: .ephemeral)
            while !Task.isCancelled {
                var image: UIImage?
                if let (data, response) = try? await session.data(from: url),
                   (response as? HTTPURLResponse)?.statusCode == 200 {
                    image = UIImage(data: data)
                }
                if Task.isCancelled { break }
                if let image { self?.headImage = image }
                try? await Task.sleep(nanoseconds: image == nil ? 500_000_000 : 90_000_000)
            }
        }
    }

    private func stopHeadView() {
        headTask?.cancel()
        headTask = nil
        headImage = nil
    }

    private func headURL() -> URL? {
        guard var parts = URLComponents(string: serverURL) else { return nil }
        parts.scheme = parts.scheme == "wss" ? "https" : "http"
        parts.path = "/camera/head.jpg"
        parts.queryItems = [URLQueryItem(name: "w", value: "720")]
        return parts.url
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
        if isArgusTalking { return }

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
        // The head-mount camera and the shared screen are already on the PC at full size.
        let ownCamera = mode != .screen && cameraSource != .head
        guard wantsOcr, camera.isRunning, ownCamera else {
            if wantsOcr && !camera.isRunning && ownCamera {
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
        replyAudioDue = .distantPast
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
    /// False while the head-mount camera is the chosen one: the iPhone's own frames aren't needed then.
    private var ownCamera = true
    /// "", "live" or "sign": the PC app is using this iPhone's camera.
    private var pcWants = ""
    private var lastSent: TimeInterval = 0

    func update(mode: ArgusMode) { lock.lock(); self.mode = mode; lastSent = 0; lock.unlock() }
    func update(connected: Bool) { lock.lock(); self.connected = connected; lock.unlock() }
    func update(camera: Bool) { lock.lock(); cameraOn = camera; lock.unlock() }
    func update(ownCamera: Bool) { lock.lock(); self.ownCamera = ownCamera; lock.unlock() }
    func update(pcWants: String) { lock.lock(); self.pcWants = pcWants; lock.unlock() }

    /// What to send when a frame is due, else nil. signEvent: the iPhone's own sign session;
    /// timed: the frame carries a timestamp (any sign language session needs it).
    func shouldSend() -> (signEvent: Bool, timed: Bool, maxPx: CGFloat, quality: CGFloat)? {
        lock.lock(); defer { lock.unlock() }
        guard connected, cameraOn else { return nil }
        // Screen mode looks at the iPhone's screen, so the camera is only sent when the PC app asks for it.
        let own = ownCamera && mode != .screen
        guard own || !pcWants.isEmpty else { return nil }
        let ownSign = own && mode == .slt
        let sign = ownSign || pcWants == "sign"
        let interval = sign ? FrameConfig.signIntervalMs
            : (own && mode == .assistive ? FrameConfig.assistIntervalMs : FrameConfig.liveIntervalMs)
        let now = Date().timeIntervalSince1970 * 1000
        guard now - lastSent >= interval else { return nil }
        lastSent = now
        return sign ? (ownSign, true, FrameConfig.signPx, 0.7) : (false, false, FrameConfig.livePx, 0.8)
    }
}
