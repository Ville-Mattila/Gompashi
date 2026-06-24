package fi.gompashi.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class UiState(
    val loading: Boolean = true,
    val hasCompass: Boolean = true,
    val permissionGranted: Boolean = false,
    val storeName: String? = null,
    val distanceText: String? = null,
    val rotationDeg: Float = 0f,
    val bearingDeg: Float = 0f,
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

    val state: StateFlow<UiState> = run {
        val azimuth = compass.azimuthFlow()
        val loc = location.locationFlow()

        combine(loc, azimuth, selectedRank, permission) { l, az, rank, perm ->
            val ranked = NearestStoreFinder.rank(l.latitude, l.longitude, stores)
            val safeRank = rank.coerceIn(0, (ranked.size - 1).coerceAtLeast(0))
            val target = ranked.getOrNull(safeRank)
            if (target == null) {
                UiState(
                    loading = false,
                    hasCompass = compass.hasCompass,
                    permissionGranted = perm,
                    storeCount = stores.size,
                    selectedRank = safeRank,
                )
            } else {
                val bearing = target.bearingDeg.toFloat()
                UiState(
                    loading = false,
                    hasCompass = compass.hasCompass,
                    permissionGranted = perm,
                    storeName = target.store.name,
                    distanceText = DistanceFormat.format(target.distanceMeters),
                    bearingDeg = bearing,
                    rotationDeg = ((bearing - az + 360f) % 360f),
                    storeCount = stores.size,
                    selectedRank = safeRank,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UiState(hasCompass = compass.hasCompass, storeCount = stores.size),
        )
    }
}
