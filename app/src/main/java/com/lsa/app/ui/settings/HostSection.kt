package com.lsa.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lsa.app.R
import com.lsa.app.ml.HostState
import com.lsa.app.ml.ModelHost
import com.lsa.app.rememberContainer
import io.github.taehunkim.ondolsaem.sdk.OndolSaem.Categories
import kotlinx.coroutines.launch

/** Categories Nunsori uses, with what each is for. */
private val USED = listOf(
    Categories.EMBEDDING to R.string.host_cat_embedding,
    Categories.OCR to R.string.host_cat_ocr,
    Categories.ASR to R.string.host_cat_asr,
    Categories.LLM to R.string.host_cat_llm,
)

/**
 * The AI models live in the OndolSaem app: shows whether it is installed and reachable and which model serves each
 * category, with buttons to install or open it. Shared by Settings and the first-run wizard.
 */
@Composable
fun OndolSaemSection() {
    val container = rememberContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.models.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.models.refresh() }
    // the user may come back from OndolSaem after downloading a model
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { container.models.refresh() } }

    Section(R.string.host_title, R.string.host_desc)
    HostStatusCard(
        state,
        onInstall = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelHost.INSTALL_URL))) },
        onUpdateApp = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelHost.APP_RELEASES_URL))) },
        onOpen = { container.models.client.launchIntent()?.let(ctx::startActivity) },
        onApprove = { runCatching { ctx.startActivity(io.github.taehunkim.ondolsaem.sdk.OndolSaem.approvalIntent()) } },
        onCheck = { scope.launch { container.models.refresh() } },
    )
    Text(stringResource(R.string.host_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Connection state, active models and the matching action (install, update either app, open). Stateless for tests. */
@Composable
fun HostStatusCard(
    state: HostState, onInstall: () -> Unit, onUpdateApp: () -> Unit, onOpen: () -> Unit, onCheck: () -> Unit,
    onApprove: () -> Unit = onOpen,
) {
    Card(Modifier.fillMaxWidth().testTag("host_status")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val (msg, error) = when (state.status) {
                HostState.Status.UNKNOWN -> R.string.host_checking to false
                HostState.Status.NOT_INSTALLED -> R.string.host_not_installed to true
                HostState.Status.NOT_ALLOWED -> R.string.host_not_allowed to true
                HostState.Status.NOT_APPROVED -> R.string.host_not_approved to true
                HostState.Status.UNREACHABLE -> R.string.host_unreachable to true
                HostState.Status.CONNECTED -> R.string.host_connected to false
                HostState.Status.HOST_TOO_OLD -> R.string.host_too_old to true
                HostState.Status.CLIENT_TOO_OLD -> R.string.host_client_too_old to true
            }
            Text(
                stringResource(msg), style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (state.status == HostState.Status.CONNECTED && state.hostVersion != null) {
                Text(
                    stringResource(R.string.host_version, state.hostVersion!!, state.hostApi),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.models.isNotEmpty()) {
                USED.forEach { (cat, label) ->
                    val m = state.models[cat]
                    // fixed shares: a long model name must not squeeze the label to one letter per line
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(label), Modifier.weight(0.35f), style = MaterialTheme.typography.bodySmall)
                        Text(
                            when {
                                m == null || m.id.isEmpty() -> stringResource(R.string.host_model_none)
                                m.ready -> m.displayName
                                else -> stringResource(R.string.host_model_not_downloaded, m.displayName)
                            },
                            Modifier.weight(0.65f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            color = if (m?.ready == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.status) {
                    HostState.Status.NOT_INSTALLED, HostState.Status.HOST_TOO_OLD -> Button(
                        onClick = onInstall,
                    ) {
                        Text(stringResource(if (state.status == HostState.Status.NOT_INSTALLED) R.string.host_install else R.string.host_update))
                    }
                    HostState.Status.NOT_APPROVED -> Button(onClick = onApprove) { Text(stringResource(R.string.host_approve)) }
                    HostState.Status.CLIENT_TOO_OLD -> Button(
                        onClick = onUpdateApp,
                    ) { Text(stringResource(R.string.host_update_app)) }
                    else -> {
                    Button(onClick = onOpen) {
                        Text(stringResource(R.string.host_open))
                    }
                    }
                }
                OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.host_check)) }
            }
        }
    }
}
