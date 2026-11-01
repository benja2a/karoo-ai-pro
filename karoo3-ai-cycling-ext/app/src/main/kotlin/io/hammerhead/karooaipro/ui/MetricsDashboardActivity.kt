package io.hammerhead.karooaipro.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
 * Reliable Slippy-Map Tile Downloader & Memory Cache for Karoo 3.
 * Uses `readBytes()` + `BitmapFactory.decodeByteArray` so Android network streams never return null,
 * and fetches from mobile-friendly Carto Voyager, Esri World Imagery (3D Satellite), and Esri Topo servers.
 */
object LiveTileMapEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tileCache = ConcurrentHashMap<String, ImageBitmap>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val versionCounter = AtomicInteger(0)

    fun tileVersion(): Int = versionCounter.get()

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
                        2 -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/$z/$y/$wrappedX"
                        else -> "https://a.basemaps.cartocdn.com/rastertiles/voyager/$z/$wrappedX/$y.png"
                    }
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 7000
                        readTimeout = 7000
                        doInput = true
                        setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 12; Karoo3) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                        )
                    }
                    if (conn.responseCode == 200) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        if (bytes.isNotEmpty()) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                if (tileCache.size > 140) tileCache.clear()
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
    private var mapZoomState = mutableIntStateOf(16)
    private var mapStyleState = mutableIntStateOf(0)
    private var map3DState = mutableStateOf(true)
    private var daylightState = mutableStateOf(false)
    private var carbsLoggedState = mutableIntStateOf(0)
    private var hydrationMlState = mutableIntStateOf(0)
    private var lastKeyToastState = mutableStateOf("")
    private var lastBackPressMs: Long = 0L

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
            KarooCleanCockpit(
                currentPage = pageState.intValue,
                onSelectPage = { newPage -> pageState.intValue = (newPage + 4) % 4 },
                mapZoomLevel = mapZoomState.intValue,
                onChangeZoom = { newZoom -> mapZoomState.intValue = newZoom.coerceIn(13, 18) },
                mapStyleIndex = mapStyleState.intValue,
                onCycleMapStyle = { mapStyleState.intValue = (mapStyleState.intValue + 1) % 3 },
                is3DView = map3DState.value,
                onToggle3D = { map3DState.value = !map3DState.value },
                daylightMode = daylightState.value,
                onToggleDaylight = { daylightState.value = !daylightState.value },
                carbsLogged = carbsLoggedState.intValue,
                onAddGel = { carbsLoggedState.intValue += 25 },
                hydrationMl = hydrationMlState.intValue,
                onAddSip = { hydrationMlState.intValue += 150 },
                hardwareKeyToast = lastKeyToastState.value
            )
        }
    }

    override fun onResume() {
        super.onResume()
        SharedTelemetryBus.startInternalHardwareSensors(applicationContext)
    }

    /**
     * Intercepts physical button presses on the Hammerhead Karoo 3 at the Window level
     * BEFORE Compose or child views can swallow them.
     * Supports standard Android KeyCodes AND Karoo hardware GPIO scanCodes.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val code = event.keyCode
            val scan = event.scanCode

            // Do not intercept system Power or Home keys
            if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_HOME) {
                return super.dispatchKeyEvent(event)
            }

            when {
                // 1. Karoo Top-Left / Previous Page Buttons
                code == KeyEvent.KEYCODE_DPAD_LEFT ||
                code == KeyEvent.KEYCODE_DPAD_UP ||
                code == KeyEvent.KEYCODE_VOLUME_UP ||
                code == KeyEvent.KEYCODE_PAGE_UP ||
                code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                code == KeyEvent.KEYCODE_NAVIGATE_PREVIOUS ||
                code == KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT ||
                code == KeyEvent.KEYCODE_BUTTON_L1 ||
                code == KeyEvent.KEYCODE_F1 ||
                code == KeyEvent.KEYCODE_SOFT_LEFT -> {
                    pageState.intValue = (pageState.intValue + 3) % 4
                    lastKeyToastState.value = "◀ PÁGINA ${pageState.intValue + 1}/4"
                    return true
                }

                // 2. Karoo Top-Right / Next Page Buttons
                code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_DOWN ||
                code == KeyEvent.KEYCODE_VOLUME_DOWN ||
                code == KeyEvent.KEYCODE_PAGE_DOWN ||
                code == KeyEvent.KEYCODE_MEDIA_NEXT ||
                code == KeyEvent.KEYCODE_NAVIGATE_NEXT ||
                code == KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT ||
                code == KeyEvent.KEYCODE_BUTTON_R1 ||
                code == KeyEvent.KEYCODE_F2 ||
                code == KeyEvent.KEYCODE_SOFT_RIGHT ||
                code == KeyEvent.KEYCODE_STEM_1 ||
                code == KeyEvent.KEYCODE_STEM_2 ||
                code == KeyEvent.KEYCODE_STEM_3 -> {
                    pageState.intValue = (pageState.intValue + 1) % 4
                    lastKeyToastState.value = "PÁGINA ${pageState.intValue + 1}/4 ▶"
                    return true
                }

                // 3. Karoo Bottom-Right / Confirm / Contextual Action Button
                code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_DPAD_CENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                code == KeyEvent.KEYCODE_SPACE ||
                code == KeyEvent.KEYCODE_BUTTON_A ||
                code == KeyEvent.KEYCODE_BUTTON_SELECT ||
                code == KeyEvent.KEYCODE_BUTTON_R2 ||
                code == KeyEvent.KEYCODE_F3 ||
                code == KeyEvent.KEYCODE_F4 -> {
                    triggerContextualButtonAction()
                    return true
                }

                // 4. Karoo Bottom-Left / Back Button: Navigate back across pages; double-press on Page 1 to exit
                code == KeyEvent.KEYCODE_BACK ||
                code == KeyEvent.KEYCODE_ESCAPE ||
                code == KeyEvent.KEYCODE_BUTTON_B ||
                code == KeyEvent.KEYCODE_BUTTON_L2 -> {
                    val now = System.currentTimeMillis()
                    if (pageState.intValue > 0) {
                        pageState.intValue = (pageState.intValue + 3) % 4
                        lastKeyToastState.value = "◀ PÁGINA ${pageState.intValue + 1}/4"
                        return true
                    } else if (now - lastBackPressMs > 1800L) {
                        lastBackPressMs = now
                        lastKeyToastState.value = "PULSA ATRÁS OTRA VEZ PARA SALIR"
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }

                // 5. Fallback for custom Karoo 3 GPIO hardware scanCodes
                scan > 0 -> {
                    if (scan % 2 == 0) {
                        pageState.intValue = (pageState.intValue + 1) % 4
                        lastKeyToastState.value = "BOTÓN [$scan] ▶ PÁG ${pageState.intValue + 1}/4"
                    } else {
                        pageState.intValue = (pageState.intValue + 3) % 4
                        lastKeyToastState.value = "◀ PÁG ${pageState.intValue + 1}/4 [$scan]"
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun triggerContextualButtonAction() {
        when (pageState.intValue) {
            0 -> {
                val nextDemo = !SharedTelemetryBus.isDemoMode()
                SharedTelemetryBus.setDemoMode(nextDemo)
                lastKeyToastState.value = if (nextDemo) "MODO DEMO ACTIVADO" else "SENSORES REALES ACTIVOS"
            }
            1 -> {
                val nextZoom = if (mapZoomState.intValue >= 18) 14 else mapZoomState.intValue + 1
                mapZoomState.intValue = nextZoom
                lastKeyToastState.value = "MAPA 3D ZOOM Z$nextZoom"
            }
            2 -> {
                daylightState.value = !daylightState.value
                lastKeyToastState.value = if (daylightState.value) "MODO DÍA ☀️" else "MODO NOCHE 🌙"
            }
            3 -> {
                carbsLoggedState.intValue += 25
                hydrationMlState.intValue += 150
                lastKeyToastState.value = "+25g GEL Y +150ml AGUA"
            }
        }
    }
}

@Composable
fun KarooCleanCockpit(
    currentPage: Int,
    onSelectPage: (Int) -> Unit,
    mapZoomLevel: Int,
    onChangeZoom: (Int) -> Unit,
    mapStyleIndex: Int,
    onCycleMapStyle: () -> Unit,
    is3DView: Boolean,
    onToggle3D: () -> Unit,
    daylightMode: Boolean,
    onToggleDaylight: () -> Unit,
    carbsLogged: Int,
    onAddGel: () -> Unit,
    hydrationMl: Int,
    onAddSip: () -> Unit,
    hardwareKeyToast: String
) {
    var telemetry by remember { mutableStateOf(SharedTelemetryBus.latestState()) }
    var diag by remember { mutableStateOf(SharedTelemetryBus.latestDiagnostics()) }
    var elapsedRideSec by remember { mutableIntStateOf(0) }
    var tileRefreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            telemetry = SharedTelemetryBus.latestState()
            diag = SharedTelemetryBus.latestDiagnostics()
            elapsedRideSec += 1
            tileRefreshTick = LiveTileMapEngine.tileVersion()
            val extCmd = SharedTelemetryBus.consumePageCommand()
            if (extCmd in 0..3) {
                onSelectPage(extCmd)
            }
            delay(1000L)
        }
    }

    val (metrics, wind, ai) = telemetry
    val bgColor = if (daylightMode) Color(0xFFF1F5F9) else Color(0xFF070B12)
    val cardBg = if (daylightMode) Color(0xFFFFFFFF) else Color(0xFF111827)
    val borderCol = if (daylightMode) Color(0xFFCBD5E1) else Color(0xFF1E293B)
    val primaryText = if (daylightMode) Color(0xFF0F172A) else Color(0xFFFFFFFF)
    val secondaryText = if (daylightMode) Color(0xFF475569) else Color(0xFF94A3B8)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(currentPage) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd = {
                        if (totalDrag < -55f) {
                            onSelectPage(currentPage + 1)
                        } else if (totalDrag > 55f) {
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

            // 1. ULTRA-CLEAN COMPACT STATUS BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B111E))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val mins = (elapsedRideSec / 60).toString().padStart(2, '0')
                val secs = (elapsedRideSec % 60).toString().padStart(2, '0')
                val statusText = if (hardwareKeyToast.isNotEmpty()) {
                    hardwareKeyToast
                } else {
                    val gpsTag = if (diag.gpsFixActive) "● GPS OK" else "◌ GPS"
                    "$mins:$secs • $gpsTag • ${diag.altitudeMeters.toInt()}m"
                }

                Text(
                    text = statusText,
                    color = if (hardwareKeyToast.isNotEmpty()) Color(0xFFFACC15) else Color(0xFF34D399),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (diag.demoModeEnabled) Color(0xFFD97706) else Color(0xFF059669),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { SharedTelemetryBus.setDemoMode(!diag.demoModeEnabled) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (diag.demoModeEnabled) "DEMO" else "REAL",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                            .clickable { onToggleDaylight() }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (daylightMode) "☀️" else "🌙",
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // 2. CLEAN 4-TAB NAVIGATION BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val tabs = listOf("1. DATOS", "2. MAPA 3D", "3. VIENTO", "4. IA PRO")
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
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            // 3. MAIN PAGE CONTENT (4 UNCLUTTERED PRO SCREENS)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (currentPage) {
                    0 -> EssentialRideMetricsPage(
                        metrics = metrics,
                        ai = ai,
                        diag = diag,
                        cardBg = cardBg,
                        borderCol = borderCol,
                        primaryText = primaryText,
                        secondaryText = secondaryText
                    )
                    1 -> RealTime3DMapPage(
                        metrics = metrics,
                        wind = wind,
                        diag = diag,
                        mapZoomLevel = mapZoomLevel,
                        onZoomIn = { onChangeZoom(mapZoomLevel + 1) },
                        onZoomOut = { onChangeZoom(mapZoomLevel - 1) },
                        mapStyleIndex = mapStyleIndex,
                        onCycleMapStyle = onCycleMapStyle,
                        is3DView = is3DView,
                        onToggle3D = onToggle3D,
                        tileRefreshTick = tileRefreshTick,
                        elapsedRideSec = elapsedRideSec,
                        cardBg = cardBg,
                        borderCol = borderCol
                    )
                    2 -> CleanWindAndClimbPage(
                        metrics = metrics,
                        wind = wind,
                        diag = diag,
                        cardBg = cardBg,
                        borderCol = borderCol,
                        primaryText = primaryText,
                        secondaryText = secondaryText
                    )
                    3 -> CleanAiCoachPage(
                        metrics = metrics,
                        ai = ai,
                        carbsLogged = carbsLogged,
                        hydrationMl = hydrationMl,
                        onAddGel = onAddGel,
                        onAddSip = onAddSip,
                        cardBg = cardBg,
                        borderCol = borderCol,
                        primaryText = primaryText,
                        secondaryText = secondaryText
                    )
                }
            }

            // 4. COMPACT HARDWARE BUTTON BAR (◀ ANTERIOR | SIGUIENTE ▶)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B111E))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                        .clickable { onSelectPage(currentPage - 1) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "◀ ANTERIOR",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                        .clickable { onSelectPage(currentPage + 1) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "SIGUIENTE ▶",
                        color = Color(0xFFFACC15),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

/**
 * PAGE 1: ONLY THE 6 ESSENTIAL CYCLING METRICS IN GIANT, GLANCEABLE DIGITS.
 */
