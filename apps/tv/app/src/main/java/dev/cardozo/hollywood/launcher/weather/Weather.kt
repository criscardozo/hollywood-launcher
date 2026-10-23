package dev.cardozo.hollywood.launcher.weather

import dev.cardozo.hollywood.launcher.net.Http
import dev.cardozo.hollywood.launcher.settings.LauncherSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.math.roundToInt

/** The weather in the top bar: whole degrees and a symbol. */
data class Weather(val temperature: Int, val symbol: String)

/** A place from Open-Meteo's geocoding, offered on the settings page. */
@Serializable
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    @SerialName("admin1") val region: String? = null,
)

@Serializable private data class Places(val results: List<Place> = emptyList())
@Serializable private data class Current(@SerialName("temperature_2m") val temperature: Double, @SerialName("weather_code") val code: Int, @SerialName("is_day") val isDay: Int = 1)
@Serializable private data class Forecast(val current: Current)

/**
 * Open-Meteo: free, no key, no account. One request when Home shows and then every half hour while it
 * stays on screen; nothing in the background.
 */
class WeatherRepository(private val settings: LauncherSettings) {

    suspend fun current(): Weather? {
        val s = settings.current()
        val latitude = s.weatherLatitude ?: return null
        val longitude = s.weatherLongitude ?: return null
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", latitude.toString())
            .addQueryParameter("longitude", longitude.toString())
            .addQueryParameter("current", "temperature_2m,weather_code,is_day")
            .build()
        val current = Http.json.decodeFromString<Forecast>(Http.get(Request.Builder().url(url).build())).current
        return Weather(current.temperature.roundToInt(), symbol(current.code, current.isDay == 1))
    }

    suspend fun search(query: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", query)
            .addQueryParameter("count", "8")
            .addQueryParameter("language", "es")
            .build()
        return Http.json.decodeFromString<Places>(Http.get(Request.Builder().url(url).build())).results
    }

    /** WMO weather codes (open-meteo.com/en/docs) to an emoji the TV's font already has. */
    private fun symbol(code: Int, day: Boolean): String = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1, 2 -> if (day) "🌤️" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }
}
