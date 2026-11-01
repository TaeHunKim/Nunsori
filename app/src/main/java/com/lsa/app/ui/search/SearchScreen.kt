package com.lsa.app.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.core.net.toUri
import com.lsa.app.ui.common.shareImages
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lsa.app.R
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.rememberContainer
import com.lsa.app.ui.common.ItemDetailSheet
import com.lsa.app.ui.common.ItemThumb
import com.lsa.app.ui.common.locationLabel
import com.lsa.app.ui.common.WarningCard

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(onOpenSettings: () -> Unit) {
    val container = rememberContainer()
    val vm: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var detailId by remember { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val ctx = LocalContext.current
    val selecting = selected.isNotEmpty()
    // New results invalidate the selection.
    LaunchedEffect(state) { selected = emptySet() }
    BackHandler(enabled = selecting) { selected = emptySet() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = vm.query, onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            trailingIcon = {
                IconButton(onClick = vm::search) { Icon(Icons.Filled.Search, stringResource(R.string.nav_search)) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
        )
        when (val s = state) {
            SearchUi.Idle -> Hint(stringResource(R.string.search_idle))
            SearchUi.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is SearchUi.Failed -> WarningCard(s.message)
            is SearchUi.Done -> {
                if (selecting) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.action_cancel))
                        }
                        Text(
                            pluralStringResource(R.plurals.selection_count, selected.size, selected.size),
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { selected = s.hits.map { it.hit.itemId }.toSet() }) {
                            Text(stringResource(R.string.select_all))
                        }
                        androidx.compose.material3.Button(onClick = {
                            shareImages(ctx, s.hits.filter { it.hit.itemId in selected }.map { it.summary.uri.toUri() })
                        }) {
                            Icon(Icons.Filled.Share, null)
                            Text(stringResource(R.string.action_share), Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (!s.semanticAvailable) {
                    WarningCard(stringResource(R.string.search_model_missing), stringResource(R.string.go_settings), onOpenSettings)
                }
                if (s.hits.isEmpty()) Hint(stringResource(R.string.search_no_results))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(s.hits, key = { it.hit.itemId }) { r ->
                        val id = r.hit.itemId
                        val isSel = id in selected
                        fun toggle() { selected = if (isSel) selected - id else selected + id }
                        Card(
                            Modifier.combinedClickable(
                                onClick = { if (selecting) toggle() else detailId = id },
                                onLongClick = { toggle() },
                            ),
                            border = if (isSel) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                        ) {
                            Box {
                                ItemThumb(r.summary.kind, r.summary.uri, r.thumbPath, r.summary.displayName, r.summary.status == ItemStatus.FAILED, Modifier.fillMaxWidth().aspectRatio(1f))
                                if (isSel) {
                                    Icon(
                                        Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                                    )
                                }
                            }
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    r.hit.visualSim?.let { Badge(stringResource(R.string.badge_image), "%.2f".format(it)) }
                                    r.hit.textSim?.let { Badge(stringResource(R.string.badge_ocr), "%.2f".format(it)) }
                                    r.hit.audioSim?.let { Badge(stringResource(R.string.badge_audio), "%.2f".format(it)) }
                                    if (r.hit.keywordMatches > 0) Badge(stringResource(R.string.badge_keyword), null)
                                }
                                Text(
                                    listOfNotNull(r.summary.displayName, locationLabel(r.hit.best?.page, r.hit.best?.startMs)).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                )
                                r.snippet?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    detailId?.let { ItemDetailSheet(it) { detailId = null } }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}

@Composable
private fun Badge(label: String, value: String?) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            if (value == null) label else "$label $value",
            style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
