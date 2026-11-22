package com.phonecam.studio

import android.util.Log
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Serveur H.264 (port 8091) :
 *  GET /h264      → flux Annex-B brut (OBS « Source média », ffmpeg, ffplay, mpv)
 *  GET /ws        → WebSocket binaire (un message = une access unit Annex-B)
 *  GET /view      → page lecteur plein cadre (WebCodecs)
 *  GET /player.js → lecteur (réutilisé par la page de contrôle :8090)
 */
class H264Server(private val port: Int) {

    companion object {
        private const val TAG = "PhoneCam"
        /** ~0,33 s de retard toléré avant de décrocher le client et de repartir d'un keyframe. */
        private const val MAX_QUEUE = 10
    }

    /**
     * File d'un client. On ne jette jamais une image P isolée (ça corrompt l'image jusqu'au
     * prochain keyframe) : si le client prend trop de retard, on vide tout et on attend un
     * keyframe, précédé des SPS/PPS.
     */
    private inner class Sink {
        val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile var closed = false
        @Volatile var needKey = true

        fun deliver(au: ByteArray, kind: AuKind) {
            when (kind) {
                AuKind.CONFIG -> { queue.offer(au); return }
                AuKind.KEY -> if (needKey) {
                    needKey = false
                    csdProvider?.invoke()?.let { queue.offer(it) }
                }
                AuKind.DELTA -> if (needKey) return
            }
            if (queue.size >= MAX_QUEUE) {
                queue.clear()
                needKey = true
                keyframeRequester?.invoke()
                return
            }
            queue.offer(au)
        }

        fun next(): ByteArray? = queue.poll(250, TimeUnit.MILLISECONDS)
    }

    private val sinks = CopyOnWriteArrayList<Sink>()
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var keyframeRequester: (() -> Unit)? = null

    /** Fournit les SPS/PPS courants. */
    var csdProvider: (() -> ByteArray?)? = null

    val clientCount: Int get() = sinks.size

    /** Appelé quand un client a besoin d'un keyframe. */
    fun onNewClient(cb: () -> Unit) { keyframeRequester = cb }

    /** À brancher sur H264Encoder.onAccessUnit (thread encodeur). */
    fun push(au: ByteArray, kind: AuKind) {
        for (s in sinks) {
            if (s.closed) sinks.remove(s) else s.deliver(au, kind)
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        try {
            val ss = ServerSocket(port, 16, InetAddress.getByName("0.0.0.0")).apply { reuseAddress = true }
            serverSocket = ss
            Log.i(TAG, "serveur H264 prêt sur :$port")
            thread(name = "h264-accept", isDaemon = true) {
                while (running.get()) {
                    val client = try { ss.accept() } catch (e: Exception) {
                        if (!running.get() || ss.isClosed) break
                        Thread.sleep(200); continue
                    }
                    thread(name = "h264-client", isDaemon = true) { handle(client) }
                }
            }
        } catch (e: Exception) {
            running.set(false)
            Log.e(TAG, "serveur H264: bind échoué sur $port: ${e.message}")
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        sinks.forEach { it.closed = true }
        sinks.clear()
    }

    // ------------------------------------------------------------- requêtes

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 8000
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val reqLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                val idx = h.indexOf(':')
                if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
            }
            val path = reqLine.split(" ").getOrNull(1)?.substringBefore('?')?.trim() ?: "/"
            when {
                path == "/ws" && headers["upgrade"].equals("websocket", ignoreCase = true) ->
                    serveWebSocket(client, input, headers)
                path == "/h264" -> serveRawStream(client)
                path == "/player.js" -> serveAsset(client, "player.js")
                path == "/favicon.svg" || path == "/favicon.ico" -> serveAsset(client, "favicon.svg")
                else -> serveAsset(client, "h264view.html")
            }
        } catch (e: Exception) {
            Log.w(TAG, "H264 HTTP: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** Lecture d'une ligne HTTP sans tampon (le flux reste exploitable pour le WebSocket). */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    private fun serveAsset(client: Socket, name: String) {
        val body = WebAssets.get(name)
        val out = BufferedOutputStream(client.getOutputStream())
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        } else {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: ${WebAssets.mimeOf(name)}\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            out.write(body)
        }
        out.flush()
    }

    private fun register(): Sink {
        val sink = Sink()
        sinks.add(sink)
        csdProvider?.invoke()?.let { sink.queue.offer(it) }
        keyframeRequester?.invoke()
        return sink
    }

    /** Flux Annex-B continu — consommé par ffmpeg/OBS/ffplay. */
    private fun serveRawStream(client: Socket) {
        val sink = register()
        try {
            client.soTimeout = 0
            tuneForLatency(client)
            val out = BufferedOutputStream(client.getOutputStream(), 1 shl 17)
            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/h264\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            Log.i(TAG, "client /h264 connecté (${client.inetAddress.hostAddress})")
            while (running.get() && !sink.closed) {
                val au = sink.next() ?: continue
                out.write(au)
                if (sink.queue.isEmpty()) out.flush()
            }
        } catch (_: Exception) {
            // client parti
        } finally {
            sink.closed = true
            sinks.remove(sink)
        }
    }

    // ---------------------------------------------------------- websocket --

    private fun serveWebSocket(client: Socket, input: InputStream, headers: Map<String, String>) {
        val key = headers["sec-websocket-key"] ?: return
        val out = BufferedOutputStream(client.getOutputStream(), 1 shl 16)
        Ws.handshake(out, key)
        client.soTimeout = 0
        tuneForLatency(client)
        val sink = register()
        Log.i(TAG, "client WS connecté (${client.inetAddress.hostAddress})")
        thread(name = "ws-read", isDaemon = true) {
            try { Ws.drainUntilClosed { input.read() } } catch (_: Exception) {}
            sink.closed = true
        }
        try {
            while (running.get() && !sink.closed) {
                val au = sink.next() ?: continue
                Ws.writeBinary(out, au)
                if (sink.queue.isEmpty()) out.flush()
            }
        } catch (_: Exception) {
            // déconnexion
        } finally {
            sink.closed = true
            sinks.remove(sink)
        }
    }

    /**
     * Tampon d'envoi TCP réduit (~0,15 s à 12 Mbit/s) : si le réseau ralentit, le retard
     * s'accumule dans notre file (qu'on sait purger) plutôt que dans le noyau.
     */
    private fun tuneForLatency(client: Socket) {
        try { client.sendBufferSize = 192 * 1024 } catch (_: Exception) {}
    }
}
