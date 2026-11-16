package com.argus.assistant

import java.util.Locale

/** Phone-side voice commands. Kept in line with the iPhone app and the backend's wake-word rules. */
object Commands {
    /** Ways speech recognition tends to write "Argus". */
    private val argusVariants = listOf(
        "argus", "argas", "argos", "august", "aragus", "argis", "arkus", "ar gus", "our guess",
        "are guess", "artis", "arigus", "agus", "agas", "ag us", "a gus", "august us", "arguss",
    ).sortedByDescending { it.length }

    private val wakePrefixes = listOf("hey", "hi", "hello", "ok", "okay", "yo")

    private val stopPhrases = setOf(
        "stop", "ok stop", "okay stop", "stop talking", "stop speaking", "be quiet", "quiet", "cancel",
        "pause", "enough", "thats enough", "please stop", "okay thats enough", "ok thats enough",
        "please be quiet",
    )

    private val sleepPhrases = setOf(
        "bye", "goodbye", "good bye", "sleep", "go to sleep", "thats all", "im done", "im finished",
        "end conversation", "okay bye", "ok bye", "okay goodbye", "ok goodbye",
    )

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

    /** Lower case, letters and digits only, single spaces. */
    fun normalize(text: String): String {
        val cleaned = StringBuilder(text.length)
        for (c in text.lowercase(Locale.US)) cleaned.append(if (c in 'a'..'z' || c in '0'..'9') c else ' ')
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

    fun wantsOcr(text: String): Boolean = ocrIntent.containsMatchIn(normalize(text))
}
