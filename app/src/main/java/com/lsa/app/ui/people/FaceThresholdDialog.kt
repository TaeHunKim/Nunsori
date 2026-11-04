package com.lsa.app.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsa.app.R
import com.lsa.app.indexing.faces.FaceClusterer
import com.lsa.app.rememberContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Grouping: looser … stricter. Shows how many groups a threshold would make (from the stored face vectors) and, on
 * *Apply*, groups every face again with it; names, covers and "not this person" are kept.
 */
@Composable
fun FaceThresholdDialog(onDismiss: () -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val current by c.settings.faceThreshold.collectAsState(initial = null)
    val busy by c.faces.regrouping.collectAsState()
    var value by remember { mutableStateOf<Float?>(null) }
    var preview by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(current) { if (value == null) value = current }
    LaunchedEffect(value) {
        val v = value ?: return@LaunchedEffect
        preview = null
        delay(250) // the slider is still moving
        preview = runCatching { c.faces.previewGroups(v) }.getOrNull()
    }
    fun fmt(t: Float) = String.format(Locale.ROOT, "%.2f", t)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("face_threshold_dialog"),
        title = { Text(stringResource(R.string.face_threshold)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.face_threshold_desc), style = MaterialTheme.typography.bodyMedium)
                val v = value
                if (v != null) {
                    Text(stringResource(R.string.face_threshold_value, fmt(v), fmt(FaceClusterer.DEFAULT_THRESHOLD)), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = v, onValueChange = { value = FaceClusterer.clampThreshold(it) }, enabled = !busy,
                        valueRange = FaceClusterer.MIN_THRESHOLD..FaceClusterer.MAX_THRESHOLD,
                        steps = Math.round((FaceClusterer.MAX_THRESHOLD - FaceClusterer.MIN_THRESHOLD) * 100) - 1,
                        modifier = Modifier.testTag("face_threshold_slider"),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.face_threshold_looser), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.face_threshold_stricter), style = MaterialTheme.typography.bodySmall)
                    }
                }
                val p = preview
                Text(
                    when {
                        busy -> stringResource(R.string.face_threshold_applying)
                        p == null -> stringResource(R.string.face_threshold_counting)
                        else -> stringResource(R.string.face_threshold_preview, p.first, p.second)
                    },
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("face_threshold_preview"),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.face_threshold_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { value = FaceClusterer.DEFAULT_THRESHOLD }, enabled = !busy) { Text(stringResource(R.string.face_threshold_default)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val v = value ?: return@TextButton
                    // runs in the app's scope so that leaving the screen does not stop it halfway
                    scope.launch { c.appScope.launch { c.faces.regroupAll(v) }.join(); onDismiss() }
                },
                enabled = !busy && value != null,
                modifier = Modifier.testTag("face_threshold_apply"),
            ) { Text(stringResource(R.string.face_threshold_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}
