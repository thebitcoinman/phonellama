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

import com.google.ai.edge.gallery.data.BuiltInTaskId

import com.google.ai.edge.gallery.data.Model

import com.google.ai.edge.gallery.data.ModelDownloadStatusType

import com.google.ai.edge.gallery.edgeserver.EdgeServerManager

import com.google.ai.edge.gallery.proto.OrchestratorMode

import com.google.ai.edge.gallery.runtime.runtimeHelper

import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType

import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

import com.google.ai.edge.litertlm.Contents

import kotlin.coroutines.resume

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.coroutineScope

import kotlinx.coroutines.delay

import kotlinx.coroutines.launch

import kotlinx.coroutines.suspendCancellableCoroutine

import kotlinx.coroutines.withContext



private const val TAG = "LocalOrchestrator"

private const val INIT_TIMEOUT_MS = 180_000L

private const val INIT_POLL_MS = 300L

// Trigger words for OpenClaw routing. The Vosk offline model frequently mishears

// "claw" (call, clause, claude, …), so match verb + fuzzy claw-word and rewrite to

// the canonical "use claw" prefix that OPENCLAW_TRIGGERS on the orchestrator strips.

private val CLAW_VERBS =

  setOf("use", "used", "uses", "ask", "asked", "asking", "hey", "open")

private val CLAW_WORDS =

  setOf("claw", "claws", "clause", "claude", "clod", "clow", "klaw", "call", "cloud")



internal fun rewriteClawPrompt(prompt: String): String? {

  // Wake-phrase transcripts often include the wake phrase itself and Vosk verb

  // mishears ("hey llama used claw …"), so scan the first few tokens for a

  // verb + claw-word pair instead of exact prefix matching.

  val tokens = prompt.trim().lowercase().split(Regex("[\\s,.!?']+")).filter { it.isNotEmpty() }

  for (i in 0 until minOf(tokens.size - 1, 5)) {

    if (tokens[i] in CLAW_VERBS && tokens[i + 1] in CLAW_WORDS) {

      val rest = tokens.drop(i + 2).joinToString(" ")

      return "use claw ${rest.ifEmpty { "introduce yourself" }}"

    }

  }

  return null

}



