package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Useful places drawn on the map as small round icons (fuel, parking, food, cafés, pharmacies,
 * supermarkets), tappable. Built to stay light: the places of each map tile are picked out once
 * and kept, icons are pre-drawn images, at most [MAX] are shown and never on top of each other.
 */
class PoiLayer {
    enum class Kind(val color: Int, val label: String, val amenity: String, val icon: String) {
        FUEL(Color.rgb(232, 89, 12), "Fuel", "fuel", "⛽"),
        PARKING(Color.rgb(31, 111, 235), "Car park", "parking", "🅿"),
        FOOD(Color.rgb(194, 37, 92), "Food", "restaurant", "🍽"),
        CAFE(Color.rgb(139, 94, 60), "Café", "cafe", "☕"),
        PHARMACY(Color.rgb(47, 158, 68), "Pharmacy", "pharmacy", "💊"),
        SHOP(Color.rgb(112, 72, 232), "Supermarket", "supermarket", "🛒"),
    }

    class Poi(val name: String, val kind: Kind, val pos: LatLng, val rank: Int) {
        /** As a destination. */
        fun place() = Place(name.ifBlank { kind.label }, kind.label, pos, kind.icon)
    }

    companion object {
        const val MIN_ZOOM = 15.5
        private const val MAX = 28

        /** Places of each data tile, picked out once ("z/x/y" → list). */
        private val perTile = object : LruCache<String, List<Poi>>(160) {}

        private fun kindOf(cls: String?, sub: String?): Kind? = when {
            cls == "fuel" -> Kind.FUEL
            cls == "parking" && sub != "bicycle_parking" && sub != "motorcycle_parking" -> Kind.PARKING
            cls == "restaurant" || cls == "fast_food" -> Kind.FOOD
            cls == "cafe" -> Kind.CAFE
            cls == "pharmacy" -> Kind.PHARMACY
            cls == "grocery" && sub == "supermarket" -> Kind.SHOP
            else -> null
        }

        fun of(z: Int, x: Int, y: Int, vt: VectorTile): List<Poi> {
            val key = "$z/$x/$y"
            perTile.get(key)?.let { return it }
            val n = (1 shl z).toDouble()
            val out = ArrayList<Poi>()
            for (f in vt.features) {
                if (f.layer != "poi" || f.type != 1) continue
                val kind = kindOf(f["class"], f["subclass"]) ?: continue
                val part = f.parts.firstOrNull() ?: continue
                if (part.size < 2) continue
                val px = part[0]; val py = part[1]
                if (px < 0 || py < 0 || px > f.extent || py > f.extent) continue
                val pos = LatLng(Geo.lat((y + py / f.extent.toDouble()) / n, 1.0), Geo.lon((x + px / f.extent.toDouble()) / n, 1.0))
                out += Poi(f["name"].orEmpty(), kind, pos, f["rank"]?.toIntOrNull() ?: 50)
            }
            out.sortBy { it.rank }
            perTile.put(key, out)
            return out
        }
    }

    // ---- icons, drawn once per kind and size
    private val icons = HashMap<String, Bitmap>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun icon(kind: Kind, sizePx: Int): Bitmap = icons.getOrPut("${kind.name}/$sizePx") {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = sizePx / 2f
        p.style = Paint.Style.FILL; p.color = Color.argb(70, 0, 0, 0)
        c.drawCircle(r, r + sizePx * 0.03f, r * 0.94f, p)
        p.color = Color.WHITE; c.drawCircle(r, r, r * 0.92f, p)
        p.color = kind.color; c.drawCircle(r, r, r * 0.76f, p)
        p.style = Paint.Style.STROKE; p.color = Color.WHITE
        p.strokeWidth = sizePx * 0.075f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val s = r * 0.42f // glyph half size
        path.reset()
        when (kind) {
            Kind.FUEL -> { // pump
                path.addRect(r - s * 0.8f, r - s, r + s * 0.3f, r + s, Path.Direction.CW)
                path.moveTo(r - s * 0.8f, r - s * 0.2f); path.lineTo(r + s * 0.3f, r - s * 0.2f)
                path.moveTo(r + s * 0.3f, r - s * 0.6f); path.lineTo(r + s * 0.9f, r - s * 0.1f); path.lineTo(r + s * 0.9f, r + s * 0.7f)
            }
            Kind.PARKING -> { // P
                path.moveTo(r - s * 0.45f, r + s); path.lineTo(r - s * 0.45f, r - s)
                path.lineTo(r + s * 0.15f, r - s)
                path.quadTo(r + s * 0.85f, r - s, r + s * 0.85f, r - s * 0.35f)
                path.quadTo(r + s * 0.85f, r + s * 0.3f, r + s * 0.15f, r + s * 0.3f)
                path.lineTo(r - s * 0.45f, r + s * 0.3f)
            }
            Kind.FOOD -> { // fork and knife
                path.moveTo(r - s * 0.5f, r - s); path.lineTo(r - s * 0.5f, r + s)
                path.moveTo(r - s * 0.85f, r - s); path.lineTo(r - s * 0.85f, r - s * 0.2f); path.lineTo(r - s * 0.15f, r - s * 0.2f); path.lineTo(r - s * 0.15f, r - s)
                path.moveTo(r + s * 0.55f, r + s); path.lineTo(r + s * 0.55f, r - s); path.quadTo(r + s * 0.1f, r - s * 0.4f, r + s * 0.55f, r)
            }
            Kind.CAFE -> { // cup
                path.moveTo(r - s * 0.85f, r - s * 0.4f); path.lineTo(r + s * 0.45f, r - s * 0.4f)
                path.lineTo(r + s * 0.35f, r + s * 0.6f); path.lineTo(r - s * 0.75f, r + s * 0.6f); path.close()
                path.moveTo(r + s * 0.45f, r - s * 0.2f); path.quadTo(r + s * 1.05f, r - s * 0.15f, r + s * 0.4f, r + s * 0.3f)
            }
            Kind.PHARMACY -> { // cross
                path.moveTo(r, r - s); path.lineTo(r, r + s); path.moveTo(r - s, r); path.lineTo(r + s, r)
            }
            Kind.SHOP -> { // basket
                path.moveTo(r - s, r - s * 0.2f); path.lineTo(r + s, r - s * 0.2f); path.lineTo(r + s * 0.7f, r + s * 0.9f)
                path.lineTo(r - s * 0.7f, r + s * 0.9f); path.close()
                path.moveTo(r - s * 0.45f, r - s * 0.2f); path.lineTo(r - s * 0.1f, r - s); path.moveTo(r + s * 0.45f, r - s * 0.2f); path.lineTo(r + s * 0.1f, r - s)
            }
        }
        c.drawPath(path, p)
        b
    }

