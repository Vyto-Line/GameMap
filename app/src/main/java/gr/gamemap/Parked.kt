package gr.gamemap

import android.content.Context

/**
 * Where the car is parked: saved when Android Auto disconnects (the phone is in the car at that
 * moment), or by hand from ☰. Cleared when Android Auto connects again (you're driving off).
 */
object Parked {
    class Spot(val pos: LatLng, val at: Long)

    val changed = Signal()
    private lateinit var appContext: Context
    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)

    fun init(ctx: Context) { appContext = ctx.applicationContext; cached = load() }

    /** Read on every map frame, so kept in memory. */
    @Volatile private var cached: Spot? = null
    fun get(): Spot? = cached?.takeIf { System.currentTimeMillis() - it.at < 7 * 86_400_000L }

    private fun load(): Spot? {
        if (!::appContext.isInitialized) return null
        val p = prefs()
        if (!p.contains("parkedLat")) return null
        return Spot(LatLng(p.getFloat("parkedLat", 0f).toDouble(), p.getFloat("parkedLon", 0f).toDouble()), p.getLong("parkedAt", 0))
    }

    fun save(pos: LatLng, at: Long = System.currentTimeMillis()) {
        if (!::appContext.isInitialized) return
        prefs().edit().putFloat("parkedLat", pos.lat.toFloat()).putFloat("parkedLon", pos.lon.toFloat()).putLong("parkedAt", at).apply()
        cached = Spot(pos, at)
        CrashLog.note("parking spot saved")
        changed.fire()
    }

    fun clear() {
        if (!::appContext.isInitialized || cached == null) return
        prefs().edit().remove("parkedLat").remove("parkedLon").remove("parkedAt").apply()
        cached = null
        changed.fire()
    }

    /** Android Auto just disconnected: the car is where the phone's last good fix was. */
    fun carDisconnected() {
        val l = LocationTracker.last ?: return
        if (System.currentTimeMillis() - l.time > 5 * 60_000L) return // too old to trust
        if (l.hasAccuracy() && l.accuracy > 80) return
        save(LatLng(l.latitude, l.longitude))
    }

    fun place(): Place? = get()?.let {
        Place(L.t("Your car"), L.t("Parked") + " " + NavEngine.clock(it.at), it.pos, "🚗")
    }
}
