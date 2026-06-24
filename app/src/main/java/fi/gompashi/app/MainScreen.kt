package fi.gompashi.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MainScreen(
    state: UiState,
    onToggleRank: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            !state.permissionGranted -> {
                Text(
                    "Gompashi tarvitsee sijaintiluvan näyttääkseen suunnan Alkoon.",
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRequestPermission, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Salli sijainti")
                }
            }

            state.loading || state.distanceText == null -> {
                Text("Haetaan sijaintia…")
            }

            else -> {
                // Toggle: nearest / second nearest
                Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FilterChip(
                        selected = state.selectedRank == 0,
                        onClick = { if (state.selectedRank != 0) onToggleRank() },
                        label = { Text("Lähin") },
                    )
                    FilterChip(
                        selected = state.selectedRank == 1,
                        enabled = state.storeCount > 1,
                        onClick = { if (state.selectedRank != 1) onToggleRank() },
                        label = { Text("Toiseksi lähin") },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                val rotation = if (state.hasCompass) state.rotationDeg else state.bearingDeg
                Image(
                    painter = painterResource(id = R.drawable.compass_needle),
                    contentDescription = "Suunta Alkoon",
                    modifier = Modifier.size(200.dp).rotate(rotation),
                )

                Text(
                    text = state.distanceText,
                    fontSize = 32.sp,
                    modifier = Modifier.padding(top = 24.dp),
                )
                state.storeName?.let {
                    Text(text = it, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
                if (!state.hasCompass) {
                    Text(
                        text = "Ei kompassia — nuoli näyttää suunnan pohjoisesta (${state.bearingDeg.toInt()}°).",
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
