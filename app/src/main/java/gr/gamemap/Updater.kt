package gr.gamemap

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer build, downloads it and hands it to UnknownAuto, so the
 * update keeps the "installed from Google Play" mark that Android Auto requires.
 */
object Updater {
    private const val REPO = "Vyto-Line/GameRoute"
    const val UNKNOWN_AUTO = "com.mag.unknownauto"

    class Release(val versionCode: Long, val tag: String, val apkUrl: String)

    /** Newest release found by the last check, if it is newer than this app. */
    @Volatile
    var available: Release? = null
        private set

    fun currentVersion(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    /** Blocking network call: run off the main thread. Returns a newer release or null. */
    fun check(ctx: Context): Release? {
        val o = JSONObject(Http.get("https://api.github.com/repos/$REPO/releases/latest"))
        val code = Regex("versionCode[:=]\\s*(\\d+)").find(o.optString("body"))
            ?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val assets = o.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        val r = Release(code, o.optString("tag_name"), apk.getString("browser_download_url"))
        ctx.getSharedPreferences("gamemap", Context.MODE_PRIVATE).edit()
            .putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
        available = if (code > currentVersion(ctx)) r else null
        return available
    }

    fun dueForCheck(ctx: Context): Boolean {
        val last = ctx.getSharedPreferences("gamemap", Context.MODE_PRIVATE).getLong("lastUpdateCheck", 0)
        return System.currentTimeMillis() - last > 3 * 3600_000L
    }

    /** Blocking download into the app cache; [progress] gets 0..100. */
    fun download(ctx: Context, r: Release, progress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, UpdateProvider.NAME)
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Gameroute-updater")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return out
    }

    fun hasUnknownAuto(ctx: Context) = try {
        ctx.packageManager.getPackageInfo(UNKNOWN_AUTO, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Opens the downloaded APK in UnknownAuto, or in Android's installer if it is missing. */
    fun install(ctx: Context) {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(UpdateProvider.uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (hasUnknownAuto(ctx)) i.setPackage(UNKNOWN_AUTO)
        ctx.startActivity(i)
    }
}

/** Serves the downloaded update (read-only) to UnknownAuto or the system installer. */
class UpdateProvider : ContentProvider() {
    companion object {
        const val NAME = "Gameroute-update.apk"
        val uri: Uri = Uri.parse("content://gr.gamemap.update/$NAME")
    }

    private fun file(u: Uri): File {
        if (u.lastPathSegment != NAME) throw FileNotFoundException()
        return File(context!!.cacheDir, NAME)
    }

    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> NAME
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            })
        }
    }

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
