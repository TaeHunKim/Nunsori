package com.lsa.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lsa.app.R
import com.lsa.app.rememberContainer
import com.lsa.app.service.Approval

/**
 * Apps that asked to use the index (search, read, move and delete with the user's confirmation in that app). Apps
 * signed with Nunsori's own key (Malbotdari) connect without asking and are not listed.
 */
@Composable
fun ConnectedAppsScreen() {
    val c = rememberContainer()
    val all by c.approvals.all.collectAsStateWithLifecycle()
    // load the stored decisions (the flow starts empty until the first lookup)
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.approvals.get("") } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.apps_desc), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.apps_own), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (all.isEmpty()) Text(stringResource(R.string.apps_none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("apps_none"))
        all.values.sortedByDescending { it.requestedAt }.forEach { a ->
            Card(Modifier.fillMaxWidth().testTag("app_${a.pkg}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.appLabel(a.pkg), style = MaterialTheme.typography.titleSmall)
                    Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(
                            when (a.state) {
                                Approval.State.PENDING -> R.string.apps_state_pending
                                Approval.State.ALLOWED -> R.string.apps_state_allowed
                                Approval.State.DENIED -> R.string.apps_state_denied
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (a.state == Approval.State.ALLOWED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (a.state != Approval.State.ALLOWED) Button(onClick = { c.approvals.decide(a.pkg, true) }, modifier = Modifier.testTag("allow_${a.pkg}")) { Text(stringResource(R.string.apps_allow)) }
                        if (a.state != Approval.State.DENIED) OutlinedButton(onClick = { c.approvals.decide(a.pkg, false) }) { Text(stringResource(R.string.apps_deny)) }
                        TextButton(onClick = { c.approvals.forget(a.pkg) }) { Text(stringResource(R.string.apps_forget)) }
                    }
                }
            }
        }
    }
}
