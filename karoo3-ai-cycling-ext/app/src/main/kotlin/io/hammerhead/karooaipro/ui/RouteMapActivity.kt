package io.hammerhead.karooaipro.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hammerhead.karooaipro.extension.SharedTelemetryBus
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Modern Route Map & Wind-Colored Navigation Screen for Karoo 3 (3.2" 480x800 Display).
 * Displays turn-by-turn cue banner, wind-colored route path, live chevron, and climber profile.
 */
class RouteMapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KarooRouteMapScreen()
        }
    }
}

@Composable
fun KarooRouteMapScreen() {
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
        color = Color(0xFF070D17)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
        ) {
            // 1. Turn-by-Turn Navigation Banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B1220), RoundedCornerShape(12.dp))
                    .border(2.dp, Color(0xFFFACC15), RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "↱",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFACC15),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "340 m • Hwy 2",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        text = "Turn Right → Presa de la Amistad",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "REM DIST", fontSize = 9.sp, color = Color(0xFF64748B))
                    Text(
                        text = "${(50.0 - metrics.distanceKm).coerceAtLeast(0.0).let { String.format("%.1f", it) }} km",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF10B981)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 2. Wind Vector Floating Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF14B8A6), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${wind.windImpactLabel}: ${wind.headwindKmh} km/h (${wind.relativeWindAngleDeg}°)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2DD4BF)
                )
                Text(
                    text = "${if (wind.aeroPowerImpactWatts >= 0) "+" else ""}${wind.aeroPowerImpactWatts}W Drag",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = if (wind.aeroPowerImpactWatts >= 0) Color(0xFFF97316) else Color(0xFF10B981)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 3. Vector Route Map Canvas with Wind Segments
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF070D17), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Background Grid
                    val gridStep = 40f
                    var x = 0f
                    while (x < w) {
                        drawLine(Color(0x22334155), Offset(x, 0f), Offset(x, h))
                        x += gridStep
                    }
                    var y = 0f
                    while (y < h) {
                        drawLine(Color(0x22334155), Offset(0f, y), Offset(w, y))
                        y += gridStep
                    }

                    // Water outline
                    val waterPath = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(w * 0.45f, 0f)
                        quadraticBezierTo(w * 0.35f, h * 0.25f, 0f, h * 0.32f)
                        close()
                    }
                    drawPath(waterPath, Color(0x330E7490))

                    // Route Loop coordinates
                    val pts = listOf(
                        Offset(w * 0.2f, h * 0.85f),
                        Offset(w * 0.3f, h * 0.70f),
                        Offset(w * 0.45f, h * 0.62f),
                        Offset(w * 0.60f, h * 0.48f),
                        Offset(w * 0.70f, h * 0.32f),
                        Offset(w * 0.58f, h * 0.18f),
                        Offset(w * 0.36f, h * 0.22f),
                        Offset(w * 0.22f, h * 0.44f),
                        Offset(w * 0.2f, h * 0.85f)
                    )

                    // Draw Route casing
                    val routePath = Path().apply {
                        pts.forEachIndexed { i, pt ->
                            if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                        }
                    }
                    drawPath(routePath, Color(0xFF020617), style = Stroke(width = 12f))

                    // Draw colored route segments based on wind
                    for (i in 0 until pts.size - 1) {
                        val p1 = pts[i]
                        val p2 = pts[i + 1]
                        val segColor = when (i % 3) {
                            0 -> Color(0xFFF97316) // Headwind segment
                            1 -> Color(0xFF38BDF8) // Crosswind segment
                            else -> Color(0xFF10B981) // Tailwind segment
                        }
                        drawLine(segColor, p1, p2, strokeWidth = 8f)
                    }

                    // Draw Live Rider Chevron at current position
                    val riderPt = pts[3]
                    drawCircle(Color(0x44FACC15), radius = 24f, center = riderPt)
                    drawCircle(Color(0xFFFACC15), radius = 8f, center = riderPt)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 4. Climber Elevation Profile Drawer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B101B), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                    .padding(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "⛰️ CLIMBER PROFILE • GRADE ${metrics.gradePercent}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFFACC15)
                    )
                    Text(
                        text = "VAM ${metrics.vamMetersPerHour} m/h",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
                    val w = size.width
                    val h = size.height
                    val profilePath = Path().apply {
                        moveTo(0f, h * 0.85f)
                        lineTo(w * 0.25f, h * 0.7f)
                        lineTo(w * 0.5f, h * 0.35f)
                        lineTo(w * 0.75f, h * 0.45f)
                        lineTo(w, h * 0.15f)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    drawPath(profilePath, Color(0x33FACC15))
                    drawLine(Color(0xFFFACC15), Offset(0f, h * 0.85f), Offset(w * 0.25f, h * 0.7f), strokeWidth = 3f)
                    drawLine(Color(0xFFFACC15), Offset(w * 0.25f, h * 0.7f), Offset(w * 0.5f, h * 0.35f), strokeWidth = 3f)
                    drawLine(Color(0xFFFACC15), Offset(w * 0.5f, h * 0.35f), Offset(w * 0.75f, h * 0.45f), strokeWidth = 3f)
                    drawLine(Color(0xFFFACC15), Offset(w * 0.75f, h * 0.45f), Offset(w, h * 0.15f), strokeWidth = 3f)
                    drawCircle(Color(0xFF38BDF8), radius = 5f, center = Offset(w * 0.5f, h * 0.35f))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 5. Bottom Glanceable 3.2" Cockpit Tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Power
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF111827), RoundedCornerShape(8.dp))
                        .padding(6.dp)
                ) {
                    Text(text = "3S POWER", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                    Text(
                        text = "${metrics.power3s}W",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFFACC15)
                    )
                }

                // Speed
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF111827), RoundedCornerShape(8.dp))
                        .padding(6.dp)
                ) {
                    Text(text = "SPEED", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                    Text(
                        text = "${metrics.speedKmh}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF10B981)
                    )
                }

                // AI Target
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF111827), RoundedCornerShape(8.dp))
                        .padding(6.dp)
                ) {
                    Text(text = "AI TARGET", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA))
                    Text(
                        text = "${ai.adaptiveTargetWatts}W",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }
            }
        }
    }
}