class LocalOrchestrator(

  private val context: Context,

  private val modelManagerViewModel: ModelManagerViewModel,

) {



  suspend fun route(

    prompt: String,

    orchestratorMode: OrchestratorMode,

    orchestratorUrl: String,

    onStatus: (String) -> Unit = {},

  ): RouteResult =

    withContext(Dispatchers.Default) {

      val startMs = System.currentTimeMillis()



      // Claw wake word beats every other route — the user explicitly asked for the

      // OpenClaw agent, which only exists on the orchestrator. Without this check the

      // complexity scorer sends short claw prompts to on-phone Gemma, which knows

      // nothing about claw.

      val clawPrompt = rewriteClawPrompt(prompt)

      if (clawPrompt != null) {

        Log.i(TAG, "Claw trigger matched; rewrote \"$prompt\" -> \"$clawPrompt\"")

        if (orchestratorMode == OrchestratorMode.ORCHESTRATOR_MODE_PHONE_ONLY) {

          return@withContext RouteResult.Error(

            "OpenClaw runs on the orchestrator. Switch orchestrator mode to Phone-first or Remote-only."

          )

        }

        return@withContext routeRemoteWithProgress(

          prompt = clawPrompt,

          tier = ComplexityTier.COMPLEX,

          orchestratorUrl = orchestratorUrl,

          startMs = startMs,

          onStatus = onStatus,

          label = "OpenClaw agent working (can take a few minutes)",

        )

      }



      when (RealtimeQueryRouter.classify(prompt)) {

        RealtimeIntent.WEATHER -> {

          onStatus("Fetching live weather…")

          return@withContext routeWeather(prompt, startMs, onStatus)

        }

        RealtimeIntent.OTHER -> {

          if (orchestratorMode == OrchestratorMode.ORCHESTRATOR_MODE_PHONE_ONLY) {

            return@withContext RouteResult.Error(

              "That needs live data (news, stocks, scores). Switch orchestrator mode to Phone-first " +

                "and deploy the Proxmox orchestrator, or ask a general knowledge question."

            )

          }

          val remote =

            routeRemoteWithProgress(

              prompt = prompt,

              tier = ComplexityTier.COMPLEX,

              orchestratorUrl = orchestratorUrl,

              startMs = startMs,

              onStatus = onStatus,

              label = "Live data query — waiting on Proxmox",

            )

          if (remote is RouteResult.Error &&

            orchestratorMode == OrchestratorMode.ORCHESTRATOR_MODE_PHONE_FIRST

          ) {

            return@withContext RouteResult.Error(

              remote.message +

                " Deploy the orchestrator with search tools for live news and scores."

            )

          }

          return@withContext remote

        }

        null -> {}

      }



      val tier = ComplexityScorer.scoreComplexity(prompt)



      when (orchestratorMode) {

        OrchestratorMode.ORCHESTRATOR_MODE_REMOTE_ONLY -> {

          return@withContext routeRemoteWithProgress(

            prompt = prompt,

            tier = tier,

            orchestratorUrl = orchestratorUrl,

            startMs = startMs,

            onStatus = onStatus,

            label = "Waiting on Proxmox",

          )

        }

        OrchestratorMode.ORCHESTRATOR_MODE_PHONE_ONLY -> {

          val localTier = if (tier == ComplexityTier.COMPLEX) ComplexityTier.MEDIUM else tier

          return@withContext routeLocal(prompt, localTier, onStatus, startMs)

        }

        else -> {

          if (tier == ComplexityTier.COMPLEX) {

            val remote =

              routeRemoteWithProgress(

                prompt = prompt,

                tier = tier,

                orchestratorUrl = orchestratorUrl,

                startMs = startMs,

                onStatus = onStatus,

                label = "Complex query — waiting on Proxmox",

              )

            if (remote is RouteResult.Error &&

              orchestratorMode == OrchestratorMode.ORCHESTRATOR_MODE_PHONE_FIRST

            ) {

              onStatus("Proxmox unavailable — falling back to E4B…")

              return@withContext routeLocal(prompt, ComplexityTier.MEDIUM, onStatus, startMs)

            }

            return@withContext remote

          }

          return@withContext routeLocal(prompt, tier, onStatus, startMs)

        }

      }

    }



  private suspend fun routeWeather(

    prompt: String,

    startMs: Long,

    onStatus: (String) -> Unit,

  ): RouteResult {

    val result = WeatherClient.answerWeatherQuery(prompt)

    return result.fold(

      onSuccess = { text ->

        RouteResult.Local(

          response = text,

          tier = ComplexityTier.SIMPLE,

          modelName = "Open-Meteo (live)",

          latencyMs = System.currentTimeMillis() - startMs,

        )

      },

      onFailure = { err ->

        onStatus(err.message ?: "Weather lookup failed")

        RouteResult.Error(err.message ?: "Weather lookup failed")

      },

    )

  }



  private suspend fun routeLocal(

    prompt: String,

    tier: ComplexityTier,

    onStatus: (String) -> Unit,

    startMs: Long,

  ): RouteResult {

    val model =

      resolveModel(tier)

        ?: return RouteResult.Error(

          "Model ${VoiceAssistantModels.modelNameForTier(tier)} not found. Download it from the Models tab."

        )



    if (!isDownloaded(model)) {

      return RouteResult.Error(

        "Model '${model.name}' is not downloaded yet. Download ${VoiceAssistantModels.modelNameForTier(tier)} from Models."

      )

    }



    onStatus("Loading ${model.name}…")

    val ensureResult = ensureModel(model, onStatus)

    if (ensureResult.isFailure) {

      return RouteResult.Error(ensureResult.exceptionOrNull()?.message ?: "Model load failed")

    }



    onStatus("Running on ${model.name}…")

    val inferResult = inferLocally(model, prompt)

    return inferResult.fold(

      onSuccess = { text ->

        RouteResult.Local(

          response = text,

          tier = tier,

          modelName = model.name,

          latencyMs = System.currentTimeMillis() - startMs,

        )

      },

      onFailure = { RouteResult.Error(it.message ?: "Inference failed") },

    )

  }



  /**

   * routeRemote with a periodic elapsed-time status update so long waits

   * (OpenClaw agent turns, cold CPU inference) never look hung.

   */

  private suspend fun routeRemoteWithProgress(

    prompt: String,

    tier: ComplexityTier,

    orchestratorUrl: String,

    startMs: Long,

    onStatus: (String) -> Unit,

    label: String,

  ): RouteResult = coroutineScope {

    onStatus("$label…")

    val ticker = launch {

      while (true) {

        delay(15_000L)

        val elapsedSec = (System.currentTimeMillis() - startMs) / 1000

        onStatus("$label… ${elapsedSec}s")

      }

    }

    try {

      routeRemote(prompt, tier, orchestratorUrl, startMs, onStatus)

    } finally {

      ticker.cancel()

    }

  }



  private suspend fun routeRemote(

    prompt: String,

    tier: ComplexityTier,

    orchestratorUrl: String,

    startMs: Long,

    onStatus: (String) -> Unit = {},

  ): RouteResult {

    val result = OrchestratorClient.routePrompt(orchestratorUrl, prompt)

    return result.fold(

      onSuccess = { remote ->

        RouteResult.Remote(

          response = remote.response,

          tier = tier,

          targetLabel = remote.target,

          latencyMs = System.currentTimeMillis() - startMs,

        )

      },

      onFailure = {

        onStatus(it.message ?: "Remote routing failed")

        RouteResult.Error(it.message ?: "Remote routing failed")

      },

    )

  }



  suspend fun preloadE2B(onStatus: (String) -> Unit = {}): Result<Unit> =

    withContext(Dispatchers.Default) {

      val model =

        resolveModel(ComplexityTier.SIMPLE)

          ?: return@withContext Result.failure(

            Exception(

              "No Gemma model in catalog. Download ${VoiceAssistantModels.E2B_PRIMARY} from Models."

            )

          )



      if (!isDownloaded(model)) {

        return@withContext Result.failure(

          Exception(

            "Download ${VoiceAssistantModels.E2B_PRIMARY} (or ${VoiceAssistantModels.E4B_PRIMARY}) from the Models tab."

          )

        )

      }



      val loaded = currentlyLoadedModel()

      if (loaded != null && loaded.name.equals(model.name, ignoreCase = true) && loaded.instance != null) {

        onStatus("Ready: ${model.name}")

        return@withContext Result.success(Unit)

      }



      ensureModel(model, onStatus).map {

        onStatus("Ready: ${it.name}")

      }

    }



  private fun currentlyLoadedModel(): Model? =

    modelManagerViewModel.getAllModels().firstOrNull { it.instance != null }



  private fun isDownloaded(model: Model): Boolean =

    modelManagerViewModel.isModelDownloaded(model) ||

      modelManagerViewModel.uiState.value.modelDownloadStatus[model.name]?.status ==

        ModelDownloadStatusType.SUCCEEDED



  private fun resolveModel(tier: ComplexityTier): Model? =

    VoiceAssistantModels.findModel(

      allModels = modelManagerViewModel.getAllModels(),

      tier = tier,

      isDownloaded = ::isDownloaded,

      currentlyLoaded = currentlyLoadedModel(),

    )



  private suspend fun ensureModel(model: Model, onStatus: (String) -> Unit): Result<Model> {

    if (model.instance != null) {

      return Result.success(model)

    }



    if (!isDownloaded(model)) {

      return Result.failure(Exception("Model '${model.name}' is not downloaded."))

    }



    val task =

      modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT)

        ?: return Result.failure(Exception("LLM Chat task not available"))



    onStatus("Initializing ${model.name}…")

    EdgeServerManager.waitForIdle(10_000L)



    val alreadyInitializing =

      model.initializing ||

        modelManagerViewModel.uiState.value.modelInitializationStatus[model.name]?.status ==

          ModelInitializationStatusType.INITIALIZING



    if (!alreadyInitializing) {

      modelManagerViewModel.initializeModel(

        context = context,

        task = task,

        model = model,

        onDone = {},

      )

    }



    return waitForModelInit(model)

  }



  private suspend fun waitForModelInit(model: Model): Result<Model> {

    val deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS

    var sawInitializing = model.initializing



    while (System.currentTimeMillis() < deadline) {

      if (model.instance != null) {

        return Result.success(model)

      }



      val initStatus = modelManagerViewModel.uiState.value.modelInitializationStatus[model.name]

      if (initStatus?.status == ModelInitializationStatusType.INITIALIZING) {

        sawInitializing = true

      }



      if (initStatus?.status == ModelInitializationStatusType.ERROR) {

        val err = initStatus.error.ifBlank { "Initialization failed" }

        Log.e(TAG, "Init error for ${model.name}: $err")

        return Result.failure(Exception(err))

      }



      if (

        sawInitializing &&

          !model.initializing &&

          initStatus?.status != ModelInitializationStatusType.INITIALIZING &&

          model.instance == null

      ) {

        val err =

          initStatus?.error?.takeIf { it.isNotBlank() }

            ?: "Initialization failed for ${model.name}"

        return Result.failure(Exception(err))

      }



      delay(INIT_POLL_MS)

    }



    return Result.failure(Exception("Timed out initializing ${model.name}"))

  }



  private suspend fun inferLocally(model: Model, prompt: String): Result<String> =

    suspendCancellableCoroutine { cont ->

      val helper = model.runtimeHelper

      helper.resetConversation(

        model = model,

        supportImage = false,

        supportAudio = false,

        systemInstruction =

          Contents.of(

            "You are a helpful voice assistant. Be concise. " +

              "You do not have live internet data — if the user asks for current weather, news, " +

              "or scores, say the app will route those queries automatically."

          ),

      )



      val sb = StringBuilder()

      helper.runInference(

        model = model,

        input = prompt,

        resultListener = { partial: String, done: Boolean, _: String? ->

          if (partial.isNotEmpty() && !partial.startsWith("<ctrl")) {

            sb.append(partial)

          }

          if (done && cont.isActive) {

            cont.resume(Result.success(sb.toString().trim()))

          }

        },

        cleanUpListener = {},

        onError = { msg: String ->

          if (cont.isActive) {

            cont.resume(Result.failure(Exception(msg)))

          }

        },

      )

    }



  suspend fun swapBackToE2BIfNeeded(onStatus: (String) -> Unit = {}) {

    val loaded = currentlyLoadedModel() ?: return

    val e2b = resolveModel(ComplexityTier.SIMPLE) ?: return

    if (loaded.name.equals(e2b.name, ignoreCase = true)) return

    if (!isDownloaded(e2b)) return

    ensureModel(e2b, onStatus)

  }

}


