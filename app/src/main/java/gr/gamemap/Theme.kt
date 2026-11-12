package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface

/** How the HUD panels are drawn. */
enum class HudShape { ROUND, CHAMFER, PIXEL }

/**
 * Game-style looks. The base map is CARTO "dark_all" (OpenStreetMap data): dark land,
 * darker water, grey roads, light labels. Each theme recolours it once per tile, either
 * with a colour matrix or by mapping brightness onto a colour ramp, and adds overlays.
 *
 * Themes are original designs evoking a genre's feel; they don't reuse any game's art.
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
    /**
     * Brightness → colour stops, as pairs (brightness 0..255, colour).
     * The base map's water is darkest, then land, minor roads, major roads, labels.
     */
    val ramp: IntArray? = null,
    /** Snap to the nearest ramp colour instead of blending (blocky looks). */
    val quantize: Boolean = false,
    val hud: HudShape = HudShape.ROUND,
    val serif: Boolean = false,
    val dashedRoute: Boolean = false,
    val paper: Boolean = false,
    val sunGlow: Boolean = false,
) {
    OPEN_WORLD(
        "Open World", Color.rgb(8, 10, 14),
        route = Color.rgb(177, 77, 255), routeGlow = Color.argb(110, 177, 77, 255),
        player = Color.WHITE, playerOutline = Color.BLACK,
        hudText = Color.WHITE, hudPanel = Color.argb(170, 0, 0, 0),
        radar = true,
        ramp = intArrayOf(
            0, Color.rgb(54, 75, 92),     // water: slate blue
            14, Color.rgb(30, 36, 40),    // land: dark asphalt grey
            40, Color.rgb(62, 70, 76),    // minor roads
            90, Color.rgb(150, 156, 160), // major roads
            200, Color.rgb(235, 235, 235),
        ),
    ),
    NEON_NOIR(
        "Neon Noir", Color.rgb(4, 14, 17),
        route = Color.rgb(252, 238, 10), routeGlow = Color.argb(120, 252, 238, 10),
        player = Color.rgb(255, 51, 85), playerOutline = Color.rgb(252, 238, 10),
        hudText = Color.rgb(252, 238, 10), hudPanel = Color.argb(200, 6, 20, 24),
        scanlines = true, hud = HudShape.CHAMFER,
        ramp = intArrayOf(
            0, Color.rgb(2, 8, 12),
            14, Color.rgb(7, 22, 27),
            40, Color.rgb(12, 70, 82),
            90, Color.rgb(32, 196, 214),
            200, Color.rgb(220, 252, 255),
        ),
    ),
    NEON_RACER(
        "Neon Racer", Color.rgb(12, 2, 24),
        route = Color.rgb(0, 240, 255), routeGlow = Color.argb(120, 0, 240, 255),
        player = Color.rgb(255, 43, 214), playerOutline = Color.WHITE,
        hudText = Color.rgb(0, 240, 255), hudPanel = Color.argb(170, 20, 0, 40),
        grid = true,
    ),
    BLOCKY(
        "Blocky", Color.rgb(94, 158, 58),
        route = Color.rgb(255, 216, 61), routeGlow = Color.argb(120, 60, 40, 10),
        player = Color.WHITE, playerOutline = Color.rgb(40, 40, 40),
        hudText = Color.WHITE, hudPanel = Color.argb(200, 55, 55, 55),
        pixel = 8, quantize = true, hud = HudShape.PIXEL,
        ramp = intArrayOf(
            0, Color.rgb(63, 118, 228),   // water
            10, Color.rgb(94, 158, 58),   // grass
            32, Color.rgb(76, 133, 48),   // darker grass
            48, Color.rgb(150, 116, 74),  // dirt path
            85, Color.rgb(138, 138, 138), // stone road
            170, Color.rgb(240, 240, 240),
        ),
    ),
    PARCHMENT_QUEST(
        "Parchment Quest", Color.rgb(234, 217, 174),
        route = Color.rgb(179, 38, 30), routeGlow = Color.argb(70, 179, 38, 30),
        player = Color.rgb(46, 110, 82), playerOutline = Color.rgb(250, 240, 210),
        hudText = Color.rgb(74, 52, 32), hudPanel = Color.argb(225, 240, 226, 186),
        serif = true, dashedRoute = true, paper = true,
        ramp = intArrayOf(
            0, Color.rgb(150, 184, 176),  // faded sea
            14, Color.rgb(234, 217, 174), // paper
            36, Color.rgb(212, 190, 140),
            70, Color.rgb(140, 106, 63),  // ink roads
            150, Color.rgb(74, 52, 32),
        ),
    ),
    LOOKOUT(
        "Lookout", Color.rgb(43, 27, 61),
        route = Color.rgb(242, 242, 232), routeGlow = Color.argb(110, 63, 184, 175),
        player = Color.rgb(63, 184, 175), playerOutline = Color.rgb(242, 242, 232),
        hudText = Color.rgb(247, 213, 155), hudPanel = Color.argb(190, 43, 27, 61),
        sunGlow = true,
        ramp = intArrayOf(
            0, Color.rgb(30, 42, 74),     // lake
            14, Color.rgb(43, 27, 61),    // dusk forest
            40, Color.rgb(110, 46, 79),
            75, Color.rgb(232, 131, 58),  // roads glowing orange
            170, Color.rgb(247, 213, 155),
        ),
    ),
    RETRO(
        "8-Bit", Color.rgb(155, 188, 15),
        route = Color.rgb(15, 56, 15), routeGlow = Color.argb(90, 48, 98, 48),
        player = Color.rgb(15, 56, 15), playerOutline = Color.rgb(155, 188, 15),
        hudText = Color.rgb(15, 56, 15), hudPanel = Color.argb(210, 139, 172, 15),
        pixel = 4, quantize = true, hud = HudShape.PIXEL,
        ramp = intArrayOf(
            0, Color.rgb(155, 188, 15),
            22, Color.rgb(139, 172, 15),
            50, Color.rgb(48, 98, 48),
            95, Color.rgb(15, 56, 15),
        ),
    ),
    NIGHT_VISION(
        "Night Vision", Color.rgb(0, 8, 0),
        route = Color.rgb(182, 255, 110), routeGlow = Color.argb(110, 120, 255, 60),
        player = Color.rgb(232, 255, 224), playerOutline = Color.rgb(0, 40, 0),
        hudText = Color.rgb(150, 255, 120), hudPanel = Color.argb(170, 0, 20, 0),
        scanlines = true,
    );

    val typeface: Typeface
        get() = if (serif) Typeface.create(Typeface.SERIF, Typeface.BOLD)
        else Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    /** Recolours one downloaded base tile into this theme. */
    fun process(raw: Bitmap): Bitmap {
        val r = ramp
        if (r != null) return applyRamp(raw, r)
        val out = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix()) }
        Canvas(out).drawBitmap(raw, 0f, 0f, paint)
        return out
    }

    private fun matrix(): ColorMatrix {
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
            NEON_RACER -> lum(2.4f, 0.55f, 3.2f, 10f, 0f, 24f)
            NIGHT_VISION -> lum(0.3f, 2.8f, 0.4f, 0f, 10f, 0f)
            else -> ColorMatrix()
        }
    }

    /** Maps each pixel's brightness onto the ramp (optionally shrunk first for chunky pixels). */
    private fun applyRamp(raw: Bitmap, stops: IntArray): Bitmap {
        val src = if (pixel > 1) Bitmap.createScaledBitmap(raw, raw.width / pixel, raw.height / pixel, true)
        else raw.copy(Bitmap.Config.ARGB_8888, true)
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        // Precompute a 256-entry lookup table.
        val lut = IntArray(256)
        val n = stops.size / 2
        for (l in 0..255) {
            var i = 0
            while (i < n - 1 && l >= stops[(i + 1) * 2]) i++
            lut[l] = if (i >= n - 1) stops[(n - 1) * 2 + 1]
            else if (quantize) stops[i * 2 + 1]
            else {
                val a = stops[i * 2]; val b = stops[(i + 1) * 2]
                blend(stops[i * 2 + 1], stops[(i + 1) * 2 + 1], (l - a).toFloat() / (b - a))
            }
        }
        for (i in px.indices) {
            val c = px[i]
            val l = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
            px[i] = lut[l.coerceIn(0, 255)]
        }
        val out = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}
