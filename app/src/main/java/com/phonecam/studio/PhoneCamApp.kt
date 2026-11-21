package com.phonecam.studio

import android.app.Application
import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/** Point d'entrée du process : initialise les pages web embarquées et les serveurs. */
class PhoneCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WebAssets.init(this)
        ServerBus.wire()
    }
}

/** Serveurs uniques pour tout le process (survivent aux redémarrages du service). */
object ServerBus {
    const val PORT = 8090
    const val VIDEO_PORT = 8091
    val http: MjpegHttpServer by lazy { MjpegHttpServer(PORT) }
    val h264: H264Server by lazy { H264Server(VIDEO_PORT) }

    private var wired = false

    /** Branche les serveurs sur le service courant (une seule fois, sans référence au service). */
    @Synchronized
    fun wire() {
        if (wired) return
        wired = true
        http.onSettings = { p -> StreamService.instance?.submit(p) }
        http.infoProvider = {
            StreamService.instance?.stateJson()?.toString() ?: StreamService.offlineJson().toString()
        }
        http.onPreviewActivity = { active -> StreamService.instance?.setPreviewActive(active) }
        h264.onNewClient { StreamService.instance?.requestKeyframe() }
        h264.csdProvider = { StreamService.instance?.csd() }
    }
}

/** Pages web (assets/web/…) lues une fois et gardées en mémoire. */
object WebAssets {
    private lateinit var ctx: Context
    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun init(c: Context) { ctx = c.applicationContext }

    fun get(name: String): ByteArray? {
        cache[name]?.let { return it }
        return try {
            ctx.assets.open("web/$name").use { it.readBytes() }.also { cache[name] = it }
        } catch (_: Exception) {
            null
        }
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}

/** Réseau : IP locale à afficher (WiFi en priorité, puis point d'accès, puis le reste). */
object NetInfo {
    fun localIp(): String? {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { n ->
                    n.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress }
                        .map { n.name to it.hostAddress }
                }
            fun rank(name: String) = when {
                name.startsWith("wlan") -> 0
                name.startsWith("swlan") || name.startsWith("ap") -> 1
                name.startsWith("eth") || name.startsWith("rndis") -> 2
                name.startsWith("rmnet") || name.startsWith("ccmni") -> 9 // données mobiles
                else -> 5
            }
            candidates.minByOrNull { rank(it.first) }
                ?.takeIf { rank(it.first) < 9 }
                ?.second
        } catch (_: Exception) {
            null
        }
    }
}
