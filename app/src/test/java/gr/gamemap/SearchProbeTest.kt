package gr.gamemap

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        val k = Traffic.key
        for (u in listOf(
            "https://api.tomtom.com/search/2/search/Syntagma.json?key=$k&limit=3",
            "https://api.tomtom.com/search/2/search/Syntagma.json?key=$k&limit=3&typeahead=true&lat=37.98&lon=23.72&language=en-GB",
            "https://api.tomtom.com/search/2/search/Syntagma.json?key=$k&limit=3&idxSet=POI,PAD,Addr,Str,Geo",
            "https://api.tomtom.com/search/2/search/%CE%A3%CF%8D%CE%BD%CF%84%CE%B1%CE%B3%CE%BC%CE%B1.json?key=$k&limit=3&language=el-GR",
        )) {
            val c = URL(u).openConnection() as HttpURLConnection
            val code = c.responseCode
            val body = (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            log.append("$code ${u.replace(k, "KEY")}\n   ${body.take(400)}\n")
        }
        try { log.append("Places.tomtom: " + Places.tomtom("Syntagma", LatLng(37.98, 23.72), true).map { it.name } + "\n") }
        catch (e: Exception) { log.append("Places.tomtom error: $e\n") }
        File(out, "search.txt").writeText(log.toString())
    }
}
