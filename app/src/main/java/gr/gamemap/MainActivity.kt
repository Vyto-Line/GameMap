package gr.gamemap

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
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
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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

/** Phone screen: the game map with search, quick places, route preview, driving cards and sheets. */
class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var map: GameMapView
    private lateinit var topArea: LinearLayout
    private lateinit var searchCard: LinearLayout
    private lateinit var search: EditText
    private lateinit var searchIcon: TextView
    private lateinit var clearBtn: TextView
    private lateinit var menuBtn: TextView
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private lateinit var suggestScroll: ScrollView
    private lateinit var suggestList: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var styleBtn: TextView
    private lateinit var recenterBtn: TextView
    private lateinit var muteBtn: TextView
    private lateinit var overviewBtn: TextView
    private lateinit var navMenuBtn: TextView
    private lateinit var reportBtn: TextView
    private lateinit var askCard: LinearLayout
    private lateinit var askText: TextView
    private var askShown: Alerts.Report? = null
    private lateinit var homeSheet: LinearLayout
    private lateinit var homeTitle: TextView
    private lateinit var homeCards: LinearLayout
    private lateinit var navCard: LinearLayout
    private lateinit var etaBig: TextView
    private lateinit var etaTraffic: TextView
    private lateinit var etaSmall: TextView
    private lateinit var endBtn: TextView
    private lateinit var shareBtn: TextView
    private lateinit var preview: LinearLayout
    private lateinit var previewTitle: TextView
    private lateinit var previewSub: TextView
    private lateinit var previewList: LinearLayout
    private lateinit var previewClose: TextView
    private lateinit var previewBack: TextView
    private lateinit var trafficBtn: TextView
    private lateinit var laterBtn: TextView
    private lateinit var startBtn: TextView
    private lateinit var offerCard: LinearLayout
    private lateinit var offerTitle: TextView
    private lateinit var offerSub: TextView
    private lateinit var offerVia: TextView
    private lateinit var keepBtn: TextView
    private lateinit var switchBtn: TextView
    private lateinit var sheetOverlay: FrameLayout
    private lateinit var tripOverlay: FrameLayout
    private lateinit var tripCard: LinearLayout
    private var shownTrip: Explorer.Trip? = null
    private lateinit var sheetHost: LinearLayout
    private var sheetKind = ""
    private val fabs = mutableListOf<TextView>()

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var d = 1f
    private var insets = Rect()
    private var querySeq = 0
    private var lastResults: List<Place> = emptyList()
    /** "home" / "work" while the user is choosing that saved place in search. */
    private var pickFor: String? = null
    private var etaAskedAt = 0L
    private var etaVersion = 0
    private var lastHomeKey = ""
    private var lastPreviewKey = ""

    private val stateListener: () -> Unit = { refreshChrome() }
    private val ticker = object : Runnable {
        override fun run() {
            if (NavEngine.offer != null) { refreshChrome(); main.postDelayed(this, 1000) }
        }
    }

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
        map.onTouchStart = {
            if (search.hasFocus() || suggestScroll.visibility == View.VISIBLE || pickFor != null) closeSearch()
        }
        buildSearch()
        buildControls()
        buildHomeSheet()
        buildNavCard()
        buildPreview()
        buildOffer()
        sheetHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; elevation = 16 * d }
        sheetOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            elevation = 30 * d
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            setOnClickListener { closeSheet() }
            addView(sheetHost, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }

        tripCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; elevation = 18 * d; gravity = Gravity.CENTER_HORIZONTAL }
        tripOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            elevation = 32 * d
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            isClickable = true
            addView(tripCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
                val m = (22 * d).toInt(); setMargins(m, m, m, m)
            })
        }

        val root = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(homeSheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            addView(topArea, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(controls, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
            addView(navCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            addView(offerCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(askCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            addView(sheetOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(tripOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // keep the search box from grabbing focus (and opening suggestions) at launch
        root.isFocusableInTouchMode = true
        root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        setContentView(root)
        root.requestFocus()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, wi ->
            val bars = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = wi.getInsets(WindowInsetsCompat.Type.ime())
            insets.set(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            val p = (10 * d).toInt()
            topArea.setPadding(bars.left + p, bars.top + p / 2, bars.right + p, 0)
            controls.setPadding(0, 0, bars.right + p, 0)
            navCard.setPadding(bars.left + 2 * p, p + p / 2, bars.right + 2 * p, bars.bottom + p + p / 2)
            preview.setPadding(bars.left + p + p / 2, p, bars.right + p + p / 2, bars.bottom + p + p / 2)
            homeSheet.setPadding(bars.left, p, bars.right, bars.bottom + p)
            sheetHost.setPadding(bars.left + 2 * p, p + p / 2, bars.right + 2 * p, bars.bottom + 2 * p)
            (offerCard.layoutParams as FrameLayout.LayoutParams).setMargins(bars.left + p, bars.top + p, bars.right + p, 0)
            (askCard.layoutParams as FrameLayout.LayoutParams).setMargins(bars.left + p, 0, bars.right + p, bars.bottom + (120 * d).toInt())
            offerCard.requestLayout()
            updateMapInsets()
            wi
        }
        topArea.viewTreeObserver.addOnGlobalLayoutListener { updateMapInsets() }
        preview.viewTreeObserver.addOnGlobalLayoutListener { updateMapInsets() }
        homeSheet.viewTreeObserver.addOnGlobalLayoutListener { updateMapInsets() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    tripOverlay.visibility == View.VISIBLE -> NavEngine.dismissTrip()
                    askCard.visibility == View.VISIBLE -> Alerts.clearAsk()
                    sheetOverlay.visibility == View.VISIBLE -> closeSheet()
                    suggestScroll.visibility == View.VISIBLE || pickFor != null -> closeSearch()
                    NavEngine.planning -> NavEngine.cancelPlan()
                    NavEngine.active -> AlertDialog.Builder(this@MainActivity)
                        .setTitle("End navigation?")
                        .setPositiveButton("End") { _, _ -> NavEngine.stop() }
                        .setNegativeButton("Keep going", null)
                        .show()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (LocationTracker.hasPermission(this)) LocationTracker.start(this)
        permissions.launch(wanted.toTypedArray())

        if (Updater.dueForCheck(this)) checkForUpdates(manual = false)
    }

    // ------------------------------------------------------------------ look helpers

    private val theme get() = MapState.theme
    private val ink get() = Ui.ink(theme)
    private val panel: Int get() = Ui.panel(theme)
    private val accent get() = theme.route
    /** Secondary text (strength 255 = full ink); thin lines and fills below 120 stay faint. */
    private fun dim(a: Int = 185) = if (a < 120) Color.argb(a, Color.red(ink), Color.green(ink), Color.blue(ink)) else Ui.dim(theme, a)
    private fun bg(fill: Int, stroke: Int = 0, strokeDp: Float = 0f, radius: Float = 16f, topOnly: Boolean = false, pill: Boolean = false) =
        HudDrawable(theme.hud, fill, d, radius, stroke, strokeDp, topOnly, pill)
    private fun luminance(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
    private fun onAccent(c: Int) = if (luminance(c) > 0.5) Color.rgb(15, 18, 22) else Color.WHITE
    private fun darken(c: Int) = Color.rgb(Color.red(c) * 6 / 10, Color.green(c) * 6 / 10, Color.blue(c) * 6 / 10)
    private val lightPanel get() = luminance(panel) > 0.6
    private fun readable(c: Int) = Ui.onPanel(theme, c)

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        val p = (12 * d).toInt()
        setPadding(p + p / 2, p, p + p / 2, p)
        minHeight = (48 * d).toInt()
        setOnClickListener { onClick() }
    }

    private fun label(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    // ------------------------------------------------------------------ search & quick places

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
            setOnFocusChangeListener { _, has -> if (has) showSuggestions(); refreshChrome() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    clearBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    val q = s?.toString().orEmpty()
                    main.removeCallbacksAndMessages(SEARCH_TOKEN)
                    if (q.trim().length < 2) { if (search.hasFocus()) showRecents() }
                    else main.postAtTime({ runQuery(q, full = false) }, SEARCH_TOKEN, android.os.SystemClock.uptimeMillis() + 550)
                }
            })
        }
        clearBtn = TextView(this).apply {
            text = "✕"; textSize = 18f; visibility = View.GONE
            setPadding(p, p, p, p)
            setOnClickListener { search.setText("") }
        }
        searchIcon = TextView(this).apply {
            text = "⌕"; textSize = 22f; gravity = Gravity.CENTER
            minWidth = (44 * d).toInt(); minHeight = (44 * d).toInt()
            setPadding(p / 2, 0, 0, 0)
            setOnClickListener { if (search.hasFocus() || suggestScroll.visibility == View.VISIBLE || pickFor != null) closeSearch() else search.requestFocus() }
        }
        menuBtn = TextView(this).apply {
            text = "☰"; textSize = 20f; gravity = Gravity.CENTER
            contentDescription = "Settings"
            setOnClickListener { showSettings() }
            layoutParams = LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()).apply { marginEnd = (5 * d).toInt() }
        }
        searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 6 * d
            minimumHeight = (56 * d).toInt()
            addView(searchIcon)
            addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clearBtn)
            addView(menuBtn)
        }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (10 * d).toInt(), 0, (6 * d).toInt()) }
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(chips)
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
            addView(chipsScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(suggestScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        }
    }

    private fun renderChips() {
        chips.removeAllViews()
        fun chip(text: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
            val v = TextView(this).apply {
                this.text = text
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ink)
                gravity = Gravity.CENTER
                val p = (12 * d).toInt()
                setPadding(p + p / 4, 0, p + p / 4, 0)
                minHeight = (40 * d).toInt()
                elevation = 3 * d
                background = bg(panel, dim(80), 1f, 12f, pill = true)
                setOnClickListener { onClick() }
                if (onLong != null) setOnLongClickListener { onLong(); true }
            }
            chips.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, (40 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
        }
        for (k in listOf("home", "work")) {
            val p = Places.saved(k)
            val name = if (k == "home") "🏠 Home" else "💼 Work"
            val eta = p?.let { Places.cachedEta(it) }?.let { " · ${NavEngine.formatDuration(it.seconds.toDouble())}" } ?: ""
            chip(if (p == null) "$name  +" else name + eta, onLong = { startPicking(k) }) {
                if (p == null) startPicking(k) else NavEngine.plan(this, p)
            }
        }
        chip("⛽ Fuel") { showNearby("FUEL NEARBY", listOf("fuel", "charging_station")) }
        chip("🍽 Food") { showNearby("FOOD NEARBY", listOf("restaurant", "fast_food", "cafe")) }
        chip("🅿 Parking") { showNearby("PARKING NEARBY", listOf("parking")) }
    }

    private fun startPicking(key: String) {
        pickFor = key
        search.hint = if (key == "home") "Search for your home address" else "Search for your work address"
        search.setText("")
        search.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
        showRecents()
        refreshChrome()
    }

    private fun showNearby(title: String, types: List<String>) {
        val near = LocationTracker.position
        if (near == null) { Toast.makeText(this, "Waiting for your location…", Toast.LENGTH_SHORT).show(); return }
        val seq = ++querySeq
        lastResults = emptyList()
        renderResults(emptyList(), "Searching nearby…", header = title, force = true)
        io.execute {
            val local = try { Places.nearbyLocal(types, near) } catch (e: Exception) { emptyList<Place>() }
            if (local.isNotEmpty()) runOnUiThread {
                if (seq == querySeq && !isFinishing) { lastResults = local; renderResults(local, null, header = title, force = true) }
            }
            val r = try { Places.nearbyFull(types, near, local) } catch (e: Exception) { null }
            runOnUiThread {
                if (seq != querySeq || isFinishing) return@runOnUiThread
                lastResults = r ?: emptyList()
                renderResults(lastResults, when {
                    r == null -> "No connection: check mobile data"
                    r.isEmpty() -> "Nothing found nearby"
                    else -> null
                }, header = title, force = true)
            }
        }
    }

    // ------------------------------------------------------------------ home sheet (recent places)

    private var exploreCard: LinearLayout? = null

    /** Trailblazer: "N landmarks to discover nearby" with your level. */
    private fun renderExploreCard(near: LatLng?) {
        exploreCard?.let { homeSheet.removeView(it) }
        exploreCard = null
        if (theme != Theme.TRAILBLAZER) return
        val todo = near?.let { Explorer.near(it, 1500.0).filterNot { l -> Explorer.isFound(l) } } ?: emptyList()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (12 * d).toInt()
            setPadding(p, p, p, p)
            background = bg(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)), radius = 16f)
            setOnClickListener {
                if (todo.isNotEmpty() && near != null) MapState.phone.requestFit(todo.map { it.pos } + near)
                else Toast.makeText(this@MainActivity, "Drive or walk around to find landmarks", Toast.LENGTH_SHORT).show()
            }
        }
        card.addView(label("★", 20f, bold = true).apply {
            gravity = Gravity.CENTER; setTextColor(onAccent(accent))
            background = bg(accent, radius = 20f, pill = true)
        }, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply { marginEnd = (12 * d).toInt() })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(when {
                near == null -> "Landmarks appear once GPS finds you"
                todo.isEmpty() -> "No new landmarks nearby"
                todo.size == 1 -> "1 landmark to discover nearby"
                else -> "${todo.size} landmarks to discover nearby"
            }, 15f, bold = true).apply { setTextColor(ink) })
            val (have, of) = Explorer.progress()
            addView(label("Level ${Explorer.level} · $have / $of XP · ${Explorer.foundCount} found", 13f).apply { setTextColor(dim()) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(label("›", 22f).apply { setTextColor(dim()) })
        homeSheet.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            val m = (14 * d).toInt(); setMargins(m, (10 * d).toInt(), m, 0)
        })
        exploreCard = card
    }

    private fun buildHomeSheet() {
        homeTitle = label("Recent", 17f, bold = true).apply { setPadding((18 * d).toInt(), 0, 0, (8 * d).toInt()) }
        homeCards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding((14 * d).toInt(), 0, (8 * d).toInt(), 0) }
        homeSheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 8 * d
            isClickable = true
            addView(homeTitle)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(homeCards)
            })
        }
    }

    private fun renderHomeSheet() {
        val rec = Places.recents().take(6)
        homeSheet.background = bg(panel, radius = 22f, topOnly = true)
        homeTitle.setTextColor(ink); homeTitle.typeface = theme.typeface
        homeCards.removeAllViews()
        val near = LocationTracker.position
        for (pl in rec) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (12 * d).toInt()
                setPadding(p, p - p / 4, p, p - p / 4)
                background = bg(Color.argb(28, Color.red(ink), Color.green(ink), Color.blue(ink)), dim(60), 1f, 14f)
                setOnClickListener { NavEngine.plan(this@MainActivity, pl) }
            }
            card.addView(label(pl.name, 15f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val dist = near?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) }
            card.addView(label(listOfNotNull(pl.address.split(",").firstOrNull()?.takeIf { it.isNotBlank() }, dist).joinToString(" · "), 13f).apply {
                setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            Places.cachedEta(pl)?.let { e ->
                val mins = (e.seconds + 30) / 60
                val late = e.delaySeconds / 60
                card.addView(label(if (late >= 2) "● $mins min · +$late traffic" else "● $mins min · clear", 13f, bold = true).apply {
                    setTextColor(readable(if (late >= 2) Color.rgb(255, 120, 80) else Color.rgb(47, 210, 122)))
                    setPadding(0, (4 * d).toInt(), 0, 0)
                })
            }
            homeCards.addView(card, LinearLayout.LayoutParams((176 * d).toInt(), WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
        }
        homeTitle.visibility = if (rec.isEmpty()) View.GONE else View.VISIBLE
        homeCards.visibility = if (rec.isEmpty()) View.GONE else View.VISIBLE
        renderExploreCard(near)
        // live ETAs for recents, home and work (once a minute at most; TomTom answers are cached 5 min)
        if (near != null && Traffic.active && System.currentTimeMillis() - etaAskedAt > 60_000) {
            etaAskedAt = System.currentTimeMillis()
            val targets = (listOfNotNull(Places.saved("home"), Places.saved("work")) + rec).filter { Places.cachedEta(it) == null }
            if (targets.isNotEmpty()) io.execute {
                var any = false
                for (t in targets) if (Places.refreshEta(t, near) != null) any = true
                if (any) runOnUiThread { etaVersion++; refreshChrome() }
            }
        }
    }

    // ------------------------------------------------------------------ right-hand buttons

    private fun fab(label: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        elevation = 5 * d
        contentDescription = desc
        setOnClickListener { onClick() }
        val s = (50 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s).apply { topMargin = (10 * d).toInt() }
        fabs += this
    }

    private fun buildControls() {
        styleBtn = fab("🎮", "Map style") { showStylePicker() }
        recenterBtn = fab("◎", "Recenter") { MapState.phone.recenter() }
        muteBtn = fab("🔊", "Mute voice") { Voice.muted = !Voice.muted }
        overviewBtn = fab("🗺", "Route overview") {
            val r = NavEngine.route
            if (r.size > 1) MapState.phone.showOverview(r.filterIndexed { i, _ -> i % 5 == 0 } + r.last())
        }
        navMenuBtn = fab("☰", "Settings") { showSettings() }
        reportBtn = fab("⚠", "Report") { openSheet("report") }
        trafficBtn = fab("🚦", "Traffic on the map") {
            Traffic.showLayer = !Traffic.showLayer
            Toast.makeText(this, if (Traffic.showLayer) "Traffic shown" else "Traffic hidden", Toast.LENGTH_SHORT).show()
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(styleBtn); addView(trafficBtn); addView(reportBtn); addView(muteBtn); addView(overviewBtn); addView(recenterBtn); addView(navMenuBtn)
        }
        askText = label("", 16f, bold = true)
        askCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 12 * d
            visibility = View.GONE
            isClickable = true
            val p = (14 * d).toInt(); setPadding(p, p, p, p)
            addView(askText)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pill("Not there") { askShown?.let { Alerts.vote(it, false) }; Alerts.clearAsk() },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = (10 * d).toInt() })
                addView(pill("Still there") { askShown?.let { Alerts.vote(it, true) }; Alerts.clearAsk() },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        }
    }

    // ------------------------------------------------------------------ driving card

    private fun buildNavCard() {
        etaBig = TextView(this).apply { textSize = 28f; typeface = Typeface.DEFAULT_BOLD }
        etaTraffic = TextView(this).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setPadding((10 * d).toInt(), 0, 0, 0) }
        etaSmall = TextView(this).apply { textSize = 15f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        shareBtn = pill("Share") { shareEta() }
        endBtn = pill("End") { NavEngine.stop() }
        navCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 8 * d
            visibility = View.GONE
            isClickable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    addView(etaBig); addView(etaTraffic)
                })
                addView(etaSmall)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(shareBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (8 * d).toInt() })
            addView(endBtn)
        }
    }

    private fun shareEta() {
        val mins = (NavEngine.remainingSeconds / 60).toInt().coerceAtLeast(1)
        val msg = "On my way to ${NavEngine.destinationName}: arriving around ${NavEngine.arrivalClock()} (about $mins min)."
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, msg)
        }
        startActivity(android.content.Intent.createChooser(send, "Share arrival time"))
    }

    // ------------------------------------------------------------------ route preview

    private fun buildPreview() {
        previewTitle = TextView(this).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        previewSub = TextView(this).apply { textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        previewClose = pill("✕") { NavEngine.cancelPlan() }
        previewBack = pill("←") { NavEngine.cancelPlan(); search.requestFocus(); showSuggestions() }
        previewList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        laterBtn = pill("Leave later") { pickDeparture() }
        startBtn = pill("Start") { NavEngine.startPlanned() }
        preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 10 * d
            visibility = View.GONE
            isClickable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(previewBack)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(previewTitle); addView(previewSub)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(previewClose)
            })
            addView(previewList, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(laterBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
                addView(startBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
        }
    }

    private fun pickDeparture() {
        if (NavEngine.departAt != null) { NavEngine.setDeparture(null); return }
        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, 30) }
        android.app.TimePickerDialog(this, { _, h, m ->
            val t = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m); set(java.util.Calendar.SECOND, 0)
                if (timeInMillis < System.currentTimeMillis() - 60_000) add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            NavEngine.setDeparture(t.timeInMillis)
        }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
    }

    private var infoAsked: Place? = null
    private var infoFuel: String? = null

    /** Fuel prices and stars of the destination, with a Reviews button (loaded once per place). */
    private fun placeInfo(place: Place): View? {
        if (infoAsked != place) {
            infoAsked = place
            infoFuel = place.extra.ifBlank { null }
            val wantsFuel = infoFuel == null && place.icon == "⛽"
            if (wantsFuel || Reviews.enabled) io.execute {
                val f = if (wantsFuel) try { Fuel.forPlace(place) } catch (e: Exception) { null } else null
                if (Reviews.enabled) try { Reviews.details(place) } catch (e: Exception) {}
                runOnUiThread { if (infoAsked == place && !isFinishing) { if (f != null) infoFuel = f; if (NavEngine.planning) renderPreview() } }
            }
        }
        val info = Reviews.cached(place)
        val stars = Reviews.stars(info?.rating ?: place.rating, info?.count ?: place.ratings)
        val fuel = infoFuel
        val reviewable = place.icon != "🛣" && place.icon != "🏙" && place.address != "Street" && place.address != "Area"
        if (stars == null && fuel == null && !reviewable) return null
        val p = (12 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p / 2, p / 2, p / 2)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = stars ?: if (Reviews.enabled && info == null) "Loading rating…" else if (fuel != null) "⛽ Prices" else "Reviews"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (stars != null) Color.rgb(255, 193, 7).let { if (Ui.contrast(it, panel) < 3) readable(it) else it } else ink)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (reviewable) top.addView(TextView(this).apply {
            text = "Reviews ›"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(readable(accent))
            setPadding(p, p / 2, p / 2, p / 2)
            isClickable = true
            setOnClickListener { showReviews(place) }
        })
        box.addView(top)
        if (fuel != null) box.addView(TextView(this).apply {
            text = fuel; textSize = 14f; setTextColor(ink)
        })
        return box
    }

    private fun showReviews(place: Place) {
        val info = Reviews.cached(place)
        if (info == null || info.reviews.isEmpty()) {
            // no key (or nothing from Google): its reviews are in Google Maps
            val url = info?.mapsUrl ?: Reviews.mapsLink(place)
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            catch (e: Exception) { Toast.makeText(this, "No app to open Google Maps", Toast.LENGTH_SHORT).show() }
            return
        }
        val p = (16 * d).toInt()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p / 2, p, p) }
        for (r in info.reviews) {
            list.addView(TextView(this).apply {
                text = "★".repeat(r.stars.coerceIn(0, 5)) + "☆".repeat((5 - r.stars).coerceIn(0, 5)) + "  ${r.author} · ${r.when_}"
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, p, 0, p / 4)
            })
            list.addView(TextView(this).apply { text = r.text; textSize = 14f })
        }
        AlertDialog.Builder(this)
            .setTitle(listOfNotNull(place.name, Reviews.stars(info.rating, info.count)).joinToString("  "))
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("Close", null)
            .setNeutralButton("All on Google Maps") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.mapsUrl ?: Reviews.mapsLink(place)))) } catch (_: Exception) {}
            }
            .show()
    }

    private fun renderPreview() {
        val opts = NavEngine.options
        val place = NavEngine.planPlace
        previewTitle.setTextColor(ink); previewTitle.typeface = theme.typeface
        previewSub.setTextColor(dim()); previewClose.setTextColor(ink); previewBack.setTextColor(ink)
        previewTitle.text = when {
            NavEngine.planError != null -> NavEngine.planError
            NavEngine.planLoading -> "Finding routes…"
            opts.size == 1 -> "1 route"
            else -> "${opts.size} routes"
        }
        val depart = NavEngine.departAt
        previewSub.text = listOfNotNull(
            place?.name,
            if (depart != null) "leaving ${NavEngine.clock(depart)}" else if (Traffic.active) "live traffic" else null,
        ).joinToString(" · ")
        previewList.removeAllViews()
        if (place != null) placeInfo(place)?.let { previewList.addView(it) }
        val fastest = NavEngine.fastestIndex()
        val p = (12 * d).toInt()
        opts.forEachIndexed { i, o ->
            val sel = i == NavEngine.selected
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(p, p - p / 4, p, p)
                isClickable = true
                setOnClickListener { NavEngine.select(i) }
                background = bg(
                    if (sel) Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)) else Color.TRANSPARENT,
                    if (sel) accent else dim(70), if (sel) 2f else 1f, 12f,
                )
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            top.addView(TextView(this).apply {
                text = if (NavEngine.timeKnown(o)) NavEngine.formatDuration(o.seconds) else "· · ·"
                textSize = if (sel) 22f else 19f; typeface = theme.typeface
                setTextColor(if (sel) readable(accent) else ink)
            })
            top.addView(TextView(this).apply {
                text = "  ${NavEngine.formatDistance(o.distance)}" + (if (NavEngine.timeKnown(o)) " · arrive ${NavEngine.optionArrival(o)}" else " · checking traffic")
                textSize = 14f; setTextColor(dim())
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val tag = when {
                NavEngine.breaksAvoid(o) -> if (o.hasToll && NavEngine.avoidTolls) "TOLLS" else "MOTORWAY"
                o.trafficSeconds != null && i == fastest && opts.size > 1 -> "FASTEST"
                o.delaySeconds >= 60 -> "+${o.delaySeconds / 60} min traffic"
                else -> null
            }
            if (tag != null) top.addView(TextView(this).apply {
                text = tag; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(readable(if (tag == "FASTEST") accent else Color.rgb(255, 120, 90)))
            })
            row.addView(top)
            if (o.via.isNotBlank()) row.addView(TextView(this).apply {
                text = "via ${o.via}"; textSize = 13f; setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(TrafficBar(this, o), LinearLayout.LayoutParams(MATCH_PARENT, (5 * d).toInt()).apply { topMargin = (7 * d).toInt() })
            previewList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        }
        preview.background = bg(panel, radius = 22f, topOnly = true)
        laterBtn.text = if (depart != null) "Leave now" else "Leave later"
        laterBtn.setTextColor(readable(accent))
        laterBtn.background = bg(Color.TRANSPARENT, accent, 1.5f, 14f)
        startBtn.isEnabled = opts.isNotEmpty()
        startBtn.alpha = if (opts.isNotEmpty()) 1f else 0.5f
        startBtn.setTextColor(onAccent(accent))
        startBtn.typeface = theme.typeface
        startBtn.background = bg(accent, radius = 14f)
    }

    // ------------------------------------------------------------------ faster route offer

    private fun buildOffer() {
        offerTitle = TextView(this).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; text = "⚡ Faster route found" }
        offerSub = TextView(this).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        offerVia = TextView(this).apply { textSize = 14f }
        keepBtn = pill("Keep current") { NavEngine.declineOffer() }
        switchBtn = pill("Switch") { NavEngine.acceptOffer() }
        offerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 12 * d
            visibility = View.GONE
            isClickable = true
            val p = (14 * d).toInt()
            setPadding(p + p / 4, p, p + p / 4, p)
            addView(offerTitle); addView(offerSub); addView(offerVia)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(keepBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = (10 * d).toInt() })
                addView(switchBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
        }
    }

    private fun renderOffer() {
        val o = NavEngine.offer ?: return
        val green = Color.rgb(61, 220, 132)
        offerCard.background = bg(panel, green, 2f, 18f)
        offerTitle.setTextColor(ink); offerTitle.typeface = theme.typeface
        val arrive = NavEngine.clock(System.currentTimeMillis() + (o.option.seconds * 1000).toLong())
        offerSub.text = "Saves ${(o.savingSeconds + 30) / 60} min · arrive $arrive"
        offerSub.setTextColor(readable(green))
        val first = o.option.steps.firstOrNull { it.type != "depart" }
        offerVia.text = listOfNotNull(
            o.option.via.takeIf { it.isNotBlank() }?.let { "Via $it" },
            first?.let { "Next: ${it.text}" },
        ).joinToString("\n")
        offerVia.setTextColor(dim(200))
        keepBtn.setTextColor(ink)
        keepBtn.background = bg(Color.TRANSPARENT, dim(200), 1.5f, 14f)
        val left = (NavEngine.OFFER_SECONDS - (System.currentTimeMillis() - o.createdAt) / 1000).coerceAtLeast(0)
        switchBtn.text = if (NavEngine.autoSwitch) "Switch · ${left}s" else "Switch"
        keepBtn.text = if (NavEngine.autoSwitch) "Keep current" else "Keep · ${left}s"
        switchBtn.setTextColor(Color.rgb(11, 36, 22))
        switchBtn.background = bg(green, radius = 14f)
    }

    // ------------------------------------------------------------------ arrival card

    private fun renderTrip(trip: Explorer.Trip) {
        val t = theme
        tripCard.removeAllViews()
        val p = (20 * d).toInt()
        tripCard.setPadding(p, p, p, p)
        tripCard.background = bg(panel, accent, 2f, 22f)
        tripCard.addView(label(Explorer.completeTitle(t).uppercase(), 13f, bold = true).apply {
            setTextColor(onAccent(accent)); letterSpacing = 0.12f; gravity = Gravity.CENTER
            val q = (8 * d).toInt(); setPadding(q * 2, q, q * 2, q)
            background = bg(accent, radius = 8f)
        })
        tripCard.addView(label(trip.place, 22f, bold = true).apply {
            setTextColor(ink); typeface = t.typeface; gravity = Gravity.CENTER; setPadding(0, (14 * d).toInt(), 0, 0)
        })
        tripCard.addView(label("Arrived ${NavEngine.clock(trip.arrivedAt)}", 14f).apply { setTextColor(dim()); gravity = Gravity.CENTER })
        tripCard.addView(View(this).apply { setBackgroundColor(dim(50)) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            topMargin = (14 * d).toInt(); bottomMargin = (10 * d).toInt()
        })
        val speed = if (MapState.imperial) "${(trip.avgKmh * 0.621).toInt()} mph" else "${trip.avgKmh} km/h"
        val stats = listOf(
            NavEngine.formatDuration(trip.seconds.toDouble()) to "journey",
            NavEngine.formatDistance(trip.meters) to "travelled",
            (if (trip.savedSeconds >= 60) "${trip.savedSeconds / 60} min" else speed) to (if (trip.savedSeconds >= 60) "saved dodging traffic" else "average speed"),
            "+${trip.xp} XP" to (if (trip.landmarks > 0) "${trip.landmarks} new landmark" + (if (trip.landmarks > 1) "s" else "") else "experience"),
        )
        for (rowIdx in 0..1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0..1) {
                val (big, small) = stats[rowIdx * 2 + k]
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                    addView(label(big, 24f, bold = true).apply { setTextColor(ink); typeface = t.typeface })
                    addView(label(small, 13f).apply { setTextColor(dim()) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            tripCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        // level bar
        val (have, of) = Explorer.progress()
        val up = Explorer.level > trip.levelBefore
        tripCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * d).toInt(), 0, (6 * d).toInt())
            addView(label(if (up) "Level up! Level ${Explorer.level}" else "Explorer · level ${Explorer.level}", 14f, bold = true).apply {
                setTextColor(if (up) readable(accent) else ink)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label("$have / $of XP", 14f).apply { setTextColor(dim()) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        tripCard.addView(object : View(this) {
            private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val r = height / 2f
                pt.color = dim(50); c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, pt)
                pt.color = accent; c.drawRoundRect(0f, 0f, width * (have.toFloat() / maxOf(of, 1)), height.toFloat(), r, r, pt)
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, (10 * d).toInt()))
        tripCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("🅿 Parking nearby") {
                NavEngine.dismissTrip(); showNearby("PARKING NEARBY", listOf("parking"))
            }.apply { setTextColor(ink); textSize = 15f; background = bg(Color.TRANSPARENT, dim(160), 1.5f, 14f) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.2f).apply { marginEnd = (10 * d).toInt() })
            addView(pill("Done") { NavEngine.dismissTrip() }.apply {
                setTextColor(onAccent(accent)); typeface = t.typeface; background = bg(accent, radius = 14f)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (18 * d).toInt() })
    }

    // ------------------------------------------------------------------ state → UI

    /** Colours everything to match the map theme and shows what fits the moment. */
    private var lastLookKey = ""
    private var tripLook = ""

    private fun refreshChrome() {
        val t = theme
        val lookKey = "${t.name}|${MapState.dark}"
        if (lookKey != lastLookKey) {
            val first = lastLookKey.isEmpty()
            lastLookKey = lookKey
            if (!first && suggestScroll.visibility == View.VISIBLE) { main.post { if (lastResults.isNotEmpty()) renderResults(lastResults, null) else showSuggestions() } }
        }
        val nav = NavEngine.active
        val planning = NavEngine.planning
        val searching = search.hasFocus() || suggestScroll.visibility == View.VISIBLE || pickFor != null

        searchCard.background = bg(panel, accent, 1.5f, 18f, pill = true)
        suggestScroll.background = bg(panel, radius = 18f)
        search.setTextColor(ink)
        search.setHintTextColor(dim(150))
        search.typeface = t.typeface
        searchIcon.setTextColor(readable(accent))
        searchIcon.text = if (searching) "←" else "⌕"
        searchIcon.contentDescription = if (searching) "Back" else "Search"
        clearBtn.setTextColor(ink)
        menuBtn.setTextColor(ink)
        menuBtn.background = bg(Color.argb(30, Color.red(ink), Color.green(ink), Color.blue(ink)), radius = 14f, pill = true)
        if (pickFor == null) search.hint = "Where to?"
        for (f in fabs) {
            f.setTextColor(ink)
            f.background = bg(panel, accent, 1.5f, 16f, pill = true)
        }
        navCard.background = bg(panel, radius = 22f, topOnly = true)
        etaSmall.setTextColor(ink)
        etaBig.typeface = t.typeface
        endBtn.setTextColor(Color.WHITE)
        endBtn.background = bg(Color.rgb(200, 40, 40), radius = 14f)
        shareBtn.setTextColor(ink)
        shareBtn.background = bg(Color.TRANSPARENT, accent, 1.5f, 14f)

        navCard.visibility = if (nav) View.VISIBLE else View.GONE
        preview.visibility = if (planning) View.VISIBLE else View.GONE
        searchCard.visibility = if (nav || planning) View.GONE else View.VISIBLE
        val chipsWas = chipsScroll.visibility; val homeWas = homeSheet.visibility
        chipsScroll.visibility = if (nav || planning || searching) View.GONE else View.VISIBLE
        homeSheet.visibility = if (nav || planning || searching || (Places.recents().isEmpty() && t != Theme.TRAILBLAZER)) View.GONE else View.VISIBLE
        if (chipsWas != chipsScroll.visibility || homeWas != homeSheet.visibility) lastHomeKey = ""
        if ((nav || planning) && suggestScroll.visibility == View.VISIBLE) closeSearch()
        recenterBtn.visibility = if (MapState.following && MapState.userBearing == null || planning) View.GONE else View.VISIBLE
        muteBtn.visibility = if (nav) View.VISIBLE else View.GONE
        muteBtn.text = if (Voice.muted) "🔇" else "🔊"
        overviewBtn.visibility = if (nav && !MapState.overview && NavEngine.route.size > 1) View.VISIBLE else View.GONE
        navMenuBtn.visibility = if (nav) View.VISIBLE else View.GONE
        reportBtn.visibility = if (planning) View.GONE else View.VISIBLE
        trafficBtn.visibility = if (Traffic.key.isEmpty()) View.GONE else View.VISIBLE
        trafficBtn.alpha = if (Traffic.showLayer) 1f else 0.45f
        val ask = Alerts.askAbout
        askCard.visibility = if (ask != null && !planning) View.VISIBLE else View.GONE
        if (ask != null && ask !== askShown) {
            askShown = ask
            val tp = Alerts.typeOf(ask.type)
            askText.text = "${tp.glyph}  ${tp.title} reported here: still there?"
        }
        if (ask != null) {
            askCard.background = bg(panel, Color.rgb(255, 150, 0), 2f, 18f)
            askText.setTextColor(ink)
            for (i in 0 until (askCard.getChildAt(1) as LinearLayout).childCount) {
                val b = (askCard.getChildAt(1) as LinearLayout).getChildAt(i) as TextView
                b.setTextColor(if (i == 1) Color.BLACK else ink)
                b.background = if (i == 1) bg(Color.rgb(255, 170, 40), radius = 14f) else bg(Color.TRANSPARENT, dim(160), 1.5f, 14f)
            }
        }
        controls.visibility = if (planning || searching) View.GONE else View.VISIBLE
        val homeKey = "${t.name}|${MapState.dark}|${MapState.imperial}|$etaVersion|${Explorer.version}|${Explorer.xp}|${Places.recents().joinToString { it.name }}|" +
            "${Places.saved("home")?.name}|${Places.saved("work")?.name}|${LocationTracker.position != null}"
        if (homeKey != lastHomeKey) {
            lastHomeKey = homeKey
            if (chipsScroll.visibility == View.VISIBLE) renderChips()
            if (homeSheet.visibility == View.VISIBLE) renderHomeSheet()
        }
        if (planning) {
            val o = NavEngine.options
            val key = "${t.name}|${MapState.dark}|${System.identityHashCode(o)}|${NavEngine.selected}|${o.sumOf { it.trafficSeconds ?: 0 }}|${o.count { it.timingFailed }}|" +
                "${NavEngine.departAt}|${NavEngine.planLoading}|${NavEngine.planError}"
            if (key != lastPreviewKey) { lastPreviewKey = key; renderPreview() }
        } else lastPreviewKey = ""
        val showOffer = nav && NavEngine.offer != null
        if (showOffer && offerCard.visibility != View.VISIBLE) { main.removeCallbacks(ticker); main.postDelayed(ticker, 1000) }
        offerCard.visibility = if (showOffer) View.VISIBLE else View.GONE
        if (showOffer) renderOffer()
        if (sheetOverlay.visibility == View.VISIBLE) when (sheetKind) {
            "style" -> renderStylePicker()
            "settings" -> renderSettings()
            "report" -> renderReport()
        }
        val trip = NavEngine.lastTrip
        tripOverlay.visibility = if (trip != null) View.VISIBLE else View.GONE
        if (trip != null && (trip !== shownTrip || lookKey != tripLook)) { shownTrip = trip; tripLook = lookKey; renderTrip(trip) }
        if (trip == null) shownTrip = null
        if (nav) {
            etaTraffic.text = ""
            when {
                NavEngine.arrived -> { etaBig.text = "Mission complete"; etaBig.setTextColor(ink); etaSmall.text = NavEngine.destinationName }
                NavEngine.loading || NavEngine.error != null -> {
                    etaBig.text = NavEngine.error ?: "Plotting route…"; etaBig.setTextColor(ink); etaSmall.text = NavEngine.destinationName
                }
                else -> {
                    val delay = NavEngine.trafficDelaySeconds / 60
                    etaBig.text = NavEngine.arrivalClock()
                    etaBig.setTextColor(readable(when {
                        delay >= 10 -> Color.rgb(255, 110, 110)
                        delay >= 3 -> Color.rgb(255, 184, 107)
                        else -> Color.rgb(123, 227, 160)
                    }))
                    etaTraffic.text = if (delay >= 1) "+$delay min traffic" else ""
                    etaTraffic.setTextColor(readable(Color.rgb(255, 140, 90)))
                    etaSmall.text = "${NavEngine.formatDuration(NavEngine.remainingSeconds)} · ${NavEngine.formatDistance(NavEngine.remainingDistance)} · ${NavEngine.destinationName}"
                }
            }
        }
        updateMapInsets()
    }

    private fun updateMapInsets() {
        val top = when {
            offerCard.visibility == View.VISIBLE -> offerCard.bottom
            searchCard.visibility == View.VISIBLE -> topArea.bottom.takeIf { suggestScroll.visibility != View.VISIBLE }
                ?: (searchCard.bottom + topArea.paddingTop)
            else -> insets.top
        }
        val bottom = when {
            preview.visibility == View.VISIBLE -> preview.height
            navCard.visibility == View.VISIBLE -> navCard.height
            homeSheet.visibility == View.VISIBLE -> homeSheet.height
            else -> insets.bottom
        }
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
        val seq = ++querySeq
        lastResults = emptyList()
        val rec = Places.recents()
        renderResults(rec, if (rec.isEmpty()) "Type a place, address or business" else null, header = if (rec.isEmpty()) null else "RECENT", force = true)
        val near = LocationTracker.position ?: return
        Places.warmUp(near, force = true)
        io.execute {
            val around = try { Places.around(near) } catch (e: Exception) { emptyList() }
            if (around.isNotEmpty()) runOnUiThread {
                if (seq != querySeq || isFinishing) return@runOnUiThread
                renderSections(listOf("RECENT" to rec.take(4), "AROUND YOU" to around))
            }
        }
    }

    /** Several titled lists in the suggestions box. */
    private fun renderSections(sections: List<Pair<String, List<Place>>>) {
        val p = (12 * d).toInt()
        suggestList.removeAllViews()
        for ((title, places) in sections) {
            if (places.isEmpty()) continue
            suggestList.addView(TextView(this).apply {
                text = title; textSize = 12f; setTextColor(dim(170)); letterSpacing = 0.1f
                setPadding(p + p / 2, p, p, p / 3)
            })
            for (pl in places) suggestList.addView(resultRow(pl))
        }
        suggestScroll.visibility = View.VISIBLE
    }

    private fun runQuery(q: String, full: Boolean) {
        if (q.trim().length < 2) return
        val seq = ++querySeq
        val near = LocationTracker.position
        if (full) hideKeyboard()
        io.execute {
            // 1) instant: names in the map data on the phone
            val local = try { Places.local(q, near) } catch (e: Exception) { emptyList<Place>() }
            if (local.isNotEmpty()) runOnUiThread {
                if (seq == querySeq && !isFinishing) { lastResults = local; renderResults(local, null, header = "NEARBY") }
            }
            // 2) the exact address and the search services, merged with the local names
            val r = Places.find(q, near, full, local)
            runOnUiThread {
                if (seq != querySeq || isFinishing) return@runOnUiThread
                val merged = r.orEmpty()
                lastResults = merged
                renderResults(merged, when {
                    merged.isNotEmpty() -> null
                    r == null -> "No connection: check mobile data"
                    else -> "Nothing found"
                })
            }
        }
    }

    private fun renderResults(places: List<Place>, message: String?, header: String? = null, force: Boolean = false) {
        if (!force && !search.hasFocus() && search.text.isEmpty() && places.isEmpty() && message == null) return
        val p = (12 * d).toInt()
        suggestList.removeAllViews()
        (header ?: pickFor?.let { if (it == "home") "SET HOME" else "SET WORK" })?.let {
            suggestList.addView(TextView(this).apply {
                text = it; textSize = 12f; setTextColor(dim(170)); letterSpacing = 0.1f
                setPadding(p + p / 2, p, p, p / 3)
            })
        }
        message?.let {
            suggestList.addView(TextView(this).apply { text = it; textSize = 15f; setTextColor(dim(170)); setPadding(p + p / 2, p, p, p) })
        }
        val near = LocationTracker.position
        for (pl in places) suggestList.addView(resultRow(pl))
        suggestScroll.visibility = View.VISIBLE
        refreshChrome()
        // keep the list from covering the whole screen
        suggestScroll.post {
            val max = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            val lp = suggestScroll.layoutParams
            val want = if (suggestList.height > max) max else WRAP_CONTENT
            if (lp.height != want) { lp.height = want; suggestScroll.layoutParams = lp }
        }
    }

    private fun resultRow(pl: Place): View {
        val p = (12 * d).toInt()
        val near = LocationTracker.position
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
                text = pl.address; textSize = 13f; setTextColor(dim(170))
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            val stars = Reviews.stars(pl.rating, pl.ratings)
            if (pl.extra.isNotBlank() || stars != null) addView(TextView(context).apply {
                text = listOfNotNull(stars, pl.extra.takeIf { it.isNotBlank() }).joinToString("  ")
                textSize = 13f; setTextColor(readable(accent)); typeface = Typeface.DEFAULT_BOLD
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (near != null) row.addView(TextView(this).apply {
            text = NavEngine.formatDistance(Geo.distance(near, pl.pos)); textSize = 13f; setTextColor(dim(170))
        })
        return row
    }

    private fun choose(pl: Place) {
        val key = pickFor
        closeSearch()
        search.setText("")
        if (key != null) {
            Places.save(key, pl)
            Toast.makeText(this, (if (key == "home") "Home" else "Work") + " saved: ${pl.name}", Toast.LENGTH_SHORT).show()
            etaAskedAt = 0
            refreshChrome()
        } else {
            NavEngine.plan(this, pl)
        }
    }

    private fun closeSearch() {
        querySeq++
        pickFor = null
        suggestScroll.visibility = View.GONE
        search.clearFocus()
        hideKeyboard()
        refreshChrome()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ------------------------------------------------------------------ bottom sheets

    private fun openSheet(kind: String) {
        sheetKind = kind
        sheetOverlay.visibility = View.VISIBLE
        refreshChrome()
    }

    private fun closeSheet() {
        sheetOverlay.visibility = View.GONE
        sheetKind = ""
        sheetHost.removeAllViews()
    }

    private fun sheetHeader(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, 22f, bold = true).apply { setTextColor(ink); typeface = theme.typeface }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(pill("✕") { closeSheet() }.apply { setTextColor(ink) })
    }

    internal fun showStylePicker() = openSheet("style")

    private fun renderStylePicker() {
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 24f, topOnly = true)
        sheetHost.addView(sheetHeader("Map style"))
        // Auto / Light / Dark
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = (4 * d).toInt(); setPadding(p, p, p, p)
            background = bg(Color.argb(30, Color.red(ink), Color.green(ink), Color.blue(ink)), radius = 14f)
        }
        for (m in MapState.LightMode.entries) {
            val sel = MapState.lightMode == m
            seg.addView(TextView(this).apply {
                text = when (m) { MapState.LightMode.AUTO -> "Auto"; MapState.LightMode.LIGHT -> "Light"; MapState.LightMode.DARK -> "Dark" }
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(if (sel) onAccent(accent) else ink)
                if (sel) background = bg(accent, radius = 11f)
                setOnClickListener { MapState.setLightMode(m) }
            }, LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f))
        }
        sheetHost.addView(seg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        if (MapState.lightMode == MapState.LightMode.AUTO) sheetHost.addView(label("Switches at sunrise and sunset", 13f).apply {
            setTextColor(dim()); setPadding((4 * d).toInt(), (6 * d).toInt(), 0, 0)
        })
        // theme grid, 3 per row
        val dark = MapState.dark
        var row: LinearLayout? = null
        Theme.entries.forEachIndexed { i, th ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                sheetHost.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
            }
            val sel = th == MapState.theme
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (3 * d).toInt(); setPadding(p, p, p, p)
                background = bg(Color.argb(24, Color.red(ink), Color.green(ink), Color.blue(ink)), if (sel) accent else dim(50), if (sel) 3f else 1f, 14f)
                setOnClickListener { MapState.setTheme(th) }
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                val id = resources.getIdentifier("thumb_${th.name.lowercase()}_${if (dark) "d" else "l"}", "drawable", packageName)
                if (id != 0) setImageResource(id) else setBackgroundColor(th.lookFor(dark).style.land)
                clipToOutline = true
            }
            card.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, (88 * d).toInt()))
            card.addView(label(th.title, 13f, bold = true).apply {
                setTextColor(ink); setPadding((6 * d).toInt(), (6 * d).toInt(), 0, (4 * d).toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            row?.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (i % 3 != 2) marginEnd = (8 * d).toInt()
            })
        }
        // fill an incomplete last row so cards keep their width
        val rest = (3 - Theme.entries.size % 3) % 3
        repeat(rest) { row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        // car style
        sheetHost.addView(settingRow("Android Auto style", MapState.carTheme?.title ?: "Same as phone") { chooseCarStyle() },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
    }

    private fun chooseCarStyle() {
        val names = arrayOf("Same as phone") + Theme.entries.map { it.title }.toTypedArray()
        val cur = MapState.carTheme?.let { it.ordinal + 1 } ?: 0
        AlertDialog.Builder(this).setTitle("Android Auto style")
            .setSingleChoiceItems(names, cur) { dlg, i ->
                MapState.setCarTheme(if (i == 0) null else Theme.entries[i - 1]); dlg.dismiss()
            }.show()
    }

    private fun settingRow(title: String, value: String?, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (52 * d).toInt()
        val p = (14 * d).toInt(); setPadding(p, 0, p, 0)
        setOnClickListener { onClick() }
        addView(label(title, 15f).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (value != null) addView(label("$value  ›", 14f).apply { setTextColor(dim()) })
    }

    private fun switchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (52 * d).toInt()
        val p = (14 * d).toInt(); setPadding(p, 0, p / 2, 0)
        addView(label(title, 15f).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val sw = android.widget.Switch(context).apply {
            isChecked = on
            val onCol = Ui.onPanel(theme, accent, 3.0)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            // off = plain grey, on = the theme's colour: easy to tell apart in every theme
            val lightP = Ui.luminance(panel) > 0.4
            val offThumb = if (lightP) Color.rgb(250, 250, 250) else Color.rgb(185, 185, 185)
            val offTrack = if (lightP) Color.argb(120, 0, 0, 0) else Color.argb(110, 255, 255, 255)
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(onCol, offThumb))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(
                Color.argb(150, Color.red(onCol), Color.green(onCol), Color.blue(onCol)), offTrack))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        addView(sw)
        setOnClickListener { sw.toggle() }
    }

    private fun group(title: String, vararg rows: View): List<View> {
        val head = label(title.uppercase(), 12f, bold = true).apply {
            setTextColor(dim(170)); letterSpacing = 0.08f; setPadding((6 * d).toInt(), (14 * d).toInt(), 0, (6 * d).toInt())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Color.argb(26, Color.red(ink), Color.green(ink), Color.blue(ink)), radius = 14f)
            rows.forEachIndexed { i, r ->
                if (i > 0) addView(View(context).apply { setBackgroundColor(dim(40)) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
                addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }
        return listOf(head, box)
    }

    private fun renderReport() {
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 24f, topOnly = true)
        sheetHost.addView(sheetHeader("Report"))
        if (!Alerts.reportsReady) {
            sheetHost.addView(label("Reports are shared with other GameMap drivers through a small free online database, which isn't set up yet.", 15f).apply {
                setTextColor(ink); setPadding((4 * d).toInt(), (6 * d).toInt(), 0, (12 * d).toInt())
            })
            sheetHost.addView(pill("Set up reports") { reportsSetup() }.apply {
                setTextColor(onAccent(accent)); background = bg(accent, radius = 14f)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        if (LocationTracker.position == null) {
            sheetHost.addView(label("Waiting for your location…", 15f).apply { setTextColor(dim()) })
        }
        var row: LinearLayout? = null
        Alerts.reportTypes.forEachIndexed { i, rt ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                sheetHost.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = (96 * d).toInt()
                background = bg(Color.argb(28, Color.red(ink), Color.green(ink), Color.blue(ink)), dim(60), 1f, 16f)
                setOnClickListener {
                    Alerts.report(rt.key) { ok ->
                        Toast.makeText(this@MainActivity, if (ok) "Thanks! ${rt.title} reported" else "Couldn't send the report: check internet", Toast.LENGTH_SHORT).show()
                    }
                    closeSheet()
                }
            }
            tile.addView(label(rt.glyph, 30f).apply { gravity = Gravity.CENTER })
            tile.addView(label(rt.title, 14f, bold = true).apply { setTextColor(ink); gravity = Gravity.CENTER })
            row?.addView(tile, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i % 3 != 2) marginEnd = (10 * d).toInt() })
        }
        sheetHost.addView(label("Reports show for other drivers for an hour, longer if they confirm them.", 13f).apply {
            setTextColor(dim()); setPadding((4 * d).toInt(), (12 * d).toInt(), 0, 0)
        })
    }

    private fun reportsSetup() {
        val input = EditText(this).apply {
            hint = "https://…firebasedatabase.app"
            setText(Alerts.dbUrl)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Driver reports")
            .setMessage("Paste the address of your Firebase Realtime Database (it ends in firebasedatabase.app). " +
                "Everyone using the same address sees each other's reports.")
            .setView(LinearLayout(this).apply {
                val p = (20 * d).toInt(); setPadding(p, p / 2, p, 0); addView(input)
            })
            .setPositiveButton("Save") { _, _ -> Alerts.setDbUrl(input.text.toString()); refreshChrome() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    internal fun showSettings() = openSheet("settings")
    internal fun openReportForTest() = openSheet("report")

    private fun renderSettings() {
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 24f, topOnly = true)
        sheetHost.addView(sheetHeader("Settings"))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val voice = if (Voice.muted) "Off" else if (Voice.lang == Phrases.GREEK) "Ελληνικά" else "English"
        (group("Driving",
            settingRow("Spoken directions", voice) { chooseVoice() },
            settingRow("Faster routes", if (NavEngine.autoSwitch) "Switch automatically" else "Ask me") { chooseAutoSwitch() },
            switchRow("Avoid tolls", NavEngine.avoidTolls) { NavEngine.avoidTolls = it },
            switchRow("Avoid motorways", NavEngine.avoidMotorways) { NavEngine.avoidMotorways = it },
        ) + group("Traffic",
            switchRow("Live traffic", Traffic.enabled) { Traffic.setEnabled(it) },
            settingRow("TomTom key", if (Traffic.builtIn) "Built-in" else if (Traffic.key.isNotEmpty()) "Your own" else "None") { trafficSettings() },
        ) + group("Alerts",
            switchRow("Speed cameras", Alerts.camerasOn) { Alerts.camerasOn = it },
            switchRow("Red light cameras", Alerts.redLightOn) { Alerts.redLightOn = it },
            switchRow("Accidents & roadworks", Alerts.incidentsOn) { Alerts.incidentsOn = it },
            settingRow("Driver reports", if (Alerts.reportsReady) "On" else "Set up") { reportsSetup() },
        ) + group("Map",
            settingRow("Light / dark", MapState.lightMode.label) { chooseLightMode() },
            settingRow("Units", if (MapState.imperial) "Miles" else "Kilometres") { MapState.setImperial(!MapState.imperial) },
            settingRow("Android Auto style", MapState.carTheme?.title ?: "Same as phone") { chooseCarStyle() },
            switchRow("Car: dark map when headlights are on", MapState.followHeadlights) { MapState.followHeadlights = it },
        ) + group("Places",
            settingRow("Star ratings & reviews", if (Reviews.enabled) "Google key set" else "Off: Reviews opens Google Maps") { chooseGoogleKey() },
        ) + group("App",
            settingRow("Explorer profile", "Level ${Explorer.level} · ${Explorer.xp} XP") { showProfile() },
            settingRow("Check for updates", "build ${Updater.currentVersion(this)}") { checkForUpdates(manual = true) },
            settingRow("About & map credits", null) { showAbout() },
        )).forEach { body.addView(it) }
        sheetHost.addView(ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(MATCH_PARENT, minOf((resources.displayMetrics.heightPixels * 0.72f).toInt(), (640 * d).toInt())))
    }

    private fun chooseGoogleKey() {
        val p = (20 * d).toInt()
        val input = android.widget.EditText(this).apply {
            setText(Reviews.key); hint = "Google Maps Platform API key"; setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(p, p / 2, p, 0)
            addView(TextView(context).apply {
                textSize = 14f
                text = "Stars and reviews come from Google. Paste a Google Maps Platform key with " +
                    "\"Places API (New)\" turned on (console.cloud.google.com). Google gives a free monthly " +
                    "allowance; a personal app normally stays inside it.\n\nWithout a key, \"Reviews\" opens the " +
                    "place in Google Maps."
            })
            addView(input)
        }
        AlertDialog.Builder(this).setTitle("Star ratings & reviews").setView(box)
            .setPositiveButton("Save") { _, _ -> Reviews.key = input.text.toString(); showSettings() }
            .setNeutralButton("Remove") { _, _ -> Reviews.key = ""; showSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseVoice() {
        val opts = arrayOf("English", "Ελληνικά", "Off")
        val cur = if (Voice.muted) 2 else if (Voice.lang == Phrases.GREEK) 1 else 0
        AlertDialog.Builder(this).setTitle("Spoken directions")
            .setSingleChoiceItems(opts, cur) { dlg, k ->
                when (k) {
                    0 -> { Voice.lang = Phrases.ENGLISH; Voice.muted = false }
                    1 -> { Voice.lang = Phrases.GREEK; Voice.muted = false }
                    else -> Voice.muted = true
                }
                dlg.dismiss()
                if (k < 2) Voice.say(if (k == 1) "Οι οδηγίες θα ακούγονται στα ελληνικά" else "Directions will be spoken in English")
            }.show()
    }

    private fun chooseAutoSwitch() {
        val opts = arrayOf("Switch automatically", "Ask me (keep my route)")
        AlertDialog.Builder(this).setTitle("When a faster route is found")
            .setSingleChoiceItems(opts, if (NavEngine.autoSwitch) 0 else 1) { dlg, k ->
                NavEngine.autoSwitch = k == 0; dlg.dismiss(); refreshChrome()
            }.show()
    }

    private fun chooseLightMode() {
        val modes = MapState.LightMode.entries
        AlertDialog.Builder(this)
            .setTitle("Light / dark mode")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), MapState.lightMode.ordinal) { dlg, m ->
                MapState.setLightMode(modes[m]); dlg.dismiss()
            }.show()
    }

    private fun showProfile() {
        val (have, of) = Explorer.progress()
        AlertDialog.Builder(this)
            .setTitle("Explorer · level ${Explorer.level}")
            .setMessage("${Explorer.xp} XP in total ($have / $of to the next level)\n" +
                "${Explorer.foundCount} landmarks discovered\n\n" +
                "Every trip earns XP (more for longer drives and for taking faster routes). " +
                "Pass close to museums, monuments, viewpoints and other landmarks to discover them: " +
                "+25 XP each. In the Trailblazer style they show on the map as blue ? markers.")
            .setPositiveButton("OK", null).show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("GameMap build ${Updater.currentVersion(this)}")
            .setMessage("Map data and speed cameras © OpenStreetMap contributors\nVector tiles: OpenFreeMap\nTraffic, incidents, speed limits: TomTom\nRouting: OSRM\nSearch: Photon (komoot), Nominatim\nVoice: your phone's text-to-speech")
            .setPositiveButton("OK", null).show()
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
        if (LocationTracker.hasPermission(this)) LocationTracker.start(this)
        NavEngine.changed += stateListener
        MapState.changed += stateListener
        Voice.changed += stateListener
        Explorer.changed += stateListener
        Alerts.changed += stateListener
        refreshChrome()
    }

    override fun onStop() {
        NavEngine.changed -= stateListener
        MapState.changed -= stateListener
        Voice.changed -= stateListener
        Explorer.changed -= stateListener
        Alerts.changed -= stateListener
        main.removeCallbacks(ticker)
        // in the background and not navigating: let the GPS rest
        LocationTracker.release(this, "phone")
        if (!LocationTracker.inUse && !NavEngine.active) Voice.shutdown()
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
    /** Called when a finger first touches the map (closes search). */
    var onTouchStart: (() -> Unit)? = null
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

    private var touching = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        touching = e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL
        if (MapState.gesture != (touching && (dragging || scaling || rotating))) { MapState.gesture = touching && (dragging || scaling || rotating); if (!MapState.gesture) invalidate() }
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTouchStart?.invoke()
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
        // smooth 60 fps under the fingers, 30 fps for the gliding arrow and zoom animations
        if (renderer.animating) postInvalidateOnAnimation()
    }
}

/** A thin bar showing where along a route the traffic is. */
class TrafficBar(ctx: Context, private val o: RouteOption) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = h / 2
        p.color = if (o.trafficSeconds != null) Color.rgb(47, 210, 122) else Color.argb(90, 150, 150, 150)
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
        val len = o.length.takeIf { it > 0 } ?: return
        for (s in o.traffic) {
            p.color = when {
                s.value >= 4 -> Color.rgb(140, 20, 30)
                s.value == 3 -> Color.rgb(230, 50, 60)
                s.value == 2 -> Color.rgb(255, 140, 40)
                else -> Color.rgb(255, 200, 60)
            }
            val a = (s.start / len * w).toFloat(); val b = maxOf((s.end / len * w).toFloat(), a + h)
            c.drawRect(a, 0f, b.coerceAtMost(w), h, p)
        }
    }
}
