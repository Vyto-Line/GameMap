package gr.gamemap

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** One lane at a junction: the arrows painted on it and whether it leads where we're going. */
class Lane(val directions: List<String>, val valid: Boolean)

/** One maneuver of a route. */
class NavStep(
    val type: String,
    /** "left", "right", "slight left", "uturn", "straight", "arrive", ... */
    val direction: String,
    val road: String,
    val exit: Int,
    val pos: LatLng,
    val lanes: List<Lane>?,
) {
    /** Index of the route point where this maneuver happens. */
    var routeIndex = 0
    val text: String get() = Phrases.instruction(this, Phrases.ENGLISH)
}

/** A stretch of a route, in metres from its start. */
class Stretch(val start: Double, val end: Double, val value: Int, val extra: Int = 0)

/** One way to drive somewhere, with traffic and speed limits once TomTom has looked at it. */
class RouteOption(
    val points: List<LatLng>,
    val steps: List<NavStep>,
    val distance: Double,
    /** OSRM's time without traffic. */
    val duration: Double,
    val via: String,
    val hasToll: Boolean = false,
    val hasMotorway: Boolean = false,
) {
    val cum = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distance(points[i - 1], points[i])
    }
    val length get() = cum.lastOrNull() ?: 0.0

    /** TomTom's time with traffic for the whole route (null until known). */
    var trafficSeconds: Int? = null
    var delaySeconds = 0
    /** Traffic stretches: value = TomTom magnitude 1..4, extra = delay seconds. */
    var traffic: List<Stretch> = emptyList()
    /** Speed limits: value = km/h. */
    var limits: List<Stretch> = emptyList()

    /** Best time estimate in seconds. */
    val seconds: Double get() = trafficSeconds?.toDouble() ?: duration

    init {
        var from = 0
        for (s in steps) {
            var best = from; var bestD = Double.MAX_VALUE
            for (i in from until minOf(points.size, from + 3000)) {
                val d = Geo.distance(points[i], s.pos)
                if (d < bestD) { bestD = d; best = i }
                if (d < 1.0) break
            }
            s.routeIndex = best
            from = best
        }
    }

    /** How far along the route (metres) the point nearest to [p] is, searching from point [hint]. */
    fun alongOf(p: LatLng, hint: Int, window: Int = 400): Pair<Double, Int> {
        var best = hint; var bestD = Double.MAX_VALUE
        val lo = maxOf(0, hint - 20); val hi = minOf(points.size - 1, hint + window)
        for (i in lo until hi) {
            val d = Geo.distanceToSegment(p, points[i], points[i + 1])
            if (d < bestD) { bestD = d; best = i }
        }
        if (points.size < 2) return 0.0 to 0
        val along = cum[best] + Geo.distance(points[best], p).coerceAtMost(cum[best + 1] - cum[best])
        return along to best
    }

    /** True if the two routes share most of their way. */
    fun similarTo(o: RouteOption): Boolean {
        val sample = o.points.filterIndexed { i, _ -> i % maxOf(1, o.points.size / 40) == 0 }
        var near = 0; var hint = 0
        for (p in sample) {
            val (_, idx) = alongOf(p, hint, points.size)
            hint = idx
            if (Geo.distanceToSegment(p, points[idx], points[minOf(idx + 1, points.size - 1)]) < 35) near++
        }
        return near >= sample.size * 0.9
    }
}

/** The public OSRM router: geometry, maneuvers and lanes. */
object Osrm {
    fun routes(from: LatLng, to: LatLng, alternatives: Boolean, heading: Float? = null): List<RouteOption> {
        var url = "https://router.project-osrm.org/route/v1/driving/" +
            "${from.lon},${from.lat};${to.lon},${to.lat}" +
            "?overview=full&geometries=geojson&steps=true"
        if (alternatives) url += "&alternatives=3"
        if (heading != null) url += "&bearings=${heading.toInt().coerceIn(0, 359)},60;"
        return parseAll(Http.get(url))
    }

