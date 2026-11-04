// ... existing code ...
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
    val activeZoneCol = garminZoneColor(metrics.powerZone)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 1. HERO CARD: GARMIN EDGE REAL-TIME COLOR POWER GAUGE (COMPACTO) + 30S ZONE BAR CHART + AVG/MAX/NP
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.42f)
                .background(cardBg, RoundedCornerShape(10.dp))
                .border(2.dp, activeZoneCol, RoundedCornerShape(10.dp))
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(activeZoneCol, RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = "Z${metrics.powerZone}",
                            color = Color(0xFF0F172A),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Text(
                        text = "${metrics.powerZoneName.uppercase()} • ${metrics.ftpPercent}% FTP",
                        color = activeZoneCol,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Text(
                    text = "META IA ${ai.adaptiveTargetWatts}W",
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .width(112.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(104.dp, 68.dp)) {
                        val arcDiameter = size.width * 0.82f
                        val arcTopLeft = Offset((size.width - arcDiameter) / 2f, 6f)
                        val arcSize = Size(arcDiameter, arcDiameter)
                        val strokeW = 11f

                        val zoneSpans = listOf(
                            26f to Color(0xFF94A3B8),
                            30f to Color(0xFF3B82F6),
                            26f to Color(0xFF10B981),
                            26f to Color(0xFFFACC15),
                            24f to Color(0xFFF97316),
                            24f to Color(0xFFEF4444),
                            20f to Color(0xFFA855F7)
                        )
                        var startAng = 180f
                        zoneSpans.forEachIndexed { idx, (sweep, col) ->
                            val isCurrent = (idx + 1) == metrics.powerZone
                            drawArc(
                                color = if (isCurrent) col else col.copy(alpha = 0.30f),
                                startAngle = startAng,
                                sweepAngle = sweep - 1.5f,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = Stroke(width = if (isCurrent) strokeW + 3f else strokeW, cap = StrokeCap.Butt)
                            )
                            startAng += sweep
                        }

                        val cx = size.width / 2f
                        val cy = 6f + arcDiameter / 2f
                        val radius = arcDiameter / 2f

                        val targetRatio = (ai.adaptiveTargetWatts / 420f).coerceIn(0f, 1f)
                        val targetRad = (180.0 + targetRatio * 180.0) * PI / 180.0
                        val tx = cx + (radius * cos(targetRad)).toFloat()
                        val ty = cy + (radius * sin(targetRad)).toFloat()
                        drawCircle(Color(0xFF38BDF8), radius = 4.5f, center = Offset(tx, ty))

                        val powerRatio = (metrics.power3s / 420f).coerceIn(0f, 1f)
                        val needleRad = (180.0 + powerRatio * 180.0) * PI / 180.0
                        val nx = cx + ((radius - 5f) * cos(needleRad)).toFloat()
                        val ny = cy + ((radius - 5f) * sin(needleRad)).toFloat()
                        drawLine(
                            color = activeZoneCol,
                            start = Offset(cx, cy),
                            end = Offset(nx, ny),
                            strokeWidth = 4f,
                            cap = StrokeCap.Round
                        )
                        drawCircle(Color.White, radius = 5f, center = Offset(cx, cy))
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 1.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = "${metrics.power3s}",
                            color = primaryText,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "W",
                            color = activeZoneCol,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(bottom = 3.dp, start = 2.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 5.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 3.dp, bottom = 1.dp)
                    ) {
                        val w = size.width
                        val h = size.height
                        val history = if (diag.powerHistory30s.isNotEmpty()) {
                            diag.powerHistory30s.takeLast(24)
                        } else {
                            List(24) { metrics.power3s }
                        }

                        val ftpY = h - ((280f / 450f).coerceIn(0.1f, 0.95f) * h)
                        drawLine(
                            color = Color(0x66FACC15),
                            start = Offset(0f, ftpY),
                            end = Offset(w, ftpY),
                            strokeWidth = 1.5f
                        )

                        val count = history.size.coerceAtLeast(1)
                        val gap = 2f
                        val barW = ((w - gap * (count - 1)) / count).coerceAtLeast(2.5f)

                        history.forEachIndexed { i, watts ->
                            val ratio = (watts / 450f).coerceIn(0.06f, 1f)
                            val barH = ratio * h
                            val pctFtp = (watts * 100) / 280
                            val barZone = when {
                                pctFtp < 55 -> 1
                                pctFtp < 75 -> 2
                                pctFtp < 90 -> 3
                                pctFtp < 105 -> 4
                                pctFtp < 120 -> 5
                                pctFtp < 150 -> 6
                                else -> 7
                            }
                            val bCol = garminZoneColor(barZone)
                            val left = i * (barW + gap)
                            drawRect(
                                color = bCol,
                                topLeft = Offset(left, h - barH),
                                size = Size(barW, barH)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B111E), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AVG ${diag.avgPowerWatts}W",
                            color = Color(0xFF10B981),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "MAX ${diag.maxPowerWatts}W",
                            color = Color(0xFFF97316),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "NP ${metrics.normalizedPower}W",
                            color = Color(0xFF38BDF8),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            GarminPowerScoreBar(
                powerWatts = metrics.power3s,
                aiTargetWatts = ai.adaptiveTargetWatts,
                activeZone = metrics.powerZone
            )
        }

        // 2. MIDDLE ROW 1: VELOCIDAD (KM/H) & FRECUENCIA CARDÍACA (BPM)
        Row(modifier = Modifier.fillMaxWidth().weight(0.92f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
        Row(modifier = Modifier.fillMaxWidth().weight(0.92f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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

        // 4. BOTTOM SUMMARY STRIP
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(8.dp))
                .border(1.dp, borderCol, RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactStatItem(
                label = "STRAVA GPX",
                value = if (diag.isRecordingRide) "${diag.recordedTrackPointsCount} pts" else "LISTO",
                valueColor = if (diag.isRecordingRide) Color(0xFFF87171) else Color(0xFF34D399),
                labelColor = secondaryText
            )
            CompactStatItem("W' RESERVA", "${metrics.wPrimePercent}% (${metrics.wPrimeBalanceKj}kJ)", Color(0xFFA855F7), secondaryText)
            CompactStatItem("CARGA TSS", "${metrics.tss}", Color(0xFFFACC15), secondaryText)
        }
    }
}

// ... existing code ...
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
            .background(cardBg, RoundedCornerShape(10.dp))
            .border(1.dp, borderCol, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Black)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = mainValue, color = accentColor, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(text = " $unit", color = primaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(text = subLabel, color = secondaryText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
// ... existing code ...
