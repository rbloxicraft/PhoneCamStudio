package com.phonecam.studio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import kotlin.concurrent.thread

/** Nature d'une access unit livrée par l'encodeur. */
enum class AuKind { CONFIG, KEY, DELTA }

/**
 * Encodeur H.264 matériel : la caméra écrit directement sur [inputSurface],
 * les access units encodées (Annex-B) sont livrées via [onAccessUnit].
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private var bitrateBps: Int,
    private val onAccessUnit: (au: ByteArray, kind: AuKind) -> Unit
) {
    companion object {
        private const val TAG = "PhoneCam"
    }

    @Volatile private var codec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    /** Derniers SPS/PPS (à envoyer à chaque nouveau client). */
    @Volatile var csd: ByteArray? = null
        private set

    val bitrate: Int get() = bitrateBps

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // GOP de 2 s : moins de pics de débit (gros keyframes) → moins de saccades WiFi.
            // Les nouveaux clients / clients décrochés obtiennent un keyframe à la demande.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // temps réel
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // pas d'images B : chaque image sort aussitôt
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
            // Si la caméra cale (changement de réglage, scène figée) : on répète la dernière image
            // → les lecteurs (OBS, ffplay) ne coupent pas pour cause de silence.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Certains encodeurs refusent priorité/répétition : configuration minimale
            Log.w(TAG, "configuration H264 avancée refusée (${e.message}) — repli")
            format.removeKey(MediaFormat.KEY_PRIORITY)
            format.removeKey(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER)
            format.removeKey(MediaFormat.KEY_LATENCY)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = c.createInputSurface()
        c.start()
        codec = c
        Log.i(TAG, "encodeur H264 ${width}x$height@$fps ${bitrateBps / 1000} kbps")
        thread(name = "h264-drain", isDaemon = true) { drain(c) }
    }

    fun setBitrate(bps: Int) {
        if (bps == bitrateBps) return
        bitrateBps = bps
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
        } catch (e: Exception) {
            Log.w(TAG, "bitrate refusé: ${e.message}")
        }
    }

    /** Demande un keyframe immédiat (nouveau client, client qui a décroché). */
    fun requestKeyframe() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {}
    }

    fun stop() {
        val c = codec ?: return
        codec = null // la boucle drain s'arrête d'elle-même
        try { c.signalEndOfInputStream() } catch (_: Exception) {}
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (c === codec) {
            val idx = try { c.dequeueOutputBuffer(info, 20_000) } catch (_: Exception) { break }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // SPS (csd-0) + PPS (csd-1)
                try {
                    val fmt = c.outputFormat
                    val out = java.io.ByteArrayOutputStream()
                    for (key in arrayOf("csd-0", "csd-1")) {
                        fmt.getByteBuffer(key)?.let { b ->
                            b.position(0)
                            out.write(ByteArray(b.remaining()).also { b.get(it) })
                        }
                    }
                    if (out.size() > 0) {
                        csd = out.toByteArray()
                        onAccessUnit(csd!!, AuKind.CONFIG)
                    }
                } catch (_: Exception) {}
                continue
            }
            if (idx < 0) continue
            try {
                val buf = c.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    val kind = when {
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> AuKind.CONFIG.also { csd = bytes }
                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> AuKind.KEY
                        else -> AuKind.DELTA
                    }
                    onAccessUnit(bytes, kind)
                }
                c.releaseOutputBuffer(idx, false)
            } catch (_: Exception) {
                if (c !== codec) break
            }
        }
    }
}