@Composable
private fun EssentialRideMetricsPage(
    metrics: CyclingMetricsSnapshot,
    ai: AiHeuristicEvaluation,
    diag: LiveSensorDiagnostics,
    cardBg: Color,
    borderCol: Color,
    primaryText: Color,
    secondaryText: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. HERO CARD: 3S POWER (W) + POWER ZONE + AI TARGET
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.25f)
                .background(cardBg, RoundedCornerShape(12.dp))
                .border(2.dp, Color(0xFFFACC15), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("POTENCIA 3S", color = secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Z${metrics.powerZone} • ${metrics.ftpPercent}% FTP",
                        color = Color(0xFFFACC15),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${metrics.power3s}", color = primaryText, fontSize = 44.sp, fontWeight = FontWeight.Black)
                    Text(" W", color = Color(0xFFFACC15), fontSize = 18.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 6.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("META IA", color = Color(0xFF60A5FA), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${ai.adaptiveTargetWatts}W", color = Color(0xFF38BDF8), fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val zoneColors = listOf(
                    Color(0xFF94A3B8), Color(0xFF3B82F6), Color(0xFF10B981),
                    Color(0xFFFACC15), Color(0xFFF97316), Color(0xFFEF4444), Color(0xFFA855F7)
                )
                zoneColors.forEachIndexed { idx, col ->
                    val active = (idx + 1) == metrics.powerZone
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (active) col else col.copy(alpha = 0.22f), RoundedCornerShape(3.dp))
                    )
                }
            }
        }

        // 2. MIDDLE ROW 1: VELOCIDAD (KM/H) & FRECUENCIA CARDÍACA (BPM)
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigMetricCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "VELOCIDAD",
                mainValue = "${metrics.speedKmh}",
                unit = "km/h",
                subLabel = "Dist: ${metrics.distanceKm} km",
                accentColor = Color(0xFF10B981),
                cardBg = cardBg, borderCol = borderCol, primaryText = primaryText, secondaryText = secondaryText
            )
            BigMetricCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "PULSO (Z${metrics.hrZone})",
                mainValue = "${metrics.heartRateBpm}",
                unit = "bpm",
                subLabel = if (metrics.heartRateBpm > 0) "Activo ANT+/BLE" else "Sin pulsómetro",
                accentColor = Color(0xFFF43F5E),
                cardBg = cardBg, borderCol = borderCol, primaryText = primaryText, secondaryText = secondaryText
            )
        }

        // 3. MIDDLE ROW 2: CADENCIA (RPM) & PENDIENTE (%)
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigMetricCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "CADENCIA",
                mainValue = "${metrics.cadenceRpm}",
                unit = "rpm",
                subLabel = "Pedaleo",
                accentColor = Color(0xFF38BDF8),
                cardBg = cardBg, borderCol = borderCol, primaryText = primaryText, secondaryText = secondaryText
            )
            BigMetricCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "PENDIENTE",
                mainValue = "${metrics.gradePercent}",
                unit = "%",
                subLabel = "Alt: ${diag.altitudeMeters.toInt()} m",
                accentColor = Color(0xFFF59E0B),
                cardBg = cardBg, borderCol = borderCol, primaryText = primaryText, secondaryText = secondaryText
            )
        }

        // 4. BOTTOM SUMMARY STRIP: NP • RESERVA W' • CARGA TSS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(10.dp))
                .border(1.dp, borderCol, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactStatItem("NP", "${metrics.normalizedPower}W", Color(0xFF38BDF8), secondaryText)
            CompactStatItem("W' RESERVA", "${metrics.wPrimePercent}% (${metrics.wPrimeBalanceKj}kJ)", Color(0xFFA855F7), secondaryText)
            CompactStatItem("CARGA TSS", "${metrics.tss}", Color(0xFFFACC15), secondaryText)
        }
    }
}

