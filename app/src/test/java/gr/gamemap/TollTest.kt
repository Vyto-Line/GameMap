package gr.gamemap

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Real routes from Athens with the published toll data: the totals should match the known trips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TollTest {
    @Test
    fun trips() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        Tolls.loadForTest(Http.get("https://raw.githubusercontent.com/Vyto-Line/GameRoute/data/tolls/GR.json", timeout = 20000))
        val log = StringBuilder()
        val athens = LatLng(37.9838, 23.7275)
        for ((name, to) in listOf(
            "Athens → Patras (known: €13.80)" to LatLng(38.2466, 21.7346),
            "Athens → Thessaloniki (known: €35.55–36.70)" to LatLng(40.6401, 22.9444),
            "Athens → Corinth (known: €4.60)" to LatLng(37.9386, 22.9322),
            "Athens → Tripoli (known: €10.35)" to LatLng(37.5100, 22.3794),
            "Athens → Airport via Attiki Odos (known: €2.55)" to LatLng(37.9364, 23.9445),
        )) {
            val r = try { Osrm.routes(athens, to, alternatives = false).first() } catch (e: Exception) { log.append("$name: route failed $e\n"); continue }
            val t = Tolls.along(r)
            log.append("$name: €${"%.2f".format(t.sumOf { it.price })}  (${(r.distance / 1000).toInt()} km)\n")
            t.forEach { log.append("    ${"%.2f".format(it.price)}  ${it.name}  @${(it.along / 1000).toInt()} km\n") }
        }
        File(out, "tolls.txt").writeText(log.toString())
    }
}
