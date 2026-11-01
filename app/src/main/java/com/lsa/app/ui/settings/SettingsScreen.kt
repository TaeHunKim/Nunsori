package com.lsa.app.ui.settings

import android.Manifest
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

@Composable
fun SettingsScreen() {
    val container = rememberContainer()
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container) } })
    val ctx = LocalContext.current
    var permitted by remember { mutableStateOf(hasPhotoPermission(ctx)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = hasPhotoPermission(ctx)
        if (permitted) vm.refreshFolders()
    }
    LaunchedEffect(permitted) { if (permitted) vm.refreshFolders() }
    LaunchedEffect(Unit) { vm.refreshModels() }

    val folders by vm.folders.collectAsStateWithLifecycle()
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val embId by vm.embeddingModelId.collectAsStateWithLifecycle()
    val ocrId by vm.ocrModelId.collectAsStateWithLifecycle()
    val savedToken by vm.hfToken.collectAsStateWithLifecycle()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- Folders
        Section(R.string.settings_folders, R.string.settings_folders_desc)
        if (!permitted) {
            Button(onClick = {
                val perms = buildList {
                    add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
                    add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                }
                permLauncher.launch(perms.toTypedArray())
            }) { Text(stringResource(R.string.perm_grant)) }
        } else if (folders.isEmpty()) {
            Text(stringResource(R.string.folders_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            folders.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().selectable(row.enabled, role = Role.Checkbox) {
                        vm.setFolderEnabled(row.info.relativePath, !row.enabled)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = row.enabled, onCheckedChange = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(displayFolder(row.info.relativePath))
                        Text(
                            pluralStringResource(R.plurals.image_count, row.info.imageCount, row.info.imageCount),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        HorizontalDivider()

        // --- Models
        Section(R.string.settings_embedding_models, R.string.settings_embedding_desc)
        ModelCatalog.ofKind(ModelKind.EMBEDDING).forEach { spec ->
            ModelCard(spec, states[spec.id], selected = spec.id == embId, vm = vm, onSelect = { vm.selectEmbedding(spec.id) })
        }
        Section(R.string.settings_ocr_models, null)
        ModelCatalog.ofKind(ModelKind.OCR).forEach { spec ->
            ModelCard(spec, states[spec.id], selected = spec.id == ocrId, vm = vm, onSelect = { vm.selectOcr(spec.id) })
        }
        HorizontalDivider()

        // --- Automatic indexing
        Section(R.string.auto_title, R.string.auto_desc)
        val autoMode by vm.autoMode.collectAsStateWithLifecycle()
        val minutes by vm.scheduleMinutes.collectAsStateWithLifecycle()
        val chargingOnly by vm.chargingOnly.collectAsStateWithLifecycle()
        AutoIndexOptions(autoMode, minutes, chargingOnly, vm::setAutoMode, vm::setScheduleMinutes, vm::setChargingOnly)
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
        HorizontalDivider()

        // --- Language
        Section(R.string.settings_language, null)
        LanguagePicker()
    }
}

@Composable
private fun Section(title: Int, desc: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        if (desc != null) Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelCard(spec: ModelSpec, state: ModelState?, selected: Boolean, vm: SettingsViewModel, onSelect: () -> Unit) {
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
                    if (spec.source is ModelSource.HuggingFace) {
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
