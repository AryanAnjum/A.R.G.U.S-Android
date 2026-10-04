package com.argus.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong


// ============================================================
// ARGUS BACKEND
// PHYSICAL PHONE -> PC
// ============================================================

private const val WS_URL = "ws://192.168.50.107:8000/ws"


// ============================================================
// COLORS
// ============================================================

val GlassCard = Color(0xAA0D0D12)
val ArgusGreen = Color(0xFF00E676)
val ArgusTeal = Color(0xFF1DE9B6)
val ArgusGradient = Brush.horizontalGradient(
    listOf(ArgusGreen, ArgusTeal)
)
val ArgusSubtext = Color(0xFF8A8A99)


// ============================================================
// DATA CLASSES
// ============================================================

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val isSystem: Boolean = false
)

data class SystemMetrics(
    val vramUsed: String = "--",
    val vramTotal: String = "--",
    val temperature: String = "--",
    val latency: String = "--"
)


// ============================================================
// MAIN ACTIVITY
// ============================================================

class MainActivity : ComponentActivity() {

    private val client =
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    private var socket: WebSocket? = null

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null

    private var cameraProvider: ProcessCameraProvider? = null

    private var surfaceProvider: Preview.SurfaceProvider? = null

    private var mediaPlayer: MediaPlayer? = null


    // ========================================================
    // STATE
    // ========================================================

    private var isCameraRunning by mutableStateOf(false)

    private var isVoiceActive by mutableStateOf(false)

    private var lensFacing by mutableStateOf(
        CameraSelector.LENS_FACING_BACK
    )

    private val cameraExecutor =
        Executors.newSingleThreadExecutor()

    private val lastCameraFrameMs =
        AtomicLong(0L)

    // Phone-local wake/sleep state.
    private var phoneAwake = false


