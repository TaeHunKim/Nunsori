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

/** "What to index": file kinds and their options. Shared by Settings and the first-run wizard. */
@Composable
fun KindsSection(vm: SettingsViewModel) {
    val ctx = LocalContext.current
    var allFiles by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { allFiles = Environment.isExternalStorageManager() }
    val mediaPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val kinds by vm.kinds.collectAsStateWithLifecycle()
    val embId by vm.embeddingModelId.collectAsStateWithLifecycle()
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
}

/** Folders to index, with the permission prompts they need. */
@Composable
fun FoldersSection(vm: SettingsViewModel) {
    val ctx = LocalContext.current
    var photoPermitted by remember { mutableStateOf(hasPhotoPermission(ctx)) }
    var allFiles by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var showPicker by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        photoPermitted = hasPhotoPermission(ctx)
        allFiles = Environment.isExternalStorageManager()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        photoPermitted = hasPhotoPermission(ctx)
        if (photoPermitted) vm.refreshPhotoFolders()
    }
    LaunchedEffect(photoPermitted) { if (photoPermitted) vm.refreshPhotoFolders() }
    val folders by vm.folders.collectAsStateWithLifecycle()
    val photoFolders by vm.photoFolders.collectAsStateWithLifecycle()
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
        // One-tap presets for the usual places; the picker below still reaches any folder.
        val have = folders.map { it.relativePath }.toSet()
        val root = Environment.getExternalStorageDirectory()
        val presets = FOLDER_PRESETS.filter { pr ->
            pr.paths.any { it !in have } && (!allFiles || pr.paths.any { java.io.File(root, it).isDirectory })
        }
        if (presets.isNotEmpty()) {
            Text(stringResource(R.string.folder_presets), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { pr ->
                    androidx.compose.material3.AssistChip(
                        enabled = photoPermitted || allFiles,
                        onClick = {
                            pr.paths.filter { it !in have && (!allFiles || java.io.File(root, it).isDirectory) }
                                .forEach { vm.addFolder(it, pr.recursive) }
                        },
                        label = { Text(stringResource(pr.label)) },
                    )
                }
            }
            if (!allFiles) Text(stringResource(R.string.folder_presets_media_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { showPicker = true }, enabled = photoPermitted || allFiles) { Text(stringResource(R.string.folder_add)) }
        if (showPicker) {
            FolderPickerDialog(
                allFilesAccess = allFiles, photoFolders = photoFolders, selected = folders.map { it.relativePath }.toSet(),
                onAdd = { path, rec -> vm.addFolder(path, rec) }, onDismiss = { showPicker = false },
            )
        }
}

/** Embedding model choice with download buttons and the "re-create the index?" confirmation. */
@Composable
fun EmbeddingModelsSection(vm: SettingsViewModel) {
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val embId by vm.embeddingModelId.collectAsStateWithLifecycle()
        // --- Models
        Section(R.string.settings_embedding_models, R.string.settings_embedding_desc)
        ModelCatalog.ofKind(ModelKind.EMBEDDING).forEach { spec ->
            ModelCard(spec, states[spec.id], selected = spec.id == embId, vm = vm, onSelect = { vm.selectEmbedding(spec.id) })
        }
        val pendingSwitch by vm.pendingSwitch.collectAsStateWithLifecycle()
        pendingSwitch?.let { sw ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = vm::cancelSwitch,
                title = { Text(stringResource(R.string.model_switch_title)) },
                text = {
                    Text(pluralStringResource(R.plurals.model_switch_body, sw.files, sw.files, ModelCatalog.byId(sw.modelId)?.displayName ?: sw.modelId))
                },
                confirmButton = { TextButton(onClick = { vm.confirmSwitch() }) { Text(stringResource(R.string.model_switch_confirm)) } },
                dismissButton = { TextButton(onClick = vm::cancelSwitch) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
}

/** Usual places on a phone, as MediaStore-style relative paths. */
private class FolderPreset(val label: Int, val paths: List<String>, val recursive: Boolean)

private val FOLDER_PRESETS = listOf(
    FolderPreset(R.string.folder_preset_camera, listOf("DCIM/Camera/"), true),
    FolderPreset(R.string.folder_preset_screenshots, listOf("Pictures/Screenshots/", "DCIM/Screenshots/"), true),
    FolderPreset(R.string.folder_preset_downloads, listOf("Download/"), true),
)

/** Speech recognition model choice (audio and video transcripts); shared by Settings and the first-run wizard. */
@Composable
fun SpeechModelsSection(vm: SettingsViewModel) {
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val asrId by vm.asrModelId.collectAsStateWithLifecycle()
    Section(R.string.settings_asr_models, R.string.settings_asr_desc)
    ModelCatalog.ofKind(ModelKind.ASR).forEach { spec ->
        ModelCard(spec, states[spec.id], selected = spec.id == asrId, vm = vm, onSelect = { vm.selectAsr(spec.id) })
    }
}
