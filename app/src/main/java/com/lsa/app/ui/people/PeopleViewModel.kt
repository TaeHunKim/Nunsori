package com.lsa.app.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsa.app.AppContainer
import com.lsa.app.data.db.FaceEntity
import com.lsa.app.data.db.ItemSummary
import com.lsa.app.data.db.PersonEntity
import com.lsa.app.data.db.PersonSummary
import com.lsa.app.indexing.faces.FaceAvailability
import com.lsa.app.indexing.faces.FaceProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** People list and one person's page: names, merging, "not this person", cover face. */
class PeopleViewModel(private val c: AppContainer) : ViewModel() {
    private val dao = c.db.faceDao()
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val availability: StateFlow<FaceAvailability?> = c.faces.availability.state(null)
    val progress: StateFlow<FaceProgress> = c.faces.progress
    val showSmall: StateFlow<Boolean> = c.settings.showSmallGroups.state(false)

    /** Groups to show: named ones always, unnamed ones with at least two photos unless [showSmall]. */
    val people: StateFlow<List<PersonSummary>?> = combine(dao.observePeople(), c.settings.showSmallGroups) { all, small ->
        all.filter { small || it.name != null || it.photoCount >= 2 }
    }.state(null)
    val hiddenCount: StateFlow<Int> = combine(dao.observePeople(), c.settings.showSmallGroups) { all, small ->
        if (small) 0 else all.count { it.name == null && it.photoCount < 2 }
    }.state(0)

    fun setShowSmall(on: Boolean) { viewModelScope.launch { c.settings.setShowSmallGroups(on) } }
    fun startIndexing() = c.indexing.start()

    fun thumbFile(rel: String?): File? = rel?.let { c.thumbs.file(it) }

    fun person(id: Long): Flow<PersonEntity?> = dao.observePerson(id)
    fun photos(id: Long): Flow<List<ItemSummary>> = dao.observePhotosOf(id)
    fun faces(id: Long): Flow<List<FaceEntity>> = dao.observeFacesOf(id)

    /** Another group already called [name] (case and spacing ignored), if any. */
    suspend fun sameName(id: Long, name: String): PersonEntity? = withContext(Dispatchers.IO) {
        val key = normalizeName(name)
        dao.namedPeople().firstOrNull { it.personId != id && normalizeName(it.name!!) == key }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch(Dispatchers.IO) { dao.rename(id, name.trim().ifEmpty { null }, System.currentTimeMillis()) }
    }

    /** Moves every face of [from] into [into]; [into] keeps its name, or takes [from]'s when it has none. */
    fun merge(from: Long, into: Long, done: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val a = dao.person(from); val b = dao.person(into)
                dao.merge(from, into, now)
                if (b?.name == null && a?.name != null) dao.rename(into, a.name, now)
            }
            done()
        }
    }

    fun setCover(id: Long, faceId: Long) { viewModelScope.launch(Dispatchers.IO) { dao.setCover(id, faceId, System.currentTimeMillis()) } }

    /** "Not this person": the face leaves the group (and never comes back to it) and is grouped again elsewhere. */
    fun reject(faceId: Long) {
        viewModelScope.launch(Dispatchers.IO) { dao.reject(faceId); c.faces.regroup() }
    }

    fun otherPeople(id: Long): Flow<List<PersonSummary>> = combine(dao.observePeople(), c.settings.showSmallGroups) { all, _ -> all.filter { it.personId != id } }

    companion object {
        fun normalizeName(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()
    }
}
