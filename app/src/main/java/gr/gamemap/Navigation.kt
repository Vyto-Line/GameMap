package gr.gamemap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp

/**
 * GPS position and heading from the phone's own providers (no Play services needed),
 * smoothed for display: the arrow glides between fixes, keeps moving between them at
 * the current speed, and turns gradually.
 */
object LocationTracker : LocationListener {
    val changed = Signal()
    var last: Location? = null
        private set
    private var started = false

    // display smoothing
    private var fromPos: LatLng? = null
    private var toPos: LatLng? = null
    private var fixAt = 0L
    private var speed = 0f
    private var moveBearing = 0f
    private var headingTarget = 0f
    private var compass: Float? = null
    private var lastTick = 0L

    /** Heading shown on the map, degrees (smoothed). */
    var heading = 0f
        private set

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
        val providers = mutableListOf(LocationManager.GPS_PROVIDER)
        providers += if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER))
            LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
        for (p in providers) {
            try {
                if (lm.allProviders.contains(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, this, looper)
                    if (last == null) lm.getLastKnownLocation(p)?.let { onLocationChanged(it) }
                }
            } catch (_: Exception) {
            }
        }
        started = true
        changed.fire()
    }

    /** Raw latest position (for routing and search). */
    val position: LatLng? get() = last?.let { LatLng(it.latitude, it.longitude) }
    val accuracy: Float get() = last?.takeIf { it.hasAccuracy() }?.accuracy ?: 0f
    val speedMps: Float get() = speed

    /** Smoothed position for drawing. */
    fun displayPosition(): LatLng? {
        val to = toPos ?: return null
        val from = fromPos ?: to
        val t = (SystemClock.uptimeMillis() - fixAt) / 1000.0
        if (t < 1.0) return Geo.lerp(from, to, t)
        if (speed < 2f) return to
        // Between fixes, keep rolling forward at the current speed (up to 1.5 s).
        return Geo.offset(to, moveBearing, speed * (t - 1.0).coerceAtMost(1.5))
    }

    /** Advances the heading animation. Returns true while anything is still moving. */
    fun tick(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastTick).coerceIn(0, 100)
        lastTick = now
        val c = compass
        val target = if (speed < 1.0f && c != null && !NavEngine.active) c else headingTarget
        val diff = Geo.angleDiff(heading, target)
        val turning = abs(diff) > 0.3f
        heading = if (turning) (heading + diff * (1 - exp(-dt / 260.0)).toFloat() + 360f) % 360f else target
        val gliding = toPos != null && (now - fixAt < 1000 || speed >= 2f)
        return turning || gliding
    }

    /** Phone compass (used only when standing still). Pass null to stop using it. */
    fun setCompass(deg: Float?) {
        compass = deg
        if (deg != null && speed < 1.0f) changed.fire()
    }

    override fun onLocationChanged(location: Location) {
        // Prefer GPS; ignore a coarser fix that arrives right after a GPS one.
        val prev = last
        if (prev != null && prev.provider == LocationManager.GPS_PROVIDER &&
            location.provider != LocationManager.GPS_PROVIDER &&
            location.time - prev.time < 10_000
        ) return
        // Drop wildly inaccurate fixes once we have a decent one.
        if (prev != null && location.hasAccuracy() && location.accuracy > 150 &&
            prev.hasAccuracy() && prev.accuracy < 50 && location.time - prev.time < 30_000
        ) return

        last = location
        speed = if (location.hasSpeed()) location.speed else 0f
        var p = LatLng(location.latitude, location.longitude)
        val snapped = if (speed > 1f) NavEngine.snap(p) else null
        if (snapped != null) {
            p = snapped.first
            headingTarget = snapped.second
        } else if (location.hasBearing() && speed > 1.5f) {
            headingTarget = location.bearing
        }
        if (speed > 1f) moveBearing = headingTarget

        val firstFix = toPos == null
        fromPos = if (firstFix) p else displayPosition()
        toPos = p
        fixAt = SystemClock.uptimeMillis()
        if (firstFix) heading = headingTarget

        NavEngine.onLocation(location)
        changed.fire()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}

/** A place found by search. [icon] is a small emoji for its category. */
data class Place(val name: String, val address: String, val pos: LatLng, val icon: String = "📍")

