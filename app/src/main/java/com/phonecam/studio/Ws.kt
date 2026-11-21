package com.phonecam.studio

import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/** WebSocket minimal (serveur → client), partagé par les deux serveurs. */
object Ws {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun handshake(out: OutputStream, key: String) {
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII))
        )
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** Trame binaire unique (FIN). */
    fun writeBinary(out: OutputStream, data: ByteArray) {
        val len = data.size
        val head = ByteArray(10)
        head[0] = 0x82.toByte()
        val n = when {
            len < 126 -> { head[1] = len.toByte(); 2 }
            len < 65536 -> { head[1] = 126; head[2] = (len shr 8).toByte(); head[3] = len.toByte(); 4 }
            else -> {
                head[1] = 127
                for (i in 0 until 8) head[2 + i] = (len.toLong() shr (56 - 8 * i)).toByte()
                10
            }
        }
        out.write(head, 0, n)
        out.write(data)
    }

    /**
     * Consomme les trames du client jusqu'à fermeture (trame close ou socket coupé).
     * [read] renvoie un octet (0..255) ou -1 en fin de flux.
     */
    fun drainUntilClosed(read: () -> Int) {
        while (true) {
            val b0 = read(); if (b0 < 0) return
            val b1 = read(); if (b1 < 0) return
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                val a = read(); val b = read(); if (b < 0) return
                len = ((a shl 8) or b).toLong()
            } else if (len == 127L) {
                len = 0
                repeat(8) { val x = read(); if (x < 0) return; len = (len shl 8) or x.toLong() }
            }
            var skip = len + if (b1 and 0x80 != 0) 4 else 0
            while (skip-- > 0) if (read() < 0) return
            if (b0 and 0x0F == 0x8) return // close
        }
    }
}
