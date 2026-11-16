package com.argus.assistant

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The phone side of ARGUS: same WebSocket protocol and behaviour as the iPhone app.
 * The phone sends voice and pictures to the ARGUS PC and shows and speaks the answers.
 * Everything here runs on the main thread unless a comment says otherwise.
 */
class ArgusModel(private val app: Application) {
    private val prefs = app.getSharedPreferences("argus", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    // Connection
    /** The PC's address that last worked, the other addresses of that PC, and its name. */
    var serverUrl by mutableStateOf(prefs.getString("ws_url", "") ?: "")
        private set
    private var savedUrls = prefs.getString("ws_urls", "") ?: ""
    var pcName by mutableStateOf(prefs.getString("pc_name", "") ?: "")
        private set
    var scanningQR by mutableStateOf(false)
        private set
    private var connectedThisSession = false
    var connection by mutableStateOf(ConnectionState.DISCONNECTED)
        private set
    var metrics by mutableStateOf(ServerMetrics())
        private set
    /** ARGUS PCs found on this Wi-Fi or hotspot. */
    var servers by mutableStateOf<List<ArgusServer>>(emptyList())
        private set

    // Modes and features
    var mode by mutableStateOf(ArgusMode.LIVE)
        private set
    var ocrMode by mutableStateOf(false)
    var guiding by mutableStateOf(false)
        private set
    var sltAvailable by mutableStateOf(true)
        private set
    var signLanguages by mutableStateOf<List<SignLanguage>>(emptyList())
        private set
    var signLanguage by mutableStateOf("de")
        private set
    var signCaption by mutableStateOf("")
        private set
    var signCaptionEnglish by mutableStateOf("")
        private set
    var signStatus by mutableStateOf("Show your hands to sign")
        private set

    // Cameras
    /** Back, front, or the head-mount camera that is plugged into the PC. */
    var cameraSource by mutableStateOf(CameraSource.from(prefs.getString("camera", "back") ?: "back"))
        private set
    /** The Camera button: whether ARGUS should be looking at all. */
    var cameraEnabled by mutableStateOf(false)
        private set
    /** True while one of the phone's own cameras is running. */
    var lensRunning by mutableStateOf(false)
        private set
    /** Latest picture from the head-mount camera, shown while it is the chosen camera. */
    var remoteFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    /** What the PC says about the head-mount camera when something is wrong with it. */
    var headNote by mutableStateOf("")
        private set
    private var serverHasCameras = false
    /** "", "live" or "sign": the PC app is using this phone's camera. */
    private var pcWantsPhone = ""

    // Language
    /** English or Bengali: the language ARGUS speaks and answers in on this phone. */
    var language by mutableStateOf(ReplyLanguage.from(prefs.getString("language", "en") ?: "en"))
        private set
    private var serverHasLanguage = false
    private var translatorState = ""
    /** Bangla sentences of each reply, by sentence number, as the PC translates them. */
    private val translationParts = LinkedHashMap<String, java.util.TreeMap<Int, String>>()

    // Screen mode
    var screenSharing by mutableStateOf(false)
        private set
    private var serverHasPhoneScreen = false

    // Conversation
    val messages = mutableStateListOf<ChatMessage>()
    var thinking by mutableStateOf(false)
        private set
    var voiceOn by mutableStateOf(false)
        private set
    var awake by mutableStateOf(false)
        private set
    /** What the speech recognizer has heard so far of the current phrase. */
    var partial by mutableStateOf("")
        private set
    /** Permissions the screen should ask for now (the model needs them to carry on). */
    var permissionRequest by mutableStateOf<List<String>?>(null)
        private set

    val camera = CameraController(app)
    private val speech = SpeechListener(app)
    private val audio = ReplyAudioPlayer(app)
    private val finder = ServerFinder(app)
    private val socket = ArgusSocket()
    private val viewHttp = socket.http.newBuilder().readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()

    /** Read on the camera thread. */
    private val gate = FrameGate()
    @Volatile private var isConnected = false
    private var foreground = true
    private var nextMessageId = 1L
    private var pendingVoice = false
    @Volatile private var remoteToken = 0
    private var remoteRunning = false

    /** How long after ARGUS's voice stops the mic keeps ignoring speech (room echo, recognizer lag). */
    private val echoTailMs = 1200L
    /** Until when a reply's voice is still expected (between its text and its first audio clip). */
    private var replyAudioDue = 0L

    /** True while ARGUS is speaking, between two sentences of a reply, or just after it finished. */
    private val isArgusTalking: Boolean
        get() {
            val now = SystemClock.elapsedRealtime()
            return audio.isPlaying || now < replyAudioDue || now - audio.lastSoundEnd < echoTailMs
        }

    init {
        socket.onState = { s ->
            connection = s
            isConnected = s == ConnectionState.CONNECTED
            gate.connected = isConnected
            applyCamera()
        }
        socket.onOpen = { url ->
            serverUrl = url
            prefs.edit().putString("ws_url", url).apply()
            connectedThisSession = true
            sendHello()
        }
        socket.onEvent = { e -> handle(e) }
        finder.onServers = { found ->
            servers = found
            serversChanged(found)
        }
        camera.onQRCode = { text -> scanned(text) }
        camera.onError = { message -> system(message) }
        camera.onRunningChanged = { on ->
            lensRunning = on
            gate.lensOn = on
        }
        camera.onFrame = { image ->
            val spec = gate.shouldSend()
            if (spec != null) {
                val encoded = camera.encode(image, spec.maxPx, spec.quality)
                if (encoded != null) {
                    val event = JSONObject().put("event", if (spec.signEvent) "slt_frame" else "video_frame")
                        .put("image_data", encoded)
                    if (spec.timed) event.put("ts", System.currentTimeMillis())
                    socket.sendFrame(event)
                }
            }
        }
        speech.onPhrase = { phrase -> handlePhrase(phrase) }
        speech.onPartial = { text -> partial = text }
        speech.onError = { message -> system(message) }
        // Echo guard: when ARGUS starts or stops talking, drop what the mic heard so far, because
        // on the loudspeaker that is ARGUS's own voice, not the user.
        audio.onTalkingChanged = { talking ->
            speech.discardCurrentPhrase()
            if (!talking) {
                main.postDelayed({ if (!audio.isPlaying) speech.discardCurrentPhrase() }, echoTailMs)
            }
        }
        gate.mode = mode
        gate.ownCamera = cameraSource != CameraSource.HEAD
        camera.setFront(cameraSource == CameraSource.FRONT)
        finder.start()
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    // region Connection

    private val savedUrlList: List<String>
        get() {
            val list = savedUrls.split(",").filter { it.isNotEmpty() }
            return if (list.isEmpty() && serverUrl.isNotEmpty()) listOf(serverUrl) else list
        }

    /** Connects to the saved PC unless already connected or connecting (called when the app comes to the front). */
    fun connect() {
        finder.start()
        val saved = savedUrlList
        if (connection != ConnectionState.DISCONNECTED || saved.isEmpty()) return
        // Try the address that worked last time first.
        socket.connect(if (serverUrl in saved) listOf(serverUrl) + saved.filter { it != serverUrl } else saved)
    }

    /** Connects to a PC picked from the list, scanned from the QR code, or typed in. */
    fun connect(server: ArgusServer) {
        pcName = server.name
        savedUrls = server.urls.joinToString(",")
        serverUrl = server.urls.firstOrNull() ?: ""
        prefs.edit().putString("pc_name", pcName).putString("ws_urls", savedUrls).putString("ws_url", serverUrl).apply()
        connectedThisSession = false
        socket.connect(server.urls)
    }

    fun connectToAddress(raw: String) {
        val server = ArgusServer.fromLink(ArgusServer.normalizeAddress(raw))
        if (server == null) {
            system("That address doesn't look right. Use the PC's IP, for example 192.168.1.10.")
            return
        }
        connect(server)
    }

    /**
     * PCs found on the network changed. Connects by itself when the choice is obvious:
     * the saved PC got a new address, or there is exactly one PC and nothing has worked yet.
     */
    private fun serversChanged(found: List<ArgusServer>) {
        if (connection == ConnectionState.CONNECTED || scanningQR || found.isEmpty()) return
        val same = if (pcName.isEmpty()) null else found.firstOrNull { it.name == pcName }
        if (same != null) {
            if (same.urls != savedUrlList) connect(same)
            return
        }
        if ((savedUrlList.isEmpty() || !connectedThisSession) && found.size == 1) connect(found[0])
    }

    /** The app came to the front or went to the background. */
    fun setForeground(front: Boolean) {
        foreground = front
        if (front) {
            connect()
            if (voiceOn) speech.start()
        } else {
            finder.stop()
            // Android only lets an app use the microphone in the background while it is sharing the screen.
            if (!screenSharing) speech.stop()
        }
        socket.send(JSONObject().put("event", "app_state").put("foreground", front))
    }

    // endregion
    // region Pairing with the QR code in the PC app's Connect phone panel

    fun startQRScan() {
        scanningQR = true
        camera.scanQR = true
        applyCamera()
    }

    fun stopQRScan() {
        scanningQR = false
        camera.scanQR = false
        applyCamera()
    }

    private fun scanned(text: String) {
        if (!scanningQR) return
        val server = ArgusServer.fromLink(text) ?: return
        stopQRScan()
        buzz(40)
        system("Found ${server.name}. Connecting…")
        connect(server)
    }

    /** Another app (the phone's camera, a browser) opened an `argus://connect?...` link from the PC's QR code. */
    fun openLink(link: String) {
        val server = ArgusServer.fromLink(link) ?: return
        system("Connecting to ${server.name}…")
        connect(server)
    }

    private fun sendHello() {
        socket.send(
            JSONObject().put("event", "hello").put("client", "phone").put("mode", mode.wire)
                .put("features", JSONArray(listOf("audio_queue", "camera_source", "screen_share", "language")))
                .put("language", language.wire)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim()).put("platform", "android")
        )
        if (mode == ArgusMode.SLT) {
            socket.send(JSONObject().put("event", "set_mode").put("mode", "slt").put("language", signLanguage))
        }
        sendCameraChoice()
        socket.send(JSONObject().put("event", "app_state").put("foreground", foreground))
        if (screenSharing) socket.send(JSONObject().put("event", "screen_share").put("on", true))
        system(if (pcName.isEmpty()) "Connected to ARGUS." else "Connected to ARGUS on $pcName.")
    }

    // endregion
    // region Server events

    private fun handle(e: JSONObject) {
        when (e.optString("event")) {
            "hello" -> {
                sltAvailable = e.optBoolean("slt_available")
                e.optJSONArray("slt_languages")?.let { signLanguages = languages(it) }
                e.optJSONObject("metrics")?.let { updateMetrics(it) }
                metrics = metrics.copy(
                    gpuName = e.optString("gpu_name", metrics.gpuName),
                    vramTotal = e.optString("vram_total", metrics.vramTotal),
                )
                val serverName = e.optString("server_name")
                if (serverName.isNotEmpty()) {
                    pcName = serverName
                    prefs.edit().putString("pc_name", serverName).apply()
                }
                val features = e.optJSONArray("features")
                val names = (0 until (features?.length() ?: 0)).map { features!!.optString(it) }
                serverHasCameras = "camera_source" in names
                serverHasPhoneScreen = "phone_screen" in names
                if (cameraSource == CameraSource.HEAD && !serverHasCameras) {
                    system("Update ARGUS on the PC to use the head-mount camera. Using this phone's camera for now.")
                    selectCamera(CameraSource.BACK)
                }
                e.optJSONObject("camera")?.let { cameraState(it) }
                serverHasLanguage = "language" in names
                translatorState = ""
                e.optJSONObject("language")?.let { languageState(it) }
                if (language == ReplyLanguage.BENGALI && !serverHasLanguage) {
                    system("Update ARGUS on the PC to hear Bengali. Answers are in English for now.")
                }
            }
            "language_state" -> languageState(e)
            "argus_reply_translation" -> addTranslation(e.optString("id"), e.optInt("seq"), e.optString("text"))
            "mode_changed" -> ArgusMode.from(e.optString("mode"))?.let { applyMode(it, tellServer = false) }
            "argus_reply_start" -> {
                thinking = true
                add(Sender.ARGUS, "", replyId = e.optString("id"), allowEmpty = true)
            }
            "argus_reply_delta" -> appendDelta(e.optString("id"), e.optString("text"))
            "argus_reply" -> {
                replyAudioDue = SystemClock.elapsedRealtime() + 4000  // its voice usually starts within a few seconds
                thinking = false
                finishReply(e.optString("id"), e.optString("text"))
            }
            "speech_recognized" -> if (!e.optBoolean("typed")) add(Sender.YOU, e.optString("text"))
            "perception_log" -> system(e.optString("text"))
            "system_metrics" -> updateMetrics(e)
            "tts_audio" -> {
                replyAudioDue = 0  // playback (and the tail after it) now guards the mic
                audio.enqueue(e.optString("audio_base64"), e.optString("request_id"))
            }
            "guide_status" -> guiding = e.optBoolean("on")
            "guide_update" -> {
                val warning = e.optBoolean("warning")
                val english = e.optString("source")  // set when the update was spoken in Bangla
                if (english.isEmpty()) add(Sender.ARGUS, e.optString("text"), isWarning = warning)
                else add(Sender.ARGUS, english, isWarning = warning, translation = e.optString("text"))
                if (warning) buzz(180)
            }
            "slt_languages" -> {
                e.optJSONArray("languages")?.let { signLanguages = languages(it) }
                val code = e.optString("language")
                if (code.isNotEmpty()) signLanguage = code
            }
            "slt_caption" -> {
                signCaption = if (e.optBoolean("unsure")) "Not sure, please sign again" else e.optString("text")
                signCaptionEnglish = ""
            }
            "slt_caption_english" -> signCaptionEnglish = e.optString("text")
            "slt_status" -> signStatus = when (e.optString("state")) {
                "signing" -> "Signing… ${e.optString("seconds")}s"
                "translating" -> "Translating…"
                else -> e.optString("note").ifEmpty { "Show your hands to sign" }
            }
            "slt_error" -> system(e.optString("text"))
            "camera_state" -> cameraState(e)
        }
    }

    /** The PC tells which camera this phone's questions use and whether the PC app wants this phone's camera. */
    private fun cameraState(e: JSONObject) {
        val wanted = e.optString("phone_wanted")
        if (wanted != pcWantsPhone) {
            pcWantsPhone = wanted
            gate.pcWants = wanted
            if (wanted.isNotEmpty()) system("The PC app is using this phone's camera.")
            applyCamera()
        }
        val note = e.optJSONObject("head")?.optString("error") ?: ""
        if (note != headNote) {
            headNote = note
            if (note.isNotEmpty() && cameraSource == CameraSource.HEAD) system(note)
        }
    }

    /** The PC confirms the language (it also changes by voice: "speak Bengali") and says if Bengali is ready. */
    private fun languageState(e: JSONObject) {
        val chosen = ReplyLanguage.from(e.optString("language", language.wire))
        if (chosen != language) {
            language = chosen
            prefs.edit().putString("language", chosen.wire).apply()
        }
        val state = e.optJSONObject("translator")?.optString("state") ?: ""
        if (state != translatorState) {
            val before = translatorState
            translatorState = state
            if (language == ReplyLanguage.BENGALI) when (state) {
                "loading" -> system("Getting Bengali ready on the PC. Answers are in English until it is.")
                "error" -> system("Bengali couldn't load on the PC, so answers stay in English. Check the PC's log.")
                "ready" -> if (before == "loading") system("Bengali is ready.")
            }
        }
    }

    private fun addTranslation(id: String, seq: Int, text: String) {
        if (id.isEmpty() || text.isEmpty()) return
        val parts = translationParts.getOrPut(id) { java.util.TreeMap() }
        parts[seq] = text
        while (translationParts.size > 20) translationParts.remove(translationParts.keys.first())
        val i = messages.indexOfLast { it.replyId == id && it.sender == Sender.ARGUS }
        if (i >= 0) messages[i] = messages[i].copy(translation = parts.values.joinToString(" "))
    }

    fun selectLanguage(choice: ReplyLanguage) {
        if (choice == language) return
        language = choice
        prefs.edit().putString("language", choice.wire).apply()
        if (connection != ConnectionState.CONNECTED) return
        if (!serverHasLanguage) {
            if (choice == ReplyLanguage.BENGALI) system("Update ARGUS on the PC to hear Bengali.")
            return
        }
        socket.send(JSONObject().put("event", "set_language").put("language", choice.wire))
    }

    private fun languages(list: JSONArray): List<SignLanguage> =
        (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let {
                SignLanguage(it.optString("code"), it.optString("label"), it.optString("sign_language"), it.optBoolean("ready"))
            }
        }

    private fun updateMetrics(d: JSONObject) {
        metrics = ServerMetrics(
            gpuName = d.optString("gpu_name", metrics.gpuName),
            vramUsed = d.optString("vram_used", "--"),
            vramTotal = d.optString("vram_total", metrics.vramTotal),
            temperature = d.optString("temperature", "--"),
            latency = d.optString("latency", "--"),
        )
    }

    private fun appendDelta(id: String, text: String) {
        val i = messages.indexOfLast { it.replyId == id && it.sender == Sender.ARGUS }
        if (i >= 0) messages[i] = messages[i].copy(text = messages[i].text + text)
        else add(Sender.ARGUS, text, replyId = id)
    }

    private fun finishReply(id: String, text: String) {
        val i = messages.indexOfLast { it.replyId == id && it.sender == Sender.ARGUS }
        if (i >= 0) messages[i] = messages[i].copy(text = text)
        else add(Sender.ARGUS, text, replyId = id, translation = translationParts[id]?.values?.joinToString(" ") ?: "")
    }

    // endregion
    // region Messages

    fun system(text: String) = add(Sender.SYSTEM, text)

    private fun add(sender: Sender, text: String, replyId: String? = null, isWarning: Boolean = false,
                    allowEmpty: Boolean = false, translation: String = "") {
        if (text.isEmpty() && !allowEmpty) return
        messages.add(ChatMessage(nextMessageId++, sender, text, replyId, isWarning, translation))
        while (messages.size > 60) messages.removeAt(0)
    }

    fun clearChat() = messages.clear()

    // endregion
    // region Modes

    fun selectMode(m: ArgusMode) = applyMode(m, tellServer = true)

    private fun applyMode(m: ArgusMode, tellServer: Boolean) {
        mode = m
        gate.mode = m
        if (m != ArgusMode.SLT) {
            signCaption = ""
            signCaptionEnglish = ""
        } else if (!cameraEnabled) {
            cameraEnabled = true
            system("Camera on: sign in front of it.")
        }
        if (m != ArgusMode.ASSISTIVE) guiding = false
        if (tellServer) {
            val event = JSONObject().put("event", "set_mode").put("mode", m.wire)
            if (m == ArgusMode.SLT) event.put("language", signLanguage)
            socket.send(event)
        }
        applyCamera()
    }

    fun selectSignLanguage(code: String) {
        socket.send(JSONObject().put("event", "slt_language").put("language", code))
    }

    fun signControl(action: String) {
        socket.send(JSONObject().put("event", "slt_control").put("action", action))
        if (action == "clear") {
            signCaption = ""
            signCaptionEnglish = ""
        }
    }

    fun toggleGuide() {
        if (!guiding && mode != ArgusMode.ASSISTIVE) applyMode(ArgusMode.ASSISTIVE, tellServer = true)
        socket.send(JSONObject().put("event", "guide").put("on", !guiding))
    }

    // endregion
    // region Cameras

    /** The Camera button. */
    fun toggleCamera() {
        if (mode == ArgusMode.SCREEN) {
            system("Screen mode looks at this phone's screen, not the camera. Pick Live, Assistive or Sign to use a camera.")
            return
        }
        cameraEnabled = !cameraEnabled
        applyCamera()
    }

    /** Back camera, front camera, or the head-mount camera on the PC. Choosing one also turns the view on. */
    fun selectCamera(source: CameraSource) {
        if (source == CameraSource.HEAD && connection == ConnectionState.CONNECTED && !serverHasCameras) {
            system("Update ARGUS on the PC to use the head-mount camera.")
            return
        }
        cameraSource = source
        prefs.edit().putString("camera", source.wire).apply()
        gate.ownCamera = source != CameraSource.HEAD
        cameraEnabled = true
        sendCameraChoice()
        applyCamera()
    }

    private fun sendCameraChoice() {
        val head = cameraSource == CameraSource.HEAD
        socket.send(
            JSONObject().put("event", "set_camera").put("source", if (head) "head" else "phone")
                .put("facing", if (cameraSource == CameraSource.FRONT) "front" else "back")
        )
    }

    /** Turns the phone's camera and the head-mount view on or off to match the current choices. */
    private fun applyCamera() {
        val own = cameraEnabled && cameraSource != CameraSource.HEAD && mode != ArgusMode.SCREEN
        val wantLens = scanningQR || pcWantsPhone.isNotEmpty() || own
        if (wantLens && !has(Manifest.permission.CAMERA)) {
            camera.stop()
            if (scanningQR || own) permissionRequest = listOf(Manifest.permission.CAMERA)
        } else if (wantLens) {
            camera.setFront(!scanningQR && cameraSource == CameraSource.FRONT)  // QR codes are read with the back camera
            camera.start()
        } else {
            camera.stop()
        }
        val wantHead = cameraEnabled && cameraSource == CameraSource.HEAD && mode != ArgusMode.SCREEN &&
            !scanningQR && connection == ConnectionState.CONNECTED
        if (wantHead) startHeadView() else stopHeadView()
    }

    /** The screen asked for permissions on the model's behalf; this is the answer. */
    fun permissionResult(result: Map<String, Boolean>) {
        permissionRequest = null
        if (Manifest.permission.CAMERA in result) {
            if (result[Manifest.permission.CAMERA] == true) {
                applyCamera()
            } else {
                cameraEnabled = false
                if (scanningQR) stopQRScan()
                system("Allow Camera for ARGUS in the phone's Settings.")
            }
        }
        if (Manifest.permission.RECORD_AUDIO in result) {
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                if (pendingVoice) startVoice()
            } else {
                system("Allow Microphone for ARGUS in the phone's Settings.")
            }
            pendingVoice = false
        }
    }

