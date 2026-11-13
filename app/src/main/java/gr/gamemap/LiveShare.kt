package gr.gamemap

import org.json.JSONObject
import java.security.SecureRandom

/**
 * Live arrival sharing: while on, GameRoute posts your position (end-to-end encrypted) and arrival time to a private,
 * random ntfy.sh topic every ~40 s; the link opens a page that shows them on a map, live.
 * It stops by itself when you arrive or end the trip (posting a last "arrived"/"ended").
 */
object LiveShare {
    const val PAGE = "https://vyto-line.github.io/GameRoute/live/"
    @Volatile var topic: String? = null
        private set
    @Volatile private var key: ByteArray? = null
    val active get() = topic != null
    private val io = Bg.pool("liveshare", 1)
    @Volatile private var sentAt = 0L
    private var lastLeft = -1.0

    /** Starts sharing for this trip; returns the link to send. */
    fun start(): String {
        val t = topic ?: run {
            val abc = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val r = SecureRandom()
            "gm" + (1..22).map { abc[r.nextInt(abc.length)] }.joinToString("")
        }
        val k = key ?: Seal.randomKey()
        topic = t; key = k
        sentAt = 0
        tick(force = true)
        // the key rides after "#": browsers never send that part to any server
        return PAGE + "#" + t + "." + Seal.b64(k)
    }

    fun stop(state: String = "stopped") {
        val t = topic ?: return
        post(t, payload(state))
        topic = null; key = null
    }

    /** Called on every location fix while navigating; posts at most every 40 s. */
    fun tick(force: Boolean = false) {
        val t = topic ?: return
        if (!NavEngine.active) { stop(); return }
        if (NavEngine.arrived) { stop("arrived"); return }
        val now = System.currentTimeMillis()
        val moved = Math.abs(NavEngine.remainingDistance - lastLeft) > 50
        if (!force && (now - sentAt < 40_000 || !moved)) return
        sentAt = now
        lastLeft = NavEngine.remainingDistance
        post(t, payload("driving"))
    }

    private fun payload(state: String): String {
        val p = LocationTracker.position
        val o = JSONObject().put("state", state).put("ts", System.currentTimeMillis())
            .put("dest", NavEngine.destinationName).put("left", NavEngine.remainingDistance)
            .put("eta", System.currentTimeMillis() + (NavEngine.remainingSeconds * 1000).toLong())
        if (p != null) o.put("lat", p.lat).put("lon", p.lon)
        NavEngine.destination?.let { o.put("dlat", it.lat).put("dlon", it.lon) }
        return o.toString()
    }

    private fun post(topic: String, body: String) {
        val k = key ?: return
        io.execute { try { Http.post("https://ntfy.sh/$topic", Seal.seal(k, body), "text/plain", timeout = 8000) } catch (_: Exception) {} }
    }
}
