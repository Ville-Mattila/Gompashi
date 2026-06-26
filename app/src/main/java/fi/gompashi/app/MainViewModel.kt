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
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = LocalStore(app)
    private val bundledStores: List<AlkoStore> = AlkoRepository.loadFromAssets(app)
    private var customStoresList: List<AlkoStore> = storage.loadCustomStores()
    private var stores: List<AlkoStore> = bundledStores + customStoresList
    private val closedDates: Set<String> = AlkoRepository.loadClosedDates(app)
    private val compass = CompassProvider(app)
    private val location = LocationProvider(app)

    @Volatile private var lastLocation: android.location.Location? = null

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
                    val ranked = NearestStoreFinder.rank(l.latitude, l.longitude, stores)
                    val safeRank = rank.coerceIn(0, (ranked.size - 1).coerceAtLeast(0))
                    val target = ranked.getOrNull(safeRank)
                    if (target == null) {
                        baseState(loading = false, permissionGranted = true)
                    } else {
                        val bearing = target.bearingDeg.toFloat()
                        val now = LocalDateTime.now()
                        val status = OpeningHours.status(now, target.store.hours, closedDates)
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
