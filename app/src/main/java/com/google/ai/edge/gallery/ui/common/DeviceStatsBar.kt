package com.google.ai.edge.gallery.ui.common

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import com.google.ai.edge.gallery.ui.rammanager.RamManagerSheet
import com.google.ai.edge.gallery.edgeserver.EdgeServer
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

// ── Data model ───────────────────────────────────────────────────────────────

private data class CpuSnapshot(val idle: Long, val total: Long)

private data class DeviceStats(
  val availRamMb: Long,
  val totalRamMb: Long,
  val swapUsedMb: Long,
  val swapTotalMb: Long,
  val cpuPct: Int,             // 0-100%, -1 if unknown
  val batteryPct: Int,
  val batteryTempC: Float,
  val isCharging: Boolean,
  val thermalLabel: String,
  val thermalColor: Color,
  val tokensPerSec: Float,
)

// ── Readers ───────────────────────────────────────────────────────────────────

/** Read a single line from /proc/meminfo and parse the kB value for `key`. */
private fun readMemInfoKb(key: String): Long {
  try {
    BufferedReader(FileReader("/proc/meminfo")).use { br ->
      var line = br.readLine()
      while (line != null) {
        if (line.startsWith(key)) {
          return line.trim().split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
        }
        line = br.readLine()
      }
    }
  } catch (_: Exception) {}
  return 0L
}

/** Snapshot of /proc/stat for delta-CPU usage. */
private fun cpuSnapshot(): CpuSnapshot {
  try {
    BufferedReader(FileReader("/proc/stat")).use { br ->
      val line = br.readLine() ?: return CpuSnapshot(0, 0)
      // cpu  user nice system idle iowait irq softirq steal guest guest_nice
      val parts = line.trim().split("\\s+".toRegex())
      if (parts.size < 5) return CpuSnapshot(0, 0)
      val user    = parts.getOrNull(1)?.toLongOrNull() ?: 0L
      val nice    = parts.getOrNull(2)?.toLongOrNull() ?: 0L
      val system  = parts.getOrNull(3)?.toLongOrNull() ?: 0L
      val idle    = parts.getOrNull(4)?.toLongOrNull() ?: 0L
      val iowait  = parts.getOrNull(5)?.toLongOrNull() ?: 0L
      val irq     = parts.getOrNull(6)?.toLongOrNull() ?: 0L
      val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L
      val total   = user + nice + system + idle + iowait + irq + softirq
      return CpuSnapshot(idle = idle + iowait, total = total)
    }
  } catch (_: Exception) {}
  return CpuSnapshot(0, 0)
}

private fun cpuPctFromSnapshots(a: CpuSnapshot, b: CpuSnapshot): Int {
  val dTotal = b.total - a.total
  val dIdle  = b.idle  - a.idle
  if (dTotal <= 0L) return -1
  return ((dTotal - dIdle) * 100L / dTotal).toInt().coerceIn(0, 100)
}

// ── Main stats collector ──────────────────────────────────────────────────────

