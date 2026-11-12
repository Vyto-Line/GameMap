package gr.gamemap

import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Can the map be dragged while previewing a route and while navigating? */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class PanTest {
    @Test
    fun pan() {
        val data = File(System.getProperty("previewData") ?: "../previewdata")
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val log = StringBuilder()
        val opts = Osrm.parseAll(File(data, "route.json").readText())
        val place = Place("Museum", "", opts[0].points.last())
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val loc = Location("gps").apply { latitude = opts[0].points[3].lat; longitude = opts[0].points[3].lon; accuracy = 5f; speed = 8f; bearing = 10f; time = System.currentTimeMillis() }
        LocationTracker.onLocationChanged(loc)
        fun idle() { repeat(10) { shadowOf(Looper.getMainLooper()).idle(); root.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888))); Thread.sleep(30) } }
        fun drag(tag: String) {
            idle()
            val before = "following=${MapState.following} center=${MapState.freeCenter}"
            val t = SystemClock.uptimeMillis()
            val x = root.width / 2f; var y = root.height / 2f
            // find what's under the finger
            val hit = StringBuilder()
            fun find(v: android.view.View, depth: Int) {
                val r = IntArray(2); v.getLocationOnScreen(r)
                if (v.visibility == android.view.View.VISIBLE && x >= r[0] && x < r[0] + v.width && y >= r[1] && y < r[1] + v.height) {
                    hit.append("${"  ".repeat(depth)}${v.javaClass.simpleName} clickable=${v.isClickable} h=${v.height}\n")
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), depth + 1)
                }
            }
            find(root, 0)
            root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            repeat(10) { k -> y += 30f; root.dispatchTouchEvent(MotionEvent.obtain(t, t + 20L * (k + 1), MotionEvent.ACTION_MOVE, x, y, 0)) }
            root.dispatchTouchEvent(MotionEvent.obtain(t, t + 300, MotionEvent.ACTION_UP, x, y, 0))
            idle()
            log.append("$tag: before $before\n   after following=${MapState.following} center=${MapState.freeCenter}\n$hit\n")
        }
        drag("idle")
        MapState.recenter()
        NavEngine.debugPlan(place, opts)
        idle()
        drag("preview")
        NavEngine.debugDrive(place, opts[0], 30)
        LocationTracker.onLocationChanged(loc)
        drag("navigating")
        File(out, "pan.txt").writeText(log.toString())
    }
}
