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

/** Screenshots of the game side: Trailblazer landmarks, discovery banner, arrival cards. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class Build3PreviewTest {
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
        fun settle(rounds: Int = 50) {
            val tmp = Bitmap.createBitmap(maxOf(root.width, 1), maxOf(root.height, 1), Bitmap.Config.ARGB_8888)
            val c = Canvas(tmp)
            repeat(rounds) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)); root.draw(c); Thread.sleep(60) }
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun shot(name: String) {
            settle()
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val here = LatLng(37.9838, 23.7275)
        val loc = Location("gps").apply { latitude = here.lat; longitude = here.lon; accuracy = 5f; time = System.currentTimeMillis() }
        LocationTracker.onLocationChanged(loc)
        MapState.zoomBy(15.6 - MapState.targetZoom)
        repeat(300) { MapState.tick(); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10)) }
        MapState.setLightMode(MapState.LightMode.LIGHT); MapState.setTheme(Theme.TRAILBLAZER)
        settle()
        val all = Explorer.near(here, 2500.0)
        val log = StringBuilder("landmarks ${all.size}\n")
        all.take(40).forEach { log.append("  ${it.kind} ${it.name} ${it.pos}\n") }
        File(out, "build3.txt").writeText(log.toString())
        // discover the nearest one so the banner shows
        all.minByOrNull { Geo.distance(here, it.pos) }?.let { l ->
            Explorer.onLocation(l.pos, 0f)
        }
        shot("ui3_trailblazer_home")

        val trip = Explorer.Trip("National Archaeological Museum", System.currentTimeMillis(), 8 * 60 + 20, 1450.0, 150, 47, 1, Explorer.level)
        MapState.setLightMode(MapState.LightMode.LIGHT); MapState.setTheme(Theme.PARCHMENT_QUEST)
        NavEngine.debugTrip(trip)
        shot("ui3_arrival_parchment")
        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.NEON_NOIR)
        shot("ui3_arrival_neon_noir")
        for (th in listOf(Theme.NEON_NOIR, Theme.LOOKOUT)) {
            MapState.setTheme(th)
            val w = 1920; val h = 720
            val rr = MapRenderer(car = true).apply { carButtons = true }
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val area = android.graphics.Rect(0, 90, w, h)
            repeat(50) { rr.draw(c, w, h, area, 1.6f, hud = true); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(60) }
            rr.draw(c, w, h, area, 1.6f, hud = true)
            File(out, "ui3_car_arrival_${th.name.lowercase()}.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        MapState.setLightMode(MapState.LightMode.LIGHT); MapState.setTheme(Theme.RETRO)
        shot("ui3_arrival_retro")
    }
}
