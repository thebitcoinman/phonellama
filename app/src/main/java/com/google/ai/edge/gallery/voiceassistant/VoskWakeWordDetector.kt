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

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import org.vosk.Model
import org.vosk.Recognizer

private const val TAG = "VoskWakeWordDetector"
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

object VoskWakeWordDetector {

  val defaultPhrases = listOf("hey llama", "hey phone llama", "phone llama")

  /**
   * Blocks until a wake phrase is detected or [isRunning] returns false.
   * Uses Vosk grammar mode — fully offline, no third-party API keys.
   */
  fun listenForWakePhrase(
    context: Context,
    phrases: List<String>,
    isRunning: () -> Boolean,
  ): Boolean {
    if (!VoskTranscriber.isModelDownloaded(context)) {
      Log.e(TAG, "Vosk model not downloaded")
      return false
    }
    val activePhrases = phrases.filter { it.isNotBlank() }.ifEmpty { defaultPhrases }
    val grammar = activePhrases.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    var model: Model? = null
    var recognizer: Recognizer? = null
    var recorder: AudioRecord? = null
    try {
      model = Model(VoskTranscriber.getModelDir(context).absolutePath)
      recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammar)

      val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
      recorder =
        AudioRecord(
          MediaRecorder.AudioSource.MIC,
          SAMPLE_RATE,
          CHANNEL_CONFIG,
          AUDIO_FORMAT,
          minBufferSize * 2,
        )
      val buffer = ByteArray(minBufferSize)
      recorder.startRecording()

      while (isRunning()) {
        val read = recorder.read(buffer, 0, buffer.size)
        if (read <= 0) continue

        if (recognizer.acceptWaveForm(buffer, read)) {
          val text = extractText(recognizer.result)
          if (text.isNotBlank()) {
            Log.d(TAG, "Wake phrase detected (final): $text")
            return true
          }
        } else {
          val partial = extractText(recognizer.partialResult)
          if (partial.isNotBlank() && matchesPhrase(partial, activePhrases)) {
            Log.d(TAG, "Wake phrase detected (partial): $partial")
            return true
          }
        }
      }
      return false
    } catch (e: Exception) {
      Log.e(TAG, "Wake word listen failed", e)
      return false
    } finally {
      try {
        recorder?.stop()
      } catch (_: Exception) {}
      recorder?.release()
      try {
        recognizer?.close()
      } catch (_: Exception) {}
      try {
        model?.close()
      } catch (_: Exception) {}
    }
  }

  private fun extractText(json: String): String =
    Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)?.trim().orEmpty()

  private fun matchesPhrase(text: String, phrases: List<String>): Boolean {
    val normalized = text.lowercase().trim()
    return phrases.any { phrase -> normalized.contains(phrase.lowercase()) }
  }
}
