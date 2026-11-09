import Foundation

/// Phone-side voice commands. Kept in line with the Android app and the backend's wake-word rules.
enum Commands {
    /// Ways iOS speech recognition tends to write "Argus".
    static let argusVariants = [
        "argus", "argas", "argos", "august", "aragus", "argis", "arkus", "ar gus", "our guess",
        "are guess", "artis", "arigus", "agus", "agas", "ag us", "a gus", "august us", "arguss",
    ].sorted { $0.count > $1.count }

    static let wakePrefixes = ["hey", "hi", "hello", "ok", "okay", "yo"]

    static let stopPhrases: Set<String> = [
        "stop", "ok stop", "okay stop", "stop talking", "stop speaking", "be quiet", "quiet", "cancel",
        "pause", "enough", "thats enough", "please stop", "okay thats enough", "ok thats enough",
        "please be quiet",
    ]

    static let sleepPhrases: Set<String> = [
        "bye", "goodbye", "good bye", "sleep", "go to sleep", "thats all", "im done", "im finished",
        "end conversation", "okay bye", "ok bye", "okay goodbye", "ok goodbye",
    ]

    /// Questions that need a sharp full-resolution photo (reading text, money, medicine labels).
    static let ocrIntent = try! NSRegularExpression(pattern:
        "\\b(read|reading|what does (it|this|that) say|what is written|whats written|written|text|label|" +
        "how much (money|is this)|money|cash|taka|banknote|note is this|bill|price|medicine|expiry|" +
        "dose|dosage|ingredients|equation|formula|number)\\b")

    static func normalize(_ text: String) -> String {
        let lower = text.lowercased()
        let cleaned = lower.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) && $0.isASCII ? Character($0) : " " }
        return String(cleaned).split(separator: " ").joined(separator: " ")
    }

    private static func wakeForms() -> [String] {
        var forms: [String] = []
        for variant in argusVariants {
            for prefix in wakePrefixes { forms.append("\(prefix) \(variant)") }
            forms.append(variant)
        }
        return forms.sorted { $0.count > $1.count }
    }
    private static let allWakeForms = wakeForms()

    /// nil: no wake word. "": wake word only. Otherwise the question that followed it.
    static func wakeRemainder(_ text: String) -> String? {
        let t = normalize(text)
        if t.isEmpty { return nil }
        for wake in allWakeForms {
            if t == wake { return "" }
            if t.hasPrefix(wake + " ") { return String(t.dropFirst(wake.count + 1)).trimmingCharacters(in: .whitespaces) }
        }
        return nil
    }

    private static func stripWake(_ t: String) -> String {
        for wake in allWakeForms where t.hasPrefix(wake + " ") {
            return String(t.dropFirst(wake.count + 1))
        }
        return t
    }

    static func isStop(_ text: String) -> Bool {
        let t = normalize(text)
        return stopPhrases.contains(t) || stopPhrases.contains(stripWake(t))
    }

    static func isSleep(_ text: String) -> Bool {
        let t = normalize(text)
        if sleepPhrases.contains(t) || sleepPhrases.contains(stripWake(t)) { return true }
        for variant in argusVariants where ["goodbye \(variant)", "good bye \(variant)", "bye \(variant)", "sleep \(variant)"].contains(t) {
            return true
        }
        return false
    }

    static func wantsOcr(_ text: String) -> Bool {
        let t = normalize(text)
        return ocrIntent.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) != nil
    }
}
