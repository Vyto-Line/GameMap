package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The app in Greek: a selection of phone and car screens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class GreekTest {
    private fun find(v: View): GameMapView? {
        if (v is GameMapView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test
    fun screens() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val opts = Osrm.parseAll(File(data, "route.json").readText())
        opts.forEachIndexed { i, o ->
            o.trafficSeconds = (o.duration * 2.0 + i * 90).toInt(); o.delaySeconds = if (i == 1) 240 else 30
            o.traffic = listOf(Stretch(o.length * 0.5, o.length * 0.6, 2, 60)); o.limits = listOf(Stretch(0.0, o.length, 50))
        }
        val r = opts[0]
        val dest = Place("National Archaeological Museum", "Patision 44", r.points.last())
        L.init(org.robolectric.RuntimeEnvironment.getApplication()); L.setMode("el")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun shot(name: String) {
            val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            repeat(30) { looper.idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(30) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun locate(m: Double, speed: Float = 11f) {
            var i = 0
            while (i < r.points.size - 2 && r.cum[i + 1] < m) i++
            val f = ((m - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
            val p = Geo.lerp(r.points[i], r.points[i + 1], f)
            LocationTracker.onLocationChanged(Location("gps").apply {
                latitude = p.lat; longitude = p.lon; this.speed = speed; bearing = Geo.bearing(r.points[i], r.points[i + 1])
                accuracy = 5f; time = System.currentTimeMillis()
            })
        }
        fun back() { activity.onBackPressedDispatcher.onBackPressed(); looper.idle() }
        val now = System.currentTimeMillis()
        Explorer.record(Explorer.Trip("National Archaeological Museum", now - 3_600_000, 840, 4200.0, 120, 41, 1, 1))
        Explorer.record(Explorer.Trip("Piraeus Port", now - 86_400_000, 1860, 11800.0, 0, 69, 0, 1))
        Places.toggleSaved(Place("Mikel Coffee", "Kolonaki", LatLng(37.9785, 23.7410)))
        locate(300.0, 0f)

        for ((mode, th) in listOf("light" to Theme.TRAILBLAZER)) {
            MapState.setTheme(th)
            MapState.setLightMode(if (mode == "light") MapState.LightMode.LIGHT else MapState.LightMode.DARK)
            MapState.zoomBy(16.5 - MapState.targetZoom)
            shot("g_${mode}_01_home")
            for ((k, sheet) in listOf("style", "settings", "trips", "offline", "convoy", "report").withIndex()) {
                activity.openSheetForTest(sheet); shot("g_${mode}_0${k + 2}_$sheet"); back()
            }
            val map = find(root)!!
            map.onPlaceTap?.invoke(Place("EKO", "Fuel · Patision 60", LatLng(37.9880, 23.7300), "⛽", rating = 4.1, ratings = 212,
                extra = "95 €1.970 · Diesel €2.035 (area average, 11 Nov)"))
            shot("g_${mode}_08_place"); back()
            map.onLongPress?.invoke(LatLng(37.9830, 23.7262)); shot("g_${mode}_09_pin"); back()
            NavEngine.debugPlan(dest, opts); shot("g_${mode}_10_routes")
            NavEngine.debugDrive(dest, r, 120); locate(400.0)
            repeat(200) { MapState.tick(); LocationTracker.tick(); looper.idleFor(java.time.Duration.ofMillis(10)) }
            shot("g_${mode}_11_drive")
            NavEngine.debugStop(Place("EKO", "Fuel", r.points[r.points.size * 2 / 3], "⛽"))
            Convoy.debugStart(dest)
            Convoy.debugMember("Nikos", r.points[r.points.size / 2], 48, now + 9 * 60_000L, r.length * 0.4, r.length, "driving")
            Convoy.debugMember("Maria", r.points[5], 31, now + 14 * 60_000L, r.length * 0.85, r.length, "driving")
            shot("g_${mode}_12_drive_stop_convoy")
            Convoy.leave(quiet = true)
            NavEngine.debugOffer(opts.getOrElse(1) { opts[0] }, 240); shot("g_${mode}_13_offer")
            NavEngine.stop()
            NavEngine.debugTrip(Explorer.Trip("National Archaeological Museum", now, 840, 4200.0, 120, 41, 1, 1))
            shot("g_${mode}_14_trip_done")
            NavEngine.dismissTrip()
            looper.idle()
        }

        // the first-launch screens
        MapState.setTheme(Theme.OPEN_WORLD); MapState.setLightMode(MapState.LightMode.LIGHT)
        for (k in 0..2) { activity.showWelcome(k); shot("g_welcome_$k") }
        back(); back(); activity.showWelcome(2)
        // settings pages
        activity.openSheetForTest("settings"); shot("g_settings_groups")
                // car: idle, driving, place card, convoy; light and dark
        val w = 1920; val h = 720
        for ((mode, th) in listOf("light" to Theme.TRAILBLAZER)) {
            MapState.setTheme(th)
            MapState.setLightMode(if (mode == "light") MapState.LightMode.LIGHT else MapState.LightMode.DARK)
            val rr = MapRenderer(car = true).apply { carButtons = true }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp); val area = Rect(0, 0, w, h)
            fun car(name: String) {
                repeat(30) { rr.draw(c, w, h, area, 1.6f, true); looper.idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(30) }
                rr.draw(c, w, h, area, 1.6f, true)
                File(out, "g_car_${mode}_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            NavEngine.stop(); locate(300.0, 0f); car("1_idle")
            rr.selectedPlace = Place("EKO", "Fuel", LatLng(37.9880, 23.7300), "⛽", extra = "95 €1.970 · Diesel €2.035 (area average)")
            car("2_place"); rr.selectedPlace = null
            NavEngine.debugDrive(dest, r, 120); locate(400.0)
            repeat(100) { MapState.tick(); LocationTracker.tick(); looper.idleFor(java.time.Duration.ofMillis(10)) }
            car("3_drive")
            Convoy.debugStart(dest)
            Convoy.debugMember("Nikos", r.points[r.points.size / 2], 48, now + 9 * 60_000L, r.length * 0.4, r.length, "driving")
            car("4_convoy"); Convoy.leave(quiet = true)
            locate(r.length - 2, 0f); repeat(50) { looper.idleFor(java.time.Duration.ofMillis(20)) }
            car("5_arrive")
            NavEngine.stop()
        }
    }
}
