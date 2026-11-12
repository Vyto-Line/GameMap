package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Times the real services and the renderer, and checks search answers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DiagnosticsTest {
    private val log = StringBuilder()
    private fun <T> time(label: String, f: () -> T): T? {
        val t0 = System.currentTimeMillis()
        return try {
            val r = f(); log.append("%-38s %6d ms  %s\n".format(label, System.currentTimeMillis() - t0, summary(r))); r
        } catch (e: Exception) {
            log.append("%-38s %6d ms  ERROR %s\n".format(label, System.currentTimeMillis() - t0, e.toString().take(200))); null
        }
    }
    private fun summary(r: Any?): String = when (r) {
        is List<*> -> "${r.size} → " + r.take(3).joinToString(" | ") { (it as? Place)?.name ?: it.toString().take(60) }
        else -> r.toString().take(160)
    }

    @Test
    fun diagnose() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val athens = LatLng(37.9838, 23.7275)
        for (q in listOf("Syntagma", "Σύνταγμα", "Acropolis Museum", "Ikea", "Πειραιάς", "Κηφισίας 100", "starbucks")) {
            time("photon suggest '$q'") { Places.suggest(q, athens) }
        }
        time("search (Enter) 'Ikea'") { Places.search("Ikea", athens) }
        time("photon 'Syntagma'") { Places.photon("Syntagma", athens) }
        time("nearby fuel") { Places.nearby(listOf("fuel", "charging_station"), athens) }
        time("nearby parking") { Places.nearby(listOf("parking"), athens) }
        val dest = LatLng(37.9990, 23.7329)
        val routes = time("osrm 3 routes 2 km") { Osrm.routes(athens, dest, true) } ?: emptyList()
        val far = time("osrm 3 routes 25 km (Piraeus→Kifisia)") { Osrm.routes(LatLng(37.9420, 23.6470), LatLng(38.0740, 23.8110), true) } ?: emptyList()
        for (o in routes + far) {
            time("tomtom evaluate ${(o.length / 1000).toInt()} km osrm=${o.duration.toInt()}s") {
                TomTomTime.evaluate(o, 0.0)?.let { "tt=${it.seconds}s delay=${it.delay}s traffic=${it.traffic.size} limits=${it.limits.size}" }
            }
        }
        time("tomtom eta 25 km") { Traffic.eta(LatLng(37.9420, 23.6470), LatLng(38.0740, 23.8110))?.let { "${it.seconds}s delay ${it.delaySeconds}" } }

        // traffic road types in a flow tile
        val t = time("traffic tile 14/9271/6321") {
            var tile: Traffic.Tile? = null
            repeat(40) { if (tile == null) { tile = Traffic.tile(14, 9271, 6321); Thread.sleep(250) } }
            tile
        }
        log.append("traffic segs: ${t?.segs?.size} types: ${Traffic.roadTypes}\n")

        // renderer timing
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        for (theme in listOf(Theme.OPEN_WORLD, Theme.NEON_NOIR)) {
            MapState.setTheme(theme)
            MapState.zoomBy(16.5 - MapState.targetZoom)
            repeat(300) { MapState.tick(); Thread.sleep(1) }
            val w = 1080; val h = 2340
            val r = MapRenderer()
            val c = Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
            repeat(80) { r.draw(c, w, h, Rect(0, 0, w, h), 2.75f, hud = true); Thread.sleep(50) }
            MapRenderer.times.clear(); MapRenderer.profile = true
            val t0 = System.nanoTime()
            repeat(30) { r.draw(c, w, h, Rect(0, 0, w, h), 2.75f, hud = true) }
            val total = (System.nanoTime() - t0) / 30 / 1e6
            MapRenderer.profile = false
            log.append("render ${theme.name}: ${"%.1f".format(total)} ms/frame; " +
                MapRenderer.times.entries.joinToString { "${it.key} ${"%.1f".format(it.value / 30 / 1e6)}" } + "\n")
        }
        File(out, "diagnostics.txt").writeText(log.toString())
    }
}
