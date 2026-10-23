package io.hammerhead.karooaipro.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class RiderProfile(
    val ftpWatts: Int = 280,
    val wPrimeJoules: Double = 22000.0, // 22.0 kJ anaerobic capacity
    val maxHrBpm: Int = 190,
    val restingHrBpm: Int = 52,
    val weightKg: Double = 72.0,
    val bikeWeightKg: Double = 7.8,
    val cda: Double = 0.32, // m^2 aerodynamic drag area
    val crr: Double = 0.0045
)

data class CyclingMetricsSnapshot(
    val powerInstant: Int,
    val power3s: Int,
    val normalizedPower: Int,
    val intensityFactor: Double,
    val tss: Double,
    val ftpPercent: Int,
    val powerZone: Int,
    val powerZoneName: String,
    val wPrimeBalanceKj: Double,
    val wPrimePercent: Int,
    val heartRateBpm: Int,
    val hrZone: Int,
    val cadenceRpm: Int,
    val speedKmh: Double,
    val distanceKm: Double,
    val gradePercent: Double,
    val vamMetersPerHour: Int,
    val kilojoulesTotal: Double,
    val segmentDeltaSeconds: Double,
    val aerobicDecouplingPercent: Double
)

/**
 * Computes TrainingPeaks (NP, IF, TSS), Zwift/Coggan 7-Zone Power,
 * Skiba W' Balance, VAM, and Strava-style Live Segment Virtual Partner metrics.
 */
class AdvancedCyclingCalculator(var profile: RiderProfile = RiderProfile()) {

    private val window3s = ArrayDeque<Int>(3)
    private val window30s = ArrayDeque<Int>(30)
    private var sumFourthPowerRolling: Double = 0.0
    private var rollingCount: Long = 0L
    private var totalWorkJoules: Double = 0.0
    private var elapsedSeconds: Long = 0L

    // Skiba W' Balance state
    private var wPrimeBalJoules: Double = profile.wPrimeJoules

    // Aerobic decoupling (Pa:Hr) first-half vs second-half tracking
    private val powerHrRatios = ArrayDeque<Double>(1800)

    // Live Segment Virtual Partner state
    private var segmentVirtualDistKm: Double = 0.0
    private var segmentRiderDistKm: Double = 0.0

