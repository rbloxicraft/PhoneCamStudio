package com.phonecam.studio

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Palette commune à l'appli et à la page web (assets/web/control.html). */
object Pal {
    val BG = Color.parseColor("#15171A")
    val SURFACE = Color.parseColor("#1F2226")
    val SURFACE2 = Color.parseColor("#2A2E34")
    val LINE = Color.parseColor("#353A41")
    val TEXT = Color.parseColor("#ECE8E1")
    val DIM = Color.parseColor("#9A968E")
    val AMBER = Color.parseColor("#F0B43C")
    val TALLY = Color.parseColor("#E8443A")
    val OK = Color.parseColor("#5FBF7F")

    val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    val condensedBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

/** Conteneur au ratio fixe (16:9 par défaut). */
class AspectFrame(ctx: Context, private val ratio: Float = 16f / 9f) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w / ratio).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}

/** Repères de viseur (coins) dessinés par-dessus l'aperçu. */
class ViewfinderMarks(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpf(2f)
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(150, 236, 232, 225)
    }
    var live = false
        set(v) { field = v; p.color = if (v) Color.argb(220, 232, 68, 58) else Color.argb(150, 236, 232, 225); invalidate() }

    override fun onDraw(c: Canvas) {
        val m = context.dpf(12f)
        val l = context.dpf(18f)
        val w = width.toFloat(); val h = height.toFloat()
        // haut-gauche, haut-droite, bas-gauche, bas-droite
        c.drawLine(m, m, m + l, m, p); c.drawLine(m, m, m, m + l, p)
        c.drawLine(w - m, m, w - m - l, m, p); c.drawLine(w - m, m, w - m, m + l, p)
        c.drawLine(m, h - m, m + l, h - m, p); c.drawLine(m, h - m, m, h - m - l, p)
        c.drawLine(w - m, h - m, w - m - l, h - m, p); c.drawLine(w - m, h - m, w - m, h - m - l, p)
        if (!live) return // pas de croix sur le texte « Aperçu à l'arrêt »
        // croix centrale discrète
        val cx = w / 2; val cy = h / 2; val s = context.dpf(6f)
        c.drawLine(cx - s, cy, cx + s, cy, p); c.drawLine(cx, cy - s, cx, cy + s, p)
    }
}

/**
 * Bouton d'enregistrement façon caméra : rond rouge au repos, carré arrondi pendant le direct.
 */
class RecordButton(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(4f); color = Pal.TEXT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.TALLY }
    private val rect = RectF()
    private var t = 0f // 0 = rond, 1 = carré
    private var anim: ValueAnimator? = null
    private var pressScale = 1f

    var live = false
        set(v) {
            if (field == v) return
            field = v
            anim?.cancel()
            anim = ValueAnimator.ofFloat(t, if (v) 1f else 0f).apply {
                duration = 260
                interpolator = DecelerateInterpolator()
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = context.dp(84f)
        setMeasuredDimension(s, s)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressScale = 0.92f; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressScale = 1f; invalidate() }
        }
        return super.onTouchEvent(e)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val outer = width / 2f - ring.strokeWidth
        c.drawCircle(cx, cy, outer, ring)
        val circleR = outer - context.dpf(7f)
        val squareHalf = outer * 0.42f
        val half = (circleR + (squareHalf - circleR) * t) * pressScale
        val corner = half * (1f - 0.7f * t)
        rect.set(cx - half, cy - half, cx + half, cy + half)
        c.drawRoundRect(rect, corner, corner, fill)
    }
}

/** Sélecteur à deux (ou plus) options, style « segment ». */
class Segmented(ctx: Context, labels: List<String>, private val onPick: (Int) -> Unit) : LinearLayout(ctx) {
    private val items = ArrayList<TextView>()
    var selected = 0
        private set

    init {
        orientation = HORIZONTAL
        val pad = ctx.dp(3f)
        setPadding(pad, pad, pad, pad)
        background = rounded(Pal.SURFACE2, ctx.dpf(12f))
        labels.forEachIndexed { i, label ->
            val tv = TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Pal.medium
                setPadding(0, ctx.dp(9f), 0, ctx.dp(9f))
                setOnClickListener { if (isEnabled) { select(i); onPick(i) } }
            }
            addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            items.add(tv)
        }
        select(0)
    }

    fun select(i: Int) {
        selected = i
        items.forEachIndexed { idx, tv ->
            val on = idx == i
            tv.background = if (on) rounded(Pal.AMBER, context.dpf(9f)) else null
            tv.setTextColor(if (on) Color.parseColor("#2A1D05") else Pal.DIM)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        items.forEach { it.isEnabled = enabled }
        alpha = if (enabled) 1f else 0.45f
    }
}

/** Interrupteur simple (piste + pastille). */
class Toggle(ctx: Context, private val onChange: (Boolean) -> Unit) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var pos = 0f
    var checked = false
        private set

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { if (isEnabled) { set(!checked, animate = true); onChange(checked) } }
    }

    fun set(v: Boolean, animate: Boolean = false) {
        if (v == checked && (pos == if (v) 1f else 0f)) return
        checked = v
        if (!animate) { pos = if (v) 1f else 0f; invalidate(); return }
        ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
            duration = 160
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(50f), context.dp(30f))
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        track.color = blend(Pal.SURFACE2, Pal.AMBER, pos)
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, h / 2, h / 2, track)
        val pad = context.dpf(4f)
        val kr = h / 2 - pad
        knob.color = blend(Pal.DIM, Color.parseColor("#2A1D05"), pos)
        c.drawCircle(pad + kr + (w - 2 * pad - 2 * kr) * pos, h / 2, kr, knob)
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )
}
