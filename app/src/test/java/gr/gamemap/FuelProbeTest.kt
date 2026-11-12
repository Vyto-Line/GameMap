package gr.gamemap

import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class FuelProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        for (u in listOf(
            "https://www.fuel-prices.eu/live/api.php?action=stations&country=GR&lat=37.957&lng=23.728&radius=3&limit=5",
            "https://www.fuel-prices.eu/live/api.php?action=stations&country=GR&lat=40.64&lng=22.94&radius=3&limit=2",
            "https://www.fuel-prices.eu/live/geojson.php?country=GR&fuel=diesel&bounds=37.9,23.6,38.0,23.8&limit=3",
        )) {
            val t0 = System.currentTimeMillis()
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 20000; c.setRequestProperty("User-Agent", "GameMap/1.0 (Android navigation app)")
            val code = try { c.responseCode } catch (e: Exception) { -1 }
            val body = try { (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() } catch (e: Exception) { e.toString() }
            log.append("$code ${System.currentTimeMillis() - t0} ms $u\n${body.take(2500)}\n\n")
        }
        File(out, "fuel.txt").writeText(log.toString())
    }
}
