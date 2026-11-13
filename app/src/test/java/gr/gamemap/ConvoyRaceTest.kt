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

/** Convoy race start: Ready buttons, waiting, the 3-2-1 countdown and everyone starting together. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ConvoyRaceTest {
    @Test
    fun raceStart() {
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
        fun frame(): Bitmap {
            looper.idleFor(java.time.Duration.ofMillis(50))
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp)); return bmp
        }
        fun save(b: Bitmap, name: String) = File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        fun shot(name: String) { repeat(30) { frame(); Thread.sleep(30) }; save(frame(), name) }
        val log = StringBuilder()

        MapState.setLightMode(MapState.LightMode.DARK); MapState.setTheme(Theme.NEON_NOIR)
        val dest = Place("National Archaeological Museum", "Patision 44", r.points.last())
        Convoy.myName = "Vyto"
        Convoy.debugStart(dest)
        val start = r.points[0]
        LocationTracker.onLocationChanged(Location("gps").apply { latitude = start.lat; longitude = start.lon; accuracy = 5f; time = System.currentTimeMillis() })
        val now = System.currentTimeMillis()
        Convoy.debugMember("Nikos", Geo.offset(start, 90f, 25.0), 0, 0, -1.0, -1.0, "ready")
        Convoy.debugMember("Maria", Geo.offset(start, 200f, 40.0), 0, 0, -1.0, -1.0, "ready")
        Convoy.members.values.first { it.name == "Nikos" }.apply { ready = true; readyAt = now }
        MapState.zoomBy(16.5 - MapState.targetZoom)

        activity.openSheetForTest("convoy"); shot("race_1_lobby")
        Convoy.setReady(true); shot("race_2_waiting")
        log.append("after me: goAt=${Convoy.goAt} (want 0: Maria isn't ready)\n")
        activity.onBackPressedDispatcher.onBackPressed(); shot("race_3_chip")

        // Maria presses Ready: her message arrives
        Convoy.members.values.first { it.name == "Maria" }.apply { ready = true; readyAt = System.currentTimeMillis() }
        Convoy.setReady(true) // (re-checks, as a message would)
        val go = Convoy.goAt
        log.append("all ready: GO in ${go - System.currentTimeMillis()} ms\n")
        // the countdown frames, phone and car
        val car = MapRenderer(car = true)
        val cb = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        val wanted = linkedMapOf(-3500L to "race_4_everyone", -2500L to "race_5_three", -500L to "race_6_one", 300L to "race_7_go")
        while (System.currentTimeMillis() < go + 1400) {
            val b = frame()
            val t = System.currentTimeMillis() - go
            wanted.entries.firstOrNull { t >= it.key }?.let { (k, name) ->
                save(b, name); wanted.remove(k)
                if (name == "race_5_three") { car.draw(Canvas(cb), 1920, 720, Rect(0, 0, 1920, 720), 1.6f, true); save(cb, "race_car_three") }
                log.append("$name at ${t} ms: navigating=${NavEngine.active}\n")
            }
            Thread.sleep(20)
        }
        log.append("after GO: navigating=${NavEngine.active} to ${NavEngine.destinationName}; my ready reset=${!Convoy.meReady}\n")
        // leaving from the quick menu
        activity.openSheetForTest("quick"); shot("race_8_quick")
        Convoy.leave(quiet = true)
        log.append("left: active=${Convoy.active}\n")
        File(out, "convoyrace.txt").writeText(log.toString())
    }
}
