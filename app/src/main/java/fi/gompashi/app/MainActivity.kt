package fi.gompashi.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.setPermissionGranted(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setPermissionGranted(hasLocationPermission())

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black,
                    surface = Color.Black,
                    primary = Color(0xFFD7263D),
                    onPrimary = Color.White,
                ),
            ) {
                Surface(color = Color.Black) {
                    val state by viewModel.state.collectAsState()
                    val customStores by viewModel.customStores.collectAsState()
                    val needle by viewModel.needle.collectAsState()
                    val pickImage = rememberLauncherForActivityResult(
                        ActivityResultContracts.PickVisualMedia()
                    ) { uri -> uri?.let { viewModel.setNeedle(it) } }

                    MainScreen(
                        state = state,
                        needle = needle,
                        customStores = customStores,
                        onToggleRank = { viewModel.toggleRank() },
                        onRequestPermission = { requestLocationPermission() },
                        onAddCustom = { name -> viewModel.addCustomStore(name) },
                        onRemoveCustom = { index -> viewModel.removeCustomStore(index) },
                        onPickNeedle = {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onResetNeedle = { viewModel.resetNeedle() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setPermissionGranted(hasLocationPermission())
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }
}
