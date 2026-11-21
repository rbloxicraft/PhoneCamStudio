package com.phonecam.studio

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Écran du téléphone : démarrer/arrêter, aperçu, objectif/torche, adresses à ouvrir sur le PC.
 * La caméra vit dans [StreamService] → l'écran peut s'éteindre pendant le stream.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tallyDot: View
    private lateinit var tallyText: TextView
    private lateinit var tallyPill: LinearLayout
    private lateinit var preview: ImageView
    private lateinit var marks: ViewfinderMarks
    private lateinit var placeholder: TextView
    private lateinit var statusLine: TextView
    private lateinit var recButton: RecordButton
    private lateinit var recLabel: TextView
    private lateinit var lensSeg: Segmented
    private lateinit var torchToggle: Toggle
    private lateinit var torchRow: View
    private lateinit var urlMain: TextView
    private lateinit var linksBox: LinearLayout
    private lateinit var statRes: TextView
    private lateinit var statFps: TextView
    private lateinit var statRate: TextView
    private lateinit var statClients: TextView

    @Volatile private var previewLoopOn = false
    private var pulse: ObjectAnimator? = null
    private var lastIp: String? = "-"
    private var starting = false
    /** Instant du dernier appui : l'état affiché suit l'intention le temps que le service réagisse. */
    private var pendingStartAt = 0L
    private var pendingStopAt = 0L
    private val pendingMs = 5000L

    // ------------------------------------------------------------ cycle de vie

    @Suppress("DEPRECATION") // couleurs des barres : ignorées (bord à bord) sur Android 15+
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(buildUi())
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ui.post(ticker)
        startPreviewLoop()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        previewLoopOn = false
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 700)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val camIdx = permissions.indexOf(Manifest.permission.CAMERA)
        if (camIdx >= 0 && grantResults.getOrNull(camIdx) != PackageManager.PERMISSION_GRANTED) {
            toast("Sans accès à la caméra, le stream ne peut pas démarrer. Autorise-le dans les paramètres de l'appli.")
        } else if (camIdx >= 0 && starting) {
            startStream()
        }
        starting = false
    }

    private fun askPermissions() {
        val wanted = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    // ---------------------------------------------------------------- actions

    private fun startStream() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            starting = true
            askPermissions()
            return
        }
        pendingStartAt = System.currentTimeMillis()
        pendingStopAt = 0L
        startForegroundService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_START))
        refresh() // bascule immédiate de l'affichage
        ui.postDelayed({ refresh() }, 150)
    }

    private fun stopStream() {
        pendingStopAt = System.currentTimeMillis()
        pendingStartAt = 0L
        startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
        refresh()
        ui.postDelayed({ refresh() }, 150)
    }

    /** Réglage depuis le téléphone : direct si le stream tourne, sinon mémorisé pour le prochain. */
    private fun setting(key: String, value: String) {
        val svc = StreamService.instance
        if (svc != null) {
            svc.submit(mapOf(key to value))
        } else {
            val s = CamSettings().also { Prefs.load(this, it) }
            if (key == "cam") s.facingBack = value != "front"
            Prefs.save(this, s)
        }
        ui.postDelayed({ refresh() }, 250)
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("PhoneCam", text))
        if (Build.VERSION.SDK_INT < 33) toast("Copié : $text") // Android 13+ affiche déjà une confirmation
    }

    // ------------------------------------------------------------------- état

    private fun refresh() {
        val svc = StreamService.instance
        val now = System.currentTimeMillis()
        // Le service démarre/s'arrête de façon asynchrone : on affiche l'intention en attendant
        if (svc != null) pendingStartAt = 0L else pendingStopAt = 0L
        val booting = svc == null && now - pendingStartAt < pendingMs
        val stopping = svc != null && now - pendingStopAt < pendingMs
        val running = (svc != null && !stopping) || booting
        val st = if (stopping) null else svc?.stateJson()
        val ready = st?.optBoolean("ready") == true

        recButton.live = running
        recLabel.text = if (running) "Arrêter le stream" else "Démarrer le stream"

        when {
            running && ready -> setTally("En direct", Pal.TALLY, pulse = true)
            running -> setTally("Démarrage", Pal.AMBER, pulse = false)
            else -> setTally("Arrêté", Pal.DIM, pulse = false)
        }
        marks.live = running && ready
        statusLine.text = when {
            !running -> "Appuie sur le bouton pour diffuser. Tu pourras ensuite éteindre l'écran."
            booting -> "Démarrage du stream…"
            ready -> "L'écran peut s'éteindre, le stream continue."
            else -> st?.optString("status").orEmpty()
        }
        placeholder.visibility = if (running && ready && preview.drawable != null) View.GONE else View.VISIBLE
        placeholder.text = when {
            !running -> "Aperçu à l'arrêt"
            !ready -> "Ouverture de la caméra…"
            else -> "En attente d'image…"
        }
        if (!running && preview.drawable != null) preview.setImageDrawable(null)

        val back = st?.optString("facing")?.let { it == "back" }
            ?: CamSettings().also { Prefs.load(this, it) }.facingBack
        lensSeg.select(if (back) 0 else 1)
        val torchAvail = st?.optBoolean("torchAvail") == true
        torchToggle.isEnabled = running && torchAvail
        torchToggle.set(st?.optBoolean("torchOn") == true)
        torchRow.alpha = if (running && torchAvail) 1f else 0.55f

        val q = st?.optInt("q") ?: CamSettings().also { Prefs.load(this, it) }.jpegQuality
        statRes.text = "1080p"
        statFps.text = "${st?.optInt("fps", 30) ?: 30}"
        statRate.text = String.format(java.util.Locale.FRANCE, "%.1f", StreamService.bitrateFor(q) / 1e6)
        statClients.text = "${st?.optInt("h264Clients") ?: 0}"

        val ip = NetInfo.localIp()
        if (ip != lastIp) { lastIp = ip; buildLinks(ip) }
    }

    private fun setTally(text: String, color: Int, pulse: Boolean) {
        tallyText.text = text
        tallyText.setTextColor(if (color == Pal.DIM) Pal.DIM else Pal.TEXT)
        tallyDot.background = rounded(color, dpf(5f))
        tallyPill.background = rounded(
            if (pulse) Color.argb(46, 232, 68, 58) else Pal.SURFACE, dpf(20f), dp(1f),
            if (pulse) Color.argb(110, 232, 68, 58) else Pal.LINE
        )
        if (pulse && this.pulse == null) {
            this.pulse = ObjectAnimator.ofFloat(tallyDot, View.ALPHA, 1f, 0.25f).apply {
                duration = 800; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
        } else if (!pulse && this.pulse != null) {
            this.pulse?.cancel(); this.pulse = null; tallyDot.alpha = 1f
        }
    }

    // --------------------------------------------------------------- aperçu

    /** Décode le dernier JPEG (½ résolution) tant que l'écran est visible. */
    private fun startPreviewLoop() {
        if (previewLoopOn) return
        previewLoopOn = true
        thread(name = "phone-preview", isDaemon = true) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2; inMutable = true }
            val pool = arrayOfNulls<Bitmap>(3)
            var slot = 0
            var lastSeq = -1L
            while (previewLoopOn) {
                if (StreamService.isRunning) {
                    val http = ServerBus.http
                    http.markPreviewActivity()
                    val seq = http.frameSeq
                    val jpg = http.latestFrame()
                    if (jpg != null && seq != lastSeq) {
                        lastSeq = seq
                        opts.inBitmap = pool[slot]
                        val bmp = try {
                            BitmapFactory.decodeByteArray(jpg, 0, jpg.size, opts)
                        } catch (_: IllegalArgumentException) {
                            opts.inBitmap = null
                            BitmapFactory.decodeByteArray(jpg, 0, jpg.size, opts)
                        }
                        if (bmp != null) {
                            pool[slot] = bmp
                            slot = (slot + 1) % pool.size
                            ui.post {
                                if (previewLoopOn && StreamService.isRunning) {
                                    preview.setImageBitmap(bmp)
                                    placeholder.visibility = View.GONE
                                }
                            }
                        }
                    }
                }
                try { Thread.sleep(45) } catch (_: InterruptedException) { break }
            }
        }
    }

    // --------------------------------------------------------------- interface

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Pal.BG)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = true
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        val side = dp(20f)
        col.setPadding(side, dp(14f), side, dp(24f))
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int; val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = b.top; bottom = b.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            // Le contenu défile SOUS une marge fixe : jamais derrière l'heure / la barre de navigation
            scroll.setPadding(0, top, 0, bottom)
            insets
        }

        // En-tête
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("PhoneCam Studio", 22f, Pal.TEXT, Pal.condensedBold))
        titles.addView(text("Webcam sans fil pour ton PC", 13f, Pal.DIM))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tallyPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(7f), dp(14f), dp(7f))
        }
        tallyDot = View(this)
        tallyPill.addView(tallyDot, LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { rightMargin = dp(8f) })
        tallyText = text("Arrêté", 13f, Pal.DIM, Pal.medium)
        tallyPill.addView(tallyText)
        header.addView(tallyPill)
        col.addView(header)

        // Aperçu (viseur)
        val frame = AspectFrame(this).apply {
            background = rounded(Color.BLACK, dpf(18f), dp(1f), Pal.LINE)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dpf(18f))
            }
        }
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        placeholder = text("Aperçu à l'arrêt", 14f, Pal.DIM).apply { gravity = Gravity.CENTER }
        frame.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        marks = ViewfinderMarks(this)
        frame.addView(marks, FrameLayout.LayoutParams(-1, -1))
        col.addView(frame, lp(top = 18f))

        statusLine = text("", 13f, Pal.DIM).apply { gravity = Gravity.CENTER }
        col.addView(statusLine, lp(top = 10f))

        // Bouton d'enregistrement
        recButton = RecordButton(this).apply {
            setOnClickListener { if (live) stopStream() else startStream() }
        }
        col.addView(recButton, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(18f)
        })
        recLabel = text("Démarrer le stream", 15f, Pal.TEXT, Pal.medium).apply { gravity = Gravity.CENTER }
        col.addView(recLabel, lp(top = 8f))

        // Caméra
        val camCard = card()
        camCard.addView(text("Objectif", 13f, Pal.DIM))
        lensSeg = Segmented(this, listOf("Arrière", "Avant (selfie)")) { i ->
            setting("cam", if (i == 0) "back" else "front")
        }
        camCard.addView(lensSeg, lp(top = 8f))
        val tr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tl.addView(text("Torche", 15f, Pal.TEXT, Pal.medium))
        tl.addView(text("Objectif arrière uniquement", 12.5f, Pal.DIM))
        tr.addView(tl, LinearLayout.LayoutParams(0, -2, 1f))
        torchToggle = Toggle(this) { on -> setting("torch", if (on) "1" else "0") }
        tr.addView(torchToggle)
        torchRow = tr
        camCard.addView(tr, lp(top = 16f))
        col.addView(camCard, lp(top = 22f))

        // Adresses
        val linkCard = card()
        linkCard.addView(text("Sur le PC, ouvre dans le navigateur", 13f, Pal.DIM))
        urlMain = text("", 24f, Pal.AMBER, Pal.condensedBold).apply {
            setPadding(0, dp(4f), 0, dp(2f))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        linkCard.addView(urlMain)
        linksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        linkCard.addView(linksBox)
        col.addView(linkCard, lp(top = 12f))

        // Mesures
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statRes = stat(stats, "Vidéo")
        statFps = stat(stats, "Images/s")
        statRate = stat(stats, "Mbit/s")
        statClients = stat(stats, "Lecteurs")
        col.addView(stats, lp(top = 12f))

        col.addView(text("Réglages fins (exposition, zoom, qualité) depuis la page du PC.", 12.5f, Pal.DIM).apply {
            gravity = Gravity.CENTER
        }, lp(top = 18f))
        return scroll
    }

    private fun buildLinks(ip: String?) {
        linksBox.removeAllViews()
        if (ip == null) {
            urlMain.text = "Pas de WiFi"
            urlMain.setTextColor(Pal.DIM)
            urlMain.setOnClickListener(null)
            linksBox.addView(text(
                "Connecte le téléphone au même WiFi que le PC, ou branche-le en USB et lance PhoneCam-USB.bat " +
                    "(page sur http://localhost:18090).", 13f, Pal.DIM
            ), lp(top = 6f))
            return
        }
        val main = "http://$ip:${ServerBus.PORT}"
        urlMain.text = main.removePrefix("http://")
        urlMain.setTextColor(Pal.AMBER)
        urlMain.setOnClickListener { copy(main) }
        linksBox.addView(linkRow("Copier l'adresse de la page", main), lp(top = 8f))
        linksBox.addView(linkRow("OBS, source Navigateur", "$main/view"), lp(top = 2f))
        linksBox.addView(linkRow("OBS, source Média (H.264)", "http://$ip:${ServerBus.VIDEO_PORT}/h264"), lp(top = 2f))
    }

    private fun linkRow(title: String, url: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = rounded(Pal.SURFACE2, dpf(12f))
            isClickable = true
            setOnClickListener { copy(url) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 14f, Pal.TEXT, Pal.medium))
        texts.addView(text(url.removePrefix("http://"), 12.5f, Pal.DIM, Pal.condensed).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text("Copier", 13f, Pal.AMBER, Pal.medium))
        return row
    }

    private fun stat(parent: LinearLayout, label: String): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(8f), dp(10f))
            background = rounded(Pal.SURFACE, dpf(14f), dp(1f), Pal.LINE)
        }
        val v = text("—", 20f, Pal.TEXT, Pal.condensedBold)
        box.addView(v)
        box.addView(text(label, 11.5f, Pal.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        parent.addView(box, LinearLayout.LayoutParams(0, -2, 1f).apply {
            if (parent.childCount > 0) leftMargin = dp(8f)
        })
        return v
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        background = rounded(Pal.SURFACE, dpf(18f), dp(1f), Pal.LINE)
    }

    private fun text(s: String, size: Float, color: Int, tf: android.graphics.Typeface? = null) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        tf?.let { typeface = it }
    }

    private fun lp(top: Float = 0f) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