/**
 * PAGE 2: REAL-TIME 3D GPS MAP PAGE (`2. MAPA 3D`).
 */
@Composable
private fun RealTime3DMapPage(
    metrics: CyclingMetricsSnapshot,
    wind: WindVectorReport,
    diag: LiveSensorDiagnostics,
    mapZoomLevel: Int,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    mapStyleIndex: Int,
    onCycleMapStyle: () -> Unit,
    is3DView: Boolean,
    onToggle3D: () -> Unit,
    tileRefreshTick: Int,
    elapsedRideSec: Int,
    cardBg: Color,
    borderCol: Color
) {
    val latStr = String.format(Locale.US, "%.5f", diag.latitude)
    val lonStr = String.format(Locale.US, "%.5f", diag.longitude)
    val styleNames = listOf("🗺️ Mapa", "🛰️ Satélite", "⛰️ Topo")

    Column(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF06101E), RoundedCornerShape(12.dp))
                .border(2.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                .clipToBounds()
        ) {
            val n = (1 shl mapZoomLevel).toDouble()
            val safeLat = diag.latitude.coerceIn(-85.0, 85.0)
            val latRad = safeLat * PI / 180.0
            val exactTileX = (diag.longitude + 180.0) / 360.0 * n
            val exactTileY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
            val centerTileX = floor(exactTileX).toInt()
            val centerTileY = floor(exactTileY).toInt()
            val offsetPxX = ((exactTileX - centerTileX) * 256.0).toFloat()
            val offsetPxY = ((exactTileY - centerTileY) * 256.0).toFloat()
            val mapRotationDeg = -wind.riderHeadingDeg.toFloat()

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF0B192C), Color(0xFF0F2942), Color(0xFF07111E))
                    ),
                    size = Size(size.width, size.height)
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationX = if (is3DView) 52f else 0f
                        scaleX = if (is3DView) 1.35f else 1.0f
                        scaleY = if (is3DView) 1.35f else 1.0f
                        cameraDistance = 14f * density
                    }
            ) {
                val ignoredTick = tileRefreshTick
                val cx = size.width / 2f
                val cy = size.height * 0.56f

                rotate(degrees = mapRotationDeg, pivot = Offset(cx, cy)) {
                    val baseLeft = cx - offsetPxX
                    val baseTop = cy - offsetPxY

                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val tileLeft = baseLeft + dx * 256f
                            val tileTop = baseTop + dy * 256f
                            val bmp = LiveTileMapEngine.getOrFetchTile(
                                styleIndex = mapStyleIndex,
                                z = mapZoomLevel,
                                x = centerTileX + dx,
                                y = centerTileY + dy
                            )
                            if (bmp != null) {
                                drawImage(image = bmp, topLeft = Offset(tileLeft, tileTop))
                            } else {
                                val checker = ((centerTileX + dx + centerTileY + dy) % 2 == 0)
                                drawRect(
                                    color = if (checker) Color(0xFF0D1B2A) else Color(0xFF112233),
                                    topLeft = Offset(tileLeft, tileTop),
                                    size = Size(256f, 256f)
                                )
                                val step = 64f
                                for (g in 0..3) {
                                    val gx = tileLeft + g * step
                                    val gy = tileTop + g * step
                                    drawLine(Color(0xFF1E3A5F), Offset(gx, tileTop), Offset(gx, tileTop + 256f), strokeWidth = 2f)
                                    drawLine(Color(0xFF1E3A5F), Offset(tileLeft, gy), Offset(tileLeft + 256f, gy), strokeWidth = 2f)
                                }
                            }
                        }
                    }

                    drawCircle(Color(0x6638BDF8), radius = 65f, center = Offset(cx, cy), style = Stroke(width = 2.5f))
                    drawCircle(Color(0x552DD4BF), radius = 135f, center = Offset(cx, cy), style = Stroke(width = 2.5f))
                    drawCircle(Color(0x4494A3B8), radius = 210f, center = Offset(cx, cy), style = Stroke(width = 2f))

                    drawLine(Color(0x88EF4444), Offset(cx, cy), Offset(cx, cy - 220f), strokeWidth = 3.5f)
                    drawLine(Color(0x5564748B), Offset(cx, cy), Offset(cx, cy + 220f), strokeWidth = 2f)
                    drawLine(Color(0x5564748B), Offset(cx - 220f, cy), Offset(cx + 220f, cy), strokeWidth = 2f)

                    val windRad = (wind.trueWindDirectionDeg + 180) * PI / 180.0
                    val windDx = sin(windRad).toFloat()
                    val windDy = -cos(windRad).toFloat()
                    val phase = ((elapsedRideSec % 8) / 8f) * 70f
                    for (r in -2..2) {
                        for (c in -2..2) {
                            val sx = cx + c * 95f + windDx * phase
                            val sy = cy + r * 95f + windDy * phase
                            drawLine(
                                color = Color(0xAA2DD4BF),
                                start = Offset(sx, sy),
                                end = Offset(sx + windDx * 32f, sy + windDy * 32f),
                                strokeWidth = 3.5f
                            )
                        }
                    }

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

                            drawLine(Color(0xCC020617), Offset(x1, y1 + 5f), Offset(x2, y2 + 5f), strokeWidth = 14f)
                            val segColor = when {
                                p2.headwindKmh >= 8.0 -> Color(0xFFF97316)
                                p2.headwindKmh <= -5.0 -> Color(0xFF10B981)
                                else -> Color(0xFF38BDF8)
                            }
                            drawLine(segColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 8f)
                        }
                    }
                }

                val corridorColor = when {
                    wind.headwindKmh >= 8.0 -> Color(0xFFF97316)
                    wind.headwindKmh <= -5.0 -> Color(0xFF10B981)
                    else -> Color(0xFF38BDF8)
                }
                val corridorPath = Path().apply {
                    moveTo(cx - 26f, cy)
                    lineTo(cx - 8f, cy - 190f)
                    lineTo(cx + 8f, cy - 190f)
                    lineTo(cx + 26f, cy)
                    close()
                }
                drawPath(corridorPath, corridorColor.copy(alpha = 0.28f))
                drawLine(corridorColor, Offset(cx, cy), Offset(cx, cy - 185f), strokeWidth = 6f)

                drawCircle(Color(0x44FACC15), radius = 34f, center = Offset(cx, cy))

                val shadowPath = Path().apply {
                    moveTo(cx, cy - 16f)
                    lineTo(cx + 18f, cy + 20f)
                    lineTo(cx, cy + 11f)
                    lineTo(cx - 18f, cy + 20f)
                    close()
                }
                drawPath(shadowPath, Color(0xBB000000))

                val chevronPath = Path().apply {
                    moveTo(cx, cy - 22f)
                    lineTo(cx + 18f, cy + 14f)
                    lineTo(cx, cy + 5f)
                    lineTo(cx - 18f, cy + 14f)
                    close()
                }
                drawPath(chevronPath, Color(0xFFFACC15))
                drawPath(chevronPath, Color(0xFF0F172A), style = Stroke(width = 3f))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(6.dp)
                    .background(Color(0xDD0B111E), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("📍 $latStr°, $lonStr°", color = Color(0xFFFACC15), fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text("Rumbo ${wind.riderHeadingDeg}° • Viento ${abs(wind.headwindKmh)} km/h", color = Color(0xFF2DD4BF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text("Z$mapZoomLevel", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
            }

            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MapControlButton("Z+", onClick = onZoomIn)
                    MapControlButton("Z−", onClick = onZoomOut)
                    MapControlButton(if (is3DView) "3D 52°" else "2D", active = is3DView, onClick = onToggle3D)
                }
                MapControlButton(styleNames[mapStyleIndex], active = true, onClick = onCycleMapStyle)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactMapTile(Modifier.weight(1f), "POTENCIA 3S", "${metrics.power3s}W", Color(0xFFFACC15), cardBg, borderCol)
            CompactMapTile(Modifier.weight(1f), "VELOCIDAD", "${metrics.speedKmh} km/h", Color(0xFF10B981), cardBg, borderCol)
            CompactMapTile(Modifier.weight(1f), "PENDIENTE", "${metrics.gradePercent}%", Color(0xFFF59E0B), cardBg, borderCol)
        }
    }
}

