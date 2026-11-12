package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Mockups of traffic display styles, drawn over the real map with simulated traffic. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TrafficMockTest {
    @Test
    fun mockups() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val w = 1080; val h = 1920; val d = 2.75f
        for (theme in listOf(Theme.OPEN_WORLD, Theme.NEON_NOIR, Theme.PARCHMENT_QUEST, Theme.TRAILBLAZER)) {
            MapState.setTheme(theme)
            MapState.zoomBy(16.4 - MapState.targetZoom)
            repeat(400) { MapState.tick(); Thread.sleep(2) }
            val r = MapRenderer()
            val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c0 = Canvas(base)
            for (i in 0 until 60) { r.draw(c0, w, h, Rect(0, 160, w, h), d, hud = false); Thread.sleep(60) }
            r.draw(c0, w, h, Rect(0, 160, w, h), d, hud = false)
            val cam = r.camera!!
            val roads = segments(cam, 16)
            for (style in listOf("A", "B", "C")) {
                val frame = base.copy(Bitmap.Config.ARGB_8888, true)
                val c = Canvas(frame)
                drawTraffic(c, roads, theme, style, d, cam.scale)
                label(c, theme, style, d)
                File(out, "traffic_${style}_${theme.name.lowercase()}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 90, it) }
            }
        }
    }

    private class Seg(val pts: FloatArray, val state: Int, val width: Float) // 0 free, 1 slow, 2 jam

    private fun segments(cam: MapRenderer.Camera, z: Int): List<Seg> {
        val dz = 14; val tilePx = TileCache.tilePx
        val dataPx = tilePx * 2.0.pow(z - dz)
        val a = Math.toRadians(-cam.bearing.toDouble()); val ca = cos(a); val sa = sin(a)
        val out = ArrayList<Seg>()
        var n = 0
        for (ay in 6320..6322) for (ax in 9270..9272) {
            val vt = VectorSource.peek(dz, ax, ay) ?: continue
            for (f in vt.features) {
                if (f.layer != "transportation" || f.type != 2) continue
                val width = when (f["class"]) { "motorway", "trunk" -> 7f; "primary" -> 6f; "secondary" -> 5.5f; "tertiary" -> 5f; else -> continue }
                val k = dataPx / f.extent
                for (part in f.parts) {
                    val p = FloatArray(part.size)
                    var i = 0
                    while (i < part.size) {
                        val wx = (ax * dataPx + part[i] * k - cam.px) * cam.scale
                        val wy = (ay * dataPx + part[i + 1] * k - cam.py) * cam.scale
                        p[i] = (cam.cx + wx * ca - wy * sa).toFloat(); p[i + 1] = (cam.cy + wx * sa + wy * ca).toFloat()
                        i += 2
                    }
                    val hsh = ((part[0].toInt() * 73 + part[1].toInt() * 31 + n++ / 3) and 0x7fffffff) % 100
                    out += Seg(p, if (hsh < 55) 0 else if (hsh < 82) 1 else 2, width)
                }
            }
        }
        return out
    }

    private fun path(p: FloatArray) = Path().apply {
        moveTo(p[0], p[1]); var i = 2
        while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
    }

    private fun drawTraffic(c: Canvas, segs: List<Seg>, theme: Theme, mode: String, d: Float, scale: Float) {
        val st = theme.style
        val colors = intArrayOf(st.trafficFree, st.trafficSlow, st.trafficJam)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        when (mode) {
            "A" -> for (s in segs) {
                val p = path(s.pts)
                line.color = Color.argb(150, 0, 0, 0); line.strokeWidth = 5.5f * d; c.drawPath(p, line)
                line.color = colors[s.state]; line.strokeWidth = 3.5f * d; c.drawPath(p, line)
            }
            "B" -> for (s in segs) {
                if (s.state == 0) continue
                val p = path(s.pts)
                line.color = colors[s.state]; line.strokeWidth = s.width * d * 1.05f; c.drawPath(p, line)
            }
            "C" -> {
                for (s in segs) {
                    val p = path(s.pts)
                    if (s.state == 0) {
                        line.color = colors[0]; line.alpha = 90; line.strokeWidth = 2.2f * d; c.drawPath(p, line)
                        continue
                    }
                    line.color = colors[s.state]
                    line.alpha = 60; line.strokeWidth = 13f * d; c.drawPath(p, line)
                    line.alpha = 110; line.strokeWidth = 8f * d; c.drawPath(p, line)
                    line.alpha = 255; line.strokeWidth = 3.5f * d; c.drawPath(p, line)
                    if (s.state == 2) chevrons(c, p, d)
                }
            }
        }
    }

    /** Small arrow markers along jammed stretches. */
    private fun chevrons(c: Canvas, p: Path, d: Float) {
        val pm = PathMeasure(p, false)
        val pos = FloatArray(2); val tan = FloatArray(2)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.8f * d; strokeCap = Paint.Cap.ROUND }
        var at = 18f * d
        while (at < pm.length) {
            pm.getPosTan(at, pos, tan)
            val ang = Math.toDegrees(atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat()
            c.save(); c.rotate(ang, pos[0], pos[1])
            val s = 3.2f * d
            c.drawLine(pos[0] - s, pos[1] - s, pos[0], pos[1], paint)
            c.drawLine(pos[0] - s, pos[1] + s, pos[0], pos[1], paint)
            c.restore()
            at += 30f * d
        }
    }

    private fun label(c: Canvas, theme: Theme, mode: String, d: Float) {
        val title = mapOf("A" to "A · Lines", "B" to "B · Painted roads", "C" to "C · Game style")[mode]!!
        val bg = Paint().apply { color = Color.argb(215, 0, 0, 0) }
        c.drawRect(0f, 0f, 1080f, 160f, bg)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f * d; isFakeBoldText = true }
        c.drawText(title, 16f * d, 32f * d, t)
        t.textSize = 13f * d; t.isFakeBoldText = false
        c.drawText(theme.title, 16f * d, 52f * d, t)
        // legend
        val st = theme.style
        var x = 1080f - 16f * d
        for ((col, name) in listOf(st.trafficJam to "Jam", st.trafficSlow to "Slow", st.trafficFree to "Free").reversed().asReversed()) {
            val tw = t.measureText(name)
            x -= tw
            c.drawText(name, x, 52f * d, t)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
            c.drawCircle(x - 9f * d, 47f * d, 5f * d, p)
            x -= 24f * d
        }
    }
}
