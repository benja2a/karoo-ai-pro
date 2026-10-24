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
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnLocationChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class KarooAiExtensionService : KarooExtension("karoo-ai-pro", "1.0.0") {

    private lateinit var karooSystem: KarooSystemService
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val calculator = AdvancedCyclingCalculator()
    private val windEngine = WindWeatherEngine()
    private val aiCoach = OnDeviceAiCoachEngine()

    @Volatile private var latestPower: Int = 245
    @Volatile private var latestHr: Int = 162
    @Volatile private var latestCadence: Int = 91
    @Volatile private var latestSpeedKmh: Double = 33.8
    @Volatile private var latestDistanceKm: Double = 41.2
    @Volatile private var latestGradePct: Double = 2.4
    @Volatile private var latestLat: Double = 29.3242
    @Volatile private var latestLon: Double = -100.9522
    private var elapsedSec: Long = 0L

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect {
            subscribeToKarooStreams()
            startLoops()
        }
    }

    fun openAllMetricsWindow() {
        val intent = Intent(applicationContext, MetricsDashboardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun subscribeToKarooStreams() {
        karooSystem.addConsumer { loc: OnLocationChanged ->
            latestLat = loc.lat
            latestLon = loc.lng
            windEngine.updateGpsPosition(loc.lat, loc.lng)
        }
    }

    private fun startLoops() {
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
                }
                delay(1000L)
            }
        }

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
