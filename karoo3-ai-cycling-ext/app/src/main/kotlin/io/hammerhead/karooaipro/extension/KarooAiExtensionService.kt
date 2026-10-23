package io.hammerhead.karooaipro.extension

import android.content.Intent
import io.hammerhead.karooaipro.engine.AdvancedCyclingCalculator
import io.hammerhead.karooaipro.engine.AiHeuristicEvaluation
import io.hammerhead.karooaipro.engine.CyclingMetricsSnapshot
import io.hammerhead.karooaipro.engine.OnDeviceAiCoachEngine
import io.hammerhead.karooaipro.engine.WindVectorReport
import io.hammerhead.karooaipro.engine.WindWeatherEngine
import io.hammerhead.karooaipro.ui.MetricsDashboardActivity
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Official Hammerhead Karoo 3 Extension Service (`io.hammerhead:karoo-ext`).
 * Streams native Karoo sensor telemetry, queries key-less Open-Meteo weather,
 * runs the On-Device AI Heuristic Coach, exposes custom DataTypes to Karoo Ride Pages,
 * and launches the separate All-Metrics Pro Window (`MetricsDashboardActivity`).
 */
class KarooAiExtensionService : KarooExtension("karoo-ai-pro", "1.0.0") {

    private lateinit var karooSystem: KarooSystemService
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val calculator = AdvancedCyclingCalculator()
    private val windEngine = WindWeatherEngine()
    private val aiCoach = OnDeviceAiCoachEngine()

    @Volatile private var latestPower: Int = 0
    @Volatile private var latestHr: Int = 0
    @Volatile private var latestCadence: Int = 0
    @Volatile private var latestSpeedKmh: Double = 0.0
    @Volatile private var latestDistanceKm: Double = 0.0
    @Volatile private var latestGradePct: Double = 0.0
    @Volatile private var latestLat: Double = 29.3242
    @Volatile private var latestLon: Double = -100.9522
    private var elapsedSec: Long = 0L

