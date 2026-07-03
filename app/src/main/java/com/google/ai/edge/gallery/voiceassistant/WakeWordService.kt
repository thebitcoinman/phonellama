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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "WakeWordService"
private const val NOTIFICATION_ID = 9002
private const val CHANNEL_ID = "wake_word_channel"

class WakeWordService : Service() {

  companion object {
    const val ACTION_START = "com.google.ai.edge.gallery.voiceassistant.START"
    const val ACTION_STOP = "com.google.ai.edge.gallery.voiceassistant.STOP"
    const val EXTRA_WAKE_PHRASE = "wake_phrase"
  }

  private val running = AtomicBoolean(false)
  private var listenThread: Thread? = null
  private var wakePhrases: List<String> = VoskWakeWordDetector.defaultPhrases

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        val customPhrase = intent.getStringExtra(EXTRA_WAKE_PHRASE)?.trim().orEmpty()
        // A custom phrase replaces the defaults — every extra active phrase
        // widens the false-trigger surface against ambient speech.
        wakePhrases =
          if (customPhrase.isNotEmpty()) {
            listOf(customPhrase)
          } else {
            VoskWakeWordDetector.defaultPhrases
          }
        if (!VoskTranscriber.isModelDownloaded(applicationContext)) {
          VoiceAssistantManager.updateStatus("Download the Vosk model before enabling wake word")
          VoiceAssistantManager.setWakeWordListening(false)
          WakeWordManager.markNotRunning()
          stopSelf()
          return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("Listening for \"hey llama\"…"))
        startListening()
      }
      ACTION_STOP -> {
        stopListening()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    stopListening()
    super.onDestroy()
  }

  private fun startListening() {
    if (running.getAndSet(true)) return
    VoiceAssistantManager.setWakeWordListening(true)
    listenThread =
      thread(name = "WakeWordListen") {
        try {
          VoiceAssistantManager.updateStatus("Wake word active (Vosk)")
          while (running.get()) {
            val detected =
              VoskWakeWordDetector.listenForWakePhrase(
                context = applicationContext,
                phrases = wakePhrases,
                isRunning = { running.get() },
              )
            if (!running.get()) break
            if (detected) {
              handleWakeWord()
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Wake word loop error", e)
          VoiceAssistantManager.updateStatus("Wake word error: ${e.message}")
        } finally {
          running.set(false)
          VoiceAssistantManager.setWakeWordListening(false)
        }
      }
  }

  private fun handleWakeWord() {
    VoiceAssistantManager.updateStatus("Wake phrase heard — listening…")
    try {
      val pcm = AudioVadRecorder.recordUntilSilence()
      if (pcm.isEmpty()) {
        VoiceAssistantManager.updateStatus("No audio captured")
        return
      }
      VoiceAssistantManager.updateStatus("Transcribing…")
      val transcript =
        VoskTranscriber.transcribe(applicationContext, pcm).getOrElse {
          VoiceAssistantManager.updateStatus(it.message ?: "STT failed")
          return
        }
      WakeWordManager.deliverTranscript(transcript)
    } catch (e: Exception) {
      Log.e(TAG, "handleWakeWord failed", e)
      VoiceAssistantManager.updateStatus("Wake flow error: ${e.message}")
    }
  }

  private fun stopListening() {
    running.set(false)
    listenThread?.interrupt()
    listenThread = null
    VoiceAssistantManager.setWakeWordListening(false)
  }

  private fun buildNotification(text: String): Notification {
    createChannel()
    val tapIntent =
      Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
    val pendingIntent =
      PendingIntent.getActivity(
        this,
        1,
        tapIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("PhoneLlama Voice Assistant")
      .setContentText(text)
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentIntent(pendingIntent)
      .setOngoing(true)
      .build()
  }

  private fun createChannel() {
    val channel =
      NotificationChannel(CHANNEL_ID, "Voice Assistant", NotificationManager.IMPORTANCE_LOW)
        .apply { description = "Wake word and voice assistant status" }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }
}
