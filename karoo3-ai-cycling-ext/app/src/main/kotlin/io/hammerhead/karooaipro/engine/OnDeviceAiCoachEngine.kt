package io.hammerhead.karooaipro.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class AiHeuristicEvaluation(
    val adaptiveTargetWatts: Int,
    val bonkRiskPercent: Int,
    val bonkStatusLabel: String,
    val recommendedCarbsGramsPerHour: Int,
    val recommendedHydrationMlPerHour: Int,
    val nextCarbIntakeMinutes: Int,
    val nextHydrationMinutes: Int,
    val tacticalHeadline: String,
    val tacticalReason: String,
    val shouldTriggerInRideAlert: Boolean
)

/**
 * 100% Offline On-Device AI Heuristic Engine for Karoo 3.
 * Combines physiological strain (W' Balance, IF, TSS, Cardiac Drift),
 * terrain grade, and Open-Meteo wind vectors to produce adaptive power targets,
 * bonk/fatigue risk predictions, and dynamic fueling schedules.
 */
class OnDeviceAiCoachEngine {

    private var lastAlertTimestampSec: Long = -300L

    fun evaluate(
        metrics: CyclingMetricsSnapshot,
        wind: WindVectorReport,
        profile: RiderProfile,
        elapsedSeconds: Long,
        carbsConsumedGrams: Int = 0
    ): AiHeuristicEvaluation {
        val ftp = profile.ftpWatts.toDouble()

        // 1. Adaptive Target Power heuristic:
        // Base target starts at upper Endurance / Tempo (82% FTP), scales up slightly on climbs
        // for constant speed efficiency, scales down if W' balance or cardiac drift signals fatigue,
        // and moderates surging into heavy headwinds to preserve anaerobic matches.
        var targetMultiplier = 0.82
        if (metrics.gradePercent >= 4.0) {
            targetMultiplier += min(0.14, metrics.gradePercent * 0.015)
        } else if (metrics.gradePercent <= -2.0) {
            targetMultiplier -= 0.18
        }

        // Penalize target if W' reserve is depleted (< 40%) or aerobic decoupling > 5%
        if (metrics.wPrimePercent < 40) {
            targetMultiplier -= (40 - metrics.wPrimePercent) * 0.004
        }
        if (metrics.aerobicDecouplingPercent > 5.0) {
            targetMultiplier -= min(0.10, (metrics.aerobicDecouplingPercent - 5.0) * 0.015)
        }

        // In strong headwinds (>15 km/h), advise steady sub-threshold pacing in the drops
        if (wind.headwindKmh > 15.0 && targetMultiplier > 0.92) {
            targetMultiplier = 0.90
        }

        val adaptiveTargetWatts = (ftp * targetMultiplier.coerceIn(0.55, 1.05)).roundToInt()

        // 2. Glycogen / Bonk Risk Index (0 - 100%)
        // Estimates endogenous glycogen depletion (~450g / ~1800 kcal reserve) vs carb intake and W' burn
        val approxCarbsBurnedGrams = (metrics.kilojoulesTotal * 0.24 * max(0.35, metrics.intensityFactor - 0.35)).roundToInt()
        val netCarbDeficitGrams = max(0, approxCarbsBurnedGrams - carbsConsumedGrams)
        val deficitRisk = (netCarbDeficitGrams.toDouble() / 160.0) * 65.0
        val wPrimeRisk = ((100 - metrics.wPrimePercent).toDouble() / 100.0) * 25.0
        val driftRisk = max(0.0, metrics.aerobicDecouplingPercent) * 2.0
        val bonkRisk = (deficitRisk + wPrimeRisk + driftRisk).roundToInt().coerceIn(5, 99)

        val bonkLabel = when {
            bonkRisk >= 75 -> "HIGH BONK RISK"
            bonkRisk >= 45 -> "MODERATE FATIGUE"
            else -> "OPTIMAL GLYCOGEN"
        }

        // 3. Dynamic Carb & Hydration Targets
        val recommendedCarbsHr = when {
            metrics.intensityFactor >= 0.88 -> 90
            metrics.intensityFactor >= 0.75 -> 65
            else -> 45
        }
        val heatFactor = max(0.0, wind.temperatureC - 18.0) * 22.0
        val humidityFactor = max(0, wind.humidityPct - 50) * 3.5
        val recommendedHydrationMlHr = (500.0 + heatFactor + humidityFactor).roundToInt().coerceIn(450, 1100)

        val minutesElapsed = (elapsedSeconds / 60L).toInt()
        val nextCarbMin = max(1, 20 - (minutesElapsed % 20))
        val nextDrinkMin = max(1, 10 - (minutesElapsed % 10))

        // 4. Context-Aware Tactical Coach Headline & Rationale
        val (headline, reason, urgent) = selectTacticalDirective(
            metrics = metrics,
            wind = wind,
            adaptiveTarget = adaptiveTargetWatts,
            bonkRisk = bonkRisk,
            nextCarbMin = nextCarbMin
        )

        val triggerAlert = urgent && (elapsedSeconds - lastAlertTimestampSec >= 180L)
        if (triggerAlert) {
            lastAlertTimestampSec = elapsedSeconds
        }

        return AiHeuristicEvaluation(
            adaptiveTargetWatts = adaptiveTargetWatts,
            bonkRiskPercent = bonkRisk,
            bonkStatusLabel = bonkLabel,
            recommendedCarbsGramsPerHour = recommendedCarbsHr,
            recommendedHydrationMlPerHour = recommendedHydrationMlHr,
            nextCarbIntakeMinutes = nextCarbMin,
            nextHydrationMinutes = nextDrinkMin,
            tacticalHeadline = headline,
            tacticalReason = reason,
            shouldTriggerInRideAlert = triggerAlert
        )
    }

