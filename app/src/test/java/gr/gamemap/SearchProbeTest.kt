package gr.gamemap

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchProbeTest {
    private fun fetch(u: String): Pair<Int, ByteArray> {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 20000; c.setRequestProperty("User-Agent", "GameMap/1.0 (probe)")
        val code = try { c.responseCode } catch (e: Exception) { -1 }
        val body = try { (if (code == 200) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0) } catch (e: Exception) { ByteArray(0) }
        return code to body
    }

    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        val near = LatLng(37.957, 23.728) // Neos Kosmos
        val t = JSONObject(String(fetch("https://tiles.openfreemap.org/planet").second)).getJSONArray("tiles").getString(0)
        for (z in listOf(12, 13, 14)) {
            val n = 1 shl z
            val x = (Geo.x(near.lon, 1.0) * n).toInt(); val y = (Geo.y(near.lat, 1.0) * n).toInt()
            val t0 = System.currentTimeMillis()
            val (code, bytes) = fetch(t.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y"))
            val ms = System.currentTimeMillis() - t0
            val vt = try { VectorTile(bytes) } catch (e: Exception) { log.append("z$z parse $e\n"); continue }
            val names = vt.features.filter { it.layer == "transportation_name" }.mapNotNull { it["name"] }.toSet()
            val parks = vt.features.filter { it.layer == "poi" && (it["class"] == "parking" || it["subclass"] == "parking") }
            log.append("z$z $code ${bytes.size} B ${ms} ms: ${names.size} street names, Εγγέλη=${names.any { it.contains("Εγγ") }}, " +
                "parking pois=${parks.size} (named ${parks.count { !it["name"].isNullOrBlank() }}) layers=${vt.features.groupingBy { it.layer }.eachCount()}\n")
            log.append("   sample names: ${names.take(15)}\n")
            log.append("   parking sample: ${parks.take(5).map { it.props }}\n")
            log.append("   poi classes: ${vt.features.filter { it.layer == "poi" }.groupingBy { it["class"] }.eachCount()}\n")
        }
        val vb = "&viewbox=${near.lon - 0.15},${near.lat + 0.15},${near.lon + 0.15},${near.lat - 0.15}"
        for (q in listOf("Εγγέλη 32", "Εγγέλη 32 Νέος Κόσμος", "Εγγελι 32", "eggeli 32", "engeli 32", "Εγγέλη")) {
            for (b in listOf("", "&bounded=1")) {
                val (code, body) = fetch("https://nominatim.openstreetmap.org/search?q=${URLEncoder.encode(q, "UTF-8")}&format=jsonv2&limit=3$vb$b")
                log.append("nominatim '$q'$b $code: ${String(body).take(300)}\n")
                Thread.sleep(1100)
            }
        }
        for (q in listOf("eggeli 32 news kosmos", "Λυκοβριση", "ikea")) {
            val t0 = System.currentTimeMillis()
            val r = try { Places.suggest(q, near).map { it.name + " | " + it.address }.take(4).toString() } catch (e: Exception) { e.toString() }
            log.append("suggest '$q' ${System.currentTimeMillis() - t0} ms: $r\n")
        }
        File(out, "search.txt").writeText(log.toString())
    }
}
