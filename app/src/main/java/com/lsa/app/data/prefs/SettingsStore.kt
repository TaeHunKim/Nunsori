package com.lsa.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lsa.app.data.db.ItemKind
import com.lsa.app.indexing.AutoIndexMode
import com.lsa.app.indexing.video.VideoDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val kEmbedding = stringPreferencesKey("embedding_model_id")
    private val kOcr = stringPreferencesKey("ocr_model_id")
    private val kAutoMode = stringPreferencesKey("auto_index_mode")
    private val kScheduleMinutes = intPreferencesKey("schedule_minutes")
    private val kChargingOnly = booleanPreferencesKey("schedule_charging_only")
    private val kOnboarding = booleanPreferencesKey("onboarding_done")
    private val kLastVersion = intPreferencesKey("last_version_code")
    private val kAsr = stringPreferencesKey("asr_model_id")
    private val kResourceUi = booleanPreferencesKey("show_resource_indicator")
    private val kLastAutoRun = stringPreferencesKey("last_auto_run")
    private val kSkipMusic = booleanPreferencesKey("skip_music")
    private val kVideoDetail = stringPreferencesKey("video_detail")
    private val kVideoSpeech = booleanPreferencesKey("video_transcribe")
    private val kFaceModel = stringPreferencesKey("face_model_id")
    private val kFaceGrouping = booleanPreferencesKey("face_grouping")
    private val kFaceThreshold = androidx.datastore.preferences.core.floatPreferencesKey("face_threshold")
    private val kFaceGroupingKey = stringPreferencesKey("face_grouping_key")
    private val kShowSmallGroups = booleanPreferencesKey("people_show_small")
    private val kApiApprovals = stringPreferencesKey("api_approvals")
    private fun kindKey(k: ItemKind) = booleanPreferencesKey("kind_" + k.name.lowercase())

    // The model ids below mirror OndolSaem's active models (see ModelHost.refresh); empty / "none" = not available.
    val embeddingModelId: Flow<String> = context.dataStore.data.map { it[kEmbedding] ?: DEFAULT_EMBEDDING }
    val ocrModelId: Flow<String> = context.dataStore.data.map { it[kOcr] ?: DEFAULT_OCR }
    /** Top-bar indicator with backend, memory and CPU use. */
    val lastAutoRun: Flow<com.lsa.app.indexing.LastAutoRun?> = context.dataStore.data.map { com.lsa.app.indexing.LastAutoRun.decode(it[kLastAutoRun]) }
    val showResourceIndicator: Flow<Boolean> = context.dataStore.data.map { it[kResourceUi] ?: false }
    val asrModelId: Flow<String> = context.dataStore.data.map { it[kAsr] ?: DEFAULT_ASR }
    /** OndolSaem's face model when it is downloaded (mirrored like the others); empty = none or OndolSaem too old. */
    val faceModelId: Flow<String> = context.dataStore.data.map { it[kFaceModel] ?: "" }
    suspend fun setFaceModelId(id: String) { context.dataStore.edit { it[kFaceModel] = id } }
    /** Find and group faces when indexing photos (on by default; turning it off deletes the face data). */
    val faceGrouping: Flow<Boolean> = context.dataStore.data.map { it[kFaceGrouping] ?: true }
    suspend fun setFaceGrouping(on: Boolean) { context.dataStore.edit { it[kFaceGrouping] = on } }
    /** Similarity at which two faces count as the same person (Grouping: looser … stricter). */
    val faceThreshold: Flow<Float> = context.dataStore.data.map {
        com.lsa.app.indexing.faces.FaceClusterer.clampThreshold(it[kFaceThreshold] ?: com.lsa.app.indexing.faces.FaceClusterer.DEFAULT_THRESHOLD)
    }
    suspend fun setFaceThreshold(t: Float) { context.dataStore.edit { it[kFaceThreshold] = t } }
    /** [com.lsa.app.indexing.faces.FaceClusterer.groupingKey] the stored groups were made with; empty = before 0.11.0. */
    val faceGroupingKey: Flow<String> = context.dataStore.data.map { it[kFaceGroupingKey] ?: "" }
    suspend fun setFaceGroupingKey(key: String) { context.dataStore.edit { it[kFaceGroupingKey] = key } }

    /** Other apps the user allowed or denied to use the index (service/Approvals, JSON). */
    val apiApprovalsJson: Flow<String> = context.dataStore.data.map { it[kApiApprovals] ?: "" }
    suspend fun setApiApprovalsJson(json: String) { context.dataStore.edit { it[kApiApprovals] = json } }
    /** People screen: also list groups with a single face. */
    val showSmallGroups: Flow<Boolean> = context.dataStore.data.map { it[kShowSmallGroups] ?: false }
    suspend fun setShowSmallGroups(on: Boolean) { context.dataStore.edit { it[kShowSmallGroups] = on } }
    val skipMusic: Flow<Boolean> = context.dataStore.data.map { it[kSkipMusic] ?: true }
    val videoDetail: Flow<VideoDetail> = context.dataStore.data.map { parseEnum(it[kVideoDetail], VideoDetail.MEDIUM) }
    val videoTranscribe: Flow<Boolean> = context.dataStore.data.map { it[kVideoSpeech] ?: true }

    val autoMode: Flow<AutoIndexMode> = context.dataStore.data.map { parseEnum(it[kAutoMode], AutoIndexMode.MANUAL) }
    /** Minutes after local midnight. */
    val scheduleMinutes: Flow<Int> = context.dataStore.data.map { it[kScheduleMinutes] ?: DEFAULT_SCHEDULE_MINUTES }
    val scheduleChargingOnly: Flow<Boolean> = context.dataStore.data.map { it[kChargingOnly] ?: true }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[kOnboarding] ?: false }

    /** Which kinds of files are indexed. Images are on by default, everything else is opt-in. */
    val enabledKinds: Flow<Set<ItemKind>> = context.dataStore.data.map { p ->
        ItemKind.entries.filterTo(HashSet()) { p[kindKey(it)] ?: (it == ItemKind.IMAGE) }
    }
    suspend fun setKindEnabled(kind: ItemKind, enabled: Boolean) { context.dataStore.edit { it[kindKey(kind)] = enabled } }

    suspend fun setEmbeddingModelId(id: String) { context.dataStore.edit { it[kEmbedding] = id } }
    suspend fun setOcrModelId(id: String) { context.dataStore.edit { it[kOcr] = id } }
    suspend fun setLastAutoRun(run: com.lsa.app.indexing.LastAutoRun) { context.dataStore.edit { it[kLastAutoRun] = run.encode() } }
    suspend fun setShowResourceIndicator(on: Boolean) { context.dataStore.edit { it[kResourceUi] = on } }
    suspend fun setAsrModelId(id: String) { context.dataStore.edit { it[kAsr] = id } }
    suspend fun setSkipMusic(v: Boolean) { context.dataStore.edit { it[kSkipMusic] = v } }
    suspend fun setVideoDetail(d: VideoDetail) { context.dataStore.edit { it[kVideoDetail] = d.name } }
    suspend fun setVideoTranscribe(v: Boolean) { context.dataStore.edit { it[kVideoSpeech] = v } }
    suspend fun setAutoMode(mode: AutoIndexMode) { context.dataStore.edit { it[kAutoMode] = mode.name } }
    suspend fun setScheduleMinutes(minutes: Int) { context.dataStore.edit { it[kScheduleMinutes] = minutes.coerceIn(0, 24 * 60 - 1) } }
    suspend fun setScheduleChargingOnly(v: Boolean) { context.dataStore.edit { it[kChargingOnly] = v } }
    /** 0.8.0: models moved to OndolSaem; drops the settings that went with them. */
    /** 0.8.1 removed the low-power indexing option (OndolSaem picks the backend now). */
    suspend fun removeLowPowerKey() { context.dataStore.edit { it.remove(booleanPreferencesKey("index_low_power")) } }

    suspend fun removeModelHostingKeys() {
        context.dataStore.edit { p ->
            listOf("hf_token", "residency_policy").forEach { p.remove(stringPreferencesKey(it)) }
            p.remove(intPreferencesKey("chat_context_tokens"))
        }
    }

    /** 0.10.0: the chat moved to the Malbotdari app; drops its settings. */
    suspend fun removeChatKeys() {
        context.dataStore.edit { p ->
            listOf("chat_model_id", "chat_custom_prompt").forEach { p.remove(stringPreferencesKey(it)) }
            p.remove(booleanPreferencesKey("task_judge_first"))
        }
    }

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
