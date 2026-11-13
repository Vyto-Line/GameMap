package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.os.Looper
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Is the recenter button there after moving the map, in and out of a convoy, driving or not? */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ConvoyRecenterTest {
    @Test
    fun recenter() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        val btn = MainActivity::class.java.getDeclaredField("recenterBtn").apply { isAccessible = true }.get(activity) as View
        val log = StringBuilder()
        fun idle(ms: Long) { repeat((ms / 50).toInt()) { looper.idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(50) } }
        fun shot(name: String) {
            idle(1500)
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val p = r.points[5]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; accuracy = 5f; speed = 10f; time = System.currentTimeMillis() })
        val dest = Place("Museum", "", r.points.last())
        fun check(label: String) {
            MapState.phone.recenter(); idle(500)
            MapState.phone.pan(Geo.offset(p, 45f, 300.0), 0f); idle(1000)
            log.append("$label: after moving the map, recenter ${if (btn.visibility == View.VISIBLE) "shown" else "HIDDEN"} (following=${MapState.phone.following})")
            idle(3000)
            log.append(", 3 s later ${if (btn.visibility == View.VISIBLE) "shown" else "HIDDEN"} (following=${MapState.phone.following})\n")
        }
        check("no convoy, browsing")
        Convoy.myName = "Vyto"; Convoy.debugStart(dest)
        Convoy.debugMember("Nikos", r.points[20], 45, System.currentTimeMillis() + 600_000, r.length * 0.5, r.length, "driving")
        check("convoy, browsing"); shot("recenter_convoy_browse")
        NavEngine.debugDrive(dest, r, 0)
        check("convoy, driving"); shot("recenter_convoy_drive")
        Convoy.leave(quiet = true)
        check("no convoy, driving")
        File(out, "recenter.txt").writeText(log.toString())
    }
}
