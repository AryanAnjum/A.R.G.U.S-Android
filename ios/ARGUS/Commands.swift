import Foundation

/// Phone-side voice commands. Kept in line with the Android app and the backend's wake-word rules.
enum Commands {
    /// Ways iOS speech recognition tends to write "Argus".
    static let argusVariants = [
        "argus", "argas", "argos", "august", "aragus", "argis", "arkus", "ar gus", "our guess",
        "are guess", "artis", "arigus", "agus", "agas", "ag us", "a gus", "august us", "arguss",
        // Bangla speech recognition (Assistive mode in Bengali) writes it in Bangla letters
        "আর্গাস", "আরগাস", "আর্গুস", "আরগুস", "আর্গোস", "আরগোস", "আর্গস", "আরগস", "আর্গাছ", "আগাস",
    ].sorted { $0.count > $1.count }

    static let wakePrefixes = ["hey", "hi", "hello", "ok", "okay", "yo", "হেই", "হে", "হাই", "হ্যালো", "হেলো", "ওকে", "এই"]

    static let stopPhrases: Set<String> = [
        "stop", "ok stop", "okay stop", "stop talking", "stop speaking", "be quiet", "quiet", "cancel",
        "pause", "enough", "thats enough", "please stop", "okay thats enough", "ok thats enough",
        "please be quiet",
        "থামো", "থামুন", "থাম", "থামাও", "থামান", "থেমে যাও", "থেমে যান", "চুপ", "চুপ করো", "চুপ করুন", "চুপ কর",
        "কথা বন্ধ করো", "কথা বন্ধ করুন", "বন্ধ করো", "বন্ধ করুন", "স্টপ", "ওকে স্টপ", "যথেষ্ট", "প্লিজ থামো",
    ]

    static let sleepPhrases: Set<String> = [
        "bye", "goodbye", "good bye", "sleep", "go to sleep", "thats all", "im done", "im finished",
        "end conversation", "okay bye", "ok bye", "okay goodbye", "ok goodbye",
        "বিদায়", "আল্লাহ হাফেজ", "আল্লাহ হাফিজ", "খোদা হাফেজ", "বাই", "বাই বাই", "গুডবাই", "গুড বাই", "টাটা",
        "ঘুমাও", "ঘুমান", "ঘুমিয়ে পড়ো", "ঘুমিয়ে পড়ুন", "আজ এই পর্যন্ত",
    ]

    /// Bangla words that ask to read something (a sharp full-resolution photo).
    static let ocrWordsBn = ["পড়ো", "পড়ুন", "পড়ে শোনাও", "পড়ে শোনান", "কী লেখা", "কি লেখা", "লেখা আছে",
                             "টাকা", "নোট", "ওষুধ", "দাম", "মেয়াদ"]

    /// Questions that need a sharp full-resolution photo (reading text, money, medicine labels).
    static let ocrIntent = try! NSRegularExpression(pattern:
        "\\b(read|reading|what does (it|this|that) say|what is written|whats written|written|text|label|" +
        "how much (money|is this)|money|cash|taka|banknote|note is this|bill|price|medicine|expiry|" +
        "dose|dosage|ingredients|equation|formula|number)\\b")

    /// Lower case, letters and digits only (English and Bangla), single spaces.
    static func normalize(_ text: String) -> String {
        let lower = text.precomposedStringWithCanonicalMapping.lowercased()
        var scalars = String.UnicodeScalarView()
        for s in lower.unicodeScalars {
            let ascii = s.isASCII && CharacterSet.alphanumerics.contains(s)
            let bangla = (0x0980...0x09FF).contains(s.value) && s.value != 0x0964 && s.value != 0x0965  // not the full stops
            scalars.append(ascii || bangla ? s : " ")
        }
        return String(scalars).split(separator: " ").joined(separator: " ")
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
        if ocrWordsBn.contains(where: { t.contains($0) }) { return true }
        return ocrIntent.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) != nil
    }
}
