package gr.gamemap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Downloads base map tiles (cached on disk) and keeps theme-processed tiles in memory.
 * Map data © OpenStreetMap contributors, tiles © CARTO (free for personal, non-commercial use).
 */
object TileCache {
    val loaded = Signal()

    /** Pixel size of one tile; 512 = "@2x" retina tiles for sharp text on dense screens. */
    var tilePx = 512
        private set

    private lateinit var dir: File
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(4)

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "tiles")
        tilePx = if (ctx.resources.displayMetrics.density >= 1.5f) 512 else 256
    }

    private fun key(theme: Theme, z: Int, x: Int, y: Int) = "${theme.ordinal}/$z/$x/$y"

    /** Themed tile if ready; otherwise starts loading it and returns null. */
    fun get(theme: Theme, z: Int, x: Int, y: Int): Bitmap? {
        val k = key(theme, z, x, y)
        mem.get(k)?.let { return it }
        val f = failed[k]
        if (f != null && System.currentTimeMillis() - f < 15_000) return null
        if (inFlight.add(k)) {
            pool.execute {
                try {
                    val raw = loadRaw(z, x, y)
                    if (raw != null) {
                        mem.put(k, theme.process(raw))
                        failed.remove(k)
                        loaded.fire()
                    } else {
                        failed[k] = System.currentTimeMillis()
                    }
                } catch (t: Throwable) {
                    failed[k] = System.currentTimeMillis()
                } finally {
                    inFlight.remove(k)
                }
            }
        }
        return null
    }

    /** Only what is already in memory (used for blurry stand-ins while a tile loads). */
    fun peek(theme: Theme, z: Int, x: Int, y: Int): Bitmap? = mem.get(key(theme, z, x, y))

    private fun loadRaw(z: Int, x: Int, y: Int): Bitmap? {
        val file = File(dir, "$tilePx/$z/$x/$y.png")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 30L * 86_400_000) {
            BitmapFactory.decodeFile(file.path)?.let { return it }
        }
        val sub = "abcd"[(x + y).mod(4)]
        val suffix = if (tilePx == 512) "@2x" else ""
        val url = URL("https://$sub.basemaps.cartocdn.com/dark_all/$z/$x/$y$suffix.png")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "GameMap/1.0 (Android; personal use)")
        return try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            conn.disconnect()
        }
    }
}
