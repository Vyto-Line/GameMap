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
import java.net.HttpURLConnection
import java.net.URL

/** Stops on the way, trip history, saved places, offline maps, mark a camera; and the live-share relay. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class FeaturesPreviewTest {
    @Test
    fun screens() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        fun shot(name: String) {
            val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(40) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        MapState.setLightMode(MapState.LightMode.LIGHT); MapState.setTheme(Theme.TRAILBLAZER)
        // history and saved places
        val now = System.currentTimeMillis()
        Explorer.record(Explorer.Trip("National Archaeological Museum", now - 3_600_000, 840, 4200.0, 120, 41, 1, 1))
        Explorer.record(Explorer.Trip("Piraeus Port", now - 86_400_000, 1860, 11800.0, 0, 69, 0, 1))
        Explorer.record(Explorer.Trip("Home", now - 90_000_000, 720, 5100.0, 60, 35, 0, 1))
        Places.toggleSaved(Place("Mikel Coffee", "Kolonaki", LatLng(37.9785, 23.7410)))
        Places.toggleSaved(Place("Gym", "Neos Kosmos", LatLng(37.9570, 23.7280)))
        shot("feat_home_saved")
        activity.openSheetForTest("trips"); shot("feat_trips")
        activity.openSheetForTest("offline"); shot("feat_offline")
        activity.openSheetForTest("report"); shot("feat_report")
        // navigating with a stop on the way
        activity.onBackPressedDispatcher.onBackPressed()
        NavEngine.debugDrive(Place("National Archaeological Museum", "Patision 44", r.points.last()), r, 60)
        NavEngine.debugStop(Place("EKO", "Fuel", r.points[r.points.size / 2], "⛽"))
        val p = r.points[3]; val p2 = r.points[4]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 10f; bearing = Geo.bearing(p, p2); accuracy = 5f; time = now })
        shot("feat_nav_stop")

        // the relay the live page reads: post and read back
        val topic = "gmtest" + (now % 1_000_000)
        val log = StringBuilder()
        try {
            Http.post("https://ntfy.sh/$topic", "{\"state\":\"driving\",\"ts\":$now}", "text/plain", timeout = 8000)
            val c = URL("https://ntfy.sh/$topic/json?poll=1").openConnection() as HttpURLConnection
            log.append("ntfy ${c.responseCode}: ${c.inputStream.bufferedReader().readText().take(200)}\n")
        } catch (e: Exception) { log.append("ntfy failed: $e\n") }
        File(out, "features.txt").writeText(log.toString())
    }
}
