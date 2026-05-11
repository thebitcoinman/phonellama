/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * One-time dialog shown on first launch advising users to close background apps when
 * running larger models for a more reliable API experience.
 */
@Composable
fun PerformanceTipDialog(onDismiss: () -> Unit) {
  Dialog(
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    onDismissRequest = {},
  ) {
    Card(shape = RoundedCornerShape(28.dp)) {
      Column(
        modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Spacer(modifier = Modifier.height(24.dp))

        Icon(
          imageVector = Icons.Rounded.Memory,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(40.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = "Best Performance Tips",
          style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
          modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = "PhoneLlama runs large AI models directly on your device. " +
            "For the most reliable API experience — especially with larger models " +
            "(2B+ parameters) — we recommend:",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
          TipRow(emoji = "📵", text = "Close all other apps before loading a model")
          TipRow(emoji = "🔋", text = "Keep the device plugged in during long sessions")
          TipRow(emoji = "🌡️", text = "Allow the device to cool down if it feels warm")
          TipRow(emoji = "📱", text = "Keep PhoneLlama in the foreground while serving")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "Smaller models (under 2B) run well in most conditions.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
          onClick = onDismiss,
          modifier = Modifier.align(Alignment.End).padding(bottom = 24.dp),
        ) {
          Text("Got it")
        }
      }
    }
  }
}

@Composable
private fun TipRow(emoji: String, text: String) {
  Text(
    text = "$emoji  $text",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(vertical = 3.dp),
  )
}
