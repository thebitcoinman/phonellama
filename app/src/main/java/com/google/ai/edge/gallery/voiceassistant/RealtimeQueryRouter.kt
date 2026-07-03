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

enum class RealtimeIntent {
  WEATHER,
  OTHER,
}

object RealtimeQueryRouter {
  private val weatherKeywords =
    listOf(
      "weather",
      "forecast",
      "temperature",
      "rain",
      "raining",
      "snow",
      "snowing",
      "humidity",
      "wind speed",
      "will it rain",
      "how hot",
      "how cold",
    )

  private val otherRealtimeKeywords =
    listOf(
      "news",
      "headline",
      "stock price",
      "stock market",
      "score of the",
      "who won",
      "election results",
      "exchange rate",
      "bitcoin price",
    )

  fun classify(prompt: String): RealtimeIntent? {
    val lower = prompt.lowercase()
    if (weatherKeywords.any { it in lower }) return RealtimeIntent.WEATHER
    if (otherRealtimeKeywords.any { it in lower }) return RealtimeIntent.OTHER
    return null
  }

  fun needsLiveData(prompt: String): Boolean = classify(prompt) != null
}
