package gr.gamemap

import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class NtfyProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        val topic = "gmtest" + System.currentTimeMillis()
        fun req(method: String, u: String, body: String? = null, type: String = "text/plain"): String {
            val c = URL(u).openConnection() as HttpURLConnection
            c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "GameMap/1.0")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", type); c.outputStream.use { it.write(body.toByteArray()) } }
            val code = try { c.responseCode } catch (e: Exception) { -1 }
            val txt = try { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() } catch (e: Exception) { e.toString() }
            return "$code ${txt.take(300)}"
        }
        log.append("POST: " + req("POST", "https://ntfy.sh/$topic", "{\"state\":\"driving\",\"ts\":1}") + "\n")
        Thread.sleep(1500)
        log.append("GET since=all: " + req("GET", "https://ntfy.sh/$topic/json?poll=1&since=all") + "\n")
        log.append("GET since=12h: " + req("GET", "https://ntfy.sh/$topic/json?poll=1&since=12h") + "\n")
        File(out, "ntfy.txt").writeText(log.toString())
    }
}
