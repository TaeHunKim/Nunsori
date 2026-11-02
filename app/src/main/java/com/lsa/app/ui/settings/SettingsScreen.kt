package com.lsa.app.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lsa.app.R
import com.lsa.app.models.DownloadException
import com.lsa.app.models.ModelCatalog
import com.lsa.app.models.ModelKind
import com.lsa.app.models.ModelSource
import com.lsa.app.models.ModelSpec
import com.lsa.app.models.ModelState
import com.lsa.app.rememberContainer
import com.lsa.app.ui.common.displayFolder
import com.lsa.app.ui.index.hasPhotoPermission
import com.lsa.app.ui.onboarding.AutoIndexOptions
import com.lsa.app.indexing.ResidencyPolicy
import com.lsa.app.indexing.KindSupport
import com.lsa.app.data.db.ItemKind
import com.lsa.app.ui.common.WarningCard

@Composable
fun SettingsScreen() {
    val container = rememberContainer()
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container) } })

    // Both permissions can change while we are in the system settings screen: re-check when coming back.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        vm.refreshModels(); vm.refreshThumbBytes()
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(R.string.settings_tab_index, R.string.settings_tab_models, R.string.settings_tab_chat, R.string.settings_tab_app)
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label), maxLines = 1) }) }
        }
        // one scroll state per tab, so switching back keeps the position
        key(tab) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    0 -> IndexTab(vm)
                    1 -> ModelsTab(vm)
                    2 -> ChatTab(vm)
                    else -> AppTab(vm)
                }
            }
        }
    }
}

@Composable
private fun IndexTab(vm: SettingsViewModel) {
        KindsSection(vm)
        HorizontalDivider()

        FoldersSection(vm)
        HorizontalDivider()

        // --- Automatic indexing
        Section(R.string.auto_title, R.string.auto_desc)
        val autoMode by vm.autoMode.collectAsStateWithLifecycle()
        val minutes by vm.scheduleMinutes.collectAsStateWithLifecycle()
        val chargingOnly by vm.chargingOnly.collectAsStateWithLifecycle()
        AutoIndexOptions(autoMode, minutes, chargingOnly, vm::setAutoMode, vm::setScheduleMinutes, vm::setChargingOnly)
}

@Composable
private fun ModelsTab(vm: SettingsViewModel) {
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val ocrId by vm.ocrModelId.collectAsStateWithLifecycle()
    val savedToken by vm.hfToken.collectAsStateWithLifecycle()
        EmbeddingModelsSection(vm)
        Section(R.string.settings_ocr_models, null)
        ModelCatalog.ofKind(ModelKind.OCR).forEach { spec ->
            ModelCard(spec, states[spec.id], selected = spec.id == ocrId, vm = vm, onSelect = { vm.selectOcr(spec.id) })
        }
        SpeechModelsSection(vm)
        HorizontalDivider()

        // --- Chat model
        Section(R.string.settings_chat_models, R.string.settings_chat_desc)
        val chatId by vm.chatModelId.collectAsStateWithLifecycle()
        ModelCatalog.ofKind(ModelKind.LLM).forEach { spec ->
            ModelCard(spec, states[spec.id], selected = spec.id == chatId, vm = vm, onSelect = { vm.selectChat(spec.id) })
        }
        HorizontalDivider()

        // --- Model memory
        Section(R.string.residency_title, R.string.residency_desc)
        val residency by vm.residency.collectAsStateWithLifecycle()
        listOf(
            Triple(ResidencyPolicy.IDLE_2M, R.string.residency_idle, R.string.residency_idle_desc),
            Triple(ResidencyPolicy.KEEP, R.string.residency_keep, R.string.residency_keep_desc),
            Triple(ResidencyPolicy.IMMEDIATE, R.string.residency_immediate, R.string.residency_immediate_desc),
        ).forEach { (p, title, desc) ->
            Row(Modifier.fillMaxWidth().selectable(residency == p, role = Role.RadioButton) { vm.setResidency(p) }, verticalAlignment = Alignment.Top) {
                RadioButton(selected = residency == p, onClick = null, modifier = Modifier.padding(top = 4.dp, end = 12.dp))
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider()

        // --- HF token
        Section(R.string.settings_token, R.string.settings_token_desc)
        var token by remember(savedToken) { mutableStateOf(savedToken) }
        OutlinedTextField(
            value = token, onValueChange = { token = it }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            label = { Text("hf_…") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { vm.saveToken(token) }, enabled = token != savedToken) { Text(stringResource(R.string.btn_save)) }
}

@Composable
private fun ChatTab(vm: SettingsViewModel) {
        val chatContext by vm.chatContextTokens.collectAsStateWithLifecycle()
        Text(stringResource(R.string.chat_context_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.chat_context_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2048, 4096, 8192).forEach { n ->
                FilterChip(selected = chatContext == n, onClick = { vm.setChatContext(n) }, label = { Text("${n / 1024}K") })
            }
        }
        val savedPrompt by vm.chatCustomPrompt.collectAsStateWithLifecycle()
        var prompt by remember(savedPrompt) { mutableStateOf(savedPrompt) }
        Text(stringResource(R.string.chat_prompt_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.chat_prompt_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it.take(com.lsa.app.data.prefs.SettingsStore.MAX_CUSTOM_PROMPT) },
            minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.chat_prompt_hint)) },
            supportingText = { Text("${prompt.length} / ${com.lsa.app.data.prefs.SettingsStore.MAX_CUSTOM_PROMPT}") },
        )
        OutlinedButton(onClick = { vm.saveChatPrompt(prompt) }, enabled = prompt.trim() != savedPrompt) { Text(stringResource(R.string.btn_save)) }
        HorizontalDivider()

        // --- Scheduled tasks
        Section(R.string.bg_title, R.string.bg_desc)
        BackgroundAccessCards()
        Text(stringResource(R.string.task_file_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val judgeFirst by vm.taskJudgeFirst.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_judge_first_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.settings_judge_first_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = judgeFirst, onCheckedChange = vm::setTaskJudgeFirst)
        }
}

