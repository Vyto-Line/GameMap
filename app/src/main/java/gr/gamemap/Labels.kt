package gr.gamemap

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Street and place names, laid out on screen every frame: constant size whatever the zoom,
 * always upright, never overlapping, and each street named once in a neighbourhood.
 */
class Labels {

    private class Cand(
        val name: String,
        /** Points in world pixels at zoom [z]. */
        val pts: DoubleArray,
        val rank: Int,
        val place: Boolean,
        val textSize: Float,
        val minX: Double, val minY: Double, val maxX: Double, val maxY: Double,
    )

    private var cacheKey = ""
    private var cands: List<Cand> = emptyList()

    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val placed = ArrayList<RectF>(64)
    private val placedNames = ArrayList<Pair<String, FloatArray>>(64)
    private val pm = PathMeasure()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private val screen = FloatArray(512)

    fun draw(c: Canvas, cam: MapRenderer.Camera, z: Int, area: Rect, d: Float, theme: Theme) {
        val st = theme.style
        if (!st.labels || theme.pixel > 1) return
        val tilePx = TileCache.tilePx
        val dz = minOf(z, VectorSource.MAX_Z)

        // data tiles under the view
        val reach = hypot(area.width().toDouble(), area.height().toDouble()) / cam.scale
        val dataPx = tilePx * 2.0.pow(z - dz) // one data tile, in world pixels at zoom z
        val ax0 = ((cam.px - reach) / dataPx).toInt(); val ax1 = ((cam.px + reach) / dataPx).toInt()
        val ay0 = ((cam.py - reach) / dataPx).toInt(); val ay1 = ((cam.py + reach) / dataPx).toInt()
        val tiles = ArrayList<Triple<Int, Int, VectorTile>>()
        val keyB = StringBuilder("$z/")
        for (ay in ay0..ay1) for (ax in ax0..ax1) {
            val vt = VectorSource.peek(dz, ax, ay) ?: continue
            tiles += Triple(ax, ay, vt); keyB.append(ax).append(',').append(ay).append(';')
        }
        val key = keyB.toString()
        if (key != cacheKey) { cacheKey = key; cands = collect(tiles, z, dz, dataPx) }

        text.typeface = if (theme.serif) Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        else Typeface.create("sans-serif-condensed", Typeface.BOLD)
        text.color = st.label
        halo.typeface = text.typeface
        halo.color = st.labelHalo
        halo.strokeWidth = 3.5f * d

        placed.clear(); placedNames.clear()
        val a = Math.toRadians(-cam.bearing.toDouble())
        val ca = cos(a); val sa = sin(a)
        // world-pixel box of the screen, for quick rejection
        val vMinX = cam.px - reach; val vMaxX = cam.px + reach; val vMinY = cam.py - reach; val vMaxY = cam.py + reach
        var count = 0

        for (cd in cands) {
            if (count >= 45) break
            if (cd.maxX < vMinX || cd.minX > vMaxX || cd.maxY < vMinY || cd.minY > vMaxY) continue
            val n = minOf(cd.pts.size, screen.size)
            var i = 0
            while (i < n) {
                val wx = (cd.pts[i] - cam.px) * cam.scale; val wy = (cd.pts[i + 1] - cam.py) * cam.scale
                screen[i] = (cam.cx + wx * ca - wy * sa).toFloat()
                screen[i + 1] = (cam.cy + wx * sa + wy * ca).toFloat()
                i += 2
            }
            text.textSize = cd.textSize * d; halo.textSize = text.textSize
            if (cd.place) {
                if (placePoint(c, cd, area, d)) count++
            } else {
                if (placeStreet(c, cd, n, area, d)) count++
            }
        }
    }

    private fun placePoint(c: Canvas, cd: Cand, area: Rect, d: Float): Boolean {
        val x = screen[0]; val y = screen[1]
        text.textAlign = Paint.Align.CENTER; halo.textAlign = Paint.Align.CENTER
        val w = text.measureText(cd.name) / 2 + 4 * d
        val box = RectF(x - w, y - text.textSize, x + w, y + text.textSize * 0.35f)
        if (!inside(box, area) || !free(box)) return false
        c.drawText(cd.name, x, y, halo)
        c.drawText(cd.name, x, y, text)
        return true
    }

