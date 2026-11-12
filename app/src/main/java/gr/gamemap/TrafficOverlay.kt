package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * Traffic drawn once into see-through tiles on a background thread, then pasted over the map
 * tiles every frame: much cheaper than stroking the traffic lines again in each frame.
 */
object TrafficOverlay {
    private val mem = object : LruCache<String, Bitmap>(36) {}
    /** Newest overlay per tile (any version), shown while a fresher one is drawn. */
    private val latest = ConcurrentHashMap<String, String>()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val exec = Bg.pool("traffic-tiles", 1)
    private val builder = MapRenderer()
    private val squares = LinkedHashMap<String, MapRenderer.TrafficPaths>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    /** How many of the map tiles under a traffic square are loaded (checked at most once a second). */
    private val stamps = ConcurrentHashMap<String, Pair<Long, Int>>()

    private fun roadStamp(z: Int, dz: Int, ax: Int, ay: Int): Int {
        val k = "$z/$dz/$ax/$ay"
        val now = android.os.SystemClock.uptimeMillis()
        stamps[k]?.let { (at, v) -> if (now - at < 1000) return v }
        val tilePx = TileCache.tilePx
        val span = tilePx * 2.0.pow(z - dz)
        val vz = VectorSource.dataZoom(z)
        val osmSpan = tilePx * 2.0.pow(z - vz)
        val m = span * 0.1 // the snapping reach, generously
        val tx0 = Math.floor((ax * span - m) / osmSpan).toInt(); val tx1 = Math.floor(((ax + 1) * span + m) / osmSpan).toInt()
        val ty0 = Math.floor((ay * span - m) / osmSpan).toInt(); val ty1 = Math.floor(((ay + 1) * span + m) / osmSpan).toInt()
        var v = 0
        if ((tx1 - tx0 + 1) * (ty1 - ty0 + 1) <= 16) for (ty in ty0..ty1) for (tx in tx0..tx1) if (VectorSource.peek(vz, tx, ty) != null) v++
        // map tiles leaving memory later don't make it worse: keep the best seen
        stamps[k]?.let { v = maxOf(v, it.second) }
        if (stamps.size > 400) stamps.clear()
        stamps[k] = now to v
        return v
    }

    private fun dataZoom(z: Int) = when {
        z >= 17 -> 16
        z == 16 -> 15
        else -> (z - 1).coerceIn(Traffic.MIN_Z, Traffic.DATA_Z)
    }

    /** The overlay for a map tile, or an older one / null while it's being drawn. [build] = may start drawing. */
    fun get(theme: Theme, z: Int, x: Int, y: Int, build: Boolean): Bitmap? {
        val dz = dataZoom(z)
        val sh = z - dz
        val ax = x shr sh; val ay = y shr sh
        val t = Traffic.tile(dz, ax, ay) ?: return null
        // redrawn only when road data arrives for this traffic square (lanes lock onto roads),
        // not every time any map tile anywhere loads
        val stamp = roadStamp(z, dz, ax, ay)
        val dark = MapState.dark
        val tileKey = "${theme.name}/$dark/$z/$x/$y"
        val key = "$tileKey/${t.fetchedAt}/$stamp"
        mem.get(key)?.let { return it }
        if (build && busy.add(key)) {
            val look = theme.lookFor(dark)
            exec.execute {
                try {
                    run {
                        val bmp = draw(t, look, z, x, y, dz, ax, ay, stamp)
                        mem.put(key, bmp)
                        latest[tileKey] = key
                        TileCache.loaded.fire()
                    }
                } catch (_: Exception) {
                } finally {
                    busy.remove(key)
                }
            }
        }
        return latest[tileKey]?.let { mem.get(it) }
    }

    private fun draw(t: Traffic.Tile, look: Look, z: Int, x: Int, y: Int, dz: Int, ax: Int, ay: Int, stamp: Int): Bitmap {
        val tilePx = TileCache.tilePx
        val span = tilePx * 2.0.pow(z - dz) // one traffic square in world pixels at zoom z
        val u = (tilePx / 256f) / 1.15f       // one dp in world pixels at this zoom
        val sq = "$dz/$ax/$ay/$z/${t.fetchedAt}/$stamp"
        val tp = squares[sq] ?: builder.buildTraffic(t, ax, ay, span, u, z).also {
            squares[sq] = it
            while (squares.size > 12) squares.remove(squares.keys.first())
        }
        val size = tilePx / 2
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(size / tilePx.toFloat(), size / tilePx.toFloat())
        c.translate((tp.ox - x * tilePx).toFloat(), (tp.oy - y * tilePx).toFloat())
        val st = look.style
        paint.isAntiAlias = look.traffic.smooth
        for (state in intArrayOf(Traffic.SLOW, Traffic.JAM, Traffic.CLOSED)) {
            val p = tp.byState[state]
            if (p.isEmpty) continue
            paint.pathEffect = if (state == Traffic.CLOSED) DashPathEffect(floatArrayOf(6 * u, 4 * u), 0f) else null
            paint.color = if (state == Traffic.SLOW) st.trafficSlow else st.trafficJam
            paint.alpha = 185
            paint.strokeWidth = maxOf(2.4f, look.traffic.core * 0.7f) * u
            c.drawPath(p, paint)
        }
        return bmp
    }
}
