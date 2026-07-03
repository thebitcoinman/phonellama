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
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OrchestratorClient"
private val gson = Gson()

data class RemoteRouteResponse(
  val response: String,
  val target: String,
)

object OrchestratorClient {

  suspend fun routePrompt(baseUrl: String, prompt: String): Result<RemoteRouteResponse> =
    withContext(Dispatchers.IO) {
      try {
        val normalizedUrl = baseUrl.trim().removeSuffix("/")
        val url = URL("$normalizedUrl/v1/route")
        Log.i(TAG, "POST $url")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 15_000
        // OpenClaw agent turns on the CPU box can take many minutes on a cold
        // cache (two full prefills when the agent uses web search).
        connection.readTimeout = 600_000

        val body = gson.toJson(mapOf("prompt" to prompt, "stream" to false))
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        Log.i(TAG, "request written, awaiting response")

        val responseCode = connection.responseCode
        Log.i(TAG, "response code $responseCode")
        val stream =
          if (responseCode in 200..299) connection.inputStream
          else connection.errorStream
        val responseText =
          BufferedReader(InputStreamReader(stream)).use { it.readText() }

        if (responseCode !in 200..299) {
          return@withContext Result.failure(
            Exception("Orchestrator HTTP $responseCode: $responseText")
          )
        }

        val json = JsonParser.parseString(responseText).asJsonObject
        val content = extractAssistantText(json)
        val target = json.get("target")?.asString ?: "remote"
        Result.success(RemoteRouteResponse(response = content, target = target))
      } catch (e: Exception) {
        Log.e(TAG, "Remote route failed", e)
        Result.failure(e)
      }
    }

  private fun extractAssistantText(json: JsonObject): String {
    // OpenAI chat.completion format
    json.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.let { choice ->
      choice.getAsJsonObject("message")?.get("content")?.asString?.let { return it }
      choice.get("text")?.asString?.let { return it }
    }
    json.get("response")?.asString?.let { return it }
    json.get("content")?.asString?.let { return it }
    return json.toString()
  }
}