private fun collectStats(
  context: Context,
  prevCpuSnapshot: CpuSnapshot,
  newCpuSnapshot: CpuSnapshot,
  edgeServer: EdgeServer?,
): DeviceStats {
  // RAM
  val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
  val memInfo = ActivityManager.MemoryInfo()
  am?.getMemoryInfo(memInfo)
  val totalRam = memInfo.totalMem / 1024 / 1024
  val availRam = memInfo.availMem / 1024 / 1024

  // Swap — from /proc/meminfo
  val swapTotal = readMemInfoKb("SwapTotal:") / 1024
  val swapFree  = readMemInfoKb("SwapFree:")  / 1024
  val swapUsed  = (swapTotal - swapFree).coerceAtLeast(0L)

  // CPU %
  val cpuPct = cpuPctFromSnapshots(prevCpuSnapshot, newCpuSnapshot)

  // Battery
  val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
  val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
  val batteryPct = if (scale > 0) (level * 100 / scale) else -1
  val batteryTempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
  val batteryTempC = batteryTempRaw / 10f
  val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
  val isCharging = plugged != 0

  // Thermal headroom (API 31+)
  val thermalLabel: String
  val thermalColor: Color
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val headroom = try { pm?.getThermalHeadroom(5) ?: 1f } catch (_: Exception) { 1f }
    when {
      headroom < 0.4f -> { thermalLabel = "HOT";  thermalColor = Color(0xFFE53935) }
      headroom < 0.7f -> { thermalLabel = "WARM"; thermalColor = Color(0xFFFB8C00) }
      else            -> { thermalLabel = "COOL"; thermalColor = Color(0xFF43A047) }
    }
  } else {
    thermalLabel = "—"
    thermalColor = Color.Gray
  }

  // Tokens/sec from EdgeServer
  val tps = edgeServer?.tokensPerSec() ?: 0f

  return DeviceStats(
    availRamMb   = availRam,
    totalRamMb   = totalRam,
    swapUsedMb   = swapUsed,
    swapTotalMb  = swapTotal,
    cpuPct       = cpuPct,
    batteryPct   = batteryPct,
    batteryTempC = batteryTempC,
    isCharging   = isCharging,
    thermalLabel = thermalLabel,
    thermalColor = thermalColor,
    tokensPerSec = tps,
  )
}

// ── Composables ───────────────────────────────────────────────────────────────

/** Compact bar showing live device stats relevant to inference. Polls every 2s. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceStatsBar(
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var stats by remember { mutableStateOf<DeviceStats?>(null) }
  var showRamManager by remember { mutableStateOf(false) }
  val ramSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

  LaunchedEffect(Unit) {
    var prevSnap = cpuSnapshot()
    while (true) {
      delay(2_000)
      val newSnap = cpuSnapshot()
      // EdgeServer is a singleton held by EdgeServerManager; access via companion.
      val edgeServer = try {
        com.google.ai.edge.gallery.edgeserver.EdgeServerManager.currentServer
      } catch (_: Exception) { null }
      stats = collectStats(context, prevSnap, newSnap, edgeServer)
      prevSnap = newSnap
    }
  }

  val s = stats ?: return

  // Colours
  val ramFraction = if (s.totalRamMb > 0) (s.totalRamMb - s.availRamMb).toFloat() / s.totalRamMb else 0f
  val ramColor = when {
    ramFraction > 0.85f -> Color(0xFFE53935)
    ramFraction > 0.65f -> Color(0xFFFB8C00)
    else -> Color(0xFF43A047)
  }
  val swapColor = when {
    s.swapTotalMb > 0 && s.swapUsedMb.toFloat() / s.swapTotalMb > 0.7f -> Color(0xFFE53935)
    s.swapTotalMb > 0 && s.swapUsedMb.toFloat() / s.swapTotalMb > 0.4f -> Color(0xFFFB8C00)
    else -> Color(0xFF9E9E9E)
  }
  val cpuColor = when {
    s.cpuPct > 85 -> Color(0xFFE53935)
    s.cpuPct > 60 -> Color(0xFFFB8C00)
    else -> Color(0xFF42A5F5)
  }
  val batteryColor = when {
    s.batteryPct < 15 -> Color(0xFFE53935)
    s.batteryPct < 30 -> Color(0xFFFB8C00)
    else -> Color(0xFF43A047)
  }
  val tpsColor = if (s.tokensPerSec > 0f) Color(0xFF7E57C2) else Color(0xFF9E9E9E)

  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f))
      .padding(horizontal = 10.dp, vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // RAM: tappable — opens RAM Manager sheet
    StatChip(
      label = "RAM",
      value = "%.1f/%.0fGB".format(s.availRamMb / 1024f, s.totalRamMb / 1024f),
      color = ramColor,
      onClick = { showRamManager = true },
    )

    // Swap (very relevant when loading large models)
    if (s.swapTotalMb > 0) {
      StatChip(
        label = "SWP",
        value = "%.1fGB".format(s.swapUsedMb / 1024f),
        color = swapColor,
      )
    }

    // CPU %
    if (s.cpuPct >= 0) {
      StatChip(label = "CPU", value = "${s.cpuPct}%", color = cpuColor)
    }

    // Thermal
    StatChip(label = "🌡", value = s.thermalLabel, color = s.thermalColor)

    // Battery
    val battLabel = if (s.isCharging) "⚡${s.batteryPct}%" else "🔋${s.batteryPct}%"
    val battValue = if (s.batteryTempC > 0f) "${s.batteryTempC.toInt()}°C" else ""
    if (battValue.isNotEmpty()) {
      StatChip(label = battLabel, value = battValue, color = batteryColor)
    } else {
      StatChip(label = "🔋", value = "${s.batteryPct}%", color = batteryColor)
    }

    // Tokens/sec (only show when active)
    if (s.tokensPerSec > 0.5f) {
      StatChip(label = "TPS", value = "%.1f".format(s.tokensPerSec), color = tpsColor)
    }
  }

  // RAM Manager sheet
  if (showRamManager) {
    RamManagerSheet(
      onDismiss = { showRamManager = false },
      sheetState = ramSheetState,
    )
  }
}

@Composable
private fun StatChip(label: String, value: String, color: Color, onClick: (() -> Unit)? = null) {
  val animColor by animateColorAsState(targetValue = color, animationSpec = tween(600), label = "chip")
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
    if (value.isNotEmpty()) {
      val chipMod = Modifier
        .clip(RoundedCornerShape(3.dp))
        .background(animColor.copy(alpha = 0.15f))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 4.dp, vertical = 1.dp)
      Box(modifier = chipMod) {
        Text(value, fontSize = 9.sp, color = animColor, fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

// ── Model load progress bar ───────────────────────────────────────────────────

/**
 * Thin progress bar during model loading.
 * LiteRT provides no native load callbacks — we animate a time-based estimate:
 * 0 → ~90% over estimatedMs (≈1s/80MB), then snap to 100% on INITIALIZED.
 */
