package gr.gamemap

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Draws the themed map with route, player and HUD onto any Canvas (the phone's view or
 * the Android Auto surface). Also remembers the last camera so touch can be mapped back.
 */
class MapRenderer {

    /** The camera used for the last frame. */
    class Camera(
        val px: Double, val py: Double, val world: Double, val scale: Float,
        val bearing: Float, val cx: Float, val cy: Float,
    )

    var camera: Camera? = null
        private set

    /** True if the last frame was mid-animation and another frame should follow. */
    var animating = false
        private set

    private val tilePaint = Paint().apply { isFilterBitmap = true }
    private val pixelPaint = Paint().apply { isFilterBitmap = false; isDither = false }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val path = Path()
    private var paperTex: Bitmap? = null

    /** Where Athens is, until the first GPS fix arrives. */
    private val fallback = LatLng(37.9838, 23.7275)

    /**
     * @param area the part of the canvas not covered by other UI
     * @param d    screen density (1 = 160 dpi)
     * @param hud  draw the turn banner ourselves (the car shows its own instead)
     */
    fun draw(c: Canvas, w: Int, h: Int, area: Rect, d: Float, hud: Boolean) {
        val zoomAnim = MapState.tick()
        val moveAnim = LocationTracker.tick()
        animating = zoomAnim || moveAnim

        val theme = MapState.theme
        c.drawColor(theme.background)
        text.typeface = theme.typeface

        val me = LocationTracker.displayPosition()
        val following = MapState.following
        val center = if (following) me ?: fallback else MapState.freeCenter ?: me ?: fallback
        val bearing = if (following) LocationTracker.heading else MapState.freeBearing

        val zoom = MapState.zoom
        val z = floor(zoom).toInt().coerceIn(2, 19)
        val tilePx = TileCache.tilePx
        val scale = (2.0.pow(zoom - z) * (d / (tilePx / 256f)) * 1.15).toFloat()
        val world = tilePx * 2.0.pow(z)
        val px = Geo.x(center.lon, world)
        val py = Geo.y(center.lat, world)

        val radius = min(area.width(), area.height()) / 2f - 14 * d
        val cx = area.exactCenterX()
        val cy = if (theme.radar) area.exactCenterY()
        else area.top + area.height() * (if (NavEngine.active && following) 0.68f else 0.55f)
        camera = Camera(px, py, world, scale, bearing, cx, cy)

        c.save()
        if (theme.radar) {
            path.reset(); path.addCircle(cx, cy, radius, Path.Direction.CW); c.clipPath(path)
        } else {
            c.clipRect(area)
        }
        c.translate(cx, cy)
        c.rotate(-bearing)
        c.scale(scale, scale)

        drawTiles(c, theme, z, px, py, hypot(w.toDouble(), h.toDouble()) * 0.75 / scale)
        drawRoute(c, theme, world, px, py, scale, d)

        // accuracy halo around the player
        if (me != null) {
            val mx = (Geo.x(me.lon, world) - px).toFloat(); val my = (Geo.y(me.lat, world) - py).toFloat()
            val acc = LocationTracker.accuracy
            if (acc > 8) {
                val r = (acc / Geo.metersPerPixel(me.lat, world)).toFloat()
                fill.color = Color.argb(40, Color.red(theme.player), Color.green(theme.player), Color.blue(theme.player))
                c.drawCircle(mx, my, r, fill)
            }
        }
        c.restore()

        if (MapState.mapKey.isEmpty()) drawKeyNotice(c, area, d, theme)

        // ---- theme overlays ----
        if (theme.grid) drawGrid(c, area, d)
        if (theme.scanlines) drawScanlines(c, area, d, theme)
        if (theme.paper) drawPaper(c, area)
        if (theme.sunGlow) drawSunGlow(c, area)
        if (theme.radar) drawRadarFrame(c, cx, cy, radius, bearing, d)

        // player: centred when following, otherwise wherever it is on the map
        if (me != null) {
            if (following) {
                drawPlayer(c, cx, cy, 0f, d, theme)
            } else {
                val (sx, sy) = toScreen(me, camera!!)
                if (area.contains(sx.toInt(), sy.toInt())) drawPlayer(c, sx, sy, LocationTracker.heading - bearing, d, theme)
            }
        }

        drawSpeed(c, area, d, theme)
        if (hud) drawBanner(c, area, d, theme)
        drawAttribution(c, area, d, theme)
    }

