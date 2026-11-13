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
    /** Traffic is fetched at most at this zoom (about 2 km squares); coarser when zoomed out. */
    const val DATA_Z = 14
    const val MIN_Z = 10
    private const val REFRESH_MS = 120_000L

    const val FREE = 0
    const val SLOW = 1
    const val JAM = 2
    const val CLOSED = 3

    /**
     * One road stretch: points in tile units (0..extent), its state, whether the data is for
     * one driving direction only (then the points run in that direction), and left-hand traffic.
     */
    class Seg(val pts: FloatArray, val state: Int, val oneWay: Boolean, val leftHand: Boolean)

    class Tile(val segs: List<Seg>, val extent: Int, val fetchedAt: Long)

    private lateinit var appContext: Context

    /** A key typed into the app overrides the one built into the app. */
    private var userKey = ""
    /** The built-in TomTom key (the "your own key" option was removed). */
    val key: String get() = BuildConfig.TOMTOM_KEY
    val builtIn: Boolean get() = BuildConfig.TOMTOM_KEY.isNotEmpty()
    var enabled = true
        private set
    val active get() = key.isNotEmpty() && enabled

    private val LOCAL_TYPES = setOf("Local road", "Minor local road", "Major local road")

    /** Count of flow segments per TomTom road type (diagnostics). */
    val roadTypes = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val tiles = object : LruCache<String, Tile>(40) {}
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    /** Separate from the map-drawing threads, so slow downloads never hold up the map. */
    private val net = Bg.pool("traffic", 2)

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

    /** Traffic colours drawn on the map (arrival times keep using traffic either way). */
    var showLayer: Boolean
        get() = !::appContext.isInitialized || prefs().getBoolean("trafficLayer", true)
        set(v) { prefs().edit().putBoolean("trafficLayer", v).apply(); MapState.changed.fire() }

    fun setEnabled(on: Boolean) {
        enabled = on
        prefs().edit().putBoolean("traffic", on).apply()
        MapState.changed.fire()
    }

    /** Traffic for a square if loaded; starts (re)loading it when missing or older than 2 minutes. */
    fun tile(z: Int, x: Int, y: Int): Tile? {
        if (!active) return null
        val k = "$z/$x/$y"
        val t = tiles.get(k)
        val now = System.currentTimeMillis()
        // fresher while navigating; just browsing the map, every 5 minutes is plenty
        val stale = t == null || now - t.fetchedAt > (if (NavEngine.active) REFRESH_MS else 300_000L)
        val backoff = (failedAt[k] ?: 0) > now - 30_000
        if (stale && !backoff && inFlight.add(k)) {
            net.execute {
                try {
                    tiles.put(k, fetch(z, x, y))
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

    private fun fetch(z: Int, x: Int, y: Int): Tile {
        val url = "https://api.tomtom.com/traffic/map/4/tile/flow/relative/$z/$x/$y.pbf?key=$key"
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
                    level < 0.35f -> JAM
                    level < 0.65f -> SLOW
                    else -> FREE
                }
                roadTypes.merge(f["road_type"] ?: "?", 1, Int::plus)
                // big roads only: local streets made the map busy and slow
                if (f["road_type"] in LOCAL_TYPES) continue
                val oneWay = f["traffic_road_coverage"] == "one_side"
                val leftHand = f["left_hand_traffic"].equals("true", ignoreCase = true)
                for (part in f.parts) if (part.size >= 4) segs += Seg(part, state, oneWay, leftHand)
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