    fun updateOneSecondSample(
        powerWatts: Int,
        heartRateBpm: Int,
        cadenceRpm: Int,
        speedKmh: Double,
        distanceKm: Double,
        gradePercent: Double,
        targetSegmentSpeedKmh: Double = 32.0
    ): CyclingMetricsSnapshot {
        elapsedSeconds += 1
        val cleanPower = max(0, powerWatts)
        totalWorkJoules += cleanPower.toDouble()

        // 3-second rolling average power
        if (window3s.size == 3) window3s.removeFirst()
        window3s.addLast(cleanPower)
        val power3s = window3s.average().roundToInt()

        // 30-second rolling average for Normalized Power (Coggan algorithm)
        if (window30s.size == 30) window30s.removeFirst()
        window30s.addLast(cleanPower)
        val avg30s = window30s.average()
        sumFourthPowerRolling += avg30s.pow(4.0)
        rollingCount += 1

        val np = if (rollingCount > 0) {
            (sumFourthPowerRolling / rollingCount.toDouble()).pow(0.25).roundToInt()
        } else {
            power3s
        }

        val ftp = max(100, profile.ftpWatts).toDouble()
        val intensityFactor = np.toDouble() / ftp
        val tss = ((elapsedSeconds.toDouble() * np.toDouble() * intensityFactor) / (ftp * 3600.0)) * 100.0
        val ftpPct = ((power3s.toDouble() / ftp) * 100.0).roundToInt()

        // Skiba Differential W' Balance update (1-second step)
        if (cleanPower > profile.ftpWatts) {
            val depletion = (cleanPower - profile.ftpWatts).toDouble()
            wPrimeBalJoules = max(0.0, wPrimeBalJoules - depletion)
        } else {
            val dcp = (profile.ftpWatts - cleanPower).toDouble()
            val tau = 546.0 * exp(-0.01 * dcp) + 316.0
            val recovery = (profile.wPrimeJoules - wPrimeBalJoules) * (1.0 - exp(-1.0 / tau))
            wPrimeBalJoules = min(profile.wPrimeJoules, wPrimeBalJoules + recovery)
        }

        val wPrimeKj = wPrimeBalJoules / 1000.0
        val wPrimePct = ((wPrimeBalJoules / profile.wPrimeJoules) * 100.0).roundToInt().coerceIn(0, 100)

        // Coggan 7-Zone Power
        val (pZone, pZoneName) = resolvePowerZone(ftpPct)

        // 5-Zone Heart Rate
        val hrZone = resolveHrZone(heartRateBpm, profile.maxHrBpm)

        // VAM (Vertical Ascent in Meters/Hour)
        val speedMetersPerSec = speedKmh / 3.6
        val verticalSpeedMps = speedMetersPerSec * (gradePercent / 100.0)
        val vam = max(0.0, verticalSpeedMps * 3600.0).roundToInt()

        // Live Segment Virtual Partner Delta (seconds ahead/behind target pace)
        segmentRiderDistKm += (speedKmh / 3600.0)
        segmentVirtualDistKm += (targetSegmentSpeedKmh / 3600.0)
        val distDiffKm = segmentRiderDistKm - segmentVirtualDistKm
        val refSpeedKmh = max(10.0, targetSegmentSpeedKmh)
        val segmentDeltaSec = -(distDiffKm / refSpeedKmh) * 3600.0 // negative = ahead of PR/KOM

        // Aerobic Decoupling (Pa:Hr drift %)
        if (heartRateBpm > 90 && power3s > 50) {
            if (powerHrRatios.size >= 1800) powerHrRatios.removeFirst()
            powerHrRatios.addLast(power3s.toDouble() / heartRateBpm.toDouble())
        }
        val decouplingPct = computeDecoupling()

        return CyclingMetricsSnapshot(
            powerInstant = cleanPower,
            power3s = power3s,
            normalizedPower = np,
            intensityFactor = (intensityFactor * 100.0).roundToInt() / 100.0,
            tss = (tss * 10.0).roundToInt() / 10.0,
            ftpPercent = ftpPct,
            powerZone = pZone,
            powerZoneName = pZoneName,
            wPrimeBalanceKj = (wPrimeKj * 10.0).roundToInt() / 10.0,
            wPrimePercent = wPrimePct,
            heartRateBpm = heartRateBpm,
            hrZone = hrZone,
            cadenceRpm = cadenceRpm,
            speedKmh = (speedKmh * 10.0).roundToInt() / 10.0,
            distanceKm = (distanceKm * 100.0).roundToInt() / 100.0,
            gradePercent = (gradePercent * 10.0).roundToInt() / 10.0,
            vamMetersPerHour = vam,
            kilojoulesTotal = (totalWorkJoules / 1000.0 * 10.0).roundToInt() / 10.0,
            segmentDeltaSeconds = (segmentDeltaSec * 10.0).roundToInt() / 10.0,
            aerobicDecouplingPercent = decouplingPct
        )
    }

    private fun resolvePowerZone(ftpPct: Int): Pair<Int, String> = when {
        ftpPct < 55 -> 1 to "Z1 Active Recovery"
        ftpPct <= 75 -> 2 to "Z2 Endurance"
        ftpPct <= 90 -> 3 to "Z3 Tempo"
        ftpPct <= 105 -> 4 to "Z4 Threshold"
        ftpPct <= 120 -> 5 to "Z5 VO2 Max"
        ftpPct <= 150 -> 6 to "Z6 Anaerobic"
        else -> 7 to "Z7 Neuromuscular"
    }

    private fun resolveHrZone(hr: Int, maxHr: Int): Int {
        val pct = (hr.toDouble() / max(120, maxHr).toDouble()) * 100.0
        return when {
            pct < 68 -> 1
            pct < 83 -> 2
            pct < 94 -> 3
            pct < 105 -> 4
            else -> 5
        }
    }

    private fun computeDecoupling(): Double {
        if (powerHrRatios.size < 20) return 0.0
        val half = powerHrRatios.size / 2
        val firstHalf = powerHrRatios.take(half).average()
        val secondHalf = powerHrRatios.drop(half).average()
        if (firstHalf <= 0.0) return 0.0
        val drift = ((firstHalf - secondHalf) / firstHalf) * 100.0
        return (drift * 10.0).roundToInt() / 10.0
    }
}
