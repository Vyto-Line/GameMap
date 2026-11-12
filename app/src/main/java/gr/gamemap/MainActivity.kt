package gr.gamemap

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

/** Phone screen: the same game map, plus search and controls. */
class MainActivity : ComponentActivity() {

    private lateinit var map: GameMapView
    private lateinit var search: EditText
    private lateinit var stopBtn: Button
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val navListener: () -> Unit = { stopBtn.visibility = if (NavEngine.active) View.VISIBLE else View.GONE }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LocationTracker.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        val pad = (10 * d).toInt()

        map = GameMapView(this)

        search = EditText(this).apply {
            hint = "Where to?"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = panel(d)
            setPadding(pad, pad, pad, pad)
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    runSearch(); true
                } else false
            }
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad * 3, pad, pad)
            addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("GO", d) { runSearch() })
        }

        stopBtn = button("■ STOP", d) { NavEngine.stop() }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(pad, pad, pad, pad * 2)
            addView(button("🎮 THEME", d) {
                val t = MapState.nextTheme()
                Toast.makeText(this@MainActivity, t.title, Toast.LENGTH_SHORT).show()
            })
            addView(button("−", d) { MapState.zoomBy(-1.0) })
            addView(button("+", d) { MapState.zoomBy(1.0) })
            addView(button("🔑 KEY", d) { askForKey() })
            addView(stopBtn)
        }

        setContentView(FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(bottomBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
        // Keep the map's HUD clear of the search bar and buttons.
        topBar.viewTreeObserver.addOnGlobalLayoutListener { updateInsets() }

        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (LocationTracker.hasPermission(this)) LocationTracker.start(this)
        permissions.launch(wanted.toTypedArray())
        if (MapState.mapKey.isEmpty()) askForKey()
    }

    private fun askForKey() {
        val input = EditText(this).apply {
            hint = "Paste your CARTO basemaps key"
            setText(MapState.mapKey)
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Map key")
            .setMessage(
                "The map tiles come from CARTO, which needs a free key (no account, up to " +
                    "5 million tiles a month). Tap GET FREE KEY, fill in the short form, " +
                    "then paste the key here."
            )
            .setView(box)
            .setPositiveButton("Save") { _, _ -> MapState.setMapKey(input.text.toString()) }
            .setNeutralButton("Get free key") { _, _ ->
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://carto.com/basemaps/apikey/")))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateInsets() {
        if (map.insetTop == topBar.bottom && map.insetBottom == bottomBar.height) return
        map.insetTop = topBar.bottom
        map.insetBottom = bottomBar.height
        map.invalidate()
    }

    override fun onStart() {
        super.onStart()
        NavEngine.changed += navListener
        navListener()
    }

    override fun onStop() {
        NavEngine.changed -= navListener
        super.onStop()
    }

    private fun runSearch() {
        val q = search.text.toString().trim()
        if (q.isEmpty()) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        val near = LocationTracker.position
        io.execute {
            val result = try { Places.search(q, near) } catch (e: Exception) { null }
            runOnUiThread {
                when {
                    result == null -> Toast.makeText(this, "Search failed: check internet", Toast.LENGTH_LONG).show()
                    result.isEmpty() -> Toast.makeText(this, "Nothing found", Toast.LENGTH_SHORT).show()
                    else -> AlertDialog.Builder(this)
                        .setTitle("Choose destination")
                        .setItems(result.map { p ->
                            val dist = near?.let { " · " + NavEngine.formatDistance(Geo.distance(it, p.pos)) } ?: ""
                            "${p.name}$dist\n${p.address.take(70)}"
                        }.toTypedArray()) { _, i ->
                            val p = result[i]
                            NavEngine.start(this, p.pos, p.name)
                            search.setText("")
                        }
                        .show()
                }
            }
        }
    }

    private fun panel(d: Float) = GradientDrawable().apply {
        setColor(Color.argb(200, 10, 10, 16))
        cornerRadius = 12 * d
        setStroke((1.5f * d).toInt(), Color.argb(180, 177, 77, 255))
    }

    private fun button(label: String, d: Float, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 16f
        background = panel(d)
        minWidth = (52 * d).toInt()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = (6 * d).toInt()
        }
    }
}

/** The phone's map view: redraws whenever tiles, position, route or theme change. */
class GameMapView(ctx: Context) : View(ctx) {
    private val renderer = MapRenderer()
    private val area = Rect()
    var insetTop = 0
    var insetBottom = 0
    private val redraw: () -> Unit = { invalidate() }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        area.set(0, insetTop, width, height - insetBottom)
        renderer.draw(canvas, width, height, area, resources.displayMetrics.density, hud = true)
    }
}
