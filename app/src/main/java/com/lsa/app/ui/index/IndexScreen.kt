package com.lsa.app.ui.index

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lsa.app.R
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.indexing.IndexError
import com.lsa.app.rememberContainer
import com.lsa.app.ui.common.ItemDetailSheet
import com.lsa.app.ui.common.ItemThumb
import com.lsa.app.ui.common.WarningCard

/** Photos via the media permission, or any file via "all files access". */
fun hasAnyStorageAccess(ctx: android.content.Context): Boolean =
    hasPhotoPermission(ctx) || hasAllFilesAccess()

/** "All files access" (Android 11+); Android 10 has no such permission, so plain files are never readable there. */
fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()

fun hasPhotoPermission(ctx: android.content.Context): Boolean {
    val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun IndexScreen(onOpenSettings: () -> Unit) {
    val container = rememberContainer()
    val vm: IndexViewModel = viewModel(factory = viewModelFactory { initializer { IndexViewModel(container) } })
    val ctx = LocalContext.current

    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val folderCount by vm.enabledFolderCount.collectAsStateWithLifecycle()
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    val stale by vm.staleCount.collectAsStateWithLifecycle()
    val failed by vm.failedCount.collectAsStateWithLifecycle()
    var detailId by remember { mutableStateOf<Long?>(null) }
    var failedOnly by remember { mutableStateOf(false) }
    val shown = if (failedOnly) summaries.filter { it.status == ItemStatus.FAILED } else summaries
    if (failedOnly && failed == 0) failedOnly = false

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    fun onStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    val permitted = hasAnyStorageAccess(ctx)
    val warning: String? = when {
        !permitted -> stringResource(R.string.warn_no_permission)
        folderCount == 0 -> stringResource(R.string.warn_no_folders)
        !modelReady -> stringResource(R.string.warn_no_model)
        else -> null
    }
    val canStart = warning == null

    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                if (warning != null) WarningCard(warning, stringResource(R.string.go_settings), onOpenSettings)
                if (warning == null && stale > 0) {
                    WarningCard(pluralStringResource(R.plurals.warn_stale, stale, stale))
                }
                progress.error?.let {
                    WarningCard(
                        stringResource(
                            when (it) {
                                IndexError.MODEL_MISSING -> R.string.err_model_missing
                                IndexError.NO_FOLDERS -> R.string.err_no_folders
                                IndexError.LOAD_FAILED -> R.string.err_load_failed
                            },
                        ),
                    )
                }
                StatusCard(progress, running, canStart, onStart = ::onStart, onPause = vm::pause)
                val faces by vm.faceProgress.collectAsStateWithLifecycle()
                if (faces.running) {
                    Column(Modifier.fillMaxWidth().testTag("face_progress"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.people_scanning, faces.done, faces.total), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { if (faces.total > 0) faces.done.toFloat() / faces.total else 0f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                com.lsa.app.ui.onboarding.LastAutoRunLine()
                if (failed > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = failedOnly, onClick = { failedOnly = !failedOnly },
                            label = { Text(stringResource(R.string.index_filter_failed, failed)) },
                        )
                        if (failedOnly) {
                            OutlinedButton(onClick = vm::retryFailed, enabled = canStart && !running) {
                                Text(stringResource(R.string.index_retry_failed))
                            }
                        }
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.index_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(shown, key = { it.itemId }) { s ->
            ItemThumb(
                s.kind, s.uri, s.thumbPath, s.displayName, s.status == ItemStatus.FAILED,
                Modifier.aspectRatio(1f).clickable { detailId = s.itemId },
            )
        }
    }
    detailId?.let { ItemDetailSheet(it) { detailId = null } }
}

@Composable
private fun StatusCard(
    p: com.lsa.app.indexing.IndexProgress,
    running: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = when {
                running -> R.string.index_status_running
                p.finished -> R.string.index_status_done
                p.total > 0 && p.done < p.total -> R.string.index_status_paused
                else -> R.string.index_status_idle
            }
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            if (p.total > 0) {
                LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.index_progress, p.done, p.total), style = MaterialTheme.typography.bodyMedium)
                    if (p.failed > 0) {
                        Text(
                            pluralStringResource(R.plurals.index_failed_count, p.failed, p.failed),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (running && p.current != null) {
                Text(
                    stringResource(R.string.index_current, p.current),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running) {
                Button(onClick = onPause) {
                    Icon(Icons.Filled.Pause, null); Text(stringResource(R.string.btn_pause), Modifier.padding(start = 8.dp))
                }
            } else {
                Button(onClick = onStart, enabled = canStart) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text(
                        stringResource(if (p.total > 0 && !p.finished) R.string.btn_resume else R.string.btn_start),
                        Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
