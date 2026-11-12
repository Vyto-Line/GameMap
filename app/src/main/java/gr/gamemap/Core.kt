package gr.gamemap

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

class GameMapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapState.init(this)
        TileCache.init(this)
    }
}

data class LatLng(val lat: Double, val lon: Double)

/** A set of "something changed, redraw" callbacks, always fired on the main thread. */
class Signal {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    operator fun plusAssign(l: () -> Unit) { listeners += l }
    operator fun minusAssign(l: () -> Unit) { listeners -= l }
    fun fire() {
        if (Looper.myLooper() == Looper.getMainLooper()) listeners.forEach { it() }
        else main.post { listeners.forEach { it() } }
    }
}

object Geo {
    private const val R = 6_371_000.0

    fun x(lon: Double, world: Double) = (lon + 180.0) / 360.0 * world

    fun y(lat: Double, world: Double): Double {
        val s = sin(Math.toRadians(lat)).coerceIn(-0.9999, 0.9999)
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * world
    }

    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Distance in metres from p to segment a-b (flat approximation, fine for short segments). */
    fun distanceToSegment(p: LatLng, a: LatLng, b: LatLng): Double {
        val k = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * k; val ay = a.lat - p.lat
        val bx = (b.lon - p.lon) * k; val by = b.lat - p.lat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy) * 111_320.0
    }
}

/** Map view settings shared by the phone and the car screen. */
object MapState {
    val changed = Signal()
    var theme = Theme.OPEN_WORLD
        private set
    var zoom = 16.5
        private set
    private lateinit var appContext: Context

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        val p = prefs()
        theme = Theme.entries.getOrElse(p.getInt("theme", 0)) { Theme.OPEN_WORLD }
        zoom = p.getFloat("zoom", 16.5f).toDouble()
    }

    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)

    fun nextTheme(): Theme {
        theme = Theme.entries[(theme.ordinal + 1) % Theme.entries.size]
        prefs().edit().putInt("theme", theme.ordinal).apply()
        changed.fire()
        return theme
    }

    fun zoomBy(delta: Double) {
        zoom = (zoom + delta).coerceIn(4.0, 19.0)
        prefs().edit().putFloat("zoom", zoom.toFloat()).apply()
        changed.fire()
    }

    fun zoomByFactor(factor: Float) {
        if (factor > 0) zoomBy(log2(factor.toDouble()))
    }
}
