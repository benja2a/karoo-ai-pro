package io.hammerhead.karooaipro.extension

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import io.hammerhead.karooaipro.engine.AdvancedCyclingCalculator
import io.hammerhead.karooaipro.engine.AiHeuristicEvaluation
import io.hammerhead.karooaipro.engine.CyclingMetricsSnapshot
import io.hammerhead.karooaipro.engine.OnDeviceAiCoachEngine
import io.hammerhead.karooaipro.engine.WindVectorReport
import io.hammerhead.karooaipro.engine.WindWeatherEngine
import io.hammerhead.karooaipro.ui.MetricsDashboardActivity
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Official Hammerhead Karoo 3 Extension Service (`io.hammerhead:karoo-ext:1.1.9`).
 * Delegates to `SharedTelemetryBus` so that live telemetry from both `KarooSystemService`
 * (ANT+/BLE connected sensors) and Karoo 3 internal hardware sensors (GPS + Barometer)
 * is always active whether opened from the app drawer or running during a ride.
 */
class KarooAiExtensionService : KarooExtension("karoo-ai-pro", "1.0.0") {

    override fun onCreate() {
        super.onCreate()
        SharedTelemetryBus.ensureStarted(applicationContext)
    }

    fun openAllMetricsWindow() {
        val intent = Intent(applicationContext, MetricsDashboardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

/**
 * Real-time GPS breadcrumb point recorded along the cyclist's actual ride path.
 */
data class GpsTrackPoint(
    val lat: Double,
    val lon: Double,
    val altitudeMeters: Double,
    val speedKmh: Double,
    val gradePct: Double,
    val headwindKmh: Double,
    val timestampMs: Long
)

/**
 * Live sensor status diagnostics and real GPS coordinates/breadcrumb trail for the 3D Map & UI header.
 */
data class LiveSensorDiagnostics(
    val karooServiceConnected: Boolean = false,
    val gpsFixActive: Boolean = false,
    val barometerActive: Boolean = false,
    val altitudeMeters: Double = 0.0,
    val pressureHpa: Double = 1013.25,
    val activeStreamsCount: Int = 0,
    val demoModeEnabled: Boolean = false,
    val latitude: Double = 29.3242,
    val longitude: Double = -100.9522,
    val bearingDeg: Int = 0,
    val gpsAccuracyMeters: Float = 0f,
    val trackPoints: List<GpsTrackPoint> = emptyList()
)

/**
 * Singleton Real-Time Telemetry Hub & Hardware Sensor Engine.
 * Reads live data from:
 * 1. `KarooSystemService` (`OnStreamState.StartStreaming` for Power, HR, Cadence, Speed, Distance, Grade + `OnLocationChanged`).
 * 2. Internal Karoo 3 Hardware (`LocationManager` GPS speed/bearing/distance/coordinates + `SensorManager` Barometer `TYPE_PRESSURE`).
 * 3. Live Open-Meteo Weather API (`WindWeatherEngine`) for the Karoo's real GPS coordinates.
 */
object SharedTelemetryBus {

    private val started = AtomicBoolean(false)
    private val gpsListening = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var karooSystem: KarooSystemService? = null
    private val calculator = AdvancedCyclingCalculator()
    private val windEngine = WindWeatherEngine()
    private val aiCoach = OnDeviceAiCoachEngine()

    // Real-time sensor values (initialized to 0 so stationary state shows real 0 values)
    @Volatile private var latestPower: Int = 0
    @Volatile private var latestHr: Int = 0
    @Volatile private var latestCadence: Int = 0
    @Volatile private var latestSpeedKmh: Double = 0.0
    @Volatile private var latestDistanceKm: Double = 0.0
    @Volatile private var latestGradePct: Double = 0.0
    @Volatile private var latestLat: Double = 29.3242
    @Volatile private var latestLon: Double = -100.9522
    @Volatile private var latestBearingDeg: Int = 0
    @Volatile private var latestGpsAccuracyM: Float = 12f

    // Timestamps of last karoo-ext stream updates
    @Volatile private var lastKarooSpeedMs: Long = 0L
    @Volatile private var lastKarooDistMs: Long = 0L
    @Volatile private var lastKarooGradeMs: Long = 0L
    @Volatile private var lastKarooPowerMs: Long = 0L
    @Volatile private var lastKarooHrMs: Long = 0L
    @Volatile private var lastKarooCadMs: Long = 0L

    // Internal Barometer & GPS state
    @Volatile private var baroAltitudeMeters: Double = 280.0
    @Volatile private var baroPressureHpa: Double = 1013.25
    @Volatile private var baroActive: Boolean = false
    @Volatile private var gpsActive: Boolean = false
    @Volatile private var karooConnected: Boolean = false
    @Volatile private var demoMode: Boolean = false

    private val trackHistory = CopyOnWriteArrayList<GpsTrackPoint>()
    private var prevGpsLocation: Location? = null
    private var prevGradeAltitude: Double? = null
    private var prevGradeDistanceKm: Double = 0.0
    private var elapsedSec: Long = 0L
    private val weatherFetchedOnce = AtomicBoolean(false)

    @Volatile
    private var diagnostics: LiveSensorDiagnostics = LiveSensorDiagnostics()

    @Volatile
    private var snapshot: Triple<CyclingMetricsSnapshot, WindVectorReport, AiHeuristicEvaluation> =
        Triple(
            CyclingMetricsSnapshot(
                powerInstant = 0, power3s = 0, normalizedPower = 0,
                intensityFactor = 0.0, tss = 0.0, ftpPercent = 0,
                powerZone = 1, powerZoneName = "Z1 Recovery",
                wPrimeBalanceKj = 22.0, wPrimePercent = 100,
                heartRateBpm = 0, hrZone = 1, cadenceRpm = 0,
                speedKmh = 0.0, distanceKm = 0.0, gradePercent = 0.0,
                vamMetersPerHour = 0, kilojoulesTotal = 0.0,
                segmentDeltaSeconds = 0.0, aerobicDecouplingPercent = 0.0
            ),
            WindVectorReport(
                riderHeadingDeg = 0, trueWindDirectionDeg = 0,
                trueWindSpeedKmh = 0.0, windGustsKmh = 0.0,
                relativeWindAngleDeg = 0, headwindKmh = 0.0,
                crosswindKmh = 0.0, aeroPowerImpactWatts = 0,
                windImpactLabel = "WAITING GPS/METEO",
                temperatureC = 24.0, humidityPct = 45, precipitationMm = 0.0
            ),
            AiHeuristicEvaluation(
                adaptiveTargetWatts = 220, bonkRiskPercent = 5,
                bonkStatusLabel = "OPTIMAL GLYCOGEN",
                recommendedCarbsGramsPerHour = 45, recommendedHydrationMlPerHour = 550,
                nextCarbIntakeMinutes = 20, nextHydrationMinutes = 10,
                tacticalHeadline = "SENSORES EN VIVO LISTOS",
                tacticalReason = "Esperando movimiento o datos de sensores ANT+/BLE y GPS del Karoo 3.",
                shouldTriggerInRideAlert = false
            )
        )

    fun ensureStarted(context: Context) {
        val appCtx = context.applicationContext
        startInternalHardwareSensors(appCtx)
        if (started.compareAndSet(false, true)) {
            startKarooSystemConnection(appCtx)
            startLoops()
        }
    }

    fun setDemoMode(enabled: Boolean) {
        demoMode = enabled
        if (!enabled) {
            latestPower = 0
            latestHr = 0
            latestCadence = 0
            latestSpeedKmh = 0.0
        }
    }

    fun isDemoMode(): Boolean = demoMode

    fun resetRideMetrics() {
        latestDistanceKm = 0.0
        elapsedSec = 0L
        trackHistory.clear()
    }

    private fun appendTrackPoint(lat: Double, lon: Double, force: Boolean = false) {
        if (lat == 0.0 && lon == 0.0) return
        val last = trackHistory.lastOrNull()
        if (last != null && !force) {
            val dLat = (lat - last.lat) * 111320.0
            val dLon = (lon - last.lon) * 111320.0 * cos(lat * PI / 180.0)
            val distM = kotlin.math.sqrt(dLat * dLat + dLon * dLon)
            if (distM < 3.0) return
        }
        val currentWind = snapshot.second
        trackHistory.add(
            GpsTrackPoint(
                lat = lat,
                lon = lon,
                altitudeMeters = baroAltitudeMeters,
                speedKmh = latestSpeedKmh,
                gradePct = latestGradePct,
                headwindKmh = currentWind.headwindKmh,
                timestampMs = System.currentTimeMillis()
            )
        )
        if (trackHistory.size > 600) {
            trackHistory.removeAt(0)
        }
    }

    private fun startKarooSystemConnection(appCtx: Context) {
        try {
            val service = KarooSystemService(appCtx)
            karooSystem = service
            service.connect {
                karooConnected = true
                subscribeToKarooStreams(service)
            }
        } catch (_: Exception) {
            karooConnected = false
        }
    }

    private fun extractSingleValue(event: OnStreamState): Double? {
        return try {
            for (field in event.javaClass.declaredFields) {
                field.isAccessible = true
                val v = field.get(event)
                if (v is StreamState.Streaming) {
                    val dp = v.dataPoint
                    val single = dp.values[DataType.Field.SINGLE]
                    if (single != null) return single
                    return dp.values.values.firstOrNull()
                }
            }
            for (method in event.javaClass.methods) {
                if (method.parameterCount == 0) {
                    val v = method.invoke(event)
                    if (v is StreamState.Streaming) {
                        val dp = v.dataPoint
                        val single = dp.values[DataType.Field.SINGLE]
                        if (single != null) return single
                        return dp.values.values.firstOrNull()
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun subscribeToKarooStreams(service: KarooSystemService) {
        // 1. Power Meter (ANT+ / BLE)
        service.addConsumer<OnStreamState>(OnStreamState.StartStreaming(DataType.Type.POWER)) { event: OnStreamState ->
            extractSingleValue(event)?.let {
                latestPower = it.roundToInt().coerceAtLeast(0)
                lastKarooPowerMs = System.currentTimeMillis()
            }
        }
        // 2. Heart Rate Strap / Optical HR (ANT+ / BLE)
        service.addConsumer<OnStreamState>(OnStreamState.StartStreaming(DataType.Type.HEART_RATE)) { event: OnStreamState ->
            extractSingleValue(event)?.let {
                latestHr = it.roundToInt().coerceAtLeast(0)
                lastKarooHrMs = System.currentTimeMillis()
            }
        }
        // 3. Cadence Sensor (ANT+ / BLE)
        service.addConsumer<OnStreamState>(OnStreamState.StartStreaming(DataType.Type.CADENCE)) { event: OnStreamState ->
            extractSingleValue(event)?.let {
                latestCadence = it.roundToInt().coerceAtLeast(0)
                lastKarooCadMs = System.currentTimeMillis()
            }
        }
        // 4. Speed (Karoo fused wheel sensor / GPS in m/s -> km/h)
        service.addConsumer<OnStreamState>(OnStreamState.StartStreaming(DataType.Type.SPEED)) { event: OnStreamState ->
            extractSingleValue(event)?.let {
                latestSpeedKmh = (it * 3.6).coerceAtLeast(0.0)
                lastKarooSpeedMs = System.currentTimeMillis()
            }
        }
        // 5. Distance (Karoo ride distance in meters -> km)
        service.addConsumer<OnStreamState>(OnStreamState.StartStreaming(DataType.Type.DISTANCE)) { event: OnStreamState ->
            extractSingleValue(event)?.let {
                latestDistanceKm = (it / 1000.0).coerceAtLeast(0.0)
                lastKarooDistMs = System.currentTimeMillis()
            }
        }
        // 6. Elevation Grade (%)
        service.addConsumer<OnStreamState>(OnStreamState.StartStreaming(DataType.Type.ELEVATION_GRADE)) { event: OnStreamState ->
            extractSingleValue(event)?.let {
                latestGradePct = it.coerceIn(-35.0, 35.0)
                lastKarooGradeMs = System.currentTimeMillis()
            }
        }
        // 7. Live GPS Location from Karoo System
        service.addConsumer<OnLocationChanged> { loc: OnLocationChanged ->
            if (loc.lat != 0.0 || loc.lng != 0.0) {
                latestLat = loc.lat
                latestLon = loc.lng
                gpsActive = true
                latestBearingDeg = windEngine.updateGpsPosition(loc.lat, loc.lng)
                appendTrackPoint(loc.lat, loc.lng)
                if (weatherFetchedOnce.compareAndSet(false, true)) {
                    scope.launch { windEngine.fetchWeatherBlocking(latestLat, latestLon) }
                }
            }
        }
    }

    /**
     * Connects directly to the Karoo 3's internal GPS (`LocationManager`) and
     * internal Barometric Altimeter (`Sensor.TYPE_PRESSURE`) so live coordinates, speed,
     * distance, altitude, grade %, and 3D map positioning work immediately.
     */
    @SuppressLint("MissingPermission")
    fun startInternalHardwareSensors(context: Context) {
        val appCtx = context.applicationContext

        // 1. Internal Barometer (Sensor.TYPE_PRESSURE)
        try {
            val sensorManager = appCtx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
            if (pressureSensor != null && !baroActive) {
                baroActive = true
                sensorManager.registerListener(
                    object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent?) {
                            val hpa = event?.values?.firstOrNull() ?: return
                            if (hpa > 300f && hpa < 1100f) {
                                baroPressureHpa = (hpa * 10.0).roundToInt() / 10.0
                                val rawAlt = SensorManager.getAltitude(
                                    SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                                    hpa
                                ).toDouble()
                                baroAltitudeMeters = if (baroAltitudeMeters == 280.0) {
                                    rawAlt
                                } else {
                                    baroAltitudeMeters * 0.85 + rawAlt * 0.15
                                }
                            }
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                    },
                    pressureSensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
            }
        } catch (_: Exception) {}

        // 2. Internal GPS & Network Location (LocationManager)
        val hasFine = appCtx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = appCtx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if ((!hasFine && !hasCoarse) || !gpsListening.compareAndSet(false, true)) {
            return
        }

        try {
            val locationManager = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

            // Seed immediately with last known location so the 3D map centers right away
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            for (prov in providers) {
                try {
                    val lastLoc = locationManager.getLastKnownLocation(prov)
                    if (lastLoc != null && (lastLoc.latitude != 0.0 || lastLoc.longitude != 0.0)) {
                        latestLat = lastLoc.latitude
                        latestLon = lastLoc.longitude
                        gpsActive = true
                        if (lastLoc.hasAccuracy()) latestGpsAccuracyM = lastLoc.accuracy
                        appendTrackPoint(lastLoc.latitude, lastLoc.longitude, force = true)
                        if (weatherFetchedOnce.compareAndSet(false, true)) {
                            scope.launch { windEngine.fetchWeatherBlocking(latestLat, latestLon) }
                        }
                        break
                    }
                } catch (_: Exception) {}
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (location.latitude == 0.0 && location.longitude == 0.0) return
                    gpsActive = true
                    latestLat = location.latitude
                    latestLon = location.longitude
                    if (location.hasAccuracy()) {
                        latestGpsAccuracyM = location.accuracy
                    }

                    val now = System.currentTimeMillis()

                    if (location.hasBearing() && location.bearing > 0f) {
                        latestBearingDeg = location.bearing.roundToInt() % 360
                        windEngine.updateGpsPosition(location.latitude, location.longitude)
                    } else {
                        latestBearingDeg = windEngine.updateGpsPosition(location.latitude, location.longitude)
                    }

                    if (now - lastKarooSpeedMs > 3000L && location.hasSpeed()) {
                        val spdKmh = (location.speed * 3.6).coerceAtLeast(0.0)
                        latestSpeedKmh = if (spdKmh < 1.0) 0.0 else spdKmh
                    }

                    val prev = prevGpsLocation
                    if (prev != null) {
                        val deltaMeters = prev.distanceTo(location).toDouble()
                        if (deltaMeters in 1.5..150.0 && latestSpeedKmh >= 1.5) {
                            if (now - lastKarooDistMs > 3000L) {
                                latestDistanceKm += deltaMeters / 1000.0
                            }
                            val distSinceGradeMeters = (latestDistanceKm - prevGradeDistanceKm) * 1000.0
                            if (distSinceGradeMeters >= 15.0 && now - lastKarooGradeMs > 3000L) {
                                val currentAlt = if (baroActive) baroAltitudeMeters else if (location.hasAltitude()) location.altitude else null
                                val lastAlt = prevGradeAltitude
                                if (currentAlt != null && lastAlt != null) {
                                    val rawGrade = ((currentAlt - lastAlt) / distSinceGradeMeters) * 100.0
                                    latestGradePct = (latestGradePct * 0.6 + rawGrade.coerceIn(-25.0, 25.0) * 0.4)
                                }
                                prevGradeAltitude = currentAlt
                                prevGradeDistanceKm = latestDistanceKm
                            } else if (prevGradeAltitude == null) {
                                prevGradeAltitude = if (baroActive) baroAltitudeMeters else if (location.hasAltitude()) location.altitude else null
                                prevGradeDistanceKm = latestDistanceKm
                            }
                        }
                    }
                    prevGpsLocation = location
                    appendTrackPoint(location.latitude, location.longitude)

                    if (weatherFetchedOnce.compareAndSet(false, true)) {
                        scope.launch { windEngine.fetchWeatherBlocking(latestLat, latestLon) }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            for (prov in providers) {
                try {
                    if (locationManager.isProviderEnabled(prov)) {
                        locationManager.requestLocationUpdates(
                            prov,
                            1000L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            gpsListening.set(false)
        }
    }

    private fun startLoops() {
        scope.launch {
            while (isActive) {
                elapsedSec += 1
                val now = System.currentTimeMillis()

                if (!demoMode) {
                    if (lastKarooPowerMs > 0L && now - lastKarooPowerMs > 5000L) latestPower = 0
                    if (lastKarooCadMs > 0L && now - lastKarooCadMs > 5000L) latestCadence = 0
                    if (lastKarooSpeedMs > 0L && now - lastKarooSpeedMs > 5000L && (prevGpsLocation == null || now - (prevGpsLocation?.time ?: 0L) > 5000L)) {
                        latestSpeedKmh = 0.0
                    }
                } else {
                    // Indoor demo simulation around the user's real base coordinates
                    latestPower = 248 + ((elapsedSec % 7).toInt() - 3) * 6
                    latestHr = 161 + ((elapsedSec % 5).toInt() - 2)
                    latestCadence = 90 + ((elapsedSec % 3).toInt() - 1)
                    latestSpeedKmh = 34.2
                    latestDistanceKm += 34.2 / 3600.0
                    latestGradePct = 2.4
                    latestBearingDeg = ((latestBearingDeg + 4) % 360)
                    val rad = latestBearingDeg * PI / 180.0
                    latestLat += (cos(rad) * 0.00008)
                    latestLon += (sin(rad) * 0.00008)
                    appendTrackPoint(latestLat, latestLon, force = true)
                }

                val metrics = calculator.updateOneSecondSample(
                    powerWatts = latestPower,
                    heartRateBpm = latestHr,
                    cadenceRpm = latestCadence,
                    speedKmh = latestSpeedKmh,
                    distanceKm = latestDistanceKm,
                    gradePercent = latestGradePct
                )
                val wind = windEngine.computeWindVector(
                    riderHeadingDeg = latestBearingDeg,
                    riderSpeedKmh = latestSpeedKmh,
                    cda = calculator.profile.cda
                )
                val ai = aiCoach.evaluate(
                    metrics = metrics,
                    wind = wind,
                    profile = calculator.profile,
                    elapsedSeconds = elapsedSec
                )

                var activeStreams = 0
                if (now - lastKarooPowerMs <= 5000L) activeStreams++
                if (now - lastKarooHrMs <= 5000L) activeStreams++
                if (now - lastKarooCadMs <= 5000L) activeStreams++
                if (now - lastKarooSpeedMs <= 5000L) activeStreams++

                diagnostics = LiveSensorDiagnostics(
                    karooServiceConnected = karooConnected,
                    gpsFixActive = gpsActive,
                    barometerActive = baroActive,
                    altitudeMeters = (baroAltitudeMeters * 10.0).roundToInt() / 10.0,
                    pressureHpa = baroPressureHpa,
                    activeStreamsCount = activeStreams,
                    demoModeEnabled = demoMode,
                    latitude = latestLat,
                    longitude = latestLon,
                    bearingDeg = latestBearingDeg,
                    gpsAccuracyMeters = latestGpsAccuracyM,
                    trackPoints = trackHistory.toList()
                )

                snapshot = Triple(metrics, wind, ai)

                if (ai.shouldTriggerInRideAlert && karooConnected) {
                    try {
                        karooSystem?.dispatch(
                            InRideAlert(
                                id = "ai-coach-alert",
                                icon = android.R.drawable.ic_dialog_info,
                                title = ai.tacticalHeadline,
                                detail = ai.tacticalReason,
                                autoDismissMs = 8000L,
                                backgroundColor = android.R.color.holo_blue_dark,
                                textColor = android.R.color.white
                            )
                        )
                    } catch (_: Exception) {}
                }
                delay(1000L)
            }
        }

        // 5-Minute Open-Meteo Weather Refresh Loop
        scope.launch {
            while (isActive) {
                windEngine.fetchWeatherBlocking(latestLat, latestLon)
                delay(300_000L)
            }
        }
    }

    fun latestState() = snapshot

    fun latestDiagnostics() = diagnostics
}
