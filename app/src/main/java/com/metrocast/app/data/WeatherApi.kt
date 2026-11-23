package com.metrocast.app.data

import androidx.annotation.DrawableRes
import com.metrocast.app.R
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Open-Meteo forecast + geocoding. Free, no API key. */
object WeatherApi {

    fun forecastRaw(lat: Double, lon: Double, fahrenheit: Boolean): String {
        val units = if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,wind_speed_10m" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7$units"
        return Http.get(url)
    }

    /**
     * @param ageMs how old the cached response is, so "next 24 hours" stays relative to now.
     */
    fun parse(raw: String, locationName: String, ageMs: Long = 0): Weather {
        val root = JSONObject(raw)
        val cur = root.getJSONObject("current")
        val fetchedAt = LocalDateTime.parse(cur.getString("time"))
        val now = fetchedAt.plusSeconds(ageMs / 1000)
        val temp = cur.getDouble("temperature_2m")

        val current = CurrentWeather(
            temp = temp,
            feelsLike = cur.optDouble("apparent_temperature", temp),
            code = cur.optInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            wind = cur.optDouble("wind_speed_10m", 0.0),
            humidity = cur.optInt("relative_humidity_2m"),
        )

        val h = root.getJSONObject("hourly")
        val hTimes = h.getJSONArray("time")
        val hTemps = h.getJSONArray("temperature_2m")
        val hCodes = h.getJSONArray("weather_code")
        val hPrecip = h.getJSONArray("precipitation_probability")
        val hDay = h.getJSONArray("is_day")
        val startHour = now.truncatedTo(ChronoUnit.HOURS)
        val hourly = (0 until hTimes.length()).map { i ->
            HourlyPoint(
                time = LocalDateTime.parse(hTimes.getString(i)),
                temp = hTemps.optDouble(i),
                code = hCodes.optInt(i),
                precipChance = hPrecip.optInt(i),
                isDay = hDay.optInt(i, 1) == 1,
            )
        }.filter { !it.time.isBefore(startHour) }.take(24)

        val d = root.getJSONObject("daily")
        val dTimes = d.getJSONArray("time")
        val dCodes = d.getJSONArray("weather_code")
        val dMax = d.getJSONArray("temperature_2m_max")
        val dMin = d.getJSONArray("temperature_2m_min")
        val dPrecip = d.getJSONArray("precipitation_probability_max")
        val today = now.toLocalDate()
        val daily = (0 until dTimes.length()).map { i ->
            DailyPoint(
                date = LocalDate.parse(dTimes.getString(i)),
                code = dCodes.optInt(i),
                high = dMax.optDouble(i),
                low = dMin.optDouble(i),
                precipChance = dPrecip.optInt(i),
            )
        }.filter { !it.date.isBefore(today) }

        val windUnit = root.optJSONObject("current_units")?.str("wind_speed_10m")
            ?.replace("mp/h", "mph") ?: "mph"

        return Weather(locationName, current, hourly, daily, windUnit)
    }

    fun searchPlaces(query: String): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        // A 5-digit ZIP code: look only in the US (the same digits are postal codes elsewhere too).
        val us = if (Regex("""\d{5}""").matches(query.trim())) "&countryCode=US" else ""
        val raw = Http.get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=en&format=json$us")
        val arr = JSONObject(raw).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val name = o.str("name") ?: ""
            val detail = listOfNotNull(o.str("admin1"), o.str("country")).joinToString(", ")
            Place(name, detail, o.getDouble("latitude"), o.getDouble("longitude"))
        }
    }
}

object WeatherCodes {
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Showers"
        81 -> "Rain showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorms"
        96, 99 -> "Storms with hail"
        else -> "—"
    }

    @DrawableRes
    fun icon(code: Int, isDay: Boolean): Int = when (code) {
        0, 1 -> if (isDay) R.drawable.ic_wx_sun else R.drawable.ic_wx_moon
        2 -> if (isDay) R.drawable.ic_wx_partly_day else R.drawable.ic_wx_partly_night
        3 -> R.drawable.ic_wx_cloud
        45, 48 -> R.drawable.ic_wx_fog
        in 51..67, in 80..82 -> R.drawable.ic_wx_rain
        in 71..77, 85, 86 -> R.drawable.ic_wx_snow
        in 95..99 -> R.drawable.ic_wx_storm
        else -> R.drawable.ic_wx_cloud
    }
}