    private fun selectTacticalDirective(
        metrics: CyclingMetricsSnapshot,
        wind: WindVectorReport,
        adaptiveTarget: Int,
        bonkRisk: Int,
        nextCarbMin: Int
    ): Triple<String, String, Boolean> {
        return when {
            metrics.wPrimePercent <= 25 -> Triple(
                "CRITICAL W' RESERVE (${metrics.wPrimeBalanceKj} kJ)",
                "Drop to ${adaptiveTarget}W immediately for 90s to recharge anaerobic capacity.",
                true
            )
            bonkRisk >= 75 || nextCarbMin <= 2 -> Triple(
                "FUEL NOW: TAKE 25-30g CARBS",
                "High glycogen burn (${metrics.kilojoulesTotal.roundToInt()} kJ). Eat a gel/chew & drink 150ml.",
                true
            )
            wind.headwindKmh >= 14.0 && metrics.power3s > adaptiveTarget + 25 -> Triple(
                "AERO TUCK: ${wind.headwindKmh} KM/H HEADWIND",
                "Wind adds +${wind.aeroPowerImpactWatts}W drag. Hold ${adaptiveTarget}W in the drops instead of surging.",
                true
            )
            kotlin.math.abs(wind.crosswindKmh) >= 18.0 -> Triple(
                "CROSSWIND ALERT (${kotlin.math.abs(wind.crosswindKmh)} KM/H)",
                "Strong lateral gusts (${wind.windGustsKmh} km/h). Relax elbows and shelter on the leeward side.",
                false
            )
            metrics.gradePercent >= 5.0 -> Triple(
                "CLIMB PACING: HOLD ${adaptiveTarget}W",
                "Steady ${metrics.gradePercent}% grade (${metrics.vamMetersPerHour} m/h VAM). Keep cadence >80 rpm.",
                false
            )
            wind.headwindKmh <= -10.0 -> Triple(
                "TAILWIND ASSIST (${kotlin.math.abs(wind.headwindKmh)} KM/H)",
                "Saving ${kotlin.math.abs(wind.aeroPowerImpactWatts)}W aero drag. Shift up and carry momentum.",
                false
            )
            else -> Triple(
                "ON PACE: TARGET ${adaptiveTarget}W (Z${metrics.powerZone})",
                "Aerobic decoupling ${metrics.aerobicDecouplingPercent}%. Next carb intake in ${nextCarbMin}m.",
                false
            )
        }
    }
}
