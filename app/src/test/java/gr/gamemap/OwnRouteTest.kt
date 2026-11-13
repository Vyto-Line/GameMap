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

/** In a convoy, driving somewhere of my own: a normal route screen; the others still on the map. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class OwnRouteTest {
    @Test
    fun ownRoute() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val r = Osrm.parseAll(File(data, "route.json").readText())[0]
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun shot(name: String) {
            repeat(30) { looper.idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(30) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.NEON_NOIR)
        val p = r.points[10]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; accuracy = 5f; speed = 9f; bearing = Geo.bearing(r.points[10], r.points[11]); time = System.currentTimeMillis() })
        Convoy.myName = "Vyto"
        Convoy.debugStart(Place("Acropolis Museum", "", LatLng(37.9685, 23.7285))) // the convoy goes elsewhere
        Convoy.debugMember("Nikos", r.points[30], 40, System.currentTimeMillis() + 600_000, 3000.0, 6000.0, "driving")
        NavEngine.debugDrive(Place("National Archaeological Museum", "", r.points.last()), r, 0) // my own route
        MapState.zoomBy(16.0 - MapState.targetZoom)
        shot("own_1_drive")
        activity.openSheetForTest("convoy"); shot("own_2_sheet")
        val st = Convoy::class.java.getDeclaredMethod("myState").apply { isAccessible = true }.invoke(Convoy)
        File(out, "ownroute.txt").writeText("on convoy route: ${Convoy.onConvoyRoute} (want false); I tell the others: $st (want own)\n")
        Convoy.leave(quiet = true); NavEngine.stop()
    }
}
