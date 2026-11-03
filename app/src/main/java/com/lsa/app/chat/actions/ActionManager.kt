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
class ActionManager(
    private val dao: PendingActionDao,
    private val fileOps: FileOps? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val hasFileAccess: Boolean get() = fileOps?.hasAccess == true


    /** Stores a proposal for [sessionId] and returns the tool result the model sees. */
    suspend fun propose(sessionId: Long, kind: ActionKind, payload: JSONObject): String {
        val id = dao.insert(PendingActionEntity(sessionId = sessionId, kind = kind, status = ActionStatus.PROPOSED, payload = payload.toString(), createdAt = clock()))
        return JSONObject().put("action_id", id).put("status", "waiting_for_user_approval")
            .put("note", "A card with this was shown to the user, who decides. Tell them to approve it in the chat; do not say it is done.").toString()
    }

    /**
     * Approves a card. Calendar and contact cards return the intent to launch (the caller then calls [markDone]); file cards are
     * carried out here and marked done or failed. [permanent] chooses permanent deletion on a delete card.
     */
    suspend fun approve(actionId: Long, permanent: Boolean = false): ApprovalResult {
        val a = dao.get(actionId) ?: return ApprovalResult.Failed("gone")
        if (a.status != ActionStatus.PROPOSED) return ApprovalResult.Failed("handled")
        val o = runCatching { JSONObject(a.payload) }.getOrNull() ?: return ApprovalResult.Failed("bad payload")
        return when (a.kind) {
            ActionKind.CALENDAR_EVENT -> ApprovalResult.Launch(calendarIntent(CalendarEventDraft.fromJson(o)))
            ActionKind.CONTACT -> ApprovalResult.Launch(contactIntent(ContactDraft.fromJson(o)))
            ActionKind.MOVE_FILES, ActionKind.DELETE_FILES -> runFiles(a, o, permanent)
        }
    }

    private suspend fun runFiles(a: PendingActionEntity, o: JSONObject, permanent: Boolean): ApprovalResult {
        val ops = fileOps ?: return ApprovalResult.Failed("unavailable")
        if (!ops.hasAccess) return ApprovalResult.Failed(FileOps.NEEDS_ACCESS) // stays PROPOSED: grant access, then approve again
        val arr = o.optJSONArray("files") ?: return ApprovalResult.Failed("bad payload")
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).optLong("id") }
        val results = when (a.kind) {
            ActionKind.MOVE_FILES -> ops.move(ids, o.optString("destination"))
            else -> if (permanent || o.optBoolean("permanent")) ops.delete(ids) else ops.trash(ids)
        }
        val note = summary(results)
        return if (results.any { it.ok }) {
            dao.setStatus(a.actionId, ActionStatus.DONE, note); ApprovalResult.Done(note)
        } else {
            dao.setStatus(a.actionId, ActionStatus.FAILED, note); ApprovalResult.Failed(note)
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
        /** `3 done` or `2 done, 1 failed: a.jpg (reason)`. */
        fun summary(results: List<FileResult>): String {
            val ok = results.count { it.ok }
            val failed = results.filter { !it.ok }
            return buildString {
                append("$ok done")
                if (failed.isNotEmpty()) append(", ${failed.size} failed: ").append(failed.take(3).joinToString { "${it.name} (${it.reason})" })
            }
        }

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
