package io.hammerhead.karooaipro.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Serializable
data class OpenMeteoResponse(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val current: OpenMeteoCurrent = OpenMeteoCurrent()
)

@Serializable
data class OpenMeteoCurrent(
    @SerialName("temperature_2m") val temperatureC: Double = 22.0,
    @SerialName("relative_humidity_2m") val humidityPct: Int = 45,
    @SerialName("precipitation") val precipitationMm: Double = 0.0,
    @SerialName("wind_speed_10m") val windSpeedKmh: Double = 14.0,
    @SerialName("wind_direction_10m") val windDirectionDeg: Int = 45,
    @SerialName("wind_gusts_10m") val windGustsKmh: Double = 21.0
)

data class WindVectorReport(
    val riderHeadingDeg: Int,
    val trueWindDirectionDeg: Int,
    val trueWindSpeedKmh: Double,
    val windGustsKmh: Double,
    val relativeWindAngleDeg: Int, // 0 = direct headwind, +90 = right crosswind, +/-180 = tailwind
    val headwindKmh: Double,       // positive = headwind, negative = tailwind
    val crosswindKmh: Double,      // positive = from right, negative = from left
    val aeroPowerImpactWatts: Int, // positive = extra watts needed vs calm air
    val windImpactLabel: String,
    val temperatureC: Double,
    val humidityPct: Int,
    val precipitationMm: Double
)

/**
 * Fetches key-less live weather from Open-Meteo (https://api.open-meteo.com/v1/forecast)
 * and computes real-time Headwind/Tailwind, Crosswind, and Relative Wind Angle from GPS heading.
 */
class WindWeatherEngine {

    private val json = Json { ignoreUnknownKeys = true }
    private var cachedWeather: OpenMeteoCurrent = OpenMeteoCurrent()
    private var prevLat: Double? = null
    private var prevLon: Double? = null
    private var currentBearingDeg: Int = 0

    /**
     * Builds the key-less Open-Meteo URL for use with KarooSystemService MakeHttpRequest or HttpURLConnection.
     */
    fun buildOpenMeteoUrl(lat: Double, lon: Double): String {
        return "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,precipitation,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
            "&wind_speed_unit=kmh"
    }

    fun fetchWeatherBlocking(lat: Double, lon: Double): OpenMeteoCurrent {
        val url = URL(buildOpenMeteoUrl(lat, lon))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }
        return try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseOpenMeteoJson(body)
            } else {
                cachedWeather
            }
        } catch (_: Exception) {
            cachedWeather
        } finally {
            conn.disconnect()
        }
    }

    fun parseOpenMeteoJson(payload: String): OpenMeteoCurrent {
        return try {
            val parsed = json.decodeFromString<OpenMeteoResponse>(payload)
            cachedWeather = parsed.current
            cachedWeather
        } catch (_: Exception) {
            cachedWeather
        }
    }

    /**
     * Updates GPS bearing from consecutive lat/lon fixes when moving.
     */
    fun updateGpsPosition(lat: Double, lon: Double): Int {
        val pLat = prevLat
        val pLon = prevLon
        if (pLat != null && pLon != null && (abs(lat - pLat) > 1e-5 || abs(lon - pLon) > 1e-5)) {
            currentBearingDeg = computeBearingDegrees(pLat, pLon, lat, lon)
        }
        prevLat = lat
        prevLon = lon
        return currentBearingDeg
    }

    fun computeWindVector(
        riderHeadingDeg: Int = currentBearingDeg,
        riderSpeedKmh: Double,
        cda: Double = 0.32
    ): WindVectorReport {
        // Meteorological wind direction is where the wind blows FROM.
        // If rider heads North (0°) and wind is FROM North (0°), relative angle = 0° (pure headwind).
        val rawDiff = (cachedWeather.windDirectionDeg - riderHeadingDeg + 540) % 360 - 180
        val rad = rawDiff * PI / 180.0

        val headwind = cachedWeather.windSpeedKmh * cos(rad)
        val crosswind = cachedWeather.windSpeedKmh * sin(rad)

        // Aerodynamic drag power difference: 0.5 * rho * CdA * ((v_g + v_w)^2 * sgn - v_g^2) * v_g
        val rho = 1.204 // kg/m^3 at ~20C
        val vg = riderSpeedKmh / 3.6
        val vw = headwind / 3.6
        val vAir = vg + vw
        val calmAeroWatts = 0.5 * rho * cda * vg * vg * vg
        val windAeroWatts = 0.5 * rho * cda * vAir * abs(vAir) * vg
        val deltaAeroWatts = (windAeroWatts - calmAeroWatts).roundToInt()

        val label = when {
            headwind >= 12.0 -> "STRONG HEADWIND"
            headwind >= 4.0 -> "MOD HEADWIND"
            headwind <= -12.0 -> "STRONG TAILWIND"
            headwind <= -4.0 -> "TAILWIND ASSIST"
            abs(crosswind) >= 15.0 -> "GUSTY CROSSWIND"
            else -> "CALM / NEUTRAL"
        }

        return WindVectorReport(
            riderHeadingDeg = (riderHeadingDeg + 360) % 360,
            trueWindDirectionDeg = cachedWeather.windDirectionDeg,
            trueWindSpeedKmh = (cachedWeather.windSpeedKmh * 10.0).roundToInt() / 10.0,
            windGustsKmh = (cachedWeather.windGustsKmh * 10.0).roundToInt() / 10.0,
            relativeWindAngleDeg = rawDiff,
            headwindKmh = (headwind * 10.0).roundToInt() / 10.0,
            crosswindKmh = (crosswind * 10.0).roundToInt() / 10.0,
            aeroPowerImpactWatts = deltaAeroWatts,
            windImpactLabel = label,
            temperatureC = cachedWeather.temperatureC,
            humidityPct = cachedWeather.humidityPct,
            precipitationMm = cachedWeather.precipitationMm
        )
    }

    private fun computeBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        val theta = Math.toDegrees(atan2(y, x))
        return ((theta + 360.0) % 360.0).roundToInt()
    }
}
