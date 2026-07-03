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

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import java.io.ByteArrayOutputStream
import kotlin.math.abs

private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val SILENCE_THRESHOLD = 800
private const val SILENCE_DURATION_MS = 1500L
private const val MAX_RECORDING_MS = 60_000L

object AudioVadRecorder {

  fun recordUntilSilence(): ByteArray {
    val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    val recorder =
      AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT,
        minBufferSize * 2,
      )
    val stream = ByteArrayOutputStream()
    val buffer = ShortArray(minBufferSize / 2)
    var lastSpeechMs = System.currentTimeMillis()
    val startMs = lastSpeechMs

    try {
      recorder.startRecording()
      while (true) {
        val read = recorder.read(buffer, 0, buffer.size)
        if (read > 0) {
          val peak = buffer.take(read).maxOf { abs(it.toInt()) }
          if (peak > SILENCE_THRESHOLD) {
            lastSpeechMs = System.currentTimeMillis()
          }
          for (i in 0 until read) {
            val sample = buffer[i]
            stream.write(sample.toInt() and 0xFF)
            stream.write((sample.toInt() shr 8) and 0xFF)
          }
        }
        val elapsed = System.currentTimeMillis() - startMs
        val silentFor = System.currentTimeMillis() - lastSpeechMs
        if (elapsed > 500 && silentFor >= SILENCE_DURATION_MS) break
        if (elapsed >= MAX_RECORDING_MS) break
      }
    } finally {
      try {
        recorder.stop()
      } catch (_: Exception) {}
      recorder.release()
    }
    return stream.toByteArray()
  }
}
