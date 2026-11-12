package gr.gamemap

import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TomTomProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val k = System.getenv("TOMTOM_KEY").orEmpty()
        val log = StringBuilder("key set: ${k.isNotEmpty()}\n")
        val q = URLEncoder.encode("Εγγέλη 32", "UTF-8")
        for ((name, u) in listOf(
            "fuzzy search" to "https://api.tomtom.com/search/2/search/$q.json?key=$k&limit=3&lat=37.98&lon=23.72&language=el-GR",
            "poi search" to "https://api.tomtom.com/search/2/poiSearch/IKEA.json?key=$k&limit=2&lat=37.98&lon=23.72",
            "nearby" to "https://api.tomtom.com/search/2/nearbySearch/.json?key=$k&limit=2&lat=37.957&lon=23.728&categorySet=7313",
            "autocomplete" to "https://api.tomtom.com/search/2/autocomplete/Λυκοβρ.json?key=$k&language=el-GR&lat=37.98&lon=23.72",
            "geocode" to "https://api.tomtom.com/search/2/geocode/$q.json?key=$k&limit=2&lat=37.98&lon=23.72",
            "reverse" to "https://api.tomtom.com/search/2/reverseGeocode/37.9564,23.7269.json?key=$k&language=el-GR",
        )) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val code = try { c.responseCode } catch (e: Exception) { -1 }
            val body = try { (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() } catch (e: Exception) { e.toString() }
            log.append("$name: $code ${body.replace(k, "KEY").take(300)}\n")
        }
        File(out, "tomtom.txt").writeText(log.toString())
    }
}
