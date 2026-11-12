package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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

/** Places on the map, a tapped place's card, a dropped pin (phone), and the car's place card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class PlacesPreviewTest {
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
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        fun shot(name: String) {
            val tmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            repeat(50) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)); root.draw(Canvas(tmp)); Thread.sleep(50) }
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        MapState.setLightMode(MapState.LightMode.LIGHT); MapState.setTheme(Theme.TRAILBLAZER)
        MapState.zoomBy(17.0 - MapState.targetZoom)
        shot("places_map")
        val map = find(root)!!
        // tap a place: take the first fuel or parking icon drawn
        val c = Canvas(Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888))
        map.draw(c)
        map.onPlaceTap?.invoke(Place("Δημοτικό Πάρκινγκ «Πασά»", "Car park", LatLng(37.9845, 23.7290), "🅿", rating = 4.2, ratings = 318))
        shot("places_card")
        map.onLongPress?.invoke(LatLng(37.9830, 23.7262))
        shot("places_pin")

        // car
        for (th in listOf(Theme.NEON_NOIR, Theme.TRAILBLAZER)) {
            MapState.setTheme(th); MapState.setLightMode(if (th == Theme.NEON_NOIR) MapState.LightMode.DARK else MapState.LightMode.LIGHT)
            MapState.car.zoomBy(17.0 - MapState.car.targetZoom)
            val w = 1920; val h = 720
            val rr = MapRenderer(car = true).apply { carButtons = true }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cc = Canvas(bmp)
            val area = Rect(0, 0, w, h)
            repeat(40) { rr.draw(cc, w, h, area, 1.6f, true); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(50) }
            rr.selectedPlace = Place("EKO", "Fuel", LatLng(37.9850, 23.7300), "⛽", extra = "95 €1.970 · 98 €2.292 · Diesel €2.035 · LPG €1.210 (area average, 11 Nov)")
            rr.draw(cc, w, h, area, 1.6f, true)
            File(out, "places_car_${th.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