@Composable
private fun AppTab(vm: SettingsViewModel) {
    val ctx = LocalContext.current
        // --- Storage
        Section(R.string.storage_title, null)
        val thumbBytes by vm.thumbBytes.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.storage_previews, android.text.format.Formatter.formatShortFileSize(ctx, thumbBytes)), Modifier.weight(1f))
            OutlinedButton(onClick = { vm.clearThumbs() }, enabled = thumbBytes > 0) { Text(stringResource(R.string.storage_clear)) }
        }
        HorizontalDivider()

        // --- About
        Section(R.string.about_title, null)
        Text(stringResource(R.string.about_version, com.lsa.app.BuildConfig.VERSION_NAME, com.lsa.app.data.db.AppDatabase.VERSION))
        Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.about_places_credit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val showRes by vm.showResourceIndicator.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_resource_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.settings_resource_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = showRes, onCheckedChange = vm::setShowResourceIndicator)
        }
        HorizontalDivider()

        // --- Language
        Section(R.string.settings_language, null)
        LanguagePicker()
}

@Composable
internal fun Section(title: Int, desc: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        if (desc != null) Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ModelCard(spec: ModelSpec, state: ModelState?, selected: Boolean, vm: SettingsViewModel, onSelect: () -> Unit) {
    val ctx = LocalContext.current
    val installed = state is ModelState.Installed
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect, enabled = installed)
                Column(Modifier.weight(1f)) {
                    Text(spec.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            spec.sizeBytes?.let { Formatter.formatShortFileSize(ctx, it) },
                            spec.license,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(stringResource(spec.descriptionRes), style = MaterialTheme.typography.bodySmall)
            when (state) {
                is ModelState.Downloading -> {
                    val pct = if (state.total > 0) (state.downloaded * 100 / state.total).toInt() else 0
                    LinearProgressIndicator(
                        progress = { if (state.total > 0) state.downloaded.toFloat() / state.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.model_downloading, pct), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.cancelDownload(spec.id) }) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
                is ModelState.Failed -> {
                    Text(
                        stringResource(
                            when (state.kind) {
                                DownloadException.Kind.AUTH -> R.string.model_err_auth
                                DownloadException.Kind.NOT_FOUND -> R.string.model_err_not_found
                                DownloadException.Kind.NETWORK -> R.string.model_err_network
                                else -> R.string.model_err_http
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { vm.download(spec.id) }) { Text(stringResource(R.string.model_retry)) }
                }
                ModelState.Installed -> {
                    if (spec.source is ModelSource.HuggingFace || spec.source is ModelSource.Archive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.model_installed), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.deleteModel(spec.id) }) { Text(stringResource(R.string.model_delete)) }
                        }
                    } else Text(stringResource(R.string.model_bundled), style = MaterialTheme.typography.bodySmall)
                }
                ModelState.Unavailable -> Text(stringResource(R.string.model_coming_soon), style = MaterialTheme.typography.bodySmall)
                else -> Button(onClick = { vm.download(spec.id) }) { Text(stringResource(R.string.model_download)) }
            }
        }
    }
}

@Composable
private fun LanguagePicker() {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
    val options = listOf("" to R.string.lang_system, "en" to R.string.lang_en, "ko" to R.string.lang_ko)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (tag, label) ->
            FilterChip(
                selected = current == tag,
                onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}
