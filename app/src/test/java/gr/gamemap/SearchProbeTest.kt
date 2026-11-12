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
