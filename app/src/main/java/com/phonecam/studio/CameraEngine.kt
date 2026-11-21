package com.phonecam.studio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.abs

/** Réglages caméra (persistés par le service, ré-appliqués après chaque redémarrage). */
data class CamSettings(
    var manual: Boolean = false,        // false = AE auto ; true = shutter/ISO manuels, ou repli EV
    var exposureNs: Long = 16_000_000L, // ~1/60 s (capteurs à mode manuel uniquement)
    var iso: Int = 200,
    var zoom: Float = 1.0f,
    var torch: Boolean = false,
    var evSteps: Int = 0,               // compensation EV, en pas du capteur
    var fpsTarget: Int = 30,            // beaucoup de capteurs plafonnent à 30
    var jpegQuality: Int = 78,          // qualité (50..92) → JPEG + débit H.264
    var facingBack: Boolean = true
)

/**
 * Moteur camera2 : une session avec deux sorties
 *  - la surface de l'encodeur H.264 (toujours alimentée)
 *  - un ImageReader JPEG pour l'aperçu web (seulement si quelqu'un regarde)
 */
@SuppressLint("MissingPermission") // permission vérifiée par le service avant start()
class CameraEngine(private val ctx: Context) {

    interface Listener {
        fun onCameraReady()
        fun onCameraError(msg: String)
    }

    var listener: Listener? = null
    /** Appelé (thread caméra) à chaque JPEG d'aperçu. */
    var onFrame: ((ByteArray) -> Unit)? = null

    // Capacités réelles du capteur (remplies au démarrage)
    var manualSupported = false; private set
    var torchAvailable = false; private set
    var exposureRange: Range<Long>? = null; private set
    var isoRange: Range<Int>? = null; private set
    var zoomRange: Range<Float>? = null; private set
    var evRange: Range<Int>? = null; private set
    var evStep: Float = 0f; private set
    var streamWidth = 0; private set
    var streamHeight = 0; private set

    private var fpsRanges: List<Range<Int>> = emptyList()
    private var nrMode: Int? = null
    private var edgeMode: Int? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var videoSurface: Surface? = null
    private var imageReader: ImageReader? = null
    @Volatile private var previewActive = false
    @Volatile private var closed = false
    private val settings = CamSettings()

