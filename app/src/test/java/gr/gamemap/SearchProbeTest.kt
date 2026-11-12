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
            "https://api.tomtom.com/search/2/poiSearch/Syntagma.json?key=$k&limit=3&lat=37.98&lon=23.72",
            "https://api.tomtom.com/search/2/geocode/Syntagma.json?key=$k&limit=3&lat=37.98&lon=23.72",
            "https://api.tomtom.com/search/2/autocomplete/Syntag.json?key=$k&language=en-GB&lat=37.98&lon=23.72",
            "https://api.tomtom.com/search/2/categorySearch/museum.json?key=$k&limit=3&lat=37.98&lon=23.72",
            "https://api.tomtom.com/search/2/nearbySearch/.json?key=$k&limit=2&lat=37.98&lon=23.72",
            "https://api.tomtom.com/search/2/structuredGeocode.json?key=$k&countryCode=GR&streetName=Kifisias&streetNumber=100&municipality=Athens",
            "https://photon.komoot.io/api/?q=Syntagma&limit=2",
            "https://nominatim.openstreetmap.org/search?q=Syntag&format=jsonv2&limit=2",
        )) {
            val c = URL(u).openConnection() as HttpURLConnection
            val code = run { c.connectTimeout = 8000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", "GameMap/1.0"); try { c.responseCode } catch (e: Exception) { -1 } }
            val body = try { (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() } catch (e: Exception) { e.toString() }
            log.append("$code ${u.replace(k, "KEY")}\n   ${body.take(400)}\n")
        }
        try { log.append("Places.tomtom: " + Places.tomtom("Syntagma", LatLng(37.98, 23.72), true).map { it.name } + "\n") }
        catch (e: Exception) { log.append("Places.tomtom error: $e\n") }
        File(out, "search.txt").writeText(log.toString())
    }
}
