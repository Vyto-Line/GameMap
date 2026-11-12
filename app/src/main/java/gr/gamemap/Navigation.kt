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
        Explorer.onLocation(LatLng(location.latitude, location.longitude), speed)
        Alerts.onLocation(p, speed, headingTarget)
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

    private val searchLang get() = if (java.util.Locale.getDefault().language == "el") "el-GR" else "en-GB"

    /** TomTom search (fast, good with Greek and English). [typeahead] = the user is still typing. */
    fun tomtom(query: String, near: LatLng?, typeahead: Boolean): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        var url = "https://api.tomtom.com/search/2/search/$q.json?key=${Traffic.key}&limit=8&language=$searchLang" +
            "&typeahead=$typeahead&idxSet=POI,PAD,Addr,Str,Geo"
        if (near != null) url += "&lat=${near.lat}&lon=${near.lon}"
        return parseTomTom(Http.get(url, timeout = 6000))
    }

    private fun parseTomTom(json: String): List<Place> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<Place>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val pos = r.optJSONObject("position") ?: continue
            val addr = r.optJSONObject("address")
            val poi = r.optJSONObject("poi")
            val free = addr?.optString("freeformAddress").orEmpty()
            val street = listOfNotNull(addr?.optString("streetName")?.takeIf { it.isNotBlank() }, addr?.optString("streetNumber")?.takeIf { it.isNotBlank() })
                .joinToString(" ")
            val name = poi?.optString("name")?.takeIf { it.isNotBlank() }
                ?: street.ifBlank { null }
                ?: addr?.optString("municipality")?.takeIf { it.isNotBlank() }
                ?: free
            if (name.isBlank()) continue
            val code = poi?.optJSONArray("classifications")?.optJSONObject(0)?.optString("code").orEmpty()
            val address = free.removePrefix(name).trimStart(',', ' ').ifBlank { addr?.optString("municipality").orEmpty() }
            out += Place(name, address, LatLng(pos.getDouble("lat"), pos.getDouble("lon")), tomtomIcon(code, r.optString("type")))
        }
        return out.distinctBy { it.name + "|" + it.address }
    }

    private fun tomtomIcon(code: String, type: String) = when {
        code == "PETROL_STATION" || code == "ELECTRIC_VEHICLE_STATION" -> "⛽"
        code == "RESTAURANT" -> "🍽"
        code == "CAFE_PUB" -> "☕"
        code == "PARKING_GARAGE" || code == "OPEN_PARKING_AREA" -> "🅿"
        code == "HOSPITAL_POLYCLINIC" || code == "DOCTOR" -> "🏥"
        code == "PHARMACY" -> "💊"
        code == "SHOP" || code == "SHOPPING_CENTER" || code == "MARKET" -> "🛍"
        code == "HOTEL_MOTEL" -> "🏨"
        code == "MUSEUM" || code == "IMPORTANT_TOURIST_ATTRACTION" || code == "TOURIST_ATTRACTION" -> "🏛"
        code == "AIRPORT" -> "✈"
        code == "RAILWAY_STATION" || code == "PUBLIC_TRANSPORT_STOP" -> "🚉"
        code == "BEACH" -> "🏖"
        code == "PARK_RECREATION_AREA" -> "🌳"
        code == "COLLEGE_UNIVERSITY" || code == "SCHOOL" -> "🎓"
        type == "Geography" -> "🏙"
        type == "Street" -> "🛣"
        type == "Point Address" || type == "Address Range" -> "🏠"
        else -> "📍"
    }

    /** Search-as-you-type suggestions: TomTom first, Photon if that fails. */
    fun suggest(query: String, near: LatLng?): List<Place> {
        if (Traffic.key.isNotEmpty()) {
            try { return tomtom(query, near, typeahead = true) } catch (_: Exception) {}
        }
        return photon(query, near)
    }

    /** Photon (OpenStreetMap data); short timeout, it's only a fallback now. */
    fun photon(query: String, near: LatLng?): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        var url = "https://photon.komoot.io/api/?q=$q&limit=8"
        if (near != null) url += "&lat=${near.lat}&lon=${near.lon}&zoom=12&location_bias_scale=0.3"
        val features = JSONObject(Http.get(url, timeout = 4000)).getJSONArray("features")
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

    /** Full search on Enter: TomTom, then Photon, then Nominatim. */
    fun search(query: String, near: LatLng?): List<Place> {
        if (Traffic.key.isNotEmpty()) {
            val t = try { tomtom(query, near, typeahead = false) } catch (e: Exception) { emptyList() }
            if (t.isNotEmpty()) return t
        }
        val photon = try { photon(query, near) } catch (e: Exception) { emptyList() }
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

    fun icon(key: String?, value: String?): String = when {
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

    // ---- places near me by category (Fuel, Food, Parking) ----

    /** Places of an OSM amenity type around [near], nearest first (TomTom, Nominatim as fallback). */
    fun nearby(amenities: List<String>, near: LatLng): List<Place> {
        val cats = amenities.mapNotNull {
            when (it) {
                "fuel" -> "7311"; "charging_station" -> "7309"; "restaurant", "fast_food" -> "7315"
                "cafe" -> "9376"; "parking" -> "7313,7369"; else -> null
            }
        }.distinct().joinToString(",")
        if (Traffic.key.isNotEmpty() && cats.isNotEmpty()) {
            try {
                val url = "https://api.tomtom.com/search/2/nearbySearch/.json?key=${Traffic.key}&lat=${near.lat}&lon=${near.lon}" +
                    "&radius=6000&limit=15&categorySet=$cats&language=$searchLang"
                val r = parseTomTom(Http.get(url, timeout = 6000)).sortedBy { Geo.distance(near, it.pos) }
                if (r.isNotEmpty()) return r
            } catch (_: Exception) {}
        }
        val out = mutableListOf<Place>()
        for (span in listOf(0.025, 0.08)) {
            for (a in amenities) {
                val url = "https://nominatim.openstreetmap.org/search?amenity=$a&format=jsonv2&limit=15&bounded=1" +
                    "&viewbox=${near.lon - span},${near.lat + span},${near.lon + span},${near.lat - span}"
                val arr = try { JSONArray(Http.get(url)) } catch (e: Exception) { continue }
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("type") != a) continue
                    val display = o.optString("display_name")
                    val name = o.optString("name").ifBlank { display.substringBefore(",") }
                    out += Place(
                        name, display.removePrefix(name).trimStart(',', ' ').split(", ").take(2).joinToString(", "),
                        LatLng(o.getString("lat").toDouble(), o.getString("lon").toDouble()),
                        icon("amenity", a),
                    )
                }
            }
            if (out.size >= 4) break
        }
        return out.distinctBy { "${it.name}|${(it.pos.lat * 5000).toInt()}|${(it.pos.lon * 5000).toInt()}" }
            .sortedBy { Geo.distance(near, it.pos) }.take(12)
    }

    // ---- home & work ----

    fun saved(key: String): Place? {
        if (!::appContext.isInitialized) return null
        val raw = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE).getString("place_$key", null) ?: return null
        return try {
            val o = JSONObject(raw)
            Place(o.getString("n"), o.optString("a"), LatLng(o.getDouble("lat"), o.getDouble("lon")), if (key == "home") "🏠" else "💼")
        } catch (e: Exception) { null }
    }

    fun save(key: String, p: Place) {
        val o = JSONObject().put("n", p.name).put("a", p.address).put("lat", p.pos.lat).put("lon", p.pos.lon)
        appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE).edit().putString("place_$key", o.toString()).apply()
    }

    // ---- live ETAs for the home screen (TomTom, cached 5 minutes) ----

    private val etaCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Traffic.Eta>>()

    fun cachedEta(p: Place): Traffic.Eta? = etaCache[key(p)]?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.second

    fun refreshEta(p: Place, from: LatLng): Traffic.Eta? {
        cachedEta(p)?.let { return it }
        if (Geo.distance(from, p.pos) < 150) return null
        val e = try { Traffic.eta(from, p.pos) } catch (e: Exception) { null } ?: return null
        etaCache[key(p)] = System.currentTimeMillis() to e
        return e
    }

    private fun key(p: Place) = "${(p.pos.lat * 10000).toInt()},${(p.pos.lon * 10000).toInt()}"

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
    fun get(url: String, timeout: Int = 15000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = minOf(10000, timeout)
        c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "GameMap/1.0 (Android; personal use)")
        c.setRequestProperty("Accept-Language", java.util.Locale.getDefault().language)
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun post(url: String, body: String, contentType: String = "application/json"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", contentType)
        c.setRequestProperty("User-Agent", "GameMap/1.0 (Android; personal use)")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

