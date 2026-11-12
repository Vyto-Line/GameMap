package gr.gamemap

import android.graphics.Color
import android.graphics.Typeface

/** How the HUD panels are drawn. */
enum class HudShape { ROUND, CHAMFER, PIXEL }

/** Colours for drawing the map itself (from OpenStreetMap vector data). */
class MapStyle(
    val land: Int,
    val water: Int,
    val park: Int,
    val wood: Int,
    val sand: Int,
    val farm: Int,
    val urban: Int,
    val pitch: Int,
    val building: Int,
    /** Building outline, or null for none. */
    val buildingEdge: Int?,
    /** Shoreline line, or null for none. */
    val waterEdge: Int?,
    val motorway: Int,
    val major: Int,
    val minor: Int,
    val path: Int,
    val rail: Int,
    /** Darker outline around roads (old-map double lines), or null. */
    val casing: Int? = null,
    /** Neon-style glow behind roads. */
    val glow: Boolean = false,
    val label: Int,
    val labelHalo: Int,
    val labels: Boolean = true,
    /** Traffic colours: moving slowly / jammed. */
    val trafficSlow: Int = Color.rgb(255, 170, 0),
    val trafficJam: Int = Color.rgb(230, 30, 30),
)

/**
 * Game-style looks: original designs evoking different game genres. Each one styles the
 * real map data (roads, buildings, water, parks, names) and adds its own HUD and overlays.
 */
