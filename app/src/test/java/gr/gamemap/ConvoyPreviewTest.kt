package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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

/** Convoy: strip and list on the phone, box on the car, friends on the map. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ConvoyPreviewTest {
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
        fun at(i: Int) = r.points[i.coerceIn(0, r.points.size - 1)]
        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.NEON_NOIR)
        val dest = Place("National Archaeological Museum", "Patision 44", r.points.last())
        Convoy.myName = "Vyto"
        Convoy.debugStart(dest)
        NavEngine.debugDrive(dest, r, 60)
        val n = r.points.size
        val now = System.currentTimeMillis()
        Convoy.debugMember("Nikos", at(n * 2 / 3), 68, now + 9 * 60_000L, r.length * 0.3, r.length, "driving")
        Convoy.debugMember("Maria", at(n / 6), 37, now + 14 * 60_000L, r.length * 0.75, r.length, "driving")
        Convoy.debugMember("Giorgos", at(2), 0, now + 20 * 60_000L, r.length * 0.95, r.length, "driving")
        val p = at(n / 3); val p2 = at(n / 3 + 1)
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = p.lat; longitude = p.lon; speed = 11.7f; bearing = Geo.bearing(p, p2); accuracy = 5f; time = now })
        MapState.zoomBy(15.5 - MapState.targetZoom)
        shot("convoy_drive")
        activity.openSheetForTest("convoy"); shot("convoy_list")
        // car
        val w = 1920; val h = 720
        val rr = MapRenderer(car = true).apply { carButtons = true }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        MapState.car.zoomBy(15.0 - MapState.car.targetZoom)
        repeat(40) { rr.draw(c, w, h, Rect(0, 0, w, h), 1.6f, true); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(40) }
        File(out, "convoy_car.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
