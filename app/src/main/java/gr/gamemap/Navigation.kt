package gr.gamemap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** GPS position and heading, from the phone's own location providers (no Play services needed). */
object LocationTracker : LocationListener {
    val changed = Signal()
    var last: Location? = null
        private set
    /** Direction of travel in degrees; kept while standing still. */
    var heading = 0f
        private set
    private var started = false

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (started || !hasPermission(ctx)) return
        val lm = ctx.applicationContext.getSystemService(LocationManager::class.java) ?: return
        val looper = Looper.getMainLooper()
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.allProviders.contains(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, this, looper)
                    if (last == null) lm.getLastKnownLocation(p)?.let { last = it }
                }
            } catch (_: Exception) {
            }
        }
        started = true
        changed.fire()
    }

    val position: LatLng? get() = last?.let { LatLng(it.latitude, it.longitude) }

    override fun onLocationChanged(location: Location) {
        // Prefer GPS; ignore a coarse network fix that arrives right after a GPS one.
        val prev = last
        if (prev != null && prev.provider == LocationManager.GPS_PROVIDER &&
            location.provider != LocationManager.GPS_PROVIDER &&
            location.time - prev.time < 10_000
        ) return
        if (location.hasBearing() && location.speed > 1.5f) heading = location.bearing
        last = location
        NavEngine.onLocation(location)
        changed.fire()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}

/** A place found by search. */
data class Place(val name: String, val address: String, val pos: LatLng)

/** Destination search via OpenStreetMap Nominatim. */
object Places {
    fun search(query: String, near: LatLng?): List<Place> {
        val q = URLEncoder.encode(query, "UTF-8")
        var url = "https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&limit=8&addressdetails=0"
        if (near != null) {
            val d = 0.6
            url += "&viewbox=${near.lon - d},${near.lat + d},${near.lon + d},${near.lat - d}"
        }
        val arr = JSONArray(Http.get(url))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            val name = o.optString("name").ifBlank { display.substringBefore(",") }
            val address = display.removePrefix(name).trimStart(',', ' ')
            Place(name, address, LatLng(o.getString("lat").toDouble(), o.getString("lon").toDouble()))
        }.sortedBy { p -> near?.let { Geo.distance(it, p.pos) } ?: 0.0 }
    }
}

object Http {
    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "GameMap/1.0 (Android; personal use)")
        c.setRequestProperty("Accept-Language", java.util.Locale.getDefault().language)
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** Turn-by-turn navigation using the public OSRM routing service. */
object NavEngine {
    val changed = Signal()

    class Step(
        val text: String,
        val road: String,
        /** "left", "right", "slight left", "uturn", "straight", "arrive", ... */
        val direction: String,
        val pos: LatLng,
        var routeIndex: Int = 0,
    )

    var destination: LatLng? = null
        private set
    var destinationName = ""
        private set
    var route: List<LatLng> = emptyList()
        private set
    var steps: List<Step> = emptyList()
        private set
    var loading = false
        private set
    var arrived = false
        private set
    var error: String? = null
        private set

    /** Index into [steps] of the next maneuver. */
    var nextStep = -1
        private set
    var distanceToNext = 0.0
        private set
    var remainingDistance = 0.0
        private set
    var remainingSeconds = 0.0
        private set

    val active get() = destination != null
    val currentStep: Step? get() = steps.getOrNull(nextStep)

    private var cum = DoubleArray(0)
    private var secondsPerMeter = 0.08
    private var nearest = 0
    private var offRouteCount = 0
    private var lastRouteAt = 0L
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context

    fun start(ctx: Context, dest: LatLng, name: String) {
        appContext = ctx.applicationContext
        destination = dest
        destinationName = name
        arrived = false
        route = emptyList(); steps = emptyList(); nextStep = -1
        changed.fire()
        try {
            if (LocationTracker.hasPermission(appContext))
                ContextCompat.startForegroundService(appContext, Intent(appContext, NavService::class.java))
        } catch (_: Exception) {
        }
        fetchRoute()
    }

    fun stop() {
        destination = null
        route = emptyList(); steps = emptyList(); nextStep = -1
        loading = false; arrived = false; error = null
        if (::appContext.isInitialized) {
            appContext.stopService(Intent(appContext, NavService::class.java))
        }
        changed.fire()
    }

    private fun fetchRoute() {
        val dest = destination ?: return
        val from = LocationTracker.position
        if (from == null) {
            error = "Waiting for GPS…"
            loading = true
            changed.fire()
            return
        }
        loading = true
        error = null
        lastRouteAt = System.currentTimeMillis()
        changed.fire()
        io.execute {
            try {
                val json = Http.get(
                    "https://router.project-osrm.org/route/v1/driving/" +
                        "${from.lon},${from.lat};${dest.lon},${dest.lat}" +
                        "?overview=full&geometries=geojson&steps=true"
                )
                val parsed = parse(JSONObject(json))
                main.post {
                    if (destination == dest) apply(parsed)
                }
            } catch (e: Exception) {
                main.post {
                    loading = false
                    error = "No route (${e.message})"
                    changed.fire()
                }
            }
        }
    }

    private class Parsed(val points: List<LatLng>, val steps: List<Step>, val distance: Double, val duration: Double)

