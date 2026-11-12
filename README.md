# GameMap

Turn-by-turn navigation for Android and **Android Auto** that looks like a video game minimap.

**Themes** (🎮 button, on the phone or the car screen):
- **Open World**: round radar minimap, purple GPS line, north marker on the rim
- **Neon Racer**: neon roads, grid and horizon glow
- **8-Bit**: chunky pixel map in Game Boy greens
- **Night Vision**: green scanlines

Search a destination, get a route, follow the turn arrows. Arriving says **MISSION COMPLETE**.
Map data © OpenStreetMap contributors, tiles © CARTO, routing by OSRM, search by Nominatim
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