    private fun httpBase(): String? {
        val url = serverUrl
        if (url.isEmpty()) return null
        val hostPort = url.substringAfter("://").substringBefore("/")
        return (if (url.startsWith("wss")) "https://" else "http://") + hostPort
    }

    /** Shows what the head-mount camera sees by fetching its picture from the PC a few times a second. */
    private fun startHeadView() {
        if (remoteRunning) return
        val base = httpBase() ?: return
        remoteRunning = true
        val token = ++remoteToken
        val thread = Thread({
            while (token == remoteToken) {
                val bitmap = try {
                    viewHttp.newCall(Request.Builder().url("$base/camera/head.jpg?w=720").build()).execute().use { r ->
                        if (r.code == 200) r.body?.byteStream()?.let { BitmapFactory.decodeStream(it) } else null
                    }
                } catch (e: Exception) {
                    null
                }
                if (token != remoteToken) break
                main.post { if (token == remoteToken) remoteFrame = bitmap?.asImageBitmap() ?: remoteFrame }
                try {
                    Thread.sleep(if (bitmap != null) 90L else 500L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "argus-head-view")
        thread.isDaemon = true
        thread.start()
    }

    private fun stopHeadView() {
        if (!remoteRunning) return
        remoteRunning = false
        remoteToken++
        remoteFrame = null
    }

    // endregion
    // region Screen sharing (Screen mode)

    /** Called by [ScreenShareService] when Android starts handing over the screen. */
    fun screenShareStarted() {
        screenSharing = true
        socket.send(JSONObject().put("event", "screen_share").put("on", true))
        if (connection == ConnectionState.CONNECTED && !serverHasPhoneScreen) {
            system("Update ARGUS on the PC so Screen mode can use this phone's screen.")
        } else {
            system("Sharing this phone's screen. Open any app and ask ARGUS about it.")
        }
    }

    fun screenShareStopped() {
        screenSharing = false
        socket.send(JSONObject().put("event", "screen_share").put("on", false))
        if (!foreground) speech.stop()
        system("Stopped sharing the screen.")
    }

    /** Called on the capture thread with a picture of the phone's screen. */
    fun sendScreenFrame(jpegBase64: String) {
        if (isConnected) socket.sendFrame(JSONObject().put("event", "screen_frame").put("image_data", jpegBase64))
    }

    // endregion
    // region Voice

    fun toggleVoice() {
        if (voiceOn) {
            voiceOn = false
            speech.stop()
            return
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            pendingVoice = true
            permissionRequest = listOf(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoice()
    }

    private fun startVoice() {
        voiceOn = true
        speech.start()
        if (speech.isListening) system("Listening. Say \"Hey Argus\" and your question.") else voiceOn = false
    }

    /**
     * Same flow as the iPhone app: stop and sleep are handled on the phone,
     * "Hey Argus" wakes it, and once awake every phrase is a question until "goodbye".
     */
    private fun handlePhrase(phrase: String) {
        val normalized = Commands.normalize(phrase)
        if (normalized.isEmpty()) return

        if (Commands.isStop(normalized)) {
            add(Sender.YOU, phrase)
            stopTalking()
            awake = true
            system("Stopped. Still listening.")
            return
        }
        if (Commands.isSleep(normalized)) {
            add(Sender.YOU, phrase)
            stopTalking()
            awake = false
            system("Going to sleep. Say Hey Argus to wake me.")
            return
        }
        // While ARGUS is talking, ignore anything but stop so it doesn't answer its own voice.
        if (isArgusTalking) return

        add(Sender.YOU, phrase)
        val rest = Commands.wakeRemainder(phrase)
        if (!awake) {
            if (rest == null) {
                system("Heard you, but I'm asleep. Say Hey Argus.")
                return
            }
            awake = true
            if (rest.isEmpty()) {
                system("Listening.")
                return
            }
            sendPrompt(rest)
            return
        }
        sendPrompt(if (rest.isNullOrEmpty()) phrase else rest)
    }

    /** Typed question from the text box. */
    fun sendTyped(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        add(Sender.YOU, t)
        sendPrompt(t)
    }

    /** "Read this" shortcut: a sharp photo plus a reading question. */
    fun readThis() {
        add(Sender.YOU, "Read this")
        sendPrompt("Read this for me.", forceOcr = true)
    }

    private fun sendPrompt(text: String, forceOcr: Boolean = false) {
        audio.stop()
        val wantsOcr = forceOcr || ocrMode || Commands.wantsOcr(text)
        val prompt = JSONObject().put("event", "user_prompt").put("text", text).put("mode", mode.wire).put("ocr", wantsOcr)
        // The head-mount camera and the shared screen are already on the PC at full size.
        val ownCamera = mode != ArgusMode.SCREEN && cameraSource != CameraSource.HEAD
        if (!(wantsOcr && ownCamera && camera.isRunning)) {
            if (wantsOcr && ownCamera) system("Camera is off, so ARGUS will use the last frame it has.")
            socket.send(prompt)
            return
        }
        system("Taking a sharp photo to read…")
        camera.capturePhoto(FrameConfig.OCR_PHOTO_PX) { jpeg ->
            if (jpeg != null) prompt.put("image_data", jpeg)
            socket.send(prompt)
        }
    }

    fun stopTalking() {
        replyAudioDue = 0
        audio.stop()
        thinking = false
        socket.send(JSONObject().put("event", "stop"))
    }

    private fun buzz(ms: Long) {
        try {
            val vibrator = app.getSystemService(Vibrator::class.java) ?: return
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // no vibrator
        }
    }

    // endregion
}

/** Decides, on the camera thread, whether a frame should go to the server and at what size. */
class FrameGate {
    class Spec(val signEvent: Boolean, val timed: Boolean, val maxPx: Int, val quality: Int)

    @Volatile var mode = ArgusMode.LIVE
    @Volatile var connected = false
    @Volatile var lensOn = false
    /** False while the head-mount camera is the chosen one: the phone's own frames aren't needed then. */
    @Volatile var ownCamera = true
    /** "", "live" or "sign": the PC app is using this phone's camera. */
    @Volatile var pcWants = ""
    private var lastSent = 0L

    @Synchronized
    fun shouldSend(): Spec? {
        if (!connected || !lensOn) return null
        // Screen mode looks at the phone's screen, so the camera isn't needed for the phone's own questions.
        val own = ownCamera && mode != ArgusMode.SCREEN
        if (!own && pcWants.isEmpty()) return null
        val ownSign = own && mode == ArgusMode.SLT
        val sign = ownSign || pcWants == "sign"
        val interval = when {
            sign -> FrameConfig.SIGN_INTERVAL_MS
            own && mode == ArgusMode.ASSISTIVE -> FrameConfig.ASSIST_INTERVAL_MS
            else -> FrameConfig.LIVE_INTERVAL_MS
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSent < interval) return null
        lastSent = now
        return if (sign) Spec(ownSign, true, FrameConfig.SIGN_PX, 70) else Spec(false, false, FrameConfig.LIVE_PX, 80)
    }
}
