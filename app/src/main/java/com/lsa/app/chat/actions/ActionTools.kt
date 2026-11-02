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

/** Proposes moving indexed files to another folder. The user approves the card; only then are files moved. */
class MoveFilesTool(private val actions: ActionManager, private val items: ItemDao, private val sessionId: Long) : ChatTool {
    override val name = ChatToolNames.MOVE
    override val description =
        "Propose moving indexed files (by item_id from search_index) to a folder. The user sees a card listing the files and approves it; " +
            "only then are they moved. Never claim they are moved. Up to ${FilePaths.MAX_FILES} files."
    override val parameters = """{"type":"object","properties":{
        "item_ids":{"type":"string","description":"Comma separated item_id numbers from search_index."},
        "destination":{"type":"string","description":"Folder relative to the phone's storage, e.g. Pictures/Wedding (created if missing)."}},
        "required":["item_ids","destination"]}"""

    override fun execute(args: JSONObject): String {
        val ids = idsOf(args)
        if (ids.isEmpty()) return "Error: item_ids is required (numbers from search_index)."
        val dest = FilePaths.safeDestination(args.optString("destination"))
            ?: return "Error: destination must be a folder like Pictures/Wedding (not absolute, hidden or inside Android/)."
        val (files, missing) = filesPayload(items, ids)
        if (missing.isNotEmpty()) return "Error: unknown item_id ${missing.joinToString()}; use ids from search_index."
        return runBlocking { actions.propose(sessionId, ActionKind.MOVE_FILES, JSONObject().put("destination", dest).put("files", files)) }
    }
}

/** Proposes deleting indexed files: to the trash by default, permanently only when [permanent] is set or chosen on the card. */
class DeleteFilesTool(private val actions: ActionManager, private val items: ItemDao, private val sessionId: Long) : ChatTool {
    override val name = ChatToolNames.DELETE
    override val description =
        "Propose deleting indexed files (by item_id from search_index). By default they go to the trash. The user sees a card listing the files " +
            "and approves it; only then anything happens. Never claim they are deleted. Up to ${FilePaths.MAX_FILES} files."
    override val parameters = """{"type":"object","properties":{
        "item_ids":{"type":"string","description":"Comma separated item_id numbers from search_index."},
        "permanent":{"type":"boolean","description":"True only if the user explicitly asked to delete permanently instead of using the trash."}},
        "required":["item_ids"]}"""

    override fun execute(args: JSONObject): String {
        val ids = idsOf(args)
        if (ids.isEmpty()) return "Error: item_ids is required (numbers from search_index)."
        val (files, missing) = filesPayload(items, ids)
        if (missing.isNotEmpty()) return "Error: unknown item_id ${missing.joinToString()}; use ids from search_index."
        return runBlocking { actions.propose(sessionId, ActionKind.DELETE_FILES, JSONObject().put("permanent", args.optBoolean("permanent", false)).put("files", files)) }
    }
}