enum class Theme(
    val title: String,
    val route: Int,
    val routeGlow: Int,
    val player: Int,
    val playerOutline: Int,
    val hudText: Int,
    val hudPanel: Int,
    val style: MapStyle,
    /** Draw the map inside a round radar instead of full screen. */
    val radar: Boolean = false,
    /** Chunky pixels: the map is drawn this many times smaller and scaled up. */
    val pixel: Int = 1,
    val scanlines: Boolean = false,
    val grid: Boolean = false,
    val hud: HudShape = HudShape.ROUND,
    val serif: Boolean = false,
    val dashedRoute: Boolean = false,
    val paper: Boolean = false,
    val sunGlow: Boolean = false,
) {
    OPEN_WORLD(
        "Open World",
        route = Color.rgb(177, 77, 255), routeGlow = Color.argb(110, 177, 77, 255),
        player = Color.WHITE, playerOutline = Color.BLACK,
        hudText = Color.WHITE, hudPanel = Color.argb(170, 0, 0, 0),
        style = MapStyle(
            land = Color.rgb(36, 41, 46), water = Color.rgb(52, 76, 98), park = Color.rgb(40, 66, 46),
            wood = Color.rgb(34, 58, 40), sand = Color.rgb(108, 100, 76), farm = Color.rgb(48, 56, 44),
            urban = Color.rgb(42, 46, 52), pitch = Color.rgb(46, 74, 50),
            building = Color.rgb(56, 62, 69), buildingEdge = Color.rgb(68, 75, 83), waterEdge = null,
            motorway = Color.rgb(205, 205, 205), major = Color.rgb(160, 165, 170), minor = Color.rgb(98, 104, 110),
            path = Color.rgb(74, 80, 86), rail = Color.rgb(84, 84, 90),
            label = Color.rgb(225, 228, 232), labelHalo = Color.rgb(20, 22, 26),
        ),
    ),
    NEON_NOIR(
        "Neon Noir",
        route = Color.rgb(252, 238, 10), routeGlow = Color.argb(120, 252, 238, 10),
        player = Color.rgb(255, 51, 85), playerOutline = Color.rgb(252, 238, 10),
        hudText = Color.rgb(252, 238, 10), hudPanel = Color.argb(200, 6, 20, 24),
        scanlines = true, hud = HudShape.CHAMFER,
        style = MapStyle(
            land = Color.rgb(6, 18, 22), water = Color.rgb(2, 8, 13), park = Color.rgb(8, 32, 32),
            wood = Color.rgb(6, 28, 28), sand = Color.rgb(20, 34, 34), farm = Color.rgb(8, 24, 26),
            urban = Color.rgb(10, 24, 30), pitch = Color.rgb(10, 40, 40),
            building = Color.rgb(11, 34, 41), buildingEdge = Color.rgb(28, 112, 130), waterEdge = Color.rgb(32, 196, 214),
            motorway = Color.rgb(0, 255, 240), major = Color.rgb(32, 196, 214), minor = Color.rgb(16, 96, 112),
            path = Color.rgb(14, 70, 82), rail = Color.rgb(255, 51, 120), glow = true,
            label = Color.rgb(190, 250, 255), labelHalo = Color.rgb(0, 6, 8),
            trafficSlow = Color.rgb(255, 160, 0), trafficJam = Color.rgb(255, 30, 90),
        ),
    ),
    NEON_RACER(
        "Neon Racer",
        route = Color.rgb(0, 240, 255), routeGlow = Color.argb(120, 0, 240, 255),
        player = Color.rgb(255, 43, 214), playerOutline = Color.WHITE,
        hudText = Color.rgb(0, 240, 255), hudPanel = Color.argb(170, 20, 0, 40),
        grid = true,
        style = MapStyle(
            land = Color.rgb(16, 4, 30), water = Color.rgb(5, 1, 18), park = Color.rgb(24, 6, 42),
            wood = Color.rgb(22, 4, 38), sand = Color.rgb(34, 12, 48), farm = Color.rgb(20, 6, 36),
            urban = Color.rgb(22, 8, 40), pitch = Color.rgb(28, 8, 50),
            building = Color.rgb(32, 10, 54), buildingEdge = Color.rgb(120, 30, 150), waterEdge = Color.rgb(0, 160, 200),
            motorway = Color.rgb(255, 150, 240), major = Color.rgb(255, 43, 214), minor = Color.rgb(140, 30, 160),
            path = Color.rgb(90, 20, 110), rail = Color.rgb(0, 200, 230), glow = true,
            label = Color.rgb(170, 245, 255), labelHalo = Color.rgb(10, 0, 22),
            trafficSlow = Color.rgb(255, 200, 0), trafficJam = Color.rgb(255, 40, 40),
        ),
    ),
    BLOCKY(
        "Blocky",
        route = Color.rgb(255, 40, 24), routeGlow = Color.argb(130, 120, 0, 0),
        player = Color.WHITE, playerOutline = Color.rgb(30, 30, 30),
        hudText = Color.WHITE, hudPanel = Color.argb(200, 40, 40, 40),
        hud = HudShape.PIXEL,
        style = MapStyle( // only background + traffic colours are used; Blocky draws its own world
            land = Color.rgb(94, 158, 58), water = Color.rgb(63, 118, 228), park = 0, wood = 0, sand = 0, farm = 0,
            urban = 0, pitch = 0, building = 0, buildingEdge = null, waterEdge = null,
            motorway = 0, major = 0, minor = 0, path = 0, rail = 0, label = 0, labelHalo = 0, labels = false,
            trafficSlow = Color.rgb(255, 200, 40), trafficJam = Color.rgb(200, 20, 10),
        ),
    ),
    PARCHMENT_QUEST(
        "Parchment Quest",
        route = Color.rgb(179, 38, 30), routeGlow = Color.argb(70, 179, 38, 30),
        player = Color.rgb(46, 110, 82), playerOutline = Color.rgb(250, 240, 210),
        hudText = Color.rgb(74, 52, 32), hudPanel = Color.argb(225, 240, 226, 186),
        serif = true, dashedRoute = true, paper = true,
        style = MapStyle(
            land = Color.rgb(234, 217, 174), water = Color.rgb(160, 190, 180), park = Color.rgb(212, 210, 158),
            wood = Color.rgb(184, 190, 132), sand = Color.rgb(238, 222, 170), farm = Color.rgb(224, 210, 160),
            urban = Color.rgb(226, 206, 164), pitch = Color.rgb(206, 208, 150),
            building = Color.rgb(214, 188, 140), buildingEdge = Color.rgb(120, 88, 54), waterEdge = Color.rgb(84, 108, 100),
            motorway = Color.rgb(222, 150, 110), major = Color.rgb(246, 234, 200), minor = Color.rgb(246, 236, 206),
            path = Color.rgb(120, 90, 55), rail = Color.rgb(90, 66, 40), casing = Color.rgb(110, 80, 48),
            label = Color.rgb(74, 52, 32), labelHalo = Color.rgb(240, 226, 186),
            trafficSlow = Color.rgb(214, 140, 30), trafficJam = Color.rgb(160, 24, 20),
        ),
    ),
    LOOKOUT(
        "Lookout",
        route = Color.rgb(242, 242, 232), routeGlow = Color.argb(110, 63, 184, 175),
        player = Color.rgb(63, 184, 175), playerOutline = Color.rgb(242, 242, 232),
        hudText = Color.rgb(247, 213, 155), hudPanel = Color.argb(190, 43, 27, 61),
        sunGlow = true,
        style = MapStyle(
            land = Color.rgb(52, 32, 68), water = Color.rgb(30, 42, 78), park = Color.rgb(44, 28, 60),
            wood = Color.rgb(36, 22, 52), sand = Color.rgb(110, 62, 70), farm = Color.rgb(60, 36, 70),
            urban = Color.rgb(60, 36, 72), pitch = Color.rgb(56, 34, 70),
            building = Color.rgb(84, 46, 88), buildingEdge = Color.rgb(120, 64, 106), waterEdge = Color.rgb(247, 213, 155),
            motorway = Color.rgb(247, 213, 155), major = Color.rgb(232, 131, 58), minor = Color.rgb(168, 78, 74),
            path = Color.rgb(120, 60, 80), rail = Color.rgb(140, 90, 110),
            label = Color.rgb(247, 213, 155), labelHalo = Color.rgb(43, 27, 61),
            trafficSlow = Color.rgb(255, 196, 80), trafficJam = Color.rgb(255, 70, 60),
        ),
    ),
    RETRO(
        "8-Bit",
        route = Color.rgb(15, 56, 15), routeGlow = Color.argb(90, 48, 98, 48),
        player = Color.rgb(15, 56, 15), playerOutline = Color.rgb(155, 188, 15),
        hudText = Color.rgb(15, 56, 15), hudPanel = Color.argb(210, 139, 172, 15),
        pixel = 4, hud = HudShape.PIXEL,
        style = MapStyle(
            land = Color.rgb(155, 188, 15), water = Color.rgb(48, 98, 48), park = Color.rgb(139, 172, 15),
            wood = Color.rgb(139, 172, 15), sand = Color.rgb(155, 188, 15), farm = Color.rgb(155, 188, 15),
            urban = Color.rgb(139, 172, 15), pitch = Color.rgb(139, 172, 15),
            building = Color.rgb(139, 172, 15), buildingEdge = Color.rgb(48, 98, 48), waterEdge = null,
            motorway = Color.rgb(15, 56, 15), major = Color.rgb(15, 56, 15), minor = Color.rgb(48, 98, 48),
            path = Color.rgb(48, 98, 48), rail = Color.rgb(48, 98, 48),
            label = 0, labelHalo = 0, labels = false,
            trafficSlow = Color.rgb(48, 98, 48), trafficJam = Color.rgb(15, 56, 15),
        ),
    ),
    NIGHT_VISION(
        "Night Vision",
        route = Color.rgb(182, 255, 110), routeGlow = Color.argb(110, 120, 255, 60),
        player = Color.rgb(232, 255, 224), playerOutline = Color.rgb(0, 40, 0),
        hudText = Color.rgb(150, 255, 120), hudPanel = Color.argb(170, 0, 20, 0),
        scanlines = true,
        style = MapStyle(
            land = Color.rgb(0, 16, 2), water = Color.rgb(0, 5, 0), park = Color.rgb(0, 26, 4),
            wood = Color.rgb(0, 24, 3), sand = Color.rgb(10, 32, 8), farm = Color.rgb(0, 20, 3),
            urban = Color.rgb(4, 22, 6), pitch = Color.rgb(0, 30, 6),
            building = Color.rgb(6, 40, 8), buildingEdge = Color.rgb(36, 110, 36), waterEdge = Color.rgb(40, 140, 40),
            motorway = Color.rgb(180, 255, 150), major = Color.rgb(120, 230, 90), minor = Color.rgb(40, 130, 40),
            path = Color.rgb(28, 90, 28), rail = Color.rgb(60, 160, 60),
            label = Color.rgb(160, 255, 130), labelHalo = Color.rgb(0, 10, 0),
            trafficSlow = Color.rgb(230, 255, 60), trafficJam = Color.rgb(255, 255, 255),
        ),
    ),
    TRAILBLAZER(
        "Trailblazer",
        route = Color.rgb(84, 104, 255), routeGlow = Color.argb(110, 84, 104, 255),
        player = Color.rgb(255, 255, 255), playerOutline = Color.rgb(30, 120, 200),
        hudText = Color.rgb(24, 70, 64), hudPanel = Color.argb(235, 250, 255, 250),
        style = MapStyle(
            land = Color.rgb(150, 214, 140), water = Color.rgb(84, 176, 240), park = Color.rgb(112, 196, 112),
            wood = Color.rgb(92, 178, 98), sand = Color.rgb(236, 222, 160), farm = Color.rgb(170, 220, 140),
            urban = Color.rgb(160, 206, 150), pitch = Color.rgb(124, 206, 120),
            building = Color.rgb(176, 226, 168), buildingEdge = Color.rgb(132, 196, 128), waterEdge = Color.rgb(200, 236, 255),
            motorway = Color.rgb(255, 236, 150), major = Color.rgb(206, 246, 236), minor = Color.rgb(198, 240, 228),
            path = Color.rgb(226, 246, 214), rail = Color.rgb(130, 170, 130), casing = Color.rgb(255, 255, 255),
            label = Color.rgb(28, 84, 64), labelHalo = Color.rgb(236, 252, 236),
            trafficSlow = Color.rgb(255, 160, 40), trafficJam = Color.rgb(240, 60, 60),
        ),
    );

    /** Colour behind the map while tiles load. */
    val background: Int get() = style.land

    val typeface: Typeface
        get() = if (serif) Typeface.create(Typeface.SERIF, Typeface.BOLD)
        else Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}
