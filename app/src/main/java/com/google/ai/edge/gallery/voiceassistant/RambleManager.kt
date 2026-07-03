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

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.vosk.Model
import org.vosk.Recognizer

private const val TAG = "RambleManager"
private const val SAMPLE_RATE = 16000

data class RambleState(
  val recording: Boolean = false,
  val processing: Boolean = false,
  val mode: String = "challenge",
  val transcript: String = "",
  val segmentsSent: Int = 0,
  val status: String = "",
  val result: String = "",
)

/**
 * Long-form think-aloud mode: records continuously (no 60s cap), transcribes
 * offline with Vosk segment by segment, streams each segment to the
 * orchestrator, and on stop asks it to analyze the whole transcript against a
 * mode-specific system prompt (challenge / solve / summarize).
 */
object RambleManager {

  val modes = listOf("challenge", "solve", "summarize")

  private val _state = MutableStateFlow(RambleState())
  val state: StateFlow<RambleState> = _state.asStateFlow()

  private val stopRequested = AtomicBoolean(false)
  private var recordThread: Thread? = null
  private var sessionId: String = ""

  fun setMode(mode: String) {
    if (mode in modes) _state.value = _state.value.copy(mode = mode)
  }

  fun start(context: Context, orchestratorUrl: String) {
    if (_state.value.recording || _state.value.processing) return
    if (!VoskTranscriber.isModelDownloaded(context)) {
      _state.value = _state.value.copy(status = "Download the Vosk model first")
      return
    }
    sessionId = UUID.randomUUID().toString()
    stopRequested.set(false)
    _state.value =
      _state.value.copy(
        recording = true,
        processing = false,
        transcript = "",
        segmentsSent = 0,
        result = "",
        status = "Listening — ramble away…",
      )
    recordThread =
      thread(name = "RambleRecord") {
        try {
          runRecordingLoop(context, orchestratorUrl)
        } catch (e: Exception) {
          Log.e(TAG, "Ramble loop failed", e)
          _state.value =
            _state.value.copy(
              recording = false,
              processing = false,
              status = "Ramble failed: ${e.message}",
            )
        }
      }
  }

  fun stop() {
    stopRequested.set(true)
  }

  @SuppressLint("MissingPermission")
  private fun runRecordingLoop(context: Context, orchestratorUrl: String) {
    val model = Model(VoskTranscriber.getModelDir(context).absolutePath)
    val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
    val minBuffer =
      AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    val recorder =
      AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBuffer * 2,
      )
    val buffer = ByteArray(4096)
    val startMs = System.currentTimeMillis()

    try {
      recorder.startRecording()
      while (!stopRequested.get()) {
        val read = recorder.read(buffer, 0, buffer.size)
        if (read > 0 && recognizer.acceptWaveForm(buffer, read)) {
          val text = extractText(recognizer.result)
          if (text.isNotBlank()) {
            appendSegment(text, orchestratorUrl)
          }
        }
        val elapsedSec = (System.currentTimeMillis() - startMs) / 1000
        if (elapsedSec > 0 && elapsedSec % 15 == 0L) {
          _state.value =
            _state.value.copy(status = "Listening… ${elapsedSec / 60}m ${elapsedSec % 60}s")
        }
      }
      val tail = extractText(recognizer.finalResult)
      if (tail.isNotBlank()) {
        appendSegment(tail, orchestratorUrl)
      }
    } finally {
      try {
        recorder.stop()
      } catch (_: Exception) {}
      recorder.release()
      recognizer.close()
      model.close()
    }

    finishAndAnalyze(orchestratorUrl)
  }

  private fun appendSegment(text: String, orchestratorUrl: String) {
    val current = _state.value
    _state.value =
      current.copy(
        transcript = (current.transcript + " " + text).trim(),
        segmentsSent = current.segmentsSent + 1,
      )
    // Fire-and-forget upload; the final request re-sends nothing — the server
    // accumulates — so a dropped segment only loses that segment's words.
    VoiceAssistantManager.routeScope.launch {
      OrchestratorClient.ramble(
          baseUrl = orchestratorUrl,
          sessionId = sessionId,
          mode = _state.value.mode,
          chunk = text,
          final = false,
        )
        .onFailure { Log.w(TAG, "Segment upload failed: ${it.message}") }
    }
  }

  private fun finishAndAnalyze(orchestratorUrl: String) {
    val transcript = _state.value.transcript
    if (transcript.isBlank()) {
      _state.value =
        _state.value.copy(recording = false, processing = false, status = "No speech captured")
      return
    }
    _state.value =
      _state.value.copy(
        recording = false,
        processing = true,
        status = "Analyzing ${transcript.split(" ").size} words (~2-4 min)…",
      )
    val ticker =
      VoiceAssistantManager.routeScope.launch {
        val analysisStart = System.currentTimeMillis()
        while (true) {
          delay(15_000L)
          val sec = (System.currentTimeMillis() - analysisStart) / 1000
          _state.value = _state.value.copy(status = "Analyzing… ${sec}s")
        }
      }
    val result = runBlocking {
      OrchestratorClient.ramble(
        baseUrl = orchestratorUrl,
        sessionId = sessionId,
        mode = _state.value.mode,
        chunk = "",
        final = true,
      )
    }
    ticker.cancel()
    result.fold(
      onSuccess = { text ->
        _state.value =
          _state.value.copy(processing = false, result = text.orEmpty(), status = "Done")
      },
      onFailure = { e ->
        _state.value =
          _state.value.copy(processing = false, status = "Analysis failed: ${e.message}")
      },
    )
  }

  private fun extractText(json: String): String =
    Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)?.trim().orEmpty()
}
