package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Every theme with real live TomTom traffic over central Athens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TrafficPreviewTest {
    @Test
    fun liveTraffic() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val log = StringBuilder("traffic active=${Traffic.active}\n")
        for (mode in listOf(MapState.LightMode.DARK)) for (theme in listOf(Theme.OPEN_WORLD, Theme.NEON_NOIR, Theme.TRAILBLAZER, Theme.PARCHMENT_QUEST)) {
            MapState.setLightMode(mode)
            MapState.setTheme(theme)
            MapState.zoomBy(15.3 - MapState.targetZoom)
            repeat(400) { MapState.tick(); Thread.sleep(2) }
            val r = MapRenderer()
            val frame = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            val c = Canvas(frame)
            for (i in 0 until 80) {
                r.draw(c, 1080, 1920, Rect(0, 0, 1080, 1920), 2.75f, hud = false)
                Thread.sleep(80)
            }
            r.draw(c, 1080, 1920, Rect(0, 0, 1080, 1920), 2.75f, hud = false)
            val t = Traffic.tile(13, 4635, 3160)
            log.append("${theme.name}: segments=${t?.segs?.size} by state=${t?.segs?.groupingBy { it.state }?.eachCount()} oneWayJams=${t?.segs?.count { it.oneWay && it.state >= 2 }}\n")
            // title strip
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 50f; isFakeBoldText = true }
            c.drawRect(0f, 0f, 1080f, 80f, Paint().apply { color = Color.argb(200, 0, 0, 0) })
            c.drawText("${theme.title} · ${mode.label}", 24f, 58f, p)
            File(out, "live_${theme.name.lowercase()}_${mode.name.lowercase()}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        File(out, "timings.txt").writeText(log.toString())
    }
}