    fun toScreen(p: LatLng, cam: Camera): Pair<Float, Float> {
        val wx = (Geo.x(p.lon, cam.world) - cam.px) * cam.scale
        val wy = (Geo.y(p.lat, cam.world) - cam.py) * cam.scale
        val a = Math.toRadians(-cam.bearing.toDouble())
        return (cam.cx + wx * cos(a) - wy * sin(a)).toFloat() to (cam.cy + wx * sin(a) + wy * cos(a)).toFloat()
    }

    /** The map point under a screen pixel. */
    fun toMap(sx: Float, sy: Float, cam: Camera): LatLng {
        val dx = (sx - cam.cx).toDouble(); val dy = (sy - cam.cy).toDouble()
        val a = Math.toRadians(cam.bearing.toDouble())
        val wx = (dx * cos(a) - dy * sin(a)) / cam.scale + cam.px
        val wy = (dx * sin(a) + dy * cos(a)) / cam.scale + cam.py
        return LatLng(Geo.lat(wy, cam.world), Geo.lon(wx, cam.world))
    }

    // ---------------------------------------------------------------- tiles

    private fun drawTiles(c: Canvas, theme: Theme, z: Int, px: Double, py: Double, reach: Double) {
        val tilePx = TileCache.tilePx
        val n = 1 shl z
        val tx0 = floor((px - reach) / tilePx).toInt(); val tx1 = floor((px + reach) / tilePx).toInt()
        val ty0 = floor((py - reach) / tilePx).toInt().coerceAtLeast(0)
        val ty1 = floor((py + reach) / tilePx).toInt().coerceAtMost(n - 1)
        val paint = if (theme.pixel > 1) pixelPaint else tilePaint

        // Ask for the coarser level first, then the visible tiles far-to-near: the loader
        // works newest-first, so the nearest tiles arrive first and the coarse ones are
        // ready as stand-ins when zooming out.
        if (z > 2) {
            for (ty in ty0 / 2..ty1 / 2) for (tx in floor(tx0 / 2.0).toInt()..floor(tx1 / 2.0).toInt())
                TileCache.get(theme, z - 1, tx.mod(n / 2), ty)
        }
        val tiles = ArrayList<IntArray>()
        for (ty in ty0..ty1) for (tx in tx0..tx1) tiles += intArrayOf(tx, ty)
        val ctx = px / tilePx - 0.5; val cty = py / tilePx - 0.5
        tiles.sortByDescending { (it[0] - ctx) * (it[0] - ctx) + (it[1] - cty) * (it[1] - cty) }

        for (t in tiles) {
            val tx = t[0]; val ty = t[1]
            val x = tx.mod(n)
            val left = (tx * tilePx - px).toFloat(); val top = (ty * tilePx - py).toFloat()
            dst.set(left, top, left + tilePx, top + tilePx)
            val bmp = TileCache.get(theme, z, x, ty)
            if (bmp != null) c.drawBitmap(bmp, null, dst, paint)
            else drawStandIn(c, theme, z, x, ty, paint)
        }
    }

