import CoreImage
import Foundation
import ImageIO
import Network
import ReplayKit

/// "ARGUS Screen" broadcast: while it runs, iOS hands this extension pictures of the whole iPhone screen
/// (in every app). It sends one to the ARGUS PC about once a second when the screen changes, so Screen mode
/// answers questions about the iPhone's screen. The camera is never used.
///
/// The extension finds the PC on its own with Bonjour (`_argus._tcp`, like the app's PC list), so no
/// shared settings with the app are needed. iOS limits broadcast extensions to about 50 MB of memory, so
/// pictures are shrunk before they are encoded.
final class SampleHandler: RPBroadcastSampleHandler {
    private let sendEvery: TimeInterval = 1.0
    private let maxPx: CGFloat = 1600
    private let context = CIContext(options: [.useSoftwareRenderer: false, .cacheIntermediates: false])
    private let queue = DispatchQueue(label: "argus.screen")
    private var browser: NWBrowser?
    private var urls: [URL] = []
    private var urlIndex = 0
    private var task: URLSessionWebSocketTask?
    private var session: URLSession!
    private var open = false
    private var lastSent = Date.distantPast
    private var busy = false
    private var stopped = false

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        session = URLSession(configuration: .ephemeral)
        findPC()
    }

    override func broadcastFinished() {
        queue.sync {
            stopped = true
            browser?.cancel()
            task?.cancel(with: .normalClosure, reason: nil)
        }
    }

    // MARK: Finding and connecting to the PC

    private func findPC() {
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_argus._tcp", domain: nil), using: NWParameters())
        b.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            var found: [URL] = []
            for r in results {
                guard case let .bonjour(txt) = r.metadata else { continue }
                let port = txt["port"] ?? "8000"
                let path = txt["path"] ?? "/ws"
                for host in (txt["hosts"] ?? "").split(separator: ",") where !host.isEmpty {
                    if let url = URL(string: "ws://\(host):\(port)\(path)") { found.append(url) }
                }
            }
            self.queue.async {
                guard !found.isEmpty, found != self.urls else { return }
                self.urls = found
                if !self.open { self.urlIndex = 0; self.connect() }
            }
        }
        b.start(queue: queue)
        browser = b
    }

    private func connect() {
        guard !stopped, !urls.isEmpty else { return }
        task?.cancel()
        open = false
        let url = urls[urlIndex % urls.count]
        var request = URLRequest(url: url)
        request.timeoutInterval = 4
        let t = session.webSocketTask(with: request)
        task = t
        t.resume()
        let hello: [String: Any] = ["event": "hello", "client": "phone_screen", "device": "iPhone screen"]
        send(hello, on: t) { [weak self] ok in
            guard let self else { return }
            self.queue.async {
                guard t === self.task else { return }
                if ok {
                    self.open = true
                    self.lastSent = .distantPast
                } else {
                    self.urlIndex += 1
                    self.queue.asyncAfter(deadline: .now() + 1.5) { self.connect() }
                }
            }
        }
        listen(t)
    }

    /// Reads (and ignores) what the PC sends, so a dropped link is noticed and reconnected.
    private func listen(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success: self.listen(t)
            case .failure:
                self.queue.async {
                    guard t === self.task, !self.stopped else { return }
                    self.open = false
                    self.queue.asyncAfter(deadline: .now() + 1.5) { self.connect() }
                }
            }
        }
    }

    private func send(_ event: [String: Any], on t: URLSessionWebSocketTask, done: @escaping (Bool) -> Void) {
        guard let data = try? JSONSerialization.data(withJSONObject: event),
              let text = String(data: data, encoding: .utf8) else { done(false); return }
        t.send(.string(text)) { error in done(error == nil) }
    }

    // MARK: Screen pictures

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video, let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let ready: Bool = queue.sync {
            guard open, !busy, Date().timeIntervalSince(lastSent) >= sendEvery else { return false }
            busy = true
            lastSent = Date()
            return true
        }
        guard ready else { return }
        var orientation = CGImagePropertyOrientation.up
        if let raw = CMGetAttachment(sampleBuffer, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber,
           let o = CGImagePropertyOrientation(rawValue: raw.uint32Value) {
            orientation = o
        }
        let jpeg: Data? = autoreleasepool {
            var image = CIImage(cvPixelBuffer: pixels).oriented(orientation)
            let longSide = max(image.extent.width, image.extent.height)
            if longSide > maxPx {
                let s = maxPx / longSide
                image = image.transformed(by: CGAffineTransform(scaleX: s, y: s))
            }
            guard let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
            return context.jpegRepresentation(of: image, colorSpace: space, options: [
                CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.75,
            ])
        }
        queue.async {
            guard let jpeg, let t = self.task, self.open else { self.busy = false; return }
            let event: [String: Any] = ["event": "screen_frame", "image_data": jpeg.base64EncodedString()]
            self.send(event, on: t) { _ in self.queue.async { self.busy = false } }
        }
    }
}
