package gr.gamemap

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Convoy: a group driving to the same place. Everyone in it sees the others on the map with
 * their speed, arrival time and how far along the route they are.
 *
 * No accounts or server of our own: each convoy is a private, random topic on the free ntfy.sh
 * relay. Members post a small update (~every 15 s while driving) and listen to the others.
 */
object Convoy {
    class Member(
        val id: String,
        var name: String,
        val color: Int,
        var pos: LatLng? = null,
        var heading: Float = 0f,
        var kmh: Int = 0,
        var eta: Long = 0,
        var left: Double = -1.0,
        var total: Double = -1.0,
        var state: String = "ready", // ready, driving, arrived, left
        var arrivedAt: Long = 0,
        var seen: Long = 0,
    ) {
        /** 0 at the start … 1 at the destination. */
        val progress: Double get() = if (state == "arrived") 1.0 else if (total > 0 && left >= 0) (1 - left / total).coerceIn(0.0, 1.0) else 0.0
        /** Updates come every ~15 s while driving (stopped too), so 45 s without one = signal lost. */
        val stale: Boolean get() = System.currentTimeMillis() - seen > (if (state == "driving") 45_000 else 150_000)

        // ---- gliding between updates
        @Volatile var fixAt = 0L          // when the last position arrived (phone clock)
        @Volatile var blendFrom: LatLng? = null
        @Volatile var blendAt = 0L
        @Volatile var shown: LatLng? = null
        private var routeOf: Any? = null
        private var routeFix = -1L
        private var routeAt = -1.0

        /** Where they probably are now: carried on along the road (or their heading) at their speed. */
        fun predicted(now: Long): LatLng? {
            val p = pos ?: return null
            if (state != "driving" || kmh < 3 || stale) return p
            val secs = ((now - fixAt) / 1000.0).coerceIn(0.0, 18.0)
            val metres = kmh / 3.6 * secs
            // on my route (same destination, usually the same roads): follow its bends;
            // the route position is looked up once per update, not every frame
            val route = NavEngine.current
            if (routeOf !== route || routeFix != fixAt) { routeOf = route; routeFix = fixAt; routeAt = NavEngine.routeDistanceOf(p) ?: -1.0 }
            if (routeAt >= 0) NavEngine.routePointAt(routeAt + metres)?.let { return it }
            return Geo.offset(p, heading, metres.coerceAtMost(kmh / 3.6 * 6))
        }

        /** The point to draw: eases from where it was drawn to the new estimate over ~1.5 s. */
        fun display(now: Long): LatLng? {
            val target = predicted(now) ?: return null
            val from = blendFrom ?: return target.also { shown = it }
            val f = ((now - blendAt) / 1500.0).coerceIn(0.0, 1.0)
            val e = f * f * (3 - 2 * f)
            val out = if (f >= 1.0) target else LatLng(from.lat + (target.lat - from.lat) * e, from.lon + (target.lon - from.lon) * e)
            shown = out
            return out
        }

        val moving: Boolean get() = state == "driving" && kmh >= 3 && !stale
    }

    const val PAGE = "https://vyto-line.github.io/GameMap/convoy/"
    private val palette = intArrayOf(
        Color.rgb(47, 210, 122), Color.rgb(255, 169, 77), Color.rgb(77, 171, 247),
        Color.rgb(255, 107, 154), Color.rgb(255, 212, 59), Color.rgb(34, 211, 238), Color.rgb(151, 117, 250),
    )

