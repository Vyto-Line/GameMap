package gr.gamemap

import android.content.Context
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * OpenFreeMap vector tiles (OpenStreetMap data, free, no key): downloads, disk cache and a
 * small in-memory cache of parsed tiles shared by every theme and by Blocky.
 * Data exists up to zoom 14; closer zooms draw a part of the zoom-14 tile.
 */
object VectorSource {
    const val MAX_Z = 14

    /** Tiles being downloaded or parsed right now (for the "loading" hint). */
    val busy = AtomicInteger(0)

    private lateinit var dir: File
    private lateinit var appContext: Context
    private val parsed = object : LruCache<String, VectorTile>(10) {}
    private val locks = ConcurrentHashMap<String, Any>()
    @Volatile private var template: String? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        dir = File(ctx.cacheDir, "vector")
        // caches from older versions
        for (old in listOf("tiles", "tiles-v2", "blocky", "blocky-v2")) File(ctx.cacheDir, old).deleteRecursively()
    }

    /** For tests: use already-parsed data for a tile. */
    fun put(z: Int, x: Int, y: Int, vt: VectorTile) { parsed.put("$z/$x/$y", vt) }

    fun get(z: Int, x: Int, y: Int): VectorTile? {
        val key = "$z/$x/$y"
        parsed.get(key)?.let { return it }
        synchronized(locks.getOrPut(key) { Any() }) {
            parsed.get(key)?.let { return it }
            busy.incrementAndGet()
            try {
                val bytes = load(z, x, y) ?: return null
                val vt = VectorTile(bytes)
                parsed.put(key, vt)
                return vt
            } catch (e: Exception) {
                return null
            } finally {
                busy.decrementAndGet()
                locks.remove(key)
            }
        }
    }

    /** The tile URL pattern from OpenFreeMap's TileJSON (it changes with each data release). */
    private fun template(): String? {
        template?.let { return it }
        val prefs = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)
        val saved = prefs.getString("ofmTemplate", null)
        val savedAt = prefs.getLong("ofmTemplateAt", 0)
        if (saved != null && System.currentTimeMillis() - savedAt < 86_400_000) { template = saved; return saved }
        return try {
            val t = JSONObject(Http.get("https://tiles.openfreemap.org/planet")).getJSONArray("tiles").getString(0)
            prefs.edit().putString("ofmTemplate", t).putLong("ofmTemplateAt", System.currentTimeMillis()).apply()
            template = t
            t
        } catch (e: Exception) {
            saved
        }
    }

    private fun load(z: Int, x: Int, y: Int): ByteArray? {
        val file = File(dir, "$z/$x/$y.pbf")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 30L * 86_400_000) {
            return file.readBytes()
        }
        val t = template() ?: return if (file.exists()) file.readBytes() else null
        val url = t.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "GameMap/1.0 (Android; personal use)")
        try {
            if (c.responseCode != 200) return if (file.exists()) file.readBytes() else null
            var bytes = c.inputStream.use { it.readBytes() }
            if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                bytes = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            }
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            return bytes
        } catch (e: Exception) {
            return if (file.exists()) file.readBytes() else null
        } finally {
            c.disconnect()
        }
    }
}
