package gr.gamemap

import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LivePageProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val c = URL("https://vyto-line.github.io/GameMap/live/").openConnection() as HttpURLConnection
        val code = try { c.responseCode } catch (e: Exception) { -1 }
        val body = try { c.inputStream.bufferedReader().readText() } catch (e: Exception) { e.toString() }
        File(out, "livepage.txt").writeText("$code ${Regex("<title>(.*?)</title>").find(body)?.groupValues?.get(1)}\n")
    }
}
