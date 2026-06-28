package fi.gompashi.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class UiState(
    val loading: Boolean = true,
    val hasCompass: Boolean = true,
    val permissionGranted: Boolean = false,
    val storeName: String? = null,
    val distanceText: String? = null,
    val hoursText: String = "",
    val hoursOpen: Boolean = false,
    val hoursKnown: Boolean = true,
    val rotationDeg: Float = 0f,
    val bearingDeg: Float = 0f,
    val azimuthDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val storeCount: Int = 0,
    val selectedRank: Int = 0,
    val stepsRemaining: Int? = null,
    // Current user + target coordinates, for the route map view.
    val userLat: Double? = null,
    val userLon: Double? = null,
    val storeLat: Double? = null,
    val storeLon: Double? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = LocalStore(app)
    private val bundledStores: List<AlkoStore> = AlkoRepository.loadFromAssets(app)
    private var customStoresList: List<AlkoStore> = storage.loadCustomStores()
    private var stores: List<AlkoStore> = bundledStores + customStoresList
    private val closedDates: Map<String, Set<String>> = AlkoRepository.loadClosedDates(app)
    private val compass = CompassProvider(app)
    private val location = LocationProvider(app)
    private val stepProvider = StepProvider(app)
    private val stride = StrideEstimator()
    private val routeProvider = RouteProvider()
    val tileStore = TileStore(app, viewModelScope)

    // Walking-route state. The route feeds the map view and a detour factor for steps.
    val route = MutableStateFlow<FootRoute?>(null)
    @Volatile private var routeKey: String? = null
    private var routeOrigin: android.location.Location? = null
    @Volatile private var routeFetching = false
    private var routeCooldownUntil = 0L
    @Volatile private var detourFactor = 1.0

    @Volatile private var lastLocation: android.location.Location? = null

    // Step counting / stride calibration state.
    @Volatile private var currentSteps = 0
    private var prevFix: android.location.Location? = null
    private var prevFixTime = 0L
    private var stepsAtPrevFix = 0
    private val stepPermission =
        MutableStateFlow(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q)

    fun setStepPermission(granted: Boolean) {
        if (granted) stepPermission.value = true
    }

    private val stepsAvailable: Boolean
        get() = stepProvider.hasSensor && stepPermission.value

    @OptIn(ExperimentalCoroutinesApi::class)
    private val stepCollector = viewModelScope.launch {
        stepPermission.flatMapLatest { ok ->
            if (ok && stepProvider.hasSensor) stepProvider.stepFlow() else flowOf(0)
        }.collect { currentSteps = it }
    }

    // Exposed for the settings screen.
    val customStores = MutableStateFlow(customStoresList)
    val needle = MutableStateFlow(storage.loadNeedle())

    fun addCustomStore(name: String): Boolean {
        val loc = lastLocation ?: return false
        val store = AlkoStore(
            name = name.ifBlank { "Oma Alko" },
            lat = loc.latitude, lon = loc.longitude,
            hours = emptyList(), hoursKnown = true,
        )
        customStoresList = customStoresList + store
        persistCustom()
        return true
    }

    fun removeCustomStore(index: Int) {
        customStoresList = customStoresList.toMutableList().apply { if (index in indices) removeAt(index) }
        persistCustom()
    }

    private fun persistCustom() {
        storage.saveCustomStores(customStoresList)
        stores = bundledStores + customStoresList
        customStores.value = customStoresList
    }

    fun setNeedle(uri: android.net.Uri) {
        if (storage.saveNeedleFromUri(uri)) needle.value = storage.loadNeedle()
    }

    fun resetNeedle() {
        storage.deleteNeedle()
        needle.value = null
    }

    // Emits every second so the opening-hours countdown updates live.
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(1000)
        }
    }

    private val selectedRank = MutableStateFlow(0)
    private val permission = MutableStateFlow(false)

    fun setPermissionGranted(granted: Boolean) { permission.value = granted }

    fun toggleRank() {
        // 0 <-> 1, but never beyond available store count
        val next = if (selectedRank.value == 0) 1 else 0
        selectedRank.value = if (next < stores.size) next else 0
    }

    /**
     * On each genuinely-new location fix, feed the stride estimator the distance moved and
     * the steps taken since the previous fix. Only plausible walking segments are used, so
     * GPS jitter while standing still does not corrupt the stride estimate.
     */
    private fun calibrateStride(l: android.location.Location) {
        if (l.time == prevFixTime) return // same fix re-emitted by the ticker; ignore
        val pf = prevFix
        if (pf != null) {
            val seg = pf.distanceTo(l).toDouble()
            val segSteps = currentSteps - stepsAtPrevFix
            val accurate = !l.hasAccuracy() || l.accuracy <= 35f
            if (accurate && seg in 1.5..80.0 && segSteps in 1..200) {
                stride.onSegment(seg, segSteps)
            }
        }
        prevFix = l
        prevFixTime = l.time
        stepsAtPrevFix = currentSteps
    }

    private fun storeKey(s: AlkoStore) = "${s.lat},${s.lon}"

    /**
     * Fetch a walking route to the target when needed (store changed, or the user moved far
     * enough), throttled and with a cooldown after failures. Updates [route] for the map and a
     * detour factor used to scale the step estimate. Falls back to the straight line on failure.
     */
    private fun maybeFetchRoute(user: android.location.Location, store: AlkoStore, straightLine: Double) {
        val key = storeKey(store)
        val moved = routeOrigin?.distanceTo(user)?.toDouble() ?: Double.MAX_VALUE
        val need = key != routeKey || moved > 75.0
        if (!need || routeFetching || System.currentTimeMillis() < routeCooldownUntil) return
        if (key != routeKey) { // target changed: drop the stale route until the new one arrives
            route.value = null
            routeKey = null
            detourFactor = 1.0
        }
        routeFetching = true
        val oLat = user.latitude
        val oLon = user.longitude
        viewModelScope.launch {
            val r = routeProvider.fetch(oLat, oLon, store.lat, store.lon)
            if (r != null) {
                route.value = r
                routeKey = key
                routeOrigin = android.location.Location("gompashi").apply { latitude = oLat; longitude = oLon }
                detourFactor = if (straightLine > 1.0) (r.distanceMeters / straightLine).coerceIn(1.0, 2.5) else 1.0
            } else {
                routeCooldownUntil = System.currentTimeMillis() + 15_000
            }
            routeFetching = false
        }
    }

    private fun baseState(
        loading: Boolean,
        permissionGranted: Boolean,
    ) = UiState(
        loading = loading,
        hasCompass = compass.hasCompass,
        permissionGranted = permissionGranted,
        storeCount = stores.size,
        selectedRank = selectedRank.value,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState> =
        permission.flatMapLatest { granted ->
            if (!granted) {
                // No permission yet: show the permission prompt, don't touch location.
                flowOf(baseState(loading = false, permissionGranted = false))
            } else {
                // Seed orientation with zeros so combine can emit before/without a sensor reading.
                val orientation = compass.orientationFlow()
                    .onStart { emit(DeviceOrientation(0f, 0f, 0f)) }
                combine(location.locationFlow(), orientation, selectedRank, ticker) { l, o, rank, _ ->
                    lastLocation = l
                    calibrateStride(l)
                    val ranked = NearestStoreFinder.rank(l.latitude, l.longitude, stores)
                    val safeRank = rank.coerceIn(0, (ranked.size - 1).coerceAtLeast(0))
                    val target = ranked.getOrNull(safeRank)
                    if (target == null) {
                        baseState(loading = false, permissionGranted = true)
                    } else {
                        val bearing = target.bearingDeg.toFloat()
                        val now = LocalDateTime.now()
                        val storeClosed = closedDates[target.store.country] ?: emptySet()
                        val status = OpeningHours.status(now, target.store.hours, storeClosed)
                        maybeFetchRoute(l, target.store, target.distanceMeters)
                        // Steps follow the real walking route: the straight-line distance (which
                        // shrinks live) scaled by the route's detour factor when we have one.
                        val routeMatches = routeKey == storeKey(target.store)
                        val walkMeters = target.distanceMeters * (if (routeMatches) detourFactor else 1.0)
                        baseState(loading = false, permissionGranted = true).copy(
                            storeName = target.store.name,
                            distanceText = DistanceFormat.format(target.distanceMeters),
                            hoursText = OpeningHours.countdownText(status, now),
                            hoursOpen = status?.open == true,
                            hoursKnown = target.store.hoursKnown,
                            bearingDeg = bearing,
                            rotationDeg = ((bearing - o.azimuth + 360f) % 360f),
                            azimuthDeg = o.azimuth,
                            pitchDeg = o.pitch,
                            rollDeg = o.roll,
                            selectedRank = safeRank,
                            stepsRemaining = if (stepsAvailable) stride.stepsFor(walkMeters) else null,
                            userLat = l.latitude,
                            userLon = l.longitude,
                            storeLat = target.store.lat,
                            storeLon = target.store.lon,
                        )
                    }
                }.onStart {
                    // While waiting for the first location fix, show a loading state.
                    emit(baseState(loading = true, permissionGranted = true))
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = baseState(loading = true, permissionGranted = false),
        )
}
