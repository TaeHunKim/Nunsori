package com.lsa.app.chat.actions

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** A calendar event as the card shows it and the calendar app receives it. Times are epoch milliseconds. */
data class CalendarEventDraft(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String,
    val description: String,
) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("startMs", startMs).put("endMs", endMs).put("allDay", allDay)
        .put("location", location).put("description", description)

    companion object {
        fun fromJson(o: JSONObject) = CalendarEventDraft(
            o.optString("title"), o.optLong("startMs"), o.optLong("endMs"), o.optBoolean("allDay"), o.optString("location"), o.optString("description"),
        )
    }
}

/** A contact as the card shows it and the contacts app receives it. */
data class ContactDraft(val name: String, val phone: String, val email: String, val organization: String, val note: String) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("phone", phone).put("email", email)
        .put("organization", organization).put("note", note)

    companion object {
        fun fromJson(o: JSONObject) = ContactDraft(o.optString("name"), o.optString("phone"), o.optString("email"), o.optString("organization"), o.optString("note"))
    }
}

/** Turns the model's tool arguments into drafts, or an `Error: ...` text the model can act on. */
object ActionPayloads {
    const val MAX_FIELD = 500

    /** A parsed moment: [ms] and whether only a date (no time of day) was given. */
    data class Moment(val ms: Long, val dateOnly: Boolean)

    /** Accepts `YYYY-MM-DD`, `YYYY-MM-DD HH:mm` and `YYYY-MM-DDTHH:mm` (local time). */
    fun parseMoment(text: String, zone: ZoneId = ZoneId.systemDefault()): Moment? {
        val t = text.trim().replace('T', ' ')
        if (t.isEmpty()) return null
        return try {
            if (' ' in t) {
                val (d, hm) = t.split(' ', limit = 2)
                val time = hm.trim().take(5).let { if (it.length == 4) "0$it" else it }
                Moment(LocalDateTime.of(LocalDate.parse(d), java.time.LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli(), false)
            } else Moment(LocalDate.parse(t).atStartOfDay(zone).toInstant().toEpochMilli(), true)
        } catch (_: DateTimeParseException) { null }
    }

    sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>
        data class Error(val message: String) : Parsed<Nothing>
    }

    private const val BAD_TIME = "must be YYYY-MM-DD or YYYY-MM-DD HH:mm (local time)."

    fun calendarEvent(
        title: String, start: String, end: String = "", allDay: Boolean = false,
        location: String = "", description: String = "", zone: ZoneId = ZoneId.systemDefault(),
    ): Parsed<CalendarEventDraft> {
        val cleanTitle = title.trim().take(MAX_FIELD)
        if (cleanTitle.isEmpty()) return Parsed.Error("Error: title is required.")
        val from = parseMoment(start, zone) ?: return Parsed.Error("Error: start $BAD_TIME")
        val to = if (end.isBlank()) null else parseMoment(end, zone) ?: return Parsed.Error("Error: end $BAD_TIME")
        val place = location.trim().take(MAX_FIELD)
        val notes = description.trim().take(MAX_FIELD * 4)
        return if (allDay || (from.dateOnly && (to == null || to.dateOnly))) {
            // All-day events are stored as UTC midnights, end exclusive.
            val firstDay = startOfDayUtc(from.ms, zone)
            val lastDay = to?.let { startOfDayUtc(it.ms, zone) } ?: firstDay
            if (lastDay < firstDay) return Parsed.Error("Error: end is before start.")
            Parsed.Ok(CalendarEventDraft(cleanTitle, firstDay, lastDay + DAY_MS, true, place, notes))
        } else {
            val endMs = to?.ms ?: (from.ms + HOUR_MS)
            if (endMs <= from.ms) return Parsed.Error("Error: end is before start.")
            Parsed.Ok(CalendarEventDraft(cleanTitle, from.ms, endMs, false, place, notes))
        }
    }

    fun contact(name: String, phone: String = "", email: String = "", organization: String = "", note: String = ""): Parsed<ContactDraft> {
        val n = name.trim().take(MAX_FIELD)
        val ph = phone.trim().take(MAX_FIELD)
        val em = email.trim().take(MAX_FIELD)
        if (n.isEmpty() && ph.isEmpty() && em.isEmpty()) return Parsed.Error("Error: give at least a name, phone or email.")
        return Parsed.Ok(ContactDraft(n, ph, em, organization.trim().take(MAX_FIELD), note.trim().take(MAX_FIELD * 4)))
    }

    private fun startOfDayUtc(ms: Long, zone: ZoneId): Long =
        java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L
}
