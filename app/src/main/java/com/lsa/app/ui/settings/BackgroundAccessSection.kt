package com.lsa.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.lsa.app.R
import com.lsa.app.rememberContainer
import com.lsa.app.ui.common.WarningCard

/** What the device needs to allow so scheduled tasks arrive on time and can tell the user. */
data class BackgroundAccess(val notifications: Boolean, val exactAlarms: Boolean, val batteryExempt: Boolean) {
    val allSet get() = notifications && exactAlarms && batteryExempt
}

@Composable
fun rememberBackgroundAccess(): BackgroundAccess {
    val ctx = LocalContext.current
    val alarms = rememberContainer().taskAlarms
    fun read() = BackgroundAccess(
        notifications = NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
        exactAlarms = alarms.canScheduleExact(),
        batteryExempt = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
    )
    var state by remember { mutableStateOf(read()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = read() }
    return state
}

/**
 * Notification permission, exact alarms and the battery-optimization exemption, one card per missing item
 * (same pattern as the all-files-access card). With [showWhenAllSet] a short "all set" line replaces them.
 */
@Composable
fun BackgroundAccessCards(showWhenAllSet: Boolean = true, batteryOnly: Boolean = false) {
    val ctx = LocalContext.current
    val access = rememberBackgroundAccess()
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pkg = Uri.parse("package:${ctx.packageName}")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!access.notifications && !batteryOnly) {
            WarningCard(stringResource(R.string.bg_notifications), stringResource(R.string.bg_allow)) {
                val needsRuntime = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsRuntime) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
        }
        if (!access.exactAlarms && !batteryOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WarningCard(stringResource(R.string.bg_exact), stringResource(R.string.bg_allow)) {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            }
        }
        if (!access.batteryExempt) {
            WarningCard(stringResource(R.string.bg_battery), stringResource(R.string.bg_allow)) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) }
                    .onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }
        if (access.allSet && showWhenAllSet && !batteryOnly) {
            Text(stringResource(R.string.bg_all_set), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            Text(stringResource(R.string.bg_samsung), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