@Composable
fun ModelLoadProgressBar(
  modelManagerViewModel: ModelManagerViewModel,
  modelSizeBytes: Long = 0L,
  modifier: Modifier = Modifier,
) {
  val uiState = modelManagerViewModel.uiState
  val initStatuses = uiState.value.modelInitializationStatus
  val isLoading = initStatuses.values.any { it.status == ModelInitializationStatusType.INITIALIZING }

  var progress by remember { mutableFloatStateOf(0f) }
  var startTimeMs by remember { mutableStateOf(0L) }

  LaunchedEffect(isLoading) {
    if (isLoading) {
      startTimeMs = System.currentTimeMillis()
      val estimatedMs = if (modelSizeBytes > 0L) {
        (modelSizeBytes / (80L * 1024 * 1024) * 1000L).coerceIn(5_000L, 60_000L)
      } else 20_000L
      progress = 0f
      while (true) {
        val elapsed = System.currentTimeMillis() - startTimeMs
        val t = (elapsed.toFloat() / estimatedMs).coerceIn(0f, 1f)
        progress = 0.9f * (1f - (1f - t) * (1f - t))
        delay(100)
      }
    } else {
      if (progress > 0f) {
        progress = 1f
        delay(600)
        progress = 0f
      }
    }
  }

  val animatedProgress by animateFloatAsState(
    targetValue = progress,
    animationSpec = tween(200),
    label = "loadProgress",
  )

  if (animatedProgress > 0f) {
    LinearProgressIndicator(
      progress = { animatedProgress },
      modifier = modifier.fillMaxWidth().height(3.dp),
      strokeCap = StrokeCap.Round,
      color = MaterialTheme.colorScheme.primary,
      trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
  }
}
