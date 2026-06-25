package fi.gompashi.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF000000)
private val Accent = Color(0xFFD7263D)
private val BitcountSingle = FontFamily(Font(R.font.bitcount_single))
private val TextPrimary = Color(0xFFF5F5F5)
private val TextSecondary = Color(0xFF9A9A9A)
private val TrackColor = Color(0xFF161616)
private val DisabledText = Color(0xFF4A4A4A)
private val OpenGreen = Color(0xFF7BD88F)
private val ClosedRed = Color(0xFFE0707A)

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

        // Hero: a dim true-north needle behind the glowing bottle that points to the store.
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

            // Dim north needle: always points to true north (screen rotation = -azimuth).
            if (state.hasCompass) {
                val northRotation = rememberAnimatedAngle(-state.azimuthDeg, "northRotation")
                Image(
                    painter = painterResource(id = R.drawable.compass_needle_north),
                    contentDescription = null,
                    modifier = Modifier
                        // Lay out at the bottle's size, but draw at ~650dp via scale so the
                        // big faint compass overflows behind the bottle without pushing the
                        // distance/toggle off-screen.
                        .size(420.dp)
                        .graphicsLayer {
                            val s = 650f / 420f
                            scaleX = s
                            scaleY = s
                            rotationZ = northRotation
                            rotationX = (state.pitchDeg * -0.5f).coerceIn(-7f, 7f)
                            rotationY = (state.rollDeg * -0.5f).coerceIn(-7f, 7f)
                            cameraDistance = 16f * density
                            alpha = 0.08f
                        },
                )
            }

            // Bottle needle: points to the selected Alko, with a slight 3D lean opposite
            // to the phone's tilt.
            val target = if (state.hasCompass) state.rotationDeg else state.bearingDeg
            val bottleRotation = rememberAnimatedAngle(target, "needleRotation")
            Image(
                painter = painterResource(id = R.drawable.compass_needle),
                contentDescription = "Suunta Alkoon",
                modifier = Modifier
                    .height(420.dp)
                    .graphicsLayer {
                        rotationZ = bottleRotation
                        rotationX = (state.pitchDeg * -0.5f).coerceIn(-7f, 7f)
                        rotationY = (state.rollDeg * -0.5f).coerceIn(-7f, 7f)
                        cameraDistance = 16f * density
                    },
            )
        }

        Spacer(Modifier.height(32.dp))

        val distanceStyle = TextStyle(
            color = TextPrimary,
            fontFamily = BitcountSingle,
            fontSize = 64.sp,
            // Slight red glow, echoing the bottle needle's halo.
            shadow = Shadow(color = Accent, offset = Offset.Zero, blurRadius = 26f),
        )
        AnimatedDistance(text = state.distanceText.orEmpty(), style = distanceStyle)

        state.storeName?.let { name ->
            // Fade AND animate the width between names of different lengths, so there is
            // no snap at the end of the transition when toggling nearest / second nearest.
            AnimatedContent(
                targetState = name,
                transitionSpec = {
                    (fadeIn(tween(300)) togetherWith fadeOut(tween(300)))
                        .using(SizeTransform { _, _ -> tween(300) })
                },
                contentAlignment = Alignment.Center,
                label = "storeName",
                modifier = Modifier.padding(top = 6.dp),
            ) { shown ->
                Text(
                    text = shown,
                    color = TextSecondary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (state.hoursText.isNotEmpty()) {
            Text(
                text = state.hoursText,
                color = if (state.hoursOpen) OpenGreen else ClosedRed,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!state.hoursKnown) {
                Text(
                    text = "aukioloaika ei tiedossa — vakioajat käytössä",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
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

/**
 * Animates an angle (degrees) the short way across the 0/360 seam, smoothed by a
 * critically-damped spring at frame rate. Returns the current animated value.
 */
@Composable
private fun rememberAnimatedAngle(target: Float, label: String): Float {
    var continuous by remember { mutableFloatStateOf(target) }
    LaunchedEffect(target) {
        continuous += GeoUtils.smallestAngleDelta(continuous.toDouble(), target.toDouble()).toFloat()
    }
    val animated by animateFloatAsState(
        targetValue = continuous,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow),
        label = label,
    )
    return animated
}

@Composable
private fun AnimatedDistance(text: String, style: TextStyle) {
    // Each character animates independently: only the digits that actually change roll
    // vertically (odometer style), up when increasing and down when decreasing.
    //
    // Each glyph is padded top/bottom by `edge`, and a vertical gradient mask fades
    // exactly that padding band (matched in px). A resting glyph sits entirely inside the
    // un-faded middle, so it stays fully solid; a rolling glyph fades softly as it travels
    // through the padding band, so it has no hard appear/disappear line.
    val edge = 20.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val f = (edge.toPx() / size.height).coerceIn(0f, 0.49f)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        f to Color.Black,
                        1f - f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        text.forEachIndexed { index, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    val dir = if (targetState >= initialState) 1 else -1
                    slideInVertically(tween(350)) { h -> dir * h } togetherWith
                        slideOutVertically(tween(350)) { h -> -dir * h }
                },
                label = "distChar$index",
            ) { c ->
                Text(
                    text = c.toString(),
                    style = style,
                    modifier = Modifier.padding(vertical = edge),
                )
            }
        }
    }
}

@Composable
private fun SegmentedToggle(
    selectedRank: Int,
    secondEnabled: Boolean,
    onToggleRank: () -> Unit,
) {
    val labels = listOf("Lähin", "Toiseksi lähin")
    val shape = RoundedCornerShape(percent = 50)
    val segHeight = 44.dp
    BoxWithConstraints(
        modifier = Modifier
            .clip(shape)
            .background(TrackColor)
            .padding(4.dp),
    ) {
        val segWidth = maxWidth / 2
        // Highlight pill glides under the selected label with a springy settle.
        val indicatorOffset by animateDpAsState(
            targetValue = segWidth * selectedRank,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "toggleSlide",
        )
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segWidth)
                .height(segHeight)
                .clip(shape)
                .background(Accent),
        )
        Row {
            labels.forEachIndexed { index, label ->
                val selected = selectedRank == index
                val enabled = index == 0 || secondEnabled
                val textColor by animateColorAsState(
                    targetValue = when {
                        !enabled -> DisabledText
                        selected -> Color.White
                        else -> TextSecondary
                    },
                    label = "toggleText$index",
                )
                Box(
                    modifier = Modifier
                        .width(segWidth)
                        .height(segHeight)
                        .clickable(
                            enabled = enabled,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { if (!selected) onToggleRank() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = textColor,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
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
