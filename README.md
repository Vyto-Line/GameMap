<div align="center">

# 🎮 GameMap

**Turn-by-turn navigation for Android and Android Auto that looks like a video-game minimap.**

Live traffic · lane guidance · spoken directions · speed cameras · collectible landmarks · 8 game-inspired map styles

[![Latest release](https://img.shields.io/github/v/release/Vyto-Line/GameMap?label=download&style=for-the-badge&color=7c4dff)](../../releases/latest)
![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=for-the-badge&logo=android&logoColor=white)
![Android Auto](https://img.shields.io/badge/Android%20Auto-supported-4285f4?style=for-the-badge&logo=androidauto&logoColor=white)

<img src="docs/screenshots/hero.jpg" alt="GameMap screens: driving, route choice, Trailblazer landmarks, arrival card, home screen" width="100%">

</div>

---

## ✨ What it does

<table>
<tr>
<td width="33%" valign="top"><img src="docs/screenshots/driving.jpg" alt="Driving screen"><br>
<b>🧭 Drive</b><br>Big turn card with the next turn, lane arrows at junctions, your speed next to the speed limit, jam warnings and an arrival time that counts traffic.</td>
<td width="33%" valign="top"><img src="docs/screenshots/route-preview.jpg" alt="Route preview"><br>
<b>🛣️ Pick a route</b><br>Up to three routes, each timed with live traffic and a bar showing where the jams are. "Leave later" shows the time for another departure.</td>
<td width="33%" valign="top"><img src="docs/screenshots/faster-route.jpg" alt="Faster route found"><br>
<b>⚡ Faster routes</b><br>Traffic is re-checked every 90 seconds. When another way saves 3+ minutes you get Switch / Keep, or it switches by itself.</td>
</tr>
<tr>
<td valign="top"><img src="docs/screenshots/home.jpg" alt="Home screen"><br>
<b>🏠 Home screen</b><br>Search as you type (Greek or English, even with small spelling slips), Home and Work, Fuel / Food / Parking nearby and recent places with live ETAs.</td>
<td valign="top"><img src="docs/screenshots/alerts.jpg" alt="Speed camera ahead"><br>
<b>📷 Alerts</b><br>Speed cameras from OpenStreetMap, accidents and roadworks from TomTom and reports from other drivers, with a warning before you reach them.</td>
<td valign="top"><img src="docs/screenshots/arrival.jpg" alt="Quest complete card"><br>
<b>🏆 Arrive in style</b><br>Every trip ends with a themed card: journey time, distance, time saved, XP and your explorer level.</td>
</tr>
<tr>
<td valign="top"><img src="docs/screenshots/landmarks.jpg" alt="Trailblazer landmarks"><br>
<b>⭐ Collect landmarks</b><br>In the Trailblazer style, museums, monuments and viewpoints show as blue <b>?</b> markers. Pass by one to discover it (+25 XP).</td>
<td valign="top"><img src="docs/screenshots/style-picker.jpg" alt="Map style picker"><br>
<b>🎨 Your look</b><br>Eight styles, each with light, dark or automatic sunset switching. Panels and buttons take the shape of each style.</td>
<td valign="top"><img src="docs/screenshots/settings.jpg" alt="Settings"><br>
<b>⚙️ Settings</b><br>Voice in English or Ελληνικά, avoid tolls or motorways, traffic on/off, kilometres or miles, and a separate style for the car.</td>
</tr>
</table>

## 🚗 On the car screen

<img src="docs/screenshots/android-auto.jpg" alt="GameMap on Android Auto" width="100%">

The same map on Android Auto, with lane guidance and "Then…" on the turn card, a traffic-coloured arrival time, alerts for faster routes, a route picker and a report button.

## 🗺️ Map styles

Original designs in the spirit of different game genres. Each comes in a light and a dark version.

<img src="docs/screenshots/themes-light.jpg" alt="The eight map styles, light" width="100%">

| Style | Feel |
|---|---|
| **Open World** | Asphalt-grey streets, slate water, purple GPS line |
| **Neon Noir** | Teal megacity grid, yellow route, cut-corner panels, scanlines |
| **Neon Racer** | Neon roads, grid and horizon glow |
| **Blocky** | A block world built from real buildings: planks, bricks, stone and terracotta roofs |
| **Parchment Quest** | Old paper map, ink roads, red dashed route, serif lettering |
| **Lookout** | Dusk palette from purple to sunset orange |
| **8-Bit** | Handheld-console greens and pixel panels |
| **Trailblazer** | Bright outdoor-adventure look, with landmarks to collect |

<details>
<summary>Dark versions</summary>

<img src="docs/screenshots/themes-dark.jpg" alt="The eight map styles, dark" width="100%">
</details>

## 📲 Install

Android Auto only shows apps it believes came from Google Play, so GameMap is installed with **UnknownAuto**:

1. Android Auto settings ▸ tap **Version** 10× ▸ ⋮ ▸ Developer settings ▸ **Unknown sources**: ON.
2. Install **Shizuku** (Play Store) and **UnknownAuto** (github.com/GitMag/UnknownAuto).
3. Start Shizuku (Wireless debugging, or the `adb shell …libshizuku.so` command from a computer).
4. Download `GameMap.apk` from the [latest release](../../releases/latest).
5. UnknownAuto ▸ Load `GameMap.apk` ▸ **Install** ▸ allow Shizuku ▸ confirm. It should show ✔.
6. Open GameMap on the phone once and allow location.
7. Restart the phone, connect to the car and open **GameMap** from the app list.

Every build is signed with the same key, so new versions install over old ones.

## 🔄 Updates

GameMap checks for a new release every few hours, or when you tap **Settings ▸ Check for updates**.
It downloads the update and opens it in UnknownAuto: have Shizuku running and tap Install, so GameMap stays visible in Android Auto.
The car screen shows a notice when an update is ready.

## 🛠️ Build

Actions ▸ **Build APK** ▸ Run workflow, or push a tag like `v1.1`. Each build becomes a release with `GameMap.apk`.
Kotlin, Android Car App Library 1.4, no Google Play services. Map drawing, routing and search are all done in the app.

## 🙏 Credits

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors · vector tiles by [OpenFreeMap](https://openfreemap.org) ·
live traffic, incidents and speed limits by [TomTom](https://developer.tomtom.com) · routing by [OSRM](https://project-osrm.org) ·
search by TomTom, Photon and Nominatim. All free services: please keep use personal and moderate.

> ⚠️ Keep your eyes on the road. Set your destination before you drive, and follow local laws about speed camera alerts.