    private lateinit var appContext: Context
    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)
    val changed = Signal()
    private val main = Handler(Looper.getMainLooper())

    @Volatile var code: String? = null
        private set
    val active get() = code != null
    @Volatile var destination: Place? = null
        private set
    /** Others in the convoy (not me), by id. */
    val members = ConcurrentHashMap<String, Member>()

    /** This phone's id in convoys (random, kept). */
    val myId: String by lazy {
        prefs().getString("convoyId", null) ?: randomCode(10).also { prefs().edit().putString("convoyId", it).apply() }
    }
    var myName: String
        get() = prefs().getString("convoyName", "").orEmpty()
        set(v) { prefs().edit().putString("convoyName", v.trim().take(16)).apply() }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        // a convoy still on when the app was closed (e.g. phone restarted mid-drive)
        val c = prefs().getString("convoyCode", null)
        if (c != null) {
            val d = prefs().getString("convoyDest", null)?.let { s ->
                try { val o = JSONObject(s); Place(o.getString("n"), o.optString("a"), LatLng(o.getDouble("lat"), o.getDouble("lon"))) } catch (e: Exception) { null }
            }
            code = c; destination = d
            listen(c)
        }
    }

    private fun randomCode(n: Int): String {
        val abc = "abcdefghjkmnpqrstuvwxyz23456789" // no 0/o, 1/l/i
        val r = SecureRandom()
        return (1..n).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    fun link(): String? = code?.let { PAGE + "#" + it }

    /** The code as people read it out: "K7P 2QX". */
    fun pretty(): String? = code?.uppercase()?.let { if (it.length == 6) it.take(3) + " " + it.drop(3) else it }

    /** True while someone in the convoy is moving (their arrow glides, so keep drawing). */
    val anyMoving: Boolean get() = members.values.any { it.moving }

    /** Starts a new convoy to [dest]; returns the invite link. */
    fun create(dest: Place): String {
        leave(quiet = true)
        val c = randomCode(6)
        setCode(c, dest)
        publish(hello("ready"))
        return link()!!
    }

    /** Joins from a link or a code; the destination arrives with the first messages. */
    fun join(linkOrCode: String): Boolean {
        val c = linkOrCode.trim().substringAfterLast('#').substringAfterLast('/').filter { it.isLetterOrDigit() }.lowercase()
        if (c.length < 6) return false
        if (c == code) return true
        leave(quiet = true)
        setCode(c, null)
        publish(hello("ready"))
        return true
    }

    private fun setCode(c: String, dest: Place?) {
        code = c; destination = dest; members.clear()
        val e = prefs().edit().putString("convoyCode", c)
        if (dest != null) e.putString("convoyDest", JSONObject().put("n", dest.name).put("a", dest.address).put("lat", dest.pos.lat).put("lon", dest.pos.lon).toString())
        else e.remove("convoyDest")
        e.apply()
        listen(c)
        changed.fire()
    }

    fun leave(quiet: Boolean = false) {
        val c = code ?: return
        if (!quiet) publish(JSONObject().put("t", "bye").put("id", myId).put("ts", System.currentTimeMillis()), c)
        code = null; destination = null; members.clear()
        prefs().edit().remove("convoyCode").remove("convoyDest").apply()
        listener?.interrupt(); listener = null
        changed.fire()
    }

    // ------------------------------------------------------------- sending

    private val sender = Bg.pool("convoy-send", 1)
    @Volatile private var sentAt = 0L
    private var routeTotal = -1.0

    private fun hello(state: String): JSONObject {
        val o = JSONObject().put("t", "pos").put("id", myId).put("n", myName.ifBlank { "Driver" })
            .put("st", state).put("ts", System.currentTimeMillis())
        destination?.let { o.put("dn", it.name).put("da", it.address).put("dlat", it.pos.lat).put("dlon", it.pos.lon) }
        LocationTracker.position?.let { o.put("lat", it.lat).put("lon", it.lon).put("h", LocationTracker.heading.toDouble()) }
        o.put("s", (LocationTracker.speedMps * 3.6f).toInt())
        if (NavEngine.active) {
            val left = NavEngine.remainingDistance
            if (routeTotal < left) routeTotal = left
            o.put("left", left).put("tot", routeTotal)
                .put("eta", System.currentTimeMillis() + (NavEngine.remainingSeconds * 1000).toLong())
        }
        return o
    }

    /** On each location fix: an update every ~15 s while driving, every minute otherwise. */
    fun tick() {
        if (code == null) return
        val now = System.currentTimeMillis()
        val driving = NavEngine.active && !NavEngine.arrived
        val gap = if (driving) 15_000 else 60_000
        if (now - sentAt < gap) return
        publish(hello(if (NavEngine.arrived) "arrived" else if (driving) "driving" else "ready"))
    }

    /** Called when this driver arrives: tell the group right away. */
    fun arrived() {
        if (code == null) return
        publish(hello("arrived").put("at", System.currentTimeMillis()))
    }

    fun startedDriving() {
        routeTotal = -1.0
        if (code != null) { sentAt = 0; tick() }
    }

    private fun publish(o: JSONObject, topic: String? = code) {
        val t = topic ?: return
        sentAt = System.currentTimeMillis()
        sender.execute { try { Http.post("https://ntfy.sh/gmc$t", o.toString(), "text/plain", timeout = 8000) } catch (_: Exception) {} }
    }

    // ------------------------------------------------------------- listening

    @Volatile private var listener: Thread? = null

    private fun listen(c: String) {
        listener?.interrupt()
        val t = Thread {
            var since = "12h"
            while (code == c && !Thread.currentThread().isInterrupted) {
                try {
                    val conn = URL("https://ntfy.sh/gmc$c/json?since=$since").openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000; conn.readTimeout = 90_000 // ntfy sends a keepalive every 45 s
                    conn.setRequestProperty("User-Agent", "GameMap/1.0 (Android)")
                    conn.inputStream.bufferedReader().use { r ->
                        while (code == c) {
                            val line = r.readLine() ?: break
                            val m = try { JSONObject(line) } catch (e: Exception) { continue }
                            if (m.optString("event") != "message") continue
                            since = m.optString("id", since)
                            try { take(JSONObject(m.getString("message"))) } catch (_: Exception) {}
                        }
                    }
                } catch (e: InterruptedException) { return@Thread } catch (_: Exception) {}
                try { Thread.sleep(5_000) } catch (e: InterruptedException) { return@Thread }
            }
        }
        t.isDaemon = true; t.priority = Thread.MIN_PRIORITY
        listener = t; t.start()
    }

    private fun take(o: JSONObject) {
        val id = o.optString("id"); if (id.isBlank()) return
        // the destination travels with every message (so whoever joins late gets it too)
        if (destination == null && o.has("dlat")) {
            destination = Place(o.optString("dn", "Destination"), o.optString("da"), LatLng(o.getDouble("dlat"), o.getDouble("dlon")))
            prefs().edit().putString("convoyDest", JSONObject().put("n", destination!!.name).put("a", destination!!.address)
                .put("lat", destination!!.pos.lat).put("lon", destination!!.pos.lon).toString()).apply()
        }
        if (id == myId) { main.post { changed.fire() }; return }
        if (o.optString("t") == "bye") { members[id]?.state = "left"; members.remove(id); main.post { changed.fire() }; return }
        val m = members.getOrPut(id) { Member(id, o.optString("n", "Driver"), palette[members.size % palette.size]) }
        m.name = o.optString("n", m.name)
        if (o.has("lat")) {
            val now = System.currentTimeMillis()
            m.blendFrom = m.shown ?: m.predicted(now)
            m.blendAt = now
            m.pos = LatLng(o.getDouble("lat"), o.getDouble("lon"))
            m.fixAt = now
        }
        m.heading = o.optDouble("h", m.heading.toDouble()).toFloat()
        m.kmh = o.optInt("s", m.kmh)
        if (o.has("eta")) m.eta = o.getLong("eta")
        if (o.has("left")) m.left = o.getDouble("left")
        if (o.has("tot")) m.total = maxOf(m.total, o.getDouble("tot"))
        val st = o.optString("st", m.state)
        if (st == "arrived" && m.state != "arrived") m.arrivedAt = o.optLong("at", o.optLong("ts"))
        m.state = st
        m.seen = o.optLong("ts", System.currentTimeMillis()).coerceAtMost(System.currentTimeMillis())
        main.post { changed.fire() }
    }

    // ------------------------------------------------------------- tests

    internal fun debugStart(dest: Place) { code = "testconvoy01"; destination = dest; members.clear(); routeTotal = -1.0 }

    internal fun debugMember(name: String, pos: LatLng, kmh: Int, eta: Long, left: Double, total: Double, state: String) {
        val m = Member("t-$name", name, palette[members.size % palette.size], pos, 0f, kmh, eta, left, total, state, 0, System.currentTimeMillis())
        m.fixAt = System.currentTimeMillis()
        members[m.id] = m
        changed.fire()
    }

    // ------------------------------------------------------------- for the screens

    /** Me plus the others, furthest along first. */
    data class Row(val name: String, val color: Int, val me: Boolean, val kmh: Int, val eta: Long, val progress: Double,
                   val left: Double, val state: String, val stale: Boolean, val arrivedAt: Long)

    @Volatile private var rowsCache: Pair<Long, List<Row>>? = null

    fun rows(myColor: Int): List<Row> {
        val now = System.currentTimeMillis()
        rowsCache?.let { (at, r) -> if (now - at < 500 && r.firstOrNull { it.me }?.color == myColor) return r }
        return computeRows(myColor).also { rowsCache = now to it }
    }

    private fun computeRows(myColor: Int): List<Row> {
        val me = Row("You", myColor, true, (LocationTracker.speedMps * 3.6f).toInt(),
            if (NavEngine.active) System.currentTimeMillis() + (NavEngine.remainingSeconds * 1000).toLong() else 0,
            if (NavEngine.arrived) 1.0 else if (NavEngine.active && routeTotal > 0) (1 - NavEngine.remainingDistance / routeTotal).coerceIn(0.0, 1.0) else 0.0,
            if (NavEngine.active) NavEngine.remainingDistance else -1.0,
            if (NavEngine.arrived) "arrived" else if (NavEngine.active) "driving" else "ready", false,
            NavEngine.lastTrip?.arrivedAt ?: 0)
        val others = members.values.map { Row(it.name, it.color, false, it.kmh, it.eta, it.progress, it.left, it.state, it.stale, it.arrivedAt) }
        return (others + me).sortedWith(compareByDescending<Row> { it.progress }.thenBy { it.eta })
    }

    /** "1.3 km ahead" / "0.8 km behind" relative to me. */
    fun relative(r: Row): String {
        if (r.me) return "you"
        if (r.state == "arrived") return "arrived"
        if (r.stale) return "no signal"
        if (r.state == "ready") return "not started"
        val mine = if (NavEngine.active) NavEngine.remainingDistance else return NavEngine.formatDistance(r.left.coerceAtLeast(0.0)) + " to go"
        val d = mine - r.left
        if (r.kmh == 0) return "stopped"
        return if (d >= 0) "${NavEngine.formatDistance(d)} ahead" else "${NavEngine.formatDistance(-d)} behind"
    }

    /** Finishing order once everyone (or me) has arrived: (place, total). */
    fun myPlace(): Pair<Int, Int>? {
        if (!active) return null
        val done = rows(0).filter { it.state == "arrived" }.sortedBy { it.arrivedAt }
        val i = done.indexOfFirst { it.me }
        return if (i < 0) null else (i + 1) to (members.size + 1)
    }
}
