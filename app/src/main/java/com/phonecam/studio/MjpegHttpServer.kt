package com.phonecam.studio

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Serveur HTTP de contrôle (port 8090) :
 *  GET /               → page de contrôle (aperçu + réglages)
 *  GET /view           → image seule plein cadre (OBS « Source navigateur »)
 *  GET /frame?after=N  → dernière image JPEG ; attend (≤ 1 s) une image plus récente que N
 *  GET /ws             → WebSocket : JPEG poussés en direct (aperçu le plus fluide)
 *  GET /stream         → flux MJPEG (multipart/x-mixed-replace)
 *  GET /api/info       → JSON : état, capacités et réglages actuels
 *  GET /api/set?k=v    → applique des réglages (manual, ev, zoom, torch, q, cam)
 */
class MjpegHttpServer(private val port: Int) {

    private companion object {
        const val TAG = "PhoneCam"
        /** Délai sans demande d'image au-delà duquel on coupe la capture JPEG. */
        const val PREVIEW_IDLE_MS = 4000L
    }

    private val frameLock = Object()
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile var frameSeq = 0L
        private set

    @Volatile private var lastFrameFetch = 0L
    @Volatile private var previewWasActive = false
    var onPreviewActivity: ((Boolean) -> Unit)? = null

    /** Appelé (thread réseau) avec les paramètres de /api/set. */
    var onSettings: ((Map<String, String>) -> Unit)? = null

    /** Fournit le JSON de /api/info. */
    var infoProvider: (() -> String)? = null

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val watcherStarted = AtomicBoolean(false)

    /** Nouvelle image JPEG du moteur caméra (null = flux arrêté). */
    fun pushFrame(jpeg: ByteArray?) {
        synchronized(frameLock) {
            latestJpeg = jpeg
            if (jpeg != null) frameSeq++
            frameLock.notifyAll()
        }
    }

    fun latestFrame(): ByteArray? = latestJpeg

    /** Signale qu'un spectateur regarde l'aperçu (garde la capture JPEG active). */
    fun markPreviewActivity() {
        lastFrameFetch = System.currentTimeMillis()
        if (!previewWasActive) {
            previewWasActive = true
            try { onPreviewActivity?.invoke(true) } catch (_: Exception) {}
        }
    }

    val previewActive: Boolean get() = previewWasActive

