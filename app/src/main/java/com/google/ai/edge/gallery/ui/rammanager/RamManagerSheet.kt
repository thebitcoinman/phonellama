package com.google.ai.edge.gallery.ui.rammanager

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Debug
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Data model ────────────────────────────────────────────────────────────────

data class ProcessEntry(
  val pid: Int,
  val packageName: String,
  val appName: String,
  val rssKb: Long,
  val isSystem: Boolean,
  val isCurrentApp: Boolean,
)

// ── Process loader ─────────────────────────────────────────────────────────────

private suspend fun loadProcesses(context: Context): List<ProcessEntry> = withContext(Dispatchers.IO) {
  val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    ?: return@withContext emptyList()
  val pm = context.packageManager
  val myPid = android.os.Process.myPid()

  val runningProcs = am.runningAppProcesses ?: return@withContext emptyList()
  val pids = runningProcs.map { it.pid }.toIntArray()
  val memInfoArray: Array<Debug.MemoryInfo> = try {
    am.getProcessMemoryInfo(pids)
  } catch (_: Exception) {
    Array(pids.size) { Debug.MemoryInfo() }
  }

  runningProcs.mapIndexedNotNull { index, proc ->
    val pkgName = proc.pkgList?.firstOrNull() ?: proc.processName ?: return@mapIndexedNotNull null
    val appName = try {
      pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString()
    } catch (_: Exception) {
      pkgName.substringAfterLast(".")
    }
    val isSystem = try {
      val info = pm.getApplicationInfo(pkgName, 0)
      (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: Exception) { false }

    val rssKb = memInfoArray.getOrNull(index)?.totalPss?.toLong() ?: 0L

    ProcessEntry(
      pid = proc.pid,
      packageName = pkgName,
      appName = appName,
      rssKb = rssKb,
      isSystem = isSystem,
      isCurrentApp = proc.pid == myPid,
    )
  }
    .filter { it.rssKb > 0L }
    .sortedByDescending { it.rssKb }
}

// ── Bottom sheet ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RamManagerSheet(
  onDismiss: () -> Unit,
  sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var processes by remember { mutableStateOf<List<ProcessEntry>?>(null) }
  var isLoading by remember { mutableStateOf(true) }
  var killedPackages by remember { mutableStateOf(setOf<String>()) }

  val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
  val memInfo = remember { ActivityManager.MemoryInfo() }.also { am?.getMemoryInfo(it) }
  val totalRamMb = memInfo.totalMem / 1024 / 1024
  val availRamMb = memInfo.availMem / 1024 / 1024
  val usedRamMb  = totalRamMb - availRamMb

  fun refresh() {
    isLoading = true
    killedPackages = emptySet()
    scope.launch {
      processes = loadProcesses(context)
      isLoading = false
    }
  }

  LaunchedEffect(Unit) { refresh() }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {

      // Header
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("RAM Manager", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
      }

      Spacer(Modifier.height(8.dp))

      // RAM bar
      val ramFraction = if (totalRamMb > 0) usedRamMb.toFloat() / totalRamMb else 0f
      val ramBarColor = when {
        ramFraction > 0.85f -> Color(0xFFE53935)
        ramFraction > 0.65f -> Color(0xFFFB8C00)
        else -> MaterialTheme.colorScheme.primary
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("RAM", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
          progress = { ramFraction },
          modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
          color = ramBarColor,
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
          strokeCap = StrokeCap.Round,
        )
        Text("%.1f / %.0f GB".format(usedRamMb / 1024f, totalRamMb / 1024f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      }

      Spacer(Modifier.height(12.dp))

      // Kill All Background button
      val killableCount = processes?.count { !it.isSystem && !it.isCurrentApp && it.packageName !in killedPackages } ?: 0
      Button(
        onClick = {
          scope.launch {
            processes
              ?.filter { !it.isSystem && !it.isCurrentApp && it.packageName !in killedPackages }
              ?.forEach { proc ->
                try { am?.killBackgroundProcesses(proc.packageName) } catch (_: Exception) {}
                killedPackages = killedPackages + proc.packageName
              }
            delay(800)
            refresh()
          }
        },
        enabled = killableCount > 0 && !isLoading,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
      ) {
        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Kill All Background Apps ($killableCount)")
      }

      Spacer(Modifier.height(12.dp))

      if (isLoading) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text("Reading process memory…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      } else {
        val procs = processes ?: emptyList()
        val topRssKb = procs.firstOrNull()?.rssKb ?: 1L

        Text(
          "${procs.size} processes • tap Kill to free memory",
          fontSize = 11.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 6.dp),
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          items(procs, key = { it.pid }) { proc ->
            ProcessRow(
              proc = proc,
              topRssKb = topRssKb,
              isKilled = proc.packageName in killedPackages,
              onKill = {
                scope.launch {
                  try { am?.killBackgroundProcesses(proc.packageName) } catch (_: Exception) {}
                  killedPackages = killedPackages + proc.packageName
                  delay(500)
                  refresh()
                }
              },
            )
          }
        }
      }
    }
  }
}

// ── Process row ───────────────────────────────────────────────────────────────

@Composable
private fun ProcessRow(
  proc: ProcessEntry,
  topRssKb: Long,
  isKilled: Boolean,
  onKill: () -> Unit,
) {
  val fraction = if (topRssKb > 0) proc.rssKb.toFloat() / topRssKb else 0f
  val barColor = when {
    proc.isCurrentApp -> MaterialTheme.colorScheme.primary
    proc.isSystem     -> Color(0xFF9E9E9E)
    fraction > 0.6f   -> Color(0xFFE53935)
    fraction > 0.3f   -> Color(0xFFFB8C00)
    else              -> Color(0xFF43A047)
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(
        if (isKilled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
      )
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (isKilled) Color.Gray else barColor))

    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          proc.appName,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
          color = if (isKilled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        when {
          proc.isCurrentApp -> Text("(this app)", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
          proc.isSystem     -> Text("system", fontSize = 9.sp, color = Color(0xFF9E9E9E))
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
          progress = { fraction },
          modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
          color = if (isKilled) Color.Gray else barColor,
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
          strokeCap = StrokeCap.Round,
        )
        Text(formatMem(proc.rssKb), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
      }
    }

    AnimatedVisibility(visible = !proc.isCurrentApp && !isKilled, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
      OutlinedButton(
        onClick = onKill,
        enabled = !proc.isSystem,
        modifier = Modifier.height(30.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (proc.isSystem) Color.Gray else Color(0xFFE53935)),
      ) {
        Icon(Icons.Default.Close, contentDescription = "Kill", modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text("Kill", fontSize = 11.sp)
      }
    }

    if (isKilled) {
      Text("freed", fontSize = 11.sp, color = Color(0xFF43A047), fontWeight = FontWeight.SemiBold)
    }
  }
}

private fun formatMem(kb: Long): String = when {
  kb >= 1024 * 1024 -> "%.1f GB".format(kb / 1024f / 1024f)
  kb >= 1024        -> "%.0f MB".format(kb / 1024f)
  else              -> "$kb KB"
}
