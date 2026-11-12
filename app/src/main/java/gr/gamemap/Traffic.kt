package gr.gamemap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Live traffic from TomTom (free key, 50,000 tiles a day): coloured traffic lines drawn in
 * each theme's colours, and traffic-aware travel times. Turn-by-turn directions stay on the
 * open OSRM router.
 */
object Traffic {
    private const val REFRESH_MS = 120_000L
    private lateinit var appContext: Context

    /** A key typed into the app overrides the one built into the app. */
    private var userKey = ""
    val key: String get() = userKey.ifEmpty { BuildConfig.TOMTOM_KEY }
    val builtIn: Boolean get() = userKey.isEmpty() && BuildConfig.TOMTOM_KEY.isNotEmpty()
    var enabled = true
        private set

    val active get() = key.isNotEmpty() && enabled

    private class Entry(val bmp: Bitmap?, val at: Long)
    private val tiles = object : LruCache<String, Entry>(80) {}
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

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

    /** Traffic overlay for a tile in the theme's colours, if ready (refreshes every 2 minutes). */
    fun tile(theme: Theme, z: Int, x: Int, y: Int): Bitmap? {
        if (!active || z < 9 || z > 20) return null
        val k = "${theme.ordinal}/$z/$x/$y"
        val e = tiles.get(k)
        val now = System.currentTimeMillis()
        if ((e == null || now - e.at > REFRESH_MS) && inFlight.add(k)) {
            TileCache.background {
                try {
                    val bmp = fetch(theme, z, x, y)
                    tiles.put(k, Entry(bmp, System.currentTimeMillis()))
                    TileCache.loaded.fire()
                } catch (_: Throwable) {
                    tiles.put(k, Entry(e?.bmp, System.currentTimeMillis() - REFRESH_MS + 30_000))
                } finally {
                    inFlight.remove(k)
                }
            }
        }
        return e?.bmp
    }

    private fun fetch(theme: Theme, z: Int, x: Int, y: Int): Bitmap? {
        val size = TileCache.tilePx
        val url = "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/$z/$x/$y.png" +
            "?key=$key&tileSize=$size&thickness=${if (z >= 15) 8 else 5}"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            val raw = c.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
            return recolor(raw, theme)
        } finally {
            c.disconnect()
        }
    }

    /**
     * TomTom draws free-flowing roads green, slower ones orange and jams red. Free flow is
     * left out (the map stays clean); slow and jammed traffic take the theme's colours.
     */
    private fun recolor(raw: Bitmap, theme: Theme): Bitmap? {
        val w = raw.width; val h = raw.height
        val px = IntArray(w * h)
        raw.getPixels(px, 0, w, 0, 0, w, h)
        val slow = theme.style.trafficSlow; val jam = theme.style.trafficJam
        var any = false
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c)
            if (a < 30) { px[i] = 0; continue }
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val target = when {
                g > r + 20 && g > b -> 0                     // green: moving freely
                r > 150 && g > 90 -> slow                    // orange / yellow
                r > g + 40 -> jam                            // red / dark red
                else -> 0
            }
            px[i] = if (target == 0) 0 else { any = true; (a shl 24) or (target and 0xffffff) }
        }
        if (!any) return null
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
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
