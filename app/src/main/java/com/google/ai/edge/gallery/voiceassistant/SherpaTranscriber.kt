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
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SherpaTranscriber"
private const val MODEL_DIR = "sherpa-zipformer-en"
private const val ZIP_NAME = "sherpa-zipformer-en-int8.zip"
private const val ENCODER = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
private const val DECODER = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx"
private const val JOINER = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
private const val TOKENS = "tokens.txt"

private const val WHISPER_DIR = "sherpa-whisper-small-en"
private const val WHISPER_ZIP = "sherpa-whisper-small-en-int8.zip"
private const val W_ENCODER = "small.en-encoder.int8.onnx"
private const val W_DECODER = "small.en-decoder.int8.onnx"
private const val W_TOKENS = "small.en-tokens.txt"
private const val VAD_MODEL = "silero_vad.onnx"

private const val SAMPLE_RATE = 16000

/**
 * Streaming Zipformer STT via sherpa-onnx — a large accuracy upgrade over the
 * small Vosk model at natural speaking pace. The int8 model (~70 MB on disk)
 * is downloaded from the orchestrator, which serves it at /models/.
 */
object SherpaTranscriber {

  private fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR)

  private fun whisperDir(context: Context): File = File(context.filesDir, WHISPER_DIR)

  fun isModelDownloaded(context: Context): Boolean {
    val dir = modelDir(context)
    return listOf(ENCODER, DECODER, JOINER, TOKENS).all { File(dir, it).exists() }
  }

  fun isWhisperDownloaded(context: Context): Boolean {
    val dir = whisperDir(context)
    return listOf(W_ENCODER, W_DECODER, W_TOKENS, VAD_MODEL).all { File(dir, it).exists() }
  }

  suspend fun downloadModel(
    context: Context,
    orchestratorUrl: String,
    onProgress: (message: String, fraction: Float?) -> Unit,
  ): Result<Unit> =
    downloadZip(context, orchestratorUrl, ZIP_NAME, modelDir(context), "Zipformer", onProgress) {
      isModelDownloaded(context)
    }

  suspend fun downloadWhisper(
    context: Context,
    orchestratorUrl: String,
    onProgress: (message: String, fraction: Float?) -> Unit,
  ): Result<Unit> =
    downloadZip(context, orchestratorUrl, WHISPER_ZIP, whisperDir(context), "Whisper", onProgress) {
      isWhisperDownloaded(context)
    }

  private suspend fun downloadZip(
    context: Context,
    orchestratorUrl: String,
    zipName: String,
    targetDir: File,
    label: String,
    onProgress: (message: String, fraction: Float?) -> Unit,
    isComplete: () -> Boolean,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      if (isComplete()) {
        onProgress("$label model ready", 1f)
        return@withContext Result.success(Unit)
      }
      val zipFile = File(context.cacheDir, zipName)
      try {
        val base = orchestratorUrl.trim().removeSuffix("/")
        val url = URL("$base/models/$zipName")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        try {
          if (connection.responseCode !in 200..299) {
            throw IOException("HTTP ${connection.responseCode} from orchestrator /models")
          }
          val total = connection.contentLengthLong
          var downloaded = 0L
          var lastUpdate = 0L
          connection.inputStream.use { input ->
            FileOutputStream(zipFile).use { output ->
              val buf = ByteArray(64 * 1024)
              var n: Int
              while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                downloaded += n
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 200) {
                  lastUpdate = now
                  val fraction =
                    if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 0.99f) else null
                  onProgress(
                    "Downloading $label STT… ${downloaded / (1024 * 1024)} MB" +
                      if (total > 0) " / ${total / (1024 * 1024)} MB" else "",
                    fraction,
                  )
                }
              }
            }
          }
          if (total > 0 && downloaded < total) {
            throw IOException("Incomplete download ($downloaded/$total bytes)")
          }
        } finally {
          connection.disconnect()
        }

        onProgress("Extracting model…", null)
        targetDir.deleteRecursively()
        targetDir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zis ->
          var entry = zis.nextEntry
          while (entry != null) {
            if (!entry.isDirectory) {
              // Flatten: the zip contains bare files.
              val outFile = File(targetDir, File(entry.name).name)
              FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
            }
            zis.closeEntry()
            entry = zis.nextEntry
          }
        }
        if (!isComplete()) {
          throw IOException("Extracted model is incomplete")
        }
        onProgress("$label model ready", 1f)
        Result.success(Unit)
      } catch (e: Exception) {
        Log.e(TAG, "$label download failed", e)
        targetDir.deleteRecursively()
        Result.failure(e)
      } finally {
        zipFile.delete()
      }
    }

  fun createRecognizer(context: Context): OnlineRecognizer {
    val dir = modelDir(context)
    val config =
      OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig =
          OnlineModelConfig(
            transducer =
              OnlineTransducerModelConfig(
                encoder = File(dir, ENCODER).absolutePath,
                decoder = File(dir, DECODER).absolutePath,
                joiner = File(dir, JOINER).absolutePath,
              ),
            tokens = File(dir, TOKENS).absolutePath,
            numThreads = 2,
            // The en-2023-06-26 model is a zipformer2 architecture; the v1
            // "zipformer" loader aborts on it ('attention_dims' missing).
            modelType = "zipformer2",
          ),
        endpointConfig =
          EndpointConfig(
            // Segment on ~1.3s of trailing silence after speech, matching the
            // cadence the ramble pipeline was tuned for with Vosk.
            rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0f),
            rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.3f, minUtteranceLength = 0f),
            rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 30f),
          ),
        enableEndpoint = true,
      )
    return OnlineRecognizer(config = config)
  }

  /**
   * Whisper small.en via sherpa-onnx's offline recognizer. Not streaming —
   * used per VAD-detected speech segment.
   */
  fun createWhisperRecognizer(context: Context): OfflineRecognizer {
    val dir = whisperDir(context)
    val config =
      OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig =
          OfflineModelConfig(
            whisper =
              OfflineWhisperModelConfig(
                encoder = File(dir, W_ENCODER).absolutePath,
                decoder = File(dir, W_DECODER).absolutePath,
                language = "en",
                task = "transcribe",
              ),
            tokens = File(dir, W_TOKENS).absolutePath,
            numThreads = 4,
            modelType = "whisper",
          ),
      )
    return OfflineRecognizer(config = config)
  }

  /** Silero VAD for segmenting speech ahead of the non-streaming Whisper decoder. */
  fun createVad(context: Context): Vad {
    val config =
      VadModelConfig(
        sileroVadModelConfig =
          SileroVadModelConfig(
            model = File(whisperDir(context), VAD_MODEL).absolutePath,
            threshold = 0.5f,
            minSilenceDuration = 0.8f,
            minSpeechDuration = 0.25f,
            windowSize = 512,
            maxSpeechDuration = 20f,
          ),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
        provider = "cpu",
      )
    return Vad(config = config)
  }

  /** VAD window size in samples — feed exactly this many at a time. */
  const val VAD_WINDOW = 512

  /** Convert PCM16LE bytes to normalized float samples for sherpa-onnx. */
  fun pcmToFloats(buffer: ByteArray, len: Int): FloatArray {
    val samples = FloatArray(len / 2)
    for (i in samples.indices) {
      val lo = buffer[2 * i].toInt() and 0xFF
      val hi = buffer[2 * i + 1].toInt()
      samples[i] = ((hi shl 8) or lo) / 32768.0f
    }
    return samples
  }
}

/**
 * Thin streaming session over sherpa-onnx: feed PCM chunks, get finalized
 * segments back on endpoint detection.
 */
class SherpaStream(private val recognizer: OnlineRecognizer) : AutoCloseable {
  private val stream: OnlineStream = recognizer.createStream()

  /** Feed audio; returns a finalized segment's text, or null if none yet. */
  fun accept(buffer: ByteArray, len: Int): String? {
    stream.acceptWaveform(SherpaTranscriber.pcmToFloats(buffer, len), SAMPLE_RATE)
    while (recognizer.isReady(stream)) {
      recognizer.decode(stream)
    }
    if (recognizer.isEndpoint(stream)) {
      val text = recognizer.getResult(stream).text.trim()
      recognizer.reset(stream)
      return text.ifBlank { null }
    }
    return null
  }

  /** Flush and return any trailing text. */
  fun finish(): String {
    stream.inputFinished()
    while (recognizer.isReady(stream)) {
      recognizer.decode(stream)
    }
    return recognizer.getResult(stream).text.trim()
  }

  override fun close() {
    stream.release()
  }
}
