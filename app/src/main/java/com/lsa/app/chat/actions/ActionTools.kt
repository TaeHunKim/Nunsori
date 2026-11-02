package com.lsa.app.chat.actions

import com.lsa.app.chat.ChatToolNames
import com.lsa.app.data.db.ActionKind
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
