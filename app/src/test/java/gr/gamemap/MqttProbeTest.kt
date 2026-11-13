package gr.gamemap

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Which public MQTT brokers answer, and how fast a message makes the round trip (incl. a retained one). */
class MqttProbeTest {
    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        for (broker in listOf("tcp://broker.hivemq.com:1883", "tcp://broker.emqx.io:1883", "tcp://test.mosquitto.org:1883", "ssl://broker.hivemq.com:8883", "ssl://broker.emqx.io:8883")) {
            try {
                val topic = "gameroute/probe/" + System.nanoTime()
                val got = ConcurrentHashMap<String, Long>()
                val sub = MqttClient(broker, "gr-sub-" + System.nanoTime(), MemoryPersistence())
                val pub = MqttClient(broker, "gr-pub-" + System.nanoTime(), MemoryPersistence())
                val opts = MqttConnectOptions().apply { isCleanSession = true; connectionTimeout = 8; keepAliveInterval = 30 }
                var t0 = System.currentTimeMillis()
                pub.connect(opts)
                val connectMs = System.currentTimeMillis() - t0
                // retained first, then subscribe: should arrive at once
                pub.publish("$topic/r", MqttMessage("retained".toByteArray()).apply { qos = 0; isRetained = true })
                sub.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {}
                    override fun messageArrived(t: String, m: MqttMessage) { got[String(m.payload)] = System.currentTimeMillis() }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                sub.connect(opts); sub.subscribe("$topic/#", 0)
                Thread.sleep(1500)
                val lat = ArrayList<Long>()
                repeat(10) { i ->
                    val s = System.currentTimeMillis()
                    pub.publish("$topic/x", MqttMessage("m$i".toByteArray()).apply { qos = 0 })
                    var w = 0; while (got["m$i"] == null && w++ < 300) Thread.sleep(10)
                    got["m$i"]?.let { lat += it - s }
                    Thread.sleep(300)
                }
                pub.publish("$topic/r", MqttMessage(ByteArray(0)).apply { isRetained = true }) // clear
                pub.disconnect(); sub.disconnect()
                log.append("$broker: connect ${connectMs} ms, retained ${if (got.containsKey("retained")) "yes" else "NO"}, ${lat.size}/10 delivered, median ${lat.sorted().getOrNull(lat.size / 2)} ms\n")
            } catch (e: Exception) { log.append("$broker: FAILED $e\n") }
        }
        File(out, "mqtt.txt").writeText(log.toString())
    }
}
