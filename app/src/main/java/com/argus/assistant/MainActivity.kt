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

// --- UPDATED FOR ANDROID STUDIO EMULATOR ---
private const val WS_URL = "ws://10.0.2.2:8000/ws"

// --- SLEEK DARK STREAMING COLORS ---
val GlassCard = Color(0xAA0D0D12)
val ArgusGreen = Color(0xFF00E676)
val ArgusTeal = Color(0xFF1DE9B6)
val ArgusGradient = Brush.horizontalGradient(listOf(ArgusGreen, ArgusTeal))
val ArgusSubtext = Color(0xFF8A8A99)

data class ChatMessage(val id: String = UUID.randomUUID().toString(), val sender: String, val text: String, val isSystem: Boolean = false)
data class SystemMetrics(val vramUsed: String = "--", val vramTotal: String = "--", val temperature: String = "--", val latency: String = "--")

class MainActivity : ComponentActivity() {

    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var socket: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var mediaPlayer: MediaPlayer? = null

    // State Trackers
    private var isCameraRunning by mutableStateOf(false)
    private var isVoiceActive by mutableStateOf(false)
    private var lensFacing by mutableStateOf(CameraSelector.LENS_FACING_BACK)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val lastCameraFrameMs = AtomicLong(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black)) {
                ArgusScreen(
                    onConnect = { connectToBackend() },
                    onDisconnect = { disconnectBackend() },
                    onMode = { mode -> sendEvent(JSONObject().put("event", "set_mode").put("mode", mode)) },
                    onVoiceToggle = { toggleContinuousVoice() },
                    onCameraToggle = { togglePhoneCamera() },
                    onFlipCamera = { flipCamera() },
                    isCameraRunning = isCameraRunning,
                    isVoiceActive = isVoiceActive,
                    onSurfaceProviderReady = { provider ->
                        surfaceProvider = provider
                        if (isCameraRunning) restartCamera()
                    }
                )
            }
        }
    }

    private fun connectToBackend() {
        if (socket != null) return
        connectionStatus.value = "Connecting..."
        val request = Request.Builder().url(WS_URL).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post { connectionStatus.value = "Connected" }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text)
                    when (data.optString("event")) {
                        "argus_reply" -> addTransientMessage("A.R.G.U.S.", data.optString("text"))
                        "speech_recognized" -> addTransientMessage("You", data.optString("text"))
                        "perception_log" -> {
                            val msg = data.optString("text", "")
                            if (msg.isNotBlank()) addTransientMessage("System", msg, isSystem = true)
                        }
                        "system_metrics" -> updateMetrics(data)
                        "tts_audio" -> playPhoneAudio(data.optString("audio_base64", ""))
                        "mode_changed" -> mainHandler.post {
                            connectionStatus.value = "Connected • Mode: ${data.optString("mode").uppercase()}"
                        }
                    }
                } catch (_: Exception) {}
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post { connectionStatus.value = "Disconnected"; socket = null }
            }
        })
    }

    private fun disconnectBackend() {
        socket?.close(1000, "User disconnected")
        socket = null
        connectionStatus.value = "Disconnected"
    }

    private fun addTransientMessage(sender: String, text: String, isSystem: Boolean = false) {
        val msg = ChatMessage(sender = sender, text = text, isSystem = isSystem)
        mainHandler.post {
            chatMessages.add(msg)
            mainHandler.postDelayed({ chatMessages.remove(msg) }, 5000)
        }
    }

    private fun toggleContinuousVoice() {
        if (isVoiceActive) {
            isVoiceActive = false
            speechRecognizer?.stopListening()
            addTransientMessage("System", "Voice paused.", isSystem = true)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                return
            }
            isVoiceActive = true
            startListeningLoop()
            addTransientMessage("System", "Voice active.", isSystem = true)
        }
    }

    private fun startListeningLoop() {
        if (!isVoiceActive) return
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        if (isVoiceActive) mainHandler.postDelayed({ startListeningLoop() }, 400)
                    }
                    override fun onResults(results: Bundle?) {
                        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                        if (!phrase.isNullOrBlank()) {
                            addTransientMessage("You", phrase)
                            sendEvent(JSONObject().put("event", "user_prompt").put("text", phrase).put("origin", "phone"))
                        }
                        if (isVoiceActive) mainHandler.postDelayed({ startListeningLoop() }, 400)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            }
            speechRecognizer?.startListening(intent)
        }
    }

    private fun togglePhoneCamera() {
        if (isCameraRunning) {
            cameraProvider?.unbindAll()
            isCameraRunning = false
            addTransientMessage("System", "Optics offline.", isSystem = true)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                return
            }
            isCameraRunning = true
            restartCamera()
            addTransientMessage("System", "Optics online.", isSystem = true)
        }
    }

    private fun flipCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        if (isCameraRunning) {
            restartCamera()
        }
    }

    private fun restartCamera() {
        if (surfaceProvider == null) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            analysis.setAnalyzer(cameraExecutor) { image -> sendCameraFrame(image) }

            try {
                provider.unbindAll()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                provider.bindToLifecycle(this, selector, preview, analysis)
                cameraProvider = provider
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendCameraFrame(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastCameraFrameMs.get() < 600L) return
            lastCameraFrameMs.set(now)
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            val output = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 30, output)
            val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            sendEvent(JSONObject().put("event", "video_frame").put("image_data", encoded))
            bitmap.recycle()
            if (cropped !== bitmap) cropped.recycle()
        } catch (_: Exception) {} finally { image.close() }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true && isVoiceActive) startListeningLoop()
        if (results[Manifest.permission.CAMERA] == true && isCameraRunning) restartCamera()
    }

    private fun playPhoneAudio(encoded: String) {
        if (encoded.isBlank()) return
        try {
            val file = File(cacheDir, "argus_reply.mp3").apply { writeBytes(Base64.decode(encoded, Base64.DEFAULT)) }
            mainHandler.post {
                mediaPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        it.release()
                        file.delete()
                        if (mediaPlayer == this) mediaPlayer = null
                    }
                    prepare()
                    start()
                }
            }
        } catch (_: Exception) {}
    }

    private fun updateMetrics(data: JSONObject) {
        mainHandler.post {
            systemMetrics.value = SystemMetrics(
                data.optString("vram_used", "--"), data.optString("vram_total", "--"),
                data.optString("temperature", "--"), data.optString("latency", "--")
            )
        }
    }

    private fun sendEvent(data: JSONObject) { socket?.send(data.toString()) }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        socket?.close(1000, "App closed")
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    companion object {
        val connectionStatus = mutableStateOf("Disconnected")
        val chatMessages = mutableStateListOf<ChatMessage>()
        val systemMetrics = mutableStateOf(SystemMetrics())
    }
}

