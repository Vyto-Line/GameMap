package gr.gamemap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Fuel prices from fuel-prices.eu (open, no key), which collects the official national price
 * feeds. Most countries report each station's own price. Greece publishes one average per
 * prefecture per day, so there every station shows that area average and GameMap says so.
 */
object Fuel {
    class Station(
        val name: String,
        val address: String,
        val pos: LatLng,
        /** "sp95", "sp98", "diesel", "gpl", "e10"… → € per litre */
        val prices: Map<String, Double>,
        val updated: String,
    )

    class Area(val stations: List<Station>, val country: String) {
        /** Every station has the same 95 price: an area average (Greece), not each pump's. */
        val averageOnly: Boolean = stations.size >= 3 &&
            stations.mapNotNull { it.prices["sp95"] ?: it.prices["diesel"] }.distinct().size == 1
    }

    private lateinit var appContext: Context
    fun init(ctx: Context) { appContext = ctx.applicationContext }

    private val areas = ConcurrentHashMap<String, Pair<Long, Area>>()
    private val countries = ConcurrentHashMap<String, String>()

    /** ISO country code at [p] (Nominatim reverse at country level, kept per 1° square). */
    fun country(p: LatLng): String? {
        val cell = "${Math.floor(p.lat).toInt()}_${Math.floor(p.lon).toInt()}"
        countries[cell]?.let { return it }
        val prefs = if (::appContext.isInitialized) appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE) else null
        prefs?.getString("cc_$cell", null)?.let { countries[cell] = it; return it }
        return try {
            val o = JSONObject(Http.get("https://nominatim.openstreetmap.org/reverse?lat=${p.lat}&lon=${p.lon}&zoom=3&format=jsonv2", timeout = 6000))
            val cc = o.optJSONObject("address")?.optString("country_code")?.uppercase()?.takeIf { it.length == 2 } ?: return null
            countries[cell] = cc
            prefs?.edit()?.putString("cc_$cell", cc)?.apply()
            cc
        } catch (e: Exception) { null }
    }

    /** Stations with prices within [radiusKm] of [p], nearest first (cached ~10 min per ~1 km). */
    fun near(p: LatLng, radiusKm: Int = 6): Area? {
        val key = "${(p.lat * 100).toInt()}_${(p.lon * 100).toInt()}_$radiusKm"
        areas[key]?.let { (at, a) -> if (System.currentTimeMillis() - at < 600_000) return a }
        val cc = country(p) ?: return null
        val url = "https://www.fuel-prices.eu/live/api.php?action=stations&country=$cc&lat=${p.lat}&lng=${p.lon}&radius=$radiusKm&limit=40"
        val o = try { JSONObject(Http.get(url, timeout = 7000)) } catch (e: Exception) { return null }
        if (!o.optBoolean("ok")) return null
        val arr = o.optJSONObject("data")?.optJSONArray("stations") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            val s = arr.getJSONObject(i)
            val pr = s.optJSONObject("prices") ?: return@mapNotNull null
            val prices = HashMap<String, Double>()
            var updated = ""
            for (k in pr.keys()) {
                val f = pr.optJSONObject(k) ?: continue
                val v = f.optDouble("price", Double.NaN)
                if (!v.isNaN() && v > 0) prices[k] = v
                if (updated.isEmpty()) updated = f.optString("updated")
            }
            if (prices.isEmpty()) return@mapNotNull null
            val brand = s.optString("brand").takeIf { it.isNotBlank() && it != "null" }
            val name = s.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: brand ?: "Fuel"
            val address = listOfNotNull(
                s.optString("address").takeIf { it.isNotBlank() && it != "null" },
                s.optString("city").takeIf { it.isNotBlank() && it != "null" },
            ).joinToString(", ")
            Station(prettyName(name), address, LatLng(s.getDouble("lat"), s.getDouble("lng")), prices, updated)
        }
        val a = Area(list, cc)
        areas[key] = System.currentTimeMillis() to a
        return a
    }

    private fun prettyName(n: String) =
        if (n.length > 4 && n == n.uppercase() && n.any { it.isLetter() }) n.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .replace("Eko", "EKO").replace("Bp", "BP") else n

    private val order = listOf("sp95" to "95", "e10" to "E10", "sp98" to "98", "diesel" to "Diesel", "gpl" to "LPG", "lpg" to "LPG")

    /** "95 €1.970 · Diesel €2.035 · LPG €1.210" */
    fun label(prices: Map<String, Double>, short: Boolean = false): String =
        order.mapNotNull { (k, n) -> prices[k]?.let { "$n €${"%.3f".format(java.util.Locale.US, it)}" } }
            .distinctBy { it.substringBefore(" ") }
            .let { if (short) it.take(2) else it }
            .joinToString(" · ")

    /** The date the prices are from: "11 Nov". */
    fun day(updated: String): String = try {
        val d = updated.take(10).split("-")
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        "${d[2].toInt()} ${months[d[1].toInt() - 1]}"
    } catch (e: Exception) { "" }

    /** The price line for a fuel place, or null. */
    fun describe(s: Station, area: Area, short: Boolean = false): String =
        label(s.prices, short) + if (area.averageOnly) " (area average, ${day(s.updated)})" else if (!short) " (${day(s.updated)})" else ""

    /** Fuel stations near [p] as places with their prices, matched with places already found. */
    fun asPlaces(p: LatLng, others: List<Place>): List<Place>? {
        val a = near(p) ?: return null
        val out = ArrayList<Place>()
        for (s in a.stations) {
            val same = others.firstOrNull { Geo.distance(it.pos, s.pos) < 90 }
            val name = same?.name?.takeIf { it != "Fuel" } ?: s.name
            out += Place(name, s.address.ifBlank { same?.address.orEmpty() }, s.pos, "⛽", extra = describe(s, a))
        }
        // stations the price feed doesn't have; where prices are an area average, it's theirs too
        val avg = a.stations.firstOrNull()?.takeIf { a.averageOnly }
        for (o in others) if (out.none { Geo.distance(it.pos, o.pos) < 90 }) {
            out += if (avg != null && o.icon == "⛽" && o.extra.isBlank()) o.copy(extra = describe(avg, a)) else o
        }
        return out.sortedBy { Geo.distance(p, it.pos) }
            .fold(ArrayList<Place>()) { acc, x -> if (acc.none { it.name == x.name && Geo.distance(it.pos, x.pos) < 40 }) acc += x; acc }
    }

    /** Prices for one place (a fuel station picked from search), or null. */
    fun forPlace(pl: Place): String? {
        val a = near(pl.pos, radiusKm = 2) ?: return null
        val s = a.stations.minByOrNull { Geo.distance(it.pos, pl.pos) }?.takeIf { Geo.distance(it.pos, pl.pos) < 120 } ?: return null
        return describe(s, a)
    }
}