/** Destination search: Photon for live suggestions, Nominatim as a fallback, plus recents. */
object Places {
    private lateinit var appContext: Context

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /** Search-as-you-type suggestions near [near] (Photon, OpenStreetMap data). */
    fun suggest(query: String, near: LatLng?): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        var url = "https://photon.komoot.io/api/?q=$q&limit=8"
        if (near != null) url += "&lat=${near.lat}&lon=${near.lon}&zoom=12&location_bias_scale=0.3"
        val features = JSONObject(Http.get(url)).getJSONArray("features")
        val out = mutableListOf<Place>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            fun s(k: String) = p.optString(k).takeIf { it.isNotBlank() && it != "null" }
            val street = listOfNotNull(s("street"), s("housenumber")).joinToString(" ").ifBlank { null }
            val name = s("name") ?: street ?: s("city") ?: continue
            val address = listOfNotNull(
                street.takeIf { it != name },
                s("district") ?: s("locality"),
                s("city").takeIf { it != name },
                s("state").takeIf { s("city") == null },
                s("country"),
            ).distinct().joinToString(", ")
            out += Place(name, address, LatLng(c.getDouble(1), c.getDouble(0)), icon(s("osm_key"), s("osm_value")))
        }
        return out.distinctBy { it.name + "|" + it.address }
    }

    /** Full search on Enter: Photon first, Nominatim if Photon finds nothing. */
    fun search(query: String, near: LatLng?): List<Place> {
        val photon = try { suggest(query, near) } catch (e: Exception) { emptyList() }
        if (photon.isNotEmpty()) return photon
        val q = URLEncoder.encode(query, "UTF-8")
        var url = "https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&limit=8"
        if (near != null) {
            val d = 0.6
            url += "&viewbox=${near.lon - d},${near.lat + d},${near.lon + d},${near.lat - d}"
        }
        val arr = JSONArray(Http.get(url))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            val name = o.optString("name").ifBlank { display.substringBefore(",") }
            Place(
                name, display.removePrefix(name).trimStart(',', ' '),
                LatLng(o.getString("lat").toDouble(), o.getString("lon").toDouble()),
                icon(o.optString("category"), o.optString("type")),
            )
        }
    }

    private fun icon(key: String?, value: String?): String = when {
        value == "fuel" || value == "charging_station" -> "⛽"
        value in setOf("restaurant", "fast_food", "food_court") -> "🍽"
        value == "cafe" -> "☕"
        value in setOf("bar", "pub", "nightclub") -> "🍺"
        value == "parking" -> "🅿"
        value in setOf("hospital", "clinic", "doctors") -> "🏥"
        value == "pharmacy" -> "💊"
        value in setOf("school", "university", "college") -> "🎓"
        value in setOf("supermarket", "convenience") -> "🛒"
        key == "shop" -> "🛍"
        value in setOf("hotel", "hostel", "guest_house", "motel") -> "🏨"
        value in setOf("museum", "attraction", "monument", "castle", "ruins") -> "🏛"
        key == "aeroway" -> "✈"
        key == "railway" || value == "bus_station" -> "🚉"
        value in setOf("beach") -> "🏖"
        key == "leisure" || value in setOf("park", "forest", "wood", "peak") -> "🌳"
        key == "place" -> "🏙"
        key == "highway" -> "🛣"
        key == "building" || value == "house" -> "🏠"
        else -> "📍"
    }

    // ---- recent destinations ----

    fun recents(): List<Place> {
        if (!::appContext.isInitialized) return emptyList()
        val raw = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE).getString("recents", "[]")
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Place(o.getString("n"), o.optString("a"), LatLng(o.getDouble("lat"), o.getDouble("lon")), "🕘")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecent(p: Place) {
        val list = (listOf(p) + recents().filterNot { Geo.distance(it.pos, p.pos) < 30 }).take(8)
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("n", it.name).put("a", it.address).put("lat", it.pos.lat).put("lon", it.pos.lon))
        }
        appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE).edit().putString("recents", a.toString()).apply()
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

    fun start(ctx: Context, place: Place) {
        appContext = ctx.applicationContext
        Places.addRecent(place)
        destination = place.pos
        destinationName = place.name
        arrived = false
        route = emptyList(); steps = emptyList(); nextStep = -1
        MapState.recenter()
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

    /** If p is within 30 m of the route, the closest point on it and the road's direction there. */
    fun snap(p: LatLng): Pair<LatLng, Float>? {
        if (!active || route.size < 2) return null
        var best: Pair<LatLng, Double>? = null
        var seg = 0
        val lo = maxOf(0, nearest - 5); val hi = minOf(route.size - 1, nearest + 60)
        for (i in lo until hi) {
            val pr = Geo.project(p, route[i], route[i + 1])
            if (best == null || pr.second < best.second) { best = pr; seg = i }
        }
        val b = best ?: return null
        if (b.second > 30) return null
        return b.first to Geo.bearing(route[seg], route[seg + 1])
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
                    error = "No route found"
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
            else -> {
                val base = when (mod) {
                    "uturn" -> "Make a U-turn$onto"
                    "straight" -> "Go straight$onto"
                    else -> "Turn $mod$onto"
                }
                if (type == "end of road") "At the end of the road, ${base.replaceFirstChar { it.lowercase() }}" else base
            }
        }
    }

    private fun apply(p: Parsed) {
        route = p.points
        cum = DoubleArray(route.size)
        for (i in 1 until route.size) cum[i] = cum[i - 1] + Geo.distance(route[i - 1], route[i])
        secondsPerMeter = if (p.distance > 0) p.duration / p.distance else 0.08
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

    fun arrivalClock(): String {
        val t = System.currentTimeMillis() + (remainingSeconds * 1000).toLong()
        return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
    }
}
