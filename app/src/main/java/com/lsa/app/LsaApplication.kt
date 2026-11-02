package com.lsa.app

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import com.lsa.app.data.db.AppDatabase
import com.lsa.app.data.db.ALL_MIGRATIONS
import com.lsa.app.data.media.CompositeSource
import com.lsa.app.data.media.FileSource
import com.lsa.app.data.media.MediaStoreRepository
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.IndexingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.lsa.app.models.ModelState
import com.lsa.app.indexing.AutoIndexScheduler
import com.lsa.app.indexing.docs.ThumbStore
import com.lsa.app.indexing.IndexRunner
import com.lsa.app.indexing.places.PlaceFinder
import com.lsa.app.ml.ModelResidency
import com.lsa.app.models.ModelManager
import com.lsa.app.search.SearchEngine

class AppContainer(val context: Context) {
    /** Lives as long as the process; used for background housekeeping (model unloading, schedule updates). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy { Room.databaseBuilder(context, AppDatabase::class.java, "lsa.db").addMigrations(*ALL_MIGRATIONS)
            // The index is derived data: after a downgrade (older build installed over a newer one) rebuilding beats crashing.
            .fallbackToDestructiveMigrationOnDowngrade(true).build() }
    val settings = SettingsStore(context)
    val modelManager = ModelManager(context, settings)
    val mediaRepo = MediaStoreRepository(context).also { repo -> appScope.launch { settings.skipMusic.collect { repo.skipMusic = it } } }
    val thumbs = ThumbStore(context)
    val residency = ModelResidency(context, modelManager, settings, appScope)
    private val source = CompositeSource(
        listOf(
            mediaRepo,
            // Plain files (documents, text) need "all files access"; without it this source simply lists nothing.
            FileSource(Environment.getExternalStorageDirectory()) { Environment.isExternalStorageManager() },
        ),
    )
    val places by lazy { PlaceFinder.fromAssets(context) }
    val indexRunner by lazy { IndexRunner(db.itemDao(), source, places = places) }
    val indexing by lazy { IndexingController(context, db.itemDao(), indexRunner, settings, modelManager) }
    val autoIndex = AutoIndexScheduler(
        context, settings, appScope,
        catchUpSignals = combine(
            db.itemDao().observeFolders().map { f -> f.filter { it.enabled }.map { it.relativePath }.toSet() },
            settings.embeddingModelId,
            settings.enabledKinds,
            modelManager.states.map { st -> st.filterValues { it is ModelState.Installed }.keys },
        ) { folders, model, kinds, installed -> listOf(folders, model, kinds, installed) },
    )
    val taskAlarms by lazy { com.lsa.app.chat.schedule.TaskAlarms(context) }
    val taskManager: com.lsa.app.chat.schedule.TaskManager by lazy {
        com.lsa.app.chat.schedule.TaskManager(context, db.taskDao(), db.chatDao(), db.itemDao(), search, taskAlarms, { chat }, judgeFirst = { settings.taskJudgeFirst.first() })
    }
    val actions: com.lsa.app.chat.actions.ActionManager by lazy { com.lsa.app.chat.actions.ActionManager(db.actionDao(), fileOps = fileOps) }
    val fileOps: com.lsa.app.chat.actions.FileOps by lazy { com.lsa.app.chat.actions.FileOps(context, db.itemDao(), folders = { indexing.folderSpecs() }) }
    val chat: com.lsa.app.chat.ChatController by lazy { com.lsa.app.chat.ChatController(context, db.chatDao(), db.itemDao(), residency, search, settings, appScope, taskManager = taskManager, actions = actions) }
    val search by lazy { SearchEngine(db.itemDao(), { q -> residency.embedder()?.use { it.value.embedQuery(q) } }, settings) }
}

class LsaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        container.autoIndex.start()
        container.appScope.launch { runCatching { container.taskManager.rearmAll() } }
        container.appScope.launch { AppUpgrade.run(container.settings, container.db.itemDao(), BuildConfig.VERSION_CODE) }
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
