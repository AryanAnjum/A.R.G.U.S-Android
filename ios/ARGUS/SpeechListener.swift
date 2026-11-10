import AVFoundation
import Speech

/// Continuous listening with Apple speech recognition (on-device when the iPhone supports it).
/// A phrase ends after a short pause, is handed to `onPhrase`, and listening carries on.
final class SpeechListener: NSObject, ObservableObject {
    @Published private(set) var isListening = false
    @Published private(set) var partial = ""

    var onPhrase: ((String) -> Void)?
    var onError: ((String) -> Void)?

    /// Pause that ends a phrase. Matches the backend's 1.0 s end-of-speech pause.
    var endOfPhrasePause: TimeInterval = 1.0

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var silenceTimer: Timer?
    private var latestText = ""
    private var tapInstalled = false

    static func requestPermissions(_ done: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            AVAudioSession.sharedInstance().requestRecordPermission { mic in
                DispatchQueue.main.async { done(status == .authorized && mic) }
            }
        }
    }

    func start() {
        guard !isListening else { return }
        guard let recognizer, recognizer.isAvailable else {
            onError?("Speech recognition isn't available on this iPhone right now.")
            return
        }
        do {
            AudioSessionConfig.activate()
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            if !tapInstalled {
                input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
                    self?.request?.append(buffer)
                }
                tapInstalled = true
            }
            engine.prepare()
            try engine.start()
            isListening = true
            beginTask()
        } catch {
            onError?("Couldn't start the microphone: \(error.localizedDescription)")
            stop()
        }
    }

    func stop() {
        isListening = false
        silenceTimer?.invalidate()
        task?.cancel()
        task = nil
        request?.endAudio()
        request = nil
        if engine.isRunning { engine.stop() }
        if tapInstalled {
            engine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        partial = ""
    }

    /// Throws away whatever has been heard so far (ARGUS's own voice) and starts listening afresh.
    func discardCurrentPhrase() {
        guard isListening else { return }
        silenceTimer?.invalidate()
        latestText = ""
        partial = ""
        request?.endAudio()
        request = nil
        task?.cancel()
        task = nil
        beginTask()
    }

    private func beginTask() {
        guard isListening, let recognizer else { return }
        task?.cancel()
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        if recognizer.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
        req.contextualStrings = ["Argus", "Hey Argus", "guide me", "read this", "sign mode", "assistive mode"]
        if #available(iOS 16.0, *) { req.addsPunctuation = false }
        request = req
        latestText = ""
        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            DispatchQueue.main.async { self?.handle(result: result, error: error, for: req) }
        }
    }

    private func handle(result: SFSpeechRecognitionResult?, error: Error?, for req: SFSpeechAudioBufferRecognitionRequest) {
        guard req === request else { return }  // a result from a task we already replaced
        if let result {
            latestText = result.bestTranscription.formattedString
            partial = latestText
            silenceTimer?.invalidate()
            if result.isFinal {
                finishPhrase()
                return
            }
            silenceTimer = Timer.scheduledTimer(withTimeInterval: endOfPhrasePause, repeats: false) { [weak self] _ in
                self?.finishPhrase()
            }
        } else if error != nil {
            // Recognition tasks end on long silence or after about a minute; just start a fresh one.
            finishPhrase()
        }
    }

    private func finishPhrase() {
        silenceTimer?.invalidate()
        let text = latestText.trimmingCharacters(in: .whitespacesAndNewlines)
        latestText = ""
        partial = ""
        request?.endAudio()
        request = nil
        task?.cancel()
        task = nil
        if !text.isEmpty { onPhrase?(text) }
        if isListening {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in self?.beginTask() }
        }
    }
}

/// One shared audio session: record and play together, with echo cancellation so ARGUS
/// doesn't hear its own voice, and replies on the loudspeaker (or Bluetooth / earphones).
enum AudioSessionConfig {
    static func activate() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playAndRecord, mode: .voiceChat,
                           options: [.defaultToSpeaker, .allowBluetooth, .allowBluetoothA2DP, .duckOthers])
        try? s.setActive(true, options: [])
    }
}
