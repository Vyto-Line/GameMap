package gr.gamemap.car

import android.Manifest
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.validation.HostValidator
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import gr.gamemap.LocationTracker
import gr.gamemap.MapRenderer
import gr.gamemap.MapState
import gr.gamemap.NavEngine
import gr.gamemap.Place
import gr.gamemap.Places
import gr.gamemap.R
import gr.gamemap.TileCache
import java.util.TimeZone
import java.util.concurrent.Executors

class GameMapCarAppService : CarAppService() {
    // Sideloaded app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = GameMapSession()
}

class GameMapSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = MapScreen(carContext)
}

fun icon(ctx: CarContext, res: Int) = CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()

/** Draws the game map onto the car's screen surface. */
class CarMapSurface(private val ctx: CarContext) : SurfaceCallback {
    private val renderer = MapRenderer()
    private val main = Handler(Looper.getMainLooper())
    private var surface: Surface? = null
    private var w = 0
    private var h = 0
    private var density = 1f
    private val visible = Rect()
    private val draw = Runnable { render() }
    val redraw: () -> Unit = { main.removeCallbacks(draw); main.post(draw) }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surface = container.surface
        w = container.width; h = container.height
        density = (container.dpi / 160f).coerceAtLeast(0.75f)
        visible.set(0, 0, w, h)
        redraw()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible.set(visibleArea)
        redraw()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        surface = null
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        MapState.zoomByFactor(scaleFactor)
    }

    private fun render() {
        val s = surface ?: return
        if (!s.isValid || w == 0) return
        val c = try { s.lockCanvas(null) } catch (e: Exception) { return }
        try {
            renderer.draw(c, w, h, if (visible.isEmpty) Rect(0, 0, w, h) else visible, density, hud = false)
        } finally {
            s.unlockCanvasAndPost(c)
        }
    }
}

class MapScreen(carContext: CarContext) : Screen(carContext) {
    private val surface = CarMapSurface(carContext)
    private val nav = carContext.getCarService(NavigationManager::class.java)
    private var navigating = false
    private var lastInvalidate = 0L
    private val main = Handler(Looper.getMainLooper())
    private val refresh = Runnable { lastInvalidate = System.currentTimeMillis(); invalidate() }

