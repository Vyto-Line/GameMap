package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Screenshots of the home screen, style picker, settings and themed panels. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class Build2PreviewTest {
    @Test
    fun screens() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        data.listFiles()?.filter { it.name.endsWith(".pbf") }?.forEach { f ->
            val (x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            VectorSource.put(14, x, y, VectorTile(f.readBytes()))
        }
        Places.addRecent(Place("Piraeus Port", "Akti Miaouli, Piraeus", LatLng(37.9420, 23.6470)))
        Places.addRecent(Place("Syntagma", "Athens", LatLng(37.9755, 23.7348)))
        Places.addRecent(Place("Acropolis Museum", "Dionysiou Areopagitou 15, Athens", LatLng(37.9685, 23.7286)))
        Places.save("home", Place("Home", "Kifisias 100", LatLng(38.0000, 23.7800)))

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        fun settle(rounds: Int = 60) {
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
        MapState.zoomBy(16.4 - MapState.targetZoom)

        MapState.setLightMode(MapState.LightMode.LIGHT); MapState.setTheme(Theme.TRAILBLAZER)
        shot("ui2_home_trailblazer")
        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.NEON_NOIR)
        shot("ui2_home_neon_noir")
        MapState.setLightMode(MapState.LightMode.LIGHT); MapState.setTheme(Theme.RETRO)
        shot("ui2_home_retro")

        MapState.setLightMode(MapState.LightMode.LIGHT); MapState.setTheme(Theme.OPEN_WORLD)
        activity.showStylePicker()
        shot("ui2_style_picker")
        activity.onBackPressedDispatcher.onBackPressed()

        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.LOOKOUT)
        activity.showSettings()
        shot("ui2_settings")
    }
}
