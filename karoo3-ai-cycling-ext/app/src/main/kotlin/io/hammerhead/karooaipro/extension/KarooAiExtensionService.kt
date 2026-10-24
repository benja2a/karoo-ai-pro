package io.hammerhead.karooaipro.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hammerhead.karooaipro.engine.AiHeuristicEvaluation
import io.hammerhead.karooaipro.engine.CyclingMetricsSnapshot
import io.hammerhead.karooaipro.engine.WindVectorReport
import io.hammerhead.karooaipro.extension.SharedTelemetryBus
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-Screen Multi-Page Cockpit for Hammerhead Karoo 3 (3.2" 480x800 Display).
 * Supports 4 main pages (1: Métricas XL, 2: Mapa de Ruta, 3: Radar de Viento, 4: IA Coach),
 * 3 sub-views inside Métricas, Daylight/Night high-contrast mode, touch buttons,
 * horizontal swipe gestures, and Karoo 3 physical hardware buttons.
 */
class MetricsDashboardActivity : ComponentActivity() {

    private var pageState = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KarooMultiPageCockpit(
                currentPage = pageState.intValue,
                onSelectPage = { newPage -> pageState.intValue = (newPage + 4) % 4 }
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                pageState.intValue = (pageState.intValue + 1) % 4
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_PAGE_UP -> {
                pageState.intValue = (pageState.intValue + 3) % 4
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

@Composable
fun KarooMultiPageCockpit(
    currentPage: Int,
    onSelectPage: (Int) -> Unit
) {
    var telemetry by remember { mutableStateOf(SharedTelemetryBus.latestState()) }
    var subPage by remember { mutableIntStateOf(0) } // 0=Todo, 1=Potencia/W', 2=Viento/Subida
    var daylightMode by remember { mutableStateOf(false) }
    var mapZoom by remember { mutableFloatStateOf(1.0f) }
    var mapWindColorMode by remember { mutableStateOf(true) }
    var showClimber by remember { mutableStateOf(true) }
    var extraCarbsLogged by remember { mutableIntStateOf(50) }
    var extraHydrationMl by remember { mutableIntStateOf(600) }
    var tickCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            telemetry = SharedTelemetryBus.latestState()
            tickCount += 1
            delay(1000L)
        }
    }

    val (metrics, wind, ai) = telemetry
    val bgColor = if (daylightMode) Color(0xFFF8FAFC) else Color(0xFF080C12)
    val cardBg = if (daylightMode) Color(0xFFFFFFFF) else Color(0xFF111827)
    val borderCol = if (daylightMode) Color(0xFFCBD5E1) else Color(0xFF1E293B)
    val primaryText = if (daylightMode) Color(0xFF0F172A) else Color(0xFFFFFFFF)
    val secondaryText = if (daylightMode) Color(0xFF475569) else Color(0xFF94A3B8)

    val pageTitles = listOf("1/4 • MÉTRICAS", "2/4 • MAPA RUTA", "3/4 • VIENTO", "4/4 • IA COACH")

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(currentPage) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd = {
                        if (totalDrag < -60f) {
                            onSelectPage(currentPage + 1)
                        } else if (totalDrag > 60f) {
                            onSelectPage(currentPage - 1)
                        }
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        totalDrag += dragAmount
                    }
                )
            },
        color = bgColor
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // TOP STATUS & DAYLIGHT BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D131F))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val mins = (74 + tickCount / 60)
                val secs = (tickCount % 60).toString().padStart(2, '0')
                Text(
                    text = "01:${(mins % 60).toString().padStart(2, '0')}:$secs  •  GPS 3D",
                    color = Color(0xFFFACC15),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${wind.temperatureC}°C",
                        color = Color(0xFF2DD4BF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .background(
                                if (daylightMode) Color(0xFFFACC15) else Color(0xFF1E293B),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { daylightMode = !daylightMode }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (daylightMode) "☀️ DÍA" else "🌙 NOCHE",
                            color = if (daylightMode) Color(0xFF0F172A) else Color(0xFFE2E8F0),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            // TOP 4-PAGE SELECTOR TABS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B101B))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val tabs = listOf("Métricas", "🗺️ Mapa", "🌬️ Viento", "🧠 IA")
                tabs.forEachIndexed { idx, title ->
                    val active = currentPage == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (active) Color(0xFFFACC15) else Color(0xFF1E293B),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectPage(idx) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (active) Color(0xFF0F172A) else Color(0xFFCBD5E1),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            // MAIN PAGE CONTENT AREA
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (currentPage) {
                    0 -> AllMetricsPageContent(
                        metrics = metrics,
                        wind = wind,
                        ai = ai,
                        subPage = subPage,
                        onSelectSubPage = { subPage = it },
                        cardBg = cardBg,
                        borderCol = borderCol,
                        primaryText = primaryText,
                        secondaryText = secondaryText
                    )
                    1 -> RouteMapPageContent(
                        metrics = metrics,
                        wind = wind,
                        ai = ai,
                        mapZoom = mapZoom,
                        onZoomIn = { mapZoom = (mapZoom + 0.2f).coerceAtMost(1.8f) },
                        onZoomOut = { mapZoom = (mapZoom - 0.2f).coerceAtLeast(0.7f) },
                        mapWindColorMode = mapWindColorMode,
                        onToggleColorMode = { mapWindColorMode = !mapWindColorMode },
                        showClimber = showClimber,
                        onToggleClimber = { showClimber = !showClimber },
                        daylightMode = daylightMode,
                        cardBg = cardBg,
                        borderCol = borderCol
                    )
                    2 -> WindRadarPageContent(
                        wind = wind,
                        cardBg = cardBg,
                        borderCol = borderCol,
                        primaryText = primaryText,
                        secondaryText = secondaryText
                    )
                    3 -> AiCoachPageContent(
                        metrics = metrics,
                        wind = wind,
                        ai = ai,
                        carbsLogged = extraCarbsLogged,
                        hydrationMl = extraHydrationMl,
                        onAddGel = { extraCarbsLogged += 25 },
                        onAddSip = { extraHydrationMl += 150 },
                        cardBg = cardBg,
                        borderCol = borderCol,
                        primaryText = primaryText,
                        secondaryText = secondaryText
                    )
                }
            }

            // BOTTOM KAROO 3 HARDWARE-STYLE PAGE SWITCHER BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D131F))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                        .clickable { onSelectPage(currentPage - 1) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "◀ PÁGINA",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Text(
                    text = pageTitles[currentPage],
                    color = Color(0xFFFACC15),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                        .clickable { onSelectPage(currentPage + 1) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "PÁGINA ▶",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun AllMetricsPageContent(
    metrics: CyclingMetricsSnapshot,
    wind: WindVectorReport,
    ai: AiHeuristicEvaluation,
    subPage: Int,
    onSelectSubPage: (Int) -> Unit,
    cardBg: Color,
    borderCol: Color,
    primaryText: Color,
    secondaryText: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Sub-view filter pills for 3.2" big-digit legibility
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val subLabels = listOf("Todo XL", "1: Potencia/W'", "2: Viento/Subida")
            subLabels.forEachIndexed { idx, label ->
                val selected = subPage == idx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selected) Color(0xFF2563EB) else cardBg,
                            RoundedCornerShape(6.dp)
                        )
                        .border(1.dp, borderCol, RoundedCornerShape(6.dp))
                        .clickable { onSelectSubPage(idx) }
                        .padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.White else secondaryText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // AI Tactical Banner
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF172554), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "AI TARGET: ${ai.adaptiveTargetWatts}W",
                    color = Color(0xFF93C5FD),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "BONK RISK ${ai.bonkRiskPercent}%",
                    color = Color(0xFF34D399),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Text(
                text = ai.tacticalHeadline,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = ai.tacticalReason,
                color = Color(0xFFDBEAFE),
                fontSize = 10.sp
            )
        }

        if (subPage == 0 || subPage == 1) {
            // 3s Power & Skiba W' Balance
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "3S POWER (${metrics.powerZoneName})",
                    value = "${metrics.power3s}W",
                    subValue = "${metrics.ftpPercent}% FTP • Tgt ${ai.adaptiveTargetWatts}W",
                    accentColor = Color(0xFFFACC15),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "W' BALANCE (SKIBA)",
                    value = "${metrics.wPrimeBalanceKj} kJ",
                    subValue = "${metrics.wPrimePercent}% Reserva Anaeróbica",
                    accentColor = Color(0xFFA855F7),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
            }

            // NP / IF / TSS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "NORM POWER",
                    value = "${metrics.normalizedPower}W",
                    subValue = "${metrics.kilojoulesTotal} kJ",
                    accentColor = Color(0xFF38BDF8),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "INTENSITY (IF)",
                    value = "${metrics.intensityFactor}",
                    subValue = "Drift ${metrics.aerobicDecouplingPercent}%",
                    accentColor = Color(0xFF38BDF8),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "TSS LOAD",
                    value = "${metrics.tss}",
                    subValue = "TrainingPeaks",
                    accentColor = Color(0xFF38BDF8),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
            }
        }

        if (subPage == 0 || subPage == 2) {
            // Speed, HR, Cadence, Grade, VAM
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "VELOCIDAD / DIST",
                    value = "${metrics.speedKmh} km/h",
                    subValue = "${metrics.distanceKm} km",
                    accentColor = Color(0xFF10B981),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "PULSO / CADENCIA",
                    value = "${metrics.heartRateBpm} bpm",
                    subValue = "Z${metrics.hrZone} • ${metrics.cadenceRpm} rpm",
                    accentColor = Color(0xFFF43F5E),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "PENDIENTE / VAM",
                    value = "${metrics.gradePercent}%",
                    subValue = "${metrics.vamMetersPerHour} m/h",
                    accentColor = Color(0xFFF59E0B),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
            }

            // Wind Vector Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val headLabel = if (wind.headwindKmh >= 0) "VIENTO FRONTAL" else "VIENTO A FAVOR"
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = headLabel,
                    value = "${abs(wind.headwindKmh)} km/h",
                    subValue = "Aero ${if (wind.aeroPowerImpactWatts >= 0) "+" else ""}${wind.aeroPowerImpactWatts}W",
                    accentColor = if (wind.headwindKmh >= 0) Color(0xFFF97316) else Color(0xFF10B981),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "VIENTO CRUZADO",
                    value = "${abs(wind.crosswindKmh)} km/h",
                    subValue = "Ángulo ${wind.relativeWindAngleDeg}°",
                    accentColor = Color(0xFF2DD4BF),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
            }

            // Strava Segment & Fueling Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val ahead = metrics.segmentDeltaSeconds <= 0
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "STRAVA SEGMENT",
                    value = "${if (ahead) "" else "+"}${metrics.segmentDeltaSeconds}s",
                    subValue = if (ahead) "AHEAD OF PR" else "BEHIND PR",
                    accentColor = if (ahead) Color(0xFF10B981) else Color(0xFFF97316),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "NUTRICIÓN IA",
                    value = "Gel ${ai.nextCarbIntakeMinutes}m • Agua ${ai.nextHydrationMinutes}m",
                    subValue = "${ai.recommendedCarbsGramsPerHour}g/h • ${ai.recommendedHydrationMlPerHour}ml/h",
                    accentColor = Color(0xFF60A5FA),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
            }
        }
    }
}

