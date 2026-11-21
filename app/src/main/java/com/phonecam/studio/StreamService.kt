package com.phonecam.studio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Service au premier plan : possède la caméra et l'encodeur H.264 matériel.
 * Les serveurs (contrôle :8090, vidéo :8091) vivent dans [ServerBus], à l'échelle du process.
 * Toutes les opérations caméra passent par un seul thread ([control]) : pas de course
 * entre un réglage envoyé par le PC, un changement d'objectif et une reprise sur erreur.
 */
class StreamService : Service() {

    companion object {
        private const val TAG = "PhoneCam"
        private const val CHANNEL_ID = "phonecam_stream"
        private const val NOTIF_ID = 1
        const val ACTION_START = "com.phonecam.studio.START"
        const val ACTION_STOP = "com.phonecam.studio.STOP"
        const val WIDTH = 1920
        const val HEIGHT = 1080

        @Volatile var instance: StreamService? = null
            private set
        val isRunning: Boolean get() = instance != null

        /** Qualité (50..92) → débit encodeur (3..15,6 Mbps). */
        fun bitrateFor(q: Int): Int = 3_000_000 + (q.coerceIn(50, 95) - 50) * 300_000

        fun offlineJson(): JSONObject = JSONObject().put("running", false)
    }

    private val control = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "phonecam-ctl") }
    private var engine: CameraEngine? = null
    private var encoder: H264Encoder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLocks: List<WifiManager.WifiLock> = emptyList()
    private var retry: ScheduledFuture<*>? = null
    private var retries = 0

    @Volatile var status: String = "Démarrage…"
        private set
    @Volatile var cameraReady = false
        private set

    /** Réglages souhaités — persistés et ré-appliqués après chaque (re)démarrage caméra. */
    private val desired = CamSettings()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startStreaming()
        return START_NOT_STICKY
    }

    private fun startStreaming() {
        if (instance != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "service: permission caméra manquante")
            stopSelf()
            return
        }
        createChannel()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneCam::stream")
            .also { it.setReferenceCounted(false); it.acquire() }
        // Empêche l'économie d'énergie WiFi (sinon pics de 100-300 ms → freezes).
        // LOW_LATENCY n'agit qu'appli au premier plan + écran allumé ; HIGH_PERF couvre l'écran éteint.
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLocks = listOf(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WifiManager.WIFI_MODE_FULL_LOW_LATENCY).mapNotNull { mode ->
            try {
                wm.createWifiLock(mode, "PhoneCam::wifi$mode").also { it.setReferenceCounted(false); it.acquire() }
            } catch (_: Exception) { null }
        }

        Prefs.load(this, desired)
        instance = this
        ServerBus.wire()
        ServerBus.h264.start()
        ServerBus.http.start()
        control.execute { startVideo() }
        Log.i(TAG, "service: démarré")
    }

    // ---------------------------------------------------------------- vidéo
    // (toutes ces méthodes tournent sur le thread [control])

    private fun startVideo() {
        stopVideo()
        cameraReady = false
        status = "Ouverture de la caméra…"
        try {
            val enc = H264Encoder(WIDTH, HEIGHT, desired.fpsTarget, bitrateFor(desired.jpegQuality)) { au, kind ->
                ServerBus.h264.push(au, kind)
            }
            enc.start()
            encoder = enc
            val eng = CameraEngine(this)
            engine = eng
            eng.listener = object : CameraEngine.Listener {
                override fun onCameraReady() {
                    control.execute {
                        if (eng !== engine) return@execute
                        cameraReady = true
                        retries = 0
                        status = "En direct"
                        applyDesired()
                    }
                }

                override fun onCameraError(msg: String) {
                    Log.e(TAG, "service: $msg")
                    control.execute { if (eng === engine) scheduleRetry(msg) }
                }
            }
            eng.onFrame = { bytes -> ServerBus.http.pushFrame(bytes) }
            eng.update { copySettings(desired, it) }
            eng.start(enc.inputSurface!!, desired.facingBack, ServerBus.http.previewActive, WIDTH, HEIGHT)
            Log.i(TAG, "service: pipeline ${desired.fpsTarget} i/s, ${bitrateFor(desired.jpegQuality) / 1000} kbps, " +
                if (desired.facingBack) "arrière" else "avant")
        } catch (e: Exception) {
            Log.e(TAG, "service: démarrage vidéo: ${e.message}")
            scheduleRetry(e.message ?: "erreur inconnue")
        }
    }

    private fun stopVideo() {
        retry?.cancel(false)
        retry = null
        engine?.stop()
        engine = null
        encoder?.stop()
        encoder = null
        cameraReady = false
    }

    /** Reprise automatique (caméra prise par une autre appli, erreur passagère…). */
    private fun scheduleRetry(reason: String) {
        if (instance !== this) return
        stopVideo()
        retries++
        val delay = (1500L * retries).coerceAtMost(10_000L)
        status = "Caméra indisponible — nouvel essai dans ${delay / 1000} s ($reason)"
        retry = control.schedule({ startVideo() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun applyDesired() {
        engine?.update { copySettings(desired, it) }
        encoder?.setBitrate(bitrateFor(desired.jpegQuality))
    }

    private fun copySettings(from: CamSettings, to: CamSettings) {
        to.manual = from.manual; to.evSteps = from.evSteps
        to.exposureNs = from.exposureNs; to.iso = from.iso
        to.zoom = from.zoom; to.torch = from.torch
        to.jpegQuality = from.jpegQuality; to.fpsTarget = from.fpsTarget
    }

    // ------------------------------------------------------------- commandes

    /** Réglages venant du PC (/api/set) ou de l'appli — n'importe quel thread. */
    fun submit(p: Map<String, String>) {
        try { control.execute { applyParams(p) } } catch (_: Exception) {}
    }

    fun setPreviewActive(active: Boolean) {
        try { control.execute { engine?.setPreviewActive(active) } } catch (_: Exception) {}
    }

    fun requestKeyframe() { encoder?.requestKeyframe() }
    fun csd(): ByteArray? = encoder?.csd

    private fun applyParams(p: Map<String, String>) {
        fun bool(v: String) = v == "1" || v == "true" || v == "on"
        p["manual"]?.let { desired.manual = bool(it) }
        p["ev"]?.toIntOrNull()?.let { desired.evSteps = it }
        p["exposureNs"]?.toLongOrNull()?.let { desired.exposureNs = it }
        p["iso"]?.toIntOrNull()?.let { desired.iso = it }
        p["zoom"]?.toFloatOrNull()?.let { if (it.isFinite()) desired.zoom = it }
        p["torch"]?.let { desired.torch = bool(it) }
        p["q"]?.toIntOrNull()?.let { desired.jpegQuality = it.coerceIn(50, 92) }
        // « fps » : ignoré, le capteur plafonne à 30
        var restart = false
        p["cam"]?.let { v ->
            val back = v != "front"
            if (back != desired.facingBack) {
                desired.facingBack = back
                desired.torch = false     // pas de torche côté selfie
                desired.zoom = 1f         // plages de zoom différentes
                restart = true
            }
        }
        Prefs.save(this, desired)
        if (restart) {
            stopVideo()
            ServerBus.http.pushFrame(null)
            startVideo()
        } else {
            applyDesired()
        }
    }

    // ------------------------------------------------------------------ état

    /** Instantané de l'état pour /api/info et l'écran du téléphone. */
    fun stateJson(): JSONObject {
        val e = engine
        val d = desired
        return JSONObject().apply {
            put("running", true)
            put("ready", cameraReady)
            put("status", status)
            put("facing", if (d.facingBack) "back" else "front")
            put("manual", d.manual)
            put("ev", d.evSteps)
            put("zoom", d.zoom.toDouble())
            put("torchOn", d.torch)
            put("q", d.jpegQuality)
            put("fps", d.fpsTarget)
            put("w", WIDTH)
            put("h", HEIGHT)
            put("bitrateKbps", bitrateFor(d.jpegQuality) / 1000)
            put("h264Clients", ServerBus.h264.clientCount)
            put("previewActive", ServerBus.http.previewActive)
            if (e != null && cameraReady) {
                put("manualSensor", e.manualSupported)
                put("torchAvail", e.torchAvailable)
                e.evRange?.let { put("evMin", it.lower); put("evMax", it.upper) }
                put("evStep", e.evStep.toDouble())
                e.zoomRange?.let { put("zoomMin", it.lower.toDouble()); put("zoomMax", it.upper.toDouble()) }
                put("cap", e.infoText())
            }
        }
    }

    override fun onDestroy() {
        instance = null
        try {
            control.submit { stopVideo() }.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        control.shutdownNow()
        ServerBus.http.pushFrame(null)
        try { wakeLock?.release() } catch (_: Exception) {}
        wifiLocks.forEach { try { it.release() } catch (_: Exception) {} }
        Log.i(TAG, "service: arrêté")
        super.onDestroy()
    }

    // ---------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Stream en cours", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Affichée tant que la caméra est diffusée"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val ip = NetInfo.localIp()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Caméra diffusée")
            .setContentText(if (ip != null) "Contrôle : http://$ip:${ServerBus.PORT}" else "Écran éteignable, le stream continue")
            .setSmallIcon(R.drawable.ic_stat_cam)
            .setColor(0xFFE8443A.toInt())
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Arrêter", stop).build())
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }
}

/** Mémorise les derniers réglages (objectif, qualité, zoom, exposition) entre deux sessions. */
object Prefs {
    private const val FILE = "settings"

    fun load(ctx: Context, s: CamSettings) {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        s.facingBack = p.getBoolean("back", true)
        s.jpegQuality = p.getInt("q", 78)
        s.zoom = p.getFloat("zoom", 1f)
        s.manual = p.getBoolean("manual", false)
        s.evSteps = p.getInt("ev", 0)
        s.torch = false
    }

    fun save(ctx: Context, s: CamSettings) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("back", s.facingBack)
            .putInt("q", s.jpegQuality)
            .putFloat("zoom", s.zoom)
            .putBoolean("manual", s.manual)
            .putInt("ev", s.evSteps)
            .apply()
    }
}
