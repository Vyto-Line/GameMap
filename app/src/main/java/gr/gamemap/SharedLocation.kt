package gr.gamemap

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Locations other apps hand to GameRoute: geo: links, Google Maps links (long and the
 * maps.app.goo.gl short ones), Waze, Apple Maps and OpenStreetMap links, a WhatsApp/Viber
 * shared location (a Maps link inside a message), or plain "37.98, 23.72" text.
 */
object SharedLocation {
    /** Where it points: coordinates (with a name if the link has one), or an address to search. */
    class Found(val pos: LatLng?, val label: String?, val query: String?)

    private val num = "(-?\\d{1,3}\\.\\d+)"
    private val pair = Regex("$num\\s*,\\s*$num")

    /** Finds a location in [text]; follows short links (network: call off the main thread). */
    fun find(text: String): Found? {
        val link = Regex("(geo:[^\\s]+|https?://[^\\s]+)").find(text)?.value
        if (link != null) {
            parse(link)?.let { return it }
            // short links (maps.app.goo.gl, goo.gl/maps, waze.link…) only say where after a redirect
            resolve(link)?.let { full -> parse(full)?.let { return it } }
        }
        // plain coordinates in a message
        pair.find(text)?.let { m -> ok(m.groupValues[1], m.groupValues[2])?.let { return Found(it, null, null) } }
        return null
    }

    fun parse(raw: String): Found? {
        val s = try { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { raw }
        if (s.startsWith("geo:")) {
            // geo:lat,lon  ·  geo:0,0?q=lat,lon(Label)  ·  geo:0,0?q=some address
            val q = s.substringAfter("q=", "").substringBefore("&")
            val label = Regex("\\(([^)]*)\\)").find(q)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            pair.find(q)?.let { m -> ok(m.groupValues[1], m.groupValues[2])?.let { return Found(it, label, null) } }
            pair.find(s.removePrefix("geo:").substringBefore("?"))?.let { m ->
                ok(m.groupValues[1], m.groupValues[2])?.takeIf { it.lat != 0.0 || it.lon != 0.0 }?.let { return Found(it, label, null) }
            }
            return q.substringBefore("(").trim().takeIf { it.isNotBlank() }?.let { Found(null, null, it) }
        }
        val name = Regex("/place/([^/@?]+)").find(s)?.groupValues?.get(1)?.replace('+', ' ')?.trim()
        // Google's exact pin (!3d lat !4d lon) beats the map view (@lat,lon)
        Regex("!3d$num!4d$num").find(s)?.let { m -> ok(m.groupValues[1], m.groupValues[2])?.let { return Found(it, name, null) } }
        for (key in listOf("q", "query", "destination", "daddr", "ll", "sll")) {
            Regex("[?&]$key=([^&#]+)").find(s)?.groupValues?.get(1)?.let { v ->
                pair.matchEntire(v.trim())?.let { m -> ok(m.groupValues[1], m.groupValues[2])?.let { return Found(it, name, null) } }
                if (key in listOf("q", "query", "destination", "daddr") && v.isNotBlank() && !v.startsWith("http")) return Found(null, null, v.trim().replace('+', ' '))
            }
        }
        // OpenStreetMap: mlat/mlon or #map=zoom/lat/lon
        val mlat = Regex("mlat=$num").find(s)?.groupValues?.get(1); val mlon = Regex("mlon=$num").find(s)?.groupValues?.get(1)
        if (mlat != null && mlon != null) ok(mlat, mlon)?.let { return Found(it, null, null) }
        Regex("#map=\\d+/$num/$num").find(s)?.let { m -> ok(m.groupValues[1], m.groupValues[2])?.let { return Found(it, null, null) } }
        Regex("@$num,$num").find(s)?.let { m -> ok(m.groupValues[1], m.groupValues[2])?.let { return Found(it, name, null) } }
        if (name != null) return Found(null, null, name)
        return null
    }

    private fun ok(a: String, b: String): LatLng? {
        val lat = a.toDoubleOrNull() ?: return null; val lon = b.toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLng(lat, lon) else null
    }

    /** Follows redirects by hand (up to 6) and returns the last address. */
    private fun resolve(link: String): String? {
        var u = link
        repeat(6) {
            val c = try { URL(u).openConnection() as HttpURLConnection } catch (e: Exception) { return null }
            c.instanceFollowRedirects = false
            c.connectTimeout = 6000; c.readTimeout = 6000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) GameRoute")
            try {
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                if (code in 300..399 && loc != null) { u = URL(URL(u), loc).toString(); return@repeat }
                // some short links answer with a page that carries the full address inside it
                if (code == 200 && parse(u) == null) {
                    val body = c.inputStream.bufferedReader().use { r -> r.readText().take(200_000) }
                    Regex("https://www\\.google\\.[a-z.]+/maps/[^\"'\\s<>]+").find(body)?.value?.let { return it.replace("\\u0026", "&") }
                }
                return u
            } catch (e: Exception) { return null } finally { c.disconnect() }
        }
        return u
    }
}
