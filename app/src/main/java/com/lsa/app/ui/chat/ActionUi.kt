package com.lsa.app.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lsa.app.R
import com.lsa.app.chat.actions.CalendarEventDraft
import com.lsa.app.chat.actions.ContactDraft
import com.lsa.app.data.db.ActionKind
import com.lsa.app.data.db.ActionStatus
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

/** The card a proposal of the model (calendar event, contact, ...) leaves in the chat: approve or dismiss it. */
@Composable
fun ActionCard(actionId: Long, vm: ChatViewModel) {
    val ctx = LocalContext.current
    val action by vm.action(actionId).collectAsStateWithLifecycle(null)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val a = action
            if (a == null) {
                Text(stringResource(R.string.action_gone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val payload = remember(a.payload) { runCatching { JSONObject(a.payload) }.getOrDefault(JSONObject()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Bolt, null, Modifier.size(18.dp))
                Text(stringResource(titleOf(a.kind)), style = MaterialTheme.typography.titleSmall)
            }
            when (a.kind) {
                ActionKind.CALENDAR_EVENT -> {
                    val e = remember(a.payload) { CalendarEventDraft.fromJson(payload) }
                    Text(e.title, style = MaterialTheme.typography.bodyMedium)
                    Text(eventWhen(e), style = MaterialTheme.typography.bodySmall)
                    if (e.location.isNotEmpty()) Text(e.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ActionKind.CONTACT -> {
                    val c = remember(a.payload) { ContactDraft.fromJson(payload) }
                    if (c.name.isNotEmpty()) Text(c.name, style = MaterialTheme.typography.bodyMedium)
                    listOf(c.organization, c.phone, c.email).filter { it.isNotEmpty() }.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ActionKind.MOVE_FILES, ActionKind.DELETE_FILES -> FileList(a.kind, payload)
            }
            if (a.kind == ActionKind.MOVE_FILES || a.kind == ActionKind.DELETE_FILES) {
                if (a.status == ActionStatus.PROPOSED && vm.needsFileAccess) {
                    Text(stringResource(R.string.action_needs_access), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { openAllFilesSettings(ctx) }) { Text(stringResource(R.string.action_grant_access)) }
                }
                if (a.status != ActionStatus.PROPOSED && a.resultNote?.contains("failed") == true) Text(a.resultNote, style = MaterialTheme.typography.labelSmall)
            }
            when (a.status) {
                ActionStatus.PROPOSED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val permanentFirst = a.kind == ActionKind.DELETE_FILES && payload.optBoolean("permanent")
                    Button(onClick = {
                        vm.approveAction(actionId, permanent = permanentFirst) { intent ->
                            try { ctx.startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
                        }
                    }) { Text(stringResource(approveLabel(a.kind, permanentFirst))) }
                    OutlinedButton(onClick = { vm.dismissAction(actionId) }) { Text(stringResource(R.string.action_dismiss)) }
                }
                ActionStatus.DONE -> Text(
                    stringResource(if (a.kind == ActionKind.CALENDAR_EVENT || a.kind == ActionKind.CONTACT) R.string.action_done else R.string.action_files_done),
                    style = MaterialTheme.typography.labelMedium,
                )
                ActionStatus.DISMISSED -> Text(stringResource(R.string.action_dismissed), style = MaterialTheme.typography.labelMedium)
                ActionStatus.FAILED -> Text(
                    stringResource(if (a.kind == ActionKind.CALENDAR_EVENT || a.kind == ActionKind.CONTACT) R.string.action_failed_no_app else R.string.action_files_failed),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                )
            }
            // delete: the permanent choice sits next to the trash button so it is always a deliberate second action
            if (a.kind == ActionKind.DELETE_FILES && a.status == ActionStatus.PROPOSED && !payload.optBoolean("permanent")) {
                TextButton(onClick = { vm.approveAction(actionId, permanent = true) }) {
                    Text(stringResource(R.string.action_delete_permanently), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun titleOf(kind: ActionKind) = when (kind) {
    ActionKind.CALENDAR_EVENT -> R.string.action_calendar_title
    ActionKind.CONTACT -> R.string.action_contact_title
    ActionKind.MOVE_FILES -> R.string.action_move_title
    ActionKind.DELETE_FILES -> R.string.action_delete_title
}

private fun approveLabel(kind: ActionKind, permanent: Boolean) = when (kind) {
    ActionKind.CALENDAR_EVENT -> R.string.action_approve_calendar
    ActionKind.CONTACT -> R.string.action_approve_contact
    ActionKind.MOVE_FILES -> R.string.action_approve_move
    ActionKind.DELETE_FILES -> if (permanent) R.string.action_delete_permanently else R.string.action_approve_trash
}

@Composable
private fun FileList(kind: ActionKind, payload: JSONObject) {
    val files = remember(payload.toString()) {
        payload.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }.orEmpty()
    }
    if (kind == ActionKind.MOVE_FILES) Text(stringResource(R.string.action_move_to, payload.optString("destination")), style = MaterialTheme.typography.bodyMedium)
    else Text(
        stringResource(if (payload.optBoolean("permanent")) R.string.action_delete_permanent_hint else R.string.action_delete_trash_hint, files.size),
        style = MaterialTheme.typography.bodyMedium,
    )
    files.take(MAX_LISTED).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
    if (files.size > MAX_LISTED) Text(stringResource(R.string.action_more_files, files.size - MAX_LISTED), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val MAX_LISTED = 8

private fun openAllFilesSettings(ctx: android.content.Context) {
    if (Build.VERSION.SDK_INT < 30) return
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
    try { ctx.startActivity(intent) } catch (_: ActivityNotFoundException) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

@Composable
private fun eventWhen(e: CalendarEventDraft): String {
    if (e.allDay) {
        // all-day events are stored as UTC midnights
        val day = DateFormat.getDateInstance(DateFormat.MEDIUM).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val first = day.format(Date(e.startMs)); val last = day.format(Date(e.endMs - 1))
        return stringResource(R.string.action_when_all_day) + " · " + if (first == last) first else "$first – $last"
    }
    val f = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return "${f.format(Date(e.startMs))} – ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.endMs))}"
}
