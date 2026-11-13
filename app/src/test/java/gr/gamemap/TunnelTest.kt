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

/** Drive, lose GPS (a tunnel), and check the arrow keeps moving along the route, then recovers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TunnelTest {
    @Test
    fun tunnel() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        NavEngine.debugDrive(Place("Museum", "", r.points.last()), r, 0)
        val looper = shadowOf(Looper.getMainLooper())
        val log = StringBuilder()
        fun fixAt(m: Double) {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val p = Geo.lerp(r.points[i], r.points[i + 1], ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0))
            LocationTracker.onLocationChanged(Location(LocationManager.GPS_PROVIDER).apply {
                latitude = p.lat; longitude = p.lon; speed = 12f; bearing = Geo.bearing(r.points[i], r.points[i + 1]); accuracy = 5f
                time = System.currentTimeMillis()
            })
            looper.idle()
        }
        var m = 100.0
        repeat(8) { fixAt(m); m += 12; Thread.sleep(1000); looper.idleFor(java.time.Duration.ofMillis(1000)) }
        log.append("before the tunnel: along=${NavEngine.along.toInt()} estimating=${NavEngine.estimating}\n")
        val a0 = NavEngine.along
        // no fixes for 8 s
        repeat(8) { Thread.sleep(1000); looper.idleFor(java.time.Duration.ofMillis(1000))
            log.append("  no GPS +${it + 1}s: along=${NavEngine.along.toInt()} estimating=${NavEngine.estimating}\n") }
        log.append("moved ${(NavEngine.along - a0).toInt()} m without GPS (expected about ${(12 * 5.5).toInt()}–${12 * 8} m)\n")
        m += 12 * 8
        fixAt(m)
        log.append("GPS back: along=${NavEngine.along.toInt()} (real ${m.toInt()}) estimating=${NavEngine.estimating}\n")
        File(out, "tunnel.txt").writeText(log.toString())
    }
}
