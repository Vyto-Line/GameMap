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
        val tiles = mapOf("athens" to intArrayOf(9272, 6322), "coast" to intArrayOf(9268, 6324))
        for ((name, t) in tiles) {
            val f = File(data, "$name.pbf")
            if (f.exists()) VectorSource.put(14, t[0], t[1], VectorTile(f.readBytes()))
        }
        // a 2x2 block of zoom-16 tiles in central Athens, and one at zoom 17, per theme
        for (theme in Theme.entries) {
            for ((z, x0, y0) in listOf(Triple(16, 37089, 25289), Triple(17, 74179, 50579), Triple(15, 18536, 12649))) {
                val sheet = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
                val c = Canvas(sheet)
                for (dy in 0..1) for (dx in 0..1) {
                    val bmp = if (theme == Theme.BLOCKY) BlockWorld.tile(z, x0 + dx, y0 + dy, 512)
                    else VectorRenderer.render(theme, z, x0 + dx, y0 + dy, 512)
                    if (bmp != null) c.drawBitmap(bmp, null, Rect(dx * 512, dy * 512, dx * 512 + 512, dy * 512 + 512), Paint().apply { isFilterBitmap = theme.pixel == 1 })
                }
                File(out, "${theme.name.lowercase()}_z$z.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