    fun parseAll(json: String): List<RouteOption> {
        val o = JSONObject(json)
        if (o.optString("code") != "Ok") throw RuntimeException(o.optString("code"))
        val arr = o.getJSONArray("routes")
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    private fun parse(r: JSONObject): RouteOption {
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until coords.length()).map {
            val c = coords.getJSONArray(it)
            LatLng(c.getDouble(1), c.getDouble(0))
        }
        val out = mutableListOf<NavStep>()
        val legs = r.getJSONArray("legs")
        var summary = ""
        var toll = false; var motorway = false
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            if (summary.isBlank()) summary = leg.optString("summary")
            val s = leg.getJSONArray("steps")
            for (i in 0 until s.length()) {
                val st = s.getJSONObject(i)
                val m = st.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val type = m.optString("type")
                val mod = m.optString("modifier", "straight")
                st.optJSONArray("intersections")?.let { ins ->
                    for (k in 0 until ins.length()) ins.getJSONObject(k).optJSONArray("classes")?.let { cl ->
                        for (q in 0 until cl.length()) when (cl.optString(q)) {
                            "toll" -> toll = true
                            "motorway" -> motorway = true
                        }
                    }
                }
                var lanes: List<Lane>? = null
                st.optJSONArray("intersections")?.optJSONObject(0)?.optJSONArray("lanes")?.let { la ->
                    lanes = (0 until la.length()).map { k ->
                        val lo = la.getJSONObject(k)
                        val ind = lo.optJSONArray("indications") ?: JSONArray()
                        Lane((0 until ind.length()).map { ind.getString(it) }, lo.optBoolean("valid"))
                    }
                }
                out += NavStep(
                    type = type,
                    direction = if (type == "arrive") "arrive" else mod,
                    road = st.optString("name"),
                    exit = m.optInt("exit", 0),
                    pos = LatLng(loc.getDouble(1), loc.getDouble(0)),
                    lanes = lanes?.takeIf { l2 -> l2.size >= 2 },
                )
            }
        }
        val via = summary.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().take(2).joinToString(" & ")
        return RouteOption(pts, out, r.getDouble("distance"), r.getDouble("duration"), via, toll, motorway)
    }
}

/**
 * TomTom looks at a route we already have (by following points along it) and tells us how long
 * it takes in traffic, where the jams are and the speed limits. Geometry and turns stay OSRM's.
 */
object TomTomTime {
    class Result(val seconds: Int, val delay: Int, val traffic: List<Stretch>, val limits: List<Stretch>)

