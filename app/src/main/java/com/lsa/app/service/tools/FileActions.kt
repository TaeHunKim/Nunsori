package com.lsa.app.service.tools

import com.lsa.app.data.db.ItemDao
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** What the move/delete tools put on a card: the files by name, so the user sees what will be touched. */
private fun filesPayload(items: ItemDao, ids: List<Long>): Pair<org.json.JSONArray, List<Long>> {
    val arr = org.json.JSONArray(); val missing = ArrayList<Long>()
    for (id in ids) {
        val item = runBlocking { items.getItem(id) }
        if (item == null) missing += id
        else arr.put(JSONObject().put("id", id).put("name", item.displayName).put("folder", item.folderKey))
    }
    return arr to missing
}

private fun idsOf(args: JSONObject): List<Long> = FilePaths.parseIds(args.opt("item_ids")?.toString().orEmpty())

/**
 * The files a move/delete is about: `item_ids`, or every photo of `person` (a name from the People screen).
 * Returns the ids, or an error for the model.
 */
private fun targetsOf(args: JSONObject, people: PeopleDirectory?): Pair<List<Long>, String?> {
    val name = args.optString("person").trim()
    if (name.isEmpty()) {
        val ids = idsOf(args)
        return if (ids.isEmpty()) ids to "Error: give item_ids (numbers from search_index) or person." else ids to null
    }
    val found = runBlocking { people?.find(name) } ?: return emptyList<Long>() to "Error: people are not available."
    return when (found) {
        is PeopleDirectory.Lookup.Missing -> emptyList<Long>() to found.error
        is PeopleDirectory.Lookup.Found -> when {
            found.itemIds.isEmpty() -> emptyList<Long>() to "Error: no photos of ${found.person.name} are indexed."
            found.itemIds.size > FilePaths.MAX_FILES -> emptyList<Long>() to
                "Error: ${found.person.name} is in ${found.itemIds.size} photos, more than ${FilePaths.MAX_FILES} per card. Use search_index with person and a date range to split them, then pass item_ids."
            else -> found.itemIds to null
        }
    }
}

/** A move/delete checked and ready to show: the card payload, or an error for the model. */
sealed interface FilePrep {
    data class Ready(val payload: JSONObject) : FilePrep
    data class Invalid(val error: String) : FilePrep
}

/** Checks the move/delete arguments another app's model sent (service/IndexApi) and lists the files for its card. */
object FileActionPrep {
    fun move(args: JSONObject, items: ItemDao, people: PeopleDirectory?): FilePrep {
        val (ids, error) = targetsOf(args, people)
        if (error != null) return FilePrep.Invalid(error)
        if (args.optString("destination").isBlank()) {
            return FilePrep.Invalid("Error: destination is missing. Call move_files again with the folder the user named, e.g. " +
                (if (args.optString("person").isNotBlank()) """{"person":"${args.optString("person")}","destination":"Pictures/Family"}""" else """{"item_ids":"12,15","destination":"Pictures/Family"}""") + ".")
        }
        val dest = FilePaths.safeDestination(args.optString("destination"))
            ?: return FilePrep.Invalid("Error: destination must be a folder like Pictures/Wedding (not absolute, hidden or inside Android/).")
        val (files, missing) = filesPayload(items, ids)
        if (missing.isNotEmpty()) return FilePrep.Invalid("Error: unknown item_id ${missing.joinToString()}; use ids from search_index.")
        return FilePrep.Ready(JSONObject().put("destination", dest).put("files", files))
    }

    fun delete(args: JSONObject, items: ItemDao, people: PeopleDirectory?): FilePrep {
        val (ids, error) = targetsOf(args, people)
        if (error != null) return FilePrep.Invalid(error)
        val (files, missing) = filesPayload(items, ids)
        if (missing.isNotEmpty()) return FilePrep.Invalid("Error: unknown item_id ${missing.joinToString()}; use ids from search_index.")
        return FilePrep.Ready(JSONObject().put("permanent", args.optBoolean("permanent", false)).put("files", files))
    }
}

/** The move/delete tools as other apps' models see them: Nunsori prepares, the user confirms there, then Nunsori commits. */
object FileActionTools {
    const val MOVE_DESCRIPTION =
        "Propose moving indexed files (by item_id from search_index, or all photos of a person) to a folder. The user sees a card listing the files and approves it; " +
            "only then are they moved. Never claim they are moved. Up to ${FilePaths.MAX_FILES} files."
    const val MOVE_PARAMETERS = """{"type":"object","properties":{
        "item_ids":{"type":"string","description":"Comma separated item_id numbers from search_index."},
        "person":{"type":"string","description":"Instead of item_ids: every photo of this person (name from the People screen)."},
        "destination":{"type":"string","description":"Folder relative to the phone's storage, e.g. Pictures/Wedding (created if missing)."}},
        "required":["destination"]}"""
    const val DELETE_DESCRIPTION =
        "Propose deleting indexed files (by item_id from search_index, or all photos of a person). By default they go to the trash. The user sees a card listing the files " +
            "and approves it; only then anything happens. Never claim they are deleted. Up to ${FilePaths.MAX_FILES} files."
    const val DELETE_PARAMETERS = """{"type":"object","properties":{
        "item_ids":{"type":"string","description":"Comma separated item_id numbers from search_index."},
        "person":{"type":"string","description":"Instead of item_ids: every photo of this person (name from the People screen)."},
        "permanent":{"type":"boolean","description":"True only if the user explicitly asked to delete permanently instead of using the trash."}}}"""

    /** `3 done` or `2 done, 1 failed: a.jpg (reason)`. */
    fun summary(results: List<FileResult>): String {
        val ok = results.count { it.ok }
        val failed = results.filter { !it.ok }
        return buildString {
            append("$ok done")
            if (failed.isNotEmpty()) append(", ${failed.size} failed: ").append(failed.take(3).joinToString { "${it.name} (${it.reason})" })
        }
    }
}