    fun start() {
        if (watcherStarted.compareAndSet(false, true)) {
            thread(name = "preview-watch", isDaemon = true) {
                while (true) {
                    Thread.sleep(1000)
                    if (previewWasActive && System.currentTimeMillis() - lastFrameFetch > PREVIEW_IDLE_MS) {
                        previewWasActive = false
                        try { onPreviewActivity?.invoke(false) } catch (_: Exception) {}
                    }
                }
            }
        }
        if (running.getAndSet(true)) return
        try {
            val ss = ServerSocket(port, 32, InetAddress.getByName("0.0.0.0")).apply { reuseAddress = true }
            serverSocket = ss
            Log.i(TAG, "serveur HTTP prêt sur :$port")
            thread(name = "http-accept", isDaemon = true) {
                while (running.get()) {
                    val client = try { ss.accept() } catch (e: Exception) {
                        if (!running.get() || ss.isClosed) break
                        Thread.sleep(200); continue
                    }
                    thread(name = "http-client", isDaemon = true) { handle(client) }
                }
            }
        } catch (e: Exception) {
            running.set(false)
            Log.e(TAG, "serveur HTTP: bind échoué sur $port: ${e.message}")
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    // ------------------------------------------------------------- requêtes

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15000
            client.tcpNoDelay = true
            val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            val out = BufferedOutputStream(client.getOutputStream(), 1 shl 16)
            // Keep-alive : le navigateur réutilise la connexion (moins de latence sur /frame)
            while (true) {
                val reqLine = input.readLine() ?: break
                if (reqLine.isEmpty()) continue
                var keepAlive = true
                var wsKey: String? = null
                var upgrade = false
                while (true) {
                    val h = input.readLine() ?: break
                    if (h.isEmpty()) break
                    val name = h.substringBefore(':').trim().lowercase()
                    val value = h.substringAfter(':', "").trim()
                    when (name) {
                        "connection" -> if (value.contains("close", ignoreCase = true)) keepAlive = false
                        "upgrade" -> upgrade = value.equals("websocket", ignoreCase = true)
                        "sec-websocket-key" -> wsKey = value
                    }
                }
                val target = reqLine.split(" ").getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val key = wsKey
                if (path == "/ws" && upgrade && key != null) {
                    serveJpegSocket(client, input, out, key)
                    break
                }
                val query = parseQuery(target.substringAfter('?', ""))
                if (!serveRoute(client, out, path, query) || !keepAlive) break
            }
        } catch (_: java.net.SocketTimeoutException) {
        } catch (e: Exception) {
            Log.w(TAG, "HTTP: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            try { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { k to v }
        }

    /** @return true si la connexion peut resservir (keep-alive). */
    private fun serveRoute(client: Socket, out: OutputStream, path: String, query: Map<String, String>): Boolean {
        when (path) {
            "/stream" -> { serveMjpeg(client, out); return false }
            "/frame" -> serveFrame(out, query["after"]?.toLongOrNull())
            "/api/info" -> respond(out, 200, "application/json; charset=utf-8",
                (infoProvider?.invoke() ?: "{}").toByteArray(Charsets.UTF_8))
            "/api/set" -> {
                onSettings?.invoke(query)
                respond(out, 200, "application/json", """{"ok":true}""".toByteArray())
            }
            "/", "/index.html" -> asset(out, "control.html")
            "/view" -> asset(out, "view.html")
            "/favicon.ico" -> respond(out, 204, "image/x-icon", ByteArray(0))
            else -> {
                val name = path.removePrefix("/")
                if (name.matches(Regex("[a-z0-9_-]+\\.(js|css|svg)"))) asset(out, name)
                else respond(out, 404, "text/plain", "Introuvable".toByteArray())
            }
        }
        return true
    }

    private fun asset(out: OutputStream, name: String) {
        val body = WebAssets.get(name)
        if (body == null) respond(out, 404, "text/plain", "Introuvable".toByteArray())
        else respond(out, 200, WebAssets.mimeOf(name), body, cache = "no-cache")
    }

    /** Long-poll : renvoie dès qu'une image plus récente que [after] existe (≤ 1 s d'attente). */
    private fun serveFrame(out: OutputStream, after: Long?) {
        markPreviewActivity()
        var frame: ByteArray?
        var seq: Long
        synchronized(frameLock) {
            if (after != null) {
                val deadline = System.currentTimeMillis() + 1000
                while (frameSeq <= after) {
                    val wait = deadline - System.currentTimeMillis()
                    if (wait <= 0) break
                    frameLock.wait(wait)
                }
            }
            frame = latestJpeg
            seq = frameSeq
        }
        val f = frame
        if (f == null || (after != null && seq <= after)) {
            respond(out, 204, "image/jpeg", ByteArray(0), extra = "X-Frame: $seq\r\n")
        } else {
            respond(out, 200, "image/jpeg", f, extra = "X-Frame: $seq\r\n")
        }
    }

    private fun respond(
        out: OutputStream, code: Int, contentType: String, body: ByteArray,
        cache: String = "no-store", extra: String = ""
    ) {
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; 404 -> "Not Found"; else -> "OK" }
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: $cache\r\n$extra" + "Connection: keep-alive\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    /**
     * Aperçu poussé par WebSocket : chaque message = un JPEG. Le client reçoit toujours
     * l'image la plus récente ; s'il est lent, les images intermédiaires sont sautées
     * (jamais de file d'attente → latence minimale).
     */
    private fun serveJpegSocket(client: Socket, input: BufferedReader, out: OutputStream, key: String) {
        Ws.handshake(out, key)
        client.soTimeout = 0
        // ~1,5 image en vol au maximum : le retard ne s'accumule pas dans le noyau
        try { client.sendBufferSize = 384 * 1024 } catch (_: Exception) {}
        val closedFlag = AtomicBoolean(false)
        thread(name = "jpeg-ws-read", isDaemon = true) {
            try { Ws.drainUntilClosed { input.read() } } catch (_: Exception) {}
            closedFlag.set(true)
            synchronized(frameLock) { frameLock.notifyAll() }
        }
        var lastSeq = -1L
        try {
            while (running.get() && !closedFlag.get()) {
                markPreviewActivity() // garde la capture JPEG active tant que la page est ouverte
                var frame: ByteArray?
                var seq: Long
                synchronized(frameLock) {
                    if (frameSeq == lastSeq && !closedFlag.get()) frameLock.wait(500)
                    frame = latestJpeg
                    seq = frameSeq
                }
                val f = frame
                if (f == null || seq == lastSeq) continue
                lastSeq = seq
                Ws.writeBinary(out, f)
                out.flush()
            }
        } catch (_: Exception) {
            // page fermée
        }
    }

    private fun serveMjpeg(client: Socket, out: OutputStream) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=pcframe\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
        client.soTimeout = 0
        var lastSeq = -1L
        while (running.get()) {
            markPreviewActivity() // un spectateur MJPEG garde la capture JPEG active
            var frame: ByteArray?
            synchronized(frameLock) {
                if (frameSeq == lastSeq) frameLock.wait(500)
                frame = latestJpeg
                if (frameSeq == lastSeq) frame = null
                lastSeq = frameSeq
            }
            val f = frame ?: continue
            out.write("--pcframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(f)
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }
}
