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

/** Fuel nearby with real data around Athens: by price, chips, filters sheet; and what the car list would show. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class FuelPreviewTest {
    @Test
    fun screens() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val looper = shadowOf(Looper.getMainLooper())
        fun shot(name: String, rounds: Int = 30) {
            val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            repeat(rounds) { looper.idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(40) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = 37.9838; longitude = 23.7275; accuracy = 5f; time = System.currentTimeMillis() })
        MapState.setTheme(Theme.TRAILBLAZER); MapState.setLightMode(MapState.LightMode.LIGHT)
        MapState.zoomBy(15.0 - MapState.targetZoom)
        Fuel.myFuels = setOf("sp95", "diesel", "lpg"); Fuel.chosen = "diesel"; Fuel.sort = "cheapest"
        activity.showFuelForTest()
        shot("fuel_list", rounds = 250) // waits for the price feed
        activity.openSheetForTest("fuelfilters"); shot("fuel_filters")
        // what the car would list
        val near = LatLng(37.9838, 23.7275)
        val all = Places.nearbyFull(listOf("fuel", "charging_station"), near, Places.nearbyLocal(listOf("fuel", "charging_station"), near)).orEmpty()
        val log = StringBuilder("found ${all.size}: ${all.count { it.icon == "⛽" }} fuel, ${all.count { it.icon == "🔌" }} chargers\n")
        for (k in listOf("diesel", "sp95", "ev")) {
            Fuel.chosen = k
            log.append("\n$k, cheapest:\n")
            Fuel.arrange(all, near).take(6).forEach {
                log.append("  ${it.icon} ${it.name} [${it.brand}] ${Fuel.priceOf(it, k) ?: ""} ${if (it.areaAvg) "(area avg)" else ""} plugs=${it.plugs} kw=${it.kw}\n")
            }
        }
        File(out, "fuel.txt").writeText(log.toString())
    }
}
