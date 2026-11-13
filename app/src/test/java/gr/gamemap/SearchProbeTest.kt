package gr.gamemap

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.net.URLEncoder

/** What each search service finds for real queries (raw), to see where places go missing. */
@RunWith(RobolectricTestRunner::class)
class SearchProbeTest {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    @Test
    fun probe() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        Traffic.init(RuntimeEnvironment.getApplication()); Places.init(RuntimeEnvironment.getApplication())
        val key = Traffic.key
        val log = StringBuilder()
        val glyfada = LatLng(37.8650, 23.7530)
        val syntagma = LatLng(37.9755, 23.7348)
        fun d(a: LatLng, b: LatLng) = "%.1f km".format(Geo.distance(a, b) / 1000)
        fun get(url: String) = try { Http.get(url, timeout = 10000) } catch (e: Exception) { "ERR $e" }
        val queries = listOf("crepa land", "Crepa Land Glyfada", "crepaland", "κρεπα λαντ", "Ζάχαρη και Αλάτι", "zaxari kai alati",
            "lidl glyfada", "everest", "masoutis", "αιγυπτου 25 γλυφαδα", "mikel")
        for (near in listOf(glyfada, syntagma)) for (q in queries) {
            log.append("\n=== \"$q\" near ${if (near == glyfada) "Glyfada" else "Syntagma"}\n")
            // TomTom one-box
            run {
                val j = get("https://api.tomtom.com/search/2/search/${enc(q)}.json?key=$key&limit=8&language=el-GR&typeahead=false&idxSet=POI,PAD,Addr,Str,Geo&lat=${near.lat}&lon=${near.lon}")
                if (j.startsWith("ERR")) log.append(" tomtom: $j\n") else {
                    val a = JSONObject(j).optJSONArray("results") ?: JSONArray()
                    for (i in 0 until a.length()) {
                        val r = a.getJSONObject(i); val p = r.getJSONObject("position"); val ll = LatLng(p.getDouble("lat"), p.getDouble("lon"))
                        log.append(" tomtom ${r.optString("type")} score=${"%.2f".format(r.optDouble("score"))} '${r.optJSONObject("poi")?.optString("name") ?: ""}' ${r.optJSONObject("address")?.optString("freeformAddress")} ${d(near, ll)}\n")
                    }
                }
            }
            // TomTom POI search, near
            run {
                val j = get("https://api.tomtom.com/search/2/poiSearch/${enc(q)}.json?key=$key&limit=5&language=el-GR&lat=${near.lat}&lon=${near.lon}&radius=60000")
                if (j.startsWith("ERR")) log.append(" ttpoi: $j\n") else {
                    val a = JSONObject(j).optJSONArray("results") ?: JSONArray()
                    for (i in 0 until a.length()) {
                        val r = a.getJSONObject(i); val p = r.getJSONObject("position"); val ll = LatLng(p.getDouble("lat"), p.getDouble("lon"))
                        log.append(" ttpoi '${r.optJSONObject("poi")?.optString("name")}' ${r.optJSONObject("address")?.optString("freeformAddress")} ${d(near, ll)} ${ll.lat},${ll.lon}\n")
                    }
                }
            }
            // Photon
            run {
                val j = get("https://photon.komoot.io/api/?q=${enc(q)}&limit=6&lat=${near.lat}&lon=${near.lon}&zoom=12&location_bias_scale=0.3")
                if (j.startsWith("ERR")) log.append(" photon: $j\n") else {
                    val a = JSONObject(j).getJSONArray("features")
                    for (i in 0 until a.length()) {
                        val f = a.getJSONObject(i); val c = f.getJSONObject("geometry").getJSONArray("coordinates"); val pr = f.getJSONObject("properties")
                        val ll = LatLng(c.getDouble(1), c.getDouble(0))
                        log.append(" photon '${pr.optString("name")}' ${pr.optString("osm_key")}=${pr.optString("osm_value")} ${pr.optString("street")} ${pr.optString("housenumber")} ${pr.optString("city")} ${d(near, ll)}\n")
                    }
                }
            }
            // Nominatim
            run {
                val dd = 0.25
                val j = get("https://nominatim.openstreetmap.org/search?q=${enc(q)}&format=jsonv2&limit=5&viewbox=${near.lon - dd},${near.lat + dd},${near.lon + dd},${near.lat - dd}")
                if (j.startsWith("ERR")) log.append(" nominatim: $j\n") else {
                    val a = JSONArray(j)
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i); val ll = LatLng(o.getString("lat").toDouble(), o.getString("lon").toDouble())
                        log.append(" nominatim '${o.optString("name")}' ${o.optString("category")}=${o.optString("type")} ${o.optString("display_name").take(70)} ${d(near, ll)}\n")
                    }
                }
                Thread.sleep(1100)
            }
        }
        // OpenStreetMap itself: anything called crep* around Glyfada
        log.append("\n=== Overpass name~crep around Glyfada\n")
        val ov = get("https://overpass-api.de/api/interpreter?data=" + enc("[out:json][timeout:25];nwr(around:6000,37.8650,23.7530)[name~\"crep|κρεπ|κρέπ\",i];out center tags;"))
        log.append(ov.take(4000)).append("\n")
        // how current is each source: cafés/restaurants around one Glyfada block, OSM vs TomTom
        log.append("\n=== food & cafés within 300 m of Αιγύπτου/Γούναρη (37.8735,23.7590): OSM vs TomTom\n")
        val spot = LatLng(37.8735, 23.7590)
        val osm = get("https://overpass-api.de/api/interpreter?data=" + enc("[out:json][timeout:25];nwr(around:300,${spot.lat},${spot.lon})[amenity~\"cafe|restaurant|fast_food|ice_cream|bar\"];out center tags;"))
        try {
            val el = JSONObject(osm).getJSONArray("elements")
            for (i in 0 until el.length()) {
                val e = el.getJSONObject(i); val t = e.optJSONObject("tags") ?: continue
                val lat = e.optDouble("lat", e.optJSONObject("center")?.optDouble("lat") ?: 0.0); val lon = e.optDouble("lon", e.optJSONObject("center")?.optDouble("lon") ?: 0.0)
                log.append(" osm ${e.optString("type")} '${t.optString("name")}' ${t.optString("amenity")} ${t.optString("addr:street")} ${t.optString("addr:housenumber")} $lat,$lon check_date=${t.optString("check_date")}\n")
            }
        } catch (e: Exception) { log.append(" osm: ${osm.take(300)}\n") }
        val tt = get("https://api.tomtom.com/search/2/nearbySearch/.json?key=$key&lat=${spot.lat}&lon=${spot.lon}&radius=300&limit=60&categorySet=7315,9376,7318&language=el-GR")
        try {
            val a = JSONObject(tt).getJSONArray("results")
            for (i in 0 until a.length()) {
                val r = a.getJSONObject(i); val p = r.getJSONObject("position")
                log.append(" tomtom '${r.optJSONObject("poi")?.optString("name")}' ${r.optJSONObject("poi")?.optJSONArray("classifications")?.optJSONObject(0)?.optString("code")} ${r.optJSONObject("address")?.optString("freeformAddress")} ${p.getDouble("lat")},${p.getDouble("lon")}\n")
            }
        } catch (e: Exception) { log.append(" tomtom: ${tt.take(300)}\n") }
        // what the app does today
        log.append("\n=== app search today (Glyfada)\n")
        for (q in listOf("crepa land", "Crepa Land Glyfada", "κρεπα λαντ")) {
            val r = try { Places.search(q, glyfada) } catch (e: Exception) { emptyList() }
            log.append(" \"$q\": " + r.joinToString(" | ") { "${it.icon} ${it.name} (${it.address.take(30)}) ${d(glyfada, it.pos)}" } + "\n")
        }
        File(out, "searchprobe.txt").writeText(log.toString())
    }
}
