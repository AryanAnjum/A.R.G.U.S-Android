package com.argus.assistant

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** A PC running ARGUS that the phone can connect to. */
data class ArgusServer(
    val name: String,
    /** WebSocket addresses of that PC, best first (it may have both Wi-Fi and Ethernet). */
    val urls: List<String>,
) {
    companion object {
        /**
         * From a pairing link: `argus://connect?h=192.168.1.10,10.0.0.5&p=8000&n=MY-PC`
         * (the QR code in the PC app's Connect phone panel). Also accepts a plain `ws://` address.
         */
        fun fromLink(link: String): ArgusServer? {
            val text = link.trim()
            val lower = text.lowercase(Locale.US)
            val uri = try {
                Uri.parse(text)
            } catch (e: Exception) {
                return null
            }
            if (lower.startsWith("ws://") || lower.startsWith("wss://")) {
                val host = uri.host ?: return null
                return if (host.isEmpty()) null else ArgusServer(host, listOf(text))
            }
            if (uri.scheme?.lowercase(Locale.US) != "argus" || !uri.isHierarchical) return null
            val hosts = (uri.getQueryParameter("h") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (hosts.isEmpty()) return null
            val port = uri.getQueryParameter("p")?.toIntOrNull() ?: 8000
            val name = uri.getQueryParameter("n").let { if (it.isNullOrEmpty()) hosts[0] else it }
            return ArgusServer(name, hosts.map { "ws://$it:$port/ws" })
        }

        /** Accepts "192.168.1.10" or "192.168.1.10:8000" and fills in the rest. */
        fun normalizeAddress(raw: String): String {
            var s = raw.trim()
            if (!s.startsWith("ws://") && !s.startsWith("wss://")) s = "ws://$s"
            val afterScheme = s.substringAfter("://")
            val hostPort = afterScheme.substringBefore("/")
            if (!hostPort.contains(":")) s = s.replaceFirst(hostPort, "$hostPort:8000")
            if (!afterScheme.contains("/")) s += "/ws"
            return s
        }

        /** "ws://192.168.1.10:8000/ws" -> "192.168.1.10" (the port is shown only when it isn't 8000). */
        fun shortAddress(url: String): String =
            url.replace("ws://", "").replace("wss://", "").replace(":8000/ws", "").replace("/ws", "")
    }
}

/**
 * Finds ARGUS PCs on the same Wi-Fi or hotspot. The PC announces itself with Bonjour
 * (`_argus._tcp`, see `src/argus_connect.py` in the backend), so nothing has to be typed.
 */
class ServerFinder(context: Context) {
    /** Called on the main thread whenever the list of PCs changes. */
    var onServers: ((List<ArgusServer>) -> Unit)? = null

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, ArgusServer>()
    private var listener: NsdManager.DiscoveryListener? = null
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (listener != null || nsd == null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { listener = null }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post {
                    toResolve.addLast(serviceInfo)
                    resolveNext()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (found.remove(serviceInfo.serviceName) != null) publish()
                }
            }
        }
        listener = l
        try {
            nsd.discoverServices("_argus._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try {
            nsd?.stopServiceDiscovery(l)
        } catch (e: Exception) {
            // already stopped
        }
    }

    /** Android resolves one service at a time, so they wait in a queue. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || nsd == null) return
        val info = toResolve.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        add(serviceInfo)
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    @Suppress("DEPRECATION")
    private fun add(info: NsdServiceInfo) {
        val txt = HashMap<String, String>()
        for ((key, value) in info.attributes) if (value != null) txt[key] = String(value, Charsets.UTF_8)
        var hosts = (txt["hosts"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (hosts.isEmpty()) hosts = listOfNotNull(info.host?.hostAddress)
        if (hosts.isEmpty()) return
        val port = txt["port"]?.toIntOrNull() ?: info.port.takeIf { it > 0 } ?: 8000
        val path = txt["path"] ?: "/ws"
        val name = txt["name"].let { if (it.isNullOrEmpty()) info.serviceName else it }
        found[info.serviceName] = ArgusServer(name, hosts.map { "ws://$it:$port$path" })
        publish()
    }

    private fun publish() {
        onServers?.invoke(found.values.distinctBy { it.name }.sortedBy { it.name })
    }
}
