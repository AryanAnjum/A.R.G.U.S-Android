package com.argus.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Base64
import java.io.File

/**
 * Plays the server's reply audio (`tts_audio`) sentence by sentence, in order.
 * A clip for a new request replaces whatever was still playing from the previous reply.
 * Use from the main thread.
 */
class ReplyAudioPlayer(private val context: Context) {
    var isPlaying = false
        private set
    /** When the last clip stopped (elapsedRealtime). The mic ignores speech for a moment after it. */
    var lastSoundEnd = 0L
        private set
    /** Called when ARGUS starts or stops talking (not between the sentences of one reply). */
    var onTalkingChanged: ((Boolean) -> Unit)? = null

    private val queue = ArrayDeque<File>()
    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private var requestId = ""
    private var counter = 0

    fun enqueue(base64: String, id: String) {
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return
        }
        if (bytes.isEmpty()) return
        if (id.isEmpty() || id != requestId) {
            requestId = id
            stop()
        }
        val file = File(context.cacheDir, "argus_reply_${counter++ % 64}.audio")
        try {
            file.writeBytes(bytes)
        } catch (e: Exception) {
            return
        }
        queue.addLast(file)
        if (player == null) playNext()
    }

    fun stop() {
        for (f in queue) f.delete()
        queue.clear()
        release()
        setPlaying(false)
    }

    private fun release() {
        player?.let {
            try {
                it.setOnCompletionListener(null)
                it.setOnErrorListener(null)
                it.release()
            } catch (e: Exception) {
                // already released
            }
        }
        player = null
        playingFile?.delete()
        playingFile = null
    }

    private fun setPlaying(on: Boolean) {
        if (!on && isPlaying) lastSoundEnd = SystemClock.elapsedRealtime()
        if (on == isPlaying) return
        isPlaying = on
        onTalkingChanged?.invoke(on)
    }

    private fun playNext() {
        release()
        val file = queue.removeFirstOrNull()
        if (file == null) {
            setPlaying(false)
            return
        }
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(file.absolutePath)  // WAV (Piper) and MP3 (edge-tts) both work
            p.setOnCompletionListener { playNext() }
            p.setOnErrorListener { _, _, _ ->
                playNext()
                true
            }
            p.prepare()
            player = p
            playingFile = file
            setPlaying(true)
            p.start()
        } catch (e: Exception) {
            file.delete()
            playNext()
        }
    }
}
