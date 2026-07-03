/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.voiceassistant

import android.util.Log
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "WeatherClient"
private const val DEFAULT_CITY = "San Francisco"

object WeatherClient {

  suspend fun answerWeatherQuery(prompt: String): Result<String> =
    withContext(Dispatchers.IO) {
      try {
        val locationQuery = extractLocation(prompt) ?: DEFAULT_CITY
        val geo = geocode(locationQuery)
          ?: return@withContext Result.failure(
            Exception("Could not find location \"$locationQuery\". Try \"weather in Seattle\".")
          )
        val weather = fetchCurrentWeather(geo.latitude, geo.longitude)
          ?: return@withContext Result.failure(Exception("Weather data unavailable right now."))
        Result.success(formatWeatherReply(geo.displayName, weather))
      } catch (e: Exception) {
        Log.e(TAG, "Weather lookup failed", e)
        Result.failure(e)
      }
    }

  private data class GeoResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
  )

  private data class CurrentWeather(
    val temperatureC: Double,
    val feelsLikeC: Double,
    val humidity: Int,
    val windKmh: Double,
    val weatherCode: Int,
  )

  fun extractLocation(prompt: String): String? {
    val patterns =
      listOf(
        Regex("""weather\s+(?:in|for|at)\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:forecast|temperature)\s+(?:in|for|at)\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""(.+?)\s+weather(?:\s+forecast)?\s*$""", RegexOption.IGNORE_CASE),
        Regex("""what(?:'s| is) the weather(?: like)?(?: in| for| at)?\s+(.+)""", RegexOption.IGNORE_CASE),
      )
    for (pattern in patterns) {
      val match = pattern.find(prompt.trim()) ?: continue
      val raw = match.groupValues[1].trim().trimEnd('?', '.', '!')
      if (raw.isNotBlank() && raw.length <= 80) {
        return raw.replace(Regex("""\b(like|today|tomorrow|now|please)\b""", RegexOption.IGNORE_CASE), "")
          .trim()
          .ifBlank { null }
      }
    }
    return null
  }

  private fun geocode(name: String): GeoResult? {
    val encoded = URLEncoder.encode(name, Charsets.UTF_8.name())
    val url =
      URL("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json")
    val json = getJson(url) ?: return null
    val results = json.getAsJsonArray("results") ?: return null
    if (results.size() == 0) return null
    val first = results[0].asJsonObject
    val city = first.get("name")?.asString ?: name
    val admin = first.get("admin1")?.asString
    val country = first.get("country")?.asString
    val displayName =
      listOfNotNull(city, admin, country).distinct().joinToString(", ")
    return GeoResult(
      displayName = displayName,
      latitude = first.get("latitude").asDouble,
      longitude = first.get("longitude").asDouble,
    )
  }

  private fun fetchCurrentWeather(lat: Double, lon: Double): CurrentWeather? {
    val url =
      URL(
        "https://api.open-meteo.com/v1/forecast?" +
          "latitude=$lat&longitude=$lon" +
          "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
          "&timezone=auto"
      )
    val json = getJson(url) ?: return null
    val current = json.getAsJsonObject("current") ?: return null
    return CurrentWeather(
      temperatureC = current.get("temperature_2m").asDouble,
      feelsLikeC = current.get("apparent_temperature").asDouble,
      humidity = current.get("relative_humidity_2m").asInt,
      windKmh = current.get("wind_speed_10m").asDouble,
      weatherCode = current.get("weather_code").asInt,
    )
  }

  private fun getJson(url: URL): com.google.gson.JsonObject? {
    val connection = url.openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("User-Agent", "PhoneLlama/1.0")
    connection.connect()
    if (connection.responseCode !in 200..299) {
      Log.w(TAG, "HTTP ${connection.responseCode} for $url")
      return null
    }
    val text = connection.inputStream.bufferedReader().use { it.readText() }
    connection.disconnect()
    return JsonParser.parseString(text).asJsonObject
  }

  private fun formatWeatherReply(location: String, weather: CurrentWeather): String {
    val condition = wmoDescription(weather.weatherCode)
    val tempF = celsiusToFahrenheit(weather.temperatureC)
    val feelsF = celsiusToFahrenheit(weather.feelsLikeC)
    return buildString {
      append("Current weather in $location: $condition. ")
      append("${weather.temperatureC.toInt()}°C (${tempF}°F), ")
      append("feels like ${weather.feelsLikeC.toInt()}°C (${feelsF}°F). ")
      append("Humidity ${weather.humidity}%, wind ${weather.windKmh.toInt()} km/h.")
    }
  }

  private fun celsiusToFahrenheit(c: Double): Int = ((c * 9 / 5) + 32).toInt()

  private fun wmoDescription(code: Int): String =
    when (code) {
      0 -> "clear sky"
      1, 2, 3 -> "partly cloudy"
      45, 48 -> "foggy"
      51, 53, 55 -> "light drizzle"
      61, 63, 65 -> "rain"
      71, 73, 75 -> "snow"
      80, 81, 82 -> "rain showers"
      95 -> "thunderstorm"
      else -> "variable conditions"
    }
}
