@file:OptIn(ExperimentalMaterial3Api::class)

package com.lsa.app.ui.onboarding

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lsa.app.R
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.AutoIndexMode
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private data class ModeText(@StringRes val title: Int, @StringRes val desc: Int)

private fun text(mode: AutoIndexMode) = when (mode) {
    AutoIndexMode.IMMEDIATE -> ModeText(R.string.auto_mode_immediate, R.string.auto_mode_immediate_desc)
    AutoIndexMode.SCHEDULED -> ModeText(R.string.auto_mode_scheduled, R.string.auto_mode_scheduled_desc)
    AutoIndexMode.IDLE -> ModeText(R.string.auto_mode_idle, R.string.auto_mode_idle_desc)
    AutoIndexMode.MANUAL -> ModeText(R.string.auto_mode_manual, R.string.auto_mode_manual_desc)
}

fun formatMinutes(minutes: Int): String =
    LocalTime.of(minutes / 60, minutes % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** Mode radios + (for SCHEDULED) time and "only while charging". [mode] null = nothing chosen yet. */
@Composable
fun AutoIndexOptions(
    mode: AutoIndexMode?,
    minutes: Int,
    chargingOnly: Boolean,
    onMode: (AutoIndexMode) -> Unit,
    onMinutes: (Int) -> Unit,
    onChargingOnly: (Boolean) -> Unit,
) {
    var showTime by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Recommended-first order: idle, immediate, scheduled, manual.
        listOf(AutoIndexMode.IDLE, AutoIndexMode.IMMEDIATE, AutoIndexMode.SCHEDULED, AutoIndexMode.MANUAL).forEach { m ->
            val t = text(m)
            Row(
                Modifier.fillMaxWidth().selectable(mode == m, role = Role.RadioButton) { onMode(m) },
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = mode == m, onClick = null, modifier = Modifier.padding(top = 4.dp, end = 12.dp))
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(stringResource(t.title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(t.desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (m == AutoIndexMode.IDLE && mode == AutoIndexMode.IDLE) IdlePresetChooser()
            if (m == AutoIndexMode.SCHEDULED && mode == AutoIndexMode.SCHEDULED) {
                Column(Modifier.padding(start = 36.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.auto_schedule_time, formatMinutes(minutes)), Modifier.weight(1f))
                        OutlinedButton(onClick = { showTime = true }) { Text(stringResource(R.string.auto_change_time)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.auto_charging_only), Modifier.weight(1f))
                        Switch(checked = chargingOnly, onCheckedChange = onChargingOnly)
                    }
                }
            }
        }
    }
    if (showTime) {
        TimeDialog(minutes, onDismiss = { showTime = false }) { onMinutes(it); showTime = false }
    }
    if (mode != null && mode != AutoIndexMode.MANUAL) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            LastAutoRunLine()
            // The system may delay automatic runs of apps that are battery-optimized.
            com.lsa.app.ui.settings.BackgroundAccessCards(showWhenAllSet = false, batteryOnly = true)
        }
    }
}

/** Fast / Normal / Relaxed: how long the phone must be unused before idle indexing, by battery level. */
@Composable
private fun IdlePresetChooser() {
    val settings = com.lsa.app.rememberContainer().settings
    val preset by settings.idlePreset.collectAsStateWithLifecycle(io.github.taehunkim.ondolsaem.sdk.IdlePreset.DEFAULT)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.padding(start = 36.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            io.github.taehunkim.ondolsaem.sdk.IdlePreset.entries.forEach { p ->
                androidx.compose.material3.FilterChip(
                    selected = preset == p,
                    onClick = { scope.launch { settings.setIdlePreset(p) } },
                    label = {
                        Text(stringResource(when (p) {
                            io.github.taehunkim.ondolsaem.sdk.IdlePreset.FAST -> R.string.idle_preset_fast
                            io.github.taehunkim.ondolsaem.sdk.IdlePreset.NORMAL -> R.string.idle_preset_normal
                            io.github.taehunkim.ondolsaem.sdk.IdlePreset.RELAXED -> R.string.idle_preset_relaxed
                        }))
                    },
                )
            }
        }
        val (high, mid, low) = preset.minutesOff
        Text(
            stringResource(R.string.idle_preset_rule, high, mid, low),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Last automatic run 10:32: indexed 2 files", or why it did nothing. Shows nothing before the first run. */
@Composable
fun LastAutoRunLine() {
    val run by com.lsa.app.rememberContainer().settings.lastAutoRun.collectAsStateWithLifecycle(null)
    val r = run ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val time = android.text.format.DateUtils.formatDateTime(ctx, r.at, android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_ALL or android.text.format.DateUtils.FORMAT_SHOW_DATE)
    val text = when (r.outcome) {
        com.lsa.app.indexing.AutoOutcome.INDEXED -> stringResource(R.string.auto_last_indexed, time, r.count)
        com.lsa.app.indexing.AutoOutcome.HOT -> stringResource(R.string.auto_last_hot, time)
        com.lsa.app.indexing.AutoOutcome.NO_MODEL -> stringResource(R.string.auto_last_no_model, time)
        com.lsa.app.indexing.AutoOutcome.LOAD_FAILED -> stringResource(R.string.auto_last_load_failed, time)
        com.lsa.app.indexing.AutoOutcome.NO_ACCESS -> stringResource(R.string.auto_last_no_access, time)
        com.lsa.app.indexing.AutoOutcome.NO_FOLDERS -> stringResource(R.string.auto_last_no_folders, time)
        com.lsa.app.indexing.AutoOutcome.ERROR -> stringResource(R.string.auto_last_error, time)
        else -> stringResource(R.string.auto_last_nothing, time)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun TimeDialog(initialMinutes: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val ctx = LocalContext.current
    val state = rememberTimePickerState(initialMinutes / 60, initialMinutes % 60, DateFormat.is24HourFormat(ctx))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.dialog_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        text = { TimePicker(state) },
    )
}
