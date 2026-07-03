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
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.DEFAULT_ORCHESTRATOR_URL
import com.google.ai.edge.gallery.data.DEFAULT_WAKE_PHRASE
import com.google.ai.edge.gallery.proto.OrchestratorMode
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "VoiceAssistantVM"

data class VoiceAssistantUiState(
  val enabled: Boolean = false,
  val orchestratorUrl: String = DEFAULT_ORCHESTRATOR_URL,
  val orchestratorMode: OrchestratorMode = OrchestratorMode.ORCHESTRATOR_MODE_PHONE_FIRST,
  val wakeWordEnabled: Boolean = false,
  val wakePhrase: String = DEFAULT_WAKE_PHRASE,
)

@HiltViewModel
class VoiceAssistantViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val dataStoreRepository: DataStoreRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(VoiceAssistantUiState())
  val uiState: StateFlow<VoiceAssistantUiState> = _uiState.asStateFlow()

  init {
    loadSettings()
  }

  private fun loadSettings() {
    _uiState.value =
      VoiceAssistantUiState(
        enabled = dataStoreRepository.readVoiceAssistantEnabled(),
        orchestratorUrl = dataStoreRepository.readOrchestratorUrl(),
        orchestratorMode = dataStoreRepository.readOrchestratorMode(),
        wakeWordEnabled = dataStoreRepository.readWakeWordEnabled(),
        wakePhrase = dataStoreRepository.readWakePhrase(),
      )
  }

  fun setEnabled(enabled: Boolean, modelManagerViewModel: ModelManagerViewModel) {
    _uiState.value = _uiState.value.copy(enabled = enabled)
    dataStoreRepository.saveVoiceAssistantEnabled(enabled)
    if (enabled) {
      VoiceAssistantManager.updateStatus("Enabling Voice Assistant…")
      viewModelScope.launch {
        VoiceAssistantManager.setProcessing(true)
        VoiceAssistantManager.updateStatus("Pre-loading Gemma E2B…")
        val orchestrator = LocalOrchestrator(context, modelManagerViewModel)
        orchestrator.preloadE2B { VoiceAssistantManager.updateStatus(it) }
          .onSuccess { VoiceAssistantManager.updateStatus("Voice Assistant ready (E2B pre-loaded)") }
          .onFailure { VoiceAssistantManager.updateStatus(it.message ?: "Pre-load failed") }
        VoiceAssistantManager.setProcessing(false)
      }
    } else {
      WakeWordManager.stop(context)
      _uiState.value = _uiState.value.copy(wakeWordEnabled = false)
      dataStoreRepository.saveWakeWordEnabled(false)
      VoiceAssistantManager.updateStatus("Voice Assistant disabled")
    }
  }

  fun setOrchestratorUrl(url: String) {
    _uiState.value = _uiState.value.copy(orchestratorUrl = url)
    dataStoreRepository.saveOrchestratorUrl(url)
  }

  fun setOrchestratorMode(mode: OrchestratorMode) {
    _uiState.value = _uiState.value.copy(orchestratorMode = mode)
    dataStoreRepository.saveOrchestratorMode(mode)
  }

  fun saveWakePhrase(phrase: String) {
    val trimmed = phrase.trim().ifBlank { DEFAULT_WAKE_PHRASE }
    _uiState.value = _uiState.value.copy(wakePhrase = trimmed)
    dataStoreRepository.saveWakePhrase(trimmed)
  }

  fun setWakeWordEnabled(
    enabled: Boolean,
    modelManagerViewModel: ModelManagerViewModel,
  ) {
    _uiState.value = _uiState.value.copy(wakeWordEnabled = enabled)
    dataStoreRepository.saveWakeWordEnabled(enabled)
    if (enabled) {
      if (!_uiState.value.enabled) {
        VoiceAssistantManager.updateStatus("Enable Voice Assistant first")
        _uiState.value = _uiState.value.copy(wakeWordEnabled = false)
        dataStoreRepository.saveWakeWordEnabled(false)
        return
      }
      if (!VoskTranscriber.isModelDownloaded(context)) {
        VoiceAssistantManager.updateStatus("Download the Vosk model first")
        _uiState.value = _uiState.value.copy(wakeWordEnabled = false)
        dataStoreRepository.saveWakeWordEnabled(false)
        return
      }
      val started =
        WakeWordManager.start(context, modelManagerViewModel, this)
      if (!started) {
        _uiState.value = _uiState.value.copy(wakeWordEnabled = false)
        dataStoreRepository.saveWakeWordEnabled(false)
        return
      }
      VoiceAssistantManager.updateStatus("Wake phrase listening started")
    } else {
      WakeWordManager.stop(context)
      VoiceAssistantManager.updateStatus("Wake phrase listening stopped")
    }
  }

  fun routeTranscript(
    transcript: String,
    modelManagerViewModel: ModelManagerViewModel,
  ) {
    if (transcript.isBlank()) return
    // Deliberately NOT viewModelScope: remote routes can outlive the screen, and a
    // cleared ViewModel would cancel them silently mid-flight.
    VoiceAssistantManager.routeScope.launch {
      try {
        Log.i(TAG, "routeTranscript: \"$transcript\"")
        VoiceAssistantManager.setProcessing(true)
        VoiceAssistantManager.updateStatus("Scoring complexity…")
        val settings = _uiState.value
        val orchestrator = LocalOrchestrator(context, modelManagerViewModel)
        val result =
          orchestrator.route(
            prompt = transcript,
            orchestratorMode = settings.orchestratorMode,
            orchestratorUrl = settings.orchestratorUrl,
            onStatus = { VoiceAssistantManager.updateStatus(it) },
          )
        VoiceAssistantManager.recordResult(transcript, result)
        if (
          settings.orchestratorMode == OrchestratorMode.ORCHESTRATOR_MODE_PHONE_FIRST &&
            result is RouteResult.Local &&
            result.tier == ComplexityTier.MEDIUM
        ) {
          orchestrator.swapBackToE2BIfNeeded { VoiceAssistantManager.updateStatus(it) }
        }
      } catch (e: Exception) {
        Log.e(TAG, "routeTranscript failed", e)
        VoiceAssistantManager.updateStatus(e.message ?: "Routing failed")
        VoiceAssistantManager.setProcessing(false)
      }
    }
  }
}
