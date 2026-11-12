package gr.gamemap

import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CameraProbeTest {
    private fun overpass(ep: String, q: String): Triple<Int, Long, String> {
        val t0 = System.currentTimeMillis()
        val c = URL(ep).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 120000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("User-Agent", "GameMap/1.0 (probe)")
        val code = try { c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }; c.responseCode } catch (e: Exception) { -1 }
        val body = try { (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() } catch (e: Exception) { e.toString() }
        return Triple(code, System.currentTimeMillis() - t0, body)
    }

    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        val cell = "(37.5,23.5,38.0,24.0)"
        val qs = listOf(
            "current app query" to "[out:json][timeout:50];(node[\"highway\"=\"speed_camera\"]$cell;node[\"enforcement\"=\"maxspeed\"]$cell;);out;",
            "relations too" to "[out:json][timeout:50];(node[\"highway\"=\"speed_camera\"]$cell;relation[\"enforcement\"~\"maxspeed|average_speed|traffic_signals\"]$cell;node[\"enforcement\"]$cell;);out tags center;",
            "greece total" to "[out:json][timeout:90];area[\"ISO3166-1\"=\"GR\"]->.a;(node[\"highway\"=\"speed_camera\"](area.a);relation[\"enforcement\"](area.a););out count;",
        )
        for ((name, q) in qs) for (ep in listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter")) {
            val (code, ms, body) = overpass(ep, q)
            val n = Regex("\"type\"").findAll(body).count()
            log.append("$name @ $ep: $code $ms ms, elements~$n\n   ${body.take(if (name == "greece total") 600 else 300)}\n")
        }
        File(out, "cameras.txt").writeText(log.toString())
    }
}
