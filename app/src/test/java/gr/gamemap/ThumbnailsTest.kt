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

/** Small light/dark previews of every theme for the style picker (res/drawable-nodpi/thumb_*.webp). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ThumbnailsTest {
    @Test
    fun thumbnails() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val w = 336; val h = 264
        for (mode in listOf(MapState.LightMode.LIGHT, MapState.LightMode.DARK)) {
            for (theme in Theme.entries) {
                MapState.setLightMode(mode); MapState.setTheme(theme)
                MapState.zoomBy((if (theme == Theme.BLOCKY) 16.6 else 16.0) - MapState.targetZoom)
                repeat(400) { MapState.tick(); Thread.sleep(2) }
                val r = MapRenderer()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                for (i in 0 until 60) { r.draw(c, w, h, Rect(0, 0, w, h), 1.6f, hud = false); Thread.sleep(60) }
                r.draw(c, w, h, Rect(0, 0, w, h), 1.6f, hud = false)
                val tag = if (mode == MapState.LightMode.DARK) "d" else "l"
                @Suppress("DEPRECATION")
                File(out, "thumb_${theme.name.lowercase()}_$tag.webp").outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP, 82, it) }
            }
        }
    }
}
