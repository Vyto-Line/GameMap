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

/**
 * How quickly a wrong turn is noticed: drive the route, then turn into a side street a block
 * early (one GPS fix a second). Also drives the whole route correctly, which must never reroute.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RerouteTest {
    @Test
    fun wrongTurn() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val dest = Place("Museum", "", r.points.last())
        val log = StringBuilder()
        var t = System.currentTimeMillis()
        fun fix(p: LatLng, bearing: Float, speed: Float) {
            t += 1000
            LocationTracker.onLocationChanged(Location(LocationManager.GPS_PROVIDER).apply {
                latitude = p.lat; longitude = p.lon; this.bearing = bearing; this.speed = speed; accuracy = 6f; time = t
            })
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun at(m: Double): Pair<LatLng, Float> {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            return Geo.lerp(r.points[i], r.points[i + 1], f) to Geo.bearing(r.points[i], r.points[i + 1])
        }

        // 1. the whole route, correctly: count false reroutes
        NavEngine.debugDrive(dest, r, 0)
        var false1 = 0
        var m = 0.0
        while (m < r.length - 40) {
            val (p, b) = at(m); fix(p, b, 9f)
            if (NavEngine.loading) { false1++; NavEngine.debugDrive(dest, r, 0) }
            m += 9.0
        }
        log.append("correct drive (${r.length.toInt()} m): false reroutes = $false1\n")

        // 2. wrong turns: before each real turn, turn 90° into a side street ~50 m early
        val turns = r.steps.filter { it.type == "turn" || it.type == "end of road" }.filter { r.cum[it.routeIndex] > 120 }
        for (st in turns.take(4)) {
            NavEngine.debugDrive(dest, r, 0)
            val turnAt = r.cum[st.routeIndex]
            var mm = turnAt - 160
            while (mm < turnAt - 50) { val (p, b) = at(mm); fix(p, b, 9f); mm += 9.0 }
            val (p0, b0) = at(turnAt - 50)
            val side = if (st.direction.contains("left")) 90f else -90f // the other way from the real turn
            val nb = (b0 + side + 360f) % 360f
            var secs = 0; var pos = p0
            while (secs < 15 && !NavEngine.loading) {
                pos = Geo.offset(pos, nb, 9.0); fix(pos, nb, 9f); secs++
            }
            val dist = Geo.distance(p0, pos)
            log.append("wrong turn before '${st.text}': rerouted after ${if (NavEngine.loading) "$secs s (${dist.toInt()} m into the side street)" else "NOT within 15 s"}\n")
        }
        File(out, "reroute.txt").writeText(log.toString())
    }
}
