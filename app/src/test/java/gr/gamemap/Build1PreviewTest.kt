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

/** Screenshots of the new driving screens (phone UI + car surface) with a real OSRM route. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class Build1PreviewTest {
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
            o.trafficSeconds = (o.duration * 2.2 + i * 100).toInt()
            o.delaySeconds = if (i == 1) 240 else 30
            o.traffic = if (i == 1) listOf(Stretch(o.length * 0.3, o.length * 0.55, 3, 200))
            else listOf(Stretch(o.length * 0.5, o.length * 0.58, 1, 30))
            o.limits = listOf(Stretch(0.0, o.length, 50))
        }
        val place = Place("National Archaeological Museum", "Patision 44", opts[0].points.last())
        val log = StringBuilder()
        log.append("routes ${opts.size}\n")
        opts.forEach { o ->
            log.append("via=${o.via} len=${o.length.toInt()} steps=${o.steps.size} lanes=${o.steps.count { it.lanes != null }}\n")
            o.steps.forEach { s -> log.append("   ${s.type}/${s.direction} '${s.road}' exit=${s.exit} lanes=${s.lanes?.joinToString { l -> l.directions.joinToString("|") + (if (l.valid) "*" else "") }}\n") }
        }

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.window.decorView

        fun settle(rounds: Int = 60) {
            val tmp = Bitmap.createBitmap(maxOf(root.width, 1), maxOf(root.height, 1), Bitmap.Config.ARGB_8888)
            val c = Canvas(tmp)
            repeat(rounds) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60))
                root.draw(c)
                Thread.sleep(60)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun shot(name: String) {
            settle()
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // 1. route preview (Neon Noir, dark)
        MapState.setLightMode(MapState.LightMode.DARK)
        MapState.setTheme(Theme.NEON_NOIR)
        NavEngine.debugPlan(place, opts)
        shot("ui_preview_neon_noir")
        MapState.setLightMode(MapState.LightMode.LIGHT)
        MapState.setTheme(Theme.TRAILBLAZER)
        shot("ui_preview_trailblazer")

        // 2. driving (Open World, dark), 140 m before a junction with lanes
        MapState.setLightMode(MapState.LightMode.DARK)
        MapState.setTheme(Theme.OPEN_WORLD)
        val r = opts[0]
        NavEngine.debugDrive(place, r, 120)
        val target = r.steps.firstOrNull { it.lanes != null && r.cum[it.routeIndex] > 250 }
            ?: r.steps.getOrElse(2) { r.steps.last() }
        val at = (r.cum[target.routeIndex] - 140).coerceAtLeast(5.0)
        var i = 0
        while (i < r.points.size - 2 && r.cum[i + 1] < at) i++
        val f = ((at - r.cum[i]) / maxOf(r.cum[i + 1] - r.cum[i], 0.1)).coerceIn(0.0, 1.0)
        val pos = Geo.lerp(r.points[i], r.points[i + 1], f)
        r.traffic = listOf(Stretch(at + 450, at + 800, 3, 120))
        val loc = Location("gps").apply {
            latitude = pos.lat; longitude = pos.lon
            speed = 12f; bearing = Geo.bearing(r.points[i], r.points[i + 1]); accuracy = 5f
            time = System.currentTimeMillis()
        }
        LocationTracker.onLocationChanged(loc)
        MapState.zoomBy(16.9 - MapState.targetZoom)
        repeat(300) { MapState.tick(); LocationTracker.tick(); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10)) }
        log.append("driving: step=${NavEngine.nextStep} dist=${NavEngine.distanceToNext.toInt()} limit=${NavEngine.speedLimit} jam=${NavEngine.jamAhead} then=${NavEngine.followingStep?.text}\n")
        shot("ui_drive_open_world")
        MapState.setTheme(Theme.PARCHMENT_QUEST); MapState.setLightMode(MapState.LightMode.LIGHT)
        shot("ui_drive_parchment")

        // 3. car surface while driving
        MapState.setTheme(Theme.OPEN_WORLD); MapState.setLightMode(MapState.LightMode.DARK)
        run {
            val w = 1920; val h = 720
            val rr = MapRenderer()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val area = Rect(620, 0, w, h)
            repeat(60) { rr.draw(c, w, h, area, 1.6f, hud = false); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)); Thread.sleep(60) }
            rr.draw(c, w, h, area, 1.6f, hud = false)
            File(out, "ui_car_drive.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // 4. faster route offer
        NavEngine.debugOffer(opts.getOrElse(1) { opts[0] }, 240)
        shot("ui_offer")

        File(out, "build1.txt").writeText(log.toString())
    }
}
