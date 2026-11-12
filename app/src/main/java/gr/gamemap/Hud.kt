package gr.gamemap

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Panel background in the theme's own shape: rounded (most themes), cut corners (Neon Noir)
 * or pixel-stepped corners (Blocky, 8-Bit). Used for every card, sheet, chip and button.
 */
class HudDrawable(
    private val shape: HudShape,
    private val fillColor: Int,
    private val d: Float,
    private val radiusDp: Float = 16f,
    private val strokeColor: Int = 0,
    private val strokeDp: Float = 0f,
    /** Only the top corners are shaped (bottom sheets). */
    private val topOnly: Boolean = false,
    /** Fully round ends (chips, round buttons) when the theme is ROUND. */
    private val pill: Boolean = false,
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = strokeColor; strokeWidth = strokeDp * d
    }
    private val path = Path()
    private val r = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        val inset = if (strokeDp > 0) strokeDp * d / 2 else 0f
        r.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        path.reset()
        when (shape) {
            HudShape.ROUND -> {
                val rad = if (pill) r.height() / 2 else radiusDp * d
                val radii = if (topOnly) floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f) else FloatArray(8) { rad }
                path.addRoundRect(r, radii, Path.Direction.CW)
            }
            HudShape.CHAMFER -> {
                val k = minOf(12 * d, r.height() / 3)
                path.moveTo(r.left + k, r.top); path.lineTo(r.right, r.top)
                if (topOnly) { path.lineTo(r.right, r.bottom) } else { path.lineTo(r.right, r.bottom - k); path.lineTo(r.right - k, r.bottom) }
                path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.top + k); path.close()
            }
            HudShape.PIXEL -> {
                val k = 4 * d
                path.moveTo(r.left + k, r.top); path.lineTo(r.right - k, r.top); path.lineTo(r.right - k, r.top + k)
                path.lineTo(r.right, r.top + k)
                if (topOnly) { path.lineTo(r.right, r.bottom); path.lineTo(r.left, r.bottom) } else {
                    path.lineTo(r.right, r.bottom - k); path.lineTo(r.right - k, r.bottom - k); path.lineTo(r.right - k, r.bottom)
                    path.lineTo(r.left + k, r.bottom); path.lineTo(r.left + k, r.bottom - k); path.lineTo(r.left, r.bottom - k)
                }
                path.lineTo(r.left, r.top + k); path.lineTo(r.left + k, r.top + k); path.close()
            }
        }
        fill.isAntiAlias = shape != HudShape.PIXEL
        c.drawPath(path, fill)
        if (strokeDp > 0 && strokeColor != 0) c.drawPath(path, stroke)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Readable colours for app UI and the map HUD in every theme: panels are made solid, and text
 * that wouldn't stand out enough from its panel (WCAG 4.5:1) is switched to near-white or near-black.
 */
object Ui {
    private fun ch(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    fun luminance(c: Int) = 0.2126 * ch(android.graphics.Color.red(c)) + 0.7152 * ch(android.graphics.Color.green(c)) +
        0.0722 * ch(android.graphics.Color.blue(c))

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return android.graphics.Color.rgb(
            m(android.graphics.Color.red(a), android.graphics.Color.red(b)),
            m(android.graphics.Color.green(a), android.graphics.Color.green(b)),
            m(android.graphics.Color.blue(a), android.graphics.Color.blue(b)),
        )
    }

    /** The theme's panel colour, made fully solid (as if laid over a plain dark or light map). */
    fun panel(t: Theme): Int {
        val p = t.hudPanel
        val a = android.graphics.Color.alpha(p) / 255f
        val base = if (luminance(p or 0xFF000000.toInt()) > 0.4) android.graphics.Color.WHITE else android.graphics.Color.rgb(12, 12, 14)
        return mix(base, p or 0xFF000000.toInt(), a.coerceAtLeast(0.6f))
    }

    private val light = android.graphics.Color.rgb(245, 245, 245)
    private val darkInk = android.graphics.Color.rgb(20, 20, 22)

    /** Main text colour on [panel]. */
    fun ink(t: Theme): Int {
        val p = panel(t)
        return if (contrast(t.hudText, p) >= 4.5) t.hudText else if (luminance(p) > 0.4) darkInk else light
    }

    /** Secondary text: toned down toward the panel, but never below 4.5:1. */
    fun dim(t: Theme, strength: Int): Int {
        val p = panel(t); val i = ink(t)
        var k = (1f - strength / 255f).coerceIn(0f, 0.6f)
        while (k > 0f) {
            val c = mix(i, p, k)
            if (contrast(c, p) >= 4.5) return c
            k -= 0.05f
        }
        return i
    }

    /** A colour (accent, status green/red…) adjusted until it reads on the panel. */
    fun onPanel(t: Theme, c: Int, min: Double = 4.5): Int {
        val p = panel(t)
        if (contrast(c, p) >= min) return c
        val toward = if (luminance(p) > 0.4) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        var k = 0.1f
        while (k <= 1f) {
            val m = mix(c, toward, k)
            if (contrast(m, p) >= min) return m
            k += 0.1f
        }
        return ink(t)
    }
}
