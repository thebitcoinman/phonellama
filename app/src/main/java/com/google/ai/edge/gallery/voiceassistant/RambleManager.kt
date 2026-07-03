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
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.vosk.Model
import org.vosk.Recognizer

private const val TAG = "RambleManager"
private const val SAMPLE_RATE = 16000

data class RambleState(
  val recording: Boolean = false,
  val paused: Boolean = false,
  val processing: Boolean = false,
  val mode: String = "challenge",
  val transcript: String = "",
  val segmentsSent: Int = 0,
  val status: String = "",
  val result: String = "",
  val liveNotesEnabled: Boolean = true,
  val liveNotes: List<String> = emptyList(),
  /** 0..1 estimated fraction of the final analysis completed; capped at 0.95 until done. */
  val processingProgress: Float = 0f,
  val keepAudio: Boolean = false,
  val keepTranscript: Boolean = true,
  val keepAnswer: Boolean = true,
  /** Which STT engine the active/last session used ("Zipformer" or "Vosk"). */
  val sttEngine: String = "",
)

/** Streaming STT engine abstraction: Vosk (small, basic) or Zipformer (large, accurate). */
private interface SttEngine : AutoCloseable {
  /** Feed PCM16LE audio; returns a finalized segment's text, or null. */
  fun accept(buffer: ByteArray, len: Int): String?

  /** Flush and return trailing text. */
  fun finish(): String
}

private class VoskEngine(modelDir: String) : SttEngine {
  private val model = Model(modelDir)
  private val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

  override fun accept(buffer: ByteArray, len: Int): String? {
    if (recognizer.acceptWaveForm(buffer, len)) {
      return extract(recognizer.result).ifBlank { null }
    }
    return null
  }

  override fun finish(): String = extract(recognizer.finalResult)

  override fun close() {
    recognizer.close()
    model.close()
  }

  private fun extract(json: String): String =
    Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)?.trim().orEmpty()
}

private class ZipformerEngine(context: android.content.Context) : SttEngine {
  private val recognizer = SherpaTranscriber.createRecognizer(context)
  private val stream = SherpaStream(recognizer)

  override fun accept(buffer: ByteArray, len: Int): String? = stream.accept(buffer, len)

  override fun finish(): String = stream.finish()

  override fun close() {
    stream.close()
    recognizer.release()
  }
}

// On-device live-note prompts: short observations on the newest fragment while
// the user is still talking. "PASS" means nothing noteworthy in this fragment.
private val LIVE_PROMPTS =
  mapOf(
    "challenge" to
      "You hear a fragment of someone thinking out loud (imperfect speech-to-text). " +
        "List EVERY logical fallacy, unstated assumption, or contradiction in it — " +
        "each on its own line, one short sentence, starting with the type " +
        "(e.g. \"Assumption: ...\"). Up to 3 lines. If nothing is notable, reply exactly PASS.",
    "solve" to
      "You hear a fragment of someone describing a problem out loud (imperfect " +
        "speech-to-text). List each concrete solution direction or key constraint it " +
        "suggests — one short sentence per line, up to 3 lines. If none, reply exactly PASS.",
    "summarize" to
      "You hear a fragment of a spoken monologue (imperfect speech-to-text). " +
        "List its key points — one short sentence per line, up to 3 lines. " +
        "If it adds nothing new, reply exactly PASS.",
  )

// Words to accumulate before an on-device live-note pass.
private const val LIVE_CHUNK_WORDS = 120

/**
 * One recording session. Each session owns its control flags so that a stale
 * thread or coroutine can never be revived by flag resets from a newer session.
 */
private class RambleSession(val id: String = UUID.randomUUID().toString()) {
  val stopRequested = AtomicBoolean(false)
  val abortRequested = AtomicBoolean(false)
  val paused = AtomicBoolean(false)
  val liveAnalysisBusy = AtomicBoolean(false)
  val pendingLiveWords = StringBuilder()
}

/**
 * Long-form think-aloud mode: records continuously (no 60s cap), transcribes
 * offline with Vosk segment by segment, streams each segment to the
 * orchestrator, optionally produces on-device live notes, and on stop asks the
 * orchestrator to analyze the whole transcript against a mode-specific prompt.
 */
object RambleManager {

  val modes = listOf("challenge", "solve", "summarize")

  private val _state = MutableStateFlow(RambleState())
  val state: StateFlow<RambleState> = _state.asStateFlow()

