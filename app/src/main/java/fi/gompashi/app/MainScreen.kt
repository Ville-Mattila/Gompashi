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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

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
    needle: ImageBitmap?,
    route: FootRoute?,
    customStores: List<AlkoStore>,
    onToggleRank: () -> Unit,
    onRequestPermission: () -> Unit,
    onAddCustom: (String) -> Boolean,
    onRemoveCustom: (Int) -> Unit,
    onPickNeedle: () -> Unit,
    onResetNeedle: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tileStore = remember { TileStore(scope) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !state.permissionGranted -> PermissionState(onRequestPermission)
            state.loading || state.distanceText == null -> LoadingState()
            else -> CompassContent(state, needle, onToggleRank)
        }

        Text(
            text = "⚙",
            color = TextSecondary,
            fontSize = 22.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 16.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { showSettings = true },
        )

        // Route-map toggle, mirroring the settings gear on the opposite corner.
        if (state.permissionGranted && !state.loading) {
            MapIconButton(
                onClick = { showMap = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 8.dp, start = 16.dp),
            )
        }

        if (showMap) {
            RouteMap(state = state, route = route, tileStore = tileStore, onClose = { showMap = false })
        }

        if (showSettings) {
            SettingsScreen(
                needle = needle,
                customStores = customStores,
                onAddCustom = onAddCustom,
                onRemoveCustom = onRemoveCustom,
                onPickNeedle = onPickNeedle,
                onResetNeedle = onResetNeedle,
                onClose = { showSettings = false },
            )
        }
    }
}

@Composable
private fun CompassContent(state: UiState, needle: ImageBitmap?, onToggleRank: () -> Unit) {
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
            val needlePainter = needle?.let { remember(it) { BitmapPainter(it) } }
                ?: painterResource(id = R.drawable.compass_needle)
            Image(
                painter = needlePainter,
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

        state.stepsRemaining?.let { steps ->
            Text(
                text = "≈ ${formatSteps(steps)} askelta",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

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

/** Groups an integer with spaces every three digits, e.g. 1234 -> "1 234". */
private fun formatSteps(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(" ").reversed()

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
private fun SettingsScreen(
    needle: ImageBitmap?,
    customStores: List<AlkoStore>,
    onAddCustom: (String) -> Boolean,
    onRemoveCustom: (Int) -> Unit,
    onPickNeedle: () -> Unit,
    onResetNeedle: () -> Unit,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Asetukset", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(24.dp))
        Text("Kompassineula", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        val preview = needle?.let { remember(it) { BitmapPainter(it) } }
            ?: painterResource(id = R.drawable.compass_needle)
        Image(
            painter = preview,
            contentDescription = null,
            modifier = Modifier.height(120.dp).align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPickNeedle) { Text("Vaihda kuva") }
            TextButton(onClick = onResetNeedle) { Text("Palauta oletus", color = TextSecondary) }
        }

        Spacer(Modifier.height(28.dp))
        Text("Oma Alko", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Lisää myymälä, jota ei ole datassa. Tallentuu vain tähän laitteeseen.",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            placeholder = { Text("Nimi (esim. Alko Mökkikylä)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = {
            msg = if (onAddCustom(name)) { name = ""; "Lisätty." } else "Sijaintia ei vielä saatu — salli sijainti ja yritä uudelleen."
        }) { Text("Lisää nykyiseen sijaintiin") }
        if (msg.isNotEmpty()) {
            Text(msg, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(8.dp))
        customStores.forEachIndexed { i, s ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.name, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemoveCustom(i) }) { Text("Poista", color = Accent) }
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Sulje") }
    }
}

/** Small themed "route" glyph (two endpoints joined by a line), drawn to match the UI. */
@Composable
private fun MapIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .size(26.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClick() },
    ) {
        val sw = 2.dp.toPx()
        val r = 2.6.dp.toPx()
        val a = Offset(size.width * 0.26f, size.height * 0.74f)
        val b = Offset(size.width * 0.74f, size.height * 0.30f)
        drawLine(TextSecondary, a, b, strokeWidth = sw, cap = StrokeCap.Round)
        drawCircle(TextSecondary, r, a, style = Stroke(width = sw))
        drawCircle(TextSecondary, r, b, style = Stroke(width = sw))
    }
}

/** Expandable half-screen walking-route map: dim CARTO-dark base tiles + the route on top. */
@Composable
private fun RouteMap(state: UiState, route: FootRoute?, tileStore: TileStore, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.52f)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Background)
                .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .navigationBarsPadding()
                .padding(12.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val info = when {
                        state.userLat == null -> "Odotetaan sijaintia"
                        route != null -> "Kävellen ${DistanceFormat.format(route.distanceMeters)} · ~${max(1, (route.durationSeconds / 60).roundToInt())} min"
                        else -> "Reitti vaatii verkon — näytetään linnuntie"
                    }
                    Text(info, color = TextSecondary, fontSize = 14.sp)
                    Text(
                        text = "✕",
                        color = TextSecondary,
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { onClose() }
                            .padding(start = 12.dp),
                    )
                }
                RouteCanvas(state, route, tileStore)
            }
        }
    }
}

