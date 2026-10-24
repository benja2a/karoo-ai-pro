package io.hammerhead.karooaipro.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.hammerhead.karooaipro.engine.AiHeuristicEvaluation
import io.hammerhead.karooaipro.engine.CyclingMetricsSnapshot
import io.hammerhead.karooaipro.engine.WindVectorReport
import io.hammerhead.karooaipro.extension.LiveSensorDiagnostics
import io.hammerhead.karooaipro.extension.SharedTelemetryBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/**
 * Asynchronous Slippy-Map Tile Downloader & Memory Cache for the Native 3D Perspective Map.
 * Supports OpenStreetMap (Calles), Esri World Imagery (Satélite 3D), and OpenTopoMap (Topográfico).
 */
object LiveTileMapEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tileCache = ConcurrentHashMap<String, ImageBitmap>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val versionCounter = AtomicInteger(0)

    fun tileVersion(): Int = versionCounter.get()
    fun cachedCount(): Int = tileCache.size

    fun getOrFetchTile(styleIndex: Int, z: Int, x: Int, y: Int): ImageBitmap? {
        val maxTile = (1 shl z)
        if (y < 0 || y >= maxTile) return null
        val wrappedX = ((x % maxTile) + maxTile) % maxTile
        val key = "$styleIndex:$z:$wrappedX:$y"

        tileCache[key]?.let { return it }

        if (inFlight.add(key)) {
            scope.launch {
                try {
                    val urlStr = when (styleIndex) {
                        1 -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$wrappedX"
                        2 -> "https://a.tile.opentopomap.org/$z/$wrappedX/$y.png"
                        else -> "https://tile.openstreetmap.org/$z/$wrappedX/$y.png"
                    }
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 6500
                        readTimeout = 6500
                        setRequestProperty("User-Agent", "Karoo3AiCyclingPro/1.0 (Hammerhead Karoo 3)")
                    }
                    if (conn.responseCode == 200) {
                        conn.inputStream.use { stream ->
                            val bmp = BitmapFactory.decodeStream(stream)
                            if (bmp != null) {
                                if (tileCache.size > 160) {
                                    tileCache.clear()
                                }
                                tileCache[key] = bmp.asImageBitmap()
                                versionCounter.incrementAndGet()
                            }
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return null
    }
}

class MetricsDashboardActivity : ComponentActivity() {

    private var pageState = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start KarooSystemService + internal barometer + GPS immediately
        SharedTelemetryBus.ensureStarted(applicationContext)

        // Request GPS permission on Karoo 3 if not yet granted
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                1001
            )
        }

        setContent {
            KarooMultiPageCockpit(
                currentPage = pageState.intValue,
                onSelectPage = { newPage -> pageState.intValue = (newPage + 4) % 4 }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Automatically starts internal GPS & Barometer as soon as the permission dialog closes
        SharedTelemetryBus.startInternalHardwareSensors(applicationContext)
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
    var diag by remember { mutableStateOf(SharedTelemetryBus.latestDiagnostics()) }
    var subPage by remember { mutableIntStateOf(0) }
    var daylightMode by remember { mutableStateOf(false) }
    var mapZoomLevel by remember { mutableIntStateOf(16) }
    var mapWindColorMode by remember { mutableStateOf(true) }
    var showClimber by remember { mutableStateOf(true) }
    var extraCarbsLogged by remember { mutableIntStateOf(0) }
    var extraHydrationMl by remember { mutableIntStateOf(0) }
    var elapsedRideSec by remember { mutableIntStateOf(0) }
    var tileRefreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            telemetry = SharedTelemetryBus.latestState()
            diag = SharedTelemetryBus.latestDiagnostics()
            elapsedRideSec += 1
            tileRefreshTick = LiveTileMapEngine.tileVersion()
            delay(1000L)
        }
    }

    val (metrics, wind, ai) = telemetry
    val bgColor = if (daylightMode) Color(0xFFF8FAFC) else Color(0xFF080C12)
    val cardBg = if (daylightMode) Color(0xFFFFFFFF) else Color(0xFF111827)
    val borderCol = if (daylightMode) Color(0xFFCBD5E1) else Color(0xFF1E293B)
    val primaryText = if (daylightMode) Color(0xFF0F172A) else Color(0xFFFFFFFF)
    val secondaryText = if (daylightMode) Color(0xFF475569) else Color(0xFF94A3B8)

    val pageTitles = listOf("1/4 • MÉTRICAS", "2/4 • MAPA 3D", "3/4 • VIENTO", "4/4 • IA COACH")

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(currentPage) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd = {
                        if (currentPage != 1) {
                            if (totalDrag < -60f) {
                                onSelectPage(currentPage + 1)
                            } else if (totalDrag > 60f) {
                                onSelectPage(currentPage - 1)
                            }
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

            // TOP STATUS, SENSOR DIAGNOSTICS & MODE BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D131F))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val hrs = (elapsedRideSec / 3600).toString().padStart(2, '0')
                val mins = ((elapsedRideSec % 3600) / 60).toString().padStart(2, '0')
                val secs = (elapsedRideSec % 60).toString().padStart(2, '0')
                val gpsLabel = if (diag.gpsFixActive) "GPS 3D OK" else "BUSCANDO GPS"
                Text(
                    text = "$hrs:$mins:$secs • $gpsLabel • ${diag.altitudeMeters.toInt()}m",
                    color = if (diag.gpsFixActive) Color(0xFF34D399) else Color(0xFFFACC15),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (diag.demoModeEnabled) Color(0xFFD97706) else Color(0xFF059669),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { SharedTelemetryBus.setDemoMode(!diag.demoModeEnabled) }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (diag.demoModeEnabled) "🧪 DEMO" else "🟢 REAL",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(
                                if (daylightMode) Color(0xFFFACC15) else Color(0xFF1E293B),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { daylightMode = !daylightMode }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
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

            // LIVE SENSOR CONNECTION STATUS STRIP
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111827))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (diag.karooServiceConnected) "KAROO-EXT: CONECTADO (${diag.activeStreamsCount} sensores)" else "KAROO-EXT: VINCULANDO...",
                    color = if (diag.karooServiceConnected) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (diag.barometerActive) "BARÓMETRO: ${diag.pressureHpa} hPa" else "CLIMA: ${wind.temperatureC}°C",
                    color = Color(0xFF2DD4BF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // TOP 4-PAGE SELECTOR TABS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B101B))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val tabs = listOf("Métricas", "🗺️ Mapa 3D", "🌬️ Viento", "🧠 IA")
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
                        diag = diag,
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
                        diag = diag,
                        mapZoomLevel = mapZoomLevel,
                        onZoomIn = { mapZoomLevel = (mapZoomLevel + 1).coerceAtMost(18) },
                        onZoomOut = { mapZoomLevel = (mapZoomLevel - 1).coerceAtLeast(12) },
                        mapWindColorMode = mapWindColorMode,
                        onToggleColorMode = { mapWindColorMode = !mapWindColorMode },
                        showClimber = showClimber,
                        onToggleClimber = { showClimber = !showClimber },
                        daylightMode = daylightMode,
                        tileRefreshTick = tileRefreshTick,
                        elapsedRideSec = elapsedRideSec,
                        cardBg = cardBg,
                        borderCol = borderCol
                    )
                    2 -> WindRadarPageContent(
                        wind = wind,
                        diag = diag,
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
    diag: LiveSensorDiagnostics,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "3S POWER (${metrics.powerZoneName})",
                    value = "${metrics.power3s}W",
                    subValue = "Inst: ${metrics.powerInstant}W • ${metrics.ftpPercent}% FTP",
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
                    label = "PENDIENTE / ALT",
                    value = "${metrics.gradePercent}%",
                    subValue = "${diag.altitudeMeters.toInt()}m • VAM ${metrics.vamMetersPerHour}",
                    accentColor = Color(0xFFF59E0B),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val headLabel = if (wind.headwindKmh >= 0) "VIENTO FRONTAL" else "VIENTO A FAVOR"
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = headLabel,
                    value = "${abs(wind.headwindKmh)} km/h",
                    subValue = "Aero ${if (wind.aeroPowerImpactWatts >= 0) "+" else ""}${wind.aeroPowerImpactWatts}W • Rumbo ${wind.riderHeadingDeg}°",
                    accentColor = if (wind.headwindKmh >= 0) Color(0xFFF97316) else Color(0xFF10B981),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "VIENTO CRUZADO",
                    value = "${abs(wind.crosswindKmh)} km/h",
                    subValue = "Ángulo rel. ${wind.relativeWindAngleDeg}°",
                    accentColor = Color(0xFF2DD4BF),
                    cardBg = cardBg,
                    borderCol = borderCol,
                    secondaryText = secondaryText
                )
            }

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

/**
 * Real-Time 3D GPS Map Page (`2/4 • MAPA 3D`) centered on the user's real Karoo 3 GPS coordinates.
 * Combines:
 * 1. Native 3D Perspective Slippy-Tile Map (OSM Calles, Esri Satélite 3D, OpenTopoMap) with 54° 3D tilt,
 *    Course-Up GPS heading rotation, live GPS breadcrumb trail colored by wind/grade, and 3D wind vector particles.
 * 2. WebGL 3D MapLibre Engine (`🌐 WebGL 3D`) with 3D extruded buildings & vector streets.
 */
@Composable
private fun RouteMapPageContent(
    metrics: CyclingMetricsSnapshot,
    wind: WindVectorReport,
    ai: AiHeuristicEvaluation,
    diag: LiveSensorDiagnostics,
    mapZoomLevel: Int,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    mapWindColorMode: Boolean,
    onToggleColorMode: () -> Unit,
    showClimber: Boolean,
    onToggleClimber: () -> Unit,
    daylightMode: Boolean,
    tileRefreshTick: Int,
    elapsedRideSec: Int,
    cardBg: Color,
    borderCol: Color
) {
    var is3DView by remember { mutableStateOf(true) }
    var courseUpRotation by remember { mutableStateOf(true) }
    var tileStyleIndex by remember { mutableIntStateOf(0) } // 0 = OSM Calles, 1 = Satélite 3D, 2 = Topo
    var useWebGl3D by remember { mutableStateOf(false) }

    val latStr = String.format(Locale.US, "%.5f", diag.latitude)
    val lonStr = String.format(Locale.US, "%.5f", diag.longitude)
    val tileStyleNames = listOf("🗺️ Calles", "🛰️ Satélite", "⛰️ Topo")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // LIVE REAL GPS COORDINATES & WIND NAVIGATION HEADER
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B1220), RoundedCornerShape(10.dp))
                .border(2.dp, Color(0xFFFACC15), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "📍 GPS REAL: $latStr°, $lonStr°",
                    color = Color(0xFFFACC15),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "Rumbo ${wind.riderHeadingDeg}° • Viento ${abs(wind.headwindKmh)} km/h (${if (wind.aeroPowerImpactWatts >= 0) "+" else ""}${wind.aeroPowerImpactWatts}W)",
                    color = Color(0xFF2DD4BF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${metrics.speedKmh} km/h",
                    color = Color(0xFF10B981),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "Z$mapZoomLevel • ${if (is3DView) "3D 54°" else "2D"}",
                    color = Color(0xFF93C5FD),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // MAP 3D CONTROLS & LAYER TOOLBAR
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                    .clickable { onZoomIn() }
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text("Z+", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(
                modifier = Modifier
                    .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                    .clickable { onZoomOut() }
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text("Z−", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(
                modifier = Modifier
                    .background(
                        if (is3DView) Color(0xFF2563EB) else Color(0xFF1E293B),
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { is3DView = !is3DView }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (is3DView) "📐 3D ON" else "📐 2D",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Box(
                modifier = Modifier
                    .background(Color(0xFF0F766E), RoundedCornerShape(6.dp))
                    .clickable { tileStyleIndex = (tileStyleIndex + 1) % 3 }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(
                    text = tileStyleNames[tileStyleIndex],
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Box(
                modifier = Modifier
                    .background(
                        if (courseUpRotation) Color(0xFF7C3AED) else Color(0xFF1E293B),
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { courseUpRotation = !courseUpRotation }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (courseUpRotation) "🧭 Rumbo" else "⬆️ Norte",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Box(
                modifier = Modifier
                    .background(
                        if (useWebGl3D) Color(0xFFD97706) else Color(0xFF1E293B),
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { useWebGl3D = !useWebGl3D }
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (useWebGl3D) "🌐 WebGL" else "⚡ Nativo",
                    color = Color(0xFFFACC15),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // MAIN REAL-TIME 3D VIEWPORT
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(if (daylightMode) Color(0xFFCBD5E1) else Color(0xFF050A14), RoundedCornerShape(10.dp))
                .border(1.dp, borderCol, RoundedCornerShape(10.dp))
                .clipToBounds()
        ) {
            if (useWebGl3D) {
                MapLibreWebGl3DView(
                    lat = diag.latitude,
                    lon = diag.longitude,
                    bearingDeg = if (courseUpRotation) wind.riderHeadingDeg else 0,
                    zoom = mapZoomLevel,
                    pitchDeg = if (is3DView) 62 else 0,
                    trackPoints = diag.trackPoints
                )
            } else {
                // NATIVE 3D PERSPECTIVE SLIPPY-TILE & TERRAIN RADAR SURFACE
                val n = (1 shl mapZoomLevel).toDouble()
                val safeLat = diag.latitude.coerceIn(-85.0, 85.0)
                val latRad = safeLat * PI / 180.0
                val exactTileX = (diag.longitude + 180.0) / 360.0 * n
                val exactTileY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
                val centerTileX = floor(exactTileX).toInt()
                val centerTileY = floor(exactTileY).toInt()
                val offsetPxX = ((exactTileX - centerTileX) * 256.0).toFloat()
                val offsetPxY = ((exactTileY - centerTileY) * 256.0).toFloat()
                val mapRotationDeg = if (courseUpRotation) -wind.riderHeadingDeg.toFloat() else 0f

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationX = if (is3DView) 54f else 0f
                            scaleX = if (is3DView) 1.35f else 1.02f
                            scaleY = if (is3DView) 1.35f else 1.02f
                            cameraDistance = 15f * density
                        }
                ) {
                    // Read tileRefreshTick so Compose invalidates Canvas whenever a new tile downloads
                    val ignoredTick = tileRefreshTick
                    val cx = size.width / 2f
                    val cy = size.height / 2f

                    rotate(degrees = mapRotationDeg, pivot = Offset(cx, cy)) {
                        val baseLeft = cx - offsetPxX
                        val baseTop = cy - offsetPxY

                        // 1. Draw Real Downloaded 256x256 Map Tiles around Rider's Real Coordinates
                        for (dy in -2..2) {
                            for (dx in -2..2) {
                                val tileLeft = baseLeft + dx * 256f
                                val tileTop = baseTop + dy * 256f
                                val bmp = LiveTileMapEngine.getOrFetchTile(
                                    styleIndex = tileStyleIndex,
                                    z = mapZoomLevel,
                                    x = centerTileX + dx,
                                    y = centerTileY + dy
                                )
                                if (bmp != null) {
                                    drawImage(
                                        image = bmp,
                                        topLeft = Offset(tileLeft, tileTop)
                                    )
                                } else {
                                    // Dynamic 3D Coordinate Terrain Grid while tile downloads or when offline
                                    val step = 64f
                                    for (gx in 0..3) {
                                        val lx = tileLeft + gx * step
                                        drawLine(
                                            color = Color(0x3338BDF8),
                                            start = Offset(lx, tileTop),
                                            end = Offset(lx, tileTop + 256f),
                                            strokeWidth = 1.2f
                                        )
                                    }
                                    for (gy in 0..3) {
                                        val ly = tileTop + gy * step
                                        drawLine(
                                            color = Color(0x3338BDF8),
                                            start = Offset(tileLeft, ly),
                                            end = Offset(tileLeft + 256f, ly),
                                            strokeWidth = 1.2f
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Draw Real-Time 3D Wind Streamlines across the Map
                        val windRad = (wind.trueWindDirectionDeg + 180) * PI / 180.0
                        val windDx = sin(windRad).toFloat()
                        val windDy = -cos(windRad).toFloat()
                        val phase = ((elapsedRideSec % 10) / 10f) * 80f
                        for (row in -2..2) {
                            for (col in -2..2) {
                                val sx = cx + col * 110f + windDx * phase
                                val sy = cy + row * 110f + windDy * phase
                                drawLine(
                                    color = Color(0x662DD4BF),
                                    start = Offset(sx, sy),
                                    end = Offset(sx + windDx * 28f, sy + windDy * 28f),
                                    strokeWidth = 2.5f
                                )
                            }
                        }

                        // 3. Draw Real Recorded GPS Breadcrumb Trail in 3D Perspective
                        val pts = diag.trackPoints
                        if (pts.size >= 2) {
                            for (i in 0 until pts.size - 1) {
                                val p1 = pts[i]
                                val p2 = pts[i + 1]
                                val p1LatRad = p1.lat.coerceIn(-85.0, 85.0) * PI / 180.0
                                val p2LatRad = p2.lat.coerceIn(-85.0, 85.0) * PI / 180.0

                                val p1Tx = (p1.lon + 180.0) / 360.0 * n
                                val p1Ty = (1.0 - ln(tan(p1LatRad) + 1.0 / cos(p1LatRad)) / PI) / 2.0 * n
                                val p2Tx = (p2.lon + 180.0) / 360.0 * n
                                val p2Ty = (1.0 - ln(tan(p2LatRad) + 1.0 / cos(p2LatRad)) / PI) / 2.0 * n

                                val x1 = cx + ((p1Tx - exactTileX) * 256.0).toFloat()
                                val y1 = cy + ((p1Ty - exactTileY) * 256.0).toFloat()
                                val x2 = cx + ((p2Tx - exactTileX) * 256.0).toFloat()
                                val y2 = cy + ((p2Ty - exactTileY) * 256.0).toFloat()

                                // 3D Ground Casing Shadow
                                drawLine(
                                    color = Color(0xAA020617),
                                    start = Offset(x1, y1 + 4f),
                                    end = Offset(x2, y2 + 4f),
                                    strokeWidth = 12f
                                )

                                val segColor = if (mapWindColorMode) {
                                    when {
                                        p2.headwindKmh >= 8.0 -> Color(0xFFF97316) // Headwind (Orange)
                                        p2.headwindKmh <= -5.0 -> Color(0xFF10B981) // Tailwind (Emerald)
                                        else -> Color(0xFF38BDF8) // Crosswind (Sky)
                                    }
                                } else {
                                    when {
                                        p2.gradePct >= 4.0 -> Color(0xFFEF4444)
                                        p2.gradePct >= 1.5 -> Color(0xFFFACC15)
                                        else -> Color(0xFF10B981)
                                    }
                                }

                                drawLine(
                                    color = segColor,
                                    start = Offset(x1, y1),
                                    end = Offset(x2, y2),
                                    strokeWidth = 7f
                                )
                            }
                        }

                        // 4. 3D Compass & Distance Ring around Real Rider Coordinates
                        drawCircle(
                            color = Color(0x4438BDF8),
                            radius = 75f,
                            center = Offset(cx, cy),
                            style = Stroke(width = 2f)
                        )
                        drawCircle(
                            color = Color(0x33FACC15),
                            radius = 30f,
                            center = Offset(cx, cy)
                        )
                    }

                    // 5. 3D Volumetric Cyclist Chevron & Forward Heading Cone (Always at Center)
                    val chevronRotation = if (courseUpRotation) 0f else wind.riderHeadingDeg.toFloat()
                    rotate(degrees = chevronRotation, pivot = Offset(cx, cy)) {
                        // Forward 3D Radar Cone
                        val conePath = Path().apply {
                            moveTo(cx, cy)
                            lineTo(cx - 34f, cy - 85f)
                            lineTo(cx + 34f, cy - 85f)
                            close()
                        }
                        drawPath(conePath, Color(0x28FACC15))

                        // 3D Drop Shadow under Chevron
                        val shadowPath = Path().apply {
                            moveTo(cx, cy - 12f)
                            lineTo(cx + 14f, cy + 18f)
                            lineTo(cx, cy + 10f)
                            lineTo(cx - 14f, cy + 18f)
                            close()
                        }
                        drawPath(shadowPath, Color(0xAA000000))

                        // 3D Extruded Navigation Chevron
                        val chevronPath = Path().apply {
                            moveTo(cx, cy - 18f)
                            lineTo(cx + 14f, cy + 12f)
                            lineTo(cx, cy + 4f)
                            lineTo(cx - 14f, cy + 12f)
                            close()
                        }
                        drawPath(chevronPath, Color(0xFFFACC15))
                        drawPath(chevronPath, Color(0xFF0F172A), style = Stroke(width = 2.5f))
                    }
                }
            }

            // FLOATING 3D HORIZON SKY GRADIENT & OVERLAY BADGES
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color(0x99080C12))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Puntos GPS: ${diag.trackPoints.size} • Tiles: ${LiveTileMapEngine.cachedCount()}",
                    color = Color(0xFFCBD5E1),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (mapWindColorMode) "🎨 Ruta: Viento" else "🎨 Ruta: Pendiente",
                        color = Color(0xFF2DD4BF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.clickable { onToggleColorMode() }
                    )
                    Text(
                        text = if (showClimber) "⛰️ Ocultar Climber" else "⛰️ Ver Climber",
                        color = Color(0xFFFACC15),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.clickable { onToggleClimber() }
                    )
                }
            }
        }

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
                        text = "⛰️ CLIMBER • PENDIENTE REAL ${metrics.gradePercent}% • ALT ${diag.altitudeMeters.toInt()}m",
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
                Canvas(modifier = Modifier.fillMaxWidth().height(22.dp)) {
                    val w = size.width
                    val h = size.height
                    val pts = diag.trackPoints
                    if (pts.size >= 2) {
                        val minAlt = pts.minOf { it.altitudeMeters }
                        val maxAlt = (pts.maxOf { it.altitudeMeters }).coerceAtLeast(minAlt + 5.0)
                        for (i in 0 until pts.size - 1) {
                            val x1 = (i.toFloat() / (pts.size - 1).coerceAtLeast(1)) * w
                            val x2 = ((i + 1).toFloat() / (pts.size - 1).coerceAtLeast(1)) * w
                            val y1 = h - (((pts[i].altitudeMeters - minAlt) / (maxAlt - minAlt)).toFloat() * (h * 0.8f) + h * 0.1f)
                            val y2 = h - (((pts[i + 1].altitudeMeters - minAlt) / (maxAlt - minAlt)).toFloat() * (h * 0.8f) + h * 0.1f)
                            drawLine(Color(0xFFFACC15), Offset(x1, y1), Offset(x2, y2), strokeWidth = 3f)
                        }
                        drawCircle(Color(0xFF38BDF8), radius = 4.5f, center = Offset(w - 4f, h * 0.45f))
                    } else {
                        drawLine(Color(0xFFFACC15), Offset(0f, h * 0.7f), Offset(w, h * 0.5f), strokeWidth = 3f)
                        drawCircle(Color(0xFF38BDF8), radius = 4.5f, center = Offset(w * 0.5f, h * 0.6f))
                    }
                }
            }
        }

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
                label = "SPEED / DIST",
                value = "${metrics.speedKmh}",
                subValue = "${metrics.distanceKm} km",
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

/**
 * Embedded MapLibre GL JS 3D WebGL View (`🌐 WebGL`) with 62° 3D camera pitch,
 * 3D extruded buildings (`OpenFreeMap Liberty`), and real-time GPS coordinate tracking.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapLibreWebGl3DView(
    lat: Double,
    lon: Double,
    bearingDeg: Int,
    zoom: Int,
    pitchDeg: Int,
    trackPoints: List<io.hammerhead.karooaipro.extension.GpsTrackPoint>
) {
    val htmlTemplate = remember {
        """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no" />
          <link href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" rel="stylesheet" />
          <script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
          <style>
            html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #070d17; overflow: hidden; }
            .rider-marker {
              width: 22px; height: 22px; border-radius: 50%;
              background: #facc15; border: 3px solid #0f172a;
              box-shadow: 0 0 16px rgba(250, 204, 21, 0.95);
            }
          </style>
        </head>
        <body>
          <div id="map"></div>
          <script>
            let mapReady = false;
            const map = new maplibregl.Map({
              container: 'map',
              style: 'https://tiles.openfreemap.org/styles/liberty',
              center: [$lon, $lat],
              zoom: $zoom,
              pitch: $pitchDeg,
              bearing: $bearingDeg,
              attributionControl: false
            });
            const el = document.createElement('div');
            el.className = 'rider-marker';
            const marker = new maplibregl.Marker({ element: el }).setLngLat([$lon, $lat]).addTo(map);

            map.on('load', () => {
              mapReady = true;
              map.addSource('rider-track', {
                type: 'geojson',
                data: { type: 'Feature', geometry: { type: 'LineString', coordinates: [[$lon, $lat]] } }
              });
              map.addLayer({
                id: 'rider-track-line',
                type: 'line',
                source: 'rider-track',
                paint: { 'line-color': '#10b981', 'line-width': 6 }
              });
            });

            window.updateKaroo3DMap = function(newLat, newLon, newBearing, newZoom, newPitch, coordsJson) {
              marker.setLngLat([newLon, newLat]);
              map.easeTo({
                center: [newLon, newLat],
                bearing: newBearing,
                zoom: newZoom,
                pitch: newPitch,
                duration: 650
              });
              if (mapReady && coordsJson) {
                const src = map.getSource('rider-track');
                if (src) {
                  src.setData({ type: 'Feature', geometry: { type: 'LineString', coordinates: JSON.parse(coordsJson) } });
                }
              }
            };
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://tiles.openfreemap.org/", htmlTemplate, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            val coordsJson = if (trackPoints.size >= 2) {
                trackPoints.takeLast(120).joinToString(prefix = "[", postfix = "]") { "[${it.lon},${it.lat}]" }
            } else {
                "[[$lon,$lat]]"
            }
            val js = "if(window.updateKaroo3DMap){window.updateKaroo3DMap($lat,$lon,$bearingDeg,$zoom,$pitchDeg,'$coordsJson');}"
            webView.evaluateJavascript(js, null)
        }
    )
}

@Composable
private fun WindRadarPageContent(
    wind: WindVectorReport,
    diag: LiveSensorDiagnostics,
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

        Box(
            modifier = Modifier
                .size(160.dp)
                .background(cardBg, RoundedCornerShape(80.dp))
                .border(2.dp, Color(0xFF14B8A6), RoundedCornerShape(80.dp)),
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
                text = "Rel: ${wind.relativeWindAngleDeg}°",
                color = primaryText,
                fontSize = 14.sp,
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
                label = "ALTITUD BAROMÉTRICA",
                value = "${diag.altitudeMeters} m",
                subValue = "${diag.pressureHpa} hPa",
                accentColor = Color(0xFFFACC15),
                cardBg = cardBg,
                borderCol = borderCol,
                secondaryText = secondaryText
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "TEMP / HUMEDAD",
                value = "${wind.temperatureC}°C",
                subValue = "${wind.humidityPct}% RH • ${wind.precipitationMm} mm",
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
