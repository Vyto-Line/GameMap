package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Draws the themed, heading-up map with route, player arrow and HUD onto any Canvas
 * (the phone's view or the Android Auto surface).
 */
class MapRenderer {

    private val tilePaint = Paint()
    private val pixelPaint = Paint().apply { isFilterBitmap = false; isDither = false }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val src = Rect()
    private val dst = RectF()
    private val path = Path()

    /** Where Athens is, until the first GPS fix arrives. */
    private val fallback = LatLng(37.9838, 23.7275)

    /**
     * @param area the part of the canvas not covered by other UI
     * @param d    screen density (1 = 160 dpi)
     * @param hud  draw the turn banner ourselves (the car shows its own instead)
     */
    fun draw(c: Canvas, w: Int, h: Int, area: Rect, d: Float, hud: Boolean) {
        val theme = MapState.theme
        c.drawColor(theme.background)

        val pos = LocationTracker.position ?: fallback
        val bearing = LocationTracker.heading
        val zoom = MapState.zoom
        val z = floor(zoom).toInt().coerceIn(2, 19)
        val tilePx = TileCache.tilePx
        // Bigger tiles on dense screens so labels stay readable.
        val scale = (2.0.pow(zoom - z) * (d / (tilePx / 256f)) * 1.15).toFloat()
        val world = tilePx * 2.0.pow(z)
        val px = Geo.x(pos.lon, world)
        val py = Geo.y(pos.lat, world)

        val radius = min(area.width(), area.height()) / 2f - 14 * d
        val cx = area.exactCenterX()
        val cy = if (theme.radar) area.exactCenterY()
        else area.top + area.height() * (if (NavEngine.active) 0.66f else 0.55f)

        c.save()
        if (theme.radar) {
            path.reset(); path.addCircle(cx, cy, radius, Path.Direction.CW); c.clipPath(path)
        } else {
            c.clipRect(area)
        }
        c.translate(cx, cy)
        c.rotate(-bearing)
        c.scale(scale, scale)

        // ---- tiles ----
        val reach = hypot(w.toDouble(), h.toDouble()) * 0.75 / scale
        val n = 1 shl z
        val tx0 = floor((px - reach) / tilePx).toInt(); val tx1 = floor((px + reach) / tilePx).toInt()
        val ty0 = floor((py - reach) / tilePx).toInt().coerceAtLeast(0)
        val ty1 = floor((py + reach) / tilePx).toInt().coerceAtMost(n - 1)
        val paint = if (theme.pixel > 1) pixelPaint else tilePaint.apply { isFilterBitmap = true }
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val x = tx.mod(n)
            val left = (tx * tilePx - px).toFloat(); val top = (ty * tilePx - py).toFloat()
            dst.set(left, top, left + tilePx, top + tilePx)
            val bmp = TileCache.get(theme, z, x, ty)
            if (bmp != null) {
                c.drawBitmap(bmp, null, dst, paint)
            } else {
                // Stand-in: the matching quarter of the parent tile, if we have it.
                val parent = if (z > 2) TileCache.peek(theme, z - 1, x / 2, ty / 2) else null
                if (parent != null) drawQuarter(c, parent, x % 2, ty % 2, paint)
            }
        }

        // ---- route ----
        val route = NavEngine.route
        if (route.size > 1) {
            path.reset()
            route.forEachIndexed { i, p ->
                val x = (Geo.x(p.lon, world) - px).toFloat(); val y = (Geo.y(p.lat, world) - py).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            routePaint.color = theme.routeGlow
            routePaint.strokeWidth = 16 * d / scale
            routePaint.isAntiAlias = theme.pixel == 1
            c.drawPath(path, routePaint)
            routePaint.color = theme.route
            routePaint.strokeWidth = 7 * d / scale
            c.drawPath(path, routePaint)
        }
        // destination blip
        NavEngine.destination?.let { dest ->
            val x = (Geo.x(dest.lon, world) - px).toFloat(); val y = (Geo.y(dest.lat, world) - py).toFloat()
            fill.color = theme.routeGlow; c.drawCircle(x, y, 18 * d / scale, fill)
            fill.color = Color.rgb(255, 214, 0); c.drawCircle(x, y, 9 * d / scale, fill)
            stroke.color = Color.BLACK; stroke.strokeWidth = 2 * d / scale; c.drawCircle(x, y, 9 * d / scale, stroke)
        }
        c.restore()

        if (MapState.mapKey.isEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.color = theme.hudText
            text.textSize = 16 * d
            fill.color = theme.hudPanel
            val bx = RectF(area.exactCenterX() - 170 * d, area.top + 20 * d, area.exactCenterX() + 170 * d, area.top + 82 * d)
            c.drawRoundRect(bx, 10 * d, 10 * d, fill)
            c.drawText("MAP KEY NEEDED", bx.centerX(), bx.top + 26 * d, text)
            text.textSize = 12 * d
            c.drawText("Open GameMap on the phone ▸ KEY", bx.centerX(), bx.top + 48 * d, text)
        }

        // ---- theme overlays ----
        if (theme.grid) drawGrid(c, area, d)
        if (theme.scanlines) drawScanlines(c, area, d)
        if (theme.radar) drawRadarFrame(c, cx, cy, radius, bearing, d, theme)

        drawPlayer(c, cx, cy, d, theme)
        drawSpeed(c, area, d, theme)
        if (hud) drawBanner(c, area, d, theme)
        drawAttribution(c, area, d, theme)
    }

    private fun drawQuarter(c: Canvas, parent: Bitmap, qx: Int, qy: Int, paint: Paint) {
        val hw = parent.width / 2; val hh = parent.height / 2
        src.set(qx * hw, qy * hh, qx * hw + hw, qy * hh + hh)
        c.drawBitmap(parent, src, dst, paint)
    }

    private fun drawPlayer(c: Canvas, x: Float, y: Float, d: Float, t: Theme) {
        val s = 15 * d
        path.reset()
        path.moveTo(x, y - s)
        path.lineTo(x + s * 0.75f, y + s * 0.8f)
        path.lineTo(x, y + s * 0.4f)
        path.lineTo(x - s * 0.75f, y + s * 0.8f)
        path.close()
        fill.isAntiAlias = t.pixel == 1
        fill.color = t.routeGlow; c.drawCircle(x, y, s * 1.5f, fill)
        fill.color = t.player; c.drawPath(path, fill)
        stroke.color = t.playerOutline; stroke.strokeWidth = 2.5f * d; c.drawPath(path, stroke)
        fill.isAntiAlias = true
    }

    private fun drawRadarFrame(c: Canvas, cx: Float, cy: Float, r: Float, bearing: Float, d: Float, t: Theme) {
        // soft inner shadow
        fill.shader = RadialGradient(
            cx, cy, r, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)),
            floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
        stroke.color = Color.argb(230, 20, 20, 20); stroke.strokeWidth = 9 * d
        c.drawCircle(cx, cy, r + 3 * d, stroke)
        stroke.color = Color.argb(200, 220, 220, 220); stroke.strokeWidth = 2 * d
        c.drawCircle(cx, cy, r - 1 * d, stroke)
        // north marker riding the rim
        val a = Math.toRadians((-bearing - 90).toDouble())
        val nx = cx + (r * cos(a)).toFloat(); val ny = cy + (r * sin(a)).toFloat()
        fill.color = Color.argb(240, 30, 30, 30); c.drawCircle(nx, ny, 13 * d, fill)
        text.color = Color.WHITE; text.textSize = 15 * d; text.textAlign = Paint.Align.CENTER
        c.drawText("N", nx, ny + 5.5f * d, text)
    }

    private fun drawGrid(c: Canvas, a: Rect, d: Float) {
        stroke.color = Color.argb(45, 0, 240, 255); stroke.strokeWidth = 1 * d
        val step = 40 * d
        var x = a.left.toFloat(); while (x < a.right) { c.drawLine(x, a.top.toFloat(), x, a.bottom.toFloat(), stroke); x += step }
        var y = a.top.toFloat(); while (y < a.bottom) { c.drawLine(a.left.toFloat(), y, a.right.toFloat(), y, stroke); y += step }
        // horizon glow at the top
        fill.shader = android.graphics.LinearGradient(
            0f, a.top.toFloat(), 0f, a.top + a.height() * 0.35f,
            Color.argb(160, 255, 43, 214), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        c.drawRect(a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.top + a.height() * 0.35f, fill)
        fill.shader = null
    }

    private fun drawScanlines(c: Canvas, a: Rect, d: Float) {
        stroke.color = Color.argb(50, 0, 0, 0); stroke.strokeWidth = 1.2f * d
        var y = a.top.toFloat(); while (y < a.bottom) { c.drawLine(a.left.toFloat(), y, a.right.toFloat(), y, stroke); y += 3 * d }
        val r = hypot(a.width().toFloat(), a.height().toFloat()) / 2
        fill.shader = RadialGradient(
            a.exactCenterX(), a.exactCenterY(), r,
            intArrayOf(Color.TRANSPARENT, Color.argb(200, 0, 10, 0)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(a, fill)
        fill.shader = null
    }

    private fun drawSpeed(c: Canvas, a: Rect, d: Float, t: Theme) {
        val l = LocationTracker.last ?: return
        val kmh = if (l.hasSpeed()) (l.speed * 3.6f).toInt() else 0
        val pad = 14 * d
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 34 * d
        val big = kmh.toString()
        val bw = text.measureText(big)
        text.textSize = 13 * d
        val box = RectF(a.right - pad - bw - 26 * d, a.bottom - pad - 58 * d, a.right - pad, a.bottom - pad)
        fill.color = t.hudPanel; c.drawRoundRect(box, 10 * d, 10 * d, fill)
        text.color = t.hudText
        text.textSize = 34 * d
        c.drawText(big, box.right - 13 * d, box.top + 36 * d, text)
        text.textSize = 12 * d
        c.drawText("KM/H", box.right - 13 * d, box.bottom - 8 * d, text)
    }

    private fun drawBanner(c: Canvas, a: Rect, d: Float, t: Theme) {
        if (!NavEngine.active) return
        val (title, sub) = when {
            NavEngine.arrived -> "MISSION COMPLETE" to NavEngine.destinationName
            NavEngine.error != null -> (NavEngine.error ?: "") to NavEngine.destinationName
            NavEngine.loading -> "Plotting route…" to NavEngine.destinationName
            else -> {
                val s = NavEngine.currentStep ?: return
                NavEngine.formatDistance(NavEngine.distanceToNext) to s.text
            }
        }
        val pad = 12 * d
        val box = RectF(a.left + pad, a.top + pad, a.right - pad, a.top + pad + 74 * d)
        fill.color = t.hudPanel; c.drawRoundRect(box, 12 * d, 12 * d, fill)
        stroke.color = t.route; stroke.strokeWidth = 2 * d; c.drawRoundRect(box, 12 * d, 12 * d, stroke)

        val iconX = box.left + 40 * d; val iconY = box.centerY()
        if (!NavEngine.loading && !NavEngine.arrived) drawTurnArrow(c, iconX, iconY, 20 * d, NavEngine.currentStep?.direction ?: "straight", t)
        text.textAlign = Paint.Align.LEFT
        text.color = t.hudText
        text.textSize = 24 * d
        c.drawText(title, box.left + 76 * d, box.top + 32 * d, text)
        text.textSize = 15 * d
        text.typeface = Typeface.DEFAULT_BOLD
        val maxW = box.right - box.left - 90 * d
        var line = sub
        while (line.length > 3 && text.measureText(line) > maxW) line = line.dropLast(2) + "…"
        c.drawText(line, box.left + 76 * d, box.top + 58 * d, text)
        text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        // remaining distance / time in a small strip under the banner
        if (!NavEngine.loading && !NavEngine.arrived) {
            val mins = (NavEngine.remainingSeconds / 60).toInt().coerceAtLeast(1)
            val info = "${NavEngine.formatDistance(NavEngine.remainingDistance)} · $mins min"
            text.textSize = 13 * d
            val w = text.measureText(info) + 20 * d
            val strip = RectF(box.left, box.bottom + 6 * d, box.left + w, box.bottom + 30 * d)
            fill.color = t.hudPanel; c.drawRoundRect(strip, 8 * d, 8 * d, fill)
            c.drawText(info, strip.left + 10 * d, strip.bottom - 7 * d, text)
        }
    }

    private fun drawTurnArrow(c: Canvas, x: Float, y: Float, s: Float, dir: String, t: Theme) {
        val angle = when {
            dir == "uturn" -> 180f
            dir.contains("sharp left") -> -135f
            dir.contains("slight left") -> -45f
            dir.contains("left") -> -90f
            dir.contains("sharp right") -> 135f
            dir.contains("slight right") -> 45f
            dir.contains("right") -> 90f
            else -> 0f
        }
        if (dir == "arrive") {
            fill.color = Color.rgb(255, 214, 0); c.drawCircle(x, y, s * 0.6f, fill); return
        }
        c.save()
        c.rotate(angle, x, y)
        path.reset()
        path.moveTo(x, y - s)
        path.lineTo(x + s * 0.7f, y - s * 0.2f)
        path.lineTo(x + s * 0.25f, y - s * 0.2f)
        path.lineTo(x + s * 0.25f, y + s)
        path.lineTo(x - s * 0.25f, y + s)
        path.lineTo(x - s * 0.25f, y - s * 0.2f)
        path.lineTo(x - s * 0.7f, y - s * 0.2f)
        path.close()
        fill.color = t.route; c.drawPath(path, fill)
        c.restore()
    }

    private fun drawAttribution(c: Canvas, a: Rect, d: Float, t: Theme) {
        text.textAlign = Paint.Align.LEFT
        text.textSize = 9 * d
        text.color = Color.argb(170, Color.red(t.hudText), Color.green(t.hudText), Color.blue(t.hudText))
        c.drawText("© OpenStreetMap © CARTO", a.left + 8 * d, a.bottom - 6 * d, text)
    }

    @Suppress("unused")
    private fun deg(rad: Double) = rad * 180 / PI
}
