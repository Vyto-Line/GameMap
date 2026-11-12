package gr.gamemap

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import kotlin.math.abs

/** Phone screen: the game map with search, suggestions, controls and a navigation card. */
class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var map: GameMapView
    private lateinit var topArea: LinearLayout
    private lateinit var searchCard: LinearLayout
    private lateinit var search: EditText
    private lateinit var clearBtn: TextView
    private lateinit var suggestScroll: ScrollView
    private lateinit var suggestList: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var recenterBtn: TextView
    private lateinit var navCard: LinearLayout
    private lateinit var etaBig: TextView
    private lateinit var etaSmall: TextView
    private lateinit var endBtn: TextView
    private val fabs = mutableListOf<TextView>()

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var d = 1f
    private var insets = Rect()
    private var querySeq = 0
    private var lastResults: List<Place> = emptyList()

    private val stateListener: () -> Unit = { refreshChrome() }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LocationTracker.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        d = resources.displayMetrics.density

        map = GameMapView(this)
        buildSearch()
        buildControls()
        buildNavCard()

        val root = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(topArea, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(controls, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
            addView(navCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, wi ->
            val bars = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = wi.getInsets(WindowInsetsCompat.Type.ime())
            insets.set(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            val p = (10 * d).toInt()
            topArea.setPadding(bars.left + p, bars.top + p / 2, bars.right + p, 0)
            controls.setPadding(0, 0, bars.right + p, 0)
            navCard.setPadding(bars.left + 2 * p, p + p / 2, bars.right + 2 * p, bars.bottom + p + p / 2)
            updateMapInsets()
            wi
        }
        topArea.viewTreeObserver.addOnGlobalLayoutListener { updateMapInsets() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (suggestScroll.visibility == View.VISIBLE) closeSearch()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (LocationTracker.hasPermission(this)) LocationTracker.start(this)
        permissions.launch(wanted.toTypedArray())

        if (Updater.dueForCheck(this)) checkForUpdates(manual = false)
    }

    // ------------------------------------------------------------------ building

    private fun panelBg(radius: Float = 16f) = GradientDrawable().apply { cornerRadius = radius * d }

    private fun buildSearch() {
        val p = (12 * d).toInt()
        search = EditText(this).apply {
            hint = "Where to?"
            setSingleLine()
            textSize = 17f
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(p / 2, p, p / 2, p)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { runQuery(text.toString(), full = true); true } else false
            }
            setOnFocusChangeListener { _, has -> if (has) showSuggestions() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    clearBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    val q = s?.toString().orEmpty()
                    main.removeCallbacksAndMessages(SEARCH_TOKEN)
                    if (q.trim().length < 2) showRecents()
                    else main.postAtTime({ runQuery(q, full = false) }, SEARCH_TOKEN, android.os.SystemClock.uptimeMillis() + 350)
                }
            })
        }
        clearBtn = TextView(this).apply {
            text = "✕"; textSize = 18f; visibility = View.GONE
            setPadding(p, p, p, p)
            setOnClickListener { search.setText("") }
        }
        val icon = TextView(this).apply { text = "🔍"; textSize = 17f; setPadding(p, 0, 0, 0) }
        searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 6 * d
            addView(icon)
            addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clearBtn)
        }
        suggestList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        suggestScroll = ScrollView(this).apply {
            visibility = View.GONE
            elevation = 6 * d
            addView(suggestList)
        }
        topArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(suggestScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        }
    }

    private fun fab(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        elevation = 5 * d
        setOnClickListener { onClick() }
        val s = (50 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s).apply { topMargin = (10 * d).toInt() }
        fabs += this
    }

    private fun buildControls() {
        recenterBtn = fab("◎") { MapState.recenter() }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(fab("🎮") { pickTheme() })
            addView(fab("+") { MapState.zoomBy(1.0) })
            addView(fab("−") { MapState.zoomBy(-1.0) })
            addView(recenterBtn)
            addView(fab("⋮") { showMenu() })
        }
    }

    private fun buildNavCard() {
        etaBig = TextView(this).apply { textSize = 26f; typeface = Typeface.DEFAULT_BOLD }
        etaSmall = TextView(this).apply { textSize = 15f }
        endBtn = TextView(this).apply {
            text = "END"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val p = (14 * d).toInt()
            setPadding(p * 2, p, p * 2, p)
            setOnClickListener { NavEngine.stop() }
        }
        navCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 8 * d
            visibility = View.GONE
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(etaBig); addView(etaSmall)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(endBtn)
        }
    }

    // ------------------------------------------------------------------ state → UI

    /** Colours the controls to match the map theme and shows what fits the moment. */
    private fun refreshChrome() {
        val t = MapState.theme
        val panel = Color.argb(maxOf(Color.alpha(t.hudPanel), 225), Color.red(t.hudPanel), Color.green(t.hudPanel), Color.blue(t.hudPanel))
        val ink = t.hudText
        val accent = t.route
        val radius = when (t.hud) { HudShape.ROUND -> 18f; HudShape.CHAMFER -> 4f; HudShape.PIXEL -> 0f }

        searchCard.background = panelBg(radius).apply { setColor(panel); setStroke((1.5f * d).toInt(), accent) }
        suggestScroll.background = panelBg(radius).apply { setColor(panel) }
        search.setTextColor(ink)
        search.setHintTextColor(Color.argb(150, Color.red(ink), Color.green(ink), Color.blue(ink)))
        search.typeface = t.typeface
        clearBtn.setTextColor(ink)
        for (f in fabs) {
            f.setTextColor(ink)
            f.background = (if (t.hud == HudShape.ROUND) GradientDrawable().apply { shape = GradientDrawable.OVAL }
            else panelBg(radius)).apply { setColor(panel); setStroke((1.5f * d).toInt(), accent) }
        }
        navCard.background = GradientDrawable().apply {
            setColor(panel)
            val r = radius * 1.4f * d
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        etaBig.setTextColor(ink); etaSmall.setTextColor(ink)
        etaBig.typeface = t.typeface
        endBtn.setTextColor(Color.WHITE)
        endBtn.background = panelBg(radius).apply { setColor(Color.rgb(200, 40, 40)) }

        val nav = NavEngine.active
        navCard.visibility = if (nav) View.VISIBLE else View.GONE
        searchCard.visibility = if (nav) View.GONE else View.VISIBLE
        if (nav && suggestScroll.visibility == View.VISIBLE) closeSearch()
        recenterBtn.visibility = if (MapState.following && MapState.userBearing == null) View.GONE else View.VISIBLE
        if (nav) {
            when {
                NavEngine.arrived -> { etaBig.text = "Mission complete"; etaSmall.text = NavEngine.destinationName }
                NavEngine.loading || NavEngine.error != null -> {
                    etaBig.text = NavEngine.error ?: "Plotting route…"; etaSmall.text = NavEngine.destinationName
                }
                else -> {
                    val mins = (NavEngine.remainingSeconds / 60).toInt().coerceAtLeast(1)
                    val dur = if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
                    etaBig.text = "${NavEngine.arrivalClock()} arrival"
                    val delay = NavEngine.trafficDelaySeconds / 60
                    val traffic = if (delay >= 1) " · +$delay min traffic" else ""
                    etaSmall.text = "${NavEngine.formatDistance(NavEngine.remainingDistance)} · $dur$traffic · ${NavEngine.destinationName}"
                }
            }
        }
        updateMapInsets()
    }

    private fun updateMapInsets() {
        val top = if (searchCard.visibility == View.VISIBLE) searchCard.bottom + topArea.paddingTop / 2 + (6 * d).toInt()
        else insets.top
        val bottom = if (navCard.visibility == View.VISIBLE) navCard.height else insets.bottom
        if (map.insetTop != top || map.insetBottom != bottom) {
            map.insetTop = top
            map.insetBottom = bottom
            map.invalidate()
        }
    }

    // ------------------------------------------------------------------ suggestions

    private fun showSuggestions() {
        if (search.text.trim().length < 2) showRecents() else renderResults(lastResults, "Searching…")
    }

    private fun showRecents() {
        querySeq++
        lastResults = emptyList()
        val rec = Places.recents()
        renderResults(rec, if (rec.isEmpty()) "Type a place, address or business" else null, header = if (rec.isEmpty()) null else "RECENT")
    }

    private fun runQuery(q: String, full: Boolean) {
        if (q.trim().length < 2) return
        val seq = ++querySeq
        val near = LocationTracker.position
        if (full) hideKeyboard()
        io.execute {
            val r = try { if (full) Places.search(q, near) else Places.suggest(q, near) } catch (e: Exception) { null }
            runOnUiThread {
                if (seq != querySeq || isFinishing) return@runOnUiThread
                lastResults = r ?: emptyList()
                renderResults(lastResults, when {
                    r == null -> "No connection: check mobile data"
                    r.isEmpty() -> "Nothing found"
                    else -> null
                })
            }
        }
    }

    private fun renderResults(places: List<Place>, message: String?, header: String? = null) {
        if (!search.hasFocus() && search.text.isEmpty() && places.isEmpty() && message == null) return
        val t = MapState.theme
        val ink = t.hudText
        val dim = Color.argb(170, Color.red(ink), Color.green(ink), Color.blue(ink))
        val p = (12 * d).toInt()
        suggestList.removeAllViews()
        header?.let {
            suggestList.addView(TextView(this).apply {
                text = it; textSize = 12f; setTextColor(dim); letterSpacing = 0.1f
                setPadding(p + p / 2, p, p, p / 3)
            })
        }
        message?.let {
            suggestList.addView(TextView(this).apply { text = it; textSize = 15f; setTextColor(dim); setPadding(p + p / 2, p, p, p) })
        }
        val near = LocationTracker.position
        for (pl in places) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(p, p - p / 4, p + p / 2, p - p / 4)
                isClickable = true
                setOnClickListener { choose(pl) }
            }
            row.addView(TextView(this).apply {
                text = pl.icon; textSize = 20f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), WRAP_CONTENT)
            })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(p / 2, 0, p / 2, 0)
                addView(TextView(context).apply {
                    text = pl.name; textSize = 16f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                if (pl.address.isNotBlank()) addView(TextView(context).apply {
                    text = pl.address; textSize = 13f; setTextColor(dim)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (near != null) row.addView(TextView(this).apply {
                text = NavEngine.formatDistance(Geo.distance(near, pl.pos)); textSize = 13f; setTextColor(dim)
            })
            suggestList.addView(row)
        }
        suggestScroll.visibility = View.VISIBLE
        // keep the list from covering the whole screen
        suggestScroll.post {
            val max = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            val lp = suggestScroll.layoutParams
            val want = if (suggestList.height > max) max else WRAP_CONTENT
            if (lp.height != want) { lp.height = want; suggestScroll.layoutParams = lp }
        }
    }

    private fun choose(pl: Place) {
        closeSearch()
        search.setText("")
        NavEngine.start(this, pl)
    }

    private fun closeSearch() {
        querySeq++
        suggestScroll.visibility = View.GONE
        search.clearFocus()
        hideKeyboard()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ------------------------------------------------------------------ menus

    private fun pickTheme() {
        val all = Theme.entries
        AlertDialog.Builder(this)
            .setTitle("Map theme")
            .setSingleChoiceItems(all.map { it.title }.toTypedArray(), MapState.theme.ordinal) { dlg, i ->
                MapState.setTheme(all[i]); dlg.dismiss()
            }
            .show()
    }

    private fun showMenu() {
        val items = arrayOf("🚦  Live traffic", "⬆  Check for updates", "ℹ  About")
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> trafficSettings()
                1 -> checkForUpdates(manual = true)
                2 -> AlertDialog.Builder(this)
                    .setTitle("GameMap build ${Updater.currentVersion(this)}")
                    .setMessage("Map data © OpenStreetMap contributors\nVector tiles: OpenFreeMap\nTraffic: TomTom\nRouting: OSRM\nSearch: Photon (komoot), Nominatim")
                    .setPositiveButton("OK", null).show()
            }
        }.show()
    }

    private fun trafficSettings() {
        val input = EditText(this).apply {
            hint = if (Traffic.builtIn) "Built-in key in use (optional: your own)" else "Paste your TomTom API key"
            setText(if (Traffic.builtIn) "" else Traffic.key)
            setSingleLine()
        }
        val toggle = android.widget.CheckBox(this).apply {
            text = "Show live traffic"
            isChecked = Traffic.enabled
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * d).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input); addView(toggle)
        }
        AlertDialog.Builder(this)
            .setTitle("Live traffic")
            .setMessage(
                "Traffic comes from TomTom. Their free plan (no credit card) covers 50,000 map " +
                    "tiles a day, plenty for driving. Tap GET FREE KEY, sign up, create a key " +
                    "and paste it here. Slow traffic and jams are drawn in your theme's colours, " +
                    "and arrival times include traffic delays."
            )
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Traffic.setKey(input.text.toString())
                Traffic.setEnabled(toggle.isChecked)
            }
            .setNeutralButton("Get free key") { _, _ ->
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://developer.tomtom.com/user/register")))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        io.execute {
            val r = try { Updater.check(this) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (r == null) {
                    if (manual) Toast.makeText(this, "You have the latest version (build ${Updater.currentVersion(this)})", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Update available")
                    .setMessage("${r.tag} is ready (you have build ${Updater.currentVersion(this)}). Download and install it?")
                    .setPositiveButton("Update") { _, _ -> downloadAndInstall(r) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun downloadAndInstall(r: Updater.Release) {
        Toast.makeText(this, "Downloading update…", Toast.LENGTH_LONG).show()
        io.execute {
            val ok = try { Updater.download(this, r) {}; true } catch (e: Exception) { false }
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, "Download failed: check internet", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val viaUnknownAuto = Updater.hasUnknownAuto(this)
                AlertDialog.Builder(this)
                    .setTitle("Install update")
                    .setMessage(
                        if (viaUnknownAuto)
                            "Make sure Shizuku is running, then tap Install in UnknownAuto. " +
                                "This keeps GameMap visible in Android Auto."
                        else
                            "UnknownAuto isn't installed, so Android's normal installer will be used. " +
                                "The update will work on the phone, but Android Auto may hide GameMap " +
                                "until you reinstall it through UnknownAuto."
                    )
                    .setPositiveButton("Continue") { _, _ ->
                        try { Updater.install(this) } catch (e: Exception) {
                            Toast.makeText(this, "Couldn't open the installer: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle & compass

    override fun onStart() {
        super.onStart()
        NavEngine.changed += stateListener
        MapState.changed += stateListener
        refreshChrome()
    }

    override fun onStop() {
        NavEngine.changed -= stateListener
        MapState.changed -= stateListener
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        getSystemService(SensorManager::class.java).unregisterListener(this)
        LocationTracker.setCompass(null)
        super.onPause()
    }

    private val rot = FloatArray(9)
    private val remap = FloatArray(9)
    private val orient = FloatArray(3)
    private var lastCompass = -999f

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        @Suppress("DEPRECATION")
        val r = windowManager.defaultDisplay.rotation
        val (ax, ay) = when (r) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rot, ax, ay, remap)
        SensorManager.getOrientation(remap, orient)
        val deg = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
        if (abs(Geo.angleDiff(lastCompass, deg)) > 2f) {
            lastCompass = deg
            LocationTracker.setCompass(deg)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private val SEARCH_TOKEN = Any()
    }
}

/** The phone's map view: redraws on changes, animates smoothly, pinch to zoom, drag to look around. */
class GameMapView(ctx: Context) : View(ctx) {
    private val renderer = MapRenderer()
    private val area = Rect()
    var insetTop = 0
    var insetBottom = 0
    private val redraw: () -> Unit = { postInvalidateOnAnimation() }
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var scaling = false
    private var rotating = false
    private var lastAngle = 0f
    private var twist = 0f
    private var downX = 0f
    private var downY = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scaling = true; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            MapState.zoomByFactor(detector.scaleFactor)
            return true
        }
    })

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        TileCache.loaded += redraw
        LocationTracker.changed += redraw
        NavEngine.changed += redraw
        MapState.changed += redraw
    }

    override fun onDetachedFromWindow() {
        TileCache.loaded -= redraw
        LocationTracker.changed -= redraw
        NavEngine.changed -= redraw
        MapState.changed -= redraw
        super.onDetachedFromWindow()
    }

    private fun angle(e: MotionEvent) =
        Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                dragging = false; scaling = false; rotating = false; twist = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { lastAngle = angle(e); twist = 0f }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    // Two-finger twist rotates the map (after a small dead zone, so pinching stays clean).
                    val a = angle(e)
                    val delta = Geo.angleDiff(lastAngle, a)
                    lastAngle = a
                    twist += delta
                    if (!rotating && abs(twist) > 12f) rotating = true
                    if (rotating) {
                        val cam = renderer.camera
                        if (cam != null) MapState.rotateBy(-delta, cam.bearing)
                    }
                } else if (!scaling && !rotating) {
                    val dx = e.x - lastX; val dy = e.y - lastY
                    if (dragging || abs(e.x - downX) + abs(e.y - downY) > 12 * resources.displayMetrics.density) {
                        dragging = true
                        val cam = renderer.camera
                        if (cam != null) {
                            // Move the camera so the point under the finger stays under it.
                            MapState.pan(renderer.toMap(cam.cx - dx, cam.cy - dy, cam), cam.bearing)
                        }
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                // Tap on the compass: back to driving direction / north.
                if (!dragging && !scaling && !rotating && renderer.compassBounds.contains(e.x, e.y)) {
                    MapState.clearRotation()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        area.set(0, insetTop, width, height - insetBottom)
        renderer.draw(canvas, width, height, area, resources.displayMetrics.density, hud = true)
        if (renderer.animating) postInvalidateOnAnimation()
    }
}
