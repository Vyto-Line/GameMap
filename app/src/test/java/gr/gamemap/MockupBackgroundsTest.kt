package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Clean map renders (no app UI) to use as backgrounds for design mockups. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MockupBackgroundsTest {
    @Test
    fun backgrounds() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val shots = listOf(
            Triple(Theme.OPEN_WORLD, MapState.LightMode.DARK, 16.4),
            Triple(Theme.TRAILBLAZER, MapState.LightMode.LIGHT, 16.4),
            Triple(Theme.NEON_NOIR, MapState.LightMode.DARK, 16.4),
            Triple(Theme.PARCHMENT_QUEST, MapState.LightMode.LIGHT, 16.4),
            Triple(Theme.BLOCKY, MapState.LightMode.LIGHT, 16.8),
            Triple(Theme.LOOKOUT, MapState.LightMode.DARK, 16.4),
            Triple(Theme.OPEN_WORLD, MapState.LightMode.LIGHT, 16.4),
            Triple(Theme.RETRO, MapState.LightMode.LIGHT, 16.4),
            Triple(Theme.NEON_RACER, MapState.LightMode.DARK, 16.4),
        )
        for ((theme, mode, zoom) in shots) {
            MapState.setLightMode(mode); MapState.setTheme(theme)
            MapState.zoomBy(zoom - MapState.targetZoom)
            repeat(400) { MapState.tick(); Thread.sleep(2) }
            for ((tag, w, h) in listOf(Triple("phone", 1170, 2532), Triple("car", 1920, 720))) {
                val r = MapRenderer()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val d = if (tag == "phone") 3f else 1.6f
                for (i in 0 until 70) { r.draw(c, w, h, Rect(0, 0, w, h), d, hud = false); Thread.sleep(70) }
                r.draw(c, w, h, Rect(0, 0, w, h), d, hud = false)
                File(out, "bg_${tag}_${theme.name.lowercase()}_${mode.name.lowercase()}.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            }
        }
    }
}
