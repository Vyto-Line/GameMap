package gr.gamemap

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Draws one map tile for a theme from OpenFreeMap vector data: land use, water, buildings,
 * roads at roughly real widths (with casings or neon glow), and street and place names.
 */
object VectorRenderer {

    private const val EARTH = 40_075_016.686

    /** Road classes: real width in metres, thinnest width in pixels, first zoom shown. */
    private class RoadKind(val meters: Float, val minPx: Float, val minZoom: Int)

    private val kinds = mapOf(
        "motorway" to RoadKind(22f, 2.6f, 5), "trunk" to RoadKind(20f, 2.4f, 6),
        "primary" to RoadKind(16f, 2.0f, 8), "secondary" to RoadKind(14f, 1.7f, 10),
        "tertiary" to RoadKind(12f, 1.5f, 11), "minor" to RoadKind(9f, 1.1f, 13),
        "service" to RoadKind(6f, 0.8f, 14), "track" to RoadKind(4f, 0.8f, 14),
        "path" to RoadKind(3f, 0.8f, 15), "rail" to RoadKind(4f, 1.0f, 12), "transit" to RoadKind(4f, 1.0f, 13),
    )
    private val drawOrder = listOf("path", "track", "rail", "transit", "service", "minor", "tertiary", "secondary", "primary", "trunk", "motorway")

