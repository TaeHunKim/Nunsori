package com.lsa.app.ui.people

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.lsa.app.R
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.indexing.faces.FaceAvailability
import com.lsa.app.rememberContainer
import com.lsa.app.ui.common.ItemThumb
import com.lsa.app.ui.common.openFile
import kotlinx.coroutines.launch
import java.io.File

@Composable
private fun peopleViewModel(): PeopleViewModel {
    val container = rememberContainer()
    return viewModel(factory = viewModelFactory { initializer { PeopleViewModel(container) } })
}

/** Round face crop, or a person icon when there is none. */
@Composable
fun FaceAvatar(file: File?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) AsyncImage(file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Groups of faces found in the photos; tap one to see that person's photos. */
@Composable
fun PeopleScreen(onOpen: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val vm = peopleViewModel()
    val people by vm.people.collectAsState()
    val availability by vm.availability.collectAsState()
    val progress by vm.progress.collectAsState()
    val showSmall by vm.showSmall.collectAsState()
    val hidden by vm.hiddenCount.collectAsState()

    LazyVerticalGrid(
        GridCells.Adaptive(104.dp), Modifier.fillMaxSize().testTag("people"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (availability) {
                    FaceAvailability.OFF -> StatusCard(stringResource(R.string.people_off), stringResource(R.string.people_open_settings), onOpenSettings)
                    FaceAvailability.HOST_TOO_OLD -> StatusCard(stringResource(R.string.people_host_too_old), null, null)
                    FaceAvailability.NO_MODEL -> StatusCard(stringResource(R.string.people_no_model), null, null)
                    else -> {}
                }
                if (progress.running) {
                    Text(stringResource(R.string.people_scanning, progress.done, progress.total), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f }, Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (hidden > 0) pluralStringResource(R.plurals.people_small_hidden, hidden, hidden) else stringResource(R.string.people_show_small),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(showSmall, vm::setShowSmall)
                }
            }
        }
        val list = people
        if (list != null && list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                Text(stringResource(R.string.people_empty), style = MaterialTheme.typography.bodyMedium)
                if (availability == FaceAvailability.READY) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = vm::startIndexing) { Text(stringResource(R.string.btn_start)) }
                }
            }
        }
        items(list.orEmpty(), key = { it.personId }) { p ->
            Column(
                Modifier.clickable { onOpen(p.personId) }.testTag("person_${p.personId}"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FaceAvatar(vm.thumbFile(p.thumbPath), 88.dp)
                Spacer(Modifier.height(4.dp))
                Text(
                    p.name ?: stringResource(R.string.people_unnamed), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (p.name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(pluralStringResource(R.plurals.people_photos, p.photoCount, p.photoCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusCard(text: String, action: String?, onAction: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null && onAction != null) OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

/** One person: name (rename, merge by giving an existing name), faces (cover, not this person), photos. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonScreen(id: Long, onOpenPerson: (Long) -> Unit, onGone: () -> Unit) {
    val vm = peopleViewModel()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val person by remember(id) { vm.person(id) }.collectAsState(initial = null)
    val photos by remember(id) { vm.photos(id) }.collectAsState(initial = null)
    val faces by remember(id) { vm.faces(id) }.collectAsState(initial = emptyList())
    var name by rememberSaveable(person?.name) { mutableStateOf(person?.name.orEmpty()) }
    var mergeTarget by remember { mutableStateOf<com.lsa.app.data.db.PersonEntity?>(null) }
    var choosingMerge by remember { mutableStateOf(false) }
    var faceMenu by remember { mutableStateOf<Long?>(null) }

    // merged away or emptied ("not this person" on its last face)
    if (photos != null && photos!!.isEmpty() && faces.isEmpty()) { androidx.compose.runtime.LaunchedEffect(Unit) { onGone() }; return }

    LazyVerticalGrid(
        GridCells.Adaptive(104.dp), Modifier.fillMaxSize().testTag("person"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FaceAvatar(faces.firstOrNull { it.faceId == person?.coverFaceId }?.thumbPath?.let(vm::thumbFile) ?: faces.firstOrNull()?.thumbPath?.let(vm::thumbFile), 64.dp)
                    OutlinedTextField(
                        name, { name = it }, Modifier.weight(1f).testTag("person_name"), singleLine = true,
                        label = { Text(stringResource(R.string.people_name)) },
                        placeholder = { Text(stringResource(R.string.people_unnamed)) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = name.trim() != person?.name.orEmpty(),
                        onClick = {
                            scope.launch {
                                val same = if (name.isBlank()) null else vm.sameName(id, name)
                                if (same != null) mergeTarget = same else vm.rename(id, name)
                            }
                        },
                    ) { Text(stringResource(R.string.btn_save)) }
                    OutlinedButton(onClick = { choosingMerge = true }) { Text(stringResource(R.string.people_merge)) }
                }
                Text(stringResource(R.string.people_faces_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(faces, key = { it.faceId }) { f ->
                        Box {
                            FaceAvatar(vm.thumbFile(f.thumbPath), 52.dp, Modifier.combinedClickable(onClick = { faceMenu = f.faceId }, onLongClick = { faceMenu = f.faceId }))
                            DropdownMenu(expanded = faceMenu == f.faceId, onDismissRequest = { faceMenu = null }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.people_set_cover)) }, onClick = { vm.setCover(id, f.faceId); faceMenu = null })
                                DropdownMenuItem(text = { Text(stringResource(R.string.people_not_this_person)) }, onClick = { vm.reject(f.faceId); faceMenu = null })
                            }
                        }
                    }
                }
                Text(pluralStringResource(R.plurals.people_photos, photos?.size ?: 0, photos?.size ?: 0), style = MaterialTheme.typography.titleSmall)
            }
        }
        items(photos.orEmpty(), key = { it.itemId }) { p ->
            ItemThumb(
                p.kind, p.uri, null, p.displayName, p.status == ItemStatus.FAILED,
                Modifier.aspectRatio(1f).clickable { openFile(ctx, Uri.parse(p.uri), p.displayName) },
            )
        }
    }

    mergeTarget?.let { other ->
        AlertDialog(
            onDismissRequest = { mergeTarget = null },
            title = { Text(stringResource(R.string.people_merge_same_name_title, other.name.orEmpty())) },
            text = { Text(stringResource(R.string.people_merge_same_name_text)) },
            confirmButton = { TextButton(onClick = { mergeTarget = null; vm.merge(id, other.personId) { onOpenPerson(other.personId) } }) { Text(stringResource(R.string.people_merge)) } },
            dismissButton = { TextButton(onClick = { mergeTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (choosingMerge) {
        val others by remember(id) { vm.otherPeople(id) }.collectAsState(initial = emptyList())
        AlertDialog(
            onDismissRequest = { choosingMerge = false },
            title = { Text(stringResource(R.string.people_merge_pick)) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.height(360.dp)) {
                    items(others, key = { it.personId }) { o ->
                        Row(
                            Modifier.fillMaxWidth().clickable { choosingMerge = false; vm.merge(id, o.personId) { onOpenPerson(o.personId) } }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FaceAvatar(vm.thumbFile(o.thumbPath), 40.dp)
                            Column {
                                Text(o.name ?: stringResource(R.string.people_unnamed))
                                Text(pluralStringResource(R.plurals.people_photos, o.photoCount, o.photoCount), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingMerge = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
