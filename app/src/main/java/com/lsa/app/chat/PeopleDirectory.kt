package com.lsa.app.chat

import com.lsa.app.data.db.FaceDao
import com.lsa.app.data.db.PersonEntity

/** Named people from face grouping, as the chat tools see them: names and the photos each person is in. */
class PeopleDirectory(private val dao: FaceDao) {
    sealed interface Lookup {
        data class Found(val person: PersonEntity, val itemIds: List<Long>) : Lookup
        data class Missing(val error: String) : Lookup
    }

    suspend fun names(): List<String> = dao.namedPeople().mapNotNull { it.name }

    /** The person called [name], allowing particles and possessives around it ("Mina's", "민수가"). */
    suspend fun find(name: String): Lookup {
        val people = dao.namedPeople()
        val p = match(name, people.map { it.name!! })?.let { n -> people.first { it.name == n } }
            ?: return Lookup.Missing(
                if (people.isEmpty()) "Error: no people have names yet. The user can name groups of faces in the People screen."
                else "Error: no person called \"$name\". Known people: ${people.joinToString { it.name!! }}.",
            )
        return Lookup.Found(p, dao.itemIdsOf(p.personId))
    }

    companion object {
        private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

        /** Exact (case and spaces ignored), else the longest known name contained in [query] or containing it; null if none or ambiguous. */
        fun match(query: String, names: List<String>): String? {
            val q = norm(query)
            if (q.isEmpty()) return null
            names.firstOrNull { norm(it) == q }?.let { return it }
            val inQuery = names.filter { q.contains(norm(it)) }
            if (inQuery.isNotEmpty()) return inQuery.maxBy { it.length }.takeIf { best -> inQuery.count { it.length == best.length } == 1 }
            val containing = names.filter { norm(it).contains(q) }
            return containing.singleOrNull()
        }
    }
}
