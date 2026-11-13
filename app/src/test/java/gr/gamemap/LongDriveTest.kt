package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Two hours of driving, squeezed: 7200 GPS fixes (one per simulated second) with the car and phone
 * maps drawn for each, lap after lap with arrivals, convoy friends and a light-mode switch.
 * Every 10 simulated minutes it records memory (after a GC), frame time and thread count, so a
 * slow leak or a slowdown over a long drive shows up as a rising column.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LongDriveTest {
    @Test
    fun twoHours() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        r.limits = listOf(Stretch(0.0, r.length, 50))
        val dest = Place("Museum", "", r.points.last())
        fun at(m: Double, speed: Float): Location {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            val p = Geo.lerp(r.points[i], r.points[i + 1], f)
            return Location("gps").apply {
                latitude = p.lat; longitude = p.lon; this.speed = speed; bearing = Geo.bearing(r.points[i], r.points[i + 1])
                accuracy = 5f; time = System.currentTimeMillis()
            }
        }
        val looper = shadowOf(Looper.getMainLooper())
        MapState.setTheme(Theme.OPEN_WORLD); MapState.setLightMode(MapState.LightMode.DARK)
        val car = MapRenderer(car = true).apply { carButtons = true }
        val phone = MapRenderer(car = false)
        val cBmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888); val cc = Canvas(cBmp)
        val pBmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888); val pc = Canvas(pBmp)
        val cArea = Rect(0, 0, 1920, 720); val pArea = Rect(0, 0, 1080, 2340)

        Convoy.debugStart(dest)
        val rt = Runtime.getRuntime()
        fun heapMb(): Double { repeat(3) { System.gc(); Thread.sleep(30) }; return (rt.totalMemory() - rt.freeMemory()) / 1048576.0 }

        val log = StringBuilder("minute  heap MB  car ms  phone ms  threads  laps\n")
        var m = 0.0; var laps = 0; var frameNs = 0L; var phoneNs = 0L; var n = 0
        NavEngine.debugDrive(dest, r, 60)
        val baseHeap = heapMb()
        val rows = ArrayList<DoubleArray>()
        for (sec in 0 until 7200) {
            // traffic-light stops now and then; otherwise 30-50 km/h
            val speed = if (sec % 240 in 200..230) 0f else 8f + (sec % 17)
            m += speed
            if (m >= r.length - 5) { // arrived: record it like a real trip and start another lap
                m = 0.0; laps++
                looper.idleFor(java.time.Duration.ofMillis(200))
                NavEngine.debugDrive(dest, r, 60)
            }
            LocationTracker.onLocationChanged(at(m, speed))
            // two convoy friends driving the same road a little behind
            if (sec % 15 == 0) for (k in 1..2) {
                val fm = (m - 150.0 * k).coerceAtLeast(0.0)
                val p = at(fm, speed)
                Convoy.debugMember("Friend $k", LatLng(p.latitude, p.longitude), (speed * 3.6f).toInt(),
                    System.currentTimeMillis() + 600_000, r.length - fm, r.length, "driving")
            }
            if (sec % 1200 == 600) MapState.setLightMode(MapState.LightMode.LIGHT)
            if (sec % 1200 == 0) MapState.setLightMode(MapState.LightMode.DARK)
            var t0 = System.nanoTime(); car.draw(cc, 1920, 720, cArea, 1.6f, true); frameNs += System.nanoTime() - t0
            t0 = System.nanoTime(); phone.draw(pc, 1080, 2340, pArea, 2.75f, true); phoneNs += System.nanoTime() - t0
            n++
            looper.idleFor(java.time.Duration.ofMillis(5))
            if ((sec + 1) % 600 == 0) {
                val heap = heapMb()
                val row = doubleArrayOf((sec + 1) / 60.0, heap, frameNs / n / 1e6, phoneNs / n / 1e6, Thread.activeCount().toDouble(), laps.toDouble())
                rows += row
                log.append("%6.0f  %7.1f  %6.1f  %8.1f  %7.0f  %4.0f\n".format(*row.toTypedArray()))
                frameNs = 0; phoneNs = 0; n = 0
            }
        }
        // verdict: compare the last half hour with the first (after warm-up)
        val early = rows.subList(1, 3); val late = rows.takeLast(3)
        fun avg(l: List<DoubleArray>, i: Int) = l.map { it[i] }.average()
        log.append("\nstart heap %.1f MB\n".format(baseHeap))
        log.append("heap growth, 20-30 min → last 30 min: %+.1f MB\n".format(avg(late, 1) - avg(early, 1)))
        log.append("car frame change: %+.1f ms, phone frame change: %+.1f ms\n".format(avg(late, 2) - avg(early, 2), avg(late, 3) - avg(early, 3)))
        log.append("threads change: %+.0f\n".format(avg(late, 4) - avg(early, 4)))
        log.append("trips in history: ${Explorer.history().size}\n")
        File(out, "longdrive.txt").writeText(log.toString())
    }
}