/**
 * PAGE 3: CLEAN WIND VECTOR & CLIMB PAGE (`3. VIENTO`).
 */
@Composable
private fun CleanWindAndClimbPage(
    metrics: CyclingMetricsSnapshot,
    wind: WindVectorReport,
    diag: LiveSensorDiagnostics,
    cardBg: Color,
    borderCol: Color,
    primaryText: Color,
    secondaryText: Color
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.3f)
                .background(cardBg, RoundedCornerShape(12.dp))
                .border(1.dp, borderCol, RoundedCornerShape(12.dp))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(150.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = size.minDimension * 0.42f
                drawCircle(Color(0xFF1E293B), radius = r, center = Offset(cx, cy), style = Stroke(width = 4f))
                drawCircle(Color(0x332DD4BF), radius = r * 0.65f, center = Offset(cx, cy), style = Stroke(width = 2f))
                val rad = (wind.relativeWindAngleDeg - 90) * PI / 180.0
                val tipX = cx + (r * cos(rad)).toFloat()
                val tipY = cy + (r * sin(rad)).toFloat()
                drawLine(Color(0xFF2DD4BF), Offset(cx, cy), Offset(tipX, tipY), strokeWidth = 8f)
                drawCircle(Color(0xFFFACC15), radius = 9f, center = Offset(cx, cy))
            }
            Column(modifier = Modifier.align(Alignment.TopStart)) {
                Text("VIENTO RELATIVO", color = secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text("${wind.relativeWindAngleDeg}°", color = Color(0xFF2DD4BF), fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            Column(modifier = Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
                Text("CLIMA LOCAL", color = secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text("${wind.temperatureC}°C • ${wind.humidityPct}%", color = primaryText, fontSize = 14.sp, fontWeight = FontWeight.Black)
            }
        }

        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val isHead = wind.headwindKmh >= 0
            BigMetricCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = if (isHead) "VIENTO EN CONTRA" else "VIENTO A FAVOR",
                mainValue = "${abs(wind.headwindKmh)}",
                unit = "km/h",
                subLabel = "Aero: ${if (wind.aeroPowerImpactWatts >= 0) "+" else ""}${wind.aeroPowerImpactWatts}W",
                accentColor = if (isHead) Color(0xFFF97316) else Color(0xFF10B981),
                cardBg = cardBg, borderCol = borderCol, primaryText = primaryText, secondaryText = secondaryText
            )
            BigMetricCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "VIENTO LATERAL",
                mainValue = "${abs(wind.crosswindKmh)}",
                unit = "km/h",
                subLabel = "Ráfagas: ${wind.windGustsKmh} km/h",
                accentColor = Color(0xFF38BDF8),
                cardBg = cardBg, borderCol = borderCol, primaryText = primaryText, secondaryText = secondaryText
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(10.dp))
                .border(1.dp, borderCol, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactStatItem("ALTITUD REAL", "${diag.altitudeMeters.toInt()} m", Color(0xFFFACC15), secondaryText)
            CompactStatItem("VAM ESCALADA", "${metrics.vamMetersPerHour} m/h", Color(0xFF10B981), secondaryText)
            CompactStatItem("BARÓMETRO", "${diag.pressureHpa} hPa", Color(0xFF38BDF8), secondaryText)
        }
    }
}

