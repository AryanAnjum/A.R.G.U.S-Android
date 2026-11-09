import AVFoundation

/// Plays the server's reply audio (`tts_audio`) sentence by sentence, in order.
/// A clip for a new request replaces whatever was still playing from the previous reply.
final class ReplyAudioPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published private(set) var isPlaying = false

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
        isPlaying = false
    }

    private func playNext() {
        guard !queue.isEmpty else {
            player = nil
            isPlaying = false
            return
        }
        let data = queue.removeFirst()
        do {
            AudioSessionConfig.activate()
            let p = try AVAudioPlayer(data: data)  // WAV (Piper) and MP3 (edge-tts) both work
            p.delegate = self
            p.prepareToPlay()
            player = p
            isPlaying = true
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