    /** While a tile loads, show a cached tile from a nearby zoom level instead of a gap. */
    private fun drawStandIn(c: Canvas, theme: Theme, z: Int, x: Int, y: Int, paint: Paint) {
        // Coarser levels: zoom into part of a parent tile.
        for (up in 1..5) {
            val pz = z - up
            if (pz < 1) break
            val parent = TileCache.peek(theme, pz, x shr up, y shr up) ?: continue
            val parts = 1 shl up
            val sw = parent.width / parts; val sh = parent.height / parts
            if (sw < 1 || sh < 1) break
            val sx = (x and (parts - 1)) * sw; val sy = (y and (parts - 1)) * sh
            src.set(sx, sy, sx + sw, sy + sh)
            c.drawBitmap(parent, src, dst, paint)
            return
        }
        // Finer level: four children, if we have any (zooming out).
        if (z < 19) {
            val hw = dst.width() / 2; val hh = dst.height() / 2
            val l = dst.left; val t = dst.top
            for (dy in 0..1) for (dx in 0..1) {
                val child = TileCache.peek(theme, z + 1, x * 2 + dx, y * 2 + dy) ?: continue
                val q = RectF(l + dx * hw, t + dy * hh, l + (dx + 1) * hw, t + (dy + 1) * hh)
                c.drawBitmap(child, null, q, paint)
            }
        }
    }

    // ---------------------------------------------------------------- route

