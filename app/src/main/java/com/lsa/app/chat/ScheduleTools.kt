package com.lsa.app.chat

import com.lsa.app.chat.schedule.TaskManager
import com.lsa.app.ml.llm.ChatTool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Proposes a scheduled task. Nothing happens until the user confirms the card the chat shows. */
class ScheduleTaskTool(private val tasks: TaskManager, private val sessionId: Long) : ChatTool {
    override val name = ChatToolNames.SCHEDULE
    override val description =
        "Schedule an instruction to be carried out later in this chat, once or repeatedly, either at a time (trigger=time) or " +
            "whenever a newly indexed file matches (trigger=file). The user must confirm it before it takes effect. " +
            "Write instruction so it makes sense on its own (the task runs without the user and can search and read files)."
    override val parameters = """{"type":"object","properties":{
        "trigger":{"type":"string","description":"time or file."},
        "instruction":{"type":"string","description":"What to do each time, e.g. 'Summarize the photos taken today'."},
        "repeat":{"type":"string","description":"time only: once, every_minutes, daily or weekly."},
        "at":{"type":"string","description":"time only: local time of day HH:mm (daily, weekly, once with date)."},
        "weekdays":{"type":"string","description":"weekly only: comma separated mon,tue,wed,thu,fri,sat,sun."},
        "every_minutes":{"type":"integer","description":"every_minutes only: interval, at least 5."},
        "date":{"type":"string","description":"once only: YYYY-MM-DD (default today, or tomorrow if the time has passed)."},
        "in_minutes":{"type":"integer","description":"once only: run this many minutes from now instead of date/at."},
        "query":{"type":"string","description":"file only: words or description a new file must match; leave out for any new file of the kinds."},
        "kinds":{"type":"string","description":"file only: comma separated document, image, audio, video; leave out for all."}},
        "required":["trigger","instruction"]}"""

    override fun execute(args: JSONObject): String = runBlocking { tasks.propose(sessionId, args) }
}

/** Changes a task of this chat (when, how often, the instruction, or what files it waits for). Applies at once. */
class UpdateScheduledTaskTool(private val tasks: TaskManager, private val sessionId: Long) : ChatTool {
    override val name = ChatToolNames.UPDATE_TASK
    override val description =
        "Change a scheduled task of this chat when the user asks (a different time, weekday or interval, a new instruction, " +
            "another search query or file kinds). Give task_id (see list_scheduled_tasks) and only the fields that change; " +
            "for a new schedule give repeat plus its fields as in schedule_task. It takes effect immediately."
    override val parameters = """{"type":"object","properties":{
        "task_id":{"type":"integer","description":"The task to change."},
        "instruction":{"type":"string","description":"New instruction."},
        "repeat":{"type":"string","description":"time tasks: once, every_minutes, daily or weekly."},
        "at":{"type":"string","description":"time of day HH:mm."},
        "weekdays":{"type":"string","description":"weekly: comma separated mon,tue,wed,thu,fri,sat,sun."},
        "every_minutes":{"type":"integer","description":"every_minutes: interval, at least 5."},
        "date":{"type":"string","description":"once: YYYY-MM-DD."},
        "in_minutes":{"type":"integer","description":"once: minutes from now."},
        "query":{"type":"string","description":"file tasks: new query (empty string = any file)."},
        "kinds":{"type":"string","description":"file tasks: comma separated document, image, audio, video."}},
        "required":["task_id"]}"""

    override fun execute(args: JSONObject): String = runBlocking { tasks.update(sessionId, args) }
}

class ListScheduledTasksTool(private val tasks: TaskManager, private val sessionId: Long) : ChatTool {
    override val name = ChatToolNames.LIST_TASKS
    override val description = "List the scheduled tasks of this chat with their task_id, schedule and last result."
    override val parameters = """{"type":"object","properties":{}}"""

    override fun execute(args: JSONObject): String = runBlocking { tasks.listText(sessionId) }
}

class CancelScheduledTaskTool(private val tasks: TaskManager, private val sessionId: Long) : ChatTool {
    override val name = ChatToolNames.CANCEL_TASK
    override val description = "Delete a scheduled task of this chat by task_id (see list_scheduled_tasks)."
    override val parameters = """{"type":"object","properties":{"task_id":{"type":"integer","description":"The task_id to delete."}},"required":["task_id"]}"""

    override fun execute(args: JSONObject): String = runBlocking { tasks.cancelText(sessionId, args.optLong("task_id", -1)) }
}
