package com.lsa.app

import android.util.Log
import com.lsa.app.data.db.ItemDao
import com.lsa.app.data.prefs.SettingsStore

/**
 * Runs once per app update, before anything depends on new settings or file layouts.
 * Database schema changes belong in Room migrations (data/db/Migrations.kt); derived data is rebuilt through
 * `pipelineKey`. This is for the rest: renamed or reinterpreted settings keys, changed cache layouts, ...
 *
 * Add steps as `if (from < <versionCode of the release that needs it>) { ... }`; versionCode = major*10000 + minor*100 + patch.
 */
object AppUpgrade {
    private const val TAG = "LSA.Upgrade"

    suspend fun run(context: android.content.Context, settings: SettingsStore, dao: ItemDao, current: Int) {
        val from = settings.lastVersionCode()
        if (from >= current) return
        Log.i(TAG, "upgrading from $from to $current")
        // 0.2.0: place names gained aliases (countries, regions); look them up again for photos indexed earlier.
        if (from < 200) dao.resetPlaces()
        // 0.8.0: models are downloaded and run by the OndolSaem app; delete the copies this app downloaded
        // (up to several GB) and the settings that went with them. Model ids stay the same, so the index stays valid.
        if (from in 1 until 800) {
            java.io.File(context.filesDir, "models").deleteRecursively()
            java.io.File(context.cacheDir, "litertlm").deleteRecursively()
            settings.removeModelHostingKeys()
        }
        // 0.8.1: the low-power indexing switch is gone; drop its stored value.
        if (from in 1 until 801) settings.removeLowPowerKey()
        settings.setLastVersionCode(current)
    }
}
