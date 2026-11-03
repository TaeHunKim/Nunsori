package com.lsa.app.chat.actions

import com.lsa.app.chat.ChatToolNames
import com.lsa.app.data.db.ActionKind
import com.lsa.app.data.db.ItemDao
import com.lsa.app.ml.llm.ChatTool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Proposes a calendar event. The user approves the card, which opens the calendar app with the fields filled in. */
class AddCalendarEventTool(private val actions: ActionManager, private val sessionId: Long) : ChatTool {
    override val name = ChatToolNames.CALENDAR
    override val description =
        "Propose adding an event to the user's calendar (for example from an invitation, ticket or appointment found in a file). " +
            "The user sees a card and approves it; the calendar app then opens with these fields filled in. Never claim it is added."
    override val parameters = """{"type":"object","properties":{
        "title":{"type":"string","description":"Event title."},
        "start":{"type":"string","description":"Local start: YYYY-MM-DD HH:mm, or YYYY-MM-DD for an all-day event."},
        "end":{"type":"string","description":"Local end in the same format; optional (default one hour, or the same day when all-day)."},
        "all_day":{"type":"boolean","description":"True for an all-day event."},
        "location":{"type":"string","description":"Place, optional."},
        "description":{"type":"string","description":"Notes, optional."}},
        "required":["title","start"]}"""

    override fun execute(args: JSONObject): String = when (val p = ActionPayloads.calendarEvent(
        args.optString("title"), args.optString("start"), args.optString("end"), args.optBoolean("all_day", false),
        args.optString("location"), args.optString("description"),
    )) {
        is ActionPayloads.Parsed.Error -> p.message
        is ActionPayloads.Parsed.Ok -> runBlocking { actions.propose(sessionId, ActionKind.CALENDAR_EVENT, p.value.toJson()) }
    }
}

/** Proposes a new contact. The user approves the card, which opens the contacts app with the fields filled in. */
class AddContactTool(private val actions: ActionManager, private val sessionId: Long) : ChatTool {
    override val name = ChatToolNames.CONTACT
    override val description =
        "Propose saving a new contact (for example from a business card photo or a document). The user sees a card and approves it; " +
            "the contacts app then opens with these fields filled in. Never claim it is saved."
    override val parameters = """{"type":"object","properties":{
        "name":{"type":"string","description":"Full name."},
        "phone":{"type":"string","description":"Phone number, optional."},
        "email":{"type":"string","description":"Email address, optional."},
        "organization":{"type":"string","description":"Company, optional."},
        "note":{"type":"string","description":"Notes, optional."}}}"""

    override fun execute(args: JSONObject): String = when (val p = ActionPayloads.contact(
        args.optString("name"), args.optString("phone"), args.optString("email"), args.optString("organization"), args.optString("note"),
    )) {
        is ActionPayloads.Parsed.Error -> p.message
        is ActionPayloads.Parsed.Ok -> runBlocking { actions.propose(sessionId, ActionKind.CONTACT, p.value.toJson()) }
    }
}

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
private fun targetsOf(args: JSONObject, people: com.lsa.app.chat.PeopleDirectory?): Pair<List<Long>, String?> {
    val name = args.optString("person").trim()
    if (name.isEmpty()) {
        val ids = idsOf(args)
        return if (ids.isEmpty()) ids to "Error: give item_ids (numbers from search_index) or person." else ids to null
    }
    val found = runBlocking { people?.find(name) } ?: return emptyList<Long>() to "Error: people are not available."
    return when (found) {
        is com.lsa.app.chat.PeopleDirectory.Lookup.Missing -> emptyList<Long>() to found.error
        is com.lsa.app.chat.PeopleDirectory.Lookup.Found -> when {
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

/** Checks move/delete arguments the same way for the chat tools and for other apps (service/IndexApi). */
object FileActionPrep {
    fun move(args: JSONObject, items: ItemDao, people: com.lsa.app.chat.PeopleDirectory?): FilePrep {
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

    fun delete(args: JSONObject, items: ItemDao, people: com.lsa.app.chat.PeopleDirectory?): FilePrep {
        val (ids, error) = targetsOf(args, people)
        if (error != null) return FilePrep.Invalid(error)
        val (files, missing) = filesPayload(items, ids)
        if (missing.isNotEmpty()) return FilePrep.Invalid("Error: unknown item_id ${missing.joinToString()}; use ids from search_index.")
        return FilePrep.Ready(JSONObject().put("permanent", args.optBoolean("permanent", false)).put("files", files))
    }
}

/** Proposes moving indexed files to another folder. The user approves the card; only then are files moved. */
class MoveFilesTool(private val actions: ActionManager, private val items: ItemDao, private val sessionId: Long, private val people: com.lsa.app.chat.PeopleDirectory? = null) : ChatTool {
    override val name = ChatToolNames.MOVE
    override val description = MOVE_DESCRIPTION
    override val parameters = MOVE_PARAMETERS

    override fun execute(args: JSONObject): String = when (val p = FileActionPrep.move(args, items, people)) {
        is FilePrep.Invalid -> p.error
        is FilePrep.Ready -> runBlocking { actions.propose(sessionId, ActionKind.MOVE_FILES, p.payload) }
    }

    companion object {
        const val MOVE_DESCRIPTION =
            "Propose moving indexed files (by item_id from search_index, or all photos of a person) to a folder. The user sees a card listing the files and approves it; " +
                "only then are they moved. Never claim they are moved. Up to ${FilePaths.MAX_FILES} files."
        const val MOVE_PARAMETERS = """{"type":"object","properties":{
        "item_ids":{"type":"string","description":"Comma separated item_id numbers from search_index."},
        "person":{"type":"string","description":"Instead of item_ids: every photo of this person (name from the People screen)."},
        "destination":{"type":"string","description":"Folder relative to the phone's storage, e.g. Pictures/Wedding (created if missing)."}},
        "required":["destination"]}"""
    }
}

/** Proposes deleting indexed files: to the trash by default, permanently only when [permanent] is set or chosen on the card. */
class DeleteFilesTool(private val actions: ActionManager, private val items: ItemDao, private val sessionId: Long, private val people: com.lsa.app.chat.PeopleDirectory? = null) : ChatTool {
    override val name = ChatToolNames.DELETE
    override val description = DELETE_DESCRIPTION
    override val parameters = DELETE_PARAMETERS

    override fun execute(args: JSONObject): String = when (val p = FileActionPrep.delete(args, items, people)) {
        is FilePrep.Invalid -> p.error
        is FilePrep.Ready -> runBlocking { actions.propose(sessionId, ActionKind.DELETE_FILES, p.payload) }
    }

    companion object {
        const val DELETE_DESCRIPTION =
            "Propose deleting indexed files (by item_id from search_index, or all photos of a person). By default they go to the trash. The user sees a card listing the files " +
                "and approves it; only then anything happens. Never claim they are deleted. Up to ${FilePaths.MAX_FILES} files."
        const val DELETE_PARAMETERS = """{"type":"object","properties":{
        "item_ids":{"type":"string","description":"Comma separated item_id numbers from search_index."},
        "person":{"type":"string","description":"Instead of item_ids: every photo of this person (name from the People screen)."},
        "permanent":{"type":"boolean","description":"True only if the user explicitly asked to delete permanently instead of using the trash."}}}"""
    }
}
