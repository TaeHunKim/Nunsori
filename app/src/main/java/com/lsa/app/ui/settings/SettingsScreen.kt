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
import androidx.compose.material3.Switch
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
import com.lsa.app.indexing.KindSupport
import com.lsa.app.data.db.ItemKind
import com.lsa.app.ui.common.WarningCard

@Composable
fun SettingsScreen() {
    val container = rememberContainer()
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container) } })
    val ctx = LocalContext.current
    var photoPermitted by remember { mutableStateOf(hasPhotoPermission(ctx)) }
    var allFiles by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var showPicker by remember { mutableStateOf(false) }

    // Both permissions can change while we are in the system settings screen: re-check when coming back.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        photoPermitted = hasPhotoPermission(ctx)
        allFiles = Environment.isExternalStorageManager()
        vm.refreshModels(); vm.refreshThumbBytes()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        photoPermitted = hasPhotoPermission(ctx)
        if (photoPermitted) vm.refreshPhotoFolders()
    }
    LaunchedEffect(photoPermitted) { if (photoPermitted) vm.refreshPhotoFolders() }

    val mediaPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val folders by vm.folders.collectAsStateWithLifecycle()
    val photoFolders by vm.photoFolders.collectAsStateWithLifecycle()
    val kinds by vm.kinds.collectAsStateWithLifecycle()
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val embId by vm.embeddingModelId.collectAsStateWithLifecycle()
    val ocrId by vm.ocrModelId.collectAsStateWithLifecycle()
    val savedToken by vm.hfToken.collectAsStateWithLifecycle()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- What to index
        Section(R.string.kinds_title, R.string.kinds_desc)
        listOf(
            ItemKind.IMAGE to R.string.kind_images, ItemKind.TEXT to R.string.kind_text, ItemKind.DOC to R.string.kind_docs,
            ItemKind.AUDIO to R.string.kind_audio, ItemKind.VIDEO to R.string.kind_video,
        ).forEach { (kind, label) ->
            val supported = kind in KindSupport.implemented
            Row(
                Modifier.fillMaxWidth().selectable(kind in kinds && supported, enabled = supported, role = Role.Checkbox) {
                    // media types need their own runtime permission on Android 13+
                    if (kind !in kinds && Build.VERSION.SDK_INT >= 33) {
                        when (kind) {
                            ItemKind.AUDIO -> mediaPermLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                            ItemKind.VIDEO -> mediaPermLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
                            else -> Unit
                        }
                    }
                    vm.setKind(kind, kind !in kinds)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = kind in kinds && supported, onCheckedChange = null, enabled = supported)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(stringResource(label), color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!supported) Text(stringResource(R.string.kind_coming_soon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (ItemKind.AUDIO in kinds) {
            val skipMusic by vm.skipMusic.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth().padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.skip_music))
                    Text(stringResource(R.string.skip_music_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = skipMusic, onCheckedChange = vm::setSkipMusic)
            }
            if (ModelCatalog.byId(embId)?.audio != true) WarningCard(stringResource(R.string.audio_needs_740m))
        }
        if (ItemKind.VIDEO in kinds) {
            val detail by vm.videoDetail.collectAsStateWithLifecycle()
            val speech by vm.videoTranscribe.collectAsStateWithLifecycle()
            Column(Modifier.padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.video_detail_title), style = MaterialTheme.typography.titleSmall)
                listOf(
                    com.lsa.app.indexing.video.VideoDetail.LOW to R.string.video_detail_low,
                    com.lsa.app.indexing.video.VideoDetail.MEDIUM to R.string.video_detail_medium,
                    com.lsa.app.indexing.video.VideoDetail.HIGH to R.string.video_detail_high,
                ).forEach { (d, label) ->
                    Row(Modifier.fillMaxWidth().selectable(detail == d, role = Role.RadioButton) { vm.setVideoDetail(d) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = detail == d, onClick = null, modifier = Modifier.padding(end = 12.dp))
                        Text(stringResource(label))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.video_speech))
                        Text(stringResource(R.string.video_speech_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = speech, onCheckedChange = vm::setVideoTranscribe)
                }
            }
        }
        val needsFiles = kinds.any { it != ItemKind.IMAGE && it in KindSupport.implemented && it != ItemKind.AUDIO && it != ItemKind.VIDEO }
        if (needsFiles && !allFiles) {
            WarningCard(stringResource(R.string.perm_all_files), stringResource(R.string.perm_all_files_grant)) {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
                )
            }
        }
        HorizontalDivider()

        // --- Folders
        Section(R.string.settings_folders, R.string.settings_folders_desc)
        if (!photoPermitted && !allFiles) {
            Button(onClick = {
                val perms = buildList {
                    add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
                    add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                }
                permLauncher.launch(perms.toTypedArray())
            }) { Text(stringResource(R.string.perm_grant)) }
        }
        if (folders.isEmpty()) {
            Text(stringResource(R.string.folder_none_selected), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        folders.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(displayFolder(row.relativePath))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = row.recursive, onCheckedChange = { vm.setRecursive(row.relativePath, it) }, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.folder_include_sub), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { vm.removeFolder(row) }) { Text(stringResource(R.string.folder_remove)) }
            }
        }
        OutlinedButton(onClick = { showPicker = true }, enabled = photoPermitted || allFiles) { Text(stringResource(R.string.folder_add)) }
        if (showPicker) {
            FolderPickerDialog(
                allFilesAccess = allFiles, photoFolders = photoFolders, selected = folders.map { it.relativePath }.toSet(),
                onAdd = { path, rec -> vm.addFolder(path, rec) }, onDismiss = { showPicker = false },
            )
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
        Section(R.string.settings_asr_models, R.string.settings_asr_desc)
        val asrId by vm.asrModelId.collectAsStateWithLifecycle()
        ModelCatalog.ofKind(ModelKind.ASR).forEach { spec ->
            ModelCard(spec, states[spec.id], selected = spec.id == asrId, vm = vm, onSelect = { vm.selectAsr(spec.id) })
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

        // --- Storage
        Section(R.string.storage_title, null)
        val thumbBytes by vm.thumbBytes.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.storage_previews, android.text.format.Formatter.formatShortFileSize(ctx, thumbBytes)), Modifier.weight(1f))
            OutlinedButton(onClick = { vm.clearThumbs() }, enabled = thumbBytes > 0) { Text(stringResource(R.string.storage_clear)) }
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