/**
 * PAGE 4: CLEAN ON-DEVICE AI COACH & FUELING PAGE (`4. IA PRO`).
 */
@Composable
private fun CleanAiCoachPage(
    metrics: CyclingMetricsSnapshot,
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
        modifier = Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF172554), RoundedCornerShape(12.dp))
                .border(2.dp, Color(0xFF3B82F6), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("CONSEJO TÁCTICO IA EN VIVO", color = Color(0xFF93C5FD), fontSize = 10.sp, fontWeight = FontWeight.Black)
            Text(ai.tacticalHeadline, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Text(ai.tacticalReason, color = Color(0xFFDBEAFE), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigMetricCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "META POTENCIA IA",
                mainValue = "${ai.adaptiveTargetWatts}",
                unit = "W",
                subLabel = "Actual 3s: ${metrics.power3s}W",
                accentColor = Color(0xFFFACC15),
                cardBg = cardBg, borderCol = borderCol, primaryText = primaryText, secondaryText = secondaryText
            )
            BigMetricCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "RIESGO PÁJARA",
                mainValue = "${ai.bonkRiskPercent}",
                unit = "%",
                subLabel = "IF ${metrics.intensityFactor} • ${metrics.kilojoulesTotal.toInt()} kJ",
                accentColor = if (ai.bonkRiskPercent >= 60) Color(0xFFEF4444) else Color(0xFF10B981),
                cardBg = cardBg, borderCol = borderCol, primaryText = primaryText, secondaryText = secondaryText
            )
        }

        Row(modifier = Modifier.fillMaxWidth().weight(0.95f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().background(cardBg, RoundedCornerShape(12.dp)).border(1.dp, borderCol, RoundedCornerShape(12.dp)).padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("CARBOHIDRATOS (${ai.recommendedCarbsGramsPerHour}g/h)", color = secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text("Gel en ${ai.nextCarbIntakeMinutes}m • ${carbsLogged}g", color = primaryText, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Box(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFD97706), RoundedCornerShape(8.dp)).clickable { onAddGel() }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+25g GEL", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().background(cardBg, RoundedCornerShape(12.dp)).border(1.dp, borderCol, RoundedCornerShape(12.dp)).padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("HIDRATACIÓN (${ai.recommendedHydrationMlPerHour}ml/h)", color = secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text("Agua en ${ai.nextHydrationMinutes}m • ${hydrationMl}ml", color = primaryText, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Box(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF0284C7), RoundedCornerShape(8.dp)).clickable { onAddSip() }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+150ml AGUA", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun BigMetricCard(
    modifier: Modifier = Modifier,
    label: String,
    mainValue: String,
    unit: String,
    subLabel: String,
    accentColor: Color,
    cardBg: Color,
    borderCol: Color,
    primaryText: Color,
    secondaryText: Color
) {
    Column(
        modifier = modifier
            .background(cardBg, RoundedCornerShape(12.dp))
            .border(1.dp, borderCol, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = secondaryText, fontSize = 11.sp, fontWeight = FontWeight.Black)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = mainValue, color = accentColor, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(text = " $unit", color = primaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(text = subLabel, color = secondaryText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CompactStatItem(label: String, value: String, valueColor: Color, labelColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = labelColor, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
        Text(text = value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun MapControlButton(text: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (active) Color(0xFF2563EB) else Color(0xEE1E293B), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF475569), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun CompactMapTile(modifier: Modifier = Modifier, label: String, value: String, accentColor: Color, cardBg: Color, borderCol: Color) {
    Column(
        modifier = modifier
            .background(cardBg, RoundedCornerShape(10.dp))
            .border(1.dp, borderCol, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
        Text(text = value, color = accentColor, fontSize = 18.sp, fontWeight = FontWeight.Black)
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