@Composable
private fun RouteMapPageContent(
    metrics: CyclingMetricsSnapshot,
    wind: WindVectorReport,
    ai: AiHeuristicEvaluation,
    mapZoom: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    mapWindColorMode: Boolean,
    onToggleColorMode: () -> Unit,
    showClimber: Boolean,
    onToggleClimber: () -> Unit,
    daylightMode: Boolean,
    cardBg: Color,
    borderCol: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // Turn-by-Turn Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B1220), RoundedCornerShape(10.dp))
                .border(2.dp, Color(0xFFFACC15), RoundedCornerShape(10.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "↱ 340 m  •  Carr. 2 Presa de la Amistad",
                    color = Color(0xFFFACC15),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "Viento: ${wind.headwindKmh} km/h (${wind.relativeWindAngleDeg}°) • ${if (wind.aeroPowerImpactWatts >= 0) "+" else ""}${wind.aeroPowerImpactWatts}W",
                    color = Color(0xFF2DD4BF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "18.8 km",
                color = Color(0xFF10B981),
                fontSize = 13.sp,
                fontWeight = FontWeight.Black
            )
        }

        // Map Interactive Control Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                    .clickable { onZoomIn() }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text("Zoom +", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(
                modifier = Modifier
                    .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                    .clickable { onZoomOut() }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text("Zoom −", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(
                modifier = Modifier
                    .background(Color(0xFF0F766E), RoundedCornerShape(6.dp))
                    .clickable { onToggleColorMode() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (mapWindColorMode) "Color: Viento" else "Color: Pendiente",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Box(
                modifier = Modifier
                    .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                    .clickable { onToggleClimber() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("⛰️ Climber", color = Color(0xFFFACC15), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        // Vector Map Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(if (daylightMode) Color(0xFFE2E8F0) else Color(0xFF070D17), RoundedCornerShape(10.dp))
                .border(1.dp, borderCol, RoundedCornerShape(10.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Grid
                val step = (40f * mapZoom).coerceAtLeast(20f)
                var x = 0f
                while (x < w) {
                    drawLine(Color(0x2264748B), Offset(x, 0f), Offset(x, h))
                    x += step
                }
                var y = 0f
                while (y < h) {
                    drawLine(Color(0x2264748B), Offset(0f, y), Offset(w, y))
                    y += step
                }

                // Route loop scaled by mapZoom
                val rawPts = listOf(
                    0.20f to 0.84f,
                    0.30f to 0.70f,
                    0.45f to 0.60f,
                    0.60f to 0.46f,
                    0.70f to 0.30f,
                    0.56f to 0.18f,
                    0.35f to 0.24f,
                    0.22f to 0.46f,
                    0.20f to 0.84f
                )
                val pts = rawPts.map { (rx, ry) ->
                    Offset(
                        w * 0.5f + (rx - 0.5f) * w * mapZoom,
                        h * 0.5f + (ry - 0.5f) * h * mapZoom
                    )
                }

                val routePath = Path().apply {
                    pts.forEachIndexed { i, pt ->
                        if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                    }
                }
                drawPath(routePath, Color(0xFF020617), style = Stroke(width = 14f))

                for (i in 0 until pts.size - 1) {
                    val segColor = if (mapWindColorMode) {
                        when (i % 3) {
                            0 -> Color(0xFFF97316) // Headwind
                            1 -> Color(0xFF38BDF8) // Crosswind
                            else -> Color(0xFF10B981) // Tailwind
                        }
                    } else {
                        if (i % 2 == 0) Color(0xFFFACC15) else Color(0xFFF97316)
                    }
                    drawLine(segColor, pts[i], pts[i + 1], strokeWidth = 8f)
                }

                // Rider live position marker
                val riderPt = pts[3]
                drawCircle(Color(0x44FACC15), radius = 24f, center = riderPt)
                drawCircle(Color(0xFFFACC15), radius = 9f, center = riderPt)
            }
        }

        // Climber Drawer
        if (showClimber) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B101B), RoundedCornerShape(8.dp))
                    .border(1.dp, borderCol, RoundedCornerShape(8.dp))
                    .padding(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "⛰️ CLIMBER • PENDIENTE ${metrics.gradePercent}%",
                        color = Color(0xFFFACC15),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "VAM ${metrics.vamMetersPerHour} m/h",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                    val w = size.width
                    val h = size.height
                    drawLine(Color(0xFFFACC15), Offset(0f, h * 0.85f), Offset(w * 0.35f, h * 0.55f), strokeWidth = 3f)
                    drawLine(Color(0xFFFACC15), Offset(w * 0.35f, h * 0.55f), Offset(w * 0.7f, h * 0.25f), strokeWidth = 3f)
                    drawLine(Color(0xFFFACC15), Offset(w * 0.7f, h * 0.25f), Offset(w, h * 0.15f), strokeWidth = 3f)
                    drawCircle(Color(0xFF38BDF8), radius = 5f, center = Offset(w * 0.45f, h * 0.45f))
                }
            }
        }

        // Bottom 3 Glanceable Map Data Tiles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "3S POWER",
                value = "${metrics.power3s}W",
                subValue = metrics.powerZoneName,
                accentColor = Color(0xFFFACC15),
                cardBg = cardBg,
                borderCol = borderCol,
                secondaryText = Color(0xFF94A3B8)
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "SPEED",
                value = "${metrics.speedKmh}",
                subValue = "km/h",
                accentColor = Color(0xFF10B981),
                cardBg = cardBg,
                borderCol = borderCol,
                secondaryText = Color(0xFF94A3B8)
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "AI TARGET",
                value = "${ai.adaptiveTargetWatts}W",
                subValue = "Óptimo",
                accentColor = Color(0xFF38BDF8),
                cardBg = cardBg,
                borderCol = borderCol,
                secondaryText = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
private fun WindRadarPageContent(
    wind: WindVectorReport,
    cardBg: Color,
    borderCol: Color,
    primaryText: Color,
    secondaryText: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "RADAR DE VIENTO OPEN-METEO • ${wind.windImpactLabel}",
            color = Color(0xFF2DD4BF),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black
        )

        // Compass Rose Canvas
        Box(
            modifier = Modifier
                .size(170.dp)
                .background(cardBg, RoundedCornerShape(85.dp))
                .border(2.dp, Color(0xFF14B8A6), RoundedCornerShape(85.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = size.minDimension * 0.38f
                drawCircle(Color(0x3314B8A6), radius = r, center = Offset(cx, cy), style = Stroke(width = 3f))

                val rad = (wind.relativeWindAngleDeg - 90) * PI / 180.0
                val tipX = cx + (r * cos(rad)).toFloat()
                val tipY = cy + (r * sin(rad)).toFloat()
                drawLine(Color(0xFF2DD4BF), Offset(cx, cy), Offset(tipX, tipY), strokeWidth = 6f)
                drawCircle(Color(0xFFFACC15), radius = 7f, center = Offset(cx, cy))
            }
            Text(
                text = "${wind.relativeWindAngleDeg}°",
                color = primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MetricTile(
                modifier = Modifier.weight(1f),
                label = if (wind.headwindKmh >= 0) "VIENTO FRONTAL" else "VIENTO A FAVOR",
                value = "${abs(wind.headwindKmh)} km/h",
                subValue = "Impacto Aero: ${if (wind.aeroPowerImpactWatts >= 0) "+" else ""}${wind.aeroPowerImpactWatts}W",
                accentColor = Color(0xFFF97316),
                cardBg = cardBg,
                borderCol = borderCol,
                secondaryText = secondaryText
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "VIENTO CRUZADO",
                value = "${abs(wind.crosswindKmh)} km/h",
                subValue = "Rachas ${wind.windGustsKmh} km/h",
                accentColor = Color(0xFF38BDF8),
                cardBg = cardBg,
                borderCol = borderCol,
                secondaryText = secondaryText
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "TEMPERATURA",
                value = "${wind.temperatureC}°C",
                subValue = "Open-Meteo Live",
                accentColor = Color(0xFFFACC15),
                cardBg = cardBg,
                borderCol = borderCol,
                secondaryText = secondaryText
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "HUMEDAD / LLUVIA",
                value = "${wind.humidityPct}%",
                subValue = "${wind.precipitationMm} mm",
                accentColor = Color(0xFF2DD4BF),
                cardBg = cardBg,
                borderCol = borderCol,
                secondaryText = secondaryText
            )
        }
    }
}

@Composable
private fun AiCoachPageContent(
    metrics: CyclingMetricsSnapshot,
    wind: WindVectorReport,
    ai: AiHeuristicEvaluation,
    carbsLogged: Int,
    hydrationMl: Int,
    onAddGel: () -> Unit,
    onAddSip: () -> Unit,
    cardBg: Color,
    borderCol: Color,
    primaryText: Color,
    secondaryText: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "ENTRENADOR IA ON-DEVICE (100% OFFLINE)",
            color = Color(0xFF60A5FA),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "POTENCIA OBJETIVO IA",
                value = "${ai.adaptiveTargetWatts}W",
                subValue = "Actual 3s: ${metrics.power3s}W",
                accentColor = Color(0xFFFACC15),
                cardBg = cardBg,
                borderCol = borderCol,
                secondaryText = secondaryText
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "RIESGO DE PÁJARA",
                value = "${ai.bonkRiskPercent}%",
                subValue = ai.bonkStatusLabel,
                accentColor = Color(0xFF10B981),
                cardBg = cardBg,
                borderCol = borderCol,
                secondaryText = secondaryText
            )
        }

        // Interactive Nutrition & Hydration Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(8.dp))
                    .border(1.dp, borderCol, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("CARBOHIDRATOS", color = secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("${carbsLogged}g registrados", color = primaryText, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Text("Meta: ${ai.recommendedCarbsGramsPerHour}g/h", color = Color(0xFFF59E0B), fontSize = 10.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFD97706), RoundedCornerShape(6.dp))
                        .clickable { onAddGel() }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+25g Gel Energético", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(8.dp))
                    .border(1.dp, borderCol, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("HIDRATACIÓN", color = secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("${hydrationMl}ml bebidos", color = primaryText, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Text("Meta: ${ai.recommendedHydrationMlPerHour}ml/h", color = Color(0xFF38BDF8), fontSize = 10.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0284C7), RoundedCornerShape(6.dp))
                        .clickable { onAddSip() }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+150ml Sorbo Agua", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(8.dp))
                .border(1.dp, borderCol, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Text("DIRECTIVA TÁCTICA ACTIVA", color = Color(0xFF60A5FA), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            Text(ai.tacticalHeadline, color = primaryText, fontSize = 13.sp, fontWeight = FontWeight.Black)
            Text(ai.tacticalReason, color = secondaryText, fontSize = 11.sp)
        }
    }
}

@Composable
fun MetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    subValue: String,
    accentColor: Color,
    cardBg: Color = Color(0xFF161F30),
    borderCol: Color = Color(0xFF1E293B),
    secondaryText: Color = Color(0xFF94A3B8)
) {
    Column(
        modifier = modifier
            .background(cardBg, RoundedCornerShape(8.dp))
            .border(1.dp, borderCol, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = secondaryText, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        Text(text = value, color = accentColor, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(text = subValue, color = secondaryText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}
