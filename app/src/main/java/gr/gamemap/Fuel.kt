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
        val brand: String = "",
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
            Station(prettyName(name), address, LatLng(s.getDouble("lat"), s.getDouble("lng")), prices, updated, brand?.let { prettyName(it) }.orEmpty())
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

    /** For a car list row: just 95 and diesel. */
    fun brief(extra: String): String {
        val parts = extra.substringBefore(" (").split(" · ")
        val pick = parts.filter { it.startsWith("95 ") || it.startsWith("Diesel ") }
        return (pick.ifEmpty { parts.take(2) }).joinToString(" · ") + if ("area average" in extra) " (area avg.)" else ""
    }

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
            val same = others.firstOrNull { it.icon == "⛽" && Geo.distance(it.pos, s.pos) < 90 }
            val name = same?.name?.takeIf { it != "Fuel" } ?: s.name
            out += Place(name, s.address.ifBlank { same?.address.orEmpty() }, s.pos, "⛽", extra = describe(s, a),
                prices = s.prices, areaAvg = a.averageOnly, brand = same?.brand?.ifBlank { null } ?: s.brand)
        }
        // stations the price feed doesn't have; where prices are an area average, it's theirs too
        val avg = a.stations.firstOrNull()?.takeIf { a.averageOnly }
        for (o in others) if (out.none { Geo.distance(it.pos, o.pos) < 90 && (o.icon == "⛽" || it.name == o.name) }) {
            out += if (avg != null && o.icon == "⛽" && o.extra.isBlank()) o.copy(extra = describe(avg, a), prices = avg.prices, areaAvg = true) else o
        }
        return out.sortedBy { Geo.distance(p, it.pos) }
            .fold(ArrayList<Place>()) { acc, x -> if (acc.none { it.name == x.name && Geo.distance(it.pos, x.pos) < 40 }) acc += x; acc }
    }

    // ------------------------------------------------------------ your fuels, chargers, brands

    /** The fuels people pick from, with the price feed's names for each. */
    val fuelKinds = listOf(
        Triple("sp95", "95", listOf("sp95", "e10")),
        Triple("sp98", "98/100", listOf("sp98", "sp100")),
        Triple("diesel", "Diesel", listOf("diesel")),
        Triple("lpg", "LPG", listOf("gpl", "lpg")),
        Triple("cng", "CNG", listOf("cng", "gnv")),
    )
    val plugKinds = listOf("type2" to "Type 2", "ccs" to "CCS", "chademo" to "CHAdeMO", "tesla" to "Tesla")
    val brandList = listOf("EKO", "Shell", "BP", "Avin", "Aegean", "Revoil", "Elin", "Cyclon", "Jet Oil", "Silkoil", "AP")

    fun plugKind(t: String): String? = when {
        t.contains("CCS", true) || t.contains("Combo", true) -> "ccs"
        t.contains("Chademo", true) -> "chademo"
        t.contains("Tesla", true) -> "tesla"
        t.contains("Type2", true) || t.contains("Type 2", true) -> "type2"
        else -> null
    }

    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)
    private fun set(key: String, def: Set<String>) = if (::appContext.isInitialized) prefs().getStringSet(key, def)?.toSet() ?: def else def

    /** The fuels you use (their prices show on the map and in the lists). */
    var myFuels: Set<String>
        get() = set("fuelMine", setOf("sp95", "diesel")).ifEmpty { setOf("sp95") }
        set(v) { prefs().edit().putStringSet("fuelMine", v).apply() }
    /** The fuel the list is sorted and priced by ("ev" = chargers). */
    var chosen: String
        get() = (if (::appContext.isInitialized) prefs().getString("fuelChosen", null) else null)?.takeIf { it in myFuels || (it == "ev" && showChargers) } ?: myFuels.first()
        set(v) { prefs().edit().putString("fuelChosen", v).apply() }
    /** "cheapest", "nearest" or "route" */
    var sort: String
        get() = if (::appContext.isInitialized) prefs().getString("fuelSort", "cheapest") ?: "cheapest" else "cheapest"
        set(v) { prefs().edit().putString("fuelSort", v).apply() }
    var showChargers: Boolean
        get() = ::appContext.isInitialized && prefs().getBoolean("fuelChargers", true)
        set(v) { prefs().edit().putBoolean("fuelChargers", v).apply() }
    var plugs: Set<String>
        get() = set("fuelPlugs", plugKinds.map { it.first }.toSet())
        set(v) { prefs().edit().putStringSet("fuelPlugs", v).apply() }
    var fastOnly: Boolean
        get() = ::appContext.isInitialized && prefs().getBoolean("fuelFast", false)
        set(v) { prefs().edit().putBoolean("fuelFast", v).apply() }
    /** Empty = every brand. */
    var brands: Set<String>
        get() = set("fuelBrands", emptySet())
        set(v) { prefs().edit().putStringSet("fuelBrands", v).apply() }

    fun reset() {
        prefs().edit().remove("fuelMine").remove("fuelChosen").remove("fuelSort").remove("fuelChargers")
            .remove("fuelPlugs").remove("fuelFast").remove("fuelBrands").apply()
    }

    fun nameOf(kind: String) = if (kind == "ev") "EV" else fuelKinds.firstOrNull { it.first == kind }?.second ?: kind

    /** This place's price for [kind], or null. */
    fun priceOf(p: Place, kind: String): Double? {
        val keys = fuelKinds.firstOrNull { it.first == kind }?.third ?: return null
        return keys.firstNotNullOfOrNull { p.prices[it] }
    }

    fun isCharger(p: Place) = p.icon == "🔌"

    /** How far off the route [p] is (metres there and back, roughly) and whether it's still ahead; null when not driving. */
    fun detour(p: Place): Double? {
        val r = NavEngine.current ?: return null
        if (!NavEngine.active || r.points.size < 2) return null
        val (along, idx) = r.alongOf(p.pos, 0, r.points.size)
        if (along < NavEngine.along - 50) return null // already behind us
        val off = Geo.distanceToSegment(p.pos, r.points[idx], r.points[minOf(idx + 1, r.points.size - 1)])
        return off * 2
    }

    /**
     * Your filters and sort applied to a "fuel nearby" list: chargers only if you want them (with your
     * plugs and power), fuel stations of your brands, then cheapest / nearest / smallest detour.
     */
    fun arrange(list: List<Place>, near: LatLng): List<Place> {
        val kind = chosen
        val brandSet = brands
        val keep = list.filter { p ->
            if (isCharger(p)) {
                showChargers && (p.plugs.isEmpty() || p.plugs.any { it in plugs }) && (!fastOnly || p.kw == 0 || p.kw >= 50)
            } else {
                (brandSet.isEmpty() || brandSet.any { b -> p.brand.contains(b, true) || p.name.contains(b, true) }) &&
                    // a station's own price list without your fuel: it doesn't sell it
                    (p.areaAvg || p.prices.isEmpty() || kind == "ev" || priceOf(p, kind) != null)
            }
        }.let { l -> if (kind == "ev") l.filter { isCharger(it) } else l.filterNot { isCharger(it) } + l.filter { isCharger(it) } }
        val dist = { p: Place -> Geo.distance(near, p.pos) }
        return when (sort) {
            "route" -> keep.mapNotNull { p -> detour(p)?.let { p to it } }.sortedBy { it.second }.map { it.first }.ifEmpty { keep.sortedBy(dist) }
            "nearest" -> keep.sortedBy(dist)
            else -> keep.sortedWith(compareBy<Place> { if (isCharger(it) && kind != "ev") 1 else 0 }
                .thenBy { if (kind == "ev") -(it.kw.toDouble()) else priceOf(it, kind) ?: 99.0 }.thenBy(dist))
        }
    }

    /** Price tags for the map while the fuel list is open: (where, "D €1.74"). */
    @Volatile var pins: List<Pair<LatLng, String>> = emptyList()

    /** Prices for one place (a fuel station picked from search), or null. */
    fun forPlace(pl: Place): String? {
        val a = near(pl.pos, radiusKm = 2) ?: return null
        val s = a.stations.minByOrNull { Geo.distance(it.pos, pl.pos) }?.takeIf { Geo.distance(it.pos, pl.pos) < 120 } ?: return null
        return describe(s, a)
    }
}