    // ========================================================
    // ACTIVITY
    // ========================================================

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContent {

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black
                )
            ) {

                ArgusScreen(

                    onConnect = {
                        connectToBackend()
                    },

                    onDisconnect = {
                        disconnectBackend()
                    },

                    onMode = { mode ->

                        sendEvent(
                            JSONObject()
                                .put("event", "set_mode")
                                .put("mode", mode)
                        )
                    },

                    onVoiceToggle = {
                        toggleContinuousVoice()
                    },

                    onCameraToggle = {
                        togglePhoneCamera()
                    },

                    onFlipCamera = {
                        flipCamera()
                    },

                    isCameraRunning = isCameraRunning,

                    isVoiceActive = isVoiceActive,

                    onSurfaceProviderReady = { provider ->

                        surfaceProvider = provider

                        if (isCameraRunning) {
                            restartCamera()
                        }
                    }
                )
            }
        }
    }


    // ========================================================
    // WEBSOCKET CONNECTION
    // ========================================================

    private fun connectToBackend() {

        // Already connected/connecting
        if (socket != null) {
            return
        }

        connectionStatus.value = "Connecting..."

        val request =
            Request.Builder()
                .url(WS_URL)
                .build()

        socket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response
                    ) {

                        mainHandler.post {

                            connectionStatus.value =
                                "Connected"
                        }
                    }


                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String
                    ) {

                        try {

                            val data =
                                JSONObject(text)

                            when (
                                data.optString("event")
                            ) {

                                // ------------------------
                                // ARGUS TEXT RESPONSE
                                // ------------------------

                                "argus_reply" -> {

                                    addTransientMessage(
                                        "A.R.G.U.S.",
                                        data.optString("text")
                                    )
                                }


                                // ------------------------
                                // SPEECH RECOGNIZED
                                // ------------------------

                                "speech_recognized" -> {

                                    addTransientMessage(
                                        "You",
                                        data.optString("text")
                                    )
                                }


                                // ------------------------
                                // PERCEPTION LOG
                                // ------------------------

                                "perception_log" -> {

                                    val msg =
                                        data.optString(
                                            "text",
                                            ""
                                        )

                                    if (msg.isNotBlank()) {

                                        addTransientMessage(
                                            "System",
                                            msg,
                                            isSystem = true
                                        )
                                    }
                                }


                                // ------------------------
                                // SYSTEM METRICS
                                // ------------------------

                                "system_metrics" -> {

                                    updateMetrics(data)
                                }


                                // ------------------------
                                // PHONE TTS
                                // ------------------------

                                "tts_audio" -> {

                                    playPhoneAudio(
                                        data.optString(
                                            "audio_base64",
                                            ""
                                        )
                                    )
                                }


                                // ------------------------
                                // MODE CHANGE
                                // ------------------------

                                "mode_changed" -> {

                                    mainHandler.post {

                                        connectionStatus.value =
                                            "Connected • Mode: ${
                                                data.optString(
                                                    "mode"
                                                ).uppercase()
                                            }"
                                    }
                                }
                            }

                        } catch (_: Exception) {

                            // Ignore malformed messages
                        }
                    }


                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {

                        mainHandler.post {

                            connectionStatus.value =
                                "Connection failed"

                            socket = null
                            phoneAwake = false

                            addTransientMessage(
                                "System",
                                "Backend connection failed: ${
                                    t.message ?: "Unknown error"
                                }",
                                isSystem = true
                            )
                        }
                    }


                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        mainHandler.post {

                            connectionStatus.value =
                                "Disconnected"

                            socket = null
                            phoneAwake = false
                        }
                    }
                }
            )
    }


    // ========================================================
    // DISCONNECT
    // ========================================================

    private fun disconnectBackend() {

        socket?.close(
            1000,
            "User disconnected"
        )

        socket = null
        phoneAwake = false

        connectionStatus.value =
            "Disconnected"
    }


    // ========================================================
    // CHAT MESSAGE
    // ========================================================

    private fun addTransientMessage(
        sender: String,
        text: String,
        isSystem: Boolean = false
    ) {

        val msg =
            ChatMessage(
                sender = sender,
                text = text,
                isSystem = isSystem
            )

        mainHandler.post {

            chatMessages.add(msg)

            mainHandler.postDelayed(
                {
                    chatMessages.remove(msg)
                },
                5000
            )
        }
    }


    // ========================================================
    // PHONE VOICE
    // ========================================================

    private fun toggleContinuousVoice() {

        if (isVoiceActive) {

            isVoiceActive = false

            speechRecognizer?.stopListening()

            addTransientMessage(
                "System",
                "Voice paused.",
                isSystem = true
            )

        } else {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO
                    )
                )

                return
            }

            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                addTransientMessage(
                    "System",
                    "Speech recognition is not available on this phone.",
                    isSystem = true
                )
                return
            }

            isVoiceActive = true
            startListeningLoop()

            addTransientMessage(
                "System",
                "Voice active. Listening...",
                isSystem = true
            )
        }
    }


    // ========================================================
    // SPEECH RECOGNITION LOOP
    // ========================================================

    // ========================================================
    // PHONE-LOCAL WAKE / STOP / SLEEP CONTROL
    // ========================================================

    // ========================================================
    // PHONE-LOCAL WAKE / STOP / SLEEP CONTROL
    // ========================================================

    private fun normalizePhoneCommand(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // Android SpeechRecognizer often writes "Argus" phonetically.
    // Keep these aligned with the backend wake-word behavior and add
    // common mobile-recognition mistakes.
    private val phoneArgusVariants = listOf(
        "argus",
        "argas",
        "argos",
        "august",
        "aragus",
        "argis",
        "arkus",
        "ar gus",
        "our guess",
        "are guess",
        "artis",
        "arigus",
        "agus",
        "agas",
        "ag us",
        "a gus",
        "ar gus"
    )

    private val phoneWakePrefixes = listOf(
        "hey",
        "hi",
        "hello",
        "ok",
        "okay",
        "yo"
    )

    private fun isPhoneArgusWord(text: String): Boolean {
        val t = normalizePhoneCommand(text)
        return phoneArgusVariants.contains(t)
    }

    private fun extractPhoneWakeRemainder(text: String): String? {
        val t = normalizePhoneCommand(text)

        if (t.isBlank()) return null

        // Accept:
        //   Argus
        //   Agus
        //   Agas
        //   Arigus
        //   Hey Argus
        //   Okay Argus
        //   Hey Agus what do you see?
        //
        // This deliberately mirrors the backend's "optional wake prefix"
        // behavior, but with extra Android SpeechRecognizer variants.
        for (variant in phoneArgusVariants.sortedByDescending { it.length }) {

            if (t == variant) {
                return ""
            }

            val suffix = " $variant "
            if (t.startsWith(suffix)) {
                return t.removePrefix(suffix).trim()
            }

            val endSuffix = " $variant"
            if (t.endsWith(endSuffix)) {
                // Example: "hey argus" after a prefix.
                val prefix = t.removeSuffix(endSuffix).trim()
                if (prefix in phoneWakePrefixes) {
                    return ""
                }
            }

            for (prefix in phoneWakePrefixes) {
                val fullPrefix = "$prefix $variant"

                if (t == fullPrefix) {
                    return ""
                }

                if (t.startsWith("$fullPrefix ")) {
                    return t.removePrefix("$fullPrefix ").trim()
                }
            }
        }

        return null
    }

    private fun isPhoneStopCommand(text: String): Boolean {
        val t = normalizePhoneCommand(text)

        val basic = setOf(
            "stop",
            "ok stop",
            "okay stop",
            "stop talking",
            "stop speaking",
            "be quiet",
            "quiet",
            "cancel",
            "pause",
            "enough",
            "thats enough",
            "please stop",
            "okay thats enough",
            "ok thats enough",
            "please be quiet"
        )

        if (t in basic) return true

        // Also accept "Argus stop", "Agus stop", "Hey Argus stop", etc.
        for (variant in phoneArgusVariants) {
            for (prefix in listOf("", "hey ", "hi ", "hello ", "ok ", "okay ", "yo ")) {
                val wake = "${prefix}${variant}".trim()
                if (
                    t == "$wake stop" ||
                    t == "$wake stop talking" ||
                    t == "$wake stop speaking" ||
                    t == "$wake be quiet" ||
                    t == "$wake quiet" ||
                    t == "$wake cancel" ||
                    t == "$wake pause"
                ) {
                    return true
                }
            }
        }

        return false
    }

    private fun isPhoneSleepCommand(text: String): Boolean {
        val t = normalizePhoneCommand(text)

        val basic = setOf(
            "bye",
            "goodbye",
            "good bye",
            "sleep",
            "go to sleep",
            "thats all",
            "im done",
            "im finished",
            "end conversation",
            "okay bye",
            "ok bye",
            "okay goodbye",
            "ok goodbye"
        )

        if (t in basic) return true

        for (variant in phoneArgusVariants) {
            for (prefix in listOf("", "hey ", "hi ", "hello ", "ok ", "okay ", "yo ")) {
                val wake = "${prefix}${variant}".trim()

                if (
                    t == "$wake goodbye" ||
                    t == "$wake good bye" ||
                    t == "$wake bye" ||
                    t == "$wake sleep" ||
                    t == "$wake go to sleep" ||
                    t == "goodbye $variant" ||
                    t == "good bye $variant" ||
                    t == "bye $variant" ||
                    t == "sleep $variant"
                ) {
                    return true
                }
            }
        }

        return false
    }

    private fun stopPhoneAudio() {
        mainHandler.post {
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                } catch (_: Exception) {
                }
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
            mediaPlayer = null
        }
    }

    private fun handlePhoneSpeech(phrase: String) {

        val cleanedPhrase = phrase.trim()
        val normalized = normalizePhoneCommand(cleanedPhrase)

        if (normalized.isBlank()) return

        // Always show what Android actually recognized.
        addTransientMessage("You", cleanedPhrase)

        if (isPhoneStopCommand(normalized)) {
            stopPhoneAudio()
            phoneAwake = true
            addTransientMessage(
                "System",
                "Stopped. Still listening.",
                isSystem = true
            )
            return
        }

        if (isPhoneSleepCommand(normalized)) {
            stopPhoneAudio()
            phoneAwake = false
            addTransientMessage(
                "System",
                "Going to sleep. Say Hey Argus to wake me.",
                isSystem = true
            )
            return
        }

        if (!phoneAwake) {
            val wakeRemainder = extractPhoneWakeRemainder(cleanedPhrase)

            if (wakeRemainder == null) {
                addTransientMessage(
                    "System",
                    "Heard you, but I'm asleep. Say Hey Argus.",
                    isSystem = true
                )
                return
            }

            phoneAwake = true

            if (wakeRemainder.isBlank()) {
                addTransientMessage(
                    "System",
                    "Listening.",
                    isSystem = true
                )
                return
            }

            sendEvent(
                JSONObject()
                    .put("event", "user_prompt")
                    .put("text", wakeRemainder)
                    .put("origin", "phone")
            )
            return
        }

        sendEvent(
            JSONObject()
                .put("event", "user_prompt")
                .put("text", cleanedPhrase)
                .put("origin", "phone")
        )
    }


    private fun startListeningLoop() {

        if (!isVoiceActive) return

        mainHandler.post {

            if (!isVoiceActive) return@post

            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }

            speechRecognizer =
                SpeechRecognizer
                    .createSpeechRecognizer(this)
                    .apply {

                        setRecognitionListener(
                            object : RecognitionListener {

                                override fun onReadyForSpeech(params: Bundle?) {
                                    addTransientMessage(
                                        "System",
                                        "Listening...",
                                        isSystem = true
                                    )
                                }

                                override fun onBeginningOfSpeech() {
                                    addTransientMessage(
                                        "System",
                                        "Hearing you...",
                                        isSystem = true
                                    )
                                }

                                override fun onRmsChanged(rmsdB: Float) {}

                                override fun onBufferReceived(buffer: ByteArray?) {}

                                override fun onEndOfSpeech() {}

                                override fun onError(error: Int) {

                                    val errorText = when (error) {
                                        SpeechRecognizer.ERROR_AUDIO ->
                                            "Microphone audio error."
                                        SpeechRecognizer.ERROR_CLIENT ->
                                            "Speech recognizer client error."
                                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                            "Microphone permission is missing."
                                        SpeechRecognizer.ERROR_NETWORK ->
                                            "Speech recognition network error."
                                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                            "Speech recognition network timeout."
                                        SpeechRecognizer.ERROR_NO_MATCH ->
                                            "I heard no recognizable speech."
                                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                            "Speech recognizer is busy."
                                        SpeechRecognizer.ERROR_SERVER ->
                                            "Speech recognition server error."
                                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                            "No speech detected."
                                        else ->
                                            "Speech recognition error: $error"
                                    }

                                    addTransientMessage(
                                        "System",
                                        errorText,
                                        isSystem = true
                                    )

                                    if (isVoiceActive) {
                                        mainHandler.postDelayed(
                                            {
                                                if (isVoiceActive) {
                                                    startListeningLoop()
                                                }
                                            },
                                            500
                                        )
                                    }
                                }

                                override fun onResults(results: Bundle?) {

                                    val phrase =
                                        results
                                            ?.getStringArrayList(
                                                SpeechRecognizer.RESULTS_RECOGNITION
                                            )
                                            ?.firstOrNull()
                                            ?.trim()

                                    if (!phrase.isNullOrBlank()) {
                                        handlePhoneSpeech(phrase)
                                    } else {
                                        addTransientMessage(
                                            "System",
                                            "Speech result was empty.",
                                            isSystem = true
                                        )
                                    }

                                    if (isVoiceActive) {
                                        mainHandler.postDelayed(
                                            {
                                                if (isVoiceActive) {
                                                    startListeningLoop()
                                                }
                                            },
                                            250
                                        )
                                    }
                                }

                                override fun onPartialResults(
                                    partialResults: Bundle?
                                ) {
                                    // Partial results are intentionally not sent.
                                }

                                override fun onEvent(
                                    eventType: Int,
                                    params: Bundle?
                                ) {}
                            }
                        )
                    }

            val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        "en-US"
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                        "en-US"
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        true
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        5
                    )
                }

            try {
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {

                addTransientMessage(
                    "System",
                    "Could not start microphone: ${
                        e.message ?: "Unknown error"
                    }",
                    isSystem = true
                )

                if (isVoiceActive) {
                    mainHandler.postDelayed(
                        {
                            if (isVoiceActive) {
                                startListeningLoop()
                            }
                        },
                        1000
                    )
                }
            }
        }
    }


    // ========================================================
    // PHONE CAMERA
    // ========================================================

    private fun togglePhoneCamera() {

        if (isCameraRunning) {

            cameraProvider?.unbindAll()

            isCameraRunning = false

            addTransientMessage(
                "System",
                "Optics offline.",
                isSystem = true
            )

        } else {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA
                    )
                )

                return
            }

            isCameraRunning = true

            restartCamera()

            addTransientMessage(
                "System",
                "Optics online.",
                isSystem = true
            )
        }
    }


    // ========================================================
    // FLIP CAMERA
    // ========================================================

    private fun flipCamera() {

        lensFacing =
            if (
                lensFacing ==
                CameraSelector.LENS_FACING_BACK
            ) {

                CameraSelector.LENS_FACING_FRONT

            } else {

                CameraSelector.LENS_FACING_BACK
            }

        if (isCameraRunning) {
            restartCamera()
        }
    }


    // ========================================================
    // CAMERA SETUP
    // ========================================================

    private fun restartCamera() {

        if (surfaceProvider == null) {
            return
        }

        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener(

            {

                val provider =
                    providerFuture.get()

                val preview =
                    Preview.Builder()
                        .build()
                        .also {

                            it.setSurfaceProvider(
                                surfaceProvider
                            )
                        }


                val analysis =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                            ImageAnalysis
                                .STRATEGY_KEEP_ONLY_LATEST
                        )
                        .setOutputImageFormat(
                            ImageAnalysis
                                .OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )
                        .build()


                analysis.setAnalyzer(
                    cameraExecutor
                ) { image ->

                    sendCameraFrame(image)
                }


                try {

                    provider.unbindAll()

                    val selector =
                        CameraSelector
                            .Builder()
                            .requireLensFacing(
                                lensFacing
                            )
                            .build()

                    provider.bindToLifecycle(
                        this,
                        selector,
                        preview,
                        analysis
                    )

                    cameraProvider =
                        provider

                } catch (_: Exception) {
                }

            },

            ContextCompat.getMainExecutor(this)
        )
    }


    // ========================================================
    // CAMERA FRAME -> BACKEND
    // ========================================================

    private fun sendCameraFrame(
        image: ImageProxy
    ) {

        try {

            val now =
                System.currentTimeMillis()

            // Send at most one frame every 600ms
            if (
                now -
                lastCameraFrameMs.get()
                < 600L
            ) {

                return
            }

            lastCameraFrameMs.set(now)


            val plane =
                image.planes.firstOrNull()
                    ?: return

            val buffer =
                plane.buffer

            val pixelStride =
                plane.pixelStride

            val rowStride =
                plane.rowStride

            val width =
                image.width

            val height =
                image.height

            val rowPadding =
                rowStride -
                        pixelStride * width

            val paddedWidth =
                width +
                        rowPadding / pixelStride


            val bitmap =
                Bitmap.createBitmap(
                    paddedWidth,
                    height,
                    Bitmap.Config.ARGB_8888
                )


            bitmap.copyPixelsFromBuffer(
                buffer
            )


            val cropped =
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    width,
                    height
                )


            val output =
                ByteArrayOutputStream()


            cropped.compress(
                Bitmap.CompressFormat.JPEG,
                30,
                output
            )


            val encoded =
                Base64.encodeToString(
                    output.toByteArray(),
                    Base64.NO_WRAP
                )


            sendEvent(

                JSONObject()
                    .put(
                        "event",
                        "video_frame"
                    )
                    .put(
                        "image_data",
                        encoded
                    )
            )


            bitmap.recycle()

            if (cropped !== bitmap) {
                cropped.recycle()
            }

        } catch (_: Exception) {

        } finally {

            image.close()
        }
    }


    // ========================================================
    // PERMISSIONS
    // ========================================================

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->

            if (
                results[
                    Manifest.permission.RECORD_AUDIO
                ] == true &&
                isVoiceActive
            ) {

                startListeningLoop()
            }


            if (
                results[
                    Manifest.permission.CAMERA
                ] == true &&
                isCameraRunning
            ) {

                restartCamera()
            }
        }


    // ========================================================
    // PHONE TTS
    // ========================================================

    private fun playPhoneAudio(
        encoded: String
    ) {

        if (encoded.isBlank()) {
            return
        }

        try {

            val file =
                File(
                    cacheDir,
                    "argus_reply.mp3"
                ).apply {

                    writeBytes(
                        Base64.decode(
                            encoded,
                            Base64.DEFAULT
                        )
                    )
                }


            mainHandler.post {

                mediaPlayer?.let {

                    if (it.isPlaying) {
                        it.stop()
                    }

                    it.release()
                }


                mediaPlayer =
                    MediaPlayer().apply {

                        setDataSource(
                            file.absolutePath
                        )

                        setOnCompletionListener {

                            it.release()

                            file.delete()

                            if (
                                mediaPlayer ==
                                this
                            ) {

                                mediaPlayer = null
                            }
                        }

                        prepare()

                        start()
                    }
            }

        } catch (_: Exception) {
        }
    }


    // ========================================================
    // SYSTEM METRICS
    // ========================================================

    private fun updateMetrics(
        data: JSONObject
    ) {

        mainHandler.post {

            systemMetrics.value =
                SystemMetrics(

                    data.optString(
                        "vram_used",
                        "--"
                    ),

                    data.optString(
                        "vram_total",
                        "--"
                    ),

                    data.optString(
                        "temperature",
                        "--"
                    ),

                    data.optString(
                        "latency",
                        "--"
                    )
                )
        }
    }


    // ========================================================
    // SEND EVENT TO BACKEND
    // ========================================================

    private fun sendEvent(
        data: JSONObject
    ) {

        socket?.send(
            data.toString()
        )
    }


    // ========================================================
    // CLEANUP
    // ========================================================

    override fun onDestroy() {

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        cameraProvider?.unbindAll()

        cameraExecutor.shutdown()

        socket?.close(
            1000,
            "App closed"
        )

        stopPhoneAudio()

        super.onDestroy()
    }


    // ========================================================
    // SHARED UI STATE
    // ========================================================

    companion object {

        val connectionStatus =
            mutableStateOf(
                "Disconnected"
            )

        val chatMessages =
            mutableStateListOf<ChatMessage>()

        val systemMetrics =
            mutableStateOf(
                SystemMetrics()
            )
    }
}


