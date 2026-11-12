package gr.gamemap

import android.content.Context
import android.util.LruCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * Live traffic from TomTom vector flow tiles: every road stretch with its speed compared to
 * normal, closures, and whether only one direction is affected. Drawn by the map in each
 * theme's style. Turn-by-turn directions stay on the open OSRM router.
 */
object Traffic {
    /** Traffic is fetched at this zoom (about 2 km squares) and drawn at every zoom. */
    const val DATA_Z = 14
    private const val REFRESH_MS = 120_000L

    const val FREE = 0
    const val SLOW = 1
    const val JAM = 2
    const val CLOSED = 3

    /** One road stretch: points in tile units (0..extent), its state, and if it's one direction. */
    class Seg(val pts: FloatArray, val state: Int, val oneWay: Boolean)

    class Tile(val segs: List<Seg>, val extent: Int, val fetchedAt: Long)

    private lateinit var appContext: Context

    /** A key typed into the app overrides the one built into the app. */
    private var userKey = ""
    val key: String get() = userKey.ifEmpty { BuildConfig.TOMTOM_KEY }
    val builtIn: Boolean get() = userKey.isEmpty() && BuildConfig.TOMTOM_KEY.isNotEmpty()
    var enabled = true
        private set
    val active get() = key.isNotEmpty() && enabled

    private val tiles = object : LruCache<String, Tile>(40) {}
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    /** Separate from the map-drawing threads, so slow downloads never hold up the map. */
    private val net = Executors.newFixedThreadPool(2)

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        val p = prefs()
        userKey = p.getString("tomtomKey", "") ?: ""
        enabled = p.getBoolean("traffic", true)
    }

    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)

    fun setKey(k: String) {
        userKey = k.trim().takeIf { it != BuildConfig.TOMTOM_KEY } ?: ""
        prefs().edit().putString("tomtomKey", userKey).apply()
        tiles.evictAll()
        MapState.changed.fire()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        prefs().edit().putBoolean("traffic", on).apply()
        MapState.changed.fire()
    }

    /** Traffic for a zoom-14 square if loaded; starts (re)loading it when missing or older than 2 minutes. */
    fun tile(x: Int, y: Int): Tile? {
        if (!active) return null
        val k = "$x/$y"
        val t = tiles.get(k)
        val now = System.currentTimeMillis()
        val stale = t == null || now - t.fetchedAt > REFRESH_MS
        val backoff = (failedAt[k] ?: 0) > now - 30_000
        if (stale && !backoff && inFlight.add(k)) {
            net.execute {
                try {
                    tiles.put(k, fetch(x, y))
                    failedAt.remove(k)
                    TileCache.loaded.fire()
                } catch (_: Throwable) {
                    failedAt[k] = System.currentTimeMillis()
                } finally {
                    inFlight.remove(k)
                }
            }
        }
        return t
    }

    private fun fetch(x: Int, y: Int): Tile {
        val url = "https://api.tomtom.com/traffic/map/4/tile/flow/relative/$DATA_Z/$x/$y.pbf?key=$key"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            var bytes = c.inputStream.use { it.readBytes() }
            if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                bytes = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            }
            val vt = VectorTile(bytes)
            var extent = 4096
            val segs = ArrayList<Seg>()
            for (f in vt.features) {
                if (f.type != 2) continue
                extent = f.extent
                val level = f["traffic_level"]?.toFloatOrNull() ?: 1f
                val state = when {
                    f["road_closure"].equals("true", ignoreCase = true) -> CLOSED
                    level < 0.4f -> JAM
                    level < 0.75f -> SLOW
                    else -> FREE
                }
                val oneWay = f["traffic_road_coverage"] == "one_side"
                for (part in f.parts) if (part.size >= 4) segs += Seg(part, state, oneWay)
            }
            return Tile(segs, extent, System.currentTimeMillis())
        } finally {
            c.disconnect()
        }
    }

    /** Traffic-aware driving time between two points, from TomTom routing (summary only). */
    class Eta(val seconds: Int, val delaySeconds: Int)

    fun eta(from: LatLng, to: LatLng): Eta? {
        if (!active) return null
        val url = "https://api.tomtom.com/routing/1/calculateRoute/" +
            "${from.lat},${from.lon}:${to.lat},${to.lon}/json?key=$key&traffic=true&travelMode=car&routeType=fastest&routeRepresentation=summaryOnly"
        val o = JSONObject(Http.get(url))
        val sum = o.getJSONArray("routes").getJSONObject(0).getJSONObject("summary")
        return Eta(sum.getInt("travelTimeInSeconds"), sum.optInt("trafficDelayInSeconds", 0))
    }
}