    private fun placeStreet(c: Canvas, cd: Cand, n: Int, area: Rect, d: Float): Boolean {
        // screen length
        var len = 0f
        var i = 2
        while (i < n) { len += hypot(screen[i] - screen[i - 2], screen[i + 1] - screen[i - 1]); i += 2 }
        val tw = text.measureText(cd.name)
        if (len < tw * 1.15f) return false
        // read left to right
        val reverse = screen[n - 2] < screen[0]
        val p = Path()
        if (reverse) {
            p.moveTo(screen[n - 2], screen[n - 1]); i = n - 4
            while (i >= 0) { p.lineTo(screen[i], screen[i + 1]); i -= 2 }
        } else {
            p.moveTo(screen[0], screen[1]); i = 2
            while (i < n) { p.lineTo(screen[i], screen[i + 1]); i += 2 }
        }
        pm.setPath(p, false)
        val start = (len - tw) / 2
        pm.getPosTan(len / 2, pos, tan)
        // skip labels that would be steeply curved or near-vertical upside down
        if (tan[0] < -0.05f) return false
        val mx = pos[0]; val my = pos[1]
        // same street already named close by?
        for ((nm, at) in placedNames) if (nm == cd.name && hypot(at[0] - mx, at[1] - my) < 260 * d) return false
        pm.getPosTan(start, pos, null); val x0 = pos[0]; val y0 = pos[1]
        pm.getPosTan(start + tw, pos, null); val x1 = pos[0]; val y1 = pos[1]
        val h = text.textSize * 0.7f
        val box = RectF(minOf(x0, x1, mx) - h, minOf(y0, y1, my) - h, maxOf(x0, x1, mx) + h, maxOf(y0, y1, my) + h)
        if (!inside(box, area) || !free(box)) return false
        placedNames += cd.name to floatArrayOf(mx, my)
        text.textAlign = Paint.Align.LEFT; halo.textAlign = Paint.Align.LEFT
        val v = text.textSize * 0.35f
        c.drawTextOnPath(cd.name, p, start, v, halo)
        c.drawTextOnPath(cd.name, p, start, v, text)
        return true
    }

    private fun inside(r: RectF, a: Rect) = r.left >= a.left && r.right <= a.right && r.top >= a.top && r.bottom <= a.bottom

    private fun free(r: RectF): Boolean {
        for (o in placed) if (RectF.intersects(o, r)) return false
        placed += r
        return true
    }

    /** Gathers names from the loaded data tiles, most important first. */
    private fun collect(tiles: List<Triple<Int, Int, VectorTile>>, z: Int, dz: Int, dataPx: Double): List<Cand> {
        val out = ArrayList<Cand>()
        for ((ax, ay, vt) in tiles) {
            for (f in vt.features) {
                val name = f["name"] ?: continue
                val k = dataPx / f.extent
                fun world(part: FloatArray): DoubleArray {
                    val r = DoubleArray(part.size)
                    var i = 0
                    while (i < part.size) { r[i] = ax * dataPx + part[i] * k; r[i + 1] = ay * dataPx + part[i + 1] * k; i += 2 }
                    return r
                }
                if (f.layer == "place" && f.type == 1) {
                    val (rank, size, minZ, maxZ) = when (f["class"]) {
                        "city" -> Quad(0, 17f, 4, 15)
                        "town" -> Quad(1, 15f, 9, 16)
                        "village" -> Quad(2, 13.5f, 12, 17)
                        "suburb" -> Quad(2, 13.5f, 12, 16)
                        "quarter", "neighbourhood" -> Quad(3, 12f, 14, 18)
                        else -> continue
                    }
                    if (z < minZ || z > maxZ) continue
                    val pt = f.parts.firstOrNull() ?: continue
                    if (pt[0] < 0 || pt[1] < 0 || pt[0] > f.extent || pt[1] > f.extent) continue // buffer copy
                    val w = world(pt)
                    val upper = if (rank <= 1) name.uppercase() else name
                    out += Cand(upper, w, rank, true, size, w[0], w[1], w[0], w[1])
                } else if (f.layer == "transportation_name" && f.type == 2 && z >= 15) {
                    val rank = when (f["class"]) {
                        "motorway", "trunk" -> 4; "primary" -> 5; "secondary" -> 6; "tertiary" -> 7
                        "minor" -> 8; "service" -> if (z >= 16) 9 else continue
                        "path" -> if (z >= 17) 10 else continue
                        else -> continue
                    }
                    val part = f.parts.firstOrNull() ?: continue
                    if (part.size < 4) continue
                    val w = world(part)
                    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
                    var i = 0
                    while (i < w.size) {
                        minX = minOf(minX, w[i]); maxX = maxOf(maxX, w[i]); minY = minOf(minY, w[i + 1]); maxY = maxOf(maxY, w[i + 1]); i += 2
                    }
                    out += Cand(name, w, rank, false, 12.5f, minX, minY, maxX, maxY)
                }
            }
        }
        // important first; among streets, longer ones first
        return out.sortedWith(compareBy<Cand> { it.rank }.thenByDescending { (it.maxX - it.minX) + (it.maxY - it.minY) })
    }

    private data class Quad(val a: Int, val b: Float, val c: Int, val d: Int)
}