// ============================================================
// ARGUS UI
// ============================================================

@Composable
fun ArgusScreen(

    onConnect: () -> Unit,

    onDisconnect: () -> Unit,

    onMode: (String) -> Unit,

    onVoiceToggle: () -> Unit,

    onCameraToggle: () -> Unit,

    onFlipCamera: () -> Unit,

    isCameraRunning: Boolean,

    isVoiceActive: Boolean,

    onSurfaceProviderReady:
        (Preview.SurfaceProvider) -> Unit

) {

    val listState =
        rememberLazyListState()


    LaunchedEffect(
        MainActivity.chatMessages.size
    ) {

        if (
            MainActivity.chatMessages.isNotEmpty()
        ) {

            listState.animateScrollToItem(
                MainActivity.chatMessages.lastIndex
            )
        }
    }


    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
    ) {


        // ====================================================
        // CAMERA PREVIEW
        // ====================================================

        AndroidView(

            factory = { ctx ->

                PreviewView(ctx).apply {

                    scaleType =
                        PreviewView
                            .ScaleType
                            .FILL_CENTER

                    onSurfaceProviderReady(
                        this.surfaceProvider
                    )
                }
            },

            modifier =
                Modifier.fillMaxSize()
        )


        // ====================================================
        // HUD
        // ====================================================

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = 12.dp,
                        vertical = 24.dp
                    ),

            verticalArrangement =
                Arrangement.SpaceBetween
        ) {


            // =================================================
            // TOP BAR
            // =================================================

            Row(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(
                            RoundedCornerShape(
                                20.dp
                            )
                        )
                        .background(
                            GlassCard
                        )
                        .padding(12.dp),

                verticalAlignment =
                    Alignment.CenterVertically,

                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {


                Text(
                    "A.R.G.U.S.",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight =
                        FontWeight.ExtraBold,
                    fontFamily =
                        FontFamily.Default
                )


                MetricsRow(
                    MainActivity.systemMetrics.value
                )


                val isConnected =
                    MainActivity
                        .connectionStatus
                        .value
                        .contains(
                            "Connected"
                        )


                Button(

                    onClick = {

                        if (isConnected) {

                            onDisconnect()

                        } else {

                            onConnect()
                        }
                    },

                    colors =
                        ButtonDefaults.buttonColors(

                            containerColor =
                                if (isConnected)
                                    Color.Transparent
                                else
                                    ArgusGreen
                        ),

                    modifier =
                        if (isConnected)
                            Modifier.border(
                                1.dp,
                                ArgusGreen,
                                CircleShape
                            )
                        else
                            Modifier,

                    shape =
                        CircleShape,

                    contentPadding =
                        PaddingValues(
                            horizontal = 12.dp,
                            vertical = 2.dp
                        )
                ) {

                    Text(

                        if (isConnected)
                            "ONLINE"
                        else
                            "CONNECT",

                        color =
                            if (isConnected)
                                ArgusGreen
                            else
                                Color.Black,

                        fontSize = 10.sp,

                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }


            // =================================================
            // SPACER
            // =================================================

            Spacer(
                modifier =
                    Modifier.weight(1f)
            )


            // =================================================
            // CHAT
            // =================================================

            LazyColumn(

                state = listState,

                verticalArrangement =
                    Arrangement.spacedBy(6.dp),

                modifier =
                    Modifier
                        .fillMaxWidth(0.75f)
                        .heightIn(
                            max = 180.dp
                        )
                        .padding(
                            bottom = 8.dp
                        )
            ) {

                items(

                    MainActivity.chatMessages,

                    key = {
                        it.id
                    }

                ) { msg ->


                    AnimatedVisibility(

                        visible = true,

                        enter =
                            slideInVertically(
                                initialOffsetY = {
                                    it / 2
                                }
                            ) +
                                    fadeIn(
                                        tween(200)
                                    ),

                        exit =
                            fadeOut(
                                tween(400)
                            )
                    ) {


                        val isAI =
                            msg.sender ==
                                    "A.R.G.U.S."


                        Column(

                            modifier =
                                Modifier
                                    .clip(
                                        RoundedCornerShape(
                                            12.dp
                                        )
                                    )
                                    .background(
                                        GlassCard
                                    )
                                    .padding(8.dp)
                        ) {


                            Text(

                                msg.sender.uppercase(),

                                color =
                                    if (isAI)
                                        ArgusTeal
                                    else
                                        Color.White,

                                fontSize = 9.sp,

                                fontWeight =
                                    FontWeight.Bold,

                                fontFamily =
                                    FontFamily.Default
                            )


                            Spacer(
                                modifier =
                                    Modifier.height(2.dp)
                            )


                            Text(

                                msg.text,

                                color =
                                    Color.White,

                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }


            // =================================================
            // BOTTOM CONTROLS
            // =================================================

            Column(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(
                            RoundedCornerShape(
                                20.dp
                            )
                        )
                        .background(
                            GlassCard
                        )
                        .padding(10.dp)
            ) {


                // =================================================
                // MODE BUTTONS
                // =================================================

                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceAround
                ) {

                    listOf(
                        "SCREEN",
                        "LIVE",
                        "ASSIST"
                    ).forEach { mode ->

                        TextButton(

                            onClick = {

                                onMode(
                                    mode.lowercase()
                                )
                            },

                            contentPadding =
                                PaddingValues(
                                    2.dp
                                )
                        ) {

                            Text(

                                mode,

                                color =
                                    ArgusSubtext,

                                fontSize = 11.sp,

                                fontWeight =
                                    FontWeight.Bold
                            )
                        }
                    }
                }


                Spacer(
                    modifier =
                        Modifier.height(6.dp)
                )


                // =================================================
                // MIC / CAMERA / FLIP
                // =================================================

                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.spacedBy(6.dp)
                ) {


                    // MIC

                    Button(

                        onClick =
                            onVoiceToggle,

                        modifier =
                            Modifier
                                .weight(1.2f)
                                .height(46.dp),

                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    Color.Transparent
                            ),

                        shape =
                            RoundedCornerShape(
                                24.dp
                            ),

                        border =
                            androidx.compose.foundation.BorderStroke(

                                1.5.dp,

                                if (isVoiceActive)
                                    ArgusGreen
                                else
                                    Color(0xFF333344)
                            )
                    ) {

                        Text(

                            if (isVoiceActive)
                                "MIC ON"
                            else
                                "MIC OFF",

                            color =
                                if (isVoiceActive)
                                    ArgusGreen
                                else
                                    Color.White,

                            fontSize = 11.sp,

                            fontWeight =
                                FontWeight.Bold
                        )
                    }


                    // CAMERA

                    Button(

                        onClick =
                            onCameraToggle,

                        modifier =
                            Modifier
                                .weight(1.2f)
                                .height(46.dp),

                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    Color.Transparent
                            ),

                        shape =
                            RoundedCornerShape(
                                24.dp
                            ),

                        border =
                            androidx.compose.foundation.BorderStroke(

                                1.5.dp,

                                if (isCameraRunning)
                                    ArgusTeal
                                else
                                    Color(0xFF333344)
                            )
                    ) {

                        Text(

                            if (isCameraRunning)
                                "CAM ON"
                            else
                                "CAM OFF",

                            color =
                                if (isCameraRunning)
                                    ArgusTeal
                                else
                                    Color.White,

                            fontSize = 11.sp,

                            fontWeight =
                                FontWeight.Bold
                        )
                    }


                    // FLIP

                    OutlinedButton(

                        onClick =
                            onFlipCamera,

                        modifier =
                            Modifier
                                .weight(0.8f)
                                .height(46.dp),

                        shape =
                            RoundedCornerShape(
                                24.dp
                            ),

                        border =
                            androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                Color(0xFF444455)
                            ),

                        contentPadding =
                            PaddingValues(0.dp)
                    ) {

                        Text(

                            "FLIP",

                            color =
                                Color.White,

                            fontSize = 11.sp,

                            fontWeight =
                                FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}


// ============================================================
// METRICS
// ============================================================

@Composable
fun MetricsRow(
    metrics: SystemMetrics
) {

    Row(
        horizontalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {


        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(
                text = "VRAM",
                color = ArgusSubtext,
                fontSize = 8.sp,
                fontFamily =
                    FontFamily.Default
            )

            Text(
                text =
                    "${metrics.vramUsed}G",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight =
                    FontWeight.Bold
            )
        }


        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(
                text = "TEMP",
                color = ArgusSubtext,
                fontSize = 8.sp,
                fontFamily =
                    FontFamily.Default
            )

            Text(
                text =
                    "${metrics.temperature}°",
                color = ArgusTeal,
                fontSize = 10.sp,
                fontWeight =
                    FontWeight.Bold
            )
        }


        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(
                text = "LAT",
                color = ArgusSubtext,
                fontSize = 8.sp,
                fontFamily =
                    FontFamily.Default
            )

            Text(
                text =
                    "${metrics.latency}ms",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight =
                    FontWeight.Bold
            )
        }
    }
}
