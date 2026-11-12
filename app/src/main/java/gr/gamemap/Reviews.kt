package gr.gamemap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Star ratings and reviews from Google Places (the new Places API). It needs the user's own
 * Google Maps Platform key (Settings › Reviews); without one, the phone opens the place in
 * Google Maps instead, where its reviews are.
 */
object Reviews {
    class Review(val author: String, val stars: Int, val text: String, val when_: String)
    class Info(val rating: Double?, val count: Int, val reviews: List<Review>, val mapsUrl: String?)

    private lateinit var appContext: Context
    fun init(ctx: Context) { appContext = ctx.applicationContext }

    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)

    var key: String
        get() = if (::appContext.isInitialized) prefs().getString("googlePlacesKey", "").orEmpty() else ""
        set(v) { prefs().edit().putString("googlePlacesKey", v.trim()).apply(); failedUntil = 0 }

    val enabled get() = key.isNotBlank()
    @Volatile var lastError: String? = null
        private set
    @Volatile private var failedUntil = 0L

    private val infos = ConcurrentHashMap<String, Info>()
    private fun id(p: Place) = "${p.name}@${"%.4f".format(java.util.Locale.US, p.pos.lat)},${"%.4f".format(java.util.Locale.US, p.pos.lon)}"

    fun cached(p: Place): Info? = infos[id(p)]

    /** Google Maps, at this place (works without a key: its reviews are one tap away there). */
    fun mapsLink(p: Place): String =
        "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode("${p.name} ${p.pos.lat},${p.pos.lon}", "UTF-8")

    private fun post(url: String, body: JSONObject, fields: String): JSONObject? {
        if (!enabled || System.currentTimeMillis() < failedUntil) return null
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("X-Goog-Api-Key", key)
        c.setRequestProperty("X-Goog-FieldMask", fields)
        return try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) {
                val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
                lastError = try { JSONObject(err).optJSONObject("error")?.optString("message") } catch (e: Exception) { null } ?: "HTTP ${c.responseCode}"
                if (c.responseCode in 400..403) failedUntil = System.currentTimeMillis() + 10 * 60_000
                null
            } else { lastError = null; JSONObject(c.inputStream.bufferedReader().readText()) }
        } catch (e: Exception) { null } finally { c.disconnect() }
    }

    /** Rating and reviews of one place (the destination); null without a key or on failure. */
    fun details(p: Place): Info? {
        infos[id(p)]?.let { if (it.reviews.isNotEmpty() || it.rating == null) return it }
        val body = JSONObject()
            .put("textQuery", p.name)
            .put("maxResultCount", 3)
            .put("languageCode", if (java.util.Locale.getDefault().language == "el") "el" else "en")
            .put("locationBias", JSONObject().put("circle", JSONObject()
                .put("center", JSONObject().put("latitude", p.pos.lat).put("longitude", p.pos.lon))
                .put("radius", 250.0)))
        val o = post("https://places.googleapis.com/v1/places:searchText", body,
            "places.location,places.rating,places.userRatingCount,places.reviews,places.googleMapsUri") ?: return null
        val arr = o.optJSONArray("places") ?: JSONArray()
        val best = (0 until arr.length()).map { arr.getJSONObject(it) }
            .minByOrNull { g -> g.optJSONObject("location")?.let { Geo.distance(p.pos, LatLng(it.optDouble("latitude"), it.optDouble("longitude"))) } ?: 1e9 }
            ?.takeIf { g -> g.optJSONObject("location")?.let { Geo.distance(p.pos, LatLng(it.optDouble("latitude"), it.optDouble("longitude"))) < 300 } == true }
        val info = if (best == null) Info(null, 0, emptyList(), null) else {
            val rv = best.optJSONArray("reviews") ?: JSONArray()
            Info(
                best.optDouble("rating").takeIf { !it.isNaN() },
                best.optInt("userRatingCount"),
                (0 until rv.length()).map { rv.getJSONObject(it) }.map { r ->
                    Review(
                        r.optJSONObject("authorAttribution")?.optString("displayName").orEmpty(),
                        r.optInt("rating"),
                        (r.optJSONObject("text") ?: r.optJSONObject("originalText"))?.optString("text").orEmpty(),
                        r.optString("relativePublishTimeDescription"),
                    )
                },
                best.optString("googleMapsUri").takeIf { it.isNotBlank() },
            )
        }
        infos[id(p)] = info
        return info
    }

    /**
     * Star ratings for a list of nearby places in one request (Google's places of the same kind
     * around [near], matched by position). Returns the places with "★ 4.3 (120)" filled in.
     */
    fun rate(list: List<Place>, near: LatLng, kinds: List<String>): List<Place> {
        if (!enabled || list.isEmpty()) return list
        val types = kinds.flatMap {
            when (it) {
                "fuel" -> listOf("gas_station"); "charging_station" -> listOf("electric_vehicle_charging_station")
                "parking" -> listOf("parking"); "restaurant", "fast_food" -> listOf("restaurant", "fast_food_restaurant")
                "cafe" -> listOf("cafe"); else -> emptyList()
            }
        }.distinct()
        if (types.isEmpty()) return list
        val far = list.maxOf { Geo.distance(near, it.pos) }.coerceIn(500.0, 8000.0)
        val body = JSONObject()
            .put("includedTypes", JSONArray(types))
            .put("maxResultCount", 20)
            .put("rankPreference", "DISTANCE")
            .put("locationRestriction", JSONObject().put("circle", JSONObject()
                .put("center", JSONObject().put("latitude", near.lat).put("longitude", near.lon))
                .put("radius", far + 200)))
        val o = post("https://places.googleapis.com/v1/places:searchNearby", body,
            "places.location,places.rating,places.userRatingCount") ?: return list
        val arr = o.optJSONArray("places") ?: return list
        val g = (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { x ->
            val l = x.optJSONObject("location") ?: return@mapNotNull null
            val r = x.optDouble("rating").takeIf { !it.isNaN() } ?: return@mapNotNull null
            Triple(LatLng(l.optDouble("latitude"), l.optDouble("longitude")), r, x.optInt("userRatingCount"))
        }
        return list.map { p ->
            val m = g.minByOrNull { Geo.distance(it.first, p.pos) }?.takeIf { Geo.distance(it.first, p.pos) < 80 } ?: return@map p
            infos.putIfAbsent(id(p), Info(m.second, m.third, emptyList(), null))
            p.copy(rating = m.second, ratings = m.third)
        }
    }

    /** "★ 4.3 (1.2k)" */
    fun stars(rating: Double?, count: Int): String? {
        if (rating == null) return null
        val c = if (count >= 1000) "%.1fk".format(java.util.Locale.US, count / 1000.0) else "$count"
        return "★ ${"%.1f".format(java.util.Locale.US, rating)} ($c)"
    }
}
