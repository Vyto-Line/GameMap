package gr.gamemap

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Greek toll stations and their car prices (made weekly in CI from OpenStreetMap positions and
 * the operators' price lists, published with the camera data). Adds up each route's tolls and
 * warns about the next one while driving.
 */
object Tolls {
    class Station(val name: String, val price: Double, val pos: LatLng, val side: Boolean = false)
    /** A toll on a route: [along] metres from the route's start. */
    class OnRoute(val name: String, val price: Double, val along: Double, val pos: LatLng)

    private const val URL = "https://raw.githubusercontent.com/Vyto-Line/GameRoute/data/tolls/GR.json"
    @Volatile private var stations: List<Station> = emptyList()
    private var file: File? = null
    @Volatile private var loading = false

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "tolls_GR.json")
        file?.takeIf { it.exists() }?.let { stations = parse(it.readText()) }
        refresh()
    }

    private fun parse(json: String): List<Station> = try {
        val a = JSONArray(json)
        (0 until a.length()).map { val o = a.getJSONObject(it); Station(o.getString("n"), o.getDouble("p"), LatLng(o.getDouble("lat"), o.getDouble("lon")), o.optInt("s") == 1) }
    } catch (e: Exception) { emptyList() }

    /** Once a week from the data branch (in the background). */
    private fun refresh() {
        val f = file ?: return
        if (loading || (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L && stations.isNotEmpty())) return
        loading = true
        Bg.factory("tolls").newThread {
            try {
                val body = Http.get(URL, timeout = 15_000)
                val list = parse(body)
                if (list.isNotEmpty()) { f.writeText(body); stations = list }
            } catch (_: Exception) {} finally { loading = false }
        }.start()
    }

    /** The toll stations [r] goes through, in order (one per plaza). */
    fun along(r: RouteOption): List<OnRoute> {
        val all = stations
        if (all.isEmpty() || r.points.size < 2) return emptyList()
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        for (p in r.points) { minLat = minOf(minLat, p.lat); maxLat = maxOf(maxLat, p.lat); minLon = minOf(minLon, p.lon); maxLon = maxOf(maxLon, p.lon) }
        val out = ArrayList<OnRoute>()
        for (s in all) {
            if (s.pos.lat < minLat - 0.002 || s.pos.lat > maxLat + 0.002 || s.pos.lon < minLon - 0.002 || s.pos.lon > maxLon + 0.002) continue
            val (along, idx) = r.alongOf(s.pos, 0, r.points.size)
            val off = Geo.distanceToSegment(s.pos, r.points[idx], r.points[minOf(idx + 1, r.points.size - 1)])
            // a main plaza spans the road (either carriageway); a side station sits on its ramp, so the
            // route must go right through it (driving past on the motorway doesn't count)
            if (off > (if (s.side) 7.0 else 25.0)) continue
            // one plaza has several booths (lanes, carriageways, two names): count it once
            if (out.any { (it.name == s.name && kotlin.math.abs(it.along - along) < 3000) || kotlin.math.abs(it.along - along) < 1500 }) continue
            // Attiki Odos charges once, at the entry
            if (s.name == "Αττική Οδός" && out.any { it.name == s.name }) continue
            out += OnRoute(s.name, s.price, along, s.pos)
        }
        return out.sortedBy { it.along }
    }

    internal fun loadForTest(json: String) { stations = parse(json) }

    /** Fills in the tolls and their total for each route (background thread). */
    fun annotate(routes: List<RouteOption>) { for (r in routes) { r.tolls = along(r) } }

    fun money(v: Double) = "€" + "%.2f".format(java.util.Locale.US, v)
}
