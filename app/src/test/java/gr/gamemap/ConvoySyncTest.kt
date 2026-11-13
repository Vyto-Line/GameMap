package gr.gamemap

import android.os.Looper
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * A friend drives the route at 50 km/h and reports through the real brokers every 2 s (like the
 * app); this phone listens. Measures how late updates arrive, whether the drawn arrow moves
 * smoothly (no jumps between frames) and whether the speed shown matches what they sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConvoySyncTest {
    @Test
    fun friendDriving() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val dest = Place("Museum", "", r.points.last())
        NavEngine.debugDrive(dest, r, 0)
        Convoy.join("k7p2qx")
        val looper = shadowOf(Looper.getMainLooper())
        Thread.sleep(6000) // connect to the brokers
        val log = StringBuilder("connected: ${ConvoyLink.connected}\n")
        val key = Seal.convoyKey("k7p2qx")
        val topic = "gameroute/" + Seal.convoyTopic("k7p2qx") + "/friend1"
        val friend = MqttClient("ssl://broker.emqx.io:8883", "gr-test-" + System.nanoTime(), MemoryPersistence())
        friend.connect(MqttConnectOptions().apply { isCleanSession = true; connectionTimeout = 10 })
        val speed = 50 / 3.6
        val t0 = System.currentTimeMillis()
        var lastSent = 0L
        var maxStep = 0.0; var steps = 0; var prev: LatLng? = null
        val lags = ArrayList<Long>(); var lastSeenTs = 0L
        val kmhShown = ArrayList<Int>()
        while (System.currentTimeMillis() - t0 < 30_000) {
            val now = System.currentTimeMillis()
            val m = (now - t0) / 1000.0 * speed + 50
            if (now - lastSent >= 2000) {
                lastSent = now
                var i = 0
                while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
                val p = Geo.lerp(r.points[i], r.points[i + 1], ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0))
                val o = JSONObject().put("t", "pos").put("id", "friend1").put("n", "Nikos").put("st", "driving").put("ts", now)
                    .put("lat", p.lat).put("lon", p.lon).put("h", Geo.bearing(r.points[i], r.points[i + 1]).toDouble()).put("s", 50)
                    .put("left", r.length - m).put("tot", r.length)
                friend.publish(topic, MqttMessage(Seal.seal(key, o.toString()).toByteArray()).apply { qos = 0; isRetained = true })
            }
            looper.idle()
            val mem = Convoy.members["friend1"]
            if (mem != null) {
                if (mem.lastTs != lastSeenTs) { lastSeenTs = mem.lastTs; lags += System.currentTimeMillis() - mem.lastTs; kmhShown += mem.kmh }
                val d = mem.display(System.currentTimeMillis())
                if (d != null && prev != null) { maxStep = maxOf(maxStep, Geo.distance(prev, d)); steps++ }
                prev = d
            }
            Thread.sleep(33) // ~30 frames a second
        }
        friend.publish(topic, MqttMessage(ByteArray(0)).apply { isRetained = true }); friend.disconnect()
        log.append("updates received: ${lags.size} in 30 s (sent ${30 / 2})\n")
        log.append("delay: median ${lags.sorted().getOrNull(lags.size / 2)} ms, worst ${lags.maxOrNull()} ms\n")
        log.append("arrow: largest move between two frames ${"%.2f".format(maxStep)} m (driving moves ${"%.2f".format(speed * 0.033)} m per frame)\n")
        log.append("speed shown: ${kmhShown.distinct()} (sent 50)\n")
        Convoy.leave(quiet = true)
        File(out, "convoysync.txt").writeText(log.toString())
    }
}
