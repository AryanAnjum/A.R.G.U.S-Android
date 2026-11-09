import CoreGraphics
import Foundation

enum ArgusMode: String, CaseIterable, Identifiable {
    case screen, live, assistive, slt
    var id: String { rawValue }

    var title: String {
        switch self {
        case .screen: return "Screen"
        case .live: return "Live"
        case .assistive: return "Assistive"
        case .slt: return "Sign"
        }
    }

    var symbol: String {
        switch self {
        case .screen: return "desktopcomputer"
        case .live: return "eye"
        case .assistive: return "figure.walk"
        case .slt: return "hand.raised"
        }
    }
}

struct ChatMessage: Identifiable, Equatable {
    enum Sender { case you, argus, system }
    let id = UUID()
    var sender: Sender
    var text: String
    /// Server reply id, so streamed deltas land on the right bubble.
    var replyId: String?
    var isWarning = false
}

struct SignLanguage: Identifiable, Equatable {
    var code: String
    var label: String
    var signLanguage: String
    var ready: Bool
    var id: String { code }
}

struct ServerMetrics: Equatable {
    var gpuName = ""
    var vramUsed = "--"
    var vramTotal = "--"
    var temperature = "--"
    var latency = "--"
}

/// Tunables shared with the Android app.
enum FrameConfig {
    static let liveIntervalMs: Double = 800      // background frame for normal questions
    static let assistIntervalMs: Double = 500    // walking guidance compares frames, so send more often
    static let livePx: CGFloat = 1024            // long side of normal frames
    static let signIntervalMs: Double = 70       // ~14 fps for sign language translation
    static let signPx: CGFloat = 640
    static let ocrPhotoPx: CGFloat = 1920        // OCR photo: full detail for small text
}

extension Dictionary where Key == String, Value == Any {
    func string(_ key: String, _ fallback: String = "") -> String {
        switch self[key] {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        default: return fallback
        }
    }
    func bool(_ key: String) -> Bool {
        switch self[key] {
        case let b as Bool: return b
        case let n as NSNumber: return n.boolValue
        default: return false
        }
    }
}