    private fun parse(o: JSONObject): Parsed {
        val r = o.getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until coords.length()).map {
            val c = coords.getJSONArray(it)
            LatLng(c.getDouble(1), c.getDouble(0))
        }
        val out = mutableListOf<Step>()
        val legs = r.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val s = legs.getJSONObject(l).getJSONArray("steps")
            for (i in 0 until s.length()) {
                val st = s.getJSONObject(i)
                val m = st.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val type = m.optString("type")
                val mod = m.optString("modifier", "straight")
                val name = st.optString("name")
                val exit = m.optInt("exit", 0)
                out += Step(
                    text = instruction(type, mod, name, exit),
                    road = name,
                    direction = if (type == "arrive") "arrive" else mod,
                    pos = LatLng(loc.getDouble(1), loc.getDouble(0)),
                )
            }
        }
        return Parsed(pts, out, r.getDouble("distance"), r.getDouble("duration"))
    }

    private fun instruction(type: String, mod: String, name: String, exit: Int): String {
        val onto = if (name.isNotBlank()) " onto $name" else ""
        val dir = mod.replaceFirstChar { it.uppercase() }
        return when (type) {
            "arrive" -> "Arrive at destination"
            "depart" -> "Head out$onto"
            "roundabout", "rotary" ->
                if (exit > 0) "At the roundabout, take exit $exit$onto" else "Enter the roundabout$onto"
            "exit roundabout", "exit rotary" -> "Exit the roundabout$onto"
            "fork" -> "Keep ${mod.removePrefix("slight ")}$onto"
            "merge" -> "Merge$onto"
            "on ramp" -> "Take the ramp$onto"
            "off ramp" -> "Take the exit$onto"
            "continue", "new name" ->
                if (mod == "straight") "Continue$onto" else "Continue $mod$onto"
            else -> when (mod) {
                "uturn" -> "Make a U-turn$onto"
                "straight" -> "Go straight$onto"
                else -> "Turn $mod$onto"
            }.let { if (type == "end of road") "At the end of the road, ${it.replaceFirstChar { c -> c.lowercase() }}" else it }
        }.let { if (it.isEmpty()) dir else it }
    }

    private fun apply(p: Parsed) {
        route = p.points
        cum = DoubleArray(route.size)
        for (i in 1 until route.size) cum[i] = cum[i - 1] + Geo.distance(route[i - 1], route[i])
        secondsPerMeter = if (p.distance > 0) p.duration / p.distance else 0.08
        // Map each maneuver to its closest route point (searching forward).
        var from = 0
        for (s in p.steps) {
            var best = from; var bestD = Double.MAX_VALUE
            for (i in from until minOf(route.size, from + 2000)) {
                val d = Geo.distance(route[i], s.pos)
                if (d < bestD) { bestD = d; best = i }
                if (d < 1.0) break
            }
            s.routeIndex = best
            from = best
        }
        steps = p.steps
        nearest = 0
        offRouteCount = 0
        loading = false
        error = null
        LocationTracker.last?.let { onLocation(it) } ?: changed.fire()
    }

    fun onLocation(l: Location) {
        if (!active) return
        val pos = LatLng(l.latitude, l.longitude)
        if (route.size < 2) {
            if (loading && error != null) fetchRoute() // was waiting for the first GPS fix
            return
        }
        // Closest route segment, searching a window ahead of the last match.
        var best = nearest; var bestD = Double.MAX_VALUE
        val lo = maxOf(0, nearest - 10); val hi = minOf(route.size - 1, nearest + 300)
        for (i in lo until hi) {
            val d = Geo.distanceToSegment(pos, route[i], route[i + 1])
            if (d < bestD) { bestD = d; best = i }
        }
        nearest = best
        val along = cum[best] + Geo.distance(route[best], pos).coerceAtMost(cum[best + 1] - cum[best])
        remainingDistance = (cum.last() - along).coerceAtLeast(0.0)
        remainingSeconds = remainingDistance * secondsPerMeter

        val dest = destination!!
        if (Geo.distance(pos, dest) < 30 || remainingDistance < 20) {
            if (!arrived) {
                arrived = true
                main.postDelayed({ if (arrived) stop() }, 20_000)
            }
            changed.fire()
            return
        }

        nextStep = steps.indexOfFirst { it.routeIndex > best || it.direction == "arrive" }
            .let { if (it < 0) steps.size - 1 else it }
        steps.getOrNull(nextStep)?.let { distanceToNext = (cum[it.routeIndex] - along).coerceAtLeast(0.0) }

        // Off route for a few fixes in a row -> ask for a new route.
        val accuracy = if (l.hasAccuracy()) l.accuracy.toDouble() else 20.0
        offRouteCount = if (bestD > maxOf(50.0, accuracy * 1.5)) offRouteCount + 1 else 0
        if (offRouteCount >= 3 && !loading && System.currentTimeMillis() - lastRouteAt > 15_000) {
            offRouteCount = 0
            fetchRoute()
        }
        changed.fire()
    }

    fun formatDistance(m: Double): String = when {
        m >= 10_000 -> "${(m / 1000).toInt()} km"
        m >= 1000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000)
        m >= 100 -> "${((m / 50).toInt() * 50)} m"
        else -> "${((m / 10).toInt() * 10)} m"
    }
}
