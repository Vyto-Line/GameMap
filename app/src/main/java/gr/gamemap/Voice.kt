package gr.gamemap

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Spoken directions through the phone's own text-to-speech voice (Android Settings ›
 * Text-to-speech). Music is turned down while it speaks; in the car it plays through the car.
 */
object Voice {
    val changed = Signal()
    private lateinit var appContext: Context
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var focus: AudioFocusRequest? = null

    // per-maneuver announcement state
    private var lastStep = -1
    private var saidFar = false
    private var saidPrep = false
    private var saidNow = false
    private var stepStartDist = 0.0

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    private fun prefs() = appContext.getSharedPreferences("gamemap", Context.MODE_PRIVATE)

    var muted: Boolean
        get() = ::appContext.isInitialized && prefs().getBoolean("voiceMuted", false)
        set(v) {
            prefs().edit().putBoolean("voiceMuted", v).apply()
            if (v) tts?.stop()
            changed.fire()
        }

    /** "el" or "en"; defaults to the phone's language. */
    var lang: String
        get() {
            if (!::appContext.isInitialized) return Phrases.ENGLISH
            val def = if (Locale.getDefault().language == "el") Phrases.GREEK else Phrases.ENGLISH
            return prefs().getString("voiceLang", def) ?: def
        }
        set(v) {
            prefs().edit().putString("voiceLang", v).apply()
            ready = false
            tts?.shutdown(); tts = null
            changed.fire()
        }

    fun reset() {
        lastStep = -1
        saidFar = false; saidPrep = false; saidNow = false
    }

    /** Free the speech engine (app in the background with nothing to guide). */
    fun shutdown() {
        if (!::appContext.isInitialized) return
        abandonFocus()
        tts?.shutdown(); tts = null; ready = false; pending = null
    }

    fun stop() {
        tts?.stop()
        if (::appContext.isInitialized) abandonFocus()
        reset()
    }

    /** Called on every position update with the next maneuver. */
    fun progress(index: Int, step: NavStep, dist: Double, speed: Float, then: NavStep?) {
        if (index != lastStep) {
            lastStep = index
            saidFar = false; saidPrep = false; saidNow = false
            stepStartDist = dist
        }
        if (step.direction == "arrive") return // the arrival has its own line
        val near = maxOf(speed * 7.0, 40.0)
        val prep = maxOf(speed * 22.0, 250.0).coerceAtMost(1500.0)
        when {
            !saidNow && dist <= near -> {
                saidNow = true; saidPrep = true; saidFar = true
                val t = Phrases.instruction(step, lang)
                say(if (then != null) "$t, ${Phrases.then(then, lang)}" else t)
            }
            !saidPrep && dist <= prep && dist > near * 1.8 && stepStartDist > near * 2 -> {
                saidPrep = true; saidFar = true
                say(Phrases.ahead(step, dist, lang))
            }
            !saidFar && dist in 1800.0..2300.0 && speed > 17f -> {
                saidFar = true
                say(Phrases.ahead(step, dist, lang))
            }
        }
    }

    /**
     * Another app is recording (Instagram, the camera, a call): a voice line would take the audio
     * focus and make it stop, so stay quiet.
     */
    private fun someoneRecording(): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val am = appContext.getSystemService(AudioManager::class.java) ?: return false
        return try {
            am.activeRecordingConfigurations.isNotEmpty() || am.mode == AudioManager.MODE_IN_CALL ||
                am.mode == AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) { false }
    }

    fun say(text: String) {
        if (!::appContext.isInitialized || muted) return
        // nothing to say unless the map is in use (open, in the car, or navigating)
        if (!LocationTracker.inUse && !NavEngine.active) return
        if (someoneRecording()) return
        val t = tts
        if (t == null || !ready) {
            pending = text
            if (t == null) create()
            return
        }
        if (!requestFocus()) return
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "gm" + System.nanoTime())
    }

    private fun create() {
        tts = TextToSpeech(appContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
            val t = tts ?: return
            if (status != TextToSpeech.SUCCESS) return
            val loc = if (lang == Phrases.GREEK) Locale("el", "GR") else Locale.UK
            val r = t.setLanguage(loc)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.US)
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { abandonFocus() }
                // cut off by a newer line or stop(): give the focus back too
                override fun onStop(utteranceId: String?, interrupted: Boolean) { abandonFocus() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { abandonFocus() }
            })
            ready = true
            pending?.let { pending = null; say(it) }
    }

    /** Short, duck-others focus for one line; false if the system said no (e.g. a call). */
    private fun requestFocus(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val am = appContext.getSystemService(AudioManager::class.java) ?: return true
        focus?.let { am.abandonAudioFocusRequest(it) } // never leave an old request behind
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            ).build()
        focus = req
        return am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT < 26) return
        val am = appContext.getSystemService(AudioManager::class.java) ?: return
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }
}
