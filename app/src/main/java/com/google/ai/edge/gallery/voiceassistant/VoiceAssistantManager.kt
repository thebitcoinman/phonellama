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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceAssistantState(
  val lastTranscript: String = "",
  val lastResponse: String = "",
  val lastTierLabel: String = "",
  val lastModelOrTarget: String = "",
  val lastLatencyMs: Long = 0L,
  val statusMessage: String = "",
  val isProcessing: Boolean = false,
  val wakeWordListening: Boolean = false,
)

object VoiceAssistantManager {
  private val _state = MutableStateFlow(VoiceAssistantState())
  val state: StateFlow<VoiceAssistantState> = _state.asStateFlow()

  /**
   * Process-lifetime scope for routing work. Remote routes (OpenClaw agent turns)
   * can take many minutes; running them in viewModelScope silently cancels them
   * whenever the screen's ViewModel is cleared mid-request.
   */
  val routeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  fun updateStatus(message: String) {
    _state.value = _state.value.copy(statusMessage = message)
  }

  fun setProcessing(processing: Boolean) {
    _state.value = _state.value.copy(isProcessing = processing)
  }

  fun setWakeWordListening(listening: Boolean) {
    _state.value = _state.value.copy(wakeWordListening = listening)
  }

  fun recordResult(
    transcript: String,
    result: RouteResult,
  ) {
    when (result) {
      is RouteResult.Local ->
        _state.value =
          _state.value.copy(
            lastTranscript = transcript,
            lastResponse = result.response,
            lastTierLabel = VoiceAssistantModels.displayTierLabel(result.tier),
            lastModelOrTarget = result.modelName,
            lastLatencyMs = result.latencyMs,
            statusMessage = "Done (${result.latencyMs}ms)",
            isProcessing = false,
          )
      is RouteResult.Remote ->
        _state.value =
          _state.value.copy(
            lastTranscript = transcript,
            lastResponse = result.response,
            lastTierLabel = VoiceAssistantModels.displayTierLabel(result.tier),
            lastModelOrTarget = result.targetLabel,
            lastLatencyMs = result.latencyMs,
            statusMessage = "Done via Proxmox (${result.latencyMs}ms)",
            isProcessing = false,
          )
      is RouteResult.Error ->
        _state.value =
          _state.value.copy(
            lastTranscript = transcript,
            lastResponse = "",
            statusMessage = result.message,
            isProcessing = false,
          )
    }
  }
}