  @Volatile private var currentSession: RambleSession? = null
  private var recordThread: Thread? = null
  private var localOrchestrator: LocalOrchestrator? = null

  /**
   * Apply a state change owned by [session]; silently dropped if that session
   * is no longer current (aborted or superseded). Pass null for global
   * settings changes that are not session-scoped.
   */
  private inline fun updateState(
    session: RambleSession?,
    crossinline transform: (RambleState) -> RambleState,
  ) {
    _state.update { s ->
      if (session != null && session !== currentSession) s else transform(s)
    }
  }

  fun setMode(mode: String) {
    if (mode in modes) updateState(null) { it.copy(mode = mode) }
  }

  fun setLiveNotesEnabled(enabled: Boolean) {
    updateState(null) { it.copy(liveNotesEnabled = enabled) }
  }

  fun setKeepAudio(keep: Boolean) {
    updateState(null) { it.copy(keepAudio = keep) }
  }

  fun setKeepTranscript(keep: Boolean) {
    updateState(null) { it.copy(keepTranscript = keep) }
  }

  fun setKeepAnswer(keep: Boolean) {
    updateState(null) { it.copy(keepAnswer = keep) }
  }

  fun start(context: Context, orchestratorUrl: String, orchestrator: LocalOrchestrator? = null) {
    if (_state.value.recording || _state.value.processing) return
    if (!VoskTranscriber.isModelDownloaded(context)) {
      updateState(null) { it.copy(status = "Download the Vosk model first") }
      return
    }
    // The previous thread always sees its own session's stop flag, so it exits
    // promptly — but never run two recorders. Bail rather than block the UI.
    recordThread?.let { prev ->
      if (prev.isAlive) {
        prev.join(1_000)
        if (prev.isAlive) {
          updateState(null) { it.copy(status = "Previous session still stopping — try again") }
          return
        }
      }
    }
    sweepStaleTempFiles(context)
    val session = RambleSession()
    currentSession = session
    localOrchestrator = orchestrator
    updateState(null) {
      it.copy(
        recording = true,
        paused = false,
        processing = false,
        transcript = "",
        segmentsSent = 0,
        result = "",
        liveNotes = emptyList(),
        processingProgress = 0f,
        status = "Listening — ramble away…",
      )
    }
    // Foreground service keeps the mic alive through screen-off/backgrounding
    // and shows live progress in the notification shade.
    RambleService.start(context.applicationContext)
    recordThread =
      thread(name = "RambleRecord") {
        try {
          runRecordingLoop(context, orchestratorUrl, session)
        } catch (e: Exception) {
          Log.e(TAG, "Ramble loop failed", e)
          tempPcmFile(context, session.id).delete()
          updateState(session) {
            it.copy(recording = false, processing = false, status = "Ramble failed: ${e.message}")
          }
        }
      }
  }

  fun stop() {
    currentSession?.stopRequested?.set(true)
  }

  /** Pause the mic without ending the session — resume to keep rambling. */
  fun pause() {
    val session = currentSession ?: return
    session.paused.set(true)
    updateState(session) { it.copy(paused = true, status = "Paused") }
  }

  fun resume() {
    val session = currentSession ?: return
    session.paused.set(false)
    updateState(session) { it.copy(paused = false, status = "Listening — ramble away…") }
  }

  /**
   * Discard the session: stop recording without analysis, drop the server-side
   * chunks, delete any temp audio, and reset the UI. In-flight work from the
   * discarded session (segment uploads, live notes, a pending analysis) is
   * silently ignored when it completes, because the session is no longer
   * current.
   */
  fun abort(context: Context, orchestratorUrl: String) {
    val session = currentSession
    currentSession = null
    if (session != null) {
      session.abortRequested.set(true)
      session.stopRequested.set(true)
      VoiceAssistantManager.routeScope.launch {
        OrchestratorClient.ramble(
          baseUrl = orchestratorUrl,
          sessionId = session.id,
          mode = _state.value.mode,
          chunk = "",
          final = false,
          abort = true,
        )
      }
      tempPcmFile(context, session.id).delete()
    }
    _state.update {
      it.copy(
        recording = false,
        paused = false,
        processing = false,
        transcript = "",
        segmentsSent = 0,
        result = "",
        liveNotes = emptyList(),
        processingProgress = 0f,
        status = if (session != null) "Discarded" else "",
      )
    }
  }

  private fun tempPcmFile(context: Context, id: String): File =
    File(context.cacheDir, "ramble_$id.pcm")

