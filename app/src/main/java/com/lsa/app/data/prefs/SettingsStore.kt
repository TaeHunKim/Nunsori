package com.lsa.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lsa.app.data.db.ItemKind
import com.lsa.app.indexing.AutoIndexMode
import com.lsa.app.indexing.ResidencyPolicy
import com.lsa.app.indexing.video.VideoDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val kEmbedding = stringPreferencesKey("embedding_model_id")
    private val kOcr = stringPreferencesKey("ocr_model_id")
    private val kToken = stringPreferencesKey("hf_token")
    private val kAutoMode = stringPreferencesKey("auto_index_mode")
    private val kScheduleMinutes = intPreferencesKey("schedule_minutes")
    private val kChargingOnly = booleanPreferencesKey("schedule_charging_only")
    private val kResidency = stringPreferencesKey("residency_policy")
    private val kOnboarding = booleanPreferencesKey("onboarding_done")
    private val kLastVersion = intPreferencesKey("last_version_code")
    private val kAsr = stringPreferencesKey("asr_model_id")
    private val kSkipMusic = booleanPreferencesKey("skip_music")
    private val kVideoDetail = stringPreferencesKey("video_detail")
    private val kVideoSpeech = booleanPreferencesKey("video_transcribe")
    private fun kindKey(k: ItemKind) = booleanPreferencesKey("kind_" + k.name.lowercase())

    val embeddingModelId: Flow<String> = context.dataStore.data.map { it[kEmbedding] ?: DEFAULT_EMBEDDING }
    val ocrModelId: Flow<String> = context.dataStore.data.map { it[kOcr] ?: DEFAULT_OCR }
    val asrModelId: Flow<String> = context.dataStore.data.map { it[kAsr] ?: DEFAULT_ASR }
    val skipMusic: Flow<Boolean> = context.dataStore.data.map { it[kSkipMusic] ?: true }
    val videoDetail: Flow<VideoDetail> = context.dataStore.data.map { parseEnum(it[kVideoDetail], VideoDetail.MEDIUM) }
    val videoTranscribe: Flow<Boolean> = context.dataStore.data.map { it[kVideoSpeech] ?: true }
    val hfToken: Flow<String> = context.dataStore.data.map { it[kToken] ?: "" }

    val autoMode: Flow<AutoIndexMode> = context.dataStore.data.map { parseEnum(it[kAutoMode], AutoIndexMode.MANUAL) }
    /** Minutes after local midnight. */
    val scheduleMinutes: Flow<Int> = context.dataStore.data.map { it[kScheduleMinutes] ?: DEFAULT_SCHEDULE_MINUTES }
    val scheduleChargingOnly: Flow<Boolean> = context.dataStore.data.map { it[kChargingOnly] ?: true }
    val residency: Flow<ResidencyPolicy> = context.dataStore.data.map { parseEnum(it[kResidency], ResidencyPolicy.IDLE_2M) }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[kOnboarding] ?: false }

    /** Which kinds of files are indexed. Images are on by default, everything else is opt-in. */
    val enabledKinds: Flow<Set<ItemKind>> = context.dataStore.data.map { p ->
        ItemKind.entries.filterTo(HashSet()) { p[kindKey(it)] ?: (it == ItemKind.IMAGE) }
    }
    suspend fun setKindEnabled(kind: ItemKind, enabled: Boolean) { context.dataStore.edit { it[kindKey(kind)] = enabled } }

    suspend fun setEmbeddingModelId(id: String) { context.dataStore.edit { it[kEmbedding] = id } }
    suspend fun setOcrModelId(id: String) { context.dataStore.edit { it[kOcr] = id } }
    suspend fun setAsrModelId(id: String) { context.dataStore.edit { it[kAsr] = id } }
    suspend fun setSkipMusic(v: Boolean) { context.dataStore.edit { it[kSkipMusic] = v } }
    suspend fun setVideoDetail(d: VideoDetail) { context.dataStore.edit { it[kVideoDetail] = d.name } }
    suspend fun setVideoTranscribe(v: Boolean) { context.dataStore.edit { it[kVideoSpeech] = v } }
    suspend fun setHfToken(token: String) { context.dataStore.edit { it[kToken] = token.trim() } }
    suspend fun setAutoMode(mode: AutoIndexMode) { context.dataStore.edit { it[kAutoMode] = mode.name } }
    suspend fun setScheduleMinutes(minutes: Int) { context.dataStore.edit { it[kScheduleMinutes] = minutes.coerceIn(0, 24 * 60 - 1) } }
    suspend fun setScheduleChargingOnly(v: Boolean) { context.dataStore.edit { it[kChargingOnly] = v } }
    suspend fun setResidency(p: ResidencyPolicy) { context.dataStore.edit { it[kResidency] = p.name } }

    /** Saves the first-run choice in one atomic edit. */
    suspend fun completeOnboarding(mode: AutoIndexMode, minutes: Int, chargingOnly: Boolean) {
        context.dataStore.edit {
            it[kAutoMode] = mode.name
            it[kScheduleMinutes] = minutes.coerceIn(0, 24 * 60 - 1)
            it[kChargingOnly] = chargingOnly
            it[kOnboarding] = true
        }
    }

    /** App version code the settings were last upgraded to; 0 on a fresh install. */
    suspend fun lastVersionCode(): Int = context.dataStore.data.first()[kLastVersion] ?: 0
    suspend fun setLastVersionCode(v: Int) { context.dataStore.edit { it[kLastVersion] = v } }

    /** Test hook: show the first-run dialog again. */
    suspend fun clearOnboardingForTest() { context.dataStore.edit { it.remove(kOnboarding) } }

    companion object {
        const val DEFAULT_EMBEDDING = "eg2-440m"
        const val DEFAULT_OCR = "mlkit-ko"
        const val DEFAULT_ASR = "asr-sensevoice"
        const val DEFAULT_SCHEDULE_MINUTES = 3 * 60

        inline fun <reified E : Enum<E>> parseEnum(raw: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == raw } ?: default
    }
}
