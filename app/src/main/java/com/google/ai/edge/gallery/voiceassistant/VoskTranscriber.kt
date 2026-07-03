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

import java.io.File

import java.io.FileOutputStream

import java.io.IOException

import java.net.HttpURLConnection

import java.net.URL

import java.util.zip.ZipInputStream

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.delay

import kotlinx.coroutines.withContext

import org.vosk.Model

import org.vosk.Recognizer



private const val TAG = "VoskTranscriber"

private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"

private const val MODEL_ZIP_URL =

  "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

/** From alphacephei Content-Length header (bytes). */

private const val EXPECTED_ZIP_BYTES = 41_205_931L

private const val MIN_ZIP_BYTES = 35_000_000L

private const val MAX_DOWNLOAD_ATTEMPTS = 3

private const val BUFFER_SIZE = 8192



object VoskTranscriber {



  fun getModelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)



  fun isModelDownloaded(context: Context): Boolean {

    val dir = getModelDir(context)

    return dir.exists() && File(dir, "am/final.mdl").exists()

  }



  /**

   * @param onProgress Human-readable status and optional download fraction (0–1).

   */

  suspend fun downloadModel(

    context: Context,

    onProgress: (message: String, fraction: Float?) -> Unit,

  ): Result<Unit> =

    withContext(Dispatchers.IO) {

      if (isModelDownloaded(context)) {

        onProgress("Vosk model ready", 1f)

        return@withContext Result.success(Unit)

      }



      var lastError: Exception? = null

      repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->

        try {

          if (attempt > 0) {

            onProgress("Retrying download (attempt ${attempt + 1}/$MAX_DOWNLOAD_ATTEMPTS)…", null)

            delay(1_500)

          }

          cleanupPartialDownload(context)

          downloadZipWithProgress(context, onProgress)

          extractZip(context, onProgress)

          if (!isModelDownloaded(context)) {

            throw IOException("Extracted model is incomplete")

          }

          onProgress("Vosk model ready", 1f)

          return@withContext Result.success(Unit)

        } catch (e: Exception) {

          lastError = e as? Exception ?: Exception(e.message, e)

          Log.e(TAG, "Vosk download attempt ${attempt + 1} failed", e)

          cleanupPartialDownload(context)

        }

      }

      Result.failure(lastError ?: IOException("Vosk download failed"))

    }



  fun transcribe(context: Context, pcm16le: ByteArray): Result<String> {

    if (!isModelDownloaded(context)) {

      return Result.failure(Exception("Vosk model not downloaded"))

    }

    return try {

      val model = Model(getModelDir(context).absolutePath)

      val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

      val chunkSize = 4096

      var offset = 0

      while (offset < pcm16le.size) {

        val len = minOf(chunkSize, pcm16le.size - offset)

        recognizer.acceptWaveForm(pcm16le.copyOfRange(offset, offset + len), len)

        offset += len

      }

      val json = recognizer.finalResult

      val text =

        Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)?.trim().orEmpty()

      recognizer.close()

      model.close()

      if (text.isBlank()) Result.failure(Exception("No speech detected"))

      else Result.success(text)

    } catch (e: Exception) {

      Log.e(TAG, "Transcription failed", e)

      Result.failure(e)

    }

  }



  fun cleanupPartialDownload(context: Context) {

    File(context.cacheDir, "vosk-model.zip").delete()

    File(context.cacheDir, "vosk-model.zip.part").delete()

    getModelDir(context).deleteRecursively()

  }



  private fun downloadZipWithProgress(

    context: Context,

    onProgress: (message: String, fraction: Float?) -> Unit,

  ) {

    val partFile = File(context.cacheDir, "vosk-model.zip.part")

    val url = URL(MODEL_ZIP_URL)

    val connection = openConnection(url, partFile.length())

    try {

      val responseCode = connection.responseCode

      if (

        responseCode != HttpURLConnection.HTTP_OK &&

          responseCode != HttpURLConnection.HTTP_PARTIAL

      ) {

        throw IOException("HTTP $responseCode from Vosk model server")

      }



      var downloadedBytes = partFile.length()

      if (responseCode == HttpURLConnection.HTTP_OK && downloadedBytes > 0) {

        // Server ignored Range — restart from scratch.

        partFile.delete()

        downloadedBytes = 0L

      }



      val totalBytes = resolveTotalBytes(connection, responseCode, downloadedBytes)

      var lastProgressUpdateMs = 0L



      connection.inputStream.use { input ->

        FileOutputStream(partFile, downloadedBytes > 0).use { output ->

          val buffer = ByteArray(BUFFER_SIZE)

          var read: Int

          while (input.read(buffer).also { read = it } != -1) {

            output.write(buffer, 0, read)

            downloadedBytes += read

            val now = System.currentTimeMillis()

            if (now - lastProgressUpdateMs >= 200) {

              lastProgressUpdateMs = now

              val fraction =

                if (totalBytes > 0) {

                  (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 0.99f)

                } else {

                  null

                }

              val pctText =

                if (fraction != null) {

                  "${(fraction * 100).toInt()}% · ${formatMegabytes(downloadedBytes)} / ${formatMegabytes(totalBytes)}"

                } else {

                  formatMegabytes(downloadedBytes)

                }

              onProgress("Downloading Vosk STT… $pctText", fraction)

            }

          }

          output.fd.sync()

        }

      }



      if (downloadedBytes < MIN_ZIP_BYTES) {

        throw IOException(

          "Download incomplete (${formatMegabytes(downloadedBytes)} received, expected ~${formatMegabytes(EXPECTED_ZIP_BYTES)})"

        )

      }

      if (totalBytes > 0 && downloadedBytes < totalBytes) {

        throw IOException("Unexpected end of stream (${downloadedBytes}/$totalBytes bytes)")

      }

      validateZipHeader(partFile)



      val zipFile = File(context.cacheDir, "vosk-model.zip")

      if (zipFile.exists()) zipFile.delete()

      if (!partFile.renameTo(zipFile)) {

        partFile.copyTo(zipFile, overwrite = true)

        partFile.delete()

      }

    } finally {

      connection.disconnect()

    }

  }



  private fun openConnection(url: URL, existingBytes: Long): HttpURLConnection {

    val connection = url.openConnection() as HttpURLConnection

    connection.connectTimeout = 30_000

    connection.readTimeout = 120_000

    connection.instanceFollowRedirects = true

    connection.setRequestProperty("User-Agent", "PhoneLlama/1.0 (Android)")

    connection.setRequestProperty("Accept-Encoding", "identity")

    if (existingBytes > 0) {

      connection.setRequestProperty("Range", "bytes=$existingBytes-")

    }

    connection.connect()

    return connection

  }



  private fun resolveTotalBytes(

    connection: HttpURLConnection,

    responseCode: Int,

    alreadyDownloaded: Long,

  ): Long {

    if (responseCode == HttpURLConnection.HTTP_PARTIAL) {

      connection.getHeaderField("Content-Range")?.substringAfter("/")?.toLongOrNull()?.let {

        return it

      }

    }

    val contentLength = connection.contentLengthLong

    if (contentLength > 0) {

      return alreadyDownloaded + contentLength

    }

    return EXPECTED_ZIP_BYTES

  }



  private fun validateZipHeader(file: File) {

    file.inputStream().use { input ->

      val header = ByteArray(4)

      if (input.read(header) < 4 || header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {

        throw IOException("Downloaded file is not a valid zip archive")

      }

    }

  }



  private fun extractZip(context: Context, onProgress: (message: String, fraction: Float?) -> Unit) {

    val zipFile = File(context.cacheDir, "vosk-model.zip")

    if (!zipFile.exists()) throw IOException("Missing vosk-model.zip after download")

    onProgress("Extracting Vosk model…", null)

    ZipInputStream(zipFile.inputStream()).use { zis ->

      var entry = zis.nextEntry

      while (entry != null) {

        val outFile = File(context.filesDir, entry.name)

        if (entry.isDirectory) {

          outFile.mkdirs()

        } else {

          outFile.parentFile?.mkdirs()

          FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }

        }

        zis.closeEntry()

        entry = zis.nextEntry

      }

    }

    zipFile.delete()

    File(context.cacheDir, "vosk-model.zip.part").delete()

  }



  private fun formatMegabytes(bytes: Long): String {

    val mb = bytes / (1024f * 1024f)

    return if (mb >= 10f) "${mb.toInt()} MB" else String.format("%.1f MB", mb)

  }

}



private const val SAMPLE_RATE = 16000

