package gr.gamemap

import android.location.Location
import android.location.LocationManager
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * Taking another way home: driving well away from the suggested route must get a new route
 * quickly (even while the slow traffic check is busy) and must never count as arriving.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetourTest {
    @Test
    fun anotherWay() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val dest = Place("Museum", "", r.points.last())
        val log = StringBuilder()
        var t = System.currentTimeMillis()
        val looper = shadowOf(Looper.getMainLooper())
        fun fix(p: LatLng, bearing: Float, speed: Float = 12f) {
            t += 1000
            LocationTracker.onLocationChanged(Location(LocationManager.GPS_PROVIDER).apply {
                latitude = p.lat; longitude = p.lon; this.bearing = bearing; this.speed = speed; accuracy = 8f; time = t
            })
            looper.idle()
        }
        // 1. near the end of the route but 400 m to the side of it, heading away: not "arrived"
        NavEngine.debugDrive(dest, r, 0)
        val n = r.points.size
        val (p0, b0) = r.points[n - 3] to Geo.bearing(r.points[n - 3], r.points[n - 2])
        NavEngine::class.java.getDeclaredField("nearest").apply { isAccessible = true }.setInt(NavEngine, n - 4)
        val side = Geo.offset(p0, (b0 + 90) % 360, 400.0)
        for (k in 0 until 6) fix(Geo.offset(side, (b0 + 90) % 360, k * 12.0), (b0 + 90) % 360)
        log.append("400 m beside the route's end: arrived=${NavEngine.arrived} (want false), rerouting=${NavEngine.loading || NavEngine.current !== r}\n")

        // 2. a reroute while the slow traffic check is busy (simulated: its line blocked for 30 s)
        NavEngine.debugDrive(dest, r, 0)
        val bg = NavEngine::class.java.getDeclaredField("bg").apply { isAccessible = true }.get(NavEngine) as ExecutorService
        bg.execute { Thread.sleep(30_000) }
        NavEngine::class.java.getDeclaredField("lastRouteAt").apply { isAccessible = true }.setLong(NavEngine, 0L)
        val mid = r.points[n / 2]; val mb = Geo.bearing(r.points[n / 2], r.points[n / 2 + 1])
        val away = Geo.offset(mid, (mb + 90) % 360, 300.0)
        val t0 = System.currentTimeMillis()
        for (k in 0 until 3) fix(Geo.offset(away, (mb + 90) % 360, k * 12.0), (mb + 90) % 360)
        var waited = 0
        while ((NavEngine.loading || NavEngine.current === r) && waited++ < 200) { looper.idle(); Thread.sleep(100) }
        val newStart = NavEngine.current?.points?.firstOrNull()
        log.append("reroute with the traffic check busy: ${if (NavEngine.current !== r) "new route" else "NO new route"} in ${System.currentTimeMillis() - t0} ms; " +
            "starts ${newStart?.let { "%.0f".format(Geo.distance(it, away)) } ?: "?"} m from the car; error=${NavEngine.error}\n")
        NavEngine.stop()
        File(out, "detour.txt").writeText(log.toString())
    }
}
