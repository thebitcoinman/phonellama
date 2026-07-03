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
import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "RambleHistoryStore"
private const val HISTORY_DIR = "ramble_history"
private const val SAMPLE_RATE = 16000
/** Oldest entries beyond this count are evicted on save. */
private const val MAX_ENTRIES = 50
private val gson = Gson()

data class RambleEntry(
  val id: String,
  val timestampMs: Long,
  val mode: String,
  val words: Int,
  val transcript: String? = null,
  val answer: String? = null,
  val hasAudio: Boolean = false,
  val audioSeconds: Int = 0,
)

/**
 * Opt-in persistence for ramble sessions. Each kept session is a directory
 * under filesDir/ramble_history/<id>/ holding meta.json and optionally
 * audio.wav (16 kHz mono PCM16). Everything is app-private storage.
 */
object RambleHistoryStore {

  private val _entries = MutableStateFlow<List<RambleEntry>>(emptyList())
  val entries: StateFlow<List<RambleEntry>> = _entries.asStateFlow()

  private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private fun historyDir(context: Context): File =
    File(context.filesDir, HISTORY_DIR).apply { mkdirs() }

  /** Reload the entry list from disk on the IO dispatcher. */
  fun refresh(context: Context) {
    val appContext = context.applicationContext
    ioScope.launch { refreshBlocking(appContext) }
  }

  private fun refreshBlocking(context: Context) {
    val loaded =
      historyDir(context)
        .listFiles { f -> f.isDirectory }
        .orEmpty()
        .mapNotNull { dir ->
          try {
            gson.fromJson(File(dir, "meta.json").readText(), RambleEntry::class.java)
          } catch (e: Exception) {
            Log.w(TAG, "Unreadable history entry ${dir.name}", e)
            null
          }
        }
        .sortedByDescending { it.timestampMs }
    _entries.value = loaded
  }

  fun saveAsync(
    context: Context,
    id: String,
    mode: String,
    transcript: String,
    answer: String,
    keepTranscript: Boolean,
    keepAnswer: Boolean,
    rawPcmFile: File?,
  ) {
    val appContext = context.applicationContext
    ioScope.launch {
      try {
        val dir = File(historyDir(appContext), id).apply { mkdirs() }
        var audioSeconds = 0
        var hasAudio = false
        if (rawPcmFile != null && rawPcmFile.exists() && rawPcmFile.length() > 0) {
          audioSeconds = (rawPcmFile.length() / (SAMPLE_RATE * 2)).toInt()
          writeWav(rawPcmFile, File(dir, "audio.wav"))
          hasAudio = true
        }
        val entry =
          RambleEntry(
            id = id,
            timestampMs = System.currentTimeMillis(),
            mode = mode,
            words = transcript.split(" ").count { it.isNotBlank() },
            transcript = transcript.takeIf { keepTranscript },
            answer = answer.takeIf { keepAnswer && answer.isNotBlank() },
            hasAudio = hasAudio,
            audioSeconds = audioSeconds,
          )
        File(dir, "meta.json").writeText(gson.toJson(entry))
        evictBeyondCap(appContext)
        refreshBlocking(appContext)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to save ramble entry", e)
      } finally {
        rawPcmFile?.delete()
      }
    }
  }

  fun delete(context: Context, id: String) {
    val appContext = context.applicationContext
    ioScope.launch {
      File(historyDir(appContext), id).deleteRecursively()
      refreshBlocking(appContext)
    }
  }

  private fun evictBeyondCap(context: Context) {
    val dirs =
      historyDir(context)
        .listFiles { f -> f.isDirectory }
        .orEmpty()
        .sortedByDescending { dir ->
          try {
            gson.fromJson(File(dir, "meta.json").readText(), RambleEntry::class.java).timestampMs
          } catch (_: Exception) {
            0L
          }
        }
    dirs.drop(MAX_ENTRIES).forEach { it.deleteRecursively() }
  }

  fun audioFile(context: Context, id: String): File? =
    File(File(historyDir(context), id), "audio.wav").takeIf { it.exists() }

  /** Prepend a 44-byte WAV header to raw 16 kHz mono PCM16 data. */
  private fun writeWav(rawPcm: File, out: File) {
    val dataLen = rawPcm.length()
    RandomAccessFile(out, "rw").use { wav ->
      wav.setLength(0)
      val header =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
          put("RIFF".toByteArray())
          putInt((36 + dataLen).toInt())
          put("WAVE".toByteArray())
          put("fmt ".toByteArray())
          putInt(16) // PCM chunk size
          putShort(1) // PCM format
          putShort(1) // mono
          putInt(SAMPLE_RATE)
          putInt(SAMPLE_RATE * 2) // byte rate
          putShort(2) // block align
          putShort(16) // bits per sample
          put("data".toByteArray())
          putInt(dataLen.toInt())
        }
      wav.write(header.array())
      rawPcm.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
          wav.write(buf, 0, n)
        }
      }
    }
  }
}
