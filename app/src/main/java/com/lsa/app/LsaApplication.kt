package com.lsa.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import com.lsa.app.data.db.AppDatabase
import com.lsa.app.data.media.MediaStoreRepository
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.IndexingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.lsa.app.models.ModelState
import com.lsa.app.indexing.AutoIndexScheduler
import com.lsa.app.indexing.IndexRunner
import com.lsa.app.ml.ModelResidency
import com.lsa.app.models.ModelManager
import com.lsa.app.search.SearchEngine

class AppContainer(val context: Context) {
    /** Lives as long as the process; used for background housekeeping (model unloading, schedule updates). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy { Room.databaseBuilder(context, AppDatabase::class.java, "lsa.db").build() }
    val settings = SettingsStore(context)
    val modelManager = ModelManager(context, settings)
    val mediaRepo = MediaStoreRepository(context)
    val residency = ModelResidency(context, modelManager, settings, appScope)
    val indexRunner by lazy { IndexRunner(db.imageDao(), mediaRepo) }
    val indexing by lazy { IndexingController(context, db.imageDao(), indexRunner, settings) }
    val autoIndex = AutoIndexScheduler(
        context, settings, appScope,
        catchUpSignals = combine(
            db.imageDao().observeFolders().map { f -> f.filter { it.enabled }.map { it.relativePath }.toSet() },
            settings.embeddingModelId,
            modelManager.states.map { st -> st.filterValues { it is ModelState.Installed }.keys },
        ) { folders, model, installed -> Triple(folders, model, installed) },
    )
    val search by lazy { SearchEngine(db.imageDao(), residency, settings) }
}

class LsaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        container.autoIndex.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        android.util.Log.i("LSA.Residency", "onTrimMemory level=$level")
        if (level >= TRIM_MEMORY_BACKGROUND) container.appScope.launch { container.residency.trim() }
    }
}

@Composable
fun rememberContainer(): AppContainer {
    val ctx = LocalContext.current.applicationContext
    return remember { (ctx as LsaApplication).container }
}
