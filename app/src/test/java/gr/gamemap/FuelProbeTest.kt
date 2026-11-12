package gr.gamemap

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FuelProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        val app = RuntimeEnvironment.getApplication()
        Places.init(app); VectorSource.init(app); Fuel.init(app); Reviews.init(app)
        for (p in listOf(LatLng(37.957, 23.728), LatLng(45.4642, 9.19), LatLng(40.4168, -3.7038))) {
            val t0 = System.currentTimeMillis()
            val r = Places.nearbyFull(listOf("fuel", "charging_station"), p, emptyList())
            log.append("${p} ${System.currentTimeMillis() - t0} ms country=${Fuel.country(p)}\n")
            r.orEmpty().take(6).forEach { log.append("   ${it.name} | ${it.address} | ${it.extra}\n") }
        }
        log.append("forPlace: " + Fuel.forPlace(Place("AEGEAN", "", LatLng(37.9578816, 23.730779), "⛽")) + "\n")
        log.append("maps: " + Reviews.mapsLink(Place("Shell", "", LatLng(37.96, 23.71))) + "\n")
        File(out, "fuel.txt").writeText(log.toString())
    }
}
