package gr.gamemap

import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** What the ntfy.sh relay allows us: rate-limit headers on a publish, and how a fast burst fares. */
class NtfyProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        val topic = "gmtest" + System.currentTimeMillis()
        fun post(i: Int): String {
            val c = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 8000; c.doOutput = true
            c.setRequestProperty("User-Agent", "GameRoute/1.0")
            c.outputStream.use { it.write("{\"i\":$i}".toByteArray()) }
            val code = try { c.responseCode } catch (e: Exception) { -1 }
            val h = c.headerFields.filterKeys { it != null && (it.contains("Rate", true) || it.contains("Retry", true) || it.contains("Limit", true)) }
            return "$code $h"
        }
        log.append("first: " + post(0) + "\n")
        val t0 = System.currentTimeMillis()
        var firstFail = -1
        for (i in 1..70) { val r = post(i); if (!r.startsWith("200") && firstFail < 0) { firstFail = i; log.append("first refusal at #$i after ${System.currentTimeMillis() - t0} ms: $r\n") } }
        log.append("burst done; first refusal: $firstFail\n")
        File(out, "ntfy.txt").writeText(log.toString())
    }
}
