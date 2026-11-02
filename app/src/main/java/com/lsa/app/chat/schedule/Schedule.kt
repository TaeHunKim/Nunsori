package com.lsa.app.chat.schedule

import com.lsa.app.data.db.ScheduledTaskEntity
import com.lsa.app.data.db.TaskRepeat
import com.lsa.app.data.db.TaskTrigger
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** When a TIME task fires next. Pure date math, recomputed from local date and time so daylight saving changes cannot drift it. */
object Schedule {
    const val MIN_INTERVAL_MINUTES = 5
    const val MAX_TASKS = 20
    const val MAX_TASKS_PER_CHAT = 5

    /** A fire time missed because the device was off still runs once if it is at most this late. */
    const val CATCH_UP_MS = 12 * 60 * 60 * 1000L

    /** The first fire time strictly after [after], or null when the task never fires again. */
    fun nextRun(task: ScheduledTaskEntity, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (task.trigger != TaskTrigger.TIME) return null
        return when (task.repeat) {
            TaskRepeat.ONCE -> task.onceAtMs?.takeIf { it > after }
            TaskRepeat.EVERY_MINUTES -> {
                val step = task.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L
                after + step
            }
            TaskRepeat.DAILY -> atOrAfter(after, zone, task.atMinuteOfDay) { true }
            TaskRepeat.WEEKLY -> {
                val mask = task.weekdayMask.takeIf { it != 0 } ?: return null
                atOrAfter(after, zone, task.atMinuteOfDay) { day -> mask and (1 shl (day.dayOfWeek.value - 1)) != 0 }
            }
        }
    }

    private fun atOrAfter(after: Long, zone: ZoneId, minuteOfDay: Int, dayOk: (LocalDate) -> Boolean): Long? {
        val time = LocalTime.of(minuteOfDay.coerceIn(0, 1439) / 60, minuteOfDay.coerceIn(0, 1439) % 60)
        var day = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        repeat(8) {
            if (dayOk(day)) {
                val at = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
                if (at > after) return at
            }
            day = day.plusDays(1)
        }
        return null
    }

    /** "mon,wed" or "1,3" style input to a mask; null when nothing was recognised. */
    fun parseWeekdays(text: String): Int? {
        var mask = 0
        for (part in text.split(',', ' ', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }) {
            val day = DayOfWeek.entries.firstOrNull { d ->
                d.name.lowercase().startsWith(part.take(3)) && part.length >= 3
            }?.value ?: part.toIntOrNull()?.takeIf { it in 1..7 } ?: return null
            mask = mask or (1 shl (day - 1))
        }
        return mask.takeIf { it != 0 }
    }

    /** "09:30" or "9" to minutes after midnight. */
    fun parseTime(text: String): Int? {
        val m = Regex("""^\s*(\d{1,2})(?::(\d{2}))?\s*$""").matchEntire(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }
}
