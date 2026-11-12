package gr.gamemap

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

class GameMapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapState.init(this)
        TileCache.init(this)
        Places.init(this)
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
    private const val M_PER_DEG = 111_320.0

    fun x(lon: Double, world: Double) = (lon + 180.0) / 360.0 * world

    fun y(lat: Double, world: Double): Double {
        val s = sin(Math.toRadians(lat)).coerceIn(-0.9999, 0.9999)
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * world
    }

    fun lon(x: Double, world: Double) = x / world * 360.0 - 180.0

    fun lat(y: Double, world: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / world))))

    /** Metres covered by one world pixel at this latitude. */
    fun metersPerPixel(lat: Double, world: Double) = cos(Math.toRadians(lat)) * 2 * PI * R / world

    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Compass bearing from a to b, degrees. */
    fun bearing(a: LatLng, b: LatLng): Float {
        val k = cos(Math.toRadians(a.lat))
        val dx = (b.lon - a.lon) * k; val dy = b.lat - a.lat
        return ((Math.toDegrees(atan2(dx, dy)) + 360) % 360).toFloat()
    }

    /** Point [meters] away from p in direction [bearing]. */
    fun offset(p: LatLng, bearing: Float, meters: Double): LatLng {
        val b = Math.toRadians(bearing.toDouble())
        return LatLng(
            p.lat + meters * cos(b) / M_PER_DEG,
            p.lon + meters * sin(b) / (M_PER_DEG * cos(Math.toRadians(p.lat))),
        )
    }

    fun lerp(a: LatLng, b: LatLng, t: Double) = LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** Closest point to p on segment a-b, and its distance in metres (flat approximation). */
    fun project(p: LatLng, a: LatLng, b: LatLng): Pair<LatLng, Double> {
        val k = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * k; val ay = a.lat - p.lat
        val bx = (b.lon - p.lon) * k; val by = b.lat - p.lat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return LatLng(p.lat + cy, p.lon + cx / k) to sqrt(cx * cx + cy * cy) * M_PER_DEG
    }

    fun distanceToSegment(p: LatLng, a: LatLng, b: LatLng) = project(p, a, b).second

    /** Signed smallest difference b - a, in -180..180. */
    fun angleDiff(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f
}

/** Camera and settings shared by the phone and the car screen. */
object MapState {
    val changed = Signal()
    var theme = Theme.OPEN_WORLD
        private set

    /** Zoom being shown right now (animates toward [targetZoom]). */
    var zoom = 16.5
        private set
    var targetZoom = 16.5
        private set

    /** True while the camera follows the player; false after the user drags the map. */
    var following = true
        private set
    var freeCenter: LatLng? = null
        private set
    var freeBearing = 0f
        private set

    /** Map rotation chosen with a two-finger twist; null = turn with the direction of travel. */
    var userBearing: Float? = null
        private set

    private lateinit var appContext: Context
    private var lastTick = 0L

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        val p = prefs()
        theme = Theme.entries.getOrElse(p.getInt("theme", 0)) { Theme.OPEN_WORLD }
        zoom = p.getFloat("zoom", 16.5f).toDouble().coerceIn(MIN_ZOOM, MAX_ZOOM)
        targetZoom = zoom
    }

    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)

    fun nextTheme(): Theme = setTheme(Theme.entries[(theme.ordinal + 1) % Theme.entries.size])

    fun setTheme(t: Theme): Theme {
        theme = t
        prefs().edit().putInt("theme", theme.ordinal).apply()
        changed.fire()
        return theme
    }

    /** Button zoom: animates smoothly to the next level. */
    fun zoomBy(delta: Double) {
        targetZoom = (targetZoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        saveZoom()
        changed.fire()
    }

    /** Pinch zoom: follows the fingers directly. */
    fun zoomByFactor(factor: Float) {
        if (factor <= 0) return
        zoom = (zoom + log2(factor.toDouble())).coerceIn(MIN_ZOOM, MAX_ZOOM)
        targetZoom = zoom
        saveZoom()
        changed.fire()
    }

    private fun saveZoom() = prefs().edit().putFloat("zoom", targetZoom.toFloat()).apply()

    /** Advances the zoom animation; safe to call from several renderers. Returns true while animating. */
    fun tick(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastTick).coerceIn(0, 100)
        lastTick = now
        if (abs(targetZoom - zoom) < 0.003) {
            zoom = targetZoom
            return false
        }
        zoom += (targetZoom - zoom) * (1 - exp(-dt / 110.0))
        return true
    }

    fun pan(center: LatLng, bearing: Float) {
        following = false
        freeCenter = center
        freeBearing = bearing
        changed.fire()
    }

    fun recenter() {
        following = true
        freeCenter = null
        userBearing = null
        changed.fire()
    }

    /** Two-finger twist: rotate the map by [delta] degrees from [current]. */
    fun rotateBy(delta: Float, current: Float) {
        val b = ((userBearing ?: current) + delta + 720f) % 360f
        userBearing = b
        if (!following) freeBearing = b
        changed.fire()
    }

    /** Reset rotation to the direction of travel (or north when panning). */
    fun clearRotation() {
        userBearing = null
        if (!following) freeBearing = 0f
        changed.fire()
    }

    const val MIN_ZOOM = 3.0
    const val MAX_ZOOM = 19.0
}