    /** Evaluates [r] from [fromAlong] metres to its end. [departAt] (ms) asks for predicted traffic. */
    fun evaluate(r: RouteOption, fromAlong: Double, start: LatLng? = null, departAt: Long? = null): Result? {
        if (!Traffic.active || r.points.size < 2) return null
        val total = r.length
        val remaining = total - fromAlong
        if (remaining < 50) return null
        val step = maxOf(remaining / 90, 25.0)
        val pts = ArrayList<LatLng>()
        start?.let { pts += it }
        var next = fromAlong
        for (i in r.points.indices) {
            if (r.cum[i] + 0.1 >= next) { pts += r.points[i]; next = r.cum[i] + step }
        }
        if (pts.last() != r.points.last()) pts += r.points.last()
        if (pts.size < 2) return null
        val body = JSONObject().put("supportingPoints", JSONArray().apply {
            pts.forEach { put(JSONObject().put("latitude", it.lat).put("longitude", it.lon)) }
        })
        val a = pts.first(); val b = pts.last()
        var url = "https://api.tomtom.com/routing/1/calculateRoute/${a.lat},${a.lon}:${b.lat},${b.lon}/json" +
            "?key=${Traffic.key}&traffic=true&travelMode=car&routeRepresentation=polyline" +
            "&sectionType=traffic&sectionType=speedLimit"
        if (departAt != null) {
            url += "&departAt=" + SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(departAt))
        }
        val o = JSONObject(Http.post(url, body.toString()))
        val route = o.getJSONArray("routes").getJSONObject(0)
        val sum = route.getJSONObject("summary")
        // Where each TomTom point sits along our route.
        val tPts = ArrayList<LatLng>()
        val legs = route.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val p = legs.getJSONObject(l).getJSONArray("points")
            for (i in 0 until p.length()) {
                val q = p.getJSONObject(i); tPts += LatLng(q.getDouble("latitude"), q.getDouble("longitude"))
            }
        }
        val along = DoubleArray(tPts.size)
        var hint = r.alongOf(tPts.firstOrNull() ?: a, 0, r.points.size).second
        for (i in tPts.indices) {
            val (d, idx) = r.alongOf(tPts[i], hint)
            along[i] = d; hint = idx
        }
        val traffic = mutableListOf<Stretch>()
        val limits = mutableListOf<Stretch>()
        route.optJSONArray("sections")?.let { s ->
            for (i in 0 until s.length()) {
                val sec = s.getJSONObject(i)
                val si = sec.optInt("startPointIndex").coerceIn(0, along.size - 1)
                val ei = sec.optInt("endPointIndex").coerceIn(0, along.size - 1)
                if (along.isEmpty()) break
                when (sec.optString("sectionType")) {
                    "TRAFFIC" -> traffic += Stretch(along[si], along[ei], sec.optInt("magnitudeOfDelay", 1), sec.optInt("delayInSeconds", 0))
                    "SPEED_LIMIT" -> sec.optInt("maxSpeedLimitInKmh", 0).takeIf { it > 0 }?.let {
                        limits += Stretch(along[si], along[ei], it)
                    }
                }
            }
        }
        return Result(sum.getInt("travelTimeInSeconds"), sum.optInt("trafficDelayInSeconds", 0), traffic, limits)
    }
}

/** Spoken and written instructions in English or Greek. */
object Phrases {
    const val ENGLISH = "en"
    const val GREEK = "el"

    private fun en(dir: String) = when (dir) {
        "uturn" -> "make a U-turn"
        "straight" -> "go straight"
        "sharp left" -> "turn sharp left"
        "sharp right" -> "turn sharp right"
        "slight left" -> "bear left"
        "slight right" -> "bear right"
        "left" -> "turn left"
        "right" -> "turn right"
        else -> "continue"
    }

    private fun el(dir: String) = when (dir) {
        "uturn" -> "κάντε αναστροφή"
        "straight" -> "συνεχίστε ευθεία"
        "sharp left" -> "στρίψτε απότομα αριστερά"
        "sharp right" -> "στρίψτε απότομα δεξιά"
        "slight left" -> "κρατήστε αριστερά"
        "slight right" -> "κρατήστε δεξιά"
        "left" -> "στρίψτε αριστερά"
        "right" -> "στρίψτε δεξιά"
        else -> "συνεχίστε"
    }

