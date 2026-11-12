package gr.gamemap

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        val app = RuntimeEnvironment.getApplication()
        Places.init(app); VectorSource.init(app)
        val me = LatLng(37.9838, 23.7275) // ~3 km from Neos Kosmos
        log.append("greeklish: ${listOf("eggeli", "xalandri", "thiseio", "neos kosmos", "lykovrysi").map { it + "→" + Places.greeklish(it) }}\n")
        var t0 = System.currentTimeMillis()
        val q = "[out:csv(name,::lat,::lon;false;\"\\t\")][timeout:60];way[highway~\"^(primary|secondary|tertiary|unclassified|residential|living_street|pedestrian|service)$\"][name](37.9,23.7,38.0,23.8);out center;"
        for (ep in Alerts.overpass) {
            t0 = System.currentTimeMillis()
            val c = java.net.URL(ep).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 90000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("User-Agent", "GameMap/1.0 (probe)")
            val code = try { c.outputStream.use { it.write(("data=" + java.net.URLEncoder.encode(q, "UTF-8")).toByteArray()) }; c.responseCode } catch (e: Exception) { log.append("overpass $ep $e\n"); -1 }
            val body = try { (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() } catch (e: Exception) { e.toString() }
            log.append("overpass $ep $code ${System.currentTimeMillis() - t0} ms ${body.length} chars, lines ${body.lines().size}, Εγγ: ${body.lines().filter { it.contains("γγελ") }.take(3)}\n   ${body.take(200)}\n")
        }
        t0 = System.currentTimeMillis()
        Places.prepareStreets(me)
        log.append("streets index ${System.currentTimeMillis() - t0} ms\n")
        for (q in listOf("eggeli 32 news kosmos", "eggeli", "Λυκοβριση", "lykovrysi", "Εγγέλη 32", "kallirois 50", "ikea")) {
            t0 = System.currentTimeMillis()
            val local = Places.local(q, me)
            val tl = System.currentTimeMillis() - t0
            val all = Places.find(q, me, full = true, local = local)
            log.append("'$q' local ${tl} ms ${local.take(3).map { it.name + " (" + it.address + ")" }}\n   find ${System.currentTimeMillis() - t0} ms ${all.orEmpty().take(4).map { it.name + " | " + it.address.take(40) }}\n")
            Thread.sleep(1100)
        }
        t0 = System.currentTimeMillis()
        val parks = Places.nearbyLocal(listOf("parking"), LatLng(37.957, 23.728))
        log.append("parking local ${System.currentTimeMillis() - t0} ms: ${parks.take(5).map { it.name }}\n")
        File(out, "search.txt").writeText(log.toString())
    }
}