    // ---- what was drawn, for taps (double-buffered: taps may come from another thread)
    private class Hits(val x: FloatArray = FloatArray(MAX), val y: FloatArray = FloatArray(MAX), val poi: Array<Poi?> = arrayOfNulls(MAX)) { var n = 0 }
    private var building = Hits()
    @Volatile private var shown = Hits()
    @Volatile var hitRadius = 24f
        private set

    /** The place under a screen point (generous: car screens and fingers aren't precise). */
    fun at(x: Float, y: Float): Poi? {
        val h = shown
        var best: Poi? = null; var bestD = hitRadius * hitRadius
        for (i in 0 until h.n) {
            val dx = h.x[i] - x; val dy = h.y[i] - y
            val d = dx * dx + dy * dy
            if (d <= bestD) { bestD = d; best = h.poi[i] }
        }
        return best
    }

    private var occupied = IntArray(0)
    private val icon = Paint().apply { isFilterBitmap = true }

    /**
     * Draws the icons on the (unrotated) screen. [only] limits the kinds (while navigating:
     * fuel and parking); [selected] gets a ring.
     */
    fun draw(c: Canvas, cam: MapRenderer.Camera, zoom: Double, w: Int, h: Int, d: Float, only: Set<Kind>?, selected: LatLng?) {
        val out = building
        out.n = 0
        if (zoom < MIN_ZOOM) { publish(); return }
        val size = (30 * d).toInt()
        val half = size / 2f
        hitRadius = 26 * d
        // a coarse grid so icons never overlap
        val cell = size * 1.15f
        val gw = (w / cell).toInt() + 2; val gh = (h / cell).toInt() + 2
        if (occupied.size < gw * gh) occupied = IntArray(gw * gh)
        java.util.Arrays.fill(occupied, 0, gw * gh, 0)

        val dz = VectorSource.MAX_Z
        val nz = 1 shl dz
        // data tiles around the camera centre
        val worldDz = TileCache.tilePx * Math.pow(2.0, dz.toDouble())
        val k = worldDz / cam.world
        val cxw = cam.px * k; val cyw = cam.py * k
        val reach = Math.hypot(w.toDouble(), h.toDouble()) * 0.6 / cam.scale * k
        val t0x = floor((cxw - reach) / TileCache.tilePx).toInt(); val t1x = floor((cxw + reach) / TileCache.tilePx).toInt()
        val t0y = floor((cyw - reach) / TileCache.tilePx).toInt().coerceAtLeast(0); val t1y = floor((cyw + reach) / TileCache.tilePx).toInt().coerceAtMost(nz - 1)
        if ((t1x - t0x + 1) * (t1y - t0y + 1) > 30) { publish(); return }
        val a = Math.toRadians(-cam.bearing.toDouble())
        val ca = cos(a); val sa = sin(a)
        var ringX = Float.NaN; var ringY = 0f; var ringKind: Kind? = null
        for (ty in t0y..t1y) for (tx in t0x..t1x) {
            val vt = VectorSource.peek(dz, tx.mod(nz), ty) ?: continue
            for (poi in of(dz, tx.mod(nz), ty, vt)) {
                if (out.n >= MAX) break
                if (only != null && poi.kind !in only) continue
                // food, cafés and shops only close in: the busy centre stays readable
                if (zoom < 17.0 && (poi.kind == Kind.FOOD || poi.kind == Kind.CAFE || poi.kind == Kind.SHOP)) continue
                val wx = (Geo.x(poi.pos.lon, cam.world) - cam.px) * cam.scale
                val wy = (Geo.y(poi.pos.lat, cam.world) - cam.py) * cam.scale
                val sx = (cam.cx + wx * ca - wy * sa).toFloat()
                val sy = (cam.cy + wx * sa + wy * ca).toFloat()
                if (sx < -half || sy < -half || sx > w + half || sy > h + half) continue
                val gx = ((sx + cell) / cell).toInt(); val gy = ((sy + cell) / cell).toInt()
                if (gx !in 0 until gw || gy !in 0 until gh || occupied[gy * gw + gx] != 0) continue
                occupied[gy * gw + gx] = 1
                c.drawBitmap(icon(poi.kind, size), sx - half, sy - half, icon)
                out.x[out.n] = sx; out.y[out.n] = sy; out.poi[out.n] = poi; out.n++
                if (selected != null && Geo.distance(selected, poi.pos) < 3) { ringX = sx; ringY = sy; ringKind = poi.kind }
            }
        }
        if (!ringX.isNaN()) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = ringKind!!.color
            c.drawCircle(ringX, ringY, half + 6 * d, p)
        }
        publish()
    }

    private fun publish() {
        val done = building
        building = shown
        shown = done
    }
}
