package com.lsa.app.ui.common

import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lsa.app.R
import com.lsa.app.rememberContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/** One sample of this process. GPU/NPU load is not readable by apps, so only the backend in use is reported. */
data class ResourceSample(val rssMb: Int, val cpuPercent: Int, val thermal: Int)

/** Parses `utime + stime` (clock ticks) out of `/proc/self/stat`. The command name may contain spaces/parentheses. */
internal fun cpuTicks(stat: String): Long? {
    val rest = stat.substringAfterLast(')', "").trim().split(' ')
    // after ")" the fields start at #3 (state): utime is #14, stime #15
    val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
    val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
    return utime + stime
}

internal fun rssKb(status: String): Long? =
    status.lineSequence().firstOrNull { it.startsWith("VmRSS:") }?.filter { it.isDigit() }?.toLongOrNull()

/** Samples once per second while collected. */
fun resourceSamples(context: android.content.Context, periodMs: Long = 1000): Flow<ResourceSample> = flow {
    val hz = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1)
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val power = context.getSystemService(PowerManager::class.java)
    var lastTicks = cpuTicks(File("/proc/self/stat").readText()) ?: 0L
    var lastAt = System.nanoTime()
    while (true) {
        delay(periodMs)
        val now = System.nanoTime()
        val ticks = runCatching { cpuTicks(File("/proc/self/stat").readText()) }.getOrNull() ?: lastTicks
        val seconds = (now - lastAt) / 1e9
        val cpu = if (seconds > 0) ((ticks - lastTicks) / hz.toDouble() / seconds / cores * 100).toInt().coerceIn(0, 100) else 0
        lastTicks = ticks; lastAt = now
        val rss = runCatching { rssKb(File("/proc/self/status").readText()) }.getOrNull() ?: 0L
        emit(ResourceSample((rss / 1024).toInt(), cpu, power.currentThermalStatus))
    }
}.flowOn(Dispatchers.IO)

/** Small top-bar readout: whether OndolSaem (which runs the models) is connected, process memory, CPU share, heat. */
@Composable
fun ResourceIndicator(modifier: Modifier = Modifier) {
    val container = rememberContainer()
    val ctx = LocalContext.current
    val sample by remember(ctx) { resourceSamples(ctx) }.collectAsStateWithLifecycle(null)
    val host by container.models.embedderBackend.collectAsStateWithLifecycle(null)
    val none = stringResource(R.string.resource_unloaded)
    Column(modifier.padding(end = 12.dp)) {
        Text(
            stringResource(R.string.resource_models, host ?: none),
            style = MaterialTheme.typography.labelSmall,
        )
        sample?.let {
            val hot = if (it.thermal >= PowerManager.THERMAL_STATUS_MODERATE) " 🌡" else ""
            Text(
                stringResource(R.string.resource_usage, it.rssMb, it.cpuPercent) + hot,
                style = MaterialTheme.typography.labelSmall,
                color = if (hot.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}
