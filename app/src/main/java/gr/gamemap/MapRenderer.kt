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
    private val labels = Labels()
    private val tileList = ArrayList<IntArray>(64)
    private val quarter = RectF()

    /** Where Athens is, until the first GPS fix arrives. */
    private val fallback = LatLng(37.9838, 23.7275)

    /**
     * @param area the part of the canvas not covered by other UI
     * @param d    screen density (1 = 160 dpi)
     * @param hud  draw the turn banner ourselves (the car shows its own instead)
     */
    fun draw(c: Canvas, w: Int, h: Int, area: Rect, d: Float, hud: Boolean) {
        compassBounds.setEmpty()
        val zoomAnim = MapState.tick()
        val moveAnim = LocationTracker.tick()
        animating = zoomAnim || moveAnim

        val theme = MapState.theme
        MapState.fitRequest?.let { fit(it, area, d, theme) }
        c.drawColor(theme.background)
        text.typeface = theme.typeface

        val me = LocationTracker.displayPosition()
        val following = MapState.following
        val center = if (following) me ?: fallback else MapState.freeCenter ?: me ?: fallback
        val bearing = if (following) MapState.userBearing ?: LocationTracker.heading else MapState.freeBearing

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
        val reach = hypot(w.toDouble(), h.toDouble()) * 0.75 / scale
        // traffic glow under the route, traffic colours on top of it (jams on your route stay visible)
        drawTraffic(c, theme, z, px, py, scale, d, reach, under = true)
        drawRoute(c, theme, world, px, py, scale, d)
        drawTraffic(c, theme, z, px, py, scale, d, reach, under = false)

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

        // names on top of the map, laid out on screen (never overlapping, always upright)
        labels.draw(c, camera!!, z, area, d, theme)

        if (VectorSource.busy.get() > 0) {
            animating = true
            text.textAlign = Paint.Align.CENTER; text.textSize = 13 * d
            val msg = if (theme == Theme.BLOCKY) "⛏ Building the world…" else "Loading map…"
            val tw = text.measureText(msg) / 2 + 12 * d
            val bx = RectF(area.exactCenterX() - tw, area.bottom - 92 * d, area.exactCenterX() + tw, area.bottom - 64 * d)
            panel(c, bx, d, theme, outline = false)
            text.color = theme.hudText
            c.drawText(msg, bx.centerX(), bx.bottom - 9 * d, text)
        }

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
        bannerBottom = area.top.toFloat()
        if (hud && !NavEngine.planning && NavEngine.offer == null) drawBanner(c, area, d, theme)
        drawJamChip(c, area, d, theme, hud)
        if (hud && (MapState.userBearing != null || !following)) drawCompass(c, area, bearing, d, theme)
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

        // Ask for the visible tiles far-to-near: the loader works newest-first, so the
        // tiles nearest the player are drawn first.
        val tiles = tileList
        tiles.clear()
        for (ty in ty0..ty1) for (tx in tx0..tx1) tiles += intArrayOf(tx, ty)
        val ctx = px / tilePx - 0.5; val cty = py / tilePx - 0.5
        tiles.sortByDescending { (it[0] - ctx) * (it[0] - ctx) + (it[1] - cty) * (it[1] - cty) }

        // quietly download the map data just outside the screen
        if (theme != Theme.BLOCKY && VectorSource.busy.get() == 0) {
            val dz = VectorSource.dataZoom(z); val sh = z - dz
            val nd = 1 shl dz
            for (ty in ((ty0 shr sh) - 1)..((ty1 shr sh) + 1)) for (tx in ((tx0 shr sh) - 1)..((tx1 shr sh) + 1)) {
                if (ty in 0 until nd) VectorSource.prefetch(dz, tx.mod(nd), ty)
            }
        }
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
                quarter.set(l + dx * hw, t + dy * hh, l + (dx + 1) * hw, t + (dy + 1) * hh)
                c.drawBitmap(child, null, quarter, paint)
            }
        }
    }

    // ---------------------------------------------------------------- traffic

    /** A traffic stretch that gets direction arrows: points in world pixels around the square's corner. */
    private class ArrowLine(val pts: FloatArray, val bothWays: Boolean, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    /** Traffic lines for one data square at one zoom, in world pixels around the square's corner. */
    private class TrafficPaths(val byState: Array<Path>, val arrows: List<ArrowLine>, val ox: Double, val oy: Double)
    private val arrowShape = Path()
    private val trafficCache = LinkedHashMap<String, TrafficPaths>()
    private val trafficPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun drawTraffic(
        c: Canvas, theme: Theme, z: Int, px: Double, py: Double, scale: Float, d: Float, reach: Double, under: Boolean,
    ) {
        if (!Traffic.active || z < 11) return
        val look = theme.traffic
        val st = theme.style
        // Coarser traffic data zoomed out (few squares); finer geometry close up.
        val dz = when {
            z >= 17 -> 16
            z == 16 -> 15
            else -> (z - 1).coerceIn(Traffic.MIN_Z, Traffic.DATA_Z)
        }
        val span = TileCache.tilePx * 2.0.pow(z - dz) // one traffic square in world pixels
        val ax0 = floor((px - reach) / span).toInt(); val ax1 = floor((px + reach) / span).toInt()
        val ay0 = floor((py - reach) / span).toInt(); val ay1 = floor((py + reach) / span).toInt()
        if ((ax1 - ax0 + 1) * (ay1 - ay0 + 1) > 36) return
        trafficPaint.isAntiAlias = look.smooth
        arrowPaint.isAntiAlias = look.smooth
        val u = d / scale // one dp in world pixels
        var arrowsDrawn = false

        for (ay in ay0..ay1) for (ax in ax0..ax1) {
            val t = Traffic.tile(dz, ax, ay) ?: continue
            // rebuilt when new map data arrives too, so lanes can lock onto the roads
            val key = "$dz/$ax/$ay/$z/${t.fetchedAt}/${VectorSource.loadedCount}"
            val tp = trafficCache.getOrPut(key) { buildTraffic(t, ax, ay, span, u, z) }
            c.save()
            c.translate((tp.ox - px).toFloat(), (tp.oy - py).toFloat())

            // free-flowing roads: a faint line
            if (under && look.freeAlpha > 0 && look.freeWidth > 0) {
                trafficPaint.pathEffect = null
                trafficPaint.color = st.trafficFree; trafficPaint.alpha = look.freeAlpha
                trafficPaint.strokeWidth = look.freeWidth * u
                c.drawPath(tp.byState[Traffic.FREE], trafficPaint)
            }
            // slow, jammed and closed: glow, outline, then the line itself
            for (state in intArrayOf(Traffic.SLOW, Traffic.JAM, Traffic.CLOSED)) {
                val p = tp.byState[state]
                if (p.isEmpty) continue
                val col = if (state == Traffic.SLOW) st.trafficSlow else st.trafficJam
                trafficPaint.pathEffect = null
                if (under) {
                    for ((wdp, alpha) in look.glow) {
                        trafficPaint.color = col; trafficPaint.alpha = alpha
                        trafficPaint.strokeWidth = wdp * u
                        c.drawPath(p, trafficPaint)
                    }
                    continue
                }
                look.casing?.let { cas ->
                    trafficPaint.color = cas; trafficPaint.alpha = 255
                    trafficPaint.strokeWidth = (look.core + 2f) * u
                    c.drawPath(p, trafficPaint)
                }
                trafficPaint.color = col; trafficPaint.alpha = 255
                trafficPaint.strokeWidth = look.core * u
                if (state == Traffic.CLOSED) trafficPaint.pathEffect = DashPathEffect(floatArrayOf(6 * u, 4 * u), 0f)
                c.drawPath(p, trafficPaint)
            }
            // arrows along slow and jammed stretches, pointing the way the traffic is going
            if (!under && z >= 13 && tp.arrows.isNotEmpty()) {
                if (drawArrows(c, tp, look, u, px - tp.ox, py - tp.oy, reach)) arrowsDrawn = true
            }
            trafficPaint.pathEffect = null
            c.restore()
        }
        if (arrowsDrawn) animating = true
        while (trafficCache.size > 48) trafficCache.remove(trafficCache.keys.first())
    }

    private fun buildTraffic(t: Traffic.Tile, ax: Int, ay: Int, span: Double, u: Float, z: Int): TrafficPaths {
        val paths = Array(4) { Path() }
        val arrows = ArrayList<ArrowLine>()
        val k = (span / t.extent).toFloat()
        // Each driving direction gets its own lane on its side of the road, ~5 m from the
        // middle (about where the carriageways of a divided road are), never closer than 2.6 dp.
        val world = TileCache.tilePx * 2.0.pow(z)
        val lat = Geo.lat((ay + 0.5) * span, world) // centre of this square
        val mpp = Geo.metersPerPixel(lat, world).toFloat()
        val lane = maxOf(2.6f * u, 5f / mpp)

        fun add(pts: FloatArray, state: Int) {
            val n = pts.size / 2
            val p = paths[state]
            p.moveTo(pts[0], pts[1])
            for (i in 1 until n) p.lineTo(pts[i * 2], pts[i * 2 + 1])
            if (state == Traffic.JAM || state == Traffic.CLOSED) { // arrows on red traffic only
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (i in 0 until n) {
                    minX = minOf(minX, pts[i * 2]); maxX = maxOf(maxX, pts[i * 2])
                    minY = minOf(minY, pts[i * 2 + 1]); maxY = maxOf(maxY, pts[i * 2 + 1])
                }
                arrows += ArrowLine(pts, false, minX, minY, maxX, maxY)
            }
        }

        // Lock lanes onto the map's roads (within ~25 m, same direction only).
        val maxSnap = maxOf(25f / mpp, 12f * u)
        val roads = RoadSnap.index(z, ax * span, ay * span, span, maxSnap)
        fun lane(raw: FloatArray, side: Float): FloatArray? {
            val fallback = raw.copyOf().also { offsetLine(it, side * lane) }
            return if (roads == null) fallback else RoadSnap.snapLane(roads, raw, fallback, maxSnap, side, 1.8f * u)
        }
        for (sg in t.segs) {
            val base = FloatArray(sg.pts.size)
            for (i in base.indices) base[i] = sg.pts[i] * k
            val side = if (sg.leftHand) -1f else 1f
            if (sg.oneWay) {
                // data for one direction: points run the way the traffic goes
                lane(base, side)?.let { add(it, sg.state) }
            } else {
                // data for both directions: one lane each way, each pointing its own way
                // (a lane against a one-way street is dropped)
                lane(base, side)?.let { add(it, sg.state) }
                lane(reversedLine(base), side)?.let { add(it, sg.state) }
            }
        }
        return TrafficPaths(paths, arrows, ax * span, ay * span)
    }

    private fun reversedLine(p: FloatArray): FloatArray {
        val out = FloatArray(p.size)
        var i = 0
        while (i < p.size) { out[p.size - 2 - i] = p[i]; out[p.size - 1 - i] = p[i + 1]; i += 2 }
        return out
    }

    /** Shifts a line sideways by [d] (positive = right of its direction, screen coordinates). */
    private fun offsetLine(p: FloatArray, d: Float) {
        val n = p.size / 2
        if (n < 2) return
        val src = p.copyOf()
        for (i in 0 until n) {
            // average of the normals of the segments before and after this point
            var nx = 0f; var ny = 0f
            if (i > 0) { val dx = src[i * 2] - src[i * 2 - 2]; val dy = src[i * 2 + 1] - src[i * 2 - 1]; val l = hypot(dx, dy); if (l > 0) { nx += -dy / l; ny += dx / l } }
            if (i < n - 1) { val dx = src[i * 2 + 2] - src[i * 2]; val dy = src[i * 2 + 3] - src[i * 2 + 1]; val l = hypot(dx, dy); if (l > 0) { nx += -dy / l; ny += dx / l } }
            val l = hypot(nx, ny)
            if (l > 0) { p[i * 2] = src[i * 2] + nx / l * d; p[i * 2 + 1] = src[i * 2 + 1] + ny / l * d }
        }
    }

    /**
     * Arrows every ~40 dp along jammed (red) stretches, always moving: forward along
     * one-direction jams, and in both directions (alternating) on two-way jams.
     */
    private fun drawArrows(c: Canvas, tp: TrafficPaths, look: TrafficLook, u: Float, vx: Double, vy: Double, reach: Double): Boolean {
        val spacing = (if (look.smooth) 40f else 44f) * u
        val size = (look.core + 2.5f) * u
        arrowShape.reset()
        arrowShape.moveTo(-size * 0.6f, -size); arrowShape.lineTo(size * 0.7f, 0f); arrowShape.lineTo(-size * 0.6f, size)
        arrowShape.lineTo(-size * 0.15f, 0f); arrowShape.close()
        arrowPaint.color = look.arrow
        arrowPaint.pathEffect = null
        // arrows flow along the jam at ~22 dp per second
        val crawl = ((android.os.SystemClock.uptimeMillis() / 1000.0 * 22 * u) % spacing).toFloat()
        val l = (vx - reach).toFloat(); val r = (vx + reach).toFloat(); val t = (vy - reach).toFloat(); val b = (vy + reach).toFloat()
        var drawn = 0
        for (al in tp.arrows) {
            if (al.maxX < l || al.minX > r || al.maxY < t || al.minY > b) continue
            val pts = al.pts
            var total = 0f
            var i = 2
            while (i < pts.size) { total += hypot(pts[i] - pts[i - 2], pts[i + 1] - pts[i - 1]); i += 2 }
            if (total < size * 2.5f) continue
            if (al.bothWays) {
                // two-way jam: arrows alternate, each set flowing its own way
                drawn += arrowRun(c, pts, total, crawl, spacing, false)
                // backward set: counted from the far end, so it moves the way it points
                drawn += arrowRun(c, pts, total, (crawl + spacing / 2) % spacing, spacing, true)
            } else {
                drawn += arrowRun(c, pts, total, crawl, spacing, false)
            }
            if (drawn > 500) return true
        }
        return drawn > 0
    }

    /** Arrows along [pts] starting at [start], every [spacing]; [backwards] points them the other way. */
    private fun arrowRun(c: Canvas, pts: FloatArray, total: Float, start: Float, spacing: Float, backwards: Boolean): Int {
        var at = if (backwards) total - start else start
        var n = 0
        // walk the line once, placing arrows as we pass their distance
        val positions = ArrayList<Float>()
        while (at >= 0 && at <= total) { positions += at; at += if (backwards) -spacing else spacing }
        if (positions.isEmpty()) return 0
        positions.sort()
        var walked = 0f
        var pi = 0
        var i = 2
        while (i < pts.size && pi < positions.size) {
            val x0 = pts[i - 2]; val y0 = pts[i - 1]; val x1 = pts[i]; val y1 = pts[i + 1]
            val len = hypot(x1 - x0, y1 - y0)
            while (pi < positions.size && positions[pi] <= walked + len && len > 0) {
                val f = (positions[pi] - walked) / len
                var ang = Math.toDegrees(kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
                if (backwards) ang += 180f
                c.save()
                c.translate(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)
                c.rotate(ang)
                c.drawPath(arrowShape, arrowPaint)
                c.restore()
                n++; pi++
            }
            walked += len
            i += 2
        }
        return n
    }

    // ---------------------------------------------------------------- route

    private class CachedPath(val path: Path, val ox: Double, val oy: Double)
    private val pathCache = HashMap<List<LatLng>, CachedPath>()
    private var pathCacheZ = -1

    private fun pathFor(pts: List<LatLng>, world: Double, z: Int): CachedPath {
        if (z != pathCacheZ || pathCache.size > 12) { pathCache.clear(); pathCacheZ = z }
        return pathCache.getOrPut(pts) {
            val ox = Geo.x(pts[0].lon, world); val oy = Geo.y(pts[0].lat, world)
            val p = Path()
            pts.forEachIndexed { i, q ->
                val x = (Geo.x(q.lon, world) - ox).toFloat(); val y = (Geo.y(q.lat, world) - oy).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            CachedPath(p, ox, oy)
        }
    }

    private fun strokeRoute(
        c: Canvas, pts: List<LatLng>, world: Double, z: Int, px: Double, py: Double, scale: Float, d: Float,
        color: Int, glow: Int, width: Float, dashed: Boolean, pixel: Boolean,
    ) {
        if (pts.size < 2) return
        val cp = pathFor(pts, world, z)
        c.save()
        c.translate((cp.ox - px).toFloat(), (cp.oy - py).toFloat())
        routePaint.pathEffect = null
        routePaint.isAntiAlias = !pixel
        if (Color.alpha(glow) > 0) {
            routePaint.color = glow
            routePaint.strokeWidth = (width + 9) * d / scale
            c.drawPath(cp.path, routePaint)
        }
        routePaint.color = color
        routePaint.strokeWidth = width * d / scale
        if (dashed) routePaint.pathEffect = DashPathEffect(floatArrayOf(14 * d / scale, 10 * d / scale), 0f)
        c.drawPath(cp.path, routePaint)
        routePaint.pathEffect = null
        c.restore()
    }

    private fun withAlpha(col: Int, a: Int) = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))

    private fun drawRoute(c: Canvas, theme: Theme, world: Double, px: Double, py: Double, scale: Float, d: Float) {
        val z = kotlin.math.round(kotlin.math.log2(world / TileCache.tilePx)).toInt()
        val pixel = theme.pixel != 1
        if (NavEngine.planning) {
            val opts = NavEngine.options
            opts.forEachIndexed { i, o ->
                if (i != NavEngine.selected) strokeRoute(c, o.points, world, z, px, py, scale, d,
                    withAlpha(theme.route, 120), 0, 6f, false, pixel)
            }
            opts.getOrNull(NavEngine.selected)?.let {
                strokeRoute(c, it.points, world, z, px, py, scale, d, theme.route, theme.routeGlow, 7f, theme.dashedRoute, pixel)
            }
            NavEngine.planPlace?.let { drawDestination(c, it.pos, world, px, py, scale, d, theme) }
            return
        }
        val route = NavEngine.route
        val offer = NavEngine.offer
        if (route.size > 1) {
            if (offer != null) {
                strokeRoute(c, route, world, z, px, py, scale, d, withAlpha(theme.route, 110), 0, 6f, false, pixel)
                strokeRoute(c, offer.option.points, world, z, px, py, scale, d,
                    Color.rgb(61, 220, 132), Color.argb(90, 61, 220, 132), 8f, false, pixel)
            } else {
                strokeRoute(c, route, world, z, px, py, scale, d, theme.route, theme.routeGlow, 7f, theme.dashedRoute, pixel)
            }
        }
        NavEngine.destination?.let { drawDestination(c, it, world, px, py, scale, d, theme) }
    }

    private fun drawDestination(c: Canvas, dest: LatLng, world: Double, px: Double, py: Double, scale: Float, d: Float, theme: Theme) {
        val x = (Geo.x(dest.lon, world) - px).toFloat(); val y = (Geo.y(dest.lat, world) - py).toFloat()
        fill.color = theme.routeGlow; c.drawCircle(x, y, 18 * d / scale, fill)
        fill.color = Color.rgb(255, 214, 0); c.drawCircle(x, y, 9 * d / scale, fill)
        stroke.color = Color.BLACK; stroke.strokeWidth = 2 * d / scale; c.drawCircle(x, y, 9 * d / scale, stroke)
    }

    /** Works out the centre and zoom that fit [pts] into [area], and hands them to MapState. */
    private fun fit(pts: List<LatLng>, area: Rect, d: Float, theme: Theme) {
        if (area.width() <= 0 || area.height() <= 0) return
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in pts) {
            val x = Geo.x(p.lon, 1.0); val y = Geo.y(p.lat, 1.0)
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val pad = 48 * d
        val k = 256.0 * d * 1.15
        val dx = maxOf(maxX - minX, 1e-7); val dy = maxOf(maxY - minY, 1e-7)
        val zx = kotlin.math.log2((area.width() - 2 * pad) / (dx * k))
        val zy = kotlin.math.log2((area.height() - 2 * pad - 40 * d) / (dy * k))
        val zoom = minOf(zx, zy).coerceIn(MapState.MIN_ZOOM, 17.5)
        // the camera puts its centre at 55% of the height; shift so the routes sit in the middle
        val camY = if (theme.radar) 0.5 else 0.55
        val shift = (camY - 0.5) * area.height() / (k * 2.0.pow(zoom))
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2 - shift
        MapState.applyFit(LatLng(Geo.lat(cy, 1.0), Geo.lon(cx, 1.0)), zoom)
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
        return
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

    private fun drawSpeed(c: Canvas, a: Rect, d: Float, t: Theme) {
        if (LocationTracker.last == null) return
        val kmh = (LocationTracker.speedMps * 3.6f).toInt()
        val limit = if (NavEngine.active) NavEngine.speedLimit else null
        val over = limit != null && kmh > limit + 4
        val pad = 14 * d
        text.textAlign = Paint.Align.CENTER
        text.textSize = 30 * d
        val big = kmh.toString()
        val bw = maxOf(text.measureText(big), text.measureText("88")) + 24 * d
        val box = RectF(a.left + pad, a.bottom - pad - 58 * d, a.left + pad + bw, a.bottom - pad)
        panel(c, box, d, t, outline = over)
        text.color = if (over) Color.rgb(255, 92, 92) else t.hudText
        c.drawText(big, box.centerX(), box.top + 33 * d, text)
        text.textSize = 11 * d
        c.drawText("KM/H", box.centerX(), box.bottom - 9 * d, text)
        if (limit != null) {
            val r = 21 * d
            val sx = box.right + 8 * d + r; val sy = box.bottom - r - 4 * d
            fill.color = Color.WHITE; c.drawCircle(sx, sy, r, fill)
            stroke.color = Color.rgb(208, 2, 27); stroke.strokeWidth = 4.5f * d; c.drawCircle(sx, sy, r - 2.2f * d, stroke)
            text.color = Color.rgb(17, 20, 24); text.textSize = (if (limit >= 100) 14 else 16) * d
            val tf = text.typeface; text.typeface = Typeface.DEFAULT_BOLD
            c.drawText(limit.toString(), sx, sy + 5.5f * d, text)
            text.typeface = tf
        }
    }

    /** Bottom of the turn banner on screen (for placing things below it). */
    private var bannerBottom = 0f

    private fun drawBanner(c: Canvas, a: Rect, d: Float, t: Theme) {
        if (!NavEngine.active) return
        val step = NavEngine.currentStep
        val (title, sub) = when {
            NavEngine.arrived -> "MISSION COMPLETE" to NavEngine.destinationName
            NavEngine.error != null -> (NavEngine.error ?: "") to NavEngine.destinationName
            NavEngine.loading || step == null -> "Plotting route…" to NavEngine.destinationName
            else -> {
                val st = step!!
                NavEngine.formatDistance(NavEngine.distanceToNext) to
                    (if (st.road.isNotBlank() && st.type != "arrive") st.road else st.text)
            }
        }
        val detail = if (!NavEngine.arrived && !NavEngine.loading && NavEngine.error == null && step != null &&
            step.road.isNotBlank() && step.type != "arrive") step.text.substringBefore(" onto ") else null
        val lanes = step?.lanes?.takeIf { NavEngine.distanceToNext < 900 && !NavEngine.loading }
        val pad = 12 * d
        val mainH = if (detail != null) 96 * d else 80 * d
        val lanesH = if (lanes != null) 44 * d else 0f
        val box = RectF(a.left + pad, a.top + pad, a.right - pad, a.top + pad + mainH + lanesH)
        panel(c, box, d, t, outline = true)

        val iconX = box.left + 42 * d; val iconY = box.top + mainH / 2
        when {
            NavEngine.arrived -> drawTurnArrow(c, iconX, iconY, 22 * d, "arrive", t)
            !NavEngine.loading && NavEngine.error == null && step != null -> drawTurnArrow(c, iconX, iconY, 22 * d, step.direction, t)
        }
        val tx = box.left + 82 * d
        val maxW = box.right - tx - 12 * d
        text.textAlign = Paint.Align.LEFT
        text.color = t.hudText
        text.textSize = 30 * d
        c.drawText(title, tx, box.top + 36 * d, text)
        text.textSize = 18 * d
        text.typeface = if (t.serif) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.DEFAULT_BOLD
        c.drawText(ellipsize(sub, maxW), tx, box.top + 62 * d, text)
        if (detail != null) {
            text.textSize = 14 * d
            text.typeface = if (t.serif) Typeface.create(Typeface.SERIF, Typeface.NORMAL) else Typeface.DEFAULT
            text.color = withAlpha(t.hudText, 190)
            c.drawText(ellipsize(detail, maxW), tx, box.top + 84 * d, text)
        }
        text.typeface = t.typeface
        if (lanes != null) drawLanes(c, RectF(box.left, box.top + mainH, box.right, box.bottom), lanes, step?.direction ?: "straight", d, t)
        bannerBottom = box.bottom

        // "Then …" tab under the banner
        val then = NavEngine.followingStep
        if (then != null && !NavEngine.arrived) {
            text.textSize = 15 * d
            text.typeface = Typeface.DEFAULT_BOLD
            val label = ellipsize(if (then.road.isNotBlank() && then.type != "arrive") then.road else then.text, box.width() - 110 * d)
            val w = text.measureText(label) + 82 * d
            val tab = RectF(box.left + 14 * d, box.bottom - 2 * d, box.left + 14 * d + w, box.bottom + 38 * d)
            panel(c, tab, d, t, outline = false)
            text.color = withAlpha(t.hudText, 190)
            c.drawText("Then", tab.left + 12 * d, tab.centerY() + 5.5f * d, text)
            drawTurnArrow(c, tab.left + 62 * d, tab.centerY(), 9 * d, then.direction, t)
            text.color = t.hudText
            c.drawText(label, tab.left + 76 * d, tab.centerY() + 5.5f * d, text)
            text.typeface = t.typeface
            bannerBottom = tab.bottom
        }
    }

    private fun ellipsize(s: String, maxW: Float): String {
        var line = s
        while (line.length > 3 && text.measureText(line) > maxW) line = line.dropLast(2) + "…"
        return line
    }

    /** One small arrow per lane: bright for lanes to use, faint for the others. */
    private fun drawLanes(c: Canvas, r: RectF, lanes: List<Lane>, maneuver: String, d: Float, t: Theme) {
        fill.color = withAlpha(t.hudText, 22)
        c.drawRect(r.left + 8 * d, r.top, r.right - 8 * d, r.bottom - 6 * d, fill)
        val cell = minOf(40 * d, (r.width() - 24 * d) / lanes.size)
        var x = r.centerX() - cell * lanes.size / 2 + cell / 2
        val y = r.centerY() - 3 * d
        for (lane in lanes) {
            val dir = lane.directions.firstOrNull { lane.valid && it == maneuver }
                ?: lane.directions.firstOrNull { lane.valid && it != "none" }
                ?: lane.directions.firstOrNull() ?: "straight"
            drawLaneArrow(c, x, y, 13 * d, if (dir == "none") "straight" else dir, if (lane.valid) t.hudText else withAlpha(t.hudText, 70), d)
            x += cell
        }
    }

    private fun drawLaneArrow(c: Canvas, x: Float, y: Float, s: Float, dir: String, color: Int, d: Float) {
        stroke.color = color; stroke.strokeWidth = 3 * d; stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
        val ang = when {
            dir == "uturn" -> 180f
            dir.contains("sharp left") -> -135f
            dir.contains("slight left") -> -40f
            dir.contains("left") -> -90f
            dir.contains("sharp right") -> 135f
            dir.contains("slight right") -> 40f
            dir.contains("right") -> 90f
            else -> 0f
        }
        path.reset()
        path.moveTo(x, y + s)
        if (ang == 0f) {
            path.lineTo(x, y - s)
            c.drawPath(path, stroke)
            path.reset(); path.moveTo(x - s * 0.45f, y - s * 0.5f); path.lineTo(x, y - s); path.lineTo(x + s * 0.45f, y - s * 0.5f)
        } else {
            path.lineTo(x, y)
            val a = Math.toRadians((ang - 90).toDouble())
            val ex = x + (s * cos(a)).toFloat(); val ey = y + (s * sin(a)).toFloat()
            path.lineTo(ex, ey)
            c.drawPath(path, stroke)
            val back = a + Math.PI
            path.reset()
            path.moveTo(ex + (s * 0.5f * cos(back + 0.6)).toFloat(), ey + (s * 0.5f * sin(back + 0.6)).toFloat())
            path.lineTo(ex, ey)
            path.lineTo(ex + (s * 0.5f * cos(back - 0.6)).toFloat(), ey + (s * 0.5f * sin(back - 0.6)).toFloat())
        }
        c.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeJoin = Paint.Join.MITER
    }

    /** "Jam in 800 m · +2 min", under the banner (phone) or at the top of the map (car). */
    private fun drawJamChip(c: Canvas, a: Rect, d: Float, t: Theme, hud: Boolean) {
        if (!NavEngine.active || NavEngine.arrived || NavEngine.offer != null) return
        val (dist, delay) = NavEngine.jamAhead ?: return
        val mins = (delay + 30) / 60
        val label = (if (dist < 100) "Jam ahead" else "Jam in ${NavEngine.formatDistance(dist)}") + (if (mins >= 1) " · +$mins min" else "")
        text.textSize = 15 * d
        text.typeface = Typeface.DEFAULT_BOLD
        text.textAlign = Paint.Align.LEFT
        val w = text.measureText(label) + 44 * d
        val h = 38 * d
        val top = if (hud) bannerBottom + 10 * d else a.top + 12 * d
        val left = if (hud) a.right - 12 * d - w else a.left + 12 * d
        val r = RectF(left, top, left + w, top + h)
        fill.color = Color.rgb(251, 227, 227)
        c.drawRoundRect(r, h / 2, h / 2, fill)
        // warning triangle
        path.reset()
        val tx = r.left + 20 * d; val ty = r.centerY()
        path.moveTo(tx, ty - 8 * d); path.lineTo(tx + 9 * d, ty + 7 * d); path.lineTo(tx - 9 * d, ty + 7 * d); path.close()
        fill.color = Color.rgb(196, 30, 36); c.drawPath(path, fill)
        text.color = Color.rgb(122, 16, 16)
        c.drawText(label, r.left + 34 * d, r.centerY() + 5.5f * d, text)
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

    /** Where the compass button sits (the phone taps it to reset rotation). */
    val compassBounds = RectF()

    private fun drawCompass(c: Canvas, a: Rect, bearing: Float, d: Float, t: Theme) {
        val r = 22 * d
        val x = a.left + 14 * d + r
        val y = maxOf(a.top + 14 * d, bannerBottom + 12 * d) + r
        compassBounds.set(x - r, y - r, x + r, y + r)
        fill.color = t.hudPanel; c.drawCircle(x, y, r, fill)
        stroke.color = t.route; stroke.strokeWidth = 2 * d; c.drawCircle(x, y, r, stroke)
        c.save()
        c.rotate(-bearing, x, y)
        path.reset(); path.moveTo(x, y - r * 0.75f); path.lineTo(x + r * 0.28f, y); path.lineTo(x - r * 0.28f, y); path.close()
        fill.color = Color.rgb(230, 50, 40); c.drawPath(path, fill)
        path.reset(); path.moveTo(x, y + r * 0.75f); path.lineTo(x + r * 0.28f, y); path.lineTo(x - r * 0.28f, y); path.close()
        fill.color = t.hudText; c.drawPath(path, fill)
        c.restore()
    }

    private fun drawAttribution(c: Canvas, a: Rect, d: Float, t: Theme) {
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 9 * d
        text.color = Color.argb(170, Color.red(t.hudText), Color.green(t.hudText), Color.blue(t.hudText))
        c.drawText(if (Traffic.active) "© OpenStreetMap · OpenFreeMap · TomTom" else "© OpenStreetMap · OpenFreeMap", a.right - 8 * d, a.bottom - 6 * d, text)
    }
}
