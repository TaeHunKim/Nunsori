package com.lsa.app.chat.actions

import android.content.Intent
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.lsa.app.data.db.ActionKind
import com.lsa.app.data.db.ActionStatus
import com.lsa.app.data.db.PendingActionDao
import com.lsa.app.data.db.PendingActionEntity
import org.json.JSONObject

/** What approving a card does: hand an intent to a system screen, or (later kinds) do the work itself. */
sealed interface ApprovalResult {
    /** The card's button launches [intent]; the action is then marked done. */
    data class Launch(val intent: Intent) : ApprovalResult
    data class Done(val note: String? = null) : ApprovalResult
    data class Failed(val reason: String) : ApprovalResult
}

/**
 * Proposals the model makes (calendar event, contact, ...) wait here as cards in the chat. Nothing happens until the user
 * approves the card; scheduled runs can only propose.
 */
class ActionManager(private val dao: PendingActionDao, private val clock: () -> Long = System::currentTimeMillis) {

    /** Stores a proposal for [sessionId] and returns the tool result the model sees. */
    suspend fun propose(sessionId: Long, kind: ActionKind, payload: JSONObject): String {
        val id = dao.insert(PendingActionEntity(sessionId = sessionId, kind = kind, status = ActionStatus.PROPOSED, payload = payload.toString(), createdAt = clock()))
        return JSONObject().put("action_id", id).put("status", "waiting_for_user_approval")
            .put("note", "A card with this was shown to the user, who decides. Tell them to approve it in the chat; do not say it is done.").toString()
    }

    /** The intent for an intent-type card, or null when the card is gone, already handled or not an intent kind. */
    suspend fun intentFor(actionId: Long): ApprovalResult {
        val a = dao.get(actionId) ?: return ApprovalResult.Failed("gone")
        if (a.status != ActionStatus.PROPOSED) return ApprovalResult.Failed("handled")
        val o = runCatching { JSONObject(a.payload) }.getOrNull() ?: return ApprovalResult.Failed("bad payload")
        return when (a.kind) {
            ActionKind.CALENDAR_EVENT -> ApprovalResult.Launch(calendarIntent(CalendarEventDraft.fromJson(o)))
            ActionKind.CONTACT -> ApprovalResult.Launch(contactIntent(ContactDraft.fromJson(o)))
        }
    }

    suspend fun markDone(actionId: Long, note: String? = null) {
        if (dao.get(actionId)?.status == ActionStatus.PROPOSED) dao.setStatus(actionId, ActionStatus.DONE, note)
    }

    suspend fun markFailed(actionId: Long, note: String?) {
        if (dao.get(actionId)?.status == ActionStatus.PROPOSED) dao.setStatus(actionId, ActionStatus.FAILED, note)
    }

    suspend fun dismiss(actionId: Long) {
        if (dao.get(actionId)?.status == ActionStatus.PROPOSED) dao.setStatus(actionId, ActionStatus.DISMISSED, null)
    }

    companion object {
        fun calendarIntent(e: CalendarEventDraft): Intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, e.title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.endMs)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, e.allDay)
            if (e.location.isNotEmpty()) putExtra(CalendarContract.Events.EVENT_LOCATION, e.location)
            if (e.description.isNotEmpty()) putExtra(CalendarContract.Events.DESCRIPTION, e.description)
        }

        fun contactIntent(c: ContactDraft): Intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            if (c.name.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.NAME, c.name)
            if (c.phone.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.PHONE, c.phone)
            if (c.email.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.EMAIL, c.email)
            if (c.organization.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.COMPANY, c.organization)
            if (c.note.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.NOTES, c.note)
        }
    }
}
