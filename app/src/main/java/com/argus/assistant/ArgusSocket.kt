package com.argus.assistant

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket link to the ARGUS server (`ws://<pc-ip>:8000/ws`), with automatic reconnect.
 * A PC can have several addresses (Wi-Fi and Ethernet); they are tried in turn until one opens.
 * connect / disconnect and all callbacks run on the main thread; send can be called from any thread.
 */
class ArgusSocket {
    var onEvent: ((JSONObject) -> Unit)? = null
    var onState: ((ConnectionState) -> Unit)? = null
    /** Called on every (re)connect with the address that worked, so the app can send its hello. */
    var onOpen: ((String) -> Unit)? = null

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: WebSocket? = null
    private var urls: List<String> = emptyList()
    private var urlIndex = 0
    private var wantConnected = false
    /** True once the current connection has opened (false while an address is still being tried). */
    private var opened = false
    private var generation = 0

    /** Connects to the first address that answers and keeps retrying all of them until one does. */
    fun connect(urlStrings: List<String>) {
        disconnect()
        val valid = urlStrings.map { it.trim() }.filter { it.startsWith("ws://") || it.startsWith("wss://") }
        if (valid.isEmpty()) return
        urls = valid
        urlIndex = 0
        wantConnected = true
        open()
    }

    fun disconnect() {
        wantConnected = false
        opened = false
        generation++
        socket?.cancel()
        socket = null
        onState?.invoke(ConnectionState.DISCONNECTED)
    }

    private fun open() {
        if (!wantConnected || urls.isEmpty()) return
        generation++
        val gen = generation
        onState?.invoke(ConnectionState.CONNECTING)
        val url = urls[urlIndex % urls.size]
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            scheduleReconnect(gen, false)
            return
        }
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (gen != generation) return@post
                    opened = true
                    onState?.invoke(ConnectionState.CONNECTED)
                    onOpen?.invoke(url)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return
                }
                main.post { if (gen == generation) onEvent?.invoke(event) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { dropped(gen) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { dropped(gen) }
            }
        })
    }

    private fun dropped(gen: Int) {
        if (gen != generation) return
        val wasOpen = opened
        opened = false
        socket = null
        onState?.invoke(if (wasOpen || urls.size == 1) ConnectionState.DISCONNECTED else ConnectionState.CONNECTING)
        scheduleReconnect(gen, wasOpen)
    }

    private fun scheduleReconnect(gen: Int, wasOpen: Boolean) {
        // An address that never opened: move on to the next one quickly. A dropped link: retry it.
        if (!wasOpen && urls.size > 1) urlIndex = (urlIndex + 1) % urls.size
        main.postDelayed({
            if (wantConnected && gen == generation) open()
        }, if (wasOpen || urls.size == 1) 2000L else 300L)
    }

    /** Sends an event. Every message is tagged as coming from the phone. */
    fun send(event: JSONObject): Boolean {
        event.put("origin", "phone")
        return socket?.send(event.toString()) ?: false
    }

    /** Sends a camera or screen frame unless the link is still busy with earlier ones (drops, never lags). */
    fun sendFrame(event: JSONObject) {
        val s = socket ?: return
        if (s.queueSize() > 700_000L) return
        event.put("origin", "phone")
        s.send(event.toString())
    }
}