    fun render(theme: Theme, z: Int, x: Int, y: Int, size: Int): Bitmap? {
        val dz = min(z, VectorSource.MAX_Z)
        val sub = z - dz
        val ax = x shr sub; val ay = y shr sub
        val vt = VectorSource.get(dz, ax, ay) ?: return null
        val s = theme.style
        val out = size / theme.pixel
        val aa = theme.pixel == 1

        val bmp = Bitmap.createBitmap(out, out, if (aa) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(s.land)

        // Part of the data tile this map tile covers, in data-tile units.
        val extent = vt.features.firstOrNull()?.extent ?: 4096
        val span = extent.toFloat() / (1 shl sub)
        val ox = (x - (ax shl sub)) * span; val oy = (y - (ay shl sub)) * span
        val k = out / span // output pixels per data unit
        c.scale(k, k)
        c.translate(-ox, -oy)
        val margin = span * 0.1f
        val l = ox - margin; val t = oy - margin; val r = ox + span + margin; val b = oy + span + margin

        // metres per output pixel at this tile's latitude
        val worldTiles = 1 shl z
        val lat = Geo.lat((y + 0.5) * 256.0, 256.0 * worldTiles)
        val mpp = (EARTH * cos(lat * PI / 180) / worldTiles / out).toFloat()

        val fill = Paint().apply { isAntiAlias = aa; style = Paint.Style.FILL }
        val line = Paint().apply {
            isAntiAlias = aa; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        val byLayer = vt.features.groupBy { it.layer }
        fun layer(n: String) = (byLayer[n] ?: emptyList()).filter { it.intersects(l, t, r, b) }

        fun area(f: VectorTile.Feature, color: Int) {
            if (f.type != 3 || color == 0) return
            fill.color = color
            c.drawPath(pathOf(f), fill)
        }

        fun px(width: Float) = width / k // output pixels → data units

        // ---- ground cover
        for (f in layer("landcover")) area(f, when (f["class"]) {
            "wood" -> s.wood
            "grass", "wetland" -> s.park
            "sand", "beach" -> s.sand
            "farmland" -> s.farm
            "rock", "ice" -> s.urban
            else -> 0
        })
        for (f in layer("landuse")) area(f, when (f["class"]) {
            "commercial", "retail", "industrial", "railway", "garages", "military", "quarry" -> s.urban
            "pitch", "stadium", "track", "playground" -> s.pitch
            "cemetery" -> s.park
            else -> 0
        })
        for (f in layer("park")) area(f, s.park)

        // ---- water
        val water = layer("water")
        for (f in water) area(f, s.water)
        s.waterEdge?.let { edge ->
            if (z >= 12) {
                line.color = edge; line.strokeWidth = px(if (aa) 1.2f else 1f); line.alpha = 170
                for (f in water) if (f.type == 3) c.drawPath(pathOf(f), line)
                line.alpha = 255
            }
        }
        for (f in layer("waterway")) if (f.type == 2) {
            val m = when (f["class"]) { "river" -> 14f; "canal" -> 10f; else -> 3f }
            if (f["class"] != "river" && z < 13) continue
            line.color = s.water
            line.strokeWidth = px(max(if (f["class"] == "river") 1.5f else 0.8f, m / mpp))
            c.drawPath(pathOf(f), line)
        }

        // ---- buildings
        if (z >= 14) {
            val buildings = layer("building")
            for (f in buildings) area(f, s.building)
            s.buildingEdge?.let { edge ->
                if (z >= 15) {
                    line.color = edge; line.strokeWidth = px(if (z >= 17) 1.4f else 0.9f)
                    for (f in buildings) if (f.type == 3) c.drawPath(pathOf(f), line)
                }
            }
        }

        // ---- roads
        val roads = layer("transportation").filter { it["brunnel"] != "tunnel" && it.type == 2 }
        for (f in layer("transportation")) if (f.type == 3 && z >= 15) area(f, s.minor) // pedestrian squares
        fun roadColor(cls: String) = when (cls) {
            "motorway", "trunk" -> s.motorway
            "primary", "secondary", "tertiary" -> s.major
            "minor", "service" -> s.minor
            "rail", "transit" -> s.rail
            else -> s.path
        }
        fun width(kind: RoadKind) = max(kind.minPx, kind.meters * 0.85f / mpp)
        val visible = drawOrder.mapNotNull { cls -> kinds[cls]?.takeIf { z >= it.minZoom }?.let { cls to it } }

        // glow or casing underneath everything, so junctions stay clean
        if (s.glow && aa) {
            line.maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
            for ((cls, kind) in visible) {
                if (cls == "path" || cls == "track" || cls == "service") continue
                line.color = roadColor(cls); line.alpha = if (z >= 17) 110 else 70
                line.strokeWidth = px(width(kind) * 1.25f + 4f)
                for (f in roads) if (f["class"] == cls) c.drawPath(pathOf(f), line)
            }
            line.maskFilter = null; line.alpha = 255
        }
        s.casing?.let { cas ->
            line.color = cas
            for ((cls, kind) in visible) {
                if (cls == "rail" || cls == "transit" || cls == "path" || cls == "track") continue
                line.strokeWidth = px(width(kind) + 2.2f)
                for (f in roads) if (f["class"] == cls) c.drawPath(pathOf(f), line)
            }
        }
        for ((cls, kind) in visible) {
            line.color = roadColor(cls)
            line.strokeWidth = px(width(kind))
            val dashed = cls == "path" || cls == "track" || ((cls == "rail" || cls == "transit") && s.casing != null)
            line.pathEffect = if (dashed && aa) android.graphics.DashPathEffect(floatArrayOf(px(4f), px(3f)), 0f) else null
            for (f in roads) if (f["class"] == cls) c.drawPath(pathOf(f), line)
        }
        line.pathEffect = null

        // ---- names
        if (s.labels && aa) drawLabels(c, vt, theme, z, k, ox, oy, span)
        return bmp
    }

    private fun pathOf(f: VectorTile.Feature): Path {
        f.path?.let { return it }
        val p = Path()
        if (f.type == 3) p.fillType = Path.FillType.EVEN_ODD
        for (part in f.parts) {
            if (part.size < 4) continue
            p.moveTo(part[0], part[1])
            var i = 2
            while (i < part.size) { p.lineTo(part[i], part[i + 1]); i += 2 }
            if (f.type == 3) p.close()
        }
        f.path = p
        return p
    }

    // ---------------------------------------------------------------- labels

    private fun drawLabels(c: Canvas, vt: VectorTile, theme: Theme, z: Int, k: Float, ox: Float, oy: Float, span: Float) {
        val s = theme.style
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (theme.serif) android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC)
            else android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            color = s.label
        }
        val halo = Paint(text).apply {
            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
            color = s.labelHalo; strokeWidth = 3.2f / k
        }
        val taken = ArrayList<RectF>()
        fun free(rf: RectF): Boolean {
            for (o in taken) if (RectF.intersects(o, rf)) return false
            taken += rf
            return true
        }
        val inner = RectF(ox, oy, ox + span, oy + span)

        // place names
        for (f in vt.features) {
            if (f.layer != "place" || f.type != 1) continue
            val name = f["name"] ?: continue
            val cls = f["class"] ?: continue
            val (size, minZ, maxZ) = when (cls) {
                "city" -> Triple(34f, 4, 14)
                "town" -> Triple(28f, 9, 15)
                "village" -> Triple(24f, 12, 16)
                "suburb" -> Triple(24f, 12, 15)
                "quarter", "neighbourhood" -> Triple(20f, 14, 17)
                else -> continue
            }
            if (z < minZ || z > maxZ) continue
            val pt = f.parts.firstOrNull() ?: continue
            if (!inner.contains(pt[0], pt[1])) continue
            val label = if (cls == "city" || cls == "town") name.uppercase() else name
            text.textSize = size / k; halo.textSize = text.textSize
            text.textAlign = Paint.Align.CENTER; halo.textAlign = Paint.Align.CENTER
            val w = text.measureText(label) / 2
            val box = RectF(pt[0] - w, pt[1] - text.textSize, pt[0] + w, pt[1] + text.textSize * 0.3f)
            if (box.left < ox || box.right > ox + span || box.top < oy || box.bottom > oy + span) continue
            if (!free(box)) continue
            c.drawText(label, pt[0], pt[1], halo)
            c.drawText(label, pt[0], pt[1], text)
        }

        // street names, along the street
        if (z < 15) return
        text.textAlign = Paint.Align.LEFT; halo.textAlign = Paint.Align.LEFT
        text.textSize = 19f / k; halo.textSize = text.textSize
        val pm = PathMeasure()
        val pos = FloatArray(2)
        for (f in vt.features) {
            if (f.layer != "transportation_name" || f.type != 2) continue
            val name = f["name"] ?: continue
            if (f["class"] == "path" && z < 17) continue
            if (!f.intersects(ox, oy, ox + span, oy + span)) continue
            val part = f.parts.maxByOrNull { it.size } ?: continue
            if (part.size < 4) continue
            // read left to right
            val pts = if (part[part.size - 2] < part[0]) reversed(part) else part
            val p = Path().apply {
                moveTo(pts[0], pts[1]); var i = 2
                while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
            }
            pm.setPath(p, false)
            val len = pm.length
            val tw = text.measureText(name)
            if (len < tw * 1.3f) continue
            pm.getPosTan(len / 2, pos, null)
            val box = RectF(pos[0] - tw / 2, pos[1] - tw / 2, pos[0] + tw / 2, pos[1] + tw / 2)
            if (box.left < ox || box.right > ox + span || box.top < oy || box.bottom > oy + span) continue
            if (!free(box)) continue
            val off = (len - tw) / 2
            val v = text.textSize * 0.35f
            c.drawTextOnPath(name, p, off, v, halo)
            c.drawTextOnPath(name, p, off, v, text)
        }
    }

    private fun reversed(p: FloatArray): FloatArray {
        val out = FloatArray(p.size)
        var i = 0
        while (i < p.size) { out[p.size - 2 - i] = p[i]; out[p.size - 1 - i] = p[i + 1]; i += 2 }
        return out
    }

    @Suppress("unused")
    private val unused = Color.TRANSPARENT
}
