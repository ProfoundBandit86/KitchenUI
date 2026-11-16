package com.kitchenui.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import kotlin.math.roundToInt

enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

data class DayForecast(val date: LocalDate, val code: Int, val hi: Int, val lo: Int)

data class WeatherData(
    val temp: Int,
    val code: Int,
    val hi: Int,
    val lo: Int,
    val days: List<DayForecast>,
)

data class Place(val label: String, val lat: Double, val lon: Double)

/** Weather from Open-Meteo (free, no API key). */
object Weather {
    private fun r(d: Double): Int = if (d.isNaN()) 0 else d.roundToInt()

    suspend fun fetch(lat: Double, lon: Double, fahrenheit: Boolean): WeatherData = withContext(Dispatchers.IO) {
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&temperature_unit=$unit&timezone=auto&forecast_days=6"
        val j = JSONObject(Net.get(url))
        val cur = j.getJSONObject("current")
        val daily = j.getJSONObject("daily")
        val times = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val max = daily.getJSONArray("temperature_2m_max")
        val min = daily.getJSONArray("temperature_2m_min")
        val days = (0 until times.length()).map {
            DayForecast(LocalDate.parse(times.getString(it)), codes.optInt(it), r(max.optDouble(it)), r(min.optDouble(it)))
        }
        WeatherData(
            temp = r(cur.optDouble("temperature_2m")),
            code = cur.optInt("weather_code"),
            hi = days.firstOrNull()?.hi ?: 0,
            lo = days.firstOrNull()?.lo ?: 0,
            days = days.drop(1).take(5),
        )
    }

    suspend fun geocode(name: String): Place? = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(name.trim(), "UTF-8") + "&count=1&language=en&format=json"
        val res = JSONObject(Net.get(url)).optJSONArray("results") ?: return@withContext null
        if (res.length() == 0) return@withContext null
        val o = res.getJSONObject(0)
        val region = o.optString("admin1").takeIf { it.isNotBlank() }
        Place(
            label = listOfNotNull(o.getString("name"), region).joinToString(", "),
            lat = o.getDouble("latitude"),
            lon = o.getDouble("longitude"),
        )
    }

    fun sky(code: Int): Sky = when (code) {
        0, 1 -> Sky.CLEAR
        2 -> Sky.PARTLY
        3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.CLOUDY
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorms"
        else -> "—"
    }
}
