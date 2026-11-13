package gr.gamemap

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * If the app crashes (on the phone or in Android Auto), saves what happened to a file. The next
 * time the app opens it offers to send the report, so problems get fixed without guessing.
 * Nothing leaves the phone unless you choose to share it.
 */
object CrashLog {
    private var file: File? = null
    private var version = ""
    private val recent = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "crash.txt")
        version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
        } catch (e: Exception) { "?" }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { save(thread, e) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }

    /** A short note of what the app was doing (kept: the last 40), included in a report. */
    fun note(what: String) = synchronized(recent) {
        recent.addLast(time.format(Date()) + "  " + what)
        while (recent.size > 40) recent.removeFirst()
    }

    private fun save(thread: Thread, e: Throwable) {
        val f = file ?: return
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val notes = synchronized(recent) { recent.joinToString("\n") }
        f.writeText(
            "GameRoute $version crashed ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Thread: ${thread.name}\n\n$sw\nWhat it was doing:\n$notes\n"
        )
    }

    /** The report from the last crash, if there is one waiting. */
    fun pending(): String? = file?.takeIf { it.exists() }?.let { try { it.readText() } catch (e: Exception) { null } }

    fun clear() { file?.delete() }
}
