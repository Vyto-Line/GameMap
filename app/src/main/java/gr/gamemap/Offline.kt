package gr.gamemap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

/**
 * Offline maps: the map of an area downloaded to the phone, so it shows without signal (and
 * without using data). Routes and search still need the internet; the map, street names,
 * places and your position work anywhere.
 */
object Offline {
    class Area(val name: String, val center: LatLng, val km: Int, val tiles: Int, val bytes: Long, val at: Long)

    private lateinit var appContext: Context
    fun init(ctx: Context) { appContext = ctx.applicationContext }
    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)
    val changed = Signal()

    fun areas(): List<Area> = try {
        val a = JSONArray(prefs().getString("offlineAreas", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Area(o.getString("n"), LatLng(o.getDouble("lat"), o.getDouble("lon")), o.getInt("km"), o.getInt("t"), o.getLong("b"), o.getLong("at"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(list: List<Area>) {
        val a = JSONArray()
        for (x in list) a.put(JSONObject().put("n", x.name).put("lat", x.center.lat).put("lon", x.center.lon)
            .put("km", x.km).put("t", x.tiles).put("b", x.bytes).put("at", x.at))
        prefs().edit().putString("offlineAreas", a.toString()).apply()
    }

    /** Map tiles of an area: every zoom from the whole region (8) down to street detail (14). */
    private fun tiles(center: LatLng, km: Int): List<Triple<Int, Int, Int>> {
        val dLat = km / 111.0
        val dLon = km / (111.0 * Math.cos(Math.toRadians(center.lat)))
        val out = ArrayList<Triple<Int, Int, Int>>()
        for (z in 8..VectorSource.MAX_Z) {
            val n = 1 shl z
            val x0 = floor(Geo.x(center.lon - dLon, 1.0) * n).toInt(); val x1 = floor(Geo.x(center.lon + dLon, 1.0) * n).toInt()
            val y0 = floor(Geo.y(center.lat + dLat, 1.0) * n).toInt(); val y1 = floor(Geo.y(center.lat - dLat, 1.0) * n).toInt()
            for (y in y0..y1) for (x in x0..x1) out += Triple(z, x, y)
        }
        return out
    }

    fun tileCount(center: LatLng, km: Int) = tiles(center, km).size

    // ---- downloading (one at a time, two connections; survives leaving the screen)
    @Volatile var downloading: String? = null
        private set
    val done = AtomicInteger(0)
    @Volatile var total = 0
        private set
    @Volatile var cancelled = false

    fun download(name: String, center: LatLng, km: Int) {
        if (downloading != null) return
        val list = tiles(center, km)
        downloading = name; total = list.size; done.set(0); cancelled = false
        changed.fire()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2, Bg.factory("offline"))
        Thread {
            var failed = 0
            val tasks = list.map { (z, x, y) ->
                pool.submit {
                    if (!cancelled) { if (!VectorSource.saveOffline(z, x, y)) failed++ }
                    if (done.incrementAndGet() % 10 == 0) changed.fire()
                }
            }
            tasks.forEach { try { it.get() } catch (_: Exception) {} }
            pool.shutdown()
            if (!cancelled) {
                val bytes = list.sumOf { (z, x, y) -> File(VectorSource.offlineDir, "$z/$x/$y.pbf").length() }
                save(areas().filterNot { it.name == name } + Area(name, center, km, list.size - failed, bytes, System.currentTimeMillis()))
            }
            downloading = null
            changed.fire()
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Deletes an area's map (tiles other downloaded areas still use are kept). */
    fun delete(a: Area) {
        val others = areas().filter { it.name != a.name }
        val keep = HashSet<String>()
        for (o in others) for ((z, x, y) in tiles(o.center, o.km)) keep += "$z/$x/$y"
        for ((z, x, y) in tiles(a.center, a.km)) if ("$z/$x/$y" !in keep) File(VectorSource.offlineDir, "$z/$x/$y.pbf").delete()
        save(others)
        changed.fire()
    }

    fun size(bytes: Long) = if (bytes >= 1_000_000) "${"%.0f".format(bytes / 1_000_000.0)} MB" else "${bytes / 1000} KB"
}