    private val ordEl = listOf("", "πρώτη", "δεύτερη", "τρίτη", "τέταρτη", "πέμπτη", "έκτη", "έβδομη", "όγδοη")
    private val ordEn = listOf("", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth")

    /** The instruction, capitalised, without a distance. */
    fun instruction(s: NavStep, lang: String): String {
        val name = s.road
        val g = lang == GREEK
        val onto = if (name.isBlank()) "" else if (g) " στην $name" else " onto $name"
        val base = when (s.type) {
            "arrive" -> if (g) "Φτάσατε στον προορισμό σας" else "You have arrived"
            "depart" -> if (g) "Ξεκινήστε$onto" else "Head out$onto"
            "roundabout", "rotary" ->
                if (s.exit in 1..8) {
                    if (g) "Στον κυκλικό κόμβο, πάρτε την ${ordEl[s.exit]} έξοδο$onto"
                    else "At the roundabout, take the ${ordEn[s.exit]} exit$onto"
                } else if (g) "Μπείτε στον κυκλικό κόμβο" else "Enter the roundabout"
            "exit roundabout", "exit rotary" -> if (g) "Βγείτε από τον κυκλικό κόμβο$onto" else "Exit the roundabout$onto"
            "fork" -> {
                val side = if (s.direction.contains("left")) (if (g) "αριστερά" else "left") else (if (g) "δεξιά" else "right")
                if (g) "Στη διχάλα, κρατήστε $side$onto" else "At the fork, keep $side$onto"
            }
            "merge" -> if (g) "Συγχωνευθείτε$onto" else "Merge$onto"
            "on ramp" -> if (g) "Πάρτε τη ράμπα$onto" else "Take the ramp$onto"
            "off ramp" -> if (g) "Πάρτε την έξοδο$onto" else "Take the exit$onto"
            "end of road" -> if (g) "Στο τέλος του δρόμου, ${el(s.direction)}$onto" else "At the end of the road, ${en(s.direction)}$onto"
            "continue", "new name" -> if (g) "Συνεχίστε$onto" else "Continue$onto"
            else -> if (g) el(s.direction) + onto else en(s.direction) + onto
        }
        return base.replaceFirstChar { it.uppercase() }
    }

    fun distance(m: Double, lang: String): String {
        val g = lang == GREEK
        if (!g && MapState.imperial) {
            val mi = m / 1609.344
            return when {
                mi >= 0.95 -> (Math.round(mi * 10) / 10.0).let { v -> if (v % 1.0 == 0.0) "${v.toInt()} miles" else "$v miles" }
                mi >= 0.2 -> if (mi < 0.35) "a quarter mile" else if (mi < 0.65) "half a mile" else "three quarters of a mile"
                else -> "${((m * 3.28084 / 100).roundToLong() * 100).coerceAtLeast(100)} feet"
            }
        }
        return when {
            m >= 950 -> {
                val km = Math.round(m / 100) / 10.0
                val s = if (km % 1.0 == 0.0) km.toInt().toString() else String.format(Locale.US, "%.1f", km).let { if (g) it.replace('.', ',') else it }
                if (g) "$s χιλιόμετρα" else "$s kilometres"
            }
            else -> {
                val r = if (m >= 100) (Math.round(m / 50) * 50).toInt() else (Math.round(m / 10) * 10).toInt().coerceAtLeast(10)
                if (g) "$r μέτρα" else "$r metres"
            }
        }
    }

    /** "In 300 metres, turn left onto X" */
    fun ahead(s: NavStep, m: Double, lang: String): String {
        val instr = instruction(s, lang)
        val lower = instr.replaceFirstChar { it.lowercase() }
        return if (lang == GREEK) "Σε ${distance(m, lang)}, $lower" else "In ${distance(m, lang)}, $lower"
    }

    fun then(s: NavStep, lang: String): String {
        val lower = instruction(s, lang).replaceFirstChar { it.lowercase() }
        return if (lang == GREEK) "και μετά $lower" else "then $lower"
    }

    fun minutes(sec: Double, lang: String): String {
        val m = (sec / 60).toInt().coerceAtLeast(1)
        return if (lang == GREEK) {
            if (m >= 60) "${m / 60} ώρες και ${m % 60} λεπτά" else "$m λεπτά"
        } else if (m >= 60) "${m / 60} hours ${m % 60} minutes" else "$m minutes"
    }

    fun routeReady(sec: Double, lang: String) =
        if (lang == GREEK) "Η διαδρομή είναι έτοιμη. ${minutes(sec, lang)}." else "Route ready. ${minutes(sec, lang)}."

    fun rerouting(lang: String) = if (lang == GREEK) "Επανυπολογισμός διαδρομής" else "Rerouting"

    fun faster(saved: Int, lang: String) =
        if (lang == GREEK) "Βρέθηκε ταχύτερη διαδρομή. Κερδίζετε ${minutes(saved.toDouble(), lang)}."
        else "Faster route found. It saves ${minutes(saved.toDouble(), lang)}."

    fun switched(lang: String) = if (lang == GREEK) "Αλλαγή διαδρομής" else "Switched to the faster route"

    fun jam(lang: String) = if (lang == GREEK) "Κίνηση μπροστά" else "Traffic ahead"
}