  private fun sweepStaleTempFiles(context: Context) {
    context.cacheDir
      .listFiles { f -> f.name.startsWith("ramble_") && f.name.endsWith(".pcm") }
      ?.forEach { it.delete() }
  }

  @SuppressLint("MissingPermission")
  private fun runRecordingLoop(
    context: Context,
    orchestratorUrl: String,
    session: RambleSession,
  ) {
    val useZipformer = SherpaTranscriber.isModelDownloaded(context)
    updateState(session) { it.copy(sttEngine = if (useZipformer) "Zipformer" else "Vosk") }
    val engine: SttEngine =
      if (useZipformer) ZipformerEngine(context)
      else VoskEngine(VoskTranscriber.getModelDir(context).absolutePath)
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
    val keepAudio = _state.value.keepAudio
    val pcmFile = tempPcmFile(context, session.id)
    var pcmOut: FileOutputStream? = if (keepAudio) FileOutputStream(pcmFile) else null

    try {
      recorder.startRecording()
      while (!session.stopRequested.get()) {
        val read = recorder.read(buffer, 0, buffer.size)
        // While paused the mic stays open but audio is discarded: nothing is
        // transcribed, uploaded, or written to the keep-audio file.
        if (session.paused.get()) continue
        if (read > 0) {
          try {
            pcmOut?.write(buffer, 0, read)
          } catch (e: Exception) {
            // Audio keeping failed (disk full?) — drop the tee, keep the session.
            Log.w(TAG, "Audio tee failed; continuing without audio", e)
            try {
              pcmOut?.close()
            } catch (_: Exception) {}
            pcmOut = null
            pcmFile.delete()
          }
          val segment = engine.accept(buffer, read)
          if (segment != null) {
            appendSegment(segment, orchestratorUrl, session)
          }
        }
        val elapsedSec = (System.currentTimeMillis() - startMs) / 1000
        if (elapsedSec > 0 && elapsedSec % 15 == 0L && !session.paused.get()) {
          updateState(session) {
            it.copy(status = "Listening… ${elapsedSec / 60}m ${elapsedSec % 60}s")
          }
        }
      }
      if (!session.abortRequested.get()) {
        val tail = engine.finish()
        if (tail.isNotBlank()) {
          appendSegment(tail, orchestratorUrl, session)
        }
      }
    } finally {
      try {
        recorder.stop()
      } catch (_: Exception) {}
      recorder.release()
      try {
        engine.close()
      } catch (e: Exception) {
        Log.w(TAG, "STT engine close failed", e)
      }
      try {
        pcmOut?.close()
      } catch (_: Exception) {}
    }

    if (session.abortRequested.get() || session !== currentSession) {
      pcmFile.delete()
      return
    }
    finishAndAnalyze(context, orchestratorUrl, session, pcmFile.takeIf { pcmOut != null })
  }

  private fun appendSegment(text: String, orchestratorUrl: String, session: RambleSession) {
    if (session !== currentSession) return
    updateState(session) {
      it.copy(
        transcript = (it.transcript + " " + text).trim(),
        segmentsSent = it.segmentsSent + 1,
      )
    }
    // Fire-and-forget upload; the final request re-sends nothing — the server
    // accumulates — so a dropped segment only loses that segment's words.
    VoiceAssistantManager.routeScope.launch {
      OrchestratorClient.ramble(
          baseUrl = orchestratorUrl,
          sessionId = session.id,
          mode = _state.value.mode,
          chunk = text,
          final = false,
        )
        .onFailure { Log.w(TAG, "Segment upload failed: ${it.message}") }
    }
    maybeRunLiveAnalysis(text, session)
  }

