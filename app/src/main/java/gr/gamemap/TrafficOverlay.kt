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

    // lanes re-lock onto the roads as map data arrives, but at most every 10 s
    @Volatile private var epoch = 0
    @Volatile private var epochSeen = -1
    @Volatile private var epochAt = 0L

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
        val now = android.os.SystemClock.uptimeMillis()
        if (VectorSource.loadedCount != epochSeen && now - epochAt > 10_000) {
            epochSeen = VectorSource.loadedCount; epoch++; epochAt = now
        }
        val dark = MapState.dark
        val tileKey = "${theme.name}/$dark/$z/$x/$y"
        val key = "$tileKey/${t.fetchedAt}/$epoch"
        mem.get(key)?.let { return it }
        if (build && busy.add(key)) {
            val look = theme.lookFor(dark)
            val e = epoch
            exec.execute {
                try {
                    if (e == epoch) {
                        val bmp = draw(t, look, z, x, y, dz, ax, ay)
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

    private fun draw(t: Traffic.Tile, look: Look, z: Int, x: Int, y: Int, dz: Int, ax: Int, ay: Int): Bitmap {
        val tilePx = TileCache.tilePx
        val span = tilePx * 2.0.pow(z - dz) // one traffic square in world pixels at zoom z
        val u = (tilePx / 256f) / 1.15f       // one dp in world pixels at this zoom
        val sq = "$dz/$ax/$ay/$z/${t.fetchedAt}/$epoch"
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
