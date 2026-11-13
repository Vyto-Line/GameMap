package gr.gamemap

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Play Store version: Google Play delivers updates, so the app never downloads or installs
 * anything itself (Play policy). Same shape as the GitHub version's Updater so callers compile.
 */
object Updater {
    const val ENABLED = false

    class Release(val versionCode: Long, val tag: String, val apkUrl: String)

    val available: Release? get() = null

    fun currentVersion(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    fun check(ctx: Context): Release? = null
    fun dueForCheck(ctx: Context) = false
    fun download(ctx: Context, r: Release, progress: (Int) -> Unit): File = throw UnsupportedOperationException()
    fun hasUnknownAuto(ctx: Context) = false
    fun install(ctx: Context) {}
}
