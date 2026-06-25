package fi.gompashi.app

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF000000)
private val Accent = Color(0xFFD7263D)
private val TextPrimary = Color(0xFFF5F5F5)
private val TextSecondary = Color(0xFF9A9A9A)
private val TrackColor = Color(0xFF161616)
private val DisabledText = Color(0xFF4A4A4A)

@Composable
fun MainScreen(
    state: UiState,
    onToggleRank: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !state.permissionGranted -> PermissionState(onRequestPermission)
            state.loading || state.distanceText == null -> LoadingState()
            else -> CompassContent(state, onToggleRank)
        }
    }
}

@Composable
private fun CompassContent(state: UiState, onToggleRank: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Wordmark, centered at the top, 50% of screen width.
        Image(
            painter = painterResource(id = R.drawable.title),
            contentDescription = "Gompashi",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .padding(top = 8.dp),
        )

        Spacer(Modifier.weight(1f))

        // Hero: glowing bottle that rotates toward the store.
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(420.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Accent.copy(alpha = 0.22f), Color.Transparent),
                        ),
                        shape = RoundedCornerShape(percent = 50),
                    ),
            )
            val target = if (state.hasCompass) state.rotationDeg else state.bearingDeg
            // Accumulate a continuous (unwrapped) angle so the needle always turns the
            // short way across the 0/360 seam, then let a critically-damped spring
            // interpolate it at frame rate for buttery-smooth motion.
            var continuous by remember { mutableFloatStateOf(target) }
            LaunchedEffect(target) {
                continuous += GeoUtils.smallestAngleDelta(continuous.toDouble(), target.toDouble()).toFloat()
            }
            val animatedRotation by animateFloatAsState(
                targetValue = continuous,
                animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow),
                label = "needleRotation",
            )
            Image(
                painter = painterResource(id = R.drawable.compass_needle),
                contentDescription = "Suunta Alkoon",
                modifier = Modifier
                    .height(420.dp)
                    .rotate(animatedRotation),
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = state.distanceText.orEmpty(),
            color = TextPrimary,
            fontSize = 68.sp,
            fontWeight = FontWeight.Light,
        )
        state.storeName?.let {
            Text(
                text = it,
                color = TextSecondary,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (!state.hasCompass) {
            Text(
                text = "Ei kompassia — suunta pohjoisesta ${state.bearingDeg.toInt()}°",
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        SegmentedToggle(
            selectedRank = state.selectedRank,
            secondEnabled = state.storeCount > 1,
            onToggleRank = onToggleRank,
        )
    }
}

@Composable
private fun SegmentedToggle(
    selectedRank: Int,
    secondEnabled: Boolean,
    onToggleRank: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(TrackColor)
            .padding(4.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Segment(
                label = "Lähin",
                selected = selectedRank == 0,
                enabled = true,
            ) { if (selectedRank != 0) onToggleRank() }
            Segment(
                label = "Toiseksi lähin",
                selected = selectedRank == 1,
                enabled = secondEnabled,
            ) { if (selectedRank != 1) onToggleRank() }
        }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) Accent else Color.Transparent
    val fg = when {
        !enabled -> DisabledText
        selected -> Color.White
        else -> TextSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun LoadingState() {
    Text(text = "Haetaan sijaintia…", color = TextSecondary, fontSize = 18.sp)
}

@Composable
private fun PermissionState(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Gompashi tarvitsee sijaintiluvan\nnäyttääkseen suunnan Alkoon.",
            color = TextPrimary,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .padding(top = 24.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Accent)
                .clickable(onClick = onRequestPermission)
                .padding(horizontal = 32.dp, vertical = 14.dp),
        ) {
            Text(text = "Salli sijainti", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}
