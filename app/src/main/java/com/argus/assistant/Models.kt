package com.argus.assistant

/** The four modes, with the names the server uses on the wire. */
enum class ArgusMode(val wire: String, val title: String) {
    SCREEN("screen", "Screen"),
    LIVE("live", "Live"),
    ASSISTIVE("assistive", "Assistive"),
    SLT("slt", "Sign");

    companion object {
        fun from(wire: String): ArgusMode? = entries.firstOrNull { it.wire == wire }
    }
}

/** Where the pictures come from: one of the phone's own cameras, or the head-mount camera on the PC. */
enum class CameraSource(val wire: String, val title: String) {
    BACK("back", "Back"),
    FRONT("front", "Front"),
    HEAD("head", "Head-mount");

    companion object {
        fun from(wire: String): CameraSource = entries.firstOrNull { it.wire == wire } ?: BACK
    }
}

/** The language ARGUS speaks and answers in. The PC does the translating and the Bangla voice. */
enum class ReplyLanguage(val wire: String, val title: String, val native: String, val short: String) {
    ENGLISH("en", "English", "English", "EN"),
    BENGALI("bn", "Bengali", "বাংলা", "বাং");

    companion object {
        fun from(wire: String): ReplyLanguage = entries.firstOrNull { it.wire == wire } ?: ENGLISH
    }
}

enum class Sender { YOU, ARGUS, SYSTEM }

data class ChatMessage(
    val id: Long,
    val sender: Sender,
    val text: String,
    /** Server reply id, so streamed text lands on the right bubble. */
    val replyId: String? = null,
    val isWarning: Boolean = false,
    /** The Bangla that was spoken for this message; shown on top, with [text] (English) below it. */
    val translation: String = "",
)

data class SignLanguage(val code: String, val label: String, val signLanguage: String, val ready: Boolean)

data class ServerMetrics(
    val gpuName: String = "",
    val vramUsed: String = "--",
    val vramTotal: String = "--",
    val temperature: String = "--",
    val latency: String = "--",
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/** Tunables shared with the iPhone app. */
object FrameConfig {
    const val LIVE_INTERVAL_MS = 800L      // background frame for normal questions
    const val ASSIST_INTERVAL_MS = 500L    // walking guidance looks more often
    const val PC_VIEW_INTERVAL_MS = 125L   // the PC app is showing this phone's camera: about 8 pictures a second
    const val LIVE_PX = 1024               // long side of normal frames
    const val SIGN_INTERVAL_MS = 70L       // ~14 fps for sign language translation
    const val SIGN_PX = 640
    const val OCR_PHOTO_PX = 1920          // OCR photo: full detail for small text
    const val SCREEN_INTERVAL_MS = 1200L   // how often the phone's screen is sent while sharing
    const val SCREEN_PX = 1600
}