private const val TILE = 256
private fun mercY(lat: Double) = (1 - ln(tan(lat * PI / 180) + 1 / cos(lat * PI / 180)) / PI) / 2

@Composable
private fun RouteCanvas(state: UiState, route: FootRoute?, tileStore: TileStore) {
    val uLat = state.userLat; val uLon = state.userLon
    val sLat = state.storeLat; val sLon = state.storeLon
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0B0B0B)),
    ) {
        if (uLat == null || uLon == null || sLat == null || sLon == null) return@BoxWithConstraints
        val w = constraints.maxWidth.toDouble()
        val h = constraints.maxHeight.toDouble()
        if (w <= 0 || h <= 0) return@BoxWithConstraints

        val line: List<Pair<Double, Double>> =
            route?.points?.map { it.lat to it.lon } ?: listOf(uLat to uLon, sLat to sLon)
        val pts = line + listOf(uLat to uLon, sLat to sLon)

        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for ((la, lo) in pts) {
            minLat = min(minLat, la); maxLat = max(maxLat, la)
            minLon = min(minLon, lo); maxLon = max(maxLon, lo)
        }
        val padLat = ((maxLat - minLat).takeIf { it > 0 } ?: 0.0008) * 0.35
        val padLon = ((maxLon - minLon).takeIf { it > 0 } ?: 0.0008) * 0.35
        minLat -= padLat; maxLat += padLat; minLon -= padLon; maxLon += padLon

        val pad = 12.0
        val lonFrac = max((maxLon - minLon) / 360, 1e-9)
        val latFrac = max(mercY(minLat) - mercY(maxLat), 1e-9)
        var z = minOf(
            (ln((w - 2 * pad) / (TILE * lonFrac)) / ln(2.0)),
            (ln((h - 2 * pad) / (TILE * latFrac)) / ln(2.0)),
        ).toInt()
        z = z.coerceIn(3, 18)
        val scale = TILE * 2.0.pow(z)
        val nT = 2.0.pow(z).toInt()
        val cWx = ((minLon + maxLon) / 2 + 180) / 360 * scale
        val cWy = (mercY(minLat) + mercY(maxLat)) / 2 * scale
        val sx0 = cWx - w / 2
        val sy0 = cWy - h / 2

        // Request the tiles covering the viewport (loads happen in the background).
        data class T(val img: ImageBitmap, val dx: Float, val dy: Float)
        val drawTiles = ArrayList<T>()
        var tx = kotlin.math.floor(sx0 / TILE).toInt()
        while (tx <= kotlin.math.floor((sx0 + w) / TILE).toInt()) {
            var ty = kotlin.math.floor(sy0 / TILE).toInt()
            while (ty <= kotlin.math.floor((sy0 + h) / TILE).toInt()) {
                if (ty in 0 until nT) {
                    val wx = ((tx % nT) + nT) % nT
                    tileStore.get(z, wx, ty)?.let {
                        drawTiles.add(T(it, (tx * TILE - sx0).toFloat(), (ty * TILE - sy0).toFloat()))
                    }
                }
                ty++
            }
            tx++
        }

        fun proj(la: Double, lo: Double) = Offset(
            ((lo + 180) / 360 * scale - sx0).toFloat(),
            (mercY(la) * scale - sy0).toFloat(),
        )

        Canvas(Modifier.fillMaxSize()) {
            // Dim base map.
            for (t in drawTiles) {
                drawImage(
                    image = t.img,
                    dstOffset = IntOffset(t.dx.roundToInt(), t.dy.roundToInt()),
                    dstSize = IntSize(TILE, TILE),
                    alpha = 0.55f,
                )
            }
            // Route.
            val path = Path()
            line.forEachIndexed { i, (la, lo) ->
                val o = proj(la, lo)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(
                path = path,
                color = if (route != null) Accent else Accent.copy(alpha = 0.7f),
                style = Stroke(
                    width = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = if (route == null) PathEffect.dashPathEffect(floatArrayOf(14f, 14f)) else null,
                ),
            )
            val so = proj(sLat, sLon)
            drawCircle(Accent, 6.5.dp.toPx(), so)
            drawCircle(Color.Black, 3.dp.toPx(), so)
            drawCircle(TextPrimary, 5.dp.toPx(), proj(uLat, uLon))
        }

        Text(
            text = "© OpenStreetMap, CARTO",
            color = TextSecondary.copy(alpha = 0.6f),
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
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
