package gr.gamemap

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Route planning and turn-by-turn navigation. OSRM draws the routes and the turns; TomTom
 * (when there's a key) adds live traffic times, jams and speed limits, and spots faster routes.
 */
object NavEngine {
    val changed = Signal()

    // ------------------------------------------------------------- planning (route preview)

    var planning = false
        private set
    var planPlace: Place? = null
        private set
    var options: List<RouteOption> = emptyList()
        private set
    var selected = 0
        private set
    var planLoading = false
        private set
    var planError: String? = null
        private set
    /** Planned departure (ms) for "Leave later", null = now. */
    var departAt: Long? = null
        private set
    private var userPicked = false
    private var planSeq = 0

    // ------------------------------------------------------------- driving

    var destination: LatLng? = null
        private set
    var destinationName = ""
        private set
    var current: RouteOption? = null
        private set
    val route: List<LatLng> get() = current?.points ?: emptyList()
    val steps: List<NavStep> get() = current?.steps ?: emptyList()
    var loading = false
        private set
    var arrived = false
        private set
    var error: String? = null
        private set
    var nextStep = -1
        private set
    var distanceToNext = 0.0
        private set
    var remainingDistance = 0.0
        private set
    var remainingSeconds = 0.0
        private set
    /** How far along the current route we are, metres. */
    var along = 0.0
        private set
    var trafficDelaySeconds = 0
        private set
    private var trafficRatio = 1.0
    private var trafficAt = 0L

    val active get() = destination != null
    /** Index of the route point just behind us (the line is only drawn ahead of it). */
    val passedIndex: Int get() = nearest
    val currentStep: NavStep? get() = steps.getOrNull(nextStep)

    /** The maneuver after the next one, if it comes soon after it ("Then …"). */
    val followingStep: NavStep?
        get() {
            val r = current ?: return null
            val a = steps.getOrNull(nextStep) ?: return null
            val b = steps.getOrNull(nextStep + 1) ?: return null
            if (a.direction == "arrive") return null
            return b.takeIf { r.cum[it.routeIndex] - r.cum[a.routeIndex] < 400 }
        }

    /** The speed limit where we are now, km/h, if known. */
    val speedLimit: Int?
        get() = current?.limits?.firstOrNull { along >= it.start - 5 && along < it.end + 5 }?.value

    /** The next jam on the route within 3 km: (metres to it, delay seconds). */
    val jamAhead: Pair<Double, Int>?
        get() {
            val r = current ?: return null
            val j = r.traffic.firstOrNull { it.end > along && it.start - along < 3000 && (it.value >= 2 || it.extra >= 60) }
                ?: return null
            return (j.start - along).coerceAtLeast(0.0) to j.extra
        }

    // ------------------------------------------------------------- trip stats

    /** The trip that just ended (arrival card), until dismissed. */
    var lastTrip: Explorer.Trip? = null
        private set
    private var tripStart = 0L
    private var tripMeters = 0.0
    private var tripSaved = 0
    private var tripBonus = 0
    private var lastPos: LatLng? = null

    fun dismissTrip() {
        CarParking.list = null
        lastTrip = null
        changed.fire()
    }

    private fun finishTrip() {
        val secs = ((System.currentTimeMillis() - tripStart) / 1000).toInt()
        val levelBefore = Explorer.level
        val xp = 10 + (tripMeters / 1000 * 5).toInt() + tripBonus
        Explorer.addXp(xp)
        lastTrip = Explorer.Trip(
            destinationName, System.currentTimeMillis(), secs, tripMeters, tripSaved,
            xp + Explorer.tripFound * 25, Explorer.tripFound, levelBefore,
        )
        lastTrip?.let { Explorer.record(it) }
    }

    // ------------------------------------------------------------- faster route offers

    class Offer(val option: RouteOption, val savingSeconds: Int, val createdAt: Long)

    var offer: Offer? = null
        private set
    const val OFFER_SECONDS = 12
    private val declined = mutableListOf<Pair<RouteOption, Long>>()

    private var secondsPerMeter = 0.08
    private var nearest = 0
    private var offRouteCount = 0
    private var parkingReady = false
    /** Closes the arrival card after a minute, but not while its parking list is open. */
    private val closeArrival: Runnable = object : Runnable {
        override fun run() {
            if (!arrived) return
            if (CarParking.list != null) { main.postDelayed(this, 30_000); return }
            stop(); dismissTrip()
        }
    }
    private var lastRouteAt = 0L
    private val io = Executors.newSingleThreadExecutor()
    private val timing = Bg.pool("timing", 3)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context

    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)

    /** Switch to a faster route by itself when the prompt runs out (otherwise keep the current one). */
    var autoSwitch: Boolean
        get() = !::appContext.isInitialized || prefs().getBoolean("autoSwitch", true)
        set(v) { prefs().edit().putBoolean("autoSwitch", v).apply() }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    var avoidTolls: Boolean
        get() = ::appContext.isInitialized && prefs().getBoolean("avoidTolls", false)
        set(v) { prefs().edit().putBoolean("avoidTolls", v).apply(); changed.fire() }
    var avoidMotorways: Boolean
        get() = ::appContext.isInitialized && prefs().getBoolean("avoidMotorways", false)
        set(v) { prefs().edit().putBoolean("avoidMotorways", v).apply(); changed.fire() }

    /** True if the route uses roads the user asked to avoid. */
    fun breaksAvoid(o: RouteOption) = (avoidTolls && o.hasToll) || (avoidMotorways && o.hasMotorway)

    /** Puts routes that respect "avoid tolls / motorways" first (OSRM can't exclude roads itself). */
    private fun preferAllowed(list: List<RouteOption>): List<RouteOption> {
        val ok = list.filterNot { breaksAvoid(it) }
        return if (ok.isEmpty()) list else ok + list.filter { breaksAvoid(it) }
    }

    private val wantsAlternatives get() = avoidTolls || avoidMotorways

    // ============================================================= planning

    /** Shows the route choices to [place] (the preview), without starting. */
    fun plan(ctx: Context, place: Place) {
        appContext = ctx.applicationContext
        if (active) stop()
        planning = true
        lastTrip = null
        planPlace = place
        options = emptyList(); selected = 0; userPicked = false
        departAt = null
        planError = null
        fetchOptions()
    }

    private fun fetchOptions() {
        val place = planPlace ?: return
        val from = LocationTracker.position
        val seq = ++planSeq
        if (from == null) {
            planLoading = true; planError = "Waiting for GPS…"; changed.fire()
            main.postDelayed({ if (planning && seq == planSeq) fetchOptions() }, 2000)
            return
        }
        planLoading = true; planError = null
        changed.fire()
        MapState.requestFit(listOf(from, place.pos))
        io.execute {
            val routes = try { Osrm.routes(from, place.pos, alternatives = true, heading = heading()) } catch (e: Exception) { null }
            main.post {
                if (seq != planSeq || !planning) return@post
                if (routes.isNullOrEmpty()) {
                    planLoading = false; planError = "No route found"; changed.fire(); return@post
                }
                options = preferAllowed(routes).take(3)
                selected = 0
                planLoading = false
                MapState.requestFit(options.flatMap { o -> o.points.filterIndexed { i, _ -> i % 10 == 0 } } + place.pos)
                changed.fire()
                evaluateOptions(seq)
            }
        }
    }

    /** TomTom times all the routes at once (each takes ~0.1 s). */
    private fun evaluateOptions(seq: Int) {
        val opts = options
        val depart = departAt
        for (o in opts) timing.execute {
            val r = try { TomTomTime.evaluate(o, 0.0, departAt = depart) } catch (e: Exception) { null }
            main.post {
                if (seq != planSeq) return@post
                if (r == null) { o.timingFailed = true; changed.fire(); return@post }
                o.trafficSeconds = r.seconds; o.delaySeconds = r.delay
                o.traffic = r.traffic; o.limits = r.limits
                if (!userPicked) selected = fastestIndex()
                changed.fire()
            }
        }
    }

    /** True once a route's time is worth showing (TomTom answered, failed, or isn't available). */
    fun timeKnown(o: RouteOption) = o.trafficSeconds != null || o.timingFailed || !Traffic.active

    fun fastestIndex(): Int {
        val allowed = options.indices.filterNot { breaksAvoid(options[it]) }.ifEmpty { options.indices.toList() }
        return allowed.minByOrNull { options[it].seconds } ?: 0
    }

    fun select(i: Int) {
        if (i !in options.indices) return
        selected = i; userPicked = true
        changed.fire()
    }

    /** "Leave later": re-times the routes for a departure at [ms] (null = now). */
    fun setDeparture(ms: Long?) {
        departAt = ms
        options.forEach { it.trafficSeconds = null; it.timingFailed = false; it.delaySeconds = 0; it.traffic = emptyList() }
        changed.fire()
        evaluateOptions(planSeq)
    }

    fun cancelPlan() {
        if (!planning) return
        planning = false; planPlace = null; options = emptyList(); planSeq++
        MapState.recenter()
        changed.fire()
    }

    /** Starts driving the chosen route. */
    fun startPlanned() {
        val place = planPlace ?: return
        val opt = options.getOrNull(selected)
        planning = false; planSeq++
        begin(place)
        // picking the fastest route counts as time saved against the next best one
        if (opt != null && opt.trafficSeconds != null) {
            val others = options.filter { it !== opt && it.trafficSeconds != null }.minOfOrNull { it.seconds }
            if (others != null && others - opt.seconds >= 60) tripSaved = (others - opt.seconds).toInt()
        }
        if (opt != null) apply(opt, announce = true) else fetchRoute()
    }

    /** Starts navigation straight away (no preview). */
    fun start(ctx: Context, place: Place) {
        appContext = ctx.applicationContext
        planning = false; planSeq++
        begin(place)
        fetchRoute()
    }

    private fun begin(place: Place) {
        stops.clear()
        Places.addRecent(place)
        lastTrip = null
        tripStart = System.currentTimeMillis(); tripMeters = 0.0; tripSaved = 0; tripBonus = 0; lastPos = null
        Explorer.tripFound = 0
        parkingReady = false
        destination = place.pos
        destinationName = place.name
        arrived = false
        error = null
        offer = null; declined.clear()
        trafficRatio = 1.0; trafficDelaySeconds = 0; trafficAt = 0
        current = null; nextStep = -1
        MapState.recenter()
        Voice.reset()
        changed.fire()
        try {
            if (LocationTracker.hasPermission(appContext))
                ContextCompat.startForegroundService(appContext, Intent(appContext, NavService::class.java))
        } catch (_: Exception) {
        }
    }

    // ============================================================= stops on the way

    /** Places to pass on the way to the destination, in order. */
    val stops = java.util.concurrent.CopyOnWriteArrayList<Place>()

    /** Adds a stop (fuel, coffee…) without ending the trip; the route is recalculated through it. */
    fun addStop(p: Place) {
        if (!active || stops.any { Geo.distance(it.pos, p.pos) < 30 }) return
        stops += p
        Voice.say(if (Voice.lang == Phrases.GREEK) "Προστέθηκε στάση: ${p.name}" else "Stop added: ${p.name}")
        fetchRoute()
    }

    internal fun debugStop(p: Place) { stops += p; changed.fire() }

    fun removeStop(p: Place) {
        if (stops.remove(p)) fetchRoute()
    }

    /** Reached the next stop: it's done, the route carries on to the destination. */
    private fun checkStops(pos: LatLng) {
        val s = stops.firstOrNull() ?: return
        if (Geo.distance(pos, s.pos) < 40) {
            stops.remove(s)
            Voice.say(if (Voice.lang == Phrases.GREEK) "Φτάσατε στη στάση: ${s.name}" else "Stop reached: ${s.name}")
            changed.fire()
        }
    }

    fun stop() {
        LiveShare.stop(if (arrived) "arrived" else "stopped")
        stops.clear()
        destination = null
        current = null; nextStep = -1
        loading = false; arrived = false; error = null; offer = null
        Voice.stop()
        if (::appContext.isInitialized) {
            appContext.stopService(Intent(appContext, NavService::class.java))
        }
        changed.fire()
    }

    // ============================================================= driving

    private fun heading(): Float? =
        if (LocationTracker.speedMps > 1.5f) LocationTracker.heading else null

    /**
     * After leaving the route, pick the option that carries on the way the driver went:
     * no early turn-back, and as little of the abandoned route as possible.
     */
    private fun pickForward(list: List<RouteOption>, heading: Float?, old: RouteOption?): RouteOption? {
        if (list.size <= 1) return list.firstOrNull()
        val best = list.first()
        fun score(r: RouteOption): Double {
            var s = r.duration
            if (heading != null) {
                // first ~150 m must not point back
                val p = r.points
                val i = r.cum.indexOfFirst { it > 150 }.let { if (it < 1) p.size - 1 else it }
                val b = Geo.bearing(p[0], p[i])
                val diff = Math.abs(((b - heading + 540) % 360) - 180)
                if (diff > 100) s += 600
            }
            if (old != null) {
                // re-joining the old route behind the car = being pulled back
                val end = (nearest + 1).coerceAtMost(old.points.size)
                val behind = old.points.subList(maxOf(0, end - 400), end)
                val sample = r.points.filterIndexed { k, _ -> k % 8 == 0 }.take(40)
                val back = sample.count { q -> behind.any { Geo.distance(it, q) < 25 } }
                s += back * 30
            }
            return s
        }
        return list.minByOrNull { score(it) }?.takeIf { score(it) < score(best) - 30 || it === best } ?: best
    }

    /** If p is within 30 m of the route, the closest point on it and the road's direction there. */
    fun snap(p: LatLng): Pair<LatLng, Float>? {
        val route = route
        if (!active || route.size < 2) return null
        var best: Pair<LatLng, Double>? = null
        var seg = 0
        val lo = maxOf(0, nearest - 5); val hi = minOf(route.size - 1, nearest + 60)
        for (i in lo until hi) {
            val pr = Geo.project(p, route[i], route[i + 1])
            if (best == null || pr.second < best.second) { best = pr; seg = i }
        }
        val b = best ?: return null
        if (b.second > 20) return null
        return b.first to Geo.bearing(route[seg], route[seg + 1])
    }

    private fun fetchRoute(reroute: Boolean = false) {
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
        if (reroute) Voice.say(Phrases.rerouting(Voice.lang))
        changed.fire()
        io.execute {
            val h = heading()
            val r = try {
                val list = preferAllowed(Osrm.routes(from, dest, alternatives = reroute || wantsAlternatives, heading = h, reroute = reroute, via = stops.map { it.pos }))
                if (reroute) pickForward(list, h, current) else list.firstOrNull()
            } catch (e: Exception) { null }
            main.post {
                if (destination != dest) return@post
                if (r == null) {
                    loading = false; error = "No route found"; changed.fire()
                } else apply(r, announce = !reroute)
            }
        }
    }

    private fun apply(r: RouteOption, announce: Boolean) {
        current = r
        secondsPerMeter = if (r.distance > 0) r.duration / r.distance else 0.08
        trafficRatio = r.trafficSeconds?.let { (it / maxOf(r.duration, 1.0)).coerceIn(0.6, 4.0) } ?: trafficRatio
        trafficDelaySeconds = r.delaySeconds
        // fresh traffic soon after the start, then every 3 minutes
        trafficAt = if (r.trafficSeconds != null) System.currentTimeMillis() else System.currentTimeMillis() - 85_000
        nearest = 0; along = 0.0
        offRouteCount = 0
        loading = false
        error = null
        Voice.reset()
        if (announce) Voice.say(Phrases.routeReady(r.length * secondsPerMeter * trafficRatio, Voice.lang))
        LocationTracker.last?.let { onLocation(it) } ?: changed.fire()
    }

    fun onLocation(l: Location) {
        if (!active) return
        val r = current
        val pos = LatLng(l.latitude, l.longitude)
        lastPos?.let { lp -> Geo.distance(lp, pos).takeIf { it < 300 }?.let { tripMeters += it } }
        lastPos = pos
        if (r == null || r.points.size < 2) {
            if (loading && error != null) fetchRoute() // was waiting for the first GPS fix
            return
        }
        val route = r.points
        var best = nearest; var bestD = Double.MAX_VALUE
        val lo = maxOf(0, nearest - 10); val hi = minOf(route.size - 1, nearest + 300)
        for (i in lo until hi) {
            val d = Geo.distanceToSegment(pos, route[i], route[i + 1])
            if (d < bestD) { bestD = d; best = i }
        }
        nearest = best
        along = r.cum[best] + Geo.distance(route[best], pos).coerceAtMost(r.cum[best + 1] - r.cum[best])
        remainingDistance = (r.length - along).coerceAtLeast(0.0)
        remainingSeconds = remainingDistance * secondsPerMeter * trafficRatio
        maybeRefreshTraffic(pos)
        checkStops(pos)
        LiveShare.tick()
        // last ~1.5 km: have the car parks at the destination ready for the arrival card
        if (remainingDistance < 1500 && !parkingReady) {
            parkingReady = true
            destination?.let { d -> Bg.factory("parking").newThread { try { Places.prefetchNearby(listOf("parking"), d) } catch (_: Exception) {} }.start() }
        }

        val dest = destination!!
        if (Geo.distance(pos, dest) < 30 || remainingDistance < 20) {
            if (!arrived) {
                arrived = true
                offer = null
                finishTrip()
                LiveShare.stop("arrived")
                Voice.say(Phrases.instruction(r.steps.lastOrNull() ?: NavStep("arrive", "arrive", "", 0, dest, null), Voice.lang))
                // the arrival card stays a minute (Parking / Done), then the trip closes by itself
                main.postDelayed(closeArrival, 60_000)
            }
            changed.fire()
            return
        }

        nextStep = r.steps.indexOfFirst { it.routeIndex > best || it.direction == "arrive" }
            .let { if (it < 0) r.steps.size - 1 else it }
        r.steps.getOrNull(nextStep)?.let { distanceToNext = (r.cum[it.routeIndex] - along).coerceAtLeast(0.0) }
        currentStep?.let { Voice.progress(nextStep, it, distanceToNext, LocationTracker.speedMps, followingStep) }

        // Off the route: 2 fixes in a row more than ~35 m away (or one fix far away),
        // or driving the wrong way along it. Then ask for a new route straight away.
        val accuracy = if (l.hasAccuracy()) l.accuracy.toDouble() else 20.0
        val far = bestD > maxOf(35.0, accuracy * 1.2)
        val wrongWay = l.hasBearing() && l.speed > 4f && bestD < 30 &&
            kotlin.math.abs(Geo.angleDiff(l.bearing, Geo.bearing(route[best], route[best + 1]))) > 120
        offRouteCount = if (far || wrongWay) offRouteCount + (if (bestD > 120) 2 else 1) else 0
        if (offRouteCount >= 2 && !loading && System.currentTimeMillis() - lastRouteAt > 6_000) {
            offRouteCount = 0
            offer = null
            fetchRoute(reroute = true)
        }
        changed.fire()
    }

    /**
     * Every 3 minutes: TomTom re-times the rest of the route (traffic, jams, speed limits), then
     * OSRM's other ways from here are timed too; one that saves 3+ minutes is offered.
     */
    private fun maybeRefreshTraffic(pos: LatLng) {
        if (!Traffic.active || loading || System.currentTimeMillis() - trafficAt < 90_000) return
        val dest = destination ?: return
        val r = current ?: return
        trafficAt = System.currentTimeMillis()
        val from = along
        val plain = remainingDistance * secondsPerMeter
        val declinedNow = declined.toList()
        val nearEnd = remainingDistance < 1500
        val hasOffer = offer != null
        io.execute {
            val res = try { TomTomTime.evaluate(r, from, start = pos) } catch (e: Exception) { null }
            main.post {
                if (current !== r || res == null) return@post
                if (plain > 30) trafficRatio = (res.seconds / plain).coerceIn(0.6, 4.0)
                trafficDelaySeconds = res.delay
                r.traffic = res.traffic
                if (res.limits.isNotEmpty()) r.limits = res.limits
                remainingSeconds = remainingDistance * secondsPerMeter * trafficRatio
                changed.fire()
            }
            if (res == null || hasOffer || nearEnd) return@execute
            val alts = try { Osrm.routes(pos, dest, alternatives = true, heading = heading(), via = stops.map { it.pos }) } catch (e: Exception) { null }
                ?: return@execute
            val now = System.currentTimeMillis()
            var bestAlt: RouteOption? = null
            for (a in alts) {
                if (r.similarTo(a) || breaksAvoid(a)) continue
                if (declinedNow.any { now - it.second < 15 * 60_000 && it.first.similarTo(a) }) continue
                val e = try { TomTomTime.evaluate(a, 0.0) } catch (ex: Exception) { null } ?: continue
                a.trafficSeconds = e.seconds; a.delaySeconds = e.delay; a.traffic = e.traffic; a.limits = e.limits
                if (bestAlt == null || e.seconds < bestAlt.seconds) bestAlt = a
            }
            val alt = bestAlt ?: return@execute
            val saving = (res.seconds - alt.seconds).toInt()
            if (saving >= 180 && saving >= res.seconds * 0.08) main.post {
                if (current === r && offer == null && active) showOffer(Offer(alt, saving, System.currentTimeMillis()))
            }
        }
    }

    private fun showOffer(o: Offer) {
        offer = o
        Voice.say(Phrases.faster(o.savingSeconds, Voice.lang))
        changed.fire()
        main.postDelayed({
            if (offer === o) { if (autoSwitch) acceptOffer() else declineOffer() }
        }, OFFER_SECONDS * 1000L)
    }

    fun acceptOffer() {
        val o = offer ?: return
        offer = null
        tripSaved += o.savingSeconds
        tripBonus += 10
        Voice.say(Phrases.switched(Voice.lang))
        apply(o.option, announce = false)
    }

    fun declineOffer() {
        val o = offer ?: return
        offer = null
        declined += o.option to System.currentTimeMillis()
        changed.fire()
    }

    // ============================================================= previews / tests

    internal fun debugPlan(place: Place, opts: List<RouteOption>) {
        planning = true; planPlace = place; options = opts; selected = fastestIndex()
        planLoading = false; planError = null
        MapState.requestFit(opts.flatMap { it.points } + place.pos)
        changed.fire()
    }

    internal fun debugDrive(place: Place, r: RouteOption, delaySeconds: Int) {
        planning = false
        begin(place)
        apply(r, announce = false)
        trafficRatio = (r.duration + delaySeconds) / maxOf(r.duration, 1.0)
        trafficDelaySeconds = delaySeconds
        changed.fire()
    }

    internal fun debugTrip(t: Explorer.Trip) {
        lastTrip = t
        changed.fire()
    }

    internal fun debugOffer(o: RouteOption, saving: Int) {
        offer = Offer(o, saving, System.currentTimeMillis())
        changed.fire()
    }

    // ============================================================= text helpers

    fun formatDistance(m: Double): String {
        if (MapState.imperial) {
            val mi = m / 1609.344
            return when {
                mi >= 10 -> "${mi.toInt()} mi"
                mi >= 0.2 -> String.format(java.util.Locale.US, "%.1f mi", mi)
                else -> "${((m * 3.28084 / 50).toInt() * 50).coerceAtLeast(50)} ft"
            }
        }
        return when {
            m >= 10_000 -> "${(m / 1000).toInt()} km"
            m >= 1000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000)
            m >= 100 -> "${((m / 50).toInt() * 50)} m"
            else -> "${((m / 10).toInt() * 10)} m"
        }
    }

    fun formatDuration(sec: Double): String {
        val mins = (sec / 60).toInt().coerceAtLeast(1)
        return if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
    }

    fun clock(atMs: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(atMs))

    fun arrivalClock(): String = clock(System.currentTimeMillis() + (remainingSeconds * 1000).toLong())

    /** Arrival time of a planned option. */
    fun optionArrival(o: RouteOption): String = clock((departAt ?: System.currentTimeMillis()) + (o.seconds * 1000).toLong())
}
