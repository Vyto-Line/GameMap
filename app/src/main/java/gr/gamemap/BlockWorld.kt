package gr.gamemap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * The "Blocky" world: real OpenStreetMap buildings, roads, water and land use (from the
 * Overpass API), turned into a grid of textured blocks about 3.8 m wide.
 *
 * Data is fetched once per zoom-15 area (~1 km square), rasterised into a fine class grid,
 * and every map tile at zoom 15+ samples that grid one block at a time.
 */
object BlockWorld {
    private const val AREA_Z = 15
    private const val CELLS = 1024          // class grid per area (≈1 m per cell)
    private const val CELLS_PER_BLOCK = 4
    private const val BLOCKS = CELLS / CELLS_PER_BLOCK

    // materials (stored in the red channel of the class grid)
    private const val GRASS = 1
    private const val LEAVES = 2
    private const val WATER = 3
    private const val SAND = 4
    private const val STONE = 5
    private const val STONE_BRICKS = 6
    private const val GRAVEL = 7
    private const val DIRT_PATH = 8
    private const val SMOOTH_STONE = 9
    private const val COBBLE = 10
    private const val FARMLAND = 11
    private const val BUILDING = 12
    private const val COAST = 13
    private const val PITCH = 14

    private lateinit var dir: File
    private val grids = object : LruCache<String, IntArray>(6) {}
    private val locks = ConcurrentHashMap<String, Any>()

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "blocky")
    }

    /** A themed tile for zoom >= 15, or null if the area's data can't be loaded. */
    fun tile(z: Int, x: Int, y: Int, size: Int): Bitmap? {
        if (z < AREA_Z) return null
        val sub = z - AREA_Z
        val ax = x shr sub; val ay = y shr sub
        val grid = grid(ax, ay) ?: return null
        val blocksPerTile = BLOCKS shr sub
        val offX = (x - (ax shl sub)) * blocksPerTile
        val offY = (y - (ay shl sub)) * blocksPerTile
        val s = maxOf(1, size / blocksPerTile)
        val out = IntArray(s * blocksPerTile * s * blocksPerTile)
        val w = s * blocksPerTile
        for (by in 0 until blocksPerTile) for (bx in 0 until blocksPerTile) {
            val gx = offX + bx; val gy = offY + by
            val cls = sample(grid, gx, gy)
            val mat = Color.red(cls); val variant = Color.green(cls)
            val north = Color.red(sample(grid, gx, maxOf(0, gy - 1)))
            // Map-style shading: higher than the block to the north = lit, lower = in shadow.
            val shade = when {
                height(mat) > height(north) -> 1.10f
                height(mat) < height(north) -> 0.72f
                else -> 1f
            }
            val globalX = x.toLong() * blocksPerTile + bx; val globalY = y.toLong() * blocksPerTile + by
            val jitter = 0.95f + (hash(globalX, globalY, z.toLong()) % 100) / 1000f
            val tex = texture(mat, variant)
            for (v in 0 until s) for (u in 0 until s) {
                val c = if (s >= 4) tex[(v * 16 / s) * 16 + (u * 16 / s)] else tex[8 * 16 + 8]
                out[(by * s + v) * w + bx * s + u] = scale(c, shade * jitter)
            }
        }
        return Bitmap.createBitmap(out, w, w, Bitmap.Config.ARGB_8888)
    }

    private fun sample(grid: IntArray, bx: Int, by: Int): Int {
        val cx = (bx * CELLS_PER_BLOCK + CELLS_PER_BLOCK / 2).coerceIn(0, CELLS - 1)
        val cy = (by * CELLS_PER_BLOCK + CELLS_PER_BLOCK / 2).coerceIn(0, CELLS - 1)
        return grid[cy * CELLS + cx]
    }

    private fun height(m: Int) = when (m) {
        BUILDING -> 3
        LEAVES -> 1
        WATER -> -1
        else -> 0
    }

    // ---------------------------------------------------------------- data

    private fun grid(ax: Int, ay: Int): IntArray? {
        val key = "$ax/$ay"
        grids.get(key)?.let { return it }
        synchronized(locks.getOrPut(key) { Any() }) {
            grids.get(key)?.let { return it }
            val json = load(ax, ay) ?: return null
            val g = try { rasterise(JSONObject(json), ax, ay) } catch (e: Exception) { return null }
            grids.put(key, g)
            return g
        }
    }

    private fun load(ax: Int, ay: Int): String? {
        val file = File(dir, "$ax/$ay.json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 30L * 86_400_000) {
            return file.readText()
        }
        val world = 256.0 * (1 shl AREA_Z)
        val w = Geo.lon(ax * 256.0, world); val e = Geo.lon((ax + 1) * 256.0, world)
        val n = Geo.lat(ay * 256.0, world); val s = Geo.lat((ay + 1) * 256.0, world)
        val q = "[out:json][timeout:25][bbox:$s,$w,$n,$e];(" +
            "way[building];way[highway];way[waterway];way[natural];way[landuse];way[leisure];" +
            "way[amenity=parking];relation[natural=water];relation[landuse];relation[leisure];relation[building];" +
            ");out geom qt;"
        for (endpoint in listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")) {
            try {
                val c = URL(endpoint).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 10000
                c.readTimeout = 40000
                c.setRequestProperty("User-Agent", "GameMap/1.0 (Android; personal use)")
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                try {
                    if (c.responseCode != 200) continue
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    file.parentFile?.mkdirs()
                    file.writeText(body)
                    return body
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    // ---------------------------------------------------------------- rasterising

    private class Shape(val pts: FloatArray, val closed: Boolean)

    private fun rasterise(root: JSONObject, ax: Int, ay: Int): IntArray {
        val bmp = Bitmap.createBitmap(CELLS, CELLS, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint().apply { isAntiAlias = false; isDither = false; strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER }
        val world = 256.0 * (1 shl AREA_Z)
        val ox = ax * 256.0; val oy = ay * 256.0
        val k = CELLS / 256.0

        fun shape(geom: JSONArray): Shape {
            val pts = FloatArray(geom.length() * 2)
            for (i in 0 until geom.length()) {
                val p = geom.getJSONObject(i)
                pts[i * 2] = ((Geo.x(p.getDouble("lon"), world) - ox) * k).toFloat()
                pts[i * 2 + 1] = ((Geo.y(p.getDouble("lat"), world) - oy) * k).toFloat()
            }
            val closed = pts.size >= 6 && pts[0] == pts[pts.size - 2] && pts[1] == pts[pts.size - 1]
            return Shape(pts, closed)
        }

        fun path(s: Shape) = Path().apply {
            moveTo(s.pts[0], s.pts[1])
            var i = 2
            while (i < s.pts.size) { lineTo(s.pts[i], s.pts[i + 1]); i += 2 }
            if (s.closed) close()
        }

        fun fillArea(s: Shape, mat: Int, variant: Int = 0) {
            if (s.pts.size < 6) return
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(mat, variant, 0)
            c.drawPath(path(s), paint)
        }

        fun line(s: Shape, mat: Int, width: Float) {
            if (s.pts.size < 4) return
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width
            paint.color = Color.rgb(mat, 0, 0)
            c.drawPath(path(s).apply { if (s.closed) close() }, paint)
        }

        // Collect shapes per layer so they can be drawn in a fixed order.
        val landuse = ArrayList<Pair<Shape, Int>>()
        val water = ArrayList<Shape>()
        val waterLines = ArrayList<Pair<Shape, Float>>()
        val coast = ArrayList<Shape>()
        val roads = ArrayList<Triple<Shape, Int, Float>>()
        val buildings = ArrayList<Pair<Shape, Int>>()

        val els = root.optJSONArray("elements") ?: JSONArray()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            fun t(k: String) = tags.optString(k)
            val shapes: List<Shape> = when (e.optString("type")) {
                "way" -> e.optJSONArray("geometry")?.let { listOf(shape(it)) } ?: emptyList()
                "relation" -> {
                    val m = e.optJSONArray("members") ?: JSONArray()
                    (0 until m.length()).map { m.getJSONObject(it) }
                        .filter { it.optString("role") == "outer" && it.has("geometry") }
                        .map { shape(it.getJSONArray("geometry")) }
                }
                else -> emptyList()
            }
            if (shapes.isEmpty()) continue
            val id = e.optLong("id")
            val natural = t("natural"); val land = t("landuse"); val leisure = t("leisure")
            val highway = t("highway"); val waterway = t("waterway")
            for (s in shapes) when {
                t("building").isNotEmpty() -> buildings += s to (id % 7).toInt()
                natural == "coastline" -> coast += s
                natural == "water" || land == "reservoir" || land == "basin" || waterway == "riverbank" ||
                    leisure == "swimming_pool" -> if (s.closed) water += s
                waterway.isNotEmpty() -> waterLines += s to when (waterway) {
                    "river" -> 10f; "canal" -> 8f; else -> 3f
                }
                highway.isNotEmpty() && t("tunnel").isEmpty() && t("area") != "yes" -> {
                    val (mat, width) = when (highway) {
                        "motorway", "trunk", "motorway_link", "trunk_link" -> STONE_BRICKS to 14f
                        "primary", "primary_link" -> STONE to 12f
                        "secondary", "secondary_link" -> STONE to 10f
                        "tertiary", "tertiary_link" -> STONE to 9f
                        "residential", "unclassified", "living_street" -> GRAVEL to 7f
                        "service" -> GRAVEL to 5f
                        "pedestrian" -> SMOOTH_STONE to 6f
                        else -> DIRT_PATH to 3f
                    }
                    roads += Triple(s, mat, width)
                }
                s.closed -> {
                    val mat = when {
                        natural in setOf("wood", "scrub") || land == "forest" || land == "orchard" -> LEAVES
                        natural in setOf("beach", "sand") || leisure == "playground" -> SAND
                        natural == "bare_rock" || land == "quarry" -> COBBLE
                        land == "farmland" || land == "vineyard" || land == "allotments" -> FARMLAND
                        land in setOf("commercial", "retail") || t("amenity") == "parking" -> SMOOTH_STONE
                        land in setOf("industrial", "railway", "construction") -> COBBLE
                        leisure in setOf("pitch", "stadium", "golf_course") -> PITCH
                        land.isNotEmpty() || leisure.isNotEmpty() || natural.isNotEmpty() -> GRASS
                        else -> 0
                    }
                    if (mat != 0) landuse += s to mat
                }
            }
        }

        // 1. ground
        c.drawColor(Color.rgb(GRASS, 0, 0))
        // 2. land use (big areas first so small ones stay on top)
        landuse.sortedByDescending { area(it.first) }.forEach { fillArea(it.first, it.second) }
        // 3. sea: coastline as a barrier, then flood the side OpenStreetMap marks as water (the right)
        coast.forEach { line(it, COAST, 2.5f) }
        val px = IntArray(CELLS * CELLS)
        bmp.getPixels(px, 0, CELLS, 0, 0, CELLS, CELLS)
        if (els.length() == 0) px.fill(Color.rgb(WATER, 0, 0)) // nothing mapped here: open sea
        floodSea(px, coast)
        bmp.setPixels(px, 0, CELLS, 0, 0, CELLS, CELLS)
        // 4. lakes and rivers
        water.forEach { fillArea(it, WATER) }
        waterLines.forEach { line(it.first, WATER, it.second) }
        // 5. roads, wide ones last so junctions look clean
        roads.sortedBy { it.third }.forEach { line(it.first, it.second, it.third) }
        // 6. buildings
        buildings.forEach { fillArea(it.first, BUILDING, it.second) }

        bmp.getPixels(px, 0, CELLS, 0, 0, CELLS, CELLS)
        bmp.recycle()
        // beaches along the shoreline
        for (i in px.indices) if (Color.red(px[i]) == COAST) px[i] = Color.rgb(SAND, 0, 0)
        return px
    }

    private fun area(s: Shape): Float {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < s.pts.size) {
            minX = minOf(minX, s.pts[i]); maxX = maxOf(maxX, s.pts[i])
            minY = minOf(minY, s.pts[i + 1]); maxY = maxOf(maxY, s.pts[i + 1]); i += 2
        }
        return (maxX - minX) * (maxY - minY)
    }

    private fun floodSea(px: IntArray, coast: List<Shape>) {
        val grass = Color.rgb(GRASS, 0, 0)
        val sea = Color.rgb(WATER, 0, 0)
        val stack = IntArray(CELLS * CELLS)
        for (s in coast) {
            var i = 0
            while (i + 3 < s.pts.size) {
                val x0 = s.pts[i]; val y0 = s.pts[i + 1]; val x1 = s.pts[i + 2]; val y1 = s.pts[i + 3]
                i += 2
                val dx = x1 - x0; val dy = y1 - y0
                val len = kotlin.math.sqrt(dx * dx + dy * dy)
                if (len < 0.5f) continue
                // right-hand side of the direction of travel (y grows downward)
                val sx = ((x0 + x1) / 2 - dy / len * 5).toInt()
                val sy = ((y0 + y1) / 2 + dx / len * 5).toInt()
                if (sx !in 0 until CELLS || sy !in 0 until CELLS) continue
                if (px[sy * CELLS + sx] != grass) continue
                var top = 0
                stack[top++] = sy * CELLS + sx
                px[sy * CELLS + sx] = sea
                while (top > 0) {
                    val p = stack[--top]
                    val x = p % CELLS; val y = p / CELLS
                    if (x > 0 && px[p - 1] == grass) { px[p - 1] = sea; stack[top++] = p - 1 }
                    if (x < CELLS - 1 && px[p + 1] == grass) { px[p + 1] = sea; stack[top++] = p + 1 }
                    if (y > 0 && px[p - CELLS] == grass) { px[p - CELLS] = sea; stack[top++] = p - CELLS }
                    if (y < CELLS - 1 && px[p + CELLS] == grass) { px[p + CELLS] = sea; stack[top++] = p + CELLS }
                }
            }
        }
    }

    // ---------------------------------------------------------------- textures (16×16, procedural)

    private val textures = ConcurrentHashMap<Int, IntArray>()

    private fun texture(mat: Int, variant: Int): IntArray {
        val key = if (mat == BUILDING) 100 + variant else mat
        return textures.getOrPut(key) { makeTexture(key) }
    }

    private fun makeTexture(key: Int): IntArray {
        val r = Random(key * 7919L)
        val t = IntArray(256)
        fun noisy(base: Int, amount: Int) = scale(base, 1f + (r.nextInt(amount * 2 + 1) - amount) / 100f)
        for (v in 0 until 16) for (u in 0 until 16) {
            t[v * 16 + u] = when (key) {
                GRASS -> noisy(Color.rgb(95, 159, 53), 10)
                PITCH -> noisy(Color.rgb(118, 178, 70), 6)
                LEAVES -> if (r.nextInt(5) == 0) Color.rgb(28, 70, 22) else noisy(Color.rgb(56, 118, 42), 14)
                WATER -> if ((v + u / 4) % 5 == 0 && r.nextInt(3) > 0) Color.rgb(98, 150, 240) else noisy(Color.rgb(56, 104, 214), 5)
                SAND -> noisy(Color.rgb(219, 207, 163), 6)
                STONE -> noisy(Color.rgb(125, 125, 125), 9)
                SMOOTH_STONE -> if (u == 0 || v == 0) Color.rgb(150, 150, 150) else noisy(Color.rgb(170, 170, 170), 3)
                STONE_BRICKS -> mortar(u, v, Color.rgb(120, 120, 120), Color.rgb(84, 84, 84), r, 8)
                GRAVEL -> when (r.nextInt(6)) { 0 -> Color.rgb(95, 88, 86); 1 -> Color.rgb(160, 152, 150); else -> noisy(Color.rgb(128, 122, 120), 6) }
                DIRT_PATH -> if (r.nextInt(7) == 0) Color.rgb(120, 92, 58) else noisy(Color.rgb(148, 120, 74), 7)
                COBBLE -> if (r.nextInt(4) == 0) Color.rgb(80, 80, 80) else noisy(Color.rgb(118, 118, 118), 14)
                FARMLAND -> if (v % 4 == 0) Color.rgb(74, 46, 26) else noisy(Color.rgb(108, 72, 42), 8)
                // building roofs
                100 -> planks(u, v, Color.rgb(162, 130, 78), r)          // oak
                101 -> planks(u, v, Color.rgb(114, 84, 50), r)           // spruce
                102 -> mortar(u, v, Color.rgb(150, 74, 60), Color.rgb(186, 170, 160), r, 4) // bricks
                103 -> mortar(u, v, Color.rgb(122, 122, 122), Color.rgb(88, 88, 88), r, 8)  // stone bricks
                104 -> noisy(Color.rgb(161, 83, 37), 4)                  // terracotta
                105 -> noisy(Color.rgb(222, 222, 216), 3)                // white concrete
                else -> noisy(Color.rgb(208, 196, 176), 4)               // sandstone
            }
        }
        return t
    }

    private fun planks(u: Int, v: Int, base: Int, r: Random): Int = when {
        v % 4 == 3 -> scale(base, 0.70f)
        u == (v / 4 * 5 + 3) % 16 -> scale(base, 0.80f)
        else -> scale(base, 1f + (r.nextInt(9) - 4) / 100f)
    }

    private fun mortar(u: Int, v: Int, brick: Int, line: Int, r: Random, h: Int): Int {
        val row = v / h
        val off = if (row % 2 == 0) 0 else 4
        return if (v % h == h - 1 || (u + off) % 8 == 7) line else scale(brick, 1f + (r.nextInt(11) - 5) / 100f)
    }

    private fun scale(c: Int, f: Float) = Color.rgb(
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255),
    )

    private fun hash(a: Long, b: Long, c: Long): Int {
        var h = a * 73856093L xor b * 19349663L xor c * 83492791L
        h = h xor (h ushr 13); h *= 0x5bd1e995L; h = h xor (h ushr 15)
        return ((h and 0x7fffffff) % 1000).toInt()
    }
}
