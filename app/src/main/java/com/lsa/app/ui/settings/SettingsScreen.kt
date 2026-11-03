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
import androidx.compose.ui.platform.testTag
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
import com.lsa.app.rememberContainer
import com.lsa.app.ui.common.displayFolder
import com.lsa.app.ui.index.hasPhotoPermission
import com.lsa.app.ui.onboarding.AutoIndexOptions
import com.lsa.app.indexing.KindSupport
import com.lsa.app.data.db.ItemKind
import com.lsa.app.ui.common.WarningCard

@Composable
fun SettingsScreen(onOpenApps: () -> Unit = {}) {
    val container = rememberContainer()
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container) } })

    // Both permissions can change while we are in the system settings screen: re-check when coming back.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        vm.refreshThumbBytes()
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(R.string.settings_tab_index, R.string.settings_tab_models, R.string.settings_tab_chat, R.string.settings_tab_app, R.string.settings_tab_about)
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
                    3 -> AppTab(vm, onOpenApps)
                    else -> AboutTab()
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
    OndolSaemSection()
}

@Composable
private fun ChatTab(vm: SettingsViewModel) {
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
private fun AppTab(vm: SettingsViewModel, onOpenApps: () -> Unit) {
    val ctx = LocalContext.current
    // --- Other apps using the index
    Section(R.string.apps_title, R.string.apps_desc)
    OutlinedButton(onClick = onOpenApps, modifier = Modifier.testTag("open_apps")) { Text(stringResource(R.string.apps_open)) }
    HorizontalDivider()

    // --- Storage
    Section(R.string.storage_title, null)
    val thumbBytes by vm.thumbBytes.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.storage_previews, android.text.format.Formatter.formatShortFileSize(ctx, thumbBytes)), Modifier.weight(1f))
        OutlinedButton(onClick = { vm.clearThumbs() }, enabled = thumbBytes > 0) { Text(stringResource(R.string.storage_clear)) }
    }
    HorizontalDivider()

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
private fun AboutTab() {
    val ctx = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    Text(stringResource(R.string.app_name_full), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.about_version, com.lsa.app.BuildConfig.VERSION_NAME, com.lsa.app.data.db.AppDatabase.VERSION))
    HorizontalDivider()
    androidx.compose.material3.TextButton(onClick = {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_URL)))
    }) { Text(stringResource(R.string.about_github)) }
    Text(GITHUB_URL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    androidx.compose.material3.TextButton(onClick = { showLicenses = true }) { Text(stringResource(R.string.about_licenses)) }
    Text(stringResource(R.string.about_places_credit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (showLicenses) {
        val text = remember { runCatching { ctx.assets.open("licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLicenses = false },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.about_licenses_close)) } },
            title = { Text(stringResource(R.string.about_licenses)) },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        )
    }
}

private const val GITHUB_URL = "https://github.com/TaeHunKim/Nunsori"

@Composable
internal fun Section(title: Int, desc: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        if (desc != null) Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