// ==========================================
// CLEAN STREAMING HUD UI
// ==========================================
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
    onSurfaceProviderReady: (Preview.SurfaceProvider) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(MainActivity.chatMessages.size) {
        if (MainActivity.chatMessages.isNotEmpty()) listState.animateScrollToItem(MainActivity.chatMessages.lastIndex)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    onSurfaceProviderReady(this.surfaceProvider)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(GlassCard).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("A.R.G.U.S.", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Default)

                MetricsRow(MainActivity.systemMetrics.value)

                val isConnected = MainActivity.connectionStatus.value.contains("Connected")
                Button(
                    onClick = { if (isConnected) onDisconnect() else onConnect() },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isConnected) Color.Transparent else ArgusGreen),
                    modifier = if (isConnected) Modifier.border(1.dp, ArgusGreen, CircleShape) else Modifier,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text(if (isConnected) "ONLINE" else "CONNECT", color = if (isConnected) ArgusGreen else Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(0.75f).heightIn(max = 180.dp).padding(bottom = 8.dp)
            ) {
                items(MainActivity.chatMessages, key = { it.id }) { msg ->
                    AnimatedVisibility(
                        visible = true,
                        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(tween(200)),
                        exit = fadeOut(tween(400))
                    ) {
                        val isAI = msg.sender == "A.R.G.U.S."
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(GlassCard)
                                .padding(8.dp)
                        ) {
                            Text(msg.sender.uppercase(), color = if (isAI) ArgusTeal else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(msg.text, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(GlassCard).padding(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    listOf("SCREEN", "LIVE", "ASSIST").forEach { mode ->
                        TextButton(onClick = { onMode(mode.lowercase()) }, contentPadding = PaddingValues(2.dp)) {
                            Text(mode, color = ArgusSubtext, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onVoiceToggle,
                        modifier = Modifier.weight(1.2f).height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(24.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, if (isVoiceActive) ArgusGradient else androidx.compose.ui.graphics.SolidColor(Color(0xFF333344)))
                    ) {
                        Text(if (isVoiceActive) "MIC ON" else "MIC OFF", color = if (isVoiceActive) ArgusGreen else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onCameraToggle,
                        modifier = Modifier.weight(1.2f).height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(24.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, if (isCameraRunning) ArgusGradient else androidx.compose.ui.graphics.SolidColor(Color(0xFF333344)))
                    ) {
                        Text(if (isCameraRunning) "CAM ON" else "CAM OFF", color = if (isCameraRunning) ArgusTeal else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onFlipCamera,
                        modifier = Modifier.weight(0.8f).height(46.dp),
                        shape = RoundedCornerShape(24.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF444455)),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("FLIP", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun MetricsRow(metrics: SystemMetrics) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "VRAM", color = ArgusSubtext, fontSize = 8.sp, fontFamily = FontFamily.Default)
            Text(text = "${metrics.vramUsed}G", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "TEMP", color = ArgusSubtext, fontSize = 8.sp, fontFamily = FontFamily.Default)
            Text(text = "${metrics.temperature}°", color = ArgusTeal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "LAT", color = ArgusSubtext, fontSize = 8.sp, fontFamily = FontFamily.Default)
            Text(text = "${metrics.latency}ms", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}