/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.edgeserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity

private const val TAG = "EdgeServerService"

class EdgeServerService : Service() {

  companion object {
    const val ACTION_START = "com.google.ai.edge.gallery.edgeserver.START"
    const val ACTION_STOP = "com.google.ai.edge.gallery.edgeserver.STOP"
    const val EXTRA_PORT = "port"
    private const val NOTIFICATION_ID = 9001
    private const val CHANNEL_ID = "edge_server_channel"
  }

  // WakeLock: keeps the CPU running when screen is off so inference/network keeps working.
  // WifiLock: prevents Android from powering down WiFi (and by extension ZeroTier VPN) on idle.
  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        val port = intent.getIntExtra(EXTRA_PORT, EdgeServer.DEFAULT_PORT)
        startForeground(NOTIFICATION_ID, buildNotification(port))
        acquireWakeLocks()
      }
      ACTION_STOP -> {
        releaseWakeLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    releaseWakeLocks()
    super.onDestroy()
  }

  private fun acquireWakeLocks() {
    try {
      if (wakeLock == null) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
          PowerManager.PARTIAL_WAKE_LOCK,
          "PhoneLlama::ApiServerWakeLock"
        ).also { it.acquire() }
        Log.d(TAG, "WakeLock acquired")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to acquire WakeLock", e)
    }
    try {
      if (wifiLock == null) {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(
          WifiManager.WIFI_MODE_FULL_HIGH_PERF,
          "PhoneLlama::ApiServerWifiLock"
        ).also { it.acquire() }
        Log.d(TAG, "WifiLock acquired")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to acquire WifiLock", e)
    }
  }

  private fun releaseWakeLocks() {
    try { wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null } catch (e: Exception) { Log.e(TAG, "WakeLock release error", e) }
    try { wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null } catch (e: Exception) { Log.e(TAG, "WifiLock release error", e) }
  }

  private fun buildNotification(port: Int): Notification {
    val tapIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
      this, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("PhoneLlama API Server")
      .setContentText("Serving on port $port")
      .setSmallIcon(android.R.drawable.ic_menu_share)
      .setContentIntent(pendingIntent)
      .setOngoing(true)
      .build()
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID, "PhoneLlama Server", NotificationManager.IMPORTANCE_LOW
    ).apply { description = "PhoneLlama API server status" }
    val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    mgr.createNotificationChannel(channel)
  }
}
