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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.OrchestratorMode
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictate
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VoiceAssistantScreen(
  modelManagerViewModel: ModelManagerViewModel,
  voiceViewModel: VoiceAssistantViewModel = hiltViewModel(),
  holdToDictateViewModel: HoldToDictateViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val mainHandler = remember { Handler(Looper.getMainLooper()) }
  val scope = rememberCoroutineScope()
  val settings by voiceViewModel.uiState.collectAsState()
  val assistantState by VoiceAssistantManager.state.collectAsState()
  val modelUiState by modelManagerViewModel.uiState.collectAsState()
  var wakePhraseText by remember(settings.wakePhrase) { mutableStateOf(settings.wakePhrase) }
  var micPermissionGranted by remember { mutableStateOf(false) }
  var voskReady by remember { mutableStateOf(VoskTranscriber.isModelDownloaded(context)) }
  var voskDownloading by remember { mutableStateOf(false) }
  var voskDownloadProgress by remember { mutableStateOf<Float?>(null) }

  androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          voskReady = VoskTranscriber.isModelDownloaded(context)
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val micPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      micPermissionGranted = granted
      if (!granted) {
        VoiceAssistantManager.updateStatus("Microphone permission is required for voice input")
      }
    }

  LaunchedEffect(Unit) {
    micPermissionGranted =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!micPermissionGranted) {
      micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  // The wake service dies with the process (app update, crash, swipe-away) while the
  // enabled flag persists — revive it so the toggle state and reality match.
  LaunchedEffect(settings.wakeWordEnabled, settings.enabled) {
    if (
      settings.wakeWordEnabled &&
        settings.enabled &&
        !assistantState.wakeWordListening &&
        VoskTranscriber.isModelDownloaded(context)
    ) {
      WakeWordManager.start(context, modelManagerViewModel, voiceViewModel)
    }
  }

  val loadedModelName =
    remember(modelUiState) {
      modelManagerViewModel.getAllModels().firstOrNull { it.instance != null }?.name ?: "None"
    }
  val chatTask = remember(modelUiState) { modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT) }
  val gemmaModelsAvailable =
    remember(modelUiState) {
      val isDownloaded = { model: Model -> modelManagerViewModel.isModelDownloaded(model) }
      VoiceAssistantModels.findModel(
        modelManagerViewModel.getAllModels(),
        ComplexityTier.SIMPLE,
        isDownloaded,
      )?.let(isDownloaded) == true
    }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      "Voice Assistant",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
    )
    Text(
      "Tiered routing: Gemma E2B (simple) → Gemma E4B (medium) → Proxmox (complex). Weather uses live Open-Meteo.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (assistantState.statusMessage.isNotEmpty()) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
      ) {
        Text(
          assistantState.statusMessage,
          modifier = Modifier.padding(12.dp),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }

    if (!gemmaModelsAvailable) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
      ) {
        Text(
          "Download Gemma-4-E2B-it (or Gemma-4-E4B-it) from the Models tab before using Voice Assistant.",
          modifier = Modifier.padding(12.dp),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
    }

    if (!micPermissionGranted) {
      OutlinedButton(
        onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Grant microphone permission")
      }
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Enable Voice Assistant", fontWeight = FontWeight.Medium)
            Text(
              "Pre-loads Gemma E2B for instant simple responses",
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Switch(
            checked = settings.enabled,
            onCheckedChange = { voiceViewModel.setEnabled(it, modelManagerViewModel) },
          )
        }
        Text("Loaded model: $loadedModelName", style = MaterialTheme.typography.bodySmall)
        if (assistantState.wakeWordListening) {
          Text(
            "Wake phrase listening",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        if (assistantState.isProcessing) {
          Text("Processing…", style = MaterialTheme.typography.bodySmall)
        }
      }
    }

    Text("Orchestrator mode", fontWeight = FontWeight.Medium)
    OrchestratorModeOption(
      label = "Phone-first (recommended)",
      description = "Simple/medium on-device; complex → Proxmox",
      selected = settings.orchestratorMode == OrchestratorMode.ORCHESTRATOR_MODE_PHONE_FIRST,
      onSelect = {
        voiceViewModel.setOrchestratorMode(OrchestratorMode.ORCHESTRATOR_MODE_PHONE_FIRST)
        VoiceAssistantManager.updateStatus("Mode: Phone-first")
      },
    )
    OrchestratorModeOption(
      label = "Phone-only",
      description = "All queries stay on-device (complex uses E4B)",
      selected = settings.orchestratorMode == OrchestratorMode.ORCHESTRATOR_MODE_PHONE_ONLY,
      onSelect = {
        voiceViewModel.setOrchestratorMode(OrchestratorMode.ORCHESTRATOR_MODE_PHONE_ONLY)
        VoiceAssistantManager.updateStatus("Mode: Phone-only")
      },
    )
    OrchestratorModeOption(
      label = "Remote-only",
      description = "All queries go to Proxmox orchestrator",
      selected = settings.orchestratorMode == OrchestratorMode.ORCHESTRATOR_MODE_REMOTE_ONLY,
      onSelect = {
        voiceViewModel.setOrchestratorMode(OrchestratorMode.ORCHESTRATOR_MODE_REMOTE_ONLY)
        VoiceAssistantManager.updateStatus("Mode: Remote-only")
      },
    )

    OutlinedTextField(
      value = settings.orchestratorUrl,
      onValueChange = { voiceViewModel.setOrchestratorUrl(it) },
      label = { Text("Proxmox orchestrator URL") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Wake phrase (offline)", fontWeight = FontWeight.Medium)
        Text(
          "Vosk grammar mode — no API keys. Say the wake phrase, pause, then speak your command. " +
            "Also listens for \"hey phone llama\" and \"phone llama\".",
          style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
          value = wakePhraseText,
          onValueChange = { wakePhraseText = it },
          label = { Text("Primary wake phrase") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
        Button(
          onClick = {
            voiceViewModel.saveWakePhrase(wakePhraseText)
            VoiceAssistantManager.updateStatus("Wake phrase saved: $wakePhraseText")
            if (settings.wakeWordEnabled) {
              WakeWordManager.stop(context)
              voiceViewModel.setWakeWordEnabled(true, modelManagerViewModel)
            }
          },
          enabled = !voskDownloading,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Save wake phrase")
        }
        if (!voskReady) {
          if (voskDownloading) {
            LinearProgressIndicator(
              progress = { voskDownloadProgress ?: 0f },
              modifier = Modifier.fillMaxWidth(),
            )
            Text(
              assistantState.statusMessage.ifBlank { "Downloading Vosk STT model…" },
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Button(
            onClick = {
              scope.launch {
                voskDownloading = true
                voskDownloadProgress = 0f
                VoiceAssistantManager.updateStatus("Downloading Vosk STT… 0%")
                val result =
                  withContext(Dispatchers.IO) {
                    VoskTranscriber.downloadModel(context) { msg, fraction ->
                      VoiceAssistantManager.updateStatus(msg)
                      mainHandler.post { voskDownloadProgress = fraction }
                    }
                  }
                voskReady = result.isSuccess
                voskDownloading = false
                voskDownloadProgress = if (result.isSuccess) 1f else null
                if (result.isFailure) {
                  VoiceAssistantManager.updateStatus(
                    result.exceptionOrNull()?.message
                      ?: "Vosk download failed — check internet and retry"
                  )
                }
              }
            },
            enabled = !voskDownloading,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              if (voskDownloading) "Downloading Vosk STT…"
              else "Download Vosk STT model (~40 MB, needs internet once)"
            )
          }
        } else {
          Text("Vosk model ready", style = MaterialTheme.typography.bodySmall)
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Enable wake phrase listening")
          Switch(
            checked = settings.wakeWordEnabled,
            enabled = settings.enabled && voskReady && micPermissionGranted && !voskDownloading,
            onCheckedChange = {
              voiceViewModel.setWakeWordEnabled(it, modelManagerViewModel)
            },
          )
        }
        if (settings.enabled && !voskReady) {
          Text(
            "Download the Vosk model before enabling wake phrase listening.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    }

    Text("Push-to-talk", fontWeight = FontWeight.Medium)
    if (!settings.enabled) {
      Text(
        "Turn on Voice Assistant above to use push-to-talk.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else if (chatTask == null) {
      Text(
        "Model list still loading — go back and wait for the home screen to finish loading.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    } else if (!micPermissionGranted) {
      Text(
        "Grant microphone permission to use push-to-talk.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    } else {
      HoldToDictate(
        task = chatTask,
        viewModel = holdToDictateViewModel,
        onDone = { transcript ->
          if (transcript.isBlank()) {
            VoiceAssistantManager.updateStatus("No speech heard — try again")
          } else {
            voiceViewModel.routeTranscript(transcript, modelManagerViewModel)
          }
        },
        onAmplitudeChanged = {},
        enabled = settings.enabled && !assistantState.isProcessing,
        modifier = Modifier.fillMaxWidth().height(72.dp),
      )
      Text(
        "Hold the button, speak, then release. Uses Android speech recognition (needs network).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (assistantState.lastTranscript.isNotEmpty() || assistantState.lastResponse.isNotEmpty()) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
      ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Last interaction", fontWeight = FontWeight.Medium)
          if (assistantState.lastTierLabel.isNotEmpty()) {
            Text(
              "Tier: ${assistantState.lastTierLabel} · ${assistantState.lastModelOrTarget} · ${assistantState.lastLatencyMs}ms",
              style = MaterialTheme.typography.bodySmall,
            )
          }
          if (assistantState.lastTranscript.isNotEmpty()) {
            Text("You: ${assistantState.lastTranscript}", style = MaterialTheme.typography.bodyMedium)
          }
          if (assistantState.lastResponse.isNotEmpty()) {
            Text(
              "Assistant: ${assistantState.lastResponse}",
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }
    }

    RambleSection(
      enabled = settings.enabled && micPermissionGranted && voskReady,
      orchestratorUrl = settings.orchestratorUrl,
      modelManagerViewModel = modelManagerViewModel,
    )

    Spacer(Modifier.height(32.dp))
  }
}

@Composable
private fun RambleSection(
  enabled: Boolean,
  orchestratorUrl: String,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val context = LocalContext.current
  val rambleState by RambleManager.state.collectAsState()
  val view = androidx.compose.ui.platform.LocalView.current

  // A 10-20 minute ramble (and its multi-minute analysis) must not die to the
  // lock screen.
  val keepAwake = rambleState.recording || rambleState.processing
  androidx.compose.runtime.DisposableEffect(keepAwake) {
    view.keepScreenOn = keepAwake
    onDispose { view.keepScreenOn = false }
  }

  LaunchedEffect(Unit) { RambleHistoryStore.refresh(context) }

  Text("Ramble mode", fontWeight = FontWeight.Medium)
  Text(
    "Think out loud for as long as you like (offline transcription, no time limit). " +
      "Live notes appear as you talk (on-device Gemma); the full analysis runs on the " +
      "Proxmox box when you stop.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )

  var zipformerReady by remember { mutableStateOf(SherpaTranscriber.isModelDownloaded(context)) }
  var zipformerDownloading by remember { mutableStateOf(false) }
  var zipformerProgress by remember { mutableStateOf<Float?>(null) }
  var zipformerStatus by remember { mutableStateOf("") }
  if (!zipformerReady) {
    OutlinedButton(
      onClick = {
        zipformerDownloading = true
        VoiceAssistantManager.routeScope.launch {
          SherpaTranscriber.downloadModel(context.applicationContext, orchestratorUrl) { msg, frac ->
              zipformerStatus = msg
              zipformerProgress = frac
            }
            .fold(
              onSuccess = {
                zipformerReady = true
                zipformerDownloading = false
              },
              onFailure = {
                zipformerStatus = "Download failed: ${it.message}"
                zipformerDownloading = false
              },
            )
        }
      },
      enabled = !zipformerDownloading && !rambleState.recording && !rambleState.processing,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(if (zipformerDownloading) "Downloading…" else "Download high-accuracy STT (54 MB)")
    }
    if (zipformerDownloading) {
      LinearProgressIndicator(
        progress = { zipformerProgress ?: 0f },
        modifier = Modifier.fillMaxWidth(),
      )
    }
    if (zipformerStatus.isNotEmpty()) {
      Text(
        zipformerStatus,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  } else {
    Text(
      "STT engine: Zipformer (high accuracy) ✓",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.primary,
    )
  }

  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    RambleModeChip("Challenge me", "challenge", rambleState)
    RambleModeChip("Solve it", "solve", rambleState)
    RambleModeChip("Summarize", "summarize", rambleState)
  }

  Row(verticalAlignment = Alignment.CenterVertically) {
    Switch(
      checked = rambleState.liveNotesEnabled,
      onCheckedChange = { RambleManager.setLiveNotesEnabled(it) },
      enabled = !rambleState.recording && !rambleState.processing,
    )
    Text(
      "Live on-device notes while talking",
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.padding(start = 8.dp),
    )
  }

  val keepEnabled = !rambleState.recording && !rambleState.processing
  Text(
    "Keep after each session (app-private storage):",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Row(verticalAlignment = Alignment.CenterVertically) {
    KeepCheckbox("Audio", rambleState.keepAudio, keepEnabled) { RambleManager.setKeepAudio(it) }
    KeepCheckbox("Transcript", rambleState.keepTranscript, keepEnabled) {
      RambleManager.setKeepTranscript(it)
    }
    KeepCheckbox("Answer", rambleState.keepAnswer, keepEnabled) { RambleManager.setKeepAnswer(it) }
  }

  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(
      onClick = {
        if (rambleState.recording) RambleManager.stop()
        else
          RambleManager.start(
            context,
            orchestratorUrl,
            LocalOrchestrator(context, modelManagerViewModel),
          )
      },
      enabled = enabled && !rambleState.processing,
      modifier = Modifier.weight(1f).height(56.dp),
      colors =
        ButtonDefaults.buttonColors(
          containerColor =
            if (rambleState.recording) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        ),
    ) {
      Text(
        when {
          rambleState.recording -> "Stop & analyze"
          rambleState.processing -> "Analyzing…"
          else -> "Start rambling"
        }
      )
    }
    if (rambleState.recording) {
      OutlinedButton(
        onClick = { if (rambleState.paused) RambleManager.resume() else RambleManager.pause() },
        modifier = Modifier.height(56.dp),
      ) {
        Text(if (rambleState.paused) "Resume" else "Pause")
      }
    }
    if (
      rambleState.recording ||
        rambleState.processing ||
        rambleState.transcript.isNotEmpty() ||
        rambleState.result.isNotEmpty()
    ) {
      OutlinedButton(
        onClick = { RambleManager.abort(context, orchestratorUrl) },
        modifier = Modifier.height(56.dp),
      ) {
        Text(if (rambleState.recording || rambleState.processing) "Abort" else "Reset")
      }
    }
  }

  if (rambleState.processing) {
    LinearProgressIndicator(
      progress = { rambleState.processingProgress },
      modifier = Modifier.fillMaxWidth(),
    )
  }

  if (rambleState.status.isNotEmpty()) {
    Text(
      rambleState.status,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  if (rambleState.liveNotes.isNotEmpty()) {
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors =
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Live notes", fontWeight = FontWeight.Medium)
        rambleState.liveNotes.takeLast(6).forEach { note ->
          Text("• $note", style = MaterialTheme.typography.bodySmall)
        }
      }
    }
  }

  if (rambleState.transcript.isNotEmpty() && (rambleState.recording || rambleState.processing)) {
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(12.dp)) {
        Text(
          "Heard so far (${rambleState.transcript.split(" ").size} words):",
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Medium,
        )
        Text(
          rambleState.transcript.takeLast(400),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }

  if (rambleState.result.isNotEmpty()) {
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Analysis (${rambleState.mode})", fontWeight = FontWeight.Medium)
        Text(rambleState.result, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }

  RambleHistoryTable()
}

@Composable
private fun KeepCheckbox(
  label: String,
  checked: Boolean,
  enabled: Boolean,
  onChange: (Boolean) -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
    Text(label, style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun RambleHistoryTable() {
  val context = LocalContext.current
  val entries by RambleHistoryStore.entries.collectAsState()
  var viewing by remember { mutableStateOf<RambleEntry?>(null) }
  val dateFormat = remember { java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US) }

  if (entries.isEmpty()) return

  Text("Saved sessions", fontWeight = FontWeight.Medium)
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("When", Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall)
        Text("Mode", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        Text("Words", Modifier.weight(0.7f), style = MaterialTheme.typography.labelSmall)
        Text("Kept", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1.1f))
      }
      HorizontalDivider(Modifier.padding(vertical = 4.dp))
      entries.forEach { entry ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
            dateFormat.format(java.util.Date(entry.timestampMs)),
            Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodySmall,
          )
          Text(entry.mode, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
          Text("${entry.words}", Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall)
          Text(
            listOfNotNull(
                "A".takeIf { entry.hasAudio },
                "T".takeIf { entry.transcript != null },
                "R".takeIf { entry.answer != null },
              )
              .joinToString("·"),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
          )
          Row(Modifier.weight(1.1f), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { viewing = entry }) { Text("View") }
            TextButton(onClick = { RambleHistoryStore.delete(context, entry.id) }) {
              Text("Del", color = MaterialTheme.colorScheme.error)
            }
          }
        }
      }
      Text(
        "A=audio, T=transcript, R=answer",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

  viewing?.let { entry ->
    RambleEntryDialog(entry = entry, onDismiss = { viewing = null })
  }
}

@Composable
private fun RambleEntryDialog(entry: RambleEntry, onDismiss: () -> Unit) {
  val context = LocalContext.current
  var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
  var playing by remember { mutableStateOf(false) }
  val dateFormat = remember { java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US) }

  androidx.compose.runtime.DisposableEffect(Unit) {
    onDispose {
      player?.release()
      player = null
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    title = {
      Text("${entry.mode} · ${dateFormat.format(java.util.Date(entry.timestampMs))}")
    },
    text = {
      Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (entry.hasAudio) {
          OutlinedButton(
            onClick = {
              if (playing) {
                player?.release()
                player = null
                playing = false
              } else {
                val file = RambleHistoryStore.audioFile(context, entry.id) ?: return@OutlinedButton
                val mp = android.media.MediaPlayer()
                try {
                  mp.setDataSource(file.absolutePath)
                  mp.prepare()
                  mp.setOnCompletionListener {
                    playing = false
                    it.release()
                    player = null
                  }
                  mp.start()
                  player = mp
                  playing = true
                } catch (e: Exception) {
                  android.util.Log.e("RambleHistory", "Audio playback failed", e)
                  mp.release()
                  player = null
                  playing = false
                }
              }
            }
          ) {
            Text(
              if (playing) "Stop audio" else "Play audio (${entry.audioSeconds / 60}m ${entry.audioSeconds % 60}s)"
            )
          }
        }
        if (entry.transcript != null) {
          Text("Transcript (${entry.words} words)", fontWeight = FontWeight.Medium)
          Text(entry.transcript, style = MaterialTheme.typography.bodySmall)
        }
        if (entry.answer != null) {
          Text("Analysis", fontWeight = FontWeight.Medium)
          Text(entry.answer, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
  )
}

@Composable
private fun RambleModeChip(label: String, mode: String, rambleState: RambleState) {
  FilterChip(
    selected = rambleState.mode == mode,
    onClick = { RambleManager.setMode(mode) },
    label = { Text(label) },
    enabled = !rambleState.recording && !rambleState.processing,
  )
}

@Composable
private fun OrchestratorModeOption(
  label: String,
  description: String,
  selected: Boolean,
  onSelect: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onSelect)
    Column(Modifier.padding(start = 4.dp)) {
      Text(label, fontWeight = FontWeight.Medium)
      Text(description, style = MaterialTheme.typography.bodySmall)
    }
  }
}
