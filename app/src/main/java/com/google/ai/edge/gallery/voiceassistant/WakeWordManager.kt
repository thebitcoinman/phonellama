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
import android.content.Intent
import android.util.Log
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private const val TAG = "WakeWordManager"

object WakeWordManager {
  var onTranscript: ((String) -> Unit)? = null
  private var running = false

  fun start(
    context: Context,
    wakePhrase: String = "",
    onTranscriptCallback: (String) -> Unit,
  ): Boolean {
    if (!VoskTranscriber.isModelDownloaded(context)) {
      VoiceAssistantManager.updateStatus("Download the Vosk STT model first")
      return false
    }
    onTranscript = onTranscriptCallback
    val intent =
      Intent(context, WakeWordService::class.java).apply {
        action = WakeWordService.ACTION_START
        putExtra(WakeWordService.EXTRA_WAKE_PHRASE, wakePhrase)
      }
    return try {
      context.startForegroundService(intent)
      running = true
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start WakeWordService", e)
      VoiceAssistantManager.updateStatus("Could not start wake phrase service: ${e.message}")
      running = false
      false
    }
  }

  fun stop(context: Context) {
    if (!running) {
      VoiceAssistantManager.setWakeWordListening(false)
      return
    }
    context.startService(
      Intent(context, WakeWordService::class.java).apply { action = WakeWordService.ACTION_STOP }
    )
    running = false
    onTranscript = null
    VoiceAssistantManager.setWakeWordListening(false)
  }

  fun markNotRunning() {
    running = false
    onTranscript = null
  }

  fun deliverTranscript(text: String) {
    Log.d(TAG, "Transcript: $text")
    onTranscript?.invoke(text)
  }

  fun start(
    context: Context,
    modelManagerViewModel: ModelManagerViewModel,
    viewModel: VoiceAssistantViewModel,
  ): Boolean {
    return start(context, viewModel.uiState.value.wakePhrase) { transcript ->
      viewModel.routeTranscript(transcript, modelManagerViewModel)
    }
  }
}
