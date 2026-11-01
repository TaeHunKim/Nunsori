package com.lsa.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lsa.app.R
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.AutoIndexMode
import com.lsa.app.rememberContainer
import com.lsa.app.ui.settings.EmbeddingModelsSection
import com.lsa.app.ui.settings.FoldersSection
import com.lsa.app.ui.settings.KindsSection
import com.lsa.app.ui.settings.SettingsViewModel

/**
 * First run: what to index (kinds and folders), which model, and when to index automatically.
 * Everything chosen here lives in the same settings as the Settings screen and can be changed there.
 */
@Composable
fun OnboardingScreen(onDone: (AutoIndexMode, Int, Boolean) -> Unit) {
    val container = rememberContainer()
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container) } })
    var step by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf<AutoIndexMode?>(null) }
    var minutes by rememberSaveable { mutableIntStateOf(SettingsStore.DEFAULT_SCHEDULE_MINUTES) }
    var chargingOnly by rememberSaveable { mutableStateOf(true) }
    val last = STEPS - 1

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.onboarding_step, step + 1, STEPS),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(listOf(R.string.onboarding_files_title, R.string.onboarding_model_title, R.string.onboarding_title)[step]),
                style = MaterialTheme.typography.headlineSmall,
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    0 -> {
                        Text(stringResource(R.string.onboarding_files_desc), style = MaterialTheme.typography.bodyMedium)
                        KindsSection(vm)
                        FoldersSection(vm)
                    }
                    1 -> {
                        Text(stringResource(R.string.onboarding_model_desc), style = MaterialTheme.typography.bodyMedium)
                        EmbeddingModelsSection(vm)
                    }
                    else -> AutoIndexOptions(mode, minutes, chargingOnly, { mode = it }, { minutes = it }, { chargingOnly = it })
                }
            }
            Text(
                stringResource(R.string.onboarding_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { step-- }, enabled = step > 0) { Text(stringResource(R.string.onboarding_back)) }
                if (step < last) {
                    Button(onClick = { step++ }) { Text(stringResource(R.string.onboarding_next)) }
                } else {
                    Button(enabled = mode != null, onClick = { onDone(mode!!, minutes, chargingOnly) }) {
                        Text(stringResource(R.string.onboarding_finish))
                    }
                }
            }
        }
    }
}

private const val STEPS = 3
