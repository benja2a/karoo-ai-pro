package io.hammerhead.karooaipro.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hammerhead.karooaipro.extension.SharedTelemetryBus
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Separate Full-Screen All-Metrics Cockpit Window for Hammerhead Karoo 3 (480x800).
 * Displays all priority cycling metrics (Strava, TrainingPeaks, Zwift),
 * Open-Meteo real-time Headwind/Tailwind/Crosswind & Relative Wind Angle,
 * and On-Device AI Heuristic Coaching in one high-contrast daylight-readable screen.
 */
class MetricsDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KarooAllMetricsScreen()
        }
    }
}

@Composable
fun KarooAllMetricsScreen() {
    var state by remember { mutableStateOf(SharedTelemetryBus.latestState()) }

    LaunchedEffect(Unit) {
        while (true) {
            state = SharedTelemetryBus.latestState()
            delay(1000L)
        }
    }

    val (metrics, wind, ai) = state

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0B0F17)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 1. On-Device AI Tactical Coach Banner
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF172554), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AI COACH • TARGET ${ai.adaptiveTargetWatts}W",
                        color = Color(0xFF60A5FA),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "BONK RISK ${ai.bonkRiskPercent}%",
                        color = if (ai.bonkRiskPercent >= 65) Color(0xFFF87171) else Color(0xFF34D399),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = ai.tacticalHeadline,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = ai.tacticalReason,
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp
                )
            }

            // 2. Primary Power & Zwift Zone Bar + W' Balance
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "3S POWER (${metrics.powerZoneName})",
                    value = "${metrics.power3s}W",
                    subValue = "${metrics.ftpPercent}% FTP • AI Tgt ${ai.adaptiveTargetWatts}W",
                    accentColor = Color(0xFFFACC15)
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "W' BALANCE (SKIBA)",
                    value = "${metrics.wPrimeBalanceKj} kJ",
                    subValue = "${metrics.wPrimePercent}% Anaerobic Reserve",
                    accentColor = Color(0xFFA855F7)
                )
            }

            // 3. TrainingPeaks Block: NP, IF, TSS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "NORM POWER",
                    value = "${metrics.normalizedPower}W",
                    subValue = "${metrics.kilojoulesTotal} kJ",
                    accentColor = Color(0xFF38BDF8)
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "INTENSITY (IF)",
                    value = "${metrics.intensityFactor}",
                    subValue = "Drift ${metrics.aerobicDecouplingPercent}%",
                    accentColor = Color(0xFF38BDF8)
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "TSS LOAD",
                    value = "${metrics.tss}",
                    subValue = "TrainingPeaks",
                    accentColor = Color(0xFF38BDF8)
                )
            }

            // 4. Core Telemetry + Strava Live Segment Pacing
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "SPEED / GRADE",
                    value = "${metrics.speedKmh} km/h",
                    subValue = "Grade ${metrics.gradePercent}% • VAM ${metrics.vamMetersPerHour}",
                    accentColor = Color(0xFF4ADE80)
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "HR / CADENCE",
                    value = "${metrics.heartRateBpm} bpm",
                    subValue = "Z${metrics.hrZone} HR • ${metrics.cadenceRpm} rpm",
                    accentColor = Color(0xFFF43F5E)
                )
            }

            // 5. Open-Meteo Real-Time Wind & Weather Block
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111827), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "OPEN-METEO WIND VECTOR • ${wind.windImpactLabel}",
                        color = Color(0xFF2DD4BF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${wind.temperatureC}°C • ${wind.humidityPct}% RH",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val headLabel = if (wind.headwindKmh >= 0) "HEADWIND" else "TAILWIND"
                    MetricTile(
                        modifier = Modifier.weight(1f),
                        label = headLabel,
                        value = "${abs(wind.headwindKmh)} km/h",
                        subValue = "Aero ${if (wind.aeroPowerImpactWatts >= 0) "+" else ""}${wind.aeroPowerImpactWatts}W",
                        accentColor = if (wind.headwindKmh >= 0) Color(0xFFFB923C) else Color(0xFF34D399)
                    )
                    MetricTile(
                        modifier = Modifier.weight(1f),
                        label = "CROSSWIND",
                        value = "${abs(wind.crosswindKmh)} km/h",
                        subValue = if (wind.crosswindKmh >= 0) "From Right" else "From Left",
                        accentColor = Color(0xFF38BDF8)
                    )
                    MetricTile(
                        modifier = Modifier.weight(1f),
                        label = "REL WIND ANGLE",
                        value = "${wind.relativeWindAngleDeg}°",
                        subValue = "Hdg ${wind.riderHeadingDeg}° / Wind ${wind.trueWindDirectionDeg}°",
                        accentColor = Color(0xFFE2E8F0)
                    )
                }
            }

            // 6. Strava Live Segment & AI Smart Fueling Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val segAhead = metrics.segmentDeltaSeconds <= 0
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "STRAVA LIVE SEGMENT",
                    value = "${if (segAhead) "" else "+"}${metrics.segmentDeltaSeconds}s",
                    subValue = if (segAhead) "AHEAD OF PR PACE" else "BEHIND PR PACE",
                    accentColor = if (segAhead) Color(0xFF4ADE80) else Color(0xFFFB923C)
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "AI FUEL & HYDRATION",
                    value = "Eat ${ai.nextCarbIntakeMinutes}m / Sip ${ai.nextHydrationMinutes}m",
                    subValue = "${ai.recommendedCarbsGramsPerHour}g/h • ${ai.recommendedHydrationMlPerHour}ml/h",
                    accentColor = Color(0xFF60A5FA)
                )
            }
        }
    }
}

@Composable
fun MetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    subValue: String,
    accentColor: Color
) {
    Column(
        modifier = modifier
            .background(Color(0xFF161F30), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(text = value, color = accentColor, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(text = subValue, color = Color(0xFFCBD5E1), fontSize = 10.sp)
    }
}
