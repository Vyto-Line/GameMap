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
    /** Parsed tiles, as many as a sixth of the app's memory allows. */
    private val parsed = object : LruCache<String, VectorTile>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: VectorTile) = value.approxBytes
    }
    private val prefetcher = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val prefetching = ConcurrentHashMap.newKeySet<String>()

    /**
     * Which data zoom draws a map zoom. Close up: the full-detail zoom-14 data. At
     * neighbourhood zoom (14), the next level down: a quarter of the downloads, and
     * buildings that small look the same.
     */
    fun dataZoom(z: Int) = when {
        z >= 15 -> MAX_Z
        z == 14 -> 13
        else -> z
    }

    /** Downloads a tile in the background (no parsing) so it's ready when needed. */
    fun prefetch(z: Int, x: Int, y: Int) {
        val key = "$z/$x/$y"
        if (parsed.get(key) != null || !prefetching.add(key)) return
        prefetcher.execute {
            try {
                val f = File(dir, "$z/$x/$y.pbf")
                if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 86_400_000) load(z, x, y)
            } catch (_: Exception) {
            } finally {
                if (prefetching.size > 500) prefetching.clear()
            }
        }
    }
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

    /** Already-parsed data only (never downloads; safe on the UI thread). */
    fun peek(z: Int, x: Int, y: Int): VectorTile? = parsed.get("$z/$x/$y")

    /** Goes up each time a tile is parsed (lets traffic lanes re-snap when roads arrive). */
    @Volatile var loadedCount = 0
        private set

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
                loadedCount++
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