    /** Template updates are throttled to once a second. */
    private val onNav: () -> Unit = {
        syncNavigationState()
        main.removeCallbacks(refresh)
        val wait = 1000 - (System.currentTimeMillis() - lastInvalidate)
        if (wait <= 0) refresh.run() else main.postDelayed(refresh, wait)
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface)
        nav.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = NavEngine.stop()
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                TileCache.loaded += surface.redraw
                LocationTracker.changed += surface.redraw
                MapState.changed += surface.redraw
                NavEngine.changed += surface.redraw
                NavEngine.changed += onNav
                ensureLocation()
                if (gr.gamemap.Updater.dueForCheck(carContext)) Executors.newSingleThreadExecutor().execute {
                    val r = try { gr.gamemap.Updater.check(carContext) } catch (e: Exception) { null }
                    if (r != null) main.post {
                        CarToast.makeText(carContext, "GameMap update ready: open the app on your phone", CarToast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                TileCache.loaded -= surface.redraw
                LocationTracker.changed -= surface.redraw
                MapState.changed -= surface.redraw
                NavEngine.changed -= surface.redraw
                NavEngine.changed -= onNav
                if (navigating) nav.navigationEnded()
            }
        })
    }

    private fun ensureLocation() {
        if (LocationTracker.hasPermission(carContext)) {
            LocationTracker.start(carContext)
        } else {
            carContext.requestPermissions(
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            ) { granted, _ ->
                if (granted.isNotEmpty()) LocationTracker.start(carContext)
            }
        }
    }

    private fun syncNavigationState() {
        if (NavEngine.active && !navigating) {
            navigating = true
            try { nav.navigationStarted() } catch (_: Exception) {}
        } else if (!NavEngine.active && navigating) {
            navigating = false
            try { nav.navigationEnded() } catch (_: Exception) {}
        }
    }

    private fun action(res: Int, onClick: () -> Unit) =
        Action.Builder().setIcon(icon(carContext, res)).setOnClickListener(onClick).build()

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(action(R.drawable.ic_search) { screenManager.push(SearchScreen(carContext)) })
            .addAction(action(R.drawable.ic_theme) {
                val t = MapState.nextTheme()
                CarToast.makeText(carContext, t.title, CarToast.LENGTH_SHORT).show()
            })
        if (NavEngine.active) strip.addAction(action(R.drawable.ic_stop) { NavEngine.stop() })

        val b = NavigationTemplate.Builder().setActionStrip(strip.build())
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            b.setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(action(R.drawable.ic_zoom_in) { MapState.zoomBy(1.0) })
                    .addAction(action(R.drawable.ic_zoom_out) { MapState.zoomBy(-1.0) })
                    .build()
            )
        }

        if (NavEngine.active) {
            val step = NavEngine.currentStep
            when {
                NavEngine.arrived -> b.setNavigationInfo(
                    MessageInfo.Builder("Mission complete").setText(NavEngine.destinationName)
                        .setImage(icon(carContext, R.drawable.ic_flag)).build()
                )
                NavEngine.error != null -> b.setNavigationInfo(
                    MessageInfo.Builder(NavEngine.error!!).setText(NavEngine.destinationName).build()
                )
                NavEngine.loading || step == null -> b.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
                else -> {
                    b.setNavigationInfo(
                        RoutingInfo.Builder()
                            .setCurrentStep(toCarStep(step), distance(NavEngine.distanceToNext))
                            .build()
                    )
                    val arrival = System.currentTimeMillis() + (NavEngine.remainingSeconds * 1000).toLong()
                    b.setDestinationTravelEstimate(
                        TravelEstimate.Builder(
                            distance(NavEngine.remainingDistance),
                            DateTimeWithZone.create(arrival, TimeZone.getDefault())
                        ).setRemainingTimeSeconds(NavEngine.remainingSeconds.toLong().coerceAtLeast(0)).build()
                    )
                }
            }
        }
        return b.build()
    }

    private fun distance(m: Double): Distance = when {
        m >= 10_000 -> Distance.create((m / 1000).toInt().toDouble(), Distance.UNIT_KILOMETERS)
        m >= 1000 -> Distance.create(Math.round(m / 100) / 10.0, Distance.UNIT_KILOMETERS_P1)
        m >= 100 -> Distance.create(((m / 50).toInt() * 50).toDouble(), Distance.UNIT_METERS)
        else -> Distance.create(((m / 10).toInt() * 10).toDouble(), Distance.UNIT_METERS)
    }

    private fun toCarStep(s: NavEngine.Step): Step {
        val (type, res) = when {
            s.direction == "arrive" -> Maneuver.TYPE_DESTINATION to R.drawable.ic_flag
            s.direction == "uturn" -> Maneuver.TYPE_U_TURN_LEFT to R.drawable.ic_uturn
            s.direction == "sharp left" -> Maneuver.TYPE_TURN_SHARP_LEFT to R.drawable.ic_turn_left
            s.direction == "slight left" -> Maneuver.TYPE_TURN_SLIGHT_LEFT to R.drawable.ic_turn_left
            s.direction.contains("left") -> Maneuver.TYPE_TURN_NORMAL_LEFT to R.drawable.ic_turn_left
            s.direction == "sharp right" -> Maneuver.TYPE_TURN_SHARP_RIGHT to R.drawable.ic_turn_right
            s.direction == "slight right" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT to R.drawable.ic_turn_right
            s.direction.contains("right") -> Maneuver.TYPE_TURN_NORMAL_RIGHT to R.drawable.ic_turn_right
            else -> Maneuver.TYPE_STRAIGHT to R.drawable.ic_straight
        }
        val b = Step.Builder(s.text).setManeuver(Maneuver.Builder(type).setIcon(icon(carContext, res)).build())
        if (s.road.isNotBlank()) b.setRoad(s.road)
        return b.build()
    }
}

/** Destination search on the car screen. */
class SearchScreen(carContext: CarContext) : Screen(carContext) {
    private var results: List<Place>? = null
    private var loading = false
    private var message = "Type a destination"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) = search(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Where to?")
            .setShowKeyboardByDefault(results == null && !loading)
        if (loading) {
            b.setLoading(true)
        } else {
            val list = ItemList.Builder().setNoItemsMessage(message)
            results?.take(6)?.forEach { p ->
                val row = Row.Builder().setTitle(p.name).setOnClickListener {
                    NavEngine.start(carContext, p.pos, p.name)
                    screenManager.pop()
                }
                if (p.address.isNotBlank()) row.addText(p.address.take(80))
                list.addItem(row.build())
            }
            b.setItemList(list.build())
        }
        return b.build()
    }

    private fun search(q: String) {
        if (q.isBlank()) return
        loading = true
        invalidate()
        val near = LocationTracker.position
        io.execute {
            val r = try { Places.search(q, near) } catch (e: Exception) { null }
            main.post {
                loading = false
                results = r ?: emptyList()
                message = if (r == null) "Search failed: no internet?" else "Nothing found"
                invalidate()
            }
        }
    }
}
