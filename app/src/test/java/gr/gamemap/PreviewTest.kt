package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders real map data with every theme into PNGs, to check the look. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PreviewTest {
    @Test
    fun renderPreviews() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val timings = StringBuilder()
        for (theme in Theme.entries) {
            MapState.setTheme(theme)
            // full phone-sized frames at two zoom levels (default position: central Athens)
            for (zoom in listOf(16.0, 17.3)) {
                MapState.zoomBy(zoom - MapState.targetZoom)
                repeat(400) { MapState.tick(); Thread.sleep(2) }
                val r = MapRenderer()
                val frame = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
                val c = Canvas(frame)
                val t0 = System.nanoTime()
                // draw until every tile is ready (tiles are drawn on background threads)
                for (i in 0 until 200) {
                    r.draw(c, 1080, 1920, Rect(0, 160, 1080, 1920), 2.75f, hud = true)
                    Thread.sleep(50)
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    if (i > 5 && VectorSource.busy.get() == 0 && allTilesReady(theme)) break
                }
                r.draw(c, 1080, 1920, Rect(0, 160, 1080, 1920), 2.75f, hud = true)
                timings.append("${theme.name} zoom $zoom: ${(System.nanoTime() - t0) / 1_000_000} ms\n")
                File(out, "${theme.name.lowercase()}_frame_${zoom.toInt()}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 90, it) }
            }
        }
        // per-tile render cost
        val t1 = System.nanoTime()
        for (dx in 0..3) for (dy in 0..3) VectorRenderer.render(Theme.OPEN_WORLD, 16, 37084 + dx, 25284 + dy, 512)
        timings.append("16 z16 tiles (Open World): ${(System.nanoTime() - t1) / 1_000_000} ms\n")
        File(out, "timings.txt").writeText(timings.toString())
    }

    private var lastCount = -1
    private fun allTilesReady(theme: Theme): Boolean {
        // simple heuristic: wait until a few frames pass with no new tiles arriving
        val n = (0..3).sumOf { dx -> (0..3).count { dy -> TileCache.peek(theme, 16, 37084 + dx, 25284 + dy) != null } }
        val stable = n == lastCount
        lastCount = n
        return stable && n > 0
    }
}
