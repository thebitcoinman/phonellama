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
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val NOTIFICATION_ID = 9003
private const val CHANNEL_ID = "ramble_channel"

/**
 * Foreground anchor for ramble sessions: keeps the process (and microphone)
 * alive while the screen is off or the app is backgrounded, and shows live
 * progress in the notification. All recording/analysis logic lives in
 * [RambleManager]; this service only tracks its state and stops itself when
 * the session ends.
 */
class RambleService : Service() {

  companion object {
    const val ACTION_START = "com.google.ai.edge.gallery.voiceassistant.RAMBLE_START"

    fun start(context: Context) {
      val intent =
        Intent(context, RambleService::class.java).apply { action = ACTION_START }
      context.startForegroundService(intent)
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var watchJob: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_START) {
      startForeground(NOTIFICATION_ID, buildNotification("Recording…"))
      watchState()
    } else {
      stopSelf()
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun watchState() {
    if (watchJob?.isActive == true) return
    watchJob =
      scope.launch {
        var lastText = ""
        RambleManager.state.collectLatest { s ->
          if (!s.recording && !s.processing) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return@collectLatest
          }
          val words = s.transcript.split(" ").count { it.isNotBlank() }
          val text =
            when {
              s.paused -> "Paused · $words words so far"
              s.recording -> "Recording · $words words"
              else -> s.status.ifBlank { "Analyzing…" }
            }
          if (text != lastText) {
            lastText = text
            getSystemService(NotificationManager::class.java)
              .notify(NOTIFICATION_ID, buildNotification(text))
          }
        }
      }
  }

  private fun buildNotification(text: String): Notification {
    createChannel()
    val tapIntent =
      Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
    val pendingIntent =
      PendingIntent.getActivity(
        this,
        2,
        tapIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("PhoneLlama Ramble")
      .setContentText(text)
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentIntent(pendingIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
  }

  private fun createChannel() {
    val channel =
      NotificationChannel(CHANNEL_ID, "Ramble mode", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Live status while a ramble session records or analyzes"
      }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }
}
