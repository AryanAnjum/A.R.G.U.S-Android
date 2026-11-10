import AVFoundation

/// Plays the server's reply audio (`tts_audio`) sentence by sentence, in order.
/// A clip for a new request replaces whatever was still playing from the previous reply.
final class ReplyAudioPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published private(set) var isPlaying = false
    /// When the last clip stopped. The mic ignores speech for a moment after it, because the tail of
    /// ARGUS's own voice is still in the recognizer.
    private(set) var lastSoundEnd = Date.distantPast
    /// Called on the main queue when ARGUS starts or stops talking (not between sentences of one reply).
    var onTalkingChanged: ((Bool) -> Void)?

    private var queue: [Data] = []
    private var player: AVAudioPlayer?
    private var requestId = ""

    func enqueue(base64: String, requestId id: String) {
        guard let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters), !data.isEmpty else { return }
        if id.isEmpty || id != requestId {
            requestId = id
            stop()
        }
        queue.append(data)
        if player == nil { playNext() }
    }

    func stop() {
        queue.removeAll()
        player?.stop()
        player = nil
        setPlaying(false)
    }

    private func setPlaying(_ on: Bool) {
        if !on && isPlaying { lastSoundEnd = Date() }
        guard on != isPlaying else { return }
        isPlaying = on
        onTalkingChanged?(on)
    }

    private func playNext() {
        guard !queue.isEmpty else {
            player = nil
            setPlaying(false)
            return
        }
        let data = queue.removeFirst()
        do {
            AudioSessionConfig.activate()
            let p = try AVAudioPlayer(data: data)  // WAV (Piper) and MP3 (edge-tts) both work
            p.delegate = self
            p.prepareToPlay()
            player = p
            setPlaying(true)
            if !p.play() { playNext() }
        } catch {
            playNext()
        }
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        DispatchQueue.main.async {
            guard player === self.player else { return }
            self.playNext()
        }
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        DispatchQueue.main.async {
            guard player === self.player else { return }
            self.playNext()
        }
    }
}
