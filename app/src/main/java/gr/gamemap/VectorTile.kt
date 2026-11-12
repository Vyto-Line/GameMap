package gr.gamemap

/**
 * Minimal Mapbox Vector Tile (MVT, protobuf) reader: enough to get each feature's layer,
 * a few string properties and its geometry in tile units (0..extent).
 */
class VectorTile(bytes: ByteArray) {
    /** Rough memory use once parsed (for the cache budget). */
    val approxBytes = bytes.size * 10

    class Feature(
        val layer: String,
        val props: Map<String, String>,
        /** 1 = point, 2 = line, 3 = polygon */
        val type: Int,
        /** Each part is x0,y0,x1,y1,… in tile units. */
        val parts: List<FloatArray>,
        val extent: Int,
    ) {
        operator fun get(key: String) = props[key]

        // bounding box in tile units, for skipping features outside the area being drawn
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE

        init {
            for (p in parts) {
                var i = 0
                while (i + 1 < p.size) {
                    if (p[i] < minX) minX = p[i]; if (p[i] > maxX) maxX = p[i]
                    if (p[i + 1] < minY) minY = p[i + 1]; if (p[i + 1] > maxY) maxY = p[i + 1]
                    i += 2
                }
            }
        }

        fun intersects(l: Float, t: Float, r: Float, b: Float) = maxX >= l && minX <= r && maxY >= t && minY <= b

        /** Cached drawing path (built on first use). */
        @Volatile var path: android.graphics.Path? = null
    }

    val features = ArrayList<Feature>()

    init {
        val p = Pbf(bytes, 0, bytes.size)
        while (p.more()) {
            val tag = p.varint().toInt()
            if (tag shr 3 == 3 && tag and 7 == 2) readLayer(p.sub()) else p.skip(tag and 7)
        }
    }

    private fun readLayer(p: Pbf) {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<String?>()
        val raw = ArrayList<Pbf>()
        while (p.more()) {
            val tag = p.varint().toInt()
            when (tag shr 3) {
                1 -> name = p.string()
                2 -> raw += p.sub()
                3 -> keys += p.string()
                4 -> values += readValue(p.sub())
                5 -> extent = p.varint().toInt()
                else -> p.skip(tag and 7)
            }
        }
        for (f in raw) readFeature(f, name, keys, values, extent)
    }

    private fun readValue(p: Pbf): String? {
        var v: String? = null
        while (p.more()) {
            val tag = p.varint().toInt()
            when (tag shr 3) {
                1 -> v = p.string()
                2 -> v = java.lang.Float.intBitsToFloat(p.fixed32()).toString()
                3 -> v = java.lang.Double.longBitsToDouble(p.fixed64()).toString()
                4, 5 -> v = p.varint().toString()
                6 -> v = p.varint().let { (it ushr 1) xor -(it and 1) }.toString()
                7 -> v = (p.varint() != 0L).toString()
                else -> p.skip(tag and 7)
            }
        }
        return v
    }

    private fun readFeature(p: Pbf, layer: String, keys: List<String>, values: List<String?>, extent: Int) {
        val props = HashMap<String, String>(4)
        var type = 0
        var geom = IntArray(0)
        while (p.more()) {
            val tag = p.varint().toInt()
            when (tag shr 3) {
                2 -> {
                    val t = p.packed()
                    var i = 0
                    while (i + 1 < t.size) {
                        val k = keys.getOrNull(t[i]); val v = values.getOrNull(t[i + 1])
                        if (k != null && v != null) props[k] = v
                        i += 2
                    }
                }
                3 -> type = p.varint().toInt()
                4 -> geom = p.packed()
                else -> p.skip(tag and 7)
            }
        }
        if (type == 1 && layer != "place") return // only place names are drawn as points
        val parts = decodeGeometry(geom)
        when {
            // Polygons: one feature per outer ring (with its holes), so each gets a tight
            // bounding box. OpenFreeMap packs thousands of buildings into one feature.
            type == 3 && parts.size > 1 -> {
                var group = ArrayList<FloatArray>()
                for (part in parts) {
                    if (signedArea(part) >= 0 && group.isNotEmpty()) {
                        features += Feature(layer, props, type, group, extent)
                        group = ArrayList()
                    }
                    group += part
                }
                if (group.isNotEmpty()) features += Feature(layer, props, type, group, extent)
            }
            type == 2 && parts.size > 1 -> for (part in parts) features += Feature(layer, props, type, listOf(part), extent)
            else -> features += Feature(layer, props, type, parts, extent)
        }
    }

    private fun decodeGeometry(g: IntArray): List<FloatArray> {
        val parts = ArrayList<FloatArray>()
        var cur = FloatArray(16); var n = 0
        var x = 0; var y = 0
        var i = 0
        fun flush() { if (n >= 2) parts += cur.copyOf(n); cur = FloatArray(16); n = 0 }
        fun add(px: Int, py: Int) {
            if (n + 2 > cur.size) cur = cur.copyOf(cur.size * 2)
            cur[n++] = px.toFloat(); cur[n++] = py.toFloat()
        }
        while (i < g.size) {
            val cmd = g[i] and 7; val count = g[i] ushr 3
            i++
            when (cmd) {
                1 -> repeat(count) {
                    flush()
                    x += zz(g[i++]); y += zz(g[i++]); add(x, y)
                }
                2 -> repeat(count) { x += zz(g[i++]); y += zz(g[i++]); add(x, y) }
                7 -> if (n >= 2) add(cur[0].toInt(), cur[1].toInt())
                else -> return parts
            }
        }
        flush()
        return parts
    }

    private fun signedArea(p: FloatArray): Float {
        var a = 0f
        var i = 0
        while (i < p.size) {
            val j = (i + 2) % p.size
            a += p[i] * p[j + 1] - p[j] * p[i + 1]
            i += 2
        }
        return a / 2
    }

    private fun zz(n: Int) = (n ushr 1) xor -(n and 1)

    /** Tiny protobuf cursor over a byte range. */
    private class Pbf(val b: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var r = 0L; var s = 0
            while (true) {
                val x = b[pos++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl s)
                if (x and 0x80 == 0) return r
                s += 7
            }
        }
        fun fixed32(): Int {
            var r = 0
            for (i in 0 until 4) r = r or ((b[pos + i].toInt() and 0xff) shl (8 * i))
            pos += 4
            return r
        }
        fun fixed64(): Long {
            var r = 0L
            for (i in 0 until 8) r = r or ((b[pos + i].toLong() and 0xff) shl (8 * i))
            pos += 8
            return r
        }
        fun sub(): Pbf { val len = varint().toInt(); val p = Pbf(b, pos, pos + len); pos += len; return p }
        fun string(): String { val len = varint().toInt(); val s = String(b, pos, len, Charsets.UTF_8); pos += len; return s }
        fun packed(): IntArray {
            val p = sub()
            val out = ArrayList<Int>()
            while (p.more()) out += p.varint().toInt()
            return out.toIntArray()
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> pos = end
            }
        }
    }
}
