package com.lsa.app.ui.chat

import android.content.ActivityNotFoundException
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
                Text(
                    stringResource(if (a.kind == ActionKind.CALENDAR_EVENT) R.string.action_calendar_title else R.string.action_contact_title),
                    style = MaterialTheme.typography.titleSmall,
                )
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
            }
            when (a.status) {
                ActionStatus.PROPOSED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.approveAction(actionId) { intent ->
                            try { ctx.startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
                        }
                    }) {
                        Text(stringResource(if (a.kind == ActionKind.CALENDAR_EVENT) R.string.action_approve_calendar else R.string.action_approve_contact))
                    }
                    OutlinedButton(onClick = { vm.dismissAction(actionId) }) { Text(stringResource(R.string.action_dismiss)) }
                }
                ActionStatus.DONE -> Text(stringResource(R.string.action_done), style = MaterialTheme.typography.labelMedium)
                ActionStatus.DISMISSED -> Text(stringResource(R.string.action_dismissed), style = MaterialTheme.typography.labelMedium)
                ActionStatus.FAILED -> Text(stringResource(R.string.action_failed_no_app), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
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
