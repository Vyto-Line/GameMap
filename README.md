# GameMap

Turn-by-turn navigation for Android and **Android Auto** that looks like a video game minimap.

**Themes** (🎮 button; a list on the phone, cycles on the car screen). Original designs in the spirit of different game genres:
- **Open World**: full-screen asphalt-grey streets, slate-blue water, purple GPS line
- **Neon Noir**: teal megacity grid, yellow route, cut-corner HUD, scanlines
- **Neon Racer**: neon roads, grid and horizon glow
- **Blocky**: a block world built from real OpenStreetMap data (OpenFreeMap vector tiles, no key needed): every building as a plank, brick, stone, terracotta or concrete roof with raised-block shading; stone, gravel and dirt roads; grass, leaves, farmland, sand and water
- **Parchment Quest**: old paper map, ink roads, red dashed route, serif lettering
- **Lookout**: dusk palette from purple to sunset orange, warm light from above
- **8-Bit**: handheld-console greens
- **Night Vision**: green scanlines

Pinch to zoom, twist with two fingers to rotate (tap the compass to reset), drag to look around.
Search a destination, get a route, follow the turn arrows. Arriving says **MISSION COMPLETE**.
Map data © OpenStreetMap contributors, tiles © CARTO, routing by OSRM, search by Photon and Nominatim, Blocky data from OpenFreeMap
(all free services; please keep use personal and moderate).

## Install (Android Auto needs this exact way)
Android Auto only shows apps it thinks came from Google Play, so install with **UnknownAuto**:
1. Android Auto settings ▸ tap **Version** 10× ▸ ⋮ ▸ Developer settings ▸ **Unknown sources**: ON.
2. Install **Shizuku** (Play Store) and **UnknownAuto** (github.com/GitMag/UnknownAuto).
3. Start Shizuku (Wireless debugging, or the `adb shell …libshizuku.so` command from a computer).
4. UnknownAuto ▸ Load `GameMap.apk` ▸ **Install** ▸ allow Shizuku ▸ confirm. It should show ✔.
5. Open GameMap on the phone once and allow location.
6. Restart the phone, connect to the car, open **GameMap** from the app list.

The APK is signed with a fixed key (`app/debug.keystore`), so newer builds install over older ones.

## Updates
GameMap checks for a new release when it starts (every few hours), or when you tap **⬆**.
It downloads the update and opens it in **UnknownAuto**: have Shizuku running and tap Install,
so the update keeps GameMap visible in Android Auto. The car screen shows a notice when an update is ready.

## Build
Actions ▸ Build APK ▸ **Run workflow**, or push a tag like `v1.1`. Each build becomes a release with `GameMap.apk`.
