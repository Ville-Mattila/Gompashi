package fi.gompashi.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

data class UiState(
    val loading: Boolean = true,
    val hasCompass: Boolean = true,
    val permissionGranted: Boolean = false,
    val storeName: String? = null,
    val distanceText: String? = null,
    val rotationDeg: Float = 0f,
    val bearingDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val storeCount: Int = 0,
    val selectedRank: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val stores: List<AlkoStore> = AlkoRepository.loadFromAssets(app)
    private val compass = CompassProvider(app)
    private val location = LocationProvider(app)

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
                combine(location.locationFlow(), orientation, selectedRank) { l, o, rank ->
                    val ranked = NearestStoreFinder.rank(l.latitude, l.longitude, stores)
                    val safeRank = rank.coerceIn(0, (ranked.size - 1).coerceAtLeast(0))
                    val target = ranked.getOrNull(safeRank)
                    if (target == null) {
                        baseState(loading = false, permissionGranted = true)
                    } else {
                        val bearing = target.bearingDeg.toFloat()
                        baseState(loading = false, permissionGranted = true).copy(
                            storeName = target.store.name,
                            distanceText = DistanceFormat.format(target.distanceMeters),
                            bearingDeg = bearing,
                            rotationDeg = ((bearing - o.azimuth + 360f) % 360f),
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
