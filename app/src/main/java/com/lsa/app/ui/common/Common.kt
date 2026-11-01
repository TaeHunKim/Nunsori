@file:OptIn(ExperimentalMaterial3Api::class)

package com.lsa.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import android.widget.Toast
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lsa.app.R
import com.lsa.app.data.db.ImageEntity
import com.lsa.app.data.db.IndexStatus
import com.lsa.app.rememberContainer

@Composable
fun Thumb(uri: String, failed: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.then(Modifier), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = uri, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) {
            Icon(Icons.Filled.BrokenImage, null, tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun WarningCard(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Warning, null)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ImageDetailSheet(mediaId: Long, onDismiss: () -> Unit) {
    val container = rememberContainer()
    val ctx = LocalContext.current
    val entity by produceState<ImageEntity?>(null, mediaId) { value = container.db.imageDao().get(mediaId) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val e = entity ?: return@ModalBottomSheet
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Thumb(e.uri, e.status == IndexStatus.FAILED, Modifier.fillMaxWidth().heightIn(max = 280.dp))
            Text(e.displayName, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shareImage(ctx, Uri.parse(e.uri), e.mimeType) }) {
                    Icon(Icons.Filled.Share, null); Text(stringResource(R.string.action_share), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = { openImage(ctx, Uri.parse(e.uri), e.mimeType) }) {
                    Icon(Icons.Filled.OpenInNew, null); Text(stringResource(R.string.action_open_with), Modifier.padding(start = 8.dp))
                }
            }
            Meta(stringResource(R.string.detail_path), e.relativePath.ifEmpty { "/" })
            Meta(stringResource(R.string.detail_dimensions), "${e.width} × ${e.height}")
            Meta(stringResource(R.string.detail_size), Formatter.formatShortFileSize(ctx, e.sizeBytes))
            e.dateTaken?.let { Meta(stringResource(R.string.detail_taken), DateFormat.format("yyyy-MM-dd HH:mm", it).toString()) }
            if (e.latitude != null && e.longitude != null) {
                Meta(stringResource(R.string.detail_location), "%.5f, %.5f".format(e.latitude, e.longitude))
            }
            Meta(stringResource(R.string.detail_indexed), DateFormat.format("yyyy-MM-dd HH:mm", e.indexedAt).toString())
            Meta(stringResource(R.string.detail_model), e.embeddingModelId)
            e.error?.let { Meta(stringResource(R.string.detail_error), it) }
            Text(stringResource(R.string.detail_ocr_text), style = MaterialTheme.typography.titleSmall)
            Text(
                e.ocrText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.detail_no_text),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.65f))
    }
}

fun displayFolder(relativePath: String): String = relativePath.trimEnd('/').ifEmpty { "/" }

fun shareImage(ctx: Context, uri: Uri, mime: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime ?: "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri(null, uri)
    }
    launch(ctx, Intent.createChooser(send, null))
}

fun openImage(ctx: Context, uri: Uri, mime: String?) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime ?: "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    launch(ctx, Intent.createChooser(view, null))
}

private fun launch(ctx: Context, intent: Intent) {
    try { ctx.startActivity(intent) } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, R.string.no_app_found, Toast.LENGTH_SHORT).show()
    }
}

fun shareImages(ctx: Context, uris: List<Uri>) {
    if (uris.size == 1) return shareImage(ctx, uris[0], null)
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri(null, uris.first()).also { cd ->
            uris.drop(1).forEach { cd.addItem(android.content.ClipData.Item(it)) }
        }
    }
    launch(ctx, Intent.createChooser(send, null))
}
