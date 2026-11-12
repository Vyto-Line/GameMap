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
- **Trailblazer**: bright outdoor-adventure look: lush green land, pale aqua roads with white edges, blue water

Pinch to zoom, twist with two fingers to rotate (tap the compass to reset), drag to look around.
Search a destination, get a route, follow the turn arrows. Arriving says **MISSION COMPLETE**.
Map data © OpenStreetMap contributors via OpenFreeMap vector tiles, live traffic by TomTom (optional free key), routing by OSRM, search by Photon and Nominatim
(all free services; please keep use personal and moderate).

## Features

- **Route preview:** up to 3 routes with traffic-aware times, a jam bar for each, "Leave later".
- **Driving:** big turn card with lanes and the next turn, speed and speed limit, jam warnings,
  arrival time with traffic, Share ETA, spoken directions in English or Greek (phone's own voice).
- **Faster routes:** checked every 3 minutes; switch automatically or ask (Settings).
- **Home screen:** Home / Work, Fuel / Food / Parking nearby, recent places with live ETAs.
- **Look:** 8 game-style themes, light / dark / auto, panels shaped like each theme, a separate
  style for the car.
- **Game side:** arrival card with trip stats, XP and levels; Trailblazer landmarks to discover.
- **Alerts:** speed cameras (OpenStreetMap), accidents and roadworks (TomTom), driver reports.

## Driver reports (optional)

Reports (police, accidents, hazards…) are shared through a free Firebase Realtime Database:

1. console.firebase.google.com › Add project › "gamemap" (Analytics not needed).
2. Build › Realtime Database › Create database › any location › **Start in locked mode**.
3. Rules tab, paste and Publish:

```json
{
  "rules": {
    "reports": {
      ".read": true,
      ".indexOn": ["t"],
      "$id": {
        ".write": "!data.exists() && newData.hasChildren(['type','lat','lon','t'])",
        "type": { ".validate": "newData.isString() && newData.val().matches(/^(police|accident|hazard|jam|closed|camera)$/)" },
        "lat": { ".validate": "newData.isNumber()" },
        "lon": { ".validate": "newData.isNumber()" },
        "h": { ".validate": "newData.isNumber()" },
        "t": { ".validate": "newData.val() == now" },
        "yes": { "$v": { ".write": true, ".validate": "newData.val() == now" } },
        "no": { "$v": { ".write": true, ".validate": "newData.val() == now" } },
        "$other": { ".validate": false }
      }
    }
  }
}
```

4. Copy the database address (…firebasedatabase.app) and either paste it in the app
   (Settings › Driver reports) or add it as the `REPORTS_URL` repository secret so every build has it.

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
