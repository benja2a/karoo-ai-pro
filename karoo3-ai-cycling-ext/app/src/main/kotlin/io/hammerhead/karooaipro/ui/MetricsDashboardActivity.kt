// ... existing code ...
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
                        wind = wind,
                        ai = ai,
                        diag = diag,
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

            // 4. COMPACT HARDWARE BUTTON BAR (◀ PÁG | 🔴 GRABAR / ⏹ STRAVA GPX | PÁG ▶)
// ... existing code ...
