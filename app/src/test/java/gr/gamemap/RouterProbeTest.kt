package gr.gamemap

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Plans the same trips with OSRM and with the TomTom backup, so the two can be compared. */
@RunWith(RobolectricTestRunner::class)
class RouterProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        Traffic.init(RuntimeEnvironment.getApplication())
        val log = StringBuilder("tomtom active: ${Traffic.active}\n")
        val trips = listOf(
            "Syntagma → Kifisia" to (LatLng(37.9755, 23.7348) to LatLng(38.0742, 23.8110)),
            "Piraeus → Glyfada, via Faliro" to (LatLng(37.9420, 23.6470) to LatLng(37.8650, 23.7530)),
        )
        for ((i, t) in trips.withIndex()) {
            val (name, ab) = t
            val via = if (i == 1) listOf(LatLng(37.9340, 23.6950)) else emptyList()
            for ((src, f) in listOf<Pair<String, () -> List<RouteOption>>>(
                "OSRM" to { Osrm.routes(ab.first, ab.second, alternatives = via.isEmpty(), via = via) },
                "TomTom" to { TomTomRoute.routes(ab.first, ab.second, via.isEmpty(), 90f, via) },
            )) {
                val t0 = System.currentTimeMillis()
                val rs = try { f() } catch (e: Exception) { log.append("$name [$src] failed: $e\n"); continue }
                log.append("$name [$src] ${System.currentTimeMillis() - t0} ms, ${rs.size} routes\n")
                rs.forEach { r ->
                    log.append("  ${"%.1f".format(r.distance / 1000)} km, ${(r.seconds / 60).toInt()} min (traffic ${r.trafficSeconds?.div(60)}), via ${r.via}, " +
                        "${r.points.size} pts, ${r.steps.size} steps, toll=${r.hasToll} motorway=${r.hasMotorway}, ${r.traffic.size} jams, ${r.limits.size} limits\n")
                }
                rs.firstOrNull()?.steps?.forEach {
                    log.append("    @${it.routeIndex} ${Phrases.instruction(it, Phrases.ENGLISH)} | ${Phrases.instruction(it, Phrases.GREEK)}\n")
                }
            }
        }
        File(out, "router.txt").writeText(log.toString())
    }
}
