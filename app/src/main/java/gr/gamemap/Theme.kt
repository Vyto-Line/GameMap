package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Game-style looks. The base map is a dark vector-style raster (CARTO "dark_all", from
 * OpenStreetMap data); each theme recolours it once per tile and adds its own overlays.
 */
enum class Theme(
    val title: String,
    val background: Int,
    val route: Int,
    val routeGlow: Int,
    val player: Int,
    val playerOutline: Int,
    val hudText: Int,
    val hudPanel: Int,
    /** Draw the map inside a round radar instead of full screen. */
    val radar: Boolean = false,
    /** Downscale factor for chunky pixels (1 = smooth). */
    val pixel: Int = 1,
    val scanlines: Boolean = false,
    val grid: Boolean = false,
) {
    OPEN_WORLD(
        "Open World", Color.rgb(8, 10, 14),
        route = Color.rgb(177, 77, 255), routeGlow = Color.argb(110, 177, 77, 255),
        player = Color.WHITE, playerOutline = Color.BLACK,
        hudText = Color.WHITE, hudPanel = Color.argb(170, 0, 0, 0),
        radar = true,
    ),
    NEON(
        "Neon Racer", Color.rgb(12, 2, 24),
        route = Color.rgb(0, 240, 255), routeGlow = Color.argb(120, 0, 240, 255),
        player = Color.rgb(255, 43, 214), playerOutline = Color.WHITE,
        hudText = Color.rgb(0, 240, 255), hudPanel = Color.argb(170, 20, 0, 40),
        grid = true,
    ),
    RETRO(
        "8-Bit", Color.rgb(155, 188, 15),
        route = Color.rgb(15, 56, 15), routeGlow = Color.argb(90, 48, 98, 48),
        player = Color.rgb(15, 56, 15), playerOutline = Color.rgb(155, 188, 15),
        hudText = Color.rgb(15, 56, 15), hudPanel = Color.argb(210, 139, 172, 15),
        pixel = 4,
    ),
    NIGHT_VISION(
        "Night Vision", Color.rgb(0, 8, 0),
        route = Color.rgb(182, 255, 110), routeGlow = Color.argb(110, 120, 255, 60),
        player = Color.rgb(232, 255, 224), playerOutline = Color.rgb(0, 40, 0),
        hudText = Color.rgb(150, 255, 120), hudPanel = Color.argb(170, 0, 20, 0),
        scanlines = true,
    );

    /** Recolours one downloaded base tile into this theme. */
    fun process(raw: Bitmap): Bitmap {
        if (pixel > 1) return retro(raw)
        val out = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix()) }
        Canvas(out).drawBitmap(raw, 0f, 0f, paint)
        return out
    }

    private fun matrix(): ColorMatrix {
        // L = luminance weights
        val lr = 0.30f; val lg = 0.59f; val lb = 0.11f
        fun lum(kr: Float, kg: Float, kb: Float, or: Float, og: Float, ob: Float) = ColorMatrix(
            floatArrayOf(
                lr * kr, lg * kr, lb * kr, 0f, or,
                lr * kg, lg * kg, lb * kg, 0f, og,
                lr * kb, lg * kb, lb * kb, 0f, ob,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        return when (this) {
            OPEN_WORLD -> ColorMatrix(
                floatArrayOf(
                    1.6f, 0f, 0f, 0f, 8f,
                    0f, 1.7f, 0f, 0f, 12f,
                    0f, 0f, 2.0f, 0f, 22f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            NEON -> lum(2.4f, 0.55f, 3.2f, 10f, 0f, 24f)
            NIGHT_VISION -> lum(0.3f, 2.8f, 0.4f, 0f, 10f, 0f)
            RETRO -> ColorMatrix()
        }
    }

    /** Game Boy style: shrink, then snap every pixel to a 4-shade green palette. */
    private fun retro(raw: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(raw, raw.width / pixel, raw.height / pixel, true)
        val w = small.width; val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val palette = intArrayOf(
            Color.rgb(155, 188, 15), Color.rgb(139, 172, 15),
            Color.rgb(48, 98, 48), Color.rgb(15, 56, 15),
        )
        for (i in px.indices) {
            val c = px[i]
            val l = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
            px[i] = palette[when {
                l < 22 -> 0
                l < 50 -> 1
                l < 95 -> 2
                else -> 3
            }]
        }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }
}