    override val types: List<DataTypeImpl> by lazy {
        listOf(
            // 1. Wind Vector Data Field (Headwind/Tailwind, Crosswind, Relative Angle)
            object : DataTypeImpl("karoo-ai-pro", "wind-vector-hud") {
                override fun startStream(emitter: Emitter<StreamState>) {
                    val job = serviceScope.launch {
                        while (isActive) {
                            val wind = windEngine.computeWindVector(riderSpeedKmh = latestSpeedKmh)
                            emitter.onNext(
                                StreamState.Streaming(
                                    DataPoint(
                                        dataTypeId = dataTypeId,
                                        values = mapOf(
                                            DataType.Field.SINGLE to wind.headwindKmh,
                                            "crosswind_kmh" to wind.crosswindKmh,
                                            "relative_angle_deg" to wind.relativeWindAngleDeg.toDouble(),
                                            "aero_Only_watts" to wind.aeroPowerImpactWatts.toDouble()
                                        )
                                    )
                                )
                            )
                            delay(1000L)
                        }
                    }
                    emitter.setCancellable { job.cancel() }
                }
            },
            // 2. AI Adaptive Target Power Field
            object : DataTypeImpl("karoo-ai-pro", "ai-adaptive-target") {
                override fun startStream(emitter: Emitter<StreamState>) {
                    val job = serviceScope.launch {
                        while (isActive) {
                            val (_, _, ai) = SharedTelemetryBus.latestState()
                            emitter.onNext(
                                StreamState.Streaming(
                                    DataPoint(
                                        dataTypeId = dataTypeId,
                                        values = mapOf(
                                            DataType.Field.SINGLE to ai.adaptiveTargetWatts.toDouble(),
                                            "bonk_risk_pct" to ai.bonkRiskPercent.toDouble()
                                        )
                                    )
                                )
                            )
                            delay(1000L)
                        }
                    }
                    emitter.setCancellable { job.cancel() }
                }
            },
            // 3. Skiba W' Balance Field
            object : DataTypeImpl("karoo-ai-pro", "w-prime-balance") {
                override fun startStream(emitter: Emitter<StreamState>) {
                    val job = serviceScope.launch {
                        while (isActive) {
                            val (metrics, _, _) = SharedTelemetryBus.latestState()
                            emitter.onNext(
                                StreamState.Streaming(
                                    DataPoint(
                                        dataTypeId = dataTypeId,
                                        values = mapOf(
                                            DataType.Field.SINGLE to metrics.wPrimeBalanceKj,
                                            "w_prime_pct" to metrics.wPrimePercent.toDouble()
                                        )
                                    )
                                )
                            )
                            delay(1000L)
                        }
                    }
                    emitter.setCancellable { job.cancel() }
                }
            },
            // 4. TrainingPeaks NP / IF / TSS Summary Field
            object : DataTypeImpl("karoo-ai-pro", "np-if-tss-summary") {
                override fun startStream(emitter: Emitter<StreamState>) {
                    val job = serviceScope.launch {
                        while (isActive) {
                            val (metrics, _, _) = SharedTelemetryBus.latestState()
                            emitter.onNext(
                                StreamState.Streaming(
                                    DataPoint(
                                        dataTypeId = dataTypeId,
                                        values = mapOf(
                                            DataType.Field.SINGLE to metrics.normalizedPower.toDouble(),
                                            "intensity_factor" to metrics.intensityFactor,
                                            "tss" to metrics.tss
                                        )
                                    )
                                )
                            )
                            delay(1000L)
                        }
                    }
                    emitter.setCancellable { job.cancel() }
                }
            },
            // 5. AI Smart Carb & Hydration Countdown Field
            object : DataTypeImpl("karoo-ai-pro", "ai-fueling-timer") {
                override fun startStream(emitter: Emitter<StreamState>) {
                    val job = serviceScope.launch {
                        while (isActive) {
                            val (_, _, ai) = SharedTelemetryBus.latestState()
                            emitter.onNext(
                                StreamState.Streaming(
                                    DataPoint(
                                        dataTypeId = dataTypeId,
                                        values = mapOf(
                                            DataType.Field.SINGLE to ai.nextCarbIntakeMinutes.toDouble(),
                                            "carbs_g_hr" to ai.recommendedCarbsGramsPerHour.toDouble(),
                                            "hydration_ml_hr" to ai.recommendedHydrationMlPerHour.toDouble()
                                        )
                                    )
                                )
                            )
                            delay(1000L)
                        }
                    }
                    emitter.setCancellable { job.cancel() }
                }
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect {
            subscribeToKarooStreams()
            startLoops()
        }
    }

    /**
     * Handles BonusAction clicks from SRAM AXS / Shimano Di2 hood buttons or Karoo drawer
     * to open the separate All-Metrics Pro Window (`MetricsDashboardActivity`).
     */
    override fun onBonusAction(actionId: String) {
        if (actionId == "open-all-metrics-window") {
            val intent = Intent(applicationContext, MetricsDashboardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }

    private fun subscribeToKarooStreams() {
        karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.POWER)) { event ->
            (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let {
                latestPower = it.toInt()
            }
        }
        karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.HEART_RATE)) { event ->
            (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let {
                latestHr = it.toInt()
            }
        }
        karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.CADENCE)) { event ->
            (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let {
                latestCadence = it.toInt()
            }
        }
        karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.SPEED)) { event ->
            (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let {
                latestSpeedKmh = it * 3.6 // m/s to km/h
            }
        }
        karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.DISTANCE)) { event ->
            (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let {
                latestDistanceKm = it / 1000.0
            }
        }
        karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.ELEVATION_GRADE)) { event ->
            (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let {
                latestGradePct = it
            }
        }
        karooSystem.addConsumer { loc: OnLocationChanged ->
            latestLat = loc.lat
            latestLon = loc.lng
            windEngine.updateGpsPosition(loc.lat, loc.lng)
        }
    }

    private fun startLoops() {
        // 1 Hz Telemetry, Wind Vector & On-Device AI Evaluation Loop
        serviceScope.launch {
            while (isActive) {
                elapsedSec += 1
                val metrics = calculator.updateOneSecondSample(
                    powerWatts = latestPower,
                    heartRateBpm = latestHr,
                    cadenceRpm = latestCadence,
                    speedKmh = latestSpeedKmh,
                    distanceKm = latestDistanceKm,
                    gradePercent = latestGradePct
                )
                val wind = windEngine.computeWindVector(
                    riderSpeedKmh = latestSpeedKmh,
                    cda = calculator.profile.cda
                )
                val ai = aiCoach.evaluate(
                    metrics = metrics,
                    wind = wind,
                    profile = calculator.profile,
                    elapsedSeconds = elapsedSec
                )

                SharedTelemetryBus.publish(metrics, wind, ai)

                if (ai.shouldTriggerInRideAlert) {
                    karooSystem.dispatch(
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
                    karooSystem.dispatch(
                        PlayBeepPattern(listOf(PlayBeepPattern.Step(1200, 150)))
                    )
                }
                delay(1000L)
            }
        }

        // 5-Minute Open-Meteo Weather Refresh Loop (key-less API)
        serviceScope.launch {
            while (isActive) {
                windEngine.fetchWeatherBlocking(latestLat, latestLon)
                delay(300_000L)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }
}

/**
 * Thread-safe singleton bus sharing live 1 Hz state between `KarooAiExtensionService`
 * and the separate full-screen `MetricsDashboardActivity` window.
 */
object SharedTelemetryBus {
    @Volatile
    private var snapshot: Triple<CyclingMetricsSnapshot, WindVectorReport, AiHeuristicEvaluation> =
        Triple(
            CyclingMetricsSnapshot(
                powerInstant = 245, power3s = 242, normalizedPower = 256,
                intensityFactor = 0.91, tss = 68.4, ftpPercent = 86,
                powerZone = 3, powerZoneName = "Z3 Tempo",
                wPrimeBalanceKj = 16.4, wPrimePercent = 75,
                heartRateBpm = 162, hrZone = 4, cadenceRpm = 91,
                speedKmh = 33.8, distanceKm = 41.2, gradePercent = 2.4,
                vamMetersPerHour = 812, kilojoulesTotal = 845.0,
                segmentDeltaSeconds = -4.2, aerobicDecouplingPercent = 2.8
            ),
            WindVectorReport(
                riderHeadingDeg = 35, trueWindDirectionDeg = 15,
                trueWindSpeedKmh = 18.4, windGustsKmh = 26.0,
                relativeWindAngleDeg = -20, headwindKmh = 17.3,
                crosswindKmh = -6.3, aeroPowerImpactWatts = 38,
                windImpactLabel = "STRONG HEADWIND",
                temperatureC = 24.5, humidityPct = 42, precipitationMm = 0.0
            ),
            AiHeuristicEvaluation(
                adaptiveTargetWatts = 248, bonkRiskPercent = 28,
                bonkStatusLabel = "OPTIMAL GLYCOGEN",
                recommendedCarbsGramsPerHour = 75, recommendedHydrationMlPerHour = 680,
                nextCarbIntakeMinutes = 6, nextHydrationMinutes = 3,
                tacticalHeadline = "AERO TUCK: 17.3 KM/H HEADWIND",
                tacticalReason = "Wind adds +38W aero drag. Hold 248W in the drops instead of surging.",
                shouldTriggerInRideAlert = false
            )
        )

    fun publish(
        metrics: CyclingMetricsSnapshot,
        wind: WindVectorReport,
        ai: AiHeuristicEvaluation
    ) {
        snapshot = Triple(metrics, wind, ai)
    }

    fun latestState() = snapshot
}
