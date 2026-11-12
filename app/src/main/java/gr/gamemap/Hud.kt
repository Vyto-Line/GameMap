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
