package com.lsa.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lsa.app.R
import com.lsa.app.chat.schedule.TaskManager
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ScheduledTaskEntity
import com.lsa.app.data.db.TaskRepeat
import com.lsa.app.data.db.TaskResult
import com.lsa.app.data.db.TaskStatus
import com.lsa.app.data.db.TaskTrigger
import com.lsa.app.ui.settings.BackgroundAccessCards
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/** "Every day at 09:00", "When a new image file matching “x” is indexed", … in the UI language. */
@Composable
fun taskWhenText(t: ScheduledTaskEntity): String {
    val time = "%02d:%02d".format(t.atMinuteOfDay / 60, t.atMinuteOfDay % 60)
    val dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return when (t.trigger) {
        TaskTrigger.FILE -> {
            val kinds = TaskManager.parseKinds(t.fileKinds)
            val names = buildList {
                if (ItemKind.IMAGE in kinds) add(stringResource(R.string.task_kind_image))
                if (ItemKind.TEXT in kinds || ItemKind.DOC in kinds) add(stringResource(R.string.task_kind_document))
                if (ItemKind.AUDIO in kinds) add(stringResource(R.string.task_kind_audio))
                if (ItemKind.VIDEO in kinds) add(stringResource(R.string.task_kind_video))
            }.joinToString("/")
            when {
                t.fileQuery.isNotEmpty() -> stringResource(R.string.task_when_file_match, names, t.fileQuery)
                names.isNotEmpty() -> stringResource(R.string.task_when_file_kinds, names)
                else -> stringResource(R.string.task_when_file)
            }
        }
        TaskTrigger.TIME -> when (t.repeat) {
            TaskRepeat.ONCE -> stringResource(R.string.task_when_once, dateTime.format(Date(t.onceAtMs ?: 0)))
            TaskRepeat.EVERY_MINUTES -> stringResource(R.string.task_when_every, t.intervalMinutes)
            TaskRepeat.DAILY -> stringResource(R.string.task_when_daily, time)
            TaskRepeat.WEEKLY -> {
                val days = DayOfWeek.entries.filter { t.weekdayMask and (1 shl (it.value - 1)) != 0 }
                    .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
                stringResource(R.string.task_when_weekly, days, time)
            }
        }
    }
}

/** The card the model's schedule_task call leaves in the chat: confirm or dismiss it. */
@Composable
fun TaskCard(taskId: Long, vm: ChatViewModel) {
    val ctx = LocalContext.current
    val task by vm.task(taskId).collectAsStateWithLifecycle(null)
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.confirmTask(taskId) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp))
                Text(stringResource(R.string.task_card_title), style = MaterialTheme.typography.titleSmall)
            }
            val t = task
            if (t == null) {
                Text(stringResource(R.string.task_gone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(t.instruction, style = MaterialTheme.typography.bodyMedium)
            Text(taskWhenText(t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (t.trigger == TaskTrigger.FILE) {
                Text(stringResource(R.string.task_file_disclaimer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (t.status == TaskStatus.PENDING_CONFIRM) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val needsNotif = Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (needsNotif) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.confirmTask(taskId)
                    }) { Text(stringResource(R.string.task_confirm)) }
                    OutlinedButton(onClick = { vm.deleteTask(taskId) }) { Text(stringResource(R.string.task_dismiss)) }
                }
            } else {
                Text(
                    stringResource(R.string.task_confirmed, t.nextRunAt?.let { stringResource(R.string.task_next, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) }.orEmpty()).trim(),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** All scheduled tasks of this chat with pause/resume/delete, the file-task disclaimer and any missing permissions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.tasks_title), style = MaterialTheme.typography.titleLarge)
            if (tasks.isEmpty()) Text(stringResource(R.string.tasks_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            tasks.forEach { t ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t.instruction, style = MaterialTheme.typography.bodyMedium)
                        Text(taskWhenText(t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        when (t.status) {
                            TaskStatus.PAUSED -> Text(stringResource(R.string.task_status_paused), style = MaterialTheme.typography.labelMedium)
                            TaskStatus.DONE -> Text(stringResource(R.string.task_status_done), style = MaterialTheme.typography.labelMedium)
                            else -> t.nextRunAt?.let { Text(stringResource(R.string.task_next, fmt.format(Date(it))), style = MaterialTheme.typography.labelMedium) }
                        }
                        t.lastResult?.let {
                            val text = when (it) {
                                TaskResult.OK -> R.string.task_last_ok
                                TaskResult.FAILED -> R.string.task_last_failed
                                TaskResult.SKIPPED -> R.string.task_last_skipped
                            }
                            Text(stringResource(text) + (t.lastRunAt?.let { at -> " · " + fmt.format(Date(at)) } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row {
                            if (t.status == TaskStatus.ACTIVE || t.status == TaskStatus.PAUSED) {
                                TextButton(onClick = { vm.pauseTask(t.taskId, t.status == TaskStatus.ACTIVE) }) {
                                    Text(stringResource(if (t.status == TaskStatus.ACTIVE) R.string.task_pause else R.string.task_resume))
                                }
                            }
                            TextButton(onClick = { vm.deleteTask(t.taskId) }) { Text(stringResource(R.string.task_delete)) }
                        }
                    }
                }
            }
            if (tasks.any { it.trigger == TaskTrigger.FILE }) {
                Text(stringResource(R.string.task_file_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BackgroundAccessCards(showWhenAllSet = false)
        }
    }
}