    fun start(video: Surface, backCamera: Boolean, previewActive: Boolean, width: Int, height: Int) {
        this.previewActive = previewActive
        val t = HandlerThread("PhoneCamEngine").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val wanted = if (backCamera) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        val ids = cm.cameraIdList.filter {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
        }
        fun hasManual(id: String) = cm.getCameraCharacteristics(id)
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val id = ids.firstOrNull { hasManual(it) } ?: ids.firstOrNull()
        if (id == null) {
            listener?.onCameraError("Aucune caméra ${if (backCamera) "arrière" else "avant"} trouvée")
            return
        }

        val ch = cm.getCameraCharacteristics(id)
        manualSupported = hasManual(id)
        torchAvailable = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        exposureRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        zoomRange = if (Build.VERSION.SDK_INT >= 30) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val nr = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty()
        val edge = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty()
        // Modes FAST : débruitage + netteté matériels garantis sans baisse de cadence
        // (HIGH_QUALITY peut faire chuter les i/s en vidéo → saccades).
        nrMode = listOf(
            CaptureRequest.NOISE_REDUCTION_MODE_FAST,
            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
        ).firstOrNull { it in nr }
        edgeMode = listOf(CaptureRequest.EDGE_MODE_FAST).firstOrNull { it in edge }
        Log.i(TAG, "caméra id=$id manual=$manualSupported torch=$torchAvailable ev=$evRange×$evStep zoom=$zoomRange fps=$fpsRanges")

        streamWidth = width
        streamHeight = height

        // Aperçu JPEG : même cadrage 16:9 que le flux H.264, ≤ 1920 de large
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes: List<Size> = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val jpegSize = jpegSizes.firstOrNull { it.width == width && it.height == height }
            ?: jpegSizes.filter { abs(it.width * 9L - it.height * 16L) <= it.width && it.width <= 1920 }
                .maxByOrNull { it.width * it.height }
            ?: jpegSizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
        imageReader = jpegSize?.let { size ->
            ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3).apply {
                setOnImageAvailableListener({ r ->
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        onFrame?.invoke(bytes)
                    } finally {
                        img.close()
                    }
                }, h)
            }
        }
        videoSurface = video

        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (closed) { d.close(); return }
                device = d
                val targets = listOfNotNull(video, imageReader?.surface)
                try {
                    @Suppress("DEPRECATION")
                    d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (closed) return
                            session = s
                            apply()
                            listener?.onCameraReady()
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            listener?.onCameraError("Configuration de session impossible")
                        }
                    }, h)
                } catch (e: Exception) {
                    listener?.onCameraError("Session : ${e.message}")
                }
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close(); device = null
                if (!closed) listener?.onCameraError("Caméra déconnectée (utilisée par une autre appli ?)")
            }

            override fun onError(d: CameraDevice, error: Int) {
                d.close(); device = null
                if (!closed) listener?.onCameraError("Erreur caméra $error")
            }
        }, h)
    }

    /** Modifie les réglages et ré-applique la requête de capture immédiatement. */
    fun update(transform: (CamSettings) -> Unit) {
        synchronized(this) { transform(settings) }
        apply()
    }

    /** Active/coupe la capture JPEG (économie batterie quand personne ne regarde l'aperçu). */
    fun setPreviewActive(active: Boolean) {
        if (previewActive == active) return
        previewActive = active
        apply()
    }

    @Synchronized
    private fun apply() {
        val d = device ?: return
        val s = session ?: return
        val video = videoSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(video)
            if (previewActive) imageReader?.surface?.let { b.addTarget(it) }

            val ev = evRange
            when {
                settings.manual && manualSupported -> {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureNs)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
                }
                settings.manual && ev != null -> {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.evSteps.coerceIn(ev.lower, ev.upper))
                }
                else -> {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
                }
            }
            if (torchAvailable) {
                b.set(CaptureRequest.FLASH_MODE,
                    if (settings.torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }
            // Cadence fixe si possible ([30,30]) : pas de baisse d'i/s en basse lumière
            val t = settings.fpsTarget
            (fpsRanges.firstOrNull { it.lower == t && it.upper == t }
                ?: fpsRanges.filter { it.upper == t }.maxByOrNull { it.lower }
                ?: fpsRanges.filter { it.upper <= t }.maxByOrNull { it.upper })
                ?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            b.set(CaptureRequest.JPEG_QUALITY, settings.jpegQuality.coerceIn(40, 95).toByte())
            // Stabilisation électronique coupée : elle retient des images (latence) et recadre
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            nrMode?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
            edgeMode?.let { b.set(CaptureRequest.EDGE_MODE, it) }
            if (Build.VERSION.SDK_INT >= 30) {
                zoomRange?.let { b.set(CaptureRequest.CONTROL_ZOOM_RATIO, settings.zoom.coerceIn(it.lower, it.upper)) }
            }
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "apply: ${e.message}")
        }
    }

    fun stop() {
        closed = true
        synchronized(this) {
            try { session?.stopRepeating() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}
            session = null
            try { device?.close() } catch (_: Exception) {}
            device = null
        }
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        videoSurface = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** Résumé des capacités du capteur (page de contrôle). */
    fun infoText(): String = buildList {
        add("Exposition " + if (manualSupported) "manuelle (shutter/ISO)" else "auto + compensation EV")
        evRange?.let { add("EV ${fmt(it.lower * evStep)} à ${fmt(it.upper * evStep)}") }
        exposureRange?.let { add("Shutter ${fmtExp(it.lower)} à ${fmtExp(it.upper)}") }
        isoRange?.let { add("ISO ${it.lower} à ${it.upper}") }
        zoomRange?.let { add("Zoom x${fmt(it.lower)} à x${fmt(it.upper)}") }
        add("Torche " + if (torchAvailable) "disponible" else "absente")
        if (fpsRanges.isNotEmpty()) add("Cadences " + fpsRanges.joinToString(" ") { "${it.lower}-${it.upper}" })
    }.joinToString("\n")

    companion object {
        private const val TAG = "PhoneCam"
        private fun fmt(v: Float) = String.format(java.util.Locale.FRANCE, "%.1f", v)

        /** 16_000_000 ns → « 1/63 s », 1_000_000_000 → « 1,0 s » */
        fun fmtExp(ns: Long): String =
            if (ns >= 750_000_000) String.format(java.util.Locale.FRANCE, "%.1f s", ns / 1e9)
            else "1/${Math.round(1e9 / ns)} s"
    }
}