    private fun drawRoute(c: Canvas, theme: Theme, world: Double, px: Double, py: Double, scale: Float, d: Float) {
        val route = NavEngine.route
        if (route.size > 1) {
            path.reset()
            route.forEachIndexed { i, p ->
                val x = (Geo.x(p.lon, world) - px).toFloat(); val y = (Geo.y(p.lat, world) - py).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            routePaint.pathEffect = null
            routePaint.isAntiAlias = theme.pixel == 1
            routePaint.color = theme.routeGlow
            routePaint.strokeWidth = 16 * d / scale
            c.drawPath(path, routePaint)
            routePaint.color = theme.route
            routePaint.strokeWidth = 7 * d / scale
            if (theme.dashedRoute) routePaint.pathEffect = DashPathEffect(floatArrayOf(14 * d / scale, 10 * d / scale), 0f)
            c.drawPath(path, routePaint)
            routePaint.pathEffect = null
        }
        NavEngine.destination?.let { dest ->
            val x = (Geo.x(dest.lon, world) - px).toFloat(); val y = (Geo.y(dest.lat, world) - py).toFloat()
            fill.color = theme.routeGlow; c.drawCircle(x, y, 18 * d / scale, fill)
            fill.color = Color.rgb(255, 214, 0); c.drawCircle(x, y, 9 * d / scale, fill)
            stroke.color = Color.BLACK; stroke.strokeWidth = 2 * d / scale; c.drawCircle(x, y, 9 * d / scale, stroke)
        }
    }

    // ---------------------------------------------------------------- player & frames

    private fun drawPlayer(c: Canvas, x: Float, y: Float, rotation: Float, d: Float, t: Theme) {
        val s = 15 * d
        c.save()
        c.rotate(rotation, x, y)
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
        c.restore()
    }

    private fun drawRadarFrame(c: Canvas, cx: Float, cy: Float, r: Float, bearing: Float, d: Float) {
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
        val a = Math.toRadians((-bearing - 90).toDouble())
        val nx = cx + (r * cos(a)).toFloat(); val ny = cy + (r * sin(a)).toFloat()
        fill.color = Color.argb(240, 30, 30, 30); c.drawCircle(nx, ny, 13 * d, fill)
        text.color = Color.WHITE; text.textSize = 15 * d; text.textAlign = Paint.Align.CENTER
        c.drawText("N", nx, ny + 5.5f * d, text)
    }

    /** A HUD panel in the theme's shape: rounded, cut-corner, or pixel-stepped. */
    private fun panel(c: Canvas, r: RectF, d: Float, t: Theme, outline: Boolean) {
        fill.color = t.hudPanel
        stroke.color = t.route; stroke.strokeWidth = 2 * d
        when (t.hud) {
            HudShape.ROUND -> {
                c.drawRoundRect(r, 12 * d, 12 * d, fill)
                if (outline) c.drawRoundRect(r, 12 * d, 12 * d, stroke)
            }
            HudShape.CHAMFER -> {
                val k = minOf(14 * d, r.height() / 3)
                val p = Path().apply {
                    moveTo(r.left + k, r.top); lineTo(r.right, r.top)
                    lineTo(r.right, r.bottom - k); lineTo(r.right - k, r.bottom)
                    lineTo(r.left, r.bottom); lineTo(r.left, r.top + k); close()
                }
                c.drawPath(p, fill)
                if (outline) {
                    c.drawPath(p, stroke)
                    fill.color = t.route
                    c.drawRect(r.left + k + 4 * d, r.top - 2 * d, r.left + k + 44 * d, r.top + 2 * d, fill)
                }
            }
            HudShape.PIXEL -> {
                val k = 4 * d
                c.drawRect(r.left + k, r.top, r.right - k, r.bottom, fill)
                c.drawRect(r.left, r.top + k, r.right, r.bottom - k, fill)
                if (outline) {
                    stroke.strokeWidth = k
                    stroke.isAntiAlias = false
                    c.drawRect(r.left + k / 2, r.top + k / 2, r.right - k / 2, r.bottom - k / 2, stroke)
                    stroke.isAntiAlias = true
                }
            }
        }
    }

    // ---------------------------------------------------------------- overlays

    private fun drawGrid(c: Canvas, a: Rect, d: Float) {
        stroke.color = Color.argb(45, 0, 240, 255); stroke.strokeWidth = 1 * d
        val step = 40 * d
        var x = a.left.toFloat(); while (x < a.right) { c.drawLine(x, a.top.toFloat(), x, a.bottom.toFloat(), stroke); x += step }
        var y = a.top.toFloat(); while (y < a.bottom) { c.drawLine(a.left.toFloat(), y, a.right.toFloat(), y, stroke); y += step }
        fill.shader = LinearGradient(
            0f, a.top.toFloat(), 0f, a.top + a.height() * 0.35f,
            Color.argb(160, 255, 43, 214), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        c.drawRect(a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.top + a.height() * 0.35f, fill)
        fill.shader = null
    }

    private fun drawScanlines(c: Canvas, a: Rect, d: Float, t: Theme) {
        stroke.color = Color.argb(50, 0, 0, 0); stroke.strokeWidth = 1.2f * d
        var y = a.top.toFloat(); while (y < a.bottom) { c.drawLine(a.left.toFloat(), y, a.right.toFloat(), y, stroke); y += 3 * d }
        if (t != Theme.NIGHT_VISION) return
        val r = hypot(a.width().toFloat(), a.height().toFloat()) / 2
        fill.shader = RadialGradient(
            a.exactCenterX(), a.exactCenterY(), r,
            intArrayOf(Color.TRANSPARENT, Color.argb(200, 0, 10, 0)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(a, fill)
        fill.shader = null
    }

    private fun drawPaper(c: Canvas, a: Rect) {
        val tex = paperTex ?: Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b ->
            val rnd = java.util.Random(7)
            for (y in 0 until 128) for (x in 0 until 128) {
                val v = rnd.nextInt(100)
                b.setPixel(x, y, when {
                    v < 4 -> Color.argb(60, 90, 60, 30)
                    v < 12 -> Color.argb(25, 120, 90, 50)
                    v > 96 -> Color.argb(40, 255, 250, 235)
                    else -> Color.TRANSPARENT
                })
            }
            paperTex = b
        }
        fill.shader = BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawRect(a, fill)
        val r = hypot(a.width().toFloat(), a.height().toFloat()) / 2
        fill.shader = RadialGradient(
            a.exactCenterX(), a.exactCenterY(), r,
            intArrayOf(Color.TRANSPARENT, Color.argb(70, 120, 80, 30), Color.argb(190, 80, 45, 15)),
            floatArrayOf(0.5f, 0.85f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(a, fill)
        fill.shader = null
    }

    private fun drawSunGlow(c: Canvas, a: Rect) {
        fill.shader = LinearGradient(
            0f, a.top.toFloat(), 0f, a.top + a.height() * 0.5f,
            intArrayOf(Color.argb(150, 247, 160, 70), Color.argb(60, 232, 100, 80), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.top + a.height() * 0.5f, fill)
        fill.shader = null
    }

    // ---------------------------------------------------------------- HUD

    private fun drawKeyNotice(c: Canvas, area: Rect, d: Float, t: Theme) {
        val bx = RectF(area.exactCenterX() - 170 * d, area.top + 20 * d, area.exactCenterX() + 170 * d, area.top + 82 * d)
        panel(c, bx, d, t, outline = false)
        text.textAlign = Paint.Align.CENTER
        text.color = t.hudText
        text.textSize = 16 * d
        c.drawText("MAP KEY NEEDED", bx.centerX(), bx.top + 26 * d, text)
        text.textSize = 12 * d
        c.drawText("Open GameMap on the phone ▸ ⋮ ▸ Map key", bx.centerX(), bx.top + 48 * d, text)
    }

    private fun drawSpeed(c: Canvas, a: Rect, d: Float, t: Theme) {
        if (LocationTracker.last == null) return
        val kmh = (LocationTracker.speedMps * 3.6f).toInt()
        val pad = 14 * d
        text.textAlign = Paint.Align.CENTER
        text.textSize = 30 * d
        val big = kmh.toString()
        val bw = maxOf(text.measureText(big), text.measureText("88")) + 24 * d
        val box = RectF(a.left + pad, a.bottom - pad - 58 * d, a.left + pad + bw, a.bottom - pad)
        panel(c, box, d, t, outline = false)
        text.color = t.hudText
        c.drawText(big, box.centerX(), box.top + 33 * d, text)
        text.textSize = 11 * d
        c.drawText("KM/H", box.centerX(), box.bottom - 9 * d, text)
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
        val box = RectF(a.left + pad, a.top + pad, a.right - pad, a.top + pad + 78 * d)
        panel(c, box, d, t, outline = true)

        val iconX = box.left + 40 * d; val iconY = box.centerY()
        when {
            NavEngine.arrived -> drawTurnArrow(c, iconX, iconY, 20 * d, "arrive", t)
            !NavEngine.loading && NavEngine.error == null ->
                drawTurnArrow(c, iconX, iconY, 20 * d, NavEngine.currentStep?.direction ?: "straight", t)
        }
        text.textAlign = Paint.Align.LEFT
        text.color = t.hudText
        text.textSize = 26 * d
        c.drawText(title, box.left + 76 * d, box.top + 34 * d, text)
        text.textSize = 15 * d
        text.typeface = if (t.serif) Typeface.create(Typeface.SERIF, Typeface.NORMAL) else Typeface.DEFAULT_BOLD
        val maxW = box.width() - 90 * d
        var line = sub
        while (line.length > 3 && text.measureText(line) > maxW) line = line.dropLast(2) + "…"
        c.drawText(line, box.left + 76 * d, box.top + 61 * d, text)
        text.typeface = t.typeface
    }

    private fun drawTurnArrow(c: Canvas, x: Float, y: Float, s: Float, dir: String, t: Theme) {
        if (dir == "arrive") {
            fill.color = Color.rgb(255, 214, 0); c.drawCircle(x, y, s * 0.6f, fill)
            stroke.color = t.route; stroke.strokeWidth = s * 0.15f; c.drawCircle(x, y, s * 0.9f, stroke)
            return
        }
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
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 9 * d
        text.color = Color.argb(170, Color.red(t.hudText), Color.green(t.hudText), Color.blue(t.hudText))
        c.drawText("© OpenStreetMap © CARTO", a.right - 8 * d, a.bottom - 6 * d, text)
    }
}
