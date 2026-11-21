package com.argus.assistant

import java.text.Normalizer
import java.util.Locale

/** Phone-side voice commands. Kept in line with the iPhone app and the backend's wake-word rules. */
object Commands {
    /** Ways speech recognition tends to write "Argus". */
    private val argusVariants = listOf(
        "argus", "argas", "argos", "august", "aragus", "argis", "arkus", "ar gus", "our guess",
        "are guess", "artis", "arigus", "agus", "agas", "ag us", "a gus", "august us", "arguss",
        // Bangla speech recognition (Assistive mode in Bengali) writes it in Bangla letters
        "আর্গাস", "আরগাস", "আর্গুস", "আরগুস", "আর্গোস", "আরগোস", "আর্গস", "আরগস", "আর্গাছ", "আগাস",
    ).map { nfc(it) }.sortedByDescending { it.length }

    private val wakePrefixes = listOf("hey", "hi", "hello", "ok", "okay", "yo", "হেই", "হে", "হাই", "হ্যালো", "হেলো", "ওকে", "এই")
        .map { nfc(it) }

    private val stopPhrases = setOf(
        "stop", "ok stop", "okay stop", "stop talking", "stop speaking", "be quiet", "quiet", "cancel",
        "pause", "enough", "thats enough", "please stop", "okay thats enough", "ok thats enough",
        "please be quiet",
        "থামো", "থামুন", "থাম", "থামাও", "থামান", "থেমে যাও", "থেমে যান", "চুপ", "চুপ করো", "চুপ করুন", "চুপ কর",
        "কথা বন্ধ করো", "কথা বন্ধ করুন", "বন্ধ করো", "বন্ধ করুন", "স্টপ", "ওকে স্টপ", "যথেষ্ট", "প্লিজ থামো",
    ).map { nfc(it) }.toSet()

    private val sleepPhrases = setOf(
        "bye", "goodbye", "good bye", "sleep", "go to sleep", "thats all", "im done", "im finished",
        "end conversation", "okay bye", "ok bye", "okay goodbye", "ok goodbye",
        "বিদায়", "আল্লাহ হাফেজ", "আল্লাহ হাফিজ", "খোদা হাফেজ", "বাই", "বাই বাই", "গুডবাই", "গুড বাই", "টাটা",
        "ঘুমাও", "ঘুমান", "ঘুমিয়ে পড়ো", "ঘুমিয়ে পড়ুন", "আজ এই পর্যন্ত",
    ).map { nfc(it) }.toSet()

    /** Bangla words that ask to read something (a sharp full-resolution photo). */
    private val ocrWordsBn = listOf("পড়ো", "পড়ুন", "পড়ে শোনাও", "পড়ে শোনান", "কী লেখা", "কি লেখা", "লেখা আছে",
        "টাকা", "নোট", "ওষুধ", "দাম", "মেয়াদ").map { nfc(it) }

    /** Questions that need a sharp full-resolution photo (reading text, money, medicine labels). */
    private val ocrIntent = Regex(
        "\\b(read|reading|what does (it|this|that) say|what is written|whats written|written|text|label|" +
            "how much (money|is this)|money|cash|taka|banknote|note is this|bill|price|medicine|expiry|" +
            "dose|dosage|ingredients|equation|formula|number)\\b"
    )

    private val wakeForms: List<String> = buildList {
        for (variant in argusVariants) {
            for (prefix in wakePrefixes) add("$prefix $variant")
            add(variant)
        }
    }.sortedByDescending { it.length }

    /** One spelling for Bangla letters that can be written two ways (like য়). */
    private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    /** Lower case, letters and digits only (English and Bangla), single spaces. */
    fun normalize(text: String): String {
        val cleaned = StringBuilder(text.length)
        for (c in nfc(text).lowercase(Locale.US)) {
            val bangla = c in '\u0980'..'\u09FF' && c != '\u0964' && c != '\u0965'  // not the Bangla full stops
            cleaned.append(if (c in 'a'..'z' || c in '0'..'9' || bangla) c else ' ')
        }
        return cleaned.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** null: no wake word. "": wake word only. Otherwise the question that followed it. */
    fun wakeRemainder(text: String): String? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        for (wake in wakeForms) {
            if (t == wake) return ""
            if (t.startsWith("$wake ")) return t.substring(wake.length + 1).trim()
        }
        return null
    }

    private fun stripWake(t: String): String {
        for (wake in wakeForms) if (t.startsWith("$wake ")) return t.substring(wake.length + 1)
        return t
    }

    fun isStop(text: String): Boolean {
        val t = normalize(text)
        return t in stopPhrases || stripWake(t) in stopPhrases
    }

    fun isSleep(text: String): Boolean {
        val t = normalize(text)
        if (t in sleepPhrases || stripWake(t) in sleepPhrases) return true
        return argusVariants.any { v -> t == "goodbye $v" || t == "good bye $v" || t == "bye $v" || t == "sleep $v" }
    }

    fun wantsOcr(text: String): Boolean {
        val t = normalize(text)
        return ocrIntent.containsMatchIn(t) || ocrWordsBn.any { it in t }
    }
}
