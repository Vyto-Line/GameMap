package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Screenshots of alerts: camera ahead, incidents and reports on the map, report sheet, still-there card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class Build4PreviewTest {
    @Test
    fun screens() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val opts = Osrm.parseAll(File(data, "route.json").readText())
        val r = opts[0]
        r.limits = listOf(Stretch(0.0, r.length, 50))
        val place = Place("National Archaeological Museum", "Patision 44", r.points.last())
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        fun settle(rounds: Int = 50) {
            val tmp = Bitmap.createBitmap(maxOf(root.width, 1), maxOf(root.height, 1), Bitmap.Config.ARGB_8888)
            val c = Canvas(tmp)
            repeat(rounds) { shadowOf(Looper.getMainLooper()).idle(); root.draw(c); Thread.sleep(60) }
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun shot(name: String) {
            settle()
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun at(m: Double): LatLng {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            return Geo.lerp(r.points[i], r.points[i + 1], f)
        }
        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.OPEN_WORLD)
        NavEngine.debugDrive(place, r, 60)
        val me = 600.0
        val now = System.currentTimeMillis()
        Alerts.debugInject(
            listOf(Alerts.Camera(at(me + 280), 50, null)),
            listOf(Alerts.Incident(at(me + 900), 9, "Roadworks", 60), Alerts.Incident(at(me + 1300), 1, "Accident", 120)),
            listOf(
                Alerts.Report("a", "police", at(me + 600), null, now - 600_000, now - 300_000, 3, 0),
                Alerts.Report("b", "hazard", at(me + 5), null, now - 900_000, now - 900_000, 0, 0),
            ),
        )
        val pos = at(me)
        val p2 = at(me + 10)
        val loc = Location("gps").apply {
            latitude = pos.lat; longitude = pos.lon; speed = 12f; bearing = Geo.bearing(pos, p2); accuracy = 5f
            time = System.currentTimeMillis()
        }
        LocationTracker.onLocationChanged(loc)
        MapState.zoomBy(16.0 - MapState.targetZoom)
        repeat(300) { MapState.tick(); LocationTracker.tick(); Thread.sleep(2) }
        File(out, "build4.txt").writeText("ahead=${Alerts.ahead?.label} ${Alerts.ahead?.dist} ask=${Alerts.askAbout?.type}\n")
        shot("ui4_drive_alerts")
        Alerts.clearAsk()
        activity.getSharedPreferences("gamemap", 0).edit().putString("reportsUrl", "https://example-default-rtdb.firebaseio.com").apply()
        activity.openReportForTest()
        shot("ui4_report_sheet")
    }
}