  /**
   * On-device incremental notes: accumulate transcript words and, whenever a
   * chunk's worth is ready and Gemma is free, analyze just that chunk. If the
   * model is still busy, words keep coalescing into the next pass — the feed
   * paces itself to the phone's inference speed.
   */
  private fun maybeRunLiveAnalysis(newText: String, session: RambleSession) {
    val orchestrator = localOrchestrator ?: return
    if (!_state.value.liveNotesEnabled) return
    if (session !== currentSession) return
    synchronized(session.pendingLiveWords) {
      if (session.pendingLiveWords.isNotEmpty()) session.pendingLiveWords.append(' ')
      session.pendingLiveWords.append(newText)
    }
    val pendingCount =
      synchronized(session.pendingLiveWords) { session.pendingLiveWords.split(" ").size }
    if (pendingCount < LIVE_CHUNK_WORDS) return
    if (!session.liveAnalysisBusy.compareAndSet(false, true)) return

    val chunk =
      synchronized(session.pendingLiveWords) {
        val snapshot = session.pendingLiveWords.toString()
        session.pendingLiveWords.setLength(0)
        snapshot
      }
    val system = LIVE_PROMPTS[_state.value.mode] ?: LIVE_PROMPTS.getValue("challenge")
    VoiceAssistantManager.routeScope.launch {
      try {
        orchestrator
          .analyzeChunk(prompt = chunk, systemInstruction = system)
          .onSuccess { raw ->
            val notes =
              raw
                .lines()
                .map { it.trim().trimStart('-', '•', '*', ' ') }
                .filter { it.isNotBlank() && !it.equals("PASS", ignoreCase = true) }
                .take(3)
                .map { it.take(300) }
            if (notes.isNotEmpty()) {
              updateState(session) { it.copy(liveNotes = it.liveNotes + notes) }
            }
          }
          .onFailure { Log.w(TAG, "Live note failed: ${it.message}") }
      } finally {
        session.liveAnalysisBusy.set(false)
      }
    }
  }

  private fun finishAndAnalyze(
    context: Context,
    orchestratorUrl: String,
    session: RambleSession,
    pcmFile: File?,
  ) {
    val transcript = _state.value.transcript
    if (transcript.isBlank()) {
      pcmFile?.delete()
      updateState(session) {
        it.copy(recording = false, paused = false, processing = false, status = "No speech captured")
      }
      return
    }
    val wordCount = transcript.split(" ").size
    // Empirical on the CPU box: ~150s base (decode of a structured answer at
    // ~7 tok/s) plus prefill that scales with transcript length.
    val estimatedSec = 150 + (wordCount * 0.03).toInt()
    updateState(session) {
      it.copy(
        recording = false,
        paused = false,
        processing = true,
        processingProgress = 0f,
        status = "Analyzing $wordCount words (~${(estimatedSec + 30) / 60} min)…",
      )
    }
    val ticker =
      VoiceAssistantManager.routeScope.launch {
        val analysisStart = System.currentTimeMillis()
        while (session === currentSession && !session.abortRequested.get()) {
          delay(2_000L)
          val sec = (System.currentTimeMillis() - analysisStart) / 1000
          val fraction = (sec.toFloat() / estimatedSec).coerceAtMost(0.95f)
          updateState(session) {
            it.copy(processingProgress = fraction, status = "Analyzing… ${sec}s of ~${estimatedSec}s")
          }
        }
      }
    val result = runBlocking {
      OrchestratorClient.ramble(
        baseUrl = orchestratorUrl,
        sessionId = session.id,
        mode = _state.value.mode,
        chunk = "",
        final = true,
      )
    }
    ticker.cancel()
    // The user may have aborted while the analysis was in flight — the
    // session is no longer current, so the result is silently dropped.
    if (session !== currentSession || session.abortRequested.get()) {
      pcmFile?.delete()
      return
    }
    result.fold(
      onSuccess = { text ->
        val answer = text.orEmpty()
        updateState(session) {
          it.copy(processing = false, processingProgress = 1f, result = answer, status = "Done")
        }
        persistIfRequested(context, session, transcript, answer, pcmFile)
      },
      onFailure = { e ->
        updateState(session) {
          it.copy(
            processing = false,
            processingProgress = 0f,
            status = "Analysis failed: ${e.message}",
          )
        }
        // Keep what we can even when the analysis fails — the recording and
        // transcript are not worth losing to a server error.
        persistIfRequested(context, session, transcript, answer = "", pcmFile = pcmFile)
      },
    )
  }

  private fun persistIfRequested(
    context: Context,
    session: RambleSession,
    transcript: String,
    answer: String,
    pcmFile: File?,
  ) {
    val s = _state.value
    // Only save when something would actually be stored.
    val storesAnything =
      s.keepTranscript || (s.keepAnswer && answer.isNotBlank()) || (pcmFile?.exists() == true)
    if (!storesAnything) {
      pcmFile?.delete()
      return
    }
    RambleHistoryStore.saveAsync(
      context = context,
      id = session.id,
      mode = s.mode,
      transcript = transcript,
      answer = answer,
      keepTranscript = s.keepTranscript,
      keepAnswer = s.keepAnswer,
      rawPcmFile = pcmFile,
    )
  }

}
