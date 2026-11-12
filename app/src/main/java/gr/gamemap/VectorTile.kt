package gr.gamemap

/**
 * Minimal Mapbox Vector Tile (MVT, protobuf) reader: enough to get each feature's layer,
 * a few string properties and its geometry in tile units (0..extent).
 */
class VectorTile(bytes: ByteArray) {

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
        if (type == 1) return // points aren't drawn
        features += Feature(layer, props, type, decodeGeometry(geom), extent)
    }

    private fun decodeGeometry(g: IntArray): List<FloatArray> {
        val parts = ArrayList<FloatArray>()
        var cur = FloatArray(16); var n = 0
        var x = 0; var y = 0
        var i = 0
        fun flush() { if (n >= 4) parts += cur.copyOf(n); cur = FloatArray(16); n = 0 }
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
