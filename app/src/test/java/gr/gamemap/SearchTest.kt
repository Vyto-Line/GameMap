package gr.gamemap

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The app's search with real services and data: what comes first for real queries, and how fast. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchTest {
    @Test
    fun queries() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        val glyfada = LatLng(37.8650, 23.7530)
        val syntagma = LatLng(37.9755, 23.7348)
        for (p in listOf(glyfada, syntagma)) {
            val t0 = System.currentTimeMillis()
            Places.prepareGazetteer(p); OpenPlaces.prepare(p)
            log.append("prepared ${if (p == glyfada) "Glyfada" else "Syntagma"} in ${System.currentTimeMillis() - t0} ms\n")
        }
        val queries = listOf("crepa land", "crepaland", "Crepa Land Glyfada", "κρεπα λαντ", "κρέπα λαντ γλυφάδα", "crepa land στη γλυφαδα",
            "zaxari kai alati", "lidl glyfada", "everest", "mikel", "αιγυπτου 25 γλυφαδα", "starbucks", "pharmacy", "museum")
        for (near in listOf(glyfada, syntagma)) for (q in queries) {
            val t0 = System.currentTimeMillis()
            val inst = Places.local(q, near)
            val t1 = System.currentTimeMillis()
            val r = Places.find(q, near, full = true, local = inst).orEmpty()
            val t2 = System.currentTimeMillis()
            log.append("\n\"$q\" near ${if (near == glyfada) "Glyfada" else "Syntagma"}  (instant ${t1 - t0} ms: ${inst.take(2).joinToString { it.name }}; full ${t2 - t1} ms)\n")
            r.take(5).forEachIndexed { i, p ->
                log.append("  ${i + 1}. ${p.icon} ${p.name} · ${p.address.take(45)} · ${"%.1f".format(Geo.distance(near, p.pos) / 1000)} km${if (p.entry != null) " · entrance ${"%.0f".format(Geo.distance(p.entry!!, p.pos))} m" else ""}\n")
            }
        }
        File(out, "search.txt").writeText(log.toString())
    }
}
